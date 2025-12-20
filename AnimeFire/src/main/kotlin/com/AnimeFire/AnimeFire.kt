package com.AnimeFire

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import kotlinx.coroutines.delay
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.gson.JsonParser
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

class AnimeFire : MainAPI() {
    override var mainUrl = "https://animefire.io"
    override var name = "AnimeFire"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie, TvType.OVA)
    override val usesWebView = true

    companion object {
        private const val SEARCH_PATH = "/pesquisar"
        private const val ANILIST_API = "https://graphql.anilist.co"
        private const val DEBUG_PREFIX = "🔥 [ANIMEFIRE]"
    }

    // ============ PÁGINA PRINCIPAL COM ANILIST ============
    override val mainPage = mainPageOf(
        "animefire" to "🔥 AnimeFire - Lançamentos",
        "animefire_episodes" to "🔥 AnimeFire - Últimos Episódios",
        "anilist_trending" to "📈 AniList - Em Alta",
        "anilist_popular" to "⭐ AniList - Populares",
        "anilist_season" to "📅 AniList - Temporada Atual",
        "anilist_upcoming" to "🔮 AniList - Próxima Temporada"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        println("$DEBUG_PREFIX getMainPage() - ${request.name} - Página: $page")
        
        return when (request.data) {
            "animefire" -> getAnimeFireHome(page)
            "animefire_episodes" -> getAnimeFireEpisodes(page)
            "anilist_trending" -> getAniListSection(page, "trending")
            "anilist_popular" -> getAniListSection(page, "popular")
            "anilist_season" -> getAniListSection(page, "season")
            "anilist_upcoming" -> getAniListSection(page, "upcoming")
            else -> getAnimeFireHome(page)
        }
    }

    // ============ FUNÇÕES DO SITE ANIMEFIRE ============
    private suspend fun getAnimeFireHome(page: Int): HomePageResponse {
        println("$DEBUG_PREFIX Carregando página inicial do AnimeFire")
        
        return try {
            val document = app.get(mainUrl).document
            
            val homeItems = document.select(".owl-carousel-home .divArticleLancamentos a.item")
                .mapNotNull { element -> 
                    runCatching { element.toSearchResponse() }.getOrNull()
                }
                .take(20)
            
            newHomePageResponse("🔥 AnimeFire - Lançamentos", homeItems, false)
            
        } catch (e: Exception) {
            println("$DEBUG_PREFIX Erro na página inicial: ${e.message}")
            newHomePageResponse("🔥 AnimeFire - Lançamentos", emptyList(), false)
        }
    }

    private suspend fun getAnimeFireEpisodes(page: Int): HomePageResponse {
        println("$DEBUG_PREFIX Carregando últimos episódios do AnimeFire")
        
        return try {
            val document = app.get(mainUrl).document
            
            val episodes = document.select(".divCardUltimosEpsHome").mapNotNull { card ->
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
            }.take(20)
            
            newHomePageResponse("🔥 AnimeFire - Últimos Episódios", episodes, false)
            
        } catch (e: Exception) {
            println("$DEBUG_PREFIX Erro nos episódios: ${e.message}")
            newHomePageResponse("🔥 AnimeFire - Últimos Episódios", emptyList(), false)
        }
    }

    // ============ FUNÇÕES DO ANILIST ============
    private suspend fun getAniListSection(page: Int, section: String): HomePageResponse {
        println("$DEBUG_PREFIX Carregando seção AniList: $section")
        
        val query = when (section) {
            "trending" -> """
                query {
                    Page(page: $page, perPage: 20) {
                        media(sort: TRENDING_DESC, type: ANIME, status: RELEASING) {
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
                            status
                        }
                    }
                }
            """
            "popular" -> """
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
                            status
                        }
                    }
                }
            """
            "season" -> """
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
                            status
                        }
                    }
                }
            """
            "upcoming" -> """
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
                            status
                        }
                    }
                }
            """
            else -> return newHomePageResponse("AniList", emptyList(), false)
        }
        
        return try {
            val response = app.post(
                ANILIST_API,
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
                
                println("$DEBUG_PREFIX ${mediaList.size} animes encontrados no AniList")
                
                val searchResponses = mutableListOf<SearchResponse>()
                
                for (media in mediaList.take(20)) {
                    val title = media.title?.english ?: 
                               media.title?.romaji ?: 
                               media.title?.userPreferred ?: 
                               "Sem título"
                    
                    val aniListUrl = "anilist:${media.id}:$title"
                    val poster = media.coverImage?.extraLarge ?: media.coverImage?.large
                    
                    searchResponses.add(newAnimeSearchResponse(title, aniListUrl) {
                        this.posterUrl = poster
                        this.type = TvType.Anime
                    })
                }
                
                val sectionName = when (section) {
                    "trending" -> "📈 AniList - Em Alta"
                    "popular" -> "⭐ AniList - Populares"
                    "season" -> "📅 AniList - Temporada Atual"
                    "upcoming" -> "🔮 AniList - Próxima Temporada"
                    else -> "AniList"
                }
                
                newHomePageResponse(sectionName, searchResponses, false)
            } else {
                println("$DEBUG_PREFIX Erro AniList: ${response.code}")
                newHomePageResponse("AniList", emptyList(), false)
            }
            
        } catch (e: Exception) {
            println("$DEBUG_PREFIX Exception AniList: ${e.message}")
            newHomePageResponse("AniList", emptyList(), false)
        }
    }

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

    // ============ SEARCH (MANTIDA) ============
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl$SEARCH_PATH/${URLEncoder.encode(query, "UTF-8")}"
        println("$DEBUG_PREFIX Buscando: '$query' | URL: $searchUrl")
        
        val document = app.get(searchUrl).document

        val elements = document.select("div.divCardUltimosEps article.card a")
        println("$DEBUG_PREFIX Elementos encontrados: ${elements.size}")
        
        if (elements.isEmpty()) {
            println("⚠️ Nenhum elemento encontrado com o seletor atual")
        }

        return elements.mapNotNull { element ->
            runCatching {
                val href = element.attr("href")
                if (href.isBlank()) {
                    println("⚠️ Link vazio encontrado")
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

                println("✅ Processado: '$cleanTitle' | URL: ${href.take(50)}... | Tipo: ${if (isMovie) "Filme" else "Anime"}")

                newAnimeSearchResponse(cleanTitle, fixUrl(href)) {
                    this.posterUrl = posterUrl?.let { fixUrl(it) }
                    this.type = if (isMovie) {
                        TvType.Movie
                    } else {
                        TvType.Anime
                    }
                }
            }.getOrElse { e ->
                println("❌ Erro ao processar elemento: ${e.message}")
                null
            }
        }.take(30)
    }

    // ============ LOAD ATUALIZADA ============
override suspend fun load(url: String): LoadResponse {
    println("\n$DEBUG_PREFIX load() para URL: $url")
    
    // 1. SE for link do AniList, pesquisar primeiro
    if (url.startsWith("anilist:")) {
        return handleAniListUrl(url)  // ← NOVA FUNÇÃO
    }
    
    // 2. SE NÃO for AniList, carrega normal
    return loadFromAnimeFire(url)
}

// ADICIONE esta função NOVA:
private suspend fun handleAniListUrl(aniListUrl: String): LoadResponse {
    println("$DEBUG_PREFIX [ANILIST] URL detectada: $aniListUrl")
    
    val parts = aniListUrl.split(":")
    if (parts.size < 3) {
        return createAnimeNotFoundResponse("Formato inválido", aniListUrl)
    }
    
    // Extrair título (pode ter vários ":")
    val animeTitle = parts.subList(2, parts.size).joinToString(":")
    println("$DEBUG_PREFIX [ANILIST] Buscando: '$animeTitle' no AnimeFire")
    
    // FAZER PESQUISA REAL
    val searchUrl = "$mainUrl$SEARCH_PATH/${URLEncoder.encode(animeTitle, "UTF-8")}"
    println("$DEBUG_PREFIX [ANILIST] URL de busca: $searchUrl")
    
    try {
        val document = app.get(searchUrl, timeout = 15000).document
        
        // Pegar primeiro resultado
        val firstResult = document.selectFirst("div.divCardUltimosEps article.card a")
        
        if (firstResult != null) {
            val href = firstResult.attr("href")
            if (href.isNotBlank()) {
                val realUrl = fixUrl(href)
                println("$DEBUG_PREFIX [ANILIST] ✅ Encontrado! Link real: $realUrl")
                
                // Agora carrega com a função loadFromAnimeFire existente
                return loadFromAnimeFire(realUrl)
            }
        }
        
        println("$DEBUG_PREFIX [ANILIST] ❌ Nenhum resultado encontrado")
        return createAnimeNotFoundResponse(animeTitle, aniListUrl)
        
    } catch (e: Exception) {
        println("$DEBUG_PREFIX [ANILIST] ❌ Erro na pesquisa: ${e.message}")
        return createAnimeNotFoundResponse(animeTitle, aniListUrl)
    }
}

private suspend fun searchOnAnimeFire(query: String): List<SearchResponse> {
    println("$DEBUG_PREFIX 🔍 Pesquisando no AnimeFire: '$query'")
    
    val searchUrl = "$mainUrl$SEARCH_PATH/${URLEncoder.encode(query, "UTF-8")}"
    val document = app.get(searchUrl, timeout = 15000).document
    
    val results = document.select("div.divCardUltimosEps article.card a")
        .mapNotNull { element ->
            runCatching {
                val href = element.attr("href")
                if (href.isBlank()) return@runCatching null
                
                val titleElement = element.selectFirst("h3.animeTitle, .text-block h3, .animeTitle")
                val rawTitle = titleElement?.text()?.trim() ?: "Sem Título"
                
                // Limpar o título (remover "Todos os Episódios", etc.)
                val cleanTitle = rawTitle
                    .replace(Regex("\\s*-\\s*Todos os Episódios$"), "")
                    .replace(Regex("\\(Dublado\\)"), "")
                    .replace(Regex("\\(Legendado\\)"), "")
                    .trim()
                
                println("$DEBUG_PREFIX 📦 Resultado da busca: '$cleanTitle' -> $href")
                
                newAnimeSearchResponse(cleanTitle, fixUrl(href)) {
                    val img = element.selectFirst("img.imgAnimes, img.card-img-top")
                    this.posterUrl = when {
                        img?.hasAttr("data-src") == true -> fixUrl(img.attr("data-src"))
                        img?.hasAttr("src") == true -> fixUrl(img.attr("src"))
                        else -> null
                    }
                    this.type = TvType.Anime
                }
            }.getOrNull()
        }
        .distinctBy { it.url } // Remover duplicados
    
    println("$DEBUG_PREFIX 📊 Total de resultados encontrados: ${results.size}")
    return results
}
    private suspend fun loadFromAnimeFire(url: String): LoadResponse {
        println("$DEBUG_PREFIX Carregando do AnimeFire: $url")
        
        val document = app.get(url).document

        val titleElement = document.selectFirst("h1.quicksand400, .main_div_anime_info h1, h1") ?: 
            throw ErrorLoadingException("Não foi possível encontrar o título")
        val rawTitle = titleElement.text().trim()
        
        val year = Regex("\\((\\d{4})\\)").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = rawTitle.replace(Regex("\\(\\d{4}\\)"), "").trim()
        
        val isMovie = url.contains("/filmes/") || rawTitle.contains("Movie", ignoreCase = true)
        val type = if (isMovie) TvType.Movie else TvType.Anime

        println("📌 Título: $cleanTitle, Ano: $year, Tipo: $type")

        // CORREÇÃO: Buscar MAL ID usando a função searchMALIdByName
        val malId = searchMALIdByName(cleanTitle)
        println("🔍 MAL ID: $malId")

        var aniZipData: AniZipData? = null
        if (malId != null) {
            println("🔍 Buscando AniZip...")
            try {
                val syncMetaData = app.get("https://api.ani.zip/mappings?mal_id=$malId", timeout = 10000).text
                aniZipData = parseAnimeData(syncMetaData)
                println("✅ AniZip carregado: ${aniZipData?.episodes?.size ?: 0} episódios")
            } catch (e: Exception) {
                println("❌ Erro ao buscar AniZip: ${e.message}")
            }
        }

        val siteMetadata = extractSiteMetadata(document)
        
        val episodes = if (!isMovie) {
            extractEpisodesFromSite(document, cleanTitle, aniZipData)
        } else {
            emptyList()
        }

        val recommendations = extractRecommendations(document)

        val data = document.selectFirst("div#media-info, div.anime-info")
        val genres = data?.select("div:contains(Genre:), div:contains(Gênero:) > span > a")?.map { it.text() }

        if (isMovie) {
            return newMovieLoadResponse(cleanTitle, url, type, url) {
                this.year = year ?: siteMetadata.year
                this.plot = siteMetadata.plot
                this.tags = (genres ?: emptyList()) + (siteMetadata.tags ?: emptyList())
                this.posterUrl = siteMetadata.poster
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
            }
        } else {
            return newAnimeLoadResponse(cleanTitle, url, type) {
                addEpisodes(DubStatus.Subbed, episodes)
                
                this.year = year ?: siteMetadata.year
                this.plot = siteMetadata.plot
                this.tags = (genres ?: emptyList()) + (siteMetadata.tags ?: emptyList())
                this.posterUrl = siteMetadata.poster
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
            }
        }
    }

    private suspend fun createAnimeNotFoundResponse(title: String, url: String): LoadResponse {
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.plot = """
                ❌ Este anime está no AniList mas não foi encontrado no AnimeFire.
                
                📝 Título: $title
                
                🔍 O que você pode fazer:
                1. Tente buscar manualmente usando a função de busca
                2. Verifique se o nome está correto
                3. O anime pode não estar disponível no AnimeFire
                
                💡 Dica: Busque por "$title" na função de pesquisa
            """.trimIndent()
        }
    }

    // ============ BUSCAR MAL ID (CORRIGIDA) ============
    private suspend fun searchMALIdByName(animeName: String): Int? {
        return try {
            val cleanName = animeName
                .replace(Regex("(?i)\\s*-\\s*Todos os Episódios"), "")
                .replace(Regex("(?i)\\s*\\(Dublado\\)"), "")
                .replace(Regex("(?i)\\s*\\(Legendado\\)"), "")
                .trim()
            
            // Query GraphQL do AniList
            val query = """
                query {
                    Page(page: 1, perPage: 5) {
                        media(search: "$cleanName", type: ANIME) {
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
                val json = JsonParser.parseString(response.text).asJsonObject
                val data = json.getAsJsonObject("data")
                val page = data?.getAsJsonObject("Page")
                val mediaArray = page?.getAsJsonArray("media")
                
                mediaArray?.firstOrNull()?.asJsonObject?.get("idMal")?.asInt
            } else {
                null
            }
        } catch (e: Exception) {
            println("❌ Erro ao buscar MAL ID: ${e.message}")
            null
        }
    }

    // ============ FUNÇÕES AUXILIARES ============
    private fun parseAnimeData(jsonString: String): AniZipData? {
        return try {
            val objectMapper = ObjectMapper()
            objectMapper.readValue(jsonString, AniZipData::class.java)
        } catch (e: Exception) {
            println("❌ [ANIZIP] Erro parse: ${e.message}")
            null
        }
    }

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

    private suspend fun extractEpisodesFromSite(
        document: org.jsoup.nodes.Document,
        animeTitle: String,
        aniZipData: AniZipData?
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        val episodeElements = document.select("a.lEp.epT, a.lEp, .divListaEps a, [href*='/video/'], [href*='/episodio/']")
        
        println("🔍 Encontrados ${episodeElements.size} episódios no site")
        
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
                
                val episodeDescription = aniZipEpisode?.overview ?: "Nenhuma descrição disponível"

                episodes.add(
                    newEpisode(fixUrl(href)) {
                        this.name = episodeName
                        this.season = seasonNumber
                        this.episode = episodeNumber
                        this.description = episodeDescription
                        this.posterUrl = aniZipEpisode?.image ?: aniZipData?.images?.firstOrNull()?.url
                        this.runTime = aniZipEpisode?.runtime
                        
                        aniZipEpisode?.airDateUtc?.let { dateStr ->
                            try {
                                val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
                                val date = formatter.parse(dateStr)
                                this.date = date.time
                            } catch (e: Exception) {
                                // Ignorar erro de parsing
                            }
                        }
                    }
                )
                
                if (index % 10 == 0 || index == episodeElements.size - 1) {
                    println("✅ Ep $episodeNumber: $episodeName")
                }
                
            } catch (e: Exception) {
                println("❌ Erro episódio ${index + 1}: ${e.message}")
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
        return false
    }

    // ============ CLASSES DE DADOS ============
    private data class SiteMetadata(
        val poster: String? = null,
        val plot: String? = null,
        val tags: List<String>? = null,
        val year: Int? = null
    )

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
        @JsonProperty("coverImage") val coverImage: AniListCoverImage? = null,
        @JsonProperty("status") val status: String? = null
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
        @JsonProperty("extraLarge") val extraLarge: String? = null
    )

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
}

