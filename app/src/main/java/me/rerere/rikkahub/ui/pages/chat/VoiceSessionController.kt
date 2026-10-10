package me.rerere.rikkahub.ui.pages.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.asr.ASRController
import me.rerere.asr.ASRStatus
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.MessageQueuePausedException

enum class VoicePhase { Off, Connecting, Listening, Transcribing, Speaking, Error }

data class VoiceSessionState(
    val phase: VoicePhase = VoicePhase.Off,
    val transcript: String = "",
    val error: String? = null,
    val pendingReplies: Int = 0,
) {
    val isActive: Boolean get() = phase != VoicePhase.Off && phase != VoicePhase.Error
}

enum class VoiceCommand { CancelReplies, EndSession }

private val CANCEL_PHRASES = setOf("stop", "cancel", "never mind", "nevermind", "skip", "skip it", "stop talking", "be quiet")
private val END_PHRASES = setOf("end voice mode", "stop voice mode", "exit voice mode", "end voice", "goodbye", "good bye")

/** Matches whole utterances only, so "stop the timer" still goes to the model. */
fun parseVoiceCommand(text: String): VoiceCommand? {
    val phrase = text.lowercase()
        .replace(Regex("[^a-z' ]"), " ")
        .split(' ')
        .filter { it.isNotBlank() }
        .dropWhile { it == "ok" || it == "okay" || it == "hey" }
        .dropLastWhile { it == "please" }
        .joinToString(" ")
    return when (phrase) {
        in CANCEL_PHRASES -> VoiceCommand.CancelReplies
        in END_PHRASES -> VoiceCommand.EndSession
        else -> null
    }
}

/** Capture, generation and playback run independently; speech heard during playback only counts as a command. */
class VoiceSessionController(
    private val scope: CoroutineScope,
    private val getString: (Int) -> String,
    // Waits before each consecutive reconnect after a dropped recognition connection; one more failure ends the session.
    private val reconnectDelaysMs: List<Long> = listOf(500, 2_000, 5_000),
    // A recognition connection with no speech is recycled after this long instead of timing out the session.
    private val idleReconnectMs: Long = 120_000,
    private val enqueueMessage: (String) -> Deferred<String?>,
) {
    private val mutableState = MutableStateFlow(VoiceSessionState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null

    private sealed interface Event {
        data class Utterance(val text: String, val duringPlayback: Boolean) : Event
        data class Reply(val text: String?) : Event
        data class Failed(val error: Exception) : Event
        data class CaptureFailed(val error: Exception) : Event
        data object PlaybackDone : Event
    }

    fun start(
        createAsr: () -> ASRController,
        speak: (suspend (String) -> Unit)?,
        stopSpeaking: () -> Unit,
    ) {
        if (job?.isCompleted == false) return
        mutableState.value = VoiceSessionState(VoicePhase.Connecting)
        job = scope.launch {
            try {
                stopSpeaking()
                delay(200)
                runSession(createAsr, speak, stopSpeaking)
            } catch (e: Exception) {
                if (e is CancellationException && !currentCoroutineContext().isActive) throw e
                mutableState.update {
                    it.copy(
                        phase = VoicePhase.Error,
                        error = when (e) {
                            is TimeoutCancellationException -> "Speech recognition timed out. Restart voice mode."
                            is MessageQueuePausedException -> getString(R.string.chat_page_voice_queue_paused)
                            else -> e.message ?: getString(R.string.chat_page_voice_failed)
                        },
                    )
                }
            } finally {
                stopSpeaking()
            }
        }
    }

    private suspend fun runSession(
        createAsr: () -> ASRController,
        speak: (suspend (String) -> Unit)?,
        stopSpeaking: () -> Unit,
    ) = coroutineScope {
        val events = Channel<Event>(Channel.UNLIMITED)
        val submitted = Channel<Deferred<String?>>(Channel.UNLIMITED)
        val replies = ArrayDeque<String>()
        var asr: ASRController? = null
        var capture: Job? = null
        var captureFailures = 0
        var playback: Job? = null
        var playing = false
        // Replies arrive in submission order; those up to mutedThrough were cancelled by voice and are not spoken.
        var submittedCount = 0
        var repliedCount = 0
        var mutedThrough = 0

        // Await replies in submission order, without blocking capture. Queue removal returns null.
        launch {
            try {
                for (reply in submitted) events.send(Event.Reply(reply.await()))
            } catch (e: Exception) {
                if (e is CancellationException && !isActive) throw e
                events.send(Event.Failed(e))
            }
        }

        try {
            while (isActive) {
                // Finish any sentence already in progress before starting playback. The recognition
                // connection stays open while a reply plays, so "stop" is heard without a reconnect.
                if (speak != null && playback == null && replies.isNotEmpty() &&
                    asr?.state?.value?.voiceTurn?.itemId == null
                ) {
                    val text = replies.removeFirst()
                    playing = true
                    mutableState.update { it.copy(phase = VoicePhase.Speaking) }
                    playback = launch {
                        try {
                            speak(text)
                            delay(300) // Speech starting in the loudspeaker's tail is treated as echo too.
                            events.send(Event.PlaybackDone)
                        } catch (e: Exception) {
                            if (e is CancellationException && !isActive) throw e
                            events.send(Event.Failed(e))
                        }
                    }
                    continue
                }
                if (capture == null) {
                    if (!playing) mutableState.update { it.copy(phase = VoicePhase.Connecting, transcript = "") }
                    val recorder = createAsr()
                    asr = recorder
                    capture = launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            events.send(listen(recorder) { playing })
                        } catch (e: Exception) {
                            if (e is CancellationException && !isActive) throw e
                            events.send(Event.CaptureFailed(e))
                        }
                    }
                }
                when (val event = events.receive()) {
                    is Event.Utterance -> {
                        capture?.join()
                        capture = null
                        asr = null
                        captureFailures = 0
                        val command = parseVoiceCommand(event.text)
                        when {
                            command == VoiceCommand.EndSession -> {
                                stop()
                                break
                            }
                            command == VoiceCommand.CancelReplies -> {
                                playback?.let {
                                    it.cancel()
                                    stopSpeaking()
                                }
                                playback = null
                                playing = false
                                replies.clear()
                                mutedThrough = submittedCount
                                mutableState.update { it.copy(phase = VoicePhase.Connecting, transcript = event.text) }
                            }
                            // Speech heard over a reply may be the reply itself, so only commands count.
                            event.duringPlayback -> {}
                            event.text.isNotBlank() -> {
                                val reply = enqueueMessage(event.text)
                                submittedCount++
                                mutableState.update { it.copy(transcript = event.text, pendingReplies = it.pendingReplies + 1) }
                                submitted.send(reply)
                            }
                        }
                    }
                    is Event.Reply -> {
                        repliedCount++
                        mutableState.update { it.copy(pendingReplies = (it.pendingReplies - 1).coerceAtLeast(0)) }
                        event.text?.takeIf { speak != null && it.isNotBlank() && repliedCount > mutedThrough }
                            ?.let { replies.addLast(it) }
                    }
                    is Event.PlaybackDone -> {
                        playback = null
                        playing = false
                        val listening = asr?.state?.value?.status == ASRStatus.Listening
                        mutableState.update { it.copy(phase = if (listening) VoicePhase.Listening else VoicePhase.Connecting) }
                    }
                    is Event.CaptureFailed -> {
                        capture?.join()
                        capture = null
                        asr = null
                        // Dropped sockets, network changes and expired sessions get a quiet reconnect.
                        if (captureFailures >= reconnectDelaysMs.size) throw event.error
                        if (!playing) mutableState.update { it.copy(phase = VoicePhase.Connecting) }
                        delay(reconnectDelaysMs[captureFailures++])
                    }
                    is Event.Failed -> throw event.error
                }
            }
        } finally {
            capture?.cancel()
            // Submitted messages belong to the chat queue; leaving voice mode only detaches observers.
            submitted.close()
        }
    }

    /** Returns the next utterance, noting whether it began while a reply was playing. */
    private suspend fun listen(asr: ASRController, playing: () -> Boolean): Event.Utterance {
        var startedDuringPlayback = false
        try {
            asr.start {}
            withTimeout(15_000) {
                asr.state.first {
                    check(it.errorMessage == null) { it.errorMessage.orEmpty() }
                    it.status != ASRStatus.Connecting
                }.also { check(it.status == ASRStatus.Listening || it.voiceTurn.isComplete) { "Unable to start speech recognition" } }
            }
            val ended = withTimeoutOrNull(idleReconnectMs) {
                asr.state.onEach {
                    check(it.errorMessage == null) { it.errorMessage.orEmpty() }
                    check(it.status == ASRStatus.Listening || it.voiceTurn.isComplete) { "Speech recognition disconnected" }
                    if (it.voiceTurn.itemId == null) startedDuringPlayback = false
                    else if (playing()) startedDuringPlayback = true
                    if (!playing()) {
                        mutableState.update { current -> current.copy(phase = VoicePhase.Listening, transcript = it.transcript) }
                    }
                }.first { it.voiceTurn.speechEnded }
            }
            if (ended == null) {
                check(asr.state.value.voiceTurn.itemId == null) { "Speech recognition timed out" }
                return Event.Utterance("", false) // Nothing was said; the caller opens a fresh connection.
            }
            asr.pauseCapture()
            if (!playing()) {
                mutableState.update { it.copy(phase = VoicePhase.Transcribing, transcript = ended.transcript) }
            }
            val text = withTimeout(15_000) {
                asr.state.first {
                    check(it.errorMessage == null) { it.errorMessage.orEmpty() }
                    check(it.voiceTurn.isComplete || it.status == ASRStatus.Listening) {
                        "Speech recognition disconnected before the final transcript was received"
                    }
                    it.voiceTurn.isComplete
                }.voiceTurn.finalText.orEmpty()
            }
            return Event.Utterance(text, startedDuringPlayback)
        } finally {
            asr.dispose()
        }
    }

    fun stop() {
        job?.cancel()
        mutableState.value = VoiceSessionState()
    }
}
