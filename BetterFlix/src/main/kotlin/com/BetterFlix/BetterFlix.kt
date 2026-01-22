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
                
                val url = when (type) {
                    TvType.Movie -> "$mainUrl/movie?id=$id"
                    TvType.TvSeries -> "$mainUrl/tv?id=$id"
                    TvType.Anime -> "$mainUrl/anime?id=$id"
                    else -> "$mainUrl/movie?id=$id"
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
                val url = "$mainUrl/anime?id=$id"
                
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
                
                val url = when (type) {
                    TvType.Movie -> "$mainUrl/movie?id=$id"
                    TvType.TvSeries -> "$mainUrl/tv?id=$id"
                    TvType.Anime -> "$mainUrl/anime?id=$id"
                    else -> "$mainUrl/movie?id=$id"
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

    override suspend fun search(query: String): List<SearchResponse> {
        return safeApiRequest(query) {
            // Primeiro tentar a API de busca
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
                        
                        val url = when (type) {
                            TvType.Movie -> "$mainUrl/movie?id=$id"
                            TvType.TvSeries -> "$mainUrl/tv?id=$id"
                            TvType.Anime -> "$mainUrl/anime?id=$id"
                            else -> "$mainUrl/movie?id=$id"
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
                
                // Determinar tipo
                val isSeries = href.contains("type=tv")
                val isMovie = href.contains("type=movie")
                val isAnime = title.contains("(Anime)", ignoreCase = true)
                
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
                // Extrair ID e tipo da URL
                val idMatch = Regex("[?&]id=(\\d+)").find(url)
                val id = idMatch?.groupValues?.get(1) ?: return@safeApiRequest null
                
                val type = when {
                    url.contains("/movie") -> "movie"
                    url.contains("/tv") -> "tv"
                    url.contains("/anime") -> "anime"
                    else -> "movie"
                }
                
                // Buscar detalhes da TMDB
                val tmdbApiKey = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiIxNjY3Y2I1N2UzNmEwZmQyYmEyYTQ3OWQ5NjU4MzcxZiIsInN1YiI6IjY1YzVlYTA5NTRhMDk4MDE4MjVmMDgxMCIsInNjb3BlcyI6WyJhcGlfcmVhZCJdLCJ2ZXJzaW9uIjoxfQ.3aVdvwzT41fFf7m_TfZc4cP7P8z_Rk-t-BZST_t4vzY"
                val tmdbUrl = when (type) {
                    "movie" -> "https://api.themoviedb.org/3/movie/$id?language=pt-BR"
                    "tv" -> "https://api.themoviedb.org/3/tv/$id?language=pt-BR"
                    else -> "https://api.themoviedb.org/3/movie/$id?language=pt-BR"
                }
                
                val tmdbResponse = app.get(
                    tmdbUrl,
                    headers = mapOf(
                        "Authorization" to "Bearer $tmdbApiKey",
                        "Accept" to "application/json"
                    ),
                    timeout = 30
                )
                
                if (tmdbResponse.code !in 200..299) {
                    return@safeApiRequest fallbackLoad(url)
                }
                
                val tmdbData = tmdbResponse.parsed<TmdbItem>()
                
                val title = tmdbData.title ?: tmdbData.name ?: tmdbData.originalTitle ?: tmdbData.originalName ?: "Sem título"
                val year = tmdbData.releaseDate?.substring(0, 4)?.toIntOrNull() ?: 
                          tmdbData.firstAirDate?.substring(0, 4)?.toIntOrNull()
                val poster = tmdbData.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                val backdrop = tmdbData.backdropPath?.let { "https://image.tmdb.org/t/p/original$it" }
                val overview = tmdbData.overview
                val genres = tmdbData.genres?.map { it.name } ?: emptyList()
                val score = tmdbData.voteAverage?.times(10)?.toInt() // Converter de 0-10 para 0-100
                
                // Buscar trailers
                val trailerUrl = when (type) {
                    "movie" -> "https://api.themoviedb.org/3/movie/$id/videos?language=pt-BR"
                    "tv" -> "https://api.themoviedb.org/3/tv/$id/videos?language=pt-BR"
                    else -> null
                }
                
                var trailerKey: String? = null
                if (trailerUrl != null) {
                    try {
                        val trailerResponse = app.get(
                            trailerUrl,
                            headers = mapOf(
                                "Authorization" to "Bearer $tmdbApiKey",
                                "Accept" to "application/json"
                            )
                        )
                        val trailerData = trailerResponse.parsed<TrailerResponse>()
                        trailerKey = trailerData.results
                            .find { it.type == "Trailer" && it.site == "YouTube" }
                            ?.key
                    } catch (e: Exception) {
                        // Ignorar erro de trailer
                    }
                }
                
                // Buscar atores (sistema atualizado)
                val creditsUrl = when (type) {
                    "movie" -> "https://api.themoviedb.org/3/movie/$id/credits?language=pt-BR"
                    "tv" -> "https://api.themoviedb.org/3/tv/$id/credits?language=pt-BR"
                    else -> null
                }
                
                val actors = mutableListOf<Actor>()
                if (creditsUrl != null) {
                    try {
                        val creditsResponse = app.get(
                            creditsUrl,
                            headers = mapOf(
                                "Authorization" to "Bearer $tmdbApiKey",
                                "Accept" to "application/json"
                            )
                        )
                        val creditsData = creditsResponse.parsed<CreditsResponse>()
                        actors.addAll(creditsData.cast.take(10).mapNotNull { cast ->
                            val actorName = cast.name ?: return@mapNotNull null
                            val character = cast.character ?: ""
                            val image = cast.profilePath?.let { "https://image.tmdb.org/t/p/w185$it" }
                            Actor(actorName, image)
                        })
                    } catch (e: Exception) {
                        // Ignorar erro de créditos
                    }
                }
                
                val mediaType = when (type) {
                    "movie" -> TvType.Movie
                    "tv" -> TvType.TvSeries
                    "anime" -> TvType.Anime
                    else -> TvType.Movie
                }
                
                if (mediaType == TvType.TvSeries || mediaType == TvType.Anime) {
                    // Buscar temporadas para séries
                    val seasons = tmdbData.seasons ?: emptyList()
                    val episodes = mutableListOf<Episode>()
                    
                    seasons.forEach { season ->
                        if (season.seasonNumber > 0) {
                            val seasonEpisodes = try {
                                val seasonUrl = "https://api.themoviedb.org/3/tv/$id/season/${season.seasonNumber}?language=pt-BR"
                                val seasonResponse = app.get(
                                    seasonUrl,
                                    headers = mapOf(
                                        "Authorization" to "Bearer $tmdbApiKey",
                                        "Accept" to "application/json"
                                    )
                                )
                                seasonResponse.parsed<SeasonResponse>().episodes
                            } catch (e: Exception) {
                                emptyList()
                            }
                            
                            episodes.addAll(seasonEpisodes.map { ep ->
                                newEpisode("$mainUrl/watch?type=$type&id=$id&season=${season.seasonNumber}&episode=${ep.episodeNumber}") {
                                    this.name = ep.name ?: "Episódio ${ep.episodeNumber}"
                                    this.season = season.seasonNumber
                                    this.episode = ep.episodeNumber
                                    this.description = ep.overview
                                    this.posterUrl = ep.stillPath?.let { "https://image.tmdb.org/t/p/w300$it" }
                                }
                            })
                        }
                    }
                    
                    newTvSeriesLoadResponse(title, url, mediaType, episodes) {
                        this.posterUrl = poster
                        this.backgroundPosterUrl = backdrop
                        this.year = year
                        this.plot = overview
                        this.tags = genres
                        this.score = score
                        this.duration = tmdbData.episodeRunTime?.firstOrNull()
                        this.recommendations = null
                        if (actors.isNotEmpty()) {
                            addActors(actors)
                        }
                        
                        if (trailerKey != null) {
                            addTrailer(trailerKey)
                        }
                    }
                } else {
                    newMovieLoadResponse(title, url, mediaType, "$mainUrl/watch?type=$type&id=$id") {
                        this.posterUrl = poster
                        this.backgroundPosterUrl = backdrop
                        this.year = year
                        this.plot = overview
                        this.tags = genres
                        this.score = score
                        this.duration = tmdbData.runtime
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
                fallbackLoad(url)
            }
        }
    }

    // Modelos de dados TMDB
    data class TmdbItem(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("original_title") val originalTitle: String?,
        @JsonProperty("original_name") val originalName: String?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("poster_path") val posterPath: String?,
        @JsonProperty("backdrop_path") val backdropPath: String?,
        @JsonProperty("release_date") val releaseDate: String?,
        @JsonProperty("first_air_date") val firstAirDate: String?,
        @JsonProperty("vote_average") val voteAverage: Double?,
        @JsonProperty("genres") val genres: List<Genre>?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("episode_run_time") val episodeRunTime: List<Int>?,
        @JsonProperty("seasons") val seasons: List<Season>?
    )

    data class Genre(
        @JsonProperty("id") val id: Int,
        @JsonProperty("name") val name: String
    )

    data class Season(
        @JsonProperty("id") val id: Int,
        @JsonProperty("name") val name: String,
        @JsonProperty("season_number") val seasonNumber: Int,
        @JsonProperty("episode_count") val episodeCount: Int,
        @JsonProperty("poster_path") val posterPath: String?
    )

    data class TrailerResponse(
        @JsonProperty("results") val results: List<Trailer>
    )

    data class Trailer(
        @JsonProperty("key") val key: String,
        @JsonProperty("site") val site: String,
        @JsonProperty("type") val type: String
    )

    data class CreditsResponse(
        @JsonProperty("cast") val cast: List<CastMember>
    )

    data class CastMember(
        @JsonProperty("name") val name: String?,
        @JsonProperty("character") val character: String?,
        @JsonProperty("profile_path") val profilePath: String?
    )

    data class SeasonResponse(
        @JsonProperty("episodes") val episodes: List<EpisodeItem>
    )

    data class EpisodeItem(
        @JsonProperty("id") val id: Int,
        @JsonProperty("name") val name: String?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("episode_number") val episodeNumber: Int,
        @JsonProperty("season_number") val seasonNumber: Int,
        @JsonProperty("still_path") val stillPath: String?
    )

    // Fallback para carregamento
    private suspend fun fallbackLoad(url: String): LoadResponse? {
        return try {
            val response = app.get(url, headers = headers, cookies = cookies, timeout = 10_000)
            if (response.code >= 400) return null
            
            val document = response.document
            
            // Extrair título
            val titleElement = document.selectFirst("h1, .title, header h1")
            val title = titleElement?.text() ?: return null
            
            // Extrair informações básicas
            val year = extractYear(document)
            val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
            
            // Determinar tipo
            val isSeries = url.contains("type=tv") || document.select(".episode-list, .season-list").isNotEmpty()
            val isMovie = url.contains("type=movie") || (!isSeries && document.select(".movie-player").isNotEmpty())
            val isAnime = cleanTitle.contains("(Anime)", ignoreCase = true)
            
            // Extrair sinopse
            val synopsis = document.selectFirst("p.text-gray-200, .synopsis, .description, .plot")?.text()
            
            // Extrair gêneros
            val genres = document.select("span.bg-purple-600\\/80, .genre, .tags, .category").map { it.text().trim() }
                .takeIf { it.isNotEmpty() }
            
            // Extrair poster
            val poster = extractPoster(document)
            
            if (isSeries || isAnime) {
                val type = if (isAnime) TvType.Anime else TvType.TvSeries
                
                // Para séries, tentar extrair episódios
                val episodes = tryExtractEpisodes(document, url)
                
                newTvSeriesLoadResponse(cleanTitle, url, type, episodes) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = synopsis
                    this.tags = genres
                    this.backgroundPosterUrl = poster
                }
            } else {
                // Para filmes
                newMovieLoadResponse(cleanTitle, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = synopsis
                    this.tags = genres
                    this.backgroundPosterUrl = poster
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    // Mantendo as funções auxiliares do seu código original
    private suspend fun tryExtractEpisodes(document: org.jsoup.nodes.Document, url: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        try {
            // Tentar extrair botões de episódio
            document.select("button[data-url], a[href*='episode'], .episode-item, .episode-link").forEachIndexed { index, element ->
                try {
                    val dataUrl = element.attr("data-url") ?: element.attr("href") ?: ""
                    if (dataUrl.isBlank()) return@forEachIndexed
                    
                    val epNumber = extractEpisodeNumber(element, index + 1)
                    val seasonNumber = element.attr("data-season").toIntOrNull() ?: 1
                    
                    val episode = newEpisode(fixUrl(dataUrl)) {
                        this.name = "Episódio $epNumber"
                        this.season = seasonNumber
                        this.episode = epNumber
                        
                        // Tentar extrair descrição
                        element.selectFirst(".ep-desc, .description")?.text()?.trim()?.let { desc ->
                            if (desc.isNotBlank()) {
                                this.description = desc
                            }
                        }
                    }
                    
                    episodes.add(episode)
                } catch (e: Exception) {
                    // Ignorar episódio com erro
                }
            }
        } catch (e: Exception) {
            // Falha silenciosa
        }
        
        return episodes
    }

    private fun extractYear(document: org.jsoup.nodes.Document): Int? {
        // Tenta extrair do grid de informações
        document.select("div.bg-gray-800\\/50, .info-grid, .metadata").forEach { div ->
            val label = div.selectFirst("p.text-gray-400, .label, .info-label")?.text()
            if (label?.contains("Ano") == true || label?.contains("Year") == true) {
                val yearText = div.selectFirst("p.text-white, .value, .info-value")?.text()
                return yearText?.toIntOrNull()
            }
        }
        
        // Tenta extrair do título
        val title = document.selectFirst("h1, .title")?.text() ?: ""
        return Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractPoster(document: org.jsoup.nodes.Document): String? {
        // Tenta meta tag primeiro
        val ogImage = document.selectFirst("meta[property='og:image']")?.attr("content")
        if (ogImage != null) return fixUrl(ogImage)
        
        // Tenta qualquer imagem grande
        return document.select("img[src*='tmdb.org'], img[src*='poster'], .poster img").firstOrNull()?.attr("src")?.let { fixUrl(it) }
    }

    private fun extractEpisodeNumber(element: org.jsoup.nodes.Element, default: Int): Int {
        return element.attr("data-ep").toIntOrNull() ?:
               element.selectFirst(".ep-number, .number, .episode-number")?.text()?.toIntOrNull() ?:
               Regex("Ep\\.?\\s*(\\d+)").find(element.text())?.groupValues?.get(1)?.toIntOrNull() ?:
               Regex("Epis[oó]dio\\s*(\\d+)").find(element.text())?.groupValues?.get(1)?.toIntOrNull() ?:
               default
    }

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
                
                // Procurar por iframes de player
                val iframeSrc = document.selectFirst("iframe[src*='embed'], iframe[src*='player']")?.attr("src")
                if (iframeSrc != null) {
                    return@safeApiRequest extractFromIframe(fixUrl(iframeSrc), callback)
                }
                
                // Procurar por scripts com m3u8
                val scripts = document.select("script")
                for (script in scripts) {
                    val html = script.html()
                    val m3u8Pattern = Regex("""(https?://[^"\s]+\.m3u8[^"\s]*)""")
                    val match = m3u8Pattern.find(html)
                    if (match != null) {
                        val m3u8Url = match.groupValues[1]
                        return@safeApiRequest createM3u8Link(m3u8Url, callback)
                    }
                }
                
                // Procurar por data-url em botões
                val playButton = document.selectFirst("button[data-url], a[data-url]")
                val dataUrl = playButton?.attr("data-url")
                if (dataUrl != null) {
                    return@safeApiRequest extractVideoLinks(dataUrl, subtitleCallback, callback)
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

    private suspend fun extractVideoLinks(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Tentar extrair do URL direto
        if (url.contains(".m3u8")) {
            return createM3u8Link(url, callback)
        }
        
        // Se não for m3u8, tentar seguir o link
        return safeApiRequest(url) {
            try {
                val document = app.get(url, headers = headers, cookies = cookies).document
                val iframe = document.selectFirst("iframe[src]")
                if (iframe != null) {
                    extractFromIframe(iframe.attr("src"), callback)
                } else {
                    false
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
