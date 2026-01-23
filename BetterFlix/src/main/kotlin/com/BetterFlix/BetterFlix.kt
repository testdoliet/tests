package com.BetterFlix

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class BetterFlix : MainAPI() {
    override var mainUrl = "https://betterflix.vercel.app"
    override var name = "BetterFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val usesWebView = false

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "https://betterflix.vercel.app/",
        "Origin" to "https://betterflix.vercel.app"
    )

    private val cookies = mapOf(
        "dom3ic8zudi28v8lr6fgphwffqoz0j6c" to "33de42d8-3e93-4249-b175-d6bf5346ae91%3A2%3A1",
        "pp_main_80d9775bdcedfb8fd29914d950374a08" to "1"
    )

    private val superflixDomains = listOf(
        "https://superflixapi.bond",
        "https://superflixapi.asia",
        "https://superflixapi.top"
    )

    private val genreMap = mapOf(
        "28" to "Ação e Aventura",
        "35" to "Comédia",
        "27" to "Terror e Suspense",
        "99" to "Documentário",
        "10751" to "Para a Família",
        "80" to "Crime",
        "10402" to "Musical",
        "10749" to "Romance"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/trending" to "Trending",
        "$mainUrl/genre/28" to "Ação e Aventura",
        "$mainUrl/genre/35" to "Comédia",
        "$mainUrl/genre/27" to "Terror e Suspense",
        "$mainUrl/genre/99" to "Documentário",
        "$mainUrl/genre/10751" to "Para a Família",
        "$mainUrl/genre/80" to "Crime",
        "$mainUrl/genre/10402" to "Musical",
        "$mainUrl/genre/10749" to "Romance",
        "$mainUrl/animes" to "Animes"
    )

    data class TrendingResponse(@JsonProperty("results") val results: List<ContentItem>)
    data class GenreResponse(@JsonProperty("results") val results: List<ContentItem>)
    data class AnimeResponse(@JsonProperty("results") val results: List<ContentItem>)
    data class SearchResponseData(@JsonProperty("results") val results: List<ContentItem>)

    data class ContentItem(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("original_title") val originalTitle: String?,
        @JsonProperty("original_name") val originalName: String?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("poster_path") val posterPath: String?,
        @JsonProperty("backdrop_path") val backdropPath: String?,
        @JsonProperty("media_type") val mediaType: String?,
        @JsonProperty("release_date") val releaseDate: String?,
        @JsonProperty("first_air_date") val firstAirDate: String?,
        @JsonProperty("vote_average") val voteAverage: Double?,
        @JsonProperty("genre_ids") val genreIds: List<Int>?,
        @JsonProperty("original_language") val originalLanguage: String?,
        @JsonProperty("popularity") val popularity: Double?,
        @JsonProperty("video") val video: Boolean?,
        @JsonProperty("adult") val adult: Boolean?
    )

    data class EmbeddedData(
        val id: String? = null,
        val name: String? = null,
        val date: String? = null,
        val bio: String? = null,
        val inProduction: Boolean? = null,
        val vote: Double? = null,
        val genres: String? = null,
        val poster: String? = null,
        val backdrop: String? = null
    )

    data class EpisodeData(
        val ID: Int,
        val title: String,
        val sinopse: String,
        val item: Int,
        val thumb_url: String?,
        val air_date: String?,
        val duration: Int,
        val epi_num: Int,
        val season: Int
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<SearchResponse>()
        
        when {
            request.name == "Trending" -> {
                val trending = getTrending()
                items.addAll(trending)
            }
            request.name == "Animes" -> {
                val animes = getAnimes()
                items.addAll(animes)
            }
            request.name in genreMap.values -> {
                val genreId = genreMap.entries.find { it.value == request.name }?.key
                if (genreId != null) {
                    val genreItems = getGenreContent(genreId)
                    items.addAll(genreItems)
                }
            }
        }
        
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    private suspend fun getTrending(): List<SearchResponse> {
        val url = "$mainUrl/api/trending?type=all"
        val response = app.get(url, headers = headers, cookies = cookies, timeout = 30)
        
        val data = response.parsedSafe<TrendingResponse>() ?: return emptyList()
        
        return data.results.mapNotNull { item ->
            try {
                val title = item.title ?: item.name ?: item.originalTitle ?: item.originalName ?: return@mapNotNull null
                val year = getYearFromDate(item.releaseDate ?: item.firstAirDate)
                val poster = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                val id = item.id
                
                val type = when (item.mediaType) {
                    "movie" -> TvType.Movie
                    "tv" -> TvType.TvSeries
                    "anime" -> TvType.Anime
                    else -> when {
                        title.contains("(Anime)", ignoreCase = true) -> TvType.Anime
                        item.releaseDate != null -> TvType.Movie
                        item.firstAirDate != null -> TvType.TvSeries
                        else -> TvType.Movie
                    }
                }
                
                val slug = generateSlug(title)
                val url = when (type) {
                    TvType.Movie -> "$mainUrl/$slug?id=$id&type=movie"
                    TvType.TvSeries -> "$mainUrl/$slug?id=$id&type=tv"
                    TvType.Anime -> "$mainUrl/$slug?id=$id&type=anime"
                    else -> "$mainUrl/$slug?id=$id&type=movie"
                }
                
                when (type) {
                    TvType.Movie -> newMovieSearchResponse(title, url, TvType.Movie) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    TvType.TvSeries -> newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    TvType.Anime -> newAnimeSearchResponse(title, url, TvType.Anime) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    else -> newMovieSearchResponse(title, url, TvType.Movie) {
                        this.posterUrl = poster
                        this.year = year
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private suspend fun getAnimes(): List<SearchResponse> {
        val url = "$mainUrl/api/list-animes"
        val response = app.get(url, headers = headers, cookies = cookies, timeout = 30)
        
        val data = response.parsedSafe<AnimeResponse>() ?: return emptyList()
        
        return data.results.mapNotNull { item ->
            try {
                val title = item.title ?: item.name ?: item.originalTitle ?: item.originalName ?: return@mapNotNull null
                val year = getYearFromDate(item.releaseDate ?: item.firstAirDate)
                val poster = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                val id = item.id
                
                val slug = generateSlug(title)
                val url = "$mainUrl/$slug?id=$id&type=anime"
                
                newAnimeSearchResponse(title, url, TvType.Anime) {
                    this.posterUrl = poster
                    this.year = year
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private suspend fun getGenreContent(genreId: String): List<SearchResponse> {
        val url = "$mainUrl/api/preview-genre?id=$genreId"
        val response = app.get(url, headers = headers, cookies = cookies, timeout = 30)
        
        val data = response.parsedSafe<GenreResponse>() ?: return emptyList()
        
        return data.results.mapNotNull { item ->
            try {
                val title = item.title ?: item.name ?: item.originalTitle ?: item.originalName ?: return@mapNotNull null
                val year = getYearFromDate(item.releaseDate ?: item.firstAirDate)
                val poster = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                val id = item.id
                
                val type = when (item.mediaType) {
                    "movie" -> TvType.Movie
                    "tv" -> TvType.TvSeries
                    else -> when {
                        title.contains("(Anime)", ignoreCase = true) -> TvType.Anime
                        item.releaseDate != null -> TvType.Movie
                        item.firstAirDate != null -> TvType.TvSeries
                        else -> TvType.Movie
                    }
                }
                
                val slug = generateSlug(title)
                val url = when (type) {
                    TvType.Movie -> "$mainUrl/$slug?id=$id&type=movie"
                    TvType.TvSeries -> "$mainUrl/$slug?id=$id&type=tv"
                    TvType.Anime -> "$mainUrl/$slug?id=$id&type=anime"
                    else -> "$mainUrl/$slug?id=$id&type=movie"
                }
                
                when (type) {
                    TvType.Movie -> newMovieSearchResponse(title, url, TvType.Movie) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    TvType.TvSeries -> newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    TvType.Anime -> newAnimeSearchResponse(title, url, TvType.Anime) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    else -> newMovieSearchResponse(title, url, TvType.Movie) {
                        this.posterUrl = poster
                        this.year = year
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun getYearFromDate(dateString: String?): Int? {
        return dateString?.substring(0, 4)?.toIntOrNull()
    }

    private fun generateSlug(title: String): String {
        return title
            .lowercase()
            .replace(Regex("[^a-z0-9\\s-]"), "")
            .replace(Regex("\\s+"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
    }

    override suspend fun search(query: String): List<SearchResponse> {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "$mainUrl/api/search?query=$encodedQuery"
            
            val response = app.get(url, headers = headers, cookies = cookies, timeout = 30)
            val data = response.parsedSafe<SearchResponseData>() ?: return emptyList()
            
            return data.results.mapNotNull { item ->
                try {
                    val title = item.title ?: item.name ?: item.originalTitle ?: item.originalName ?: return@mapNotNull null
                    val year = getYearFromDate(item.releaseDate ?: item.firstAirDate)
                    val poster = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                    val id = item.id
                    
                    val type = when (item.mediaType) {
                        "movie" -> TvType.Movie
                        "tv" -> TvType.TvSeries
                        "anime" -> TvType.Anime
                        else -> when {
                            title.contains("(Anime)", ignoreCase = true) -> TvType.Anime
                            item.releaseDate != null -> TvType.Movie
                            item.firstAirDate != null -> TvType.TvSeries
                            else -> TvType.Movie
                        }
                    }
                    
                    val slug = generateSlug(title)
                    val url = when (type) {
                        TvType.Movie -> "$mainUrl/$slug?id=$id&type=movie"
                        TvType.TvSeries -> "$mainUrl/$slug?id=$id&type=tv"
                        TvType.Anime -> "$mainUrl/$slug?id=$id&type=anime"
                        else -> "$mainUrl/$slug?id=$id&type=movie"
                    }
                    
                    when (type) {
                        TvType.Movie -> newMovieSearchResponse(title, url, TvType.Movie) {
                            this.posterUrl = poster
                            this.year = year
                        }
                        TvType.TvSeries -> newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                            this.posterUrl = poster
                            this.year = year
                        }
                        TvType.Anime -> newAnimeSearchResponse(title, url, TvType.Anime) {
                            this.posterUrl = poster
                            this.year = year
                        }
                        else -> newMovieSearchResponse(title, url, TvType.Movie) {
                            this.posterUrl = poster
                            this.year = year
                        }
                    }
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            return emptyList()
        }
    }

    // ========== LOAD() ==========
    override suspend fun load(url: String): LoadResponse? {
        return try {
            val response = app.get(url, headers = headers, cookies = cookies, timeout = 30)
            if (response.code >= 400) return null
            
            val document = response.document
            val html = response.text
            
            val embeddedData = extractEmbeddedData(html)
            if (embeddedData == null) {
                return null
            }
            
            val tmdbId = embeddedData.id ?: extractTmdbIdFromUrl(url)
            val isSeries = url.contains("type=tv")
            val isAnime = url.contains("type=anime")
            val isMovie = !isSeries && !isAnime
            
            if (isSeries || isAnime) {
                val type = if (isAnime) TvType.Anime else TvType.TvSeries
                val episodes = extractEpisodesFromSuperflix(tmdbId, url)
                
                newTvSeriesLoadResponse(embeddedData.name ?: "Sem título", url, type, episodes) {
                    this.posterUrl = embeddedData.poster?.let { fixUrl(it) }
                    this.backgroundPosterUrl = embeddedData.backdrop?.let { fixUrl(it) }
                    this.year = embeddedData.date?.substring(0, 4)?.toIntOrNull()
                    this.plot = embeddedData.bio
                    this.tags = embeddedData.genres?.split(",")?.map { it.trim() } ?: emptyList()
                }
            } else {
                newMovieLoadResponse(embeddedData.name ?: "Sem título", url, TvType.Movie, url) {
                    this.posterUrl = embeddedData.poster?.let { fixUrl(it) }
                    this.backgroundPosterUrl = embeddedData.backdrop?.let { fixUrl(it) }
                    this.year = embeddedData.date?.substring(0, 4)?.toIntOrNull()
                    this.plot = embeddedData.bio
                    this.tags = embeddedData.genres?.split(",")?.map { it.trim() } ?: emptyList()
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    // ========== EXTRATOR DE EPISÓDIOS QUE FUNCIONA ==========
    private suspend fun extractEpisodesFromSuperflix(tmdbId: String?, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        if (tmdbId == null) return episodes
        
        try {
            val superflixUrl = "https://superflixapi.bond/serie/$tmdbId/1/1"
            
            val superflixHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Accept-Language" to "pt-BR",
                "Referer" to "https://betterflix.vercel.app/",
                "Sec-Fetch-Dest" to "document",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "cross-site"
            )
            
            val response = app.get(superflixUrl, headers = superflixHeaders, timeout = 30)
            if (response.code >= 400) return episodes
            
            val html = response.text
            val allEpisodesData = extractAllEpisodesData(html)
            
            if (allEpisodesData == null) return episodes
            
            allEpisodesData.forEach { (seasonNumber, seasonEpisodes) ->
                val seasonNum = seasonNumber.toIntOrNull() ?: 1
                
                seasonEpisodes.forEach { episodeData ->
                    try {
                        val epNumber = episodeData.epi_num
                        val title = episodeData.title
                        val description = episodeData.sinopse.takeIf { it.isNotBlank() }
                        val thumbUrl = episodeData.thumb_url?.let { 
                            if (it.startsWith("/")) "https://image.tmdb.org/t/p/w300$it" else it 
                        }
                        
                        val episodeUrl = "$baseUrl&season=$seasonNum&episode=$epNumber"
                        
                        episodes.add(
                            newEpisode(episodeUrl) {
                                this.name = title
                                this.season = seasonNum
                                this.episode = epNumber
                                this.description = description
                                this.posterUrl = thumbUrl?.let { fixUrl(it) }
                            }
                        )
                    } catch (e: Exception) {
                        // Ignorar erro
                    }
                }
            }
        } catch (e: Exception) {
            // Ignorar erro
        }
        
        return episodes
    }

    private fun extractAllEpisodesData(html: String): Map<String, List<EpisodeData>>? {
        try {
            val pattern = Regex("""var\s+ALL_EPISODES\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
            val match = pattern.find(html)
            
            if (match != null) {
                val jsonString = match.groupValues[1]
                
                try {
                    val jsonObject = JSONObject(jsonString)
                    val result = mutableMapOf<String, List<EpisodeData>>()
                    
                    jsonObject.keys().forEach { seasonKey ->
                        val episodesArray = jsonObject.getJSONArray(seasonKey)
                        val episodesList = mutableListOf<EpisodeData>()
                        
                        for (i in 0 until episodesArray.length()) {
                            val episodeObj = episodesArray.getJSONObject(i)
                            episodesList.add(
                                EpisodeData(
                                    ID = episodeObj.optInt("ID"),
                                    title = episodeObj.optString("title"),
                                    sinopse = episodeObj.optString("sinopse"),
                                    item = episodeObj.optInt("item"),
                                    thumb_url = episodeObj.optString("thumb_url").takeIf { it != "null" && it.isNotBlank() },
                                    air_date = episodeObj.optString("air_date").takeIf { it != "null" && it.isNotBlank() },
                                    duration = episodeObj.optInt("duration"),
                                    epi_num = episodeObj.optInt("epi_num"),
                                    season = episodeObj.optInt("season")
                                )
                            )
                        }
                        
                        result[seasonKey] = episodesList
                    }
                    
                    return result
                } catch (e: Exception) {
                    return null
                }
            }
        } catch (e: Exception) {
            return null
        }
        
        return null
    }

    private fun extractEmbeddedData(html: String): EmbeddedData? {
        try {
            val pattern = Regex("""const dadosMulti\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
            val match = pattern.find(html)
            
            if (match != null) {
                val jsonString = match.groupValues[1]
                return AppUtils.tryParseJson<EmbeddedData>(jsonString)
            }
            
            return null
        } catch (e: Exception) {
            return null
        }
    }

    private fun extractTmdbIdFromUrl(url: String): String? {
        val idMatch = Regex("[?&]id=(\\d+)").find(url)
        return idMatch?.groupValues?.get(1)
    }

    // ========== LOAD LINKS (MÉTODO ORIGINAL QUE FUNCIONA) ==========
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val tmdbId = extractTmdbId(data) ?: return false
            
            for (superflixDomain in superflixDomains) {
                try {
                    val success = extractVideoFromSuperflix(superflixDomain, tmdbId, callback)
                    if (success) {
                        try {
                            val subtitleUrl = "https://complicado.sbs/cdn/down/disk11/${tmdbId.substring(0, 32)}/Subtitle/subtitle_por.vtt"
                            subtitleCallback(SubtitleFile("Português", subtitleUrl))
                        } catch (e: Exception) {
                            // Ignorar erro de legenda
                        }
                        
                        return true
                    }
                } catch (e: Exception) {
                    continue
                }
            }
            
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun extractTmdbId(url: String): String? {
        val idMatch = Regex("[?&]id=(\\d+)").find(url)
        return idMatch?.groupValues?.get(1)
    }

    private suspend fun extractVideoFromSuperflix(
        domain: String,
        tmdbId: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val possibleVideoIds = listOf("303309", "351944")
            
            for (videoId in possibleVideoIds) {
                val apiUrl = "$domain/api"
                
                val apiHeaders = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                    "Accept" to "application/json, text/plain, */*",
                    "Accept-Language" to "pt-BR",
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "Origin" to domain,
                    "Referer" to "$domain/filme/$tmdbId",
                    "X-Requested-With" to "XMLHttpRequest"
                )
                
                val apiData = mapOf(
                    "action" to "getPlayer",
                    "video_id" to videoId
                )
                
                try {
                    val apiResponse = app.post(apiUrl, data = apiData, headers = apiHeaders, timeout = 30)
                    
                    if (apiResponse.code >= 400) {
                        continue
                    }
                    
                    val apiJson = JSONObject(apiResponse.text)
                    val errors = apiJson.optString("errors", "1")
                    val message = apiJson.optString("message", "")
                    
                    if (errors == "1" || message != "success") {
                        continue
                    }
                    
                    val videoUrl = apiJson.optJSONObject("data")?.optString("video_url")
                    if (videoUrl.isNullOrEmpty()) {
                        continue
                    }
                    
                    val hash = extractHashFromVideoUrl(videoUrl)
                    if (hash == null) {
                        continue
                    }
                    
                    val playerResult = requestPlayerHash(hash, callback)
                    if (playerResult) {
                        return true
                    }
                    
                } catch (e: Exception) {
                    continue
                }
            }
            
            return false
            
        } catch (e: Exception) {
            return false
        }
    }

    private fun extractHashFromVideoUrl(videoUrl: String): String? {
        return when {
            videoUrl.contains("/video/") -> {
                videoUrl.substringAfter("/video/").substringBefore("?")
            }
            videoUrl.contains("/m/") -> {
                videoUrl.substringAfter("/m/").substringBefore("?")
            }
            else -> null
        }
    }

    private suspend fun requestPlayerHash(
        hash: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val playerDomain = "https://llanfairpwllgwyngy.com"
            val playerUrl = "$playerDomain/player/index.php?data=$hash&do=getVideo"
            
            val playerHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                "Accept" to "*/*",
                "Accept-Language" to "pt-BR",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "Origin" to playerDomain,
                "Referer" to "$playerDomain/",
                "X-Requested-With" to "XMLHttpRequest"
            )
            
            val playerData = mapOf(
                "hash" to hash,
                "r" to ""
            )
            
            val playerResponse = app.post(playerUrl, data = playerData, headers = playerHeaders, timeout = 30)
            
            if (playerResponse.code >= 400) {
                return false
            }
            
            val playerJson = JSONObject(playerResponse.text)
            val m3u8Url = playerJson.optString("securedLink")
                .takeIf { it.isNotBlank() }
                ?: playerJson.optString("videoSource")
                    .takeIf { it.isNotBlank() }
            
            if (m3u8Url.isNullOrBlank()) {
                return false
            }
            
            val quality = when {
                m3u8Url.contains("1080") -> Qualities.P1080.value
                m3u8Url.contains("720") -> Qualities.P720.value
                m3u8Url.contains("480") -> Qualities.P480.value
                m3u8Url.contains("360") -> Qualities.P360.value
                else -> Qualities.P720.value
            }
            
            newExtractorLink(name, "SuperFlix ($quality)", m3u8Url, ExtractorLinkType.M3U8) {
                referer = "$playerDomain/"
                this.quality = quality
            }.also { callback(it) }
            
            return true
            
        } catch (e: Exception) {
            return false
        }
    }
}
