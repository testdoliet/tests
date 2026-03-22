package com.PobreFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.nodes.Element

class PobreFlix : MainAPI() {
    override var mainUrl = "https://lospobreflix.site/"
    override var name = "PobreFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val usesWebView = true

    companion object {
        private const val SEARCH_PATH = "/pesquisar"

        private val FIXED_CATEGORIES = listOf(
            "/filmes" to "Filmes",
            "/series" to "Séries",
            "/animes" to "Animes",
            "/doramas" to "Doramas",
            "/calendario" to "Calendário",
            "" to "Em Alta"
        )
    }

    override val mainPage = mainPageOf(
        *FIXED_CATEGORIES.map { (path, name) ->
            if (path.isEmpty()) mainUrl to name
            else "$mainUrl$path" to name
        }.toTypedArray()
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = request.data
        val url = if (page > 1 && baseUrl != mainUrl) {
            if (baseUrl.contains("?")) {
                "$baseUrl&page=$page"
            } else {
                "$baseUrl?page=$page"
            }
        } else {
            baseUrl
        }

        val document = app.get(url).document

        val home = document.select("a.card, article.relative.group/item, .swiper-slide article.relative.group/item, .grid article.relative.group/item")
            .mapNotNull { element ->
                element.toSearchResult()
            }

        val hasNextPage = document.select("a:contains(Próxima), .page-numbers a[href*='page']").isNotEmpty() ||
                         document.select(".pagination").isNotEmpty()

        return newHomePageResponse(request.name, home.distinctBy { it.url }, hasNextPage)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = attr("title") ?: selectFirst("img")?.attr("alt") ?: return null
        val href = attr("href") ?: return null

        val localPoster = selectFirst("img")?.attr("src")?.let { fixUrl(it) }
        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()

        val badge = selectFirst(".badge-kind, .border-slate-300, .border-white\\/10, .px-1\\.5")?.text()?.lowercase() ?: ""
        val isAnime = badge.contains("anime") || href.contains("/anime/") ||
                      title.contains("(Anime)", ignoreCase = true)
        val isSerie = badge.contains("série") || badge.contains("serie") || badge.contains("dorama") ||
                     href.contains("/serie/") || href.contains("/dorama/") ||
                     (!isAnime && (badge.contains("tv") || href.contains("/tv/")))

        return when {
            isAnime -> {
                newAnimeSearchResponse(cleanTitle, fixUrl(href), TvType.Anime) {
                    this.posterUrl = localPoster
                    this.year = year
                }
            }
            isSerie -> {
                newTvSeriesSearchResponse(cleanTitle, fixUrl(href), TvType.TvSeries) {
                    this.posterUrl = localPoster
                    this.year = year
                }
            }
            else -> {
                newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                    this.posterUrl = localPoster
                    this.year = year
                }
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl$SEARCH_PATH?s=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document

        return document.select(".grid .card, a.card, article.relative.group/item").mapNotNull { card ->
            try {
                val title = card.attr("title") ?: card.selectFirst("img")?.attr("alt") ?: return@mapNotNull null
                val href = card.attr("href") ?: return@mapNotNull null

                val poster = card.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
                val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()

                val badge = card.selectFirst(".badge-kind, .border-slate-300")?.text()?.lowercase() ?: ""
                val isAnime = badge.contains("anime") || href.contains("/anime/") ||
                             title.contains("(Anime)", ignoreCase = true)
                val isSerie = badge.contains("série") || badge.contains("serie") || badge.contains("dorama") ||
                             href.contains("/serie/") || href.contains("/dorama/") ||
                             (!isAnime && (badge.contains("tv") || href.contains("/tv/")))

                when {
                    isAnime -> newAnimeSearchResponse(cleanTitle, fixUrl(href), TvType.Anime) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    isSerie -> newTvSeriesSearchResponse(cleanTitle, fixUrl(href), TvType.TvSeries) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    else -> newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                        this.posterUrl = poster
                        this.year = year
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val titleElement = document.selectFirst("h1, .text-3xl.text-lead.font-bold")
        val title = titleElement?.text() ?: return null

        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()

        val isAnime = url.contains("/anime/") || title.contains("(Anime)", ignoreCase = true)
        val isSerie = url.contains("/serie/") || url.contains("/dorama/") || 
                     (!isAnime && document.selectFirst("#episodes-list, .season-dropdown, .episode-card") != null)

        val ogImage = document.selectFirst("meta[property='og:image']")?.attr("content")
        val poster = ogImage?.let { fixUrl(it) }

        val synopsis = document.selectFirst(".text-slate-700.dark\\:text-slate-200.md\\:text-lg, .text-slate-900\\/90.dark\\:text-slate-100\\/90, meta[name='description']")?.attr("content")?.trim()
        val plot = synopsis

        val tags = document.select(".flex.flex-wrap.gap-2 a, .px-3.py-1.rounded-full.text-xs.bg-slate-200")
            .map { it.text() }
            .takeIf { it.isNotEmpty() }

        val ratingPercent = document.selectFirst("text[x='18'][y='21']")?.text()?.replace("%", "")?.toFloatOrNull()
        val scoreValue = ratingPercent?.let { it / 10 }

        val backdrop = document.selectFirst(".absolute.left-1\\/2 img, .blur-\\[4px\\] img")?.attr("src")?.let { fixUrl(it) }

        val durationText = document.selectFirst(".bg-slate-200.dark\\:bg-slate-700.rounded-lg.p-3:contains(min) .font-medium, .inline-flex.items-center.rounded-full.px-3.py-1:contains(min)")?.text()
        val duration = durationText?.let { 
            Regex("(\\d+)\\s*min").find(it)?.groupValues?.get(1)?.toIntOrNull()
        }

        val cast = document.select("#cast-section .swiper-slide .text-sm.font-bold, .swiper-slide .text-sm.font-bold")
            .map { it.text() }
            .takeIf { it.isNotEmpty() }
            ?.map { Actor(name = it) }

        val trailerKey = document.selectFirst("script:containsData(window.__trailerKeys)")?.data()?.let { script ->
            Regex("window\\.__trailerKeys\\s*=\\s*\\[\"([^\"]+)\"\\]").find(script)?.groupValues?.get(1)
        }

        val siteRecommendations = document.select("#relatedSection .swiper-slide a, .related-swiper .swiper-slide a")
            .mapNotNull { element ->
                try {
                    val href = element.attr("href") ?: return@mapNotNull null
                    val imgElement = element.selectFirst("img")
                    val titleRec = imgElement?.attr("alt") ?: element.selectFirst("h3, .text-white.font-bold")?.text() ?: return@mapNotNull null
                    val posterRec = imgElement?.attr("src")?.let { fixUrl(it) }
                    val yearRec = element.selectFirst(".text-white\\/70.text-xs")?.text()?.toIntOrNull()
                    val cleanTitleRec = titleRec.replace(Regex("\\(\\d{4}\\)"), "").trim()

                    val isAnimeRec = href.contains("/anime/")
                    val isSerieRec = href.contains("/serie/") || href.contains("/dorama/")

                    when {
                        isAnimeRec -> newAnimeSearchResponse(cleanTitleRec, fixUrl(href), TvType.Anime) {
                            this.posterUrl = posterRec
                            this.year = yearRec
                        }
                        isSerieRec -> newTvSeriesSearchResponse(cleanTitleRec, fixUrl(href), TvType.TvSeries) {
                            this.posterUrl = posterRec
                            this.year = yearRec
                        }
                        else -> newMovieSearchResponse(cleanTitleRec, fixUrl(href), TvType.Movie) {
                            this.posterUrl = posterRec
                            this.year = yearRec
                        }
                    }
                } catch (e: Exception) { null }
            }

        return if (isAnime || isSerie) {
            val type = if (isAnime) TvType.Anime else TvType.TvSeries
            val episodes = extractEpisodesFromSite(document, url, isAnime, isSerie)

            newTvSeriesLoadResponse(cleanTitle, url, type, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }

                if (scoreValue != null) {
                    this.score = Score(scoreValue.toInt(), 10)
                }

                if (cast != null && cast.isNotEmpty()) {
                    addActors(cast)
                }

                if (trailerKey != null) {
                    addTrailer("https://www.youtube.com/watch?v=$trailerKey")
                }
            }
        } else {
            val playerUrl = findPlayerUrl(document)

            newMovieLoadResponse(cleanTitle, url, TvType.Movie, playerUrl ?: url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = plot
                this.tags = tags
                this.duration = duration
                this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }

                if (scoreValue != null) {
                    this.score = Score(scoreValue.toInt(), 10)
                }

                if (cast != null && cast.isNotEmpty()) {
                    addActors(cast)
                }

                if (trailerKey != null) {
                    addTrailer("https://www.youtube.com/watch?v=$trailerKey")
                }
            }
        }
    }

    private suspend fun extractEpisodesFromSite(
        document: org.jsoup.nodes.Document,
        url: String,
        isAnime: Boolean,
        isSerie: Boolean
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()

        val scriptData = document.selectFirst("script:containsData(window.allEpisodes)")?.data()
        if (scriptData != null) {
            try {
                val jsonMatch = Regex("window\\.allEpisodes\\s*=\\s*(\\{[^;]+\\})").find(scriptData)
                val jsonString = jsonMatch?.groupValues?.get(1)
                
                if (jsonString != null) {
                    val seasonPattern = Regex("\"(\\d+)\":\\s*\\[([^\\]]+)\\]")
                    val seasonMatches = seasonPattern.findAll(jsonString)
                    
                    for (seasonMatch in seasonMatches) {
                        val seasonNum = seasonMatch.groupValues[1].toIntOrNull() ?: 1
                        val episodesJson = seasonMatch.groupValues[2]
                        
                        val episodePattern = Regex("\\{[^}]*\"epi_num\"\\s*:\\s*(\\d+)[^}]*\"title\"\\s*:\\s*\"([^\"]*)\"[^}]*\"thumb_url\"\\s*:\\s*\"([^\"]*)\"[^}]*\"duration\"\\s*:\\s*(\\d+)[^}]*\"air_date\"\\s*:\\s*\"([^\"]*)\"[^}]*\\}")
                        val episodeMatches = episodePattern.findAll(episodesJson)
                        
                        for (epMatch in episodeMatches) {
                            val epNum = epMatch.groupValues[1].toIntOrNull() ?: continue
                            val epTitle = epMatch.groupValues[2].ifEmpty { "Episódio $epNum" }
                            val thumbUrl = epMatch.groupValues[3].takeIf { it.isNotEmpty() }?.let { fixUrl(it) }
                            val durationMin = epMatch.groupValues[4].toIntOrNull()
                            val airDate = epMatch.groupValues[5].takeIf { it.isNotEmpty() }
                            
                            val episodeUrl = "$url/$seasonNum/$epNum"
                            
                            episodes.add(newEpisode(fixUrl(episodeUrl)) {
                                this.name = epTitle
                                this.season = seasonNum
                                this.episode = epNum
                                this.posterUrl = thumbUrl
                                this.description = buildString {
                                    if (durationMin != null && durationMin > 0) append("Duração: ${durationMin}min\n")
                                    if (airDate != null) append("Data: $airDate")
                                }.trim()
                            })
                        }
                    }
                    
                    if (episodes.isNotEmpty()) return episodes
                }
            } catch (e: Exception) { }
        }

        val episodeElements = document.select("#episodes-list article, .episode-card, .episode-item")
        
        episodeElements.forEachIndexed { index, element ->
            try {
                val link = element.selectFirst("a[href]") ?: return@forEachIndexed
                val dataUrl = link.attr("href")
                if (dataUrl.isBlank()) return@forEachIndexed

                val seasonText = element.selectFirst(".text-lead.shrink-0")?.text() ?: "T1:E${index + 1}"
                val seasonMatch = Regex("T(\\d+):E(\\d+)").find(seasonText)
                val seasonNumber = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val epNumber = seasonMatch?.groupValues?.get(2)?.toIntOrNull() ?: (index + 1)
                
                val epTitle = element.selectFirst("h2, .truncate")?.text() ?: "Episódio $epNumber"
                val thumb = element.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                val description = element.selectFirst(".line-clamp-2.text-xs")?.text()

                episodes.add(newEpisode(fixUrl(dataUrl)) {
                    this.name = epTitle
                    this.season = seasonNumber
                    this.episode = epNumber
                    this.posterUrl = thumb
                    this.description = description
                })
            } catch (e: Exception) { }
        }

        return episodes
    }

    private fun findPlayerUrl(document: org.jsoup.nodes.Document): String? {
        val iframe = document.selectFirst("iframe[src*='player'], iframe[src*='embed'], iframe[src*='fembed'], iframe[src*='filemoon']")
        if (iframe != null) {
            return iframe.attr("src")
        }

        val videoLink = document.selectFirst("a[href*='.m3u8'], a[href*='.mp4'], a[href*='watch']")
        return videoLink?.attr("href")
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val document = app.get(data).document
            
            val iframe = document.selectFirst("iframe[src*='player'], iframe[src*='embed'], iframe[src*='fembed'], iframe[src*='filemoon']")
            if (iframe != null) {
                val playerUrl = iframe.attr("src")
                val links = M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = playerUrl,
                    referer = playerUrl
                )
                
                if (links.isNotEmpty()) {
                    links.forEach { callback(it) }
                    true
                } else {
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = name,
                            url = playerUrl,
                            referer = playerUrl,
                            quality = Qualities.Unknown.value,
                            isM3u8 = playerUrl.contains(".m3u8")
                        )
                    )
                    true
                }
            } else {
                val videoUrl = document.selectFirst("video source, source[src]")?.attr("src")
                if (videoUrl != null) {
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = name,
                            url = videoUrl,
                            referer = data,
                            quality = Qualities.Unknown.value,
                            isM3u8 = videoUrl.contains(".m3u8")
                        )
                    )
                    true
                } else false
            }
        } catch (e: Exception) {
            false
        }
    }
}
