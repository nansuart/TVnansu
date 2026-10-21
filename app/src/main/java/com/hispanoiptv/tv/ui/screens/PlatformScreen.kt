package com.hispanoiptv.tv.ui.screens

import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hispanoiptv.tv.data.db.Kind
import com.hispanoiptv.tv.ui.LocalRepository
import com.hispanoiptv.tv.ui.components.HeroData
import com.hispanoiptv.tv.ui.components.HeroScaffold
import com.hispanoiptv.tv.ui.components.LoadingView
import com.hispanoiptv.tv.ui.components.MediaCard
import com.hispanoiptv.tv.ui.components.Shelf
import com.hispanoiptv.tv.ui.components.ViewAllCard
import com.hispanoiptv.tv.ui.components.rememberHeroState
import com.hispanoiptv.tv.ui.focusMemory
import com.hispanoiptv.tv.ui.rememberActions
import com.hispanoiptv.tv.ui.rememberFocusMemory

/** One streaming platform: its newest series, then one row per provider subcategory. */
@Composable
fun PlatformScreen(name: String) {
    val repo = LocalRepository.current
    val actions = rememberActions()
    val memory = rememberFocusMemory()
    val pid by repo.activeId.collectAsState()

    val rows by produceState<List<Pair<String, List<BrowseItem>>>?>(null, pid, name) {
        val series = repo.seriesOfPlatform(pid, name)
        val catNames = repo.dao.categories(pid, Kind.SERIES.name).associate { it.remoteId to it.name }
        val byCat = series.groupBy { it.categoryId }
            .entries
            .sortedByDescending { it.value.size }
            .map { (cat, list) -> (catNames[cat] ?: "Otras") to list.map { it.toBrowse() } }
        value = listOf("Novedades en $name" to series.take(ROW_LIMIT).map { it.toBrowse() }) +
            if (byCat.size > 1) byCat else emptyList()
    }
    val hero = rememberHeroState()
    val list = rows
    LaunchedEffect(list) {
        if (hero.target == null) {
            hero.target = list?.firstOrNull()?.second?.firstOrNull()?.hero?.copy(eyebrow = name.uppercase())
                ?: HeroData(title = name, image = null, wide = false)
        }
    }
    if (list == null) {
        LoadingView()
        return
    }
    HeroScaffold(hero = hero.shown, listState = rememberLazyListState()) {
        itemsIndexed(list, key = { i, _ -> i }) { _, (title, items) ->
            Shelf(title) {
                items(items.take(ROW_LIMIT), key = { it.remoteId }) { item ->
                    MediaCard(
                        title = item.title,
                        image = item.poster,
                        width = com.hispanoiptv.tv.ui.components.POSTER_W,
                        rating = item.rating,
                        onClick = { actions.openSeries(item.remoteId) },
                        onLongClick = { actions.toggleFavorite(Kind.SERIES, item.remoteId, item.title, item.poster) },
                        onFocused = { hero.target = item.hero },
                        modifier = Modifier.focusMemory("pf:$title:${item.remoteId}", memory),
                    )
                }
                if (title.startsWith("Novedades")) {
                    item(key = "all") {
                        ViewAllCard(
                            width = com.hispanoiptv.tv.ui.components.POSTER_W,
                            onClick = { actions.openGrid(Kind.SERIES, PLATFORM_PREFIX + name, name) },
                            modifier = Modifier.focusMemory("pf:all", memory),
                        )
                    }
                }
            }
        }
    }
}
