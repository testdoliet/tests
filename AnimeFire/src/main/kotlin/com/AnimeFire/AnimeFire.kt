package com.AnimeFire

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder
import kotlinx.coroutines.delay

class AnimeFire : MainAPI() {
    // URL correta do site
    override var mainUrl = "https://animefire.io"
    override var name = "AnimeFire"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)
    override val usesWebView = true

    // ============ CONSTANTES ============
    companion object {
        private const val SEARCH_PATH = "/pesquisar"
        private const val MAX_TRIES = 3
        private const val RETRY_DELAY = 1000L
    }

    // 4 ABAS DA PÁGINA INICIAL
    override val mainPage = mainPageOf(
        "$mainUrl" to "Lançamentos",
        "$mainUrl" to "Destaques da Semana",
        "$mainUrl" to "Últimos Animes Adicionados",
        "$mainUrl" to "Últimos Episódios Adicionados"
    )

    // ============ FUNÇÃO AUXILIAR PARA SEARCH RESPONSE ============
    private fun Element.toSearchResponse(): AnimeSearchResponse? {
        val href = attr("href") ?: return null
        if (href.isBlank()) return null
        
        val titleElement = selectFirst("h3.animeTitle") ?: return null
        val title = titleElement.text().trim()
        
        // Extrair imagem
        val imgElement = selectFirst("img.imgAnimes, img.owl-lazy, img[src*='animes']")
        val poster = when {
            imgElement?.hasAttr("data-src") == true -> imgElement.attr("data-src")
            imgElement?.hasAttr("src") == true -> imgElement.attr("src")
            else -> selectFirst("img:not([src*='logo']):not([src*='Logo'])")?.attr("src")
        } ?: return null
        
        // Filtrar logo do site
        if (poster.contains("logo", ignoreCase = true)) return null
        
        // Limpar título
        val cleanTitle = title.replace(Regex("(?i)(dublado|legendado|todos os episódios|\\(\\d{4}\\))$"), "").trim()
        
        // Verificar se é filme
        val isMovie = href.contains("/filmes/") || title.contains("Movie", ignoreCase = true)
        
        return newAnimeSearchResponse(cleanTitle, fixUrl(href)) {
            this.posterUrl = fixUrl(poster)
            this.type = if (isMovie) TvType.Movie else TvType.Anime
        }
    }

    // ============ FUNÇÕES PARA EXTRACTION ============
    private fun extractFromCarousel(document: org.jsoup.nodes.Document, selector: String): List<AnimeSearchResponse> {
        return document.select(selector).mapNotNull { it.toSearchResponse() }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        
        val homeItems = when (request.name) {
            "Lançamentos" -> 
                extractFromCarousel(document, ".owl-carousel-home .divArticleLancamentos a.item")
            "Destaques da Semana" -> 
                extractFromCarousel(document, ".owl-carousel-semana .divArticleLancamentos a.item")
            "Últimos Animes Adicionados" -> 
                extractFromCarousel(document, ".owl-carousel-l_dia .divArticleLancamentos a.item")
            "Últimos Episódios Adicionados" -> {
                document.select(".divCardUltimosEpsHome").mapNotNull { card ->
                    val link = card.selectFirst("article.card a") ?: return@mapNotNull null
                    val href = link.attr("href") ?: return@mapNotNull null
                    
                    val titleElement = card.selectFirst("h3.animeTitle") ?: return@mapNotNull null
                    val title = titleElement.text().trim()
                    
                    val epNumber = card.selectFirst(".numEp")?.text()?.toIntOrNull() ?: 1
                    
                    val imgElement = card.selectFirst("img.imgAnimesUltimosEps, img[src*='animes']")
                    val poster = when {
                        imgElement?.hasAttr("data-src") == true -> imgElement.attr("data-src")
                        imgElement?.hasAttr("src") == true -> imgElement.attr("src")
                        else -> card.selectFirst("img:not([src*='logo'])")?.attr("src")
                    } ?: return@mapNotNull null
                    
                    val cleanTitle = "${title} - Episódio $epNumber"
                    
                    newAnimeSearchResponse(cleanTitle, fixUrl(href)) {
                        this.posterUrl = fixUrl(poster)
                        this.type = TvType.Anime
                    }
                }
            }
            else -> emptyList()
        }
        
        return newHomePageResponse(request.name, homeItems.distinctBy { it.url }, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl$SEARCH_PATH/${URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document

        return document.select("article.containerAnimes a.item")
            .mapNotNull { it.toSearchResponse() }
            .take(30)
    }

    override suspend fun load(url: String): LoadResponse {
        println("🔍 [DEBUG] AnimeFire: Carregando URL: $url")
        
        val document = app.get(url).document

        // Título
        val titleElement = document.selectFirst("h1.quicksand400, .main_div_anime_info h1, h1") ?: return null
        val rawTitle = titleElement.text().trim()
        
        // Limpar título
        val year = Regex("\\((\\d{4})\\)").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = rawTitle.replace(Regex("\\(\\d{4}\\)"), "").trim()
        
        // Determinar tipo
        val isMovie = url.contains("/filmes/") || rawTitle.contains("Movie", ignoreCase = true)
        val type = if (isMovie) TvType.Movie else TvType.Anime

        // Buscar MAL ID
        val malId = searchMALIdByName(cleanTitle)
        
        // Buscar dados ani.zip
        val aniZipData = if (malId != null) {
            fetchAniZipData(malId)
        } else {
            null
        }

        // Extrair episódios (se for anime)
        val episodes = if (!isMovie) {
            extractEpisodes(document, aniZipData)
        } else {
            emptyList()
        }

        // Extrair metadados
        val metadata = extractMetadata(document)
        
        // Extrair recomendações
        val recommendations = extractRecommendations(document)

        // Criar resposta
        return newAnimeLoadResponse(cleanTitle, url, type) {
            if (!isMovie) {
                addEpisodes(DubStatus.Subbed, episodes)
            }
            
            addMalId(malId)
            
            // Metadados
            this.year = year ?: metadata.year
            this.plot = metadata.plot
            this.tags = metadata.tags
            this.showStatus = metadata.status
            this.posterUrl = metadata.poster
            this.backgroundPosterUrl = aniZipData?.images
                ?.firstOrNull { it.coverType.equals("Fanart", ignoreCase = true) }?.url
            this.recommendations = recommendations.takeIf { it.isNotEmpty() }
        }
    }

    // ============ FUNÇÕES AUXILIARES ============

    private data class Metadata(
        val year: Int? = null,
        val plot: String? = null,
        val tags: List<String>? = null,
        val status: ShowStatus? = null,
        val poster: String? = null
    )

    private fun extractMetadata(document: org.jsoup.nodes.Document): Metadata {
        // Poster
        val posterImg = document.selectFirst(".sub_animepage_img img.transitioning_src")
        val poster = when {
            posterImg?.hasAttr("src") == true -> fixUrl(posterImg.attr("src"))
            posterImg?.hasAttr("data-src") == true -> fixUrl(posterImg.attr("data-src"))
            else -> document.selectFirst("img[src*='/img/animes/']:not([src*='logo'])")
                ?.attr("src")?.let { fixUrl(it) }
        }

        // Sinopse
        val plot = document.selectFirst("div.divSinopse span.spanAnimeInfo")
            ?.text()
            ?.trim()
            ?.replace(Regex("^Sinopse:\\s*"), "")

        // Tags/Gêneros
        val tags = document.select("a.spanAnimeInfo.spanGeneros")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .takeIf { it.isNotEmpty() }

        // Ano
        val year = document.selectFirst("div.animeInfo:contains(Ano:) span.spanAnimeInfo")
            ?.text()
            ?.trim()
            ?.toIntOrNull()

        // Status
        val statusText = document.selectFirst("div.animeInfo:contains(Status:) span.spanAnimeInfo")
            ?.text()
            ?.trim()
        val status = when (statusText?.lowercase()) {
            "completo", "finalizado" -> ShowStatus.Completed
            "lançamento", "em andamento" -> ShowStatus.Ongoing
            else -> null
        }

        return Metadata(year, plot, tags, status, poster)
    }

    private suspend fun extractEpisodes(
        document: org.jsoup.nodes.Document,
        aniZipData: AniZipData?
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        document.select("a.lEp.epT, a.lEp").forEach { element ->
            try {
                val href = element.attr("href") ?: return@forEach
                val text = element.text().trim()
                
                // Extrair número do episódio
                val episodeNumber = extractEpisodeNumber(text)
                
                // Buscar dados da ani.zip
                val aniZipEpisode = aniZipData?.episodes?.get(episodeNumber.toString())
                
                episodes.add(newEpisode(fixUrl(href)) {
                    this.episode = episodeNumber
                    this.season = 1
                    this.name = aniZipEpisode?.title?.values?.firstOrNull()
                        ?: text.substringAfterLast("-").trim()
                    this.posterUrl = aniZipEpisode?.image
                    this.description = aniZipEpisode?.overview
                })
            } catch (e: Exception) {
                println("❌ Erro ao extrair episódio: ${e.message}")
            }
        }
        
        return episodes.sortedBy { it.episode }
    }

    private fun extractEpisodeNumber(text: String): Int {
        return Regex("Epis[oó]dio\\s*(\\d+)").find(text)
            ?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("\\b(\\d{1,4})\\b").find(text)
            ?.groupValues?.get(1)?.toIntOrNull()
            ?: 1
    }

    private fun extractRecommendations(document: org.jsoup.nodes.Document): List<SearchResponse> {
        return document.select(".owl-carousel-anime .divArticleLancamentos a.item")
            .mapNotNull { it.toSearchResponse() }
    }

    // ============ BUSCA MAL ID ============
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
                headers = mapOf("Content-Type" to "application/json"),
                timeout = 10_000
            )
            
            if (response.code == 200) {
                response.parsedSafe<AniListResponse>()?.data?.Page?.media?.firstOrNull()?.idMal
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    // ============ BUSCA ANI.ZIP ============
    private suspend fun fetchAniZipData(malId: Int): AniZipData? {
        for (attempt in 1..MAX_TRIES) {
            try {
                val response = app.get("https://api.ani.zip/mappings?mal_id=$malId", timeout = 10_000)
                
                if (response.code == 200) {
                    return response.parsedSafe<AniZipData>()
                }
            } catch (e: Exception) {
                println("⚠️ Tentativa $attempt falhou: ${e.message}")
            }
            
            if (attempt < MAX_TRIES) delay(RETRY_DELAY * attempt)
        }
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return AnimeFireExtractor.extractVideoLinks(data, mainUrl, name, subtitleCallback, callback)
    }

    // ============ CLASSES DE DADOS ============

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListResponse(
        @JsonProperty("data") val data: AniListData?
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListData(
        @JsonProperty("Page") val Page: AniListPage?
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListPage(
        @JsonProperty("media") val media: List<AniListMedia>?
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListMedia(
        @JsonProperty("idMal") val idMal: Int?
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniZipData(
        @JsonProperty("titles") val titles: Map<String, String>? = null,
        @JsonProperty("images") val images: List<AniZipImage>? = null,
        @JsonProperty("episodes") val episodes: Map<String, AniZipEpisode>? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniZipImage(
        @JsonProperty("coverType") val coverType: String?,
        @JsonProperty("url") val url: String?
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniZipEpisode(
        @JsonProperty("episode") val episode: String?,
        @JsonProperty("title") val title: Map<String, String>?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("image") val image: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("airDateUtc") val airDateUtc: String?
    )
}

// ============ EXTRACTOR ============
object AnimeFireExtractor {
    suspend fun extractVideoLinks(
        data: String,
        mainUrl: String,
        name: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val document = app.get(data).document
            
            // Procura por iframes
            document.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank()) {
                    loadExtractor(src, "$name - Iframe", subtitleCallback, callback)
                }
            }
            
            // Procura por links diretos
            document.select("a[href*='.mp4'], a[href*='.m3u8']").forEach { link ->
                val href = link.attr("href")
                if (href.isNotBlank()) {
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = "$name - Direct",
                            url = href,
                            referer = mainUrl,
                            quality = Qualities.Unknown.value,
                            isM3u8 = href.contains(".m3u8")
                        )
                    )
                }
            }
            
            return document.select("iframe[src]").isNotEmpty() || 
                   document.select("a[href*='.mp4'], a[href*='.m3u8']").isNotEmpty()
        } catch (e: Exception) {
            return false
        }
    }
}
