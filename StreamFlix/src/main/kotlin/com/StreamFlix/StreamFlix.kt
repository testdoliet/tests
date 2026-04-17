package com.StreamFlix

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

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

    // Mapa de gêneros TMDB
    private val genreMap = mapOf(
        28 to "Ação", 12 to "Aventura", 16 to "Animação", 35 to "Comédia",
        80 to "Crime", 99 to "Documentário", 18 to "Drama", 10751 to "Família",
        14 to "Fantasia", 36 to "História", 27 to "Terror", 10402 to "Música",
        9648 to "Mistério", 10749 to "Romance", 878 to "Ficção Científica",
        53 to "Thriller", 10752 to "Guerra", 37 to "Faroeste"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/api_proxy.php?action=get_vod_streams" to "Filmes",
        "$mainUrl/api_proxy.php?action=get_series" to "Séries"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isMovies = request.name == "Filmes"
        
        val items = if (isMovies) {
            getMoviesPaginated(page)
        } else {
            getSeriesPaginated(page)
        }
        
        return newHomePageResponse(request.name, items, hasNext = items.size == PAGE_SIZE)
    }

    private suspend fun getMoviesPaginated(page: Int): List<SearchResponse> {
        val allMovies = getAllMovies()
        val start = page * PAGE_SIZE
        val end = minOf(start + PAGE_SIZE, allMovies.length())
        
        if (start >= allMovies.length()) return emptyList()
        
        val results = mutableListOf<SearchResponse>()
        for (i in start until end) {
            val movie = allMovies.getJSONObject(i)
            val name = movie.getString("name")
            val id = movie.getInt("stream_id")
            val poster = fixImageUrl(movie.optString("stream_icon"))
            
            results.add(
                newMovieSearchResponse(name, "movie?id=$id") {
                    this.posterUrl = poster
                }
            )
        }
        return results
    }

    private suspend fun getSeriesPaginated(page: Int): List<SearchResponse> {
        val allSeries = getAllSeries()
        val start = page * PAGE_SIZE
        val end = minOf(start + PAGE_SIZE, allSeries.length())
        
        if (start >= allSeries.length()) return emptyList()
        
        val results = mutableListOf<SearchResponse>()
        for (i in start until end) {
            val series = allSeries.getJSONObject(i)
            val name = series.getString("name")
            val id = series.getInt("series_id")
            val poster = fixImageUrl(series.optString("cover"))
            
            results.add(
                newTvSeriesSearchResponse(name, "series?id=$id") {
                    this.posterUrl = poster
                }
            )
        }
        return results
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
            val name = movie.getString("name")
            if (name.lowercase().contains(queryLower)) {
                val id = movie.getInt("stream_id")
                val poster = fixImageUrl(movie.optString("stream_icon"))
                
                results.add(
                    newMovieSearchResponse(name, "movie?id=$id") {
                        this.posterUrl = poster
                    }
                )
            }
        }
        
        val allSeries = getAllSeries()
        for (i in 0 until allSeries.length()) {
            val series = allSeries.getJSONObject(i)
            val name = series.getString("name")
            if (name.lowercase().contains(queryLower)) {
                val id = series.getInt("series_id")
                val poster = fixImageUrl(series.optString("cover"))
                
                results.add(
                    newTvSeriesSearchResponse(name, "series?id=$id") {
                        this.posterUrl = poster
                    }
                )
            }
        }
        
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val cleanUrl = url
            .removePrefix("https://streamflix.live/")
            .removePrefix("http://streamflix.live/")
            .removePrefix("streamflix.live/")
        
        return when {
            cleanUrl.startsWith("movie?id=") -> {
                val id = cleanUrl.substringAfter("movie?id=")
                loadMovie(id)
            }
            cleanUrl.startsWith("series?id=") -> {
                val id = cleanUrl.substringAfter("series?id=")
                loadSeries(id)
            }
            else -> null
        }
    }

    private suspend fun loadMovie(id: String): LoadResponse? {
        return withContext(Dispatchers.IO) {
            try {
                // 1. Busca informações do site (fallback)
                val infoResponse = app.get("$mainUrl/api_proxy.php?action=get_vod_info&vod_id=$id")
                val infoJson = JSONObject(infoResponse.body.string())
                val info = infoJson.optJSONObject("info") ?: JSONObject()
                val movieData = infoJson.optJSONObject("movie_data") ?: JSONObject()
                
                val rawTitle = info.optString("name", movieData.optString("name", "Título indisponível"))
                // Remove [L] e outras tags
                val cleanTitle = rawTitle.replace(Regex("\\s*\\[[^\\]]+\\]\\s*$"), "").trim()
                
                // 2. Busca TMDB (prioridade)
                val encodedTitle = URLEncoder.encode(cleanTitle, "UTF-8")
                val tmdbResponse = app.get("$mainUrl/api_proxy.php?action=tmdb_search&query=$encodedTitle&type=movie")
                val tmdbJson = JSONObject(tmdbResponse.body.string())
                val tmdbResult = tmdbJson.optJSONObject("result")
                
                // POSTER (prioriza TMDB)
                val poster = if (tmdbResult != null) {
                    val posterPath = tmdbResult.optString("poster_path")
                    if (posterPath.isNotEmpty()) "https://image.tmdb.org/t/p/w500$posterPath"
                    else fixImageUrl(info.optString("cover_big"))
                } else {
                    fixImageUrl(info.optString("cover_big"))
                }
                
                // BANNER (prioriza TMDB)
                var backdrop: String? = null
                if (tmdbResult != null) {
                    val backdropPath = tmdbResult.optString("backdrop_path")
                    if (backdropPath.isNotEmpty()) {
                        backdrop = "https://image.tmdb.org/t/p/original$backdropPath"
                    }
                }
                if (backdrop == null) {
                    val backdropArray = info.optJSONArray("backdrop_path")
                    if (backdropArray != null && backdropArray.length() > 0) {
                        backdrop = fixImageUrl(backdropArray.getString(0))
                    }
                }
                
                // SINOPSE (prioriza TMDB)
                val plot = tmdbResult?.optString("overview")?.takeIf { it.isNotEmpty() } 
                    ?: info.optString("plot", "Sinopse não disponível.")
                
                // ANO (prioriza TMDB)
                var year: Int? = null
                if (tmdbResult != null) {
                    val releaseDate = tmdbResult.optString("release_date")
                    if (releaseDate.isNotEmpty() && releaseDate.length >= 4) {
                        year = releaseDate.substring(0, 4).toIntOrNull()
                    }
                }
                if (year == null) {
                    val releaseDate = info.optString("releaseDate")
                    if (releaseDate.isNotEmpty() && releaseDate.length >= 4) {
                        year = releaseDate.substring(0, 4).toIntOrNull()
                    }
                }
                
                // AVALIAÇÃO (prioriza TMDB)
                var rating: Float? = null
                if (tmdbResult != null) {
                    val tmdbRating = tmdbResult.optDouble("vote_average", 0.0)
                    if (tmdbRating > 0) rating = tmdbRating.toFloat()
                }
                if (rating == null) {
                    val infoRating = info.optDouble("rating_5based", 0.0)
                    if (infoRating > 0) rating = (infoRating * 2).toFloat()
                }
                
                // GÊNEROS (prioriza TMDB)
                val genres = mutableListOf<String>()
                if (tmdbResult != null) {
                    val genreIds = tmdbResult.optJSONArray("genre_ids")
                    if (genreIds != null) {
                        for (i in 0 until genreIds.length()) {
                            val genreId = genreIds.getInt(i)
                            genreMap[genreId]?.let { genres.add(it) }
                        }
                    }
                }
                if (genres.isEmpty()) {
                    val genreText = info.optString("genre")
                    if (genreText.isNotEmpty()) {
                        genres.addAll(genreText.split(",").map { it.trim() })
                    }
                }
                
                // 3. Busca URL do vídeo
                val streamResponse = app.get("$mainUrl/api_proxy.php?action=get_stream_url&type=movie&id=$id")
                val streamJson = JSONObject(streamResponse.body.string())
                val videoUrl = streamJson.getString("stream_url")
                
                newMovieLoadResponse(cleanTitle, "movie?id=$id", TvType.Movie, videoUrl) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backdrop
                    this.plot = plot
                    this.year = year
                    if (rating != null) this.score = Score.from10(rating)
                    if (genres.isNotEmpty()) this.tags = genres
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private suspend fun loadSeries(id: String): LoadResponse? {
        return withContext(Dispatchers.IO) {
            try {
                // 1. Busca episódios da API do StreamFlix
                val infoResponse = app.get("$mainUrl/api_proxy.php?action=get_series_info&series_id=$id")
                val json = JSONObject(infoResponse.body.string())
                val info = json.optJSONObject("info") ?: JSONObject()
                
                val rawTitle = info.getString("name")
                // Remove [L] e outras tags
                val cleanTitle = rawTitle.replace(Regex("\\s*\\[[^\\]]+\\]\\s*$"), "").trim()
                
                // 2. Busca TMDB (prioridade máxima para metadados)
                val encodedTitle = URLEncoder.encode(cleanTitle, "UTF-8")
                val tmdbResponse = app.get("$mainUrl/api_proxy.php?action=tmdb_search&query=$encodedTitle&type=tv")
                val tmdbJson = JSONObject(tmdbResponse.body.string())
                val tmdbResult = tmdbJson.optJSONObject("result")
                
                // POSTER (prioriza TMDB)
                val poster = if (tmdbResult != null) {
                    val posterPath = tmdbResult.optString("poster_path")
                    if (posterPath.isNotEmpty()) "https://image.tmdb.org/t/p/w500$posterPath"
                    else fixImageUrl(info.optString("cover"))
                } else {
                    fixImageUrl(info.optString("cover"))
                }
                
                // BANNER (prioriza TMDB)
                var backdrop: String? = null
                if (tmdbResult != null) {
                    val backdropPath = tmdbResult.optString("backdrop_path")
                    if (backdropPath.isNotEmpty()) {
                        backdrop = "https://image.tmdb.org/t/p/original$backdropPath"
                    }
                }
                if (backdrop == null) {
                    val backdropArray = info.optJSONArray("backdrop_path")
                    if (backdropArray != null && backdropArray.length() > 0) {
                        backdrop = fixImageUrl(backdropArray.getString(0))
                    }
                }
                
                // SINOPSE (prioriza TMDB)
                val plot = tmdbResult?.optString("overview")?.takeIf { it.isNotEmpty() } 
                    ?: info.optString("plot", "Sinopse não disponível.")
                
                // ANO (prioriza TMDB)
                var year: Int? = null
                if (tmdbResult != null) {
                    val firstAirDate = tmdbResult.optString("first_air_date")
                    if (firstAirDate.isNotEmpty() && firstAirDate.length >= 4) {
                        year = firstAirDate.substring(0, 4).toIntOrNull()
                    }
                }
                if (year == null) {
                    val releaseDate = info.optString("releaseDate")
                    if (releaseDate.isNotEmpty() && releaseDate.length >= 4) {
                        year = releaseDate.substring(0, 4).toIntOrNull()
                    }
                }
                
                // AVALIAÇÃO (prioriza TMDB)
                var rating: Float? = null
                if (tmdbResult != null) {
                    val tmdbRating = tmdbResult.optDouble("vote_average", 0.0)
                    if (tmdbRating > 0) rating = tmdbRating.toFloat()
                }
                if (rating == null) {
                    val infoRating = info.optDouble("rating_5based", 0.0)
                    if (infoRating > 0) rating = (infoRating * 2).toFloat()
                }
                
                // GÊNEROS (prioriza TMDB)
                val genres = mutableListOf<String>()
                if (tmdbResult != null) {
                    val genreIds = tmdbResult.optJSONArray("genre_ids")
                    if (genreIds != null) {
                        for (i in 0 until genreIds.length()) {
                            val genreId = genreIds.getInt(i)
                            genreMap[genreId]?.let { genres.add(it) }
                        }
                    }
                }
                if (genres.isEmpty()) {
                    val genreText = info.optString("genre")
                    if (genreText.isNotEmpty()) {
                        genres.addAll(genreText.split(",").map { it.trim() })
                    }
                }
                
                // ELENCO (fallback do site)
                val castText = info.optString("cast")
                val actors = if (castText.isNotEmpty()) {
                    castText.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                } else null
                
                // 3. Episódios
                val episodes = mutableListOf<Episode>()
                val episodesJson = json.optJSONObject("episodes")
                
                if (episodesJson != null) {
                    val seasonKeys = episodesJson.keys()
                    while (seasonKeys.hasNext()) {
                        val seasonNum = seasonKeys.next().toIntOrNull() ?: continue
                        val seasonArray = episodesJson.getJSONArray(seasonNum.toString())
                        
                        for (i in 0 until seasonArray.length()) {
                            val ep = seasonArray.getJSONObject(i)
                            val epNum = ep.getInt("episode_num")
                            val epTitle = ep.getString("title")
                            val epId = ep.getString("id")
                            
                            // Info do episódio
                            val epInfo = ep.optJSONObject("info") ?: JSONObject()
                            val epPlot = epInfo.optString("plot").takeIf { it.isNotEmpty() }
                            val epImage = fixImageUrl(epInfo.optString("movie_image").takeIf { it.isNotEmpty() })
                            val epDuration = epInfo.optInt("duration_secs", 0).takeIf { it > 0 }
                            
                            // Busca URL do vídeo
                            val streamResponse = app.get("$mainUrl/api_proxy.php?action=get_stream_url&type=series&id=$epId")
                            val streamJson = JSONObject(streamResponse.body.string())
                            val videoUrl = streamJson.getString("stream_url")
                            
                            episodes.add(
                                newEpisode(videoUrl) {
                                    this.name = epTitle
                                    this.season = seasonNum
                                    this.episode = epNum
                                    this.posterUrl = epImage
                                    this.description = epPlot
                                    if (epDuration != null && epDuration > 0) this.runTime = epDuration / 60
                                }
                            )
                        }
                    }
                }
                
                if (episodes.isEmpty()) return@withContext null
                
                newTvSeriesLoadResponse(cleanTitle, "series?id=$id", TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backdrop
                    this.plot = plot
                    this.year = year
                    if (rating != null) this.score = Score.from10(rating)
                    if (genres.isNotEmpty()) this.tags = genres
                    
                    if (actors != null && actors.isNotEmpty()) {
                        addActors(actors.map { Actor(it) })
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        callback(
            newExtractorLink(
                source = name,
                name = "StreamFlix",
                url = data,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = mainUrl
            }
        )
        return true
    }

    private fun fixImageUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (url.startsWith("data:")) return null
        
        var fixed = url.trim()
        if (fixed.startsWith("//")) fixed = "https:$fixed"
        if (!fixed.startsWith("http") && fixed.startsWith("/")) fixed = "$mainUrl$fixed"
        
        return fixed
    }
}
