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

    // Todas as categorias disponíveis
    private val ALL_MOVIE_CATEGORIES = mapOf(
        "73" to "4K",
        "69" to "AÇÃO",
        "70" to "COMÉDIA",
        "74" to "DRAMA",
        "72" to "TERROR",
        "98" to "FICÇÃO CIENTÍFICA"
    )

    private val ALL_SERIES_CATEGORIES = mapOf(
        "107" to "Netflix",
        "118" to "HBO Max",
        "109" to "Apple TV",
        "112" to "Crunchyroll",
        "126" to "Doramas"
    )

    // Categorias ativas (vem das preferências)
    private fun getActiveMovieCategories(): Map<String, String> {
        val prefs = app.getSharedPreferences("streamflix_prefs", Context.MODE_PRIVATE)
        val activeIds = prefs.getStringSet("active_movies", ALL_MOVIE_CATEGORIES.keys.toSet())
        return ALL_MOVIE_CATEGORIES.filterKeys { activeIds?.contains(it) == true }
    }

    private fun getActiveSeriesCategories(): Map<String, String> {
        val prefs = app.getSharedPreferences("streamflix_prefs", Context.MODE_PRIVATE)
        val activeIds = prefs.getStringSet("active_series", ALL_SERIES_CATEGORIES.keys.toSet())
        return ALL_SERIES_CATEGORIES.filterKeys { activeIds?.contains(it) == true }
    }

    override val mainPage = mainPageOf(
        *getActiveMovieCategories().map { (id, name) ->
            "$mainUrl/api_proxy.php?action=get_vod_streams&category_id=$id" to "🎬 $name"
        }.toTypedArray(),
        *getActiveSeriesCategories().map { (id, name) ->
            "$mainUrl/api_proxy.php?action=get_series&category_id=$id" to "📺 $name"
        }.toTypedArray()
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isMovies = request.url.contains("get_vod_streams")
        val categoryId = request.url.substringAfter("category_id=").substringBefore("&")
        
        val items = if (isMovies) {
            getMoviesByCategory(categoryId, page)
        } else {
            getSeriesByCategory(categoryId, page)
        }
        
        return newHomePageResponse(request.name, items, hasNext = items.size == PAGE_SIZE)
    }

    private suspend fun getMoviesByCategory(categoryId: String, page: Int): List<SearchResponse> {
        val allMovies = getAllMovies()
        val isFourKCategory = categoryId == "73"
        
        val categoryMovies = mutableListOf<JSONObject>()
        for (i in 0 until allMovies.length()) {
            val movie = allMovies.getJSONObject(i)
            val movieCategories = getCategoryIds(movie)
            if (movieCategories.contains(categoryId)) {
                categoryMovies.add(movie)
            }
        }
        
        val start = page * PAGE_SIZE
        val end = minOf(start + PAGE_SIZE, categoryMovies.size)
        
        if (start >= categoryMovies.size) return emptyList()
        
        val results = mutableListOf<SearchResponse>()
        for (i in start until end) {
            val movie = categoryMovies[i]
            var rawName = movie.getString("name")
            
            if (isAdultContent(rawName)) continue
            
            rawName = cleanTitle(rawName)
            val (cleanName, dubStatus, qualityTag) = processTitle(rawName, isFourKCategory)
            val id = movie.getInt("stream_id")
            val poster = fixImageUrl(movie.optString("stream_icon"))
            val ratingValue = movie.optDouble("rating_5based", 0.0).let { it.toFloat() * 2 }
            
            results.add(
                newAnimeSearchResponse(cleanName, "movie?id=$id", TvType.Movie) {
                    this.posterUrl = poster
                    if (qualityTag != null) this.quality = qualityTag
                    if (dubStatus != null) this.dubStatus = dubStatus
                    this.score = Score.from10(ratingValue)
                }
            )
        }
        return results
    }

    private suspend fun getSeriesByCategory(categoryId: String, page: Int): List<SearchResponse> {
        val allSeries = getAllSeries()
        val isFourKCategory = categoryId == "73"
        
        val categorySeries = mutableListOf<JSONObject>()
        for (i in 0 until allSeries.length()) {
            val series = allSeries.getJSONObject(i)
            val seriesCategories = getCategoryIds(series)
            if (seriesCategories.contains(categoryId)) {
                categorySeries.add(series)
            }
        }
        
        val start = page * PAGE_SIZE
        val end = minOf(start + PAGE_SIZE, categorySeries.size)
        
        if (start >= categorySeries.size) return emptyList()
        
        val results = mutableListOf<SearchResponse>()
        for (i in start until end) {
            val series = categorySeries[i]
            var rawName = series.getString("name")
            
            if (isAdultContent(rawName)) continue
            
            rawName = cleanTitle(rawName)
            val (cleanName, dubStatus, qualityTag) = processTitle(rawName, isFourKCategory)
            val id = series.getInt("series_id")
            val poster = fixImageUrl(series.optString("cover"))
            val ratingValue = series.optDouble("rating_5based", 0.0).let { it.toFloat() * 2 }
            
            results.add(
                newAnimeSearchResponse(cleanName, "series?id=$id", TvType.TvSeries) {
                    this.posterUrl = poster
                    if (qualityTag != null) this.quality = qualityTag
                    if (dubStatus != null) this.dubStatus = dubStatus
                    this.score = Score.from10(ratingValue)
                }
            )
        }
        return results
    }

    private fun cleanTitle(title: String): String {
        var cleaned = title.trim()
        cleaned = cleaned.replace(Regex("\\b4K\\b", RegexOption.IGNORE_CASE), "")
        cleaned = cleaned.replace(Regex("\\s*4K\\s*", RegexOption.IGNORE_CASE), " ")
        cleaned = cleaned.replace(Regex("\\s*\\(\\d{4}\\)\\s*"), " ")
        cleaned = cleaned.replace(Regex("\\s*\\(\\d{4}\\)\$"), "")
        cleaned = cleaned.replace(Regex("\\s*\\[[^\\]]+\\]\\s*"), " ")
        cleaned = cleaned.replace(Regex("\\s*\\[[^\\]]+\\]\\s*\$"), "")
        cleaned = cleaned.replace(Regex("\\s+"), " ").trim()
        return cleaned
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

    private suspend fun getAllMovies(): JSONArray {
        if (cachedMovies != null) return cachedMovies!!
        return withContext(Dispatchers.IO) {
            val response = app.get("$mainUrl/api_proxy.php?action=get_vod_streams")
            val json = JSONArray(response.body.string())
            cachedMovies = json
            json
        }
    }

    private suspend fun getAllSeries(): JSONArray {
        if (cachedSeries != null) return cachedSeries!!
        return withContext(Dispatchers.IO) {
            val response = app.get("$mainUrl/api_proxy.php?action=get_series")
            val json = JSONArray(response.body.string())
            cachedSeries = json
            json
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()
        
        val results = mutableListOf<SearchResponse>()
        val queryLower = query.lowercase()
        
        val allMovies = getAllMovies()
        for (i in 0 until allMovies.length()) {
            val movie = allMovies.getJSONObject(i)
            var rawName = movie.getString("name")
            if (rawName.lowercase().contains(queryLower) && !isAdultContent(rawName)) {
                rawName = cleanTitle(rawName)
                val (cleanName, dubStatus, qualityTag) = processTitle(rawName, false)
                val id = movie.getInt("stream_id")
                val poster = fixImageUrl(movie.optString("stream_icon"))
                val ratingValue = movie.optDouble("rating_5based", 0.0).let { it.toFloat() * 2 }
                
                results.add(newAnimeSearchResponse(cleanName, "movie?id=$id", TvType.Movie) {
                    this.posterUrl = poster
                    if (qualityTag != null) this.quality = qualityTag
                    if (dubStatus != null) this.dubStatus = dubStatus
                    this.score = Score.from10(ratingValue)
                })
            }
        }
        
        val allSeries = getAllSeries()
        for (i in 0 until allSeries.length()) {
            val series = allSeries.getJSONObject(i)
            var rawName = series.getString("name")
            if (rawName.lowercase().contains(queryLower) && !isAdultContent(rawName)) {
                rawName = cleanTitle(rawName)
                val (cleanName, dubStatus, qualityTag) = processTitle(rawName, false)
                val id = series.getInt("series_id")
                val poster = fixImageUrl(series.optString("cover"))
                val ratingValue = series.optDouble("rating_5based", 0.0).let { it.toFloat() * 2 }
                
                results.add(newAnimeSearchResponse(cleanName, "series?id=$id", TvType.TvSeries) {
                    this.posterUrl = poster
                    if (qualityTag != null) this.quality = qualityTag
                    if (dubStatus != null) this.dubStatus = dubStatus
                    this.score = Score.from10(ratingValue)
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

    private fun processTitle(rawTitle: String, isFourKCategory: Boolean): Triple<String, EnumSet<DubStatus>?, SearchQuality?> {
        var cleanTitle = rawTitle.trim()
        var dubStatus: EnumSet<DubStatus>? = null
        var qualityTag: SearchQuality? = null
        
        if (isFourKCategory) qualityTag = SearchQuality.FourK
        
        val hasLegTag = Regex("\\[L\\]", RegexOption.IGNORE_CASE).containsMatchIn(cleanTitle)
        
        if (hasLegTag) {
            dubStatus = EnumSet.of(DubStatus.Subbed)
            cleanTitle = cleanTitle.replace(Regex("\\s*\\[L\\]\\s*", RegexOption.IGNORE_CASE), " ")
        } else {
            dubStatus = EnumSet.of(DubStatus.Dubbed)
        }
        
        cleanTitle = cleanTitle.replace(Regex("\\s+"), " ").trim()
        return Triple(cleanTitle, dubStatus, qualityTag)
    }

    private suspend fun loadMovie(id: String): LoadResponse? {
        return withContext(Dispatchers.IO) {
            try {
                val infoResponse = app.get("$mainUrl/api_proxy.php?action=get_vod_info&vod_id=$id")
                val info = JSONObject(infoResponse.body.string()).optJSONObject("info") ?: JSONObject()
                
                val rawTitle = info.optString("name", "Título indisponível")
                val (finalTitle, _, _) = processTitle(cleanTitle(rawTitle), false)
                
                val streamResponse = app.get("$mainUrl/api_proxy.php?action=get_stream_url&type=movie&id=$id")
                val videoUrl = JSONObject(streamResponse.body.string()).getString("stream_url")
                
                newMovieLoadResponse(finalTitle, "movie?id=$id", TvType.Movie, videoUrl) {
                    this.posterUrl = fixImageUrl(info.optString("cover_big"))
                    this.plot = info.optString("plot", "Sinopse não disponível.")
                    this.score = info.optDouble("rating_5based", 0.0).let { Score.from10(it.toFloat() * 2) }
                }
            } catch (e: Exception) { null }
        }
    }

    private suspend fun loadSeries(id: String): LoadResponse? {
        return withContext(Dispatchers.IO) {
            try {
                val infoResponse = app.get("$mainUrl/api_proxy.php?action=get_series_info&series_id=$id")
                val json = JSONObject(infoResponse.body.string())
                val info = json.optJSONObject("info") ?: JSONObject()
                
                val rawTitle = info.getString("name")
                val (finalTitle, _, _) = processTitle(cleanTitle(rawTitle), false)
                
                val episodes = extractEpisodes(json)
                if (episodes.isEmpty()) return@withContext null
                
                newTvSeriesLoadResponse(finalTitle, "series?id=$id", TvType.TvSeries, episodes) {
                    this.posterUrl = fixImageUrl(info.optString("cover"))
                    this.plot = info.optString("plot", "Sinopse não disponível.")
                    this.score = info.optDouble("rating_5based", 0.0).let { Score.from10(it.toFloat() * 2) }
                }
            } catch (e: Exception) { null }
        }
    }

    private suspend fun extractEpisodes(json: JSONObject): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val episodesJson = json.optJSONObject("episodes") ?: return emptyList()
        
        val seasonKeys = episodesJson.keys()
        while (seasonKeys.hasNext()) {
            val seasonNum = seasonKeys.next().toIntOrNull() ?: continue
            val seasonArray = episodesJson.getJSONArray(seasonNum.toString())
            
            for (i in 0 until seasonArray.length()) {
                val ep = seasonArray.getJSONObject(i)
                val epNum = ep.getInt("episode_num")
                val epTitle = ep.getString("title")
                val epId = ep.getString("id")
                
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
    private data class TMDBSearchResponse(@JsonProperty("results") val results: List<TMDBResult>)
    private data class TMDBResult(val id: Int, val title: String?, val name: String?, val release_date: String?, val first_air_date: String?, val poster_path: String?)
    private data class TMDBDetailsResponse(val overview: String?, val backdrop_path: String?, val runtime: Int?, val genres: List<TMDBGenre>?, val credits: TMDBCredits?, val videos: TMDBVideos?, val vote_average: Double?, val seasons: List<TMDBSeason>?)
    private data class TMDBSeason(@JsonProperty("season_number") val season_number: Int)
    private data class TMDBSeasonResponse(val episodes: List<TMDBEpisode>)
    private data class TMDBEpisode(val episode_number: Int, val name: String, val overview: String?, val still_path: String?, val runtime: Int?, val air_date: String?)
    private data class TMDBGenre(val name: String)
    private data class TMDBCredits(val cast: List<TMDBCast>)
    private data class TMDBCast(val name: String, val character: String?, val profile_path: String?)
    private data class TMDBVideos(val results: List<TMDBVideo>)
    private data class TMDBVideo(val key: String, val site: String, val type: String, val official: Boolean?)
}
