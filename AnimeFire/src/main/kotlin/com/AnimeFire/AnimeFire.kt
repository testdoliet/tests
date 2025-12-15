package com.AnimeFire

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import java.text.SimpleDateFormat
import java.net.URLEncoder
import kotlinx.coroutines.delay
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AnimeFire : MainAPI() {
    override var mainUrl = "https://animefire.io"
    override var name = "AnimeFire"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)
    override val usesWebView = true

    // ============ APIs para dados adicionais ============
    private val ANIZIP_API_URL = "https://api.ani.zip"
    private val objectMapper = ObjectMapper()

    // ============ CONSTANTES ============
    companion object {
        private const val SEARCH_PATH = "/pesquisar"
        private const val MAX_TRIES = 2
        private const val RETRY_DELAY = 1000L
    }

    // Página principal com 4 abas
    override val mainPage = mainPageOf(
        "$mainUrl" to "Lançamentos",
        "$mainUrl" to "Destaques da Semana", 
        "$mainUrl" to "Últimos Animes Adicionados",
        "$mainUrl" to "Últimos Episódios Adicionados"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        
        val homeItems = when (request.name) {
            "Lançamentos" -> extractLancamentos(document)
            "Destaques da Semana" -> extractDestaquesSemana(document)
            "Últimos Animes Adicionados" -> extractUltimosAnimes(document)
            "Últimos Episódios Adicionados" -> extractUltimosEpisodios(document)
            else -> emptyList()
        }
        
        return newHomePageResponse(request.name, homeItems.distinctBy { it.url })
    }

    // 1. LANÇAMENTOS
    private fun extractLancamentos(document: org.jsoup.nodes.Document): List<SearchResponse> {
        return document.select(".owl-carousel-home .divArticleLancamentos a.item")
            .mapNotNull { it.toSearchResult() }
            .take(15)
    }

    // 2. DESTAQUES DA SEMANA
    private fun extractDestaquesSemana(document: org.jsoup.nodes.Document): List<SearchResponse> {
        return document.select(".owl-carousel-semana .divArticleLancamentos a.item")
            .mapNotNull { it.toSearchResult() }
            .take(15)
    }

    // 3. ÚLTIMOS ANIMES ADICIONADOS
    private fun extractUltimosAnimes(document: org.jsoup.nodes.Document): List<SearchResponse> {
        return document.select(".owl-carousel-l_dia .divArticleLancamentos a.item")
            .mapNotNull { it.toSearchResult() }
            .take(15)
    }

    // 4. ÚLTIMOS EPISÓDIOS ADICIONADOS
    private fun extractUltimosEpisodios(document: org.jsoup.nodes.Document): List<SearchResponse> {
        return document.select(".divCardUltimosEpsHome")
            .mapNotNull { it.toEpisodeSearchResult() }
            .take(20)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("href")?.takeIf { it.isNotBlank() } ?: return null
        val title = selectFirst("h3.animeTitle")?.text()?.trim() ?: return null
        
        // Buscar imagem de forma mais específica
        val imgElement = selectFirst("img.imgAnimes, img[src*='animes'], img.owl-lazy, img:not([src*='logo'])")
        val poster = when {
            imgElement?.hasAttr("data-src") == true -> imgElement.attr("data-src")
            imgElement?.hasAttr("src") == true -> imgElement.attr("src")
            else -> null
        } ?: return null
        
        // Filtrar imagens inválidas (lupa, logos, etc)
        if (poster.contains("lupa", ignoreCase = true) || 
            poster.contains("logo", ignoreCase = true) ||
            poster.contains("icon", ignoreCase = true)) {
            return null
        }
        
        val cleanTitle = title.replace(Regex("(?i)(dublado|legendado|todos os episódios|\\(\\d{4}\\))$"), "").trim()
        val isMovie = href.contains("/filmes/") || title.contains("Movie", ignoreCase = true)
        
        return if (isMovie) {
            newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                this.posterUrl = fixUrl(poster)
            }
        } else {
            newAnimeSearchResponse(cleanTitle, fixUrl(href), TvType.Anime) {
                this.posterUrl = fixUrl(poster)
            }
        }
    }

    private fun Element.toEpisodeSearchResult(): SearchResponse? {
        val link = selectFirst("article.card a") ?: return null
        val href = link.attr("href")?.takeIf { it.isNotBlank() } ?: return null
        val titleElement = selectFirst("h3.animeTitle") ?: return null
        val title = titleElement.text().trim()
        
        val epNumberElement = selectFirst(".numEp")
        val epNumber = epNumberElement?.text()?.toIntOrNull() ?: 1
        
        // Buscar imagem específica para episódios
        val imgElement = selectFirst("img.imgAnimesUltimosEps, img[src*='animes'], img.transitioning_src, img:not([src*='logo'])")
        val poster = when {
            imgElement?.hasAttr("data-src") == true -> imgElement.attr("data-src")
            imgElement?.hasAttr("src") == true -> imgElement.attr("src")
            else -> null
        } ?: return null
        
        // Filtrar imagens inválidas
        if (poster.contains("lupa", ignoreCase = true) || 
            poster.contains("logo", ignoreCase = true)) {
            return null
        }
        
        val cleanTitle = "${title} - Episódio $epNumber"
        
        return newAnimeSearchResponse(cleanTitle, fixUrl(href), TvType.Anime) {
            this.posterUrl = fixUrl(poster)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl$SEARCH_PATH/${URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document

        return document.select("article.containerAnimes a.item, a[href*='/animes/']")
            .mapNotNull { element ->
                try {
                    element.toSearchResult()
                } catch (e: Exception) {
                    null
                }
            }
            .distinctBy { it.url }
            .take(30)
    }

    override suspend fun load(url: String): LoadResponse? {
        println("🔍 [DEBUG] AnimeFire: Iniciando load para URL: $url")

        val document = app.get(url).document

        // Título principal
        val titleElement = document.selectFirst("h1.quicksand400, h1.title, h1") ?: return null
        val title = titleElement.text().trim()
        println("🔍 [DEBUG] AnimeFire: Título encontrado: $title")

        // Extrair ano
        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
        println("🔍 [DEBUG] AnimeFire: Título limpo: $cleanTitle | Ano: $year")

        // Determinar tipo
        val isMovie = url.contains("/filmes/") || title.contains("Movie", ignoreCase = true)
        val isTv = !isMovie && (url.contains("/animes/") || title.contains("Episódio", ignoreCase = true))
        
        // Buscar MAL ID pelo nome (modificado para evitar chamada suspend)
        val malId = findMALId(cleanTitle, document)
        println("🔍 [DEBUG] AnimeFire: MAL ID encontrado: $malId")

        // Buscar dados da ani.zip (se tiver MAL ID)
        val aniZipData = if (malId != null) {
            println("🔍 [DEBUG] AnimeFire: Buscando dados da ani.zip...")
            fetchAniZipData(malId)
        } else {
            println("⚠️ [DEBUG] AnimeFire: SEM MAL ID, usando dados do site")
            null
        }

        // Buscar poster correto no site
        val sitePoster = findSitePoster(document)
        val sitePlot = findSitePlot(document)
        val tags = findSiteTags(document)
        val recommendations = extractRecommendationsFromSite(document)

        // Combinar dados
        val finalTitle = aniZipData?.titles?.values?.firstOrNull() ?: cleanTitle
        val finalPoster = aniZipData?.images?.find { 
            it.coverType.equals("poster", ignoreCase = true) || 
            it.coverType.equals("banner", ignoreCase = true)
        }?.url ?: sitePoster
        
        val finalBackdrop = aniZipData?.images?.find { 
            it.coverType.equals("fanart", ignoreCase = true) || 
            it.coverType.equals("background", ignoreCase = true)
        }?.url
        
        val finalPlot = aniZipData?.episodes?.values?.firstOrNull()?.overview ?: sitePlot

        println("🏗️ [DEBUG] Título final: $finalTitle")
        println("🏗️ [DEBUG] Poster final: $finalPoster")
        println("🏗️ [DEBUG] Backdrop final: $finalBackdrop")
        println("🏗️ [DEBUG] Plot final (primeiros 50 chars): ${finalPlot?.take(50)}...")
        println("🏗️ [DEBUG] Tags: $tags")

        // Extrair episódios
        val episodes = if (isTv) {
            extractEpisodesWithData(document, aniZipData, url)
        } else {
            emptyList()
        }

        println("🏗️ [DEBUG] Total episódios: ${episodes.size}")

        // Criar resposta
        return if (isTv) {
            newTvSeriesLoadResponse(finalTitle, url, TvType.Anime, episodes) {
                this.posterUrl = finalPoster
                this.backgroundPosterUrl = finalBackdrop
                this.year = year
                this.plot = finalPlot
                this.tags = tags
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
            }
        } else {
            val playerUrl = findPlayerUrl(document) ?: url
            newMovieLoadResponse(finalTitle, url, TvType.Movie, fixUrl(playerUrl)) {
                this.posterUrl = finalPoster
                this.backgroundPosterUrl = finalBackdrop
                this.year = year
                this.plot = finalPlot
                this.tags = tags
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
            }
        }
    }

    // ============ FUNÇÕES AUXILIARES ============

    private fun findMALId(animeName: String, document: org.jsoup.nodes.Document): Int? {
        // Tentar extrair da meta tag (se existir)
        val metaTag = document.selectFirst("meta[property='mal:anime_id'], meta[name='mal-id']")
        metaTag?.attr("content")?.toIntOrNull()?.let { return it }
        
        // Tentar extrair do link MyAnimeList na página
        val malLink = document.selectFirst("a[href*='myanimelist.net/anime/'], a[href*='mal.co/']")
        malLink?.attr("href")?.let { href ->
            Regex("anime/(\\d+)").find(href)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        }
        
        // Tentar extrair de dados embutidos
        val scriptContent = document.select("script").html()
        Regex("mal[_-]?id[=:]['\"]?(\\d+)").find(scriptContent)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        
        // Último recurso: buscar pelo título (removido chamada suspend aqui)
        return null
    }

    private suspend fun fetchAniZipData(malId: Int): AniZipData? {
        for (attempt in 1..MAX_TRIES) {
            try {
                println("🔍 [ANIZIP] Buscando dados para MAL ID: $malId (tentativa $attempt)")
                
                val response = app.get("$ANIZIP_API_URL/mappings?mal_id=$malId", timeout = 10_000)
                
                if (response.code == 200) {
                    val data = response.parsedSafe<AniZipData>()
                    if (data != null && (data.titles?.isNotEmpty() == true || data.images?.isNotEmpty() == true)) {
                        println("✅ [ANIZIP] Dados obtidos com sucesso!")
                        return data
                    }
                } else if (response.code == 404) {
                    println("❌ [ANIZIP] MAL ID não encontrado")
                    return null
                }
            } catch (e: Exception) {
                println("❌ [ANIZIP] Exception: ${e.message}")
            }
            
            if (attempt < MAX_TRIES) {
                delay(RETRY_DELAY * attempt)
            }
        }
        return null
    }

    private fun findSitePoster(document: org.jsoup.nodes.Document): String? {
        // Buscar em várias posições possíveis
        val selectors = listOf(
            "img.transitioning_src",
            ".sub_anime_img img",
            ".anime-poster img",
            ".cover img",
            "img[src*='/animes/']",
            "img[src*='/covers/']",
            "img:not([src*='logo']):not([src*='lupa']):not([src*='icon'])"
        )
        
        for (selector in selectors) {
            val img = document.selectFirst(selector)
            if (img != null) {
                val src = when {
                    img.hasAttr("data-src") -> img.attr("data-src")
                    img.hasAttr("src") -> img.attr("src")
                    else -> null
                }
                if (src != null && !src.contains("logo", ignoreCase = true) && 
                    !src.contains("lupa", ignoreCase = true)) {
                    return fixUrl(src)
                }
            }
        }
        return null
    }

    private fun findSitePlot(document: org.jsoup.nodes.Document): String? {
        return document.selectFirst(".divSinopse, .sinopse, .description, .plot")?.text()?.trim()
            ?.replace(Regex("^Sinopse:\\s*"), "")
            ?.replace(Regex("^Descrição:\\s*"), "")
    }

    private fun findSiteTags(document: org.jsoup.nodes.Document): List<String> {
        return document.select(".animeInfo a.spanAnimeInfo, .spanGeneros, .tags a, .genre a, .category a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun extractRecommendationsFromSite(document: org.jsoup.nodes.Document): List<SearchResponse> {
        return document.select(".owl-carousel-anime .divArticleLancamentos a.item, .recommendations a")
            .mapNotNull { element ->
                try {
                    element.toSearchResult()
                } catch (e: Exception) {
                    null
                }
            }
            .distinctBy { it.url }
            .take(10)
    }

    private suspend fun extractEpisodesWithData(
        document: org.jsoup.nodes.Document,
        aniZipData: AniZipData?,
        baseUrl: String
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        val episodeElements = document.select(".div_video_list a.lEp, a.lep, .episode-item a, a[href*='episodio']")
        
        println("🔍 [EPISODES] Elementos encontrados no site: ${episodeElements.size}")
        
        episodeElements.forEachIndexed { index, element ->
            try {
                val episodeHref = element.attr("href")?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
                val episodeNumber = extractEpisodeNumber(element, index + 1)
                
                // Verificar se temos dados da ani.zip para este episódio
                val aniZipEpisode = aniZipData?.episodes?.get(episodeNumber.toString())
                
                val episode = if (aniZipEpisode != null) {
                    println("✅ [EPISODES] Dados ani.zip encontrados para ep $episodeNumber")
                    
                    newEpisode(fixUrl(episodeHref)) {
                        this.name = aniZipEpisode.title?.values?.firstOrNull() ?: "Episódio $episodeNumber"
                        this.season = 1
                        this.episode = episodeNumber
                        this.posterUrl = aniZipEpisode.image
                        this.description = aniZipEpisode.overview
                        
                        aniZipEpisode.airDateUtc?.let { airDate ->
                            try {
                                val dateFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
                                val date = dateFormatter.parse(airDate)
                                this.date = date.time
                            } catch (e: Exception) {
                                // Ignorar erro de data
                            }
                        }
                    }
                } else {
                    newEpisode(fixUrl(episodeHref)) {
                        this.name = "Episódio $episodeNumber"
                        this.season = 1
                        this.episode = episodeNumber
                    }
                }
                
                episodes.add(episode)
            } catch (e: Exception) {
                println("❌ [EPISODES] Erro no episódio ${index + 1}: ${e.message}")
            }
        }
        
        println("✅ [EPISODES] Total processados: ${episodes.size}")
        return episodes.sortedBy { it.episode }.distinctBy { it.episode }
    }

    private fun extractEpisodeNumber(element: Element, default: Int): Int {
        return element.attr("data-ep").toIntOrNull() ?:
               element.selectFirst(".numEp, .ep-number")?.text()?.toIntOrNull() ?:
               Regex("Ep\\.?\\s*(\\d+)").find(element.text())?.groupValues?.get(1)?.toIntOrNull() ?:
               Regex("(\\d+)").find(element.text())?.groupValues?.get(1)?.toIntOrNull() ?:
               default
    }

    private fun findPlayerUrl(document: org.jsoup.nodes.Document): String? {
        return document.selectFirst("button[data-url], a[data-url]")?.attr("data-url") ?:
               document.selectFirst("iframe[src]")?.attr("src") ?:
               document.selectFirst("a[href*='.mp4'], a[href*='.m3u8'], video source")?.attr("href")
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return AnimeFireExtractor.extractVideoLinks(data, mainUrl, name, callback)
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
        @JsonProperty("rating") val rating: String?,
        @JsonProperty("airDateUtc") val airDateUtc: String?
    )
}
