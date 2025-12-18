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
            return newHomePageResponse(pageName, cached, false)
        }

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
            items.addAll(parseAniListMediaCards(aniListDoc, showRank))
        } catch (e: Exception) {
            println("❌ Erro ao carregar $pageName do AniList: ${e.message}")
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
        doc: org.jsoup.nodes.Document,
        showRank: Boolean = false
    ): List<AnimeSearchResponse> {
        return doc.select("div.media-card").take(20).mapNotNull { card ->
            try {
                val titleElement = card.selectFirst("a.title")
                val title = titleElement?.text() ?: return@mapNotNull null
                val href = titleElement.attr("href") ?: ""
                
                // Verificar se o link é válido (contém /anime/)
                if (!href.contains("/anime/")) return@mapNotNull null
                
                val poster = card.selectFirst("img.image")?.attr("src") ?: ""
                
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
                null
            }
        }
    }

    // ============ FUNÇÃO SEARCH ============
    
    override suspend fun search(query: String): List<SearchResponse> {
        // Primeiro buscar no AnimeFire
        val animeFireResults = searchAnimeFire(query)
        
        // Também buscar no AniList se a query não for vazia
        val aniListResults = if (query.isNotBlank()) {
            searchAniList(query)
        } else {
            emptyList()
        }
        
        // Combinar resultados (AnimeFire primeiro, depois AniList)
        val combinedResults = (animeFireResults + aniListResults).distinctBy { it.url }
        
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
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "$aniListUrl/search/anime?search=$encodedQuery"
            
            val document = app.get(searchUrl, referer = aniListUrl).document
            
            val results = document.select("div.media-card").take(10).mapNotNull { card ->
                try {
                    val titleElement = card.selectFirst("a.title")
                    val title = titleElement?.text() ?: return@mapNotNull null
                    val href = titleElement.attr("href") ?: ""
                    
                    if (!href.contains("/anime/")) return@mapNotNull null
                    
                    val poster = card.selectFirst("img.image")?.attr("src") ?: ""
                    
                    val pathParts = href.split("/")
                    val aniListId = pathParts.getOrNull(2)?.toIntOrNull()
                    
                    val specialUrl = if (aniListId != null) {
                        "anilist:$aniListId:${fixUrl("$aniListUrl$href")}"
                    } else {
                        "anilist:${System.currentTimeMillis()}:${fixUrl("$aniListUrl$href")}"
                    }
                    
                    newAnimeSearchResponse(title, specialUrl) {
                        this.posterUrl = poster
                        this.type = TvType.Anime
                    }
                } catch (e: Exception) {
                    null
                }
            }
            
            results
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ============ LOAD PRINCIPAL COM SUPORTE PARA ANILIST ============
    
    override suspend fun load(url: String): LoadResponse {
        // Verificar se é uma URL do AniList (começa com "anilist:")
        if (url.startsWith("anilist:")) {
            return loadFromAniList(url)
        }
        
        // Caso contrário, carregar normalmente do AnimeFire
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
        
        try {
            // Buscar detalhes do AniList via API GraphQL
            val aniListData = fetchAniListDetails(aniListId)
            
            if (aniListData != null) {
                return createAniListLoadResponse(aniListData, originalUrl)
            }
        } catch (e: Exception) {
        }
        
        // Fallback: buscar via web scraping
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
        
        // Tentar buscar episódios do AnimeFire com base no título
        val animeFireEpisodes = try {
            searchAndGetEpisodesFromAnimeFire(title)
        } catch (e: Exception) {
            emptyList()
        }
        
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

    // ============ LOAD DO ANIMEFIRE (MANTIDO ORIGINAL) ============
    
    private suspend fun loadFromAnimeFire(url: String): LoadResponse {
        val document = app.get(url).document

        val titleElement = document.selectFirst("h1.quicksand400, .main_div_anime_info h1, h1") ?: 
            throw ErrorLoadingException("Não foi possível encontrar o título")
        val rawTitle = titleElement.text().trim()
        
        val year = Regex("\\((\\d{4})\\)").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = rawTitle.replace(Regex("\\(\\d{4}\\)"), "").trim()
        
        val isMovie = url.contains("/filmes/") || rawTitle.contains("Movie", ignoreCase = true)
        val type = if (isMovie) TvType.Movie else TvType.Anime

        val malId = searchMALIdByName(cleanTitle)

        var aniZipData: AniZipData? = null
        if (malId != null) {
            val syncMetaData = app.get("https://api.ani.zip/mappings?mal_id=$malId").text
            aniZipData = parseAnimeData(syncMetaData)
        }

        val tmdbInfo = searchOnTMDB(cleanTitle, year, !isMovie)

        val siteMetadata = extractSiteMetadata(document)
        
        val episodes = if (!isMovie) {
            extractEpisodesFromSite(document, cleanTitle, aniZipData)
        } else {
            emptyList()
        }

        val recommendations = extractRecommendations(document)

        val data = document.selectFirst("div#media-info, div.anime-info")
        val genres = data?.select("div:contains(Genre:), div:contains(Gênero:) > span > a")?.map { it.text() }

        return createLoadResponseWithTranslation(
            url = url,
            cleanTitle = cleanTitle,
            year = year,
            isMovie = isMovie,
            type = type,
            siteMetadata = siteMetadata,
            aniZipData = aniZipData,
            tmdbInfo = tmdbInfo,
            episodes = episodes,
            recommendations = recommendations,
            genres = genres
        )
    }

    // ============ FUNÇÕES ORIGINAIS MANTIDAS ============
    
    private fun parseAnimeData(jsonString: String): AniZipData? {
        return try {
            val objectMapper = ObjectMapper()
            objectMapper.readValue(jsonString, AniZipData::class.java)
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun createLoadResponseWithTranslation(
        url: String,
        cleanTitle: String,
        year: Int?,
        isMovie: Boolean,
        type: TvType,
        siteMetadata: SiteMetadata,
        aniZipData: AniZipData?,
        tmdbInfo: TMDBInfo?,
        episodes: List<Episode>,
        recommendations: List<SearchResponse>,
        genres: List<String>?
    ): LoadResponse {
        
        val finalPlot = if (TRANSLATION_ENABLED && siteMetadata.plot != null) {
            val originalPlot = siteMetadata.plot!!
            if (!isProbablyPortuguese(originalPlot)) {
                val translated = translateWithCache(originalPlot)
                if (translated != originalPlot) {
                    translated
                } else {
                    originalPlot
                }
            } else {
                originalPlot
            }
        } else {
            siteMetadata.plot ?: tmdbInfo?.overview ?: aniZipData?.episodes?.values?.firstOrNull()?.overview
        }
        
        val finalPoster = tmdbInfo?.posterUrl ?:
                         aniZipData?.images?.find { it.coverType.equals("Poster", ignoreCase = true) }?.url?.let { fixUrl(it) } ?:
                         siteMetadata.poster
        
        val finalBackdrop = tmdbInfo?.backdropUrl ?:
                           aniZipData?.images?.find { it.coverType.equals("Fanart", ignoreCase = true) }?.url?.let { fixUrl(it) } ?:
                           siteMetadata.poster?.let { fixUrl(it) }
        
        val finalYear = tmdbInfo?.year ?: year ?: siteMetadata.year ?:
                       aniZipData?.episodes?.values?.firstOrNull()?.airDateUtc?.substring(0, 4)?.toIntOrNull()
        
        val finalTags = (tmdbInfo?.genres ?: emptyList()) + 
                       (genres ?: emptyList()) + 
                       (siteMetadata.tags ?: emptyList())

        return if (isMovie) {
            newMovieLoadResponse(cleanTitle, url, type, url) {
                this.year = finalYear
                this.plot = finalPlot
                this.tags = finalTags.distinct().take(10)
                this.posterUrl = finalPoster
                this.backgroundPosterUrl = finalBackdrop
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
                
                tmdbInfo?.youtubeTrailer?.let { trailerUrl ->
                    addTrailer(trailerUrl)
                }
            }
        } else {
            newAnimeLoadResponse(cleanTitle, url, type) {
                addEpisodes(DubStatus.Subbed, episodes)
                
                this.year = finalYear
                this.plot = finalPlot
                this.tags = finalTags.distinct().take(10)
                this.posterUrl = finalPoster
                this.backgroundPosterUrl = finalBackdrop
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
                
                tmdbInfo?.youtubeTrailer?.let { trailerUrl ->
                    addTrailer(trailerUrl)
                }
            }
        }
    }

    private suspend fun extractEpisodesFromSite(
        document: org.jsoup.nodes.Document,
        animeTitle: String,
        aniZipData: AniZipData?
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        val episodeElements = document.select("a.lEp.epT, a.lEp, .divListaEps a, [href*='/video/'], [href*='/episodio/']")
        
        episodeElements.forEachIndexed { index, element ->
            try {
                val href = element.attr("href")
                if (href.isBlank()) return@forEachIndexed
                
                val text = element.text().trim()
                if (text.isBlank()) return@forEachIndexed
                
                val episodeNumber = extractEpisodeNumber(text) ?: (index + 1)
                val seasonNumber = 1
                
                val aniZipEpisode = aniZipData?.episodes?.get(episodeNumber.toString())
                
                val episodeName = element.selectFirst(".ep-name, .title")?.text()?.trim()
                    ?: text.substringAfterLast("-").trim()
                    ?: "Episódio $episodeNumber"
                
                val finalEpisodeName = if (TRANSLATION_ENABLED && !isProbablyPortuguese(episodeName)) {
                    translateWithCache(episodeName)
                } else {
                    episodeName
                }
                
                val episodeDescription = if (aniZipEpisode?.overview != null && TRANSLATION_ENABLED) {
                    val overview = aniZipEpisode.overview!!
                    if (!isProbablyPortuguese(overview)) {
                        translateWithCache(overview)
                    } else {
                        overview
                    }
                } else {
                    aniZipEpisode?.overview ?: "Nenhuma descrição disponível"
                }

                episodes.add(
                    newEpisode(fixUrl(href)) {
                        this.name = finalEpisodeName
                        this.season = seasonNumber
                        this.episode = episodeNumber
                        this.description = episodeDescription
                        this.posterUrl = aniZipEpisode?.image ?: aniZipData?.images?.firstOrNull()?.url
                        this.score = Score.from10(aniZipEpisode?.rating)
                        this.runTime = aniZipEpisode?.runtime
                        
                        aniZipEpisode?.airDateUtc?.let { dateStr ->
                            try {
                                val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
                                val date = formatter.parse(dateStr)
                                this.date = date.time
                            } catch (e: Exception) {
                            }
                        }
                    }
                )
                
            } catch (e: Exception) {
            }
        }
        
        return episodes.sortedBy { it.episode }
    }

    private fun extractEpisodeNumber(text: String): Int? {
        val patterns = listOf(
            Regex("Epis[oó]dio\\s*(\\d+)"),
            Regex("Ep\\.?\\s*(\\d+)"),
            Regex("(\\d{1,3})\\s*-"),
            Regex("#(\\d+)"),
            Regex("\\b(\\d{1,4})\\b")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                return match.groupValues[1].toIntOrNull()
            }
        }
        return null
    }
    
    private suspend fun searchMALIdByName(animeName: String): Int? {
        return try {
            val cleanName = animeName
                .replace(Regex("(?i)\\s*-\\s*Todos os Episódios"), "")
                .replace(Regex("(?i)\\s*\\(Dublado\\)"), "")
                .replace(Regex("(?i)\\s*\\(Legendado\\)"), "")
                .trim()
            
            val query = """
                query {
                    Page(page: 1, perPage: 5) {
                        media(search: "$cleanName", type: ANIME) {
                            title { romaji english native }
                            idMal
                        }
                    }
                }
            """.trimIndent()
            
            val response = app.post(
                "https://graphql.anilist.co",
                data = mapOf("query" to query),
                headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
                timeout = 10_000
            )
            
            if (response.code == 200) {
                val data = response.parsedSafe<AniListResponse>()
                data?.data?.Page?.media?.firstOrNull()?.idMal
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private data class SiteMetadata(
        val poster: String? = null,
        val plot: String? = null,
        val tags: List<String>? = null,
        val year: Int? = null
    )

    private fun extractSiteMetadata(document: org.jsoup.nodes.Document): SiteMetadata {
        val posterImg = document.selectFirst(".sub_animepage_img img.transitioning_src")
        val poster = when {
            posterImg?.hasAttr("src") == true -> fixUrl(posterImg.attr("src"))
            posterImg?.hasAttr("data-src") == true -> fixUrl(posterImg.attr("data-src"))
            else -> document.selectFirst("img[src*='/img/animes/']:not([src*='logo'])")
                ?.attr("src")?.let { fixUrl(it) }
        }

        val plot = document.selectFirst("div.divSinopse span.spanAnimeInfo")
            ?.text()
            ?.trim()
            ?.replace(Regex("^Sinopse:\\s*"), "")

        val tags = document.select("a.spanAnimeInfo.spanGeneros")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .takeIf { it.isNotEmpty() }?.toList()

        val year = document.selectFirst("div.animeInfo:contains(Ano:) span.spanAnimeInfo")
            ?.text()
            ?.trim()
            ?.toIntOrNull()

        return SiteMetadata(poster, plot, tags, year)
    }

    private suspend fun searchOnTMDB(query: String, year: Int?, isTv: Boolean): TMDBInfo? {
        if (tmdbApiKey == "dummy_api_key" || tmdbAccessToken == "dummy_access_token") {
            return searchOnTMDBFallback(query, year, isTv)
        }

        return try {
            val type = if (isTv) "tv" else "movie"
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            
            var searchUrl = "$tmdbBaseUrl/search/$type?query=$encodedQuery&api_key=$tmdbApiKey&language=pt-BR"
            if (year != null) searchUrl += "&year=$year"

            val response = app.get(searchUrl, timeout = 10_000)

            if (response.code != 200) {
                return searchOnTMDBFallback(query, year, isTv)
            }

            val searchResult = response.parsedSafe<TMDBSearchResponse>() ?: return null

            val result = searchResult.results.firstOrNull() ?: return null

            val details = getTMDBDetailsDirect(result.id, isTv) ?: return null

            val youtubeTrailer = getHighQualityTrailer(details.videos?.results)

            TMDBInfo(
                id = result.id,
                title = if (isTv) result.name else result.title,
                year = if (isTv) {
                    result.first_air_date?.substring(0, 4)?.toIntOrNull()
                } else {
                    result.release_date?.substring(0, 4)?.toIntOrNull()
                },
                posterUrl = result.poster_path?.let { "$tmdbImageUrl/w500$it" },
                backdropUrl = details.backdrop_path?.let { "$tmdbImageUrl/original$it" },
                overview = details.overview,
                genres = details.genres?.map { it.name },
                youtubeTrailer = youtubeTrailer,
                duration = details.runtime
            )
        } catch (e: Exception) {
            searchOnTMDBFallback(query, year, isTv)
        }
    }

    private suspend fun searchOnTMDBFallback(query: String, year: Int?, isTv: Boolean): TMDBInfo? {
        val TMDB_FALLBACK_PROXY = "https://lawliet.euluan1912.workers.dev"
        
        return try {
            val type = if (isTv) "tv" else "movie"
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val yearParam = year?.let { "&year=$it" } ?: ""

            val searchUrl = "$TMDB_FALLBACK_PROXY/search?query=$encodedQuery&type=$type$yearParam"

            val response = app.get(searchUrl, timeout = 10_000)

            if (response.code != 200) return null

            val searchResult = response.parsedSafe<TMDBSearchResponse>() ?: return null

            val result = searchResult.results.firstOrNull() ?: return null

            val details = getTMDBDetailsViaProxy(result.id, isTv) ?: return null

            val youtubeTrailer = getHighQualityTrailer(details.videos?.results)

            TMDBInfo(
                id = result.id,
                title = if (isTv) result.name else result.title,
                year = if (isTv) {
                    result.first_air_date?.substring(0, 4)?.toIntOrNull()
                } else {
                    result.release_date?.substring(0, 4)?.toIntOrNull()
                },
                posterUrl = result.poster_path?.let { "$tmdbImageUrl/w500$it" },
                backdropUrl = details.backdrop_path?.let { "$tmdbImageUrl/original$it" },
                overview = details.overview,
                genres = details.genres?.map { it.name },
                youtubeTrailer = youtubeTrailer,
                duration = details.runtime
            )
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun getTMDBDetailsDirect(id: Int, isTv: Boolean): TMDBDetailsResponse? {
        return try {
            val type = if (isTv) "tv" else "movie"
            val url = "$tmdbBaseUrl/$type/$id?append_to_response=videos&language=pt-BR"
            
            val headers = mapOf(
                "Authorization" to "Bearer $tmdbAccessToken",
                "accept" to "application/json"
            )
            
            val response = app.get(url, headers = headers, timeout = 10_000)

            if (response.code != 200) {
                return null
            }

            response.parsedSafe<TMDBDetailsResponse>()
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun getTMDBDetailsViaProxy(id: Int, isTv: Boolean): TMDBDetailsResponse? {
        val TMDB_FALLBACK_PROXY = "https://lawliet.euluan1912.workers.dev"
        
        return try {
            val type = if (isTv) "tv" else "movie"
            val url = "$TMDB_FALLBACK_PROXY/$type/$id?append_to_response=videos"

            val response = app.get(url, timeout = 10_000)

            if (response.code != 200) return null

            response.parsedSafe<TMDBDetailsResponse>()
        } catch (e: Exception) {
            null
        }
    }

    private fun getHighQualityTrailer(videos: List<TMDBVideo>?): String? {
        if (videos.isNullOrEmpty()) return null

        return videos.mapNotNull { video ->
            when {
                video.site == "YouTube" && video.type == "Trailer" && video.official == true ->
                    Triple(video.key, 10, "YouTube Trailer Oficial")
                video.site == "YouTube" && video.type == "Trailer" ->
                    Triple(video.key, 9, "YouTube Trailer")
                video.site == "YouTube" && video.type == "Teaser" && video.official == true ->
                    Triple(video.key, 8, "YouTube Teaser Oficial")
                video.site == "YouTube" && video.type == "Teaser" ->
                    Triple(video.key, 7, "YouTube Teaser")
                else -> null
            }
        }
        ?.sortedByDescending { it.second }
        ?.firstOrNull()
        ?.let { (key, _, _) -> "https://www.youtube.com/watch?v=$key" }
    }

    private suspend fun extractRecommendations(document: org.jsoup.nodes.Document): List<SearchResponse> {
        return document.select(".owl-carousel-anime .divArticleLancamentos a.item")
            .mapNotNull { element -> 
                runCatching { element.toSearchResponse() }.getOrNull()
            }
    }

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
