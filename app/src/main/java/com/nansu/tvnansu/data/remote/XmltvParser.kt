package com.nansu.tvnansu.data.remote

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/** Streaming XMLTV parser. Emits one programme at a time. */
object XmltvParser {

    data class Programme(
        val channel: String,
        val start: Long,
        val stop: Long,
        val title: String,
        val description: String?,
    )

    suspend fun parse(input: InputStream, onProgramme: suspend (Programme) -> Unit) {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        var channel: String? = null
        var start = 0L
        var stop = 0L
        var title: String? = null
        var desc: String? = null
        var inProgramme = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "programme" -> {
                        inProgramme = true
                        channel = parser.getAttributeValue(null, "channel")
                        start = parseTime(parser.getAttributeValue(null, "start"))
                        stop = parseTime(parser.getAttributeValue(null, "stop"))
                        title = null
                        desc = null
                    }
                    "title" -> if (inProgramme && title == null) title = safeText(parser)
                    "desc" -> if (inProgramme && desc == null) desc = safeText(parser)
                }
                XmlPullParser.END_TAG -> if (parser.name == "programme") {
                    inProgramme = false
                    val ch = channel
                    if (ch != null && start > 0 && stop > start) {
                        onProgramme(Programme(ch, start, stop, title ?: "", desc))
                    }
                }
            }
            event = try {
                parser.next()
            } catch (e: Exception) {
                // Malformed tail: keep what was parsed so far.
                XmlPullParser.END_DOCUMENT
            }
        }
    }

    private fun safeText(parser: XmlPullParser): String? = try {
        parser.nextText()?.trim()
    } catch (_: Exception) {
        null
    }

    /** Parses "20261012143000 +0200" into epoch millis (UTC when no offset is given). */
    fun parseTime(raw: String?): Long {
        if (raw == null) return 0
        val t = raw.trim()
        if (t.length < 14) return 0
        return try {
            val y = t.substring(0, 4).toInt()
            val mo = t.substring(4, 6).toInt()
            val d = t.substring(6, 8).toInt()
            val h = t.substring(8, 10).toInt()
            val mi = t.substring(10, 12).toInt()
            val s = t.substring(12, 14).toInt()
            var ms = (daysFromCivil(y, mo, d) * 86400L + h * 3600L + mi * 60L + s) * 1000L
            val rest = t.substring(14).trim()
            if (rest.length >= 5 && (rest[0] == '+' || rest[0] == '-')) {
                val sign = if (rest[0] == '-') -1 else 1
                val oh = rest.substring(1, 3).toInt()
                val om = rest.substring(3, 5).toInt()
                ms -= sign * (oh * 3_600_000L + om * 60_000L)
            }
            ms
        } catch (_: Exception) {
            0
        }
    }

    /** Days since 1970-01-01 for a proleptic Gregorian date (Howard Hinnant's algorithm). */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468L
    }
}
