package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import java.text.SimpleDateFormat

class SuperFlix : MainAPI() {
    override var mainUrl = "https://superflix30.lol"
    override var name = "SuperFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
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

        private var cachedRandomTabs: List<Pair<String, String>>? = null
        private var cacheTime: Long = 0
        private const val CACHE_DURATION = 300000L

        fun getCombinedTabs(): List<Pair<String, String>> {
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
                val baseWithoutSlash = if (baseUrl.endsWith("/")) baseUrl.dropLast(1) else baseUrl
                "$baseWithoutSlash/page/$page/"
            }
        } else {
            baseUrl
        }
        
        println("SuperFlix Debug - Loading main page URL: $url")

        val document = app.get(url).document
        val home = document.select("ul.post-lst > li.post").mapNotNull { element ->
            element.toSearchResult()
        }
        
        println("SuperFlix Debug - Found ${home.size} items on main page")

        val hasNextPage = document.select("a.next.page-numbers, a:contains(Próxima)").isNotEmpty()
        
        return newHomePageResponse(request.name, home, hasNextPage)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        try {
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

            val isSerie = href.contains("/serie/")
            val isAnime = href.contains("/anime/")

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
        } catch (e: Exception) {
            println("SuperFlix Debug - Error in toSearchResult: ${e.message}")
            return null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl$SEARCH_PATH${java.net.URLEncoder.encode(query, "UTF-8")}"
        println("SuperFlix Debug - Search URL: $searchUrl")
        
        val document = app.get(searchUrl).document

        return document.select("ul.post-lst > li.post").mapNotNull { card ->
            card.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        println("SuperFlix Debug - Loading URL: $url")
        val document = app.get(url).document

        val titleElement = document.selectFirst("article.post.single h1.entry-title")
        val title = titleElement?.text() ?: return null
        println("SuperFlix Debug - Title: $title")

        val descriptionElement = document.selectFirst("article.post.single .description p")
        val plot = descriptionElement?.text()?.trim()

        val yearElement = document.selectFirst("article.post.single .entry-meta .year")
        val year = yearElement?.text()?.toIntOrNull()

        val durationElement = document.selectFirst("article.post.single .entry-meta .duration")
        val duration = durationElement?.text()?.replace("min.", "")?.trim()?.toIntOrNull()

        val genres = document.select("article.post.single .entry-meta .genres a").map { it.text() }

        val posterElement = document.selectFirst("article.post.single .post-thumbnail img")
        val posterUrl = posterElement?.attr("src")?.let { fixUrl(it) }

        val isSerie = url.contains("/serie/") || document.selectFirst("section.episodes") != null
        val isAnime = url.contains("/anime/")
        
        println("SuperFlix Debug - Is Serie: $isSerie, Is Anime: $isAnime")

        val tmdbInfo = if (isSerie || isAnime) {
            searchOnTMDB(title, year, true)
        } else {
            searchOnTMDB(title, year, false)
        }
        
        println("SuperFlix Debug - TMDB Info found: ${tmdbInfo != null}")

        val siteRecommendations = extractRecommendationsFromSite(document)

        return if (tmdbInfo != null) {
            createLoadResponseWithTMDB(tmdbInfo, url, document, isAnime, isSerie, siteRecommendations)
        } else {
            createLoadResponseFromSite(document, url, title, year, isAnime, isSerie, plot, genres, posterUrl, duration)
        }
    }

    private suspend fun searchOnTMDB(query: String, year: Int?, isTv: Boolean): TMDBInfo? {
        return try {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val yearParam = year?.let { "&year=$it" } ?: ""

            val searchUrl = if (isTv) {
                "https://api.themoviedb.org/3/search/tv?api_key=$TMDB_API_KEY&query=$encodedQuery&language=pt-BR$yearParam"
            } else {
                "https://api.themoviedb.org/3/search/movie?api_key=$TMDB_API_KEY&query=$encodedQuery&language=pt-BR$yearParam"
            }

            val headers = mapOf(
                "Authorization" to "Bearer $TMDB_ACCESS_TOKEN",
                "accept" to "application/json"
            )

            val response = app.get(searchUrl, headers = headers, timeout = 10_000)
            if (response.code != 200) return null

            val searchResult = response.parsedSafe<TMDBSearchResponse>() ?: return null
            val result = searchResult.results.firstOrNull() ?: return null

            val details = getTMDBDetails(result.id, isTv) ?: return null

            val allActors = details.credits?.cast?.take(15)?.mapNotNull { actor ->
                if (actor.name.isNotBlank()) {
                    Actor(
                        name = actor.name,
                        image = actor.profile_path?.let { "$tmdbImageUrl/w185$it" }
                    )
                } else null
            }

            val youtubeTrailer = getHighQualityTrailer(details.videos?.results)

            val seasonsEpisodes = if (isTv) {
                getTMDBAllSeasons(result.id)
            } else {
                emptyMap()
            }

            TMDBInfo(
                id = result.id,
                title = if (isTv) result.name else result.title,
                year = if (isTv) {
                    result.first_air_date?.substring(0, 4)?.toIntOrNull()
                } else {
                    result.release_date?.substring(0, 4)?.toIntOrNull()
                },
                posterUrl = result.poster_path?.let { "$tmdbImageUrl/w500$it" },
                backdropUrl = details.backdrop_path?.let { "$tmdbImageUrl/original$it" },
                overview = details.overview,
                genres = details.genres?.map { it.name },
                actors = allActors,
                youtubeTrailer = youtubeTrailer,
                duration = if (!isTv) details.runtime else null,
                seasonsEpisodes = seasonsEpisodes
            )
        } catch (e: Exception) {
            println("SuperFlix Debug - TMDB search error: ${e.message}")
            null
        }
    }

    private suspend fun getTMDBAllSeasons(seriesId: Int): Map<Int, List<TMDBEpisode>> {
        return try {
            val headers = mapOf(
                "Authorization" to "Bearer $TMDB_ACCESS_TOKEN",
                "accept" to "application/json"
            )

            val seriesDetailsUrl = "https://api.themoviedb.org/3/tv/$seriesId?api_key=$TMDB_API_KEY&language=pt-BR"
            val seriesResponse = app.get(seriesDetailsUrl, headers = headers, timeout = 10_000)

            if (seriesResponse.code != 200) {
                return emptyMap()
            }

            val seriesDetails = seriesResponse.parsedSafe<TMDBTVDetailsResponse>() ?: return emptyMap()

            val seasonsEpisodes = mutableMapOf<Int, List<TMDBEpisode>>()

            for (season in seriesDetails.seasons) {
                if (season.season_number > 0) {
                    val seasonNumber = season.season_number

                    val seasonUrl = "https://api.themoviedb.org/3/tv/$seriesId/season/$seasonNumber?api_key=$TMDB_API_KEY&language=pt-BR"
                    val seasonResponse = app.get(seasonUrl, headers = headers, timeout = 10_000)

                    if (seasonResponse.code == 200) {
                        val seasonData = seasonResponse.parsedSafe<TMDBSeasonResponse>()
                        seasonData?.episodes?.let { episodes ->
                            seasonsEpisodes[seasonNumber] = episodes
                        }
                    }
                }
            }

            seasonsEpisodes
        } catch (e: Exception) {
            println("SuperFlix Debug - TMDB seasons error: ${e.message}")
            emptyMap()
        }
    }

    private suspend fun getTMDBDetails(id: Int, isTv: Boolean): TMDBDetailsResponse? {
        return try {
            val headers = mapOf(
                "Authorization" to "Bearer $TMDB_ACCESS_TOKEN",
                "accept" to "application/json"
            )

            val url = if (isTv) {
                "https://api.themoviedb.org/3/tv/$id?api_key=$TMDB_API_KEY&language=pt-BR&append_to_response=credits,videos"
            } else {
                "https://api.themoviedb.org/3/movie/$id?api_key=$TMDB_API_KEY&language=pt-BR&append_to_response=credits,videos"
            }

            val response = app.get(url, headers = headers, timeout = 10_000)

            if (response.code != 200) return null
            response.parsedSafe<TMDBDetailsResponse>()
        } catch (e: Exception) {
            println("SuperFlix Debug - TMDB details error: ${e.message}")
            null
        }
    }

    private fun getHighQualityTrailer(videos: List<TMDBVideo>?): String? {
        if (videos.isNullOrEmpty()) return null

        return videos.mapNotNull { video ->
            when {
                video.site == "YouTube" && video.type == "Trailer" && video.official == true ->
                    Triple(video.key, 10, "YouTube Trailer Oficial")
                video.site == "YouTube" && video.type == "Trailer" ->
                    Triple(video.key, 9, "YouTube Trailer")
                video.site == "YouTube" && video.type == "Teaser" && video.official == true ->
                    Triple(video.key, 8, "YouTube Teaser Oficial")
                video.site == "YouTube" && video.type == "Teaser" ->
                    Triple(video.key, 7, "YouTube Teaser")
                else -> null
            }
        }
        ?.sortedByDescending { it.second }
        ?.firstOrNull()
        ?.let { (key, _, _) -> "https://www.youtube.com/watch?v=$key" }
    }

    private suspend fun extractEpisodesFromSite(
        document: org.jsoup.nodes.Document,
        url: String,
        isAnime: Boolean,
        isSerie: Boolean
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        try {
            val episodeSection = document.selectFirst("section.episodes") ?: return emptyList()
            println("SuperFlix Debug - Found episode section")

            val seasonItems = episodeSection.select(".aa-drp .sub-menu li a[data-season]")
            println("SuperFlix Debug - Found ${seasonItems.size} seasons")

            if (seasonItems.isNotEmpty()) {
                for (seasonLink in seasonItems) {
                    val seasonNumber = seasonLink.attr("data-season").toIntOrNull() ?: continue
                    val postId = seasonLink.attr("data-post") ?: continue
                    
                    println("SuperFlix Debug - Loading season $seasonNumber for post $postId")

                    val ajaxUrl = "https://superflix30.lol/wp-admin/admin-ajax.php"
                    val ajaxBody = mapOf(
                        "action" to "torofilm_ajax_episodes",
                        "post" to postId,
                        "season" to seasonNumber.toString()
                    )

                    try {
                        val ajaxResponse = app.post(ajaxUrl, data = ajaxBody).document
                        val episodeElements = ajaxResponse.select("ul#episode_by_temp li")
                        println("SuperFlix Debug - Found ${episodeElements.size} episodes for season $seasonNumber")
                        
                        episodeElements.forEachIndexed { index, element ->
                            try {
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
                            } catch (e: Exception) {
                                println("SuperFlix Debug - Error parsing episode: ${e.message}")
                            }
                        }
                    } catch (e: Exception) {
                        println("SuperFlix Debug - Error loading episodes for season $seasonNumber: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            println("SuperFlix Debug - Error in extractEpisodesFromSite: ${e.message}")
        }

        println("SuperFlix Debug - Total episodes extracted: ${episodes.size}")
        return episodes.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 1 }))
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

        return episodes.map { episode ->
            val seasonNum = episode.season ?: 1
            val episodeNum = episode.episode ?: 1
            val tmdbEpisode = findTMDBEpisode(tmdbInfo, seasonNum, episodeNum)
            
            if (tmdbEpisode != null) {
                val descriptionWithDuration = buildDescriptionWithDuration(
                    tmdbEpisode.overview,
                    tmdbEpisode.runtime
                )
                
                newEpisode(episode.url) {
                    this.name = tmdbEpisode.name ?: episode.name
                    this.season = seasonNum
                    this.episode = episodeNum
                    this.posterUrl = tmdbEpisode.still_path?.let { "$tmdbImageUrl/w300$it" }
                    this.description = descriptionWithDuration
                    
                    tmdbEpisode.air_date?.let { airDate ->
                        try {
                            val dateFormatter = SimpleDateFormat("yyyy-MM-dd")
                            val date = dateFormatter.parse(airDate)
                            this.date = date.time
                        } catch (e: Exception) {}
                    }
                }
            } else {
                episode
            }
        }
    }

    private fun buildDescriptionWithDuration(overview: String?, runtime: Int?): String? {
        return when {
            overview != null && runtime != null && runtime > 0 -> {
                "$overview\n\nDuração: $runtime min"
            }
            overview != null -> {
                overview
            }
            runtime != null && runtime > 0 -> {
                "Duração: $runtime min"
            }
            else -> null
        }
    }

    private fun findTMDBEpisode(tmdbInfo: TMDBInfo?, season: Int, episode: Int): TMDBEpisode? {
        if (tmdbInfo == null) return null

        val episodes = tmdbInfo.seasonsEpisodes[season]
        if (episodes == null) {
            return null
        }

        return episodes.find { it.episode_number == episode }
    }

    private fun extractRecommendationsFromSite(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val recommendations = mutableListOf<SearchResponse>()
        
        try {
            val carousel = document.selectFirst(".owl-carousel") ?: return emptyList()
            val items = carousel.select(".owl-item .post")
            
            println("SuperFlix Debug - Found ${items.size} recommendations")
            
            items.forEach { element ->
                try {
                    val link = element.selectFirst("a.lnk-blk")?.attr("href") ?: return@forEach
                    val title = element.selectFirst(".entry-title")?.text()?.trim() ?: return@forEach
                    val img = element.selectFirst(".post-thumbnail img")?.attr("src")?.let { fixUrl(it) }
                    val year = element.selectFirst(".year")?.text()?.toIntOrNull()
                    
                    val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
                    
                    val isSerie = link.contains("/serie/")
                    val isAnime = link.contains("/anime/")
                    
                    val searchResponse = when {
                        isAnime -> newAnimeSearchResponse(cleanTitle, fixUrl(link), TvType.Anime) {
                            this.posterUrl = img
                            this.year = year
                        }
                        isSerie -> newTvSeriesSearchResponse(cleanTitle, fixUrl(link), TvType.TvSeries) {
                            this.posterUrl = img
                            this.year = year
                        }
                        else -> newMovieSearchResponse(cleanTitle, fixUrl(link), TvType.Movie) {
                            this.posterUrl = img
                            this.year = year
                        }
                    }
                    recommendations.add(searchResponse)
                } catch (e: Exception) {
                    println("SuperFlix Debug - Error parsing recommendation: ${e.message}")
                }
            }
        } catch (e: Exception) {
            println("SuperFlix Debug - Error in extractRecommendationsFromSite: ${e.message}")
        }
        
        return recommendations
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
                this.tags = tags.takeIf { it.isNotEmpty() }
                this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = tags.takeIf { it.isNotEmpty() }
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
                name = tmdbInfo.title ?: "",
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
                name = tmdbInfo.title ?: "",
                url = url,
                type = TvType.Movie,
                dataUrl = url
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

    private fun findPlayerUrl(document: org.jsoup.nodes.Document): String? {
        val iframe = document.selectFirst("iframe[src*='fembed'], iframe[src*='filemoon'], iframe[src*='player'], iframe[src*='embed']")
        if (iframe != null) {
            return iframe.attr("src")
        }

        val playButton = document.selectFirst("button.bd-play[data-url], a.bd-play[data-url]")
        if (playButton != null) {
            return playButton.attr("data-url")
        }

        val videoLink = document.selectFirst("a[href*='.m3u8'], a[href*='.mp4']")
        return videoLink?.attr("href")
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("SuperFlix Debug - Loading links from: $data")
        
        val episodeDocument = app.get(data).document
        val playerUrl = findPlayerUrl(episodeDocument)
        
        println("SuperFlix Debug - Found player URL: $playerUrl")
        
        if (playerUrl == null) {
            println("SuperFlix Debug - No player URL found")
            return false
        }

        return SuperFlixExtractor.extractVideoLinks(playerUrl, name, callback)
    }

    private data class TMDBInfo(
        val id: Int,
        val title: String?,
        val year: Int?,
        val posterUrl: String?,
        val backdropUrl: String?,
        val overview: String?,
        val genres: List<String>?,
        val actors: List<Actor>?,
        val youtubeTrailer: String?,
        val duration: Int?,
        val seasonsEpisodes: Map<Int, List<TMDBEpisode>> = emptyMap()
    )

    private data class TMDBSearchResponse(
        @JsonProperty("results") val results: List<TMDBResult>
    )

    private data class TMDBResult(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("release_date") val release_date: String? = null,
        @JsonProperty("first_air_date") val first_air_date: String? = null,
        @JsonProperty("poster_path") val poster_path: String?
    )

    private data class TMDBTVDetailsResponse(
        @JsonProperty("seasons") val seasons: List<TMDBSeasonInfo>
    )

    private data class TMDBSeasonInfo(
        @JsonProperty("season_number") val season_number: Int,
        @JsonProperty("episode_count") val episode_count: Int
    )

    private data class TMDBSeasonResponse(
        @JsonProperty("episodes") val episodes: List<TMDBEpisode>,
        @JsonProperty("air_date") val air_date: String?
    )

    private data class TMDBEpisode(
        @JsonProperty("episode_number") val episode_number: Int,
        @JsonProperty("name") val name: String,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("still_path") val still_path: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("air_date") val air_date: String?
    )

    private data class TMDBDetailsResponse(
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("backdrop_path") val backdrop_path: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("genres") val genres: List<TMDBGenre>?,
        @JsonProperty("credits") val credits: TMDBCredits?,
        @JsonProperty("videos") val videos: TMDBVideos?
    )

    private data class TMDBGenre(
        @JsonProperty("name") val name: String
    )

    private data class TMDBCredits(
        @JsonProperty("cast") val cast: List<TMDBCast>
    )

    private data class TMDBCast(
        @JsonProperty("name") val name: String,
        @JsonProperty("character") val character: String?,
        @JsonProperty("profile_path") val profile_path: String?
    )

    private data class TMDBVideos(
        @JsonProperty("results") val results: List<TMDBVideo>
    )

    private data class TMDBVideo(
        @JsonProperty("key") val key: String,
        @JsonProperty("site") val site: String,
        @JsonProperty("type") val type: String,
        @JsonProperty("official") val official: Boolean? = false
    )
}
