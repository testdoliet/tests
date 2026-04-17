package com.StreamFlix

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat

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
        val cleanUrl = url
            .removePrefix("https://streamflix.live/")
            .removePrefix("http://streamflix.live/")
            .removePrefix("streamflix.live/")
        
        println("📂 [StreamFlix] load() chamado com URL: '$cleanUrl'")
        
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

    // Função para limpar título para busca no TMDB
    private fun cleanTitleForTMDB(title: String): String {
        var cleaned = title
        // Remove tags como [L], [DV], [HDR], etc.
        cleaned = cleaned.replace(Regex("\\s*\\[[^\\]]+\\]\\s*$"), "")
        // Remove 4K, HD, FULLHD, etc.
        cleaned = cleaned.replace(Regex("\\s*4K\\s*", RegexOption.IGNORE_CASE), " ")
        cleaned = cleaned.replace(Regex("\\s*HD\\s*", RegexOption.IGNORE_CASE), " ")
        cleaned = cleaned.replace(Regex("\\s*FULLHD\\s*", RegexOption.IGNORE_CASE), " ")
        cleaned = cleaned.replace(Regex("\\s*\\[?L\\]?\\s*", RegexOption.IGNORE_CASE), " ")
        cleaned = cleaned.replace(Regex("\\s*\\[?DV\\]?\\s*", RegexOption.IGNORE_CASE), " ")
        cleaned = cleaned.replace(Regex("\\s*\\[?HDR\\]?\\s*", RegexOption.IGNORE_CASE), " ")
        cleaned = cleaned.trim()
        return cleaned
    }

    // ==================== FILMES ====================
    
    private suspend fun loadMovie(id: String): LoadResponse? {
        println("🎬 [StreamFlix] loadMovie - Iniciando para ID: $id")
        
        return withContext(Dispatchers.IO) {
            try {
                // Busca informações básicas do site (fallback)
                println("📡 [StreamFlix] Buscando informações do site...")
                val infoResponse = app.get("$mainUrl/api_proxy.php?action=get_vod_info&vod_id=$id")
                val infoJson = JSONObject(infoResponse.body.string())
                val info = infoJson.optJSONObject("info") ?: JSONObject()
                
                val rawTitle = info.optString("name", "Título indisponível")
                val cleanTitle = cleanTitleForTMDB(rawTitle)
                println("📝 [StreamFlix] Título original: '$rawTitle'")
                println("📝 [StreamFlix] Título limpo para TMDB: '$cleanTitle'")
                
                val posterFallback = fixImageUrl(info.optString("cover_big"))
                
                // Busca TMDB (prioridade)
                println("🔍 [StreamFlix] Buscando dados no TMDB para: '$cleanTitle'")
                val tmdbData = searchMovieOnTMDB(cleanTitle)
                
                if (tmdbData != null) {
                    println("✅ [StreamFlix] TMDB encontrado! Título: ${tmdbData.title}")
                    println("   📅 Ano: ${tmdbData.year}")
                    println("   ⭐ Nota: ${tmdbData.rating}")
                    println("   📖 Sinopse: ${tmdbData.overview?.take(100)}...")
                    println("   🎭 Gêneros: ${tmdbData.genres?.joinToString(", ")}")
                    println("   🎬 Duração: ${tmdbData.duration} min")
                    println("   🎥 Trailer: ${tmdbData.youtubeTrailer}")
                    println("   👥 Elenco: ${tmdbData.actors?.size} atores")
                } else {
                    println("⚠️ [StreamFlix] TMDB não encontrado! Usando dados do site como fallback.")
                    println("   📝 Sinopse do site: ${info.optString("plot").take(100)}...")
                    println("   📅 Ano do site: ${info.optString("releaseDate")}")
                    println("   ⭐ Nota do site: ${info.optDouble("rating_5based", 0.0)}")
                }
                
                // BANNER
                val backdrop = tmdbData?.backdropUrl ?: fixImageUrl(info.optString("cover_big"))
                
                // POSTER
                val poster = tmdbData?.posterUrl ?: posterFallback
                
                // SINOPSE
                val plot = tmdbData?.overview ?: info.optString("plot", "Sinopse não disponível.")
                
                // ANO
                val year = tmdbData?.year ?: info.optString("releaseDate").takeIf { it.isNotEmpty() }?.substring(0, 4)?.toIntOrNull()
                
                // AVALIAÇÃO
                val rating = tmdbData?.rating?.let { Score.from10(it) } ?: info.optDouble("rating_5based", 0.0).takeIf { it > 0 }?.let { Score.from10(it.toFloat() * 2) }
                
                // DURAÇÃO
                val duration = tmdbData?.duration ?: info.optInt("duration_secs", 0).takeIf { it > 0 }?.let { it / 60 }
                
                // TAGS (GÊNEROS)
                val tags = tmdbData?.genres ?: info.optString("genre").split(",").map { it.trim() }.filter { it.isNotEmpty() }
                
                // ELENCO
                val actors = tmdbData?.actors
                
                // TRAILER
                val trailerUrl = tmdbData?.youtubeTrailer
                
                // URL do vídeo
                println("🎥 [StreamFlix] Buscando URL do vídeo...")
                val streamResponse = app.get("$mainUrl/api_proxy.php?action=get_stream_url&type=movie&id=$id")
                val streamJson = JSONObject(streamResponse.body.string())
                val videoUrl = streamJson.getString("stream_url")
                println("✅ [StreamFlix] URL do vídeo obtida: ${videoUrl.take(80)}...")
                
                newMovieLoadResponse(cleanTitle, "movie?id=$id", TvType.Movie, videoUrl) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backdrop
                    this.plot = plot
                    this.year = year
                    this.score = rating
                    this.duration = duration
                    this.tags = tags
                    
                    if (actors != null && actors.isNotEmpty()) {
                        println("👥 [StreamFlix] Adicionando ${actors.size} atores ao LoadResponse")
                        addActors(actors)
                    }
                    if (trailerUrl != null) {
                        println("🎥 [StreamFlix] Adicionando trailer: $trailerUrl")
                        addTrailer(trailerUrl)
                    }
                }
            } catch (e: Exception) {
                println("💥 [StreamFlix] ERRO ao carregar filme: ${e.message}")
                e.printStackTrace()
                null
            }
        }
    }

    private suspend fun searchMovieOnTMDB(query: String): TMDBMovieInfo? {
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "$mainUrl/api_proxy.php?action=tmdb_search&query=$encodedQuery&type=movie"
            println("🔗 [StreamFlix] Chamando TMDB API: $url")
            
            val response = app.get(url)
            println("📡 [StreamFlix] Resposta TMDB código: ${response.code}")
            
            val json = JSONObject(response.body.string())
            val result = json.optJSONObject("result")
            
            if (result == null) {
                println("⚠️ [StreamFlix] TMDB não retornou resultados para: '$query'")
                return null
            }
            
            println("✅ [StreamFlix] TMDB encontrou: ${result.optString("title")}")
            
            // Busca detalhes completos (elenco, trailer)
            val tmdbId = result.getInt("id")
            println("🔍 [StreamFlix] Buscando detalhes do TMDB ID: $tmdbId")
            val details = getTMDBMovieDetails(tmdbId)
            
            TMDBMovieInfo(
                title = result.optString("title"),
                year = result.optString("release_date").takeIf { it.isNotEmpty() }?.substring(0, 4)?.toIntOrNull(),
                posterUrl = result.optString("poster_path").takeIf { it.isNotEmpty() }?.let { "$tmdbImageUrl/w500$it" },
                backdropUrl = result.optString("backdrop_path").takeIf { it.isNotEmpty() }?.let { "$tmdbImageUrl/original$it" },
                overview = result.optString("overview"),
                rating = result.optDouble("vote_average", 0.0).takeIf { it > 0 },
                genres = result.optJSONArray("genre_ids")?.let { genreIds ->
                    (0 until genreIds.length()).mapNotNull { idx ->
                        genreMap[genreIds.getInt(idx)]
                    }
                },
                duration = details?.runtime,
                actors = details?.actors,
                youtubeTrailer = details?.youtubeTrailer
            )
        } catch (e: Exception) {
            println("💥 [StreamFlix] ERRO ao buscar TMDB: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    private suspend fun getTMDBMovieDetails(tmdbId: Int): TMDBMovieDetails? {
        return try {
            val url = "$mainUrl/api_proxy.php?action=tmdb_movie_details&id=$tmdbId"
            println("🔗 [StreamFlix] Buscando detalhes do filme: $url")
            
            val response = app.get(url)
            val json = JSONObject(response.body.string())
            
            val runtime = json.optInt("runtime", 0).takeIf { it > 0 }
            
            val actors = json.optJSONObject("credits")?.optJSONArray("cast")?.let { castArray ->
                (0 until minOf(castArray.length(), 15)).mapNotNull { idx ->
                    val actor = castArray.getJSONObject(idx)
                    val name = actor.optString("name")
                    if (name.isNotBlank()) {
                        val character = actor.optString("character")
                        val image = actor.optString("profile_path").takeIf { it.isNotEmpty() }?.let { "$tmdbImageUrl/w185$it" }
                        Pair(Actor(name, image), character)
                    } else null
                }
            }
            
            val youtubeTrailer = json.optJSONObject("videos")?.optJSONArray("results")?.let { videos ->
                (0 until videos.length()).mapNotNull { idx ->
                    val video = videos.getJSONObject(idx)
                    if (video.optString("site") == "YouTube" && 
                        (video.optString("type") == "Trailer" || video.optString("type") == "Teaser")) {
                        video.optString("key")
                    } else null
                }.firstOrNull()?.let { "https://www.youtube.com/watch?v=$it" }
            }
            
            println("✅ [StreamFlix] Detalhes obtidos: runtime=$runtime, atores=${actors?.size}, trailer=${youtubeTrailer != null}")
            
            TMDBMovieDetails(
                runtime = runtime,
                actors = actors,
                youtubeTrailer = youtubeTrailer
            )
        } catch (e: Exception) {
            println("⚠️ [StreamFlix] Erro ao buscar detalhes do TMDB: ${e.message}")
            null
        }
    }

    // ==================== SÉRIES ====================
    
    private suspend fun loadSeries(id: String): LoadResponse? {
        println("📀 [StreamFlix] loadSeries - Iniciando para ID: $id")
        
        return withContext(Dispatchers.IO) {
            try {
                // Busca informações do site (episódios)
                println("📡 [StreamFlix] Buscando informações do site...")
                val infoResponse = app.get("$mainUrl/api_proxy.php?action=get_series_info&series_id=$id")
                val json = JSONObject(infoResponse.body.string())
                val info = json.optJSONObject("info") ?: JSONObject()
                
                val rawTitle = info.getString("name")
                val cleanTitle = cleanTitleForTMDB(rawTitle)
                println("📝 [StreamFlix] Título original: '$rawTitle'")
                println("📝 [StreamFlix] Título limpo para TMDB: '$cleanTitle'")
                
                val posterFallback = fixImageUrl(info.optString("cover"))
                
                // Busca TMDB (prioridade)
                println("🔍 [StreamFlix] Buscando dados no TMDB para: '$cleanTitle'")
                val tmdbData = searchSeriesOnTMDB(cleanTitle)
                
                if (tmdbData != null) {
                    println("✅ [StreamFlix] TMDB encontrado! Título: ${tmdbData.title}")
                    println("   📅 Ano: ${tmdbData.year}")
                    println("   ⭐ Nota: ${tmdbData.rating}")
                    println("   📖 Sinopse: ${tmdbData.overview?.take(100)}...")
                    println("   🎭 Gêneros: ${tmdbData.genres?.joinToString(", ")}")
                    println("   🎥 Trailer: ${tmdbData.youtubeTrailer}")
                    println("   👥 Elenco: ${tmdbData.actors?.size} atores")
                    println("   📺 Temporadas: ${tmdbData.seasonsEpisodes.size}")
                } else {
                    println("⚠️ [StreamFlix] TMDB não encontrado! Usando dados do site como fallback.")
                    println("   📝 Sinopse do site: ${info.optString("plot").take(100)}...")
                    println("   📅 Ano do site: ${info.optString("releaseDate")}")
                    println("   ⭐ Nota do site: ${info.optDouble("rating_5based", 0.0)}")
                }
                
                // BANNER
                val backdrop = tmdbData?.backdropUrl ?: fixImageUrl(info.optString("cover"))
                
                // POSTER
                val poster = tmdbData?.posterUrl ?: posterFallback
                
                // SINOPSE GERAL
                val plot = tmdbData?.overview ?: info.optString("plot", "Sinopse não disponível.")
                
                // ANO
                val year = tmdbData?.year ?: info.optString("releaseDate").takeIf { it.isNotEmpty() }?.substring(0, 4)?.toIntOrNull()
                
                // AVALIAÇÃO
                val rating = tmdbData?.rating?.let { Score.from10(it) } ?: info.optDouble("rating_5based", 0.0).takeIf { it > 0 }?.let { Score.from10(it.toFloat() * 2) }
                
                // TAGS (GÊNEROS)
                val tags = tmdbData?.genres ?: info.optString("genre").split(",").map { it.trim() }.filter { it.isNotEmpty() }
                
                // ELENCO
                val actors = tmdbData?.actors
                
                // TRAILER
                val trailerUrl = tmdbData?.youtubeTrailer
                
                // EPISÓDIOS
                println("📺 [StreamFlix] Extraindo episódios...")
                val episodes = extractEpisodes(json, tmdbData)
                println("✅ [StreamFlix] Total de ${episodes.size} episódios extraídos")
                
                if (episodes.isEmpty()) {
                    println("⚠️ [StreamFlix] Nenhum episódio encontrado!")
                    return@withContext null
                }
                
                newTvSeriesLoadResponse(cleanTitle, "series?id=$id", TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backdrop
                    this.plot = plot
                    this.year = year
                    this.score = rating
                    this.tags = tags
                    
                    if (actors != null && actors.isNotEmpty()) {
                        println("👥 [StreamFlix] Adicionando ${actors.size} atores ao LoadResponse")
                        addActors(actors)
                    }
                    if (trailerUrl != null) {
                        println("🎥 [StreamFlix] Adicionando trailer: $trailerUrl")
                        addTrailer(trailerUrl)
                    }
                }
            } catch (e: Exception) {
                println("💥 [StreamFlix] ERRO ao carregar série: ${e.message}")
                e.printStackTrace()
                null
            }
        }
    }

    private suspend fun searchSeriesOnTMDB(query: String): TMDBSeriesInfo? {
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "$mainUrl/api_proxy.php?action=tmdb_search&query=$encodedQuery&type=tv"
            println("🔗 [StreamFlix] Chamando TMDB API: $url")
            
            val response = app.get(url)
            println("📡 [StreamFlix] Resposta TMDB código: ${response.code}")
            
            val json = JSONObject(response.body.string())
            val result = json.optJSONObject("result")
            
            if (result == null) {
                println("⚠️ [StreamFlix] TMDB não retornou resultados para: '$query'")
                return null
            }
            
            println("✅ [StreamFlix] TMDB encontrou: ${result.optString("name")}")
            
            // Busca detalhes completos
            val tmdbId = result.getInt("id")
            println("🔍 [StreamFlix] Buscando detalhes do TMDB ID: $tmdbId")
            val details = getTMDBSeriesDetails(tmdbId)
            
            TMDBSeriesInfo(
                title = result.optString("name"),
                year = result.optString("first_air_date").takeIf { it.isNotEmpty() }?.substring(0, 4)?.toIntOrNull(),
                posterUrl = result.optString("poster_path").takeIf { it.isNotEmpty() }?.let { "$tmdbImageUrl/w500$it" },
                backdropUrl = result.optString("backdrop_path").takeIf { it.isNotEmpty() }?.let { "$tmdbImageUrl/original$it" },
                overview = result.optString("overview"),
                rating = result.optDouble("vote_average", 0.0).takeIf { it > 0 },
                genres = result.optJSONArray("genre_ids")?.let { genreIds ->
                    (0 until genreIds.length()).mapNotNull { idx ->
                        genreMap[genreIds.getInt(idx)]
                    }
                },
                actors = details?.actors,
                youtubeTrailer = details?.youtubeTrailer,
                seasonsEpisodes = details?.seasonsEpisodes ?: emptyMap()
            )
        } catch (e: Exception) {
            println("💥 [StreamFlix] ERRO ao buscar série no TMDB: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    private suspend fun getTMDBSeriesDetails(tmdbId: Int): TMDBSeriesDetails? {
        return try {
            val url = "$mainUrl/api_proxy.php?action=tmdb_series_details&id=$tmdbId"
            println("🔗 [StreamFlix] Buscando detalhes da série: $url")
            
            val response = app.get(url)
            val json = JSONObject(response.body.string())
            
            val actors = json.optJSONObject("credits")?.optJSONArray("cast")?.let { castArray ->
                (0 until minOf(castArray.length(), 15)).mapNotNull { idx ->
                    val actor = castArray.getJSONObject(idx)
                    val name = actor.optString("name")
                    if (name.isNotBlank()) {
                        val character = actor.optString("character")
                        val image = actor.optString("profile_path").takeIf { it.isNotEmpty() }?.let { "$tmdbImageUrl/w185$it" }
                        Pair(Actor(name, image), character)
                    } else null
                }
            }
            
            val youtubeTrailer = json.optJSONObject("videos")?.optJSONArray("results")?.let { videos ->
                (0 until videos.length()).mapNotNull { idx ->
                    val video = videos.getJSONObject(idx)
                    if (video.optString("site") == "YouTube" && 
                        (video.optString("type") == "Trailer" || video.optString("type") == "Teaser")) {
                        video.optString("key")
                    } else null
                }.firstOrNull()?.let { "https://www.youtube.com/watch?v=$it" }
            }
            
            // Busca episódios por temporada
            val seasonsEpisodes = mutableMapOf<Int, List<TMDBEpisodeInfo>>()
            val seasons = json.optJSONArray("seasons")
            
            if (seasons != null) {
                for (i in 0 until seasons.length()) {
                    val season = seasons.getJSONObject(i)
                    val seasonNum = season.optInt("season_number", 0)
                    if (seasonNum > 0) {
                        val seasonUrl = "$mainUrl/api_proxy.php?action=tmdb_season&id=$tmdbId&season=$seasonNum"
                        val seasonResponse = app.get(seasonUrl)
                        val seasonJson = JSONObject(seasonResponse.body.string())
                        val episodes = seasonJson.optJSONArray("episodes")?.let { episodesArray ->
                            (0 until episodesArray.length()).mapNotNull { idx ->
                                val ep = episodesArray.getJSONObject(idx)
                                TMDBEpisodeInfo(
                                    episode_number = ep.optInt("episode_number", 0),
                                    name = ep.optString("name"),
                                    overview = ep.optString("overview"),
                                    still_path = ep.optString("still_path").takeIf { it.isNotEmpty() }?.let { "$tmdbImageUrl/w300$it" },
                                    runtime = ep.optInt("runtime", 0).takeIf { it > 0 },
                                    air_date = ep.optString("air_date").takeIf { it.isNotEmpty() }
                                )
                            }
                        } ?: emptyList()
                        if (episodes.isNotEmpty()) {
                            seasonsEpisodes[seasonNum] = episodes
                            println("✅ [StreamFlix] Temporada $seasonNum: ${episodes.size} episódios")
                        }
                    }
                }
            }
            
            println("✅ [StreamFlix] Detalhes obtidos: atores=${actors?.size}, trailer=${youtubeTrailer != null}, temporadas=${seasonsEpisodes.size}")
            
            TMDBSeriesDetails(
                actors = actors,
                youtubeTrailer = youtubeTrailer,
                seasonsEpisodes = seasonsEpisodes
            )
        } catch (e: Exception) {
            println("⚠️ [StreamFlix] Erro ao buscar detalhes da série: ${e.message}")
            null
        }
    }

    private suspend fun extractEpisodes(
        json: JSONObject,
        tmdbData: TMDBSeriesInfo?
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val episodesJson = json.optJSONObject("episodes")
        
        if (episodesJson != null) {
            val seasonKeys = episodesJson.keys()
            while (seasonKeys.hasNext()) {
                val seasonNum = seasonKeys.next().toIntOrNull() ?: continue
                println("📺 [StreamFlix] Processando Temporada $seasonNum...")
                
                val seasonArray = episodesJson.getJSONArray(seasonNum.toString())
                println("   📊 Temporada $seasonNum tem ${seasonArray.length()} episódios")
                
                for (i in 0 until seasonArray.length()) {
                    val ep = seasonArray.getJSONObject(i)
                    val epNum = ep.getInt("episode_num")
                    val epTitle = ep.getString("title")
                    val epId = ep.getString("id")
                    
                    // Info do episódio do site
                    val epInfo = ep.optJSONObject("info") ?: JSONObject()
                    val epPlotFallback = epInfo.optString("plot").takeIf { it.isNotEmpty() }
                    val epImageFallback = fixImageUrl(epInfo.optString("movie_image").takeIf { it.isNotEmpty() })
                    val epDurationFallback = epInfo.optInt("duration_secs", 0).takeIf { it > 0 }
                    
                    // Dados do TMDB
                    val tmdbEpisode = tmdbData?.seasonsEpisodes?.get(seasonNum)?.find { it.episode_number == epNum }
                    
                    if (tmdbEpisode != null) {
                        println("   🎬 Episódio $epNum: '${tmdbEpisode.name}' (TMDB)")
                    } else {
                        println("   🎬 Episódio $epNum: '$epTitle' (Site)")
                    }
                    
                    // Busca URL do vídeo
                    val streamResponse = app.get("$mainUrl/api_proxy.php?action=get_stream_url&type=series&id=$epId")
                    val streamJson = JSONObject(streamResponse.body.string())
                    val videoUrl = streamJson.getString("stream_url")
                    
                    // THUMB (prioriza TMDB)
                    val thumb = tmdbEpisode?.still_path ?: epImageFallback
                    
                    // SINOPSE (prioriza TMDB)
                    val description = tmdbEpisode?.overview?.takeIf { it.isNotEmpty() } ?: epPlotFallback
                    
                    // DURAÇÃO (prioriza TMDB)
                    val duration = tmdbEpisode?.runtime ?: (epDurationFallback?.let { it / 60 })
                    
                    episodes.add(
                        newEpisode(videoUrl) {
                            this.name = tmdbEpisode?.name?.takeIf { it.isNotEmpty() } ?: epTitle
                            this.season = seasonNum
                            this.episode = epNum
                            this.posterUrl = thumb
                            this.description = description
                            if (duration != null && duration > 0) this.runTime = duration
                            if (tmdbEpisode?.air_date != null) {
                                try {
                                    val dateFormatter = SimpleDateFormat("yyyy-MM-dd")
                                    val date = dateFormatter.parse(tmdbEpisode.air_date)
                                    this.date = date.time
                                    println("      📅 Data: ${tmdbEpisode.air_date}")
                                } catch (e: Exception) { }
                            }
                        }
                    )
                }
            }
        }
        
        return episodes
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔗 [StreamFlix] loadLinks: ${data.take(80)}...")
        
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

    // ==================== DATA CLASSES ====================
    
    private data class TMDBMovieInfo(
        val title: String?,
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

    private data class TMDBMovieDetails(
        val runtime: Int?,
        val actors: List<Pair<Actor, String?>>?,
        val youtubeTrailer: String?
    )

    private data class TMDBSeriesInfo(
        val title: String?,
        val year: Int?,
        val posterUrl: String?,
        val backdropUrl: String?,
        val overview: String?,
        val rating: Double?,
        val genres: List<String>?,
        val actors: List<Pair<Actor, String?>>?,
        val youtubeTrailer: String?,
        val seasonsEpisodes: Map<Int, List<TMDBEpisodeInfo>>
    )

    private data class TMDBSeriesDetails(
        val actors: List<Pair<Actor, String?>>?,
        val youtubeTrailer: String?,
        val seasonsEpisodes: Map<Int, List<TMDBEpisodeInfo>>
    )

    private data class TMDBEpisodeInfo(
        val episode_number: Int,
        val name: String,
        val overview: String?,
        val still_path: String?,
        val runtime: Int?,
        val air_date
