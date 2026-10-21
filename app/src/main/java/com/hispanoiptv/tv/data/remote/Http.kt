package com.hispanoiptv.tv.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

const val USER_AGENT = "HispanoIPTV/1.0 (Linux; Android TV)"

/** Opens [url] and hands the (possibly gunzipped) body stream to [block]. */
suspend fun <T> OkHttpClient.withStream(url: String, block: suspend (InputStream) -> T): T =
    withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("El servidor respondió ${response.code}")
            val body = response.body ?: throw IOException("Respuesta vacía")
            val raw = BufferedInputStream(body.byteStream(), 64 * 1024)
            block(maybeGunzip(raw))
        }
    }

fun maybeGunzip(input: BufferedInputStream): InputStream {
    input.mark(4)
    val b1 = input.read()
    val b2 = input.read()
    input.reset()
    return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(input, 64 * 1024) else input
}

fun normalizeServer(raw: String): String {
    var s = raw.trim()
    if (s.isEmpty()) return s
    if (!s.startsWith("http://", ignoreCase = true) && !s.startsWith("https://", ignoreCase = true)) {
        s = "http://$s"
    }
    val cut = listOf("/player_api.php", "/get.php", "/xmltv.php", "/panel_api.php")
        .map { s.indexOf(it, ignoreCase = true) }
        .filter { it > 0 }
        .minOrNull()
    if (cut != null) s = s.substring(0, cut)
    return s.trimEnd('/')
}
