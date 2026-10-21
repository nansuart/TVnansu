package com.hispanoiptv.tv.ui.player

import android.graphics.Color
import android.graphics.Typeface
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView

/**
 * Subtitle sizes and styles. Text subtitles are drawn by [SubtitleOverlay] (Inter,
 * Apple TV look); this only configures the system renderer, which is still used
 * for image subtitles (PGS / DVB).
 */
object SubtitleStyle {
    val labels = listOf("XS", "S", "M", "L", "XL")

    /** Text size per option, in sp. XS matches the Apple TV size. */
    val sizesSp = floatArrayOf(19f, 22f, 25f, 29f, 34f)

    private val scales = floatArrayOf(0.75f, 0.88f, 1.0f, 1.18f, 1.4f)

    fun apply(view: PlayerView, size: Int, background: Boolean) {
        val sub = view.subtitleView ?: return
        sub.setApplyEmbeddedFontSizes(false)
        sub.setApplyEmbeddedStyles(!background)
        sub.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * scales[size.coerceIn(0, scales.lastIndex)])
        sub.setBottomPaddingFraction(0.06f)
        sub.setStyle(
            if (background) {
                CaptionStyleCompat(
                    Color.WHITE,
                    Color.argb(160, 0, 0, 0),
                    Color.TRANSPARENT,
                    CaptionStyleCompat.EDGE_TYPE_NONE,
                    Color.TRANSPARENT,
                    Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL),
                )
            } else {
                CaptionStyleCompat(
                    Color.WHITE,
                    Color.TRANSPARENT,
                    Color.TRANSPARENT,
                    CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                    Color.BLACK,
                    Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL),
                )
            }
        )
    }
}
