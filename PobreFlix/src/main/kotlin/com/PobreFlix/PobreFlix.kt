package com.SuperFlixAPI

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.fasterxml.jackson.annotation.JsonProperty
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat

@CloudstreamPlugin
class SuperFlixProvider : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SuperFlix())
    }
}

class SuperFlix : MainAPI() {
    override var mainUrl = "https://superflixapi.rest"
    override var name = "SuperFlixAPI"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)
    override val usesWebView = false

    private val tmdbImageUrl = "https://image.tmdb.org/t/p"
    private val TMDB_API_KEY = BuildConfig.TMDB_API_KEY
    private val TMDB_ACCESS_TOKEN = BuildConfig.TMDB_ACCESS_TOKEN

    companion object {
        private const val SEARCH_PATH = "/pesquisar"  // ← ADICIONADO
        
        private val MAIN_SECTIONS = listOf(
            "/filmes" to "Filmes",
            "/series" to "Séries",
            "/animes" to "Animes",
            "/doramas" to "Doramas"
        )
    }

    override val mainPage = mainPageOf(
        *MAIN_SECTIONS.map { (path, name) ->
            "$mainUrl$path" to name
        }.toTypedArray()
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        println("=== getMainPage INICIADO ===")
        println("Page: $page")
        println("Request name: ${request.name}")
        println("Request data: ${request.data}")
        
        var url = request.data
        
        // Construção da URL com paginação
        if (page > 1) {
            url = if (url.contains("?")) {
                "$url&page=$page"
            } else {
                "$url?page=$page"
            }
            println("URL com paginação: $url")
        }
        
        println(">>> Processando seção: ${request.name}")
        val document = app.get(url).document
        println("URL carregada: $url")
        
        // Seletor correto para os cards do SuperFlix
        val elements = document.select(".grid .group\\/card")
        println("Elementos encontrados: ${elements.size}")
        
        val items = elements.mapNotNull { element ->
            element.toSearchResult()
        }
        println("Items processados: ${items.size}")
        
        // Verifica se tem próxima página
        val hasNextPage = document.select("a[href*='page=']").any { link ->
            val href = link.attr("href")
            href.contains("page=${page + 1}") || href.contains("&page=${page + 1}")
        } || document.select("a:contains(Próxima), .pagination a:contains(Próxima)").isNotEmpty()
        
        println("Has next page: $hasNextPage")
        
        return newHomePageResponse(request.name, items, hasNext = hasNextPage)
    }
    
    private fun Element.toSearchResult(): SearchResponse? {
        println("  >>> toSearchResult")
        
        val linkElement = selectFirst("a[href]") ?: return null
        var href = linkElement.attr("href")
        if (href.isBlank()) return null
        
        if (!href.startsWith("http")) {
            href = if (href.startsWith("/")) "$mainUrl$href" else "$mainUrl/$href"
        }
        println("  href: $href")
        
        val imgElement = selectFirst("img")
        var poster: String? = null
        
        if (imgElement != null) {
            poster = imgElement.attr("data-src")
            if (poster.isNullOrBlank()) poster = imgElement.attr("src")
            if (!poster.isNullOrBlank()) {
                // Remove o prefixo do CDN se existir
                if (poster.contains("d1muf25xaso8hp.cloudfront.net/")) {
                    poster = poster.substringAfter("d1muf25xaso8hp.cloudfront.net/")
                }
                poster = fixUrl(poster)
            }
            println("  poster: $poster")
        }
        
        // Extrai título do alt da imagem ou do h3
        var title = imgElement?.attr("alt")
        if (title.isNullOrBlank()) title = selectFirst("h3")?.text()
        println("  Título original: '$title'")
        
        // Extrai idioma (DUB/LEG)
        var isDubbed = false
        val langElement = selectFirst(".absolute.bottom-2.left-2 .inline-flex")
        if (langElement != null) {
            val langText = langElement.text()
            isDubbed = langText.contains("DUB", ignoreCase = true)
            println("  Idioma: '$langText', isDubbed: $isDubbed")
        }
        
        // Extrai score
        var scoreValue: Float? = null
        val scoreElement = selectFirst(".absolute.top-2.right-2 svg text")
        if (scoreElement != null) {
            val scoreText = scoreElement.text().replace("%", "").trim()
            scoreValue = scoreText.toFloatOrNull()
            println("  Score: $scoreText%")
        }
        
        // Extrai TMDB ID da URL
        val tmdbIdMatch = Regex("/(filme|serie|anime|dorama)/(\\d+)").find(href)
        val tmdbId = tmdbIdMatch?.groupValues?.get(2)?.toIntOrNull()
        println("  TMDB ID: $tmdbId")
        
        if (title.isNullOrBlank()) {
            println("  ERRO: título em branco")
            return null
        }
        
        val cleanedTitle = title!!.replace(Regex("\\s+poster$", RegexOption.IGNORE_CASE), "").trim()
        val year = Regex("\\((\\d{4})\\)").find(cleanedTitle)?.groupValues?.get(1)?.toIntOrNull()
        val finalTitle = cleanedTitle.replace(Regex("\\(\\d{4}\\)"), "").trim()
        
        // Determina o tipo baseado na URL
        val isAnime = href.contains("/anime/")
        val isSerie = href.contains("/serie/") || href.contains("/dorama/")
        
        println("  Tipo final: isAnime=$isAnime, isSerie=$isSerie")
        println("  Título final: '$finalTitle'")
        
        val result = when {
            isAnime -> {
                println("  >>> Criando ANIME")
                newAnimeSearchResponse(finalTitle, href, TvType.Anime) {
                    this.posterUrl = poster
                    this.year = year
                    if (scoreValue != null) this.score = Score.from10(scoreValue / 10)
                }
            }
            isSerie -> {
                println("  >>> Criando SÉRIE")
                newTvSeriesSearchResponse(finalTitle, href, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = year
                    if (scoreValue != null) this.score = Score.from10(scoreValue / 10)
                }
            }
            else -> {
                println("  >>> Criando FILME")
                newMovieSearchResponse(finalTitle, href, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year
                    if (scoreValue != null) this.score = Score.from10(scoreValue / 10)
                }
            }
        }
        
        // Adiciona status de dublagem para animes
        if (result is AnimeSearchResponse) {
            result.addDubStatus(if (isDubbed) DubStatus.Dubbed else DubStatus.Subbed, null)
        }
        
        println("  <<< toSearchResult FINALIZADO")
        return result
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()
        
        // URL de busca correta do SuperFlix
        val searchUrl = "$mainUrl$SEARCH_PATH?s=${URLEncoder.encode(query, "UTF-8")}"
        println("Search URL: $searchUrl")
        
        val document = app.get(searchUrl).document
        
        return document.select(".grid .group\\/card")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        println("=== load INICIADO ===")
        println("URL: $url")
        
        // Extrai o tipo e ID da URL
        val typeMatch = Regex("/(filme|serie|anime|dorama)/(\\d+)").find(url)
        val type = typeMatch?.groupValues?.get(1)
        val tmdbId = typeMatch?.groupValues?.get(2)?.toIntOrNull()
        
        if (tmdbId == null) {
            println("ERRO: Não foi possível extrair TMDB ID da URL")
            return null
        }
        
        println("TMDB ID: $tmdbId, Tipo: $type")
        
        // Determina se é série ou filme
        val isTv = type in listOf("serie", "anime", "dorama")
        
        // Busca dados no TMDB
        val tmdbInfo = getTMDBInfo(tmdbId, isTv) ?: return null
        
        val title = tmdbInfo.title ?: "Título não encontrado"
        val year = tmdbInfo.year
        val posterUrl = tmdbInfo.posterUrl
        val backdropUrl = tmdbInfo.backdropUrl
        val plot = tmdbInfo.overview
        val genres = tmdbInfo.genres
        val rating = tmdbInfo.rating?.let { Score.from10(it) }
        val duration = tmdbInfo.duration
        
        // Extrai episódios se for série
        val episodes = if (isTv) {
            tmdbInfo.seasonsEpisodes?.let { episodesMap ->
                buildEpisodesFromTMDB(episodesMap)
            } ?: emptyList()
        } else {
            emptyList()
        }
        
        return if (isTv) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = backdropUrl
                this.year = year
                this.plot = plot
                this.tags = genres
                this.score = rating
                
                tmdbInfo.actors?.let { actors ->
                    addActors(actors.map { Actor(name = it.name, image = it.profilePath) })
                }
                
                tmdbInfo.youtubeTrailer?.let { trailerUrl ->
                    addTrailer(trailerUrl)
                }
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = backdropUrl
                this.year = year
                this.plot = plot
                this.tags = genres
                this.duration = duration
                this.score = rating
                
                tmdbInfo.actors?.let { actors ->
                    addActors(actors.map { Actor(name = it.name, image = it.profilePath) })
                }
                
                tmdbInfo.youtubeTrailer?.let { trailerUrl ->
                    addTrailer(trailerUrl)
                }
            }
        }
    }
    
    private fun buildEpisodesFromTMDB(seasonsEpisodes: Map<Int, List<TMDBEpisode>>): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        seasonsEpisodes.forEach { (seasonNum, episodeList) ->
            episodeList.forEach { ep ->
                val episodeUrl = ep.episode_url ?: ""
                val episodeName = ep.name ?: "Episódio ${ep.episode_number}"
                val episodeDesc = buildString {
                    if (ep.overview != null) append(ep.overview)
                    if (ep.runtime != null && ep.runtime > 0) {
                        if (ep.overview != null) append("\n\n")
                        append("Duração: ${ep.runtime}min")
                    }
                }.trim()
                
                episodes.add(newEpisode(episodeUrl) {
                    this.name = episodeName
                    this.season = seasonNum
                    this.episode = ep.episode_number
                    this.posterUrl = ep.still_path?.let { "$tmdbImageUrl/w300$it" }
                    this.description = episodeDesc.takeIf { it.isNotEmpty() }
                    
                    ep.air_date?.let { airDate ->
                        try {
                            val dateFormatter = SimpleDateFormat("yyyy-MM-dd")
                            val date = dateFormatter.parse(airDate)
                            this.date = date.time
                        } catch (e: Exception) { }
                    }
                })
            }
        }
        
        return episodes.sortedWith(compareBy({ it.season }, { it.episode }))
    }
    
    private suspend fun getTMDBInfo(id: Int, isTv: Boolean): TMDBInfo? {
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
            
            val response = app.get(url, headers = headers, timeout = 15_000)
            
            if (response.code != 200) {
                println("TMDB API error: ${response.code}")
                return null
            }
            
            val details = response.parsedSafe<TMDBDetailsResponse>() ?: return null
            
            // Extrai atores
            val actors = details.credits?.cast?.take(15)?.mapNotNull { actor ->
                if (actor.name.isNotBlank()) {
                    TMDBPerson(
                        name = actor.name,
                        profilePath = actor.profile_path?.let { "$tmdbImageUrl/w185$it" }
                    )
                } else null
            }
            
            // Extrai trailer
            val youtubeTrailer = details.videos?.results?.firstOrNull { 
                it.site == "YouTube" && (it.type == "Trailer" || it.type == "Teaser")
            }?.let { "https://www.youtube.com/watch?v=${it.key}" }
            
            // Extrai episódios se for série
            val seasonsEpisodes = if (isTv) {
                getTMDBSeasonsEpisodes(id)
            } else {
                null
            }
            
            TMDBInfo(
                title = if (isTv) details.name else details.title,
                year = if (isTv) {
                    details.first_air_date?.substring(0, 4)?.toIntOrNull()
                } else {
                    details.release_date?.substring(0, 4)?.toIntOrNull()
                },
                posterUrl = details.poster_path?.let { "$tmdbImageUrl/w500$it" },
                backdropUrl = details.backdrop_path?.let { "$tmdbImageUrl/original$it" },
                overview = details.overview,
                genres = details.genres?.map { it.name },
                actors = actors,
                youtubeTrailer = youtubeTrailer,
                duration = if (!isTv) details.runtime else null,
                rating = details.vote_average?.takeIf { it > 0 },
                seasonsEpisodes = seasonsEpisodes
            )
        } catch (e: Exception) {
            println("Erro ao buscar TMDB: ${e.message}")
            e.printStackTrace()
            null
        }
    }
    
    private suspend fun getTMDBSeasonsEpisodes(seriesId: Int): Map<Int, List<TMDBEpisode>> {
        val result = mutableMapOf<Int, List<TMDBEpisode>>()
        
        try {
            val headers = mapOf(
                "Authorization" to "Bearer $TMDB_ACCESS_TOKEN",
                "accept" to "application/json"
            )
            
            // Primeiro pega as informações da série para saber as temporadas
            val seriesUrl = "https://api.themoviedb.org/3/tv/$seriesId?api_key=$TMDB_API_KEY&language=pt-BR"
            val seriesResponse = app.get(seriesUrl, headers = headers, timeout = 15_000)
            
            if (seriesResponse.code != 200) return emptyMap()
            
            val seriesDetails = seriesResponse.parsedSafe<TMDBTVDetailsResponse>() ?: return emptyMap()
            
            // Para cada temporada, busca os episódios
            for (season in seriesDetails.seasons) {
                val seasonNum = season.season_number
                if (seasonNum > 0 && season.episode_count > 0) {
                    val seasonUrl = "https://api.themoviedb.org/3/tv/$seriesId/season/$seasonNum?api_key=$TMDB_API_KEY&language=pt-BR"
                    val seasonResponse = app.get(seasonUrl, headers = headers, timeout = 15_000)
                    
                    if (seasonResponse.code == 200) {
                        val seasonData = seasonResponse.parsedSafe<TMDBSeasonResponse>()
                        seasonData?.episodes?.let { episodes ->
                            // Adiciona URL do episódio
                            val episodesWithUrl = episodes.map { ep ->
                                ep.copy(episode_url = "$mainUrl/serie/$seriesId/$seasonNum/${ep.episode_number}")
                            }
                            result[seasonNum] = episodesWithUrl
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("Erro ao buscar episódios: ${e.message}")
        }
        
        return result
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
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = playerUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = playerUrl
                            this.quality = 720
                        }
                    )
                    true
                }
            } else {
                val videoUrl = document.selectFirst("video source, source[src]")?.attr("src")
                if (videoUrl != null) {
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = videoUrl,
                            type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else null
                        ) {
                            this.referer = data
                            this.quality = 720
                        }
                    )
                    true
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            println("Erro ao carregar links: ${e.message}")
            false
        }
    }
    
    // Data classes para TMDB
    private data class TMDBInfo(
        val title: String?,
        val year: Int?,
        val posterUrl: String?,
        val backdropUrl: String?,
        val overview: String?,
        val genres: List<String>?,
        val actors: List<TMDBPerson>?,
        val youtubeTrailer: String?,
        val duration: Int?,
        val rating: Double?,
        val seasonsEpisodes: Map<Int, List<TMDBEpisode>>? = null
    )
    
    private data class TMDBPerson(
        val name: String,
        val profilePath: String?
    )
    
    private data class TMDBDetailsResponse(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("poster_path") val poster_path: String?,
        @JsonProperty("backdrop_path") val backdrop_path: String?,
        @JsonProperty("release_date") val release_date: String? = null,
        @JsonProperty("first_air_date") val first_air_date: String? = null,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("genres") val genres: List<TMDBGenre>?,
        @JsonProperty("credits") val credits: TMDBCredits?,
        @JsonProperty("videos") val videos: TMDBVideos?,
        @JsonProperty("vote_average") val vote_average: Double?
    )
    
    private data class TMDBGenre(
        @JsonProperty("name") val name: String
    )
    
    private data class TMDBCredits(
        @JsonProperty("cast") val cast: List<TMDBCast>
    )
    
    private data class TMDBCast(
        @JsonProperty("name") val name: String,
        @JsonProperty("profile_path") val profile_path: String?
    )
    
    private data class TMDBVideos(
        @JsonProperty("results") val results: List<TMDBVideo>
    )
    
    private data class TMDBVideo(
        @JsonProperty("key") val key: String,
        @JsonProperty("site") val site: String,
        @JsonProperty("type") val type: String
    )
    
    private data class TMDBTVDetailsResponse(
        @JsonProperty("seasons") val seasons: List<TMDBSeasonInfo>
    )
    
    private data class TMDBSeasonInfo(
        @JsonProperty("season_number") val season_number: Int,
        @JsonProperty("episode_count") val episode_count: Int
    )
    
    private data class TMDBSeasonResponse(
        @JsonProperty("episodes") val episodes: List<TMDBEpisode>
    )
    
    private data class TMDBEpisode(
        @JsonProperty("episode_number") val episode_number: Int,
        @JsonProperty("name") val name: String?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("still_path") val still_path: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("air_date") val air_date: String?,
        val episode_url: String? = null
    )
}
