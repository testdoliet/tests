package com.AnimeFire

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import kotlinx.coroutines.delay
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.gson.JsonParser
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

class AnimeFire : MainAPI() {
    override var mainUrl = "https://animefire.plus"
    override var name = "AnimeFire (com AniList)"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie, TvType.OVA)
    override val usesWebView = true

    companion object {
        private const val SEARCH_PATH = "/pesquisar"
        private const val MAX_TRIES = 3
        private const val RETRY_DELAY = 1000L
        private const val tmdbImageUrl = "https://image.tmdb.org/t/p"
        private const val TRANSLATION_ENABLED = true
        private const val WORKERS_URL = "https://animefire.euluan1912.workers.dev"
        private const val MAX_CACHE_SIZE = 500
    }

    // ============ TMDB COM BuildConfig ============
    private val tmdbApiKey = BuildConfig.TMDB_API_KEY
    private val tmdbAccessToken = BuildConfig.TMDB_ACCESS_TOKEN
    private val tmdbBaseUrl = "https://api.themoviedb.org/3"

    // URLs do AniList
    private val aniListUrl = "https://anilist.co"
    
    // MainPage com abas do AnimeFire E AniList
    override val mainPage = mainPageOf(
        // Abas ORIGINAIS do AnimeFire (mantidas)
        "$mainUrl" to "Lançamentos",
        "$mainUrl" to "Destaques da Semana",
        "$mainUrl" to "Últimos Animes Adicionados",
        "$mainUrl" to "Últimos Episódios Adicionados",
        
        // NOVAS abas do AniList (adicionadas)
        "anilist-trending" to "AniList: Em Alta",
        "anilist-season" to "AniList: Esta Temporada", 
        "anilist-popular" to "AniList: Populares",
        "anilist-top" to "AniList: Top 100"
    )

    // ============ CACHE SIMPLES EM MEMÓRIA ============
    private val translationCache = mutableMapOf<String, String>()
    private val cacheHits = mutableMapOf<String, Int>()
    private val aniListCache = mutableMapOf<String, List<AnimeSearchResponse>>()

    // ============ FUNÇÕES DE TRADUÇÃO (MANTIDAS) ============
    
    private suspend fun translateWithCache(text: String): String {
        if (!TRANSLATION_ENABLED || text.isBlank() || text.length < 3) return text
        if (isProbablyPortuguese(text)) return text
        
        val cached = translationCache[text]
        if (cached != null) {
            cacheHits[text] = (cacheHits[text] ?: 0) + 1
            return cached
        }
        
        val translated = translateText(text)
        
        if (translated != text && translated.isNotBlank()) {
            if (translationCache.size >= MAX_CACHE_SIZE) {
                val leastUsed = cacheHits.entries.sortedBy { it.value }.firstOrNull()
                leastUsed?.key?.let { 
                    translationCache.remove(it)
                    cacheHits.remove(it)
                }
            }
            
            translationCache[text] = translated
            cacheHits[text] = 0
        }
        
        return translated
    }
    
    private suspend fun translateText(text: String): String {
        if (!TRANSLATION_ENABLED || text.isBlank() || text.length < 3) return text
        if (isProbablyPortuguese(text)) return text
        
        return try {
            val workersTranslated = translateWithWorkers(text)
            if (workersTranslated != text) return workersTranslated
            
            translateDirectGoogle(text)
        } catch (e: Exception) {
            text
        }
    }
    
    private fun isProbablyPortuguese(text: String): Boolean {
        val portugueseWords = listOf("episódio", "temporada", "sinopse", "dublado", 
            "legendado", "assistir", "anime", "filme", "série", "ação", "aventura")
        
        val lowerText = text.lowercase()
        return portugueseWords.any { lowerText.contains(it) } ||
               lowerText.contains(Regex("[áéíóúãõç]"))
    }
    
    private suspend fun translateWithWorkers(text: String): String {
        return try {
            val encodedText = URLEncoder.encode(text, "UTF-8")
            val url = "$WORKERS_URL/translate?text=$encodedText&target=pt"
            
            val response = app.get(url, timeout = 5000)
            
            if (response.code == 200) {
                val json = JsonParser.parseString(response.text)
                
                val translated = when {
                    json.isJsonObject -> {
                        json.asJsonObject.get("translatedText")?.asString
                            ?: json.asJsonObject.get("text")?.asString
                    }
                    json.isJsonPrimitive -> json.asString
                    else -> null
                }
                
                translated?.takeIf { it.isNotBlank() && it != text } ?: text
            } else {
                text
            }
        } catch (e: Exception) {
            text
        }
    }
    
    private suspend fun translateDirectGoogle(text: String): String {
        return try {
            val encodedText = URLEncoder.encode(text, "UTF-8")
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=pt&dt=t&q=$encodedText"
            
            val response = app.get(url, timeout = 3000)
            
            if (response.code == 200) {
                val json = JsonParser.parseString(response.text)
                val translated = StringBuilder()
                
                json.asJsonArray?.get(0)?.asJsonArray?.forEach { arrayElement ->
                    arrayElement.asJsonArray?.get(0)?.asString?.let { 
                        translated.append(it) 
                    }
                }
                
                translated.toString().ifBlank { text }
            } else {
                text
            }
        } catch (e: Exception) {
            text
        }
    }

    // ============ CLASSES DE DADOS ============
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniZipImage(
        @JsonProperty("coverType") val coverType: String?,
        @JsonProperty("url") val url: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniZipEpisode(
        @JsonProperty("episode") val episode: String?,
        @JsonProperty("title") val title: Map<String, String>?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("image") val image: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("rating") val rating: String?,
        @JsonProperty("airDateUtc") val airDateUtc: String?,
        @JsonProperty("finaleType") val finaleType: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniZipData(
        @JsonProperty("titles") val titles: Map<String, String>? = null,
        @JsonProperty("images") val images: List<AniZipImage>? = null,
        @JsonProperty("episodes") val episodes: Map<String, AniZipEpisode>? = null
    )

    // ============ FUNÇÃO AUXILIAR DE BUSCA ============
    
    private suspend fun Element.toSearchResponse(): AnimeSearchResponse? {
        val href = attr("href") ?: return null
        if (href.isBlank()) return null
        
        val titleElement = when {
            selectFirst("h3.animeTitle") != null -> selectFirst("h3.animeTitle")
            selectFirst(".text-block h3") != null -> selectFirst(".text-block h3")
            selectFirst(".animeTitle") != null -> selectFirst(".animeTitle")
            else -> selectFirst("h3")
        } ?: return null
        
        val rawTitle = titleElement.text().trim()
        
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val currentName = request.name
        
        // Verificar se é uma aba do AniList pelo nome
        return when {
            currentName.contains("AniList") -> {
                getAniListMainPage(currentName, page)
            }
            else -> {
                // Abas normais do AnimeFire
                getAnimeFireMainPage(currentName, page)
            }
        }
    }

    private suspend fun getAnimeFireMainPage(
        pageName: String,
        page: Int
    ): HomePageResponse {
        val document = app.get(mainUrl).document
        
        val homeItems = when (pageName) {
            "Lançamentos" -> 
                document.select(".owl-carousel-home .divArticleLancamentos a.item")
                    .mapNotNull { element -> 
                        runCatching { element.toSearchResponse() }.getOrNull()
                    }
            "Destaques da Semana" -> 
                document.select(".owl-carousel-semana .divArticleLancamentos a.item")
                    .mapNotNull { element -> 
                        runCatching { element.toSearchResponse() }.getOrNull()
                    }
            "Últimos Animes Adicionados" -> 
                document.select(".owl-carousel-l_dia .divArticleLancamentos a.item")
                    .mapNotNull { element -> 
                        runCatching { element.toSearchResponse() }.getOrNull()
                    }
            "Últimos Episódios Adicionados" -> {
                document.select(".divCardUltimosEpsHome").mapNotNull { card ->
                    runCatching {
                        val link = card.selectFirst("article.card a") ?: return@runCatching null
                        val href = link.attr("href") ?: return@runCatching null
                        
                        val titleElement = card.selectFirst("h3.animeTitle") ?: return@runCatching null
                        val rawTitle = titleElement.text().trim()
                        
                        val epNumber = card.selectFirst(".numEp")?.text()?.toIntOrNull() ?: 1
                        val cleanTitle = rawTitle.replace(Regex("(?i)(dublado|legendado|todos os episódios|\\(\\d{4}\\))$"), "").trim()
                        val displayTitle = "${cleanTitle} - Episódio $epNumber"
                        
                        val sitePoster = card.selectFirst("img.imgAnimesUltimosEps, img[src*='animes']")?.let { img ->
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
        
        return newHomePageResponse(pageName, homeItems.distinctBy { it.url }, false)
    }

    private suspend fun getAniListMainPage(
        pageName: String,
        page: Int
    ): HomePageResponse {
        // Verificar cache primeiro
        val cacheKey = "$pageName-$page"
        val cached = aniListCache[cacheKey]
        if (cached != null) {
            println("⚡ [CACHE] Usando cache para: $pageName")
            return newHomePageResponse(pageName, cached, false)
        }

        println("🌐 [ANILIST] Carregando: $pageName")
        
        val items = mutableListOf<AnimeSearchResponse>()
        
        // Determinar qual endpoint do AniList usar baseado no nome da página
        val endpoint = when (pageName) {
            "AniList: Em Alta" -> "search/anime"
            "AniList: Esta Temporada" -> "search/anime/this-season"
            "AniList: Populares" -> "search/anime/popular"
            "AniList: Top 100" -> "search/anime/top-100"
            else -> "search/anime"
        }
        
        val showRank = pageName == "AniList: Top 100"
        
        try {
            val aniListDoc = app.get("$aniListUrl/$endpoint", referer = aniListUrl).document
            
            // DEBUG: Verificar o HTML recebido
            println("📄 [ANILIST] HTML recebido: ${aniListDoc.text().take(200)}...")
            
            // Procurar por diferentes tipos de containers
            val landingSection = aniListDoc.select(".landing-section")
            println("🔍 [ANILIST] Sections encontradas: ${landingSection.size}")
            
            // Primeiro tentar encontrar na seção específica
            val targetSection = when (pageName) {
                "AniList: Em Alta" -> landingSection.find { it.select("h3").text().contains("Trending", ignoreCase = true) }
                "AniList: Esta Temporada" -> landingSection.find { it.select("h3").text().contains("Popular this season", ignoreCase = true) }
                "AniList: Populares" -> landingSection.find { it.select("h3").text().contains("All time popular", ignoreCase = true) }
                "AniList: Top 100" -> landingSection.find { it.select("h3").text().contains("Top 100", ignoreCase = true) }
                else -> null
            }
            
            if (targetSection != null) {
                println("✅ [ANILIST] Seção específica encontrada: ${targetSection.select("h3").text()}")
                items.addAll(parseAniListMediaCards(targetSection, showRank))
            } else {
                // Fallback: procurar em todos os media-cards
                println("⚠️ [ANILIST] Seção específica não encontrada, usando fallback")
                items.addAll(parseAllAniListMediaCards(aniListDoc, showRank))
            }
            
            println("✅ [ANILIST] ${items.size} itens encontrados para $pageName")
            
        } catch (e: Exception) {
            println("❌ [ANILIST] Erro ao carregar $pageName: ${e.message}")
            e.printStackTrace()
        }
        
        // Armazenar em cache
        if (items.isNotEmpty()) {
            aniListCache[cacheKey] = items
            if (aniListCache.size > 20) {
                aniListCache.remove(aniListCache.keys.first())
            }
        }
        
        return newHomePageResponse(pageName, items, false)
    }

    private fun parseAniListMediaCards(
        section: Element,
        showRank: Boolean = false
    ): List<AnimeSearchResponse> {
        println("🔍 [PARSE] Analisando seção: ${section.select("h3").text()}")
        
        // Primeiro tentar com o seletor exato do HTML
        val mediaCards = section.select("div.media-card")
        println("📊 [PARSE] Media-cards encontrados: ${mediaCards.size}")
        
        if (mediaCards.isEmpty()) {
            // Fallback: procurar por qualquer div que possa conter animes
            val allCards = section.select("div")
            println("🔄 [PARSE] Nenhum media-card, tentando fallback com ${allCards.size} divs")
        }
        
        return mediaCards.take(20).mapNotNull { card ->
            try {
                println("🔍 [PARSE] Processando card...")
                
                // Título - múltiplas formas de extrair
                val titleElement = card.selectFirst("a.title") ?: 
                                  card.selectFirst("a.cover + a") ?:
                                  card.selectFirst("a[href*='/anime/']")
                
                val title = titleElement?.text()?.trim() ?: run {
                    // Fallback: extrair do link
                    val href = card.selectFirst("a[href*='/anime/']")?.attr("href") ?: ""
                    href.split("/").getOrNull(3)?.replace("-", " ")?.replace("_", " ") ?: "Sem Título"
                }
                
                val href = card.selectFirst("a[href*='/anime/']")?.attr("href") ?: ""
                
                if (href.isBlank()) {
                    println("⚠️ [PARSE] Link vazio encontrado")
                    return@mapNotNull null
                }
                
                println("✅ [PARSE] Título: $title, Link: ${href.take(50)}...")
                
                // Poster - múltiplas formas de extrair
                val poster = card.selectFirst("img.image")?.attr("src") ?:
                            card.selectFirst("img.cover")?.attr("src") ?:
                            card.selectFirst("img[src*='anilist']")?.attr("src") ?:
                            card.selectFirst("img")?.attr("src") ?: ""
                
                // Extrair ID do AniList da URL
                val pathParts = href.split("/")
                val aniListId = pathParts.getOrNull(2)?.toIntOrNull()
                
                // Criar uma URL especial que identifique que é do AniList
                val specialUrl = if (aniListId != null) {
                    "anilist:$aniListId:${fixUrl("$aniListUrl$href")}"
                } else {
                    "anilist:${System.currentTimeMillis()}:${fixUrl("$aniListUrl$href")}"
                }
                
                // Verificar se há ranking
                val rankElement = card.selectFirst(".rank")
                val finalTitle = if (showRank && rankElement != null) {
                    val rankText = rankElement.text().trim()
                    "#$rankText $title"
                } else {
                    title
                }
                
                newAnimeSearchResponse(finalTitle, specialUrl) {
                    this.posterUrl = poster
                    this.type = TvType.Anime
                }
            } catch (e: Exception) {
                println("❌ [PARSE] Erro ao processar card: ${e.message}")
                null
            }
        }
    }
    
    private fun parseAllAniListMediaCards(
        doc: org.jsoup.nodes.Document,
        showRank: Boolean = false
    ): List<AnimeSearchResponse> {
        println("🔄 [PARSE ALL] Procurando TODOS os media-cards no documento")
        
        // Encontrar TODOS os media-cards na página
        val allMediaCards = doc.select("div.media-card")
        println("📊 [PARSE ALL] Total de media-cards encontrados: ${allMediaCards.size}")
        
        return allMediaCards.take(20).mapNotNull { card ->
            try {
                // Extrair título
                val titleElement = card.selectFirst("a.title") ?: 
                                  card.selectFirst("a[href*='/anime/']")
                
                val title = titleElement?.text()?.trim() ?: "Sem Título"
                val href = card.selectFirst("a[href*='/anime/']")?.attr("href") ?: ""
                
                if (href.isBlank()) return@mapNotNull null
                
                // Poster
                val poster = card.selectFirst("img.image")?.attr("src") ?: ""
                
                // Extrair ID
                val pathParts = href.split("/")
                val aniListId = pathParts.getOrNull(2)?.toIntOrNull()
                
                val specialUrl = if (aniListId != null) {
                    "anilist:$aniListId:${fixUrl("$aniListUrl$href")}"
                } else {
                    "anilist:${System.currentTimeMillis()}:${fixUrl("$aniListUrl$href")}"
                }
                
                // Ranking
                val rankElement = card.selectFirst(".rank")
                val finalTitle = if (showRank && rankElement != null) {
                    val rankText = rankElement.text().trim()
                    "#$rankText $title"
                } else {
                    title
                }
                
                newAnimeSearchResponse(finalTitle, specialUrl) {
                    this.posterUrl = poster
                    this.type = TvType.Anime
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    // ============ FUNÇÃO SEARCH ============
    
    override suspend fun search(query: String): List<SearchResponse> {
        println("🔍 [SEARCH] Buscando: '$query'")
        
        // Primeiro buscar no AnimeFire
        val animeFireResults = searchAnimeFire(query)
        println("✅ [ANIMEFIRE] ${animeFireResults.size} resultados")
        
        // Também buscar no AniList se a query não for vazia
        val aniListResults = if (query.isNotBlank()) {
            searchAniList(query)
        } else {
            emptyList()
        }
        println("✅ [ANILIST] ${aniListResults.size} resultados")
        
        // Combinar resultados (AnimeFire primeiro, depois AniList)
        val combinedResults = (animeFireResults + aniListResults).distinctBy { it.url }
        println("✅ [COMBINED] Total: ${combinedResults.size} resultados")
        
        return combinedResults
    }

    private suspend fun searchAnimeFire(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl$SEARCH_PATH/${URLEncoder.encode(query, "UTF-8")}"
        
        val document = app.get(searchUrl).document

        val elements = document.select("div.divCardUltimosEps article.card a")
        
        return elements.mapNotNull { element ->
            runCatching {
                val href = element.attr("href")
                if (href.isBlank()) {
                    return@runCatching null
                }

                val titleElement = element.selectFirst("h3.animeTitle, .text-block h3, .animeTitle")
                val rawTitle = titleElement?.text()?.trim() ?: "Sem Título"
                
                val cleanTitle = rawTitle
                    .replace(Regex("\\s*-\\s*Todos os Episódios$"), "")
                    .replace(Regex("\\(Dublado\\)"), "")
                    .replace(Regex("\\(Legendado\\)"), "")
                    .trim()

                val imgElement = element.selectFirst("img.imgAnimes, img.card-img-top, img.transitioning_src")
                val posterUrl = when {
                    imgElement?.hasAttr("data-src") == true -> imgElement.attr("data-src")
                    imgElement?.hasAttr("src") == true -> imgElement.attr("src")
                    else -> null
                }

                val isMovie = href.contains("/filmes/") || 
                             cleanTitle.contains("filme", ignoreCase = true) ||
                             rawTitle.contains("filme", ignoreCase = true) ||
                             rawTitle.contains("movie", ignoreCase = true)

                newAnimeSearchResponse(cleanTitle, fixUrl(href)) {
                    this.posterUrl = posterUrl?.let { fixUrl(it) }
                    this.type = if (isMovie) {
                        TvType.Movie
                    } else {
                        TvType.Anime
                    }
                }
            }.getOrElse { e ->
                null
            }
        }.take(20)
    }

    private suspend fun searchAniList(query: String): List<SearchResponse> {
        println("🔍 [ANILIST SEARCH] Buscando: '$query'")
        
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "$aniListUrl/search/anime?search=$encodedQuery"
            
            println("🌐 [ANILIST SEARCH] URL: $searchUrl")
            
            val document = app.get(searchUrl, referer = aniListUrl).document
            
            // DEBUG: Verificar o que foi encontrado
            val mediaCards = document.select("div.media-card")
            println("📊 [ANILIST SEARCH] Media-cards encontrados: ${mediaCards.size}")
            
            if (mediaCards.isEmpty()) {
                println("⚠️ [ANILIST SEARCH] Nenhum media-card encontrado, tentando fallback")
                // Fallback: procurar por qualquer link de anime
                val animeLinks = document.select("a[href*='/anime/']")
                println("🔄 [ANILIST SEARCH] Links de anime encontrados: ${animeLinks.size}")
            }
            
            val results = mediaCards.take(10).mapNotNull { card ->
                try {
                    val titleElement = card.selectFirst("a.title") ?: 
                                      card.selectFirst("a[href*='/anime/']")
                    
                    val title = titleElement?.text()?.trim() ?: return@mapNotNull null
                    val href = card.selectFirst("a[href*='/anime/']")?.attr("href") ?: ""
                    
                    if (!href.contains("/anime/")) return@mapNotNull null
                    
                    val poster = card.selectFirst("img.image")?.attr("src") ?: ""
                    
                    val pathParts = href.split("/")
                    val aniListId = pathParts.getOrNull(2)?.toIntOrNull()
                    
                    val specialUrl = if (aniListId != null) {
                        "anilist:$aniListId:${fixUrl("$aniListUrl$href")}"
                    } else {
                        "anilist:${System.currentTimeMillis()}:${fixUrl("$aniListUrl$href")}"
                    }
                    
                    println("✅ [ANILIST SEARCH] Encontrado: $title (ID: $aniListId)")
                    
                    newAnimeSearchResponse(title, specialUrl) {
                        this.posterUrl = poster
                        this.type = TvType.Anime
                    }
                } catch (e: Exception) {
                    println("❌ [ANILIST SEARCH] Erro ao processar card: ${e.message}")
                    null
                }
            }
            
            println("✅ [ANILIST SEARCH] ${results.size} resultados encontrados")
            results
        } catch (e: Exception) {
            println("❌ [ANILIST SEARCH] Erro na busca: ${e.message}")
            emptyList()
        }
    }

    // ============ LOAD PRINCIPAL COM SUPORTE PARA ANILIST ============
    
    override suspend fun load(url: String): LoadResponse {
        println("\n🚀 AnimeFire.load() para URL: $url")
        
        // Verificar se é uma URL do AniList (começa com "anilist:")
        if (url.startsWith("anilist:")) {
            println("📚 Carregando do AniList")
            return loadFromAniList(url)
        }
        
        // Caso contrário, carregar normalmente do AnimeFire
        println("🎬 Carregando do AnimeFire")
        return loadFromAnimeFire(url)
    }

    private suspend fun loadFromAniList(url: String): LoadResponse {
        // Extrair ID e URL do AniList
        val parts = url.split(":")
        if (parts.size < 3) {
            throw ErrorLoadingException("URL do AniList inválida")
        }
        
        val aniListId = parts[1]
        val originalUrl = parts.drop(2).joinToString(":")
        
        println("🔍 AniList ID: $aniListId, URL: $originalUrl")
        
        try {
            // Buscar detalhes do AniList via API GraphQL
            val aniListData = fetchAniListDetails(aniListId)
            
            if (aniListData != null) {
                println("✅ Dados do AniList obtidos via API")
                return createAniListLoadResponse(aniListData, originalUrl)
            }
        } catch (e: Exception) {
            println("❌ Erro na API do AniList: ${e.message}")
        }
        
        // Fallback: buscar via web scraping
        println("🌐 Usando fallback via scraping")
        return loadAniListViaScraping(originalUrl)
    }

    private suspend fun fetchAniListDetails(aniListId: String): AniListMediaData? {
        val query = """
            query {
                Media(id: $aniListId, type: ANIME) {
                    id
                    title {
                        romaji
                        english
                        native
                        userPreferred
                    }
                    description
                    format
                    status
                    startDate {
                        year
                        month
                        day
                    }
                    endDate {
                        year
                        month
                        day
                    }
                    season
                    seasonYear
                    episodes
                    duration
                    chapters
                    volumes
                    countryOfOrigin
                    coverImage {
                        extraLarge
                        large
                        medium
                        color
                    }
                    bannerImage
                    genres
                    averageScore
                    meanScore
                    popularity
                    favourites
                    studios {
                        edges {
                            node {
                                name
                            }
                        }
                    }
                    siteUrl
                    nextAiringEpisode {
                        episode
                        timeUntilAiring
                    }
                    trailer {
                        id
                        site
                        thumbnail
                    }
                }
            }
        """.trimIndent()
        
        try {
            val response = app.post(
                "https://graphql.anilist.co",
                data = mapOf("query" to query),
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json"
                ),
                timeout = 10_000
            )
            
            if (response.code == 200) {
                val json = JsonParser.parseString(response.text)
                val data = json.asJsonObject?.getAsJsonObject("data")
                val media = data?.getAsJsonObject("Media")
                
                if (media != null) {
                    return parseAniListMedia(media)
                }
            }
        } catch (e: Exception) {
        }
        
        return null
    }

    private fun parseAniListMedia(media: com.google.gson.JsonObject): AniListMediaData {
        val title = media.getAsJsonObject("title")
        val startDate = media.getAsJsonObject("startDate")
        val coverImage = media.getAsJsonObject("coverImage")
        val trailer = media.getAsJsonObject("trailer")
        
        return AniListMediaData(
            id = media.get("id")?.asInt ?: 0,
            titleRomaji = title?.get("romaji")?.asString,
            titleEnglish = title?.get("english")?.asString,
            titleNative = title?.get("native")?.asString,
            titleUserPreferred = title?.get("userPreferred")?.asString,
            description = media.get("description")?.asString,
            format = media.get("format")?.asString,
            status = media.get("status")?.asString,
            startYear = startDate?.get("year")?.asInt,
            startMonth = startDate?.get("month")?.asInt,
            startDay = startDate?.get("day")?.asInt,
            episodes = media.get("episodes")?.asInt,
            duration = media.get("duration")?.asInt,
            coverImageLarge = coverImage?.get("large")?.asString,
            coverImageExtraLarge = coverImage?.get("extraLarge")?.asString,
            bannerImage = media.get("bannerImage")?.asString,
            genres = media.getAsJsonArray("genres")?.map { it.asString },
            averageScore = media.get("averageScore")?.asInt,
            siteUrl = media.get("siteUrl")?.asString,
            trailerId = trailer?.get("id")?.asString,
            trailerSite = trailer?.get("site")?.asString,
            trailerThumbnail = trailer?.get("thumbnail")?.asString
        )
    }

    private suspend fun createAniListLoadResponse(
        data: AniListMediaData,
        originalUrl: String
    ): LoadResponse {
        val title = data.titleUserPreferred ?: data.titleRomaji ?: data.titleEnglish ?: "Sem Título"
        val description = data.description?.replace(Regex("<[^>]*>"), "") ?: "Sem descrição"
        
        // Traduzir descrição se não estiver em português
        val finalDescription = if (TRANSLATION_ENABLED && !isProbablyPortuguese(description)) {
            translateWithCache(description)
        } else {
            description
        }
        
        val year = data.startYear
        val poster = data.coverImageExtraLarge ?: data.coverImageLarge
        val banner = data.bannerImage
        
        val tags = data.genres?.toList() ?: emptyList()
        
        println("🔍 Buscando episódios no AnimeFire para: $title")
        
        // Tentar buscar episódios do AnimeFire com base no título
        val animeFireEpisodes = try {
            searchAndGetEpisodesFromAnimeFire(title)
        } catch (e: Exception) {
            println("❌ Erro ao buscar episódios: ${e.message}")
            emptyList()
        }
        
        println("✅ ${animeFireEpisodes.size} episódios encontrados no AnimeFire")
        
        return newAnimeLoadResponse(title, originalUrl, TvType.Anime) {
            if (animeFireEpisodes.isNotEmpty()) {
                addEpisodes(DubStatus.Subbed, animeFireEpisodes)
            } else {
                // Criar episódios placeholder baseados no número total
                val episodesCount = data.episodes ?: 12
                val placeholderEpisodes = (1..episodesCount).map { episodeNum ->
                    newEpisode(originalUrl) {
                        this.name = "Episódio $episodeNum"
                        this.season = 1
                        this.episode = episodeNum
                        this.description = "Episódio $episodeNum de $title"
                    }
                }
                addEpisodes(DubStatus.Subbed, placeholderEpisodes)
            }
            
            this.year = year
            this.plot = finalDescription
            this.tags = tags
            this.posterUrl = poster
            this.backgroundPosterUrl = banner
            
            // Adicionar trailer se disponível
            if (data.trailerId != null && data.trailerSite == "youtube") {
                addTrailer("https://www.youtube.com/watch?v=${data.trailerId}")
            }
        }
    }

    private suspend fun searchAndGetEpisodesFromAnimeFire(title: String): List<Episode> {
        val searchResults = searchAnimeFire(title)
        if (searchResults.isEmpty()) return emptyList()
        
        // Pegar o primeiro resultado
        val firstResult = searchResults.first()
        val url = firstResult.url
        
        try {
            val document = app.get(url).document
            return extractEpisodesFromSite(document, title, null)
        } catch (e: Exception) {
            return emptyList()
        }
    }

    private suspend fun loadAniListViaScraping(url: String): LoadResponse {
        val document = app.get(url, referer = aniListUrl).document
        
        val titleElement = document.selectFirst("h1.title")
        val title = titleElement?.text()?.trim() ?: "Sem Título"
        
        val descriptionElement = document.selectFirst(".description")
        val rawDescription = descriptionElement?.text()?.trim() ?: "Sem descrição"
        val description = rawDescription.replace(Regex("<[^>]*>"), "")
        
        val finalDescription = if (TRANSLATION_ENABLED && !isProbablyPortuguese(description)) {
            translateWithCache(description)
        } else {
            description
        }
        
        val poster = document.selectFirst("img.cover")?.attr("src")
        val banner = document.selectFirst(".banner")?.attr("style")?.let { 
            Regex("url\\(['\"]?([^'\")]+)['\"]?\\)").find(it)?.groupValues?.get(1)
        }
        
        val tags = document.select(".tags span").map { it.text().trim() }
        
        val yearText = document.selectFirst("span:contains(Released)")?.text()
        val year = Regex("\\d{4}").find(yearText ?: "")?.value?.toIntOrNull()
        
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.year = year
            this.plot = finalDescription
            this.tags = tags
            this.posterUrl = poster
            this.backgroundPosterUrl = banner
            
            // Tentar buscar episódios do AnimeFire
            val animeFireEpisodes = try {
                searchAndGetEpisodesFromAnimeFire(title)
            } catch (e: Exception) {
                emptyList()
            }
            
            if (animeFireEpisodes.isNotEmpty()) {
                addEpisodes(DubStatus.Subbed, animeFireEpisodes)
            }
        }
    }

    // ============ RESTANTE DO CÓDIGO (MANTIDO IGUAL) ============
    // [TODO: Incluir aqui as funções restantes que não foram modificadas]
    // Como: loadFromAnimeFire, parseAnimeData, createLoadResponseWithTranslation,
    // extractEpisodesFromSite, searchMALIdByName, extractSiteMetadata,
    // searchOnTMDB, etc.

    // [Adicionar todas as outras funções originais aqui...]

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Verificar se é um conteúdo do AniList
        if (data.startsWith("anilist:")) {
            // Para conteúdos do AniList, tentar encontrar no AnimeFire
            val parts = data.split(":")
            if (parts.size >= 3) {
                val originalUrl = parts.drop(2).joinToString(":")
                
                // Extrair título da URL do AniList
                val titleFromUrl = originalUrl.split("/").lastOrNull() ?: "Anime"
                
                // Buscar no AnimeFire
                val searchResults = searchAnimeFire(titleFromUrl)
                if (searchResults.isNotEmpty()) {
                    val animeFireUrl = searchResults.first().url
                    return AnimeFireVideoExtractor.extractVideoLinks(animeFireUrl, mainUrl, titleFromUrl, callback)
                }
            }
            return false
        }
        
        return AnimeFireVideoExtractor.extractVideoLinks(data, mainUrl, name, callback)
    }

    // ============ CLASSES DE DADOS ============
    
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListResponse(
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
        @JsonProperty("idMal") val idMal: Int? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBSearchResponse(
        @JsonProperty("results") val results: List<TMDBResult>
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBResult(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("release_date") val release_date: String? = null,
        @JsonProperty("first_air_date") val first_air_date: String? = null,
        @JsonProperty("poster_path") val poster_path: String?
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBDetailsResponse(
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("backdrop_path") val backdrop_path: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("genres") val genres: List<TMDBGenre>?,
        @JsonProperty("videos") val videos: TMDBVideos?
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBGenre(
        @JsonProperty("name") val name: String
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBVideos(
        @JsonProperty("results") val results: List<TMDBVideo>
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBVideo(
        @JsonProperty("key") val key: String,
        @JsonProperty("site") val site: String,
        @JsonProperty("type") val type: String,
        @JsonProperty("official") val official: Boolean? = false
    )

    private data class TMDBInfo(
        val id: Int,
        val title: String?,
        val year: Int?,
        val posterUrl: String?,
        val backdropUrl: String?,
        val overview: String?,
        val genres: List<String>?,
        val youtubeTrailer: String?,
        val duration: Int?
    )

    // Nova classe para dados do AniList
    private data class AniListMediaData(
        val id: Int,
        val titleRomaji: String?,
        val titleEnglish: String?,
        val titleNative: String?,
        val titleUserPreferred: String?,
        val description: String?,
        val format: String?,
        val status: String?,
        val startYear: Int?,
        val startMonth: Int?,
        val startDay: Int?,
        val episodes: Int?,
        val duration: Int?,
        val coverImageLarge: String?,
        val coverImageExtraLarge: String?,
        val bannerImage: String?,
        val genres: List<String>?,
        val averageScore: Int?,
        val siteUrl: String?,
        val trailerId: String?,
        val trailerSite: String?,
        val trailerThumbnail: String?
    )
}
