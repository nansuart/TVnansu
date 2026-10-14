package com.nansu.tvnansu.ui.screens

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
import com.nansu.tvnansu.data.Repository
import com.nansu.tvnansu.data.db.CategoryEntity
import com.nansu.tvnansu.data.db.Kind
import com.nansu.tvnansu.data.db.MovieEntity
import com.nansu.tvnansu.data.db.SeriesEntity
import com.nansu.tvnansu.ui.LocalRepository
import com.nansu.tvnansu.ui.components.CardShape
import com.nansu.tvnansu.ui.components.EmptyState
import com.nansu.tvnansu.ui.components.HeroData
import com.nansu.tvnansu.ui.components.HeroScaffold
import com.nansu.tvnansu.ui.components.LoadingView
import com.nansu.tvnansu.ui.components.MediaCard
import com.nansu.tvnansu.ui.components.Shelf
import com.nansu.tvnansu.ui.components.ViewAllCard
import com.nansu.tvnansu.ui.components.cleanTitle
import com.nansu.tvnansu.ui.components.rememberHeroState
import com.nansu.tvnansu.ui.focusMemory
import com.nansu.tvnansu.ui.rememberActions
import com.nansu.tvnansu.ui.rememberFocusMemory
import com.nansu.tvnansu.ui.theme.Palette
import com.nansu.tvnansu.ui.theme.Type

/** Lightweight item shared by the movie and series browsers. */
data class BrowseItem(
    val remoteId: String,
    val title: String,
    val poster: String?,
    val hero: HeroData,
    val rating: Float = 0f,
)

fun MovieEntity.toBrowse() = BrowseItem(remoteId, cleanTitle(name), poster, movieHero(this), rating)
fun SeriesEntity.toBrowse() = BrowseItem(remoteId, cleanTitle(name), poster, seriesHero(this), rating)

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
            image = item.poster,
            width = com.nansu.tvnansu.ui.components.POSTER_W,
            onClick = { open(item) },
            rating = item.rating,
            onLongClick = { toggleFav(item) },
            onFocused = { hero.target = item.hero },
            modifier = Modifier.focusMemory("$prefix:$rowKey:${item.remoteId}", memory),
        )
    }

    HeroScaffold(hero = hero.shown, listState = rememberLazyListState()) {
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
                            width = com.nansu.tvnansu.ui.components.POSTER_W,
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
                                width = com.nansu.tvnansu.ui.components.POSTER_W,
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
                                width = com.nansu.tvnansu.ui.components.POSTER_W,
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
    val edge = com.nansu.tvnansu.ui.LocalEdge.current
    Column(Modifier.padding(bottom = 14.dp)) {
        Text(title, style = Type.Section, color = Palette.TextDim, modifier = Modifier.padding(start = edge, bottom = 16.dp))
        Row(
            Modifier.padding(start = edge, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            repeat(7) {
                Box(
                    Modifier
                        .width(com.nansu.tvnansu.ui.components.POSTER_W)
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
                    .background(Palette.Accent)
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
