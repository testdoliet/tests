package com.StreamFlix

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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

    override val mainPage = mainPageOf(
        "$mainUrl/api_proxy.php?action=get_vod_streams" to "Filmes",
        "$mainUrl/api_proxy.php?action=get_series" to "Séries"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        println("📺 [StreamFlix] getMainPage - Página: $page, Seção: ${request.name}")
        
        val isMovies = request.name == "Filmes"
        
        val items = if (isMovies) {
            println("🎬 [StreamFlix] Carregando filmes - Página $page")
            getMoviesPaginated(page)
        } else {
            println("📀 [StreamFlix] Carregando séries - Página $page")
            getSeriesPaginated(page)
        }
        
        println("✅ [StreamFlix] Retornando ${items.size} itens")
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
        println("📂 [StreamFlix] load() chamado com URL: '$url'")
        
        // Remove a URL base se ela vier junto (correção principal!)
        val cleanUrl = url
            .removePrefix("https://streamflix.live/")
            .removePrefix("http://streamflix.live/")
            .removePrefix("streamflix.live/")
        
        println("🧹 [StreamFlix] URL limpa: '$cleanUrl'")
        
        return when {
            cleanUrl.startsWith("movie?id=") -> {
                val id = cleanUrl.substringAfter("movie?id=")
                println("🎬 [StreamFlix] Carregando filme ID: $id")
                loadMovie(id)
            }
            cleanUrl.startsWith("series?id=") -> {
                val id = cleanUrl.substringAfter("series?id=")
                println("📀 [StreamFlix] Carregando série ID: $id")
                loadSeries(id)
            }
            else -> {
                println("❌ [StreamFlix] URL não reconhecida: '$url'")
                null
            }
        }
    }

    private suspend fun loadMovie(id: String): LoadResponse? {
        println("🎬 [StreamFlix] loadMovie - ID: $id")
        
        return withContext(Dispatchers.IO) {
            try {
                // 1. Busca informações básicas
                val infoResponse = app.get("$mainUrl/api_proxy.php?action=get_vod_info&vod_id=$id")
                val infoJson = JSONObject(infoResponse.body.string())
                val info = infoJson.optJSONObject("info") ?: JSONObject()
                val movieData = infoJson.optJSONObject("movie_data") ?: JSONObject()
                
                val title = info.optString("name", movieData.optString("name", "Título indisponível"))
                println("📝 [StreamFlix] Título: '$title'")
                
                val poster = fixImageUrl(info.optString("cover_big"))
                
                // 2. Busca dados do TMDB
                val encodedTitle = URLEncoder.encode(title, "UTF-8")
                val tmdbResponse = app.get("$mainUrl/api_proxy.php?action=tmdb_search&query=$encodedTitle&type=movie")
                val tmdbJson = JSONObject(tmdbResponse.body.string())
                val tmdbResult = tmdbJson.optJSONObject("result")
                
                val plot = tmdbResult?.optString("overview")?.takeIf { it.isNotEmpty() } 
                    ?: info.optString("plot", "Sinopse não disponível.")
                val year = tmdbResult?.optString("release_date")?.takeIf { it.isNotEmpty() }?.substring(0, 4)?.toIntOrNull()
                val rating = tmdbResult?.optDouble("vote_average", 0.0)?.takeIf { it > 0 }?.toFloat()
                val backdrop = tmdbResult?.optString("backdrop_path")?.let { "https://image.tmdb.org/t/p/original$it" }
                
                // 3. Busca URL do vídeo
                val streamResponse = app.get("$mainUrl/api_proxy.php?action=get_stream_url&type=movie&id=$id")
                val streamJson = JSONObject(streamResponse.body.string())
                val videoUrl = streamJson.getString("stream_url")
                println("🎥 [StreamFlix] URL do vídeo obtida")
                
                newMovieLoadResponse(title, "movie?id=$id", TvType.Movie, videoUrl) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = fixImageUrl(backdrop)
                    this.plot = plot
                    this.year = year
                    if (rating != null) this.score = Score.from10(rating)
                }
            } catch (e: Exception) {
                println("💥 [StreamFlix] ERRO ao carregar filme: ${e.message}")
                null
            }
        }
    }

    private suspend fun loadSeries(id: String): LoadResponse? {
        println("📀 [StreamFlix] loadSeries - ID: $id")
        
        return withContext(Dispatchers.IO) {
            try {
                val infoResponse = app.get("$mainUrl/api_proxy.php?action=get_series_info&series_id=$id")
                val json = JSONObject(infoResponse.body.string())
                val info = json.optJSONObject("info") ?: JSONObject()
                
                val title = info.getString("name")
                println("📝 [StreamFlix] Título: '$title'")
                
                val poster = fixImageUrl(info.optString("cover"))
                val plot = info.optString("plot", "Sinopse não disponível.")
                val year = info.optString("releaseDate").takeIf { it.isNotEmpty() }?.toIntOrNull()
                val rating = info.optDouble("rating_5based", 0.0).takeIf { it > 0 }?.toFloat()
                
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
                            val streamJson = JSONObject(streamResponse.body.string())
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
                
                if (episodes.isEmpty()) {
                    println("⚠️ [StreamFlix] Nenhum episódio encontrado")
                    return@withContext null
                }
                
                newTvSeriesLoadResponse(title, "series?id=$id", TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = year
                    if (rating != null) this.score = Score.from10(rating * 2)
                }
            } catch (e: Exception) {
                println("💥 [StreamFlix] ERRO ao carregar série: ${e.message}")
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
            type = ExtractorLinkType.VIDEO  // ← Correto para MP4
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
