package com.nansu.tvnansu.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nansu.tvnansu.data.LiveEvent
import com.nansu.tvnansu.data.LiveTheme
import com.nansu.tvnansu.data.NowNext
import com.nansu.tvnansu.data.ThemeSummary
import com.nansu.tvnansu.data.db.ChannelEntity
import com.nansu.tvnansu.data.db.Kind
import com.nansu.tvnansu.ui.Fmt
import com.nansu.tvnansu.ui.LocalEdge
import com.nansu.tvnansu.ui.LocalRepository
import com.nansu.tvnansu.ui.LocalTopInset
import com.nansu.tvnansu.ui.RevealScroll
import com.nansu.tvnansu.ui.components.Skeleton
import kotlinx.coroutines.launch
import com.nansu.tvnansu.ui.components.CaptureImage
import com.nansu.tvnansu.ui.components.ChannelLogo
import com.nansu.tvnansu.ui.components.LiveChip
import com.nansu.tvnansu.ui.components.LivePreview
import com.nansu.tvnansu.ui.components.PillShape
import com.nansu.tvnansu.ui.components.ProgressLine
import com.nansu.tvnansu.ui.components.Shelf
import com.nansu.tvnansu.ui.components.rememberCapture
import com.nansu.tvnansu.ui.components.themeTint
import com.nansu.tvnansu.ui.focusMemory
import com.nansu.tvnansu.ui.nav.Dest
import com.nansu.tvnansu.ui.nav.LocalNavigator
import com.nansu.tvnansu.ui.rememberActions
import com.nansu.tvnansu.ui.rememberFocusMemory
import com.nansu.tvnansu.ui.theme.Palette
import com.nansu.tvnansu.ui.theme.Type
import kotlinx.coroutines.delay

/**
 * En vivo: a large slideshow of what matters right now (matches, events, news),
 * themes to browse by, the user's channels, and the full guide one step away.
 */
@Composable
fun LiveScreen() {
    val repo = LocalRepository.current
    val actions = rememberActions()
    val nav = LocalNavigator.current
    val memory = rememberFocusMemory()
    val pid by repo.activeId.collectAsState()
    val version by repo.dataVersion.collectAsState()
    val epgVersion by repo.epgVersion.collectAsState()
    val edge = LocalEdge.current
    val reduceMotion = repo.prefs.reduceMotion
    val previewOn = repo.prefs.livePreview
    val headerHidden = LocalHeaderHidden.current
    val scope = rememberCoroutineScope()

    var minuteTick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            minuteTick++
        }
    }
    val events by produceState(repo.peek<List<LiveEvent>>(repo.memoKey("live", pid)) ?: emptyList(), pid, version, epgVersion, minuteTick) {
        value = repo.liveNowShared(pid)
    }
    val themesKey = repo.memoKey("themes", pid)
    val themes by produceState(repo.peek<List<ThemeSummary>>(themesKey) ?: emptyList(), pid, version) {
        value = repo.cached(themesKey) { repo.liveThemes(pid) }
    }
    val favorites by remember(pid) { repo.favorites(pid) }
        .collectAsState(initial = repo.peek("fav:$pid") ?: emptyList())
    val favoriteChannels by produceState(repo.peek<List<ChannelEntity>>("live:fav:$pid") ?: emptyList(), pid, favorites, version) {
        value = repo.channelsByIds(pid, favorites.filter { it.kind == Kind.LIVE.name }.map { it.remoteId }.distinct())
        repo.store("live:fav:$pid", value)
    }
    val recentRefs by remember(pid) { repo.recentChannels(pid) }.collectAsState(initial = emptyList())
    val recentChannels by produceState(repo.peek<List<ChannelEntity>>("home:recent:$pid") ?: emptyList(), pid, recentRefs, version) {
        if (recentRefs.isEmpty() && value.isNotEmpty()) return@produceState
        value = repo.channelsByIds(pid, recentRefs.map { it.remoteId }.distinct())
        repo.store("home:recent:$pid", value)
    }

    // Slideshow: sports and events first, then a couple of news channels. The row
    // below only shows what is not already in the slideshow.
    val slides = remember(events) {
        val sports = events.filter { it.label != "NOTICIAS" }
        val news = events.filter { it.label == "NOTICIAS" }
        (sports.take(6) + news.take(2)).ifEmpty { news.take(4) }
    }
    val moreSports = remember(events, slides) {
        val shown = slides.map { it.channel.remoteId }.toSet()
        events.filter { it.label != "NOTICIAS" && it.channel.remoteId !in shown }
    }
    var slideIndex by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(slideIndex, slides, reduceMotion) {
        if (slides.size < 2 || reduceMotion) return@LaunchedEffect
        delay(14_000)
        slideIndex = (slideIndex + 1) % slides.size
    }
    val playAll = { ev: LiveEvent ->
        val list = events.map { it.channel }
        actions.playChannels(list, events.indexOf(ev).coerceAtLeast(0))
    }

    val listState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 24 } }
    LaunchedEffect(scrolled) { headerHidden.value = scrolled }

    RevealScroll(top = 40.dp) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 72.dp),
        ) {
            item(key = "slideshow") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(318.dp)
                        .onFocusChanged { if (it.hasFocus) scope.launch { listState.animateScrollToItem(0) } }
                ) {
                    if (slides.isNotEmpty()) {
                        val i = slideIndex % slides.size
                        EventSlideshow(
                            event = slides[i],
                            index = i,
                            count = slides.size,
                            reduceMotion = reduceMotion,
                            previewOn = previewOn,
                            onMove = { d -> slideIndex = (slideIndex + d + slides.size) % slides.size },
                            onPlay = { playAll(slides[i]) },
                            memory = memory,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Skeleton(Modifier.fillMaxSize(), RoundedCornerShape(0.dp))
                    }
                }
            }
            item(key = "themes") {
                Shelf("Explorar por temas") {
                    item(key = "guide") {
                        ThemeTile(
                            title = "Todos los canales",
                            subtitle = "Guía completa",
                            colors = listOf(Color(0xFF3A3A46), Color(0xFF1C1C24)),
                            onClick = { nav.push(Dest.LiveGuide(null)) },
                            modifier = Modifier.focusMemory("theme:all", memory),
                        )
                    }
                    items(themes, key = { it.theme.name }) { t ->
                        ThemeTile(
                            title = t.theme.title,
                            subtitle = "${t.channelCount} canales",
                            colors = themeColors(t.theme),
                            onClick = { nav.push(Dest.LiveGuide(THEME_PREFIX + t.theme.name)) },
                            modifier = Modifier.focusMemory("theme:${t.theme.name}", memory),
                        )
                    }
                }
            }
            if (moreSports.isNotEmpty()) {
                item(key = "sports") {
                    Shelf("Más deportes y eventos ahora", live = true, modifier = Modifier.animateItem()) {
                        items(moreSports, key = { "sp:" + it.channel.remoteId }) { ev ->
                            com.nansu.tvnansu.ui.components.EventCard(
                                event = ev,
                                onClick = { actions.playChannels(moreSports.map { it.channel }, moreSports.indexOf(ev)) },
                                modifier = Modifier.focusMemory("lsp:${ev.channel.remoteId}", memory),
                            )
                        }
                    }
                }
            }
            if (favoriteChannels.isNotEmpty()) {
                item(key = "fav") {
                    Shelf("Canales favoritos", live = true, modifier = Modifier.animateItem()) {
                        items(favoriteChannels, key = { "f:" + it.remoteId }) { c ->
                            ChannelCard(
                                c = c,
                                width = 176.dp,
                                onClick = { actions.playChannels(favoriteChannels, favoriteChannels.indexOf(c)) },
                                onLongClick = { actions.toggleFavorite(Kind.LIVE, c.remoteId, c.name, c.logo) },
                                modifier = Modifier.focusMemory("lfav:${c.remoteId}", memory),
                            )
                        }
                    }
                }
            }
            if (recentChannels.isNotEmpty()) {
                item(key = "recent") {
                    Shelf("Últimos canales", live = true, modifier = Modifier.animateItem()) {
                        items(recentChannels, key = { "r:" + it.remoteId }) { c ->
                            ChannelCard(
                                c = c,
                                width = 176.dp,
                                showAge = true,
                                onClick = { actions.playChannels(recentChannels, recentChannels.indexOf(c)) },
                                onLongClick = { actions.toggleFavorite(Kind.LIVE, c.remoteId, c.name, c.logo) },
                                modifier = Modifier.focusMemory("lrec:${c.remoteId}", memory),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Full-width band at the top of En vivo: the channel playing on the right (muted),
 * fading into the background on the left where the text sits. It reaches the screen
 * edges, under the menu and the section title.
 */
@Composable
private fun EventSlideshow(
    event: LiveEvent,
    index: Int,
    count: Int,
    reduceMotion: Boolean,
    previewOn: Boolean,
    onMove: (Int) -> Unit,
    onPlay: () -> Unit,
    memory: MutableState<String?>,
    modifier: Modifier = Modifier,
) {
    val repo = LocalRepository.current
    val edge = LocalEdge.current
    val nowNext by produceState<NowNext?>(null, event.channel.remoteId) {
        value = null
        delay(300)
        value = runCatching { repo.nowNext(event.channel) }.getOrNull()
    }
    Box(modifier.background(Palette.Bg)) {
        AnimatedContent(
            targetState = event,
            transitionSpec = {
                val d = if (reduceMotion) 0 else 650
                (fadeIn(tween(d)) + slideInHorizontally(tween(d)) { it / 14 }) togetherWith
                    (fadeOut(tween(d)) + slideOutHorizontally(tween(d)) { -it / 14 })
            },
            contentKey = { it.channel.remoteId },
            label = "event",
        ) { ev ->
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.linearGradient(listOf(Palette.Bg, themeTint(ev.label).copy(alpha = 0.8f))))
                )
                LivePreview(
                    url = repo.liveUrl(ev.channel),
                    enabled = previewOn,
                    channel = ev.channel,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .aspectRatio(16f / 9f),
                ) { SlidePlaceholder(ev.channel) }
            }
        }
        // Left fade for the text, top fade for the title bar, bottom fade into the rows.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Palette.Bg,
                        0.30f to Palette.Bg.copy(alpha = 0.92f),
                        0.55f to Palette.Bg.copy(alpha = 0.35f),
                        0.75f to Color.Transparent,
                    )
                )
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Palette.Bg.copy(alpha = 0.55f),
                        0.22f to Color.Transparent,
                        0.72f to Color.Transparent,
                        1f to Palette.Bg,
                    )
                )
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(0.52f)
                .padding(start = edge, bottom = 26.dp)
        ) {
            LiveChip("EN DIRECTO · ${event.label}")
            Spacer(Modifier.height(8.dp))
            Text(
                text = event.title,
                style = Type.Display.copy(fontSize = 28.sp, lineHeight = 32.sp),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                listOfNotNull(
                    event.channel.name,
                    if (event.start > 0) "${Fmt.clock(event.start)} – ${Fmt.clock(event.stop)}" else null,
                ).joinToString("  ·  "),
                style = Type.Label,
                color = Color.White.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (event.start > 0 && event.stop > event.start) {
                Spacer(Modifier.height(8.dp))
                ProgressLine(
                    fraction = (System.currentTimeMillis() - event.start).toFloat() / (event.stop - event.start),
                    modifier = Modifier.fillMaxWidth(0.6f),
                )
            }
            val desc = nowNext?.nowDescription
            if (!desc.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(desc, style = Type.Caption, color = Color.White.copy(alpha = 0.6f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier
                    .focusRestorer()
                    .focusGroup(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    onClick = onPlay,
                    modifier = Modifier
                        .height(38.dp)
                        .focusMemory("slideshow", memory),
                    shape = ClickableSurfaceDefaults.shape(shape = PillShape),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.White.copy(alpha = 0.16f),
                        contentColor = Color.White,
                        focusedContainerColor = Palette.Focus,
                        focusedContentColor = Color.Black,
                    ),
                ) {
                    Text(
                        "▶   Ver en directo",
                        style = Type.Section,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 20.dp),
                    )
                }
                if (count > 1) {
                    Spacer(Modifier.width(10.dp))
                    ArrowButton("‹") { onMove(-1) }
                    Spacer(Modifier.width(8.dp))
                    ArrowButton("›") { onMove(1) }
                }
            }
        }
        if (count > 1) {
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 36.dp, bottom = 30.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(count) { i ->
                    val active = i == index
                    val w by animateDpAsState(if (active) 20.dp else 6.dp, label = "dot")
                    Box(
                        Modifier
                            .size(width = w, height = 6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (active) Color.White else Color.White.copy(alpha = 0.35f))
                    )
                }
            }
        }
    }
}

@Composable
private fun SlidePlaceholder(channel: ChannelEntity) {
    val capture = rememberCapture(channel)
    Box(Modifier.fillMaxSize()) {
        if (capture != null) {
            CaptureImage(capture, Modifier.fillMaxSize())
        } else {
            ChannelLogo(
                url = channel.logo,
                name = "",
                showNameFallback = false,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(start = 80.dp)
                    .size(width = 176.dp, height = 99.dp)
                    .clip(RoundedCornerShape(14.dp)),
            )
        }
    }
}

@Composable
private fun ArrowButton(text: String, size: Dp = 38.dp, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(size),
        shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.16f),
            contentColor = Color.White,
            focusedContainerColor = Palette.Focus,
            focusedContentColor = Color.Black,
        ),
    ) {
        Text(text, style = Type.Title, modifier = Modifier.align(Alignment.Center).padding(bottom = 3.dp))
    }
}

@Composable
private fun ThemeTile(
    title: String,
    subtitle: String,
    colors: List<Color>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.size(width = 160.dp, height = 92.dp),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(1.5.dp, Palette.Focus), shape = shape),
        ),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = com.nansu.tvnansu.ui.components.FocusGlow),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Palette.Surface,
            contentColor = Color.White,
            focusedContainerColor = Palette.Surface2,
            focusedContentColor = Color.White,
        ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(colors))
                .padding(14.dp)
        ) {
            Text(
                title,
                style = Type.Section.copy(fontWeight = FontWeight.Bold, fontSize = 18.sp),
                color = Color.White,
                modifier = Modifier.align(Alignment.TopStart),
            )
            Text(
                subtitle,
                style = Type.Caption,
                color = Color.White.copy(alpha = 0.75f),
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
    }
}

private fun themeColors(t: LiveTheme): List<Color> = when (t) {
    LiveTheme.SPORTS -> listOf(Color(0xFF1F7A4A), Color(0xFF0F2E21))
    LiveTheme.NEWS -> listOf(Color(0xFF2A4C9C), Color(0xFF121D3D))
    LiveTheme.KIDS -> listOf(Color(0xFFE8752A), Color(0xFF5A2410))
    LiveTheme.MOVIES -> listOf(Color(0xFF7A2E8C), Color(0xFF2A0F33))
    LiveTheme.DOCS -> listOf(Color(0xFF1C7C7A), Color(0xFF0D2E2D))
    LiveTheme.MUSIC -> listOf(Color(0xFFC2307A), Color(0xFF3D0F28))
}
