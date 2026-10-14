package com.nansu.tvnansu.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nansu.tvnansu.ui.LocalEdge
import com.nansu.tvnansu.ui.theme.Palette

/** Grey shape that breathes softly while the real content loads. */
@Composable
fun Skeleton(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(12.dp)) {
    val t = rememberInfiniteTransition(label = "sk")
    val a by t.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "a",
    )
    Box(
        modifier
            .graphicsLayer { alpha = a }
            .clip(shape)
            .background(Palette.Surface)
    )
}

/** A row title and a few cards, as placeholders. */
@Composable
fun SkeletonShelf(cardWidth: Dp = POSTER_W, aspect: Float = 2f / 3f, count: Int = 7) {
    val edge = LocalEdge.current
    Column(Modifier.padding(start = edge, top = 4.dp, bottom = 22.dp)) {
        Skeleton(Modifier.size(width = 150.dp, height = 14.dp), RoundedCornerShape(7.dp))
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(count) {
                Skeleton(
                    Modifier
                        .width(cardWidth)
                        .aspectRatio(aspect),
                    CardShape,
                )
            }
        }
    }
}
