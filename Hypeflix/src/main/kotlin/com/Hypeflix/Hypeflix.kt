package com.Hypeflix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element

class Hypeflix : MainAPI() {
    override var mainUrl = "https://hypeflix.site"
    override var name = "Hypeflix"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "pt-br"
    
    override val mainPage = mainPageOf(
        "$mainUrl/lancamentos/" to "Lançamentos",
        "$mainUrl/series/" to "Séries",
        "$mainUrl/filmes/" to "Filmes",
        "$mainUrl/animes/" to "Animes",
        "$mainUrl/top/" to "Top"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data + if (page > 1) "page/$page/" else ""
        val document = app.get(url).document

        val items = document.select("div.card, a.card, div.movie-item, div.serie-item").mapNotNull { element ->
            element.toSearchResult()
        }

        val hasNextPage = document.select("a:contains(Próxima), a:contains(>), .pagination").isNotEmpty()

        return newHomePageResponse(request.name, items.distinctBy { it.url }, hasNextPage)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("href") ?: return null
        val title = selectFirst("h3, h2, .title, img[alt]")?.text() ?: 
                   selectFirst("img")?.attr("alt") ?: return null
        
        val poster = selectFirst("img[src]")?.attr("src")?.let { fixUrl(it) } ?: ""
        val cleanTitle = cleanTitle(title)
        val year = extractYearFromTitle(title)
        
        // Determinar tipo pelo href ou classe
        val isMovie = href.contains("/filme/") || hasClass("movie") || 
                      selectFirst(".type")?.text()?.contains("Filme") == true
        val isSerie = href.contains("/serie/") || hasClass("serie") || 
                      selectFirst(".type")?.text()?.contains("Série") == true

        return when {
            isMovie -> newMovieSearchResponse(cleanTitle, fixUrl(href)) {
                this.posterUrl = poster
                this.year = year
            }
            isSerie -> newTvSeriesSearchResponse(cleanTitle, fixUrl(href)) {
                this.posterUrl = poster
                this.year = year
            }
            else -> newMovieSearchResponse(cleanTitle, fixUrl(href)) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    private fun cleanTitle(title: String): String {
        return title.replace(Regex("\\(\\d{4}\\)"), "")
            .replace("(Dublado)", "")
            .replace("(Legendado)", "")
            .trim()
    }

    private fun extractYearFromTitle(title: String): Int? {
        return Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.encodeUri()}"
        val document = app.get(searchUrl).document

        return document.select("div.card, a.card, div.search-item").mapNotNull { element ->
            val href = element.attr("href") ?: return@mapNotNull null
            val title = element.selectFirst("h3, h2, .title, img[alt]")?.text() ?: 
                       element.selectFirst("img")?.attr("alt") ?: return@mapNotNull null
            
            val poster = element.selectFirst("img[src]")?.attr("src")?.let { fixUrl(it) } ?: ""
            val cleanTitle = cleanTitle(title)
            val year = extractYearFromTitle(title)
            
            // Determinar tipo
            val isMovie = href.contains("/filme/")
            val isSerie = href.contains("/serie/")

            when {
                isMovie -> newMovieSearchResponse(cleanTitle, fixUrl(href)) {
                    this.posterUrl = poster
                    this.year = year
                }
                isSerie -> newTvSeriesSearchResponse(cleanTitle, fixUrl(href)) {
                    this.posterUrl = poster
                    this.year = year
                }
                else -> newMovieSearchResponse(cleanTitle, fixUrl(href)) {
                    this.posterUrl = poster
                    this.year = year
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // Extrair título
        val title = document.selectFirst("h1, .title")?.text() ?: ""
        val cleanTitle = cleanTitle(title)
        val year = extractYearFromTitle(title)

        // Extrair poster
        val poster = document.selectFirst("img.poster, .poster img, .thumbnail img")?.attr("src")?.let { fixUrl(it) } ?: ""
        
        // Extrair sinopse
        val plot = document.selectFirst("div.sinopse, .description, .plot, p")?.text() ?: ""
        
        // Extrair gêneros/tags
        val tags = document.select("div.genres a, .tags a, .category a").map { it.text() }.takeIf { it.isNotEmpty() }

        // Extrair atores - CORREÇÃO: usar Actor (não ActorData)
        val actors = document.select("div.cast a, .actors a").map { Actor(it.text()) }.takeIf { it.isNotEmpty() }

        // Determinar se é filme ou série
        val isMovie = url.contains("/filme/") || 
                      document.selectFirst("div.player-container") != null ||
                      document.select("div.seasons, .episode-list, .season-list").isEmpty()

        // Extrair recomendações do site
        val recommendations = extractRecommendations(document)

        if (isMovie) {
            return newMovieLoadResponse(cleanTitle, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                // CORREÇÃO: usar addActors em vez de passar no construtor
                if (!actors.isNullOrEmpty()) {
                    addActors(actors)
                }
                this.recommendations = recommendations
            }
        } else {
            // É uma série - extrair episódios
            val episodes = extractEpisodes(document, url)

            return newTvSeriesLoadResponse(cleanTitle, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                // CORREÇÃO: usar addActors em vez de passar no construtor
                if (!actors.isNullOrEmpty()) {
                    addActors(actors)
                }
                this.recommendations = recommendations
            }
        }
    }

    private suspend fun extractEpisodes(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()

        // Tentar diferentes seletores de episódios
        val selectors = listOf(
            "div.episode-item",
            "li.episode-item",
            "a.episode-card",
            ".episode-list a",
            "button[data-url]",
            "[class*='episode']"
        )

        selectors.forEach { selector ->
            document.select(selector).forEachIndexed { index, element ->
                try {
                    val episodeUrl = element.attr("data-url") ?: element.attr("href") ?: return@forEachIndexed
                    if (episodeUrl.isBlank()) return@forEachIndexed

                    val episodeNumber = extractEpisodeNumber(element, index + 1)
                    val seasonNumber = element.attr("data-season").toIntOrNull() ?: 1
                    val episodeTitle = element.selectFirst(".episode-title, .title")?.text() ?: "Episódio $episodeNumber"
                    val episodePoster = element.selectFirst("img[src]")?.attr("src")?.let { fixUrl(it) }

                    episodes.add(
                        newEpisode(episodeUrl) {
                            this.name = episodeTitle
                            this.season = seasonNumber
                            this.episode = episodeNumber
                            this.posterUrl = episodePoster
                        }
                    )
                } catch (e: Exception) {
                    // Ignorar erros e continuar
                }
            }
        }

        // Se não encontrar episódios, criar um episódio com a URL principal
        if (episodes.isEmpty()) {
            episodes.add(
                newEpisode(baseUrl) {
                    this.name = "Episódio 1"
                    this.season = 1
                    this.episode = 1
                }
            )
        }

        return episodes.distinctBy { it.data }
    }

    private fun extractEpisodeNumber(element: Element, default: Int): Int {
        return element.attr("data-ep").toIntOrNull() ?:
               element.selectFirst(".ep-number, .number")?.text()?.toIntOrNull() ?:
               Regex("Ep\\.?\\s*(\\d+)").find(element.text())?.groupValues?.get(1)?.toIntOrNull() ?:
               Regex("\\b(\\d+)\\b").find(element.text())?.groupValues?.get(1)?.toIntOrNull() ?:
               default
    }

    private fun extractRecommendations(document: org.jsoup.nodes.Document): List<SearchResponse> {
        return document.select(".recs-grid .rec-card, .related a, .similar a").mapNotNull { element ->
            try {
                val href = element.attr("href") ?: return@mapNotNull null
                if (href.isBlank() || href == "#") return@mapNotNull null

                val title = element.selectFirst("img")?.attr("alt") ?:
                           element.selectFirst(".title, h3, h4")?.text() ?:
                           element.attr("title") ?: return@mapNotNull null

                val poster = element.selectFirst("img[src]")?.attr("src")?.let { fixUrl(it) } ?: ""
                val cleanTitle = cleanTitle(title)
                val year = extractYearFromTitle(title)
                
                val isMovie = href.contains("/filme/")
                val isSerie = href.contains("/serie/")

                when {
                    isMovie -> newMovieSearchResponse(cleanTitle, href) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    isSerie -> newTvSeriesSearchResponse(cleanTitle, href) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    else -> newMovieSearchResponse(cleanTitle, href) {
                        this.posterUrl = poster
                        this.year = year
                    }
                }
            } catch (e: Exception) {
                null
            }
        }.distinctBy { it.url }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return HypeflixExtractor.extractVideoLinks(data, mainUrl, callback)
    }

    private fun String.encodeUri(): String {
        return java.net.URLEncoder.encode(this, "UTF-8")
    }
}
