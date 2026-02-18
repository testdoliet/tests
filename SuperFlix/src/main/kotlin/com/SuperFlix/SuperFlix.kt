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
    override var mainUrl = "https://superflix30.lol" // Verifique se ainda é essa
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
        private const val SEARCH_PATH = "/" // Mudou: busca é na raiz com ?s=

        // ATUALIZADO: Categorias com as novas URLs
        private val FIXED_CATEGORIES = listOf(
            "/categoria/lancamentos/" to "Lançamentos",
            "/assistir-filmes-online/" to "Últimos Filmes",
            "/assistir-series-online/" to "Últimas Séries"
        )

        // ATUALIZADO: Categorias aleatórias com as novas URLs
        private val ALL_RANDOM_CATEGORIES = listOf(
            "/categoria/acao/" to "Ação",
            "/categoria/animacao/" to "Animação",
            "/categoria/aventura/" to "Aventura",
            "/categoria/comedia/" to "Comédia",
            "/categoria/drama/" to "Drama",
            "/categoria/fantasia/" to "Fantasia",
            "/categoria/ficcao-cientifica/" to "Ficção Científica",
            "/categoria/romance/" to "Romance",
            "/categoria/thriller/" to "Suspense",
            "/categoria/terror/" to "Terror",
            "/categoria/crime/" to "Crime",
            "/categoria/misterio/" to "Mistério",
            "/categoria/documentario/" to "Documentário",
            "/categoria/familia/" to "Família",
            "/categoria/faroeste/" to "Faroeste",
            "/categoria/guerra/" to "Guerra",
            "/categoria/historia/" to "História",
            "/categoria/musica/" to "Musicais",
            "/categoria/kids/" to "Infantil",
            "/categoria/filmes-dublados/" to "Filmes Dublados",
            "/categoria/filmes-legendados/" to "Filmes Legendados",
            "/categoria/series-dubladas/" to "Séries Dubladas",
            "/categoria/series-legendadas/" to "Séries Legendadas"
        )

        private var cachedRandomTabs: List<Pair<String, String>>? = null
        private var cacheTime: Long = 0
        private const val CACHE_DURATION = 300000L

        fun getCombinedTabs(): List<Pair<String, String>> {
            val currentTime = System.currentTimeMillis()

            if (cachedRandomTabs != null && (currentTime - cacheTime) < CACHE_DURATION) {
                return FIXED_CATEGORIES + cachedRandomTabs!!
            }

            val randomTabs = ALL_RANDOM_CATEGORIES
                .shuffled()
                .take(9)
                .distinctBy { it.first }

            cachedRandomTabs = randomTabs
            cacheTime = currentTime

            return FIXED_CATEGORIES + randomTabs
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
                "$baseUrl?page=$page"
            }
        } else {
            baseUrl
        }

        val document = app.get(url).document

        // ATUALIZADO: Seletor para os cards na página inicial
        val home = document.select("li.post").mapNotNull { element ->
            element.toSearchResult()
        }

        // ATUALIZADO: Verificação de próxima página
        val hasNextPage = document.select("a.next, .pagination .next, a:contains(Próxima)").isNotEmpty()

        return newHomePageResponse(request.name, home.distinctBy { it.url }, hasNextPage)
    }

    // ATUALIZADO: Função para converter elemento em SearchResponse
    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst("a.lnk-blk") ?: return null
        val href = link.attr("href") ?: return null
        val titleElement = selectFirst(".entry-title") ?: return null
        val title = titleElement.text().trim()

        val poster = selectFirst("img")?.attr("src")?.let { fixUrl(it) }
        val year = selectFirst(".year")?.text()?.toIntOrNull()
        
        // Limpa o título (remove ano se tiver)
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()

        // Determina o tipo pela URL
        val isAnime = href.contains("/anime/") || title.contains("(Anime)", ignoreCase = true)
        val isSerie = href.contains("/serie/")
        val isMovie = href.contains("/filme/")

        return when {
            isAnime -> {
                newAnimeSearchResponse(cleanTitle, fixUrl(href), TvType.Anime) {
                    this.posterUrl = poster
                    this.year = year
                }
            }
            isSerie -> {
                newTvSeriesSearchResponse(cleanTitle, fixUrl(href), TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = year
                }
            }
            else -> {
                newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year
                }
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // ATUALIZADO: URL de busca do WordPress
        val searchUrl = "$mainUrl/?s=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document

        return document.select("li.post").mapNotNull { card ->
            try {
                card.toSearchResult()
            } catch (e: Exception) {
                null
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // ATUALIZADO: Título
        val titleElement = document.selectFirst("h1.entry-title")
        val title = titleElement?.text() ?: return null

        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()

        // ATUALIZADO: Detecção de tipo pela URL
        val isAnime = url.contains("/anime/") || title.contains("(Anime)", ignoreCase = true)
        val isSerie = url.contains("/serie/")
        val isMovie = url.contains("/filme/")

        // ATUALIZADO: Poster
        val poster = document.selectFirst(".post-thumbnail img")?.attr("src")?.let { fixUrl(it) }
        
        // ATUALIZADO: Sinopse
        val plot = document.selectFirst(".description p")?.text()
        
        // ATUALIZADO: Tags/Gêneros
        val tags = document.select(".genres a").map { it.text() }.takeIf { it.isNotEmpty() }

        // ATUALIZADO: Duração (para filmes)
        val duration = document.selectFirst(".duration")?.text()?.let {
            Regex("(\\d+)h\\s*(\\d+)?m?").find(it)?.let { match ->
                val hours = match.groupValues[1].toIntOrNull() ?: 0
                val minutes = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
                hours * 60 + minutes
            } ?: Regex("(\\d+) min").find(it)?.groupValues?.get(1)?.toIntOrNull()
        }

        // TMDB (mantido igual)
        val tmdbInfo = if (isAnime || isSerie) {
            searchOnTMDB(cleanTitle, year, true)
        } else {
            searchOnTMDB(cleanTitle, year, false)
        }

        // ATUALIZADO: Recomendações
        val siteRecommendations = document.select(".owl-item .post, section.relacionados .post, aside .post").mapNotNull { element ->
            try {
                element.toSearchResult()
            } catch (e: Exception) {
                null
            }
        }.distinctBy { it.url }

        return if (isSerie || isAnime) {
            // ATUALIZADO: Extração de episódios
            val episodes = extractEpisodesFromSite(document, url)
            
            val type = if (isAnime) TvType.Anime else TvType.TvSeries

            if (tmdbInfo != null) {
                newTvSeriesLoadResponse(tmdbInfo.title ?: cleanTitle, url, type, episodes) {
                    this.posterUrl = tmdbInfo.posterUrl ?: poster
                    this.backgroundPosterUrl = tmdbInfo.backdropUrl
                    this.year = tmdbInfo.year ?: year
                    this.plot = tmdbInfo.overview ?: plot
                    this.tags = tmdbInfo.genres ?: tags
                    tmdbInfo.actors?.let { addActors(it) }
                    tmdbInfo.youtubeTrailer?.let { addTrailer(it) }
                    this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
                }
            } else {
                newTvSeriesLoadResponse(cleanTitle, url, type, episodes) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = plot
                    this.tags = tags
                    this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
                }
            }
        } else {
            // ATUALIZADO: Para filmes, pega o iframe do player
            val iframeSrc = document.selectFirst(".video iframe")?.attr("src")
            val playerUrl = iframeSrc?.let { fixUrl(it) } ?: url

            if (tmdbInfo != null) {
                newMovieLoadResponse(tmdbInfo.title ?: cleanTitle, url, TvType.Movie, playerUrl) {
                    this.posterUrl = tmdbInfo.posterUrl ?: poster
                    this.backgroundPosterUrl = tmdbInfo.backdropUrl
                    this.year = tmdbInfo.year ?: year
                    this.plot = tmdbInfo.overview ?: plot
                    this.tags = tmdbInfo.genres ?: tags
                    this.duration = tmdbInfo.duration ?: duration
                    tmdbInfo.actors?.let { addActors(it) }
                    tmdbInfo.youtubeTrailer?.let { addTrailer(it) }
                    this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
                }
            } else {
                newMovieLoadResponse(cleanTitle, url, TvType.Movie, playerUrl) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = plot
                    this.tags = tags
                    this.duration = duration
                    this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
                }
            }
        }
    }

    // NOVA FUNÇÃO: Extrair episódios de séries
    private suspend fun extractEpisodesFromSite(
        document: org.jsoup.nodes.Document,
        baseUrl: String
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()

        // Os episódios estão em ul#episode_by_temp
        document.select("#episode_by_temp li").forEach { episodeElement ->
            try {
                val link = episodeElement.selectFirst("a.lnk-blk") ?: return@forEach
                val episodeUrl = link.attr("href") ?: return@forEach
                
                val numEpi = episodeElement.selectFirst(".num-epi")?.text() ?: ""
                val title = episodeElement.selectFirst(".entry-title")?.text() ?: "Episódio"
                
                // Extrai temporada e episódio do formato "1x1", "1x2", etc
                val (season, episode) = Regex("(\\d+)x(\\d+)").find(numEpi)?.destructured
                    ?: (1 to (episodes.size + 1))

                val poster = episodeElement.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                
                episodes.add(
                    newEpisode(fixUrl(episodeUrl)) {
                        this.name = title
                        this.season = season
                        this.episode = episode
                        this.posterUrl = poster?.takeIf { !it.contains("noimg-episode") }
                    }
                )
            } catch (e: Exception) {
                // Ignora erro em um episódio e continua
            }
        }

        return episodes.distinctBy { "${it.season}-${it.episode}" }
    }

    // ATUALIZADO: loadLinks - agora recebe URL do episódio ou filme
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Se for URL de episódio, carrega a página do episódio para pegar o iframe
        if (data.contains("/episodio/")) {
            val document = app.get(data).document
            val iframeSrc = document.selectFirst(".video iframe")?.attr("src")
            if (iframeSrc != null) {
                return SuperFlixExtractor.extractVideoLinks(iframeSrc, name, callback)
            }
        }
        
        // Se for URL direta do player ou filme, tenta extrair
        return SuperFlixExtractor.extractVideoLinks(data, name, callback)
    }

    // ========== FUNÇÕES DO TMDB (mantidas IGUAIS) ==========
    
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

    // ========== DATA CLASSES (mantidas IGUAIS) ==========

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
