package com.dezacodex.ui.call

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import com.dezacodex.call.WebRtcCallEngine
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * Renders an independent WebRTC VideoTrack inside Jetpack Compose using SurfaceViewRenderer.
 * Automatically maintains proper aspect ratio (SCALE_ASPECT_FIT for screen share,
 * SCALE_ASPECT_BALANCED / FILL for camera feeds) and cleans up sinks on disposal.
 */
@Composable
fun WebRtcVideoSurface(
    trackId: String?,
    engine: WebRtcCallEngine,
    isScreenShare: Boolean,
    mirror: Boolean,
    modifier: Modifier = Modifier
) {
    val eglContext = remember(engine) { engine.getEglContext() }
    val track = remember(trackId, engine) {
        if (trackId != null) engine.getVideoTrack(trackId) else null
    }

    if (eglContext == null || track == null || trackId == null) {
        Box(modifier = modifier.background(Color(0xFF111827)))
        return
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(eglContext, null)
                setScalingType(
                    if (isScreenShare) RendererCommon.ScalingType.SCALE_ASPECT_FIT
                    else RendererCommon.ScalingType.SCALE_ASPECT_BALANCED
                )
                setMirror(mirror && !isScreenShare)
                setEnableHardwareScaler(true)
                tag = this
            }
        },
        update = { renderer ->
            renderer.setMirror(mirror && !isScreenShare)
            renderer.setScalingType(
                if (isScreenShare) RendererCommon.ScalingType.SCALE_ASPECT_FIT
                else RendererCommon.ScalingType.SCALE_ASPECT_BALANCED
            )
            engine.attachRenderer(trackId, renderer)
        },
        onRelease = { renderer ->
            engine.detachRenderer(trackId, renderer)
            runCatching { renderer.release() }
        }
    )

    DisposableEffect(trackId) {
        onDispose {
            // Track sink removal is also handled in onRelease
        }
    }
}

internal fun Context.findComponentActivity(): ComponentActivity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is ComponentActivity) return current
        current = current.baseContext
    }
    return null
}
