package com.hispanoiptv.tv.data

import android.util.JsonReader
import com.hispanoiptv.tv.data.db.AppDao
import com.hispanoiptv.tv.data.db.MovieEntity
import com.hispanoiptv.tv.data.remote.Json
import com.hispanoiptv.tv.data.remote.JsonMap
import com.hispanoiptv.tv.data.remote.list
import com.hispanoiptv.tv.data.remote.map
import com.hispanoiptv.tv.data.remote.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.StringReader
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Calendar

/** A studio, director or themed list, resolved against Wikidata. */
data class CollectionDef(
    val id: String,
    val title: String,
    /** "estudios", "directores" or "listas" */
    val group: String,
    val qid: String,
    val props: List<String>,
    val months: List<Int> = emptyList(),
    val popular: Boolean = false,
    val subtitle: String? = null,
) {
    fun activeNow(): Boolean {
        if (months.isEmpty()) return true
        return Calendar.getInstance().get(Calendar.MONTH) + 1 in months
    }
}

data class CollectionResult(val def: CollectionDef, val movies: List<MovieEntity>)

/**
 * Collections of films (Studio Ghibli, Christopher Nolan, Halloween…) built from
 * Wikidata (free, no key) and matched against the movies in the user's list.
 * Definitions live in colecciones.json in the GitHub repository, so they can be
 * edited without reinstalling; the copy inside the app is the fallback.
 */
class Collections(
    private val dao: AppDao,
    private val http: OkHttpClient,
    private val prefs: Prefs,
) {
    lateinit var dir: File
    lateinit var bundled: () -> String

    /** Results for the active playlist, filled progressively. */
    val results = MutableStateFlow<Map<String, CollectionResult>>(emptyMap())
    val loading = MutableStateFlow(false)

    /** Definitions created from searches, so their screen can find them. */
    private val registry = HashMap<String, CollectionDef>()

    private val lock = Mutex()
    private var indexFor: Long = -1
    private var index: Map<String, List<MovieEntity>> = emptyMap()
    private var loadedFor: Long = -1

    fun invalidateIndex() {
        indexFor = -1
        loadedFor = -1
        results.value = emptyMap()
    }

    fun find(id: String): CollectionDef? = registry[id] ?: results.value[id]?.def

    // ------------------------------------------------------------------ definitions

    suspend fun definitions(): List<CollectionDef> = withContext(Dispatchers.IO) {
        val cache = File(dir, "colecciones.json")
        val fresh = cache.exists() && System.currentTimeMillis() - cache.lastModified() < 3 * DAY
        if (!fresh && REMOTE_DEFS.isNotEmpty()) {
            runCatching {
                val text = getText(REMOTE_DEFS)
                if (text != null && parseDefs(text).isNotEmpty()) cache.writeText(text)
            }
        }
        val text = if (cache.exists()) cache.readText() else bundled()
        parseDefs(text).ifEmpty { parseDefs(bundled()) }.onEach { registry[it.id] = it }
    }

    private fun parseDefs(text: String): List<CollectionDef> = runCatching {
        val root = JSONObject(text)
        val out = ArrayList<CollectionDef>()
        for (group in listOf("listas", "estudios", "directores")) {
            val arr = root.optJSONArray(group) ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val qid = o.optString("wikidata")
                if (!qid.matches(Regex("Q\\d+"))) continue
                out += CollectionDef(
                    id = o.optString("id").ifBlank { qid },
                    title = o.optString("nombre"),
                    group = group,
                    qid = qid,
                    props = o.optJSONArray("propiedades").strings().filter { it.matches(Regex("P\\d+")) }
                        .ifEmpty { if (group == "directores") listOf("P57") else listOf("P272", "P750") },
                    months = o.optJSONArray("meses").ints(),
                    popular = o.optBoolean("populares", false),
                )
            }
        }
        out
    }.getOrDefault(emptyList())

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }
    private fun JSONArray?.ints(): List<Int> = if (this == null) emptyList() else (0 until length()).map { getInt(it) }

    // ------------------------------------------------------------------ loading

    /** Resolves every active collection for [pid], publishing results as they arrive. */
    suspend fun loadAll(pid: Long) = lock.withLock {
        if (loadedFor == pid && results.value.isNotEmpty()) return@withLock
        loading.value = true
        try {
            val defs = definitions().filter { it.activeNow() }
            val acc = LinkedHashMap<String, CollectionResult>()
            for (def in defs) {
                val movies = runCatching { match(pid, def) }.getOrDefault(emptyList())
                acc[def.id] = CollectionResult(def, movies)
                results.value = LinkedHashMap(acc)
            }
            loadedFor = pid
        } finally {
            loading.value = false
        }
    }

    /** Films of [def] that exist in the playlist, newest first (lists: best rated first). */
    suspend fun match(pid: Long, def: CollectionDef): List<MovieEntity> = withContext(Dispatchers.IO) {
        val films = films(def)
        val idx = movieIndex(pid)
        val seen = HashSet<String>()
        val out = ArrayList<MovieEntity>()
        for (f in films) {
            val candidates = (listOfNotNull(f.es, f.en) + f.alt).map { norm(it) }.filter { it.length >= 2 }.distinct()
            for (key in candidates) {
                val hits = idx[key] ?: continue
                for (m in hits) {
                    val my = movieYear(m)
                    if (f.year != null && my != null && kotlin.math.abs(f.year - my) > 1) continue
                    if (seen.add(m.remoteId)) out += m
                }
            }
        }
        if (def.group == "listas") out.sortedWith(compareByDescending<MovieEntity> { it.rating }.thenByDescending { movieYear(it) ?: 0 })
        else out.sortedByDescending { movieYear(it) ?: 0 }
    }

    // ------------------------------------------------------------------ Wikidata

    data class Film(val es: String?, val en: String?, val alt: List<String>, val year: Int?)

    private suspend fun films(def: CollectionDef): List<Film> = withContext(Dispatchers.IO) {
        val file = File(dir, "wd_${def.qid}_${def.props.joinToString("")}.json")
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < 30 * DAY) {
            parseFilms(file.readText()).let { if (it.isNotEmpty()) return@withContext it }
        }
        val text = getText(SPARQL + URLEncoder.encode(query(def), "UTF-8")) ?: return@withContext (
            if (file.exists()) parseFilms(file.readText()) else emptyList()
        )
        val films = parseFilms(text)
        if (films.isNotEmpty()) file.writeText(text)
        films
    }

    private fun query(def: CollectionDef): String {
        val union = def.props.joinToString(" UNION ") { "{ ?item wdt:$it wd:${def.qid} }" }
        val popular = if (def.popular) "?item wikibase:sitelinks ?links . FILTER(?links >= 15) " else ""
        val alt = if (def.popular) "" else "OPTIONAL { ?item skos:altLabel ?alt FILTER(LANG(?alt) = \"es\") } "
        // A fixed list of film types is far faster than walking the class tree.
        return "SELECT ?item ?es ?en ?alt ?date WHERE { $union VALUES ?type { wd:Q11424 wd:Q202866 wd:Q29168811 wd:Q20650540 wd:Q506240 wd:Q24869 } ?item wdt:P31 ?type . $popular" +
            "OPTIONAL { ?item rdfs:label ?es FILTER(LANG(?es) = \"es\") } " +
            "OPTIONAL { ?item rdfs:label ?en FILTER(LANG(?en) = \"en\") } " +
            alt +
            "OPTIONAL { ?item wdt:P577 ?date } }"
    }

    private fun parseFilms(text: String): List<Film> = runCatching {
        val bindings = JSONObject(text).getJSONObject("results").getJSONArray("bindings")
        data class Acc(var es: String?, var en: String?, val alt: LinkedHashSet<String>, var year: Int?)
        val byItem = LinkedHashMap<String, Acc>()
        for (i in 0 until bindings.length()) {
            val b = bindings.getJSONObject(i)
            val item = b.getJSONObject("item").getString("value")
            val acc = byItem.getOrPut(item) { Acc(null, null, LinkedHashSet(), null) }
            b.optJSONObject("es")?.optString("value")?.let { acc.es = it }
            b.optJSONObject("en")?.optString("value")?.let { acc.en = it }
            b.optJSONObject("alt")?.optString("value")?.let { acc.alt += it }
            b.optJSONObject("date")?.optString("value")?.take(4)?.toIntOrNull()?.let { y ->
                acc.year = acc.year?.let { minOf(it, y) } ?: y
            }
        }
        byItem.values.map { Film(it.es, it.en, it.alt.toList(), it.year) }
    }.getOrDefault(emptyList())

    /** Directors and studios matching free text, from Wikidata's search. */
    suspend fun search(text: String): List<CollectionDef> = withContext(Dispatchers.IO) {
        val q = text.trim()
        if (q.length < 3) return@withContext emptyList()
        val url = "https://www.wikidata.org/w/api.php?action=wbsearchentities&type=item&limit=8&format=json" +
            "&language=es&uselang=es&search=" + URLEncoder.encode(q, "UTF-8")
        val body = getText(url) ?: return@withContext emptyList()
        val root = runCatching {
            val r = JsonReader(StringReader(body)); r.isLenient = true
            Json.asMap(Json.readValue(r))
        }.getOrNull() ?: return@withContext emptyList()
        root.list("search").orEmpty().mapNotNull { e ->
            val m: JsonMap = Json.asMap(e) ?: return@mapNotNull null
            val id = m.str("id") ?: return@mapNotNull null
            val label = m.str("label") ?: return@mapNotNull null
            val desc = (m.str("description") ?: "").lowercase()
            val group = when {
                directorWords.containsMatchIn(desc) -> "directores"
                studioWords.containsMatchIn(desc) -> "estudios"
                else -> return@mapNotNull null
            }
            CollectionDef(
                id = "wd:$id",
                title = label,
                group = group,
                qid = id,
                props = if (group == "directores") listOf("P57") else listOf("P272", "P750"),
                subtitle = m.str("description"),
            ).also { registry[it.id] = it }
        }
    }

    // ------------------------------------------------------------------ matching helpers

    private suspend fun movieIndex(pid: Long): Map<String, List<MovieEntity>> {
        if (indexFor == pid) return index
        val all = dao.allMovies(pid)
        val map = HashMap<String, MutableList<MovieEntity>>(all.size * 2)
        for (m in all) {
            val key = norm(m.name)
            if (key.length >= 2) map.getOrPut(key) { ArrayList(1) } += m
        }
        index = map
        indexFor = pid
        return map
    }

    private fun movieYear(m: MovieEntity): Int? =
        m.year?.take(4)?.toIntOrNull() ?: Repository.yearIn(m.name)?.toIntOrNull()

    private suspend fun getText(url: String): String? {
        repeat(2) { attempt ->
            val result = runCatching {
                val req = Request.Builder().url(url)
                    .header("User-Agent", WIKI_UA)
                    .header("Accept", "application/sparql-results+json, application/json")
                    .build()
                http.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
            }.getOrNull()
            if (result != null) return result
            delay(1500L * (attempt + 1))
        }
        return null
    }

    companion object {
        private const val DAY = 24L * 3_600_000
        /** Optional address of an updated colecciones.json; empty uses the one inside the app. */
        const val REMOTE_DEFS = ""
        private const val SPARQL = "https://query.wikidata.org/sparql?format=json&query="
        private const val WIKI_UA = "HispanoIPTV/1.0 (personal player)"

        private val directorWords = Regex("director|directora|cineasta|realizador|realizadora|film director|filmmaker")
        private val studioWords = Regex("estudio|productora|compañía|empresa|distribuidora|studio|production company|film company")
        private val articles = Regex("^(the|el|la|los|las|le|les|il|lo)\\s+")
        private val nonWord = Regex("[^a-z0-9ñ ]")
        private val spaces = Regex("\\s+")
        private val marks = Regex("\\p{Mn}+")

        /** "ES - El Viaje de Chihiro (2001) [4K]" → "viaje de chihiro" */
        fun norm(raw: String): String {
            var s = PlatformClassifier.searchName(raw).lowercase()
            s = s.replace("&", " and ")
            s = Normalizer.normalize(s.replace("ñ", "\u0001"), Normalizer.Form.NFD)
            s = marks.replace(s, "").replace("\u0001", "ñ")
            s = nonWord.replace(s, " ")
            s = spaces.replace(s, " ").trim()
            s = articles.replace(s, "")
            return s
        }
    }
}
