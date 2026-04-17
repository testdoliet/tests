package com.streamflix

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
    
    // Cache em memória
    private var cachedMovies: List<MovieItem>? = null
    private var cachedSeries: List<SeriesItem>? = null
    private var moviesOffset = 0
    private var seriesOffset = 0
    private val PAGE_SIZE = 30
    
    data class MovieItem(
        val id: Int,
        val name: String,
        val poster: String?,
        val year: Int?,
        val rating: Float?
    )
    
    data class SeriesItem(
        val id: Int,
        val name: String,
        val poster: String?,
        val year: Int?,
        val rating: Float?,
        val plot: String?
    )
    
    // Páginas principais
    override val mainPage = mainPageOf(
        "$mainUrl/api_proxy.php?action=get_vod_streams" to "Filmes",
        "$mainUrl/api_proxy.php?action=get_series" to "Séries"
    )
    
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isMovies = request.name == "Filmes"
        
        return when {
            isMovies -> {
                val movies = getMoviesPaginated(page)
                newHomePageResponse(request.name, movies, hasNext = movies.size == PAGE_SIZE)
            }
            else -> {
                val series = getSeriesPaginated(page)
                newHomePageResponse(request.name, series, hasNext = series.size == PAGE_SIZE)
            }
        }
    }
    
    // Busca filmes com paginação
    private suspend fun getMoviesPaginated(page: Int): List<SearchResponse> {
        val allMovies = getAllMovies()
        val start = page * PAGE_SIZE
        val end = minOf(start + PAGE_SIZE, allMovies.size)
        
        if (start >= allMovies.size) return emptyList()
        
        return allMovies.subList(start, end).map { movie ->
            newMovieSearchResponse(
                name = movie.name,
                url = "movie?id=${movie.id}",
                posterUrl = movie.poster
            ) {
                year = movie.year
                if (movie.rating != null) this.score = Score.from10(movie.rating)
            }
        }
    }
    
    // Busca séries com paginação
    private suspend fun getSeriesPaginated(page: Int): List<SearchResponse> {
        val allSeries = getAllSeries()
        val start = page * PAGE_SIZE
        val end = minOf(start + PAGE_SIZE, allSeries.size)
        
        if (start >= allSeries.size) return emptyList()
        
        return allSeries.subList(start, end).map { series ->
            newTvSeriesSearchResponse(
                name = series.name,
                url = "series?id=${series.id}",
                posterUrl = series.poster
            ) {
                year = series.year
                if (series.rating != null) this.score = Score.from10(series.rating)
            }
        }
    }
    
    // Carrega todos os filmes do cache ou API
    private suspend fun getAllMovies(): List<MovieItem> {
        if (cachedMovies != null) return cachedMovies!!
        
        return withContext(Dispatchers.IO) {
            try {
                val response = app.get("$mainUrl/api_proxy.php?action=get_vod_streams")
                val json = JSONObject(response.text)
                val result = json.getJSONArray("result")
                
                val movies = mutableListOf<MovieItem>()
                for (i in 0 until result.length()) {
                    val item = result.getJSONObject(i)
                    movies.add(
                        MovieItem(
                            id = item.getInt("stream_id"),
                            name = item.getString("name"),
                            poster = fixImageUrl(item.optString("stream_icon")),
                            year = null, // Será extraído do nome se possível
                            rating = if (item.has("rating_5based")) item.optDouble("rating_5based").toFloat() / 2 else null
                        )
                    )
                }
                cachedMovies = movies
                movies
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
    
    // Carrega todas as séries do cache ou API
    private suspend fun getAllSeries(): List<SeriesItem> {
        if (cachedSeries != null) return cachedSeries!!
        
        return withContext(Dispatchers.IO) {
            try {
                val response = app.get("$mainUrl/api_proxy.php?action=get_series")
                val json = JSONObject(response.text)
                val result = json.getJSONArray("result")
                
                val series = mutableListOf<SeriesItem>()
                for (i in 0 until result.length()) {
                    val item = result.getJSONObject(i)
                    series.add(
                        SeriesItem(
                            id = item.getInt("series_id"),
                            name = item.getString("name"),
                            poster = fixImageUrl(item.optString("cover")),
                            year = null,
                            rating = if (item.has("rating_5based")) item.optDouble("rating_5based").toFloat() / 2 else null,
                            plot = item.optString("plot")
                        )
                    )
                }
                cachedSeries = series
                series
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
    
    // Busca
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()
        
        val allMovies = getAllMovies()
        val allSeries = getAllSeries()
        
        val results = mutableListOf<SearchResponse>()
        
        // Busca em filmes
        allMovies.filter { it.name.contains(query, ignoreCase = true) }.forEach { movie ->
            results.add(
                newMovieSearchResponse(movie.name, "movie?id=${movie.id}", movie.poster) {
                    year = movie.year
                    if (movie.rating != null) score = Score.from10(movie.rating)
                }
            )
        }
        
        // Busca em séries
        allSeries.filter { it.name.contains(query, ignoreCase = true) }.forEach { series ->
            results.add(
                newTvSeriesSearchResponse(series.name, "series?id=${series.id}", series.poster) {
                    year = series.year
                    if (series.rating != null) score = Score.from10(series.rating)
                }
            )
        }
        
        return results
    }
    
    // Carregar detalhes
    override suspend fun load(url: String): LoadResponse? {
        return when {
            url.startsWith("movie?id=") -> loadMovie(url.substringAfter("movie?id="))
            url.startsWith("series?id=") -> loadSeries(url.substringAfter("series?id="))
            else -> null
        }
    }
    
    private suspend fun loadMovie(id: String): LoadResponse? {
        return withContext(Dispatchers.IO) {
            try {
                // Busca informações do filme
                val infoResponse = app.get("$mainUrl/api_proxy.php?action=get_vod_info&vod_id=$id")
                val infoJson = JSONObject(infoResponse.text)
                val info = infoJson.optJSONObject("info") ?: JSONObject()
                
                // Busca URL do vídeo
                val streamResponse = app.get("$mainUrl/api_proxy.php?action=get_stream_url&type=movie&id=$id")
                val streamJson = JSONObject(streamResponse.text)
                val videoUrl = streamJson.getString("stream_url")
                
                val title = info.optString("name", "Título indisponível")
                val poster = fixImageUrl(info.optString("stream_icon"))
                val plot = info.optString("plot", "Sinopse não disponível.")
                
                newMovieLoadResponse(title, url, TvType.Movie, videoUrl) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = info.optString("releaseDate").takeIf { it.isNotEmpty() }?.toIntOrNull()
                    val rating = info.optDouble("rating_5based", 0.0)
                    if (rating > 0) this.score = Score.from10(rating.toFloat() * 2)
                }
            } catch (e: Exception) {
                null
            }
        }
    }
    
    private suspend fun loadSeries(id: String): LoadResponse? {
        return withContext(Dispatchers.IO) {
            try {
                // Busca informações da série
                val infoResponse = app.get("$mainUrl/api_proxy.php?action=get_series_info&series_id=$id")
                val json = JSONObject(infoResponse.text)
                val info = json.optJSONObject("info") ?: JSONObject()
                
                val title = info.getString("name")
                val poster = fixImageUrl(info.optString("cover"))
                val plot = info.optString("plot", "Sinopse não disponível.")
                
                // Extrai episódios
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
                            
                            // Busca URL do episódio
                            val streamResponse = app.get("$mainUrl/api_proxy.php?action=get_stream_url&type=series&id=${ep.getString("id")}")
                            val streamJson = JSONObject(streamResponse.text)
                            val videoUrl = streamJson.getString("stream_url")
                            
                            episodes.add(
                                newEpisode(videoUrl) {
                                    name = epTitle
                                    season = seasonNum
                                    episode = epNum
                                    posterUrl = fixImageUrl(ep.optString("movie_image"))
                                    description = ep.optString("plot")
                                }
                            )
                        }
                    }
                }
                
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = info.optString("releaseDate").takeIf { it.isNotEmpty() }?.toIntOrNull()
                    val rating = info.optDouble("rating_5based", 0.0)
                    if (rating > 0) this.score = Score.from10(rating.toFloat() * 2)
                }
            } catch (e: Exception) {
                null
            }
        }
    }
    
    // Links (já temos a URL direta, então só repassa)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // A URL já é direta do MP4
        callback.invoke(
            ExtractorLink(
                source = name,
                name = name,
                url = data,
                quality = Qualities.Unknown.value,
                isM3u8 = false
            )
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
