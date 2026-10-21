package com.hispanoiptv.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.hispanoiptv.tv.data.CollectionDef
import com.hispanoiptv.tv.data.Collections
import com.hispanoiptv.tv.data.db.ChannelEntity
import com.hispanoiptv.tv.data.db.Kind
import com.hispanoiptv.tv.data.db.MovieEntity
import com.hispanoiptv.tv.data.db.SeriesEntity
import com.hispanoiptv.tv.ui.LocalRepository
import com.hispanoiptv.tv.ui.PivotScroll
import com.hispanoiptv.tv.ui.components.MediaCard
import com.hispanoiptv.tv.ui.components.SearchKeyboard
import com.hispanoiptv.tv.ui.components.Shelf
import com.hispanoiptv.tv.ui.components.cleanTitle
import com.hispanoiptv.tv.ui.focusMemory
import com.hispanoiptv.tv.ui.rememberActions
import com.hispanoiptv.tv.ui.rememberFocusMemory
import com.hispanoiptv.tv.ui.theme.Palette
import com.hispanoiptv.tv.ui.theme.Type
import kotlinx.coroutines.delay

private data class SearchResults(
    val channels: List<ChannelEntity> = emptyList(),
    val movies: List<MovieEntity> = emptyList(),
    val series: List<SeriesEntity> = emptyList(),
) {
    val isEmpty get() = channels.isEmpty() && movies.isEmpty() && series.isEmpty()
}

@Composable
fun SearchScreen() {
    val repo = LocalRepository.current
    val actions = rememberActions()
    val memory = rememberFocusMemory()
    val pid by repo.activeId.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }

    val results by produceState(SearchResults(), query, pid) {
        val q = query.trim()
        if (q.length < 2) {
            value = SearchResults()
            return@produceState
        }
        delay(300)
        value = SearchResults(
            channels = repo.dao.searchChannels(pid, q, 40),
            movies = repo.dao.searchMovies(pid, q, 40),
            series = repo.dao.searchSeries(pid, q, 40),
        )
    }

    // Directors and studios: the app's own collections plus a Wikidata search.
    val people by produceState(emptyList<CollectionDef>(), query) {
        val q = query.trim()
        if (q.length < 3) {
            value = emptyList()
            return@produceState
        }
        delay(700)
        val key = Collections.norm(q)
        val local = runCatching { repo.collections.definitions() }.getOrDefault(emptyList())
            .filter { it.group != "listas" && Collections.norm(it.title).contains(key) }
        value = local
        val remote = runCatching { repo.collections.search(q) }.getOrDefault(emptyList())
        value = (local + remote).distinctBy { it.qid }.take(8)
    }

    Row(
        Modifier
            .fillMaxSize()
            .padding(top = com.hispanoiptv.tv.ui.LocalTopInset.current)
    ) {
        Column(Modifier.padding(start = com.hispanoiptv.tv.ui.LocalEdge.current, end = 24.dp)) {
            Box(
                Modifier
                    .width(276.dp)
                    .height(52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Palette.Surface)
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Text(
                    text = if (query.isEmpty()) "Buscar…" else query + "▏",
                    style = Type.Section,
                    color = if (query.isEmpty()) Palette.TextFaint else Palette.Text,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(16.dp))
            SearchKeyboard(
                onChar = { query += it },
                onSpace = { if (query.isNotEmpty() && !query.endsWith(" ")) query += " " },
                onDelete = { query = query.dropLast(1) },
                onClear = { query = "" },
            )
        }

        Box(
            Modifier
                .fillMaxHeight()
                .weight(1f)
        ) {
            when {
                query.trim().length < 2 -> Text(
                    "Escribe al menos dos letras para buscar canales, películas y series. Con tres o más también aparecen directores y estudios.",
                    style = Type.Body,
                    color = Palette.TextFaint,
                    modifier = Modifier.padding(top = 16.dp, end = 48.dp),
                )
                results.isEmpty && people.isEmpty() -> Text(
                    "Sin resultados para \"${query.trim()}\"",
                    style = Type.Body,
                    color = Palette.TextDim,
                    modifier = Modifier.padding(top = 16.dp),
                )
                else -> androidx.compose.runtime.CompositionLocalProvider(com.hispanoiptv.tv.ui.LocalEdge provides 24.dp) {
                PivotScroll(com.hispanoiptv.tv.ui.components.ROW_TOP_MARGIN) {
                    LazyColumn(
                        state = rememberLazyListState(),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 60.dp),
                    ) {
                        if (people.isNotEmpty()) {
                            item(key = "people") {
                                Shelf("Directores y estudios") {
                                    items(people, key = { it.id }) { def ->
                                        CollectionTile(
                                            title = def.title,
                                            subtitle = def.subtitle?.replaceFirstChar { it.uppercase() }
                                                ?: if (def.group == "directores") "Director" else "Estudio",
                                            onClick = { actions.openGrid(Kind.MOVIE, COLLECTION_PREFIX + def.id, def.title) },
                                            modifier = Modifier.focusMemory("sr:wd:${def.id}", memory),
                                        )
                                    }
                                }
                            }
                        }
                        if (results.channels.isNotEmpty()) {
                            item(key = "ch") {
                                Shelf("Canales", modifier = Modifier.padding(start = 0.dp)) {
                                    itemsIndexed(results.channels, key = { _, c -> c.id }) { i, c ->
                                        ChannelCard(
                                            c = c,
                                            width = 160.dp,
                                            onClick = { actions.playChannels(results.channels, i) },
                                            onLongClick = { actions.toggleFavorite(Kind.LIVE, c.remoteId, c.name, c.logo) },
                                            modifier = Modifier.focusMemory("sr:ch:${c.remoteId}", memory),
                                        )
                                    }
                                }
                            }
                        }
                        if (results.movies.isNotEmpty()) {
                            item(key = "mv") {
                                Shelf("Películas") {
                                    items(results.movies, key = { it.id }) { m ->
                                        MediaCard(
                                            title = cleanTitle(m.name),
                                            image = m.poster,
                                            width = 98.dp,
                                            rating = m.rating,
                                            onClick = { actions.openMovie(m) },
                                            onLongClick = { actions.toggleFavorite(Kind.MOVIE, m.remoteId, m.name, m.poster) },
                                            modifier = Modifier.focusMemory("sr:mv:${m.remoteId}", memory),
                                        )
                                    }
                                }
                            }
                        }
                        if (results.series.isNotEmpty()) {
                            item(key = "se") {
                                Shelf("Series") {
                                    items(results.series, key = { it.id }) { s ->
                                        MediaCard(
                                            title = cleanTitle(s.name),
                                            image = s.poster,
                                            width = 98.dp,
                                            rating = s.rating,
                                            onClick = { actions.openSeries(s) },
                                            onLongClick = { actions.toggleFavorite(Kind.SERIES, s.remoteId, s.name, s.poster) },
                                            modifier = Modifier.focusMemory("sr:se:${s.remoteId}", memory),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                }
            }
        }
    }
}
