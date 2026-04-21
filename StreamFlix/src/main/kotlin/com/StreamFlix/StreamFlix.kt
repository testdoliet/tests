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
        println("🚀 [StreamFlix] Plugin carregando...")
        registerMainAPI(StreamFlix())
        println("✅ [StreamFlix] Plugin registrado com sucesso!")
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

    private val MOVIE_CATEGORIES = mapOf(
        "73" to "🎬 FILMES EM 4K",
        "69" to "🎬 FILMES DE AÇÃO",
        "70" to "🎬 FILMES DE COMÉDIA",
        "74" to "🎬 FILMES DE DRAMA",
        "72" to "🎬 FILMES DE TERROR"
    )

    private val SERIES_CATEGORIES = mapOf(
        "107" to "📺 SÉRIES",
        "126" to "🇰🇷 DORAMAS",
        "112" to "🇯🇵 ANIMES"
    )

    override val mainPage = mainPageOf(
        *MOVIE_CATEGORIES.map { (id, name) ->
            "$id" to name
        }.toTypedArray(),
        *SERIES_CATEGORIES.map { (id, name) ->
            "series_$id" to name
        }.toTypedArray()
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        println("📱 [StreamFlix] getMainPage - Página: $page, Categoria: ${request.name}")
        
        val categoryId = when {
            MOVIE_CATEGORIES.values.contains(request.name) -> {
                MOVIE_CATEGORIES.entries.find { it.value == request.name }?.key
            }
            SERIES_CATEGORIES.values.contains(request.name) -> {
                SERIES_CATEGORIES.entries.find { it.value == request.name }?.key
            }
            else -> null
        }
        
        println("🔍 [StreamFlix] Category ID: $categoryId")
        
        val isMovies = MOVIE_CATEGORIES.values.contains(request.name)
        
        val items = if (isMovies && categoryId != null) {
            getMoviesByCategory(categoryId, page)
        } else if (!isMovies && categoryId != null) {
            getSeriesByCategory(categoryId, page)
        } else {
            emptyList()
        }
        
        println("✅ [StreamFlix] Retornando ${items.size} itens para ${request.name}")
        return newHomePageResponse(request.name, items, hasNext = items.size == PAGE_SIZE)
    }

    private suspend fun getMoviesByCategory(categoryId: String, page: Int): List<SearchResponse> {
        println("🎬 [StreamFlix] getMoviesByCategory - ID: $categoryId, Página: $page")
        
        val allMovies = getAllMovies()
        println("📊 [StreamFlix] Total de filmes: ${allMovies.length()}")
        
        val isFourKCategory = categoryId == "73"
        
        val categoryMovies = mutableListOf<JSONObject>()
        for (i in 0 until allMovies.length()) {
            val movie = allMovies.getJSONObject(i)
            val movieCategories = getCategoryIds(movie)
            if (movieCategories.contains(categoryId)) {
                categoryMovies.add(movie)
            }
        }
        
        println("📊 [StreamFlix] Filmes na categoria $categoryId: ${categoryMovies.size}")
        
        val start = page * PAGE_SIZE
        val end = minOf(start + PAGE_SIZE, categoryMovies.size)
        
        if (start >= categoryMovies.size) {
            println("⚠️ [StreamFlix] Sem mais filmes na página $page")
            return emptyList()
        }
        
        val results = mutableListOf<SearchResponse>()
        for (i in start until end) {
            val movie = categoryMovies[i]
            val rawName = movie.getString("name")
            
            if (isAdultContent(rawName)) {
                println("🔞 [StreamFlix] Pulando conteúdo adulto: $rawName")
                continue
            }
            
            val (cleanName, dubStatus, qualityTag) = processTitle(rawName, isFourKCategory)
            val finalName = cleanTitle(cleanName)
            
            val id = movie.getInt("stream_id")
            val poster = fixImageUrl(movie.optString("stream_icon"))
            val ratingValue = movie.optDouble("rating_5based", 0.0).let { it.toFloat() * 2 }
            
            println("🎯 [StreamFlix] Filme: $finalName (ID: $id) - Qualidade: $qualityTag")
            
            results.add(
                newAnimeSearchResponse(finalName, "movie?id=$id", TvType.Movie) {
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
        println("📺 [StreamFlix] getSeriesByCategory - ID: $categoryId, Página: $page")
        
        val allSeries = getAllSeries()
        println("📊 [StreamFlix] Total de séries: ${allSeries.length()}")
        
        val categorySeries = mutableListOf<JSONObject>()
        for (i in 0 until allSeries.length()) {
            val series = allSeries.getJSONObject(i)
            val seriesCategories = getCategoryIds(series)
            if (seriesCategories.contains(categoryId)) {
                categorySeries.add(series)
            }
        }
        
        println("📊 [StreamFlix] Séries na categoria $categoryId: ${categorySeries.size}")
        
        val start = page * PAGE_SIZE
        val end = minOf(start + PAGE_SIZE, categorySeries.size)
        
        if (start >= categorySeries.size) {
            println("⚠️ [StreamFlix] Sem mais séries na página $page")
            return emptyList()
        }
        
        val results = mutableListOf<SearchResponse>()
        for (i in start until end) {
            val series = categorySeries[i]
            val rawName = series.getString("name")
            
            if (isAdultContent(rawName)) {
                println("🔞 [StreamFlix] Pulando conteúdo adulto: $rawName")
                continue
            }
            
            val (cleanName, dubStatus, qualityTag) = processTitle(rawName, false)
            val finalName = cleanTitle(cleanName)
            
            val id = series.getInt("series_id")
            val poster = fixImageUrl(series.optString("cover"))
            val ratingValue = series.optDouble("rating_5based", 0.0).let { it.toFloat() * 2 }
            
            println("🎯 [StreamFlix] Série: $finalName (ID: $id)")
            
            results.add(
                newAnimeSearchResponse(finalName, "series?id=$id", TvType.TvSeries) {
                    this.posterUrl = poster
                    if (qualityTag != null) this.quality = qualityTag
                    if (dubStatus != null) this.dubStatus = dubStatus
                    this.score = Score.from10(ratingValue)
                }
            )
        }
        return results
    }

    private fun processTitle(rawTitle: String, isFourKCategory: Boolean): Triple<String, EnumSet<DubStatus>?, SearchQuality?> {
        println("🔧 [StreamFlix] Processando título: $rawTitle")
        
        var cleanTitle = rawTitle.trim()
        var dubStatus: EnumSet<DubStatus>? = null
        var qualityTag: SearchQuality? = null
        
        if (isFourKCategory || Regex("\\b4K\\b", RegexOption.IGNORE_CASE).containsMatchIn(cleanTitle)) {
            qualityTag = SearchQuality.FourK
            println("  🎬 Qualidade 4K detectada")
        }
        
        val hasLegTag = Regex("\\[L\\]", RegexOption.IGNORE_CASE).containsMatchIn(cleanTitle)
        
        if (hasLegTag) {
            dubStatus = EnumSet.of(DubStatus.Subbed)
            cleanTitle = cleanTitle.replace(Regex("\\s*\\[L\\]\\s*", RegexOption.IGNORE_CASE), " ")
            cleanTitle = cleanTitle.replace(Regex("\\s*\\[L\\]\\s*\$", RegexOption.IGNORE_CASE), "")
            println("  📝 Legendado detectado")
        } else {
            dubStatus = EnumSet.of(DubStatus.Dubbed)
            println("  🎤 Dublado detectado")
        }
        
        println("  ✅ Título limpo: $cleanTitle")
        return Triple(cleanTitle, dubStatus, qualityTag)
    }

    private fun cleanTitle(title: String): String {
        var cleaned = title.trim()
        
        cleaned = cleaned.replace(Regex("\\b4K\\b", RegexOption.IGNORE_CASE), "")
        cleaned = cleaned.replace(Regex("\\s*4K\\s*", RegexOption.IGNORE_CASE), " ")
        cleaned = cleaned.replace(Regex("\\s*\\(\\d{4}\\)\\s*"), " ")
        cleaned = cleaned.replace(Regex("\\s*\\(\\d{4}\\)\$"), "")
        cleaned = cleaned.replace(Regex("\\s*\\[[^\\]]+\\]\\s*"), " ")
        cleaned = cleaned.replace(Regex("\\s*\\[[^\\]]+\\]\\s*\$"), "")
        cleaned = cleaned.replace(Regex("\\s*HDR\\s*", RegexOption.IGNORE_CASE), " ")
        cleaned = cleaned.replace(Regex("\\s*HYBRID\\s*", RegexOption.IGNORE_CASE), " ")
        cleaned = cleaned.replace(Regex("\\s*HD\\s*", RegexOption.IGNORE_CASE), " ")
        cleaned = cleaned.replace(Regex("\\s*FULLHD\\s*", RegexOption.IGNORE_CASE), " ")
        cleaned = cleaned.replace(Regex("\\s*UHD\\s*", RegexOption.IGNORE_CASE), " ")
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
        } else {
            val categoryIdInt = obj.optInt("category_id", -1)
            if (categoryIdInt != -1) {
                ids.add(categoryIdInt.toString())
            }
        }
        
        return ids
    }

    private suspend fun getAllMovies(): JSONArray {
        if (cachedMovies != null) {
            println("💾 [StreamFlix] Usando cache de filmes (${cachedMovies?.length()} itens)")
            return cachedMovies!!
        }
        
        println("🌐 [StreamFlix] Buscando filmes da API...")
        return withContext(Dispatchers.IO) {
            val response = app.get("$mainUrl/api_proxy.php?action=get_vod_streams")
            val json = JSONArray(response.body.string())
            cachedMovies = json
            println("✅ [StreamFlix] Carregados ${json.length()} filmes")
            json
        }
    }

    private suspend fun getAllSeries(): JSONArray {
        if (cachedSeries != null) {
            println("💾 [StreamFlix] Usando cache de séries (${cachedSeries?.length()} itens)")
            return cachedSeries!!
        }
        
        println("🌐 [StreamFlix] Buscando séries da API...")
        return withContext(Dispatchers.IO) {
            val response = app.get("$mainUrl/api_proxy.php?action=get_series")
            val json = JSONArray(response.body.string())
            cachedSeries = json
            println("✅ [StreamFlix] Carregadas ${json.length()} séries")
            json
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        println("🔍 [StreamFlix] Buscando por: $query")
        
        if (query.length < 2) {
            println("⚠️ [StreamFlix] Query muito curta: ${query.length} caracteres")
            return emptyList()
        }
        
        val results = mutableListOf<SearchResponse>()
        val queryLower = query.lowercase()
        
        val allMovies = getAllMovies()
        println("📊 [StreamFlix] Buscando em ${allMovies.length()} filmes...")
        
        for (i in 0 until allMovies.length()) {
            val movie = allMovies.getJSONObject(i)
            val rawName = movie.getString("name")
            if (rawName.lowercase().contains(queryLower)) {
                if (isAdultContent(rawName)) continue
                
                val (cleanName, dubStatus, qualityTag) = processTitle(rawName, false)
                val finalName = cleanTitle(cleanName)
                val id = movie.getInt("stream_id")
                val poster = fixImageUrl(movie.optString("stream_icon"))
                val ratingValue = movie.optDouble("rating_5based", 0.0).let { it.toFloat() * 2 }
                
                println("  🎯 Encontrado filme: $finalName (ID: $id)")
                
                results.add(
                    newAnimeSearchResponse(finalName, "movie?id=$id", TvType.Movie) {
                        this.posterUrl = poster
                        if (qualityTag != null) this.quality = qualityTag
                        if (dubStatus != null) this.dubStatus = dubStatus
                        this.score = Score.from10(ratingValue)
                    }
                )
            }
        }
        
        val allSeries = getAllSeries()
        println("📊 [StreamFlix] Buscando em ${allSeries.length()} séries...")
        
        for (i in 0 until allSeries.length()) {
            val series = allSeries.getJSONObject(i)
            val rawName = series.getString("name")
            if (rawName.lowercase().contains(queryLower)) {
                if (isAdultContent(rawName)) continue
                
                val (cleanName, dubStatus, qualityTag) = processTitle(rawName, false)
                val finalName = cleanTitle(cleanName)
                val id = series.getInt("series_id")
                val poster = fixImageUrl(series.optString("cover"))
                val ratingValue = series.optDouble("rating_5based", 0.0).let { it.toFloat() * 2 }
                
                println("  🎯 Encontrada série: $finalName (ID: $id)")
                
                results.add(
                    newAnimeSearchResponse(finalName, "series?id=$id", TvType.TvSeries) {
                        this.posterUrl = poster
                        if (qualityTag != null) this.quality = qualityTag
                        if (dubStatus != null) this.dubStatus = dubStatus
                        this.score = Score.from10(ratingValue)
                    }
                )
            }
        }
        
        println("✅ [StreamFlix] Busca finalizada: ${results.size} resultados")
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        println("📥 [StreamFlix] Carregando URL: $url")
        
        val cleanUrl = url
            .removePrefix("https://streamflix.live/")
            .removePrefix("http://streamflix.live/")
            .removePrefix("streamflix.live/")
        
        return when {
            cleanUrl.startsWith("movie?id=") -> {
                val id = cleanUrl.substringAfter("movie?id=")
                println("🎬 Carregando filme ID: $id")
                loadMovie(id)
            }
            cleanUrl.startsWith("series?id=") -> {
                val id = cleanUrl.substringAfter("series?id=")
                println("📺 Carregando série ID: $id")
                loadSeries(id)
            }
            else -> {
                println("❌ URL não reconhecida: $cleanUrl")
                null
            }
        }
    }

    private fun isAdultContent(title: String): Boolean {
        val adultKeywords = listOf(
            "XXX", "ADULTOS", "Porn", "Sexo", "Erótico", "Erótica",
            "Hardcore", "18+", "Adult", "Erotico", "18 anos"
        )
        val titleUpper = title.uppercase(Locale.getDefault())
        return adultKeywords.any { titleUpper.contains(it.uppercase(Locale.getDefault())) }
    }

    private fun cleanTitleForTMDB(title: String): String {
        var cleaned = title.trim()
        
        cleaned = cleaned.replace(Regex("\\s*\\[[^\\]]+\\]\\s*"), " ")
        cleaned = cleaned.replace(Regex("\\s*\\[[^\\]]+\\]\\s*\$"), "")
        cleaned = cleaned.replace(Regex("\\b4K\\b", RegexOption.IGNORE_CASE), "")
        cleaned = cleaned.replace(Regex("\\s*4K\\s*", RegexOption.IGNORE_CASE), " ")
        cleaned = cleaned.replace(Regex("\\s*\\(\\d{4}\\)\\s*"), " ")
        cleaned = cleaned.replace(Regex("\\s*\\(\\d{4}\\)\$"), "")
        cleaned = cleaned.replace(Regex("\\s*HD\\s*", RegexOption.IGNORE_CASE), " ")
        cleaned = cleaned.replace(Regex("\\s*FULLHD\\s*", RegexOption.IGNORE_CASE), " ")
        cleaned = cleaned.replace(Regex("\\s*HDR\\s*", RegexOption.IGNORE_CASE), " ")
        cleaned = cleaned.replace(Regex("\\s*HYBRID\\s*", RegexOption.IGNORE_CASE), " ")
        cleaned = cleaned.replace(Regex("\\s+"), " ").trim()
        
        return cleaned
    }

    private suspend fun loadMovie(id: String): LoadResponse? {
        println("🎬 [StreamFlix] loadMovie - ID: $id")
        
        return withContext(Dispatchers.IO) {
            try {
                println("🌐 Buscando informações do filme...")
                val infoResponse = app.get("$mainUrl/api_proxy.php?action=get_vod_info&vod_id=$id")
                val infoJson = JSONObject(infoResponse.body.string())
                val info = infoJson.optJSONObject("info") ?: JSONObject()
                
                val rawTitle = info.optString("name", "Título indisponível")
                println("📝 Título original: $rawTitle")
                
                val (cleanName, _, _) = processTitle(rawTitle, false)
                val finalTitle = cleanTitle(cleanName)
                println("✅ Título limpo: $finalTitle")
                
                val posterFallback = fixImageUrl(info.optString("cover_big"))
                
                println("🔍 Buscando no TMDB: ${cleanTitleForTMDB(finalTitle)}")
                val tmdbData = searchMovieOnTMDB(cleanTitleForTMDB(finalTitle))
                
                if (tmdbData != null) {
                    println("✅ TMDB encontrado: ${tmdbData.title}")
                } else {
                    println("⚠️ TMDB não encontrado, usando dados locais")
                }
                
                val backdrop = tmdbData?.backdropUrl ?: fixImageUrl(info.optString("cover_big"))
                val poster = tmdbData?.posterUrl ?: posterFallback
                val plot = tmdbData?.overview ?: info.optString("plot", "Sinopse não disponível.")
                val year = tmdbData?.year ?: info.optString("releaseDate").takeIf { it.isNotEmpty() }?.substring(0, 4)?.toIntOrNull()
                val rating = tmdbData?.rating?.let { Score.from10(it) } ?: info.optDouble("rating_5based", 0.0).let { Score.from10(it.toFloat() * 2) }
                val duration = tmdbData?.duration ?: info.optInt("duration_secs", 0).takeIf { it > 0 }?.let { it / 60 }
                val tags = tmdbData?.genres ?: info.optString("genre").split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val actors = tmdbData?.actors
                val trailerUrl = tmdbData?.youtubeTrailer
                
                println("🌐 Buscando URL do stream...")
                val streamResponse = app.get("$mainUrl/api_proxy.php?action=get_stream_url&type=movie&id=$id")
                val streamJson = JSONObject(streamResponse.body.string())
                val videoUrl = streamJson.getString("stream_url")
                println("✅ URL do stream obtida: ${videoUrl.take(80)}...")
                
                newMovieLoadResponse(finalTitle, "movie?id=$id", TvType.Movie, videoUrl) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backdrop
                    this.plot = plot
                    this.year = year
                    this.score = rating
                    this.duration = duration
                    this.tags = tags
                    
                    if (actors != null && actors.isNotEmpty()) {
                        addActors(actors)
                    }
                    if (trailerUrl != null) {
                        addTrailer(trailerUrl)
                    }
                }
            } catch (e: Exception) {
                println("❌ [StreamFlix] Erro ao carregar filme: ${e.message}")
                e.printStackTrace()
                null
            }
        }
    }

    private suspend fun searchMovieOnTMDB(query: String): TMDBMovieInfo? {
        println("🔍 [StreamFlix] searchMovieOnTMDB - Query: $query")
        
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "https://api.themoviedb.org/3/search/movie?api_key=$TMDB_API_KEY&query=$encodedQuery&language=pt-BR"
            
            val headers = mapOf(
                "Authorization" to "Bearer $TMDB_ACCESS_TOKEN",
                "accept" to "application/json"
            )
            
            val response = app.get(url, headers = headers, timeout = 10_000)
            if (response.code != 200) {
                println("⚠️ TMDB erro: ${response.code}")
                return null
            }
            
            val searchResult = response.parsedSafe<TMDBSearchResponse>() ?: return null
            val result = searchResult.results.firstOrNull() ?: return null
            
            println("✅ TMDB encontrado: ${result.title} (ID: ${result.id})")
            
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
            println("❌ TMDB erro: ${e.message}")
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

    private suspend fun loadSeries(id: String): LoadResponse? {
        println("📺 [StreamFlix] loadSeries - ID: $id")
        
        return withContext(Dispatchers.IO) {
            try {
                println("🌐 Buscando informações da série...")
                val infoResponse = app.get("$mainUrl/api_proxy.php?action=get_series_info&series_id=$id")
                val json = JSONObject(infoResponse.body.string())
                val info = json.optJSONObject("info") ?: JSONObject()
                
                val rawTitle = info.getString("name")
                println("📝 Título original: $rawTitle")
                
                val (cleanName, _, _) = processTitle(rawTitle, false)
                val finalTitle = cleanTitle(cleanName)
                println("✅ Título limpo: $finalTitle")
                
                val posterFallback = fixImageUrl(info.optString("cover"))
                
                println("🔍 Buscando no TMDB: ${cleanTitleForTMDB(finalTitle)}")
                val tmdbData = searchSeriesOnTMDB(cleanTitleForTMDB(finalTitle))
                
                if (tmdbData != null) {
                    println("✅ TMDB encontrado para série")
                } else {
                    println("⚠️ TMDB não encontrado, usando dados locais")
                }
                
                val backdrop = tmdbData?.backdropUrl ?: fixImageUrl(info.optString("cover"))
                val poster = tmdbData?.posterUrl ?: posterFallback
                val plot = tmdbData?.overview ?: info.optString("plot", "Sinopse não disponível.")
                val year = tmdbData?.year ?: info.optString("releaseDate").takeIf { it.isNotEmpty() }?.substring(0, 4)?.toIntOrNull()
                val seriesRating = tmdbData?.rating?.let { Score.from10(it) } ?: info.optDouble("rating_5based", 0.0).let { Score.from10(it.toFloat() * 2) }
                val tags = tmdbData?.genres ?: info.optString("genre").split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val actors = tmdbData?.actors
                val trailerUrl = tmdbData?.youtubeTrailer
                
                println("📺 Extraindo episódios...")
                val episodes = extractEpisodes(json, tmdbData, seriesRating)
                
                if (episodes.isEmpty()) {
                    println("❌ Nenhum episódio encontrado!")
                    return@withContext null
                }
                
                println("✅ ${episodes.size} episódios encontrados")
                
                newTvSeriesLoadResponse(finalTitle, "series?id=$id", TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backdrop
                    this.plot = plot
                    this.year = year
                    this.score = seriesRating
                    this.tags = tags
                    
                    if (actors != null && actors.isNotEmpty()) {
                        addActors(actors)
                    }
                    if (trailerUrl != null) {
                        addTrailer(trailerUrl)
                    }
                }
            } catch (e: Exception) {
                println("❌ [StreamFlix] Erro ao carregar série: ${e.message}")
                e.printStackTrace()
                null
            }
        }
    }

    private suspend fun searchSeriesOnTMDB(query: String): TMDBSeriesInfo? {
        println("🔍 [StreamFlix] searchSeriesOnTMDB - Query: $query")
        
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "https://api.themoviedb.org/3/search/tv?api_key=$TMDB_API_KEY&query=$encodedQuery&language=pt-BR"
            
            val headers = mapOf(
                "Authorization" to "Bearer $TMDB_ACCESS_TOKEN",
                "accept" to "application/json"
            )
            
            val response = app.get(url, headers = headers, timeout = 10_000)
            if (response.code != 200) {
                println("⚠️ TMDB erro: ${response.code}")
                return null
            }
            
            val searchResult = response.parsedSafe<TMDBSearchResponse>() ?: return null
            val result = searchResult.results.firstOrNull() ?: return null
            
            println("✅ TMDB encontrado: ${result.name} (ID: ${result.id})")
            
            val details = getTMDBSeriesDetails(result.id)
            
            TMDBSeriesInfo(
                title = result.id.toString(),
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
            println("❌ TMDB erro: ${e.message}")
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
        println("📺 [StreamFlix] Buscando temporadas do TMDB...")
        
        val seasonsEpisodes = mutableMapOf<Int, List<TMDBEpisode>>()
        val seasons = details?.seasons ?: return emptyMap()
        
        for (season in seasons) {
            if (season.season_number > 0) {
                val seasonNumber = season.season_number
                println("  🌐 Buscando temporada $seasonNumber...")
                
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
                        println("  ✅ Temporada $seasonNumber: ${episodes.size} episódios")
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

    private suspend fun extractEpisodes(json: JSONObject, tmdbData: TMDBSeriesInfo?, seriesRating: Score?): List<Episode> {
        println("📺 [StreamFlix] Extraindo episódios...")
        
        val episodes = mutableListOf<Episode>()
        val episodesJson = json.optJSONObject("episodes")
        
        if (episodesJson != null) {
            val seasonKeys = episodesJson.keys()
            while (seasonKeys.hasNext()) {
                val seasonNum = seasonKeys.next().toIntOrNull() ?: continue
                println("  📺 Processando temporada $seasonNum...")
                
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
                    
                    println("    🎯 Episódio $epNum: $epTitle (ID: $epId)")
                    
                    val streamResponse = app.get("$mainUrl/api_proxy.php?action=get_stream_url&type=series&id=$epId")
                    val streamJson = JSONObject(streamResponse.body.string())
                    val videoUrl = streamJson.getString("stream_url")
                    
                    val thumb = tmdbEpisode?.still_path?.let { "$tmdbImageUrl/w300$it" } ?: epImageFallback
                    val description = tmdbEpisode?.overview?.takeIf { it.isNotEmpty() } ?: epPlotFallback
                    val duration = tmdbEpisode?.runtime ?: (epDurationFallback?.let { it / 60 })
                    
                    var epRating: Score? = null
                    val seriesTmdbId = tmdbData?.title?.toIntOrNull()
                    
                    if (seriesTmdbId != null && seasonNum != null && epNum != null) {
                        try {
                            val episodeDetailsUrl = "https://api.themoviedb.org/3/tv/$seriesTmdbId/season/$seasonNum/episode/$epNum?api_key=$TMDB_API_KEY&language=pt-BR"
                            val episodeResponse = app.get(episodeDetailsUrl, timeout = 5_000)
                            if (episodeResponse.code == 200) {
                                val epDetailsJson = JSONObject(episodeResponse.text)
                                val ratingValue = epDetailsJson.optDouble("vote_average").takeIf { it > 0 }
                                if (ratingValue != null) {
                                    epRating = Score.from10(ratingValue)
                                    println("      ⭐ Avaliação TMDB: $ratingValue")
                                }
                            }
                        } catch (e: Exception) { }
                    }
                    
                    if (epRating == null && seriesRating != null) {
                        epRating = seriesRating
                    }
                    
                    episodes.add(
                        newEpisode(videoUrl) {
                            this.name = tmdbEpisode?.name?.takeIf { it.isNotEmpty() } ?: epTitle
                            this.season = seasonNum
                            this.episode = epNum
                            this.posterUrl = thumb
                            this.description = description
                            if (duration != null && duration > 0) this.runTime = duration
                            if (epRating != null) this.score = epRating
                            if (tmdbEpisode?.air_date != null) {
                                try {
                                    val dateFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                                    val date = dateFormatter.parse(tmdbEpisode.air_date)
                                    this.date = date?.time
                                } catch (e: Exception) { }
                            }
                        }
                    )
                }
            }
        }
        
        println("✅ [StreamFlix] Total de episódios extraídos: ${episodes.size}")
        return episodes
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔗 [StreamFlix] Carregando links para: ${data.take(80)}...")
        
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
        
        println("✅ [StreamFlix] Link adicionado")
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

    // Data classes
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
