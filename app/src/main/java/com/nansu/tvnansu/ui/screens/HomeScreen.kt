package com.nansu.tvnansu.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nansu.tvnansu.data.LiveEvent
import com.nansu.tvnansu.data.db.ChannelEntity
import com.nansu.tvnansu.data.db.FavoriteEntity
import com.nansu.tvnansu.data.db.Kind
import com.nansu.tvnansu.data.db.MovieEntity
import com.nansu.tvnansu.data.db.SeriesEntity
import com.nansu.tvnansu.ui.Fmt
import com.nansu.tvnansu.ui.LocalEdge
import com.nansu.tvnansu.ui.LocalRepository
import com.nansu.tvnansu.ui.LocalTopInset
import com.nansu.tvnansu.ui.RevealScroll
import com.nansu.tvnansu.ui.keepAtTop
import com.nansu.tvnansu.ui.components.Skeleton
import com.nansu.tvnansu.ui.components.SkeletonShelf
import kotlinx.coroutines.launch
import com.nansu.tvnansu.ui.components.CaptureImage
import com.nansu.tvnansu.ui.components.ChannelArt
import com.nansu.tvnansu.ui.components.ChannelLogo
import com.nansu.tvnansu.ui.components.CoverBox
import com.nansu.tvnansu.ui.components.EmptyState
import com.nansu.tvnansu.ui.components.EventCard
import com.nansu.tvnansu.ui.components.HeroData
import com.nansu.tvnansu.ui.components.LiveChip
import com.nansu.tvnansu.ui.components.LiveNowRow
import com.nansu.tvnansu.ui.components.LivePreview
import com.nansu.tvnansu.ui.components.MediaCard
import com.nansu.tvnansu.ui.components.PillButton
import com.nansu.tvnansu.ui.components.PillShape
import com.nansu.tvnansu.ui.components.Shelf
import com.nansu.tvnansu.ui.components.cleanTitle
import com.nansu.tvnansu.ui.components.rememberCapture
import com.nansu.tvnansu.ui.components.themeTint
import com.nansu.tvnansu.ui.focusMemory
import com.nansu.tvnansu.ui.rememberActions
import com.nansu.tvnansu.ui.rememberFocusMemory
import com.nansu.tvnansu.ui.theme.Palette
import com.nansu.tvnansu.ui.theme.Type
import kotlinx.coroutines.delay

fun movieHero(m: MovieEntity) = HeroData(
    title = cleanTitle(m.name),
    image = m.backdrop ?: m.poster,
    wide = m.backdrop != null,
    meta = metaLine(m.year, m.rating, null),
    description = m.plot,
    eyebrow = "PELÍCULA",
)

fun seriesHero(s: SeriesEntity) = HeroData(
    title = cleanTitle(s.name),
    image = s.backdrop ?: s.poster,
    wide = s.backdrop != null,
    meta = metaLine(s.year, s.rating, s.genre),
    description = s.plot,
    eyebrow = "SERIE",
)

fun metaLine(year: String?, rating: Float, genre: String?): String =
    listOfNotNull(
        year?.takeIf { it.isNotBlank() },
        if (rating > 0f) "★ " + String.format("%.1f", rating) else null,
        genre?.takeIf { it.isNotBlank() },
    ).joinToString("  ·  ")

/** One page of the featured block: a live broadcast, a movie or a series. */
private data class Slide(
    val key: String,
    val kind: Kind,
    val remoteId: String,
    val title: String,
    val eyebrow: String,
    val meta: String?,
    val description: String?,
    val image: String?,
    val wide: Boolean,
    val poster: String?,
    val event: LiveEvent? = null,
)

private fun movieSlide(m: MovieEntity) = Slide(
    "m:" + m.remoteId, Kind.MOVIE, m.remoteId, cleanTitle(m.name), "PELÍCULA",
    metaLine(m.year, m.rating, null), m.plot, m.backdrop ?: m.poster, m.backdrop != null, m.poster,
)

private fun seriesSlide(s: SeriesEntity) = Slide(
    "s:" + s.remoteId, Kind.SERIES, s.remoteId, cleanTitle(s.name), "SERIE",
    metaLine(s.year, s.rating, s.genre), s.plot, s.backdrop ?: s.poster, s.backdrop != null, s.poster,
)

private fun eventSlide(e: LiveEvent) = Slide(
    "e:" + e.channel.remoteId, Kind.LIVE, e.channel.remoteId, e.title, "EN DIRECTO · ${e.label}",
    com.nansu.tvnansu.ui.components.eventSubtitle(e),
    null, null, false, null, e,
)

@Composable
fun HomeScreen() {
    val repo = LocalRepository.current
    val actions = rememberActions()
    val memory = rememberFocusMemory()
    val pid by repo.activeId.collectAsState()
    val version by repo.dataVersion.collectAsState()
    val edge = LocalEdge.current
    val reduceMotion = repo.prefs.reduceMotion
    val previewOn = repo.prefs.livePreview
    val headerHidden = LocalHeaderHidden.current
    val scope = rememberCoroutineScope()

    // Everything below starts from what was shown last time (kept in memory), so
    // coming back to Inicio is instant; fresh data replaces it when it arrives.
    val continueList by remember(pid) { repo.continueWatching(pid) }
        .collectAsState(initial = repo.peek("cw:$pid") ?: emptyList())
    val favorites by remember(pid) { repo.favorites(pid) }
        .collectAsState(initial = repo.peek("fav:$pid") ?: emptyList())
    val recentRefs by remember(pid) { repo.recentChannels(pid) }.collectAsState(initial = emptyList())
    LaunchedEffect(continueList) { repo.store("cw:$pid", continueList) }
    LaunchedEffect(favorites) { repo.store("fav:$pid", favorites) }

    val recentChannels by produceState(repo.peek<List<ChannelEntity>>("home:recent:$pid") ?: emptyList(), pid, recentRefs, version) {
        if (recentRefs.isEmpty() && value.isNotEmpty()) return@produceState
        value = repo.channelsByIds(pid, recentRefs.map { it.remoteId }.distinct())
        repo.store("home:recent:$pid", value)
    }
    val favoriteChannels by produceState(emptyList<ChannelEntity>(), pid, favorites, version) {
        value = repo.channelsByIds(pid, favorites.filter { it.kind == Kind.LIVE.name }.map { it.remoteId }.distinct())
    }
    val moviesOrNull by produceState(repo.peek<List<MovieEntity>>(repo.memoKey("recm", pid)), pid, version) {
        value = repo.cached(repo.memoKey("recm", pid)) { repo.recommendedMovies(pid) }
    }
    val seriesOrNull by produceState(repo.peek<List<SeriesEntity>>(repo.memoKey("recs", pid)), pid, version) {
        value = repo.cached(repo.memoKey("recs", pid)) { repo.recommendedSeries(pid) }
    }
    val movies = moviesOrNull ?: emptyList()
    val series = seriesOrNull ?: emptyList()
    val myList = favorites.filter { it.kind == Kind.MOVIE.name || it.kind == Kind.SERIES.name }

    // Live sports, events and news from the guide (shared with En vivo, refreshed every minute).
    val epgVersion by repo.epgVersion.collectAsState()
    var minuteTick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            minuteTick++
        }
    }
    val liveEvents by produceState(repo.peek<List<LiveEvent>>(repo.memoKey("live", pid)) ?: emptyList(), pid, version, epgVersion, minuteTick) {
        value = repo.liveNowShared(pid)
    }
    val sports = liveEvents.filter { it.label != "NOTICIAS" }
    val news = liveEvents.filter { it.label == "NOTICIAS" }

    // Featured block: the top live event first, then movies and series (wide artwork first).
    var backdropTick by remember { mutableIntStateOf(0) }
    val leadEvent = sports.firstOrNull() ?: news.firstOrNull()
    val secondEvent = (if (sports.size > 1) sports[1] else news.firstOrNull())?.takeIf { it != leadEvent }
    val slidesKey = repo.memoKey("slides", pid)
    val slides by produceState(repo.peek<List<Slide>>(slidesKey) ?: emptyList(), pid, version, backdropTick, leadEvent?.channel?.remoteId, secondEvent?.channel?.remoteId) {
        val ms = repo.dao.recentMovies(pid, 6).distinctBy { it.remoteId }
        val ss = repo.dao.recentSeries(pid, 6).distinctBy { it.remoteId }
        val vod = ArrayList<Slide>()
        for (i in 0 until maxOf(ms.size, ss.size)) {
            ms.getOrNull(i)?.let { vod += movieSlide(it) }
            ss.getOrNull(i)?.let { vod += seriesSlide(it) }
        }
        val sorted = vod.sortedByDescending { it.wide }.take(6).toMutableList()
        leadEvent?.let { sorted.add(0, eventSlide(it)) }
        secondEvent?.let { sorted.add(minOf(3, sorted.size), eventSlide(it)) }
        value = sorted
        repo.store(slidesKey, sorted)
        if (backdropTick == 0 && repo.prefetchBackdrops(ms.take(6))) backdropTick++
    }
    var slideIndex by rememberSaveable { mutableIntStateOf(0) }
    var pinned by remember { mutableStateOf<Slide?>(null) }
    LaunchedEffect(slideIndex, slides, pinned, reduceMotion) {
        if (pinned != null || slides.size < 2 || reduceMotion) return@LaunchedEffect
        val current = slides[slideIndex % slides.size]
        delay(if (current.event != null) 20_000 else 8_000)
        slideIndex = (slideIndex + 1) % slides.size
    }

    val loading = moviesOrNull == null || seriesOrNull == null
    val nothing = continueList.isEmpty() && recentChannels.isEmpty() && movies.isEmpty() &&
        series.isEmpty() && myList.isEmpty() && favoriteChannels.isEmpty() && liveEvents.isEmpty()
    if (nothing && !loading) {
        EmptyState(
            title = "Todavía no hay nada aquí",
            message = "Explora En vivo, Películas o Series. Lo que veas aparecerá en Inicio.",
        )
        return
    }

    fun openSlide(s: Slide) {
        when (s.kind) {
            Kind.LIVE -> {
                val ev = s.event ?: return
                val list = liveEvents.map { it.channel }.ifEmpty { listOf(ev.channel) }
                actions.playChannels(list, list.indexOfFirst { it.remoteId == ev.channel.remoteId }.coerceAtLeast(0))
            }
            Kind.SERIES -> actions.openSeries(s.remoteId)
            else -> actions.openMovie(s.remoteId)
        }
    }

    val listState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 24 } }
    LaunchedEffect(scrolled) { headerHidden.value = scrolled }

    RevealScroll(top = 40.dp) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = LocalTopInset.current, bottom = 72.dp),
        ) {
            item(key = "bento") {
                val shown = pinned ?: slides.getOrNull(slideIndex % slides.size.coerceAtLeast(1))
                Row(
                    Modifier
                        .padding(start = edge, end = 36.dp, bottom = 20.dp)
                        .fillMaxWidth()
                        .height(256.dp)
                        // The whole block always comes fully into view.
                        .keepAtTop(listState)
                ) {
                    if (shown == null && slides.isEmpty()) {
                        Skeleton(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            RoundedCornerShape(20.dp),
                        )
                    } else {
                        FeaturedBlock(
                            slide = shown,
                            index = slideIndex % slides.size.coerceAtLeast(1),
                            count = slides.size,
                            pinned = pinned != null,
                            reduceMotion = reduceMotion,
                            previewOn = previewOn,
                            onMove = { d -> if (slides.isNotEmpty()) slideIndex = (slideIndex + d + slides.size) % slides.size },
                            onOpen = { shown?.let { openSlide(it) } },
                            onLongClick = {
                                val s = shown ?: return@FeaturedBlock
                                if (s.kind != Kind.LIVE) actions.toggleFavorite(s.kind, s.remoteId, s.title, s.poster)
                            },
                            memory = memory,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    LiveNowPanel(
                        events = liveEvents.take(4),
                        onFocusEvent = { pinned = eventSlide(it) },
                        onPanelFocus = { has -> if (!has) pinned = null },
                        onPlay = { ev ->
                            val list = liveEvents.map { it.channel }
                            actions.playChannels(list, liveEvents.indexOf(ev))
                        },
                        memory = memory,
                        modifier = Modifier
                            .width(266.dp)
                            .fillMaxHeight(),
                    )
                }
            }
            if (loading && continueList.isEmpty()) {
                item(key = "sk1") { SkeletonShelf() }
                item(key = "sk2") { SkeletonShelf() }
            }
            if (continueList.isNotEmpty()) {
                item(key = "continue") {
                    Shelf("Seguir viendo", modifier = Modifier.animateItem()) {
                        items(continueList, key = { it.uid }) { p ->
                            val fraction = if (p.durationMs > 0) p.positionMs.toFloat() / p.durationMs else 0f
                            MediaCard(
                                title = p.title,
                                image = p.image,
                                subtitle = if (p.kind == Kind.SERIES.name) p.subtitle
                                else Fmt.minutesLeft(p.durationMs - p.positionMs),
                                progress = fraction,
                                onClick = { actions.openProgress(p) },
                                onLongClick = { actions.removeFromContinue(p) },
                                modifier = Modifier.focusMemory("continue:${p.uid}", memory),
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
                                width = 188.dp,
                                showAge = true,
                                onClick = { actions.playChannels(recentChannels, recentChannels.indexOf(c)) },
                                onLongClick = { actions.toggleFavorite(Kind.LIVE, c.remoteId, c.name, c.logo) },
                                modifier = Modifier.focusMemory("recent:${c.remoteId}", memory),
                            )
                        }
                    }
                }
            }
            if (movies.isNotEmpty()) {
                item(key = "movies") {
                    Shelf("Películas recomendadas", modifier = Modifier.animateItem()) {
                        items(movies, key = { "m:" + it.remoteId }) { m ->
                            MediaCard(
                                title = cleanTitle(m.name),
                                image = m.poster,
                                rating = m.rating,
                                onClick = { actions.openMovie(m) },
                                onLongClick = { actions.toggleFavorite(Kind.MOVIE, m.remoteId, m.name, m.poster) },
                                modifier = Modifier.focusMemory("movie:${m.remoteId}", memory),
                            )
                        }
                    }
                }
            }
            if (sports.isNotEmpty()) {
                item(key = "sports") {
                    Shelf("Deportes y eventos en directo", live = true, modifier = Modifier.animateItem()) {
                        items(sports, key = { "sp:" + it.channel.remoteId }) { ev ->
                            EventCard(
                                event = ev,
                                onClick = { actions.playChannels(sports.map { it.channel }, sports.indexOf(ev)) },
                                modifier = Modifier.focusMemory("sp:${ev.channel.remoteId}", memory),
                            )
                        }
                    }
                }
            }
            if (series.isNotEmpty()) {
                item(key = "series") {
                    Shelf("Series recomendadas", modifier = Modifier.animateItem()) {
                        items(series, key = { "s:" + it.remoteId }) { s ->
                            MediaCard(
                                title = cleanTitle(s.name),
                                image = s.poster,
                                rating = s.rating,
                                onClick = { actions.openSeries(s) },
                                onLongClick = { actions.toggleFavorite(Kind.SERIES, s.remoteId, s.name, s.poster) },
                                modifier = Modifier.focusMemory("series:${s.remoteId}", memory),
                            )
                        }
                    }
                }
            }
            if (news.isNotEmpty()) {
                item(key = "news") {
                    Shelf("Noticias en directo", live = true, modifier = Modifier.animateItem()) {
                        items(news, key = { "nw:" + it.channel.remoteId }) { ev ->
                            EventCard(
                                event = ev,
                                onClick = { actions.playChannels(news.map { it.channel }, news.indexOf(ev)) },
                                modifier = Modifier.focusMemory("nw:${ev.channel.remoteId}", memory),
                            )
                        }
                    }
                }
            }
            if (myList.isNotEmpty()) {
                item(key = "mylist") {
                    Shelf("Mi lista", modifier = Modifier.animateItem()) {
                        items(myList, key = { it.uid }) { f -> FavoriteCard(f, memory) }
                    }
                }
            }
            if (favoriteChannels.isNotEmpty()) {
                item(key = "favchannels") {
                    Shelf("Canales favoritos", live = true, modifier = Modifier.animateItem()) {
                        items(favoriteChannels, key = { "f:" + it.remoteId }) { c ->
                            ChannelCard(
                                c = c,
                                onClick = { actions.playChannels(favoriteChannels, favoriteChannels.indexOf(c)) },
                                onLongClick = { actions.toggleFavorite(Kind.LIVE, c.remoteId, c.name, c.logo) },
                                modifier = Modifier.focusMemory("favch:${c.remoteId}", memory),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- featured

@Composable
private fun FeaturedBlock(
    slide: Slide?,
    index: Int,
    count: Int,
    pinned: Boolean,
    reduceMotion: Boolean,
    previewOn: Boolean,
    onMove: (Int) -> Unit,
    onOpen: () -> Unit,
    onLongClick: () -> Unit,
    memory: MutableState<String?>,
    modifier: Modifier = Modifier,
) {
    var hasFocus by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(20.dp)
    val borderAlpha by androidx.compose.animation.core.animateFloatAsState(if (hasFocus) 0.9f else 0.06f, label = "fb")
    Box(
        modifier
            .clip(shape)
            .background(Palette.Surface)
            .border(BorderStroke(if (hasFocus) 1.5.dp else 1.dp, Color.White.copy(alpha = borderAlpha)), shape)
            .onFocusChanged { hasFocus = it.hasFocus }
    ) {
        AnimatedContent(
            targetState = slide,
            transitionSpec = {
                val d = if (reduceMotion) 0 else 700
                fadeIn(tween(d)) togetherWith fadeOut(tween(d))
            },
            contentKey = { it?.key },
            label = "slide",
        ) { s ->
            if (s != null) SlideArt(s, reduceMotion, previewOn)
        }
        // Readability: dark on the left and bottom, picture shows on the right.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.86f),
                        0.38f to Color.Black.copy(alpha = 0.62f),
                        0.62f to Color.Black.copy(alpha = 0.18f),
                        0.8f to Color.Transparent,
                    )
                )
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f)))
        )
        if (slide == null) return@Box
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(0.72f)
                .padding(start = 24.dp, bottom = 20.dp)
        ) {
            if (slide.event != null) {
                LiveChip(slide.eyebrow)
            } else {
                Text(slide.eyebrow, style = Type.Label.copy(fontWeight = FontWeight.SemiBold), color = Palette.Accent)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = slide.title,
                style = Type.Display.copy(fontSize = 26.sp, lineHeight = 30.sp),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!slide.meta.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(slide.meta, style = Type.Label, color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!slide.description.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    slide.description,
                    style = Type.Caption,
                    color = Color.White.copy(alpha = 0.65f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier
                    .focusRestorer()
                    .focusGroup(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.tv.material3.Surface(
                    onClick = onOpen,
                    onLongClick = onLongClick,
                    modifier = Modifier
                        .height(38.dp)
                        .focusMemory("featured", memory),
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
                        text = when (slide.kind) {
                            Kind.LIVE -> "▶   Ver en directo"
                            Kind.SERIES -> "▶   Ver serie"
                            else -> "▶   Ver película"
                        },
                        style = Type.Section,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 22.dp),
                    )
                }
                if (count > 1 && !pinned) {
                    Spacer(Modifier.width(18.dp))
                    RoundButton("‹", onClick = { onMove(-1) })
                    Spacer(Modifier.width(8.dp))
                    RoundButton("›", onClick = { onMove(1) })
                }
            }
        }
        if (count > 1 && !pinned) {
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = 22.dp),
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
private fun RoundButton(text: String, onClick: () -> Unit, size: Dp = 38.dp) {
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

/** Artwork of a slide: live preview for broadcasts, slow zoom for wide images. */
@Composable
private fun SlideArt(s: Slide, reduceMotion: Boolean, previewOn: Boolean) {
    val repo = LocalRepository.current
    val transition = rememberInfiniteTransition(label = "kb")
    val zoom by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (reduceMotion) 1f else 1.07f,
        animationSpec = infiniteRepeatable(tween(16_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "zoom",
    )
    Box(Modifier.fillMaxSize()) {
        val ev = s.event
        when {
            ev != null -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.linearGradient(listOf(Palette.Surface, themeTint(ev.label))))
                )
                // The picture fills the whole box (cropped, never stretched).
                CoverBox(16f / 9f, Modifier.fillMaxSize()) {
                    LivePreview(
                        url = repo.liveUrl(ev.channel),
                        enabled = previewOn,
                        channel = ev.channel,
                        modifier = Modifier.fillMaxSize(),
                    ) { LivePlaceholder(ev.channel) }
                }
            }
            s.wide -> AsyncImage(
                model = s.image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                    },
            )
            else -> {
                AsyncImage(
                    model = s.image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = 0.55f }
                        .blur(30.dp),
                )
                AsyncImage(
                    model = s.poster ?: s.image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 26.dp, top = 22.dp, bottom = 22.dp)
                        .fillMaxHeight()
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(12.dp)),
                )
            }
        }
    }
}

/** Under the preview while it loads: last capture of the channel, else its logo. */
@Composable
private fun LivePlaceholder(channel: ChannelEntity) {
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
                    .padding(start = 180.dp)
                    .size(width = 160.dp, height = 90.dp)
                    .clip(RoundedCornerShape(14.dp)),
            )
        }
    }
}

// ---------------------------------------------------------------------------- live now panel

@Composable
private fun LiveNowPanel(
    events: List<LiveEvent>,
    onFocusEvent: (LiveEvent) -> Unit,
    onPanelFocus: (Boolean) -> Unit,
    onPlay: (LiveEvent) -> Unit,
    memory: MutableState<String?>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Palette.Surface)
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)), RoundedCornerShape(20.dp))
            .padding(horizontal = 8.dp, vertical = 12.dp)
            .onFocusChanged { onPanelFocus(it.hasFocus) }
            .focusRestorer()
            .focusGroup()
    ) {
        Row(Modifier.padding(start = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            com.nansu.tvnansu.ui.components.LiveDot()
            Spacer(Modifier.width(8.dp))
            Text("En directo ahora", style = Type.Section, color = Palette.Text)
        }
        if (events.isEmpty()) {
            Text(
                "Cuando la guía tenga partidos, eventos o informativos en emisión aparecerán aquí.",
                style = Type.Caption,
                color = Palette.TextFaint,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        events.forEach { ev ->
            LiveNowRow(
                event = ev,
                onClick = { onPlay(ev) },
                onFocused = { onFocusEvent(ev) },
                modifier = Modifier.focusMemory("now:${ev.channel.remoteId}", memory),
            )
        }
    }
}

// ---------------------------------------------------------------------------- cards

@Composable
fun ChannelCard(
    c: ChannelEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onFocused: () -> Unit = {},
    width: Dp = 176.dp,
    showAge: Boolean = false,
) {
    MediaCard(
        title = c.name,
        image = c.logo,
        width = width,
        aspectRatio = 16f / 9f,
        art = { ChannelArt(channel = c, modifier = Modifier.fillMaxSize(), showAge = showAge) },
        onClick = onClick,
        onLongClick = onLongClick,
        onFocused = onFocused,
        modifier = modifier,
    )
}

@Composable
private fun FavoriteCard(f: FavoriteEntity, memory: MutableState<String?>) {
    val actions = rememberActions()
    val isSeries = f.kind == Kind.SERIES.name
    MediaCard(
        title = f.title,
        image = f.image,
        width = com.nansu.tvnansu.ui.components.POSTER_W,
        onClick = { if (isSeries) actions.openSeries(f.remoteId) else actions.openMovie(f.remoteId) },
        onLongClick = {
            actions.toggleFavorite(if (isSeries) Kind.SERIES else Kind.MOVIE, f.remoteId, f.title, f.image)
        },
        modifier = Modifier.focusMemory("fav:${f.uid}", memory),
    )
}
