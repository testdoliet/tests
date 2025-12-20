package com.AnimeFire

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.google.gson.JsonParser
import org.jsoup.nodes.Element
import java.net.URLEncoder
import kotlinx.coroutines.delay

class AnimeFire : MainAPI() {
    override var mainUrl = "https://animefire.io"
    override var name = "ANIMEFIRE"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie, TvType.OVA)
    override val usesWebView = false

    private val aniListApiUrl = "https://graphql.anilist.co"
    private val titleToUrlCache = mutableMapOf<String, String>()

    companion object {
        private const val SEARCH_PATH = "/pesquisar"
        private const val REQUEST_DELAY_MS = 1000L
    }

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "https://animefire.io/",
        "Connection" to "keep-alive"
    )

    override val mainPage = mainPageOf(
        "trending" to "Em Alta (AniList)",
        "popular" to "Populares (AniList)", 
        "top" to "Top 100 (AniList)"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            when (request.name) {
                "Em Alta (AniList)" -> getAniListTrending(page)
                "Populares (AniList)" -> getAniListPopular(page)
                "Top 100 (AniList)" -> getAniListTop(page)
                else -> newHomePageResponse(request.name, emptyList(), false)
            }
        } catch (e: Exception) {
            println("❌ Erro página principal: ${e.message}")
            newHomePageResponse(request.name, emptyList(), false)
        }
    }

    private suspend fun getAniListTrending(page: Int): HomePageResponse {
        val query = """
            query {
                Page(page: $page, perPage: 10) {
                    media(sort: TRENDING_DESC, type: ANIME) {
                        id
                        title {
                            romaji
                            english
                        }
                        coverImage {
                            extraLarge
                        }
                    }
                }
            }
        """.trimIndent()
        
        return executeAniListQuery(query, "Em Alta (AniList)", page)
    }

    private suspend fun getAniListPopular(page: Int): HomePageResponse {
        val query = """
            query {
                Page(page: $page, perPage: 10) {
                    media(sort: POPULARITY_DESC, type: ANIME) {
                        id
                        title {
                            romaji
                            english
                        }
                        coverImage {
                            extraLarge
                        }
                    }
                }
            }
        """.trimIndent()
        
        return executeAniListQuery(query, "Populares (AniList)", page)
    }

    private suspend fun getAniListTop(page: Int): HomePageResponse {
        val query = """
            query {
                Page(page: $page, perPage: 10) {
                    media(sort: SCORE_DESC, type: ANIME) {
                        id
                        title {
                            romaji
                            english
                        }
                        coverImage {
                            extraLarge
                        }
                    }
                }
            }
        """.trimIndent()
        
        return executeAniListQuery(query, "Top 100 (AniList)", page)
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
                headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
                timeout = 10_000
            )
            
            if (response.code == 200) {
                val aniListResponse = response.parsedSafe<AniListApiResponse>()
                val mediaList = aniListResponse?.data?.Page?.media ?: emptyList()
                
                println("📊 [ANILIST] ${mediaList.size} resultados")
                
                val filteredItems = mutableListOf<SearchResponse>()
                
                for (media in mediaList) {
                    val title = media.title?.english ?: media.title?.romaji ?: "Sem Título"
                    val cleanTitle = cleanAnimeTitle(title)
                    val posterUrl = media.coverImage?.extraLarge
                    
                    println("🔍 [ANILIST] Processando: $cleanTitle")
                    
                    val searchResult = searchAnimeFire(cleanTitle)
                    
                    if (searchResult != null) {
                        println("✅ [ANILIST] Encontrado: ${searchResult.name}")
                        
                        filteredItems.add(newAnimeSearchResponse(cleanTitle, searchResult.url) {
                            this.posterUrl = posterUrl ?: searchResult.posterUrl
                            this.type = searchResult.type
                        })
                        
                        delay(REQUEST_DELAY_MS)
                    } else {
                        println("⚠️ [ANILIST] Não encontrado: $cleanTitle")
                    }
                }
                
                println("✅ [ANILIST] ${filteredItems.size} itens adicionados")
                newHomePageResponse(pageName, filteredItems, hasNext = filteredItems.isNotEmpty())
            } else {
                println("❌ [ANILIST] Erro: ${response.code}")
                newHomePageResponse(pageName, emptyList(), false)
            }
        } catch (e: Exception) {
            println("❌ [ANILIST] Exception: ${e.message}")
            newHomePageResponse(pageName, emptyList(), false)
        }
    }

    private fun cleanAnimeTitle(title: String): String {
        return title
            .replace(Regex("\\s*\\([^)]+\\)$"), "")
            .replace(Regex("\\s*-\\s*[^-]+$"), "")
            .replace(Regex("\\s+"), " ")
            .replace(Regex("[^a-zA-Z0-9\\s-]"), "")
            .trim()
    }

    override suspend fun search(query: String): List<SearchResponse> {
        println("🔍 [SEARCH] Buscando: '$query'")
        val result = searchAnimeFire(query)
        return if (result != null) listOf(result) else emptyList()
    }
    
    private suspend fun searchAnimeFire(query: String): SearchResponse? {
        println("🔍 [ANIMEFIRE] Buscando: '$query'")
        
        val slug = createSearchSlug(query)
        val searchUrl = "$mainUrl$SEARCH_PATH/$slug"
        println("🔗 [ANIMEFIRE] URL: $searchUrl")
        
        return try {
            delay(REQUEST_DELAY_MS)
            
            val response = app.get(searchUrl, headers = headers, timeout = 15_000)
            
            when {
                response.code == 429 -> {
                    println("⚠️ [ANIMEFIRE] Erro 429 - Aguardando...")
                    delay(3000)
                    return searchAnimeFire(query)
                }
                response.code == 403 -> {
                    println("❌ [ANIMEFIRE] Acesso negado")
                    return null
                }
                response.code != 200 -> {
                    println("❌ [ANIMEFIRE] Erro HTTP: ${response.code}")
                    return null
                }
                else -> {
                    val document = response.document
                    
                    // Busca com seletores
                    val firstResult = findFirstSearchResult(document)
                    
                    if (firstResult == null) {
                        println("⚠️ [ANIMEFIRE] Nenhum resultado")
                        return tryAlternativeSelectors(document, query)
                    }
                    
                    val href = firstResult.attr("href")
                    if (href.isBlank() || (!href.contains("/animes/") && !href.contains("/filmes/"))) {
                        println("⚠️ [ANIMEFIRE] Link inválido: $href")
                        return tryAlternativeSelectors(document, query)
                    }
                    
                    val finalUrl = fixUrl(href)
                    val title = extractTitleFromElement(firstResult) ?: query
                    val posterUrl = extractImageFromElement(firstResult)
                    
                    val isMovie = finalUrl.contains("/filmes/") || 
                                 title.contains("filme", ignoreCase = true) ||
                                 title.contains("movie", ignoreCase = true)
                    
                    println("✅ [ANIMEFIRE] Encontrado: '$title' -> $finalUrl")
                    
                    titleToUrlCache[query.lowercase()] = finalUrl
                    
                    return newAnimeSearchResponse(title, finalUrl) {
                        this.posterUrl = posterUrl
                        this.type = if (isMovie) TvType.Movie else TvType.Anime
                    }
                }
            }
        } catch (e: Exception) {
            println("❌ [ANIMEFIRE] Exception: ${e.message}")
            null
        }
    }
    
    private fun findFirstSearchResult(document: org.jsoup.nodes.Document): Element? {
        val selectors = listOf(
            "div.divCardUltimosEps article.card a",
            "div.divListaAnimes article.card a",
            "article.card a",
            "a[href*='/animes/']",
            "a[href*='/filmes/']"
        )
        
        for (selector in selectors) {
            try {
                val elements = document.select(selector)
                if (elements.isNotEmpty()) {
                    val first = elements.first()
                    if (first.hasAttr("href")) {
                        val href = first.attr("href")
                        if (href.contains("/animes/") || href.contains("/filmes/")) {
                            println("🎯 [SELECTOR] Encontrado com: $selector")
                            return first
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignorar
            }
        }
        
        return null
    }
    
    private suspend fun tryAlternativeSelectors(document: org.jsoup.nodes.Document, query: String): SearchResponse? {
        println("🔄 [ANIMEFIRE] Tentando seletores alternativos...")
        
        val allLinks = document.select("a")
        for (link in allLinks) {
            val href = link.attr("href")
            if (href.contains("/animes/") || href.contains("/filmes/")) {
                val title = extractTitleFromElement(link) ?: query
                val finalUrl = fixUrl(href)
                
                println("✅ [ALTERNATIVE] Encontrado: '$title' -> $finalUrl")
                
                return newAnimeSearchResponse(title, finalUrl) {
                    this.posterUrl = extractImageFromElement(link)
                    this.type = if (finalUrl.contains("/filmes/")) TvType.Movie else TvType.Anime
                }
            }
        }
        
        return null
    }
    
    private fun extractTitleFromElement(element: Element): String? {
        val titleSelectors = listOf(
            "h3.animeTitle",
            ".animeTitle",
            ".title",
            "h3",
            ".card-title",
            ".name"
        )
        
        for (selector in titleSelectors) {
            val titleEl = element.selectFirst(selector)
            if (titleEl != null) {
                var text = titleEl.text().trim()
                if (text.isNotEmpty()) {
                    text = text
                        .replace(Regex("\\s*-\\s*Todos os Episódios$"), "")
                        .replace(Regex("\\s*\\([^)]+\\)$"), "")
                        .replace("(Dublado)", "")
                        .replace("(Legendado)", "")
                        .trim()
                    
                    if (text.isNotEmpty()) return text
                }
            }
        }
        
        return element.attr("title").takeIf { it.isNotEmpty() } ?: 
               element.attr("alt").takeIf { it.isNotEmpty() }
    }
    
    // FUNÇÃO CORRIGIDA - Sem erros de nullable
    private fun extractImageFromElement(element: Element): String? {
        val imgSelectors = listOf(
            "img.imgAnimes",
            "img[data-src]",
            "img[src*='/img/']",
            "img.card-img-top",
            "img"
        )
        
        for (selector in imgSelectors) {
            val img = element.selectFirst(selector)
            if (img != null) {
                // Verificar atributos de forma segura
                if (img.hasAttr("data-src")) {
                    val src = img.attr("data-src")
                    if (src.isNotEmpty()) {
                        return fixUrl(src)
                    }
                }
                if (img.hasAttr("src")) {
                    val src = img.attr("src")
                    if (src.isNotEmpty()) {
                        return fixUrl(src)
                    }
                }
            }
        }
        
        return null
    }
    
    private fun createSearchSlug(query: String): String {
        return query.lowercase()
            .replace(Regex("[áàâãä]"), "a")
            .replace(Regex("[éèêë]"), "e")
            .replace(Regex("[íìîï]"), "i")
            .replace(Regex("[óòôõö]"), "o")
            .replace(Regex("[úùûü]"), "u")
            .replace("ç", "c")
            .replace("ñ", "n")
            .replace(Regex("[^a-z0-9\\s-]"), "")
            .replace(Regex("\\s+"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
    }

    override suspend fun load(url: String): LoadResponse {
        println("\n🚀 AnimeFire.load() para URL: $url")
        return loadFromAnimeFire(url)
    }

    private suspend fun loadFromAnimeFire(url: String): LoadResponse {
        return try {
            delay(REQUEST_DELAY_MS)
            
            val response = app.get(url, headers = headers, timeout = 15_000)
            
            if (response.code == 429) {
                println("⚠️ [LOAD] Erro 429 - Aguardando...")
                delay(3000)
                return loadFromAnimeFire(url)
            }
            
            if (response.code != 200) {
                println("❌ [LOAD] Erro HTTP: ${response.code}")
                throw ErrorLoadingException("Erro ao carregar")
            }
            
            val document = response.document

            val titleElement = document.selectFirst("h1.quicksand400, .main_div_anime_info h1, h1") ?: 
                throw ErrorLoadingException("Título não encontrado")
            val rawTitle = titleElement.text().trim()
            
            val year = Regex("\\((\\d{4})\\)").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
            val cleanTitle = rawTitle.replace(Regex("\\(\\d{4}\\)"), "").trim()
            
            val isMovie = url.contains("/filmes/") || rawTitle.contains("Movie", ignoreCase = true)
            val type = if (isMovie) TvType.Movie else TvType.Anime

            println("📌 Título: $cleanTitle, Ano: $year, Tipo: $type")

            val plotElement = document.selectFirst("div.divSinopse, .sinopse, .description")
            val plot = plotElement?.text()?.trim()

            // CORRIGIDO: usando safe calls
            val posterImg = document.selectFirst(".sub_animepage_img img, .poster img, img[src*='/img/animes/']")
            val poster = when {
                posterImg?.hasAttr("src") == true -> fixUrl(posterImg.attr("src"))
                posterImg?.hasAttr("data-src") == true -> fixUrl(posterImg.attr("data-src"))
                else -> null
            }

            val episodes = if (!isMovie) {
                document.select("a.lEp, .episode-item, [href*='/video/']")
                    .mapIndexedNotNull { index, element ->
                        try {
                            val href = element.attr("href")
                            if (href.isBlank()) return@mapIndexedNotNull null
                            
                            val text = element.text().trim()
                            val epNumber = extractEpisodeNumber(text) ?: (index + 1)
                            
                            newEpisode(fixUrl(href)) {
                                this.name = "Episódio $epNumber"
                                this.season = 1
                                this.episode = epNumber
                            }
                        } catch (e: Exception) {
                            null
                        }
                    }
                    .sortedBy { it.episode }
            } else {
                emptyList()
            }

            if (isMovie) {
                newMovieLoadResponse(cleanTitle, url, type, url) {
                    this.year = year
                    this.plot = plot
                    this.posterUrl = poster
                    this.type = type
                }
            } else {
                newAnimeLoadResponse(cleanTitle, url, type) {
                    addEpisodes(DubStatus.Subbed, episodes)
                    this.year = year
                    this.plot = plot
                    this.posterUrl = poster
                    this.type = type
                }
            }
        } catch (e: Exception) {
            println("❌ Erro ao carregar: ${e.message}")
            newAnimeLoadResponse("Erro", url, TvType.Anime) {
                this.plot = "Não foi possível carregar."
            }
        }
    }

    private fun extractEpisodeNumber(text: String): Int? {
        return Regex("(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return false
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListApiResponse(
        @JsonProperty("data") val data: AniListData? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListData(
        @JsonProperty("Page") val Page: AniListPage? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListPage(
        @JsonProperty("media") val media: List<AniListMedia>? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListMedia(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: AniListTitle? = null,
        @JsonProperty("coverImage") val coverImage: AniListCoverImage? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListTitle(
        @JsonProperty("romaji") val romaji: String? = null,
        @JsonProperty("english") val english: String? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListCoverImage(
        @JsonProperty("extraLarge") val extraLarge: String? = null
    )
}
