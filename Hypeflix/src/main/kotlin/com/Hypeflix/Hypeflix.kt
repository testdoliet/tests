package com.Hypeflix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import java.net.URLEncoder
import com.lagradost.cloudstream3.extractors.ExtractorLink

class Hypeflix : MainAPI() {
    override var mainUrl = "https://hypeflix.info"
    override var name = "HypeFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = false
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
        val href = linkElement.attr("href") ?: return null
        
        val titleElement = selectFirst("h3")
        val title = titleElement?.text()?.trim() ?: return null
        
        // CORREÇÃO 1: Extrair ano corretamente
        val yearElement = selectFirst("time.release-date")
        val year = yearElement?.attr("datetime")?.substring(0, 4)?.toIntOrNull()
            ?: Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        
        // CORREÇÃO 1: Limpar título removendo o ano
        val cleanTitle = title
            .replace(Regex("\\(\\d{4}\\)"), "") // Remove (2023)
            .replace(Regex("\\d{4}$"), "") // Remove 2023 no final
            .trim()
        
        val imgElement = selectFirst("img")
        val posterUrl = imgElement?.attr("src")?.let { fixUrl(it) }
        
        val isAnime = href.contains("/anime/")
        val isSerie = href.contains("/serie/") || href.contains("/tv/")
        
        return when {
            isAnime -> newAnimeSearchResponse(cleanTitle, fixUrl(href), TvType.Anime) {
                this.posterUrl = posterUrl
                this.year = year
            }
            isSerie -> newTvSeriesLoadResponse(cleanTitle, fixUrl(href), TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.year = year
            }
            else -> newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
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
                            newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                                this.posterUrl = link.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
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
        
        // CORREÇÃO 2: Extrair temporadas e episódios corretamente
        val episodes = if (isAnime || isSerie) {
            extractEpisodesWithSeasons(document)
        } else {
            emptyList()
        }
        
        val genres = document.select("a.chip, .chip, .genre, .tags").map { it.text().trim() }
            .takeIf { it.isNotEmpty() }
        
        if (isAnime || isSerie) {
            val type = if (isAnime) TvType.Anime else TvType.TvSeries
            
            return newTvSeriesLoadResponse(cleanTitle, url, type, episodes) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = backdropUrl
                this.year = year
                this.plot = description
                this.tags = genres
            }
        } else {
            return newMovieLoadResponse(cleanTitle, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = backdropUrl
                this.year = year
                this.plot = description
                this.tags = genres
            }
        }
    }

    // CORREÇÃO 2: Nova função para extrair temporadas corretamente
    private fun extractEpisodesWithSeasons(document: org.jsoup.nodes.Document): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        // Primeiro, tentar encontrar o seletor de temporada
        val seasonSelector = document.selectFirst("#seasonSelect")
        val seasons = if (seasonSelector != null) {
            // Se houver seletor de temporada, extrair opções
            seasonSelector.select("option").mapNotNull { option ->
                option.attr("value").toIntOrNull()
            }
        } else {
            // Se não houver, assumir temporada 1
            listOf(1)
        }
        
        // Para cada temporada, extrair episódios
        seasons.forEach { seasonNumber ->
            // Filtrar episódios por temporada
            val episodeElements = document.select(".episode-item")
                .filter { it.hasClass("season_number_$seasonNumber") || 
                         it.attr("data-season").toIntOrNull() == seasonNumber }
            
            if (episodeElements.isEmpty() && seasonNumber == 1) {
                // Fallback: se não encontrar classe específica, pega todos
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
            val dataProtected = episodeElement.attr("data-protected")
            if (dataProtected.isBlank()) return null
            
            val titleElement = episodeElement.selectFirst(".episode-title")
            val title = titleElement?.text()?.trim() ?: "Episódio $defaultEpisodeNumber"
            
            // Extrair número do episódio de várias formas
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
            
            // Construir descrição com duração
            val fullDescription = buildString {
                description?.let { append(it) }
                episodeDuration?.let { 
                    if (isNotEmpty()) append("\n\n")
                    append("Duração: ${it} min")
                }
            }.takeIf { it.isNotEmpty() }
            
            val imgElement = episodeElement.selectFirst("img")
            val episodePoster = imgElement?.attr("src")?.let { fixUrl(it) }
            
            newEpisode(fixUrl(dataProtected)) {
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
    // Se tiver suporte a download, usar o extrator
    if (hasDownloadSupport) {
        return HypeFlixExtractor.extractVideoLinks(data, mainUrl, callback)
    }
    return false
}

// Não esquecer de habilitar o download
override val hasDownloadSupport = true
