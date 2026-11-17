package com.nansu.tvnansu.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nansu.tvnansu.data.LiveTheme
import com.nansu.tvnansu.data.ThemeSummary
import com.nansu.tvnansu.data.db.CategoryEntity
import com.nansu.tvnansu.data.db.ChannelEntity
import com.nansu.tvnansu.data.db.Kind
import com.nansu.tvnansu.data.db.ProgrammeEntity
import com.nansu.tvnansu.ui.Fmt
import com.nansu.tvnansu.ui.LocalRepository
import com.nansu.tvnansu.ui.PivotScroll
import com.nansu.tvnansu.ui.components.ChannelLogo
import com.nansu.tvnansu.ui.components.EmptyState
import com.nansu.tvnansu.ui.components.LiveDot
import com.nansu.tvnansu.ui.components.LivePreview
import com.nansu.tvnansu.ui.components.LoadingView
import com.nansu.tvnansu.ui.components.ProgressLine
import com.nansu.tvnansu.ui.rememberActions
import com.nansu.tvnansu.ui.theme.Palette
import com.nansu.tvnansu.ui.theme.Type
import kotlinx.coroutines.delay

private const val CAT_FAV = "__fav"
private const val CAT_RECENT = "__recent"
private const val CAT_ALL = "__all"
const val THEME_PREFIX = "theme:"

/** Minutes of programme shown across the screen at once. */
private const val SPAN_MIN = 120
private const val HALF_HOUR = 30 * 60_000L
private val CHANNEL_COL = 208.dp
private val ROW_H = 58.dp
private val ROW_GAP = 5.dp

private fun floorHalfHour(ms: Long) = ms - ms % HALF_HOUR

/** What the header describes: the focused channel and, when there is one, the focused programme. */
private data class GuideFocus(val channel: ChannelEntity, val programme: ProgrammeEntity?)

/**
 * Channel guide, laid out like a TV guide: channels down the left, time across the
 * top and each programme as a block as long as it lasts. A line marks the current
 * time. Moving right through the programmes scrolls the time forward. The header
 * describes the focused programme and shows the channel playing, muted.
 * OK plays the channel; holding OK adds it to Mis canales or takes it out.
 */
@Composable
fun LiveGuideScreen(start: String?) {
    val repo = LocalRepository.current
    val actions = rememberActions()
    val pid by repo.activeId.collectAsState()
    val version by repo.dataVersion.collectAsState()
    val epgVersion by repo.epgVersion.collectAsState()
    val favUids by remember(pid) { repo.favoriteUids(pid) }.collectAsState(initial = emptyList())
    val mine = remember(favUids, pid) { favUids.toHashSet() }

    val categories by produceState<List<CategoryEntity>?>(null, pid, version) {
        value = repo.dao.categories(pid, Kind.LIVE.name)
    }
    val themes by produceState(emptyList<ThemeSummary>(), pid, version) {
        value = repo.liveThemes(pid)
    }
    var selected by rememberSaveable { mutableStateOf(start ?: CAT_ALL) }
    var pendingCategory by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingCategory) {
        val p = pendingCategory ?: return@LaunchedEffect
        delay(350)
        selected = p
    }

    val channels by produceState<List<ChannelEntity>?>(null, pid, selected, version) {
        val sel = selected
        value = null
        value = when {
            sel == CAT_FAV -> repo.channelsByIds(pid, repo.favoritesOf(pid, Kind.LIVE).map { it.remoteId })
            sel == CAT_RECENT -> repo.channelsByIds(pid, repo.recentsOf(pid, 60).map { it.remoteId })
            sel == CAT_ALL -> repo.dao.allChannels(pid, 8000)
            sel.startsWith(THEME_PREFIX) ->
                LiveTheme.fromKey(sel.removePrefix(THEME_PREFIX))?.let { repo.channelsOfTheme(pid, it) } ?: emptyList()
            else -> repo.dao.channelsIn(pid, sel)
        }
    }

    // The clock: the guide starts at the current half hour and the "now" line moves every minute.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    val base = floorHalfHour(now)
    var winRequest by remember { mutableLongStateOf(base) }
    val winStart = maxOf(winRequest, base)
    val shownMin by animateFloatAsState(
        targetValue = (winStart - base) / 60_000f,
        animationSpec = tween(320, easing = FastOutSlowInEasing),
        label = "guideTime",
    )

    // Programmes of each channel, loaded as its row appears and kept while the screen lives.
    val cache = remember(pid, epgVersion) { HashMap<String, List<ProgrammeEntity>>() }
    var focus by remember { mutableStateOf<GuideFocus?>(null) }
    val firstRequester = remember { FocusRequester() }
    var initialDone by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(channels) {
        if (initialDone || channels.isNullOrEmpty()) return@LaunchedEffect
        delay(200)
        runCatching { firstRequester.requestFocus() }
        initialDone = true
    }

    val cats = categories
    if (cats == null) {
        LoadingView()
        return
    }

    val chips = listOf(
        CAT_FAV to "Mis canales",
        CAT_RECENT to "Recientes",
        CAT_ALL to "Todos",
    ) + themes.map { (THEME_PREFIX + it.theme.name) to it.theme.title } + cats.map { it.remoteId to it.name }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .padding(start = 40.dp, end = 32.dp, top = 22.dp)
    ) {
        GuideHeader(focus = focus, now = now, modifier = Modifier.fillMaxWidth().height(148.dp))
        Spacer(Modifier.height(10.dp))

        // Lists: Mis canales, recents, everything, themes and the provider's categories.
        val chipState = rememberLazyListState()
        LaunchedEffect(cats.size) {
            val i = chips.indexOfFirst { it.first == selected }
            if (i > 0) chipState.scrollToItem((i - 1).coerceAtLeast(0))
        }
        PivotScroll(0.dp) {
            LazyRow(
                state = chipState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .focusRestorer(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(chips, key = { it.first }) { (id, name) ->
                    GuideChip(
                        name = name,
                        selected = selected == id,
                        onFocus = { pendingCategory = id },
                        onClick = { pendingCategory = null; selected = id },
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val timelineW = maxWidth - CHANNEL_COL - 8.dp
            val perMin: Dp = timelineW / SPAN_MIN
            val list = channels
            Column(Modifier.fillMaxSize()) {
                TimeRuler(base = base, shownMin = shownMin, perMin = perMin, timelineW = timelineW)
                Spacer(Modifier.height(6.dp))
                when {
                    list == null -> LoadingView()
                    list.isEmpty() -> EmptyState(
                        title = if (selected == CAT_FAV) "Aún no tienes canales en Mis canales" else "Sin canales",
                        message = if (selected == CAT_FAV) "Entra en Todos o en cualquier lista y mantén pulsado OK sobre un canal para añadirlo." else null,
                    )
                    else -> PivotScroll(ROW_H + ROW_GAP) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .focusRestorer(),
                            contentPadding = PaddingValues(bottom = 40.dp),
                            verticalArrangement = Arrangement.spacedBy(ROW_GAP),
                        ) {
                            itemsIndexed(list, key = { _, c -> c.id }) { index, c ->
                                val epg = c.epgId?.takeIf { it.isNotBlank() }
                                val programmes by produceState(epg?.let { cache[it] }, epg, epgVersion, base) {
                                    if (epg == null) { value = emptyList(); return@produceState }
                                    val got = repo.dao.programmesBetween(pid, epg, base - 6 * 3_600_000L, base + 14 * 3_600_000L)
                                    cache[epg] = got
                                    value = got
                                }
                                GuideRow(
                                    channel = c,
                                    programmes = programmes,
                                    isMine = repo.uid(pid, Kind.LIVE, c.remoteId) in mine,
                                    now = now,
                                    base = base,
                                    winStart = winStart,
                                    shownMin = shownMin,
                                    perMin = perMin,
                                    timelineW = timelineW,
                                    cellModifier = if (index == 0) Modifier.focusRequester(firstRequester) else Modifier,
                                    onFocus = { p ->
                                        focus = GuideFocus(c, p)
                                        if (p != null) {
                                            val end = winStart + SPAN_MIN * 60_000L
                                            winRequest = when {
                                                p.start >= end - 20 * 60_000L -> (floorHalfHour(p.start) - HALF_HOUR).coerceAtLeast(base)
                                                p.start < winStart -> floorHalfHour(p.start).coerceAtLeast(base)
                                                else -> winStart
                                            }
                                        }
                                    },
                                    onPlay = { actions.playChannels(list, index) },
                                    onToggleMine = { actions.toggleFavorite(Kind.LIVE, c.remoteId, c.name, c.logo) },
                                )
                            }
                        }
                    }
                }
            }
            // The current time, across every row.
            val nowX = CHANNEL_COL + 8.dp + perMin * ((now - base) / 60_000f - shownMin)
            if (list?.isNotEmpty() == true && nowX >= CHANNEL_COL + 8.dp && nowX <= maxWidth) {
                Box(
                    Modifier
                        .offset(x = nowX - 1.dp, y = 18.dp)
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(Palette.Accent.copy(alpha = 0.85f))
                )
            }
        }
    }
}

@Composable
private fun GuideHeader(focus: GuideFocus?, now: Long, modifier: Modifier = Modifier) {
    val repo = LocalRepository.current
    Row(modifier, verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f).padding(end = 24.dp)) {
            val f = focus
            if (f == null) {
                Text("Guía de canales", style = Type.Display, color = Palette.Text)
                Spacer(Modifier.height(8.dp))
                Text(
                    "OK para ver un canal  ·  Mantén pulsado OK para añadirlo a Mis canales",
                    style = Type.Body,
                    color = Palette.TextFaint,
                )
                return@Column
            }
            val p = f.programme
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (f.channel.number > 0) {
                    Text("${f.channel.number}", style = Type.Label, color = Palette.Accent)
                    Spacer(Modifier.width(8.dp))
                }
                Text(f.channel.name, style = Type.Label, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                p?.title ?: "Sin información de programación",
                style = Type.Title,
                color = Palette.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            if (p != null) {
                val airing = p.start <= now && p.stop > now
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (airing) {
                        LiveDot()
                        Spacer(Modifier.width(6.dp))
                        Text("AHORA", style = Type.Caption, color = Palette.Accent)
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(
                        "${Fmt.clock(p.start)} – ${Fmt.clock(p.stop)}" +
                            if (airing) "  ·  ${Fmt.minutesLeft(p.stop - now)}" else "",
                        style = Type.Caption,
                        color = Palette.TextDim,
                    )
                }
                if (airing) {
                    Spacer(Modifier.height(6.dp))
                    ProgressLine(
                        fraction = (now - p.start).toFloat() / (p.stop - p.start).coerceAtLeast(1),
                        modifier = Modifier.width(260.dp),
                    )
                }
                if (!p.description.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(p.description, style = Type.Body, color = Palette.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        // The focused channel, muted, once focus rests on it.
        val ch = focus?.channel
        Box(
            Modifier
                .size(width = 263.dp, height = 148.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Palette.Surface)
        ) {
            if (ch != null) {
                LivePreview(
                    url = repo.liveUrl(ch),
                    enabled = repo.prefs.livePreview,
                    channel = ch,
                    startDelayMs = 1_400,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        ChannelLogo(
                            url = ch.logo,
                            name = ch.name,
                            modifier = Modifier.size(width = 150.dp, height = 84.dp).clip(RoundedCornerShape(10.dp)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimeRuler(base: Long, shownMin: Float, perMin: Dp, timelineW: Dp) {
    Row(Modifier.fillMaxWidth().height(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Hoy",
            style = Type.Caption,
            color = Palette.TextFaint,
            modifier = Modifier.width(CHANNEL_COL + 8.dp).padding(start = 4.dp),
        )
        Box(Modifier.width(timelineW).fillMaxHeight().clipToBounds()) {
            // A mark every half hour, from the start of the guide to well past the right edge.
            for (i in 0..((shownMin.toInt() + SPAN_MIN) / 30 + 1)) {
                val minute = i * 30
                val x = perMin * (minute - shownMin)
                if (x < (-60).dp || x > timelineW) continue
                Text(
                    Fmt.clock(base + minute * 60_000L),
                    style = Type.Caption,
                    color = Palette.TextDim,
                    modifier = Modifier.offset(x = x + 4.dp),
                )
            }
        }
    }
}

@Composable
private fun GuideRow(
    channel: ChannelEntity,
    programmes: List<ProgrammeEntity>?,
    isMine: Boolean,
    now: Long,
    base: Long,
    winStart: Long,
    shownMin: Float,
    perMin: Dp,
    timelineW: Dp,
    cellModifier: Modifier,
    onFocus: (ProgrammeEntity?) -> Unit,
    onPlay: () -> Unit,
    onToggleMine: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(ROW_H)) {
        ChannelCell(
            channel = channel,
            isMine = isMine,
            onFocus = { onFocus(programmes?.firstOrNull { it.start <= now && it.stop > now }) },
            onClick = onPlay,
            onLongClick = onToggleMine,
            modifier = cellModifier,
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.width(timelineW).fillMaxHeight().clipToBounds()) {
            val list = programmes
            if (list == null) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Palette.Surface.copy(alpha = 0.5f))
                )
                return@Box
            }
            // Programmes inside the visible time and the next ones just past the right edge,
            // so moving right can reach them (the guide then scrolls).
            val from = winStart
            val to = winStart + (SPAN_MIN + 90) * 60_000L
            val shown = list.filter { it.stop > from && it.start < to && it.stop > base }
            if (shown.isEmpty()) {
                ProgrammeBlock(
                    title = "Sin guía de programación",
                    time = null,
                    airing = true,
                    fraction = null,
                    onFocus = { onFocus(null) },
                    onClick = onPlay,
                    onLongClick = onToggleMine,
                    modifier = Modifier.fillMaxSize(),
                )
                return@Box
            }
            for (p in shown) {
                androidx.compose.runtime.key(p.id) {
                    val startMin = ((maxOf(p.start, base) - base) / 60_000f)
                    val endMin = ((p.stop - base) / 60_000f)
                    val x = perMin * (startMin - shownMin)
                    val w = (perMin * (endMin - maxOf(startMin, shownMin))).coerceAtLeast(24.dp)
                    val left = if (x < 0.dp) 0.dp else x
                    val airing = p.start <= now && p.stop > now
                    ProgrammeBlock(
                        title = p.title,
                        time = "${Fmt.clock(p.start)} – ${Fmt.clock(p.stop)}",
                        airing = airing,
                        fraction = if (airing) (now - p.start).toFloat() / (p.stop - p.start).coerceAtLeast(1) else null,
                        onFocus = { onFocus(p) },
                        onClick = onPlay,
                        onLongClick = onToggleMine,
                        modifier = Modifier
                            .offset(x = left)
                            .width((w - 4.dp).coerceAtLeast(20.dp))
                            .fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelCell(
    channel: ChannelEntity,
    isMine: Boolean,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .width(CHANNEL_COL)
            .fillMaxHeight()
            .onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Palette.Surface,
            contentColor = Palette.Text,
            focusedContainerColor = Palette.Focus,
            focusedContentColor = Color.Black,
        ),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (channel.number > 0) channel.number.toString() else "",
                style = Type.Caption,
                modifier = Modifier.width(30.dp),
                maxLines = 1,
            )
            ChannelLogo(
                url = channel.logo,
                name = channel.name.take(2).uppercase(),
                modifier = Modifier
                    .size(width = 58.dp, height = 34.dp)
                    .clip(RoundedCornerShape(6.dp)),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                channel.name,
                style = Type.Caption,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (isMine) {
                Spacer(Modifier.width(6.dp))
                Heart(Modifier.size(12.dp))
            }
        }
    }
}

@Composable
private fun ProgrammeBlock(
    title: String,
    time: String?,
    airing: Boolean,
    fraction: Float?,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.onFocusChanged {
            focused = it.isFocused
            if (it.isFocused) onFocus()
        },
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (airing) Palette.Surface2 else Palette.Surface,
            contentColor = if (airing) Palette.Text else Palette.TextDim,
            focusedContainerColor = Palette.Focus,
            focusedContentColor = Color.Black,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, Palette.Bg), shape = RoundedCornerShape(10.dp)),
        ),
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(title, style = Type.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (time != null) {
                    Text(
                        time,
                        style = Type.Caption,
                        color = if (focused) Color.Black.copy(alpha = 0.6f) else Palette.TextFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                }
            }
            if (fraction != null && !focused) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(Palette.Accent)
                )
            }
        }
    }
}

@Composable
private fun GuideChip(name: String, selected: Boolean, onFocus: () -> Unit, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .height(36.dp)
            .onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(50)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Palette.Surface3 else Palette.Surface,
            contentColor = if (selected) Palette.Text else Palette.TextDim,
            focusedContainerColor = Palette.Focus,
            focusedContentColor = Color.Black,
        ),
    ) {
        Row(Modifier.padding(horizontal = 16.dp).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            if (selected) {
                Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(Palette.Accent))
                Spacer(Modifier.width(8.dp))
            }
            Text(name, style = Type.Label, maxLines = 1)
        }
    }
}

/** Small filled heart: the channel is in Mis canales. */
@Composable
fun Heart(modifier: Modifier = Modifier, color: Color = Palette.Accent) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w / 2f, h * 0.92f)
            cubicTo(w * 0.08f, h * 0.62f, -w * 0.02f, h * 0.22f, w * 0.27f, h * 0.1f)
            cubicTo(w * 0.40f, h * 0.05f, w * 0.48f, h * 0.16f, w / 2f, h * 0.24f)
            cubicTo(w * 0.52f, h * 0.16f, w * 0.60f, h * 0.05f, w * 0.73f, h * 0.1f)
            cubicTo(w * 1.02f, h * 0.22f, w * 0.92f, h * 0.62f, w / 2f, h * 0.92f)
            close()
        }
        drawPath(path, color)
    }
}
