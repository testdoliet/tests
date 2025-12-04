package com.UltraCine

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver

class UltraCine : MainAPI() {
    override var name = "UltraCine"
    override var lang = "pt"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true
    override val hasQuickSearch = true

    override val mainUrl = "https://ultracine.tv"

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Início",
        "$mainUrl/filmes/" to "Filmes",
        "$mainUrl/series/" to "Séries",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data + if (page > 1) "page/$page/" else "").document
        val items = doc.select("article.item").mapNotNull {
            val a = it.selectFirst("a") ?: return@mapNotNull null
            val title = a.selectFirst(".title")?.text() ?: a.attr("title") ?: ""
            val href = fixUrl(a.attr("href"))
            val poster = a.selectFirst("img")?.attr("src") ?: a.selectFirst("img")?.attr("data-src")
            val isMovie = it.hasClass("movies")
            newHomePageItem(
                name = title,
                url = href,
                posterUrl = poster,
                type = if (isMovie) TvType.Movie else TvType.TvSeries
            )
        }
        return newHomepageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "\( mainUrl/?s= \){query.replace(" ", "+")}"
        val doc = app.get(url).document
        return doc.select("article.item").mapNotNull {
            val a = it.selectFirst("a") ?: return@mapNotNull null
            val title = a.selectFirst(".title")?.text() ?: ""
            val href = fixUrl(a.attr("href"))
            val poster = a.selectFirst("img")?.attr("src") ?: a.selectFirst("img")?.attr("data-src")
            val isMovie = it.hasClass("movies")
            if (isMovie) {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                }
            } else {
                newAnimeSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst(".data h1")?.text() ?: return null
        val poster = doc.selectFirst(".poster img")?.attr("src")
        val isMovie = doc.selectFirst(".sgeneros")?.text()?.contains("Filme") == true

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
            }
        } else {
            val episodes = doc.select(".seasons .se-c").flatMap { season ->
                val seasonNum = season.selectFirst(".title")?.text()?.replace(Regex("[^0-9]"), "")?.toIntOrNull() ?: 0
                season.select(".episodios li").mapNotNull { ep ->
                    val epNum = ep.selectFirst(".num")?.text()?.toIntOrNull() ?: return@mapNotNull null
                    val epUrl = fixUrl(ep.selectFirst("a")?.attr("href") ?: return@mapNotNull null)
                    newEpisode(epUrl) {
                        this.name = "T$seasonNum:E$epNum"
                        this.season = seasonNum
                        this.episode = epNum
                    }
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        doc.select(".player option").forEach { opt ->
            val playerUrl = opt.attr("value")
            if (playerUrl.isNotBlank() && playerUrl.contains("http")) {
                loadExtractor(playerUrl, mainUrl, subtitleCallback, callback)
            }
        }

        // WebView resolver para players embutidos
        WebViewResolver(Regex(""".*player.*""")).resolveUsingWebView(app.get(data).text) { url ->
            callback.invoke(
                ExtractorLink(
                    name,
                    name,
                    url,
                    "",
                    Qualities.Unknown.value,
                    isM3u8 = url.contains(".m3u8")
                )
            )
        }

        return true
    }
}

class EmbedPlayExtractor : ExtractorApi() {
    override val name = "EmbedPlay"
    override val mainUrl = "https://embedplay.ultracine.tv"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val response = app.get(url, referer = referer ?: mainUrl)
        val regex = Regex("""file:\s*["']([^"']+\.m3u8[^"']*)["']""")
        val m3u8 = regex.find(response.text)?.groupValues?.get(1) ?: return emptyList()
        return listOf(
            ExtractorLink(
                name,
                name,
                m3u8,
                referer ?: mainUrl,
                Qualities.Unknown.value,
                isM3u8 = true
            )
        )
    }
}