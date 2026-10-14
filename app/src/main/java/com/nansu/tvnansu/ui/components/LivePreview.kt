package com.nansu.tvnansu.ui.components

import android.content.Context
import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.nansu.tvnansu.data.remote.USER_AGENT
import kotlinx.coroutines.delay

/**
 * Keeps at most one preview stream open (providers allow one or two connections),
 * and lets the full player close it before it starts.
 */
object LivePreviewHub {
    private var current: ExoPlayer? = null
    private val released = java.util.Collections.newSetFromMap(java.util.WeakHashMap<ExoPlayer, Boolean>())

    private fun safeRelease(p: ExoPlayer) {
        if (released.add(p)) p.release()
    }

    fun claim(p: ExoPlayer) {
        current?.let { if (it !== p) safeRelease(it) }
        current = p
    }

    fun release(p: ExoPlayer) {
        safeRelease(p)
        if (current === p) current = null
    }

    fun stop() {
        current?.let { safeRelease(it) }
        current = null
    }
}

/**
 * A muted live picture of [url]: starts after a short pause (so moving past a slide
 * doesn't open streams), fades in on the first frame, and stops when hidden, when
 * the app goes to the background or when the full player opens. [placeholder] is
 * drawn underneath until the video shows (logo, guide image or last capture).
 */
@Composable
fun LivePreview(
    url: String?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    startDelayMs: Long = 1_200,
    placeholder: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> foreground = true
                Lifecycle.Event.ON_STOP -> foreground = false
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var showing by remember { mutableStateOf(false) }
    val active = enabled && foreground && !url.isNullOrBlank()

    LaunchedEffect(url, active) {
        showing = false
        player?.let { LivePreviewHub.release(it) }
        player = null
        if (!active || url == null) return@LaunchedEffect
        delay(startDelayMs)
        val p = buildPreviewPlayer(context)
        p.addListener(object : Player.Listener {
            private var triedHls = url.contains(".m3u8", ignoreCase = true)
            override fun onRenderedFirstFrame() {
                showing = true
            }

            override fun onPlayerError(error: PlaybackException) {
                showing = false
                if (!triedHls) {
                    // Many servers send HLS without the .m3u8 extension.
                    triedHls = true
                    p.setMediaItem(MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build())
                    p.prepare()
                }
            }
        })
        LivePreviewHub.claim(p)
        p.setMediaItem(
            MediaItem.Builder().setUri(url).apply {
                if (url.contains(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8)
            }.build()
        )
        p.prepare()
        player = p
    }
    DisposableEffect(Unit) {
        onDispose {
            player?.let { LivePreviewHub.release(it) }
            player = null
        }
    }

    val videoAlpha by animateFloatAsState(if (showing) 1f else 0f, tween(600), label = "preview")
    Box(modifier) {
        placeholder()
        val p = player
        if (p != null) {
            key(p) {
                AndroidView(
                    factory = { ctx -> TextureView(ctx).also { p.setVideoTextureView(it) } },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = videoAlpha },
                )
            }
        }
    }
}

private fun buildPreviewPlayer(context: Context): ExoPlayer {
    val http = DefaultHttpDataSource.Factory()
        .setUserAgent(USER_AGENT)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(10_000)
        .setReadTimeoutMs(15_000)
    val extractors = DefaultExtractorsFactory()
        .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(4_000, 10_000, 1_000, 2_000)
        .build()
    val p = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http), extractors))
        .setLoadControl(loadControl)
        .build()
    p.volume = 0f
    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        .setMaxVideoSize(1280, 720)
        .build()
    p.repeatMode = Player.REPEAT_MODE_OFF
    p.playWhenReady = true
    return p
}
