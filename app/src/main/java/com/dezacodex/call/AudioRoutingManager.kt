package com.dezacodex.call

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

enum class AudioOutputRoute(val label: String) {
    SPEAKER("Speaker"),
    EARPIECE("Earpiece"),
    BLUETOOTH("Bluetooth")
}

/**
 * Controls Android AudioManager communication mode & audio routing for calls.
 */
class AudioRoutingManager(context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var previousMode: Int = AudioManager.MODE_NORMAL
    private var previousSpeaker: Boolean = false

    fun configureCallAudio(route: AudioOutputRoute) {
        previousMode = audioManager.mode
        previousSpeaker = isSpeakerOn()
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        applyRoute(route)
    }

    fun applyRoute(route: AudioOutputRoute) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val targetType = when (route) {
                AudioOutputRoute.BLUETOOTH -> AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                AudioOutputRoute.SPEAKER -> AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                AudioOutputRoute.EARPIECE -> AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            }
            val devices = audioManager.availableCommunicationDevices
            val matching = devices.firstOrNull { it.type == targetType }
                ?: if (route == AudioOutputRoute.BLUETOOTH) {
                    devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                } else {
                    null
                }
            if (matching != null) {
                audioManager.setCommunicationDevice(matching)
                return
            }
        }
        @Suppress("DEPRECATION")
        when (route) {
            AudioOutputRoute.BLUETOOTH -> {
                audioManager.isSpeakerphoneOn = false
                if (audioManager.isBluetoothScoAvailableOffCall) {
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                }
            }
            AudioOutputRoute.SPEAKER -> {
                audioManager.isBluetoothScoOn = false
                audioManager.isSpeakerphoneOn = true
            }
            AudioOutputRoute.EARPIECE -> {
                audioManager.isBluetoothScoOn = false
                audioManager.isSpeakerphoneOn = false
            }
        }
    }

    private fun isSpeakerOn(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn
        }
    }

    fun restoreAudio() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = false
            @Suppress("DEPRECATION")
            audioManager.stopBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = previousSpeaker
        }
        audioManager.mode = previousMode
    }
}
