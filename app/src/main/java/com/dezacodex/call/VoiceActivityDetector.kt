package com.dezacodex.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.sqrt

data class VadState(
    val isSpeaking: Boolean = false,
    val levelDb: Double = -90.0,
    val micHardwareAvailable: Boolean = true,
    val thresholdDb: Double = -42.0
)

/**
 * Pure RMS/dBFS + hysteresis Voice Activity Detector plus Android AudioRecord monitor.
 * Smooths rapid fluctuations via hangover delay to avoid jarring layout flicker.
 */
class VoiceActivityDetector(
    private val context: Context
) {
    private val _state = MutableStateFlow(VadState())
    val state: StateFlow<VadState> = _state.asStateFlow()

    private var thresholdDb: Double = -42.0
    private var hangoverMs: Long = 1100L
    private var lastSpeechTimestampMs: Long = 0L
    private var monitorJob: Job? = null

    fun setSensitivityThresholdDb(db: Double) {
        thresholdDb = db.coerceIn(-75.0, -15.0)
        _state.value = _state.value.copy(thresholdDb = thresholdDb)
    }

    /**
     * Pure deterministic evaluator for PCM 16-bit buffers, testable on JVM.
     */
    fun evaluatePcmBuffer(samples: ShortArray, count: Int, nowMs: Long = System.currentTimeMillis()): VadState {
        if (count <= 0) return _state.value.copy(isSpeaking = false, levelDb = -90.0)
        var sumSq = 0.0
        for (i in 0 until count) {
            val norm = samples[i].toDouble() / 32768.0
            sumSq += norm * norm
        }
        val rms = sqrt(sumSq / count)
        val db = if (rms > 1e-6) (20.0 * log10(rms)).coerceIn(-90.0, 0.0) else -90.0
        if (db >= thresholdDb) {
            lastSpeechTimestampMs = nowMs
        }
        val speaking = db >= thresholdDb || (nowMs - lastSpeechTimestampMs) <= hangoverMs
        val next = _state.value.copy(isSpeaking = speaking, levelDb = db)
        _state.value = next
        return next
    }

    fun startMonitoring(scope: CoroutineScope, isMutedProvider: () -> Boolean) {
        stopMonitoring()
        val hasPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) {
            _state.value = _state.value.copy(isSpeaking = false, levelDb = -90.0, micHardwareAvailable = false)
            return
        }

        monitorJob = scope.launch(Dispatchers.IO) {
            val sampleRate = 16000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) {
                _state.value = _state.value.copy(micHardwareAvailable = false)
                return@launch
            }
            val recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2
                )
            } catch (_: SecurityException) {
                _state.value = _state.value.copy(micHardwareAvailable = false)
                return@launch
            } catch (_: Exception) {
                _state.value = _state.value.copy(micHardwareAvailable = false)
                return@launch
            }

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                _state.value = _state.value.copy(micHardwareAvailable = false)
                return@launch
            }

            val buffer = ShortArray(minBuf / 2)
            try {
                recorder.startRecording()
                _state.value = _state.value.copy(micHardwareAvailable = true)
                while (isActive) {
                    if (isMutedProvider()) {
                        _state.value = _state.value.copy(isSpeaking = false, levelDb = -90.0)
                        delay(150)
                    } else {
                        val read = recorder.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            evaluatePcmBuffer(buffer, read)
                        }
                        delay(80)
                    }
                }
            } catch (_: Exception) {
                _state.value = _state.value.copy(isSpeaking = false, levelDb = -90.0)
            } finally {
                try {
                    recorder.stop()
                } catch (_: Exception) {
                }
                recorder.release()
            }
        }
    }

    fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
        _state.value = _state.value.copy(isSpeaking = false, levelDb = -90.0)
    }
}
