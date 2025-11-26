package com.SuperFlix
// ... cole o código de SuperFlix.kt aqui (o código da resposta anterior) ...
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.util.*

class SuperFlix : MainAPI() {
    override var mainUrl = "https://superflix20.lol"
    override var name = "SuperFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "" to "Lançamentos",
        "series/page/" to "Séries",
        "filmes/page/" to "Filmes",
        "genero/acao/page/" to "Ação",
        "genero/comedia/page/" to "Comédia",
        "genero/terror/page/" to "Terror"
    )

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.attr("title").trim()
        val href = fixUrl(this.attr("href"))
        val posterUrl = this.select("img.card-img").attr("src")

        if (title.isBlank() || href.isBlank()) {
            return null
        }

        val type = if (href.contains("/filme/")) TvType.Movie else TvType.TvSeries

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (request.data.isEmpty()) {
            "$mainUrl/page/$page"
        } else {
            "$mainUrl/${request.data}/$page"
        }

        val document = app.get(url).document

        val contentElements = document.select("div.grid-launch.grid-launch.grid a.card")

        val home = contentElements.mapNotNull { it.toSearchResult() }

        val hasNext = document.select("a.next.page-numbers").isNotEmpty()

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document

        return document.select("div.grid-launch.grid-launch.grid a.card").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.post-title, h1.entry-title")?.text()?.trim() ?: ""

        val description = document.selectFirst("div.content-body p:last-of-type, div.entry-content p")?.text()?.trim()

        val posterUrl = document.selectFirst("div.img-body img, div.col-left img")?.attr("src")

        val genres = document.select("span.meta-gen a, div.categorias a").map { it.text().trim() }

        val type = if (url.contains("/filme/")) TvType.Movie else TvType.TvSeries

        if (type == TvType.Movie) {
            val episode = newEpisode(url) {
                this.name = title
                this.episode = 1
                this.season = 1
            }
            return newMovieLoadResponse(title, url, TvType.Movie, episode) {
                this.posterUrl = posterUrl
                this.plot = description
                this.tags = genres
            }
        } else {
            val episodes = document.select("div.lista-eps div.item, ul#seasons-list a")
                .mapIndexed { index, element ->
                    val epUrl = fixUrl(element.attr("href") ?: "")
                    val epTitle = element.text() ?: "Episódio ${index + 1}"

                    newEpisode(epUrl) {
                        this.name = epTitle.trim()
                        this.episode = index + 1
                    }
                }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.map { SeasonData(1, "", null) }) {
                this.posterUrl = posterUrl
                this.plot = description
                this.tags = genres
                addEpisodes(DubStatus.Subbed, episodes.reversed())
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        val playerLinks = document.select("iframe#embed-box, div.source-box a")

        for (link in playerLinks) {
            val embedUrl = if (link.tagName() == "iframe") {
                link.attr("src")
            } else {
                link.attr("href")
            }

            if (embedUrl.isNotBlank()) {
                val absoluteEmbedUrl = fixUrl(embedUrl)

                loadExtractor(absoluteEmbedUrl, mainUrl, subtitleCallback, callback)
            }
        }

        return true
    }
}
