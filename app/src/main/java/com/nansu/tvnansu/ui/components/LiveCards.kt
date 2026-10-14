package com.nansu.tvnansu.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.nansu.tvnansu.data.Capture
import com.nansu.tvnansu.data.LiveEvent
import com.nansu.tvnansu.data.db.ChannelEntity
import com.nansu.tvnansu.ui.Fmt
import com.nansu.tvnansu.ui.LocalRepository
import com.nansu.tvnansu.ui.theme.Palette
import com.nansu.tvnansu.ui.theme.Type

/** Last saved frame of a channel, refreshed whenever a new one is taken. */
@Composable
fun rememberCapture(channel: ChannelEntity?): Capture? {
    val repo = LocalRepository.current
    val v by repo.captures.version.collectAsState()
    return remember(channel?.playlistId, channel?.remoteId, v) {
        channel?.let { repo.captures.get(it.playlistId, it.remoteId) }
    }
}

@Composable
fun CaptureImage(capture: Capture, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val request = remember(capture) {
        ImageRequest.Builder(ctx)
            .data(capture.file)
            .memoryCacheKey("cap:${capture.file.name}:${capture.takenAt}")
            .diskCachePolicy(CachePolicy.DISABLED)
            .build()
    }
    AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
}

/**
 * Picture for a live channel: the last frame the user saw (with the logo in a
 * corner and how long ago it was taken) or, without one, the channel logo.
 */
@Composable
fun ChannelArt(
    channel: ChannelEntity,
    modifier: Modifier = Modifier,
    showAge: Boolean = true,
    logoWidth: Dp = 52.dp,
) {
    val capture = rememberCapture(channel)
    Box(modifier) {
        if (capture != null) {
            CaptureImage(capture, Modifier.fillMaxSize())
            if (showAge) {
                Text(
                    text = Fmt.ago(capture.takenAt).replaceFirstChar { it.uppercase() },
                    style = Type.Caption.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        } else {
            ChannelLogo(url = channel.logo, name = channel.name, modifier = Modifier.fillMaxSize())
        }
    }
}

/** Large card for a match, event or news broadcast that is on right now. */
@Composable
fun EventCard(
    event: LiveEvent,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    width: Dp = 228.dp,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    val capture = rememberCapture(event.channel)
    Surface(
        onClick = onClick,
        modifier = modifier
            .width(width)
            .height(width * 9f / 16f)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            },
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(1.5.dp, Palette.Focus), shape = shape),
        ),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = FocusGlow),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Palette.Surface,
            contentColor = Palette.Text,
            focusedContainerColor = Palette.Surface2,
            focusedContentColor = Palette.Text,
        ),
    ) {
        Box(Modifier.fillMaxSize()) {
            if (capture != null) {
                CaptureImage(capture, Modifier.fillMaxSize())
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.linearGradient(listOf(themeTint(event.label), Palette.Surface)))
                )
                ChannelLogo(
                    url = event.channel.logo,
                    name = "",
                    showNameFallback = false,
                    hideWhenMissing = true,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .size(width = 64.dp, height = 36.dp)
                        .clip(RoundedCornerShape(6.dp)),
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.15f),
                            0.45f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.85f),
                        )
                    )
            )
            LiveChip(
                text = event.label,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp),
            )
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Text(
                    text = event.title,
                    style = Type.Label.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White,
                    maxLines = if (eventSubtitle(event).isEmpty()) 3 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                val sub = eventSubtitle(event)
                if (sub.isNotEmpty()) {
                    Text(
                        text = sub,
                        style = Type.Caption,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (event.start > 0 && event.stop > event.start) {
                    Spacer(Modifier.height(6.dp))
                    ProgressLine(
                        fraction = (System.currentTimeMillis() - event.start).toFloat() / (event.stop - event.start),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** "● EN DIRECTO" style label. */
@Composable
fun LiveChip(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiveDot(Modifier.size(6.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = Type.Caption.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
    }
}

/** A row of the "En directo ahora" panel. */
@Composable
fun LiveNowRow(
    event: LiveEvent,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            },
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Palette.Text,
            focusedContainerColor = Palette.Surface3,
            focusedContentColor = Palette.Text,
        ),
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChannelLogo(
                url = event.channel.logo,
                name = event.channel.name.take(2).uppercase(),
                modifier = Modifier
                    .size(width = 54.dp, height = 30.dp)
                    .clip(RoundedCornerShape(6.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = event.title,
                    style = Type.Label.copy(fontWeight = FontWeight.SemiBold),
                    color = if (focused) Palette.Text else Palette.Text.copy(alpha = 0.88f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = event.label,
                        style = Type.Caption.copy(fontWeight = FontWeight.SemiBold),
                        color = Palette.Accent,
                    )
                    if (!event.channel.name.equals(event.title, ignoreCase = true)) Text(
                        text = "  ·  " + event.channel.name,
                        style = Type.Caption,
                        color = Palette.TextFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (event.start > 0 && event.stop > event.start) {
                Spacer(Modifier.width(8.dp))
                ProgressLine(
                    fraction = (System.currentTimeMillis() - event.start).toFloat() / (event.stop - event.start),
                    modifier = Modifier.width(36.dp),
                )
            }
        }
    }
}

/** Background tint for a live card without picture, by kind of broadcast. */
fun themeTint(label: String): Color = when (label) {
    "DEPORTES" -> Color(0xFF16402A)
    "NOTICIAS" -> Color(0xFF1B2A55)
    "EVENTO" -> Color(0xFF4A1E2A)
    else -> Palette.Surface3
}

/**
 * Fills its box with content of the given aspect ratio, cropping the overflow
 * (like "centre crop" for video), so nothing is ever stretched.
 */
@Composable
fun CoverBox(aspect: Float, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier.then(Modifier.clipToBounds()),
        contentAlignment = Alignment.Center,
    ) {
        val boxAspect = if (maxHeight.value > 0f) maxWidth / maxHeight else aspect
        val w = if (boxAspect > aspect) maxWidth else maxHeight * aspect
        val h = if (boxAspect > aspect) maxWidth / aspect else maxHeight
        Box(Modifier.requiredSize(w, h)) { content() }
    }
}

/** "ESPN · 23:00 – 00:00", without repeating the channel when it is also the title (PPV events). */
fun eventSubtitle(event: LiveEvent): String = listOfNotNull(
    event.channel.name.takeIf { !it.equals(event.title, ignoreCase = true) },
    if (event.start > 0) "${Fmt.clock(event.start)} – ${Fmt.clock(event.stop)}" else null,
).joinToString("  ·  ")
