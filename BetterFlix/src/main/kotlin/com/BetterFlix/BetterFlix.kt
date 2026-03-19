package com.BetterFlix

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.Jsoup
import org.json.JSONObject

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

    // Headers específicos para SuperFlix
    private val superflixHeaders = mapOf(
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

    // Headers para API do SuperFlix
    private val apiHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "pt-BR",
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "Origin" to "",
        "Referer" to "",
        "X-Requested-With" to "XMLHttpRequest",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "same-origin"
    )

    // Domínios para extração de vídeo
    private val superflixDomains = listOf(
        "https://superflixapi.bond",
        "https://superflixapi.asia",
        "https://superflixapi.top",
        "https://superflixapi.buzz"
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

    // Modelos de dados
    data class TrendingResponse(
        @JsonProperty("results") val results: List<ContentItem>
    )

    data class GenreResponse(
        @JsonProperty("results") val results: List<ContentItem>
    )

    data class AnimeResponse(
        @JsonProperty("results") val results: List<ContentItem>
    )

    data class RecommendationsResponse(
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

    data class SearchResponseData(
        @JsonProperty("results") val results: List<ContentItem>
    )

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

    // =============================================
    // FUNÇÕES DE RATING/BADGES DE AVALIAÇÃO
    // =============================================
    private fun extractRatingFromContentItem(item: ContentItem): Score? {
        return item.voteAverage?.let { rating ->
            println("⭐ [RATING] Avaliação encontrada: $rating")
            Score.from10(rating)
        }
    }

    private fun extractRatingFromEmbeddedData(data: EmbeddedData?): Score? {
        return data?.vote?.let { rating ->
            println("⭐ [RATING] Avaliação do embedded data: $rating")
            Score.from10(rating)
        }
    }

     // ========== FUNÇÃO PARA EXTRAIR SCORE DOS RESULTADOS DE BUSCA ==========
    private fun extractScoreFromSearchElement(element: org.jsoup.nodes.Element): Score? {
        return try {
            // Método 1: Procurar pelo padrão específico do HTML (div com text-yellow-500 > span.font-bold)
            val scoreContainer = element.selectFirst("""
                div.flex.items-center.gap-1.text-yellow-500,
                div.text-yellow-500,
                [class*="text-yellow"]
            """.trimIndent())
            
            if (scoreContainer != null) {
                val scoreText = scoreContainer.selectFirst("span.font-bold")?.text()?.trim()
                    ?: scoreContainer.text().trim()
                
                if (scoreText.isNotBlank()) {
                    // Extrair apenas números e ponto decimal
                    val regex = Regex("""(\d+\.?\d*)""")
                    val match = regex.find(scoreText)
                    match?.let {
                        val scoreValue = it.value.toFloatOrNull()
                        if (scoreValue != null) {
                            println("✅ [SEARCH-SCORE] Score encontrado: $scoreValue")
                            return Score.from10(scoreValue)
                        }
                    }
                }
            }
            
            // Método 2: Procurar por estrelas (★) e extrair número
            val starContainer = element.selectFirst("""
                [class*="star"], 
                [class*="rating"], 
                [class*="score"],
                svg + span.font-bold
            """.trimIndent())
            
            if (starContainer != null) {
                val parent = starContainer.parent()
                val text = parent?.text() ?: starContainer.text()
                val regex = Regex("""(\d+\.?\d*)""")
                val match = regex.find(text)
                match?.let {
                    val scoreValue = it.value.toFloatOrNull()
                    if (scoreValue != null) {
                        println("✅ [SEARCH-SCORE] Score por estrelas: $scoreValue")
                        return Score.from10(scoreValue)
                    }
                }
            }
            
            // Método 3: Procurar em qualquer elemento com número de pontuação
            val allElements = element.select("""
                div, span, p, b, strong
            """)
            
            for (el in allElements) {
                val text = el.text().trim()
                if (text.matches(Regex("""^\d+\.?\d*$"""))) {
                    val scoreValue = text.toFloatOrNull()
                    if (scoreValue != null && scoreValue >= 1 && scoreValue <= 10) {
                        println("✅ [SEARCH-SCORE] Score genérico: $scoreValue")
                        return Score.from10(scoreValue)
                    }
                }
            }
            
            null
        } catch (e: Exception) {
            println("⚠️ [SEARCH-SCORE] Erro ao extrair score: ${e.message}")
            null
        }
    }

    // =============================================
    // FUNÇÕES DE RECOMENDAÇÕES
    // =============================================
    private suspend fun fetchRecommendations(tmdbId: String?, type: String?): List<SearchResponse> {
        if (tmdbId == null || type == null) {
            println("❌ [RECOMMENDATIONS] ID ou tipo não disponível")
            return emptyList()
        }

        return try {
            val apiUrl = "$mainUrl/api/recommendations?id=$tmdbId&type=$type"
            println("🤝 [RECOMMENDATIONS] Buscando: $apiUrl")
            
            val response = app.get(apiUrl, headers = headers, cookies = cookies, timeout = 30)
            
            if (response.code != 200) {
                println("❌ [RECOMMENDATIONS] Falha: ${response.code}")
                return emptyList()
            }
            
            val data = response.parsedSafe<RecommendationsResponse>()
            if (data == null || data.results.isEmpty()) {
                println("⚠️ [RECOMMENDATIONS] Sem recomendações")
                return emptyList()
            }
            
            println("✅ [RECOMMENDATIONS] ${data.results.size} recomendações encontradas")
            
            data.results.mapNotNull { item ->
                createSearchResponseFromItem(item)
            }
            
        } catch (e: Exception) {
            println("❌ [RECOMMENDATIONS] Erro: ${e.message}")
            emptyList()
        }
    }

    // Helper para fazer requests com rate limiting
    private suspend fun <T> safeApiRequest(url: String, block: suspend () -> T): T {
        kotlinx.coroutines.delay(500)
        try {
            return block()
        } catch (e: Exception) {
            if (e.message?.contains("429") == true) {
                kotlinx.coroutines.delay(2000)
                return block()
            }
            throw e
        }
    }

    // ========== MAIN PAGE ==========
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
            createSearchResponseFromItem(item)
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
            createSearchResponseFromItem(item)
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
            createSearchResponseFromItem(item)
        }
    }

    // ========== SEARCH ==========
    override suspend fun search(query: String): List<SearchResponse> {
        return safeApiRequest(query) {
            try {
                val encodedQuery = query.encodeSearchQuery()
                val searchUrl = "$mainUrl/results?q=$encodedQuery"
                
                println("🔍 [SEARCH] Buscando: $query")
                println("🔗 [SEARCH] URL: $searchUrl")
                
                val response = app.get(
                    searchUrl,
                    headers = headers,
                    cookies = cookies,
                    timeout = 30
                )
                
                if (response.code != 200) {
                    println("❌ [SEARCH] Falha: ${response.code}")
                    return@safeApiRequest emptyList()
                }
                
                val document = response.document
                println("✅ [SEARCH] HTML carregado")
                
                // Selecionar todos os links de resultados
                val resultLinks = document.select("""
                    a.group[href*='?id='],
                    a[href*='?id='][href*='fromSearch=true'],
                    a[href*='?id='][href*='type=']
                """.trimIndent())
                
                println("🔍 [SEARCH] ${resultLinks.size} links encontrados")
                
                if (resultLinks.isEmpty()) {
                    println("⚠️ [SEARCH] Nenhum resultado encontrado")
                    return@safeApiRequest emptyList()
                }
                
                resultLinks.mapIndexedNotNull { index, link ->
                    try {
                        val href = link.attr("href") ?: return@mapIndexedNotNull null
                        val fullUrl = fixUrl(href)
                        
                        // Extrair ID da URL
                        val idMatch = Regex("[?&]id=(\\d+)").find(fullUrl)
                        val id = idMatch?.groupValues?.get(1) ?: return@mapIndexedNotNull null
                        
                        // Determinar tipo
                        val type = when {
                            fullUrl.contains("type=tv") -> TvType.TvSeries
                            fullUrl.contains("type=anime") -> TvType.Anime
                            fullUrl.contains("type=movie") -> TvType.Movie
                            else -> {
                                // Verificar por elementos visuais
                                val badge = link.selectFirst("span:contains(Série), span:contains(Filme), span:contains(Anime)")
                                when {
                                    badge?.text()?.contains("Série") == true -> TvType.TvSeries
                                    badge?.text()?.contains("Anime") == true -> TvType.Anime
                                    else -> TvType.Movie
                                }
                            }
                        }
                        
                        // Extrair título
                        val title = link.selectFirst("img")?.attr("alt")?.trim() ?:
                                   link.selectFirst("h3")?.text()?.trim() ?:
                                   link.selectFirst(".text-sm.font-bold")?.text()?.trim() ?:
                                   link.text().trim().takeIf { it.isNotBlank() } ?:
                                   return@mapIndexedNotNull null
                        
                        // Extrair poster
                        val poster = link.selectFirst("img[src*='image.tmdb.org']")?.attr("src")?.let { fixUrl(it) }
                        
                        // Limpar título (remover ano se houver)
                        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
                        
                        // Extrair ano
                        var year: Int? = null
                        val yearMatch = Regex("\\((\\d{4})\\)").find(title)
                        year = yearMatch?.groupValues?.get(1)?.toIntOrNull()
                        
                        // Extrair score do elemento HTML
                        val score = extractScoreFromSearchElement(link)
                        
                     
                        // Criar SearchResponse com score
                        when (type) {
                            TvType.Anime -> newAnimeSearchResponse(cleanTitle, fullUrl, TvType.Anime) {
                                this.posterUrl = poster
                                this.year = year
                                this.score = score
                            }
                            TvType.TvSeries -> newTvSeriesSearchResponse(cleanTitle, fullUrl, TvType.TvSeries) {
                                this.posterUrl = poster
                                this.year = year
                                this.score = score
                            }
                            TvType.Movie -> newMovieSearchResponse(cleanTitle, fullUrl, TvType.Movie) {
                                this.posterUrl = poster
                                this.year = year
                                this.score = score
                            }
                            else -> null
                        }
                        
                    } catch (e: Exception) {
                        println("❌ [SEARCH] Erro ao processar link $index: ${e.message}")
                        null
                    }
                }.filterNotNull()
                
            } catch (e: Exception) {
                println("❌ [SEARCH] Erro geral: ${e.message}")
                e.printStackTrace()
                emptyList()
            }
        }
    }

    // ========== LOAD ==========
    override suspend fun load(url: String): LoadResponse? {
        return safeApiRequest(url) {
            try {
                println("📦 [LOAD] Carregando URL: $url")
                
                val response = app.get(url, headers = headers, cookies = cookies, timeout = 30)
                println("📊 [LOAD] Status code: ${response.code}")
                
                if (response.code >= 400) {
                    println("❌ [LOAD] Erro HTTP: ${response.code}")
                    return@safeApiRequest null
                }

                val document = response.document
                val html = response.text
                println("📄 [LOAD] HTML carregado: ${html.length} caracteres")

                val embeddedData = extractEmbeddedData(html)
                if (embeddedData == null) {
                    println("❌ [LOAD] Embedded data não encontrado")
                    return@safeApiRequest null
                }
                
                println("✅ [LOAD] Embedded data encontrado:")
                println("   ├─ id: ${embeddedData.id}")
                println("   ├─ name: ${embeddedData.name}")
                println("   ├─ date: ${embeddedData.date}")
                println("   ├─ vote: ${embeddedData.vote}")
                println("   └─ genres: ${embeddedData.genres}")

                val tmdbId = embeddedData.id ?: extractTmdbIdFromUrl(url)
                println("🎯 [LOAD] TMDB ID: $tmdbId")
                
                val isSeries = url.contains("type=tv")
                val isAnime = url.contains("type=anime")
                val isMovie = !isSeries && !isAnime
                println("📋 [LOAD] Tipo: ${if (isMovie) "Filme" else if (isSeries) "Série" else "Anime"}")

                // 1. Extrair rating
                val rating = extractRatingFromEmbeddedData(embeddedData)
                println("⭐ [LOAD] Rating extraído: $rating")
                
                // 2. Buscar recomendações
                val typeParam = when {
                    isSeries -> "tv"
                    isAnime -> "anime"
                    else -> "movie"
                }
                val recommendations = if (tmdbId != null) {
                    fetchRecommendations(tmdbId, typeParam)
                } else {
                    emptyList()
                }
                println("🎯 [LOAD] ${recommendations.size} recomendações encontradas")

                if (isSeries || isAnime) {
                    val type = if (isAnime) TvType.Anime else TvType.TvSeries
                    val episodes = extractEpisodesFromSuperflix(tmdbId, url)
                    println("📺 [LOAD] ${episodes.size} episódios extraídos")

                    newTvSeriesLoadResponse(embeddedData.name ?: "Sem título", url, type, episodes) {
                        this.posterUrl = embeddedData.poster?.let { fixUrl(it) }
                        this.backgroundPosterUrl = embeddedData.backdrop?.let { fixUrl(it) }
                        this.year = embeddedData.date?.substring(0, 4)?.toIntOrNull()
                        this.plot = embeddedData.bio
                        this.tags = embeddedData.genres?.split(",")?.map { it.trim() } ?: emptyList()
                        this.score = rating
                        this.recommendations = recommendations.takeIf { it.isNotEmpty() }
                    }
                } else {
                    newMovieLoadResponse(embeddedData.name ?: "Sem título", url, TvType.Movie, url) {
                        this.posterUrl = embeddedData.poster?.let { fixUrl(it) }
                        this.backgroundPosterUrl = embeddedData.backdrop?.let { fixUrl(it) }
                        this.year = embeddedData.date?.substring(0, 4)?.toIntOrNull()
                        this.plot = embeddedData.bio
                        this.tags = embeddedData.genres?.split(",")?.map { it.trim() } ?: emptyList()
                        this.score = rating
                        this.recommendations = recommendations.takeIf { it.isNotEmpty() }
                    }
                }
            } catch (e: Exception) {
                println("💥 [LOAD] Erro: ${e.message}")
                e.printStackTrace()
                null
            }
        }
    }

    private fun extractEmbeddedData(html: String): EmbeddedData? {
        try {
            println("🔍 [EMBEDDED] Procurando dados embedded...")
            val pattern = Regex("""const dadosMulti\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
            val match = pattern.find(html)

            if (match != null) {
                val jsonString = match.groupValues[1]
                println("✅ [EMBEDDED] JSON encontrado: ${jsonString.take(100)}...")
                return AppUtils.tryParseJson<EmbeddedData>(jsonString)
            }

            println("❌ [EMBEDDED] Padrão não encontrado")
            return null
        } catch (e: Exception) {
            println("💥 [EMBEDDED] Erro: ${e.message}")
            return null
        }
    }

    private fun extractTmdbIdFromUrl(url: String): String? {
        val idMatch = Regex("[?&]id=(\\d+)").find(url)
        return idMatch?.groupValues?.get(1)
    }

    // ========== EPISODES EXTRACTION ==========
    private suspend fun extractEpisodesFromSuperflix(tmdbId: String?, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()

        if (tmdbId == null) {
            println("❌ [EPISODES] TMDB ID nulo")
            return episodes
        }

        println("📺 [EPISODES] Extraindo episódios para TMDB ID: $tmdbId")

        try {
            for (domain in superflixDomains) {
                println("🌐 [EPISODES] Tentando domínio: $domain")
                try {
                    val serieUrl = "$domain/serie/$tmdbId/1/1"
                    println("🔗 [EPISODES] URL: $serieUrl")
                    
                    val response = app.get(
                        serieUrl,
                        headers = superflixHeaders,
                        timeout = 30
                    )

                    println("📊 [EPISODES] Status code: ${response.code}")

                    if (response.code == 200) {
                        val html = response.text
                        println("📄 [EPISODES] HTML obtido: ${html.length} caracteres")
                        
                        val document = Jsoup.parse(html)
                        val scripts = document.select("script")
                        println("📜 [EPISODES] ${scripts.size} scripts encontrados")

                        for (script in scripts) {
                            val scriptText = script.html()
                            if (scriptText.contains("ALL_EPISODES")) {
                                println("✅ [EPISODES] ALL_EPISODES encontrado!")
                                val episodeData = extractEpisodeDataFromScript(scriptText, tmdbId)
                                episodes.addAll(episodeData)
                                println("📺 [EPISODES] ${episodeData.size} episódios extraídos")
                                return episodes
                            }
                        }
                    }
                } catch (e: Exception) {
                    println("⚠️ [EPISODES] Erro no domínio $domain: ${e.message}")
                    continue
                }
            }
        } catch (e: Exception) {
            println("💥 [EPISODES] Erro geral: ${e.message}")
            e.printStackTrace()
        }

        return episodes
    }

    private fun extractEpisodeDataFromScript(scriptText: String, tmdbId: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        try {
            println("🔍 [EPISODE-SCRIPT] Processando script...")
            val pattern = Regex("""ALL_EPISODES\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
            val match = pattern.find(scriptText)
            
            if (match != null) {
                val jsonStr = match.groupValues[1]
                println("✅ [EPISODE-SCRIPT] JSON encontrado: ${jsonStr.take(200)}...")
                
                val json = JSONObject(jsonStr)
                val keys = json.keys()
                
                while (keys.hasNext()) {
                    val seasonKey = keys.next()
                    val seasonNum = seasonKey.toIntOrNull() ?: 1
                    println("📅 [EPISODE-SCRIPT] Processando temporada $seasonNum")
                    
                    val episodesArray = json.getJSONArray(seasonKey)
                    println("📊 [EPISODE-SCRIPT] ${episodesArray.length()} episódios na temporada $seasonNum")
                    
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
                            
                            println("   ✅ Episódio $epNumber: $title")
                            
                        } catch (e: Exception) {
                            println("   ⚠️ Erro no episódio $i: ${e.message}")
                        }
                    }
                }
            } else {
                println("❌ [EPISODE-SCRIPT] Padrão ALL_EPISODES não encontrado")
            }
        } catch (e: Exception) {
            println("💥 [EPISODE-SCRIPT] Erro: ${e.message}")
        }

        return episodes
    }

    // ========== LOAD LINKS ==========
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return safeApiRequest(data) {
            try {
                println("\n🔗 [LOADLINKS] ============ INICIANDO EXTRAÇÃO ============")
                println("🔗 [LOADLINKS] URL recebida: $data")
                
                val tmdbId = extractTmdbId(data)
                if (tmdbId == null) {
                    println("❌ [LOADLINKS] TMDB ID não encontrado na URL")
                    println("🔗 [LOADLINKS] Tentando extrair por outros métodos...")
                    
                    // Tentar extrair por regex alternativo
                    val altIdMatch = Regex("""(\d{5,})""").find(data)
                    if (altIdMatch != null) {
                        println("✅ [LOADLINKS] ID alternativo encontrado: ${altIdMatch.value}")
                    } else {
                        println("❌ [LOADLINKS] Nenhum ID numérico encontrado")
                    }
                    return@safeApiRequest false
                }
                
                println("✅ [LOADLINKS] TMDB ID extraído: $tmdbId")
                
                val type = extractTypeFromUrl(data)
                println("📋 [LOADLINKS] Tipo de conteúdo: $type")
                
                var season: Int = 1
                var episode: Int = 1
                
                if (type == "tv" || type == "anime") {
                    season = extractSeason(data) ?: 1
                    episode = extractEpisode(data) ?: 1
                    println("📺 [LOADLINKS] Dados do episódio:")
                    println("   ├─ Temporada: $season")
                    println("   ├─ Episódio: $episode")
                    println("   └─ URL completa: ${if (type == "tv") "Série" else "Anime"}")
                } else {
                    println("🎬 [LOADLINKS] Conteúdo é um filme")
                }
                
                println("🌐 [LOADLINKS] Lista de domínios disponíveis:")
                superflixDomains.forEachIndexed { index, domain ->
                    println("   ${index + 1}. $domain")
                }
                
                var success = false
                for ((index, superflixDomain) in superflixDomains.withIndex()) {
                    println("==========================================")
                    println("🌐 [LOADLINKS] Tentativa ${index + 1}/${superflixDomains.size}")
                    println("🌐 [LOADLINKS] Testando domínio: $superflixDomain")
                    
                    try {
                        val domainSuccess = when (type) {
                            "movie" -> {
                                println("🎬 [LOADLINKS] Modo: Filme")
                                extractMovieVideo(superflixDomain, tmdbId, index + 1, callback)
                            }
                            "tv", "anime" -> {
                                println("📺 [LOADLINKS] Modo: ${if (type == "tv") "Série" else "Anime"}")
                                extractSeriesVideo(superflixDomain, tmdbId, season, episode, index + 1, callback)
                            }
                            else -> {
                                println("⚠️ [LOADLINKS] Tipo desconhecido: $type, tentando como filme")
                                extractMovieVideo(superflixDomain, tmdbId, index + 1, callback)
                            }
                        }
                        
                        if (domainSuccess) {
                            println("✅ [LOADLINKS] Sucesso com domínio: $superflixDomain")
                            
                            // Tentar adicionar legenda
                            try {
                                println("📝 [LOADLINKS] Tentando adicionar legenda em português")
                                addPortugueseSubtitle(tmdbId, subtitleCallback)
                                println("✅ [LOADLINKS] Legenda adicionada com sucesso")
                            } catch (e: Exception) {
                                println("⚠️ [LOADLINKS] Erro ao adicionar legenda: ${e.message}")
                            }
                            
                            success = true
                            println("🔗 [LOADLINKS] ============ EXTRAÇÃO CONCLUÍDA COM SUCESSO ============\n")
                            return@safeApiRequest true
                        } else {
                            println("❌ [LOADLINKS] Domínio $superflixDomain falhou na extração do vídeo")
                        }
                    } catch (e: Exception) {
                        println("💥 [LOADLINKS] Exceção no domínio $superflixDomain:")
                        println("   ├─ Mensagem: ${e.message}")
                        println("   ├─ Classe: ${e.javaClass.simpleName}")
                        println("   └─ Stacktrace: ${e.stackTrace.firstOrNull()}")
                    }
                    
                    println("------------------------------------------")
                }
                
                if (!success) {
                    println("❌ [LOADLINKS] TODOS OS DOMÍNIOS FALHARAM")
                    println("🔗 [LOADLINKS] Nenhum link de vídeo foi encontrado")
                }
                
                println("🔗 [LOADLINKS] ============ FIM DA EXTRAÇÃO ============\n")
                return@safeApiRequest success
                
            } catch (e: Exception) {
                println("💥 [LOADLINKS] ERRO GERAL NÃO TRATADO:")
                println("   ├─ Mensagem: ${e.message}")
                println("   ├─ Classe: ${e.javaClass.simpleName}")
                println("   └─ Stacktrace:")
                e.stackTrace.take(10).forEachIndexed { index, element ->
                    println("      ${index + 1}. $element")
                }
                false
            }
        }
    }

    // ========== FUNÇÕES PARA FILMES ==========
    private suspend fun extractMovieVideo(
        domain: String,
        tmdbId: String,
        attempt: Int,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            println("   🎬 [MOVIE] Iniciando extração para filme ID: $tmdbId (Tentativa $attempt)")
            
            val playerUrl = "$domain/filme/$tmdbId"
            println("   🔍 [MOVIE] URL do player: $playerUrl")
            
            val headersWithReferer = superflixHeaders.toMutableMap().apply {
                put("referer", "$domain/")
            }
            println("   📋 [MOVIE] Headers configurados: ${headersWithReferer.size} headers")
            
            val playerResponse = app.get(
                playerUrl,
                headers = headersWithReferer,
                timeout = 30
            )
            
            println("   📊 [MOVIE] Status code da resposta: ${playerResponse.code}")
            
            if (playerResponse.code != 200) {
                println("   ❌ [MOVIE] Falha ao acessar player. Status: ${playerResponse.code}")
                if (playerResponse.code == 404) {
                    println("   ℹ️ [MOVIE] Filme não encontrado (404)")
                } else if (playerResponse.code == 403) {
                    println("   ℹ️ [MOVIE] Acesso proibido (403) - Possível bloqueio")
                } else if (playerResponse.code == 429) {
                    println("   ℹ️ [MOVIE] Rate limit atingido (429)")
                }
                return false
            }
            
            val html = playerResponse.text
            println("   ✅ [MOVIE] HTML obtido: ${html.length} caracteres")
            println("   📄 [MOVIE] Primeiros 500 caracteres do HTML:")
            println("      ${html.take(500).replace("\n", " ")}...")
            
            val videoId = extractVideoIdFromHtml(html)
            if (videoId == null) {
                println("   ❌ [MOVIE] video_id NÃO encontrado no HTML")
                println("   🔍 [MOVIE] Procurando padrões alternativos...")
                
                // Verificar se há algum data-id
                val dataIdMatches = Regex("""data-id=["'](\d+)["']""").findAll(html)
                val dataIds = dataIdMatches.map { it.groupValues[1] }.toList()
                if (dataIds.isNotEmpty()) {
                    println("   ℹ️ [MOVIE] Encontrados data-ids: ${dataIds.joinToString()}")
                }
                
                return false
            }
            
            println("   ✅ [MOVIE] video_id encontrado: $videoId")
            
            // Mostrar onde encontrou o video_id
            val contextMatch = Regex("""(.{0,50})data-id=["']$videoId["'](.{0,50})""").find(html)
            contextMatch?.let {
                println("   📍 [MOVIE] Contexto do video_id:")
                println("      ...${it.groupValues[1]} [data-id=$videoId] ${it.groupValues[2]}...")
            }
            
            return getPlayerWithVideoId(domain, videoId, attempt, callback)
            
        } catch (e: Exception) {
            println("   💥 [MOVIE] Erro na extração: ${e.message}")
            println("   📍 [MOVIE] Stacktrace: ${e.stackTrace.firstOrNull()}")
            return false
        }
    }

    // ========== FUNÇÕES PARA SÉRIES ==========
    private suspend fun extractSeriesVideo(
        domain: String,
        tmdbId: String,
        season: Int,
        episode: Int,
        attempt: Int,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            println("   📺 [SERIES] Iniciando extração para: ID=$tmdbId S${season}E${episode} (Tentativa $attempt)")
            
            val playerUrl = "$domain/serie/$tmdbId/$season/$episode"
            println("   🔍 [SERIES] URL do player: $playerUrl")
            
            val headersWithReferer = superflixHeaders.toMutableMap().apply {
                put("referer", "$domain/")
            }
            println("   📋 [SERIES] Headers configurados: ${headersWithReferer.size} headers")
            
            val playerResponse = app.get(
                playerUrl,
                headers = headersWithReferer,
                timeout = 30
            )
            
            println("   📊 [SERIES] Status code da resposta: ${playerResponse.code}")
            
            if (playerResponse.code != 200) {
                println("   ❌ [SERIES] Falha ao acessar player. Status: ${playerResponse.code}")
                if (playerResponse.code == 404) {
                    println("   ℹ️ [SERIES] Episódio não encontrado (404)")
                }
                return false
            }
            
            val html = playerResponse.text
            println("   ✅ [SERIES] HTML obtido: ${html.length} caracteres")
            println("   📄 [SERIES] Primeiros 500 caracteres do HTML:")
            println("      ${html.take(500).replace("\n", " ")}...")
            
            // Verificar se há ALL_EPISODES no HTML
            val hasAllEpisodes = html.contains("ALL_EPISODES")
            println("   🔍 [SERIES] ALL_EPISODES encontrado: $hasAllEpisodes")
            
            val contentId = extractContentIdFromHtml(html, season, episode)
            if (contentId == null) {
                println("   ❌ [SERIES] contentId NÃO encontrado no HTML")
                
                // Debug: mostrar trecho onde ALL_EPISODES deveria estar
                if (hasAllEpisodes) {
                    val allEpisodesMatch = Regex("""ALL_EPISODES\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL).find(html)
                    if (allEpisodesMatch != null) {
                        println("   ℹ️ [SERIES] ALL_EPISODES encontrado, mas não conseguiu extrair contentId")
                        println("   📄 [SERIES] ALL_EPISODES (primeiros 300 chars):")
                        println("      ${allEpisodesMatch.groupValues[1].take(300)}")
                    } else {
                        println("   ℹ️ [SERIES] ALL_EPISODES mencionado mas regex não encontrou")
                    }
                }
                
                return false
            }
            
            println("   ✅ [SERIES] contentId encontrado: $contentId")
            
            // Mostrar onde encontrou
            val contextMatch = Regex(""""ID"\s*:\s*"$contentId"""").find(html)
            contextMatch?.let {
                println("   📍 [SERIES] Contexto do contentId encontrado no ALL_EPISODES")
            }
            
            return getVideoIdFromOptions(domain, contentId, attempt, callback)
            
        } catch (e: Exception) {
            println("   💥 [SERIES] Erro na extração: ${e.message}")
            println("   📍 [SERIES] Stacktrace: ${e.stackTrace.firstOrNull()}")
            return false
        }
    }

    // ========== FUNÇÕES AUXILIARES DE EXTRAÇÃO ==========
    private fun extractVideoIdFromHtml(html: String): String? {
        try {
            println("      🔍 [HTML] Procurando video_id no HTML...")
            
            val document = Jsoup.parse(html)
            
            val elements = document.select("[data-id]")
            println("      📊 [HTML] ${elements.size} elementos com data-id encontrados")
            
            for (element in elements) {
                val videoId = element.attr("data-id")
                if (videoId.isNotBlank() && videoId.matches(Regex("\\d+"))) {
                    println("      ✅ [HTML] video_id encontrado em elemento: $videoId")
                    return videoId
                }
            }
            
            val btnServers = document.select(".btn-server[data-id]")
            println("      📊 [HTML] ${btnServers.size} botões .btn-server com data-id encontrados")
            
            for (btn in btnServers) {
                val videoId = btn.attr("data-id")
                if (videoId.isNotBlank() && videoId.matches(Regex("\\d+"))) {
                    println("      ✅ [HTML] video_id encontrado em .btn-server: $videoId")
                    return videoId
                }
            }
            
            val regex = Regex("""data-id\s*=\s*["'](\d+)["']""")
            val match = regex.find(html)
            if (match != null) {
                println("      ✅ [HTML] video_id encontrado via regex: ${match.groupValues[1]}")
                return match.groupValues[1]
            }
            
            println("      ❌ [HTML] Nenhum video_id encontrado")
            return null
        } catch (e: Exception) {
            println("      ⚠️ [HTML] Erro ao extrair video_id: ${e.message}")
            return null
        }
    }

    private fun extractContentIdFromHtml(html: String, season: Int, episode: Int): String? {
        try {
            println("      🔍 [CONTENT] Procurando contentId para S${season}E${episode}...")
            
            val pattern = Regex("""ALL_EPISODES\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
            val match = pattern.find(html)
            
            if (match != null) {
                val jsonString = match.groupValues[1]
                println("      ✅ [CONTENT] ALL_EPISODES encontrado")
                
                try {
                    val json = JSONObject(jsonString)
                    val seasonArray = json.optJSONArray(season.toString())
                    
                    if (seasonArray != null) {
                        println("      📊 [CONTENT] ${seasonArray.length()} episódios na temporada $season")
                        
                        for (i in 0 until seasonArray.length()) {
                            val epObj = seasonArray.getJSONObject(i)
                            val epNum = epObj.optInt("epi_num")
                            
                            if (epNum == episode) {
                                val contentId = epObj.optString("ID")
                                if (contentId.isNotBlank()) {
                                    println("      ✅ [CONTENT] contentId encontrado para episódio $episode: $contentId")
                                    return contentId
                                }
                            }
                        }
                        println("      ❌ [CONTENT] Episódio $episode não encontrado na temporada $season")
                    } else {
                        println("      ❌ [CONTENT] Temporada $season não encontrada no JSON")
                    }
                } catch (e: Exception) {
                    println("      ❌ [CONTENT] Erro ao parsear ALL_EPISODES: ${e.message}")
                }
            } else {
                println("      ❌ [CONTENT] ALL_EPISODES não encontrado no HTML")
            }
            
            return null
        } catch (e: Exception) {
            println("      ⚠️ [CONTENT] Erro ao extrair contentId: ${e.message}")
            return null
        }
    }

    private suspend fun getVideoIdFromOptions(
        domain: String,
        contentId: String,
        attempt: Int,
        callback: (ExtractorLink) -> Unit
    ): String? {
        try {
            println("   🔧 [OPTIONS] Obtendo options para contentId: $contentId (Tentativa $attempt)")
            
            val apiUrl = "$domain/api"
            println("   🔧 [OPTIONS] URL da API: $apiUrl")
            
            val optionsData = mapOf(
                "action" to "getOptions",
                "contentid" to contentId
            )
            println("   📦 [OPTIONS] Dados enviados: $optionsData")
            
            val currentApiHeaders = apiHeaders.toMutableMap().apply {
                put("Origin", domain)
                put("Referer", "$domain/")
            }
            println("   📋 [OPTIONS] Headers: $currentApiHeaders")
            
            println("   ⏳ [OPTIONS] Enviando requisição POST...")
            val response = app.post(
                apiUrl,
                data = optionsData,
                headers = currentApiHeaders,
                timeout = 30
            )
            
            println("   📊 [OPTIONS] Status code: ${response.code}")
            
            if (response.code != 200) {
                println("   ❌ [OPTIONS] Status code inválido: ${response.code}")
                return null
            }
            
            val responseText = response.text
            println("   📄 [OPTIONS] Resposta recebida (${responseText.length} caracteres)")
            println("   📄 [OPTIONS] Resposta completa: $responseText")
            
            try {
                val json = JSONObject(responseText)
                println("   ✅ [OPTIONS] JSON parseado com sucesso")
                
                val errors = json.optString("errors", "1")
                val message = json.optString("message", "")
                println("   📊 [OPTIONS] errors: $errors, message: $message")
                
                if (errors != "0") {
                    println("   ❌ [OPTIONS] Erro na resposta: errors=$errors")
                    return null
                }
                
                val data = json.optJSONObject("data")
                if (data == null) {
                    println("   ❌ [OPTIONS] Objeto 'data' não encontrado no JSON")
                    println("   📄 [OPTIONS] Chaves disponíveis: ${json.keySet()}")
                    return null
                }
                
                println("   ✅ [OPTIONS] Objeto 'data' encontrado")
                
                val optionsArray = data.optJSONArray("options")
                if (optionsArray == null) {
                    println("   ❌ [OPTIONS] Array 'options' não encontrado no data")
                    println("   📄 [OPTIONS] Chaves em data: ${data.keySet()}")
                    return null
                }
                
                println("   📊 [OPTIONS] Número de opções encontradas: ${optionsArray.length()}")
                
                if (optionsArray.length() == 0) {
                    println("   ❌ [OPTIONS] Array de options está vazio")
                    return null
                }
                
                // Mostrar todas as opções disponíveis
                for (i in 0 until optionsArray.length()) {
                    val option = optionsArray.getJSONObject(i)
                    println("   📌 [OPTIONS] Opção ${i + 1}:")
                    println("      ├─ ID: ${option.optString("ID")}")
                    println("      ├─ name: ${option.optString("name")}")
                    println("      └─ embed: ${option.optString("embed_url")?.take(50)}...")
                }
                
                val firstOption = optionsArray.getJSONObject(0)
                val videoId = firstOption.optString("ID")
                
                if (videoId.isBlank()) {
                    println("   ❌ [OPTIONS] video_id está vazio na primeira opção")
                    return null
                }
                
                println("   ✅ [OPTIONS] video_id obtido da primeira opção: $videoId")
                return videoId
                
            } catch (e: Exception) {
                println("   ❌ [OPTIONS] Erro ao parsear JSON: ${e.message}")
                println("   📄 [OPTIONS] Resposta bruta: $responseText")
                return null
            }
            
        } catch (e: Exception) {
            println("   💥 [OPTIONS] Erro na requisição: ${e.message}")
            return null
        }
    }

    private suspend fun getPlayerWithVideoId(
        domain: String,
        videoId: String,
        attempt: Int,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            println("   🎮 [PLAYER] Obtendo player para video_id: $videoId (Tentativa $attempt)")
            
            val apiUrl = "$domain/api"
            println("   🎮 [PLAYER] URL da API: $apiUrl")
            
            val playerData = mapOf(
                "action" to "getPlayer",
                "video_id" to videoId
            )
            println("   📦 [PLAYER] Dados enviados: $playerData")
            
            val currentApiHeaders = apiHeaders.toMutableMap().apply {
                put("Origin", domain)
                put("Referer", "$domain/")
            }
            println("   📋 [PLAYER] Headers: $currentApiHeaders")
            
            println("   ⏳ [PLAYER] Enviando requisição POST...")
            val response = app.post(
                apiUrl,
                data = playerData,
                headers = currentApiHeaders,
                timeout = 30
            )
            
            println("   📊 [PLAYER] Status code: ${response.code}")
            
            if (response.code != 200) {
                println("   ❌ [PLAYER] Status code inválido: ${response.code}")
                return false
            }
            
            val responseText = response.text
            println("   📄 [PLAYER] Resposta recebida (${responseText.length} caracteres)")
            println("   📄 [PLAYER] Primeiros 500 caracteres da resposta:")
            println("      ${responseText.take(500).replace("\n", " ")}...")
            
            try {
                val json = JSONObject(responseText)
                println("   ✅ [PLAYER] JSON parseado com sucesso")
                
                val errors = json.optString("errors", "1")
                val message = json.optString("message", "")
                println("   📊 [PLAYER] errors: $errors, message: $message")
                
                if (errors != "0" || message != "success") {
                    println("   ❌ [PLAYER] Erro na resposta: errors=$errors, message=$message")
                    return false
                }
                
                val data = json.optJSONObject("data")
                if (data == null) {
                    println("   ❌ [PLAYER] Objeto 'data' não encontrado no JSON")
                    println("   📄 [PLAYER] Chaves disponíveis: ${json.keySet()}")
                    return false
                }
                
                println("   ✅ [PLAYER] Objeto 'data' encontrado")
                println("   📊 [PLAYER] Chaves em data: ${data.keySet()}")
                
                val videoUrl = data.optString("video_url")
                if (videoUrl.isBlank()) {
                    println("   ❌ [PLAYER] video_url está vazio")
                    
                    // Tentar outras possíveis chaves
                    val possibleKeys = listOf("url", "src", "source", "file", "link", "playlist")
                    for (key in possibleKeys) {
                        val value = data.optString(key)
                        if (value.isNotBlank()) {
                            println("   ℹ️ [PLAYER] Encontrado valor alternativo em '$key': ${value.take(100)}...")
                        }
                    }
                    
                    return false
                }
                
                println("   ✅ [PLAYER] video_url obtido: ${videoUrl.take(200)}...")
                
                val hash = extractHashFromVideoUrl(videoUrl)
                if (hash == null) {
                    println("   ❌ [PLAYER] Hash não encontrado na video_url")
                    return false
                }
                
                println("   ✅ [PLAYER] Hash extraído: ${hash.take(20)}...")
                
                return getFinalM3u8(hash, attempt, callback)
                
            } catch (e: Exception) {
                println("   ❌ [PLAYER] Erro ao parsear JSON: ${e.message}")
                println("   📄 [PLAYER] Resposta bruta: $responseText")
                return false
            }
            
        } catch (e: Exception) {
            println("   💥 [PLAYER] Erro na requisição: ${e.message}")
            return false
        }
    }

    private fun extractHashFromVideoUrl(videoUrl: String): String? {
        println("      🔐 [HASH] Extraindo hash de: ${videoUrl.take(100)}...")
        
        val hash = when {
            videoUrl.contains("/video/") -> {
                videoUrl.substringAfter("/video/").substringBefore("?").also {
                    println("      ✅ [HASH] Hash extraído via /video/: $it")
                }
            }
            videoUrl.contains("/m/") -> {
                videoUrl.substringAfter("/m/").substringBefore("?").also {
                    println("      ✅ [HASH] Hash extraído via /m/: $it")
                }
            }
            else -> {
                println("      ⚠️ [HASH] Formato de URL não reconhecido: ${videoUrl.take(100)}...")
                null
            }
        }
        
        return hash
    }

    private suspend fun getFinalM3u8(
        hash: String,
        attempt: Int,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            println("   🔐 [M3U8] Obtendo m3u8 para hash: ${hash.take(20)}... (Tentativa $attempt)")
            
            val playerDomain = "https://llanfairpwllgwyngy.com"
            val playerUrl = "$playerDomain/player/index.php?data=$hash&do=getVideo"
            println("   🔐 [M3U8] URL do player: $playerUrl")
            
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
            println("   📋 [M3U8] Headers configurados: ${playerHeaders.size} headers")
            
            val playerData = mapOf(
                "hash" to hash,
                "r" to ""
            )
            println("   📦 [M3U8] Dados enviados: $playerData")
            
            println("   ⏳ [M3U8] Enviando requisição POST...")
            val response = app.post(playerUrl, data = playerData, headers = playerHeaders, timeout = 30)
            
            println("   📊 [M3U8] Status code: ${response.code}")
            
            if (response.code != 200) {
                println("   ❌ [M3U8] Status code inválido: ${response.code}")
                return false
            }
            
            val responseText = response.text
            println("   📄 [M3U8] Resposta recebida (${responseText.length} caracteres)")
            println("   📄 [M3U8] Resposta completa: $responseText")
            
            try {
                val json = JSONObject(responseText)
                println("   ✅ [M3U8] JSON parseado com sucesso")
                println("   📊 [M3U8] Chaves disponíveis: ${json.keySet()}")
                
                val m3u8Url = json.optString("securedLink")
                    .takeIf { it.isNotBlank() }
                    ?: json.optString("videoSource")
                    .takeIf { it.isNotBlank() }
                
                if (m3u8Url.isNullOrBlank()) {
                    println("   ❌ [M3U8] URL m3u8 não encontrada em 'securedLink' ou 'videoSource'")
                    
                    // Tentar outras chaves possíveis
                    val possibleKeys = listOf("url", "src", "file", "link", "playlist", "hls", "source")
                    for (key in possibleKeys) {
                        val value = json.optString(key)
                        if (value.isNotBlank()) {
                            println("   ℹ️ [M3U8] Encontrado valor alternativo em '$key': ${value.take(100)}...")
                        }
                    }
                    
                    return false
                }
                
                println("   ✅ [M3U8] URL m3u8 encontrada: ${m3u8Url.take(100)}...")
                
                // Verificar se a URL contém parâmetros de expiração
                if (m3u8Url.contains("expires=")) {
                    val expiresMatch = Regex("expires=(\\d+)").find(m3u8Url)
                    expiresMatch?.let {
                        val expires = it.groupValues[1]
                        println("   ⏰ [M3U8] URL expira em: $expires (timestamp)")
                    }
                }
                
                val quality = when {
                    m3u8Url.contains("1080") -> Qualities.P1080.value
                    m3u8Url.contains("720") -> Qualities.P720.value
                    m3u8Url.contains("480") -> Qualities.P480.value
                    m3u8Url.contains("360") -> Qualities.P360.value
                    m3u8Url.contains("240") -> Qualities.P240.value
                    else -> Qualities.P720.value
                }
                
                println("   📊 [M3U8] Qualidade detectada: $quality")
                
                newExtractorLink(name, "SuperFlix ($quality)", m3u8Url, ExtractorLinkType.M3U8) {
                    referer = "$playerDomain/"
                    this.quality = quality
                }.also { 
                    callback(it)
                    println("   🎉 [M3U8] ExtractorLink criado com sucesso!")
                }
                
                return true
                
            } catch (e: Exception) {
                println("   ❌ [M3U8] Erro ao parsear JSON: ${e.message}")
                println("   📄 [M3U8] Resposta bruta: $responseText")
                return false
            }
            
        } catch (e: Exception) {
            println("   💥 [M3U8] Erro na requisição: ${e.message}")
            return false
        }
    }

    private fun addPortugueseSubtitle(tmdbId: String, subtitleCallback: (SubtitleFile) -> Unit) {
        try {
            val subtitleUrl = "https://complicado.sbs/cdn/down/disk11/${tmdbId}/Subtitle/subtitle_por.vtt"
            println("      📝 [SUBTITLE] URL da legenda: $subtitleUrl")
            
            subtitleCallback.invoke(
                SubtitleFile("Português", subtitleUrl)
            )
            println("      ✅ [SUBTITLE] Legenda adicionada")
        } catch (e: Exception) {
            println("      ⚠️ [SUBTITLE] Não foi possível adicionar legenda: ${e.message}")
        }
    }

    // =============================================
    // FUNÇÕES AUXILIARES
    // =============================================
    private fun createSearchResponseFromItem(item: ContentItem): SearchResponse? {
        return try {
            val title = item.title ?: item.name ?: item.originalTitle ?: item.originalName ?: return null
            val year = getYearFromDate(item.releaseDate ?: item.firstAirDate)
            val poster = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
            val id = item.id.toString()
            
            // Extrair rating do ContentItem
            val rating = extractRatingFromContentItem(item)

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
                    this.score = rating
                }
                TvType.TvSeries -> newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = year
                    this.score = rating
                }
                TvType.Anime -> newAnimeSearchResponse(title, url, TvType.Anime) {
                    this.posterUrl = poster
                    this.year = year
                    this.score = rating
                }
                else -> null
            }
        } catch (e: Exception) {
            println("❌ Erro ao criar SearchResponse: ${e.message}")
            null
        }
    }

    private fun getYearFromDate(dateString: String?): Int? {
        return try {
            dateString?.substring(0, 4)?.toIntOrNull()
        } catch (e: Exception) {
            null
        }
    }

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

    private fun fixUrl(url: String): String {
        return if (url.startsWith("http")) url else "$mainUrl${if (url.startsWith("/")) "" else "/"}$url"
    }

    // Funções de extração de IDs
    private fun extractTmdbId(url: String): String? {
        val idMatch = Regex("[?&]id=(\\d+)").find(url)
        if (idMatch != null) {
            return idMatch.groupValues[1]
        }
        
        val serieMatch = Regex("""/serie/(\d+)/\d+/\d+""").find(url)
        if (serieMatch != null) {
            return serieMatch.groupValues[1]
        }
        
        val filmeMatch = Regex("""/filme/(\d+)""").find(url)
        if (filmeMatch != null) {
            return filmeMatch.groupValues[1]
        }
        
        return null
    }

    private fun extractTypeFromUrl(url: String): String {
        return when {
            url.contains("/serie/") -> "tv"
            url.contains("/filme/") -> "movie"
            url.contains("type=anime") -> "anime"
            url.contains("type=tv") -> "tv"
            else -> "movie"
        }
    }

    private fun extractSeason(url: String): Int? {
        val seasonMatch = Regex("[?&]season=(\\d+)").find(url)
        if (seasonMatch != null) {
            return seasonMatch.groupValues[1].toIntOrNull()
        }
        
        val serieMatch = Regex("""/serie/\d+/(\d+)/(\d+)""").find(url)
        if (serieMatch != null) {
            return serieMatch.groupValues[1].toIntOrNull()
        }
        
        return 1
    }

    private fun extractEpisode(url: String): Int? {
        val episodeMatch = Regex("[?&]episode=(\\d+)").find(url)
        if (episodeMatch != null) {
            return episodeMatch.groupValues[1].toIntOrNull()
        }
        
        val serieMatch = Regex("""/serie/\d+/\d+/(\d+)""").find(url)
        if (serieMatch != null) {
            return serieMatch.groupValues[1].toIntOrNull()
        }
        
        return 1
    }
}
