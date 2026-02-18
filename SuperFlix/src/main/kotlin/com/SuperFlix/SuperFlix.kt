package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import java.text.SimpleDateFormat
import android.util.Log

class SuperFlix : MainAPI() {
    override var mainUrl = "https://superflix30.lol"
    override var name = "SuperFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val usesWebView = true

    private val tmdbImageUrl = "https://image.tmdb.org/t/p"
    private val TMDB_API_KEY = BuildConfig.TMDB_API_KEY
    private val TMDB_ACCESS_TOKEN = BuildConfig.TMDB_ACCESS_TOKEN

    companion object {
        private const val SEARCH_PATH = "/?s="

        private val FIXED_CATEGORIES = listOf(
            "/categoria/lancamentos/" to "Lançamentos",
            "/assistir-filmes-online/" to "Últimos Filmes",
            "/assistir-series-online/" to "Últimas Séries"
        )

        // Not used anymore, as categories are in the menu, but kept for potential future use
        // private val ALL_RANDOM_CATEGORIES = ...

        private var cachedRandomTabs: List<Pair<String, String>>? = null
        private var cacheTime: Long = 0
        private const val CACHE_DURATION = 300000L

        fun getCombinedTabs(): List<Pair<String, String>> {
            // For now, just return fixed categories. Dynamic categories can be re-added later.
            return FIXED_CATEGORIES
        }
    }

    override val mainPage = mainPageOf(
        *getCombinedTabs().map { (path, name) ->
            "$mainUrl$path" to name
        }.toTypedArray()
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = request.data
        val url = if (page > 1) {
            if (baseUrl.contains("?")) {
                "$baseUrl&page=$page"
            } else {
                // Assuming WordPress pagination
                val baseWithoutSlash = if (baseUrl.endsWith("/")) baseUrl.dropLast(1) else baseUrl
                "$baseWithoutSlash/page/$page/"
            }
        } else {
            baseUrl
        }

        val document = app.get(url).document

        // New selector for the post list
        val home = document.select("ul.post-lst > li.post").mapNotNull { element ->
            element.toSearchResult()
        }

        // Check for next page link
        val hasNextPage = document.select("a.next.page-numbers, a:contains(Próxima)").isNotEmpty()

        return newHomePageResponse(request.name, home.distinctBy { it.url }, hasNextPage)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // This element is the 'li.post'
        val article = selectFirst("article") ?: return null
        val linkElement = article.selectFirst("a.lnk-blk") ?: article.selectFirst("a[href]") ?: return null
        val href = linkElement.attr("href") ?: return null
        if (href.isBlank()) return null

        val titleElement = article.selectFirst(".entry-title") ?: return null
        val title = titleElement.text().trim()

        val imgElement = article.selectFirst(".post-thumbnail img")
        val localPoster = imgElement?.attr("src")?.let { fixUrl(it) }

        val yearElement = article.selectFirst(".post-thumbnail .year")
        val year = yearElement?.text()?.toIntOrNull()

        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()

        // Determine type based on URL or context
        val isSerie = href.contains("/serie/") || article.hasClass("series") // Check if it's a series post
        val isAnime = href.contains("/anime/") // Assuming anime might be a separate category

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
        val searchUrl = "$mainUrl$SEARCH_PATH${java.net.URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document

        // Use the same selector as main page
        return document.select("ul.post-lst > li.post").mapNotNull { card ->
            card.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // --- Extract basic info from the page ---
        val titleElement = document.selectFirst("article.post.single h1.entry-title")
        val title = titleElement?.text() ?: return null

        val descriptionElement = document.selectFirst("article.post.single .description p")
        val plot = descriptionElement?.text()?.trim()

        val yearElement = document.selectFirst("article.post.single .entry-meta .year")
        val year = yearElement?.text()?.toIntOrNull()

        val durationElement = document.selectFirst("article.post.single .entry-meta .duration")
        val durationText = durationElement?.text()?.replace("min.", "")?.trim()?.toIntOrNull()

        val genres = document.select("article.post.single .entry-meta .genres a").map { it.text() }

        val posterElement = document.selectFirst("article.post.single .post-thumbnail img")
        val posterUrl = posterElement?.attr("src")?.let { fixUrl(it) }

        val isSerie = url.contains("/serie/") || document.selectFirst("section.episodes") != null
        val isAnime = url.contains("/anime/") // Adapt if needed

        // --- TMDB Integration ---
        val tmdbInfo = if (isSerie) {
            searchOnTMDB(title, year, true)
        } else {
            searchOnTMDB(title, year, false)
        }

        val siteRecommendations = extractRecommendationsFromSite(document)

        return if (tmdbInfo != null) {
            createLoadResponseWithTMDB(tmdbInfo, url, document, isAnime, isSerie, siteRecommendations)
        } else {
            createLoadResponseFromSite(document, url, title, year, isAnime, isSerie, plot, genres, posterUrl, durationText)
        }
    }

    // --- TMDB functions (unchanged) ---
    private suspend fun searchOnTMDB(query: String, year: Int?, isTv: Boolean): TMDBInfo? { /* ... */ }
    private suspend fun getTMDBAllSeasons(seriesId: Int): Map<Int, List<TMDBEpisode>> { /* ... */ }
    private suspend fun getTMDBDetails(id: Int, isTv: Boolean): TMDBDetailsResponse? { /* ... */ }
    private fun getHighQualityTrailer(videos: List<TMDBVideo>?): String? { /* ... */ }

    // --- NEW: Function to extract episodes considering AJAX loading ---
    private suspend fun extractEpisodesFromSite(
        document: org.jsoup.nodes.Document,
        url: String,
        isAnime: Boolean,
        isSerie: Boolean
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val episodeSection = document.selectFirst("section.episodes") ?: return emptyList()

        // Find season data from the dropdown
        val seasonItems = episodeSection.select(".aa-drp .sub-menu li a[data-season]")
        val seasonUrls = seasonItems.mapNotNull { seasonLink ->
            val seasonNumber = seasonLink.attr("data-season").toIntOrNull()
            val postId = seasonLink.attr("data-post") // The post ID might be needed for AJAX
            if (seasonNumber != null && postId != null) {
                seasonNumber to postId
            } else null
        }

        if (seasonUrls.isNotEmpty()) {
            // If seasons are found, load episodes for each season via AJAX
            for ((seasonNumber, postId) in seasonUrls) {
                val ajaxUrl = "https://superflix30.lol/wp-admin/admin-ajax.php"
                val ajaxBody = mapOf(
                    "action" to "torofilm_ajax_episodes",
                    "post" to postId,
                    "season" to seasonNumber.toString()
                )

                try {
                    val ajaxResponse = app.post(ajaxUrl, data = ajaxBody).document
                    val episodeElements = ajaxResponse.select("ul#episode_by_temp li")
                    episodeElements.forEachIndexed { index, element ->
                        val episodeLink = element.selectFirst("article a.lnk-blk")?.attr("href")
                        val episodeTitleElement = element.selectFirst(".entry-title")
                        val episodeNumElement = element.selectFirst(".num-epi")

                        val episodeNum = episodeNumElement?.text()?.split("x")?.lastOrNull()?.toIntOrNull() ?: (index + 1)
                        val episodeTitle = episodeTitleElement?.text()?.trim() ?: "Episódio $episodeNum"
                        val episodeUrl = episodeLink?.let { fixUrl(it) } ?: return@forEachIndexed

                        val episode = newEpisode(episodeUrl) {
                            this.name = episodeTitle
                            this.season = seasonNumber
                            this.episode = episodeNum
                        }
                        episodes.add(episode)
                    }
                } catch (e: Exception) {
                    Log.e("SuperFlix", "Error loading episodes for season $seasonNumber: ${e.message}")
                }
            }
        } else {
            // Fallback: Try to find episodes directly on the page (less likely)
            val episodeElements = episodeSection.select("ul#episode_by_temp li")
            episodeElements.forEachIndexed { index, element ->
                val episodeLink = element.selectFirst("article a.lnk-blk")?.attr("href")
                val episodeTitleElement = element.selectFirst(".entry-title")
                val episodeNumElement = element.selectFirst(".num-epi")

                val episodeNum = episodeNumElement?.text()?.split("x")?.lastOrNull()?.toIntOrNull() ?: (index + 1)
                val seasonNum = episodeNumElement?.text()?.split("x")?.firstOrNull()?.toIntOrNull() ?: 1
                val episodeTitle = episodeTitleElement?.text()?.trim() ?: "Episódio $episodeNum"
                val episodeUrl = episodeLink?.let { fixUrl(it) } ?: return@forEachIndexed

                val episode = newEpisode(episodeUrl) {
                    this.name = episodeTitle
                    this.season = seasonNum
                    this.episode = episodeNum
                }
                episodes.add(episode)
            }
        }

        return episodes.sortedWith(compareBy({ it.season }, { it.episode }))
    }

    private suspend fun extractEpisodesWithTMDBInfo(
        document: org.jsoup.nodes.Document,
        url: String,
        tmdbInfo: TMDBInfo?,
        isAnime: Boolean,
        isSerie: Boolean
    ): List<Episode> {
        val episodes = extractEpisodesFromSite(document, url, isAnime, isSerie)
        if (tmdbInfo == null) return episodes

        // Enhance episodes with TMDB data
        return episodes.map { episode ->
            val tmdbEpisode = findTMDBEpisode(tmdbInfo, episode.season ?: 1, episode.episode ?: 1)
            if (tmdbEpisode != null) {
                episode.copy(
                    name = tmdbEpisode.name ?: episode.name,
                    posterUrl = tmdbEpisode.still_path?.let { "$tmdbImageUrl/w300$it" },
                    description = buildDescriptionWithDuration(tmdbEpisode.overview, tmdbEpisode.runtime)
                )
            } else {
                episode
            }
        }
    }

    private fun extractRecommendationsFromSite(document: org.jsoup.nodes.Document): List<SearchResponse> {
        // Find the carousel with recommended series
        val carousel = document.selectFirst(".owl-carousel") ?: return emptyList()
        return carousel.select(".owl-item .post").mapNotNull { element ->
            val link = element.selectFirst("a.lnk-blk")?.attr("href") ?: return@mapNotNull null
            val title = element.selectFirst(".entry-title")?.text()?.trim() ?: return@mapNotNull null
            val img = element.selectFirst(".post-thumbnail img")?.attr("src")?.let { fixUrl(it) }
            val year = element.selectFirst(".year")?.text()?.toIntOrNull()

            // Determine type based on context, default to movie
            val type = if (link.contains("/serie/")) TvType.TvSeries else TvType.Movie
            val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()

            newSearchResponse(cleanTitle, link, type) {
                this.posterUrl = img
                this.year = year
            }
        }
    }

    private suspend fun createLoadResponseFromSite(
        document: org.jsoup.nodes.Document,
        url: String,
        title: String,
        year: Int?,
        isAnime: Boolean,
        isSerie: Boolean,
        plot: String?,
        tags: List<String>,
        posterUrl: String?,
        duration: Int?
    ): LoadResponse {
        val siteRecommendations = extractRecommendationsFromSite(document)

        return if (isSerie || isAnime) {
            val type = if (isAnime) TvType.Anime else TvType.TvSeries
            val episodes = extractEpisodesFromSite(document, url, isAnime, isSerie)

            newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
            }
        } else {
            // For movies, the player URL is on the episode page. We use the current URL as dataUrl.
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = tags
                this.duration = duration
                this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
            }
        }
    }

    private suspend fun createLoadResponseWithTMDB(
        tmdbInfo: TMDBInfo,
        url: String,
        document: org.jsoup.nodes.Document,
        isAnime: Boolean,
        isSerie: Boolean,
        siteRecommendations: List<SearchResponse>
    ): LoadResponse {
        return if (isSerie || isAnime) {
            val episodes = extractEpisodesWithTMDBInfo(
                document = document,
                url = url,
                tmdbInfo = tmdbInfo,
                isAnime = isAnime,
                isSerie = isSerie
            )

            val type = if (isAnime) TvType.Anime else TvType.TvSeries

            newTvSeriesLoadResponse(
                name = tmdbInfo.title ?: title,
                url = url,
                type = type,
                episodes = episodes
            ) {
                this.posterUrl = tmdbInfo.posterUrl
                this.backgroundPosterUrl = tmdbInfo.backdropUrl
                this.year = tmdbInfo.year
                this.plot = tmdbInfo.overview
                this.tags = tmdbInfo.genres

                tmdbInfo.actors?.let { actors ->
                    addActors(actors)
                }

                tmdbInfo.youtubeTrailer?.let { trailerUrl ->
                    addTrailer(trailerUrl)
                }

                this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
            }
        } else {
            newMovieLoadResponse(
                name = tmdbInfo.title ?: title,
                url = url,
                type = TvType.Movie,
                dataUrl = url // Pass the movie page URL, loadLinks will be called with episode URL later
            ) {
                this.posterUrl = tmdbInfo.posterUrl
                this.backgroundPosterUrl = tmdbInfo.backdropUrl
                this.year = tmdbInfo.year
                this.plot = tmdbInfo.overview
                this.tags = tmdbInfo.genres
                this.duration = tmdbInfo.duration

                tmdbInfo.actors?.let { actors ->
                    addActors(actors)
                }

                tmdbInfo.youtubeTrailer?.let { trailerUrl ->
                    addTrailer(trailerUrl)
                }

                this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
            }
        }
    }

    private fun buildDescriptionWithDuration(overview: String?, runtime: Int?): String? { /* ... */ }
    private fun findTMDBEpisode(tmdbInfo: TMDBInfo?, season: Int, episode: Int): TMDBEpisode? { /* ... */ }

    // --- Updated function to find player URL on the episode page ---
    private fun findPlayerUrl(document: org.jsoup.nodes.Document): String? {
        // Try to find the iframe or video source on the episode page
        val iframe = document.selectFirst("iframe[src*='fembed'], iframe[src*='filemoon'], iframe[src*='player'], iframe[src*='embed']")
        if (iframe != null) {
            return iframe.attr("src")
        }

        // Look for play buttons that might contain the video URL
        val playButton = document.selectFirst("button.bd-play[data-url], a.bd-play[data-url]")
        if (playButton != null) {
            return playButton.attr("data-url")
        }

        // Look for direct video links
        val videoLink = document.selectFirst("a[href*='.m3u8'], a[href*='.mp4']")
        return videoLink?.attr("href")
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 'data' here is the URL of the episode page
        val episodeDocument = app.get(data).document
        val playerUrl = findPlayerUrl(episodeDocument) ?: return false

        return SuperFlixExtractor.extractVideoLinks(playerUrl, name, callback)
    }

    // --- Data classes (unchanged) ---
    private data class TMDBInfo( /* ... */ )
    private data class TMDBSearchResponse( /* ... */ )
    private data class TMDBResult( /* ... */ )
    private data class TMDBTVDetailsResponse( /* ... */ )
    private data class TMDBSeasonInfo( /* ... */ )
    private data class TMDBSeasonResponse( /* ... */ )
    private data class TMDBEpisode( /* ... */ )
    private data class TMDBDetailsResponse( /* ... */ )
    private data class TMDBGenre( /* ... */ )
    private data class TMDBCredits( /* ... */ )
    private data class TMDBCast( /* ... */ )
    private data class TMDBVideos( /* ... */ )
    private data class TMDBVideo( /* ... */ )
}
