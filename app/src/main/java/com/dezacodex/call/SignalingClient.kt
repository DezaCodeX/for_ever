package com.dezacodex.call

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed class SignalingEvent {
    data class PeerJoined(val peerId: String, val displayName: String) : SignalingEvent()
    data class PeerLeft(val peerId: String) : SignalingEvent()
    data class OfferReceived(val fromPeerId: String, val sdp: String) : SignalingEvent()
    data class AnswerReceived(val fromPeerId: String, val sdp: String) : SignalingEvent()
    data class IceCandidateReceived(
        val fromPeerId: String,
        val sdpMid: String,
        val sdpMLineIndex: Int,
        val candidate: String
    ) : SignalingEvent()
    data class MediaStateUpdated(
        val fromPeerId: String,
        val micMuted: Boolean,
        val camEnabled: Boolean,
        val screenSharing: Boolean,
        val webcamStreamId: String?,
        val screenStreamId: String?,
        val audioLevel: Float
    ) : SignalingEvent()
    data class Error(val message: String) : SignalingEvent()
}

/**
 * WebRTC WebSocket Signaling Client using OkHttp WebSockets.
 * Supports room join/leave, SDP offer/answer, ICE candidates, media track metadata sync,
 * and exponential-backoff reconnection. Never logs sensitive tokens or credentials.
 */
class WebSocketSignalingClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
) {
    private val TAG = "SignalingClient"
    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var currentUrl: String = ""
    private var currentRoomId: String = ""
    private var currentPeerId: String = ""
    private var currentDisplayName: String = ""
    private var manuallyClosed = false
    private var retryAttempt = 0

    private val _connectionState = MutableStateFlow(SignalingConnectionState.DISCONNECTED)
    val connectionState: StateFlow<SignalingConnectionState> = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<SignalingEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SignalingEvent> = _events.asSharedFlow()

    fun connect(
        serverUrl: String,
        roomId: String,
        peerId: String,
        displayName: String,
        scope: CoroutineScope
    ) {
        val trimmedUrl = serverUrl.trim()
        if (trimmedUrl.isEmpty() || (!trimmedUrl.startsWith("ws://") && !trimmedUrl.startsWith("wss://"))) {
            _connectionState.value = SignalingConnectionState.DISCONNECTED
            return
        }

        manuallyClosed = false
        currentUrl = trimmedUrl
        currentRoomId = roomId
        currentPeerId = peerId
        currentDisplayName = displayName
        retryAttempt = 0

        openSocket(scope)
    }

    private fun openSocket(scope: CoroutineScope) {
        if (manuallyClosed) return
        webSocket?.close(1000, "Replacing socket")
        _connectionState.value = if (retryAttempt == 0) {
            SignalingConnectionState.CONNECTING
        } else {
            SignalingConnectionState.RECONNECTING
        }

        val request = try {
            Request.Builder().url(currentUrl).build()
        } catch (e: Exception) {
            _connectionState.value = SignalingConnectionState.ERROR
            _events.tryEmit(SignalingEvent.Error("Invalid signaling URL format."))
            return
        }

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                retryAttempt = 0
                _connectionState.value = SignalingConnectionState.CONNECTED
                sendJson(
                    JSONObject().apply {
                        put("type", "join")
                        put("roomId", currentRoomId)
                        put("peerId", currentPeerId)
                        put("displayName", currentDisplayName)
                    }
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parseMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Signaling socket failure: ${t.javaClass.simpleName}")
                if (!manuallyClosed) {
                    scheduleReconnect(scope)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!manuallyClosed) {
                    scheduleReconnect(scope)
                } else {
                    _connectionState.value = SignalingConnectionState.DISCONNECTED
                }
            }
        })
    }

    private fun scheduleReconnect(scope: CoroutineScope) {
        if (manuallyClosed || retryAttempt >= 5) {
            _connectionState.value = SignalingConnectionState.ERROR
            return
        }
        reconnectJob?.cancel()
        reconnectJob = scope.launch(Dispatchers.IO) {
            _connectionState.value = SignalingConnectionState.RECONNECTING
            val delayMs = (1000L shl retryAttempt).coerceAtMost(16_000L)
            retryAttempt++
            delay(delayMs)
            if (!manuallyClosed) {
                openSocket(scope)
            }
        }
    }

    internal fun parseMessage(raw: String) {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val type = json.optString("type")
        val fromPeer = json.optString("peerId", json.optString("fromPeerId", ""))
        if (fromPeer == currentPeerId && type != "error") return

        when (type) {
            "peer-joined" -> {
                _events.tryEmit(
                    SignalingEvent.PeerJoined(
                        peerId = fromPeer,
                        displayName = json.optString("displayName", "Partner")
                    )
                )
            }
            "peer-left" -> {
                _events.tryEmit(SignalingEvent.PeerLeft(fromPeer))
            }
            "offer" -> {
                val sdp = json.optString("sdp")
                if (sdp.isNotEmpty()) {
                    _events.tryEmit(SignalingEvent.OfferReceived(fromPeer, sdp))
                }
            }
            "answer" -> {
                val sdp = json.optString("sdp")
                if (sdp.isNotEmpty()) {
                    _events.tryEmit(SignalingEvent.AnswerReceived(fromPeer, sdp))
                }
            }
            "ice-candidate" -> {
                val candidate = json.optString("candidate")
                if (candidate.isNotEmpty()) {
                    _events.tryEmit(
                        SignalingEvent.IceCandidateReceived(
                            fromPeerId = fromPeer,
                            sdpMid = json.optString("sdpMid", "0"),
                            sdpMLineIndex = json.optInt("sdpMLineIndex", 0),
                            candidate = candidate
                        )
                    )
                }
            }
            "media-state" -> {
                _events.tryEmit(
                    SignalingEvent.MediaStateUpdated(
                        fromPeerId = fromPeer,
                        micMuted = json.optBoolean("micMuted", false),
                        camEnabled = json.optBoolean("camEnabled", true),
                        screenSharing = json.optBoolean("screenSharing", false),
                        webcamStreamId = json.optString("webcamStreamId").takeIf { it.isNotEmpty() },
                        screenStreamId = json.optString("screenStreamId").takeIf { it.isNotEmpty() },
                        audioLevel = json.optDouble("audioLevel", 0.0).toFloat()
                    )
                )
            }
            "error" -> {
                _events.tryEmit(SignalingEvent.Error(json.optString("message", "Signaling error")))
            }
        }
    }

    fun sendOffer(targetPeerId: String, sdp: String) {
        sendJson(
            JSONObject().apply {
                put("type", "offer")
                put("roomId", currentRoomId)
                put("peerId", currentPeerId)
                put("targetPeerId", targetPeerId)
                put("sdp", sdp)
            }
        )
    }

    fun sendAnswer(targetPeerId: String, sdp: String) {
        sendJson(
            JSONObject().apply {
                put("type", "answer")
                put("roomId", currentRoomId)
                put("peerId", currentPeerId)
                put("targetPeerId", targetPeerId)
                put("sdp", sdp)
            }
        )
    }

    fun sendIceCandidate(targetPeerId: String, sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        sendJson(
            JSONObject().apply {
                put("type", "ice-candidate")
                put("roomId", currentRoomId)
                put("peerId", currentPeerId)
                put("targetPeerId", targetPeerId)
                put("sdpMid", sdpMid)
                put("sdpMLineIndex", sdpMLineIndex)
                put("candidate", candidate)
            }
        )
    }

    fun sendMediaState(
        micMuted: Boolean,
        camEnabled: Boolean,
        screenSharing: Boolean,
        webcamStreamId: String?,
        screenStreamId: String?,
        audioLevel: Float
    ) {
        sendJson(
            JSONObject().apply {
                put("type", "media-state")
                put("roomId", currentRoomId)
                put("peerId", currentPeerId)
                put("micMuted", micMuted)
                put("camEnabled", camEnabled)
                put("screenSharing", screenSharing)
                put("webcamStreamId", webcamStreamId ?: "")
                put("screenStreamId", screenStreamId ?: "")
                put("audioLevel", audioLevel.toDouble())
            }
        )
    }

    private fun sendJson(payload: JSONObject) {
        webSocket?.send(payload.toString())
    }

    fun disconnect() {
        manuallyClosed = true
        reconnectJob?.cancel()
        reconnectJob = null
        runCatching {
            if (currentRoomId.isNotEmpty() && currentPeerId.isNotEmpty()) {
                sendJson(
                    JSONObject().apply {
                        put("type", "leave")
                        put("roomId", currentRoomId)
                        put("peerId", currentPeerId)
                    }
                )
            }
        }
        webSocket?.close(1000, "Call ended")
        webSocket = null
        _connectionState.value = SignalingConnectionState.DISCONNECTED
    }
}
