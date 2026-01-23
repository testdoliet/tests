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
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
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

    data class EmbeddedData(
        val id: String? = null,
        val name: String? = null,
        val date: String? = null,
        val bio: String? = null,
        val inProduction: Boolean? = null,
        val vote: Double? = null,
        val genres: String? = null,
        val poster: String? = null,
        val backdrop: String? = null
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

    // ========== LOAD() TOTALMENTE CORRIGIDO ==========
    override suspend fun load(url: String): LoadResponse? {
        println("🔍 [DEBUG] === LOAD() INICIADO ===")
        println("🔍 [DEBUG] URL recebida: $url")
        
        return safeApiRequest(url) {
            try {
                // 1. CARREGAR PÁGINA DE DETALHES DO BETTERFLIX
                println("🔍 [DEBUG] Carregando página do BetterFlix...")
                val response = app.get(url, headers = headers, cookies = cookies, timeout = 30)
                println("🔍 [DEBUG] Status da página: ${response.code}")
                
                if (response.code >= 400) {
                    println("❌ [DEBUG] Erro HTTP: ${response.code}")
                    return@safeApiRequest null
                }
                
                val document = response.document
                val html = response.text
                println("🔍 [DEBUG] Tamanho do HTML: ${html.length} caracteres")
                
                // 2. EXTRAIR DADOS DO OBJETO JSON EMBUTIDO (dadosMulti)
                val embeddedData = extractEmbeddedData(html)
                if (embeddedData == null) {
                    println("❌ [DEBUG] Não encontrou dadosMulti no HTML")
                    println("🔍 [DEBUG] HTML sample (primeiros 500 chars): ${html.take(500)}")
                    return@safeApiRequest null
                }
                
                println("✅ [DEBUG] Dados extraídos:")
                println("  - Nome: ${embeddedData.name}")
                println("  - ID: ${embeddedData.id}")
                println("  - Data: ${embeddedData.date}")
                println("  - Gêneros: ${embeddedData.genres}")
                
                // 3. DETERMINAR TIPO
                val tmdbId = embeddedData.id ?: extractTmdbIdFromUrl(url)
                val isSeries = url.contains("type=tv")
                val isAnime = url.contains("type=anime")
                val isMovie = !isSeries && !isAnime
                
                println("🔍 [DEBUG] Tipo detectado:")
                println("  - TMDB ID: $tmdbId")
                println("  - É Série: $isSeries")
                println("  - É Anime: $isAnime")
                println("  - É Filme: $isMovie")
                
                // 4. SE FOR SÉRIE/ANIME, EXTRAIR EPISÓDIOS DO SUPERFLIX
                if (isSeries || isAnime) {
                    val type = if (isAnime) TvType.Anime else TvType.TvSeries
                    println("🔍 [DEBUG] Extraindo episódios para $type...")
                    val episodes = extractEpisodesFromSuperflix(tmdbId, url)
                    
                    println("✅ [DEBUG] Encontrou ${episodes.size} episódios")
                    
                    newTvSeriesLoadResponse(embeddedData.name ?: "Sem título", url, type, episodes) {
                        this.posterUrl = embeddedData.poster?.let { fixUrl(it) }
                        this.backgroundPosterUrl = embeddedData.backdrop?.let { fixUrl(it) }
                        this.year = embeddedData.date?.substring(0, 4)?.toIntOrNull()
                        this.plot = embeddedData.bio
                        this.tags = embeddedData.genres?.split(",")?.map { it.trim() } ?: emptyList()
                        
                        // Extrair duração se disponível
                        val duration = extractDuration(document)
                        this.duration = duration
                        
                        // Extrair atores e trailer (mantém suas funções originais)
                        val actors = extractActors(document)
                        if (actors.isNotEmpty()) {
                            println("✅ [DEBUG] Encontrou ${actors.size} atores")
                            addActors(actors)
                        }
                        
                        val trailerKey = extractTrailer(document)
                        if (trailerKey != null) {
                            println("✅ [DEBUG] Encontrou trailer: $trailerKey")
                            addTrailer(trailerKey)
                        }
                    }
                } else {
                    // PARA FILMES
                    println("🔍 [DEBUG] Criando resposta para filme...")
                    newMovieLoadResponse(embeddedData.name ?: "Sem título", url, TvType.Movie, url) {
                        this.posterUrl = embeddedData.poster?.let { fixUrl(it) }
                        this.backgroundPosterUrl = embeddedData.backdrop?.let { fixUrl(it) }
                        this.year = embeddedData.date?.substring(0, 4)?.toIntOrNull()
                        this.plot = embeddedData.bio
                        this.tags = embeddedData.genres?.split(",")?.map { it.trim() } ?: emptyList()
                        
                        val duration = extractDuration(document)
                        this.duration = duration
                        
                        val actors = extractActors(document)
                        if (actors.isNotEmpty()) {
                            println("✅ [DEBUG] Encontrou ${actors.size} atores")
                            addActors(actors)
                        }
                        
                        val trailerKey = extractTrailer(document)
                        if (trailerKey != null) {
                            println("✅ [DEBUG] Encontrou trailer: $trailerKey")
                            addTrailer(trailerKey)
                        }
                    }
                }
            } catch (e: Exception) {
                println("❌ [DEBUG] Erro no load(): ${e.message}")
                e.printStackTrace()
                null
            }
        }
    }

    // ========== FUNÇÕES AUXILIARES DO LOAD ==========

    data class EpisodeData(
        val ID: Int,
        val title: String,
        val sinopse: String,
        val item: Int,
        val thumb_url: String?,
        val air_date: String?,
        val duration: Int,
        val epi_num: Int,
        val season: Int
    )

    data class AllEpisodesResponse(
        val episodes: Map<String, List<EpisodeData>>
    )

    // EXTRAIR EPISÓDIOS DO SUPERFLIX (CORRETO!)
    private suspend fun extractEpisodesFromSuperflix(tmdbId: String?, baseUrl: String): List<Episode> {
    val episodes = mutableListOf<Episode>()
    
    if (tmdbId == null) {
        println("❌ [DEBUG] TMDB ID não encontrado")
        return episodes
    }
    
    try {
        println("🔍 [DEBUG] === EXTRACAO DE EPISÓDIOS INICIADA ===")
        println("🔍 [DEBUG] Buscando episódios para TMDB ID: $tmdbId")
        
        // Fazer requisição para o SuperFlix para obter lista de episódios
        val superflixUrl = "https://superflixapi.bond/serie/$tmdbId/1/1"
        println("🔍 [DEBUG] URL do SuperFlix: $superflixUrl")
        
        val superflixHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "pt-BR",
            "Referer" to "https://betterflix.vercel.app/",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site"
        )
        
        println("🔍 [DEBUG] Fazendo requisição ao SuperFlix...")
        val response = app.get(superflixUrl, headers = superflixHeaders, timeout = 30)
        println("🔍 [DEBUG] Status SuperFlix: ${response.code}")
        
        if (response.code >= 400) {
            println("❌ [DEBUG] Erro ao carregar SuperFlix: ${response.code}")
            return episodes
        }
        
        val html = response.text
        println("✅ [DEBUG] HTML recebido (${html.length} caracteres)")
        
        // SALVAR O HTML COMPLETO PARA DEBUG
        println("🔍 [DEBUG] === HTML COMPLETO (PRIMEIROS 5000 CHARS) ===")
        println(html.take(5000))
        println("🔍 [DEBUG] === FIM DO HTML ===")
        
        // DEBUG: Procurar por padrões específicos
        debugHtmlAnalysis(html)
        
        // Extrair objeto ALL_EPISODES
        println("🔍 [DEBUG] Extraindo objeto ALL_EPISODES...")
        val allEpisodesData = extractAllEpisodesData(html)
        if (allEpisodesData == null) {
            println("❌ [DEBUG] Não encontrou ALL_EPISODES no SuperFlix")
            
            // Fallback: tentar extrair de outra forma
            println("🔍 [DEBUG] Tentando fallback de extração...")
            val fallbackEpisodes = extractEpisodesFallback(html, tmdbId, baseUrl)
            println("✅ [DEBUG] Fallback encontrou ${fallbackEpisodes.size} episódios")
            return fallbackEpisodes
        }
        
        println("✅ [DEBUG] Encontrou ${allEpisodesData.size} temporada(s)")
        
        // Processar todas as temporadas e episódios...
        // ... restante do código
        
    } catch (e: Exception) {
        println("❌ [DEBUG] Erro ao extrair episódios do SuperFlix: ${e.message}")
        e.printStackTrace()
    }
    
    println("✅ [DEBUG] Total de episódios extraídos: ${episodes.size}")
    return episodes
}

// Nova função para análise detalhada do HTML
private fun debugHtmlAnalysis(html: String) {
    println("🔍 [DEBUG] === ANÁLISE DETALHADA DO HTML ===")
    
    // 1. Verificar se há algum script
    val scriptTags = Regex("""<script[^>]*>.*?</script>""", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
    println("✅ [DEBUG] Encontrou ${scriptTags.size} tags <script>")
    
    // 2. Procurar por qualquer menção a "episode" ou "episodio"
    val episodeMentions = html.count { charSequence -> 
        "episode".contains(charSequence.toString(), ignoreCase = true) ||
        "episodio".contains(charSequence.toString(), ignoreCase = true)
    }
    println("🔍 [DEBUG] Menções a 'episode/episodio': $episodeMentions")
    
    // 3. Procurar por padrões específicos que possam conter dados de episódios
    val patternsToCheck = listOf(
        "ALL_EPISODES",
        "var.*episode",
        "episodes.*=",
        "\\[\\{.*title.*:",
        "\"episodes\"",
        "season.*:",
        "epi_num",
        "air_date"
    )
    
    patternsToCheck.forEach { pattern ->
        if (html.contains(pattern, ignoreCase = true)) {
            println("✅ [DEBUG] Encontrou padrão: $pattern")
            
            // Mostrar contexto
            val index = html.indexOf(pattern, ignoreCase = true)
            if (index != -1) {
                val start = maxOf(0, index - 50)
                val end = minOf(index + 200, html.length)
                val context = html.substring(start, end)
                println("🔍 [DEBUG] Contexto: ...$context...")
            }
        } else {
            println("❌ [DEBUG] NÃO encontrou padrão: $pattern")
        }
    }
    
    // 4. Procurar por estruturas JSON
    val jsonPatterns = listOf(
        Regex("""\{.*"1".*:.*\[.*\}""", RegexOption.DOT_MATCHES_ALL),
        Regex("""\[.*\{.*title.*:.*\}.*\]""", RegexOption.DOT_MATCHES_ALL),
        Regex("""var\s+\w+\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
    )
    
    jsonPatterns.forEachIndexed { i, pattern ->
        val match = pattern.find(html)
        if (match != null) {
            println("✅ [DEBUG] Encontrou JSON com padrão $i")
            val jsonText = match.groupValues.getOrNull(1) ?: match.value
            println("🔍 [DEBUG] JSON (primeiros 300 chars): ${jsonText.take(300)}...")
        }
    }
    
    // 5. Verificar se há dados em variáveis JavaScript
    val jsVars = Regex("""var\s+(\w+)\s*=\s*([^;]+);""").findAll(html).toList()
    println("✅ [DEBUG] Encontrou ${jsVars.size} variáveis JavaScript")
    jsVars.take(10).forEach { match ->
        val varName = match.groupValues[1]
        val varValue = match.groupValues[2].trim()
        println("🔍 [DEBUG] Variável: $varName = ${varValue.take(100)}...")
    }
    
    println("🔍 [DEBUG] === FIM DA ANÁLISE ===")
}

// Atualize também a função debugExtractEpisodes para ser mais abrangente:
private fun debugExtractEpisodes(html: String) {
    println("🔍 [DEBUG] === DEBUG DE EXTRACAO DE EPISÓDIOS ===")
    
    // Salvar uma cópia do HTML para análise
    val debugFile = html.take(3000) // Primeiros 3000 chars
    println("🔍 [DEBUG] HTML (primeiros 3000 chars):")
    println("--- INÍCIO HTML ---")
    println(debugFile)
    println("--- FIM HTML ---")
    
    // Procurar por "ALL_EPISODES" de forma mais flexível
    val allEpisodesRegex = Regex("""(ALL_EPISODES|all_episodes|All_Episodes)""")
    val allEpisodesMatch = allEpisodesRegex.find(html)
    
    if (allEpisodesMatch != null) {
        println("✅ [DEBUG] Encontrou '${allEpisodesMatch.value}'")
        val start = maxOf(0, allEpisodesMatch.range.first - 20)
        val end = minOf(allEpisodesMatch.range.last + 500, html.length)
        val context = html.substring(start, end)
        println("🔍 [DEBUG] Contexto: $context")
    } else {
        println("❌ [DEBUG] NÃO encontrou nenhuma variação de 'ALL_EPISODES' no HTML")
    }
    
    // Procurar por qualquer JSON que pareça dados de episódios
    val jsonRegex = Regex("""\{(?:\s*"[^"]+"\s*:\s*(?:[^,{]|\[[^]]*\]|\{[^}]*\})*,?)+\}""")
    val jsonMatches = jsonRegex.findAll(html).toList()
    println("✅ [DEBUG] Encontrou ${jsonMatches.size} objetos JSON potenciais")
    
    jsonMatches.take(5).forEachIndexed { i, match ->
        val jsonText = match.value
        if (jsonText.contains("title") || jsonText.contains("episode") || jsonText.contains("season")) {
            println("✅ [DEBUG] JSON $i parece conter dados de episódios")
            println("🔍 [DEBUG] JSON $i (${jsonText.length} chars): ${jsonText.take(200)}...")
        }
    }
    
    println("🔍 [DEBUG] === FIM DO DEBUG ===")
}

    // EXTRAIR OBJETO ALL_EPISODES DO HTML DO SUPERFLIX
    private fun extractAllEpisodesData(html: String): Map<String, List<EpisodeData>>? {
        try {
            println("🔍 [DEBUG] === EXTRACAÇÃO DE ALL_EPISODES ===")
            
            // VERSÃO 1: Padrão mais flexível
            val pattern1 = Regex("""var\s+ALL_EPISODES\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
            println("🔍 [DEBUG] Tentando padrão 1...")
            
            val match1 = pattern1.find(html)
            if (match1 != null) {
                println("✅ [DEBUG] Padrão 1 encontrou match!")
                val jsonString = match1.groupValues[1].trim()
                println("🔍 [DEBUG] JSON extraído (primeiros 200 chars): ${jsonString.take(200)}...")
                return parseEpisodesJson(jsonString)
            }
            
            // VERSÃO 2: Padrão alternativo
            val pattern2 = Regex("""ALL_EPISODES\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL)
            println("🔍 [DEBUG] Tentando padrão 2...")
            
            val match2 = pattern2.find(html)
            if (match2 != null) {
                println("✅ [DEBUG] Padrão 2 encontrou match!")
                val jsonString = match2.groupValues[1].trim()
                println("🔍 [DEBUG] JSON extraído (primeiros 200 chars): ${jsonString.take(200)}...")
                return parseEpisodesJson(jsonString)
            }
            
            // VERSÃO 3: Procura direta pelo JSON
            println("🔍 [DEBUG] Tentando padrão 3 (busca direta)...")
            val jsonMatch = findJsonDirectly(html)
            if (jsonMatch != null) {
                println("✅ [DEBUG] Encontrou JSON diretamente!")
                return parseEpisodesJson(jsonMatch)
            }
            
            println("❌ [DEBUG] Nenhum padrão encontrou ALL_EPISODES")
            return null
            
        } catch (e: Exception) {
            println("❌ [DEBUG] Erro ao extrair ALL_EPISODES: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    private fun findJsonDirectly(html: String): String? {
        try {
            // Procura pelo início do JSON
            val startIndex = html.indexOf("""{"1":""")
            if (startIndex == -1) {
                println("❌ [DEBUG] Não encontrou início do JSON '{\"1\":'")
                return null
            }
            
            println("✅ [DEBUG] Encontrou início do JSON na posição $startIndex")
            
            // Encontra o fim do objeto JSON
            var braceCount = 0
            var i = startIndex
            var foundEnd = false
            
            while (i < html.length) {
                when (html[i]) {
                    '{' -> braceCount++
                    '}' -> {
                        braceCount--
                        if (braceCount == 0) {
                            foundEnd = true
                            break
                        }
                    }
                }
                i++
            }
            
            if (!foundEnd) {
                println("❌ [DEBUG] Não conseguiu encontrar fim do JSON")
                return null
            }
            
            val jsonString = html.substring(startIndex, i + 1)
            println("✅ [DEBUG] JSON extraído (${jsonString.length} chars)")
            return jsonString
            
        } catch (e: Exception) {
            println("❌ [DEBUG] Erro na busca direta: ${e.message}")
            return null
        }
    }

    private fun parseEpisodesJson(jsonString: String): Map<String, List<EpisodeData>>? {
        try {
            println("🔍 [DEBUG] Parseando JSON...")
            val jsonObject = JSONObject(jsonString)
            val result = mutableMapOf<String, List<EpisodeData>>()
            
            jsonObject.keys().forEach { seasonKey ->
                println("🔍 [DEBUG] Processando temporada: $seasonKey")
                val episodesArray = jsonObject.getJSONArray(seasonKey)
                val episodesList = mutableListOf<EpisodeData>()
                
                println("🔍 [DEBUG] Temporada $seasonKey tem ${episodesArray.length()} episódios")
                
                for (i in 0 until episodesArray.length()) {
                    try {
                        val episodeObj = episodesArray.getJSONObject(i)
                        
                        // Log dos campos para debug
                        println("🔍 [DEBUG] Episódio $i campos:")
                        episodeObj.keys().forEach { key ->
                            println("  - $key: ${episodeObj.opt(key)}")
                        }
                        
                        episodesList.add(
                            EpisodeData(
                                ID = episodeObj.optInt("ID"),
                                title = episodeObj.optString("title"),
                                sinopse = episodeObj.optString("sinopse"),
                                item = episodeObj.optInt("item"),
                                thumb_url = episodeObj.optString("thumb_url").takeIf { 
                                    it != "null" && it.isNotBlank() && it != "null" 
                                },
                                air_date = episodeObj.optString("air_date").takeIf { 
                                    it != "null" && it.isNotBlank() && it != "null" 
                                },
                                duration = episodeObj.optInt("duration"),
                                epi_num = episodeObj.optInt("epi_num"),
                                season = episodeObj.optInt("season")
                            )
                        )
                        
                        println("✅ [DEBUG] Episódio $i parseado: ${episodeObj.optString("title")}")
                    } catch (e: Exception) {
                        println("❌ [DEBUG] Erro ao parsear episódio $i: ${e.message}")
                    }
                }
                
                result[seasonKey] = episodesList
            }
            
            println("✅ [DEBUG] Parse concluído: ${result.size} temporadas")
            return result
            
        } catch (e: Exception) {
            println("❌ [DEBUG] Erro ao parsear JSON: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    // FUNÇÃO FALLBACK PARA EXTRACAÇÃO DE EPISÓDIOS
    private fun extractEpisodesFallback(html: String, tmdbId: String, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        try {
            println("🔍 [DEBUG] === FALLBACK DE EXTRACAÇÃO INICIADO ===")
            
            // Tentar extrair do HTML diretamente usando Jsoup
            val document = Jsoup.parse(html)
            
            // Método 1: Procurar por elementos de episódio
            val episodeElements = document.select(".episode-item, [class*='episode'], .episode")
            
            if (episodeElements.isNotEmpty()) {
                println("✅ [DEBUG] Encontrou ${episodeElements.size} elementos .episode-item")
                
                episodeElements.forEachIndexed { index, element ->
                    try {
                        // Extrair título
                        val titleElement = element.selectFirst(".ep-title, .title, h3, h4")
                        var title = titleElement?.text() ?: "Episódio ${index + 1}"
                        
                        // Extrair número do episódio
                        val epNumberMatch = Regex("""Episódio\s+(\d+)""").find(title)
                        val epNumber = epNumberMatch?.groupValues?.get(1)?.toIntOrNull() ?: (index + 1)
                        
                        // Extrair thumbnail
                        val thumbElement = element.selectFirst("img")
                        val thumbUrl = thumbElement?.attr("src")?.let { 
                            if (it.startsWith("/")) "https://image.tmdb.org/t/p/w300$it" else it 
                        }
                        
                        // Extrair data
                        val dateElement = element.selectFirst(".ep-meta, .date, .air-date")
                        val airDate = dateElement?.text()
                        
                        val episodeUrl = "$baseUrl&season=1&episode=$epNumber"
                        
                        episodes.add(
                            newEpisode(episodeUrl) {
                                this.name = title
                                this.season = 1
                                this.episode = epNumber
                                this.description = airDate
                                this.posterUrl = thumbUrl?.let { fixUrl(it) }
                            }
                        )
                        
                        println("✅ [DEBUG] Fallback - Episódio adicionado: E$epNumber - $title")
                    } catch (e: Exception) {
                        println("❌ [DEBUG] Erro no fallback episódio $index: ${e.message}")
                    }
                }
            } else {
                println("❌ [DEBUG] Nenhum elemento .episode-item encontrado no fallback")
                
                // Método 2: Procurar por scripts
                val scripts = document.select("script")
                println("🔍 [DEBUG] Analisando ${scripts.size} scripts...")
                
                scripts.forEachIndexed { index, script ->
                    val scriptContent = script.html()
                    if (scriptContent.contains("ALL_EPISODES") || scriptContent.contains("episode")) {
                        println("🔍 [DEBUG] Script $index pode conter dados de episódios")
                        println("🔍 [DEBUG] Conteúdo (primeiros 300 chars): ${scriptContent.take(300)}")
                    }
                }
            }
            
        } catch (e: Exception) {
            println("❌ [DEBUG] Erro no fallback: ${e.message}")
            e.printStackTrace()
        }
        
        println("✅ [DEBUG] Fallback retornou ${episodes.size} episódios")
        return episodes
    }

    // FUNÇÃO PARA EXTRAIR OBJETO JSON EMBUTIDO (dadosMulti)
    private fun extractEmbeddedData(html: String): EmbeddedData? {
        try {
            println("🔍 [DEBUG] === EXTRACÃO DE DADOS MULTI ===")
            
            // Procura pelo objeto dadosMulti no script
            val pattern = Regex("""const dadosMulti\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
            val match = pattern.find(html)
            
            if (match != null) {
                val jsonString = match.groupValues[1]
                println("✅ [DEBUG] Encontrou dadosMulti!")
                println("🔍 [DEBUG] JSON: $jsonString")
                return AppUtils.tryParseJson<EmbeddedData>(jsonString)
            }
            
            // Se não encontrou, tenta extrair manualmente
            println("⚠️ [DEBUG] Não encontrou padrão dadosMulti, tentando extração manual")
            return extractEmbeddedDataManually(html)
        } catch (e: Exception) {
            println("❌ [DEBUG] Erro ao extrair embedded data: ${e.message}")
            return null
        }
    }

    private fun extractEmbeddedDataManually(html: String): EmbeddedData? {
        try {
            println("🔍 [DEBUG] Extraindo dados manualmente...")
            
            fun extract(pattern: String): String? {
                val regex = Regex(pattern, RegexOption.DOT_MATCHES_ALL)
                return regex.find(html)?.groupValues?.get(1)
            }
            
            val id = extract("\"id\"\\s*:\\s*\"([^\"]+)\"") ?: extract("'id'\\s*:\\s*'([^']+)'")
            val name = extract("\"name\"\\s*:\\s*\"([^\"]+)\"") ?: extract("'name'\\s*:\\s*'([^']+)'")
            val date = extract("\"date\"\\s*:\\s*\"([^\"]+)\"") ?: extract("'date'\\s*:\\s*'([^']+)'")
            val bio = extract("\"bio\"\\s*:\\s*\"([^\"]+)\"") ?: extract("'bio'\\s*:\\s*'([^']+)'")
            
            println("🔍 [DEBUG] Dados extraídos:")
            println("  - ID: $id")
            println("  - Nome: $name")
            println("  - Data: $date")
            println("  - Bio: ${bio?.take(50)}...")
            
            return EmbeddedData(
                id = id,
                name = name,
                date = date,
                bio = bio,
                inProduction = extract("\"inProduction\"\\s*:\\s*(true|false)")?.toBoolean(),
                vote = extract("\"vote\"\\s*:\\s*([0-9.]+)")?.toDoubleOrNull(),
                genres = extract("\"genres\"\\s*:\\s*\"([^\"]+)\"") ?: extract("'genres'\\s*:\\s*'([^']+)'"),
                poster = extract("\"poster\"\\s*:\\s*\"([^\"]+)\"") ?: extract("'poster'\\s*:\\s*'([^']+)'"),
                backdrop = extract("\"backdrop\"\\s*:\\s*\"([^\"]+)\"") ?: extract("'backdrop'\\s*:\\s*'([^']+)'")
            )
        } catch (e: Exception) {
            println("❌ [DEBUG] Erro na extração manual: ${e.message}")
            return null
        }
    }

    private fun extractTmdbIdFromUrl(url: String): String? {
        val idMatch = Regex("[?&]id=(\\d+)").find(url)
        return idMatch?.groupValues?.get(1)
    }

    // ========== LOAD LINKS (MANTENDO SEU CÓDIGO ORIGINAL) ==========
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔍 [DEBUG] === LOAD LINKS INICIADO ===")
        println("🔍 [DEBUG] Data recebida: $data")
        println("🔍 [DEBUG] isCasting: $isCasting")
        
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
            println("🔍 [DEBUG] === EXTRACÇÃO DE VÍDEO DO SUPERFLIX ===")
            println("🔍 [DEBUG] Domínio: $domain")
            println("🔍 [DEBUG] TMDB ID: $tmdbId")
            println("🔍 [DEBUG] Tipo: $type")
            
            // Lista de video_ids possíveis
            val possibleVideoIds = listOf("303309", "351944", "1", "2", "3")
            
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
                    println("🔍 [DEBUG] Resposta da API (primeiros 500 chars): ${apiResponse.text.take(500)}...")
                    
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
        println("🔍 [DEBUG] Extraindo hash da URL: $videoUrl")
        
        return when {
            // Exemplo: https://llanfairpwllgwyngy.com/video/a269ba2de7c47692cce1956aca54f22d
            videoUrl.contains("/video/") -> {
                val hash = videoUrl.substringAfter("/video/").substringBefore("?")
                println("✅ [DEBUG] Hash extraído (padrão /video/): $hash")
                hash
            }
            // Exemplo: https://play-utx.playmycnvs.com/m/Pecadores.2025.1080p.WEB-DL.DUAL.5.1.mp4
            videoUrl.contains("/m/") -> {
                val hash = videoUrl.substringAfter("/m/").substringBefore("?")
                println("✅ [DEBUG] Hash extraído (padrão /m/): $hash")
                hash
            }
            // Exemplo: token=xyz
            videoUrl.contains("token=") -> {
                val pattern = Regex("token=([^&]+)")
                val match = pattern.find(videoUrl)
                val hash = match?.groupValues?.get(1)
                println("✅ [DEBUG] Hash extraído (padrão token=): $hash")
                hash
            }
            else -> {
                println("❌ [DEBUG] Não reconheceu padrão na URL")
                null
            }
        }
    }

    private suspend fun requestPlayerHash(
        hash: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            println("🔍 [DEBUG] === REQUISIÇÃO DE PLAYER HASH ===")
            println("🔍 [DEBUG] Hash recebido: $hash")
            
            // Baseado no exemplo: llAnfairpwllgwyngy.com
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
            
            println("🔍 [DEBUG] Enviando requisição POST...")
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
                println("🔍 [DEBUG] Campos disponíveis no JSON:")
                playerJson.keys().forEach { key ->
                    println("  - $key: ${playerJson.opt(key)}")
                }
                return false
            }
            
            println("✅ [DEBUG] M3U8 URL encontrada: $m3u8Url")
            
            // Determinar qualidade
            val quality = when {
                m3u8Url.contains("1080") -> Qualities.P1080.value
                m3u8Url.contains("720") -> Qualities.P720.value
                m3u8Url.contains("480") -> Qualities.P480.value
                m3u8Url.contains("360") -> Qualities.P360.value
                else -> {
                    println("⚠️ [DEBUG] Qualidade não detectada, usando 720p como padrão")
                    Qualities.P720.value
                }
            }
            
            println("✅ [DEBUG] Qualidade detectada: $quality")
            
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
        println("🔍 [DEBUG] === MÉTODO ALTERNATIVO DE EXTRAÇÃO ===")
        
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
}

// Função de extensão para codificar query
private fun String.encodeSearchQuery(): String {
    return java.net.URLEncoder.encode(this, "UTF-8")
}
