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
import kotlin.math.pow
import kotlin.math.sqrt

data class VadState(
    val isSpeaking: Boolean = false,
    val levelDb: Double = -90.0,
    val micHardwareAvailable: Boolean = true,
    val thresholdDb: Double = -42.0
)

data class VadEvaluationResult(
    val isSpeaking: Boolean,
    val rmsNormalized: Float,
    val levelDb: Float,
    val noiseFloorDb: Float
)

/**
 * Real PCM 16-bit RMS / dBFS Voice Activity Detector with adaptive noise floor,
 * configurable sensitivity, and hangover smoothing to eliminate false speaker switching.
 */
class VoiceActivityDetector(
    private val context: Context? = null,
    initialSensitivity: VadSensitivity = VadSensitivity.MEDIUM
) {
    constructor(sensitivity: VadSensitivity) : this(null, sensitivity)

    private val _state = MutableStateFlow(
        VadState(thresholdDb = initialSensitivity.thresholdDb.toDouble())
    )
    val state: StateFlow<VadState> = _state.asStateFlow()

    private var sensitivity: VadSensitivity = initialSensitivity
    private var thresholdDb: Double = initialSensitivity.thresholdDb.toDouble()
    private var marginOverNoiseDb: Double = initialSensitivity.marginOverNoiseDb.toDouble()
    private var noiseFloorDb: Double = -68.0
    private var hangoverMs: Long = 650L
    private var lastSpeechTimestampMs: Long = -10_000L
    private var monitorJob: Job? = null

    fun setSensitivity(newSensitivity: VadSensitivity) {
        sensitivity = newSensitivity
        thresholdDb = newSensitivity.thresholdDb.toDouble()
        marginOverNoiseDb = newSensitivity.marginOverNoiseDb.toDouble()
        _state.value = _state.value.copy(thresholdDb = thresholdDb)
    }

    fun setSensitivityThresholdDb(db: Double) {
        thresholdDb = db.coerceIn(-75.0, -15.0)
        _state.value = _state.value.copy(thresholdDb = thresholdDb)
    }

    /**
     * Evaluates raw 16-bit PCM audio frames and returns detailed VAD metrics.
     */
    fun processSamples(
        samples: ShortArray,
        readCount: Int = samples.size,
        nowMs: Long = System.currentTimeMillis()
    ): VadEvaluationResult {
        if (readCount <= 0 || samples.isEmpty()) {
            val withinHangover = (nowMs - lastSpeechTimestampMs) in 0..hangoverMs
            _state.value = _state.value.copy(isSpeaking = withinHangover, levelDb = -90.0)
            return VadEvaluationResult(
                isSpeaking = withinHangover,
                rmsNormalized = 0f,
                levelDb = -90f,
                noiseFloorDb = noiseFloorDb.toFloat()
            )
        }

        val limit = readCount.coerceAtMost(samples.size)
        var sumSq = 0.0
        for (i in 0 until limit) {
            val norm = samples[i].toDouble() / 32768.0
            sumSq += norm * norm
        }
        val rms = sqrt(sumSq / limit).coerceIn(0.0, 1.0)
        val db = if (rms > 1e-6) (20.0 * log10(rms)).coerceIn(-90.0, 0.0) else -90.0

        val effectiveGateDb = maxOf(thresholdDb, noiseFloorDb + marginOverNoiseDb)
        val instantSpeech = db >= effectiveGateDb
        if (instantSpeech) {
            lastSpeechTimestampMs = nowMs
        } else {
            // Adapt noise floor gradually during non-speech intervals
            noiseFloorDb = (noiseFloorDb * 0.92 + db * 0.08).coerceIn(-85.0, -35.0)
        }

        val speaking = instantSpeech || ((nowMs - lastSpeechTimestampMs) in 0..hangoverMs)
        _state.value = _state.value.copy(isSpeaking = speaking, levelDb = db)

        return VadEvaluationResult(
            isSpeaking = speaking,
            rmsNormalized = rms.toFloat(),
            levelDb = db.toFloat(),
            noiseFloorDb = noiseFloorDb.toFloat()
        )
    }

    /**
     * Evaluates normalized WebRTC audio level (0f..1f) for remote participant streams.
     */
    fun processNormalizedLevel(
        level: Float,
        nowMs: Long = System.currentTimeMillis()
    ): VadEvaluationResult {
        val clamped = level.coerceIn(0f, 1f)
        val db = if (clamped > 1e-5f) (20.0 * log10(clamped.toDouble())).coerceIn(-90.0, 0.0) else -90.0
        val effectiveGateDb = maxOf(thresholdDb, noiseFloorDb + marginOverNoiseDb)
        val instantSpeech = db >= effectiveGateDb
        if (instantSpeech) {
            lastSpeechTimestampMs = nowMs
        } else {
            noiseFloorDb = (noiseFloorDb * 0.92 + db * 0.08).coerceIn(-85.0, -35.0)
        }
        val speaking = instantSpeech || ((nowMs - lastSpeechTimestampMs) in 0..hangoverMs)
        _state.value = _state.value.copy(isSpeaking = speaking, levelDb = db)
        return VadEvaluationResult(
            isSpeaking = speaking,
            rmsNormalized = clamped,
            levelDb = db.toFloat(),
            noiseFloorDb = noiseFloorDb.toFloat()
        )
    }

    /**
     * Deterministic evaluator returning VadState (used by WebRtcCallEngine & unit tests).
     */
    fun evaluatePcmBuffer(
        samples: ShortArray,
        count: Int,
        nowMs: Long = System.currentTimeMillis()
    ): VadState {
        processSamples(samples, count, nowMs)
        return _state.value
    }

    fun startMonitoring(scope: CoroutineScope, isMutedProvider: () -> Boolean) {
        stopMonitoring()
        val ctx = context ?: run {
            _state.value = _state.value.copy(isSpeaking = false, levelDb = -90.0, micHardwareAvailable = false)
            return
        }
        val hasPerm = ContextCompat.checkSelfPermission(
            ctx,
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
                runCatching { recorder.release() }
                _state.value = _state.value.copy(micHardwareAvailable = false)
                return@launch
            }

            val buffer = ShortArray((minBuf / 2).coerceAtLeast(160))
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
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
            }
        }
    }

    fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
        _state.value = _state.value.copy(isSpeaking = false, levelDb = -90.0)
    }
}

/**
 * Captures local microphone PCM samples via AudioRecord and feeds them into VoiceActivityDetector.
 * Gracefully invokes [onUnavailable] when running on headless devices or when mic access is unavailable.
 */
class LocalAudioLevelMonitor(
    private val vad: VoiceActivityDetector
) {
    private var job: Job? = null

    fun updateSensitivity(sensitivity: VadSensitivity) {
        vad.setSensitivity(sensitivity)
    }

    fun start(
        scope: CoroutineScope,
        onLevel: (VadEvaluationResult) -> Unit,
        onUnavailable: () -> Unit = {}
    ) {
        stop()
        job = scope.launch(Dispatchers.IO) {
            val sampleRate = 16000
            val minBuf = try {
                AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
            } catch (_: Throwable) {
                -1
            }

            if (minBuf <= 0) {
                onUnavailable()
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
                onUnavailable()
                return@launch
            } catch (_: Throwable) {
                onUnavailable()
                return@launch
            }

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { recorder.release() }
                onUnavailable()
                return@launch
            }

            val buffer = ShortArray((minBuf / 2).coerceAtLeast(160))
            try {
                recorder.startRecording()
                while (isActive) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val result = vad.processSamples(buffer, read)
                        onLevel(result)
                    }
                    delay(80)
                }
            } catch (_: Throwable) {
                onUnavailable()
            } finally {
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
