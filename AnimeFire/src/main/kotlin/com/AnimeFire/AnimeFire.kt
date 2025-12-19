package com.AnimeFire

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder

class AnimeFire : MainAPI() {
    override var mainUrl = "https://animefire.io"
    override var name = "ANIMEFIRE"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie, TvType.OVA)
    override val usesWebView = false

    // API GraphQL do AniList
    private val aniListApiUrl = "https://graphql.anilist.co"
    
    // Cache para mapear títulos AniList → URLs AnimeFire
    private val titleToUrlCache = mutableMapOf<String, String>()
    
    override val mainPage = mainPageOf(
        "trending" to "Em Alta",
        "season" to "Populares Nessa Temporada", 
        "popular" to "Sempre Populares",
        "top" to "Top 100",
        "upcoming" to "Na Próxima Temporada"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return when (request.name) {
            "Em Alta" -> getAniListTrending(page)
            "Populares Nessa Temporada" -> getAniListSeason(page)
            "Sempre Populares" -> getAniListPopular(page)
            "Top 100" -> getAniListTop(page)
            "Na Próxima Temporada" -> getAniListUpcoming(page)
            else -> newHomePageResponse(request.name, emptyList(), false)
        }
    }

    private suspend fun getAniListTrending(page: Int): HomePageResponse {
        val query = """
            query {
                Page(page: $page, perPage: 20) {
                    media(sort: TRENDING_DESC, type: ANIME) {
                        id
                        title {
                            romaji
                            english
                            native
                            userPreferred
                        }
                        coverImage {
                            large
                            extraLarge
                        }
                    }
                }
            }
        """.trimIndent()
        
        return executeAniListQuery(query, "Em Alta", page)
    }

    private suspend fun getAniListSeason(page: Int): HomePageResponse {
        val query = """
            query {
                Page(page: $page, perPage: 20) {
                    media(season: WINTER, seasonYear: 2025, type: ANIME, sort: POPULARITY_DESC) {
                        id
                        title {
                            romaji
                            english
                            native
                            userPreferred
                        }
                        coverImage {
                            large
                            extraLarge
                        }
                    }
                }
            }
        """.trimIndent()
        
        return executeAniListQuery(query, "Populares Nessa Temporada", page)
    }

    private suspend fun getAniListPopular(page: Int): HomePageResponse {
        val query = """
            query {
                Page(page: $page, perPage: 20) {
                    media(sort: POPULARITY_DESC, type: ANIME) {
                        id
                        title {
                            romaji
                            english
                            native
                            userPreferred
                        }
                        coverImage {
                            large
                            extraLarge
                        }
                    }
                }
            }
        """.trimIndent()
        
        return executeAniListQuery(query, "Sempre Populares", page)
    }

    private suspend fun getAniListTop(page: Int): HomePageResponse {
        val query = """
            query {
                Page(page: $page, perPage: 20) {
                    media(sort: SCORE_DESC, type: ANIME) {
                        id
                        title {
                            romaji
                            english
                            native
                            userPreferred
                        }
                        coverImage {
                            large
                            extraLarge
                        }
                    }
                }
            }
        """.trimIndent()
        
        return executeAniListQuery(query, "Top 100", page)
    }

    private suspend fun getAniListUpcoming(page: Int): HomePageResponse {
        val query = """
            query {
                Page(page: $page, perPage: 20) {
                    media(season: SPRING, seasonYear: 2025, type: ANIME, sort: POPULARITY_DESC) {
                        id
                        title {
                            romaji
                            english
                            native
                            userPreferred
                        }
                        coverImage {
                            large
                            extraLarge
                        }
                    }
                }
            }
        """.trimIndent()
        
        return executeAniListQuery(query, "Na Próxima Temporada", page)
    }

    private suspend fun executeAniListQuery(
        query: String, 
        pageName: String,
        page: Int
    ): HomePageResponse {
        return try {
            println("📡 [ANILIST] Buscando: $pageName")
            
            val response = app.post(
                aniListApiUrl,
                data = mapOf("query" to query),
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json"
                ),
                timeout = 10_000
            )
            
            if (response.code == 200) {
                val aniListResponse = response.parsedSafe<AniListApiResponse>()
                val mediaList = aniListResponse?.data?.Page?.media ?: emptyList()
                
                println("📊 [ANILIST] ${mediaList.size} resultados encontrados para $pageName")
                
                val filteredItems = mutableListOf<SearchResponse>()
                val seenIds = mutableSetOf<Int>()
                
                // Processar em batch para melhor performance
                for (media in mediaList) {
                    if (media.id in seenIds) continue
                    seenIds.add(media.id)
                    
                    // ⭐⭐ ALTERAÇÃO AQUI: Prioridade para inglês, depois romaji
                    val aniListTitle = media.title?.english ?:      // PRIMEIRO inglês
                                     media.title?.romaji ?:          // Depois romaji  
                                     media.title?.userPreferred ?: 
                                     "Sem Título"
                    
                    // Limpar título
                    val cleanTitle = cleanAnimeTitle(aniListTitle)
                    
                    // Verificar cache primeiro
                    val cachedUrl = titleToUrlCache[cleanTitle.lowercase()]
                    val finalUrl: String
                    
                    if (cachedUrl != null) {
                        // Usar URL do cache
                        finalUrl = if (cachedUrl.startsWith("http")) cachedUrl else "anilist:${media.id}:$cleanTitle"
                        println("♻️ [CACHE] Usando URL em cache para: $cleanTitle")
                    } else {
                        // Buscar no site
                        val animeFireUrl = findAnimeFireUrl(cleanTitle)
                        finalUrl = animeFireUrl
                        
                        // Armazenar no cache (mesmo se não encontrado)
                        titleToUrlCache[cleanTitle.lowercase()] = if (animeFireUrl.startsWith("http")) animeFireUrl else "not_found"
                    }
                    
                    val finalPoster = media.coverImage?.extraLarge ?: media.coverImage?.large
                    
                    // Adicionar resultado
                    filteredItems.add(newAnimeSearchResponse(cleanTitle, finalUrl) {
                        this.posterUrl = finalPoster
                        this.type = TvType.Anime
                    })
                    
                    println("✅ [ANILIST] Adicionado: $cleanTitle → ${if (finalUrl.startsWith("http")) "SITE" else "ANILIST"}")
                    
                    // Pequena pausa para não sobrecarregar o site
                    kotlinx.coroutines.delay(50)
                }
                
                println("✅ [ANILIST] ${filteredItems.size} itens adicionados para $pageName")
                newHomePageResponse(pageName, filteredItems, hasNext = filteredItems.isNotEmpty())
            } else {
                println("❌ [ANILIST] Erro na API: ${response.code}")
                newHomePageResponse(pageName, emptyList(), false)
            }
        } catch (e: Exception) {
            println("❌ [ANILIST] Exception: ${e.message}")
            newHomePageResponse(pageName, emptyList(), false)
        }
    }

    private suspend fun findAnimeFireUrl(title: String): String {
        return try {
            val searchResults = search(title)
            
            if (searchResults.isNotEmpty()) {
                // Tenta encontrar o melhor match
                val bestMatch = findBestMatch(title, searchResults)
                if (bestMatch != null) {
                    println("🔍 [SEARCH-CACHE] Encontrado: $title → ${bestMatch.url}")
                    return bestMatch.url
                }
            }
            
            // Fallback para URL do AniList
            "anilist:0:$title"
        } catch (e: Exception) {
            println("❌ [SEARCH-CACHE] Erro ao buscar: $title - ${e.message}")
            "anilist:0:$title"
        }
    }

    private fun findBestMatch(searchTitle: String, results: List<SearchResponse>): SearchResponse? {
        if (results.isEmpty()) return null
        
        val cleanSearch = cleanForMatching(searchTitle)
        
        // Tenta encontrar match exato primeiro
        for (result in results) {
            val cleanResult = cleanForMatching(result.title)
            if (cleanResult == cleanSearch) {
                return result
            }
        }
        
        // Tenta match parcial
        for (result in results) {
            val cleanResult = cleanForMatching(result.title)
            if (cleanResult.contains(cleanSearch) || cleanSearch.contains(cleanResult)) {
                return result
            }
        }
        
        // Tenta por similaridade de palavras-chave
        val searchWords = cleanSearch.split(" ").filter { it.length > 2 }
        
        var bestMatch: SearchResponse? = null
        var bestScore = 0
        
        for (result in results) {
            val cleanResult = cleanForMatching(result.title)
            var score = 0
            
            for (word in searchWords) {
                if (cleanResult.contains(word)) {
                    score++
                }
            }
            
            if (score > bestScore) {
                bestScore = score
                bestMatch = result
            }
        }
        
        return bestMatch ?: results.first()
    }

    private fun cleanAnimeTitle(title: String): String {
        return title
            .replace(Regex("\\s*\\([^)]+\\)$"), "")
            .replace(Regex("\\s*-\\s*[^-]+$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun cleanForMatching(title: String): String {
        return title.lowercase()
            .replace(Regex("[^a-z0-9\\sáéíóúâêîôûãõç]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    override suspend fun search(query: String): List<SearchResponse> {
        println("🔍 [SEARCH] Buscando: $query")
        
        // Verificar cache primeiro
        val cacheKey = query.lowercase().trim()
        if (titleToUrlCache[cacheKey]?.startsWith("http") == true) {
            println("♻️ [SEARCH-CACHE] Cache hit para: $query")
            val cachedUrl = titleToUrlCache[cacheKey]!!
            return listOf(newAnimeSearchResponse(query, cachedUrl) {
                this.type = TvType.Anime
            })
        }
        
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "$mainUrl/pesquisar/$encodedQuery"
            
            println("🔗 [SEARCH] URL: $searchUrl")
            
            val response = app.get(searchUrl, timeout = 10_000)
            
            if (response.code == 200) {
                val document = response.document
                val results = document.select("article.card, .item, a[href*='/animes/'], a[href*='/filmes/']")
                    .mapNotNull { element ->
                        try {
                            val href = element.attr("href")
                            if (href.isBlank()) return@mapNotNull null
                            
                            val fullUrl = fixUrl(href)
                            
                            val titleElement = element.selectFirst("h3, h4, .title, .anime-title, .card-title")
                            val rawTitle = titleElement?.text()?.trim() ?: element.ownText().trim()
                            
                            // Limpar título
                            val cleanTitle = cleanSearchTitle(rawTitle)
                            
                            if (cleanTitle.isBlank()) return@mapNotNull null
                            
                            // Adicionar ao cache
                            titleToUrlCache[cleanTitle.lowercase()] = fullUrl
                            
                            val posterElement = element.selectFirst("img")
                            val poster = posterElement?.let { img ->
                                img.attr("src").takeIf { it.isNotBlank() } ?: 
                                img.attr("data-src").takeIf { it.isNotBlank() }
                            }?.let { fixUrl(it) }
                            
                            val isMovie = href.contains("/filmes/") || 
                                         cleanTitle.contains("filme", ignoreCase = true) ||
                                         cleanTitle.contains("movie", ignoreCase = true)
                            
                            newAnimeSearchResponse(cleanTitle, fullUrl) {
                                this.posterUrl = poster
                                this.type = if (isMovie) TvType.Movie else TvType.Anime
                            }
                        } catch (e: Exception) {
                            println("❌ [SEARCH] Erro ao processar elemento: ${e.message}")
                            null
                        }
                    }
                    .distinctBy { it.url }
                    .take(20)
                
                println("✅ [SEARCH] ${results.size} resultados encontrados")
                results
            } else {
                println("❌ [SEARCH] Erro HTTP: ${response.code}")
                emptyList()
            }
        } catch (e: Exception) {
            println("❌ [SEARCH] Exception: ${e.message}")
            emptyList()
        }
    }

    private fun cleanSearchTitle(title: String): String {
        return title
            .replace(Regex("\\s*-\\s*Todos os Episódios$"), "")
            .replace(Regex("\\(Dublado\\)|\\(Legendado\\)"), "")
            .replace(Regex("\\(\\d{4}\\)"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    override suspend fun load(url: String): LoadResponse {
        println("\n🚀 AnimeFire.load() para URL: $url")
        
        if (url.startsWith("anilist:")) {
            val parts = url.split(":")
            val titleFromUrl = parts.getOrNull(2) ?: "Anime do AniList"
            
            // Tenta encontrar no site
            val searchResults = search(titleFromUrl)
            val bestMatch = findBestMatch(titleFromUrl, searchResults)
            
            if (bestMatch != null) {
                println("✅ [LOAD] Encontrado no site: ${bestMatch.title}")
                return loadFromAnimeFire(bestMatch.url)
            } else {
                println("⚠️ [LOAD] Não encontrado no site: $titleFromUrl")
                return newAnimeLoadResponse(titleFromUrl, url, TvType.Anime) {
                    this.plot = "📡 Este anime está na lista mas não foi encontrado no AnimeFire.\n\nTente buscar manualmente ou verifique se o nome está correto."
                }
            }
        }
        
        return loadFromAnimeFire(url)
    }

    private suspend fun loadFromAnimeFire(url: String): LoadResponse {
        return try {
            val document = app.get(url).document
            
            val titleElement = document.selectFirst("h1, .title-anime, .anime-title")
            val title = titleElement?.text()?.trim() ?: "Sem Título"
            
            val plotElement = document.selectFirst(".sinopse, .description, .plot")
            val plot = plotElement?.text()?.trim() ?: "Sem descrição"
            
            val posterElement = document.selectFirst(".anime-poster img, .poster img, .cover img")
            val poster = posterElement?.attr("src")?.let { fixUrl(it) } ?:
                       posterElement?.attr("data-src")?.let { fixUrl(it) }
            
            // Adicionar ao cache
            titleToUrlCache[title.lowercase()] = url
            
            newAnimeLoadResponse(title, url, TvType.Anime) {
                this.plot = plot
                this.posterUrl = poster
                this.type = TvType.Anime
            }
        } catch (e: Exception) {
            println("❌ Erro ao carregar do AnimeFire: ${e.message}")
            newAnimeLoadResponse("Erro", url, TvType.Anime) {
                this.plot = "Não foi possível carregar este anime."
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("⚠️ Extração de links não implementada ainda")
        return false
    }

    // Classes de dados para o AniList
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListApiResponse(
        @JsonProperty("data") val data: AniListData? = null
    )
    
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListMediaResponse(
        @JsonProperty("data") val data: AniListMediaData? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListData(
        @JsonProperty("Page") val Page: AniListPage? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListMediaData(
        @JsonProperty("Media") val Media: AniListMedia? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListPage(
        @JsonProperty("media") val media: List<AniListMedia>? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListMedia(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: AniListTitle? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("format") val format: String? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("episodes") val episodes: Int? = null,
        @JsonProperty("duration") val duration: Int? = null,
        @JsonProperty("coverImage") val coverImage: AniListCoverImage? = null,
        @JsonProperty("bannerImage") val bannerImage: String? = null,
        @JsonProperty("genres") val genres: List<String>? = null,
        @JsonProperty("siteUrl") val siteUrl: String? = null,
        @JsonProperty("startDate") val startDate: AniListDate? = null,
        @JsonProperty("synonyms") val synonyms: List<String>? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListTitle(
        @JsonProperty("romaji") val romaji: String? = null,
        @JsonProperty("english") val english: String? = null,
        @JsonProperty("native") val native: String? = null,
        @JsonProperty("userPreferred") val userPreferred: String? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListCoverImage(
        @JsonProperty("large") val large: String? = null,
        @JsonProperty("extraLarge") val extraLarge: String? = null,
        @JsonProperty("color") val color: String? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListDate(
        @JsonProperty("year") val year: Int? = null,
        @JsonProperty("month") val month: Int? = null,
        @JsonProperty("day") val day: Int? = null
    )
}
