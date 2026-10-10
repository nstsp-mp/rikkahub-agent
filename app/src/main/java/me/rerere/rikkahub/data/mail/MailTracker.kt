package me.rerere.rikkahub.data.mail

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.db.dao.TrackedMailDao
import me.rerere.rikkahub.data.db.entity.TrackedMailEntity
import me.rerere.rikkahub.data.db.entity.TrackedMailStatus
import me.rerere.rikkahub.service.backgroundTextGenerationParams
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

data class MailTrackerStatus(
    val enabled: Boolean = false,
    val running: Boolean = false,
    val lastRunAt: Long = 0L,
    val lastSummary: String = "",
    val lastError: String? = null,
)

/**
 * Reply tracking: triages new inbox mail with the fast model, tracks messages that need the user's
 * reply (with an Outlook follow-up flag due in [REPLY_DUE_BUSINESS_DAYS] business days), resolves
 * them when the user replies, and posts a reminder once one is overdue.
 */
class MailTracker(
    private val context: Context,
    private val auth: MailAuthManager,
    private val client: GraphMailClient,
    private val dao: TrackedMailDao,
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scanMutex = Mutex()
    private val _status = MutableStateFlow(readStatus())
    val status: StateFlow<MailTrackerStatus> = _status.asStateFlow()

    val awaitingFlow = dao.observeAwaiting()

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _status.value = _status.value.copy(enabled = enabled)
        val workManager = WorkManager.getInstance(context)
        if (enabled) {
            val request = PeriodicWorkRequestBuilder<MailTrackerWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            workManager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
        } else {
            workManager.cancelUniqueWork(PERIODIC_WORK)
        }
    }

    /** Runs a scan now, outside the 15-minute schedule. */
    fun checkNow() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_SHOT_WORK,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<MailTrackerWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }

    suspend fun scan(now: Instant = Instant.now()) = scanMutex.withLock {
        _status.value = _status.value.copy(running = true)
        try {
            scanAccounts(now)
        } finally {
            _status.value = readStatus()
        }
    }

    private suspend fun scanAccounts(now: Instant) {
        var tracked = 0
        var resolved = 0
        var reminded = 0
        val errors = mutableListOf<String>()
        for (account in auth.accounts.value) {
            try {
                tracked += triageNewMail(account, now)
            } catch (e: Exception) {
                errors += "${account.email}: ${e.message}"
            }
            try {
                val (r, n) = resolveAndRemind(account, now)
                resolved += r
                reminded += n
            } catch (e: Exception) {
                errors += "${account.email}: ${e.message}"
            }
        }
        dao.deleteResolvedBefore(now.minus(Duration.ofDays(30)).toEpochMilli())
        val summary = "Tracked $tracked new, resolved $resolved, reminded $reminded"
        prefs.edit()
            .putLong(KEY_LAST_RUN, now.toEpochMilli())
            .putString(KEY_LAST_SUMMARY, summary)
            .putString(KEY_LAST_ERROR, errors.joinToString("\n").ifBlank { null })
            .apply()
    }

    suspend fun awaiting(): List<TrackedMailEntity> =
        auth.accounts.value.flatMap { dao.awaiting(it.id) }.sortedBy { it.dueAt }

    /** Stops tracking a message without a reply, and completes its Outlook flag. */
    suspend fun markHandled(messageId: String): TrackedMailEntity? {
        val entity = dao.get(messageId) ?: return null
        dao.resolve(messageId, TrackedMailStatus.DISMISSED, System.currentTimeMillis())
        auth.accounts.value.firstOrNull { it.id == entity.accountId }?.let { account ->
            runCatching { client.completeFlag(account, messageId) }
        }
        return entity
    }

    /** Starts tracking a message the triage skipped. */
    suspend fun track(account: MailAccount, messageId: String, reason: String): TrackedMailEntity {
        val message = client.findMessage(account, messageId) ?: error("Message not found")
        dao.get(messageId)?.takeIf { it.status == TrackedMailStatus.AWAITING }?.let { return it }
        val entity = trackedEntity(account, message, workDays(account), reason)
        dao.replace(entity) // also re-opens a previously resolved row with a fresh due date
        runCatching { client.setFlag(account, messageId, Instant.ofEpochMilli(entity.dueAt)) }
        return dao.get(messageId) ?: entity
    }

    private suspend fun triageNewMail(account: MailAccount, now: Instant): Int {
        val cursorKey = KEY_CURSOR_PREFIX + account.id
        // First run looks back one day rather than triaging the whole mailbox.
        val cursor = prefs.getLong(cursorKey, 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli)
            ?: now.minus(Duration.ofDays(1))
        val messages = client.listMessages(account, "inbox", top = 50, since = cursor)
            .filter { it.receivedAt != null && it.receivedAt.isAfter(cursor) }
            .sortedBy { it.receivedAt }
        if (messages.isEmpty()) return 0
        val workDays = workDays(account)
        var tracked = 0
        for (message in messages) {
            if (dao.get(message.id) == null && isTriageCandidate(message, account.email)) {
                val decision = triage(account, message)
                if (decision.needsReply) {
                    val entity = trackedEntity(account, message, workDays, decision.reason)
                    dao.insert(entity)
                    runCatching { client.setFlag(account, message.id, Instant.ofEpochMilli(entity.dueAt)) }
                    tracked++
                }
            }
            // Advance per message, so a failed triage is retried next run without redoing earlier ones.
            prefs.edit().putLong(cursorKey, message.receivedAt!!.toEpochMilli()).apply()
        }
        return tracked
    }

    private suspend fun resolveAndRemind(account: MailAccount, now: Instant): Pair<Int, Int> {
        var resolved = 0
        val stillAwaiting = mutableListOf<TrackedMailEntity>()
        for (entity in dao.awaiting(account.id)) {
            val received = Instant.ofEpochMilli(entity.receivedAt)
            when {
                client.repliedInConversation(account, entity.conversationId, received) -> {
                    dao.resolve(entity.messageId, TrackedMailStatus.REPLIED, now.toEpochMilli())
                    runCatching { client.completeFlag(account, entity.messageId) }
                    resolved++
                }
                // Gone from the mailbox, or the flag was marked complete in Outlook.
                client.findMessage(account, entity.messageId)?.flagStatus?.let { it == "complete" } != false -> {
                    dao.resolve(entity.messageId, TrackedMailStatus.DISMISSED, now.toEpochMilli())
                    resolved++
                }
                else -> stillAwaiting += entity
            }
        }
        val overdue = stillAwaiting.filter { it.dueAt <= now.toEpochMilli() && it.remindedAt == null }
        if (overdue.isNotEmpty() && postReminder(overdue)) {
            overdue.forEach { dao.markReminded(it.messageId, now.toEpochMilli()) }
        }
        return resolved to overdue.size
    }

    private suspend fun triage(account: MailAccount, message: MailMessage): TriageDecision {
        val settings = settingsStore.settingsFlow.first()
        val model = settings.findModelById(settings.fastModelId)
            ?: settings.findModelById(settings.chatModelId)
            ?: error("No fast or chat model is selected for email triage")
        val provider = model.findProvider(settings.providers) ?: error("The triage model's provider is missing")
        check(provider.enabled) { "The triage model's provider is disabled" }
        val result = providerManager.getProviderByType(provider).generateText(
            providerSetting = provider,
            messages = listOf(UIMessage.user(prompt = triagePrompt(message, account.displayName, account.email))),
            params = backgroundTextGenerationParams(model),
        )
        return parseTriageDecision(result.message.toText())
    }

    private suspend fun workDays(account: MailAccount) =
        runCatching { client.mailboxSettings(account).workDays }.getOrDefault(GraphMailClient.WEEKDAYS)

    private fun trackedEntity(
        account: MailAccount,
        message: MailMessage,
        workDays: Set<java.time.DayOfWeek>,
        reason: String,
    ): TrackedMailEntity {
        val received = message.receivedAt ?: Instant.now()
        return TrackedMailEntity(
            messageId = message.id,
            accountId = account.id,
            conversationId = message.conversationId,
            subject = message.subject,
            fromName = message.fromName,
            fromAddress = message.fromAddress,
            receivedAt = received.toEpochMilli(),
            dueAt = addBusinessDays(received, REPLY_DUE_BUSINESS_DAYS, workDays, ZoneId.systemDefault()).toEpochMilli(),
            reason = reason,
        )
    }

    private fun postReminder(overdue: List<TrackedMailEntity>): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
        ) return false
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManager.IMPORTANCE_DEFAULT)
                .setName("Email reply reminders")
                .build()
        )
        val title = if (overdue.size == 1) "Reply overdue: ${overdue[0].fromName.ifBlank { overdue[0].fromAddress }}"
        else "${overdue.size} emails are waiting for your reply"
        val lines = overdue.joinToString("\n") { "${it.fromName.ifBlank { it.fromAddress }} — ${it.subject}" }
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(lines.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines))
            .setContentIntent(
                PendingIntent.getActivity(context, NOTIFICATION_ID, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
            .setAutoCancel(true)
            .build()
        return try {
            manager.notify(NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun readStatus() = MailTrackerStatus(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        running = false,
        lastRunAt = prefs.getLong(KEY_LAST_RUN, 0L),
        lastSummary = prefs.getString(KEY_LAST_SUMMARY, "").orEmpty(),
        lastError = prefs.getString(KEY_LAST_ERROR, null),
    )

    private companion object {
        const val PREFS_NAME = "mail_tracker"
        const val KEY_ENABLED = "enabled"
        const val KEY_LAST_RUN = "last_run"
        const val KEY_LAST_SUMMARY = "last_summary"
        const val KEY_LAST_ERROR = "last_error"
        const val KEY_CURSOR_PREFIX = "cursor_"
        const val PERIODIC_WORK = "mail_tracker_periodic"
        const val ONE_SHOT_WORK = "mail_tracker_now"
        const val CHANNEL_ID = "rikkahub_mail_tracker"
        const val NOTIFICATION_ID = 3_100_001
    }
}

class MailTrackerWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {
    private val tracker: MailTracker by inject()

    override suspend fun doWork(): Result {
        tracker.scan()
        return Result.success()
    }
}
