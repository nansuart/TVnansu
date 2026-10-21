package com.hispanoiptv.tv.ui.player

import com.hispanoiptv.tv.ui.components.ChannelLogo
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.hispanoiptv.tv.data.NowNext
import com.hispanoiptv.tv.data.PlayItem
import com.hispanoiptv.tv.data.PlayRequest
import com.hispanoiptv.tv.data.db.ChannelEntity
import com.hispanoiptv.tv.data.db.IntroMarkerEntity
import com.hispanoiptv.tv.data.db.Kind
import com.hispanoiptv.tv.ui.Fmt
import com.hispanoiptv.tv.ui.LocalRepository
import com.hispanoiptv.tv.ui.toast
import kotlinx.coroutines.launch
import com.hispanoiptv.tv.ui.PivotScroll
import com.hispanoiptv.tv.ui.components.LiveDot
import com.hispanoiptv.tv.ui.components.LogoCompact
import com.hispanoiptv.tv.ui.components.PillButton
import com.hispanoiptv.tv.ui.components.ProgressLine
import com.hispanoiptv.tv.ui.components.Segmented
import com.hispanoiptv.tv.ui.components.Spinner
import com.hispanoiptv.tv.ui.nav.LocalNavigator
import com.hispanoiptv.tv.ui.theme.Palette
import com.hispanoiptv.tv.ui.theme.Type
import kotlinx.coroutines.delay

private enum class Panel { CHANNELS, TRACKS }

@Composable
fun PlayerScreen(request: PlayRequest) {
    val context = LocalContext.current
    val repo = LocalRepository.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()

    val controller = remember {
        PlayerController(
            context = context,
            repo = repo,
            items = request.items,
            startIndex = request.index,
            startPositionMs = request.startPositionMs,
            playlistId = repo.activeId.value,
            uiScope = scope,
            onFinished = { nav.pop() },
        )
    }
    DisposableEffect(Unit) {
        onDispose { controller.release() }
    }

    val item = controller.item
    val isLive = controller.isLive
    var overlay by remember { mutableStateOf(true) }
    var interaction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var digits by remember { mutableStateOf("") }
    val rootFocus = remember { FocusRequester() }
    val nextUpFocus = remember { FocusRequester() }
    var subtitleSize by remember { mutableIntStateOf(repo.prefs.subtitleSize) }
    var subtitleBackground by remember { mutableStateOf(repo.prefs.subtitleBackground) }
    var nextUpDismissedFor by remember { mutableIntStateOf(-1) }
    val skipFocus = remember { FocusRequester() }
    var skipDismissedFor by remember { mutableIntStateOf(-1) }
    var introVersion by remember { mutableIntStateOf(0) }
    val markerScope = rememberCoroutineScope()
    var playerView by remember { mutableStateOf<PlayerView?>(null) }

    // Subtitles: text ones are drawn by SubtitleOverlay; image ones (PGS) by the player view.
    var cues by remember { mutableStateOf<List<Cue>>(emptyList()) }
    DisposableEffect(controller.player) {
        val listener = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                cues = cueGroup.cues
            }
        }
        controller.player.addListener(listener)
        onDispose { controller.player.removeListener(listener) }
    }
    val imageSubs = cues.any { it.bitmap != null }
    val watermark = remember { repo.prefs.watermark }
    var leaving by remember { mutableStateOf(false) }

    // Saves what is on screen so the channel card can show it later.
    suspend fun captureFrame() {
        val view = playerView ?: return
        val current = controller.item
        if (current.kind != Kind.LIVE || controller.error != null || controller.buffering || !controller.isPlaying) return
        val bmp = FrameGrabber.grab(view) ?: return
        val pid = controller.playlistIdForMarkers
        repo.scope.launch { runCatching { repo.captures.save(pid, current.remoteId, bmp) } }
    }

    fun leave() {
        if (leaving) return
        leaving = true
        scope.launch {
            if (isLive) captureFrame()
            nav.pop()
        }
    }

    // Intro learned for this series (marked once by the user, reused in every episode).
    val introMarker by produceState<IntroMarkerEntity?>(null, controller.index, introVersion) {
        val sid = item.seriesId
        value = if (item.kind == Kind.EPISODE && sid != null) repo.introMarker(controller.playlistIdForMarkers, sid) else null
    }

    // "Next episode" card during the last 30 seconds of an episode.
    val nextItem = if (item.kind == Kind.EPISODE) controller.items.getOrNull(controller.index + 1) else null
    val remainingMs = controller.duration - controller.position
    val showNextUp = nextItem != null &&
        controller.duration > 120_000 &&
        remainingMs in 1..30_000 &&
        nextUpDismissedFor != controller.index &&
        panel == null &&
        controller.error == null

    val marker = introMarker
    val showSkipIntro = marker != null &&
        marker.endMs > marker.startMs &&
        controller.position >= marker.startMs &&
        controller.position < marker.endMs - 2_000 &&
        skipDismissedFor != controller.index &&
        panel == null &&
        controller.error == null &&
        !showNextUp

    fun poke() {
        overlay = true
        interaction = System.currentTimeMillis()
    }

    // Show the info banner whenever the item changes (zapping / next episode).
    LaunchedEffect(controller.index) { poke() }

    // Auto-hide controls.
    LaunchedEffect(interaction, overlay, controller.isPlaying, panel, controller.error) {
        if (overlay && panel == null && controller.error == null && (controller.isPlaying || isLive)) {
            delay(if (isLive) 5_000 else 4_500)
            overlay = false
        }
    }

    // Typed channel numbers: jump after a short pause.
    LaunchedEffect(digits) {
        if (digits.isEmpty()) return@LaunchedEffect
        delay(1_500)
        val n = digits.toIntOrNull()
        digits = ""
        if (n != null) {
            val i = controller.items.indexOfFirst { it.number == n }
            if (i >= 0) controller.goTo(i)
        }
    }

    // A frame a few seconds after tuning in, then every minute and a half.
    LaunchedEffect(controller.index, isLive) {
        if (!isLive) return@LaunchedEffect
        delay(8_000)
        while (true) {
            captureFrame()
            delay(90_000)
        }
    }

    LaunchedEffect(panel, showNextUp, showSkipIntro) {
        if (panel == null) {
            delay(50)
            runCatching {
                when {
                    showNextUp -> nextUpFocus.requestFocus()
                    showSkipIntro -> skipFocus.requestFocus()
                    else -> rootFocus.requestFocus()
                }
            }
        }
    }

    val nowNext by produceState<NowNext?>(null, controller.index) {
        value = null
        if (item.kind == Kind.LIVE) {
            value = repo.nowNext(
                ChannelEntity(
                    playlistId = repo.activeId.value, remoteId = item.remoteId, name = item.title,
                    logo = item.image, categoryId = "", epgId = item.epgId, url = item.url,
                    number = item.number, catchup = false,
                )
            )
        }
    }

    BackHandler {
        when {
            panel != null -> panel = null
            showNextUp -> nextUpDismissedFor = controller.index
            showSkipIntro -> skipDismissedFor = controller.index
            digits.isNotEmpty() -> digits = ""
            else -> leave()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown || panel != null) return@onKeyEvent false
                if ((showNextUp || showSkipIntro) && (ev.key == Key.DirectionCenter || ev.key == Key.Enter || ev.key == Key.NumPadEnter)) {
                    return@onKeyEvent false
                }
                val repeat = ev.nativeKeyEvent.repeatCount
                val seekStep = when {
                    repeat < 4 -> 10_000L
                    repeat < 14 -> 30_000L
                    else -> 60_000L
                }
                when (ev.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        when {
                            controller.error != null && !controller.error!!.startsWith("Reconectando") -> controller.retry()
                            isLive -> panel = Panel.CHANNELS
                            else -> controller.togglePlay()
                        }
                        poke(); true
                    }
                    Key.MediaPlayPause, Key.Spacebar -> { controller.togglePlay(); poke(); true }
                    Key.MediaPlay -> { controller.play(); poke(); true }
                    Key.MediaPause -> { controller.pause(); poke(); true }
                    Key.DirectionLeft -> {
                        if (isLive) panel = Panel.CHANNELS else controller.scrub(-seekStep)
                        poke(); true
                    }
                    Key.DirectionRight -> {
                        if (isLive) panel = Panel.TRACKS else controller.scrub(seekStep)
                        poke(); true
                    }
                    Key.MediaRewind -> { controller.scrub(-30_000); poke(); true }
                    Key.MediaFastForward -> { controller.scrub(30_000); poke(); true }
                    Key.DirectionUp -> {
                        if (isLive) controller.previous() else panel = Panel.TRACKS
                        poke(); true
                    }
                    Key.DirectionDown -> {
                        if (isLive) controller.next() else poke()
                        poke(); true
                    }
                    Key.ChannelUp, Key.PageUp -> { controller.next(); poke(); true }
                    Key.ChannelDown, Key.PageDown -> { controller.previous(); poke(); true }
                    Key.MediaNext -> { controller.next(); poke(); true }
                    Key.MediaPrevious -> { controller.previous(); poke(); true }
                    Key.Menu, Key.Info -> { panel = Panel.TRACKS; poke(); true }
                    else -> {
                        val d = digitOf(ev.key)
                        if (isLive && d != null) {
                            digits = (digits + d).takeLast(4)
                            poke()
                            true
                        } else false
                    }
                }
            }
            .focusable()
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).also { playerView = it }.apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    useController = false
                    isFocusable = false
                    isFocusableInTouchMode = false
                    keepScreenOn = true
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    player = controller.player
                }
            },
            update = { view ->
                SubtitleStyle.apply(view, subtitleSize, subtitleBackground)
                view.subtitleView?.visibility = if (imageSubs) android.view.View.VISIBLE else android.view.View.INVISIBLE
            },
            modifier = Modifier.fillMaxSize(),
        )

        val subtitleLift by animateDpAsState(
            targetValue = when {
                !overlay || panel != null -> 0.dp
                isLive -> 190.dp
                else -> 150.dp
            },
            animationSpec = tween(300),
            label = "subLift",
        )
        if (!imageSubs) {
            SubtitleOverlay(
                cues = cues,
                sizeIndex = subtitleSize,
                background = subtitleBackground,
                lift = subtitleLift,
            )
        }

        if (controller.buffering && controller.error == null) {
            Spinner(Modifier.align(Alignment.Center), size = 52)
        }

        val err = controller.error
        if (err != null) {
            Column(
                Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.75f))
                    .padding(horizontal = 32.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(err, style = Type.Section, color = Palette.Text)
                if (!err.startsWith("Reconectando")) {
                    Spacer(Modifier.height(8.dp))
                    Text("Pulsa OK para reintentar", style = Type.Caption, color = Palette.TextDim)
                }
            }
        }

        if (digits.isNotEmpty()) {
            Text(
                text = digits,
                style = Type.Display,
                color = Palette.Text,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        // Hispano IPTV watermark: live channels only, while the controls are hidden.
        AnimatedVisibility(
            visible = watermark && isLive && !overlay && panel == null && controller.error == null,
            enter = fadeIn(tween(800)),
            exit = fadeOut(tween(250)),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 40.dp, bottom = 30.dp),
        ) {
            LogoCompact(height = 24.dp, white = true, modifier = Modifier.graphicsLayer { alpha = 0.3f })
        }

        AnimatedVisibility(
            visible = overlay && panel == null && !showNextUp,
            enter = fadeIn() + slideInVertically { it / 6 },
            exit = fadeOut() + slideOutVertically { it / 6 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            if (isLive) LiveBanner(controller, nowNext) else VodControls(controller)
        }

        AnimatedVisibility(
            visible = showSkipIntro,
            enter = fadeIn() + slideInHorizontally { it / 3 },
            exit = fadeOut() + slideOutHorizontally { it / 3 },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 56.dp, bottom = if (overlay) 236.dp else 56.dp),
        ) {
            PillButton(
                text = "Saltar intro",
                leading = "»",
                primary = true,
                onClick = {
                    marker?.let { controller.seekTo(it.endMs) }
                    skipDismissedFor = controller.index
                },
                modifier = Modifier.focusRequester(skipFocus),
            )
        }

        AnimatedVisibility(
            visible = showNextUp,
            enter = fadeIn() + slideInHorizontally { it / 3 },
            exit = fadeOut() + slideOutHorizontally { it / 3 },
            modifier = Modifier.align(Alignment.BottomEnd),
        ) {
            val upcoming = nextItem
            if (upcoming != null) {
                NextUpCard(
                    next = upcoming,
                    secondsLeft = (remainingMs / 1000).toInt().coerceAtLeast(0),
                    onPlay = { controller.next() },
                    focusRequester = nextUpFocus,
                )
            }
        }

        AnimatedVisibility(
            visible = panel == Panel.CHANNELS,
            enter = fadeIn() + slideInHorizontally { -it / 3 },
            exit = fadeOut() + slideOutHorizontally { -it / 3 },
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            ChannelListPanel(controller, onPick = { i ->
                controller.goTo(i)
                panel = null
            })
        }

        AnimatedVisibility(
            visible = panel == Panel.TRACKS,
            enter = fadeIn() + slideInHorizontally { it / 3 },
            exit = fadeOut() + slideOutHorizontally { it / 3 },
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            TracksPanel(
                controller = controller,
                isEpisode = item.kind == Kind.EPISODE && item.seriesId != null,
                marker = marker,
                onMarkIntro = { isStart ->
                    val sid = item.seriesId
                    if (sid != null) {
                        val pos = controller.position
                        markerScope.launch {
                            if (isStart) repo.markIntro(controller.playlistIdForMarkers, sid, start = pos)
                            else repo.markIntro(controller.playlistIdForMarkers, sid, end = pos)
                            introVersion++
                            context.toast(
                                if (isStart) "Inicio de la intro guardado en ${Fmt.duration(pos)}"
                                else "Final de la intro guardado en ${Fmt.duration(pos)}. Se ofrecerá saltarla en esta serie."
                            )
                        }
                        if (!isStart) {
                            skipDismissedFor = controller.index
                            panel = null
                        }
                    }
                },
                onClearIntro = {
                    val sid = item.seriesId
                    if (sid != null) {
                        markerScope.launch {
                            repo.clearIntro(controller.playlistIdForMarkers, sid)
                            introVersion++
                            context.toast("Intro borrada para esta serie")
                        }
                    }
                },
                subtitleSize = subtitleSize,
                subtitleBackground = subtitleBackground,
                onSubtitleSize = {
                    subtitleSize = it
                    repo.prefs.subtitleSize = it
                },
                onSubtitleBackground = {
                    subtitleBackground = it
                    repo.prefs.subtitleBackground = it
                },
                onDone = { panel = null },
            )
        }
    }
}

private fun digitOf(key: Key): Int? = when (key) {
    Key.Zero, Key.NumPad0 -> 0
    Key.One, Key.NumPad1 -> 1
    Key.Two, Key.NumPad2 -> 2
    Key.Three, Key.NumPad3 -> 3
    Key.Four, Key.NumPad4 -> 4
    Key.Five, Key.NumPad5 -> 5
    Key.Six, Key.NumPad6 -> 6
    Key.Seven, Key.NumPad7 -> 7
    Key.Eight, Key.NumPad8 -> 8
    Key.Nine, Key.NumPad9 -> 9
    else -> null
}

@Composable
private fun VodControls(controller: PlayerController) {
    val item = controller.item
    val pos = controller.scrubTarget ?: controller.position
    val dur = controller.duration
    Box(
        Modifier
            .fillMaxWidth()
            .height(220.dp)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
    ) {
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 56.dp, vertical = 36.dp)
        ) {
            if (item.seriesTitle != null) {
                Text(
                    "${item.seriesTitle}  ·  ${item.subtitle ?: ""}",
                    style = Type.Label,
                    color = Palette.TextDim,
                )
                Spacer(Modifier.height(4.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (controller.isPlaying) "❚❚" else "▶",
                    style = Type.Title,
                    color = Palette.Text,
                    modifier = Modifier.width(44.dp),
                )
                Text(item.title, style = Type.Title, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(16.dp))
            ProgressLine(
                fraction = if (dur > 0) pos.toFloat() / dur else 0f,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(Fmt.duration(pos), style = Type.Label, color = if (controller.scrubTarget != null) Palette.Accent else Palette.Text)
                Spacer(Modifier.weight(1f))
                Text(
                    "◀ ▶ avanzar   ·   OK pausa   ·   ▲ audio, subtítulos e intro",
                    style = Type.Caption,
                    color = Palette.TextFaint,
                )
                Spacer(Modifier.weight(1f))
                Text(if (dur > 0) "-" + Fmt.duration(dur - pos) else "", style = Type.Label, color = Palette.TextDim)
            }
        }
    }
}

@Composable
private fun LiveBanner(controller: PlayerController, nowNext: NowNext?) {
    val item = controller.item
    Box(
        Modifier
            .fillMaxWidth()
            .height(230.dp)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))))
    ) {
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 56.dp, vertical = 36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChannelLogo(
                url = item.image,
                name = item.title,
                modifier = Modifier
                    .size(width = 150.dp, height = 84.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.number > 0) {
                        Text("${item.number}", style = Type.Title, color = Palette.Accent)
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(item.title, style = Type.Title, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(14.dp))
                    LiveDot()
                }
                Spacer(Modifier.height(8.dp))
                if (nowNext?.nowTitle != null) {
                    Text(
                        "${Fmt.clock(nowNext.nowStart)} – ${Fmt.clock(nowNext.nowStop)}   ${nowNext.nowTitle}",
                        style = Type.Section,
                        color = Palette.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    val total = (nowNext.nowStop - nowNext.nowStart).coerceAtLeast(1)
                    ProgressLine(
                        fraction = (System.currentTimeMillis() - nowNext.nowStart).toFloat() / total,
                        modifier = Modifier.fillMaxWidth(0.7f),
                    )
                } else {
                    Text("Sin información de programación", style = Type.Body, color = Palette.TextDim)
                }
                if (nowNext?.nextTitle != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Después  ${Fmt.clock(nowNext.nextStart)}   ${nowNext.nextTitle}",
                        style = Type.Label,
                        color = Palette.TextDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "▲ ▼ cambiar canal   ·   OK lista de canales   ·   ▶ audio y subtítulos   ·   números para ir a un canal",
                    style = Type.Caption,
                    color = Palette.TextFaint,
                )
            }
        }
    }
}

@Composable
private fun ChannelListPanel(controller: PlayerController, onPick: (Int) -> Unit) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (controller.index - 3).coerceAtLeast(0))
    val currentRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { currentRequester.requestFocus() }
    }
    Column(
        Modifier
            .fillMaxHeight()
            .width(380.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(Color.Black.copy(alpha = 0.92f), Color.Black.copy(alpha = 0.75f))
                )
            )
            .padding(top = 36.dp)
    ) {
        Text(
            "Canales",
            style = Type.Title,
            color = Palette.Text,
            modifier = Modifier.padding(start = 28.dp, bottom = 12.dp),
        )
        PivotScroll(160.dp) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(controller.items, key = { i, it -> "${it.uid}#$i" }) { i, it ->
                    val current = i == controller.index
                    Surface(
                        onClick = { onPick(i) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .then(if (current) Modifier.focusRequester(currentRequester) else Modifier),
                        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (current) Palette.Surface2 else Color.Transparent,
                            contentColor = Palette.Text,
                            focusedContainerColor = Palette.Focus,
                            focusedContentColor = Color.Black,
                        ),
                    ) {
                        Row(
                            Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (it.number > 0) "${it.number}" else "",
                                style = Type.Caption,
                                modifier = Modifier.width(36.dp),
                            )
                            ChannelLogo(
                                url = it.image,
                                name = "",
                                showNameFallback = false,
                                modifier = Modifier
                                    .size(width = 52.dp, height = 30.dp)
                                    .clip(RoundedCornerShape(6.dp)),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(it.title, style = Type.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (current) {
                                Spacer(Modifier.weight(1f))
                                Box(
                                    Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Palette.Live)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NextUpCard(
    next: PlayItem,
    secondsLeft: Int,
    onPlay: () -> Unit,
    focusRequester: FocusRequester,
) {
    Row(
        Modifier
            .padding(end = 56.dp, bottom = 56.dp)
            .width(520.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black.copy(alpha = 0.82f))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 176.dp, height = 99.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Palette.Surface2),
        ) {
            if (!next.image.isNullOrBlank()) {
                AsyncImage(
                    model = next.image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text("SIGUIENTE EPISODIO", style = Type.Caption, color = Palette.Accent)
            Spacer(Modifier.height(4.dp))
            Text(
                listOfNotNull(next.subtitle, next.title).joinToString("  ·  "),
                style = Type.Label,
                color = Palette.Text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            PillButton(
                text = "Ver ahora",
                leading = "▶",
                primary = true,
                onClick = onPlay,
                modifier = Modifier.focusRequester(focusRequester),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Empieza en $secondsLeft s  ·  Atrás para seguir viendo",
                style = Type.Caption,
                color = Palette.TextDim,
            )
        }
    }
}

@Composable
private fun TracksPanel(
    controller: PlayerController,
    isEpisode: Boolean,
    marker: IntroMarkerEntity?,
    onMarkIntro: (isStart: Boolean) -> Unit,
    onClearIntro: () -> Unit,
    subtitleSize: Int,
    subtitleBackground: Boolean,
    onSubtitleSize: (Int) -> Unit,
    onSubtitleBackground: (Boolean) -> Unit,
    onDone: () -> Unit,
) {
    val audio = remember(controller.tracks) { controller.audioOptions() }
    val text = remember(controller.tracks) { controller.textOptions() }
    val firstRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { firstRequester.requestFocus() }
    }
    Column(
        Modifier
            .fillMaxHeight()
            .width(380.dp)
            .background(Color.Black.copy(alpha = 0.9f))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 36.dp)
    ) {
        if (isEpisode) {
            Text("Intro de la serie", style = Type.Title, color = Palette.Text)
            Spacer(Modifier.height(6.dp))
            Text(
                if (marker != null && marker.endMs > marker.startMs)
                    "Guardada: ${Fmt.duration(marker.startMs)} – ${Fmt.duration(marker.endMs)}. Aparece \"Saltar intro\" en cada episodio."
                else
                    "Marca dónde empieza y acaba la intro una vez; después podrás saltarla en todos los episodios.",
                style = Type.Caption,
                color = Palette.TextDim,
            )
            Spacer(Modifier.height(8.dp))
            OptionRow(
                label = "Empieza aquí  (${Fmt.duration(controller.position)})",
                selected = false,
                onClick = { onMarkIntro(true) },
                modifier = Modifier.focusRequester(firstRequester),
            )
            OptionRow(
                label = "Termina aquí  (${Fmt.duration(controller.position)})",
                selected = false,
                onClick = { onMarkIntro(false) },
            )
            if (marker != null) {
                OptionRow(label = "Borrar intro guardada", selected = false, onClick = onClearIntro)
            }
            Spacer(Modifier.height(24.dp))
        }
        Text("Audio", style = Type.Title, color = Palette.Text)
        Spacer(Modifier.height(10.dp))
        if (audio.isEmpty()) {
            Text("Solo hay una pista de audio", style = Type.Body, color = Palette.TextDim)
        }
        audio.forEachIndexed { i, opt ->
            OptionRow(
                label = opt.label,
                selected = opt.selected,
                onClick = {
                    controller.select(C.TRACK_TYPE_AUDIO, opt)
                    onDone()
                },
                modifier = if (i == 0 && !isEpisode) Modifier.focusRequester(firstRequester) else Modifier,
            )
        }
        Spacer(Modifier.height(24.dp))
        Text("Subtítulos", style = Type.Title, color = Palette.Text)
        Spacer(Modifier.height(10.dp))
        text.forEachIndexed { i, opt ->
            OptionRow(
                label = opt.label,
                selected = opt.selected,
                onClick = {
                    controller.select(C.TRACK_TYPE_TEXT, opt)
                    onDone()
                },
                modifier = if (audio.isEmpty() && i == 0 && !isEpisode) Modifier.focusRequester(firstRequester) else Modifier,
            )
        }
        Spacer(Modifier.height(24.dp))
        Text("Tamaño de subtítulos", style = Type.Section, color = Palette.Text)
        Spacer(Modifier.height(10.dp))
        Segmented(
            options = SubtitleStyle.labels,
            selected = subtitleSize,
            onSelect = onSubtitleSize,
        )
        Spacer(Modifier.height(12.dp))
        Segmented(
            options = listOf("Sin fondo", "Fondo"),
            selected = if (subtitleBackground) 1 else 0,
            onSelect = { onSubtitleBackground(it == 1) },
        )
    }
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .height(44.dp),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Palette.Text,
            focusedContainerColor = Palette.Focus,
            focusedContentColor = Color.Black,
        ),
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (selected) "✓" else "", style = Type.Section, modifier = Modifier.width(26.dp))
            Text(label, style = Type.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
