package com.nansu.tvnansu.ui.player

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Copies the frame on screen (480 px wide) from the player's video surface. */
object FrameGrabber {
    suspend fun grab(view: PlayerView, timeoutMs: Long = 600): Bitmap? = withTimeoutOrNull(timeoutMs) {
        val sv = view.videoSurfaceView as? SurfaceView ?: return@withTimeoutOrNull null
        if (sv.width <= 0 || sv.height <= 0) return@withTimeoutOrNull null
        val surface = sv.holder.surface
        if (surface == null || !surface.isValid) return@withTimeoutOrNull null
        val w = 480
        val h = (w.toLong() * sv.height / sv.width).toInt().coerceIn(1, 480)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        suspendCancellableCoroutine { cont ->
            try {
                PixelCopy.request(sv, bmp, { result ->
                    if (cont.isActive) cont.resume(if (result == PixelCopy.SUCCESS) bmp else null)
                }, Handler(Looper.getMainLooper()))
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }
}
