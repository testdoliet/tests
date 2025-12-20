package com.AnimeFire

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
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

    // ============ ABAS DA PÁGINA INICIAL ============
    override val mainPage = mainPageOf(
        "$mainUrl" to "Lançamentos",
        "$mainUrl" to "Destaques da Semana",
        "$mainUrl" to "Últimos Animes Adicionados", 
        "$mainUrl" to "Últimos Episódios Adicionados",
        "anilist_upcoming" to "Próxima Temporada (AniList)"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return when (request.name) {
            "Próxima Temporada (AniList)" -> getAniListUpcoming(page)
            else -> getAnimeFireHomePage(request.name)
        }
    }

    // ============ PÁGINA INICIAL DO ANIMEFIRE ============
    private suspend fun getAnimeFireHomePage(pageName: String): HomePageResponse {
        return try {
            println("🏠 [HOMEPAGE] Carregando aba: $pageName")
            
            val response = app.get(mainUrl, headers = headers, timeout = 15_000)
            
            if (response.code != 200) {
                println("❌ [HOMEPAGE] Erro HTTP: ${response.code}")
                return newHomePageResponse(pageName, emptyList(), false)
            }
            
            val document = response.document
            
            val homeItems = when (pageName) {
                "Lançamentos" -> 
                    document.select(".owl-carousel-home .divArticleLancamentos a.item, .owl-carousel:first-child a.item")
                        .mapNotNull { element -> 
                            runCatching { element.toSearchResponse() }.getOrNull()
                        }
                
                "Destaques da Semana" -> 
                    document.select(".owl-carousel-semana .divArticleLancamentos a.item, .owl-carousel:nth-child(2) a.item")
                        .mapNotNull { element -> 
                            runCatching { element.toSearchResponse() }.getOrNull()
                        }
                
                "Últimos Animes Adicionados" -> 
                    document.select(".owl-carousel-l_dia .divArticleLancamentos a.item, .owl-carousel:nth-child(3) a.item")
                        .mapNotNull { element -> 
                            runCatching { element.toSearchResponse() }.getOrNull()
                        }
                
                "Últimos Episódios Adicionados" -> {
                    document.select(".divCardUltimosEpsHome, .divListaEpsHome article.card").mapNotNull { card ->
                        runCatching {
                            val link = card.selectFirst("a") ?: return@runCatching null
                            val href = link.attr("href").takeIf { it.isNotEmpty() } ?: return@runCatching null
                            
                            val titleElement = card.selectFirst("h3.animeTitle, .title, h3") ?: return@runCasting null
                            val rawTitle = titleElement.text().trim()
                            
                            // Extrair número do episódio
                            val epNumber = card.selectFirst(".numEp, .ep-number, [class*='ep']")?.text()?.let {
                                Regex("(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull()
                            } ?: 1
                            
                            // Limpar título
                            val cleanTitle = rawTitle
                                .replace(Regex("(?i)(dublado|legendado|todos os episódios|\\(\\d{4}\\)|\\s*-\\s*$)"), "")
                                .trim()
                            
                            val displayTitle = if (cleanTitle.isNotEmpty()) {
                                "$cleanTitle - Episódio $epNumber"
                            } else {
                                "Episódio $epNumber"
                            }
                            
                            // Extrair imagem
                            val sitePoster = card.selectFirst("img.imgAnimesUltimosEps, img.imgAnimes, img[src*='animes']")?.let { img ->
                                when {
                                    img.hasAttr("data-src") -> img.attr("data-src")
                                    img.hasAttr("src") -> img.attr("src")
                                    else -> null
                                }?.takeIf { !it.contains("logo", ignoreCase = true) }
                            } ?: card.selectFirst("img:not([src*='logo'])")?.attr("src")
                            
                            newAnimeSearchResponse(displayTitle, fixUrl(href)) {
                                this.posterUrl = sitePoster?.let { fixUrl(it) }
                                this.type = TvType.Anime
                            }
                        }.getOrNull()
                    }
                }
                
                else -> emptyList()
            }
            
            println("✅ [HOMEPAGE] ${homeItems.size} itens encontrados para: $pageName")
            newHomePageResponse(pageName, homeItems.distinctBy { it.url }, false)
            
        } catch (e: Exception) {
            println("❌ [HOMEPAGE] Exception: ${e.message}")
            newHomePageResponse(pageName, emptyList(), false)
        }
    }

    private suspend fun Element.toSearchResponse(): AnimeSearchResponse? {
        val href = attr("href").takeIf { it.isNotEmpty() } ?: return null
        
        val titleElement = selectFirst("h3.animeTitle, .text-block h3, .animeTitle, h3, .title, .card-title")
        val rawTitle = titleElement?.text()?.trim() ?: return null
        
        val cleanTitle = rawTitle
            .replace(Regex("(?i)(dublado|legendado|todos os episódios|\\(\\d{4}\\)|\\s*-\\s*$|\\(movie\\))"), "")
            .trim()
        
        val isMovie = href.contains("/filmes/") || 
                      rawTitle.contains("filme", ignoreCase = true) ||
                      rawTitle.contains("movie", ignoreCase = true)
        
        val sitePoster = selectFirst("img.imgAnimes, img.card-img-top, img.transitioning_src, img.owl-lazy, img[src*='animes']")?.let { img ->
            when {
                img.hasAttr("data-src") -> img.attr("data-src")
                img.hasAttr("src") -> img.attr("src")
                else -> null
            }?.takeIf { !it.contains("logo", ignoreCase = true) }
        } ?: selectFirst("img:not([src*='logo']):not([src*='Logo'])")?.attr("src")

        return newAnimeSearchResponse(cleanTitle, fixUrl(href)) {
            this.posterUrl = sitePoster?.let { fixUrl(it) }
            this.type = if (isMovie) TvType.Movie else TvType.Anime
        }
    }

    // ============ PRÓXIMA TEMPORADA (ANILIST) ============
    private suspend fun getAniListUpcoming(page: Int): HomePageResponse {
        val query = """
            query {
                Page(page: $page, perPage: 20) {
                    media(season: SPRING, seasonYear: 2025, type: ANIME, sort: POPULARITY_DESC) {
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
        
        return executeAniListQuery(query, "Próxima Temporada (AniList)", page)
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

    // ============ BUSCA NO ANIMEFIRE ============
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
                    val first = elements.firstOrNull()
                    if (first != null && first.hasAttr("href")) {
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

    // ============ LOAD (MANTIDO COMPLETO) ============
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

            // 1. TÍTULO E INFORMAÇÕES BÁSICAS
            val titleElement = document.selectFirst("h1.quicksand400, .main_div_anime_info h1, h1") ?: 
                throw ErrorLoadingException("Título não encontrado")
            val rawTitle = titleElement.text().trim()
            
            val year = Regex("\\((\\d{4})\\)").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
            val cleanTitle = rawTitle.replace(Regex("\\(\\d{4}\\)"), "").trim()
            
            val isMovie = url.contains("/filmes/") || rawTitle.contains("Movie", ignoreCase = true)
            val type = if (isMovie) TvType.Movie else TvType.Anime

            println("📌 Título: $cleanTitle, Ano: $year, Tipo: $type")

            // 2. SINOPSE
            val plotElement = document.selectFirst("div.divSinopse, .sinopse, .description")
            val plot = plotElement?.text()?.trim()

            // 3. POSTER
            val posterImg = document.selectFirst(".sub_animepage_img img, .poster img, img[src*='/img/animes/']")
            val poster = when {
                posterImg?.hasAttr("src") == true -> fixUrl(posterImg.attr("src"))
                posterImg?.hasAttr("data-src") == true -> fixUrl(posterImg.attr("data-src"))
                else -> null
            }

            // 4. AVALIAÇÃO
            val ratingElement = document.selectFirst(".rating, .score, [class*='rating'], [class*='score']")
            val ratingText = ratingElement?.text()?.trim()
            val rating = ratingText?.let {
                Regex("([0-9.]+)").find(it)?.groupValues?.get(1)?.toFloatOrNull()
            }

            // 5. ELENCO E INFORMAÇÕES ADICIONAIS
            val infoElements = document.select(".anime-info, .info, .details, .anime-details")
            val additionalInfo = mutableListOf<String>()
            
            infoElements.forEach { info ->
                val text = info.text().trim()
                if (text.isNotEmpty() && text.length < 200) { // Limitar tamanho
                    additionalInfo.add(text)
                }
            }
            
            // 6. GÊNEROS
            val genres = document.select(".genre, .genres a, .tag, [class*='genre']")
                .mapNotNull { it.text().trim().takeIf { t -> t.isNotEmpty() } }
                .distinct()

            // 7. EPISÓDIOS (se não for filme)
            val episodes = if (!isMovie) {
                document.select("a.lEp, .episode-item, [href*='/video/'], .divListaEps a")
                    .mapIndexedNotNull { index, element ->
                        try {
                            val href = element.attr("href")
                            if (href.isBlank()) return@mapIndexedNotNull null
                            
                            val text = element.text().trim()
                            val epNumber = extractEpisodeNumber(text) ?: (index + 1)
                            
                            // Título do episódio
                            val epName = element.selectFirst(".ep-name, .title, .name")?.text()?.trim()
                                ?: text.substringAfterLast("-").trim()
                                ?: "Episódio $epNumber"
                            
                            // Data do episódio (se disponível)
                            val dateElement = element.selectFirst(".date, .time, .added")
                            val date = dateElement?.text()?.trim()
                            
                            newEpisode(fixUrl(href)) {
                                this.name = epName
                                this.season = 1
                                this.episode = epNumber
                                this.posterUrl = null
                                this.description = null
                                this.date = date
                            }
                        } catch (e: Exception) {
                            null
                        }
                    }
                    .sortedBy { it.episode }
            } else {
                emptyList()
            }

            // 8. RECOMENDAÇÕES
            val recommendations = document.select(".owl-carousel-anime .divArticleLancamentos a.item, .recommendations a, .similar a")
                .mapNotNull { element -> 
                    runCatching { 
                        val href = element.attr("href")
                        val titleEl = element.selectFirst("h3.animeTitle, .text-block h3, .title")
                        val title = titleEl?.text()?.trim() ?: "Sem Título"
                        
                        newAnimeSearchResponse(title, fixUrl(href)) {
                            val img = element.selectFirst("img.imgAnimes, img.card-img-top, img")
                            this.posterUrl = img?.attr("src")?.let { fixUrl(it) }
                            this.type = TvType.Anime
                        }
                    }.getOrNull()
                }

            // 9. CRIAR RESPOSTA COMPLETA
            if (isMovie) {
                newMovieLoadResponse(cleanTitle, url, type, url) {
                    this.year = year
                    this.plot = plot
                    this.posterUrl = poster
                    this.type = type
                    this.rating = rating
                    this.tags = genres
                    this.recommendations = recommendations.takeIf { it.isNotEmpty() }
                }
            } else {
                newAnimeLoadResponse(cleanTitle, url, type) {
                    addEpisodes(DubStatus.Subbed, episodes)
                    this.year = year
                    this.plot = plot
                    this.posterUrl = poster
                    this.type = type
                    this.rating = rating
                    this.tags = genres
                    this.recommendations = recommendations.takeIf { it.isNotEmpty() }
                }
            }
        } catch (e: Exception) {
            println("❌ Erro ao carregar: ${e.message}")
            newAnimeLoadResponse("Erro", url, TvType.Anime) {
                this.plot = "Não foi possível carregar este anime."
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

    // ============ CLASSES DE DADOS ANILIST ============
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
