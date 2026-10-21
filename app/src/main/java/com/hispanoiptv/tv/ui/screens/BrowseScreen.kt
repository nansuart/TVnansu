package com.hispanoiptv.tv.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.hispanoiptv.tv.data.Repository
import com.hispanoiptv.tv.data.db.CategoryEntity
import com.hispanoiptv.tv.data.db.Kind
import com.hispanoiptv.tv.data.db.MovieEntity
import com.hispanoiptv.tv.data.db.SeriesEntity
import com.hispanoiptv.tv.ui.LocalRepository
import com.hispanoiptv.tv.ui.components.CardShape
import com.hispanoiptv.tv.ui.components.EmptyState
import com.hispanoiptv.tv.ui.components.HeroData
import com.hispanoiptv.tv.ui.components.Showcase
import com.hispanoiptv.tv.ui.components.ShowcaseItem
import com.hispanoiptv.tv.ui.components.ShowcaseScaffold
import com.hispanoiptv.tv.ui.components.LoadingView
import com.hispanoiptv.tv.ui.components.MediaCard
import com.hispanoiptv.tv.ui.components.Shelf
import com.hispanoiptv.tv.ui.components.ViewAllCard
import com.hispanoiptv.tv.ui.components.cleanTitle
import com.hispanoiptv.tv.ui.components.rememberHeroState
import com.hispanoiptv.tv.ui.focusMemory
import com.hispanoiptv.tv.ui.rememberActions
import com.hispanoiptv.tv.ui.rememberFocusMemory
import com.hispanoiptv.tv.ui.theme.Palette
import com.hispanoiptv.tv.ui.theme.Type

/** Lightweight item shared by the movie and series browsers. */
data class BrowseItem(
    val remoteId: String,
    val title: String,
    val poster: String?,
    val hero: HeroData,
    val rating: Float = 0f,
    val year: String? = null,
)

fun MovieEntity.toBrowse() = BrowseItem(remoteId, cleanTitle(name), poster, movieHero(this), rating, year?.take(4))
fun SeriesEntity.toBrowse() = BrowseItem(remoteId, cleanTitle(name), poster, seriesHero(this), rating, year?.take(4))

const val ROW_LIMIT = 40

const val PLATFORM_PREFIX = "platform:"
const val COLLECTION_PREFIX = "collection:"

suspend fun loadCategoryItems(repo: Repository, pid: Long, isSeries: Boolean, categoryId: String, limit: Int): List<BrowseItem> =
    when {
        categoryId.startsWith(PLATFORM_PREFIX) ->
            repo.seriesOfPlatform(pid, categoryId.removePrefix(PLATFORM_PREFIX)).take(limit).map { it.toBrowse() }
        categoryId.startsWith(COLLECTION_PREFIX) -> {
            val id = categoryId.removePrefix(COLLECTION_PREFIX)
            val ready = repo.collections.results.value[id]
            val def = repo.collections.find(id)
            val movies = ready?.movies ?: if (def != null) repo.collections.match(pid, def) else emptyList()
            movies.take(limit).map { it.toBrowse() }
        }
        isSeries -> repo.dao.seriesIn(pid, categoryId, limit).distinctBy { it.remoteId }.map { it.toBrowse() }
        else -> repo.dao.moviesIn(pid, categoryId, limit).distinctBy { it.remoteId }.map { it.toBrowse() }
    }

@Composable
fun BrowseScreen(isSeries: Boolean) {
    val repo = LocalRepository.current
    val actions = rememberActions()
    val memory = rememberFocusMemory()
    val pid by repo.activeId.collectAsState()
    val version by repo.dataVersion.collectAsState()
    val kind = if (isSeries) Kind.SERIES else Kind.MOVIE
    val prefix = if (isSeries) "s" else "m"

    // Kept in memory: coming back to Películas or Series shows the rows at once.
    val catsKey = repo.memoKey("cats:${kind.name}", pid)
    val recentKey = repo.memoKey("recent:${kind.name}", pid)
    val categories by produceState(repo.peek<List<CategoryEntity>>(catsKey), pid, version, isSeries) {
        value = repo.cached(catsKey) { repo.dao.categories(pid, kind.name) }
    }
    val recent by produceState(repo.peek<List<BrowseItem>>(recentKey) ?: emptyList(), pid, version, isSeries) {
        value = repo.cached(recentKey) {
            if (isSeries) repo.dao.recentSeries(pid, ROW_LIMIT).distinctBy { it.remoteId }.map { it.toBrowse() }
            else repo.dao.recentMovies(pid, ROW_LIMIT).distinctBy { it.remoteId }.map { it.toBrowse() }
        }
    }
    val favoriteRefs by remember(pid) { repo.favorites(pid) }.collectAsState(initial = emptyList())
    val favorites = remember(favoriteRefs, kind) {
        favoriteRefs.filter { it.kind == kind.name }.distinctBy { it.remoteId }.map {
            BrowseItem(
                it.remoteId, it.title, it.image,
                HeroData(it.title, it.image, wide = false, eyebrow = if (isSeries) "SERIE" else "PELÍCULA"),
            )
        }
    }
    val rowCache = remember(pid, version, isSeries) { mutableStateMapOf<String, List<BrowseItem>>() }
    val hero = rememberHeroState()

    // Series: one row per streaming platform (classified in the background).
    val platformVersion by repo.platforms.version.collectAsState()
    val platformRows by produceState(emptyList<Pair<String, List<BrowseItem>>>(), pid, version, platformVersion, isSeries) {
        if (isSeries) value = repo.seriesByPlatform(pid).map { (name, list) -> name to list.map { it.toBrowse() } }
    }

    // Movies: studios, directors and themed lists from Wikidata.
    val collectionResults by repo.collections.results.collectAsState()
    LaunchedEffect(pid, version, isSeries) {
        if (!isSeries) repo.scope.launch { repo.collections.loadAll(pid) }
    }
    val lists = remember(collectionResults) {
        collectionResults.values.filter { it.def.group == "listas" && it.movies.size >= 5 }
    }
    val studios = remember(collectionResults) {
        collectionResults.values.filter { it.def.group == "estudios" && it.movies.size >= 3 }.sortedByDescending { it.movies.size }
    }
    val directors = remember(collectionResults) {
        collectionResults.values.filter { it.def.group == "directores" && it.movies.size >= 3 }.sortedByDescending { it.movies.size }
    }

    LaunchedEffect(recent) {
        if (hero.target == null) hero.target = recent.firstOrNull()?.hero
    }

    // Carousel: newest titles with wide artwork, the best rated, and the list of the season
    // (Halloween in October, Christmas in December) for movies or a platform pick for series.
    val favUids by remember(pid) { repo.favoriteUids(pid) }.collectAsState(initial = emptyList())
    val seasonal = remember(collectionResults) {
        collectionResults.values.firstOrNull { it.def.group == "listas" && it.def.months.isNotEmpty() && it.movies.isNotEmpty() }
    }
    val showcaseKey = repo.memoKey("showcase:${kind.name}:${seasonal?.def?.id}:${platformRows.size}", pid)
    val showcase by produceState(repo.peek<List<ShowcaseItem>>(showcaseKey) ?: emptyList(), showcaseKey) {
        value = repo.cached(showcaseKey) { buildShowcase(repo, pid, isSeries, seasonal, platformRows) }
    }

    val cats = categories
    if (cats == null) {
        LoadingView()
        return
    }
    if (cats.isEmpty() && recent.isEmpty()) {
        EmptyState(
            title = if (isSeries) "No hay series en esta lista" else "No hay películas en esta lista",
            message = "Si tu proveedor las incluye, prueba a actualizar la lista en Ajustes.",
        )
        return
    }

    fun open(item: BrowseItem) {
        if (isSeries) actions.openSeries(item.remoteId) else actions.openMovie(item.remoteId)
    }

    fun toggleFav(item: BrowseItem) = actions.toggleFavorite(kind, item.remoteId, item.title, item.poster)

    @Composable
    fun Card(item: BrowseItem, rowKey: String) {
        MediaCard(
            title = item.title,
            subtitle = item.year,
            image = item.poster,
            width = com.hispanoiptv.tv.ui.components.POSTER_W,
            onClick = { open(item) },
            rating = item.rating,
            onLongClick = { toggleFav(item) },
            onFocused = { hero.target = item.hero },
            modifier = Modifier.focusMemory("$prefix:$rowKey:${item.remoteId}", memory),
        )
    }

    val listState = rememberLazyListState()
    val headerHidden = LocalHeaderHidden.current
    ShowcaseScaffold(
        listState = listState,
        onScrolled = { headerHidden.value = it },
        top = { mod ->
            Showcase(
                items = showcase,
                onOpen = { s -> if (isSeries) actions.openSeries(s.remoteId) else actions.openMovie(s.remoteId) },
                onToggleList = { s -> actions.toggleFavorite(kind, s.remoteId, s.title, s.poster) },
                inList = { s -> repo.uid(pid, kind, s.remoteId) in favUids },
                memory = memory,
                memoryKey = "$prefix:showcase",
                modifier = mod,
            )
        },
    ) {
        if (recent.isNotEmpty()) {
            item(key = "recent") {
                Shelf(if (isSeries) "Actualizadas recientemente" else "Añadidas recientemente") {
                    items(recent, key = { it.remoteId }) { Card(it, "recent") }
                }
            }
        }
        if (favorites.isNotEmpty()) {
            item(key = "favorites") {
                Shelf("Mi lista") {
                    items(favorites, key = { it.remoteId }) { Card(it, "fav") }
                }
            }
        }
        if (isSeries) {
            items(platformRows, key = { "pf:" + it.first }) { (name, list) ->
                Shelf("$name  ·  ${list.size} series") {
                    items(list.take(ROW_LIMIT), key = { it.remoteId }) { Card(it, "pf:$name") }
                    item(key = "all") {
                        ViewAllCard(
                            width = com.hispanoiptv.tv.ui.components.POSTER_W,
                            onClick = { actions.openPlatform(name) },
                            modifier = Modifier.focusMemory("s:pf:$name:all", memory),
                        )
                    }
                }
            }
        } else {
            items(lists, key = { "ls:" + it.def.id }) { r ->
                Shelf(r.def.title) {
                    items(r.movies.take(ROW_LIMIT).map { it.toBrowse() }, key = { it.remoteId }) { Card(it, "ls:${r.def.id}") }
                    if (r.movies.size > ROW_LIMIT) {
                        item(key = "all") {
                            ViewAllCard(
                                width = com.hispanoiptv.tv.ui.components.POSTER_W,
                                onClick = { actions.openGrid(Kind.MOVIE, COLLECTION_PREFIX + r.def.id, r.def.title) },
                                modifier = Modifier.focusMemory("m:ls:${r.def.id}:all", memory),
                            )
                        }
                    }
                }
            }
            if (studios.isNotEmpty()) {
                item(key = "studios") {
                    Shelf("Estudios") {
                        items(studios, key = { it.def.id }) { r ->
                            CollectionTile(
                                title = r.def.title,
                                subtitle = "${r.movies.size} películas",
                                onClick = { actions.openGrid(Kind.MOVIE, COLLECTION_PREFIX + r.def.id, r.def.title) },
                                onFocused = { r.movies.firstOrNull()?.let { hero.target = movieHero(it).copy(eyebrow = r.def.title.uppercase()) } },
                                modifier = Modifier.focusMemory("m:st:${r.def.id}", memory),
                            )
                        }
                    }
                }
            }
            if (directors.isNotEmpty()) {
                item(key = "directors") {
                    Shelf("Directores") {
                        items(directors, key = { it.def.id }) { r ->
                            CollectionTile(
                                title = r.def.title,
                                subtitle = "${r.movies.size} películas",
                                onClick = { actions.openGrid(Kind.MOVIE, COLLECTION_PREFIX + r.def.id, r.def.title) },
                                onFocused = { r.movies.firstOrNull()?.let { hero.target = movieHero(it).copy(eyebrow = "DE " + r.def.title.uppercase()) } },
                                modifier = Modifier.focusMemory("m:dr:${r.def.id}", memory),
                            )
                        }
                    }
                }
            }
        }
        items(cats, key = { "cat:" + it.remoteId }) { cat ->
            val rowKey = repo.memoKey("row:${kind.name}:${cat.remoteId}", pid)
            val loaded = rowCache[cat.remoteId] ?: repo.peek<List<BrowseItem>>(rowKey)
            LaunchedEffect(cat.remoteId, pid, version) {
                if (rowCache[cat.remoteId] == null) {
                    rowCache[cat.remoteId] = repo.cached(rowKey) { loadCategoryItems(repo, pid, isSeries, cat.remoteId, ROW_LIMIT) }
                }
            }
            when {
                loaded == null -> PlaceholderShelf(cat.name)
                loaded.isEmpty() -> Unit
                else -> Shelf(cat.name) {
                    items(loaded, key = { it.remoteId }) { Card(it, cat.remoteId) }
                    if (loaded.size >= ROW_LIMIT) {
                        item(key = "all") {
                            ViewAllCard(
                                width = com.hispanoiptv.tv.ui.components.POSTER_W,
                                onClick = { actions.openGrid(kind, cat.remoteId, cat.name) },
                                modifier = Modifier.focusMemory("$prefix:${cat.remoteId}:all", memory),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PlaceholderShelf(title: String) {
    val edge = com.hispanoiptv.tv.ui.LocalEdge.current
    Column(Modifier.padding(bottom = 14.dp)) {
        Text(title, style = Type.Section, color = Palette.TextDim, modifier = Modifier.padding(start = edge, bottom = 16.dp))
        Row(
            Modifier.padding(start = edge, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            repeat(7) {
                Box(
                    Modifier
                        .width(com.hispanoiptv.tv.ui.components.POSTER_W)
                        .aspectRatio(2f / 3f)
                        .clip(CardShape)
                        .background(Palette.Surface)
                )
            }
        }
    }
}


/** Studio or director tile: name set large on a quiet card. */
@Composable
fun CollectionTile(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
    androidx.tv.material3.Surface(
        onClick = onClick,
        modifier = modifier
            .width(220.dp)
            .height(124.dp)
            .onFocusChanged { if (it.isFocused) onFocused() },
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(shape = shape),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.07f),
        border = androidx.tv.material3.ClickableSurfaceDefaults.border(
            focusedBorder = androidx.tv.material3.Border(
                androidx.compose.foundation.BorderStroke(1.5.dp, Palette.Focus), shape = shape,
            ),
        ),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = Palette.Surface,
            contentColor = Palette.Text,
            focusedContainerColor = Palette.Surface2,
            focusedContentColor = Palette.Text,
        ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(Palette.Surface3, Palette.Surface)
                    )
                )
                .padding(16.dp)
        ) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .width(24.dp)
                    .height(3.dp)
                    .background(com.hispanoiptv.tv.ui.theme.Brand.Horizontal)
            )
            Column(Modifier.align(Alignment.BottomStart)) {
                Text(
                    text = title,
                    style = Type.Section,
                    color = Palette.Text,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = Type.Caption, color = Palette.TextDim)
            }
        }
    }
}

private suspend fun buildShowcase(
    repo: Repository,
    pid: Long,
    isSeries: Boolean,
    seasonal: com.hispanoiptv.tv.data.CollectionResult?,
    platformRows: List<Pair<String, List<BrowseItem>>>,
): List<ShowcaseItem> {
    val out = ArrayList<ShowcaseItem>()
    if (!isSeries) {
        var recent = repo.dao.recentMovies(pid, 12).distinctBy { it.remoteId }
        if (repo.prefetchBackdrops(recent.take(8))) recent = repo.dao.recentMovies(pid, 12).distinctBy { it.remoteId }
        recent.sortedByDescending { it.backdrop != null }.take(5).forEach { out += it.showcase("NUEVA EN TU LISTA") }
        repo.recommendedMovies(pid, 12).filter { m -> out.none { it.remoteId == m.remoteId } }
            .sortedByDescending { it.rating }.take(2).forEach { out += it.showcase("MEJOR VALORADA") }
        seasonal?.movies?.firstOrNull { m -> out.none { it.remoteId == m.remoteId } }?.let {
            out += it.showcase(seasonal.def.title.uppercase())
        }
    } else {
        val recent = repo.dao.recentSeries(pid, 12).distinctBy { it.remoteId }
        recent.sortedByDescending { it.backdrop != null }.take(5).forEach { out += it.showcase("SERIE · ACTUALIZADA") }
        repo.recommendedSeries(pid, 12).filter { s -> out.none { it.remoteId == s.remoteId } }
            .sortedByDescending { it.rating }.take(2).forEach { out += it.showcase("SERIE · MEJOR VALORADA") }
        for ((platform, list) in platformRows.take(2)) {
            val pick = list.firstOrNull { b -> out.none { it.remoteId == b.remoteId } } ?: continue
            repo.dao.series(pid, pick.remoteId)?.let { out += it.showcase(platform.uppercase()) }
        }
    }
    return out
}

private fun MovieEntity.showcase(eyebrow: String) = ShowcaseItem(
    key = "m:$remoteId", kind = Kind.MOVIE, remoteId = remoteId, title = cleanTitle(name), eyebrow = eyebrow,
    meta = metaLine(year, rating, null), description = plot, backdrop = backdrop, poster = poster,
)

private fun SeriesEntity.showcase(eyebrow: String) = ShowcaseItem(
    key = "s:$remoteId", kind = Kind.SERIES, remoteId = remoteId, title = cleanTitle(name), eyebrow = eyebrow,
    meta = metaLine(year, rating, genre), description = plot, backdrop = backdrop, poster = poster,
)
