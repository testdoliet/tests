package com.StreamFlix

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@CloudstreamPlugin
class StreamFlixProvider : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(StreamFlix())
    }
}

class StreamFlix : MainAPI() {
    override var mainUrl = "https://streamflix.live"
    override var name = "StreamFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private var cachedMovies: JSONArray? = null
    private var cachedSeries: JSONArray? = null
    private val PAGE_SIZE = 30

    private val tmdbImageUrl = "https://image.tmdb.org/t/p"
    private val TMDB_API_KEY = BuildConfig.TMDB_API_KEY
    private val TMDB_ACCESS_TOKEN = BuildConfig.TMDB_ACCESS_TOKEN

    // Categorias
    private val MOVIE_CATEGORIES = mapOf(
        "73" to "4K", "69" to "Ação", "96" to "Animação", "70" to "Comédia",
        "74" to "Drama", "72" to "Terror", "90" to "Suspense", "92" to "Romance",
        "98" to "Ficção Científica", "75" to "Nacionais", "71" to "Legendados",
        "97" to "Lançamentos"
    )

    private val SERIES_CATEGORIES = mapOf(
        "107" to "Séries - Netflix", "109" to "Séries - Apple TV",
        "112" to "Séries - Crunchyroll", "114" to "Séries - Discovery",
        "115" to "Séries - Disney Plus", "126" to "Séries - Doramas",
        "117" to "Séries - Globoplay", "119" to "Séries - Legendadas",
        "118" to "Séries - Max (HBO Max)", "123" to "Séries - Paramount+"
    )

    override val mainPage = mainPageOf(
        *MOVIE_CATEGORIES.map { (id, name) -> "$mainUrl/api_proxy.php?action=get_vod_streams&category_id=$id" to name }.toTypedArray(),
        *SERIES_CATEGORIES.map { (id, name) -> "$mainUrl/api_proxy.php?action=get_series&category_id=$id" to name }.toTypedArray()
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isMovies = request.url.contains("get_vod_streams")
        val categoryId = if (request.url.contains("category_id=")) {
            request.url.substringAfter("category_id=").substringBefore("&")
        } else null
        
        val items = if (isMovies) {
            getMoviesByCategory(categoryId, page)
        } else {
            getSeriesByCategory(categoryId, page)
        }
        
        return newHomePageResponse(request.name, items, hasNext = items.size == PAGE_SIZE)
    }

    private suspend fun getMoviesByCategory(categoryId: String?, page: Int): List<SearchResponse> {
        val allMovies = getAllMovies()
        
        val filteredMovies = if (categoryId != null) {
            allMovies.filter { movie ->
                val categories = getCategoryIds(movie as JSONObject)
                categories.contains(categoryId)
            }
        } else {
            allMovies
        }
        
        val start = page * PAGE_SIZE
        val end = minOf(start + PAGE_SIZE, filteredMovies.size)
        
        if (start >= filteredMovies.size) return emptyList()
        
        return filteredMovies.subList(start, end).mapNotNull { movie ->
            val movieJson = movie as JSONObject
            val rawName = movieJson.getString("name")
            
            if (isAdultContent(rawName)) return@mapNotNull null
            
            val (cleanName, dubStatus, qualityTag) = processTitle(rawName)
            val id = movieJson.getInt("stream_id")
            val poster = fixImageUrl(movieJson.optString("stream_icon"))
            val ratingValue = movieJson.optDouble("rating_5based", 0.0).takeIf { it > 0 }?.let { it.toFloat() * 2 }
            
            newAnimeSearchResponse(cleanName, "movie?id=$id", TvType.Movie) {
                this.posterUrl = poster
                if (qualityTag != null) this.quality = qualityTag
                if (dubStatus != null) this.dubStatus = dubStatus
                if (ratingValue != null && ratingValue > 0) {
                    this.score = Score.from10(ratingValue)
                }
            }
        }
    }

    private suspend fun getSeriesByCategory(categoryId: String?, page: Int): List<SearchResponse> {
        val allSeries = getAllSeries()
        
        val filteredSeries = if (categoryId != null) {
            allSeries.filter { series ->
                val categories = getCategoryIds(series as JSONObject)
                categories.contains(categoryId)
            }
        } else {
            allSeries
        }
        
        val start = page * PAGE_SIZE
        val end = minOf(start + PAGE_SIZE, filteredSeries.size)
        
        if (start >= filteredSeries.size) return emptyList()
        
        return filteredSeries.subList(start, end).mapNotNull { series ->
            val seriesJson = series as JSONObject
            val rawName = seriesJson.getString("name")
            
            if (isAdultContent(rawName)) return@mapNotNull null
            
            val (cleanName, dubStatus, qualityTag) = processTitle(rawName)
            val id = seriesJson.getInt("series_id")
            val poster = fixImageUrl(seriesJson.optString("cover"))
            val ratingValue = seriesJson.optDouble("rating_5based", 0.0).takeIf { it > 0 }?.let { it.toFloat() * 2 }
            
            newAnimeSearchResponse(cleanName, "series?id=$id", TvType.TvSeries) {
                this.posterUrl = poster
                if (qualityTag != null) this.quality = qualityTag
                if (dubStatus != null) this.dubStatus = dubStatus
                if (ratingValue != null && ratingValue > 0) {
                    this.score = Score.from10(ratingValue)
                }
            }
        }
    }

    private fun getCategoryIds(obj: JSONObject): Set<String> {
        val ids = mutableSetOf<String>()
        
        val categoryIdsArray = obj.optJSONArray("category_ids")
        if (categoryIdsArray != null) {
            for (i in 0 until categoryIdsArray.length()) {
                ids.add(categoryIdsArray.getInt(i).toString())
            }
        }
        
        val categoryId = obj.optString("category_id", null)
        if (categoryId != null && categoryId.isNotEmpty() && categoryId != "null") {
            ids.add(categoryId)
        }
        
        return ids
    }

    private suspend fun getAllMovies(): List<JSONObject> {
        if (cachedMovies != null) return cachedMovies?.map { it as JSONObject } ?: emptyList()
        
        return withContext(Dispatchers.IO) {
            val response = app.get("$mainUrl/api_proxy.php?action=get_vod_streams")
            val json = JSONArray(response.body.string())
            val list = (0 until json.length()).map { json.getJSONObject(it) }
            cachedMovies = JSONArray().apply { list.forEach { put(it) } }
            list
        }
    }

    private suspend fun getAllSeries(): List<JSONObject> {
        if (cachedSeries != null) return cachedSeries?.map { it as JSONObject } ?: emptyList()
        
        return withContext(Dispatchers.IO) {
            val response = app.get("$mainUrl/api_proxy.php?action=get_series")
            val json = JSONArray(response.body.string())
            val list = (0 until json.length()).map { json.getJSONObject(it) }
            cachedSeries = JSONArray().apply { list.forEach { put(it) } }
            list
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()
        
        val queryLower = query.lowercase()
        val results = mutableListOf<SearchResponse>()
        
        getAllMovies().forEach { movie ->
            val rawName = movie.getString("name")
            if (rawName.lowercase().contains(queryLower) && !isAdultContent(rawName)) {
                val (cleanName, dubStatus, qualityTag) = processTitle(rawName)
                val id = movie.getInt("stream_id")
                val poster = fixImageUrl(movie.optString("stream_icon"))
                val ratingValue = movie.optDouble("rating_5based", 0.0).takeIf { it > 0 }?.let { it.toFloat() * 2 }
                
                results.add(newAnimeSearchResponse(cleanName, "movie?id=$id", TvType.Movie) {
                    this.posterUrl = poster
                    if (qualityTag != null) this.quality = qualityTag
                    if (dubStatus != null) this.dubStatus = dubStatus
                    if (ratingValue != null && ratingValue > 0) this.score = Score.from10(ratingValue)
                })
            }
        }
        
        getAllSeries().forEach { series ->
            val rawName = series.getString("name")
            if (rawName.lowercase().contains(queryLower) && !isAdultContent(rawName)) {
                val (cleanName, dubStatus, qualityTag) = processTitle(rawName)
                val id = series.getInt("series_id")
                val poster = fixImageUrl(series.optString("cover"))
                val ratingValue = series.optDouble("rating_5based", 0.0).takeIf { it > 0 }?.let { it.toFloat() * 2 }
                
                results.add(newAnimeSearchResponse(cleanName, "series?id=$id", TvType.TvSeries) {
                    this.posterUrl = poster
                    if (qualityTag != null) this.quality = qualityTag
                    if (dubStatus != null) this.dubStatus = dubStatus
                    if (ratingValue != null && ratingValue > 0) this.score = Score.from10(ratingValue)
                })
            }
        }
        
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val cleanUrl = url.removePrefix("https://streamflix.live/").removePrefix("http://streamflix.live/").removePrefix("streamflix.live/")
        
        return when {
            cleanUrl.startsWith("movie?id=") -> loadMovie(cleanUrl.substringAfter("movie?id="))
            cleanUrl.startsWith("series?id=") -> loadSeries(cleanUrl.substringAfter("series?id="))
            else -> null
        }
    }

    private fun isAdultContent(title: String): Boolean {
        val adultKeywords = listOf("XXX", "ADULTOS", "Porn", "Sexo", "Erótico", "Hardcore", "18+", "Adult")
        return adultKeywords.any { title.uppercase().contains(it.uppercase()) }
    }

    private fun processTitle(rawTitle: String): Triple<String, EnumSet<DubStatus>?, SearchQuality?> {
        var cleanTitle = rawTitle.trim()
        var dubStatus: EnumSet<DubStatus>? = null
        var qualityTag: SearchQuality? = null
        
        // Legendado [L]
        if (Regex(".*\\[L\\]\\s*$", RegexOption.IGNORE_CASE).containsMatchIn(cleanTitle)) {
            dubStatus = EnumSet.of(DubStatus.Subbed)
            cleanTitle = cleanTitle.replace(Regex("\\s*\\[L\\]\\s*$", RegexOption.IGNORE_CASE), "").trim()
        } else {
            dubStatus = EnumSet.of(DubStatus.Dubbed)
        }
        
        // 4K isolado no final
        if (Regex("\\s+4K\\s*$", RegexOption.IGNORE_CASE).containsMatchIn(cleanTitle)) {
            qualityTag = SearchQuality.FourK
            cleanTitle = cleanTitle.replace(Regex("\\s+4K\\s*$", RegexOption.IGNORE_CASE), "").trim()
        }
        
        // Remove outras tags
        cleanTitle = cleanTitle.replace(Regex("\\s*\\[[^\\]]+\\]\\s*$"), "").trim()
        
        return Triple(cleanTitle, dubStatus, qualityTag)
    }

    private suspend fun loadMovie(id: String): LoadResponse? {
        return withContext(Dispatchers.IO) {
            try {
                val infoResponse = app.get("$mainUrl/api_proxy.php?action=get_vod_info&vod_id=$id")
                val info = JSONObject(infoResponse.body.string()).optJSONObject("info") ?: JSONObject()
                
                val rawTitle = info.optString("name", "Título indisponível")
                val (cleanTitle, dubStatus, qualityTag) = processTitle(rawTitle)
                
                val tmdbData = searchMovieOnTMDB(cleanTitle)
                val poster = tmdbData?.posterUrl ?: fixImageUrl(info.optString("cover_big"))
                val backdrop = tmdbData?.backdropUrl ?: fixImageUrl(info.optString("cover_big"))
                
                val streamResponse = app.get("$mainUrl/api_proxy.php?action=get_stream_url&type=movie&id=$id")
                val videoUrl = JSONObject(streamResponse.body.string()).getString("stream_url")
                
                newMovieLoadResponse(cleanTitle, "movie?id=$id", TvType.Movie, videoUrl) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backdrop
                    this.plot = tmdbData?.overview ?: info.optString("plot", "Sinopse não disponível.")
                    this.year = tmdbData?.year ?: info.optString("releaseDate").takeIf { it.isNotEmpty() }?.substring(0, 4)?.toIntOrNull()
                    this.score = tmdbData?.rating?.let { Score.from10(it) } ?: info.optDouble("rating_5based", 0.0).takeIf { it > 0 }?.let { Score.from10(it.toFloat() * 2) }
                    this.duration = tmdbData?.duration ?: info.optInt("duration_secs", 0).takeIf { it > 0 }?.let { it / 60 }
                    this.tags = tmdbData?.genres ?: info.optString("genre").split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    
                    if (dubStatus != null) this.dubStatus = dubStatus
                    if (qualityTag != null) this.quality = qualityTag
                    
                    tmdbData?.actors?.takeIf { it.isNotEmpty() }?.let { addActors(it) }
                    tmdbData?.youtubeTrailer?.let { addTrailer(it) }
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private suspend fun loadSeries(id: String): LoadResponse? {
        return withContext(Dispatchers.IO) {
            try {
                val infoResponse = app.get("$mainUrl/api_proxy.php?action=get_series_info&series_id=$id")
                val json = JSONObject(infoResponse.body.string())
                val info = json.optJSONObject("info") ?: JSONObject()
                
                val rawTitle = info.getString("name")
                val (cleanTitle, dubStatus, qualityTag) = processTitle(rawTitle)
                
                val tmdbData = searchSeriesOnTMDB(cleanTitle)
                val poster = tmdbData?.posterUrl ?: fixImageUrl(info.optString("cover"))
                val backdrop = tmdbData?.backdropUrl ?: fixImageUrl(info.optString("cover"))
                
                val episodes = extractEpisodes(json, tmdbData)
                if (episodes.isEmpty()) return@withContext null
                
                newTvSeriesLoadResponse(cleanTitle, "series?id=$id", TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backdrop
                    this.plot = tmdbData?.overview ?: info.optString("plot", "Sinopse não disponível.")
                    this.year = tmdbData?.year ?: info.optString("releaseDate").takeIf { it.isNotEmpty() }?.substring(0, 4)?.toIntOrNull()
                    this.score = tmdbData?.rating?.let { Score.from10(it) } ?: info.optDouble("rating_5based", 0.0).takeIf { it > 0 }?.let { Score.from10(it.toFloat() * 2) }
                    this.tags = tmdbData?.genres ?: info.optString("genre").split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    
                    if (dubStatus != null) this.dubStatus = dubStatus
                    if (qualityTag != null) this.quality = qualityTag
                    
                    tmdbData?.actors?.takeIf { it.isNotEmpty() }?.let { addActors(it) }
                    tmdbData?.youtubeTrailer?.let { addTrailer(it) }
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private suspend fun searchMovieOnTMDB(query: String): TMDBInfo? {
        return searchTMDB("movie", query)
    }

    private suspend fun searchSeriesOnTMDB(query: String): TMDBInfo? {
        return searchTMDB("tv", query)
    }

    private suspend fun searchTMDB(type: String, query: String): TMDBInfo? {
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "https://api.themoviedb.org/3/search/$type?api_key=$TMDB_API_KEY&query=$encodedQuery&language=pt-BR"
            val headers = mapOf("Authorization" to "Bearer $TMDB_ACCESS_TOKEN", "accept" to "application/json")
            
            val response = app.get(url, headers = headers, timeout = 10_000)
            if (response.code != 200) return null
            
            val result = response.parsedSafe<TMDBSearchResponse>()?.results?.firstOrNull() ?: return null
            val details = getTMDBDetails(type, result.id)
            
            TMDBInfo(
                year = if (type == "movie") result.release_date?.substring(0, 4)?.toIntOrNull() else result.first_air_date?.substring(0, 4)?.toIntOrNull(),
                posterUrl = result.poster_path?.let { "$tmdbImageUrl/w500$it" },
                backdropUrl = details?.backdrop_path?.let { "$tmdbImageUrl/original$it" },
                overview = details?.overview,
                rating = details?.vote_average?.takeIf { it > 0 },
                genres = details?.genres?.map { it.name },
                duration = details?.runtime,
                actors = details?.credits?.cast?.take(15)?.mapNotNull { actor ->
                    if (actor.name.isNotBlank()) Pair(Actor(actor.name, actor.profile_path?.let { "$tmdbImageUrl/w185$it" }), actor.character) else null
                },
                youtubeTrailer = getHighQualityTrailer(details?.videos?.results)
            )
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun getTMDBDetails(type: String, id: Int): TMDBDetailsResponse? {
        return try {
            val url = "https://api.themoviedb.org/3/$type/$id?api_key=$TMDB_API_KEY&language=pt-BR&append_to_response=credits,videos"
            val headers = mapOf("Authorization" to "Bearer $TMDB_ACCESS_TOKEN", "accept" to "application/json")
            app.get(url, headers = headers, timeout = 10_000).parsedSafe()
        } catch (e: Exception) { null }
    }

    private suspend fun getTMDBSeasons(seriesId: Int, details: TMDBDetailsResponse?): Map<Int, List<TMDBEpisode>> {
        val result = mutableMapOf<Int, List<TMDBEpisode>>()
        details?.seasons?.forEach { season ->
            if (season.season_number > 0) {
                val url = "https://api.themoviedb.org/3/tv/$seriesId/season/${season.season_number}?api_key=$TMDB_API_KEY&language=pt-BR"
                val headers = mapOf("Authorization" to "Bearer $TMDB_ACCESS_TOKEN", "accept" to "application/json")
                val seasonData = app.get(url, headers = headers, timeout = 10_000).parsedSafe<TMDBSeasonResponse>()
                seasonData?.episodes?.let { result[season.season_number] = it }
            }
        }
        return result
    }

    private fun getHighQualityTrailer(videos: List<TMDBVideo>?): String? {
        return videos?.find { it.site == "YouTube" && it.type == "Trailer" && it.official == true }
            ?.key?.let { "https://www.youtube.com/watch?v=$it" }
    }

    private suspend fun extractEpisodes(json: JSONObject, tmdbData: TMDBInfo?): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val episodesJson = json.optJSONObject("episodes") ?: return emptyList()
        
        val seasonKeys = episodesJson.keys()
        while (seasonKeys.hasNext()) {
            val seasonNum = seasonKeys.next().toIntOrNull() ?: continue
            val seasonArray = episodesJson.getJSONArray(seasonNum.toString())
            
            for (i in 0 until seasonArray.length()) {
                val ep = seasonArray.getJSONObject(i)
                val epNum = ep.getInt("episode_num")
                val epId = ep.getString("id")
                val epTitle = ep.getString("title")
                
                val streamResponse = app.get("$mainUrl/api_proxy.php?action=get_stream_url&type=series&id=$epId")
                val videoUrl = JSONObject(streamResponse.body.string()).getString("stream_url")
                
                episodes.add(newEpisode(videoUrl) {
                    this.name = epTitle
                    this.season = seasonNum
                    this.episode = epNum
                })
            }
        }
        return episodes
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        callback(newExtractorLink(name, name, data, mainUrl, ExtractorLinkType.VIDEO))
        return true
    }

    private fun fixImageUrl(url: String?): String? {
        if (url.isNullOrBlank() || url.startsWith("data:")) return null
        var fixed = url.trim()
        if (fixed.startsWith("//")) fixed = "https:$fixed"
        if (!fixed.startsWith("http") && fixed.startsWith("/")) fixed = "$mainUrl$fixed"
        return fixed
    }

    // Data classes
    private data class TMDBInfo(
        val year: Int?,
        val posterUrl: String?,
        val backdropUrl: String?,
        val overview: String?,
        val rating: Double?,
        val genres: List<String>?,
        val duration: Int?,
        val actors: List<Pair<Actor, String?>>?,
        val youtubeTrailer: String?
    )

    private data class TMDBSearchResponse(@JsonProperty("results") val results: List<TMDBResult>)
    private data class TMDBResult(
        val id: Int,
        val release_date: String?,
        val first_air_date: String?,
        val poster_path: String?
    )
    private data class TMDBDetailsResponse(
        val overview: String?,
        val backdrop_path: String?,
        val runtime: Int?,
        val genres: List<TMDBGenre>?,
        val credits: TMDBCredits?,
        val videos: TMDBVideos?,
        val vote_average: Double?,
        val seasons: List<TMDBSeason>?
    )
    private data class TMDBSeason(@JsonProperty("season_number") val season_number: Int)
    private data class TMDBSeasonResponse(val episodes: List<TMDBEpisode>)
    private data class TMDBEpisode(val episode_number: Int, val name: String, val overview: String?, val still_path: String?, val runtime: Int?, val air_date: String?)
    private data class TMDBGenre(val name: String)
    private data class TMDBCredits(val cast: List<TMDBCast>)
    private data class TMDBCast(val name: String, val character: String?, val profile_path: String?)
    private data class TMDBVideos(val results: List<TMDBVideo>)
    private data class TMDBVideo(val key: String, val site: String, val type: String, val official: Boolean?)
}
