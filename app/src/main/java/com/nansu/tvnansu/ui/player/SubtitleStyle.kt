package com.nansu.tvnansu.ui.player

import android.graphics.Color
import android.graphics.Typeface
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView

object SubtitleStyle {
    val labels = listOf("Pequeño", "Normal", "Grande", "Muy grande")
    private val scales = floatArrayOf(0.85f, 1.1f, 1.4f, 1.75f)

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
                    Color.argb(190, 0, 0, 0),
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
                    CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                    Color.BLACK,
                    Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD),
                )
            }
        )
    }
}
