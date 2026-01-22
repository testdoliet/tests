package com.BetterFlix

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

class BetterFlix : MainAPI() {
    override var mainUrl = "https://betterflix.vercel.app"
    override var name = "BetterFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Live)
    override val usesWebView = false

    // Headers para evitar rate limiting
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "https://betterflix.vercel.app/",
        "Origin" to "https://betterflix.vercel.app",
        "Sec-Ch-Ua" to "\"Chromium\";v=\"127\", \"Not)A;Brand\";v=\"99\", \"Microsoft Edge Simulate\";v=\"127\", \"Lemur\";v=\"127\"",
        "Sec-Ch-Ua-Mobile" to "?1",
        "Sec-Ch-Ua-Platform" to "\"Android\"",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "same-origin"
    )

    // Cookies persistentes
    private val cookies = mapOf(
        "dom3ic8zudi28v8lr6fgphwffqoz0j6c" to "33de42d8-3e93-4249-b175-d6bf5346ae91%3A2%3A1",
        "pp_main_80d9775bdcedfb8fd29914d950374a08" to "1"
    )

    // Mapeamento de gêneros
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

    // Modelos de dados para a API
    data class TrendingResponse(
        @JsonProperty("results") val results: List<ContentItem>
    )

    data class GenreResponse(
        @JsonProperty("results") val results: List<ContentItem>
    )

    data class AnimeResponse(
        @JsonProperty("results") val results: List<ContentItem>
    )

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

    // Helper para fazer requests com rate limiting
    private suspend fun <T> safeApiRequest(url: String, block: suspend () -> T): T {
        // Adicionar delay para evitar rate limiting
        kotlinx.coroutines.delay(500)
        
        try {
            return block()
        } catch (e: Exception) {
            if (e.message?.contains("429") == true) {
                // Rate limit atingido, esperar mais tempo
                kotlinx.coroutines.delay(2000)
                return block()
            }
            throw e
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<SearchResponse>()
        
        return safeApiRequest(request.name) {
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
            
            newHomePageResponse(request.name, items, hasNext = false)
        }
    }

    private suspend fun getTrending(): List<SearchResponse> {
        val url = "$mainUrl/api/trending?type=all"
        val response = app.get(
            url,
            headers = headers,
            cookies = cookies,
            timeout = 30
        )
        
        val data = response.parsedSafe<TrendingResponse>() ?: return emptyList()
        
        return data.results.mapNotNull { item ->
            try {
                val title = item.title ?: item.name ?: item.originalTitle ?: item.originalName ?: return@mapNotNull null
                val year = getYearFromDate(item.releaseDate ?: item.firstAirDate)
                val poster = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                val id = item.id
                
                // Determinar tipo
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
                
                // Gerar URL no formato correto do site
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
        val response = app.get(
            url,
            headers = headers,
            cookies = cookies,
            timeout = 30
        )
        
        val data = response.parsedSafe<AnimeResponse>() ?: return emptyList()
        
        return data.results.mapNotNull { item ->
            try {
                val title = item.title ?: item.name ?: item.originalTitle ?: item.originalName ?: return@mapNotNull null
                val year = getYearFromDate(item.releaseDate ?: item.firstAirDate)
                val poster = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                val id = item.id
                
                // Gerar URL no formato correto
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
        val response = app.get(
            url,
            headers = headers,
            cookies = cookies,
            timeout = 30
        )
        
        val data = response.parsedSafe<GenreResponse>() ?: return emptyList()
        
        return data.results.mapNotNull { item ->
            try {
                val title = item.title ?: item.name ?: item.originalTitle ?: item.originalName ?: return@mapNotNull null
                val year = getYearFromDate(item.releaseDate ?: item.firstAirDate)
                val poster = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                val id = item.id
                
                // Determinar tipo
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
                
                // Gerar URL no formato correto
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
        return try {
            dateString?.substring(0, 4)?.toIntOrNull()
        } catch (e: Exception) {
            null
        }
    }

    // Função para gerar slug a partir do título
    private fun generateSlug(title: String): String {
        return title
            .lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .replace(Regex("\\s+"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return safeApiRequest(query) {
            // Primeiro tentar a API de busca do site
            try {
                val encodedQuery = query.encodeSearchQuery()
                val url = "$mainUrl/api/search?query=$encodedQuery"
                
                val response = app.get(
                    url,
                    headers = headers,
                    cookies = cookies,
                    timeout = 30
                )
                
                val data = response.parsedSafe<SearchResponseData>() ?: return@safeApiRequest emptyList()
                
                return@safeApiRequest data.results.mapNotNull { item ->
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
                        
                        // Gerar URL no formato correto
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
                // Fallback para busca HTML
                fallbackSearch(query)
            }
        }
    }

    // Fallback caso a API de busca não esteja disponível
    private suspend fun fallbackSearch(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?q=${query.encodeSearchQuery()}"
        val document = app.get(searchUrl, headers = headers, cookies = cookies).document
        
        return document.select("a[href*='?id=']").mapNotNull { element ->
            try {
                val href = element.attr("href") ?: return@mapNotNull null
                if (href.startsWith("/canal")) return@mapNotNull null
                
                val imgElement = element.selectFirst("img")
                val title = imgElement?.attr("alt") ?: 
                           element.selectFirst(".text-white")?.text() ?:
                           return@mapNotNull null
                
                val poster = imgElement?.attr("src")?.let { fixUrl(it) }
                val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
                val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
                
                // Determinar tipo pela URL
                val isSeries = href.contains("type=tv") || href.contains("/tv")
                val isMovie = href.contains("type=movie") || href.contains("/movie")
                val isAnime = title.contains("(Anime)", ignoreCase = true) || href.contains("type=anime")
                
                when {
                    isAnime -> newAnimeSearchResponse(cleanTitle, fixUrl(href), TvType.Anime) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    isSeries -> newTvSeriesSearchResponse(cleanTitle, fixUrl(href), TvType.TvSeries) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    isMovie -> newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    data class SearchResponseData(
        @JsonProperty("results") val results: List<ContentItem>
    )

    override suspend fun load(url: String): LoadResponse? {
        return safeApiRequest(url) {
            try {
                // Carregar a página de detalhes
                val response = app.get(url, headers = headers, cookies = cookies, timeout = 30)
                if (response.code >= 400) return@safeApiRequest null
                
                val document = response.document
                
                // Extrair informações da página
                val title = extractTitle(document)
                val year = extractYear(document)
                val overview = extractOverview(document)
                val poster = extractPoster(document)
                val backdrop = extractBackdrop(document)
                val genres = extractGenres(document)
                val actors = extractActors(document)
                val trailerKey = extractTrailer(document)
                
                // Determinar tipo pela URL
                val isSeries = url.contains("type=tv") || url.contains("/tv")
                val isAnime = url.contains("type=anime") || url.contains("/anime")
                val isMovie = !isSeries && !isAnime
                
                if (isSeries || isAnime) {
                    val type = if (isAnime) TvType.Anime else TvType.TvSeries
                    val episodes = extractEpisodes(document, url)
                    
                    newTvSeriesLoadResponse(title ?: "Sem título", url, type, episodes) {
                        this.posterUrl = poster
                        this.backgroundPosterUrl = backdrop
                        this.year = year
                        this.plot = overview
                        this.tags = genres
                        this.duration = extractDuration(document)
                        this.recommendations = null
                        if (actors.isNotEmpty()) {
                            addActors(actors)
                        }
                        
                        if (trailerKey != null) {
                            addTrailer(trailerKey)
                        }
                    }
                } else {
                    newMovieLoadResponse(title ?: "Sem título", url, TvType.Movie, url) {
                        this.posterUrl = poster
                        this.backgroundPosterUrl = backdrop
                        this.year = year
                        this.plot = overview
                        this.tags = genres
                        this.duration = extractDuration(document)
                        this.recommendations = null
                        if (actors.isNotEmpty()) {
                            addActors(actors)
                        }
                        
                        if (trailerKey != null) {
                            addTrailer(trailerKey)
                        }
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    // Funções para extrair informações da página de detalhes
    private fun extractTitle(document: org.jsoup.nodes.Document): String? {
        return document.selectFirst("h1, .title, .movie-title, .tv-title, .anime-title")?.text()?.trim()
    }

    private fun extractOverview(document: org.jsoup.nodes.Document): String? {
        return document.selectFirst(".overview, .synopsis, .plot, .description")?.text()?.trim()
    }

    private fun extractPoster(document: org.jsoup.nodes.Document): String? {
        return document.selectFirst(".poster img, .movie-poster img, .tv-poster img")?.attr("src")?.let { fixUrl(it) } ?:
               document.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }
    }

    private fun extractBackdrop(document: org.jsoup.nodes.Document): String? {
        return document.selectFirst(".backdrop, .background-image")?.attr("style")?.let { 
            Regex("url\\(['\"]?(.*?)['\"]?\\)").find(it)?.groupValues?.get(1)?.let { fixUrl(it) }
        } ?: extractPoster(document)
    }

    private fun extractGenres(document: org.jsoup.nodes.Document): List<String> {
        return document.select(".genres .genre, .tags .tag, .categories .category").map { it.text().trim() }
    }

    private fun extractActors(document: org.jsoup.nodes.Document): List<Actor> {
        val actors = mutableListOf<Actor>()
        
        document.select(".cast .actor, .actors .actor").forEach { element ->
            try {
                val name = element.selectFirst(".name, .actor-name")?.text()?.trim() ?: return@forEach
                val character = element.selectFirst(".character, .role")?.text()?.trim() ?: ""
                val image = element.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                
                actors.add(Actor(name, image))
            } catch (e: Exception) {
                // Ignorar erro
            }
        }
        
        return actors
    }

    private fun extractTrailer(document: org.jsoup.nodes.Document): String? {
        // Procurar por iframe do YouTube
        val iframe = document.selectFirst("iframe[src*='youtube'], iframe[src*='youtu.be']")
        if (iframe != null) {
            val src = iframe.attr("src")
            // Extrair ID do YouTube do URL
            val youtubeId = Regex("(?:youtube\\.com\\/embed\\/|youtu\\.be\\/)([^?&]+)").find(src)?.groupValues?.get(1)
            return youtubeId
        }
        
        // Procurar por links de trailer
        val trailerLink = document.selectFirst("a[href*='youtube'], a[href*='youtu.be']")
        trailerLink?.attr("href")?.let { href ->
            val youtubeId = Regex("(?:youtube\\.com\\/watch\\?v=|youtu\\.be\\/)([^?&]+)").find(href)?.groupValues?.get(1)
            return youtubeId
        }
        
        return null
    }

    private fun extractDuration(document: org.jsoup.nodes.Document): Int? {
        val durationText = document.selectFirst(".duration, .runtime, .time")?.text()?.trim()
        if (durationText != null) {
            // Tentar extrair minutos do texto (ex: "120 min", "2h 30m")
            val minutesMatch = Regex("(\\d+)\\s*min").find(durationText)
            if (minutesMatch != null) {
                return minutesMatch.groupValues[1].toIntOrNull()
            }
            
            // Tentar extrair horas e minutos
            val hoursMatch = Regex("(\\d+)\\s*h").find(durationText)
            val minsMatch = Regex("(\\d+)\\s*m").find(durationText)
            
            val hours = hoursMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val minutes = minsMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            
            return (hours * 60) + minutes
        }
        return null
    }

    private fun extractEpisodes(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        // Extrair episódios da página
        document.select(".episode, .episode-item, .season-episode").forEach { element ->
            try {
                val epNumber = element.selectFirst(".episode-number, .number")?.text()?.toIntOrNull() ?: 
                              Regex("Ep\\.?\\s*(\\d+)").find(element.text())?.groupValues?.get(1)?.toIntOrNull() ?:
                              return@forEach
                
                val seasonNumber = element.selectFirst(".season-number")?.text()?.toIntOrNull() ?: 1
                val title = element.selectFirst(".episode-title, .title")?.text()?.trim() ?: "Episódio $epNumber"
                val description = element.selectFirst(".description, .overview")?.text()?.trim()
                val poster = element.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                
                // Tentar extrair URL do episódio
                val epUrl = element.attr("href") ?: element.attr("data-url") ?: 
                           "$baseUrl&season=$seasonNumber&episode=$epNumber"
                
                val episode = newEpisode(fixUrl(epUrl)) {
                    this.name = title
                    this.season = seasonNumber
                    this.episode = epNumber
                    this.description = description
                    this.posterUrl = poster
                }
                
                episodes.add(episode)
            } catch (e: Exception) {
                // Ignorar episódio com erro
            }
        }
        
        // Se não encontrar episódios, tentar extrair da URL
        if (episodes.isEmpty()) {
            val idMatch = Regex("[?&]id=(\\d+)").find(baseUrl)
            val id = idMatch?.groupValues?.get(1)
            val type = if (baseUrl.contains("type=tv")) "tv" else "anime"
            
            if (id != null) {
                // Buscar episódios da API do TMDB
                try {
                    val tmdbApiKey = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiIxNjY3Y2I1N2UzNmEwZmQyYmEyYTQ3OWQ5NjU4MzcxZiIsInN1YiI6IjY1YzVlYTA5NTRhMDk4MDE4MjVmMDgxMCIsInNjb3BlcyI6WyJhcGlfcmVhZCJdLCJ2ZXJzaW9uIjoxfQ.3aVdvwzT41fFf7m_TfZc4cP7P8z_Rk-t-BZST_t4vzY"
                    
                    // Primeiro buscar temporadas
                    val tvUrl = "https://api.themoviedb.org/3/tv/$id?language=pt-BR"
                    val tvResponse = app.get(
                        tvUrl,
                        headers = mapOf("Authorization" to "Bearer $tmdbApiKey", "Accept" to "application/json")
                    )
                    
                    val tvData = tvResponse.parsed<TvData>()
                    tvData.seasons?.forEach { season ->
                        if (season.seasonNumber > 0) {
                            // Buscar episódios da temporada
                            val seasonUrl = "https://api.themoviedb.org/3/tv/$id/season/${season.seasonNumber}?language=pt-BR"
                            val seasonResponse = app.get(
                                seasonUrl,
                                headers = mapOf("Authorization" to "Bearer $tmdbApiKey", "Accept" to "application/json")
                            )
                            
                            val seasonData = seasonResponse.parsed<SeasonData>()
                            seasonData.episodes?.forEach { ep ->
                                val episode = newEpisode("$baseUrl&season=${season.seasonNumber}&episode=${ep.episodeNumber}") {
                                    this.name = ep.name ?: "Episódio ${ep.episodeNumber}"
                                    this.season = season.seasonNumber
                                    this.episode = ep.episodeNumber
                                    this.description = ep.overview
                                    this.posterUrl = ep.stillPath?.let { "https://image.tmdb.org/t/p/w300$it" }
                                }
                                episodes.add(episode)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Ignorar erro da API
                }
            }
        }
        
        return episodes
    }

    // Modelos de dados TMDB para episódios
    data class TvData(
        @JsonProperty("seasons") val seasons: List<TvSeason>?
    )
    
    data class TvSeason(
        @JsonProperty("season_number") val seasonNumber: Int
    )
    
    data class SeasonData(
        @JsonProperty("episodes") val episodes: List<SeasonEpisode>?
    )
    
    data class SeasonEpisode(
        @JsonProperty("episode_number") val episodeNumber: Int,
        @JsonProperty("name") val name: String?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("still_path") val stillPath: String?
    )

    data class SearchResponseData(
        @JsonProperty("results") val results: List<ContentItem>
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return safeApiRequest(data) {
            try {
                // Tentar extrair links da página
                val document = app.get(data, headers = headers, cookies = cookies).document
                
                // Procurar por botões de player
                val playButton = document.selectFirst("button[onclick*='play'], button[data-player], .play-button")
                if (playButton != null) {
                    // Extrair URL do player do onclick ou data attribute
                    val onclick = playButton.attr("onclick")
                    val playerUrl = Regex("['\"](https?://[^'\"]+)['\"]").find(onclick)?.groupValues?.get(1) ?:
                                   playButton.attr("data-player") ?:
                                   playButton.attr("data-url")
                    
                    if (playerUrl != null) {
                        return extractFromPlayerUrl(fixUrl(playerUrl), callback)
                    }
                }
                
                // Procurar por iframes de player
                val iframeSrc = document.selectFirst("iframe[src*='embed'], iframe[src*='player']")?.attr("src")
                if (iframeSrc != null) {
                    return extractFromIframe(fixUrl(iframeSrc), callback)
                }
                
                // Procurar por scripts com m3u8
                val scripts = document.select("script")
                for (script in scripts) {
                    val html = script.html()
                    val m3u8Pattern = Regex("""(https?://[^"\s]+\.m3u8[^"\s]*)""")
                    val match = m3u8Pattern.find(html)
                    if (match != null) {
                        val m3u8Url = match.groupValues[1]
                        return createM3u8Link(m3u8Url, callback)
                    }
                }
                
                // Procurar por links diretos
                val videoLinks = document.select("a[href*='.m3u8'], a[href*='mp4'], a[href*='.mkv']")
                for (link in videoLinks) {
                    val href = link.attr("href")
                    if (href.contains(".m3u8")) {
                        return createM3u8Link(fixUrl(href), callback)
                    }
                }
                
                false
            } catch (e: Exception) {
                false
            }
        }
    }

    private suspend fun extractFromPlayerUrl(playerUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        return safeApiRequest(playerUrl) {
            try {
                val response = app.get(playerUrl, headers = headers, cookies = cookies)
                val html = response.text
                
                // Procurar por m3u8 no HTML do player
                val patterns = listOf(
                    Regex("""file["']?\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                    Regex("""src=["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                    Regex("""(https?://[^"\s]+\.m3u8[^"\s]*)""")
                )
                
                for (pattern in patterns) {
                    val match = pattern.find(html)
                    if (match != null) {
                        val m3u8Url = match.groupValues[1]
                        createM3u8Link(m3u8Url, callback)
                        return@safeApiRequest true
                    }
                }
                
                // Procurar por iframe dentro do player
                val iframeMatch = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(html)
                if (iframeMatch != null) {
                    val iframeSrc = iframeMatch.groupValues[1]
                    return extractFromIframe(fixUrl(iframeSrc), callback)
                }
                
                false
            } catch (e: Exception) {
                false
            }
        }
    }

    private suspend fun extractFromIframe(
        iframeUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return safeApiRequest(iframeUrl) {
            try {
                val response = app.get(iframeUrl, headers = headers, cookies = cookies)
                val html = response.text
                
                // Procurar por m3u8
                val patterns = listOf(
                    Regex("""file["']?\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                    Regex("""src=["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                    Regex("""(https?://[^"\s]+\.m3u8[^"\s]*)""")
                )
                
                for (pattern in patterns) {
                    val match = pattern.find(html)
                    if (match != null) {
                        val m3u8Url = match.groupValues[1]
                        createM3u8Link(m3u8Url, callback)
                        return@safeApiRequest true
                    }
                }
                
                false
            } catch (e: Exception) {
                false
            }
        }
    }

    private suspend fun createM3u8Link(
        m3u8Url: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return safeApiRequest(m3u8Url) {
            try {
                // Gerar múltiplas qualidades
                val links = M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = m3u8Url,
                    referer = mainUrl,
                    headers = headers
                )
                
                if (links.isNotEmpty()) {
                    links.forEach { callback(it) }
                    true
                } else {
                    // Link direto se M3u8Helper falhar
                    val link = newExtractorLink(
                        source = name,
                        name = "Video",
                        url = m3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = mainUrl
                        this.quality = 720
                        this.headers = headers
                    }
                    callback(link)
                    true
                }
            } catch (e: Exception) {
                false
            }
        }
    }
}

// Função de extensão para codificar query
private fun String.encodeSearchQuery(): String {
    return java.net.URLEncoder.encode(this, "UTF-8")
}
