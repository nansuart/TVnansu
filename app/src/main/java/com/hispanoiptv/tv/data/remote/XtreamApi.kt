package com.hispanoiptv.tv.data.remote

import android.util.Base64
import android.util.JsonReader
import okhttp3.OkHttpClient
import java.io.InputStreamReader
import java.net.URLEncoder

class XtreamApi(
    private val http: OkHttpClient,
    server: String,
    private val username: String,
    private val password: String,
) {
    val base: String = normalizeServer(server)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun api(action: String?, extra: String = ""): String {
        val sb = StringBuilder("$base/player_api.php?username=${enc(username)}&password=${enc(password)}")
        if (action != null) sb.append("&action=").append(action)
        sb.append(extra)
        return sb.toString()
    }

    private suspend fun <T> json(url: String, block: suspend (JsonReader) -> T): T =
        http.withStream(url) { input ->
            val reader = JsonReader(InputStreamReader(input, Charsets.UTF_8))
            reader.isLenient = true
            block(reader)
        }

    suspend fun authenticate(): JsonMap =
        json(api(null)) { Json.asMap(Json.readValue(it)) ?: emptyMap() }

    suspend fun forEach(action: String, block: suspend (JsonMap) -> Unit) =
        json(api(action)) { Json.forEachObject(it, block) }

    suspend fun vodInfo(vodId: String): JsonMap =
        json(api("get_vod_info", "&vod_id=$vodId")) { Json.asMap(Json.readValue(it)) ?: emptyMap() }

    suspend fun seriesInfo(seriesId: String): JsonMap =
        json(api("get_series_info", "&series_id=$seriesId")) { Json.asMap(Json.readValue(it)) ?: emptyMap() }

    suspend fun shortEpg(streamId: String, limit: Int = 4): List<ShortEpg> =
        json(api("get_short_epg", "&stream_id=$streamId&limit=$limit")) { r ->
            val root = Json.asMap(Json.readValue(r)) ?: return@json emptyList()
            val listings = root.list("epg_listings") ?: return@json emptyList()
            listings.mapNotNull { Json.asMap(it) }.mapNotNull { m ->
                val start = m.long("start_timestamp") ?: return@mapNotNull null
                val stop = m.long("stop_timestamp") ?: return@mapNotNull null
                ShortEpg(
                    title = decodeB64(m.str("title")) ?: "",
                    description = decodeB64(m.str("description")),
                    start = start * 1000,
                    stop = stop * 1000,
                )
            }
        }

    fun liveUrl(streamId: String, ext: String = "ts") = "$base/live/$username/$password/$streamId.$ext"
    fun movieUrl(streamId: String, ext: String) = "$base/movie/$username/$password/$streamId.$ext"
    fun episodeUrl(episodeId: String, ext: String) = "$base/series/$username/$password/$episodeId.$ext"
    fun xmltvUrl() = "$base/xmltv.php?username=${enc(username)}&password=${enc(password)}"

    data class ShortEpg(val title: String, val description: String?, val start: Long, val stop: Long)

    companion object {
        fun decodeB64(s: String?): String? {
            if (s.isNullOrBlank()) return null
            return try {
                String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8)
            } catch (_: Exception) {
                s
            }
        }

        /** Detects get.php?username=..&password=.. URLs so M3U links can be upgraded to Xtream. */
        fun parseGetPhp(url: String): Triple<String, String, String>? {
            val idx = url.indexOf("get.php", ignoreCase = true)
            if (idx < 0) return null
            val q = url.substringAfter('?', "")
            if (q.isEmpty()) return null
            val params = q.split('&').mapNotNull {
                val p = it.split('=', limit = 2)
                if (p.size == 2) p[0].lowercase() to java.net.URLDecoder.decode(p[1], "UTF-8") else null
            }.toMap()
            val user = params["username"] ?: return null
            val pass = params["password"] ?: return null
            return Triple(normalizeServer(url.substring(0, idx)), user, pass)
        }
    }
}
