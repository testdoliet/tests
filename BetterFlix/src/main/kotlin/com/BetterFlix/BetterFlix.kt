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

    // Mapeamento de gêneros (ORIGINAL)
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

    // Modelos de dados para a API (ORIGINAL)
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

    // Helper para fazer requests com rate limiting (ORIGINAL)
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

    // ========== FUNÇÕES QUE VOU MANTER ORIGINAIS ==========
    
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

    // ========== SEARCH ORIGINAL ==========
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
                // Fallback para busca HTML (ORIGINAL)
                fallbackSearch(query)
            }
        }
    }

    // Fallback caso a API de busca não esteja disponível (ORIGINAL)
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

    // ========== LOAD ORIGINAL ==========
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

    // ========== FUNÇÕES AUXILIARES DO LOAD (ORIGINAL) ==========

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

    // EXTRAIR EPISÓDIOS DO SUPERFLIX (ORIGINAL - APENAS RETIRANDO DEBUGS)
    private suspend fun extractEpisodesFromSuperflix(tmdbId: String?, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        if (tmdbId == null) {
            return episodes
        }
        
        try {
            // Fazer requisição para o SuperFlix
            val superflixUrl = "https://superflixapi.bond/serie/$tmdbId/1/1"
            val superflixHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Accept-Language" to "pt-BR",
                "Referer" to "https://betterflix.vercel.app/",
                "Sec-Fetch-Dest" to "document",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "cross-site"
            )
            
            val response = app.get(superflixUrl, headers = superflixHeaders, timeout = 30)
            
            if (response.code >= 400) {
                return episodes
            }
            
            val html = response.text
            
            // Extrair objeto ALL_EPISODES
            val allEpisodesData = extractAllEpisodesData(html)
            if (allEpisodesData == null) {
                return episodes
            }
            
            // Processar todas as temporadas e episódios
            allEpisodesData.forEach { (seasonNumber, seasonEpisodes) ->
                val seasonNum = seasonNumber.toIntOrNull() ?: 1
                
                seasonEpisodes.forEach { episodeData ->
                    try {
                        val epNumber = episodeData.epi_num
                        val title = episodeData.title
                        val description = episodeData.sinopse.takeIf { it.isNotBlank() }
                        val thumbUrl = episodeData.thumb_url?.let { 
                            if (it.startsWith("/")) "https://image.tmdb.org/t/p/w300$it" else it 
                        }
                        
                        // Construir URL do episódio
                        val episodeUrl = "$baseUrl&season=$seasonNum&episode=$epNumber"
                        
                        episodes.add(
                            newEpisode(episodeUrl) {
                                this.name = title
                                this.season = seasonNum
                                this.episode = epNumber
                                this.description = description
                                this.posterUrl = thumbUrl?.let { fixUrl(it) }
                            }
                        )
                    } catch (e: Exception) {
                        // Ignorar erro
                    }
                }
            }
            
        } catch (e: Exception) {
            // Ignorar erro
        }
        
        return episodes
    }

    // EXTRAIR OBJETO ALL_EPISODES DO HTML DO SUPERFLIX (ORIGINAL)
    private fun extractAllEpisodesData(html: String): Map<String, List<EpisodeData>>? {
        try {
            // Procura pelo objeto ALL_EPISODES no script
            val pattern = Regex("""var\s+ALL_EPISODES\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
            val match = pattern.find(html)
            
            if (match != null) {
                val jsonString = match.groupValues[1]
                
                try {
                    val jsonObject = JSONObject(jsonString)
                    val result = mutableMapOf<String, List<EpisodeData>>()
                    
                    jsonObject.keys().forEach { seasonKey ->
                        val episodesArray = jsonObject.getJSONArray(seasonKey)
                        val episodesList = mutableListOf<EpisodeData>()
                        
                        for (i in 0 until episodesArray.length()) {
                            val episodeObj = episodesArray.getJSONObject(i)
                            episodesList.add(
                                EpisodeData(
                                    ID = episodeObj.optInt("ID"),
                                    title = episodeObj.optString("title"),
                                    sinopse = episodeObj.optString("sinopse"),
                                    item = episodeObj.optInt("item"),
                                    thumb_url = episodeObj.optString("thumb_url").takeIf { it != "null" && it.isNotBlank() },
                                    air_date = episodeObj.optString("air_date").takeIf { it != "null" && it.isNotBlank() },
                                    duration = episodeObj.optInt("duration"),
                                    epi_num = episodeObj.optInt("epi_num"),
                                    season = episodeObj.optInt("season")
                                )
                            )
                        }
                        
                        result[seasonKey] = episodesList
                    }
                    
                    return result
                } catch (e: Exception) {
                    return null
                }
            }
            
            return null
        } catch (e: Exception) {
            return null
        }
    }

    // FUNÇÃO PARA EXTRAIR OBJETO JSON EMBUTIDO (dadosMulti) - ORIGINAL
    private fun extractEmbeddedData(html: String): EmbeddedData? {
        try {
            // Procura pelo objeto dadosMulti no script
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

    // ========== LOAD LINKS (ORIGINAL) ==========
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return safeApiRequest(data) {
            try {
                // Extrair TMDB ID da URL
                val tmdbId = extractTmdbId(data)
                
                if (tmdbId == null) {
                    return@safeApiRequest false
                }
                
                // Determinar tipo da URL para usar na extração
                val type = when {
                    data.contains("type=anime") -> "anime"
                    data.contains("type=tv") -> "tv"
                    else -> "movie"
                }
                
                // TENTAR TODOS OS DOMÍNIOS DO SUPERFLIX
                for (superflixDomain in superflixDomains) {
                    try {
                        val success = extractVideoFromSuperflix(superflixDomain, tmdbId, type, callback)
                        if (success) {
                            // Adicionar legenda em português se disponível
                            try {
                                val subtitleUrl = "https://complicado.sbs/cdn/down/disk11/${tmdbId.substring(0, 32)}/Subtitle/subtitle_por.vtt"
                                subtitleCallback.invoke(
                                    SubtitleFile("Português", subtitleUrl)
                                )
                            } catch (e: Exception) {
                                // Ignorar erro de legenda
                            }
                            
                            return@safeApiRequest true
                        }
                    } catch (e: Exception) {
                        // Tentar próximo domínio
                        continue
                    }
                }
                
                return@safeApiRequest false
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun extractTmdbId(url: String): String? {
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
            // Lista de video_ids possíveis
            val possibleVideoIds = listOf("303309", "351944")
            
            for (videoId in possibleVideoIds) {
                // PASSO 1: Obter o video_url da API do SuperFlix
                val apiUrl = "$domain/api"
                
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
                
                try {
                    val apiResponse = app.post(apiUrl, data = apiData, headers = apiHeaders, timeout = 30)
                    
                    if (apiResponse.code >= 400) {
                        continue
                    }
                    
                    val apiJson = JSONObject(apiResponse.text)
                    
                    // Verificar corretamente o status
                    val errors = apiJson.optString("errors", "1")
                    val message = apiJson.optString("message", "")
                    
                    if (errors == "1" || message != "success") {
                        continue
                    }
                    
                    val videoUrl = apiJson.optJSONObject("data")?.optString("video_url")
                    if (videoUrl.isNullOrEmpty()) {
                        continue
                    }
                    
                    // PASSO 2: Extrair o hash/token da URL
                    val hash = extractHashFromVideoUrl(videoUrl)
                    if (hash == null) {
                        continue
                    }
                    
                    // PASSO 3: Fazer a requisição para obter o m3u8
                    val playerResult = requestPlayerHash(hash, callback)
                    if (playerResult) {
                        return true
                    }
                    
                } catch (e: Exception) {
                    continue
                }
            }
            
            return false
            
        } catch (e: Exception) {
            return false
        }
    }

    private fun extractHashFromVideoUrl(videoUrl: String): String? {
        return when {
            videoUrl.contains("/video/") -> {
                videoUrl.substringAfter("/video/").substringBefore("?")
            }
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
            val playerDomain = "https://llanfairpwllgwyngy.com"
            val playerUrl = "$playerDomain/player/index.php?data=$hash&do=getVideo"
            
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
            
            if (playerResponse.code >= 400) {
                return false
            }
            
            val playerJson = JSONObject(playerResponse.text)
            
            // Extrair o link m3u8
            val m3u8Url = playerJson.optString("securedLink")
                .takeIf { it.isNotBlank() }
                ?: playerJson.optString("videoSource")
                    .takeIf { it.isNotBlank() }
            
            if (m3u8Url.isNullOrBlank()) {
                return false
            }
            
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
            }.also { callback(it) }
            
            return true
            
        } catch (e: Exception) {
            return false
        }
    }

    // ========== FUNÇÕES AUXILIARES (ORIGINAL) ==========

    private fun generateSlug(title: String): String {
        return title
            .lowercase()
            .replace(Regex("[^a-z0-9\\s-]"), "")
            .replace(Regex("\\s+"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
    }

    private fun String.encodeSearchQuery(): String {
        return java.net.URLEncoder.encode(this, "UTF-8")
    }
}
