package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import java.text.SimpleDateFormat
import java.net.URLEncoder
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

class SuperFlix : MainAPI() {
    override var mainUrl = "https://superflix21.lol"
    override var name = "SuperFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val usesWebView = true

    // ============ TMDB COM BuildConfig ============
    private val tmdbApiKey = BuildConfig.TMDB_API_KEY
    private val tmdbAccessToken = BuildConfig.TMDB_ACCESS_TOKEN
    private val tmdbBaseUrl = "https://api.themoviedb.org/3"
    private val tmdbImageUrl = "https://image.tmdb.org/t/p"

    companion object {
        // Headers para TMDB API
        private fun getTmdbHeaders(): Map<String, String> {
            return mapOf(
                "Authorization" to "Bearer ${BuildConfig.TMDB_ACCESS_TOKEN}",
                "accept" to "application/json"
            )
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/lancamentos" to "Lançamentos",
        "$mainUrl/filmes" to "Últimos Filmes",
        "$mainUrl/series" to "Últimas Séries",
        "$mainUrl/animes" to "Últimas Animes"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + if (page > 1) "?page=$page" else ""
        val document = app.get(url).document

        val home = document.select("a.card, div.recs-grid a.rec-card").mapNotNull { element ->
            element.toSearchResult()
        }

        return newHomePageResponse(request.name, home.distinctBy { it.url })
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = attr("title") ?: selectFirst("img")?.attr("alt") ?: return null
        val href = attr("href") ?: return null

        val localPoster = selectFirst("img")?.attr("src")?.let { fixUrl(it) }
        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()

        val badge = selectFirst(".badge-kind")?.text()?.lowercase() ?: ""
        val isAnime = badge.contains("anime") || href.contains("/anime/") ||
                      title.contains("(Anime)", ignoreCase = true)
        val isSerie = badge.contains("série") || badge.contains("serie") ||
                     href.contains("/serie/") ||
                     (!isAnime && (badge.contains("tv") || href.contains("/tv/")))
        val isMovie = !isSerie && !isAnime

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
        val searchUrl = "$mainUrl/buscar?q=${URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document

        return document.select(".grid .card, a.card").mapNotNull { card ->
            try {
                val title = card.attr("title") ?: card.selectFirst("img")?.attr("alt") ?: return@mapNotNull null
                val href = card.attr("href") ?: return@mapNotNull null

                val poster = card.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
                val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()

                val badge = card.selectFirst(".badge-kind")?.text()?.lowercase() ?: ""
                val isAnime = badge.contains("anime") || href.contains("/anime/") ||
                             title.contains("(Anime)", ignoreCase = true)
                val isSerie = badge.contains("série") || badge.contains("serie") ||
                             href.contains("/serie/") ||
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
        println("🔍 [SUPERFLIX] Load iniciado para: $url")

        val document = app.get(url).document
        val titleElement = document.selectFirst("h1, .title")
        val title = titleElement?.text() ?: return null
        
        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()

        val isAnime = url.contains("/anime/") || title.contains("(Anime)", ignoreCase = true)
        val isSerie = url.contains("/serie/") || url.contains("/tv/") ||
                     (!isAnime && document.selectFirst(".episode-list, .season-list, .seasons") != null)

        println("🔍 [SUPERFLIX] Buscando TMDB para: $cleanTitle")
        val tmdbInfo = if (isAnime || isSerie) {
            searchOnTMDB(cleanTitle, year, true)
        } else {
            searchOnTMDB(cleanTitle, year, false)
        }

        val siteRecommendations = extractRecommendationsFromSite(document)

        return if (tmdbInfo != null) {
            createLoadResponseWithTMDB(tmdbInfo, url, document, isAnime, isSerie, siteRecommendations)
        } else {
            createLoadResponseFromSite(document, url, cleanTitle, year, isAnime, isSerie)
        }
    }

    private suspend fun searchOnTMDB(query: String, year: Int?, isTv: Boolean): TMDBInfo? {
        println("🔍 [TMDB] Buscando: $query")
        
        // Verificar se as chaves são válidas
        if (tmdbApiKey == "dummy_api_key" || tmdbAccessToken == "dummy_access_token") {
            println("⚠️ [TMDB] Chaves não configuradas - usando fallback")
            return null
        }
        
        return try {
            val type = if (isTv) "tv" else "movie"
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            
            // Usando API Key na URL (método tradicional)
            var searchUrl = "$tmdbBaseUrl/search/$type?query=$encodedQuery&api_key=$tmdbApiKey&language=pt-BR"
            if (year != null) searchUrl += "&year=$year"
            
            println("🔗 [TMDB] URL: ${searchUrl.take(100)}...")
            
            val response = app.get(searchUrl, timeout = 10_000)
            
            if (response.code != 200) {
                println("❌ [TMDB] Erro HTTP: ${response.code}")
                return null
            }

            val searchResult = response.parsedSafe<TMDBSearchResponse>() ?: return null
            val result = searchResult.results.firstOrNull() ?: return null

            println("✅ [TMDB] Encontrado: ${if (isTv) result.name else result.title}")

            // Buscar detalhes completos com Access Token
            val details = getTMDBDetails(result.id, isTv) ?: return null

            // Extrair atores
            val allActors = details.credits?.cast?.take(15)?.mapNotNull { actor ->
                if (actor.name.isNotBlank()) {
                    Actor(
                        name = actor.name,
                        image = actor.profile_path?.let { "$tmdbImageUrl/w185$it" }
                    )
                } else null
            }

            // Buscar trailer
            val youtubeTrailer = getHighQualityTrailer(details.videos?.results)

            // Buscar temporadas se for série
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
            println("❌ [TMDB] Exception: ${e.message}")
            null
        }
    }

    private suspend fun getTMDBDetails(id: Int, isTv: Boolean): TMDBDetailsResponse? {
        return try {
            val type = if (isTv) "tv" else "movie"
            // Usar Access Token para detalhes (com append_to_response)
            val url = "$tmdbBaseUrl/$type/$id?append_to_response=credits,videos&language=pt-BR"
            
            val response = app.get(url, headers = getTmdbHeaders(), timeout = 10_000)
            if (response.code != 200) {
                println("❌ [TMDB Details] Erro: ${response.code}")
                return null
            }

            response.parsedSafe<TMDBDetailsResponse>()
        } catch (e: Exception) {
            println("❌ [TMDB Details] Exception: ${e.message}")
            null
        }
    }

    private suspend fun getTMDBAllSeasons(seriesId: Int): Map<Int, List<TMDBEpisode>> {
        val seasonsEpisodes = mutableMapOf<Int, List<TMDBEpisode>>()
        
        try {
            // Usar Access Token para temporadas
            val seriesUrl = "$tmdbBaseUrl/tv/$seriesId?language=pt-BR"
            val seriesResponse = app.get(seriesUrl, headers = getTmdbHeaders())
            
            if (seriesResponse.code == 200) {
                val seriesDetails = seriesResponse.parsedSafe<TMDBTVDetailsResponse>()
                seriesDetails?.seasons?.forEach { season ->
                    if (season.season_number > 0) {
                        val seasonUrl = "$tmdbBaseUrl/tv/$seriesId/season/${season.season_number}?language=pt-BR"
                        val seasonResponse = app.get(seasonUrl, headers = getTmdbHeaders())
                        
                        if (seasonResponse.code == 200) {
                            val seasonData = seasonResponse.parsedSafe<TMDBSeasonResponse>()
                            seasonData?.episodes?.let { episodes ->
                                seasonsEpisodes[season.season_number] = episodes
                                println("✅ [TMDB] Temporada ${season.season_number}: ${episodes.size} episódios")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("❌ [TMDB Seasons] Erro: ${e.message}")
        }
        
        return seasonsEpisodes
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

    // ... [RESTANTE DAS FUNÇÕES PERMANECE IGUAL - extractions, etc] ...

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return SuperFlixExtractor.extractVideoLinks(data, mainUrl, name, callback)
    }

    // ============ CLASSES DE DADOS ============

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBSearchResponse(
        @JsonProperty("results") val results: List<TMDBResult>
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBResult(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("release_date") val release_date: String? = null,
        @JsonProperty("first_air_date") val first_air_date: String? = null,
        @JsonProperty("poster_path") val poster_path: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBTVDetailsResponse(
        @JsonProperty("seasons") val seasons: List<TMDBSeasonInfo>
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBSeasonInfo(
        @JsonProperty("season_number") val season_number: Int,
        @JsonProperty("episode_count") val episode_count: Int
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBSeasonResponse(
        @JsonProperty("episodes") val episodes: List<TMDBEpisode>,
        @JsonProperty("air_date") val air_date: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBEpisode(
        @JsonProperty("episode_number") val episode_number: Int,
        @JsonProperty("name") val name: String,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("still_path") val still_path: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("air_date") val air_date: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBDetailsResponse(
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("backdrop_path") val backdrop_path: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("genres") val genres: List<TMDBGenre>?,
        @JsonProperty("credits") val credits: TMDBCredits?,
        @JsonProperty("videos") val videos: TMDBVideos?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBGenre(
        @JsonProperty("name") val name: String
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBCredits(
        @JsonProperty("cast") val cast: List<TMDBCast>
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBCast(
        @JsonProperty("name") val name: String,
        @JsonProperty("character") val character: String?,
        @JsonProperty("profile_path") val profile_path: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBVideos(
        @JsonProperty("results") val results: List<TMDBVideo>
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBVideo(
        @JsonProperty("key") val key: String,
        @JsonProperty("site") val site: String,
        @JsonProperty("type") val type: String,
        @JsonProperty("official") val official: Boolean? = false
    )

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
}
