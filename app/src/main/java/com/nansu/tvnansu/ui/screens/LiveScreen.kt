package com.nansu.tvnansu.ui.screens

import com.nansu.tvnansu.ui.components.ChannelLogo
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.onFocusChanged
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nansu.tvnansu.data.NowNext
import com.nansu.tvnansu.data.db.CategoryEntity
import com.nansu.tvnansu.data.db.ChannelEntity
import com.nansu.tvnansu.data.db.Kind
import com.nansu.tvnansu.data.db.ProgrammeEntity
import com.nansu.tvnansu.ui.Fmt
import com.nansu.tvnansu.ui.LocalRepository
import com.nansu.tvnansu.ui.PivotScroll
import com.nansu.tvnansu.ui.components.EmptyState
import com.nansu.tvnansu.ui.components.LiveDot
import com.nansu.tvnansu.ui.components.LoadingView
import com.nansu.tvnansu.ui.components.ProgressLine
import com.nansu.tvnansu.ui.focusMemory
import com.nansu.tvnansu.ui.rememberActions
import com.nansu.tvnansu.ui.rememberFocusMemory
import com.nansu.tvnansu.ui.theme.Palette
import com.nansu.tvnansu.ui.theme.Type
import kotlinx.coroutines.delay

private const val CAT_FAV = "__fav"
private const val CAT_RECENT = "__recent"
private const val CAT_ALL = "__all"

@Composable
fun LiveScreen() {
    val repo = LocalRepository.current
    val actions = rememberActions()
    val memory = rememberFocusMemory()
    val pid by repo.activeId.collectAsState()
    val version by repo.dataVersion.collectAsState()
    val epgVersion by repo.epgVersion.collectAsState()

    val categories by produceState<List<CategoryEntity>?>(null, pid, version) {
        value = repo.dao.categories(pid, Kind.LIVE.name)
    }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCategory by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(categories) {
        val cats = categories ?: return@LaunchedEffect
        if (selected == null) {
            val favCount = repo.favoritesOf(pid, Kind.LIVE).size
            selected = if (favCount > 0) CAT_FAV else cats.firstOrNull()?.remoteId ?: CAT_ALL
        }
    }
    LaunchedEffect(pendingCategory) {
        val p = pendingCategory ?: return@LaunchedEffect
        delay(250)
        selected = p
    }

    val channels by produceState<List<ChannelEntity>?>(null, pid, selected, version) {
        val sel = selected ?: return@produceState
        value = null
        value = when (sel) {
            CAT_FAV -> repo.channelsByIds(pid, repo.favoritesOf(pid, Kind.LIVE).map { it.remoteId })
            CAT_RECENT -> repo.channelsByIds(pid, repo.recentsOf(pid, 60).map { it.remoteId })
            CAT_ALL -> repo.dao.allChannels(pid, 8000)
            else -> repo.dao.channelsIn(pid, sel)
        }
    }

    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            tick = System.currentTimeMillis()
        }
    }
    val nowMap by produceState(emptyMap<String, ProgrammeEntity>(), channels, epgVersion, tick) {
        value = channels?.let { repo.nowPlaying(it) } ?: emptyMap()
    }

    var focusedChannel by remember { mutableStateOf<ChannelEntity?>(null) }

    val cats = categories
    if (cats == null) {
        LoadingView()
        return
    }
    if (cats.isEmpty() && selected == CAT_ALL && channels?.isEmpty() == true) {
        EmptyState("No hay canales en esta lista")
        return
    }

    val virtual = listOf(
        CAT_FAV to "Favoritos",
        CAT_RECENT to "Recientes",
        CAT_ALL to "Todos los canales",
    )

    Row(
        Modifier
            .fillMaxSize()
            .padding(top = 76.dp)
    ) {
        // Categories
        PivotScroll(120.dp) {
            LazyColumn(
                modifier = Modifier
                    .width(236.dp)
                    .fillMaxHeight()
                    .focusRestorer(),
                contentPadding = PaddingValues(start = 36.dp, end = 12.dp, top = 8.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(virtual, key = { it.first }) { (id, name) ->
                    CategoryItem(
                        name = name,
                        selected = selected == id,
                        onFocus = { pendingCategory = id },
                        onClick = { selected = id },
                        modifier = Modifier.focusMemory("live:cat:$id", memory),
                    )
                }
                item(key = "divider") {
                    Box(
                        Modifier
                            .padding(vertical = 8.dp, horizontal = 12.dp)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Palette.Surface3)
                    )
                }
                items(cats, key = { "c:" + it.remoteId }) { c ->
                    CategoryItem(
                        name = c.name,
                        selected = selected == c.remoteId,
                        onFocus = { pendingCategory = c.remoteId },
                        onClick = { selected = c.remoteId },
                        modifier = Modifier.focusMemory("live:cat:${c.remoteId}", memory),
                    )
                }
            }
        }

        // Channels
        val list = channels
        Box(
            Modifier
                .width(400.dp)
                .fillMaxHeight()
        ) {
            when {
                list == null -> LoadingView()
                list.isEmpty() -> EmptyState(
                    title = if (selected == CAT_FAV) "Sin favoritos" else "Sin canales",
                    message = if (selected == CAT_FAV) "Mantén pulsado OK sobre un canal para añadirlo." else null,
                )
                else -> PivotScroll(140.dp) {
                    LazyColumn(
                        state = rememberLazyListState(),
                        modifier = Modifier
                            .fillMaxSize()
                            .focusRestorer(),
                        contentPadding = PaddingValues(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 60.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        itemsIndexed(list, key = { _, c -> c.id }) { index, c ->
                            ChannelRow(
                                channel = c,
                                now = c.epgId?.let { nowMap[it] },
                                onFocus = { focusedChannel = c },
                                onClick = { actions.playChannels(list, index) },
                                onLongClick = { actions.toggleFavorite(Kind.LIVE, c.remoteId, c.name, c.logo) },
                                modifier = Modifier.focusMemory("live:ch:${c.remoteId}", memory),
                            )
                        }
                    }
                }
            }
        }

        // Details of the focused channel
        ChannelDetailPane(
            channel = focusedChannel,
            epgVersion = epgVersion,
            modifier = Modifier
                .fillMaxHeight()
                .weight(1f)
                .padding(start = 24.dp, end = 48.dp, top = 12.dp),
        )
    }
}

@Composable
private fun CategoryItem(
    name: String,
    selected: Boolean,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Palette.Surface2 else Color.Transparent,
            contentColor = if (selected) Palette.Text else Palette.TextDim,
            focusedContainerColor = Palette.Focus,
            focusedContentColor = Color.Black,
        ),
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected) {
                Box(
                    Modifier
                        .size(width = 3.dp, height = 16.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Palette.Accent)
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(name, style = Type.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ChannelRow(
    channel: ChannelEntity,
    now: ProgrammeEntity?,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            },
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Palette.Text,
            focusedContainerColor = Palette.Surface2,
            focusedContentColor = Palette.Text,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Palette.Focus), shape = RoundedCornerShape(12.dp))
        ),
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (channel.number > 0) channel.number.toString() else "",
                style = Type.Caption,
                color = Palette.TextFaint,
                modifier = Modifier.width(36.dp),
            )
            ChannelLogo(
                url = channel.logo,
                name = channel.name.take(2).uppercase(),
                modifier = Modifier
                    .size(width = 64.dp, height = 36.dp)
                    .clip(RoundedCornerShape(6.dp)),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = channel.name,
                    style = Type.Label,
                    color = if (focused) Palette.Text else Palette.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (now != null) {
                    Text(
                        text = now.title,
                        style = Type.Caption,
                        color = Palette.TextFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    val total = (now.stop - now.start).coerceAtLeast(1)
                    ProgressLine(
                        fraction = (System.currentTimeMillis() - now.start).toFloat() / total,
                        modifier = Modifier.fillMaxWidth(0.7f),
                        color = if (focused) Palette.Accent else Palette.TextFaint,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelDetailPane(channel: ChannelEntity?, epgVersion: Int, modifier: Modifier = Modifier) {
    val repo = LocalRepository.current
    val nowNext by produceState<NowNext?>(null, channel, epgVersion) {
        value = null
        val c = channel ?: return@produceState
        delay(200)
        value = repo.nowNext(c)
    }
    Column(modifier) {
        if (channel == null) {
            Spacer(Modifier.height(40.dp))
            Text("Elige un canal", style = Type.Title, color = Palette.TextDim)
            Spacer(Modifier.height(8.dp))
            Text(
                "Arriba y abajo para moverte, OK para verlo y mantén pulsado OK para añadirlo a favoritos.",
                style = Type.Body,
                color = Palette.TextFaint,
            )
            return@Column
        }
        ChannelLogo(
            url = channel.logo,
            name = channel.name,
            modifier = Modifier
                .size(width = 200.dp, height = 112.dp)
                .clip(RoundedCornerShape(14.dp)),
        )
        Spacer(Modifier.height(18.dp))
        Text(channel.name, style = Type.Title, color = Palette.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(18.dp))
        val nn = nowNext
        if (nn?.nowTitle != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LiveDot()
                Spacer(Modifier.width(8.dp))
                Text(
                    "AHORA  ·  ${Fmt.clock(nn.nowStart)} – ${Fmt.clock(nn.nowStop)}",
                    style = Type.Caption,
                    color = Palette.Accent,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(nn.nowTitle, style = Type.Section, color = Palette.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            val total = (nn.nowStop - nn.nowStart).coerceAtLeast(1)
            ProgressLine(
                fraction = (System.currentTimeMillis() - nn.nowStart).toFloat() / total,
                modifier = Modifier.fillMaxWidth(0.8f),
            )
            if (!nn.nowDescription.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(nn.nowDescription, style = Type.Body, color = Palette.TextDim, maxLines = 5, overflow = TextOverflow.Ellipsis)
            }
        } else {
            Text("Sin información de programación", style = Type.Body, color = Palette.TextFaint)
        }
        if (nn?.nextTitle != null) {
            Spacer(Modifier.height(20.dp))
            Text("A CONTINUACIÓN  ·  ${Fmt.clock(nn.nextStart)}", style = Type.Caption, color = Palette.TextFaint)
            Spacer(Modifier.height(4.dp))
            Text(nn.nextTitle, style = Type.Label, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
