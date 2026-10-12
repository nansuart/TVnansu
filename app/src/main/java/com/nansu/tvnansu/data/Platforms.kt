package com.nansu.tvnansu.data

import android.util.JsonReader
import com.nansu.tvnansu.data.db.AppDao
import com.nansu.tvnansu.data.db.Kind
import com.nansu.tvnansu.data.db.SeriesLite
import com.nansu.tvnansu.data.db.SeriesMetaEntity
import com.nansu.tvnansu.data.remote.Json
import com.nansu.tvnansu.data.remote.JsonMap
import com.nansu.tvnansu.data.remote.USER_AGENT
import com.nansu.tvnansu.data.remote.map
import com.nansu.tvnansu.data.remote.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.StringReader
import java.net.URLEncoder

/**
 * Works out the streaming platform of each series. First from the provider's
 * category names ("NETFLIX SERIES"), then from TVmaze (free, no key; CC BY-SA).
 * Runs in the background at TVmaze's pace (about 2 lookups per second).
 */
class PlatformClassifier(
    private val dao: AppDao,
    private val http: OkHttpClient,
) {
    val state = MutableStateFlow<SyncState>(SyncState.Idle)
    val version = MutableStateFlow(0)

    suspend fun run(playlistId: Long) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val settled = dao.settledMetaKeys(playlistId, now - RETRY_UNMATCHED_MS).toHashSet()
        val categoryPlatform = dao.categories(playlistId, Kind.SERIES.name)
            .mapNotNull { c -> fromText(c.name)?.let { c.remoteId to it } }
            .toMap()
        val pending = dao.seriesLite(playlistId).filter { "$playlistId:${it.remoteId}" !in settled }
        if (pending.isEmpty()) return@withContext

        val fromCategories = ArrayList<SeriesMetaEntity>()
        val lookups = ArrayList<SeriesLite>()
        for (s in pending) {
            val p = categoryPlatform[s.categoryId]
            if (p != null) fromCategories += meta(playlistId, s.remoteId, p, "categoria", now)
            else lookups += s
        }
        fromCategories.chunked(500).forEach { dao.upsertSeriesMeta(it) }
        if (fromCategories.isNotEmpty()) version.value++

        val batch = ArrayList<SeriesMetaEntity>()
        lookups.forEachIndexed { i, s ->
            state.value = SyncState.Running("Plataformas de series: ${i + 1} de ${lookups.size}")
            val platform = try {
                lookup(s)
            } catch (e: RateLimited) {
                delay(10_000)
                runCatching { lookup(s) }.getOrNull()
            } catch (_: Exception) {
                null
            }
            batch += meta(playlistId, s.remoteId, platform, "tvmaze", System.currentTimeMillis())
            if (batch.size >= 25) {
                dao.upsertSeriesMeta(ArrayList(batch))
                batch.clear()
            }
            if ((i + 1) % 100 == 0) version.value++
            delay(550)
        }
        if (batch.isNotEmpty()) dao.upsertSeriesMeta(batch)
        version.value++
        state.value = SyncState.Idle
    }

    private fun meta(pid: Long, sid: String, platform: String?, source: String, at: Long) =
        SeriesMetaEntity("$pid:$sid", pid, sid, platform, source, at)

    private class RateLimited : Exception()

    private suspend fun lookup(s: SeriesLite): String? {
        val name = searchName(s.name)
        if (name.length < 2) return null
        val year = s.year?.take(4)?.toIntOrNull()
        val single = getJson("https://api.tvmaze.com/singlesearch/shows?q=" + enc(name))?.let { Json.asMap(it) }
        if (single != null && yearMatches(single, year)) return platformOf(single)
        if (year == null) return single?.let { platformOf(it) }
        // Same title, different show: look through the ranked results for the right year.
        delay(550)
        val list = getJson("https://api.tvmaze.com/search/shows?q=" + enc(name))?.let { Json.asList(it) } ?: return null
        for (entry in list) {
            val show = Json.asMap(entry)?.map("show") ?: continue
            if (yearMatches(show, year)) return platformOf(show)
        }
        return null
    }

    private fun yearMatches(show: JsonMap, year: Int?): Boolean {
        if (year == null) return true
        val premiered = show.str("premiered")?.take(4)?.toIntOrNull() ?: return true
        return kotlin.math.abs(premiered - year) <= 1
    }

    private fun platformOf(show: JsonMap): String? {
        val web = show.map("webChannel")?.str("name")
        val network = show.map("network")?.str("name")
        return (web ?: network)?.let { normalize(it) }
    }

    private suspend fun getJson(url: String): Any? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(req).execute().use { r ->
            when {
                r.code == 404 -> null
                r.code == 429 -> throw RateLimited()
                !r.isSuccessful -> null
                else -> {
                    val body = r.body?.string() ?: return@use null
                    val reader = JsonReader(StringReader(body))
                    reader.isLenient = true
                    Json.readValue(reader)
                }
            }
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        /** Unmatched series are retried after this long (new seasons, better data). */
        const val RETRY_UNMATCHED_MS = 30L * 24 * 3_600_000

        // Language/country tags providers put in front of titles: "ES - ", "|EN| ", "[LAT] ".
        private const val TAGS = "ES|EN|US|UK|GB|MX|LAT|LATAM|AR|FR|DE|IT|PT|BR|CA|CO|CL|VE|PE|NL|TR|IN|JP|KR|MULTI|VOSE|4K|HD|FHD|UHD"
        private val prefix = Regex("""^\s*(?:[\[(|]\s*(?:$TAGS)\s*[\])|]\s*[-:|]?|(?:$TAGS)\s*[-:|])\s*""")
        private val bracketTags = Regex("""\[[^\]]*]""")
        private val yearTag = Regex("""\(\s*(19|20)\d{2}\s*\)""")
        private val qualityTags = Regex("""\b(4K|UHD|FHD|HD|SD|MULTI|SUB|DUAL|LAT|CAST|VOSE|ESP)\b""", RegexOption.IGNORE_CASE)

        /** "ES - The Bear (2022) [4K]" → "The Bear" */
        fun searchName(raw: String): String {
            var s = raw
            s = prefix.replace(s, "")
            s = bracketTags.replace(s, " ")
            s = yearTag.replace(s, " ")
            s = qualityTags.replace(s, " ")
            return s.replace(Regex("""\s+"""), " ").trim(' ', '-', '|', ':', '.')
        }

        /** Platform named in a provider category, e.g. "SERIES NETFLIX". */
        fun fromText(text: String): String? {
            val t = " " + text.lowercase() + " "
            return when {
                "netflix" in t -> "Netflix"
                "hbo" in t || " max " in t -> "HBO Max"
                "disney" in t || "star+" in t -> "Disney+"
                "apple" in t -> "Apple TV+"
                "prime" in t || "amazon" in t -> "Prime Video"
                "movistar" in t -> "Movistar Plus+"
                "skyshowtime" in t -> "SkyShowtime"
                "paramount" in t -> "Paramount+"
                "atresplayer" in t || "atres player" in t -> "Atresplayer"
                "crunchyroll" in t -> "Crunchyroll"
                "filmin" in t -> "Filmin"
                "hulu" in t -> "Hulu"
                "peacock" in t -> "Peacock"
                else -> null
            }
        }

        /** Unifies TVmaze network names: "HBO" and "Max" become "HBO Max", and so on. */
        fun normalize(raw: String): String {
            fromText(raw)?.let { return it }
            val t = raw.trim()
            val l = t.lowercase()
            return when {
                l == "fx" || l.startsWith("fx ") || l == "fxx" -> "FX"
                l.startsWith("amc") -> "AMC"
                l.startsWith("bbc") -> "BBC"
                l.startsWith("showtime") -> "Showtime"
                l.startsWith("starz") -> "Starz"
                l == "the cw" || l == "cw" -> "The CW"
                l.startsWith("itv") -> "ITV"
                l.startsWith("channel 4") || l == "e4" -> "Channel 4"
                l.startsWith("antena 3") -> "Antena 3"
                l.startsWith("la 1") || l.startsWith("tve") || l.startsWith("rtve") -> "RTVE"
                l.startsWith("telecinco") -> "Telecinco"
                else -> t
            }
        }
    }
}
