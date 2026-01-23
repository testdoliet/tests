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

    // ========== LOAD() ==========
    override suspend fun load(url: String): LoadResponse? {
        return safeApiRequest(url) {
            try {
                // 1. CARREGAR PÁGINA DE DETALHES DO BETTERFLIX
                val response = app.get(url, headers = headers, cookies = cookies, timeout = 30)
                if (response.code >= 400) return@safeApiRequest null
                
                val document = response.document
                val html = response.text
                
                // 2. EXTRAIR DADOS DO OBJETO JSON EMBUTIDO (dadosMulti)
                val embeddedData = extractEmbeddedData(html)
                if (embeddedData == null) {
                    return@safeApiRequest null
                }
                
                // 3. DETERMINAR TIPO
                val tmdbId = embeddedData.id ?: extractTmdbIdFromUrl(url)
                val isSeries = url.contains("type=tv")
                val isAnime = url.contains("type=anime")
                val isMovie = !isSeries && !isAnime
                
                // 4. SE FOR SÉRIE/ANIME, EXTRAIR EPISÓDIOS DO SUPERFLIX
                if (isSeries || isAnime) {
                    val type = if (isAnime) TvType.Anime else TvType.TvSeries
                    val episodes = extractEpisodesFromSuperflix(tmdbId, url)
                    
                    println("✅ [EPISODIOS] Total extraído: ${episodes.size} episódios")
                    
                    newTvSeriesLoadResponse(embeddedData.name ?: "Sem título", url, type, episodes) {
                        this.posterUrl = embeddedData.poster?.let { fixUrl(it) }
                        this.backgroundPosterUrl = embeddedData.backdrop?.let { fixUrl(it) }
                        this.year = embeddedData.date?.substring(0, 4)?.toIntOrNull()
                        this.plot = embeddedData.bio
                        this.tags = embeddedData.genres?.split(",")?.map { it.trim() } ?: emptyList()
                    }
                } else {
                    // PARA FILMES
                    newMovieLoadResponse(embeddedData.name ?: "Sem título", url, TvType.Movie, url) {
                        this.posterUrl = embeddedData.poster?.let { fixUrl(it) }
                        this.backgroundPosterUrl = embeddedData.backdrop?.let { fixUrl(it) }
                        this.year = embeddedData.date?.substring(0, 4)?.toIntOrNull()
                        this.plot = embeddedData.bio
                        this.tags = embeddedData.genres?.split(",")?.map { it.trim() } ?: emptyList()
                    }
                }
            } catch (e: Exception) {
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

    // ========== LOGS DETALHADOS NA EXTRAÇÃO DE EPISÓDIOS ==========
    
    // EXTRAIR EPISÓDIOS DO SUPERFLIX COM LOGS DETALHADOS
    private suspend fun extractEpisodesFromSuperflix(tmdbId: String?, baseUrl: String): List<Episode> {
    println("🔍 [EPISODIOS] ===== INICIANDO EXTRAÇÃO DE EPISÓDIOS =====")
    println("🔍 [EPISODIOS] TMDB ID: $tmdbId")
    
    val episodes = mutableListOf<Episode>()
    
    if (tmdbId == null) {
        println("❌ [EPISODIOS] TMDB ID não encontrado")
        return episodes
    }
    
    // Headers específicos para o SuperFlix (baseado no seu curl)
    val superflixHeaders = mapOf(
        "accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "accept-language" to "pt-BR",
        "priority" to "u=0, i",
        "referer" to "https://betterflix.vercel.app/",
        "sec-ch-ua" to "\"Chromium\";v=\"127\", \"Not)A;Brand\";v=\"99\", \"Microsoft Edge Simulate\";v=\"127\", \"Lemur\";v=\"127\"",
        "sec-ch-ua-mobile" to "?1",
        "sec-ch-ua-platform" to "\"Android\"",
        "sec-fetch-dest" to "iframe",
        "sec-fetch-mode" to "navigate",
        "sec-fetch-site" to "cross-site",
        "upgrade-insecure-requests" to "1",
        "user-agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"
    )
    
    try {
        // TENTAR DIFERENTES DOMÍNIOS DO SUPERFLIX
        val superflixDomains = listOf(
            "https://superflixapi.buzz",
            "https://superflixapi.bond",
            "https://superflixapi.asia",
            "https://superflixapi.top"
        )
        
        for (domain in superflixDomains) {
            try {
                // Primeiro precisamos descobrir quantas temporadas existem
                println("🔍 [EPISODIOS] Tentando domínio: $domain")
                
                // URL para obter dados da série (temporada 1 episódio 1)
                val serieUrl = "$domain/serie/$tmdbId/1/1"
                println("🔍 [EPISODIOS] URL da série: $serieUrl")
                
                val response = app.get(
                    serieUrl,
                    headers = superflixHeaders,
                    timeout = 30
                )
                
                println("✅ [EPISODIOS] Status: ${response.code}")
                
                if (response.code == 200) {
                    val html = response.text
                    println("✅ [EPISODIOS] HTML recebido: ${html.length} chars")
                    
                    // ANALISAR O HTML PARA ENCONTRAR DADOS
                    val document = Jsoup.parse(html)
                    
                    // MÉTODO 1: Procurar por scripts com dados
                    val scripts = document.select("script")
                    println("🔍 [EPISODIOS] Encontrados ${scripts.size} scripts")
                    
                    for (script in scripts) {
                        val scriptText = script.html()
                        
                        // Procurar por dados de episódios/temporadas
                        if (scriptText.contains("ALL_EPISODES") || 
                            scriptText.contains("episodes") ||
                            scriptText.contains("seasons") ||
                            scriptText.contains("temporadas")) {
                            
                            println("✅ [EPISODIOS] Encontrou script com dados!")
                            println("🔍 [EPISODIOS] Script preview: ${scriptText.take(500)}")
                            
                            // Extrair dados do script
                            val episodeData = extractEpisodeDataFromScript(scriptText, tmdbId)
                            episodes.addAll(episodeData)
                            
                            if (episodes.isNotEmpty()) {
                                println("✅ [EPISODIOS] Extraiu ${episodes.size} episódios do script")
                                return episodes
                            }
                        }
                    }
                    
                    // MÉTODO 2: Procurar por elementos HTML com dados
                    val dataElements = document.select("[data-episodes], [data-seasons], .episodes-list, .seasons-list")
                    if (dataElements.isNotEmpty()) {
                        println("✅ [EPISODIOS] Encontrou elementos com dados: ${dataElements.size}")
                        // Extrair dados dos elementos...
                    }
                    
                    // MÉTODO 3: Tentar API do SuperFlix (se existir)
                    try {
                        val apiUrl = "$domain/api/serie/$tmdbId"
                        println("🔍 [EPISODIOS] Tentando API: $apiUrl")
                        
                        val apiResponse = app.get(apiUrl, headers = superflixHeaders, timeout = 30)
                        if (apiResponse.code == 200) {
                            val apiText = apiResponse.text
                            println("✅ [EPISODIOS] API respondeu: ${apiText.length} chars")
                            
                            if (apiText.contains("{") && apiText.contains("}")) {
                                try {
                                    val json = JSONObject(apiText)
                                    println("✅ [EPISODIOS] JSON parseado com sucesso!")
                                    // Processar JSON...
                                } catch (e: Exception) {
                                    println("❌ [EPISODIOS] Não é JSON válido")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        println("⚠️ [EPISODIOS] API não disponível: ${e.message}")
                    }
                }
                
            } catch (e: Exception) {
                println("❌ [EPISODIOS] Erro no domínio $domain: ${e.message}")
                continue
            }
        }
        
    } catch (e: Exception) {
        println("❌ [EPISODIOS] Erro geral: ${e.message}")
        e.printStackTrace()
    }
    
    println("✅ [EPISODIOS] Total extraído: ${episodes.size} episódios")
    return episodes
}

private fun extractEpisodeDataFromScript(scriptText: String, tmdbId: String): List<Episode> {
    val episodes = mutableListOf<Episode>()
    
    try {
        // PADRÃO 1: ALL_EPISODES = {...}
        val pattern1 = Regex("""ALL_EPISODES\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
        val match1 = pattern1.find(scriptText)
        
        if (match1 != null) {
            println("✅ [EPISODIOS] Encontrou ALL_EPISODES no script")
            val jsonStr = match1.groupValues[1]
            
            try {
                val json = JSONObject(jsonStr)
                val keys = json.keys()
                
                while (keys.hasNext()) {
                    val seasonKey = keys.next()
                    val seasonNum = seasonKey.toIntOrNull() ?: 1
                    
                    val episodesArray = json.getJSONArray(seasonKey)
                    for (i in 0 until episodesArray.length()) {
                        try {
                            val epObj = episodesArray.getJSONObject(i)
                            
                            val epNumber = epObj.optInt("epi_num", i + 1)
                            val title = epObj.optString("title", "Episódio $epNumber")
                            val description = epObj.optString("sinopse", "").takeIf { it.isNotBlank() }
                            val thumbUrl = epObj.optString("thumb_url").takeIf { 
                                it != "null" && it.isNotBlank() 
                            }?.let {
                                if (it.startsWith("/")) "https://image.tmdb.org/t/p/w300$it" else it
                            }
                            
                            // Criar URL do episódio
                            val episodeUrl = "https://superflixapi.buzz/serie/$tmdbId/$seasonNum/$epNumber"
                            
                            episodes.add(
                                newEpisode(episodeUrl) {
                                    this.name = title
                                    this.season = seasonNum
                                    this.episode = epNumber
                                    this.description = description
                                    this.posterUrl = thumbUrl
                                }
                            )
                            
                            println("📺 [EPISODIOS] Adicionado: S${seasonNum}E${epNumber} - $title")
                            
                        } catch (e: Exception) {
                            println("❌ [EPISODIOS] Erro ao processar episódio $i: ${e.message}")
                        }
                    }
                }
                
            } catch (e: Exception) {
                println("❌ [EPISODIOS] Erro ao parsear JSON: ${e.message}")
            }
        }
        
    } catch (e: Exception) {
        println("❌ [EPISODIOS] Erro na extração do script: ${e.message}")
    }
    
    return episodes
}
    // Função de extração com logs detalhados
    private fun extractAllEpisodesDataWithLogs(html: String): Map<String, List<EpisodeData>>? {
        println("🔍 [EPISODIOS-PARSER] Iniciando extração de dados...")
        
        try {
            // VERSÃO 1: Padrão mais comum
            val pattern1 = Regex("""var\s+ALL_EPISODES\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
            println("🔍 [EPISODIOS-PARSER] Tentando padrão 1: var ALL_EPISODES = {...};")
            
            val match1 = pattern1.find(html)
            if (match1 != null) {
                println("✅ [EPISODIOS-PARSER] Padrão 1 encontrou match!")
                val jsonString = match1.groupValues[1].trim()
                println("🔍 [EPISODIOS-PARSER] JSON extraído (primeiros 300 chars):")
                println(jsonString.take(300))
                return parseEpisodesJsonWithLogs(jsonString)
            }
            
            // VERSÃO 2: Padrão alternativo
            val pattern2 = Regex("""ALL_EPISODES\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL)
            println("🔍 [EPISODIOS-PARSER] Tentando padrão 2: ALL_EPISODES = {...};")
            
            val match2 = pattern2.find(html)
            if (match2 != null) {
                println("✅ [EPISODIOS-PARSER] Padrão 2 encontrou match!")
                val jsonString = match2.groupValues[1].trim()
                println("🔍 [EPISODIOS-PARSER] JSON extraído (primeiros 300 chars):")
                println(jsonString.take(300))
                return parseEpisodesJsonWithLogs(jsonString)
            }
            
            // VERSÃO 3: Procura direta pelo JSON
            println("🔍 [EPISODIOS-PARSER] Tentando busca direta por JSON...")
            val jsonStart = html.indexOf("""{"1":""")
            if (jsonStart != -1) {
                println("✅ [EPISODIOS-PARSER] Encontrou início do JSON na posição $jsonStart")
                
                // Encontrar fim do objeto
                var braceCount = 0
                var i = jsonStart
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
                
                if (foundEnd) {
                    val jsonString = html.substring(jsonStart, i + 1)
                    println("✅ [EPISODIOS-PARSER] JSON extraído (${jsonString.length} chars)")
                    println("🔍 [EPISODIOS-PARSER] JSON (primeiros 300 chars):")
                    println(jsonString.take(300))
                    return parseEpisodesJsonWithLogs(jsonString)
                }
            }
            
            println("❌ [EPISODIOS-PARSER] Nenhum padrão encontrou ALL_EPISODES")
            return null
            
        } catch (e: Exception) {
            println("❌ [EPISODIOS-PARSER] Erro na extração: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    private fun parseEpisodesJsonWithLogs(jsonString: String): Map<String, List<EpisodeData>>? {
        println("🔍 [EPISODIOS-PARSER] Parseando JSON...")
        
        try {
            val jsonObject = JSONObject(jsonString)
            val result = mutableMapOf<String, List<EpisodeData>>()
            
            val seasonKeys = jsonObject.keys().asSequence().toList()
            println("✅ [EPISODIOS-PARSER] Temporadas encontradas: ${seasonKeys.joinToString(", ")}")
            
            seasonKeys.forEach { seasonKey ->
                println("🔍 [EPISODIOS-PARSER] Processando temporada: $seasonKey")
                val episodesArray = jsonObject.getJSONArray(seasonKey)
                println("🔍 [EPISODIOS-PARSER] Temporada $seasonKey tem ${episodesArray.length()} episódios")
                
                val episodesList = mutableListOf<EpisodeData>()
                
                for (i in 0 until episodesArray.length()) {
                    try {
                        val episodeObj = episodesArray.getJSONObject(i)
                        
                        // Log dos campos para debug
                        if (i == 0) { // Mostrar apenas para o primeiro episódio de cada temporada
                            println("🔍 [EPISODIOS-PARSER] Campos do episódio 1:")
                            episodeObj.keys().forEach { key ->
                                println("   - $key: ${episodeObj.opt(key)}")
                            }
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
                        
                        if (i < 3) { // Log dos primeiros 3 episódios
                            println("✅ [EPISODIOS-PARSER] Episódio ${i+1}: ${episodeObj.optString("title")}")
                        }
                        
                    } catch (e: Exception) {
                        println("❌ [EPISODIOS-PARSER] Erro ao parsear episódio $i: ${e.message}")
                    }
                }
                
                result[seasonKey] = episodesList
                println("✅ [EPISODIOS-PARSER] Temporada $seasonKey processada: ${episodesList.size} episódios")
            }
            
            println("✅ [EPISODIOS-PARSER] Parse concluído: ${result.size} temporadas no total")
            return result
            
        } catch (e: Exception) {
            println("❌ [EPISODIOS-PARSER] Erro ao parsear JSON: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    // ========== FUNÇÕES ORIGINAIS (SEM LOGS) ==========

    private fun extractEmbeddedData(html: String): EmbeddedData? {
        try {
            val pattern = Regex("""const dadosMulti\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
            val match = pattern.find(html)
            
            if (match != null) {
                val jsonString = match.groupValues[1]
                return AppUtils.tryParseJson<EmbeddedData>(jsonString)
            }
            
            return null
        } catch (e: Exception) {
            return null
        }
    }

    private fun extractTmdbIdFromUrl(url: String): String? {
        val idMatch = Regex("[?&]id=(\\d+)").find(url)
        return idMatch?.groupValues?.get(1)
    }

    // ========== LOAD LINKS (ORIGINAL SEM LOGS) ==========
    override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    println("🔍 [DEBUG] loadLinks iniciado")
    
    return try {
        // 1. EXTRAIR TMDB ID
        val tmdbId = extractTmdbId(data) ?: return false
        println("✅ TMDB ID: $tmdbId")
        
        // 2. DETERMINAR SE É SÉRIE
        val isSeries = data.contains("type=tv") || data.contains("type=anime")
        val season = if (isSeries) extractSeasonFromUrl(data) ?: 1 else 1
        val episode = if (isSeries) extractEpisodeFromUrl(data) ?: 1 else 1
        
        if (isSeries) {
            println("📺 É SÉRIE - S$season E$episode")
        } else {
            println("🎬 É FILME")
        }
        
        // 3. OBTER O EPISODE_ID (para séries) ou MOVIE_ID (para filmes)
        val videoId = getVideoIdFromApi(tmdbId, isSeries, season, episode)
        if (videoId == null) {
            println("❌ Não conseguiu obter video_id")
            return false
        }
        
        println("✅ Video ID obtido: $videoId")
        
        // 4. USAR A API DO SUPERFLIX PARA PEGAR O PLAYER
        val playerResult = getSuperflixPlayer(videoId)
        if (playerResult == null) {
            println("❌ Falha no player do SuperFlix")
            return false
        }
        
        println("✅ Player obtido: ${playerResult.length} chars")
        
        // 5. EXTRAIR O M3U8 DO PLAYER
        val m3u8Url = extractM3u8FromPlayer(playerResult)
        if (m3u8Url == null) {
            println("❌ Não encontrou m3u8 no player")
            return false
        }
        
        println("✅ M3U8 encontrado: $m3u8Url")
        
        // 6. CRIAR O LINK FINAL
        val quality = determineQuality(m3u8Url)
        println("✅ Qualidade: $quality")
        
        // Adicionar legenda
        try {
            val subtitleUrl = "https://complicado.sbs/cdn/down/disk11/subtitle_por.vtt"
            subtitleCallback(SubtitleFile("Português", subtitleUrl))
        } catch (e: Exception) {
            // Ignorar erro de legenda
        }
        
        // Criar ExtractorLink
        newExtractorLink(name, "SuperFlix ($quality)", m3u8Url, ExtractorLinkType.M3U8) {
            referer = "https://superflixapi.bond/"
            this.quality = quality
        }.also { callback(it) }
        
        true
        
    } catch (e: Exception) {
        println("❌ Erro geral: ${e.message}")
        e.printStackTrace()
        false
    }
}

// ========== FUNÇÕES AUXILIARES ==========

private fun extractTmdbId(url: String): String? {
    val match = Regex("[?&]id=(\\d+)").find(url)
    return match?.groupValues?.get(1)
}

private fun extractSeasonFromUrl(url: String): Int? {
    val match = Regex("[?&]season=(\\d+)").find(url)
    return match?.groupValues?.get(1)?.toIntOrNull()
}

private fun extractEpisodeFromUrl(url: String): Int? {
    val match = Regex("[?&]episode=(\\d+)").find(url)
    return match?.groupValues?.get(1)?.toIntOrNull()
}

// 1. OBTER VIDEO ID DA API
private suspend fun getVideoIdFromApi(tmdbId: String, isSeries: Boolean, season: Int = 1, episode: Int = 1): String? {
    println("🔍 Obtendo video_id da API...")
    
    val apiUrl = "https://superflixapi.bond/api"
    
    val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "pt-BR",
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "Origin" to "https://superflixapi.bond",
        "Referer" to "https://superflixapi.bond/",
        "X-Requested-With" to "XMLHttpRequest"
    )
    
    // Tentar diferentes métodos
    val methods = listOf(
        // Método 1: Para filmes
        if (!isSeries) {
            mapOf(
                "action" to "getMovie",
                "tmdb_id" to tmdbId
            )
        } else {
            // Método 2: Para séries
            mapOf(
                "action" to "getEpisode",
                "tmdb_id" to tmdbId,
                "season" to season.toString(),
                "episode" to episode.toString()
            )
        },
        
        // Método 3: Alternativo
        mapOf(
            "action" to "getVideo",
            "id" to tmdbId,
            "type" to if (isSeries) "tv" else "movie"
        ),
        
        // Método 4: Mais direto
        mapOf(
            "action" to "getPlayer",
            "tmdb_id" to tmdbId
        )
    )
    
    for (data in methods) {
        try {
            println("🔍 Tentando método: $data")
            
            val response = app.post(apiUrl, data = data, headers = headers, timeout = 30)
            println("📡 Resposta: ${response.code} - ${response.text.take(200)}...")
            
            if (response.code == 200) {
                val json = JSONObject(response.text)
                
                // Verificar diferentes formatos de resposta
                val videoId = json.optString("video_id")
                    .takeIf { it.isNotBlank() && it != "null" }
                    ?: json.optJSONObject("data")?.optString("video_id")
                    ?: json.optString("id")
                    ?: json.optJSONObject("player")?.optString("video_id")
                
                if (videoId != null && videoId.isNotBlank() && videoId != "null") {
                    println("✅ video_id encontrado: $videoId")
                    return videoId
                }
            }
            
        } catch (e: Exception) {
            println("⚠️ Erro no método: ${e.message}")
            continue
        }
    }
    
    return null
}

// 2. OBTER PLAYER DO SUPERFLIX
private suspend fun getSuperflixPlayer(videoId: String): String? {
    println("🔍 Obtendo player do SuperFlix...")
    
    val apiUrl = "https://superflixapi.bond/api"
    
    val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "pt-BR",
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "Origin" to "https://superflixapi.bond",
        "Referer" to "https://superflixapi.bond/",
        "X-Requested-With" to "XMLHttpRequest"
    )
    
    val data = mapOf(
        "action" to "getPlayer",
        "video_id" to videoId
    )
    
    try {
        val response = app.post(apiUrl, data = data, headers = headers, timeout = 30)
        println("📡 Status do player: ${response.code}")
        
        if (response.code == 200) {
            val json = JSONObject(response.text)
            val errors = json.optString("errors", "1")
            
            if (errors == "0") {
                val playerData = json.optJSONObject("data")
                if (playerData != null) {
                    // Extrair video_url ou embed_code
                    val videoUrl = playerData.optString("video_url")
                    val embedCode = playerData.optString("embed_code")
                    
                    return videoUrl.takeIf { it.isNotBlank() } ?: embedCode
                }
            }
        }
        
    } catch (e: Exception) {
        println("❌ Erro ao obter player: ${e.message}")
    }
    
    return null
}

// 3. EXTRAIR M3U8 DO PLAYER
private suspend fun extractM3u8FromPlayer(playerData: String): String? {
    println("🔍 Extraindo m3u8 do player...")
    
    // Se já for uma URL direta
    if (playerData.startsWith("http") && (playerData.contains(".m3u8") || playerData.contains("video/"))) {
        println("✅ Player já é URL: $playerData")
        return extractM3u8FromVideoUrl(playerData)
    }
    
    // Se for código embed, extrair URL do iframe
    if (playerData.contains("<iframe")) {
        val srcMatch = Regex("""src=["']([^"']+)["']""").find(playerData)
        val iframeSrc = srcMatch?.groupValues?.get(1)
        
        if (iframeSrc != null) {
            println("🔍 Iframe encontrado: $iframeSrc")
            return extractM3u8FromIframe(iframeSrc)
        }
    }
    
    // Procurar URL em qualquer formato
    val urlPattern = Regex("""(https?://[^\s"']+)""")
    val matches = urlPattern.findAll(playerData).toList()
    
    for (match in matches) {
        val url = match.groupValues[1]
        if (url.contains("superflixapi") || url.contains("llanfair") || url.contains("video/")) {
            println("🔍 URL suspeita encontrada: $url")
            val m3u8 = extractM3u8FromVideoUrl(url)
            if (m3u8 != null) return m3u8
        }
    }
    
    return null
}

// 4. EXTRAIR M3U8 DE VIDEO URL
private suspend fun extractM3u8FromVideoUrl(videoUrl: String): String? {
    println("🔍 Extraindo m3u8 de: $videoUrl")
    
    try {
        // Se for uma URL direta do player
        if (videoUrl.contains("/player/") || videoUrl.contains("do=getVideo")) {
            return extractFromPlayerApi(videoUrl)
        }
        
        // Se for uma URL de hash
        if (videoUrl.contains("/video/") || videoUrl.contains("/m/")) {
            return extractFromHashUrl(videoUrl)
        }
        
        // Tentar acessar diretamente
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
            "Accept" to "*/*",
            "Referer" to "https://superflixapi.bond/"
        )
        
        val response = app.get(videoUrl, headers = headers, timeout = 30)
        
        if (response.code == 200) {
            val html = response.text
            
            // Procurar m3u8 no HTML
            val patterns = listOf(
                Regex("""["']file["']\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                Regex("""["']src["']\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                Regex("""(https?://[^"\s]+\.m3u8[^"\s]*)""")
            )
            
            for (pattern in patterns) {
                val match = pattern.find(html)
                if (match != null) {
                    val m3u8 = match.groupValues[1]
                    println("✅ M3U8 encontrado no HTML: $m3u8")
                    return m3u8
                }
            }
        }
        
    } catch (e: Exception) {
        println("❌ Erro ao extrair de videoUrl: ${e.message}")
    }
    
    return null
}

// 5. EXTRAIR DE PLAYER API
private suspend fun extractFromPlayerApi(playerUrl: String): String? {
    println("🔍 Acessando player API: $playerUrl")
    
    try {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
            "Accept" to "application/json, text/plain, */*",
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "Origin" to "https://llanfairpwllgwyngy.com",
            "Referer" to "https://llanfairpwllgwyngy.com/",
            "X-Requested-With" to "XMLHttpRequest"
        )
        
        // Extrair hash da URL
        val hash = when {
            playerUrl.contains("data=") -> Regex("data=([^&]+)").find(playerUrl)?.groupValues?.get(1)
            playerUrl.contains("/video/") -> playerUrl.substringAfter("/video/").substringBefore("?")
            else -> null
        }
        
        if (hash == null) {
            println("❌ Hash não encontrado")
            return null
        }
        
        println("✅ Hash: $hash")
        
        val apiUrl = "https://llanfairpwllgwyngy.com/player/index.php?data=$hash&do=getVideo"
        val data = mapOf("hash" to hash, "r" to "")
        
        val response = app.post(apiUrl, data = data, headers = headers, timeout = 30)
        
        if (response.code == 200) {
            val json = JSONObject(response.text)
            val m3u8 = json.optString("securedLink")
                .takeIf { it.isNotBlank() }
                ?: json.optString("videoSource")
            
            if (m3u8.isNotBlank()) {
                println("✅ M3U8 da API: $m3u8")
                return m3u8
            }
        }
        
    } catch (e: Exception) {
        println("❌ Erro no player API: ${e.message}")
    }
    
    return null
}

// 6. EXTRAIR DE IFRAME
private suspend fun extractM3u8FromIframe(iframeSrc: String): String? {
    println("🔍 Acessando iframe: $iframeSrc")
    
    try {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Referer" to "https://superflixapi.bond/"
        )
        
        val response = app.get(iframeSrc, headers = headers, timeout = 30)
        
        if (response.code == 200) {
            val html = response.text
            
            // Procurar m3u8
            val patterns = listOf(
                Regex("""["']file["']\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                Regex("""(https?://[^"\s]+\.m3u8[^"\s]*)""")
            )
            
            for (pattern in patterns) {
                val match = pattern.find(html)
                if (match != null) {
                    val m3u8 = match.groupValues[1]
                    println("✅ M3U8 do iframe: $m3u8")
                    return m3u8
                }
            }
        }
        
    } catch (e: Exception) {
        println("❌ Erro no iframe: ${e.message}")
    }
    
    return null
}

// 7. EXTRAIR DE HASH URL
private suspend fun extractFromHashUrl(hashUrl: String): String? {
    println("🔍 Processando hash URL: $hashUrl")
    
    // Extrair hash
    val hash = when {
        hashUrl.contains("/video/") -> hashUrl.substringAfter("/video/").substringBefore("?")
        hashUrl.contains("/m/") -> hashUrl.substringAfter("/m/").substringBefore("?")
        else -> null
    }
    
    if (hash != null) {
        return extractFromPlayerApi("https://llanfairpwllgwyngy.com/player/index.php?data=$hash&do=getVideo")
    }
    
    return null
}

// 8. DETERMINAR QUALIDADE
private fun determineQuality(m3u8Url: String): Int {
    return when {
        m3u8Url.contains("1080") -> Qualities.P1080.value
        m3u8Url.contains("720") -> Qualities.P720.value
        m3u8Url.contains("480") -> Qualities.P480.value
        m3u8Url.contains("360") -> Qualities.P360.value
        else -> Qualities.P720.value
    }
}
