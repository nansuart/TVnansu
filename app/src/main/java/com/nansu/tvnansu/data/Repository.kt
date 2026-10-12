package com.nansu.tvnansu.data

import android.util.LruCache
import com.nansu.tvnansu.data.db.AppDatabase
import com.nansu.tvnansu.data.db.CategoryEntity
import com.nansu.tvnansu.data.db.ChannelEntity
import com.nansu.tvnansu.data.db.EpisodeEntity
import com.nansu.tvnansu.data.db.FavoriteEntity
import com.nansu.tvnansu.data.db.Kind
import com.nansu.tvnansu.data.db.MovieEntity
import com.nansu.tvnansu.data.db.PlaylistEntity
import com.nansu.tvnansu.data.db.ProgrammeEntity
import com.nansu.tvnansu.data.db.ProgressEntity
import com.nansu.tvnansu.data.db.RecentChannelEntity
import com.nansu.tvnansu.data.db.SeriesEntity
import com.nansu.tvnansu.data.db.SourceType
import com.nansu.tvnansu.data.db.uidOf
import com.nansu.tvnansu.data.remote.Json
import com.nansu.tvnansu.data.remote.JsonMap
import com.nansu.tvnansu.data.remote.M3uParser
import com.nansu.tvnansu.data.remote.XmltvParser
import com.nansu.tvnansu.data.remote.XtreamApi
import com.nansu.tvnansu.data.remote.firstString
import com.nansu.tvnansu.data.remote.float
import com.nansu.tvnansu.data.remote.int
import com.nansu.tvnansu.data.remote.list
import com.nansu.tvnansu.data.remote.long
import com.nansu.tvnansu.data.remote.map
import com.nansu.tvnansu.data.remote.str
import com.nansu.tvnansu.data.remote.withStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException

class Repository(
    db: AppDatabase,
    val http: OkHttpClient,
    val prefs: Prefs,
) {
    val dao = db.dao()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val epgState = MutableStateFlow<SyncState>(SyncState.Idle)
    val activeId = MutableStateFlow(prefs.activePlaylistId)

    /** Bumped after a catalogue sync so screens reload their data. */
    val dataVersion = MutableStateFlow(0)

    /** Bumped after the guide is refreshed. */
    val epgVersion = MutableStateFlow(0)

    val playlists: Flow<List<PlaylistEntity>> = dao.playlistsFlow()

    private val syncMutex = Mutex()
    private val seriesCache = LruCache<String, SeriesDetails>(24)
    private val shortEpgCache = LruCache<String, Pair<Long, List<XtreamApi.ShortEpg>>>(200)

    fun api(p: PlaylistEntity) = XtreamApi(http, p.server, p.username, p.password)

    // ------------------------------------------------------------------ playlists

    suspend fun active(): PlaylistEntity? {
        dao.playlist(activeId.value)?.let { return it }
        val first = dao.playlists().firstOrNull() ?: return null
        setActive(first.id)
        return first
    }

    fun setActive(id: Long) {
        prefs.activePlaylistId = id
        activeId.value = id
        dataVersion.value++
    }

    suspend fun addXtream(name: String, server: String, user: String, pass: String): Long =
        withContext(Dispatchers.IO) {
            val api = XtreamApi(http, server, user.trim(), pass.trim())
            if (api.base.isBlank()) throw IOException("Escribe la URL del servidor")
            val auth = try {
                api.authenticate()
            } catch (e: Exception) {
                throw IOException("No se pudo conectar con el servidor (${e.message})")
            }
            val info = auth.map("user_info")
                ?: throw IOException("El servidor no respondió como Xtream Codes")
            val authValue = info.str("auth")?.lowercase() ?: ""
            if (authValue != "1" && authValue != "true") throw IOException("Usuario o contraseña incorrectos")
            val status = info.str("status")
            if (status != null && !status.equals("Active", ignoreCase = true)) {
                throw IOException("La cuenta está en estado: $status")
            }
            val p = PlaylistEntity(
                name = name.ifBlank { hostOf(api.base) },
                type = SourceType.XTREAM,
                server = api.base,
                username = user.trim(),
                password = pass.trim(),
            )
            val id = dao.insertPlaylist(p)
            setActive(id)
            id
        }

    suspend fun addM3u(name: String, url: String, epgUrl: String): Long = withContext(Dispatchers.IO) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) throw IOException("Escribe la URL de la lista")
        val xtream = XtreamApi.parseGetPhp(trimmed)
        if (xtream != null) {
            // A get.php link carries Xtream credentials: use the richer API when it works.
            try {
                return@withContext addXtream(name, xtream.first, xtream.second, xtream.third)
            } catch (_: Exception) {
                // Fall back to plain M3U.
            }
        }
        val fixedUrl = if (trimmed.startsWith("http", ignoreCase = true)) trimmed else "http://$trimmed"
        val p = PlaylistEntity(
            name = name.ifBlank { hostOf(fixedUrl) },
            type = SourceType.M3U,
            m3uUrl = fixedUrl,
            epgUrl = epgUrl.trim(),
        )
        val id = dao.insertPlaylist(p)
        setActive(id)
        id
    }

    suspend fun deletePlaylist(id: Long) = withContext(Dispatchers.IO) {
        clearCatalog(id)
        dao.clearProgrammes(id)
        dao.clearProgress(id)
        dao.clearFavorites(id)
        dao.clearRecents(id)
        dao.deletePlaylist(id)
        if (activeId.value == id) {
            val next = dao.playlists().firstOrNull()
            setActive(next?.id ?: 0L)
        }
    }

    private suspend fun clearCatalog(id: Long) {
        dao.clearCategories(id)
        dao.clearChannels(id)
        dao.clearMovies(id)
        dao.clearSeries(id)
        dao.clearEpisodes(id)
        seriesCache.evictAll()
    }

    // ------------------------------------------------------------------ sync

    /** Downloads the whole catalogue. Returns true on success. */
    suspend fun sync(id: Long): Boolean = syncMutex.withLock {
        val p = dao.playlist(id) ?: return@withLock false
        syncState.value = SyncState.Running("Conectando…")
        try {
            withContext(Dispatchers.IO) {
                if (p.type == SourceType.XTREAM) syncXtream(p) else syncM3u(p)
                val fresh = dao.playlist(p.id) ?: p
                dao.updatePlaylist(fresh.copy(lastSync = System.currentTimeMillis()))
            }
            syncState.value = SyncState.Idle
            dataVersion.value++
            scope.launch { syncEpg(p.id) }
            true
        } catch (e: Exception) {
            syncState.value = SyncState.Failed(e.message ?: "Error desconocido")
            false
        }
    }

    fun syncInBackground(id: Long) {
        scope.launch { sync(id) }
    }

    /** Refreshes catalogue or guide when they are older than the configured interval. */
    fun refreshIfStale() {
        scope.launch {
            val p = active() ?: return@launch
            val now = System.currentTimeMillis()
            val hours = prefs.autoRefreshHours
            if (p.lastSync == 0L || (hours > 0 && now - p.lastSync > hours * 3_600_000L)) {
                sync(p.id)
            } else if (now - p.lastEpgSync > 12 * 3_600_000L) {
                syncEpg(p.id)
            }
        }
    }

    private fun report(msg: String) {
        syncState.value = SyncState.Running(msg)
    }

    private suspend fun syncXtream(p: PlaylistEntity) {
        val api = api(p)
        report("Conectando con el servidor…")
        api.authenticate()
        clearCatalog(p.id)

        val categories = ArrayList<CategoryEntity>()
        suspend fun loadCategories(action: String, kind: Kind) {
            var pos = 0
            try {
                api.forEach(action) { m ->
                    val cid = m.str("category_id") ?: return@forEach
                    categories += CategoryEntity(
                        playlistId = p.id,
                        kind = kind.name,
                        remoteId = cid,
                        name = m.str("category_name") ?: "Sin nombre",
                        position = pos++,
                    )
                }
            } catch (_: Exception) {
                // Some panels do not expose every category type.
            }
        }
        report("Leyendo categorías…")
        loadCategories("get_live_categories", Kind.LIVE)
        loadCategories("get_vod_categories", Kind.MOVIE)
        loadCategories("get_series_categories", Kind.SERIES)
        dao.insertCategories(categories)

        val channels = Batcher<ChannelEntity>(1000) { dao.insertChannels(it) }
        api.forEach("get_live_streams") { m ->
            val sid = m.str("stream_id") ?: return@forEach
            channels.add(
                ChannelEntity(
                    playlistId = p.id,
                    remoteId = sid,
                    name = m.str("name") ?: "Canal $sid",
                    logo = m.str("stream_icon"),
                    categoryId = m.str("category_id") ?: "",
                    epgId = m.str("epg_channel_id")?.lowercase(),
                    url = api.liveUrl(sid, "ts"),
                    number = m.int("num") ?: 0,
                    catchup = m.str("tv_archive") == "1",
                )
            )
            if (channels.count % 500 == 0) report("Canales: ${channels.count}")
        }
        channels.finish()

        val movies = Batcher<MovieEntity>(1000) { dao.insertMovies(it) }
        try {
            api.forEach("get_vod_streams") { m ->
                val sid = m.str("stream_id") ?: return@forEach
                movies.add(
                    MovieEntity(
                        playlistId = p.id,
                        remoteId = sid,
                        name = m.str("name") ?: "Película",
                        poster = m.str("stream_icon"),
                        backdrop = null,
                        categoryId = m.str("category_id") ?: "",
                        rating = m.float("rating") ?: 0f,
                        year = m.str("year") ?: m.str("releasedate")?.take(4),
                        added = m.long("added") ?: 0L,
                        url = api.movieUrl(sid, m.str("container_extension") ?: "mp4"),
                        plot = m.str("plot"),
                    )
                )
                if (movies.count % 1000 == 0) report("Películas: ${movies.count}")
            }
        } catch (_: Exception) {
        }
        movies.finish()

        val series = Batcher<SeriesEntity>(1000) { dao.insertSeries(it) }
        try {
            api.forEach("get_series") { m ->
                val sid = m.str("series_id") ?: return@forEach
                series.add(
                    SeriesEntity(
                        playlistId = p.id,
                        remoteId = sid,
                        name = m.str("name") ?: "Serie",
                        poster = m.str("cover"),
                        backdrop = m.firstString("backdrop_path"),
                        categoryId = m.str("category_id") ?: "",
                        rating = m.float("rating") ?: 0f,
                        year = (m.str("releaseDate") ?: m.str("release_date") ?: m.str("year"))?.take(4),
                        added = m.long("last_modified") ?: 0L,
                        plot = m.str("plot"),
                        genre = m.str("genre"),
                    )
                )
                if (series.count % 500 == 0) report("Series: ${series.count}")
            }
        } catch (_: Exception) {
        }
        series.finish()
    }

    private suspend fun syncM3u(p: PlaylistEntity) {
        report("Descargando lista…")
        http.withStream(p.m3uUrl) { input ->
            clearCatalog(p.id)
            val liveCats = LinkedHashMap<String, Int>()
            val movieCats = LinkedHashMap<String, Int>()
            val seriesCats = LinkedHashMap<String, Int>()
            val channels = Batcher<ChannelEntity>(1000) { dao.insertChannels(it) }
            val movies = Batcher<MovieEntity>(1000) { dao.insertMovies(it) }
            val episodes = Batcher<EpisodeEntity>(1000) { dao.insertEpisodes(it) }
            val seriesMap = LinkedHashMap<String, SeriesEntity>()
            var headerEpg: String? = null
            var count = 0
            var number = 0

            M3uParser.parse(input, onHeader = { h -> headerEpg = h["url-tvg"] ?: h["x-tvg-url"] }) { e ->
                count++
                if (count % 1000 == 0) report("Leyendo lista: $count elementos")
                val group = e.group ?: "Sin categoría"
                val logo = e.attrs["tvg-logo"]?.takeIf { it.isNotBlank() }
                when (M3uParser.classify(e)) {
                    M3uParser.Type.LIVE -> {
                        liveCats.getOrPut(group) { liveCats.size }
                        number++
                        val epg = (e.attrs["tvg-id"]?.takeIf { it.isNotBlank() }
                            ?: e.attrs["tvg-name"]?.takeIf { it.isNotBlank() })?.lowercase()
                        channels.add(
                            ChannelEntity(
                                playlistId = p.id,
                                remoteId = stableId(e.url),
                                name = e.name,
                                logo = logo,
                                categoryId = group,
                                epgId = epg,
                                url = e.url,
                                number = e.attrs["tvg-chno"]?.toIntOrNull() ?: number,
                                catchup = e.attrs.containsKey("catchup") || e.attrs.containsKey("catchup-days"),
                            )
                        )
                    }
                    M3uParser.Type.MOVIE -> {
                        movieCats.getOrPut(group) { movieCats.size }
                        movies.add(
                            MovieEntity(
                                playlistId = p.id,
                                remoteId = stableId(e.url),
                                name = e.name,
                                poster = logo,
                                backdrop = null,
                                categoryId = group,
                                rating = 0f,
                                year = yearIn(e.name),
                                added = count.toLong(),
                                url = e.url,
                                plot = null,
                            )
                        )
                    }
                    M3uParser.Type.SERIES -> {
                        val match = M3uParser.episodeRegex.matchEntire(e.name)
                        val seriesName = match?.groupValues?.get(1)
                            ?.trim()?.trimEnd('-', '|', '.', ' ', '_')
                            ?.takeIf { it.isNotBlank() } ?: e.name
                        val season = match?.groupValues?.get(2)?.toIntOrNull() ?: 1
                        val epNum = match?.groupValues?.get(3)?.toIntOrNull() ?: 0
                        val sid = "s" + stableId(group + "|" + seriesName.lowercase())
                        if (!seriesMap.containsKey(sid)) {
                            seriesCats.getOrPut(group) { seriesCats.size }
                            seriesMap[sid] = SeriesEntity(
                                playlistId = p.id,
                                remoteId = sid,
                                name = seriesName,
                                poster = logo,
                                backdrop = null,
                                categoryId = group,
                                rating = 0f,
                                year = null,
                                added = count.toLong(),
                                plot = null,
                                genre = null,
                            )
                        }
                        episodes.add(
                            EpisodeEntity(
                                playlistId = p.id,
                                seriesId = sid,
                                remoteId = stableId(e.url),
                                season = season,
                                episode = epNum,
                                title = e.name,
                                url = e.url,
                                image = logo,
                                plot = null,
                                durationSecs = 0,
                            )
                        )
                    }
                }
            }
            channels.finish()
            movies.finish()
            episodes.finish()
            seriesMap.values.chunked(1000).forEach { dao.insertSeries(it) }

            val cats = ArrayList<CategoryEntity>()
            fun addCats(map: Map<String, Int>, kind: Kind) {
                map.forEach { (name, pos) ->
                    cats += CategoryEntity(playlistId = p.id, kind = kind.name, remoteId = name, name = name, position = pos)
                }
            }
            addCats(liveCats, Kind.LIVE)
            addCats(movieCats, Kind.MOVIE)
            addCats(seriesCats, Kind.SERIES)
            cats.chunked(1000).forEach { dao.insertCategories(it) }

            val headerUrl = headerEpg
            if (p.epgUrl.isBlank() && !headerUrl.isNullOrBlank()) {
                dao.playlist(p.id)?.let { dao.updatePlaylist(it.copy(epgUrl = headerUrl.substringBefore(',').trim())) }
            }
        }
    }

    suspend fun syncEpg(id: Long) {
        val p = dao.playlist(id) ?: return
        val url = if (p.type == SourceType.XTREAM) api(p).xmltvUrl() else p.epgUrl
        if (url.isBlank()) return
        val ids = dao.epgIds(p.id).map { it.lowercase() }.toHashSet()
        if (ids.isEmpty()) return
        epgState.value = SyncState.Running("Actualizando guía…")
        try {
            val now = System.currentTimeMillis()
            val from = now - 6 * 3_600_000L
            val to = now + 72 * 3_600_000L
            http.withStream(url) { input ->
                dao.clearProgrammes(p.id)
                val batch = Batcher<ProgrammeEntity>(2000) { dao.insertProgrammes(it) }
                XmltvParser.parse(input) { pr ->
                    val ch = pr.channel.lowercase()
                    if (ch in ids && pr.stop > from && pr.start < to) {
                        batch.add(
                            ProgrammeEntity(
                                playlistId = p.id,
                                channelEpgId = ch,
                                start = pr.start,
                                stop = pr.stop,
                                title = pr.title,
                                description = pr.description,
                            )
                        )
                    }
                }
                batch.finish()
            }
            dao.playlist(p.id)?.let { dao.updatePlaylist(it.copy(lastEpgSync = now)) }
            epgState.value = SyncState.Idle
            epgVersion.value++
        } catch (e: Exception) {
            epgState.value = SyncState.Failed("Guía: ${e.message ?: "error"}")
        }
    }

    // ------------------------------------------------------------------ details

    suspend fun movieDetails(movie: MovieEntity): MovieDetails = withContext(Dispatchers.IO) {
        val basic = MovieDetails(
            plot = movie.plot, cast = null, director = null, genre = null, duration = null,
            year = movie.year, rating = movie.rating, backdrop = movie.backdrop, poster = movie.poster,
        )
        val p = dao.playlist(movie.playlistId)
        if (p == null || p.type != SourceType.XTREAM) return@withContext basic
        try {
            val root = api(p).vodInfo(movie.remoteId)
            val info = root.map("info") ?: emptyMap()
            val backdrop = info.firstString("backdrop_path") ?: movie.backdrop
            val plot = info.str("plot") ?: info.str("description") ?: movie.plot
            if (backdrop != movie.backdrop || plot != movie.plot) {
                dao.updateMovieInfo(movie.playlistId, movie.remoteId, backdrop, plot)
            }
            MovieDetails(
                plot = plot,
                cast = info.str("cast") ?: info.str("actors"),
                director = info.str("director"),
                genre = info.str("genre"),
                duration = info.str("duration") ?: info.int("duration_secs")?.let { formatDurationSecs(it) },
                year = (info.str("releasedate") ?: info.str("release_date"))?.take(4) ?: movie.year,
                rating = info.float("rating") ?: movie.rating,
                backdrop = backdrop,
                poster = info.str("movie_image") ?: movie.poster,
            )
        } catch (_: Exception) {
            basic
        }
    }

    suspend fun seriesDetails(series: SeriesEntity, force: Boolean = false): SeriesDetails = withContext(Dispatchers.IO) {
        val key = "${series.playlistId}:${series.remoteId}"
        if (!force) seriesCache.get(key)?.let { return@withContext it }
        val p = dao.playlist(series.playlistId)
        val details = if (p != null && p.type == SourceType.XTREAM) {
            xtreamSeries(p, series)
        } else {
            val eps = dao.episodes(series.playlistId, series.remoteId)
            SeriesDetails(
                plot = series.plot, cast = null, genre = series.genre, year = series.year,
                rating = series.rating, backdrop = series.backdrop, poster = series.poster,
                seasons = eps.groupBy { it.season }.toSortedMap().map { (n, list) ->
                    Season(n, "Temporada $n", list.sortedBy { it.episode })
                },
            )
        }
        seriesCache.put(key, details)
        details
    }

    private suspend fun xtreamSeries(p: PlaylistEntity, series: SeriesEntity): SeriesDetails {
        val api = api(p)
        val root = api.seriesInfo(series.remoteId)
        val info = root.map("info") ?: emptyMap()
        val eps = ArrayList<EpisodeEntity>()

        fun parseEpisode(m: JsonMap, seasonKey: Int?) {
            val id = m.str("id") ?: return
            val epInfo = m.map("info")
            val num = m.int("episode_num") ?: 0
            eps += EpisodeEntity(
                playlistId = p.id,
                seriesId = series.remoteId,
                remoteId = id,
                season = m.int("season") ?: seasonKey ?: 1,
                episode = num,
                title = m.str("title") ?: "Episodio $num",
                url = api.episodeUrl(id, m.str("container_extension") ?: "mp4"),
                image = epInfo?.str("movie_image") ?: epInfo?.str("cover_big"),
                plot = epInfo?.str("plot"),
                durationSecs = epInfo?.int("duration_secs") ?: 0,
            )
        }

        when (val raw = root["episodes"]) {
            is Map<*, *> -> raw.forEach { (k, v) ->
                val seasonKey = k?.toString()?.toIntOrNull()
                Json.asList(v)?.forEach { e -> Json.asMap(e)?.let { parseEpisode(it, seasonKey) } }
            }
            is List<*> -> raw.forEach { v ->
                val inner = v as? List<*>
                if (inner != null) inner.forEach { e -> Json.asMap(e)?.let { parseEpisode(it, null) } }
                else Json.asMap(v)?.let { parseEpisode(it, null) }
            }
            else -> Unit
        }

        val seasonNames = HashMap<Int, String>()
        root.list("seasons")?.forEach { s ->
            val m = Json.asMap(s) ?: return@forEach
            val n = m.int("season_number") ?: return@forEach
            m.str("name")?.let { seasonNames[n] = it }
        }

        val backdrop = info.firstString("backdrop_path") ?: series.backdrop
        val plot = info.str("plot") ?: series.plot
        if (backdrop != series.backdrop || plot != series.plot) {
            dao.updateSeriesInfo(series.playlistId, series.remoteId, backdrop, plot)
        }

        return SeriesDetails(
            plot = plot,
            cast = info.str("cast"),
            genre = info.str("genre") ?: series.genre,
            year = (info.str("releaseDate") ?: info.str("release_date"))?.take(4) ?: series.year,
            rating = info.float("rating") ?: series.rating,
            backdrop = backdrop,
            poster = info.str("cover") ?: series.poster,
            seasons = eps.groupBy { it.season }.toSortedMap().map { (n, list) ->
                Season(n, seasonNames[n]?.takeIf { !it.equals("Season $n", true) } ?: "Temporada $n", list.sortedBy { it.episode })
            },
        )
    }

    // ------------------------------------------------------------------ EPG

    suspend fun nowNext(ch: ChannelEntity): NowNext? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val epg = ch.epgId
        if (!epg.isNullOrBlank()) {
            val list = dao.upcoming(ch.playlistId, epg, now, 2)
            if (list.isNotEmpty()) return@withContext toNowNext(list.map { Triple(it.title, it.start, it.stop) }, list.firstOrNull()?.description, now)
        }
        val p = dao.playlist(ch.playlistId) ?: return@withContext null
        if (p.type != SourceType.XTREAM) return@withContext null
        val key = "${p.id}:${ch.remoteId}"
        val cached = shortEpgCache.get(key)
        val listings = if (cached != null && now - cached.first < 10 * 60_000L) {
            cached.second
        } else {
            val fetched = try {
                api(p).shortEpg(ch.remoteId, 4)
            } catch (_: Exception) {
                emptyList()
            }
            shortEpgCache.put(key, now to fetched)
            fetched
        }
        val upcoming = listings.filter { it.stop > now }.sortedBy { it.start }
        if (upcoming.isEmpty()) return@withContext null
        toNowNext(upcoming.map { Triple(it.title, it.start, it.stop) }, upcoming.first().description, now)
    }

    private fun toNowNext(list: List<Triple<String, Long, Long>>, firstDesc: String?, now: Long): NowNext {
        val first = list.first()
        val isNow = first.second <= now
        val next = if (isNow) list.getOrNull(1) else first
        return NowNext(
            nowTitle = if (isNow) first.first else null,
            nowStart = if (isNow) first.second else 0,
            nowStop = if (isNow) first.third else 0,
            nowDescription = if (isNow) firstDesc else null,
            nextTitle = next?.first,
            nextStart = next?.second ?: 0,
        )
    }

    /** Current programme for many channels at once, keyed by epg id. */
    suspend fun nowPlaying(channels: List<ChannelEntity>): Map<String, ProgrammeEntity> = withContext(Dispatchers.IO) {
        val pid = channels.firstOrNull()?.playlistId ?: return@withContext emptyMap()
        val ids = channels.mapNotNull { it.epgId?.takeIf { e -> e.isNotBlank() } }.distinct()
        if (ids.isEmpty()) return@withContext emptyMap()
        val now = System.currentTimeMillis()
        val out = HashMap<String, ProgrammeEntity>()
        ids.chunked(400).forEach { chunk ->
            dao.nowPlaying(pid, chunk, now).forEach { out[it.channelEpgId] = it }
        }
        out
    }

    // ------------------------------------------------------------------ play items

    fun liveUrl(c: ChannelEntity): String =
        if (prefs.liveFormat == "m3u8" && c.url.contains("/live/") && c.url.endsWith(".ts")) {
            c.url.dropLast(3) + ".m3u8"
        } else c.url

    fun channelItem(c: ChannelEntity) = PlayItem(
        uid = uidOf(c.playlistId, Kind.LIVE, c.remoteId),
        kind = Kind.LIVE,
        remoteId = c.remoteId,
        title = c.name,
        url = liveUrl(c),
        image = c.logo,
        epgId = c.epgId,
        number = c.number,
    )

    fun movieItem(m: MovieEntity) = PlayItem(
        uid = uidOf(m.playlistId, Kind.MOVIE, m.remoteId),
        kind = Kind.MOVIE,
        remoteId = m.remoteId,
        title = m.name,
        subtitle = m.year,
        url = m.url,
        image = m.poster,
    )

    fun episodeItem(s: SeriesEntity, e: EpisodeEntity) = PlayItem(
        uid = uidOf(e.playlistId, Kind.EPISODE, e.remoteId),
        kind = Kind.EPISODE,
        remoteId = e.remoteId,
        title = cleanEpisodeTitle(e.title, s.name, e.episode),
        subtitle = "T${e.season} · E${e.episode}",
        url = e.url,
        image = e.image ?: s.backdrop ?: s.poster,
        seriesId = s.remoteId,
        seriesTitle = s.name,
        seriesImage = s.poster,
    )

    // ------------------------------------------------------------------ progress

    suspend fun progress(uid: String): ProgressEntity? = dao.progress(uid)

    suspend fun saveProgress(item: PlayItem, playlistId: Long, positionMs: Long, durationMs: Long, hasNext: Boolean) {
        if (item.kind == Kind.LIVE || durationMs <= 0) return
        val now = System.currentTimeMillis()
        val finished = positionMs > durationMs * 0.92
        when (item.kind) {
            Kind.MOVIE -> {
                if (finished) dao.deleteProgress(item.uid)
                else if (positionMs > 15_000) dao.upsertProgress(
                    ProgressEntity(
                        uid = item.uid, playlistId = playlistId, kind = Kind.MOVIE.name, remoteId = item.remoteId,
                        title = item.title, subtitle = item.subtitle, image = item.image, url = item.url,
                        seriesId = null, positionMs = positionMs, durationMs = durationMs, updatedAt = now,
                    )
                )
            }
            Kind.EPISODE -> {
                dao.upsertProgress(
                    ProgressEntity(
                        uid = item.uid, playlistId = playlistId, kind = Kind.EPISODE.name, remoteId = item.remoteId,
                        title = item.title, subtitle = item.subtitle, image = item.image, url = item.url,
                        seriesId = item.seriesId, positionMs = if (finished) durationMs else positionMs,
                        durationMs = durationMs, updatedAt = now,
                    )
                )
                val sid = item.seriesId ?: return
                val seriesUid = uidOf(playlistId, Kind.SERIES, sid)
                if (finished && !hasNext) {
                    dao.deleteProgress(seriesUid)
                } else if (!finished && positionMs > 15_000) {
                    markSeriesResume(item, playlistId, positionMs, durationMs)
                }
            }
            else -> Unit
        }
    }

    /** Points the series' "continue watching" entry at [item]. */
    suspend fun markSeriesResume(item: PlayItem, playlistId: Long, positionMs: Long = 1, durationMs: Long = 1) {
        val sid = item.seriesId ?: return
        dao.upsertProgress(
            ProgressEntity(
                uid = uidOf(playlistId, Kind.SERIES, sid), playlistId = playlistId, kind = Kind.SERIES.name,
                remoteId = sid, title = item.seriesTitle ?: item.title, subtitle = item.subtitle,
                image = item.seriesImage ?: item.image, url = item.url, seriesId = sid,
                positionMs = positionMs, durationMs = durationMs, updatedAt = System.currentTimeMillis(),
            )
        )
    }

    fun continueWatching(pid: Long): Flow<List<ProgressEntity>> = dao.continueFlow(pid, 30)

    suspend fun removeFromContinue(uid: String) = dao.deleteProgress(uid)

    // ------------------------------------------------------------------ favorites & recents

    fun favoriteUids(pid: Long): Flow<List<String>> = dao.favoriteUidsFlow(pid)

    fun favorites(pid: Long): Flow<List<FavoriteEntity>> = dao.favoritesFlow(pid)

    suspend fun toggleFavorite(pid: Long, kind: Kind, remoteId: String, title: String, image: String?): Boolean {
        val uid = uidOf(pid, kind, remoteId)
        return if (dao.isFavorite(uid) > 0) {
            dao.deleteFavorite(uid)
            false
        } else {
            dao.upsertFavorite(FavoriteEntity(uid, pid, kind.name, remoteId, title, image, System.currentTimeMillis()))
            true
        }
    }

    suspend fun markChannelWatched(pid: Long, remoteId: String) {
        dao.upsertRecent(RecentChannelEntity(uidOf(pid, Kind.LIVE, remoteId), pid, remoteId, System.currentTimeMillis()))
    }

    fun recentChannels(pid: Long): Flow<List<RecentChannelEntity>> = dao.recentFlow(pid, 20)

    suspend fun channelsByIds(pid: Long, ids: List<String>): List<ChannelEntity> {
        if (ids.isEmpty()) return emptyList()
        val all = ids.chunked(400).flatMap { dao.channelsByIds(pid, it) }.associateBy { it.remoteId }
        return ids.mapNotNull { all[it] }
    }

    suspend fun moviesByIds(pid: Long, ids: List<String>): List<MovieEntity> {
        if (ids.isEmpty()) return emptyList()
        val all = ids.chunked(400).flatMap { dao.moviesByIds(pid, it) }.associateBy { it.remoteId }
        return ids.mapNotNull { all[it] }
    }

    suspend fun seriesByIds(pid: Long, ids: List<String>): List<SeriesEntity> {
        if (ids.isEmpty()) return emptyList()
        val all = ids.chunked(400).flatMap { dao.seriesByIds(pid, it) }.associateBy { it.remoteId }
        return ids.mapNotNull { all[it] }
    }

    // ------------------------------------------------------------------ helpers

    private class Batcher<T>(private val size: Int, private val flush: suspend (List<T>) -> Unit) {
        private val buffer = ArrayList<T>(size)
        var count = 0
            private set

        suspend fun add(item: T) {
            buffer += item
            count++
            if (buffer.size >= size) {
                flush(ArrayList(buffer))
                buffer.clear()
            }
        }

        suspend fun finish() {
            if (buffer.isNotEmpty()) {
                flush(ArrayList(buffer))
                buffer.clear()
            }
        }
    }

    companion object {
        fun stableId(s: String): String =
            s.hashCode().toUInt().toString(36) + s.length.toString(36)

        fun hostOf(url: String): String =
            url.substringAfter("://").substringBefore('/').substringBefore(':').ifBlank { "Mi lista" }

        private val yearRegex = Regex("""[\(\[ ]((19|20)\d{2})[\)\] ]?""")
        fun yearIn(name: String): String? = yearRegex.find(name)?.groupValues?.get(1)

        fun formatDurationSecs(secs: Int): String {
            val h = secs / 3600
            val m = (secs % 3600) / 60
            return if (h > 0) "${h} h ${m} min" else "$m min"
        }

        private val episodePrefix = Regex("""^.*?[Ss]\d{1,2}\s*[EeXx]\d{1,3}\s*[-–:|.]?\s*""")
        fun cleanEpisodeTitle(title: String, seriesName: String, number: Int): String {
            var t = title.trim()
            val stripped = episodePrefix.replace(t, "").trim()
            if (stripped.isNotEmpty()) t = stripped
            else if (t.startsWith(seriesName, ignoreCase = true)) t = t.substring(seriesName.length).trim(' ', '-', '|', ':')
            return if (t.isBlank() || t.equals(seriesName, true)) "Episodio $number" else t
        }
    }
}
