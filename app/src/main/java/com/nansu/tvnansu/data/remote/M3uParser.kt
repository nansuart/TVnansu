package com.nansu.tvnansu.data.remote

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/** Streaming M3U/M3U8 parser: never holds the whole playlist in memory. */
object M3uParser {

    data class Entry(
        val name: String,
        val attrs: Map<String, String>,
        val group: String?,
        val url: String,
    )

    private val attrRegex = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")

    suspend fun parse(
        input: InputStream,
        onHeader: (Map<String, String>) -> Unit,
        onEntry: suspend (Entry) -> Unit,
    ) {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8), 64 * 1024)
        var pendingInfo: String? = null
        var pendingGroup: String? = null
        while (true) {
            var line = reader.readLine() ?: break
            if (line.isNotEmpty() && line[0] == '﻿') line = line.substring(1)
            line = line.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> onHeader(attributes(line))
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pendingInfo = line
                    pendingGroup = null
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> pendingGroup = line.substringAfter(':').trim()
                line.startsWith("#") -> Unit
                else -> {
                    val info = pendingInfo
                    val attrs = if (info != null) attributes(info) else emptyMap()
                    val name = if (info != null) displayName(info) else line.substringAfterLast('/')
                    val group = attrs["group-title"]?.takeIf { it.isNotBlank() } ?: pendingGroup
                    onEntry(Entry(name.ifBlank { attrs["tvg-name"] ?: "Sin nombre" }, attrs, group, line))
                    pendingInfo = null
                    pendingGroup = null
                }
            }
        }
    }

    private fun attributes(line: String): Map<String, String> {
        val map = HashMap<String, String>()
        for (m in attrRegex.findAll(line)) map[m.groupValues[1].lowercase()] = m.groupValues[2].trim()
        return map
    }

    /** Text after the first comma that is not inside quotes. */
    private fun displayName(line: String): String {
        var inQuotes = false
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') inQuotes = !inQuotes
            else if (c == ',' && !inQuotes) return line.substring(i + 1).trim()
        }
        return ""
    }

    private val videoExt = setOf("mp4", "mkv", "avi", "mov", "m4v", "wmv", "flv", "webm")
    val episodeRegex = Regex("""^(.*?)[\s._\-|]*[Ss](\d{1,2})[\s._\-]*[EeXx](\d{1,3})\b.*$""")

    enum class Type { LIVE, MOVIE, SERIES }

    fun classify(e: Entry): Type {
        val u = e.url.lowercase()
        if (u.contains("/series/")) return Type.SERIES
        if (u.contains("/movie/") || u.contains("/movies/")) return Type.MOVIE
        val ext = u.substringBefore('?').substringAfterLast('.', "")
        if (ext in videoExt) {
            return if (episodeRegex.matches(e.name)) Type.SERIES else Type.MOVIE
        }
        return Type.LIVE
    }
}
