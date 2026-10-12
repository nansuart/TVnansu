package com.nansu.tvnansu.data.remote

import android.util.JsonReader
import android.util.JsonToken

typealias JsonMap = Map<String, Any?>

/**
 * Lenient streaming JSON helpers. IPTV panels return numbers as strings,
 * strings as numbers, nulls, and sometimes objects where arrays are expected,
 * so every scalar is normalised to String.
 */
object Json {

    fun readValue(r: JsonReader): Any? = when (r.peek()) {
        JsonToken.BEGIN_OBJECT -> {
            val m = LinkedHashMap<String, Any?>()
            r.beginObject()
            while (r.hasNext()) {
                val name = r.nextName()
                m[name] = readValue(r)
            }
            r.endObject()
            m
        }
        JsonToken.BEGIN_ARRAY -> {
            val l = ArrayList<Any?>()
            r.beginArray()
            while (r.hasNext()) l.add(readValue(r))
            r.endArray()
            l
        }
        JsonToken.STRING -> r.nextString()
        JsonToken.NUMBER -> r.nextString()
        JsonToken.BOOLEAN -> r.nextBoolean().toString()
        JsonToken.NULL -> {
            r.nextNull(); null
        }
        else -> {
            r.skipValue(); null
        }
    }

    /** Calls [block] for each object element of an array (or each value of an object). */
    suspend fun forEachObject(r: JsonReader, block: suspend (JsonMap) -> Unit) {
        when (r.peek()) {
            JsonToken.BEGIN_ARRAY -> {
                r.beginArray()
                while (r.hasNext()) {
                    val v = readValue(r)
                    asMap(v)?.let { block(it) }
                }
                r.endArray()
            }
            JsonToken.BEGIN_OBJECT -> {
                r.beginObject()
                while (r.hasNext()) {
                    r.nextName()
                    val v = readValue(r)
                    asMap(v)?.let { block(it) }
                }
                r.endObject()
            }
            else -> r.skipValue()
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun asMap(v: Any?): JsonMap? = v as? Map<String, Any?>

    fun asList(v: Any?): List<Any?>? = when (v) {
        is List<*> -> v
        is Map<*, *> -> v.values.toList()
        else -> null
    }
}

fun JsonMap.str(key: String): String? {
    val v = this[key] as? String ?: return null
    val t = v.trim()
    return if (t.isEmpty() || t.equals("null", ignoreCase = true)) null else t
}

fun JsonMap.int(key: String): Int? = str(key)?.toDoubleOrNull()?.toInt()
fun JsonMap.long(key: String): Long? = str(key)?.toDoubleOrNull()?.toLong()
fun JsonMap.float(key: String): Float? = str(key)?.toFloatOrNull()
fun JsonMap.map(key: String): JsonMap? = Json.asMap(this[key])
fun JsonMap.list(key: String): List<Any?>? = Json.asList(this[key])

/** First string of a field that may be a string or a list of strings. */
fun JsonMap.firstString(key: String): String? = when (val v = this[key]) {
    is String -> v.trim().takeIf { it.isNotEmpty() }
    is List<*> -> v.firstOrNull { it is String && it.isNotBlank() } as? String
    else -> null
}
