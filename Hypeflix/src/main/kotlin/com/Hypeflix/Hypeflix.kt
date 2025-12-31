package com.Hypeflix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Hypeflix : MainAPI() {
    override var mainUrl = "https://hypeflix.info"
    override var name = "Hypeflix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true  // Agora tem suporte a download
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val usesWebView = false

    companion object {
        private val HOME_SECTIONS = listOf(
            "popular" to "Os mais populares",
            "lancamentos" to "Séries em lançamentos", 
            "filmes-recentes" to "Filmes recentes",
            "atualizacoes" to "Últimas atualizações",
            "animes" to "Animes"
        )
    }

    override val mainPage = mainPageOf(
        *HOME_SECTIONS.map { (section, name) -> 
            "home_$section" to name 
        }.toTypedArray()
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page == 1) {
            val document = app.get(mainUrl).document
            val sectionId = request.data.removePrefix("home_")
            val items = extractHomeSection(document, sectionId)
            return newHomePageResponse(request.name, items.distinctBy { it.url }, false)
        }
        return newHomePageResponse(request.name, emptyList(), false)
    }

    private fun extractHomeSection(document: org.jsoup.nodes.Document, sectionId: String): List<SearchResponse> {
        val sectionMap = mapOf(
            "popular" to "os-mais-populares",
            "lancamentos" to "sries-em-lanamentos", 
            "filmes-recentes" to "filmes-recentes",
            "atualizacoes" to "ltimas-atualizaes",
            "animes" to "animes"
        )
        
        val realSectionId = sectionMap[sectionId] ?: return emptyList()
        
        val section = document.selectFirst("section[aria-labelledby='$realSectionId']")
            ?: document.selectFirst("section.carousels")
            ?: return emptyList()
        
        return section.select("article").mapNotNull { article ->
            article.toSearchResult()
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = selectFirst("a") ?: return null
        val href = linkElement.attr("href").takeIf { it.isNotBlank() } ?: return null
        
        val titleElement = selectFirst("h3")
        val title = titleElement?.text()?.trim() ?: return null
        
        // Extract year
        val year = selectFirst("time.release-date")
            ?.attr("datetime")
            ?.substring(0, 4)
            ?.toIntOrNull()
            ?: Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        
        // Clean title
        val cleanTitle = title
            .replace(Regex("\\(\\d{4}\\)"), "")
            .replace(Regex("\\d{4}$"), "")
            .trim()
        
        val imgElement = selectFirst("img")
        val posterUrl = imgElement?.attr("src")?.let { fixUrl(it) }
        
        val isAnime = href.contains("/anime/")
        val isSerie = href.contains("/serie/") || href.contains("/tv/")
        
        return when {
            isAnime -> newAnimeSearchResponse(cleanTitle, fixUrl(href)) {
                this.posterUrl = posterUrl
                this.year = year
            }
            isSerie -> newTvSeriesSearchResponse(cleanTitle, fixUrl(href)) {
                this.posterUrl = posterUrl
                this.year = year
            }
            else -> newMovieSearchResponse(cleanTitle, fixUrl(href)) {
                this.posterUrl = posterUrl
                this.year = year
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/?s=$encodedQuery"
        val document = app.get(searchUrl).document
        
        return document.select("article").mapNotNull { element ->
            element.toSearchResult()
        }.ifEmpty {
            document.select("a[href*='/serie/'], a[href*='/filme/'], a[href*='/anime/']")
                .mapNotNull { link ->
                    val href = link.attr("href")
                    if (href.contains("/serie/") || href.contains("/filme/") || href.contains("/anime/")) {
                        val title = link.text().trim()
                        val cleanTitle = title
                            .replace(Regex("\\(\\d{4}\\)"), "")
                            .replace(Regex("\\d{4}$"), "")
                            .trim()
                        
                        if (cleanTitle.isNotEmpty()) {
                            val isSerie = href.contains("/serie/")
                            val isAnime = href.contains("/anime/")
                            
                            when {
                                isAnime -> newAnimeSearchResponse(cleanTitle, fixUrl(href)) {
                                    this.posterUrl = link.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                                }
                                isSerie -> newTvSeriesSearchResponse(cleanTitle, fixUrl(href)) {
                                    this.posterUrl = link.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                                }
                                else -> newMovieSearchResponse(cleanTitle, fixUrl(href)) {
                                    this.posterUrl = link.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                                }
                            }
                        } else null
                    } else null
                }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        val titleElement = document.selectFirst("h1")
        val title = titleElement?.text()?.trim() ?: return null
        
        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
        
        val isAnime = url.contains("/anime/")
        val isSerie = url.contains("/serie/")
        
        val description = document.selectFirst("meta[name='description']")
            ?.attr("content")?.ifBlank { null } 
            ?: document.selectFirst("p.description, .sinopse")?.text()?.trim()
            ?: document.selectFirst(".description")?.text()?.trim()
        
        val heroSection = document.selectFirst("section.hero")
        val backgroundStyle = heroSection?.attr("style")
        val backdropUrl = backgroundStyle?.let {
            Regex("url\\(['\"]?(.*?)['\"]?\\)").find(it)?.groupValues?.get(1)
        }?.let { fixUrl(it) }
        
        val ogImage = document.selectFirst("meta[property='og:image']")?.attr("content")
        val posterUrl = ogImage?.let { fixUrl(it) }
        
        // Extract episodes
        val episodes = if (isAnime || isSerie) {
            extractEpisodesWithSeasons(document)
        } else {
            // Para filmes, cria um episódio único com a URL do filme
            listOf(
                newEpisode(url) {
                    this.name = cleanTitle
                    this.season = null
                    this.episode = 1
                }
            )
        }
        
        val genres = document.select("a.chip, .chip, .genre, .tags").map { it.text().trim() }
            .takeIf { it.isNotEmpty() }
        
        return if (isAnime || isSerie) {
            val type = if (isAnime) TvType.Anime else TvType.TvSeries
            
            newTvSeriesLoadResponse(cleanTitle, url, type, episodes) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = description
                this.backgroundPosterUrl = backdropUrl
                this.tags = genres
            }
        } else {
            newMovieLoadResponse(cleanTitle, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = description
                this.backgroundPosterUrl = backdropUrl
                this.tags = genres
            }
        }
    }

    private fun extractEpisodesWithSeasons(document: org.jsoup.nodes.Document): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        // First try to find season selector
        val seasonSelector = document.selectFirst("#seasonSelect")
        val seasons = if (seasonSelector != null) {
            seasonSelector.select("option").mapNotNull { option ->
                option.attr("value").toIntOrNull()
            }
        } else {
            listOf(1)
        }
        
        // For each season, extract episodes
        seasons.forEach { seasonNumber ->
            val episodeElements = document.select(".episode-item")
                .filter { it.hasClass("season_number_$seasonNumber") || 
                         it.attr("data-season").toIntOrNull() == seasonNumber }
            
            if (episodeElements.isEmpty() && seasonNumber == 1) {
                // Fallback
                document.select(".episode-item").forEachIndexed { index, episodeElement ->
                    extractSingleEpisode(episodeElement, seasonNumber, index + 1)?.let {
                        episodes.add(it)
                    }
                }
            } else {
                episodeElements.forEachIndexed { index, episodeElement ->
                    extractSingleEpisode(episodeElement, seasonNumber, index + 1)?.let {
                        episodes.add(it)
                    }
                }
            }
        }
        
        return episodes
    }

    private fun extractSingleEpisode(
        episodeElement: Element,
        seasonNumber: Int,
        defaultEpisodeNumber: Int
    ): Episode? {
        return try {
            // Primeiro tenta pegar o data-protected
            var dataProtected = episodeElement.attr("data-protected")
            
            // Se não tiver, tenta outras formas de extrair o link
            if (dataProtected.isBlank()) {
                // Tenta pegar link do botão de play
                val playButton = episodeElement.selectFirst("a.btn-play, button[data-url]")
                dataProtected = playButton?.attr("href") ?: playButton?.attr("data-url") ?: ""
            }
            
            // Se ainda não tiver link, tenta pegar da URL de redirecionamento
            if (dataProtected.isBlank()) {
                val episodeLink = episodeElement.selectFirst("a[href*='/assistir/']")
                dataProtected = episodeLink?.attr("href") ?: ""
            }
            
            // Se ainda estiver vazio, retorna null
            if (dataProtected.isBlank()) {
                return null
            }
            
            val titleElement = episodeElement.selectFirst(".episode-title")
            val title = titleElement?.text()?.trim() ?: "Episódio $defaultEpisodeNumber"
            
            // Extract episode number
            val epNumber = episodeElement.attr("data-ep").toIntOrNull()
                ?: Regex("""Ep\.?\s*(\d+)""").find(title)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""Epis[oó]dio\s*(\d+)""").find(title)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(\d+)""").find(title)?.groupValues?.get(1)?.toIntOrNull()
                ?: defaultEpisodeNumber
            
            val descriptionElement = episodeElement.selectFirst(".episode-description")
            val description = descriptionElement?.text()?.trim()
            
            val durationElement = episodeElement.selectFirst(".episode-number")
            val durationText = durationElement?.text()
            val episodeDuration = durationText?.let {
                Regex("""(\d+)\s*min""").find(it)?.groupValues?.get(1)?.toIntOrNull()
            }
            
            // Build description with duration
            val fullDescription = buildString {
                description?.let { append(it) }
                episodeDuration?.let { 
                    if (isNotEmpty()) append("\n\n")
                    append("Duração: ${it} min")
                }
            }.takeIf { it.isNotEmpty() }
            
            val imgElement = episodeElement.selectFirst("img")
            val episodePoster = imgElement?.attr("src")?.let { fixUrl(it) }
            
            // Para filmes, usar a URL direta, para séries, formatar com pipe
            val episodeUrl = if (title.contains("Filme", ignoreCase = true) || episodeElement.hasClass("movie")) {
                dataProtected
            } else {
                "$dataProtected|poster=${episodePoster ?: ""}"
            }
            
            newEpisode(episodeUrl) {
                this.name = title
                this.season = seasonNumber
                this.episode = epNumber
                this.description = fullDescription
                this.posterUrl = episodePoster
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    println("[Hypeflix] === loadLinks chamado ===")
    println("[Hypeflix] Data recebida: $data")
    println("[Hypeflix] isCasting: $isCasting")
    println("[Hypeflix] Main URL: $mainUrl")
    
    // Chamar o extractor para processar os links
    val result = HypeflixExtractor.extractVideoLinks(data, mainUrl, callback)
    
    println("[Hypeflix] Resultado do extractor: $result")
    println("[Hypeflix] === loadLinks finalizado ===")
    
    return result
}
