package com.UltraCine

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.toScore

class UltraCine : MainAPI() {
    override var mainUrl = "https://ultracine.org"
    override var name = "UltraCine"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "pt-BR"

    override val mainPage = mainPageOf(
        "$mainUrl/category/lancamentos/" to "Lançamentos",
        "$mainUrl/category/acao/" to "Ação",
        "$mainUrl/category/animacao/" to "Animação",
        "$mainUrl/category/comedia/" to "Comédia",
        "$mainUrl/category/drama/" to "Drama",
        "$mainUrl/category/fantasia/" to "Fantasia",
        "$mainUrl/category/ficcao-cientifica/" to "Ficção Científica",
        "$mainUrl/category/terror/" to "Terror",
        "$mainUrl/category/thriller/" to "Thriller"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + if (page > 1) "page/$page/" else ""
        val doc = app.get(url, timeout = 20).document
        val items = doc.select("div.aa-cn ul.post-lst li").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleEl = selectFirst("header h1.entry-title a") ?: return null
        val title = titleEl.text() ?: return null
        val href = fixUrl(titleEl.attr("href"))
        val poster = selectFirst("img")?.attr("src")?.let { fixUrl(it) }
            ?.replace("/w500/", "/original/")?.replace("/w780/", "/original/")

        val year = selectFirst("span.year")?.text()?.toIntOrNull()
        val quality = selectFirst("span.post-ql")?.text()?.let { getQualityFromString(it) }

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
            this.year = year
            this.quality = quality
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "\( mainUrl/?s= \){query.trim().replace(" ", "+")}"
        val doc = app.get(url).document
        return doc.select("div.aa-cn ul.post-lst li").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1.entry-title")?.text() ?: return null
        val poster = doc.selectFirst("div.bghd img")?.attr("src")
            ?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
            ?.replace("/w1280/", "/original/")

        val year = doc.selectFirst("span.year")?.text()?.toIntOrNull()
        val duration = doc.selectFirst("span.duration")?.text()
        val plot = doc.selectFirst("div.description p")?.text()
        val tags = doc.select("span.genres a").map { it.text() }
        val rating = doc.selectFirst("div.vote span.num")?.text()?.toDoubleOrNull()

        val actors = doc.select("ul.cast-lst a").mapNotNull {
            Actor(it.text(), it.attr("href"))
        }

        val trailer = doc.selectFirst("div.video iframe")?.attr("src")?.let { fixUrl(it) }

        val isSeries = url.contains("/serie/") || doc.select("div.seasons").isNotEmpty()

        // Extract player iframe or episode ID
        val playerIframe = doc.selectFirst("iframe[src*='assistir']")?.attr("src")
            ?: doc.selectFirst("iframe[data-lazy-src*='assistir']")?.attr("data-lazy-src")

        return if (isSeries) {
            val episodes = mutableListOf<Episode>()
            if (playerIframe != null && playerIframe.contains("assistirseriesonline")) {
                episodes.addAll(parseSeriesEpisodes(playerIframe))
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = rating?.times(10)?.roundToInt()?.toScore()
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, playerIframe ?: url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.duration = parseDuration(duration)
                this.score = rating?.times(10)?.roundToInt()?.toScore()
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    private suspend fun parseSeriesEpisodes(iframeUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        try {
            val doc = app.get(iframeUrl, referer = mainUrl).document

            // New structure: episodes are inside data-episode-id attributes
            doc.select("li[data-episode-id]").forEach { el ->
                val epId = el.attr("data-episode-id").takeIf { it.isNotBlank() } ?: return@forEach
                val season = el.attr("data-season-number").toIntOrNull() ?: 1
                val title = el.selectFirst("a")?.text() ?: "Episódio $epId"
                val epNum = title.substringBefore(" ").toIntOrNull() ?: 1

                episodes.add(newEpisode(epId) {
                    this.name = title
                    this.season = season
                    this.episode = epNum
                })
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return episodes
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        // Case 1: Episode ID (numeric)
        if (data.matches(Regex("^\\d+$"))) {
            val epUrl = "https://assistirseriesonline.icu/episodio/$data"
            return loadFromEpisodePage(epUrl, subtitleCallback, callback)
        }

        // Case 2: Direct iframe URL
        if (data.startsWith("http")) {
            return loadFromEpisodePage(data, subtitleCallback, callback)
        }

        return false
    }

    private suspend fun loadFromEpisodePage(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val res = app.get(url, referer = mainUrl, timeout = 20)

            // Cloudflare IUAM bypass if needed
            val doc = if (res.isSuccess) res.document else return false

            // Option 1: embedplay buttons (most common now)
            val button = doc.selectFirst("button[data-source*='embedplay.upns.pro']")
                ?: doc.selectFirst("button[data-source*='embedplay.upn.one']")
                ?: doc.selectFirst("button[data-source*='play']")

            if (button != null) {
                val source = button.attr("data-source")
                if (source.isNotBlank()) {
                    loadExtractor(source, url, subtitleCallback, callback)
                    return true
                }
            }

            // Option 2: Direct iframe in player
            val iframe = doc.selectFirst("#player iframe, div.play-overlay iframe")
            if (iframe != null) {
                var src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                if (src.startsWith("//")) src = "https:$src"
                if (src.isNotBlank()) {
                    loadExtractor(src, url, subtitleCallback, callback)
                    return true
                }
            }

            // Option 3: WebView resolver for protected players
            WebViewResolver().resolveUsingWebView(app.get(url).text).forEach {
                callback.invoke(it)
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    private fun parseDuration(text: String?): Int? = text?.let {
        Regex("(\\d+)h.*?(\\d+)m").find(it)?.let { m ->
            (m.groupValues[1].toInt() * 60) + m.groupValues[2].toInt()
        } ?: Regex("(\\d+)m").find(it)?.groupValues?.get(1)?.toInt()
    }
}