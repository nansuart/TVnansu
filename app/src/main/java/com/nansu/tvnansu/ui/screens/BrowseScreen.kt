package com.nansu.tvnansu.ui.screens

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

suspend fun loadCategoryItems(repo: Repository, pid: Long, isSeries: Boolean, categoryId: String, limit: Int): List<BrowseItem> =
    if (isSeries) repo.dao.seriesIn(pid, categoryId, limit).distinctBy { it.remoteId }.map { it.toBrowse() }
    else repo.dao.moviesIn(pid, categoryId, limit).distinctBy { it.remoteId }.map { it.toBrowse() }

@Composable
fun BrowseScreen(isSeries: Boolean) {
    val repo = LocalRepository.current
    val actions = rememberActions()
    val memory = rememberFocusMemory()
    val pid by repo.activeId.collectAsState()
    val version by repo.dataVersion.collectAsState()
    val kind = if (isSeries) Kind.SERIES else Kind.MOVIE
    val prefix = if (isSeries) "s" else "m"

    val categories by produceState<List<CategoryEntity>?>(null, pid, version, isSeries) {
        value = repo.dao.categories(pid, kind.name)
    }
    val recent by produceState(emptyList<BrowseItem>(), pid, version, isSeries) {
        value = if (isSeries) repo.dao.recentSeries(pid, ROW_LIMIT).distinctBy { it.remoteId }.map { it.toBrowse() }
        else repo.dao.recentMovies(pid, ROW_LIMIT).distinctBy { it.remoteId }.map { it.toBrowse() }
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
            width = 120.dp,
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
        items(cats, key = { "cat:" + it.remoteId }) { cat ->
            val loaded = rowCache[cat.remoteId]
            LaunchedEffect(cat.remoteId, pid, version) {
                if (rowCache[cat.remoteId] == null) {
                    rowCache[cat.remoteId] = loadCategoryItems(repo, pid, isSeries, cat.remoteId, ROW_LIMIT)
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
                                width = 120.dp,
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
    Column(Modifier.padding(bottom = 14.dp)) {
        Text(title, style = Type.Section, color = Palette.TextDim, modifier = Modifier.padding(start = 48.dp, bottom = 16.dp))
        Row(
            Modifier.padding(start = 48.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            repeat(7) {
                Box(
                    Modifier
                        .width(120.dp)
                        .aspectRatio(2f / 3f)
                        .clip(CardShape)
                        .background(Palette.Surface)
                )
            }
        }
    }
}
