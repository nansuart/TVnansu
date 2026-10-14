package com.nansu.tvnansu.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.transformations
import coil3.size.Size
import coil3.transform.Transformation
import com.nansu.tvnansu.ui.LocalRepository
import com.nansu.tvnansu.ui.theme.Palette
import com.nansu.tvnansu.ui.theme.Type
import kotlin.math.max
import kotlin.math.min

/** How transparent channel logos are drawn. */
object LogoStyle {
    const val COLOR = 0
    const val WHITE = 1
}

/**
 * Turns a channel logo into a finished 16:9 tile:
 * - logos with their own background: the tile is filled with that edge colour, so
 *   the logo looks complete instead of a cut-out rectangle;
 * - transparent logos with dark parts: placed on a light plate so nothing disappears;
 * - transparent light logos: left on the dark card (or painted white in WHITE style).
 */
class LogoTileTransformation(private val style: Int) : Transformation() {

    override val cacheKey: String = "logoTile-v1-$style"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val outW = 480
        val outH = 270
        val sample = sampleOf(input)
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val padX: Float
        val padY: Float
        when {
            sample.opaqueEdges -> {
                canvas.drawColor(sample.edgeColor)
                padX = outW * 0.10f
                padY = outH * 0.12f
            }
            style == LogoStyle.WHITE -> {
                paint.colorFilter = PorterDuffColorFilter(AColor.WHITE, PorterDuff.Mode.SRC_IN)
                padX = outW * 0.16f
                padY = outH * 0.22f
            }
            sample.darkShare > 0.22f -> {
                canvas.drawColor(LIGHT_PLATE)
                padX = outW * 0.14f
                padY = outH * 0.20f
            }
            else -> {
                padX = outW * 0.16f
                padY = outH * 0.22f
            }
        }

        val boxW = outW - 2 * padX
        val boxH = outH - 2 * padY
        val scale = min(boxW / input.width, boxH / input.height)
        val w = input.width * scale
        val h = input.height * scale
        val left = (outW - w) / 2f
        val top = (outH - h) / 2f
        canvas.drawBitmap(input, null, RectF(left, top, left + w, top + h), paint)
        return out
    }

    private class Sample(val opaqueEdges: Boolean, val edgeColor: Int, val darkShare: Float)

    private fun sampleOf(src: Bitmap): Sample {
        // Work on a small copy: enough to read edges and tones, cheap on TV boxes.
        val target = 96
        val ratio = max(src.width, src.height).toFloat() / target
        val small = if (ratio > 1f) {
            Bitmap.createScaledBitmap(
                src,
                max(1, (src.width / ratio).toInt()),
                max(1, (src.height / ratio).toInt()),
                true,
            )
        } else src
        val w = small.width
        val h = small.height
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)

        // Edge pixels: is the logo sitting on its own background?
        var edgeCount = 0
        var edgeOpaque = 0
        var r = 0L
        var g = 0L
        var b = 0L
        fun edge(c: Int) {
            edgeCount++
            if (AColor.alpha(c) >= 220) {
                edgeOpaque++
                r += AColor.red(c); g += AColor.green(c); b += AColor.blue(c)
            }
        }
        for (x in 0 until w) {
            edge(px[x]); edge(px[(h - 1) * w + x])
        }
        for (y in 1 until h - 1) {
            edge(px[y * w]); edge(px[y * w + w - 1])
        }
        val opaqueEdges = edgeCount > 0 && edgeOpaque >= edgeCount * 0.85f
        val edgeColor = if (edgeOpaque > 0) {
            AColor.rgb((r / edgeOpaque).toInt(), (g / edgeOpaque).toInt(), (b / edgeOpaque).toInt())
        } else AColor.TRANSPARENT

        // Share of visible pixels that are dark: these vanish on a dark card.
        var visible = 0
        var dark = 0
        for (c in px) {
            if (AColor.alpha(c) < 128) continue
            visible++
            val lum = (0.2126f * AColor.red(c) + 0.7152f * AColor.green(c) + 0.0722f * AColor.blue(c)) / 255f
            if (lum < 0.30f) dark++
        }
        val darkShare = if (visible == 0) 0f else dark.toFloat() / visible
        if (small !== src) small.recycle()
        return Sample(opaqueEdges, edgeColor, darkShare)
    }

    override fun equals(other: Any?) = other is LogoTileTransformation && other.style == style
    override fun hashCode() = style

    companion object {
        val LIGHT_PLATE = AColor.rgb(242, 242, 245)
    }
}

/**
 * Channel logo that fills its box as a complete 16:9 tile. Shows the channel name
 * when there is no logo or it fails to load.
 */
@Composable
fun ChannelLogo(
    url: String?,
    name: String,
    modifier: Modifier = Modifier,
    showNameFallback: Boolean = true,
    hideWhenMissing: Boolean = false,
) {
    val style = LocalRepository.current.prefs.logoStyle
    val context = LocalPlatformContext.current
    var loaded by remember(url) { mutableStateOf(false) }
    val request = remember(url, style) {
        ImageRequest.Builder(context)
            .data(url)
            .allowHardware(false)
            .transformations(LogoTileTransformation(style))
            .build()
    }
    // Without a logo, either the channel name on a plate or nothing at all (no empty grey box).
    if (hideWhenMissing && url.isNullOrBlank()) return
    Box(
        modifier.background(if (loaded || !hideWhenMissing) Palette.Surface2 else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        if (!loaded && showNameFallback && !hideWhenMissing) {
            Text(
                text = name,
                style = Type.Label,
                color = Palette.TextDim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(8.dp),
            )
        }
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onSuccess = { loaded = true },
                onError = { loaded = false },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
