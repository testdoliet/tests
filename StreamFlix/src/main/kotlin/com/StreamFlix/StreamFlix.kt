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
    private val TMDB_API_KEY = BuildConfig.TMDB_API_KEY// Coloque sua API key do TMDB
    private val TMDB_ACCESS_TOKEN = BuildConfig.TMDB_ACCESS_TOKEN  // Coloque seu token do TMDB

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
            println("📥 [StreamFlix] Baixando lista de filmes da API...")
            val response = app.get("$mainUrl/api_proxy.php?action=get_vod_streams")
            val json = JSONArray(response.body.string())
            println("✅ [StreamFlix] ${json.length()} filmes carregados")
            cachedMovies = json
            json
        }
    }

    private suspend fun getAllSeries(): JSONArray {
        if (cachedSeries != null) return cachedSeries!!
        
        return withContext(Dispatchers.IO) {
            println("📥 [StreamFlix] Baixando lista de séries da API...")
            val response = app.get("$mainUrl/api_proxy.php?action=get_series")
            val json = JSONArray(response.body.string())
            println("✅ [StreamFlix] ${json.length()} séries carregadas")
            cachedSeries = json
            json
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()
        
        println("🔍 [StreamFlix] Buscando: '$query'")
        
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
        
        println("✅ [StreamFlix] ${results.size} resultados encontrados")
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val cleanUrl = url
            .removePrefix("https://streamflix.live/")
            .removePrefix("http://streamflix.live/")
            .removePrefix("streamflix.live/")
        
        println("📂 [StreamFlix] load() - URL: '$cleanUrl'")
        
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

    // ==================== LIMPEZA DE TÍTULO (segura) ====================
    // Só remove tags isoladas entre colchetes [L], [DV] e palavras no FINAL do título
    // NUNCA remove letras/partes dentro do nome
    
    private fun cleanTitleForTMDB(title: String): String {
        var cleaned = title.trim()
        println("🧹 [StreamFlix] Limpando título original: '$cleaned'")
        
        // Remove apenas tags entre colchetes ISOLADAS no final: [L], [DV], [HDR], etc.
        cleaned = cleaned.replace(Regex("\\s*\\[[^\\]]+\\]\\s*$"), "")
        
        // Remove palavras de qualidade APENAS se estiverem no FINAL do título
        val qualityWords = listOf("4K", "HD", "FULLHD", "1080P", "720P", "480P", "HDR", "DV")
        
        for (word in qualityWords) {
            cleaned = cleaned.replace(Regex("\\s*$word\\s*$", RegexOption.IGNORE_CASE), "")
        }
        
        // Remove indicações de áudio APENAS se estiverem no FINAL
        val audioWords = listOf("DUBLADO", "LEGENDADO", "LEG", "DUB", "PT-BR")
        
        for (word in audioWords) {
            cleaned = cleaned.replace(Regex("\\s*$word\\s*$", RegexOption.IGNORE_CASE), "")
        }
        
        // Remove ano isolado no final (ex: "2024", "2023")
        cleaned = cleaned.replace(Regex("\\s*(19\\d{2}|20\\d{2})\\s*$"), "")
        
        cleaned = cleaned.trim()
        println("🧹 [StreamFlix] Título limpo: '$cleaned'")
        return cleaned
    }

    // ==================== FILMES ====================
    
    private suspend fun loadMovie(id: String): LoadResponse? {
        println("🎬 [StreamFlix] loadMovie - ID: $id")
        
        return withContext(Dispatchers.IO) {
            try {
                // Busca informações básicas do site (fallback)
                println("📡 [StreamFlix] Buscando informações do site...")
                val infoResponse = app.get("$mainUrl/api_proxy.php?action=get_vod_info&vod_id=$id")
                val infoJson = JSONObject(infoResponse.body.string())
                val info = infoJson.optJSONObject("info") ?: JSONObject()
                
                val rawTitle = info.optString("name", "Título indisponível")
                println("📝 [StreamFlix] Título original do site: '$rawTitle'")
                
                val cleanTitle = cleanTitleForTMDB(rawTitle)
                
                val posterFallback = fixImageUrl(info.optString("cover_big"))
                
                // Busca TMDB diretamente (como no CineAgora)
                println("🔍 [StreamFlix] Buscando no TMDB: '$cleanTitle'")
                val tmdbData = searchMovieOnTMDB(cleanTitle, null)
                
                if (tmdbData != null) {
                    println("✅ [StreamFlix] TMDB encontrado!")
                    println("   📖 Sinopse: ${tmdbData.overview?.take(80)}...")
                    println("   ⭐ Nota: ${tmdbData.rating}")
                    println("   📅 Ano: ${tmdbData.year}")
                    println("   🎭 Gêneros: ${tmdbData.genres?.joinToString(", ")}")
                    println("   👥 Atores: ${tmdbData.actors?.size}")
                    println("   🎥 Trailer: ${tmdbData.youtubeTrailer}")
                } else {
                    println("⚠️ [StreamFlix] TMDB NÃO encontrado para: '$cleanTitle'")
                    println("   Usando dados do site como fallback")
                }
                
                val backdrop = tmdbData?.backdropUrl ?: fixImageUrl(info.optString("cover_big"))
                val poster = tmdbData?.posterUrl ?: posterFallback
                val plot = tmdbData?.overview ?: info.optString("plot", "Sinopse não disponível.")
                val year = tmdbData?.year ?: info.optString("releaseDate").takeIf { it.isNotEmpty() }?.substring(0, 4)?.toIntOrNull()
                val rating = tmdbData?.rating?.let { Score.from10(it) } ?: info.optDouble("rating_5based", 0.0).takeIf { it > 0 }?.let { Score.from10(it.toFloat() * 2) }
                val duration = tmdbData?.duration ?: info.optInt("duration_secs", 0).takeIf { it > 0 }?.let { it / 60 }
                val tags = tmdbData?.genres ?: info.optString("genre").split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val actors = tmdbData?.actors
                val trailerUrl = tmdbData?.youtubeTrailer
                
                // URL do vídeo
                println("🎥 [StreamFlix] Buscando URL do vídeo...")
                val streamResponse = app.get("$mainUrl/api_proxy.php?action=get_stream_url&type=movie&id=$id")
                val streamJson = JSONObject(streamResponse.body.string())
                val videoUrl = streamJson.getString("stream_url")
                println("✅ [StreamFlix] URL do vídeo obtida: ${videoUrl.take(60)}...")
                
                newMovieLoadResponse(cleanTitle, "movie?id=$id", TvType.Movie, videoUrl) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backdrop
                    this.plot = plot
                    this.year = year
                    this.score = rating
                    this.duration = duration
                    this.tags = tags
                    
                    if (actors != null && actors.isNotEmpty()) {
                        println("👥 [StreamFlix] Adicionando ${actors.size} atores")
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

    private suspend fun searchMovieOnTMDB(query: String, year: Int?): TMDBMovieInfo? {
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val yearParam = year?.let { "&year=$it" } ?: ""
            val url = "https://api.themoviedb.org/3/search/movie?api_key=$TMDB_API_KEY&query=$encodedQuery&language=pt-BR$yearParam"
            
            println("🔗 [StreamFlix] Chamada TMDB: $url")
            
            val headers = mapOf(
                "Authorization" to "Bearer $TMDB_ACCESS_TOKEN",
                "accept" to "application/json"
            )
            
            val response = app.get(url, headers = headers, timeout = 10_000)
            println("📡 [StreamFlix] Resposta TMDB código: ${response.code}")
            
            if (response.code != 200) return null
            
            val searchResult = response.parsedSafe<TMDBSearchResponse>() ?: return null
            val result = searchResult.results.firstOrNull() ?: return null
            
            println("✅ [StreamFlix] TMDB: Encontrou '${result.title}'")
            
            val details = getTMDBMovieDetails(result.id)
            
            TMDBMovieInfo(
                title = result.title,
                year = result.release_date?.substring(0, 4)?.toIntOrNull(),
                posterUrl = result.poster_path?.let { "$tmdbImageUrl/w500$it" },
                backdropUrl = details?.backdrop_path?.let { "$tmdbImageUrl/original$it" },
                overview = details?.overview,
                rating = details?.vote_average?.takeIf { it > 0 },
                genres = details?.genres?.map { it.name },
                duration = details?.runtime,
                actors = details?.credits?.cast?.take(15)?.mapNotNull { actor ->
                    if (actor.name.isNotBlank()) {
                        val actorObj = Actor(name = actor.name, image = actor.profile_path?.let { "$tmdbImageUrl/w185$it" })
                        Pair(actorObj, actor.character)
                    } else null
                },
                youtubeTrailer = getHighQualityTrailer(details?.videos?.results)
            )
        } catch (e: Exception) {
            println("💥 [StreamFlix] ERRO na busca TMDB: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    private suspend fun getTMDBMovieDetails(tmdbId: Int): TMDBDetailsResponse? {
        return try {
            val url = "https://api.themoviedb.org/3/movie/$tmdbId?api_key=$TMDB_API_KEY&language=pt-BR&append_to_response=credits,videos"
            val headers = mapOf(
                "Authorization" to "Bearer $TMDB_ACCESS_TOKEN",
                "accept" to "application/json"
            )
            val response = app.get(url, headers = headers, timeout = 10_000)
            response.parsedSafe<TMDBDetailsResponse>()
        } catch (e: Exception) {
            println("⚠️ [StreamFlix] Erro ao buscar detalhes: ${e.message}")
            null
        }
    }

    // ==================== SÉRIES ====================
    
    private suspend fun loadSeries(id: String): LoadResponse? {
        println("📀 [StreamFlix] loadSeries - ID: $id")
        
        return withContext(Dispatchers.IO) {
            try {
                // Busca informações do site (episódios)
                println("📡 [StreamFlix] Buscando informações do site...")
                val infoResponse = app.get("$mainUrl/api_proxy.php?action=get_series_info&series_id=$id")
                val json = JSONObject(infoResponse.body.string())
                val info = json.optJSONObject("info") ?: JSONObject()
                
                val rawTitle = info.getString("name")
                println("📝 [StreamFlix] Título original do site: '$rawTitle'")
                
                val cleanTitle = cleanTitleForTMDB(rawTitle)
                
                val posterFallback = fixImageUrl(info.optString("cover"))
                
                // Busca TMDB diretamente
                println("🔍 [StreamFlix] Buscando no TMDB: '$cleanTitle'")
                val tmdbData = searchSeriesOnTMDB(cleanTitle, null)
                
                if (tmdbData != null) {
                    println("✅ [StreamFlix] TMDB encontrado!")
                    println("   📖 Sinopse: ${tmdbData.overview?.take(80)}...")
                    println("   ⭐ Nota: ${tmdbData.rating}")
                    println("   📅 Ano: ${tmdbData.year}")
                    println("   🎭 Gêneros: ${tmdbData.genres?.joinToString(", ")}")
                    println("   👥 Atores: ${tmdbData.actors?.size}")
                    println("   🎥 Trailer: ${tmdbData.youtubeTrailer}")
                } else {
                    println("⚠️ [StreamFlix] TMDB NÃO encontrado para: '$cleanTitle'")
                    println("   Usando dados do site como fallback")
                }
                
                val backdrop = tmdbData?.backdropUrl ?: fixImageUrl(info.optString("cover"))
                val poster = tmdbData?.posterUrl ?: posterFallback
                val plot = tmdbData?.overview ?: info.optString("plot", "Sinopse não disponível.")
                val year = tmdbData?.year ?: info.optString("releaseDate").takeIf { it.isNotEmpty() }?.substring(0, 4)?.toIntOrNull()
                val rating = tmdbData?.rating?.let { Score.from10(it) } ?: info.optDouble("rating_5based", 0.0).takeIf { it > 0 }?.let { Score.from10(it.toFloat() * 2) }
                val tags = tmdbData?.genres ?: info.optString("genre").split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val actors = tmdbData?.actors
                val trailerUrl = tmdbData?.youtubeTrailer
                
                // Episódios com dados do TMDB
                println("📺 [StreamFlix] Extraindo episódios...")
                val episodes = extractEpisodes(json, tmdbData)
                println("✅ [StreamFlix] ${episodes.size} episódios extraídos")
                
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
                        println("👥 [StreamFlix] Adicionando ${actors.size} atores")
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

    private suspend fun searchSeriesOnTMDB(query: String, year: Int?): TMDBSeriesInfo? {
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val yearParam = year?.let { "&first_air_date_year=$it" } ?: ""
            val url = "https://api.themoviedb.org/3/search/tv?api_key=$TMDB_API_KEY&query=$encodedQuery&language=pt-BR$yearParam"
            
            println("🔗 [StreamFlix] Chamada TMDB: $url")
            
            val headers = mapOf(
                "Authorization" to "Bearer $TMDB_ACCESS_TOKEN",
                "accept" to "application/json"
            )
            
            val response = app.get(url, headers = headers, timeout = 10_000)
            println("📡 [StreamFlix] Resposta TMDB código: ${response.code}")
            
            if (response.code != 200) return null
            
            val searchResult = response.parsedSafe<TMDBSearchResponse>() ?: return null
            val result = searchResult.results.firstOrNull() ?: return null
            
            println("✅ [StreamFlix] TMDB: Encontrou '${result.name}'")
            
            val details = getTMDBSeriesDetails(result.id)
            val seasonsEpisodes = getTMDBAllSeasons(result.id)
            
            TMDBSeriesInfo(
                title = result.name,
                year = result.first_air_date?.substring(0, 4)?.toIntOrNull(),
                posterUrl = result.poster_path?.let { "$tmdbImageUrl/w500$it" },
                backdropUrl = details?.backdrop_path?.let { "$tmdbImageUrl/original$it" },
                overview = details?.overview,
                rating = details?.vote_average?.takeIf { it > 0 },
                genres = details?.genres?.map { it.name },
                actors = details?.credits?.cast?.take(15)?.mapNotNull { actor ->
                    if (actor.name.isNotBlank()) {
                        val actorObj = Actor(name = actor.name, image = actor.profile_path?.let { "$tmdbImageUrl/w185$it" })
                        Pair(actorObj, actor.character)
                    } else null
                },
                youtubeTrailer = getHighQualityTrailer(details?.videos?.results),
                seasonsEpisodes = seasonsEpisodes
            )
        } catch (e: Exception) {
            println("💥 [StreamFlix] ERRO na busca TMDB: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    private suspend fun getTMDBSeriesDetails(tmdbId: Int): TMDBDetailsResponse? {
        return try {
            val url = "https://api.themoviedb.org/3/tv/$tmdbId?api_key=$TMDB_API_KEY&language=pt-BR&append_to_response=credits,videos"
            val headers = mapOf(
                "Authorization" to "Bearer $TMDB_ACCESS_TOKEN",
                "accept" to "application/json"
            )
            val response = app.get(url, headers = headers, timeout = 10_000)
            response.parsedSafe<TMDBDetailsResponse>()
        } catch (e: Exception) {
            println("⚠️ [StreamFlix] Erro ao buscar detalhes da série: ${e.message}")
            null
        }
    }

    private suspend fun getTMDBAllSeasons(seriesId: Int): Map<Int, List<TMDBEpisode>> {
        return try {
            val headers = mapOf(
                "Authorization" to "Bearer $TMDB_ACCESS_TOKEN",
                "accept" to "application/json"
            )

            val seriesDetailsUrl = "https://api.themoviedb.org/3/tv/$seriesId?api_key=$TMDB_API_KEY&language=pt-BR"
            val seriesResponse = app.get(seriesDetailsUrl, headers = headers, timeout = 10_000)
            if (seriesResponse.code != 200) return emptyMap()
            
            val seriesDetails = seriesResponse.parsedSafe<TMDBTVDetailsResponse>() ?: return emptyMap()
            val seasonsEpisodes = mutableMapOf<Int, List<TMDBEpisode>>()

            for (season in seriesDetails.seasons) {
                if (season.season_number > 0) {
                    val seasonNumber = season.season_number
                    val seasonUrl = "https://api.themoviedb.org/3/tv/$seriesId/season/$seasonNumber?api_key=$TMDB_API_KEY&language=pt-BR"
                    val seasonResponse = app.get(seasonUrl, headers = headers, timeout = 10_000)
                    
                    if (seasonResponse.code == 200) {
                        val seasonData = seasonResponse.parsedSafe<TMDBSeasonResponse>()
                        seasonData?.episodes?.let { episodes ->
                            seasonsEpisodes[seasonNumber] = episodes
                            println("   ✅ Temporada $seasonNumber: ${episodes.size} episódios")
                        }
                    }
                }
            }
            seasonsEpisodes
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun getHighQualityTrailer(videos: List<TMDBVideo>?): String? {
        if (videos.isNullOrEmpty()) return null
        
        val trailerInfo = videos.mapNotNull { video ->
            when {
                video.site == "YouTube" && video.type == "Trailer" && video.official == true -> Triple(video.key, 10, "YouTube Trailer Oficial")
                video.site == "YouTube" && video.type == "Trailer" -> Triple(video.key, 9, "YouTube Trailer")
                video.site == "YouTube" && video.type == "Teaser" && video.official == true -> Triple(video.key, 8, "YouTube Teaser Oficial")
                video.site == "YouTube" && video.type == "Teaser" -> Triple(video.key, 7, "YouTube Teaser")
                else -> null
            }
        }.sortedByDescending { it.second }.firstOrNull()
        
        return trailerInfo?.let { (key, _, _) -> "https://www.youtube.com/watch?v=$key" }
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
                println("   📊 ${seasonArray.length()} episódios")
                
                for (i in 0 until seasonArray.length()) {
                    val ep = seasonArray.getJSONObject(i)
                    val epNum = ep.getInt("episode_num")
                    val epTitle = ep.getString("title")
                    val epId = ep.getString("id")
                    
                    val epInfo = ep.optJSONObject("info") ?: JSONObject()
                    val epPlotFallback = epInfo.optString("plot").takeIf { it.isNotEmpty() }
                    val epImageFallback = fixImageUrl(epInfo.optString("movie_image").takeIf { it.isNotEmpty() })
                    val epDurationFallback = epInfo.optInt("duration_secs", 0).takeIf { it > 0 }
                    
                    val tmdbEpisode = tmdbData?.seasonsEpisodes?.get(seasonNum)?.find { it.episode_number == epNum }
                    
                    val streamResponse = app.get("$mainUrl/api_proxy.php?action=get_stream_url&type=series&id=$epId")
                    val streamJson = JSONObject(streamResponse.body.string())
                    val videoUrl = streamJson.getString("stream_url")
                    
                    val thumb = tmdbEpisode?.still_path?.let { "$tmdbImageUrl/w300$it" } ?: epImageFallback
                    val description = tmdbEpisode?.overview?.takeIf { it.isNotEmpty() } ?: epPlotFallback
                    val duration = tmdbEpisode?.runtime ?: (epDurationFallback?.let { it / 60 })
                    
                    if (tmdbEpisode != null) {
                        println("   🎬 Ep $epNum: '${tmdbEpisode.name}' (TMDB)")
                        if (tmdbEpisode.still_path != null) println("      📸 Thumb: TMDB")
                        if (tmdbEpisode.overview != null) println("      📖 Sinopse: ${tmdbEpisode.overview.take(60)}...")
                        if (tmdbEpisode.runtime != null) println("      ⏱️ Duração: ${tmdbEpisode.runtime} min")
                        if (tmdbEpisode.air_date != null) println("      📅 Data: ${tmdbEpisode.air_date}")
                    } else {
                        println("   🎬 Ep $epNum: '$epTitle' (Site)")
                    }
                    
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
        println("🔗 [StreamFlix] loadLinks: ${data.take(60)}...")
        
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
        val seasonsEpisodes: Map<Int, List<TMDBEpisode>>
    )

    private data class TMDBSearchResponse(
        @JsonProperty("results") val results: List<TMDBResult>
    )

    private data class TMDBResult(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("release_date") val release_date: String? = null,
        @JsonProperty("first_air_date") val first_air_date: String? = null,
        @JsonProperty("poster_path") val poster_path: String?
    )

    private data class TMDBTVDetailsResponse(
        @JsonProperty("seasons") val seasons: List<TMDBSeasonInfo>
    )

    private data class TMDBSeasonInfo(
        @JsonProperty("season_number") val season_number: Int,
        @JsonProperty("episode_count") val episode_count: Int
    )

    private data class TMDBSeasonResponse(
        @JsonProperty("episodes") val episodes: List<TMDBEpisode>,
        @JsonProperty("air_date") val air_date: String?
    )

    private data class TMDBEpisode(
        @JsonProperty("episode_number") val episode_number: Int,
        @JsonProperty("name") val name: String,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("still_path") val still_path: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("air_date") val air_date: String?
    )

    private data class TMDBDetailsResponse(
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("backdrop_path") val backdrop_path: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("genres") val genres: List<TMDBGenre>?,
        @JsonProperty("credits") val credits: TMDBCredits?,
        @JsonProperty("videos") val videos: TMDBVideos?,
        @JsonProperty("vote_average") val vote_average: Double?
    )

    private data class TMDBGenre(
        @JsonProperty("name") val name: String
    )

    private data class TMDBCredits(
        @JsonProperty("cast") val cast: List<TMDBCast>
    )

    private data class TMDBCast(
        @JsonProperty("name") val name: String,
        @JsonProperty("character") val character: String?,
        @JsonProperty("profile_path") val profile_path: String?
    )

    private data class TMDBVideos(
        @JsonProperty("results") val results: List<TMDBVideo>
    )

    private data class TMDBVideo(
        @JsonProperty("key") val key: String,
        @JsonProperty("site") val site: String,
        @JsonProperty("type") val type: String,
        @JsonProperty("official") val official: Boolean? = false
    )
}
