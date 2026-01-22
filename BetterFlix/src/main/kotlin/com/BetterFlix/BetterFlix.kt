package com.BetterFlix

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.Jsoup
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class BetterFlix : MainAPI() {
    override var mainUrl = "https://betterflix.vercel.app"
    override var name = "BetterFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Live)
    override val usesWebView = false

    // Headers para evitar rate limiting
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "https://betterflix.vercel.app/",
        "Origin" to "https://betterflix.vercel.app",
        "Sec-Ch-Ua" to "\"Chromium\";v=\"127\", \"Not)A;Brand\";v=\"99\", \"Microsoft Edge Simulate\";v=\"127\", \"Lemur\";v=\"127\"",
        "Sec-Ch-Ua-Mobile" to "?1",
        "Sec-Ch-Ua-Platform" to "\"Android\"",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "same-origin"
    )

    // Cookies persistentes
    private val cookies = mapOf(
        "dom3ic8zudi28v8lr6fgphwffqoz0j6c" to "33de42d8-3e93-4249-b175-d6bf5346ae91%3A2%3A1",
        "pp_main_80d9775bdcedfb8fd29914d950374a08" to "1"
    )

    // Domínios para extração de vídeo
    private val superflixDomains = listOf(
        "https://superflixapi.bond",
        "https://superflixapi.asia",
        "https://superflixapi.top"
    )

    // Mapeamento de gêneros
    private val genreMap = mapOf(
        "28" to "Ação e Aventura",
        "35" to "Comédia",
        "27" to "Terror e Suspense",
        "99" to "Documentário",
        "10751" to "Para a Família",
        "80" to "Crime",
        "10402" to "Musical",
        "10749" to "Romance"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/trending" to "Trending",
        "$mainUrl/genre/28" to "Ação e Aventura",
        "$mainUrl/genre/35" to "Comédia",
        "$mainUrl/genre/27" to "Terror e Suspense",
        "$mainUrl/genre/99" to "Documentário",
        "$mainUrl/genre/10751" to "Para a Família",
        "$mainUrl/genre/80" to "Crime",
        "$mainUrl/genre/10402" to "Musical",
        "$mainUrl/genre/10749" to "Romance",
        "$mainUrl/animes" to "Animes"
    )

    // Modelos de dados para a API
    data class TrendingResponse(
        @JsonProperty("results") val results: List<ContentItem>
    )

    data class GenreResponse(
        @JsonProperty("results") val results: List<ContentItem>
    )

    data class AnimeResponse(
        @JsonProperty("results") val results: List<ContentItem>
    )

    data class ContentItem(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("original_title") val originalTitle: String?,
        @JsonProperty("original_name") val originalName: String?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("poster_path") val posterPath: String?,
        @JsonProperty("backdrop_path") val backdropPath: String?,
        @JsonProperty("media_type") val mediaType: String?,
        @JsonProperty("release_date") val releaseDate: String?,
        @JsonProperty("first_air_date") val firstAirDate: String?,
        @JsonProperty("vote_average") val voteAverage: Double?,
        @JsonProperty("genre_ids") val genreIds: List<Int>?,
        @JsonProperty("original_language") val originalLanguage: String?,
        @JsonProperty("popularity") val popularity: Double?,
        @JsonProperty("video") val video: Boolean?,
        @JsonProperty("adult") val adult: Boolean?
    )

    // Helper para fazer requests com rate limiting
    private suspend fun <T> safeApiRequest(url: String, block: suspend () -> T): T {
        // Adicionar delay para evitar rate limiting
        kotlinx.coroutines.delay(500)
        
        try {
            return block()
        } catch (e: Exception) {
            if (e.message?.contains("429") == true) {
                // Rate limit atingido, esperar mais tempo
                kotlinx.coroutines.delay(2000)
                return block()
            }
            throw e
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<SearchResponse>()
        
        return safeApiRequest(request.name) {
            when {
                request.name == "Trending" -> {
                    val trending = getTrending()
                    items.addAll(trending)
                }
                request.name == "Animes" -> {
                    val animes = getAnimes()
                    items.addAll(animes)
                }
                request.name in genreMap.values -> {
                    val genreId = genreMap.entries.find { it.value == request.name }?.key
                    if (genreId != null) {
                        val genreItems = getGenreContent(genreId)
                        items.addAll(genreItems)
                    }
                }
            }
            
            newHomePageResponse(request.name, items, hasNext = false)
        }
    }

    private suspend fun getTrending(): List<SearchResponse> {
        val url = "$mainUrl/api/trending?type=all"
        val response = app.get(
            url,
            headers = headers,
            cookies = cookies,
            timeout = 30
        )
        
        val data = response.parsedSafe<TrendingResponse>() ?: return emptyList()
        
        return data.results.mapNotNull { item ->
            try {
                val title = item.title ?: item.name ?: item.originalTitle ?: item.originalName ?: return@mapNotNull null
                val year = getYearFromDate(item.releaseDate ?: item.firstAirDate)
                val poster = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                val id = item.id
                
                // Determinar tipo
                val type = when (item.mediaType) {
                    "movie" -> TvType.Movie
                    "tv" -> TvType.TvSeries
                    "anime" -> TvType.Anime
                    else -> when {
                        title.contains("(Anime)", ignoreCase = true) -> TvType.Anime
                        item.releaseDate != null -> TvType.Movie
                        item.firstAirDate != null -> TvType.TvSeries
                        else -> TvType.Movie
                    }
                }
                
                // Gerar URL no formato correto do site COM TYPE
                val slug = generateSlug(title)
                val url = when (type) {
                    TvType.Movie -> "$mainUrl/$slug?id=$id&type=movie"
                    TvType.TvSeries -> "$mainUrl/$slug?id=$id&type=tv"
                    TvType.Anime -> "$mainUrl/$slug?id=$id&type=anime"
                    else -> "$mainUrl/$slug?id=$id&type=movie"
                }
                
                when (type) {
                    TvType.Movie -> newMovieSearchResponse(title, url, TvType.Movie) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    TvType.TvSeries -> newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    TvType.Anime -> newAnimeSearchResponse(title, url, TvType.Anime) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    else -> newMovieSearchResponse(title, url, TvType.Movie) {
                        this.posterUrl = poster
                        this.year = year
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private suspend fun getAnimes(): List<SearchResponse> {
        val url = "$mainUrl/api/list-animes"
        val response = app.get(
            url,
            headers = headers,
            cookies = cookies,
            timeout = 30
        )
        
        val data = response.parsedSafe<AnimeResponse>() ?: return emptyList()
        
        return data.results.mapNotNull { item ->
            try {
                val title = item.title ?: item.name ?: item.originalTitle ?: item.originalName ?: return@mapNotNull null
                val year = getYearFromDate(item.releaseDate ?: item.firstAirDate)
                val poster = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                val id = item.id
                
                // Gerar URL no formato correto COM TYPE
                val slug = generateSlug(title)
                val url = "$mainUrl/$slug?id=$id&type=anime"
                
                newAnimeSearchResponse(title, url, TvType.Anime) {
                    this.posterUrl = poster
                    this.year = year
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private suspend fun getGenreContent(genreId: String): List<SearchResponse> {
        val url = "$mainUrl/api/preview-genre?id=$genreId"
        val response = app.get(
            url,
            headers = headers,
            cookies = cookies,
            timeout = 30
        )
        
        val data = response.parsedSafe<GenreResponse>() ?: return emptyList()
        
        return data.results.mapNotNull { item ->
            try {
                val title = item.title ?: item.name ?: item.originalTitle ?: item.originalName ?: return@mapNotNull null
                val year = getYearFromDate(item.releaseDate ?: item.firstAirDate)
                val poster = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                val id = item.id
                
                // Determinar tipo
                val type = when (item.mediaType) {
                    "movie" -> TvType.Movie
                    "tv" -> TvType.TvSeries
                    else -> when {
                        title.contains("(Anime)", ignoreCase = true) -> TvType.Anime
                        item.releaseDate != null -> TvType.Movie
                        item.firstAirDate != null -> TvType.TvSeries
                        else -> TvType.Movie
                    }
                }
                
                // Gerar URL no formato correto COM TYPE
                val slug = generateSlug(title)
                val url = when (type) {
                    TvType.Movie -> "$mainUrl/$slug?id=$id&type=movie"
                    TvType.TvSeries -> "$mainUrl/$slug?id=$id&type=tv"
                    TvType.Anime -> "$mainUrl/$slug?id=$id&type=anime"
                    else -> "$mainUrl/$slug?id=$id&type=movie"
                }
                
                when (type) {
                    TvType.Movie -> newMovieSearchResponse(title, url, TvType.Movie) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    TvType.TvSeries -> newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    TvType.Anime -> newAnimeSearchResponse(title, url, TvType.Anime) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    else -> newMovieSearchResponse(title, url, TvType.Movie) {
                        this.posterUrl = poster
                        this.year = year
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun getYearFromDate(dateString: String?): Int? {
        return try {
            dateString?.substring(0, 4)?.toIntOrNull()
        } catch (e: Exception) {
            null
        }
    }

    // Função para extrair ano do documento
    private fun extractYear(document: org.jsoup.nodes.Document): Int? {
        // Tenta extrair do título
        val title = document.selectFirst("h1, .title")?.text() ?: ""
        val yearMatch = Regex("\\((\\d{4})\\)").find(title)
        if (yearMatch != null) {
            return yearMatch.groupValues[1].toIntOrNull()
        }
        
        // Tenta extrair de metadados
        document.select("div.bg-gray-800\\/50, .info-grid, .metadata").forEach { div ->
            val label = div.selectFirst("p.text-gray-400, .label, .info-label")?.text()
            if (label?.contains("Ano") == true || label?.contains("Year") == true) {
                val yearText = div.selectFirst("p.text-white, .value, .info-value")?.text()
                return yearText?.toIntOrNull()
            }
        }
        
        return null
    }

    // Função para gerar slug a partir do título
    private fun generateSlug(title: String): String {
        return title
            .lowercase()
            .replace(Regex("[^a-z0-9\\s-]"), "")
            .replace(Regex("\\s+"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return safeApiRequest(query) {
            // Primeiro tentar a API de busca do site
            try {
                val encodedQuery = query.encodeSearchQuery()
                val url = "$mainUrl/api/search?query=$encodedQuery"
                
                val response = app.get(
                    url,
                    headers = headers,
                    cookies = cookies,
                    timeout = 30
                )
                
                val data = response.parsedSafe<SearchResponseData>() ?: return@safeApiRequest emptyList()
                
                return@safeApiRequest data.results.mapNotNull { item ->
                    try {
                        val title = item.title ?: item.name ?: item.originalTitle ?: item.originalName ?: return@mapNotNull null
                        val year = getYearFromDate(item.releaseDate ?: item.firstAirDate)
                        val poster = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                        val id = item.id
                        
                        val type = when (item.mediaType) {
                            "movie" -> TvType.Movie
                            "tv" -> TvType.TvSeries
                            "anime" -> TvType.Anime
                            else -> when {
                                title.contains("(Anime)", ignoreCase = true) -> TvType.Anime
                                item.releaseDate != null -> TvType.Movie
                                item.firstAirDate != null -> TvType.TvSeries
                                else -> TvType.Movie
                            }
                        }
                        
                        // Gerar URL no formato correto COM TYPE
                        val slug = generateSlug(title)
                        val url = when (type) {
                            TvType.Movie -> "$mainUrl/$slug?id=$id&type=movie"
                            TvType.TvSeries -> "$mainUrl/$slug?id=$id&type=tv"
                            TvType.Anime -> "$mainUrl/$slug?id=$id&type=anime"
                            else -> "$mainUrl/$slug?id=$id&type=movie"
                        }
                        
                        when (type) {
                            TvType.Movie -> newMovieSearchResponse(title, url, TvType.Movie) {
                                this.posterUrl = poster
                                this.year = year
                            }
                            TvType.TvSeries -> newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                                this.posterUrl = poster
                                this.year = year
                            }
                            TvType.Anime -> newAnimeSearchResponse(title, url, TvType.Anime) {
                                this.posterUrl = poster
                                this.year = year
                            }
                            else -> newMovieSearchResponse(title, url, TvType.Movie) {
                                this.posterUrl = poster
                                this.year = year
                            }
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
            } catch (e: Exception) {
                // Fallback para busca HTML
                fallbackSearch(query)
            }
        }
    }

    // Fallback caso a API de busca não esteja disponível
    private suspend fun fallbackSearch(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?q=${query.encodeSearchQuery()}"
        val document = app.get(searchUrl, headers = headers, cookies = cookies).document
        
        return document.select("a[href*='?id=']").mapNotNull { element ->
            try {
                val href = element.attr("href") ?: return@mapNotNull null
                if (href.startsWith("/canal")) return@mapNotNull null
                
                val imgElement = element.selectFirst("img")
                val title = imgElement?.attr("alt") ?: 
                           element.selectFirst(".text-white")?.text() ?:
                           return@mapNotNull null
                
                val poster = imgElement?.attr("src")?.let { fixUrl(it) }
                val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
                val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
                
                // Determinar tipo pela URL
                val isSeries = href.contains("type=tv") || href.contains("/tv")
                val isMovie = href.contains("type=movie") || href.contains("/movie")
                val isAnime = title.contains("(Anime)", ignoreCase = true) || href.contains("type=anime")
                
                // Corrigir URL para incluir type se necessário
                var finalUrl = fixUrl(href)
                if (!finalUrl.contains("type=")) {
                    when {
                        isAnime -> finalUrl += "&type=anime"
                        isSeries -> finalUrl += "&type=tv"
                        isMovie -> finalUrl += "&type=movie"
                    }
                }
                
                when {
                    isAnime -> newAnimeSearchResponse(cleanTitle, finalUrl, TvType.Anime) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    isSeries -> newTvSeriesSearchResponse(cleanTitle, finalUrl, TvType.TvSeries) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    isMovie -> newMovieSearchResponse(cleanTitle, finalUrl, TvType.Movie) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    data class SearchResponseData(
        @JsonProperty("results") val results: List<ContentItem>
    )

    override suspend fun load(url: String): LoadResponse? {
        return safeApiRequest(url) {
            try {
                // Carregar a página de detalhes
                val response = app.get(url, headers = headers, cookies = cookies, timeout = 30)
                if (response.code >= 400) return@safeApiRequest null
                
                val document = response.document
                
                // Extrair informações da página
                val title = extractTitle(document)
                val year = extractYear(document)
                val overview = extractOverview(document)
                val poster = extractPoster(document)
                val backdrop = extractBackdrop(document)
                val genres = extractGenres(document)
                val actors = extractActors(document)
                val trailerKey = extractTrailer(document)
                
                // Determinar tipo pela URL
                val isSeries = url.contains("type=tv") || document.select(".episode-list, .season-list").isNotEmpty()
                val isAnime = url.contains("type=anime") || document.select(".anime-episodes").isNotEmpty()
                val isMovie = !isSeries && !isAnime
                
                if (isSeries || isAnime) {
                    val type = if (isAnime) TvType.Anime else TvType.TvSeries
                    val episodes = extractEpisodes(document, url)
                    
                    newTvSeriesLoadResponse(title ?: "Sem título", url, type, episodes) {
                        this.posterUrl = poster
                        this.backgroundPosterUrl = backdrop
                        this.year = year
                        this.plot = overview
                        this.tags = genres
                        this.duration = extractDuration(document)
                        this.recommendations = null
                        if (actors.isNotEmpty()) {
                            addActors(actors)
                        }
                        
                        if (trailerKey != null) {
                            addTrailer(trailerKey)
                        }
                    }
                } else {
                    newMovieLoadResponse(title ?: "Sem título", url, TvType.Movie, url) {
                        this.posterUrl = poster
                        this.backgroundPosterUrl = backdrop
                        this.year = year
                        this.plot = overview
                        this.tags = genres
                        this.duration = extractDuration(document)
                        this.recommendations = null
                        if (actors.isNotEmpty()) {
                            addActors(actors)
                        }
                        
                        if (trailerKey != null) {
                            addTrailer(trailerKey)
                        }
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    // IMPLEMENTAÇÃO DA EXTRAÇÃO DE VÍDEO CORRIGIDA
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔍 [DEBUG] loadLinks chamado com data: $data")
        
        return safeApiRequest(data) {
            try {
                // Extrair TMDB ID da URL
                val tmdbId = extractTmdbId(data)
                println("🔍 [DEBUG] TMDB ID extraído: $tmdbId")
                
                if (tmdbId == null) {
                    println("❌ [DEBUG] Falha ao extrair TMDB ID da URL")
                    return@safeApiRequest false
                }
                
                // Determinar tipo da URL para usar na extração
                val type = when {
                    data.contains("type=anime") -> "anime"
                    data.contains("type=tv") -> "tv"
                    else -> "movie"
                }
                println("🔍 [DEBUG] Tipo detectado: $type")
                
                // TENTAR TODOS OS DOMÍNIOS DO SUPERFLIX
                for (superflixDomain in superflixDomains) {
                    println("🔍 [DEBUG] Tentando domínio: $superflixDomain")
                    try {
                        val success = extractVideoFromSuperflix(superflixDomain, tmdbId, type, callback)
                        if (success) {
                            println("✅ [DEBUG] Sucesso na extração do vídeo usando $superflixDomain")
                            
                            // Adicionar legenda em português se disponível
                            try {
                                val subtitleUrl = "https://complicado.sbs/cdn/down/disk11/${tmdbId.substring(0, 32)}/Subtitle/subtitle_por.vtt"
                                println("🔍 [DEBUG] Tentando adicionar legenda: $subtitleUrl")
                                subtitleCallback.invoke(
                                    SubtitleFile("Português", subtitleUrl)
                                )
                            } catch (e: Exception) {
                                println("⚠️ [DEBUG] Erro ao adicionar legenda: ${e.message}")
                            }
                            
                            return@safeApiRequest true
                        }
                    } catch (e: Exception) {
                        println("❌ [DEBUG] Erro no domínio $superflixDomain: ${e.message}")
                        // Tentar próximo domínio
                        continue
                    }
                }
                
                println("⚠️ [DEBUG] Nenhum domínio SuperFlix funcionou, tentando método alternativo")
                
                // Se nenhum domínio funcionou, tentar método alternativo
                val alternativeResult = extractVideoAlternative(data, callback)
                if (alternativeResult) {
                    println("✅ [DEBUG] Método alternativo funcionou")
                } else {
                    println("❌ [DEBUG] Método alternativo também falhou")
                }
                
                return@safeApiRequest alternativeResult
            } catch (e: Exception) {
                println("❌ [DEBUG] Erro geral no loadLinks: ${e.message}")
                e.printStackTrace()
                false
            }
        }
    }

    private fun extractTmdbId(url: String): String? {
        // Extrair ID da URL: /dinheiro-suspeito?id=1306368&type=movie
        val idMatch = Regex("[?&]id=(\\d+)").find(url)
        return idMatch?.groupValues?.get(1)
    }

    private suspend fun extractVideoFromSuperflix(
    domain: String,
    tmdbId: String,
    type: String,
    callback: (ExtractorLink) -> Unit
): Boolean {
    try {
        println("🔍 [DEBUG] Iniciando extração de vídeo do SuperFlix")
        println("🔍 [DEBUG] Domínio: $domain")
        println("🔍 [DEBUG] TMDB ID: $tmdbId")
        println("🔍 [DEBUG] Tipo: $type")
        
        // Lista de video_ids possíveis
        val possibleVideoIds = listOf("303309", "351944")
        
        for (videoId in possibleVideoIds) {
            println("🔍 [DEBUG] Tentando video_id: $videoId")
            
            // PASSO 1: Obter o video_url da API do SuperFlix
            val apiUrl = "$domain/api"
            println("🔍 [DEBUG] POST para API: $apiUrl")
            
            val apiHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                "Accept" to "application/json, text/plain, */*",
                "Accept-Language" to "pt-BR",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "Origin" to domain,
                "Referer" to "$domain/filme/$tmdbId",
                "X-Requested-With" to "XMLHttpRequest",
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "same-origin"
            )
            
            val apiData = mapOf(
                "action" to "getPlayer",
                "video_id" to videoId
            )
            
            println("🔍 [DEBUG] Enviando dados: action=getPlayer, video_id=$videoId")
            
            try {
                val apiResponse = app.post(apiUrl, data = apiData, headers = apiHeaders, timeout = 30)
                println("🔍 [DEBUG] Status da API: ${apiResponse.code}")
                println("🔍 [DEBUG] Resposta da API: ${apiResponse.text}")
                
                if (apiResponse.code >= 400) {
                    println("❌ [DEBUG] Erro HTTP na API: ${apiResponse.code}")
                    continue
                }
                
                val apiJson = JSONObject(apiResponse.text)
                
                // Verificar corretamente o status
                val errors = apiJson.optString("errors", "1")
                val message = apiJson.optString("message", "")
                
                if (errors == "1" || message != "success") {
                    println("❌ [DEBUG] API retornou errors=$errors, message=$message")
                    continue
                }
                
                val videoUrl = apiJson.optJSONObject("data")?.optString("video_url")
                if (videoUrl.isNullOrEmpty()) {
                    println("❌ [DEBUG] Não foi possível extrair video_url")
                    continue
                }
                
                println("✅ [DEBUG] Video URL obtido: $videoUrl")
                
                // PASSO 2: Extrair o hash/token da URL
                val hash = extractHashFromVideoUrl(videoUrl)
                if (hash == null) {
                    println("❌ [DEBUG] Não foi possível extrair hash da URL")
                    continue
                }
                
                println("✅ [DEBUG] Hash extraído: $hash")
                
                // PASSO 3: Fazer a requisição para obter o m3u8
                val playerResult = requestPlayerHash(hash, callback)
                if (playerResult) {
                    return true
                }
                
            } catch (e: Exception) {
                println("❌ [DEBUG] Erro ao processar video_id $videoId: ${e.message}")
                continue
            }
        }
        
        println("❌ [DEBUG] Nenhum video_id funcionou")
        return false
        
    } catch (e: Exception) {
        println("❌ [DEBUG] Erro na extração do SuperFlix: ${e.message}")
        e.printStackTrace()
        return false
    }
}

private fun extractHashFromVideoUrl(videoUrl: String): String? {
    return when {
        // Exemplo: https://llanfairpwllgwyngy.com/video/a269ba2de7c47692cce1956aca54f22d
        videoUrl.contains("/video/") -> {
            videoUrl.substringAfter("/video/").substringBefore("?")
        }
        // Exemplo: https://play-utx.playmycnvs.com/m/Pecadores.2025.1080p.WEB-DL.DUAL.5.1.mp4
        videoUrl.contains("/m/") -> {
            videoUrl.substringAfter("/m/").substringBefore("?")
        }
        else -> null
    }
}

private suspend fun requestPlayerHash(
    hash: String,
    callback: (ExtractorLink) -> Unit
): Boolean {
    try {
        println("🔍 [DEBUG] Fazendo requisição para obter m3u8 com hash: $hash")
        
        // Baseado no exemplo: llAnfairpwllgwyngy.com (nota: domínio está escrito errado nos logs)
        val playerDomain = "https://llanfairpwllgwyngy.com"
        val playerUrl = "$playerDomain/player/index.php?data=$hash&do=getVideo"
        println("🔍 [DEBUG] Player URL: $playerUrl")
        
        val playerHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
            "Accept" to "*/*",
            "Accept-Language" to "pt-BR",
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "Origin" to playerDomain,
            "Referer" to "$playerDomain/",
            "X-Requested-With" to "XMLHttpRequest",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-origin"
        )
        
        val playerData = mapOf(
            "hash" to hash,
            "r" to ""
        )
        
        val playerResponse = app.post(playerUrl, data = playerData, headers = playerHeaders, timeout = 30)
        println("🔍 [DEBUG] Status do player: ${playerResponse.code}")
        println("🔍 [DEBUG] Resposta do player: ${playerResponse.text}")
        
        if (playerResponse.code >= 400) {
            println("❌ [DEBUG] Erro HTTP no player: ${playerResponse.code}")
            return false
        }
        
        val playerJson = JSONObject(playerResponse.text)
        
        // Extrair o link m3u8
        val m3u8Url = playerJson.optString("securedLink")
            .takeIf { it.isNotBlank() }
            ?: playerJson.optString("videoSource")
                .takeIf { it.isNotBlank() }
        
        if (m3u8Url.isNullOrBlank()) {
            println("❌ [DEBUG] Nenhum link m3u8 encontrado na resposta")
            return false
        }
        
        println("✅ [DEBUG] M3U8 URL encontrada: $m3u8Url")
        
        // Determinar qualidade
        val quality = when {
            m3u8Url.contains("1080") -> Qualities.P1080.value
            m3u8Url.contains("720") -> Qualities.P720.value
            m3u8Url.contains("480") -> Qualities.P480.value
            m3u8Url.contains("360") -> Qualities.P360.value
            else -> Qualities.P720.value
        }
        
        // Criar o ExtractorLink
        newExtractorLink(name, "SuperFlix ($quality)", m3u8Url, ExtractorLinkType.M3U8) {
            referer = "$playerDomain/"
            this.quality = quality
        }.also { 
            println("✅ [DEBUG] ExtractorLink criado com sucesso")
            callback(it) 
        }
        
        return true
        
    } catch (e: Exception) {
        println("❌ [DEBUG] Erro ao obter player hash: ${e.message}")
        e.printStackTrace()
        return false
    }
}
// Nova função para extrair de redirecionamento
private suspend fun extractFromRedirectUrl(
    redirectUrl: String,
    callback: (ExtractorLink) -> Unit
): Boolean {
    try {
        println("🔍 [DEBUG] Extraindo de URL de redirecionamento: $redirectUrl")
        
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "pt-BR",
            "Referer" to "https://superflixapi.bond/"
        )
        
        val response = app.get(redirectUrl, headers = headers, timeout = 30)
        println("🔍 [DEBUG] Status do redirecionamento: ${response.code}")
        
        if (response.code >= 400) {
            return false
        }
        
        val html = response.text
        
        // Procurar por m3u8
        val patterns = listOf(
            Regex("""["']file["']\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
            Regex("""["']sources["']\s*:\s*\[\s*\{\s*["']file["']\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
            Regex("""(https?://[^"\s<>]+\.m3u8[^"\s<>]*)""")
        )
        
        for (pattern in patterns) {
            val matches = pattern.findAll(html).toList()
            if (matches.isNotEmpty()) {
                val m3u8Url = matches[0].groupValues[1]
                println("✅ [DEBUG] m3u8 encontrado no redirecionamento: $m3u8Url")
                
                val quality = when {
                    m3u8Url.contains("1080") -> Qualities.P1080.value
                    m3u8Url.contains("720") -> Qualities.P720.value
                    m3u8Url.contains("480") -> Qualities.P480.value
                    else -> Qualities.P720.value
                }
                
                newExtractorLink(name, "SuperFlix ($quality)", m3u8Url, ExtractorLinkType.M3U8) {
                    referer = redirectUrl
                    this.quality = quality
                }.also { 
                    println("✅ [DEBUG] ExtractorLink criado")
                    callback(it) 
                }
                
                return true
            }
        }
        
        println("❌ [DEBUG] Nenhum m3u8 encontrado no redirecionamento")
        return false
        
    } catch (e: Exception) {
        println("❌ [DEBUG] Erro ao extrair do redirecionamento: ${e.message}")
        return false
    }
}

    private suspend fun extractFromHashUrl(
        videoUrl: String,
        domain: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            println("🔍 [DEBUG] Processando URL com hash: $videoUrl")
            
            // Extrair hash da URL
            val hash = when {
                videoUrl.contains("hash=") -> {
                    val pattern = Regex("hash=([^&]+)")
                    pattern.find(videoUrl)?.groupValues?.get(1)
                }
                videoUrl.contains("/m/") -> {
                    videoUrl.substringAfter("/m/").substringBefore("?")
                }
                videoUrl.contains("token=") -> {
                    val pattern = Regex("token=([^&]+)")
                    pattern.find(videoUrl)?.groupValues?.get(1)
                }
                else -> null
            }
            
            if (hash == null) {
                println("❌ [DEBUG] Não foi possível extrair hash da URL")
                return false
            }
            
            println("✅ [DEBUG] Hash extraído: $hash")
            
            // Determinar domínio do player
            val playerDomains = listOf(
                "https://llanfairpwllgwyngy.com",
                "https://warezcdn.site",
                "https://superflixapi.bond"
            )
            
            for (playerDomain in playerDomains) {
                println("🔍 [DEBUG] Tentando playerDomain: $playerDomain")
                
                // Construir URL do player baseado no tipo de hash
                val playerUrl = when {
                    videoUrl.contains("/m/") && videoUrl.contains("watchingvs") -> {
                        val encodedHash = base64EncodeUrlSafe(hash)
                        "$playerDomain/player/index.php?w=$encodedHash&do=getVideo"
                    }
                    videoUrl.contains("/m/") && videoUrl.contains("cnvs") -> {
                        val encodedHash = base64EncodeUrlSafe(hash)
                        "$playerDomain/player/index.php?c=$encodedHash&do=getVideo"
                    }
                    videoUrl.contains("/deco/") -> {
                        val encodedHash = base64EncodeUrlSafe(hash)
                        "$playerDomain/player/index.php?data=$encodedHash&do=getVideo"
                    }
                    videoUrl.contains("/guiana-brasileira/") -> {
                        val token = videoUrl.substringAfter("/guiana-brasileira/")
                        "$playerDomain/player/index.php?data=$token&do=getVideo"
                    }
                    else -> {
                        "$playerDomain/player/index.php?data=$hash&do=getVideo"
                    }
                }
                
                println("🔍 [DEBUG] Player URL construída: $playerUrl")
                
                val playerHeaders = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                    "Accept" to "*/*",
                    "Accept-Language" to "pt-BR",
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "Origin" to playerDomain,
                    "Referer" to videoUrl,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "same-origin"
                )
                
                val playerData = mapOf(
                    "hash" to hash,
                    "r" to ""
                )
                
                try {
                    val playerResponse = app.post(playerUrl, data = playerData, headers = playerHeaders, timeout = 30)
                    println("🔍 [DEBUG] Status do player: ${playerResponse.code}")
                    println("🔍 [DEBUG] Resposta do player: ${playerResponse.text}")
                    
                    if (playerResponse.code >= 400) {
                        println("❌ [DEBUG] Erro HTTP no player")
                        continue
                    }
                    
                    val playerJson = JSONObject(playerResponse.text)
                    
                    // Extrair link HLS
                    val hlsUrl = playerJson.optString("videoSource")
                        .takeIf { it.isNotBlank() }
                        ?: playerJson.optString("securedLink")
                            .takeIf { it.isNotBlank() }
                    
                    if (hlsUrl.isNullOrBlank()) {
                        println("❌ [DEBUG] Nenhum link HLS encontrado no JSON")
                        continue
                    }
                    
                    println("✅ [DEBUG] HLS URL encontrada: $hlsUrl")
                    
                    val quality = when {
                        hlsUrl.contains("1080") -> Qualities.P1080.value
                        hlsUrl.contains("720") -> Qualities.P720.value
                        hlsUrl.contains("480") -> Qualities.P480.value
                        hlsUrl.contains("360") -> Qualities.P360.value
                        else -> Qualities.P720.value
                    }
                    
                    newExtractorLink(name, "SuperFlix ($quality)", hlsUrl, ExtractorLinkType.M3U8) {
                        referer = "$playerDomain/"
                        this.quality = quality
                    }.also { 
                        println("✅ [DEBUG] ExtractorLink criado com sucesso")
                        callback(it) 
                    }
                    
                    return true
                    
                } catch (e: Exception) {
                    println("❌ [DEBUG] Erro no playerDomain $playerDomain: ${e.message}")
                    continue
                }
            }
            
            return false
            
        } catch (e: Exception) {
            println("❌ [DEBUG] Erro ao extrair de hash URL: ${e.message}")
            return false
        }
    }

    private fun base64EncodeUrlSafe(input: String): String {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(input.toByteArray())
    }

    private suspend fun extractFromPlayerUrl(
        playerUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            println("🔍 [DEBUG] Extraindo do player URL: $playerUrl")
            
            // Carregar a página do player
            val playerHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Accept-Language" to "pt-BR",
                "Referer" to "https://superflixapi.bond/",
                "Sec-Fetch-Dest" to "document",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "cross-site"
            )
            
            val response = app.get(playerUrl, headers = playerHeaders, timeout = 30)
            println("🔍 [DEBUG] Status do player: ${response.code}")
            
            if (response.code >= 400) {
                println("❌ [DEBUG] Erro HTTP no player")
                return false
            }
            
            val html = response.text
            
            // Procurar por m3u8 no HTML do player
            val patterns = listOf(
                Regex("""["']file["']\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                Regex("""["']sources["']\s*:\s*\[\s*\{\s*["']file["']\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                Regex("""(https?://[^"\s<>]+\.m3u8[^"\s<>]*)""")
            )
            
            for (pattern in patterns) {
                val matches = pattern.findAll(html).toList()
                if (matches.isNotEmpty()) {
                    val m3u8Url = matches[0].groupValues[1]
                    println("✅ [DEBUG] m3u8 encontrado: $m3u8Url")
                    
                    val quality = when {
                        m3u8Url.contains("1080") -> Qualities.P1080.value
                        m3u8Url.contains("720") -> Qualities.P720.value
                        m3u8Url.contains("480") -> Qualities.P480.value
                        else -> Qualities.P720.value
                    }
                    
                    newExtractorLink(name, "SuperFlix ($quality)", m3u8Url, ExtractorLinkType.M3U8) {
                        referer = playerUrl
                        this.quality = quality
                    }.also { 
                        println("✅ [DEBUG] ExtractorLink criado")
                        callback(it) 
                    }
                    
                    return true
                }
            }
            
            println("❌ [DEBUG] Nenhum m3u8 encontrado no player")
            return false
            
        } catch (e: Exception) {
            println("❌ [DEBUG] Erro ao extrair do player URL: ${e.message}")
            return false
        }
    }

    private suspend fun extractVideoAlternative(
        data: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔍 [DEBUG] Iniciando método alternativo de extração")
        
        // Método alternativo: buscar diretamente na página do BetterFlix
        try {
            println("🔍 [DEBUG] Carregando página: $data")
            val response = app.get(data, headers = headers, cookies = cookies, timeout = 30)
            println("🔍 [DEBUG] Status da página: ${response.code}")
            
            if (response.code >= 400) {
                println("❌ [DEBUG] Erro HTTP: ${response.code}")
                return false
            }
            
            val document = response.document
            
            // Procurar por iframes de player
            val iframes = document.select("iframe[src*='embed'], iframe[src*='player']")
            println("🔍 [DEBUG] Iframes encontrados: ${iframes.size}")
            
            iframes.forEachIndexed { index, iframe ->
                val src = iframe.attr("src")
                println("🔍 [DEBUG] Iframe $index - src: $src")
            }
            
            val iframe = iframes.firstOrNull()
            val iframeSrc = iframe?.attr("src")
            
            if (iframeSrc != null) {
                println("✅ [DEBUG] Iframe encontrado: $iframeSrc")
                return extractFromIframe(iframeSrc, callback)
            }
            
            println("⚠️ [DEBUG] Nenhum iframe encontrado, procurando por scripts com m3u8")
            
            // Procurar por scripts com m3u8
            val scripts = document.select("script")
            println("🔍 [DEBUG] Scripts encontrados: ${scripts.size}")
            
            for (script in scripts) {
                val html = script.html()
                val m3u8Pattern = Regex("""(https?://[^"\s]+\.m3u8[^"\s]*)""")
                val match = m3u8Pattern.find(html)
                if (match != null) {
                    val m3u8Url = match.groupValues[1]
                    println("✅ [DEBUG] m3u8 encontrado em script: $m3u8Url")
                    return createM3u8Link(m3u8Url, callback)
                }
            }
            
            println("❌ [DEBUG] Nenhum link m3u8 encontrado nos scripts")
            
        } catch (e: Exception) {
            println("❌ [DEBUG] Erro no método alternativo: ${e.message}")
            e.printStackTrace()
            return false
        }
        
        return false
    }

    private suspend fun extractFromIframe(
        iframeUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔍 [DEBUG] Extraindo do iframe: $iframeUrl")
        
        return try {
            val response = app.get(fixUrl(iframeUrl), headers = headers, timeout = 30)
            println("🔍 [DEBUG] Status do iframe: ${response.code}")
            
            if (response.code >= 400) {
                println("❌ [DEBUG] Erro HTTP no iframe: ${response.code}")
                return false
            }
            
            val html = response.text
            
            // Procurar por m3u8 no iframe
            val patterns = listOf(
                Regex("""file["']?\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                Regex("""src=["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                Regex("""(https?://[^"\s]+\.m3u8[^"\s]*)""")
            )
            
            patterns.forEachIndexed { index, pattern ->
                val match = pattern.find(html)
                if (match != null) {
                    val m3u8Url = match.groupValues[1]
                    println("✅ [DEBUG] m3u8 encontrado no iframe (padrão $index): $m3u8Url")
                    return createM3u8Link(m3u8Url, callback)
                }
            }
            
            println("❌ [DEBUG] Nenhum m3u8 encontrado no iframe")
            false
        } catch (e: Exception) {
            println("❌ [DEBUG] Erro ao extrair do iframe: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    private suspend fun createM3u8Link(
        m3u8Url: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔍 [DEBUG] Criando link M3U8: $m3u8Url")
        
        return try {
            // Gerar múltiplas qualidades
            println("🔍 [DEBUG] Gerando qualidades com M3u8Helper")
            val links = M3u8Helper.generateM3u8(
                source = name,
                streamUrl = m3u8Url,
                referer = mainUrl,
                headers = headers
            )
            
            if (links.isNotEmpty()) {
                println("✅ [DEBUG] ${links.size} link(s) gerado(s) pelo M3u8Helper")
                links.forEachIndexed { index, link ->
                    println("🔍 [DEBUG] Link $index - Qualidade: ${link.quality}, URL: ${link.url}")
                }
                links.forEach { callback(it) }
                true
            } else {
                println("⚠️ [DEBUG] M3u8Helper não gerou links, criando link direto")
                newExtractorLink(name, "Video", m3u8Url, ExtractorLinkType.M3U8) {
                    referer = mainUrl
                    quality = Qualities.P720.value
                }.also { 
                    println("✅ [DEBUG] Link direto criado")
                    callback(it) 
                }
                true
            }
        } catch (e: Exception) {
            println("❌ [DEBUG] Erro ao criar link M3U8: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    // Funções auxiliares para extração de informações da página
    private fun extractTitle(document: org.jsoup.nodes.Document): String? {
        return document.selectFirst("h1, .title, .movie-title, .tv-title, .anime-title")?.text()?.trim()
    }

    private fun extractOverview(document: org.jsoup.nodes.Document): String? {
        return document.selectFirst(".overview, .synopsis, .plot, .description")?.text()?.trim()
    }

    private fun extractPoster(document: org.jsoup.nodes.Document): String? {
        return document.selectFirst(".poster img, .movie-poster img, .tv-poster img")?.attr("src")?.let { fixUrl(it) } ?:
               document.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }
    }

    private fun extractBackdrop(document: org.jsoup.nodes.Document): String? {
        return document.selectFirst(".backdrop, .background-image")?.attr("style")?.let { 
            Regex("url\\(['\"]?(.*?)['\"]?\\)").find(it)?.groupValues?.get(1)?.let { fixUrl(it) }
        } ?: extractPoster(document)
    }

    private fun extractGenres(document: org.jsoup.nodes.Document): List<String> {
        return document.select(".genres .genre, .tags .tag, .categories .category").map { it.text().trim() }
    }

    private fun extractActors(document: org.jsoup.nodes.Document): List<Actor> {
        val actors = mutableListOf<Actor>()
        
        document.select(".cast .actor, .actors .actor").forEach { element ->
            try {
                val name = element.selectFirst(".name, .actor-name")?.text()?.trim() ?: return@forEach
                val character = element.selectFirst(".character, .role")?.text()?.trim() ?: ""
                val image = element.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                
                actors.add(Actor(name, image))
            } catch (e: Exception) {
                // Ignorar erro
            }
        }
        
        return actors
    }

    private fun extractTrailer(document: org.jsoup.nodes.Document): String? {
        val iframe = document.selectFirst("iframe[src*='youtube'], iframe[src*='youtu.be']")
        if (iframe != null) {
            val src = iframe.attr("src")
            val youtubeId = Regex("(?:youtube\\.com\\/embed\\/|youtu\\.be\\/)([^?&]+)").find(src)?.groupValues?.get(1)
            return youtubeId
        }
        
        val trailerLink = document.selectFirst("a[href*='youtube'], a[href*='youtu.be']")
        trailerLink?.attr("href")?.let { href ->
            val youtubeId = Regex("(?:youtube\\.com\\/watch\\?v=|youtu\\.be\\/)([^?&]+)").find(href)?.groupValues?.get(1)
            return youtubeId
        }
        
        return null
    }

    private fun extractDuration(document: org.jsoup.nodes.Document): Int? {
        val durationText = document.selectFirst(".duration, .runtime, .time")?.text()?.trim()
        if (durationText != null) {
            val minutesMatch = Regex("(\\d+)\\s*min").find(durationText)
            if (minutesMatch != null) {
                return minutesMatch.groupValues[1].toIntOrNull()
            }
            
            val hoursMatch = Regex("(\\d+)\\s*h").find(durationText)
            val minsMatch = Regex("(\\d+)\\s*m").find(durationText)
            
            val hours = hoursMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val minutes = minsMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            
            return (hours * 60) + minutes
        }
        return null
    }

    private fun extractEpisodes(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        document.select(".episode, .episode-item, .season-episode").forEach { element ->
            try {
                val epNumber = element.selectFirst(".episode-number, .number")?.text()?.toIntOrNull() ?: 
                              Regex("Ep\\.?\\s*(\\d+)").find(element.text())?.groupValues?.get(1)?.toIntOrNull() ?:
                              return@forEach
                
                val seasonNumber = element.selectFirst(".season-number")?.text()?.toIntOrNull() ?: 1
                val title = element.selectFirst(".episode-title, .title")?.text()?.trim() ?: "Episódio $epNumber"
                val description = element.selectFirst(".description, .overview")?.text()?.trim()
                val poster = element.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                
                val epUrl = element.attr("href") ?: element.attr("data-url") ?: 
                           "$baseUrl&season=$seasonNumber&episode=$epNumber"
                
                val episode = newEpisode(fixUrl(epUrl)) {
                    this.name = title
                    this.season = seasonNumber
                    this.episode = epNumber
                    this.description = description
                    this.posterUrl = poster
                }
                
                episodes.add(episode)
            } catch (e: Exception) {
                // Ignorar episódio com erro
            }
        }
        
        return episodes
    }
}

// Função de extensão para codificar query
private fun String.encodeSearchQuery(): String {
    return java.net.URLEncoder.encode(this, "UTF-8")
}
