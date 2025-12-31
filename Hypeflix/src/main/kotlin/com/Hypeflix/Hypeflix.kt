package com.Hypeflix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class HypeFlix : MainAPI() {
    override var mainUrl = "https://hypeflix.site"
    override var name = "HypeFlix"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val lang = "pt"

    // Seletores CSS
    private val movieSelector = "div.card"
    private val serieSelector = "div.card-serie"
    private val episodeSelector = "div.episode-item, li.episode-item"
    private val searchSelector = "div.search-item"
    private val playerSelector = "iframe[src*='player'], video source[src]"

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
        val url = request.data + page
        val document = app.get(url).document

        val items = document.select("div.card, div.card-serie").mapNotNull { element ->
            val href = element.selectFirst("a[href]")?.attr("href") ?: return@mapNotNull null
            val title = element.selectFirst("h3, h2, .title")?.text() ?: return@mapNotNull null
            val poster = element.selectFirst("img[src]")?.attr("src") ?: ""

            // Determinar tipo (Filme ou Série)
            val type = if (href.contains("/filme/") || element.hasClass("movie")) {
                TvType.Movie
            } else {
                TvType.TvSeries
            }

            if (type == TvType.Movie) {
                newMovieLoadResponse(title, href) {
                    this.posterUrl = poster
                    this.plot = ""
                }
            } else {
                newTvSeriesLoadResponse(title, href) {
                    this.posterUrl = poster
                    this.plot = ""
                }
            }
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document

        return document.select(searchSelector).mapNotNull { element ->
            val href = element.selectFirst("a[href]")?.attr("href") ?: return@mapNotNull null
            val title = element.selectFirst("h3, h2, .title")?.text() ?: return@mapNotNull null
            val poster = element.selectFirst("img[src]")?.attr("src") ?: ""
            val year = element.selectFirst(".year")?.text()?.toIntOrNull()

            // Determinar tipo
            val isMovie = href.contains("/filme/") || element.selectFirst(".type")?.text()?.contains("Filme") == true

            if (isMovie) {
                newMovieSearchResponse(title, href) {
                    this.posterUrl = poster
                    this.year = year
                }
            } else {
                newTvSeriesSearchResponse(title, href) {
                    this.posterUrl = poster
                    this.year = year
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.title")?.text() ?: ""
        val poster = document.selectFirst("img.poster")?.attr("src") ?: ""
        val plot = document.selectFirst("div.sinopse, .plot")?.text() ?: ""
        val year = document.selectFirst("span.year")?.text()?.toIntOrNull()
        val tags = document.select("div.genres a").map { it.text() }
        val actors = document.select("div.cast a").map { it.text() }

        // Verificar se é filme ou série
        val isMovie = url.contains("/filme/") || 
                      document.selectFirst("div.player-container") != null ||
                      document.select("div.seasons").isEmpty()

        if (isMovie) {
            // É um filme
            val recommendations = document.select("div.related div.card").mapNotNull { related ->
                val href = related.selectFirst("a[href]")?.attr("href") ?: return@mapNotNull null
                val relTitle = related.selectFirst("h3")?.text() ?: return@mapNotNull null
                val relPoster = related.selectFirst("img[src]")?.attr("src") ?: ""

                newMovieLoadResponse(relTitle, href) {
                    this.posterUrl = relPoster
                }
            }

            return newMovieLoadResponse(title, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.actors = actors
                this.recommendations = recommendations
            }
        } else {
            // É uma série
            val seasons = document.select("div.season").mapIndexed { seasonIndex, seasonElement ->
                val seasonNumber = (seasonIndex + 1)
                val episodes = seasonElement.select("div.episode-item, li").mapNotNull { epElement ->
                    val epHref = epElement.selectFirst("a[href]")?.attr("href") ?: return@mapNotNull null
                    val epTitle = epElement.selectFirst(".episode-title")?.text() ?: "Episódio ${seasonIndex + 1}"
                    val epNumber = epElement.selectFirst(".episode-number")?.text()?.toIntOrNull() ?: (seasonIndex + 1)
                    val epPoster = epElement.selectFirst("img[src]")?.attr("src") ?: poster

                    newEpisode(epHref) {
                        this.name = epTitle
                        this.season = seasonNumber
                        this.episode = epNumber
                        this.posterUrl = epPoster
                    }
                }
                newTvSeason(seasonNumber, episodes)
            }

            val recommendations = document.select("div.related div.card-serie").mapNotNull { related ->
                val href = related.selectFirst("a[href]")?.attr("href") ?: return@mapNotNull null
                val relTitle = related.selectFirst("h3")?.text() ?: return@mapNotNull null
                val relPoster = related.selectFirst("img[src]")?.attr("src") ?: ""

                newTvSeriesLoadResponse(relTitle, href) {
                    this.posterUrl = relPoster
                }
            }

            return newTvSeriesLoadResponse(title, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.actors = actors
                this.seasons = seasons
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return HypeFlixExtractor.extractVideoLinks(data, mainUrl, callback)
    }
}
