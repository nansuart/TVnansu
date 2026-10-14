package com.nansu.tvnansu.ui.components

import android.content.Context
import android.os.SystemClock
import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
import com.nansu.tvnansu.data.db.ChannelEntity
import com.nansu.tvnansu.data.remote.USER_AGENT
import com.nansu.tvnansu.ui.LocalRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Time of the last remote-control key press, to know when the user is idle. */
object UserActivity {
    @Volatile
    var lastKeyAt: Long = SystemClock.uptimeMillis()

    fun touch() {
        lastKeyAt = SystemClock.uptimeMillis()
    }

    fun idleMs(): Long = SystemClock.uptimeMillis() - lastKeyAt
}

/**
 * Keeps at most one preview stream open (providers allow one or two connections),
 * lets the full player close it before it starts, and leaves a short gap between
 * closing one stream and opening the next so the provider has freed the slot.
 */
object LivePreviewHub {
    private var current: ExoPlayer? = null
    private var tour: ExoPlayer? = null
    private var lastReleaseAt = 0L
    private val released = java.util.Collections.newSetFromMap(java.util.WeakHashMap<ExoPlayer, Boolean>())

    /** True while the side menu is open: previews stop so moving around stays light. */
    val paused = MutableStateFlow(false)

    private fun safeRelease(p: ExoPlayer) {
        if (released.add(p)) {
            p.release()
            lastReleaseAt = SystemClock.uptimeMillis()
        }
    }

    fun msSinceRelease(): Long = SystemClock.uptimeMillis() - lastReleaseAt

    fun claim(p: ExoPlayer) {
        tour?.let { safeRelease(it) }
        tour = null
        current?.let { if (it !== p) safeRelease(it) }
        current = p
    }

    fun release(p: ExoPlayer) {
        safeRelease(p)
        if (current === p) current = null
        if (tour === p) tour = null
    }

    fun stop() {
        current?.let { safeRelease(it) }
        tour?.let { safeRelease(it) }
        current = null
        tour = null
    }

    val previewActive: Boolean get() = current != null

    fun claimTour(p: ExoPlayer): Boolean {
        if (current != null) return false
        tour?.let { if (it !== p) safeRelease(it) }
        tour = p
        return true
    }

    fun isTour(p: ExoPlayer) = tour === p
}

/**
 * A muted live picture of [url]: starts once the user has paused on it, fades in
 * on the first frame, and stops when hidden, when the menu opens, when the app goes
 * to the background or when the full player opens. While it plays, a frame is saved
 * as the channel's thumbnail. [placeholder] is drawn underneath until the video shows.
 */
@Composable
fun LivePreview(
    url: String?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    channel: ChannelEntity? = null,
    startDelayMs: Long = 2_000,
    placeholder: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val repo = LocalRepository.current
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
    val paused by LivePreviewHub.paused.collectAsState()

    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var texture by remember { mutableStateOf<TextureView?>(null) }
    var showing by remember { mutableStateOf(false) }
    val active = enabled && foreground && !paused && !url.isNullOrBlank()

    LaunchedEffect(url, active) {
        showing = false
        player?.let { LivePreviewHub.release(it) }
        player = null
        texture = null
        if (!active || url == null) return@LaunchedEffect
        delay(startDelayMs)
        val gap = 900 - LivePreviewHub.msSinceRelease()
        if (gap > 0) delay(gap)
        val p = buildPreviewPlayer(context)
        p.addListener(hlsFallback(p, url) { showing = it })
        LivePreviewHub.claim(p)
        p.setMediaItem(mediaItemFor(url, false))
        p.prepare()
        player = p
    }

    // Thumbnail for the channel's card: a frame shortly after the picture shows, then every minute.
    LaunchedEffect(showing, channel?.remoteId) {
        val c = channel ?: return@LaunchedEffect
        if (!showing) return@LaunchedEffect
        delay(2_500)
        while (true) {
            texture?.getBitmap(480, 270)?.let { bmp ->
                repo.scope.launch { runCatching { repo.captures.save(c.playlistId, c.remoteId, bmp) } }
            }
            delay(60_000)
        }
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
                    factory = { ctx ->
                        TextureView(ctx).also {
                            p.setVideoTextureView(it)
                            texture = it
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = videoAlpha },
                )
            }
        }
    }
}

/**
 * Refreshes channel thumbnails in the background: while the user is idle on the
 * main screens and no preview is playing, it opens the given channels one at a
 * time (a single connection, muted), keeps a frame and closes them. Pressing play
 * or any preview starting stops it at once.
 */
@Composable
fun ThumbnailTour(enabled: Boolean, candidates: suspend () -> List<ChannelEntity>) {
    if (!enabled) return
    val context = LocalContext.current
    val repo = LocalRepository.current
    var current by remember { mutableStateOf<ExoPlayer?>(null) }
    var texture by remember { mutableStateOf<TextureView?>(null) }

    fun mayRun() = !LivePreviewHub.previewActive && !LivePreviewHub.paused.value && UserActivity.idleMs() > 8_000

    LaunchedEffect(Unit) {
        delay(15_000)
        while (true) {
            delay(4_000)
            if (!mayRun()) continue
            val now = System.currentTimeMillis()
            val due = runCatching { candidates() }.getOrDefault(emptyList()).filter { c ->
                val cap = repo.captures.get(c.playlistId, c.remoteId)
                cap == null || now - cap.takenAt > 20 * 60_000L
            }
            if (due.isEmpty()) {
                delay(3 * 60_000L)
                continue
            }
            for (c in due.take(6)) {
                if (!mayRun()) break
                val gap = 900 - LivePreviewHub.msSinceRelease()
                if (gap > 0) delay(gap)
                val p = buildPreviewPlayer(context, maxHeight = 540)
                if (!LivePreviewHub.claimTour(p)) {
                    p.release()
                    break
                }
                val firstFrame = CompletableDeferred<Unit>()
                val url = repo.liveUrl(c)
                p.addListener(hlsFallback(p, url) { shown -> if (shown) firstFrame.complete(Unit) })
                p.setMediaItem(mediaItemFor(url, false))
                p.prepare()
                current = p
                // Wait for the picture, giving up as soon as the user presses a key.
                var ok = false
                var waited = 0
                while (waited < 9_000 && LivePreviewHub.isTour(p) && UserActivity.idleMs() > 1_000) {
                    if (firstFrame.isCompleted) {
                        ok = true
                        break
                    }
                    delay(250)
                    waited += 250
                }
                if (ok) {
                    delay(1_500)
                    if (LivePreviewHub.isTour(p) && UserActivity.idleMs() > 1_500) {
                        texture?.getBitmap(480, 270)?.let { bmp ->
                            repo.scope.launch { runCatching { repo.captures.save(c.playlistId, c.remoteId, bmp) } }
                        }
                    }
                }
                current = null
                texture = null
                LivePreviewHub.release(p)
                delay(1_500)
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            current?.let { LivePreviewHub.release(it) }
            current = null
        }
    }

    // Tiny surface tucked under the side menu; frames are read at full size.
    val p = current
    if (p != null) {
        key(p) {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).also {
                        p.setVideoTextureView(it)
                        texture = it
                    }
                },
                modifier = Modifier.size(width = 16.dp, height = 9.dp),
            )
        }
    }
}

private fun mediaItemFor(url: String, forceHls: Boolean): MediaItem =
    MediaItem.Builder().setUri(url).apply {
        if (forceHls || url.contains(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8)
    }.build()

/** Reports when the picture shows, and retries as HLS when a server hides the .m3u8 extension. */
private fun hlsFallback(p: ExoPlayer, url: String, onShowing: (Boolean) -> Unit) = object : Player.Listener {
    private var triedHls = url.contains(".m3u8", ignoreCase = true)

    override fun onRenderedFirstFrame() = onShowing(true)

    override fun onPlayerError(error: PlaybackException) {
        onShowing(false)
        if (!triedHls) {
            triedHls = true
            p.setMediaItem(mediaItemFor(url, true))
            p.prepare()
        }
    }
}

private fun buildPreviewPlayer(context: Context, maxHeight: Int = 720): ExoPlayer {
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
        .setMaxVideoSize(maxHeight * 16 / 9, maxHeight)
        .build()
    p.repeatMode = Player.REPEAT_MODE_OFF
    p.playWhenReady = true
    return p
}
