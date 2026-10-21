package com.hispanoiptv.tv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import com.hispanoiptv.tv.ui.LocalEdge
import com.hispanoiptv.tv.ui.components.Shelf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.hispanoiptv.tv.data.MovieDetails
import com.hispanoiptv.tv.data.SeriesDetails
import com.hispanoiptv.tv.data.db.EpisodeEntity
import com.hispanoiptv.tv.data.db.Kind
import com.hispanoiptv.tv.data.db.MovieEntity
import com.hispanoiptv.tv.data.db.ProgressEntity
import com.hispanoiptv.tv.data.db.SeriesEntity
import com.hispanoiptv.tv.ui.Fmt
import com.hispanoiptv.tv.ui.LocalRepository
import com.hispanoiptv.tv.ui.PivotScroll
import com.hispanoiptv.tv.ui.components.EmptyState
import com.hispanoiptv.tv.ui.components.HeroBackdrop
import com.hispanoiptv.tv.ui.components.LoadingView
import com.hispanoiptv.tv.ui.components.MediaCard
import com.hispanoiptv.tv.ui.components.PillButton
import com.hispanoiptv.tv.ui.components.TabPill
import com.hispanoiptv.tv.ui.components.cleanTitle
import com.hispanoiptv.tv.ui.focusMemory
import com.hispanoiptv.tv.ui.rememberActions
import com.hispanoiptv.tv.ui.rememberFocusMemory
import com.hispanoiptv.tv.ui.theme.Palette
import com.hispanoiptv.tv.ui.theme.Type
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------- Movie

@Composable
fun MovieDetailScreen(remoteId: String) {
    val repo = LocalRepository.current
    val actions = rememberActions()
    val pid by repo.activeId.collectAsState()
    val movie by produceState<MovieEntity?>(null, pid, remoteId) { value = repo.dao.movie(pid, remoteId) }
    val details by produceState<MovieDetails?>(null, movie) {
        val m = movie ?: return@produceState
        value = repo.movieDetails(m)
    }
    val favUids by remember(pid) { repo.favoriteUids(pid) }.collectAsState(initial = emptyList())
    val progress by produceState<ProgressEntity?>(null, pid, remoteId) {
        value = repo.progress(repo.uid(pid, Kind.MOVIE, remoteId))
    }
    val playRequester = remember { FocusRequester() }
    val memory = rememberFocusMemory()
    val similar by produceState(emptyList<MovieEntity>(), movie) {
        val mv = movie ?: return@produceState
        value = runCatching { repo.similarMovies(mv) }.getOrDefault(emptyList())
    }

    val m = movie
    if (m == null) {
        LoadingView()
        return
    }
    val d = details
    val isFav = repo.uid(pid, Kind.MOVIE, remoteId) in favUids
    val backdrop = d?.backdrop ?: m.backdrop

    LaunchedEffect(Unit) {
        delay(80)
        if (memory.value == null) runCatching { playRequester.requestFocus() }
    }

    Box(Modifier.fillMaxSize()) {
        HeroBackdrop(
            image = backdrop ?: d?.poster ?: m.poster,
            wide = backdrop != null,
            reduceMotion = repo.prefs.reduceMotion,
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
        Column(Modifier.padding(start = 56.dp, top = 72.dp, end = 360.dp)) {
            Text("PELÍCULA", style = Type.Label, color = Palette.Accent)
            Spacer(Modifier.height(10.dp))
            Text(cleanTitle(m.name), style = Type.Display, color = Palette.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.dp))
            val meta = listOfNotNull(
                metaLine(d?.year ?: m.year, d?.rating ?: m.rating, d?.genre).takeIf { it.isNotBlank() },
                d?.duration,
            ).joinToString("  ·  ")
            if (meta.isNotBlank()) Text(meta, style = Type.Label, color = Palette.TextDim)
            Spacer(Modifier.height(16.dp))
            val plot = d?.plot ?: m.plot
            if (!plot.isNullOrBlank()) {
                Text(plot, style = Type.Body, color = Palette.TextDim, maxLines = 5, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(12.dp))
            }
            if (!d?.director.isNullOrBlank()) {
                Text("Dirección: ${d?.director}", style = Type.Caption, color = Palette.TextFaint, maxLines = 1)
            }
            if (!d?.cast.isNullOrBlank()) {
                Text("Reparto: ${d?.cast}", style = Type.Caption, color = Palette.TextFaint, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val p = progress
                if (p != null && p.positionMs > 0) {
                    PillButton(
                        text = "Reanudar · ${Fmt.duration(p.positionMs)}",
                        leading = "▶",
                        primary = true,
                        onClick = { actions.playMovie(m, p.positionMs) },
                        modifier = Modifier
                            .focusRequester(playRequester)
                            .focusMemory("play", memory),
                    )
                    PillButton(
                        text = "Desde el principio",
                        onClick = { actions.playMovie(m, 0) },
                        modifier = Modifier.focusMemory("restart", memory),
                    )
                } else {
                    PillButton(
                        text = "Reproducir",
                        leading = "▶",
                        primary = true,
                        onClick = { actions.playMovie(m, 0) },
                        modifier = Modifier
                            .focusRequester(playRequester)
                            .focusMemory("play", memory),
                    )
                }
                PillButton(
                    text = if (isFav) "En Mi lista" else "Mi lista",
                    leading = if (isFav) "✓" else "+",
                    onClick = { actions.toggleFavorite(Kind.MOVIE, m.remoteId, m.name, m.poster) },
                    modifier = Modifier.focusMemory("fav", memory),
                )
            }
        }
        if (similar.isNotEmpty()) {
            Spacer(Modifier.height(36.dp))
            CompositionLocalProvider(LocalEdge provides 56.dp) {
                Shelf("Más como esto") {
                    items(similar, key = { "sim:" + it.remoteId }) { x ->
                        MediaCard(
                            title = cleanTitle(x.name),
                            subtitle = x.year?.take(4),
                            image = x.poster,
                            rating = x.rating,
                            onClick = { actions.openMovie(x) },
                            onLongClick = { actions.toggleFavorite(Kind.MOVIE, x.remoteId, x.name, x.poster) },
                            modifier = Modifier.focusMemory("sim:${x.remoteId}", memory),
                        )
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
        }
        }
    }
}

// ---------------------------------------------------------------------------- Series

@Composable
fun SeriesDetailScreen(remoteId: String) {
    val repo = LocalRepository.current
    val actions = rememberActions()
    val pid by repo.activeId.collectAsState()
    val series by produceState<SeriesEntity?>(null, pid, remoteId) { value = repo.dao.series(pid, remoteId) }
    var loadError by remember { mutableStateOf(false) }
    val details by produceState<SeriesDetails?>(null, series) {
        val s = series ?: return@produceState
        value = try {
            repo.seriesDetails(s)
        } catch (e: Exception) {
            loadError = true
            null
        }
    }
    val favUids by remember(pid) { repo.favoriteUids(pid) }.collectAsState(initial = emptyList())
    // Reloaded every time the screen is shown so progress bars are current.
    val episodeProgress by produceState(emptyMap<String, ProgressEntity>(), pid, remoteId) {
        value = repo.episodeProgress(pid, remoteId).associateBy { it.remoteId }
    }
    val resume by produceState<ProgressEntity?>(null, pid, remoteId) {
        value = repo.progress(repo.uid(pid, Kind.SERIES, remoteId))
    }
    var seasonIndex by rememberSaveable { mutableIntStateOf(-1) }
    val playRequester = remember { FocusRequester() }
    val memory = rememberFocusMemory()
    val similar by produceState(emptyList<SeriesEntity>(), series) {
        val se = series ?: return@produceState
        value = runCatching { repo.similarSeries(se) }.getOrDefault(emptyList())
    }

    val s = series
    if (s == null) {
        LoadingView()
        return
    }
    val d = details
    val isFav = repo.uid(pid, Kind.SERIES, remoteId) in favUids
    val seasons = d?.seasons.orEmpty()
    val allEpisodes = remember(seasons) { seasons.flatMap { it.episodes } }

    // Episode to continue from: the one the series progress points at, else the first.
    val resumeEpisode: EpisodeEntity? = remember(resume, allEpisodes) {
        val r = resume
        if (r != null) allEpisodes.firstOrNull { it.url == r.url } else null
    }

    LaunchedEffect(seasons, resumeEpisode) {
        if (seasonIndex == -1 && seasons.isNotEmpty()) {
            val target = resumeEpisode?.season
            seasonIndex = seasons.indexOfFirst { it.number == target }.takeIf { it >= 0 } ?: 0
        }
    }
    LaunchedEffect(d) {
        if (d != null) {
            delay(80)
            if (memory.value == null) runCatching { playRequester.requestFocus() }
        }
    }

    fun playEpisode(e: EpisodeEntity, startMs: Long) {
        val items = allEpisodes.map { repo.episodeItem(s, it) }
        val index = allEpisodes.indexOf(e).coerceAtLeast(0)
        actions.play(items, index, startMs)
    }

    val backdrop = d?.backdrop ?: s.backdrop
    Box(Modifier.fillMaxSize()) {
        HeroBackdrop(
            image = backdrop ?: d?.poster ?: s.poster,
            wide = backdrop != null,
            reduceMotion = repo.prefs.reduceMotion,
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 56.dp)
        ) {
            Column(Modifier.padding(start = 56.dp, end = 360.dp)) {
                Text("SERIE", style = Type.Label, color = Palette.Accent)
                Spacer(Modifier.height(8.dp))
                Text(cleanTitle(s.name), style = Type.Display, color = Palette.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                val meta = listOfNotNull(
                    metaLine(d?.year ?: s.year, d?.rating ?: s.rating, d?.genre ?: s.genre).takeIf { it.isNotBlank() },
                    if (seasons.isNotEmpty()) "${seasons.size} temporada" + (if (seasons.size > 1) "s" else "") else null,
                ).joinToString("  ·  ")
                if (meta.isNotBlank()) Text(meta, style = Type.Label, color = Palette.TextDim)
                Spacer(Modifier.height(12.dp))
                val plot = d?.plot ?: s.plot
                if (!plot.isNullOrBlank()) {
                    Text(plot, style = Type.Body, color = Palette.TextDim, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    val first = allEpisodes.firstOrNull()
                    val target = resumeEpisode ?: first
                    val targetProgress = target?.let { episodeProgress[it.remoteId] }
                    val startMs = if (targetProgress != null && targetProgress.durationMs > 0 &&
                        targetProgress.positionMs < targetProgress.durationMs * 0.92
                    ) targetProgress.positionMs else 0L
                    PillButton(
                        text = when {
                            target == null && d == null && !loadError -> "Cargando…"
                            target == null -> "Sin episodios"
                            resumeEpisode != null -> "Continuar T${target.season} · E${target.episode}"
                            else -> "Reproducir T${target.season} · E${target.episode}"
                        },
                        leading = "▶",
                        primary = true,
                        enabled = target != null,
                        onClick = { if (target != null) playEpisode(target, startMs) },
                        modifier = Modifier
                            .focusRequester(playRequester)
                            .focusMemory("play", memory),
                    )
                    PillButton(
                        text = if (isFav) "En Mi lista" else "Mi lista",
                        leading = if (isFav) "✓" else "+",
                        onClick = { actions.toggleFavorite(Kind.SERIES, s.remoteId, s.name, s.poster) },
                        modifier = Modifier.focusMemory("fav", memory),
                    )
                }
            }

            Spacer(Modifier.height(22.dp))
            when {
                d == null && !loadError -> LoadingView(modifier = Modifier.height(160.dp))
                seasons.isEmpty() -> EmptyState(
                    title = "No se encontraron episodios",
                    message = if (loadError) "El servidor no respondió. Vuelve a intentarlo más tarde." else null,
                    modifier = Modifier.height(160.dp),
                )
                else -> {
                    if (seasons.size > 1) {
                        PivotScroll(56.dp) {
                            LazyRow(
                                modifier = Modifier.focusRestorer(),
                                contentPadding = PaddingValues(horizontal = 56.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                itemsIndexed(seasons, key = { _, season -> season.number }) { i, season ->
                                    TabPill(
                                        text = season.name,
                                        selected = i == seasonIndex,
                                        onClick = { seasonIndex = i },
                                        modifier = Modifier.focusMemory("season:${season.number}", memory),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    val season = seasons.getOrNull(seasonIndex.coerceAtLeast(0)) ?: seasons.first()
                    PivotScroll(56.dp) {
                        LazyRow(
                            state = rememberLazyListState(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRestorer(),
                            contentPadding = PaddingValues(horizontal = 56.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            itemsIndexed(season.episodes, key = { _, e -> e.remoteId }) { _, e ->
                                val pr = episodeProgress[e.remoteId]
                                val fraction = if (pr != null && pr.durationMs > 0) pr.positionMs.toFloat() / pr.durationMs else null
                                val watched = fraction != null && fraction >= 0.92f
                                MediaCard(
                                    title = "${e.episode}. " + com.hispanoiptv.tv.data.Repository.cleanEpisodeTitle(e.title, s.name, e.episode),
                                    subtitle = when {
                                        watched -> "Visto"
                                        e.durationSecs > 0 -> com.hispanoiptv.tv.data.Repository.formatDurationSecs(e.durationSecs)
                                        else -> null
                                    },
                                    image = e.image ?: backdrop ?: s.poster,
                                    width = 220.dp,
                                    aspectRatio = 16f / 9f,
                                    progress = fraction?.takeIf { !watched },
                                    badge = if (watched) "✓" else null,
                                    onClick = {
                                        val start = if (pr != null && !watched) pr.positionMs else 0L
                                        playEpisode(e, start)
                                    },
                                    modifier = Modifier.focusMemory("ep:${e.remoteId}", memory),
                                )
                            }
                        }
                    }
                }
            }
            if (similar.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                CompositionLocalProvider(LocalEdge provides 56.dp) {
                    Shelf("Más como esto") {
                        items(similar, key = { "sim:" + it.remoteId }) { x ->
                            MediaCard(
                                title = cleanTitle(x.name),
                                subtitle = x.year?.take(4),
                                image = x.poster,
                                rating = x.rating,
                                onClick = { actions.openSeries(x) },
                                onLongClick = { actions.toggleFavorite(Kind.SERIES, x.remoteId, x.name, x.poster) },
                                modifier = Modifier.focusMemory("sim:${x.remoteId}", memory),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------- Category grid

@Composable
fun CategoryGridScreen(kind: Kind, categoryId: String, title: String) {
    val repo = LocalRepository.current
    val actions = rememberActions()
    val memory = rememberFocusMemory()
    val pid by repo.activeId.collectAsState()
    val isSeries = kind == Kind.SERIES
    val items by produceState<List<BrowseItem>?>(null, pid, categoryId, kind) {
        value = loadCategoryItems(repo, pid, isSeries, categoryId, 20_000)
    }
    val firstRequester = remember { FocusRequester() }

    val list = items
    LaunchedEffect(list) {
        if (list != null && list.isNotEmpty() && memory.value == null) {
            delay(80)
            runCatching { firstRequester.requestFocus() }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(start = 48.dp, top = 36.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(title, style = Type.Title, color = Palette.Text)
            if (list != null) {
                Spacer(Modifier.width(14.dp))
                Text("${list.size} títulos", style = Type.Label, color = Palette.TextDim)
            }
        }
        when {
            list == null -> LoadingView()
            list.isEmpty() -> EmptyState(
                if (categoryId.startsWith(COLLECTION_PREFIX)) "Ninguna película de $title está en tu lista"
                else "Esta categoría está vacía"
            )
            else -> PivotScroll(40.dp) {
                LazyVerticalGrid(
                    state = rememberLazyGridState(),
                    columns = GridCells.Fixed(7),
                    contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 16.dp, bottom = 60.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(list, key = { it.remoteId }) { item ->
                        val isFirst = item.remoteId == list.first().remoteId
                        MediaCard(
                            title = item.title,
                            image = item.poster,
                            width = 112.dp,
                            rating = item.rating,
                            onClick = {
                                if (isSeries) actions.openSeries(item.remoteId) else actions.openMovie(item.remoteId)
                            },
                            onLongClick = { actions.toggleFavorite(kind, item.remoteId, item.title, item.poster) },
                            modifier = (if (isFirst) Modifier.focusRequester(firstRequester) else Modifier)
                                .focusMemory("grid:${item.remoteId}", memory),
                        )
                    }
                }
            }
        }
    }
}
