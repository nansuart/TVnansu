package com.hispanoiptv.tv.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.hispanoiptv.tv.ui.theme.Palette
import com.hispanoiptv.tv.ui.theme.Type

/** What the hero area shows for the focused item. */
data class HeroData(
    val title: String,
    val image: String?,
    val wide: Boolean,
    val meta: String? = null,
    val description: String? = null,
    val eyebrow: String? = null,
)

/**
 * Large artwork in the top-right that cross-fades as focus moves, with a slow
 * zoom (Apple TV style). Posters without a wide backdrop are blurred so they
 * read as ambient colour instead of a stretched image.
 */
@Composable
fun HeroBackdrop(image: String?, wide: Boolean, reduceMotion: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "kenburns")
    val zoom by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (reduceMotion) 1f else 1.07f,
        animationSpec = infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "zoom",
    )
    Box(modifier.fillMaxSize()) {
        Crossfade(
            targetState = image to wide,
            animationSpec = tween(if (reduceMotion) 0 else 650),
            label = "hero",
        ) { (img, isWide) ->
            Box(Modifier.fillMaxSize()) {
                if (!img.isNullOrBlank()) {
                    AsyncImage(
                        model = img,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .fillMaxWidth(0.72f)
                            .fillMaxHeight(0.80f)
                            .graphicsLayer {
                                scaleX = zoom
                                scaleY = zoom
                                alpha = if (isWide) 1f else 0.6f
                            }
                            .then(if (isWide) Modifier else Modifier.blur(32.dp)),
                    )
                }
            }
        }
        // Left fade so text stays readable.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Palette.Bg,
                        0.30f to Palette.Bg,
                        0.55f to Palette.Bg.copy(alpha = 0.55f),
                        1f to Color.Transparent,
                    )
                )
        )
        // Bottom fade into the rows.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Palette.Bg.copy(alpha = 0.45f),
                        0.22f to Color.Transparent,
                        0.50f to Palette.Bg.copy(alpha = 0.25f),
                        0.78f to Palette.Bg,
                        1f to Palette.Bg,
                    )
                )
        )
    }
}

/** Title, metadata and synopsis of the focused item. */
@Composable
fun HeroText(data: HeroData?, modifier: Modifier = Modifier) {
    Column(modifier) {
        if (data == null) return@Column
        if (data.eyebrow != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (data.eyebrow == "EN DIRECTO") {
                    LiveDot()
                    Spacer(Modifier.width(8.dp))
                }
                Text(data.eyebrow, style = Type.Label, color = Palette.Accent)
            }
            Spacer(Modifier.height(8.dp))
        }
        Text(
            text = data.title,
            style = Type.Display,
            color = Palette.Text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (!data.meta.isNullOrBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(data.meta, style = Type.Label, color = Palette.TextDim, maxLines = 1)
        }
        if (!data.description.isNullOrBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = data.description,
                style = Type.Body,
                color = Palette.TextDim,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
