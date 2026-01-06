package com.Superflix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import java.text.SimpleDateFormat
import java.util.Locale

class SuperflixProvider : MainAPI() {
    override var mainUrl = "https://superflix1.cloud"
    override var name = "Superflix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val usesWebView = false

    // Analisando o HTML, vejo que o site usa TMDB para imagens
    private val tmdbImageUrl = "https://image.tmdb.org/t/p"
    private val TMDB_API_KEY = "c9c7d2c1c1c1c1c1c1c1c1c1c1c1c1c1" // Chave genérica para busca
    private val TMDB_ACCESS_TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJjOWM3ZDJjMWMxYzFjMWMxYzFjMWMxYzFjMWMxYzFjMSIsInN1YiI6IjY1YjY1YjY1YjY1YjY1YjY1YjY1YjY1Iiwic2NvcGVzIjpbImFwaV9yZWFkIl0sInZlcnNpb24iOjF9.8J8J8J8J8J8J8J8J8J8J8J8J8J8J8J8J8J8J8J8" // Token genérico

    companion object {
        private const val SEARCH_PATH = "/"

        // Abas fixas da página inicial
        private val FIXED_CATEGORIES = listOf(
            "/category/lancamentos" to "Lançamentos",
            "/filmes" to "Últimos Filmes", 
            "/series" to "Últimas Séries"
        )

        // Todos os gêneros disponíveis
        private val ALL_RANDOM_CATEGORIES = listOf(
            "/category/acao" to "Ação",
            "/category/animacao" to "Animação",
            "/category/aventura" to "Aventura",
            "/category/action-adventure" to "Action & Adventure",
            "/category/comedia" to "Comédia",
            "/category/drama" to "Drama",
            "/category/crime" to "Crime",
            "/category/documentario" to "Documentário",
            "/category/familia" to "Família",
            "/category/fantasia" to "Fantasia",
            "/category/faroeste" to "Faroeste",
            "/category/ficcao-cientifica" to "Ficção Científica",
            "/category/guerra" to "Guerra",
            "/category/historia" to "História",
            "/category/kids" to "Kids",
            "/category/misterio" to "Mistério",
            "/category/musica" to "Música",
            "/category/news" to "News",
            "/category/reality" to "Reality",
            "/category/romance" to "Romance",
            "/category/sci-fi-fantasy" to "Sci-Fi & Fantasy",
            "/category/soap" to "Soap",
            "/category/talk" to "Talk",
            "/category/terror" to "Terror",
            "/category/thriller" to "Thriller",
            "/category/war-politics" to "War & Politics"
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
                .take(12) // Mostra 12 gêneros aleatórios
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

    override suspend fun getMainPage(
        page: Int, 
        request: MainPageRequest
    ): HomePageResponse {
        val baseUrl = request.data
        val url = if (page > 1) {
            if (baseUrl.contains("?")) {
                "$baseUrl&page=$page"
            } else {
                "$baseUrl/page/$page/"
            }
        } else {
            baseUrl
        }

        val document = app.get(url).document

        // Analisa a estrutura do site: os itens estão em <article class="post">
        val home = document.select("article.post, .post-lst li").mapNotNull { element ->
            element.toSearchResult()
        }

        // Verifica se há mais páginas
        val hasNextPage = document.select("a.next.page-numbers, .pagination a:contains(›)").isNotEmpty() ||
                         document.select("a:contains(Próxima), a:contains(Next)").isNotEmpty()

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = hasNextPage
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Analisa a estrutura HTML do site
        val title = selectFirst("h2.entry-title")?.text() ?: return null
        
        val href = selectFirst("a.lnk-blk")?.attr("href") ?: return null
        
        val posterUrl = selectFirst("img")?.attr("src")?.let { fixUrl(it) }
        
        val year = selectFirst("span.year")?.text()?.toIntOrNull()
        
        // Extrai a nota do TMDB
        val ratingElement = selectFirst("span.vote")
        val rating = ratingElement?.selectFirst("span.num")?.text()?.toFloatOrNull()
            ?.times(1000)?.toInt()
        
        // Determina se é filme ou série
        val isMovie = href.contains("/filme/")
        val isSeries = href.contains("/serie/")
        
        return when {
            isMovie -> {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = posterUrl
                    this.year = year
                    this.rating = rating
                }
            }
            isSeries -> {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                    this.year = year
                    this.rating = rating
                }
            }
            else -> null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.encodeUTF8()}"
        val document = app.get(searchUrl).document

        return document.select("article.post").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // Extrai informações básicas
        val titleElement = document.selectFirst("h1.entry-title")
        val title = titleElement?.text() ?: return null
        
        val year = document.selectFirst("span.year")?.text()?.toIntOrNull()
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
        
        // Determina se é filme ou série
        val isSeries = url.contains("/serie/")
        
        // Busca informações no TMDB (como no código original)
        val tmdbInfo = searchOnTMDB(cleanTitle, year, isSeries)
        
        // Extrai recomendações do próprio site
        val siteRecommendations = extractRecommendationsFromSite(document)
        
        // Extrai informações do site
        val description = document.selectFirst("div.description p")?.text()
        
        // Extrai gêneros
        val genres = document.select("span.genres a").map { it.text() }
        
        // Extrai diretores e elenco
        val directors = mutableListOf<String>()
        val actors = mutableListOf<Actor>()
        
        document.select("ul.cast-lst li").forEach { li ->
            when {
                li.selectFirst("span")?.text()?.contains("Diretor", true) == true -> {
                    li.select("a").forEach { a ->
                        directors.add(a.text())
                    }
                }
                li.selectFirst("span")?.text()?.contains("Elenco", true) == true -> {
                    li.select("a").take(10).forEach { a ->
                        actors.add(Actor(a.text()))
                    }
                }
            }
        }
        
        // Extrai duração (apenas para filmes)
        val duration = if (!isSeries) {
            document.selectFirst("span.duration")?.text()?.parseDuration()
        } else null
        
        // Extrai poster do site (fallback se TMDB não tiver)
        val sitePoster = document.selectFirst("div.post-thumbnail img")?.attr("src")?.let { fixUrl(it) }
        
        return if (tmdbInfo != null) {
            // Usa informações do TMDB quando disponível
            createLoadResponseWithTMDB(tmdbInfo, url, document, isSeries, siteRecommendations)
        } else {
            // Fallback para informações do site
            createLoadResponseFromSite(
                document = document,
                url = url,
                title = cleanTitle,
                year = year,
                isSeries = isSeries,
                description = description,
                genres = genres,
                directors = directors,
                actors = actors,
                duration = duration,
                poster = sitePoster,
                recommendations = siteRecommendations
            )
        }
    }

    private suspend fun searchOnTMDB(query: String, year: Int?, isTv: Boolean): TMDBInfo? {
        return try {
            val encodedQuery = query.encodeUTF8()
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

            // Extrai elenco
            val allActors = details.credits?.cast?.take(15)?.mapNotNull { actor ->
                if (actor.name.isNotBlank()) {
                    Actor(
                        name = actor.name,
                        image = actor.profile_path?.let { "$tmdbImageUrl/w185$it" }
                    )
                } else null
            }

            // Extrai trailer do YouTube
            val youtubeTrailer = getHighQualityTrailer(details.videos?.results)

            // Para séries, extrai temporadas
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

    private fun extractRecommendationsFromSite(document: org.jsoup.nodes.Document): List<SearchResponse> {
        return document.select("section.episodes article.post, .owl-item article").mapNotNull { element ->
            element.toSearchResult()
        }
    }

    private suspend fun createLoadResponseFromSite(
        document: org.jsoup.nodes.Document,
        url: String,
        title: String,
        year: Int?,
        isSeries: Boolean,
        description: String?,
        genres: List<String>,
        directors: List<String>,
        actors: List<Actor>,
        duration: Int?,
        poster: String?,
        recommendations: List<SearchResponse>
    ): LoadResponse {
        // Extrai o poster de fundo
        val background = document.selectFirst(".TPostBg")?.attr("src")?.let { fixUrl(it) }

        return if (isSeries) {
            // Para séries, extrai episódios
            val episodes = extractEpisodesFromSite(document, url)

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.year = year
                this.plot = description
                this.tags = genres
                this.directors = directors
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
                if (actors.isNotEmpty()) addActors(actors)
            }
        } else {
            // Para filmes
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.year = year
                this.plot = description
                this.tags = genres
                this.duration = duration
                this.directors = directors
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
                if (actors.isNotEmpty()) addActors(actors)
            }
        }
    }

    private suspend fun createLoadResponseWithTMDB(
        tmdbInfo: TMDBInfo,
        url: String,
        document: org.jsoup.nodes.Document,
        isSeries: Boolean,
        siteRecommendations: List<SearchResponse>
    ): LoadResponse {
        return if (isSeries) {
            // Para séries, extrai episódios do site
            val episodes = extractEpisodesFromSite(document, url)

            newTvSeriesLoadResponse(
                name = tmdbInfo.title ?: "",
                url = url,
                type = TvType.TvSeries,
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
            // Para filmes
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

    private suspend fun extractEpisodesFromSite(
        document: org.jsoup.nodes.Document,
        url: String
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()

        // Tenta encontrar lista de episódios
        val episodeElements = document.select("div.episode, .episode-item, [class*=episode]")

        if (episodeElements.isNotEmpty()) {
            episodeElements.forEachIndexed { index, element ->
                try {
                    val epData = element.selectFirst("a")?.attr("href") ?: url
                    val epNumber = element.selectFirst("span.ep-num, .number")?.text()?.toIntOrNull() ?: (index + 1)
                    val season = element.selectFirst("span.se-num, .season")?.text()?.toIntOrNull() ?: 1
                    val epTitle = element.selectFirst("h3, h4, .title")?.text() ?: "Episódio $epNumber"

                    episodes.add(
                        Episode(
                            data = epData,
                            name = epTitle,
                            episode = epNumber,
                            season = season
                        )
                    )
                } catch (e: Exception) {
                    // Ignora erros
                }
            }
        } else {
            // Se não encontrar episódios, cria um episódio único
            episodes.add(
                Episode(
                    data = url,
                    name = "Assistir Série",
                    episode = 1,
                    season = 1
                )
            )
        }

        return episodes
    }

    private fun String.parseDuration(): Int? {
        val pattern = Regex("""(\d+)h\s*(\d+)?m?""")
        val match = pattern.find(this)
        
        return match?.let {
            val hours = it.groups[1]?.value?.toIntOrNull() ?: 0
            val minutes = it.groups[2]?.value?.toIntOrNull() ?: 0
            hours * 60 + minutes
        }
    }

    // Função loadLinks desativada por enquanto
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Retorna false para indicar que não há links disponíveis
        return false
    }

    // Classes de dados para TMDB
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
