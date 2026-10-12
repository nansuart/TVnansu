package com.nansu.tvnansu.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nansu.tvnansu.ui.theme.Palette
import com.nansu.tvnansu.ui.theme.Type

val CardShape = RoundedCornerShape(10.dp)

/**
 * Artwork on a soft gradient. The title is shown only while there is no image
 * (missing or failed URL), so transparent logos never sit on top of text.
 */
@Composable
fun ArtImage(
    url: String?,
    title: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    imagePadding: Dp = 0.dp,
    fallbackColors: List<Color> = listOf(Palette.Surface2, Palette.Surface),
) {
    var loaded by remember(url) { mutableStateOf(false) }
    Box(modifier.background(Brush.verticalGradient(fallbackColors))) {
        if (!loaded) {
            Text(
                text = title,
                style = Type.Label,
                color = Palette.TextDim,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(10.dp),
            )
        }
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = contentScale,
                onSuccess = { loaded = true },
                onError = { loaded = false },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(imagePadding),
            )
        }
    }
}

/** "★ 7,4" chip. Hidden when the provider sends no rating. */
@Composable
fun RatingChip(rating: Float, modifier: Modifier = Modifier) {
    if (rating <= 0f) return
    Text(
        text = "★ " + String.format("%.1f", rating),
        style = Type.Caption,
        color = Color.White,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.66f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

private val trailingYear = Regex("""\s*[\(\[]\s*(19|20)\d{2}\s*[\)\]]\s*$""")

/** Removes a trailing "(2026)" from provider titles; the year is shown separately. */
fun cleanTitle(name: String): String = trailingYear.replace(name, "").ifBlank { name }

@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, color: Color = Palette.Accent) {
    Box(
        modifier
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color.White.copy(alpha = 0.22f))
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .background(color)
        )
    }
}

/**
 * The basic focusable card: poster (2:3) or landscape (16:9) artwork,
 * lifts and gains a white outline when focused.
 */
@Composable
fun MediaCard(
    title: String,
    image: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 132.dp,
    aspectRatio: Float = 2f / 3f,
    subtitle: String? = null,
    progress: Float? = null,
    onLongClick: (() -> Unit)? = null,
    onFocused: () -> Unit = {},
    imageScale: ContentScale = ContentScale.Crop,
    imagePadding: Dp = 0.dp,
    showTitle: Boolean = true,
    badge: String? = null,
    rating: Float = 0f,
    art: (@Composable () -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(width)) {
        Surface(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused()
                },
            shape = ClickableSurfaceDefaults.shape(shape = CardShape),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(border = BorderStroke(2.dp, Palette.Focus), shape = CardShape)
            ),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Palette.Surface,
                contentColor = Palette.Text,
                focusedContainerColor = Palette.Surface2,
                focusedContentColor = Palette.Text,
            ),
        ) {
            if (art != null) {
                art()
            } else {
                ArtImage(
                    url = image,
                    title = title,
                    contentScale = imageScale,
                    imagePadding = imagePadding,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (rating > 0f) {
                RatingChip(
                    rating = rating,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(7.dp),
                )
            }
            if (badge != null) {
                Text(
                    text = badge,
                    style = Type.Caption,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (progress != null && progress > 0f) {
                ProgressLine(
                    fraction = progress,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                )
            }
        }
        if (showTitle) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = title,
                style = Type.Label,
                color = if (focused) Palette.Text else Palette.TextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = Type.Caption,
                    color = Palette.TextFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Last card of a row: opens the full category. */
@Composable
fun ViewAllCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 132.dp,
    aspectRatio: Float = 2f / 3f,
) {
    Column(Modifier.width(width)) {
        Surface(
            onClick = onClick,
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio),
            shape = ClickableSurfaceDefaults.shape(shape = CardShape),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Palette.Surface,
                contentColor = Palette.TextDim,
                focusedContainerColor = Palette.Focus,
                focusedContentColor = Color.Black,
            ),
        ) {
            Text(
                text = "Ver todo  →",
                style = Type.Section,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}
