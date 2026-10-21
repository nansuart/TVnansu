package com.hispanoiptv.tv.ui.player

import android.graphics.Typeface
import android.text.Spanned
import android.text.style.StyleSpan
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.text.Cue
import androidx.tv.material3.Text
import com.hispanoiptv.tv.R

private val SubFont = FontFamily(Font(R.font.montserrat_medium, FontWeight.Medium))

/**
 * Text subtitles drawn the Apple TV way: Montserrat Medium, white, centred, either on a
 * translucent rounded box or with a soft shadow. Cues placed at the top of the
 * picture (signs, speaker notes) stay at the top. [lift] raises them while the
 * player controls are on screen.
 */
@Composable
fun SubtitleOverlay(
    cues: List<Cue>,
    sizeIndex: Int,
    background: Boolean,
    lift: Dp,
    modifier: Modifier = Modifier,
) {
    val textCues = cues.filter { it.bitmap == null && !it.text.isNullOrBlank() }
    if (textCues.isEmpty()) return
    val size = SubtitleStyle.sizesSp[sizeIndex.coerceIn(0, SubtitleStyle.sizesSp.lastIndex)]
    val style = remember(size, background) {
        TextStyle(
            fontFamily = SubFont,
            fontWeight = FontWeight.Medium,
            fontSize = size.sp,
            lineHeight = (size * 1.24f).sp,
            color = Color.White,
            textAlign = TextAlign.Center,
            shadow = if (background) null else Shadow(Color.Black.copy(alpha = 0.85f), Offset(0f, 2f), 8f),
        )
    }
    val (top, bottom) = textCues.partition { isTop(it) }
    Box(modifier.fillMaxSize().padding(horizontal = 72.dp)) {
        if (top.isNotEmpty()) {
            CueBlock(top, style, background, Modifier.align(Alignment.TopCenter).padding(top = 34.dp))
        }
        if (bottom.isNotEmpty()) {
            CueBlock(bottom, style, background, Modifier.align(Alignment.BottomCenter).padding(bottom = 58.dp + lift))
        }
    }
}

@Composable
private fun CueBlock(cues: List<Cue>, style: TextStyle, background: Boolean, modifier: Modifier) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (c in cues) {
            val text = remember(c.text) { toAnnotated(c.text ?: "") }
            Text(
                text = text,
                style = style,
                modifier = if (background) {
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                } else Modifier,
            )
        }
    }
}

private fun isTop(c: Cue): Boolean {
    if (c.line == Cue.DIMEN_UNSET) return false
    return when (c.lineType) {
        Cue.LINE_TYPE_FRACTION -> c.line < 0.4f
        Cue.LINE_TYPE_NUMBER -> c.line >= 0f && c.line < 4f
        else -> false
    }
}

/** Keeps italics and bold from the subtitle file. */
private fun toAnnotated(cs: CharSequence): AnnotatedString {
    val plain = cs.toString().trim('\n')
    if (cs !is Spanned) return AnnotatedString(plain)
    val offset = cs.toString().indexOf(plain).coerceAtLeast(0)
    return buildAnnotatedString {
        append(plain)
        for (span in cs.getSpans(0, cs.length, StyleSpan::class.java)) {
            val start = (cs.getSpanStart(span) - offset).coerceIn(0, plain.length)
            val end = (cs.getSpanEnd(span) - offset).coerceIn(0, plain.length)
            if (end <= start) continue
            val st = when (span.style) {
                Typeface.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                Typeface.BOLD -> SpanStyle(fontWeight = FontWeight.SemiBold)
                Typeface.BOLD_ITALIC -> SpanStyle(fontStyle = FontStyle.Italic, fontWeight = FontWeight.SemiBold)
                else -> null
            } ?: continue
            addStyle(st, start, end)
        }
    }
}
