package com.nansu.tvnansu.ui.screens

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
import com.nansu.tvnansu.ui.components.rememberHeroState
import com.nansu.tvnansu.ui.focusMemory
import com.nansu.tvnansu.ui.rememberActions
import com.nansu.tvnansu.ui.rememberFocusMemory

fun movieHero(m: MovieEntity) = HeroData(
    title = m.name,
    image = m.backdrop ?: m.poster,
    wide = m.backdrop != null,
    meta = metaLine(m.year, m.rating, null),
    description = m.plot,
    eyebrow = "PELÍCULA",
)

fun seriesHero(s: SeriesEntity) = HeroData(
    title = s.name,
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

    val hero = rememberHeroState()
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
        newSeries.isEmpty() && myList.isEmpty() && favoriteChannels.isEmpty()
    if (nothing && newMoviesOrNull != null && newSeriesOrNull != null) {
        EmptyState(
            title = "Todavía no hay nada aquí",
            message = "Explora En vivo, Películas o Series. Lo que veas aparecerá en Inicio.",
        )
        return
    }

    fun focusChannel(c: ChannelEntity) {
        hero.target = HeroData(title = c.name, image = c.logo, wide = false, eyebrow = "EN DIRECTO")
        heroChannel = c
    }

    HeroScaffold(hero = hero.shown, listState = rememberLazyListState()) {
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
                                hero.target = HeroData(
                                    title = p.title, image = p.image, wide = false,
                                    meta = p.subtitle, eyebrow = "SEGUIR VIENDO",
                                )
                            },
                            modifier = Modifier.focusMemory("continue:${p.uid}", memory),
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
                        FavoriteCard(f, memory, onFocusedHero = { hero.target = it })
                    }
                }
            }
        }
        if (newMovies.isNotEmpty()) {
            item(key = "newmovies") {
                Shelf("Películas añadidas recientemente") {
                    items(newMovies, key = { it.remoteId }) { m ->
                        MediaCard(
                            title = m.name,
                            image = m.poster,
                            width = 120.dp,
                            onClick = { actions.openMovie(m) },
                            onLongClick = { actions.toggleFavorite(Kind.MOVIE, m.remoteId, m.name, m.poster) },
                            onFocused = { hero.target = movieHero(m) },
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
                            title = s.name,
                            image = s.poster,
                            width = 120.dp,
                            onClick = { actions.openSeries(s) },
                            onLongClick = { actions.toggleFavorite(Kind.SERIES, s.remoteId, s.name, s.poster) },
                            onFocused = { hero.target = seriesHero(s) },
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
