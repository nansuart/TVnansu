package com.nansu.tvnansu.ui.player

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.nansu.tvnansu.data.PlayItem
import com.nansu.tvnansu.data.Repository
import com.nansu.tvnansu.data.db.Kind
import com.nansu.tvnansu.data.remote.USER_AGENT
import com.nansu.tvnansu.ui.components.LivePreviewHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

data class TrackOption(
    val label: String,
    val group: Tracks.Group?,
    val trackIndex: Int,
    val selected: Boolean,
)

/**
 * Owns the ExoPlayer instance and exposes Compose-observable playback state.
 * Handles live reconnection, the TS→HLS fallback, progress saving and queues
 * (channel zapping and next episode).
 */
@Stable
class PlayerController(
    context: Context,
    private val repo: Repository,
    val items: List<PlayItem>,
    startIndex: Int,
    private val startPositionMs: Long,
    private val playlistId: Long,
    private val uiScope: CoroutineScope,
    private val onFinished: () -> Unit,
) {
    var index by mutableIntStateOf(startIndex.coerceIn(0, items.lastIndex))
        private set
    val item: PlayItem get() = items[index]
    val isLive: Boolean get() = item.kind == Kind.LIVE

    var isPlaying by mutableStateOf(false)
        private set
    var buffering by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var tracks by mutableStateOf(Tracks.EMPTY)
        private set
    var position by mutableLongStateOf(0L)
        private set
    var duration by mutableLongStateOf(0L)
        private set

    /** Pending seek target while the user holds left/right. */
    var scrubTarget by mutableStateOf<Long?>(null)
        private set

    private var retries = 0
    private var forceHls = false
    private var scrubJob: Job? = null
    private var retryJob: Job? = null
    private val tickerJob: Job
    private val saveJob: Job

    val player: ExoPlayer

    init {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(25_000)
        val dataSource = DefaultDataSource.Factory(context, http)
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)
            .setConstantBitrateSeekingEnabled(true)
        val renderers = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 50_000,
                /* bufferForPlaybackMs = */ 1_500,
                /* bufferForPlaybackAfterRebufferMs = */ 3_000,
            )
            .build()

        player = ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource, extractors))
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            .build()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                when (state) {
                    Player.STATE_READY -> {
                        error = null
                        retries = 0
                    }
                    Player.STATE_ENDED -> onEnded()
                    else -> Unit
                }
            }

            override fun onPlayerError(e: PlaybackException) = handleError(e)

            override fun onTracksChanged(t: Tracks) {
                tracks = t
            }
        })

        player.playWhenReady = true
        // Give the provider a moment to free the preview's connection before opening ours.
        val wait = 700 - LivePreviewHub.msSinceRelease()
        if (wait > 0) uiScope.launch { delay(wait); load(startPositionMs) } else load(startPositionMs)

        tickerJob = uiScope.launch {
            while (isActive) {
                position = player.currentPosition.coerceAtLeast(0)
                val d = player.duration
                duration = if (d == C.TIME_UNSET || d < 0) 0 else d
                delay(500)
            }
        }
        saveJob = uiScope.launch {
            while (isActive) {
                delay(15_000)
                if (player.isPlaying) save()
            }
        }
    }

    private fun mediaItem(url: String): MediaItem {
        val b = MediaItem.Builder().setUri(url)
        if (forceHls || url.contains(".m3u8", ignoreCase = true)) b.setMimeType(MimeTypes.APPLICATION_M3U8)
        return b.build()
    }

    private fun load(seekMs: Long) {
        retryJob?.cancel()
        error = null
        buffering = true
        position = 0
        duration = 0
        val it = item
        if (seekMs > 0 && !isLive) player.setMediaItem(mediaItem(it.url), seekMs)
        else player.setMediaItem(mediaItem(it.url))
        player.prepare()
        player.play()

        when (it.kind) {
            Kind.LIVE -> repo.scope.launch { repo.markChannelWatched(playlistId, it.remoteId) }
            Kind.EPISODE -> repo.scope.launch {
                val existing = repo.progress(repo.uid(playlistId, Kind.SERIES, it.seriesId ?: ""))
                if (existing == null || existing.url != it.url) repo.markSeriesResume(it, playlistId, 1, 0)
            }
            else -> Unit
        }
    }

    private fun handleError(e: PlaybackException) {
        val parsing = e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
            e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
        if (parsing && !forceHls && !item.url.contains(".m3u8", ignoreCase = true)) {
            // Many servers send HLS without the .m3u8 extension.
            forceHls = true
            load(if (isLive) 0 else player.currentPosition)
            return
        }
        if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition()
            player.prepare()
            return
        }
        val maxRetries = if (isLive) 6 else 3
        if (retries < maxRetries) {
            retries++
            error = "Reconectando… ($retries/$maxRetries)"
            val resumeAt = if (isLive) 0 else player.currentPosition
            retryJob = uiScope.launch {
                delay(1_200L * retries)
                load(resumeAt)
            }
            return
        }
        error = describe(e)
    }

    private fun describe(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            "No hay conexión con el servidor."
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
            "El servidor rechazó la emisión. Puede estar caída o haber demasiadas conexiones a la vez."
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
            "Este contenido ya no está disponible en el servidor."
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
            "Tu dispositivo no puede reproducir este formato de vídeo o audio."
        else -> "No se pudo reproducir (${e.errorCodeName})."
    }

    private fun onEnded() {
        when {
            isLive -> load(0)
            item.kind == Kind.EPISODE && index < items.lastIndex -> {
                save()
                index++
                retries = 0
                forceHls = false
                load(0)
            }
            else -> {
                save()
                onFinished()
            }
        }
    }

    // ------------------------------------------------------------------ user actions

    fun retry() {
        retries = 0
        load(if (isLive) 0 else position)
    }

    fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun play() = player.play()

    fun seekTo(ms: Long) {
        scrubTarget = null
        player.seekTo(ms.coerceAtLeast(0))
    }

    val playlistIdForMarkers: Long get() = playlistId
    fun pause() = player.pause()

    /** Accumulates seek steps and applies them after a short pause, like a scrub bar. */
    fun scrub(deltaMs: Long) {
        if (isLive || duration <= 0) return
        val base = scrubTarget ?: player.currentPosition
        scrubTarget = (base + deltaMs).coerceIn(0, (duration - 1_000).coerceAtLeast(0))
        scrubJob?.cancel()
        scrubJob = uiScope.launch {
            delay(650)
            scrubTarget?.let { player.seekTo(it) }
            scrubTarget = null
        }
    }

    fun goTo(i: Int) {
        if (items.isEmpty()) return
        val target = ((i % items.size) + items.size) % items.size
        if (target == index) return
        save()
        index = target
        retries = 0
        forceHls = false
        scrubTarget = null
        load(0)
    }

    fun next() = goTo(index + 1)
    fun previous() = goTo(index - 1)

    // ------------------------------------------------------------------ tracks

    fun audioOptions(): List<TrackOption> = optionsFor(C.TRACK_TYPE_AUDIO)

    fun textOptions(): List<TrackOption> {
        val opts = optionsFor(C.TRACK_TYPE_TEXT)
        val anySelected = opts.any { it.selected }
        return listOf(TrackOption("Desactivados", null, -1, !anySelected)) + opts
    }

    private fun optionsFor(type: Int): List<TrackOption> {
        val out = ArrayList<TrackOption>()
        var n = 0
        for (g in tracks.groups) {
            if (g.type != type) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                out += TrackOption(labelFor(g.getTrackFormat(i), type, n), g, i, g.isTrackSelected(i))
                n++
            }
        }
        return out
    }

    fun select(type: Int, option: TrackOption) {
        val builder = player.trackSelectionParameters.buildUpon()
        val g = option.group
        if (g == null) {
            builder.setTrackTypeDisabled(type, true)
        } else {
            builder.setTrackTypeDisabled(type, false)
            builder.setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, option.trackIndex))
        }
        player.trackSelectionParameters = builder.build()
    }

    private fun labelFor(f: Format, type: Int, n: Int): String {
        val es = Locale("es", "ES")
        val lang = f.language
            ?.takeIf { it.isNotBlank() && it != "und" }
            ?.let { code -> Locale(code).getDisplayLanguage(es).replaceFirstChar { c -> c.uppercase(es) } }
        val name = f.label?.takeIf { it.isNotBlank() } ?: lang ?: "Pista ${n + 1}"
        val channels = if (type == C.TRACK_TYPE_AUDIO) when (f.channelCount) {
            1 -> "Mono"
            2 -> "Estéreo"
            6 -> "5.1"
            8 -> "7.1"
            else -> null
        } else null
        val codec = f.sampleMimeType?.substringAfter('/')?.uppercase()?.let {
            when {
                it.contains("EAC3") || it.contains("E-AC3") -> "Dolby Digital+"
                it.contains("AC3") -> "Dolby Digital"
                it.contains("DTS") -> "DTS"
                it.contains("MP4A") || it.contains("AAC") -> "AAC"
                else -> null
            }
        }
        return listOfNotNull(name, channels, codec).distinct().joinToString(" · ")
    }

    // ------------------------------------------------------------------ lifecycle

    fun save() {
        val it = item
        if (it.kind == Kind.LIVE) return
        val pos = player.currentPosition
        val dur = player.duration
        if (dur <= 0 || dur == C.TIME_UNSET) return
        val hasNext = it.kind == Kind.EPISODE && index < items.lastIndex
        repo.scope.launch { repo.saveProgress(it, playlistId, pos, dur, hasNext) }
    }

    fun release() {
        save()
        tickerJob.cancel()
        saveJob.cancel()
        scrubJob?.cancel()
        retryJob?.cancel()
        player.release()
    }
}
