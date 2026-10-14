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
import com.nansu.tvnansu.ui.PivotScroll
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

    var minuteTick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            minuteTick++
        }
    }
    val events by produceState(emptyList<LiveEvent>(), pid, version, epgVersion, minuteTick) {
        value = repo.liveNow(pid, limit = 24)
    }
    val themes by produceState(emptyList<ThemeSummary>(), pid, version) { value = repo.liveThemes(pid) }
    val favorites by remember(pid) { repo.favorites(pid) }.collectAsState(initial = emptyList())
    val favoriteChannels by produceState(emptyList<ChannelEntity>(), pid, favorites, version) {
        value = repo.channelsByIds(pid, favorites.filter { it.kind == Kind.LIVE.name }.map { it.remoteId }.distinct())
    }
    val recentRefs by remember(pid) { repo.recentChannels(pid) }.collectAsState(initial = emptyList())
    val recentChannels by produceState(emptyList<ChannelEntity>(), pid, recentRefs, version) {
        value = repo.channelsByIds(pid, recentRefs.map { it.remoteId }.distinct())
    }

    // Slideshow: sports and events first, then a couple of news channels.
    val slides = remember(events) {
        val sports = events.filter { it.label != "NOTICIAS" }
        val news = events.filter { it.label == "NOTICIAS" }
        (sports.take(6) + news.take(2)).ifEmpty { news.take(4) }
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

    PivotScroll(76.dp) {
        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = LocalTopInset.current, bottom = 72.dp),
        ) {
            if (slides.isNotEmpty()) {
                item(key = "slideshow") {
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
                        modifier = Modifier
                            .padding(start = edge, end = 40.dp, bottom = 24.dp)
                            .fillMaxWidth()
                            .height(292.dp),
                    )
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
            val sports = events.filter { it.label != "NOTICIAS" }
            if (sports.size > 1) {
                item(key = "sports") {
                    Shelf("Deportes y eventos ahora", live = true) {
                        items(sports, key = { "sp:" + it.channel.remoteId }) { ev ->
                            com.nansu.tvnansu.ui.components.EventCard(
                                event = ev,
                                onClick = { actions.playChannels(sports.map { it.channel }, sports.indexOf(ev)) },
                                modifier = Modifier.focusMemory("lsp:${ev.channel.remoteId}", memory),
                            )
                        }
                    }
                }
            }
            if (favoriteChannels.isNotEmpty()) {
                item(key = "fav") {
                    Shelf("Canales favoritos", live = true) {
                        items(favoriteChannels, key = { "f:" + it.remoteId }) { c ->
                            ChannelCard(
                                c = c,
                                width = 196.dp,
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
                    Shelf("Últimos canales", live = true) {
                        items(recentChannels, key = { "r:" + it.remoteId }) { c ->
                            ChannelCard(
                                c = c,
                                width = 196.dp,
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
    var hasFocus by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(24.dp)
    val nowNext by produceState<NowNext?>(null, event.channel.remoteId) {
        value = null
        delay(300)
        value = runCatching { repo.nowNext(event.channel) }.getOrNull()
    }
    Box(
        modifier
            .clip(shape)
            .background(Palette.Surface)
            .border(BorderStroke(if (hasFocus) 2.dp else 1.dp, Color.White.copy(alpha = if (hasFocus) 0.9f else 0.06f)), shape)
            .onFocusChanged { hasFocus = it.hasFocus }
    ) {
        AnimatedContent(
            targetState = event,
            transitionSpec = {
                val d = if (reduceMotion) 0 else 650
                (fadeIn(tween(d)) + slideInHorizontally(tween(d)) { it / 12 }) togetherWith
                    (fadeOut(tween(d)) + slideOutHorizontally(tween(d)) { -it / 12 })
            },
            contentKey = { it.channel.remoteId },
            label = "event",
        ) { ev ->
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.linearGradient(listOf(Palette.Surface, themeTint(ev.label))))
                )
                LivePreview(
                    url = repo.liveUrl(ev.channel),
                    enabled = previewOn,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .aspectRatio(16f / 9f),
                ) { SlidePlaceholder(ev.channel) }
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.92f),
                        0.38f to Color.Black.copy(alpha = 0.7f),
                        0.62f to Color.Transparent,
                    )
                )
        )
        Column(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(0.5f)
                .padding(start = 28.dp, top = 24.dp, bottom = 22.dp)
        ) {
            LiveChip("EN DIRECTO · ${event.label}")
            Spacer(Modifier.height(10.dp))
            Text(
                text = event.title,
                style = Type.Display.copy(fontSize = 30.sp, lineHeight = 34.sp),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChannelLogo(
                    url = event.channel.logo,
                    name = "",
                    showNameFallback = false,
                    modifier = Modifier
                        .size(width = 48.dp, height = 27.dp)
                        .clip(RoundedCornerShape(5.dp)),
                )
                Spacer(Modifier.width(10.dp))
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
            }
            if (event.start > 0 && event.stop > event.start) {
                Spacer(Modifier.height(8.dp))
                ProgressLine(
                    fraction = (System.currentTimeMillis() - event.start).toFloat() / (event.stop - event.start),
                    modifier = Modifier.fillMaxWidth(0.7f),
                )
            }
            val desc = nowNext?.nowDescription
            if (!desc.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(desc, style = Type.Caption, color = Color.White.copy(alpha = 0.6f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            nowNext?.nextTitle?.let { next ->
                Spacer(Modifier.height(6.dp))
                Text(
                    "Después  ${Fmt.clock(nowNext?.nextStart ?: 0)}  ·  $next",
                    style = Type.Caption,
                    color = Color.White.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier
                    .focusRestorer()
                    .focusGroup(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    onClick = onPlay,
                    modifier = Modifier
                        .height(42.dp)
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
                            .padding(horizontal = 22.dp),
                    )
                }
                if (count > 1) {
                    Spacer(Modifier.width(12.dp))
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
                    .padding(end = 22.dp, bottom = 20.dp),
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
private fun ArrowButton(text: String, size: Dp = 42.dp, onClick: () -> Unit) {
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
        modifier = modifier.size(width = 176.dp, height = 104.dp),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Palette.Focus), shape = shape),
        ),
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
