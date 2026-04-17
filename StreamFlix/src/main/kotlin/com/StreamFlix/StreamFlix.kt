package com.StreamFlix

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

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

    private var cachedMovies: JSONObject? = null
    private var cachedSeries: JSONObject? = null
    private val PAGE_SIZE = 30

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
            val rating = movie.optDouble("rating_5based", 0.0)
            
            // CORRETO: sem TvType.Movie como terceiro parâmetro
            results.add(
                newMovieSearchResponse(name, "movie?id=$id") {
                    this.posterUrl = poster
                    if (rating > 0) this.score = Score.from10(rating.toFloat() * 2)
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
            val rating = series.optDouble("rating_5based", 0.0)
            
            // CORRETO: sem TvType.TvSeries como terceiro parâmetro
            results.add(
                newTvSeriesSearchResponse(name, "series?id=$id") {
                    this.posterUrl = poster
                    if (rating > 0) this.score = Score.from10(rating.toFloat() * 2)
                }
            )
        }
        return results
    }

    private suspend fun getAllMovies(): JSONObject {
        if (cachedMovies != null) return cachedMovies!!
        
        return withContext(Dispatchers.IO) {
            val response = app.get("$mainUrl/api_proxy.php?action=get_vod_streams")
            val json = JSONObject(response.text)
            cachedMovies = json
            json
        }
    }

    private suspend fun getAllSeries(): JSONObject {
        if (cachedSeries != null) return cachedSeries!!
        
        return withContext(Dispatchers.IO) {
            val response = app.get("$mainUrl/api_proxy.php?action=get_series")
            val json = JSONObject(response.text)
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
                val rating = movie.optDouble("rating_5based", 0.0)
                
                results.add(
                    newMovieSearchResponse(name, "movie?id=$id") {
                        this.posterUrl = poster
                        if (rating > 0) this.score = Score.from10(rating.toFloat() * 2)
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
                val rating = series.optDouble("rating_5based", 0.0)
                
                results.add(
                    newTvSeriesSearchResponse(name, "series?id=$id") {
                        this.posterUrl = poster
                        if (rating > 0) this.score = Score.from10(rating.toFloat() * 2)
                    }
                )
            }
        }
        
        return results
    }

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
                val infoResponse = app.get("$mainUrl/api_proxy.php?action=get_vod_info&vod_id=$id")
                val infoJson = JSONObject(infoResponse.text)
                val info = infoJson.optJSONObject("info") ?: JSONObject()
                
                val streamResponse = app.get("$mainUrl/api_proxy.php?action=get_stream_url&type=movie&id=$id")
                val streamJson = JSONObject(streamResponse.text)
                val videoUrl = streamJson.getString("stream_url")
                
                val title = info.optString("name", "Título indisponível")
                val poster = fixImageUrl(info.optString("stream_icon"))
                val plot = info.optString("plot", "Sinopse não disponível.")
                val year = info.optString("releaseDate").takeIf { it.isNotEmpty() }?.toIntOrNull()
                val rating = info.optDouble("rating_5based", 0.0)
                
                newMovieLoadResponse(title, "movie?id=$id", TvType.Movie, videoUrl) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = year
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
                val infoResponse = app.get("$mainUrl/api_proxy.php?action=get_series_info&series_id=$id")
                val json = JSONObject(infoResponse.text)
                val info = json.optJSONObject("info") ?: JSONObject()
                
                val title = info.getString("name")
                val poster = fixImageUrl(info.optString("cover"))
                val plot = info.optString("plot", "Sinopse não disponível.")
                val year = info.optString("releaseDate").takeIf { it.isNotEmpty() }?.toIntOrNull()
                val rating = info.optDouble("rating_5based", 0.0)
                
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
                            
                            val streamResponse = app.get("$mainUrl/api_proxy.php?action=get_stream_url&type=series&id=$epId")
                            val streamJson = JSONObject(streamResponse.text)
                            val videoUrl = streamJson.getString("stream_url")
                            val epPoster = fixImageUrl(ep.optString("movie_image"))
                            val epPlot = ep.optString("plot")
                            
                            episodes.add(
                                newEpisode(videoUrl) {
                                    this.name = epTitle
                                    this.season = seasonNum
                                    this.episode = epNum
                                    this.posterUrl = epPoster
                                    this.description = epPlot
                                }
                            )
                        }
                    }
                }
                
                newTvSeriesLoadResponse(title, "series?id=$id", TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = year
                    if (rating > 0) this.score = Score.from10(rating.toFloat() * 2)
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
        // CORRETO: ExtractorLinkType.M3U8 (não DIRECT)
        callback(
            ExtractorLink(
                source = name,
                name = name,
                url = data,
                referer = mainUrl,
                quality = Qualities.Unknown.value,
                type = ExtractorLinkType.M3U8
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
