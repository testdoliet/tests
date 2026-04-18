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
            val rawName = movie.getString("name")
            
            // BLOQUEAR CONTEÚDO ADULTO
            if (isAdultContent(rawName)) {
                println("🔞 [StreamFlix] Conteúdo adulto bloqueado: '$rawName'")
                continue
            }
            
            val (cleanName, dubStatus, qualityTag) = processTitle(rawName)
            val id = movie.getInt("stream_id")
            val poster = fixImageUrl(movie.optString("stream_icon"))
            
            // AVALIAÇÃO
            val rating = movie.optDouble("rating_5based", 0.0).takeIf { it > 0 }?.let { it.toFloat() * 2 }
            
            results.add(
                newMovieSearchResponse(cleanName, "movie?id=$id") {
                    this.posterUrl = poster
                    this.dubStatus = dubStatus
                    if (qualityTag != null) this.quality = qualityTag
                    if (rating != null) this.score = Score.from10(rating)
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
            val rawName = series.getString("name")
            
            // BLOQUEAR CONTEÚDO ADULTO
            if (isAdultContent(rawName)) {
                println("🔞 [StreamFlix] Conteúdo adulto bloqueado: '$rawName'")
                continue
            }
            
            val (cleanName, dubStatus, qualityTag) = processTitle(rawName)
            val id = series.getInt("series_id")
            val poster = fixImageUrl(series.optString("cover"))
            
            // AVALIAÇÃO
            val rating = series.optDouble("rating_5based", 0.0).takeIf { it > 0 }?.let { it.toFloat() * 2 }
            
            results.add(
                newTvSeriesSearchResponse(cleanName, "series?id=$id") {
                    this.posterUrl = poster
                    this.dubStatus = dubStatus
                    if (qualityTag != null) this.quality = qualityTag
                    if (rating != null) this.score = Score.from10(rating)
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
            val rawName = movie.getString("name")
            if (rawName.lowercase().contains(queryLower)) {
                if (isAdultContent(rawName)) continue
                
                val (cleanName, dubStatus, qualityTag) = processTitle(rawName)
                val id = movie.getInt("stream_id")
                val poster = fixImageUrl(movie.optString("stream_icon"))
                val rating = movie.optDouble("rating_5based", 0.0).takeIf { it > 0 }?.let { it.toFloat() * 2 }
                
                results.add(
                    newMovieSearchResponse(cleanName, "movie?id=$id") {
                        this.posterUrl = poster
                        this.dubStatus = dubStatus
                        if (qualityTag != null) this.quality = qualityTag
                        if (rating != null) this.score = Score.from10(rating)
                    }
                )
            }
        }
        
        val allSeries = getAllSeries()
        for (i in 0 until allSeries.length()) {
            val series = allSeries.getJSONObject(i)
            val rawName = series.getString("name")
            if (rawName.lowercase().contains(queryLower)) {
                if (isAdultContent(rawName)) continue
                
                val (cleanName, dubStatus, qualityTag) = processTitle(rawName)
                val id = series.getInt("series_id")
                val poster = fixImageUrl(series.optString("cover"))
                val rating = series.optDouble("rating_5based", 0.0).takeIf { it > 0 }?.let { it.toFloat() * 2 }
                
                results.add(
                    newTvSeriesSearchResponse(cleanName, "series?id=$id") {
                        this.posterUrl = poster
                        this.dubStatus = dubStatus
                        if (qualityTag != null) this.quality = qualityTag
                        if (rating != null) this.score = Score.from10(rating)
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

    // ==================== FUNÇÕES AUXILIARES ====================
    
    // Verifica se é conteúdo adulto
    private fun isAdultContent(title: String): Boolean {
        val adultKeywords = listOf(
            "XXX", "ADULTOS", "Porn", "Sexo", "Erótico", "Erótica",
            "Hardcore", "18+", "Adult", "Erotico", "18 anos"
        )
        val titleUpper = title.uppercase()
        return adultKeywords.any { titleUpper.contains(it.uppercase()) }
    }

    // Processa o título: limpa, extrai DUB/Legendado e qualidade
    private fun processTitle(rawTitle: String): Triple<String, EnumSet<DubStatus>?, SearchQuality?> {
        var cleanTitle = rawTitle.trim()
        var dubStatus: EnumSet<DubStatus>? = null
        var qualityTag: SearchQuality? = null
        
        // Verifica [L] no final para Legendado
        if (cleanTitle.matches(Regex(".*\\[L\\]\\s*$"))) {
            dubStatus = EnumSet.of(DubStatus.Subbed)
            cleanTitle = cleanTitle.replace(Regex("\\s*\\[L\\]\\s*$"), "").trim()
        }
        
        // Verifica 4K no final
        if (cleanTitle.matches(Regex(".*4K\\s*$", RegexOption.IGNORE_CASE))) {
            qualityTag = SearchQuality.FourK
            cleanTitle = cleanTitle.replace(Regex("\\s*4K\\s*$", RegexOption.IGNORE_CASE), "").trim()
        }
        
        // Remove outras tags isoladas no final
        cleanTitle = cleanTitle.replace(Regex("\\s*\\[[^\\]]+\\]\\s*$"), "").trim()
        
        return Triple(cleanTitle, dubStatus, qualityTag)
    }

    private fun cleanTitleForTMDB(title: String): String {
        var cleaned = title.trim()
        cleaned = cleaned.replace(Regex("\\s*\\[[^\\]]+\\]\\s*$"), "")
        cleaned = cleaned.replace(Regex("\\s*4K\\s*", RegexOption.IGNORE_CASE), " ")
        cleaned = cleaned.replace(Regex("\\s*HD\\s*", RegexOption.IGNORE_CASE), " ")
        cleaned = cleaned.replace(Regex("\\s*FULLHD\\s*", RegexOption.IGNORE_CASE), " ")
        cleaned = cleaned.trim()
        return cleaned
    }

    // ==================== FILMES ====================
    
    private suspend fun loadMovie(id: String): LoadResponse? {
        println("🎬 [StreamFlix] loadMovie - ID: $id")
        
        return withContext(Dispatchers.IO) {
            try {
                val infoResponse = app.get("$mainUrl/api_proxy.php?action=get_vod_info&vod_id=$id")
                val infoJson = JSONObject(infoResponse.body.string())
                val info = infoJson.optJSONObject("info") ?: JSONObject()
                
                val rawTitle = info.optString("name", "Título indisponível")
                val (cleanTitle, dubStatus, qualityTag) = processTitle(rawTitle)
                
                val posterFallback = fixImageUrl(info.optString("cover_big"))
                
                val tmdbData = searchMovieOnTMDB(cleanTitleForTMDB(cleanTitle))
                
                val backdrop = tmdbData?.backdropUrl ?: fixImageUrl(info.optString("cover_big"))
                val poster = tmdbData?.posterUrl ?: posterFallback
                val plot = tmdbData?.overview ?: info.optString("plot", "Sinopse não disponível.")
                val year = tmdbData?.year ?: info.optString("releaseDate").takeIf { it.isNotEmpty() }?.substring(0, 4)?.toIntOrNull()
                val rating = tmdbData?.rating?.let { Score.from10(it) } ?: info.optDouble("rating_5based", 0.0).takeIf { it > 0 }?.let { Score.from10(it.toFloat() * 2) }
                val duration = tmdbData?.duration ?: info.optInt("duration_secs", 0).takeIf { it > 0 }?.let { it / 60 }
                val tags = tmdbData?.genres ?: info.optString("genre").split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val actors = tmdbData?.actors
                val trailerUrl = tmdbData?.youtubeTrailer
                
                val streamResponse = app.get("$mainUrl/api_proxy.php?action=get_stream_url&type=movie&id=$id")
                val streamJson = JSONObject(streamResponse.body.string())
                val videoUrl = streamJson.getString("stream_url")
                
                newMovieLoadResponse(cleanTitle, "movie?id=$id", TvType.Movie, videoUrl) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backdrop
                    this.plot = plot
                    this.year = year
                    this.score = rating
                    this.duration = duration
                    this.tags = tags
                    this.dubStatus = dubStatus
                    if (qualityTag != null) this.quality = qualityTag
                    
                    if (actors != null && actors.isNotEmpty()) {
                        addActors(actors)
                    }
                    if (trailerUrl != null) {
                        addTrailer(trailerUrl)
                    }
                }
            } catch (e: Exception) {
                println("💥 [StreamFlix] ERRO ao carregar filme: ${e.message}")
                null
            }
        }
    }

    private suspend fun searchMovieOnTMDB(query: String): TMDBMovieInfo? {
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "https://api.themoviedb.org/3/search/movie?api_key=$TMDB_API_KEY&query=$encodedQuery&language=pt-BR"
            
            val headers = mapOf(
                "Authorization" to "Bearer $TMDB_ACCESS_TOKEN",
                "accept" to "application/json"
            )
            
            val response = app.get(url, headers = headers, timeout = 10_000)
            if (response.code != 200) return null
            
            val searchResult = response.parsedSafe<TMDBSearchResponse>() ?: return null
            val result = searchResult.results.firstOrNull() ?: return null
            
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
            null
        }
    }

    // ==================== SÉRIES ====================
    
    private suspend fun loadSeries(id: String): LoadResponse? {
        println("📀 [StreamFlix] loadSeries - ID: $id")
        
        return withContext(Dispatchers.IO) {
            try {
                val infoResponse = app.get("$mainUrl/api_proxy.php?action=get_series_info&series_id=$id")
                val json = JSONObject(infoResponse.body.string())
                val info = json.optJSONObject("info") ?: JSONObject()
                
                val rawTitle = info.getString("name")
                val (cleanTitle, dubStatus, qualityTag) = processTitle(rawTitle)
                
                val posterFallback = fixImageUrl(info.optString("cover"))
                
                val tmdbData = searchSeriesOnTMDB(cleanTitleForTMDB(cleanTitle))
                
                val backdrop = tmdbData?.backdropUrl ?: fixImageUrl(info.optString("cover"))
                val poster = tmdbData?.posterUrl ?: posterFallback
                val plot = tmdbData?.overview ?: info.optString("plot", "Sinopse não disponível.")
                val year = tmdbData?.year ?: info.optString("releaseDate").takeIf { it.isNotEmpty() }?.substring(0, 4)?.toIntOrNull()
                val rating = tmdbData?.rating?.let { Score.from10(it) } ?: info.optDouble("rating_5based", 0.0).takeIf { it > 0 }?.let { Score.from10(it.toFloat() * 2) }
                val tags = tmdbData?.genres ?: info.optString("genre").split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val actors = tmdbData?.actors
                val trailerUrl = tmdbData?.youtubeTrailer
                
                val episodes = extractEpisodes(json, tmdbData)
                
                if (episodes.isEmpty()) return@withContext null
                
                newTvSeriesLoadResponse(cleanTitle, "series?id=$id", TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backdrop
                    this.plot = plot
                    this.year = year
                    this.score = rating
                    this.tags = tags
                    this.dubStatus = dubStatus
                    if (qualityTag != null) this.quality = qualityTag
                    
                    if (actors != null && actors.isNotEmpty()) {
                        addActors(actors)
                    }
                    if (trailerUrl != null) {
                        addTrailer(trailerUrl)
                    }
                }
            } catch (e: Exception) {
                println("💥 [StreamFlix] ERRO ao carregar série: ${e.message}")
                null
            }
        }
    }

    private suspend fun searchSeriesOnTMDB(query: String): TMDBSeriesInfo? {
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "https://api.themoviedb.org/3/search/tv?api_key=$TMDB_API_KEY&query=$encodedQuery&language=pt-BR"
            
            val headers = mapOf(
                "Authorization" to "Bearer $TMDB_ACCESS_TOKEN",
                "accept" to "application/json"
            )
            
            val response = app.get(url, headers = headers, timeout = 10_000)
            if (response.code != 200) return null
            
            val searchResult = response.parsedSafe<TMDBSearchResponse>() ?: return null
            val result = searchResult.results.firstOrNull() ?: return null
            
            val details = getTMDBSeriesDetails(result.id)
            
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
                seasonsEpisodes = getTMDBAllSeasons(result.id, details)
            )
        } catch (e: Exception) {
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
            null
        }
    }

    private suspend fun getTMDBAllSeasons(seriesId: Int, details: TMDBDetailsResponse?): Map<Int, List<TMDBEpisode>> {
        val seasonsEpisodes = mutableMapOf<Int, List<TMDBEpisode>>()
        val seasons = details?.seasons ?: return emptyMap()
        
        for (season in seasons) {
            if (season.season_number > 0) {
                val seasonNumber = season.season_number
                val seasonUrl = "https://api.themoviedb.org/3/tv/$seriesId/season/$seasonNumber?api_key=$TMDB_API_KEY&language=pt-BR"
                val headers = mapOf(
                    "Authorization" to "Bearer $TMDB_ACCESS_TOKEN",
                    "accept" to "application/json"
                )
                val seasonResponse = app.get(seasonUrl, headers = headers, timeout = 10_000)
                if (seasonResponse.code == 200) {
                    val seasonData = seasonResponse.parsedSafe<TMDBSeasonResponse>()
                    seasonData?.episodes?.let { episodes ->
                        seasonsEpisodes[seasonNumber] = episodes
                    }
                }
            }
        }
        return seasonsEpisodes
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

    private suspend fun extractEpisodes(json: JSONObject, tmdbData: TMDBSeriesInfo?): List<Episode> {
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

    private data class TMDBDetailsResponse(
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("backdrop_path") val backdrop_path: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("genres") val genres: List<TMDBGenre>?,
        @JsonProperty("credits") val credits: TMDBCredits?,
        @JsonProperty("videos") val videos: TMDBVideos?,
        @JsonProperty("vote_average") val vote_average: Double?,
        @JsonProperty("seasons") val seasons: List<TMDBSeason>? = null
    )

    private data class TMDBSeason(
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
