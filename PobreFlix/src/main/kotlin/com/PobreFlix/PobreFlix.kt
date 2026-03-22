package com.PobreFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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
        val url = if (page > 1 && request.data != mainUrl) {
            if (request.data.contains("?")) "${request.data}&page=$page"
            else "${request.data}?page=$page"
        } else {
            request.data
        }

        val document = app.get(url).document

        val home = document.select("article.relative.group/item, .swiper-slide article.relative.group/item, .grid article.relative.group/item, a.card")
            .mapNotNull { element ->
                element.toSearchResult()
            }

        val hasNextPage = document.select("a:contains(Próxima), .pagination a:contains(Próxima), .page-numbers a:contains(Próxima)").isNotEmpty()

        return newHomePageResponse(request.name, home.distinctBy { it.url }, hasNextPage)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = selectFirst("h3, .font-bold, .text-sm.md\\:text-base.font-bold")
        val title = titleElement?.text() ?: selectFirst("img")?.attr("alt") ?: return null
        
        val link = selectFirst("a[href]") ?: return null
        val href = link.attr("href") ?: return null
        val cleanUrl = fixUrl(href)
        
        val poster = selectFirst("img")?.attr("src")?.let { fixUrl(it) }
        
        val yearElement = selectFirst(".text-xs span:first-child, .text-xs span:matches(\\d{4}), .text-white\\/70.text-xs")
        val year = yearElement?.text()?.trim()?.toIntOrNull()
        
        val badge = selectFirst(".border-slate-300, .border-white\\/10, .px-1\\.5.py-0\\.5")?.text()?.lowercase() ?: ""
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
        
        val isAnime = href.contains("/anime/") || badge.contains("anime")
        val isSerie = href.contains("/serie/") || badge.contains("série") || badge.contains("serie") || href.contains("/dorama/")
        
        return when {
            isAnime -> newAnimeSearchResponse(cleanTitle, cleanUrl, TvType.Anime) {
                this.posterUrl = poster
                this.year = year
            }
            isSerie -> newTvSeriesSearchResponse(cleanTitle, cleanUrl, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
            else -> newMovieSearchResponse(cleanTitle, cleanUrl, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
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
                val cleanUrl = fixUrl(href)
                
                val poster = card.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                val year = Regex("\\b(\\d{4})\\b").find(title)?.groupValues?.get(1)?.toIntOrNull()
                val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
                
                val isAnime = href.contains("/anime/") || title.contains("(Anime)", ignoreCase = true)
                val isSerie = href.contains("/serie/") || href.contains("/tv/") || href.contains("/dorama/")
                
                when {
                    isAnime -> newAnimeSearchResponse(cleanTitle, cleanUrl, TvType.Anime) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    isSerie -> newTvSeriesSearchResponse(cleanTitle, cleanUrl, TvType.TvSeries) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    else -> newMovieSearchResponse(cleanTitle, cleanUrl, TvType.Movie) {
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
        
        val title = document.selectFirst("h1, .text-3xl.text-lead.font-bold, .text-3xl.md\\:text-4xl.font-bold")?.text() ?: return null
        
        val year = document.selectFirst(".inline-flex.items-center.rounded-full.px-3.py-1 span:contains(\\d{4}), .text-xs span:contains(\\d{4})")?.text()?.toIntOrNull()
        
        val plot = document.selectFirst(".text-slate-700.dark\\:text-slate-200.md\\:text-lg, .text-slate-900\\/90.dark\\:text-slate-100\\/90")?.text()
        
        val tags = document.select(".flex.flex-wrap.gap-2 a, .px-3.py-1.rounded-full.text-xs.bg-slate-200")
            .map { it.text() }
            .takeIf { it.isNotEmpty() }
        
        // Converter % para Float (ex: 90% -> 9.0) e depois para Score
        val ratingPercent = document.selectFirst("text[x='18'][y='21']")?.text()?.replace("%", "")?.toFloatOrNull()
        val scoreValue = ratingPercent?.let { it / 10 }
        val score = scoreValue?.let { Score(it.toInt(), 10) }
        
        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }
        
        val background = document.selectFirst(".absolute.left-1\\/2 img, .blur-\\[4px\\] img")?.attr("src")?.let { fixUrl(it) }
        
        val durationText = document.selectFirst(".bg-slate-200.dark\\:bg-slate-700.rounded-lg.p-3:contains(min) .font-medium")?.text()
        val duration = durationText?.let { 
            Regex("(\\d+)\\s*min").find(it)?.groupValues?.get(1)?.toIntOrNull()
        }
        
        val isSerie = url.contains("/serie/") || url.contains("/dorama/") || 
                      document.select("#episodes-list, .season-dropdown, .episode-card").isNotEmpty()
        
        // Elenco como lista de Actor (usando ActorData)
        val cast = document.select("#cast-section .swiper-slide .text-sm.font-bold, .swiper-slide .text-sm.font-bold")
            .map { it.text() }
            .takeIf { it.isNotEmpty() }
            ?.map { ActorData(it) }
        
        // Trailer - não usar trailerUrl diretamente, usar no LoadResponse
        val trailerKey = document.selectFirst("script:containsData(window.__trailerKeys)")?.data()?.let { script ->
            Regex("window\\.__trailerKeys\\s*=\\s*\\[\"([^\"]+)\"\\]").find(script)?.groupValues?.get(1)
        }
        
        // Recomendações
        val recommendations = document.select("#relatedSection .swiper-slide a, .related-swiper .swiper-slide a")
            .mapNotNull { element ->
                try {
                    val href = element.attr("href") ?: return@mapNotNull null
                    val titleEl = element.selectFirst("h3, .text-white.font-bold")
                    val titleRec = titleEl?.text() ?: return@mapNotNull null
                    val posterRec = element.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                    val yearRec = element.selectFirst(".text-white\\/70.text-xs")?.text()?.toIntOrNull()
                    
                    val isAnimeRec = href.contains("/anime/")
                    val isSerieRec = href.contains("/serie/") || href.contains("/dorama/")
                    
                    when {
                        isAnimeRec -> newAnimeSearchResponse(titleRec, fixUrl(href), TvType.Anime) {
                            this.posterUrl = posterRec
                            this.year = yearRec
                        }
                        isSerieRec -> newTvSeriesSearchResponse(titleRec, fixUrl(href), TvType.TvSeries) {
                            this.posterUrl = posterRec
                            this.year = yearRec
                        }
                        else -> newMovieSearchResponse(titleRec, fixUrl(href), TvType.Movie) {
                            this.posterUrl = posterRec
                            this.year = yearRec
                        }
                    }
                } catch (e: Exception) { null }
            }
        
        return if (isSerie) {
            val episodes = extractEpisodes(document, url)
            
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = score
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
                
                if (cast != null) {
                    addActors(cast)
                }
                
                if (trailerKey != null) {
                    addTrailer("https://www.youtube.com/watch?v=$trailerKey")
                }
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = score
                this.duration = duration
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
                
                if (cast != null) {
                    addActors(cast)
                }
                
                if (trailerKey != null) {
                    addTrailer("https://www.youtube.com/watch?v=$trailerKey")
                }
            }
        }
    }

    private suspend fun extractEpisodes(document: org.jsoup.nodes.Document, url: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        val scriptData = document.selectFirst("script:containsData(window.allEpisodes)")?.data()
        if (scriptData != null) {
            try {
                val jsonMatch = Regex("window\\.allEpisodes\\s*=\\s*(\\{[^;]+\\})").find(scriptData)
                val jsonString = jsonMatch?.groupValues?.get(1) ?: return extractEpisodesFromHtml(document)
                
                val seasonPattern = Regex("\"(\\d+)\":\\s*\\[([^\\]]+)\\]")
                val seasonMatches = seasonPattern.findAll(jsonString)
                
                for (seasonMatch in seasonMatches) {
                    val seasonNum = seasonMatch.groupValues[1].toIntOrNull() ?: 1
                    val episodesJson = seasonMatch.groupValues[2]
                    
                    val episodePattern = Regex("\\{[^}]*\"epi_num\"\\s*:\\s*(\\d+)[^}]*\"title\"\\s*:\\s*\"([^\"]*)\"[^}]*\"thumb_url\"\\s*:\\s*\"([^\"]*)\"[^}]*\"duration\"\\s*:\\s*(\\d+)[^}]*\"air_date\"\\s*:\\s*\"([^\"]*)\"[^}]*\"has_dub\"\\s*:\\s*(true|false)[^}]*\"has_leg\"\\s*:\\s*(true|false)[^}]*\\}")
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
            } catch (e: Exception) { }
        }
        
        return extractEpisodesFromHtml(document)
    }
    
    private fun extractEpisodesFromHtml(document: org.jsoup.nodes.Document): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        val episodeElements = document.select("#episodes-list article, .episode-card, .episode-item")
        
        episodeElements.forEach { element ->
            try {
                val link = element.selectFirst("a[href]") ?: return@forEach
                val episodeUrl = link.attr("href")
                if (episodeUrl.isBlank()) return@forEach
                
                val seasonText = element.selectFirst(".text-lead.shrink-0")?.text() ?: "T1:E1"
                val seasonMatch = Regex("T(\\d+):E(\\d+)").find(seasonText)
                val season = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val episodeNum = seasonMatch?.groupValues?.get(2)?.toIntOrNull() ?: 1
                
                val title = element.selectFirst("h2, .truncate")?.text() ?: "Episódio $episodeNum"
                val thumb = element.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                val description = element.selectFirst(".line-clamp-2.text-xs")?.text()
                
                episodes.add(newEpisode(fixUrl(episodeUrl)) {
                    this.name = title
                    this.season = season
                    this.episode = episodeNum
                    this.posterUrl = thumb
                    this.description = description
                })
            } catch (e: Exception) { }
        }
        
        return episodes
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
                loadExtractor(playerUrl, subtitleCallback, callback)
            } else {
                val videoUrl = document.selectFirst("video source, source[src]")?.attr("src")
                if (videoUrl != null) {
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = name,
                            url = videoUrl,
                            referer = mainUrl,
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
