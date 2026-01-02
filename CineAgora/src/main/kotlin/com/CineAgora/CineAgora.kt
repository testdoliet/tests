package com.CineAgora

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import java.net.URLEncoder

class CineAgora : MainAPI() {
    override var mainUrl = "https://cineagora.net"
    override var name = "CineAgora"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val usesWebView = false

    companion object {
        private val HOME_SECTIONS = listOf(
            "ultimos-filmes" to "Últimos Filmes",
            "ultimas-series" to "Últimas Séries"
        )

        private val SECTION_URLS = mapOf(
            "ultimos-filmes" to "https://cineagora.net/filmes-hd-online/",
            "ultimas-series" to "https://cineagora.net/series-online-hd-gratis/",
            "filmes-populares" to "https://cineagora.net/filmes-hd-online/filmes-populares-hd/",
            "series-populares" to "https://cineagora.net/series-online-hd-gratis/series-populares-hd/",
            "netflix" to "https://cineagora.net/netflix/",
            "paramount" to "https://cineagora.net/paramount/",
            "disney" to "https://cineagora.net/disney/",
            "apple" to "https://cineagora.net/apple/",
            "hbo" to "https://cineagora.net/hbo/",
            "acao" to "https://cineagora.net/filmes-hd-online/filmes-de-acao-hd/",
            "aventura" to "https://cineagora.net/filmes-hd-online/filmes-de-aventura-gratis/",
            "animacao" to "https://cineagora.net/filmes-hd-online/filmes-de-animacao-online/",
            "biograficos" to "https://cineagora.net/filmes-hd-online/assistir-filmes-biograficos/",
            "comedia" to "https://cineagora.net/filmes-hd-online/comedia-filmes-online/",
            "crime" to "https://cineagora.net/filmes-hd-online/crime-filmes-online/",
            "documentarios" to "https://cineagora.net/filmes-hd-online/documentarios-em-portugues/",
            "esporte" to "https://cineagora.net/filmes-hd-online/filmes-de-esporte-hd/",
            "drama" to "https://cineagora.net/filmes-hd-online/filmes-drama-online-hd/",
            "familia" to "https://cineagora.net/filmes-hd-online/filmes-familia-online/",
            "fantasia" to "https://cineagora.net/filmes-hd-online/filmes-fantasia-magia/",
            "historicos" to "https://cineagora.net/filmes-hd-online/filmes-historicos-hd/",
            "terror" to "https://cineagora.net/filmes-hd-online/filmes-terror-horror/",
            "musicais" to "https://cineagora.net/filmes-hd-online/filmes-musicais-online/",
            "misterio" to "https://cineagora.net/filmes-hd-online/filmes-misterio-suspense/",
            "romanticos" to "https://cineagora.net/filmes-hd-online/filmes-romanticos-online/",
            "suspense" to "https://cineagora.net/filmes-hd-online/filmes-suspense-hd/",
            "sci-fi" to "https://cineagora.net/filmes-hd-online/ficcao-cientifica-hd/",
            "tv" to "https://cineagora.net/filmes-hd-online/filmes-para-tv-hd/",
            "thriller" to "https://cineagora.net/filmes-hd-online/thriller-suspense-online/",
            "guerra" to "https://cineagora.net/filmes-hd-online/filmes-guerra-epicas/",
            "faroeste" to "https://cineagora.net/filmes-hd-online/filmes-faroeste-online/"
        )
    }

    override val mainPage = mainPageOf(
        *HOME_SECTIONS.map { (section, name) -> "home_$section" to name }.toTypedArray(),
        *SECTION_URLS.filterKeys { it !in HOME_SECTIONS.map { it.first } }
            .map { (section, _) -> "section_$section" to getSectionName(section) }.toTypedArray()
    )

    private fun getSectionName(section: String): String {
        return when (section) {
            "ultimos-filmes" -> "Últimos Filmes"
            "ultimas-series" -> "Últimas Séries"
            "filmes-populares" -> "Filmes Populares"
            "series-populares" -> "Séries Populares"
            "netflix" -> "Netflix"
            "paramount" -> "Paramount+"
            "disney" -> "Disney+"
            "apple" -> "Apple TV+"
            "hbo" -> "HBO Max"
            else -> section.replace("-", " ").split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sectionId = request.data.removePrefix("home_").removePrefix("section_")
        val baseUrl = SECTION_URLS[sectionId] ?: mainUrl
        val url = if (page > 1) {
            val cleanUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            "$cleanUrl/page/$page/"
        } else baseUrl

        val document = try {
            app.get(url).document
        } catch (e: Exception) {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
            throw e
        }

        val items = extractSectionItems(document, sectionId)
        val hasNextPage = checkForNextPage(document, page)

        return newHomePageResponse(request.name, items.distinctBy { it.url }, hasNextPage)
    }

    private fun checkForNextPage(document: org.jsoup.nodes.Document, currentPage: Int): Boolean {
        val nextLinks = document.select("a.next, a[href*='page/${currentPage + 1}/'], .pagination .next")
        if (nextLinks.isNotEmpty()) return true

        val pageNumbers = document.select(".page-numbers, .page-number")
            .mapNotNull { it.text().toIntOrNull() }
            .sorted()
        return pageNumbers.any { it > currentPage }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()

        val searchUrl = mainUrl
        return try {
            val document = app.post(
                url = searchUrl,
                data = mapOf("do" to "search", "subaction" to "search", "story" to query),
                referer = searchUrl
            ).document
            extractSearchResults(document)
        } catch (e: Exception) {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val doc = app.get("$mainUrl/?do=search&subaction=search&story=$encoded").document
                extractSearchResults(doc)
            } catch (e2: Exception) {
                emptyList()
            }
        }
    }

    private fun extractSearchResults(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val items = document.select(".film-list .content .item-relative > a.item")
        return if (items.isNotEmpty()) items.mapNotNull { it.toSearchResult() }
        else document.select(".item, .item-relative .item").mapNotNull { it.toSearchResult() }
    }

    private fun extractSectionItems(document: org.jsoup.nodes.Document, sectionId: String): List<SearchResponse> {
        return document.select(".item, .item-relative .item").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkEl = selectFirst("a") ?: return null
        val href = fixUrl(linkEl.attr("href").takeIf { it.isNotBlank() } ?: return null)

        val titleEl = selectFirst(".item-footer .title, .title, h3, h4") ?: return null
        var title = titleEl.text().trim()

        val year = selectFirst(".info span:first-child, .year")?.text()?.toIntOrNull()
            ?: Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()

        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()

        val posterUrl = selectFirst("img.thumbnail, img")?.attr("src")?.let { fixUrl(it) }

        val qualityBadge = selectFirst(".item-info div:first-child, .quality")?.text()?.trim()

        val quality = when {
            qualityBadge?.contains("HD", ignoreCase = true) == true -> SearchQuality.HD
            qualityBadge?.contains("4K", ignoreCase = true) == true -> SearchQuality.HD
            qualityBadge?.contains("TS", ignoreCase = true) == true -> SearchQuality.Cam
            else -> null
        }

        val isSerie = href.contains("/series-") || href.contains("/series-online") ||
                selectFirst(".episode, .data")?.text()?.contains(Regex("S\\d+.*E\\d+")) == true

        return if (isSerie) {
            newTvSeriesSearchResponse(cleanTitle, href) {
                this.posterUrl = posterUrl
                this.year = year
                this.quality = quality
            }
        } else {
            newMovieSearchResponse(cleanTitle, href) {
                this.posterUrl = posterUrl
                this.year = year
                this.quality = quality
            }
        }
    }

    private fun extractBannerUrl(doc: org.jsoup.nodes.Document): String? {
        val source = doc.selectFirst("picture source[srcset]")?.attr("srcset")
        if (!source.isNullOrBlank()) return fixUrl(source.trim())

        val imgInPicture = doc.selectFirst("picture img")?.attr("src")
        if (!imgInPicture.isNullOrBlank()) return fixUrl(imgInPicture.trim())

        return doc.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }
            ?: doc.selectFirst(".cover-img, .hero img")?.attr("src")?.let { fixUrl(it) }
    }

    private fun extractYear(doc: org.jsoup.nodes.Document): Int? {
        return doc.selectFirst(".year, .date, time")?.text()?.toIntOrNull()
            ?: Regex("(\\d{4})").find(doc.selectFirst("h1")?.text() ?: "")?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractGenres(doc: org.jsoup.nodes.Document): List<String>? {
        return doc.select(".info a[href*='/filmes-hd-online/'], .genres a")
            .mapNotNull { it.text().trim() }
            .filter { it.isNotBlank() }
            .takeIf { it.isNotEmpty() }
    }

    private fun extractTrailer(doc: org.jsoup.nodes.Document): String? {
        val patterns = listOf(
            """youtube\.com/embed/([^"']+)""",
            """youtube\.com/watch\?v=([^"']+)""",
            """youtu\.be/([^"']+)"""
        )
        val html = doc.html()
        for (pattern in patterns) {
            Regex(pattern).find(html)?.groupValues?.get(1)?.let { return "https://www.youtube.com/watch?v=$it" }
        }
        return null
    }

    private fun extractSeriesSlug(url: String): String {
        return url.substringAfterLast("/").substringAfter("-").substringBefore(".html")
    }

    private suspend fun extractSeriesEpisodes(seriesUrl: String, seriesTitle: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        try {
            val slug = extractSeriesSlug(seriesUrl)
            val json = app.get("https://watch.brplayer.cc/fetch_series_data.php?seriesSlug=$slug").text
            val data = AppUtils.tryParseJson<Map<String, Any>>(json) ?: return episodes

            @Suppress("UNCHECKED_CAST")
            val seasons = data["seasons"] as? Map<String, List<Map<String, Any>>> ?: return episodes

            seasons.forEach { (seasonNum, eps) ->
                eps.forEachIndexed { i, ep ->
                    val epNum = ep["episode_number"]?.toString()?.toIntOrNull() ?: (i + 1)
                    val videoSlug = ep["video_slug"]?.toString() ?: return@forEachIndexed
                    val epUrl = "https://watch.brplayer.cc/watch/$videoSlug"
                    val name = "\( seriesTitle S \){seasonNum.padStart(2, '0')}E${epNum.toString().padStart(2, '0')}"

                    episodes.add(newEpisode(epUrl) {
                        this.name = name
                        this.season = seasonNum.toIntOrNull()
                        this.episode = epNum
                    })
                }
            }
        } catch (e: Exception) { /* silencioso */ }
        return episodes.sortedBy { it.episode }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val bannerUrl = extractBannerUrl(doc)
        val posterUrl = doc.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }
            ?: doc.selectFirst("#info--box .cover-img, .cover img")?.attr("src")?.let { fixUrl(it) }
            ?: bannerUrl

        val title = doc.selectFirst("h1.title, h1")?.text()?.trim() ?: return null

        val isSerie = url.contains("/series-") || url.contains("/series-online") ||
                doc.select("#seasons, .seasons").isNotEmpty()

        val year = extractYear(doc)
        val plot = doc.selectFirst(".info-description, .description")?.text()?.trim()
        val genres = extractGenres(doc)
        val trailer = extractTrailer(doc)

        if (isSerie) {
            val episodes = extractSeriesEpisodes(url, title)
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = bannerUrl
                this.year = year
                this.plot = plot
                this.tags = genres
                trailer?.let { addTrailer(it) }
            }
        } else {
            val durationText = doc.selectFirst(".time, .duration")?.text()?.trim()
            val duration = durationText?.let { Regex("""(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = bannerUrl
                this.year = year
                this.plot = plot
                this.tags = genres
                this.duration = duration
                trailer?.let { addTrailer(it) }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.contains("youtube.com") || data.contains("youtu.be")) return false

        if (data.contains("watch.brplayer.cc/watch/")) {
            return CineAgoraExtractor.extractVideoLinks(data, "Vídeo", callback)
        }

        val doc = app.get(data).document
        val title = doc.selectFirst("h1.title, h1")?.text()?.trim() ?: "Filme"

        val playerUrl = doc.selectFirst(".mirrors .button.active[data-link*=watch.brplayer.cc], span.button.active[data-link*=watch.brplayer.cc]")
            ?.attr("abs:data-link")
            ?: doc.selectFirst(".mirrors .button[data-link*=watch.brplayer.cc]:contains(Dublado), span.button[data-link*=watch.brplayer.cc]")
                ?.attr("abs:data-link")
            ?: doc.selectFirst("iframe[src*=watch.brplayer.cc], iframe[data-src*=watch.brplayer.cc]")
                ?.let { it.attr("abs:src").takeIf { it.isNotBlank() } ?: it.attr("abs:data-src") }
            ?: doc.select("script").find { it.html().contains("watch.brplayer.cc/watch?v=") }
                ?.html()
                ?.let { Regex("""(https?://watch\.brplayer\.cc/watch\?v=[A-Za-z0-9]+)""").find(it)?.groupValues?.get(1) }

        if (playerUrl.isNullOrBlank()) return false

        return CineAgoraExtractor.extractVideoLinks(playerUrl, title, callback)
    }
}
