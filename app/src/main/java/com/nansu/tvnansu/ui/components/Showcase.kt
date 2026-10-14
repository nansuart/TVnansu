package com.nansu.tvnansu.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nansu.tvnansu.data.db.Kind
import com.nansu.tvnansu.ui.LocalEdge
import com.nansu.tvnansu.ui.LocalRepository
import com.nansu.tvnansu.ui.LocalTopInset
import com.nansu.tvnansu.ui.RevealScroll
import com.nansu.tvnansu.ui.focusMemory
import com.nansu.tvnansu.ui.keepAtTop
import com.nansu.tvnansu.ui.theme.Palette
import com.nansu.tvnansu.ui.theme.Type
import kotlinx.coroutines.delay

/** One page of the Películas / Series carousel. */
data class ShowcaseItem(
    val key: String,
    val kind: Kind,
    val remoteId: String,
    val title: String,
    val eyebrow: String,
    val meta: String?,
    val description: String?,
    val backdrop: String?,
    val poster: String?,
)

/**
 * A screen with a carousel on top and rows underneath. The section title hides
 * when the list scrolls (reported through [onScrolled]) and the carousel always
 * comes back whole when focus returns to it.
 */
@Composable
fun ShowcaseScaffold(
    listState: LazyListState,
    onScrolled: (Boolean) -> Unit,
    top: (@Composable (Modifier) -> Unit)?,
    content: LazyListScope.() -> Unit,
) {
    val edge = LocalEdge.current
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 24 } }
    LaunchedEffect(scrolled) { onScrolled(scrolled) }
    RevealScroll(top = 40.dp) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = LocalTopInset.current, bottom = 72.dp),
        ) {
            if (top != null) {
                item(key = "showcase") {
                    top(
                        Modifier
                            .padding(start = edge, end = 36.dp, bottom = 20.dp)
                            .fillMaxWidth()
                            .height(268.dp)
                            .keepAtTop(listState)
                    )
                }
            }
            content()
        }
    }
}

/**
 * Full-width rounded carousel: the wide artwork fills the box, the text sits on
 * the left over a dark gradient so the picture still shows but always reads well.
 */
@Composable
fun Showcase(
    items: List<ShowcaseItem>,
    onOpen: (ShowcaseItem) -> Unit,
    onToggleList: (ShowcaseItem) -> Unit,
    inList: (ShowcaseItem) -> Boolean,
    memory: MutableState<String?>,
    memoryKey: String,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) {
        Skeleton(modifier, RoundedCornerShape(20.dp))
        return
    }
    val reduceMotion = LocalRepository.current.prefs.reduceMotion
    var index by rememberSaveable(memoryKey) { mutableIntStateOf(0) }
    var hasFocus by remember { mutableStateOf(false) }
    val i = index % items.size
    val item = items[i]
    LaunchedEffect(index, items.size, reduceMotion) {
        if (items.size < 2 || reduceMotion) return@LaunchedEffect
        delay(9_000)
        index = (index + 1) % items.size
    }
    val shape = RoundedCornerShape(20.dp)
    val borderAlpha by animateFloatAsState(if (hasFocus) 0.9f else 0.06f, label = "sb")
    Box(
        modifier
            .clip(shape)
            .background(Palette.Surface)
            .border(BorderStroke(if (hasFocus) 1.5.dp else 1.dp, Color.White.copy(alpha = borderAlpha)), shape)
            .onFocusChanged { hasFocus = it.hasFocus }
    ) {
        AnimatedContent(
            targetState = item,
            transitionSpec = {
                val d = if (reduceMotion) 0 else 700
                fadeIn(tween(d)) togetherWith fadeOut(tween(d))
            },
            contentKey = { it.key },
            label = "showcase",
        ) { s -> ShowcaseArt(s, reduceMotion) }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.9f),
                        0.34f to Color.Black.copy(alpha = 0.72f),
                        0.58f to Color.Black.copy(alpha = 0.2f),
                        0.78f to Color.Transparent,
                    )
                )
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f)))
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(0.5f)
                .padding(start = 26.dp, bottom = 22.dp)
        ) {
            Text(item.eyebrow, style = Type.Label.copy(fontWeight = FontWeight.SemiBold), color = Palette.Accent)
            Spacer(Modifier.height(6.dp))
            Text(
                item.title,
                style = Type.Display.copy(fontSize = 28.sp, lineHeight = 32.sp),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!item.meta.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(item.meta, style = Type.Label, color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!item.description.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    item.description,
                    style = Type.Caption,
                    color = Color.White.copy(alpha = 0.65f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier
                    .focusRestorer()
                    .focusGroup(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShowcaseButton(
                    text = if (item.kind == Kind.SERIES) "▶   Ver serie" else "▶   Ver película",
                    onClick = { onOpen(item) },
                    modifier = Modifier.focusMemory("$memoryKey:open", memory),
                )
                Spacer(Modifier.width(10.dp))
                ShowcaseButton(
                    text = if (inList(item)) "✓  En Mi lista" else "+  Mi lista",
                    onClick = { onToggleList(item) },
                )
                if (items.size > 1) {
                    Spacer(Modifier.width(16.dp))
                    ShowcaseRound("‹") { index = (index - 1 + items.size) % items.size }
                    Spacer(Modifier.width(8.dp))
                    ShowcaseRound("›") { index = (index + 1) % items.size }
                }
            }
        }
        if (items.size > 1) {
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(items.size) { d ->
                    val active = d == i
                    val w by animateDpAsState(if (active) 20.dp else 6.dp, label = "dot")
                    Box(
                        Modifier
                            .size(width = w, height = 6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (active) Color.White else Color.White.copy(alpha = 0.35f))
                    )
                }
            }
        }
    }
}

@Composable
private fun ShowcaseArt(s: ShowcaseItem, reduceMotion: Boolean) {
    val transition = rememberInfiniteTransition(label = "kb")
    val zoom by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (reduceMotion) 1f else 1.06f,
        animationSpec = infiniteRepeatable(tween(18_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "zoom",
    )
    Box(Modifier.fillMaxSize()) {
        if (s.backdrop != null) {
            AsyncImage(
                model = s.backdrop,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                    },
            )
        } else {
            // Only a poster: blurred as background, and the poster itself on the right.
            AsyncImage(
                model = s.poster,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 0.55f }
                    .blur(30.dp),
            )
            AsyncImage(
                model = s.poster,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 70.dp, top = 22.dp, bottom = 22.dp)
                    .fillMaxHeight()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(12.dp)),
            )
        }
    }
}

@Composable
private fun ShowcaseButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(38.dp),
        shape = ClickableSurfaceDefaults.shape(shape = PillShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.16f),
            contentColor = Color.White,
            focusedContainerColor = Palette.Focus,
            focusedContentColor = Color.Black,
        ),
    ) {
        Text(
            text,
            style = Type.Section,
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 18.dp),
        )
    }
}

@Composable
private fun ShowcaseRound(text: String, size: Dp = 38.dp, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(size),
        shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.16f),
            contentColor = Color.White,
            focusedContainerColor = Palette.Focus,
            focusedContentColor = Color.Black,
        ),
    ) {
        Text(text, style = Type.Title, modifier = Modifier.align(Alignment.Center).padding(bottom = 3.dp))
    }
}
