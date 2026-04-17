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
        println("📺 [StreamFlix] getMainPage chamado - Página: $page, Seção: ${request.name}")
        
        val isMovies = request.name == "Filmes"
        
        val items = if (isMovies) {
            println("🎬 [StreamFlix] Carregando filmes - Página $page")
            getMoviesPaginated(page)
        } else {
            println("📀 [StreamFlix] Carregando séries - Página $page")
            getSeriesPaginated(page)
        }
        
        println("✅ [StreamFlix] Retornando ${items.size} itens para ${request.name}")
        return newHomePageResponse(request.name, items, hasNext = items.size == PAGE_SIZE)
    }

    private suspend fun getMoviesPaginated(page: Int): List<SearchResponse> {
        println("📥 [StreamFlix] getMoviesPaginated - Iniciando página $page")
        
        val allMovies = getAllMovies()
        val start = page * PAGE_SIZE
        val end = minOf(start + PAGE_SIZE, allMovies.length())
        
        println("📊 [StreamFlix] Total de filmes: ${allMovies.length()}, Buscando índices $start até $end")
        
        if (start >= allMovies.length()) {
            println("⚠️ [StreamFlix] Não há mais filmes para carregar")
            return emptyList()
        }
        
        val results = mutableListOf<SearchResponse>()
        for (i in start until end) {
            try {
                val movie = allMovies.getJSONObject(i)
                val name = movie.getString("name")
                val id = movie.getInt("stream_id")
                val poster = fixImageUrl(movie.optString("stream_icon"))
                
                println("🎯 [StreamFlix] Filme ${i+1}: '$name' (ID: $id)")
                
                results.add(
                    newMovieSearchResponse(name, "movie?id=$id") {
                        this.posterUrl = poster
                    }
                )
            } catch (e: Exception) {
                println("❌ [StreamFlix] Erro ao processar filme índice $i: ${e.message}")
            }
        }
        
        println("✅ [StreamFlix] Retornando ${results.size} filmes")
        return results
    }

    private suspend fun getSeriesPaginated(page: Int): List<SearchResponse> {
        println("📥 [StreamFlix] getSeriesPaginated - Iniciando página $page")
        
        val allSeries = getAllSeries()
        val start = page * PAGE_SIZE
        val end = minOf(start + PAGE_SIZE, allSeries.length())
        
        println("📊 [StreamFlix] Total de séries: ${allSeries.length()}, Buscando índices $start até $end")
        
        if (start >= allSeries.length()) {
            println("⚠️ [StreamFlix] Não há mais séries para carregar")
            return emptyList()
        }
        
        val results = mutableListOf<SearchResponse>()
        for (i in start until end) {
            try {
                val series = allSeries.getJSONObject(i)
                val name = series.getString("name")
                val id = series.getInt("series_id")
                val poster = fixImageUrl(series.optString("cover"))
                
                println("🎯 [StreamFlix] Série ${i+1}: '$name' (ID: $id)")
                
                results.add(
                    newTvSeriesSearchResponse(name, "series?id=$id") {
                        this.posterUrl = poster
                    }
                )
            } catch (e: Exception) {
                println("❌ [StreamFlix] Erro ao processar série índice $i: ${e.message}")
            }
        }
        
        println("✅ [StreamFlix] Retornando ${results.size} séries")
        return results
    }

    private suspend fun getAllMovies(): JSONArray {
        println("🗄️ [StreamFlix] getAllMovies - Verificando cache...")
        
        if (cachedMovies != null) {
            println("✅ [StreamFlix] Usando cache de filmes (${cachedMovies!!.length()} filmes)")
            return cachedMovies!!
        }
        
        println("🌐 [StreamFlix] Buscando filmes da API...")
        
        return withContext(Dispatchers.IO) {
            try {
                val url = "$mainUrl/api_proxy.php?action=get_vod_streams"
                println("🔗 [StreamFlix] GET: $url")
                
                val response = app.get(url)
                println("📡 [StreamFlix] Resposta HTTP: ${response.code}")
                
                val jsonString = response.body.string()
                println("📦 [StreamFlix] JSON recebido: ${jsonString.length} caracteres")
                
                val json = JSONArray(jsonString)
                println("✅ [StreamFlix] ${json.length()} filmes carregados com sucesso!")
                
                cachedMovies = json
                json
            } catch (e: Exception) {
                println("💥 [StreamFlix] ERRO ao buscar filmes: ${e.message}")
                e.printStackTrace()
                JSONArray()
            }
        }
    }

    private suspend fun getAllSeries(): JSONArray {
        println("🗄️ [StreamFlix] getAllSeries - Verificando cache...")
        
        if (cachedSeries != null) {
            println("✅ [StreamFlix] Usando cache de séries (${cachedSeries!!.length()} séries)")
            return cachedSeries!!
        }
        
        println("🌐 [StreamFlix] Buscando séries da API...")
        
        return withContext(Dispatchers.IO) {
            try {
                val url = "$mainUrl/api_proxy.php?action=get_series"
                println("🔗 [StreamFlix] GET: $url")
                
                val response = app.get(url)
                println("📡 [StreamFlix] Resposta HTTP: ${response.code}")
                
                val jsonString = response.body.string()
                println("📦 [StreamFlix] JSON recebido: ${jsonString.length} caracteres")
                
                val json = JSONArray(jsonString)
                println("✅ [StreamFlix] ${json.length()} séries carregadas com sucesso!")
                
                cachedSeries = json
                json
            } catch (e: Exception) {
                println("💥 [StreamFlix] ERRO ao buscar séries: ${e.message}")
                e.printStackTrace()
                JSONArray()
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        println("🔍 [StreamFlix] Buscando: '$query'")
        
        if (query.length < 2) {
            println("⚠️ [StreamFlix] Query muito curta (${query.length} chars), ignorando")
            return emptyList()
        }
        
        val results = mutableListOf<SearchResponse>()
        val queryLower = query.lowercase()
        
        println("🎬 [StreamFlix] Buscando em filmes...")
        val allMovies = getAllMovies()
        var movieCount = 0
        for (i in 0 until allMovies.length()) {
            val movie = allMovies.getJSONObject(i)
            val name = movie.getString("name")
            if (name.lowercase().contains(queryLower)) {
                val id = movie.getInt("stream_id")
                val poster = fixImageUrl(movie.optString("stream_icon"))
                
                println("🎯 [StreamFlix] Filme encontrado: '$name' (ID: $id)")
                results.add(
                    newMovieSearchResponse(name, "movie?id=$id") {
                        this.posterUrl = poster
                    }
                )
                movieCount++
            }
        }
        println("📊 [StreamFlix] Encontrados $movieCount filmes")
        
        println("📀 [StreamFlix] Buscando em séries...")
        val allSeries = getAllSeries()
        var seriesCount = 0
        for (i in 0 until allSeries.length()) {
            val series = allSeries.getJSONObject(i)
            val name = series.getString("name")
            if (name.lowercase().contains(queryLower)) {
                val id = series.getInt("series_id")
                val poster = fixImageUrl(series.optString("cover"))
                
                println("🎯 [StreamFlix] Série encontrada: '$name' (ID: $id)")
                results.add(
                    newTvSeriesSearchResponse(name, "series?id=$id") {
                        this.posterUrl = poster
                    }
                )
                seriesCount++
            }
        }
        println("📊 [StreamFlix] Encontradas $seriesCount séries")
        
        println("✅ [StreamFlix] Total de ${results.size} resultados para '$query'")
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        println("📂 [StreamFlix] load() chamado com URL: '$url'")
        
        return when {
            url.startsWith("movie?id=") -> {
                val id = url.substringAfter("movie?id=")
                println("🎬 [StreamFlix] Carregando filme ID: $id")
                loadMovie(id)
            }
            url.startsWith("series?id=") -> {
                val id = url.substringAfter("series?id=")
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
                println("📡 [StreamFlix] 1/3 - Buscando informações básicas...")
                val infoUrl = "$mainUrl/api_proxy.php?action=get_vod_info&vod_id=$id"
                println("🔗 [StreamFlix] GET: $infoUrl")
                
                val infoResponse = app.get(infoUrl)
                println("📡 [StreamFlix] Resposta HTTP: ${infoResponse.code}")
                
                val infoJson = JSONObject(infoResponse.body.string())
                val info = infoJson.optJSONObject("info") ?: JSONObject()
                val movieData = infoJson.optJSONObject("movie_data") ?: JSONObject()
                
                val title = info.optString("name", movieData.optString("name", "Título indisponível"))
                println("📝 [StreamFlix] Título: '$title'")
                
                val poster = fixImageUrl(info.optString("cover_big"))
                println("🖼️ [StreamFlix] Poster: ${poster?.take(50)}...")
                
                // 2. Busca dados do TMDB
                println("📡 [StreamFlix] 2/3 - Buscando dados do TMDB...")
                val encodedTitle = URLEncoder.encode(title, "UTF-8")
                val tmdbUrl = "$mainUrl/api_proxy.php?action=tmdb_search&query=$encodedTitle&type=movie"
                println("🔗 [StreamFlix] GET: $tmdbUrl")
                
                val tmdbResponse = app.get(tmdbUrl)
                val tmdbJson = JSONObject(tmdbResponse.body.string())
                val tmdbResult = tmdbJson.optJSONObject("result")
                
                val plot = tmdbResult?.optString("overview")?.takeIf { it.isNotEmpty() } 
                    ?: info.optString("plot", "Sinopse não disponível.")
                println("📖 [StreamFlix] Sinopse: ${plot.take(100)}...")
                
                val year = tmdbResult?.optString("release_date")?.takeIf { it.isNotEmpty() }?.substring(0, 4)?.toIntOrNull()
                println("📅 [StreamFlix] Ano: $year")
                
                val rating = tmdbResult?.optDouble("vote_average", 0.0)?.takeIf { it > 0 }?.toFloat()
                println("⭐ [StreamFlix] Nota: $rating")
                
                val backdrop = tmdbResult?.optString("backdrop_path")?.let { "https://image.tmdb.org/t/p/original$it" }
                
                // 3. Busca URL do vídeo
                println("📡 [StreamFlix] 3/3 - Buscando URL do vídeo...")
                val streamUrl = "$mainUrl/api_proxy.php?action=get_stream_url&type=movie&id=$id"
                println("🔗 [StreamFlix] GET: $streamUrl")
                
                val streamResponse = app.get(streamUrl)
                val streamJson = JSONObject(streamResponse.body.string())
                val videoUrl = streamJson.getString("stream_url")
                println("🎥 [StreamFlix] URL do vídeo: ${videoUrl.take(80)}...")
                
                println("✅ [StreamFlix] Filme '$title' carregado com sucesso!")
                
                newMovieLoadResponse(title, "movie?id=$id", TvType.Movie, videoUrl) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = fixImageUrl(backdrop)
                    this.plot = plot
                    this.year = year
                    if (rating != null) this.score = Score.from10(rating)
                }
            } catch (e: Exception) {
                println("💥 [StreamFlix] ERRO ao carregar filme $id: ${e.message}")
                e.printStackTrace()
                null
            }
        }
    }

    private suspend fun loadSeries(id: String): LoadResponse? {
        println("📀 [StreamFlix] loadSeries - ID: $id")
        
        return withContext(Dispatchers.IO) {
            try {
                println("📡 [StreamFlix] Buscando informações da série...")
                val infoUrl = "$mainUrl/api_proxy.php?action=get_series_info&series_id=$id"
                println("🔗 [StreamFlix] GET: $infoUrl")
                
                val infoResponse = app.get(infoUrl)
                println("📡 [StreamFlix] Resposta HTTP: ${infoResponse.code}")
                
                val json = JSONObject(infoResponse.body.string())
                val info = json.optJSONObject("info") ?: JSONObject()
                
                val title = info.getString("name")
                println("📝 [StreamFlix] Título: '$title'")
                
                val poster = fixImageUrl(info.optString("cover"))
                println("🖼️ [StreamFlix] Poster: ${poster?.take(50)}...")
                
                val plot = info.optString("plot", "Sinopse não disponível.")
                println("📖 [StreamFlix] Sinopse: ${plot.take(100)}...")
                
                val year = info.optString("releaseDate").takeIf { it.isNotEmpty() }?.toIntOrNull()
                println("📅 [StreamFlix] Ano: $year")
                
                val rating = info.optDouble("rating_5based", 0.0).takeIf { it > 0 }?.toFloat()
                println("⭐ [StreamFlix] Nota: $rating")
                
                val episodes = mutableListOf<Episode>()
                val episodesJson = json.optJSONObject("episodes")
                
                if (episodesJson != null) {
                    val seasonKeys = episodesJson.keys()
                    var totalEpisodes = 0
                    
                    while (seasonKeys.hasNext()) {
                        val seasonNum = seasonKeys.next().toIntOrNull() ?: continue
                        println("📀 [StreamFlix] Processando Temporada $seasonNum...")
                        
                        val seasonArray = episodesJson.getJSONArray(seasonNum.toString())
                        println("📊 [StreamFlix] Temporada $seasonNum tem ${seasonArray.length()} episódios")
                        
                        for (i in 0 until seasonArray.length()) {
                            val ep = seasonArray.getJSONObject(i)
                            val epNum = ep.getInt("episode_num")
                            val epTitle = ep.getString("title")
                            val epId = ep.getString("id")
                            
                            println("🎬 [StreamFlix] Episódio S${seasonNum}E${epNum}: '$epTitle' (ID: $epId)")
                            
                            val streamResponse = app.get("$mainUrl/api_proxy.php?action=get_stream_url&type=series&id=$epId")
                            val streamJson = JSONObject(streamResponse.body.string())
                            val videoUrl = streamJson.getString("stream_url")
                            println("🎥 [StreamFlix] URL do vídeo: ${videoUrl.take(60)}...")
                            
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
                            totalEpisodes++
                        }
                    }
                    println("✅ [StreamFlix] Total de ${totalEpisodes} episódios carregados!")
                } else {
                    println("⚠️ [StreamFlix] Nenhum episódio encontrado para esta série")
                }
                
                if (episodes.isEmpty()) {
                    println("❌ [StreamFlix] Série sem episódios, retornando null")
                    return@withContext null
                }
                
                println("✅ [StreamFlix] Série '$title' carregada com sucesso!")
                
                newTvSeriesLoadResponse(title, "series?id=$id", TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = year
                    if (rating != null) this.score = Score.from10(rating * 2)
                }
            } catch (e: Exception) {
                println("💥 [StreamFlix] ERRO ao carregar série $id: ${e.message}")
                e.printStackTrace()
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
        println("🔗 [StreamFlix] loadLinks - URL: ${data.take(80)}...")
        println("📱 [StreamFlix] isCasting: $isCasting")
        
        callback(
            newExtractorLink(
                source = name,
                name = "StreamFlix",
                url = data,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = mainUrl
            }
        )
        
        println("✅ [StreamFlix] Link adicionado com sucesso!")
        return true
    }

    private fun fixImageUrl(url: String?): String? {
        if (url.isNullOrBlank()) {
            println("🖼️ [StreamFlix] fixImageUrl: URL vazia ou nula")
            return null
        }
        if (url.startsWith("data:")) {
            println("🖼️ [StreamFlix] fixImageUrl: Ignorando data URL")
            return null
        }
        
        var fixed = url.trim()
        if (fixed.startsWith("//")) fixed = "https:$fixed"
        if (!fixed.startsWith("http") && fixed.startsWith("/")) fixed = "$mainUrl$fixed"
        
        println("🖼️ [StreamFlix] fixImageUrl: ${url.take(40)}... → ${fixed.take(40)}...")
        return fixed
    }
}
