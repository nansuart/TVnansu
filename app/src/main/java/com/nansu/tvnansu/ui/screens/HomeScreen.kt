package com.nansu.tvnansu.ui.screens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nansu.tvnansu.data.LiveEvent
import com.nansu.tvnansu.ui.components.LiveDot
import com.nansu.tvnansu.ui.components.PillShape
import com.nansu.tvnansu.ui.components.ProgressLine
import com.nansu.tvnansu.ui.theme.Palette
import com.nansu.tvnansu.ui.theme.Type
import kotlinx.coroutines.delay
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.nansu.tvnansu.data.db.ChannelEntity
import com.nansu.tvnansu.data.db.FavoriteEntity
import com.nansu.tvnansu.data.db.Kind
import com.nansu.tvnansu.data.db.MovieEntity
import com.nansu.tvnansu.data.db.SeriesEntity
import com.nansu.tvnansu.ui.Fmt
import com.nansu.tvnansu.ui.LocalRepository
import com.nansu.tvnansu.ui.components.EmptyState
import com.nansu.tvnansu.ui.components.HeroData
import com.nansu.tvnansu.ui.components.HeroScaffold
import com.nansu.tvnansu.ui.components.MediaCard
import com.nansu.tvnansu.ui.components.Shelf
import com.nansu.tvnansu.ui.components.cleanTitle
import com.nansu.tvnansu.ui.components.rememberHeroState
import com.nansu.tvnansu.ui.focusMemory
import com.nansu.tvnansu.ui.rememberActions
import com.nansu.tvnansu.ui.rememberFocusMemory

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

@Composable
fun HomeScreen() {
    val repo = LocalRepository.current
    val actions = rememberActions()
    val memory = rememberFocusMemory()
    val pid by repo.activeId.collectAsState()
    val version by repo.dataVersion.collectAsState()

    val continueList by remember(pid) { repo.continueWatching(pid) }.collectAsState(initial = emptyList())
    val favorites by remember(pid) { repo.favorites(pid) }.collectAsState(initial = emptyList())
    val recentRefs by remember(pid) { repo.recentChannels(pid) }.collectAsState(initial = emptyList())

    val recentChannels by produceState(emptyList<ChannelEntity>(), pid, recentRefs, version) {
        value = repo.channelsByIds(pid, recentRefs.map { it.remoteId }.distinct())
    }
    val favoriteChannels by produceState(emptyList<ChannelEntity>(), pid, favorites, version) {
        value = repo.channelsByIds(pid, favorites.filter { it.kind == Kind.LIVE.name }.map { it.remoteId }.distinct())
    }
    val newMoviesOrNull by produceState<List<MovieEntity>?>(null, pid, version) {
        value = repo.dao.recentMovies(pid, 30).distinctBy { it.remoteId }
    }
    val newSeriesOrNull by produceState<List<SeriesEntity>?>(null, pid, version) {
        value = repo.dao.recentSeries(pid, 30).distinctBy { it.remoteId }
    }
    val newMovies = newMoviesOrNull ?: emptyList()
    val newSeries = newSeriesOrNull ?: emptyList()
    val myList = favorites.filter { it.kind == Kind.MOVIE.name || it.kind == Kind.SERIES.name }

    // Live sports, events and news from the guide, refreshed every minute.
    val epgVersion by repo.epgVersion.collectAsState()
    var minuteTick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            minuteTick++
        }
    }
    val liveEvents by produceState(emptyList<LiveEvent>(), pid, version, epgVersion, minuteTick) {
        value = repo.liveNow(pid)
    }

    // Featured carousel: newest movies and series, those with wide artwork first.
    var backdropTick by remember { mutableIntStateOf(0) }
    val featured by produceState(emptyList<FeaturedItem>(), pid, version, backdropTick) {
        val movies = repo.dao.recentMovies(pid, 6).distinctBy { it.remoteId }
        val series = repo.dao.recentSeries(pid, 6).distinctBy { it.remoteId }
        val mixed = ArrayList<FeaturedItem>()
        for (i in 0 until maxOf(movies.size, series.size)) {
            movies.getOrNull(i)?.let { mixed += FeaturedItem(Kind.MOVIE, it.remoteId, movieHero(it).copy(eyebrow = "DESTACADO · PELÍCULA"), it.poster, it.rating) }
            series.getOrNull(i)?.let { mixed += FeaturedItem(Kind.SERIES, it.remoteId, seriesHero(it).copy(eyebrow = "DESTACADO · SERIE"), it.poster, it.rating) }
        }
        value = mixed.sortedByDescending { it.hero.wide }.take(8)
        if (backdropTick == 0 && repo.prefetchBackdrops(movies.take(6))) backdropTick++
    }
    var featuredIndex by rememberSaveable { mutableIntStateOf(0) }
    var featuredActive by remember { mutableStateOf(true) }
    val reduceMotion = repo.prefs.reduceMotion

    val hero = rememberHeroState()
    LaunchedEffect(featuredIndex, featuredActive, featured) {
        if (!featuredActive || featured.isEmpty()) return@LaunchedEffect
        val i = featuredIndex % featured.size
        hero.target = featured[i].hero
        if (!reduceMotion && featured.size > 1) {
            delay(8_000)
            featuredIndex = (i + 1) % featured.size
        }
    }
    var heroChannel by remember { mutableStateOf<ChannelEntity?>(null) }
    LaunchedEffect(heroChannel) {
        val c = heroChannel ?: return@LaunchedEffect
        val nn = repo.nowNext(c)
        if (hero.target?.title == c.name && nn?.nowTitle != null) {
            hero.target = hero.target?.copy(
                meta = "Ahora: ${nn.nowTitle}  ·  ${Fmt.clock(nn.nowStart)} – ${Fmt.clock(nn.nowStop)}",
                description = nn.nowDescription,
            )
        }
    }

    // Default hero: first available item.
    LaunchedEffect(newMovies, newSeries, continueList) {
        if (hero.target == null) {
            hero.target = newMovies.firstOrNull()?.let { movieHero(it) }
                ?: newSeries.firstOrNull()?.let { seriesHero(it) }
        }
    }

    val nothing = continueList.isEmpty() && recentChannels.isEmpty() && newMovies.isEmpty() &&
        newSeries.isEmpty() && myList.isEmpty() && favoriteChannels.isEmpty() && liveEvents.isEmpty()
    if (nothing && newMoviesOrNull != null && newSeriesOrNull != null) {
        EmptyState(
            title = "Todavía no hay nada aquí",
            message = "Explora En vivo, Películas o Series. Lo que veas aparecerá en Inicio.",
        )
        return
    }

    fun focusChannel(c: ChannelEntity) {
        featuredActive = false
        hero.target = HeroData(title = c.name, image = c.logo, wide = false, eyebrow = "EN DIRECTO")
        heroChannel = c
    }

    fun focusHero(data: HeroData) {
        featuredActive = false
        hero.target = data
    }

    HeroScaffold(hero = hero.shown, listState = rememberLazyListState()) {
        if (featured.isNotEmpty()) {
            item(key = "featured") {
                val current = featured[featuredIndex % featured.size]
                FeaturedControls(
                    item = current,
                    index = featuredIndex % featured.size,
                    count = featured.size,
                    onMove = { delta -> featuredIndex = (featuredIndex + delta + featured.size) % featured.size },
                    onOpen = {
                        if (current.kind == Kind.SERIES) actions.openSeries(current.remoteId)
                        else actions.openMovie(current.remoteId)
                    },
                    onLongClick = { actions.toggleFavorite(current.kind, current.remoteId, current.hero.title, current.poster) },
                    onFocused = { featuredActive = true },
                    modifier = Modifier.focusMemory("featured", memory),
                )
            }
        }
        if (continueList.isNotEmpty()) {
            item(key = "continue") {
                Shelf("Seguir viendo") {
                    items(continueList, key = { it.uid }) { p ->
                        val fraction = if (p.durationMs > 0) p.positionMs.toFloat() / p.durationMs else 0f
                        MediaCard(
                            title = p.title,
                            image = p.image,
                            subtitle = if (p.kind == Kind.SERIES.name) p.subtitle
                            else Fmt.minutesLeft(p.durationMs - p.positionMs),
                            progress = fraction,
                            width = 120.dp,
                            onClick = { actions.openProgress(p) },
                            onLongClick = { actions.removeFromContinue(p) },
                            onFocused = {
                                focusHero(
                                    HeroData(
                                        title = p.title, image = p.image, wide = false,
                                        meta = p.subtitle, eyebrow = "SEGUIR VIENDO",
                                    )
                                )
                            },
                            modifier = Modifier.focusMemory("continue:${p.uid}", memory),
                        )
                    }
                }
            }
        }
        if (liveEvents.isNotEmpty()) {
            item(key = "livenow") {
                Shelf("En directo ahora") {
                    items(liveEvents, key = { "ev:" + it.channel.remoteId }) { ev ->
                        EventCard(
                            event = ev,
                            onClick = { actions.playChannels(liveEvents.map { it.channel }, liveEvents.indexOf(ev)) },
                            onFocused = {
                                focusHero(
                                    HeroData(
                                        title = ev.title,
                                        image = ev.channel.logo,
                                        wide = false,
                                        meta = listOfNotNull(
                                            ev.channel.name,
                                            if (ev.start > 0) "${Fmt.clock(ev.start)} – ${Fmt.clock(ev.stop)}" else null,
                                        ).joinToString("  ·  "),
                                        eyebrow = "EN DIRECTO",
                                    )
                                )
                            },
                            modifier = Modifier.focusMemory("ev:${ev.channel.remoteId}", memory),
                        )
                    }
                }
            }
        }
        if (recentChannels.isNotEmpty()) {
            item(key = "recent") {
                Shelf("Canales recientes") {
                    items(recentChannels, key = { it.remoteId }) { c ->
                        ChannelCard(
                            c = c,
                            onClick = { actions.playChannels(recentChannels, recentChannels.indexOf(c)) },
                            onLongClick = { actions.toggleFavorite(Kind.LIVE, c.remoteId, c.name, c.logo) },
                            onFocused = { focusChannel(c) },
                            modifier = Modifier.focusMemory("recent:${c.remoteId}", memory),
                        )
                    }
                }
            }
        }
        if (favoriteChannels.isNotEmpty()) {
            item(key = "favchannels") {
                Shelf("Canales favoritos") {
                    items(favoriteChannels, key = { it.remoteId }) { c ->
                        ChannelCard(
                            c = c,
                            onClick = { actions.playChannels(favoriteChannels, favoriteChannels.indexOf(c)) },
                            onLongClick = { actions.toggleFavorite(Kind.LIVE, c.remoteId, c.name, c.logo) },
                            onFocused = { focusChannel(c) },
                            modifier = Modifier.focusMemory("favch:${c.remoteId}", memory),
                        )
                    }
                }
            }
        }
        if (myList.isNotEmpty()) {
            item(key = "mylist") {
                Shelf("Mi lista") {
                    items(myList, key = { it.uid }) { f ->
                        FavoriteCard(f, memory, onFocusedHero = { focusHero(it) })
                    }
                }
            }
        }
        if (newMovies.isNotEmpty()) {
            item(key = "newmovies") {
                Shelf("Películas añadidas recientemente") {
                    items(newMovies, key = { it.remoteId }) { m ->
                        MediaCard(
                            title = cleanTitle(m.name),
                            image = m.poster,
                            width = 120.dp,
                            rating = m.rating,
                            onClick = { actions.openMovie(m) },
                            onLongClick = { actions.toggleFavorite(Kind.MOVIE, m.remoteId, m.name, m.poster) },
                            onFocused = { focusHero(movieHero(m)) },
                            modifier = Modifier.focusMemory("newm:${m.remoteId}", memory),
                        )
                    }
                }
            }
        }
        if (newSeries.isNotEmpty()) {
            item(key = "newseries") {
                Shelf("Series actualizadas") {
                    items(newSeries, key = { it.remoteId }) { s ->
                        MediaCard(
                            title = cleanTitle(s.name),
                            image = s.poster,
                            width = 120.dp,
                            rating = s.rating,
                            onClick = { actions.openSeries(s) },
                            onLongClick = { actions.toggleFavorite(Kind.SERIES, s.remoteId, s.name, s.poster) },
                            onFocused = { focusHero(seriesHero(s)) },
                            modifier = Modifier.focusMemory("news:${s.remoteId}", memory),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ChannelCard(
    c: ChannelEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onFocused: () -> Unit = {},
    width: androidx.compose.ui.unit.Dp = 176.dp,
) {
    MediaCard(
        title = c.name,
        image = c.logo,
        width = width,
        aspectRatio = 16f / 9f,
        imageScale = ContentScale.Fit,
        imagePadding = 18.dp,
        onClick = onClick,
        onLongClick = onLongClick,
        onFocused = onFocused,
        modifier = modifier,
    )
}

@Composable
private fun FavoriteCard(
    f: FavoriteEntity,
    memory: androidx.compose.runtime.MutableState<String?>,
    onFocusedHero: (HeroData) -> Unit,
) {
    val actions = rememberActions()
    val isSeries = f.kind == Kind.SERIES.name
    MediaCard(
        title = f.title,
        image = f.image,
        width = 120.dp,
        onClick = { if (isSeries) actions.openSeries(f.remoteId) else actions.openMovie(f.remoteId) },
        onLongClick = {
            actions.toggleFavorite(if (isSeries) Kind.SERIES else Kind.MOVIE, f.remoteId, f.title, f.image)
        },
        onFocused = {
            onFocusedHero(
                HeroData(
                    title = f.title, image = f.image, wide = false,
                    eyebrow = if (isSeries) "SERIE" else "PELÍCULA",
                )
            )
        },
        modifier = Modifier.focusMemory("fav:${f.uid}", memory),
    )
}


data class FeaturedItem(
    val kind: Kind,
    val remoteId: String,
    val hero: HeroData,
    val poster: String?,
    val rating: Float,
)

/** The carousel's control: one button (open), dots, and ◀ ▶ to change the featured title. */
@Composable
private fun FeaturedControls(
    item: FeaturedItem,
    index: Int,
    count: Int,
    onMove: (Int) -> Unit,
    onOpen: () -> Unit,
    onLongClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.padding(start = 48.dp, top = 4.dp, bottom = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            onClick = onOpen,
            onLongClick = onLongClick,
            modifier = modifier
                .height(44.dp)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused()
                }
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (ev.key) {
                        Key.DirectionRight -> { onMove(1); true }
                        Key.DirectionLeft -> if (index > 0) { onMove(-1); true } else false
                        else -> false
                    }
                },
            shape = ClickableSurfaceDefaults.shape(shape = PillShape),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Palette.Surface3,
                contentColor = Palette.Text,
                focusedContainerColor = Palette.Focus,
                focusedContentColor = Color.Black,
            ),
        ) {
            Text(
                text = if (item.kind == Kind.SERIES) "▶   Ver serie" else "▶   Ver película",
                style = Type.Section,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp),
            )
        }
        Spacer(Modifier.width(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(count) { i ->
                val active = i == index
                val w by animateDpAsState(if (active) 22.dp else 7.dp, label = "dot")
                Box(
                    Modifier
                        .size(width = w, height = 7.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (active) Palette.Accent else Color.White.copy(alpha = 0.35f))
                )
            }
        }
        if (focused) {
            Spacer(Modifier.width(16.dp))
            Text("◀ ▶ cambiar destacado", style = Type.Caption, color = Palette.TextDim)
        }
    }
}

/** A live sports match, event or news broadcast. */
@Composable
private fun EventCard(
    event: LiveEvent,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Surface(
        onClick = onClick,
        modifier = modifier
            .width(264.dp)
            .height(148.dp)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            },
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Palette.Focus), shape = shape),
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Palette.Surface,
            contentColor = Palette.Text,
            focusedContainerColor = Palette.Surface2,
            focusedContentColor = Palette.Text,
        ),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(width = 58.dp, height = 34.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Palette.Surface3),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!event.channel.logo.isNullOrBlank()) {
                        AsyncImage(
                            model = event.channel.logo,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(4.dp),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                LiveDot()
                Spacer(Modifier.width(6.dp))
                Text(event.label, style = Type.Caption, color = Palette.Accent)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = event.title,
                style = Type.Label,
                color = if (focused) Palette.Text else Palette.TextDim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = event.channel.name,
                style = Type.Caption,
                color = Palette.TextFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (event.start > 0 && event.stop > event.start) {
                Spacer(Modifier.height(6.dp))
                ProgressLine(
                    fraction = (System.currentTimeMillis() - event.start).toFloat() / (event.stop - event.start),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
