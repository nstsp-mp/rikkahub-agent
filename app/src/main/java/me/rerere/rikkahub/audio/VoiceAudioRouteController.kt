package me.rerere.rikkahub.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

class VoiceAudioRouteController(context: Context) {
    private val audioManager =
        context.applicationContext.getSystemService(AudioManager::class.java)

    private var acquired = false
    private var previousMode = AudioManager.MODE_NORMAL

    fun acquireBluetoothCommunicationRoute(): Result<AudioDeviceInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return Result.failure(
                UnsupportedOperationException(
                    "Explicit Bluetooth voice routing requires Android 12 or later"
                )
            )
        }

        if (acquired) {
            return audioManager.communicationDevice
                ?.let(Result<AudioDeviceInfo>::success)
                ?: Result.failure(
                    IllegalStateException(
                        "Voice route is active but no communication device is selected"
                    )
                )
        }

        val bluetoothDevice =
            audioManager.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            } ?: return Result.failure(
                IllegalStateException(
                    "No Bluetooth communication device is available. " +
                        "Connect the Shokz headset and enable phone-call audio."
                )
            )

        previousMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        if (!audioManager.setCommunicationDevice(bluetoothDevice)) {
            audioManager.mode = previousMode

            return Result.failure(
                IllegalStateException(
                    "Android rejected the Bluetooth communication route"
                )
            )
        }

        acquired = true
        return Result.success(bluetoothDevice)
    }

    fun release() {
        if (!acquired) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        }

        audioManager.mode = previousMode
        acquired = false
    }
}
