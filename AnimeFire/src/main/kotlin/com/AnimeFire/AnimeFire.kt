package com.AnimeFire

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import java.text.SimpleDateFormat
import java.net.URLEncoder

class AnimeFire : MainAPI() {
    override var mainUrl = "https://animefire.plus"
    override var name = "AnimeFire"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)
    override val usesWebView = true

    // ============ PROXY TMDB ============
    private val TMDB_PROXY_URL = "https://lawliet.euluan1912.workers.dev"
    private val tmdbImageUrl = "https://image.tmdb.org/t/p"

    // APENAS 4 ABAS DA PÁGINA INICIAL
    override val mainPage = mainPageOf(
        "$mainUrl" to "Lançamentos",
        "$mainUrl" to "Destaques da Semana",
        "$mainUrl" to "Últimos Animes Adicionados",
        "$mainUrl" to "Últimos Episódios Adicionados"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        
        val homeItems = when (request.name) {
            "Lançamentos" -> extractLancamentos(document)
            "Destaques da Semana" -> extractDestaquesSemana(document)
            "Últimos Animes Adicionados" -> extractUltimosAnimes(document)
            "Últimos Episódios Adicionados" -> extractUltimosEpisodios(document)
            else -> emptyList()
        }
        
        return newHomePageResponse(request.name, homeItems.distinctBy { it.url })
    }

    // 1. LANÇAMENTOS
    private fun extractLancamentos(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        
        val titleElement = document.selectFirst("h1.section2:contains(Em lançamento)")
        titleElement?.let { title ->
            val carousel = title.parent()?.nextElementSibling()?.selectFirst(".owl-carousel-home")
            carousel?.select(".divArticleLancamentos a.item")?.forEach { item ->
                item.toSearchResult()?.let { items.add(it) }
            }
        }
        
        return items.take(15).distinctBy { it.url }
    }

    // 2. DESTAQUES DA SEMANA
    private fun extractDestaquesSemana(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        
        val titleElement = document.selectFirst("div.divSection:nth-child(4) > h1.section2:contains(Destaques da semana)")
        titleElement?.let { title ->
            val carousel = title.parent()?.nextElementSibling()?.selectFirst(".owl-carousel-semana")
            carousel?.select(".divArticleLancamentos a.item")?.forEach { item ->
                item.toSearchResult()?.let { items.add(it) }
            }
        }
        
        return items.take(15).distinctBy { it.url }
    }

    // 3. ÚLTIMOS ANIMES ADICIONADOS
    private fun extractUltimosAnimes(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        
        val titleElement = document.selectFirst("div.divSection:nth-child(6) > h1.section2:contains(Últimos animes adicionados)")
        titleElement?.let { title ->
            val carousel = title.parent()?.nextElementSibling()?.selectFirst(".owl-carousel-l_dia")
            carousel?.select(".divArticleLancamentos a.item")?.forEach { item ->
                item.toSearchResult()?.let { items.add(it) }
            }
        }
        
        return items.take(15).distinctBy { it.url }
    }

    // 4. ÚLTIMOS EPISÓDIOS ADICIONADOS
    private fun extractUltimosEpisodios(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        
        val titleElement = document.selectFirst("div.divSectionUltimosEpsHome:nth-child(3) > h2.section2:contains(Últimos episódios adicionados)")
        titleElement?.let { title ->
            val container = title.parent()?.nextElementSibling()?.selectFirst(".row")
            container?.select(".divCardUltimosEpsHome")?.forEach { card ->
                card.toEpisodeSearchResult()?.let { items.add(it) }
            }
        }
        
        return items.take(20).distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("href") ?: return null
        if (href.isBlank()) return null
        
        val titleElement = selectFirst("h3.animeTitle") ?: return null
        val title = titleElement.text().trim()
        
        val imgElement = selectFirst("img.imgAnimes, img.owl-lazy")
        val poster = when {
            imgElement?.hasAttr("data-src") == true -> imgElement.attr("data-src")
            imgElement?.hasAttr("src") == true -> imgElement.attr("src")
            else -> selectFirst("img")?.attr("src")
        } ?: return null
        
        val cleanTitle = title.replace(Regex("(?i)(dublado|legendado|todos os episódios|\\(\\d{4}\\))$"), "").trim()
        
        val isMovie = href.contains("/filmes/") || title.contains("Movie", ignoreCase = true)
        
        return if (isMovie) {
            newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                this.posterUrl = fixUrl(poster)
            }
        } else {
            newAnimeSearchResponse(cleanTitle, fixUrl(href), TvType.Anime) {
                this.posterUrl = fixUrl(poster)
            }
        }
    }

    private fun Element.toEpisodeSearchResult(): SearchResponse? {
        val link = selectFirst("article.card a") ?: return null
        val href = link.attr("href") ?: return null
        if (href.isBlank()) return null
        
        val titleElement = selectFirst("h3.animeTitle") ?: return null
        val title = titleElement.text().trim()
        
        val epNumberElement = selectFirst(".numEp")
        val epNumber = epNumberElement?.text()?.toIntOrNull() ?: 1
        
        val imgElement = selectFirst("img.imgAnimesUltimosEps, img.transitioning_src")
        val poster = when {
            imgElement?.hasAttr("data-src") == true -> imgElement.attr("data-src")
            imgElement?.hasAttr("src") == true -> imgElement.attr("src")
            else -> selectFirst("img")?.attr("src")
        } ?: return null
        
        val cleanTitle = "${title} - Episódio $epNumber"
        
        return newAnimeSearchResponse(cleanTitle, fixUrl(href), TvType.Anime) {
            this.posterUrl = fixUrl(poster)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl$SEARCH_PATH/${URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document

        return document.select("article.containerAnimes a.item").mapNotNull { element ->
            try {
                element.toSearchResult()
            } catch (e: Exception) {
                null
            }
        }.take(30)
    }

    override suspend fun load(url: String): LoadResponse? {
        println("🔍 [DEBUG] AnimeFire: Iniciando load para URL: $url")

        val document = app.get(url).document

        // Título do site
        val titleElement = document.selectFirst("h1.quicksand400, .main_div_anime_info h1, h1") ?: return null
        val title = titleElement.text().trim()
        
        println("🔍 [DEBUG] AnimeFire: Título encontrado: $title")

        // Extrair ano do título
        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
        println("🔍 [DEBUG] AnimeFire: Título limpo: $cleanTitle | Ano: $year")

        // Determinar se é anime ou filme
        val isAnime = url.contains("/animes/") || !url.contains("/filmes/")
        val isMovie = url.contains("/filmes/") || title.contains("Movie", ignoreCase = true)
        val isTv = isAnime && !isMovie
        
        println("🔍 [DEBUG] AnimeFire: Tipo - Anime: $isAnime, Movie: $isMovie, TV: $isTv")

        // Buscar no TMDB
        println("🔍 [DEBUG] AnimeFire: Buscando no TMDB...")
        val tmdbInfo = if (isTv) {
            searchOnTMDB(cleanTitle, year, true)
        } else {
            searchOnTMDB(cleanTitle, year, false)
        }

        if (tmdbInfo == null) {
            println("⚠️ [DEBUG] AnimeFire: TMDB não retornou informações!")
        } else {
            println("✅ [DEBUG] AnimeFire: TMDB OK! Título: ${tmdbInfo.title}, Ano: ${tmdbInfo.year}")
            println("✅ [DEBUG] AnimeFire: Poster: ${tmdbInfo.posterUrl}")
            println("✅ [DEBUG] AnimeFire: Atores: ${tmdbInfo.actors?.size ?: 0}")
            println("✅ [DEBUG] AnimeFire: Trailer: ${tmdbInfo.youtubeTrailer}")
        }

        // Extrair recomendações do site
        val siteRecommendations = extractRecommendationsFromSite(document)

        return if (tmdbInfo != null) {
            println("✅ [DEBUG] AnimeFire: Criando resposta COM TMDB")
            createLoadResponseWithTMDB(tmdbInfo, url, document, isAnime, isMovie, siteRecommendations)
        } else {
            println("⚠️ [DEBUG] AnimeFire: Criando resposta APENAS com dados do site")
            createLoadResponseFromSite(document, url, cleanTitle, year, isAnime, isMovie)
        }
    }

    // ============ FUNÇÕES TMDB (IGUAL AO SUPERFLIX) ============

    private suspend fun searchOnTMDB(query: String, year: Int?, isTv: Boolean): TMDBInfo? {
        println("🔍 [TMDB DEBUG] AnimeFire: Buscando no TMDB")
        println("🔍 [TMDB DEBUG] Query: $query | Ano: $year | Tipo: ${if (isTv) "TV" else "Movie"}")

        return try {
            val type = if (isTv) "tv" else "movie"
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val yearParam = year?.let { "&year=$it" } ?: ""

            val searchUrl = "$TMDB_PROXY_URL/search?query=$encodedQuery&type=$type$yearParam"
            println("🔗 [TMDB DEBUG] URL: $searchUrl")

            val response = app.get(searchUrl, timeout = 10_000)
            println("📡 [TMDB DEBUG] Status: ${response.code}")

            if (response.code != 200) return null

            val searchResult = response.parsedSafe<TMDBSearchResponse>() ?: return null
            println("✅ [TMDB DEBUG] Resultados: ${searchResult.results.size}")

            val result = searchResult.results.firstOrNull() ?: return null

            // Buscar detalhes completos
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

            // Temporadas para séries
            val seasonsEpisodes = if (isTv) {
                println("🔍 [TMDB DEBUG] Buscando temporadas...")
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
            println("❌ [TMDB DEBUG] AnimeFire ERRO: ${e.message}")
            null
        }
    }

    private suspend fun getTMDBAllSeasons(seriesId: Int): Map<Int, List<TMDBEpisode>> {
        println("🔍 [TMDB DEBUG] AnimeFire: Buscando temporadas para ID: $seriesId")

        return try {
            val seriesDetailsUrl = "$TMDB_PROXY_URL/tv/$seriesId"
            val seriesResponse = app.get(seriesDetailsUrl, timeout = 10_000)

            if (seriesResponse.code != 200) {
                return emptyMap()
            }

            val seriesDetails = seriesResponse.parsedSafe<TMDBTVDetailsResponse>() ?: return emptyMap()

            val seasonsEpisodes = mutableMapOf<Int, List<TMDBEpisode>>()

            for (season in seriesDetails.seasons) {
                if (season.season_number > 0) {
                    val seasonNumber = season.season_number
                    val seasonUrl = "$TMDB_PROXY_URL/tv/$seriesId/season/$seasonNumber"
                    
                    val seasonResponse = app.get(seasonUrl, timeout = 10_000)
                    
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
            println("❌ [TMDB DEBUG] AnimeFire ERRO temporadas: ${e.message}")
            emptyMap()
        }
    }

    private suspend fun getTMDBDetails(id: Int, isTv: Boolean): TMDBDetailsResponse? {
        return try {
            val type = if (isTv) "tv" else "movie"
            val url = "$TMDB_PROXY_URL/$type/$id"
            
            val response = app.get(url, timeout = 10_000)
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

    // ============ FUNÇÕES DO SITE ============

    private fun extractRecommendationsFromSite(document: org.jsoup.nodes.Document): List<SearchResponse> {
        return document.select(".owl-carousel-anime .divArticleLancamentos a.item").mapNotNull { element ->
            try {
                element.toSearchResult()
            } catch (e: Exception) {
                null
            }
        }
    }

    private suspend fun createLoadResponseFromSite(
        document: org.jsoup.nodes.Document,
        url: String,
        title: String,
        year: Int?,
        isAnime: Boolean,
        isMovie: Boolean
    ): LoadResponse {
        
        // Poster do site
        val posterImg = document.selectFirst("img.transitioning_src, .sub_anime_img img, img")
        val posterUrl = posterImg?.attr("src") ?: posterImg?.attr("data-src")
        
        // Sinopse do site
        val plot = document.selectFirst(".divSinopse, .sinopse")?.text()?.trim()
        
        // Tags do site
        val tags = document.select(".animeInfo a.spanAnimeInfo, .spanGeneros").map { it.text().trim() }
            .takeIf { it.isNotEmpty() }?.toList()
        
        // Recomendações
        val siteRecommendations = extractRecommendationsFromSite(document)

        // Episódios do site
        val episodes = extractEpisodesFromSite(document, url, isAnime, isMovie)

        return if (isAnime && !isMovie) {
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = posterUrl?.let { fixUrl(it) }
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
            }
        } else {
            val playerUrl = if (episodes.isNotEmpty()) {
                episodes.first().data
            } else {
                url
            }
            
            newMovieLoadResponse(title, url, TvType.Movie, fixUrl(playerUrl)) {
                this.posterUrl = posterUrl?.let { fixUrl(it) }
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
            }
        }
    }

    private suspend fun createLoadResponseWithTMDB(
        tmdbInfo: TMDBInfo,
        url: String,
        document: org.jsoup.nodes.Document,
        isAnime: Boolean,
        isMovie: Boolean,
        siteRecommendations: List<SearchResponse>
    ): LoadResponse {

        // Episódios com dados TMDB
        val episodes = extractEpisodesWithTMDBInfo(
            document = document,
            url = url,
            tmdbInfo = tmdbInfo,
            isAnime = isAnime,
            isMovie = isMovie
        )

        return if (isAnime && !isMovie) {
            newTvSeriesLoadResponse(
                name = tmdbInfo.title ?: "",
                url = url,
                type = TvType.Anime,
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
            val playerUrl = findPlayerUrl(document) ?: url
            
            newMovieLoadResponse(
                name = tmdbInfo.title ?: "",
                url = url,
                type = TvType.Movie,
                dataUrl = fixUrl(playerUrl)
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
        url: String,
        isAnime: Boolean,
        isMovie: Boolean
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()

        val episodeElements = document.select(".div_video_list a.lEp, a[href*='/animes/'], a.lep")
        
        episodeElements.forEachIndexed { index, element ->
            try {
                val episodeHref = element.attr("href") ?: return@forEachIndexed
                val episodeText = element.text().trim()
                
                val episodeNumber = Regex("Epis[oó]dio\\s*(\\d+)").find(episodeText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("Ep\\.?\\s*(\\d+)").find(episodeText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: (index + 1)
                
                episodes.add(
                    newEpisode(fixUrl(episodeHref)) {
                        this.name = "Episódio $episodeNumber"
                        this.episode = episodeNumber
                        this.season = 1
                    }
                )
            } catch (e: Exception) {
                // Ignorar erro
            }
        }

        return episodes.sortedBy { it.episode }.distinctBy { it.episode }
    }

    private suspend fun extractEpisodesWithTMDBInfo(
        document: org.jsoup.nodes.Document,
        url: String,
        tmdbInfo: TMDBInfo?,
        isAnime: Boolean,
        isMovie: Boolean
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()

        val episodeElements = document.select(".div_video_list a.lEp, a[href*='/animes/'], a.lep")
        
        episodeElements.forEachIndexed { index, element ->
            try {
                val episodeHref = element.attr("href") ?: return@forEachIndexed
                val episodeText = element.text().trim()
                
                val episodeNumber = extractEpisodeNumber(element, index + 1)
                val seasonNumber = 1 // AnimeFire geralmente tem só 1 temporada

                // Tentar encontrar episódio no TMDB
                val tmdbEpisode = findTMDBEpisode(tmdbInfo, seasonNumber, episodeNumber)

                val episode = if (tmdbEpisode != null) {
                    val descriptionWithDuration = buildDescriptionWithDuration(
                        tmdbEpisode.overview,
                        tmdbEpisode.runtime
                    )

                    newEpisode(fixUrl(episodeHref)) {
                        this.name = tmdbEpisode.name ?: "Episódio $episodeNumber"
                        this.season = seasonNumber
                        this.episode = episodeNumber
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
                    newEpisode(fixUrl(episodeHref)) {
                        this.name = "Episódio $episodeNumber"
                        this.season = seasonNumber
                        this.episode = episodeNumber
                    }
                }

                episodes.add(episode)
            } catch (e: Exception) {
                // Ignorar erro
            }
        }

        return episodes.sortedBy { it.episode }.distinctBy { it.episode }
    }

    private fun buildDescriptionWithDuration(overview: String?, runtime: Int?): String? {
        return when {
            overview != null && runtime != null && runtime > 0 -> {
                "$overview\n\nDuração: $runtime min"
            }
            overview != null -> overview
            runtime != null && runtime > 0 -> "Duração: $runtime min"
            else -> null
        }
    }

    private fun findTMDBEpisode(tmdbInfo: TMDBInfo?, season: Int, episode: Int): TMDBEpisode? {
        if (tmdbInfo == null) return null

        val episodes = tmdbInfo.seasonsEpisodes[season]
        return episodes?.find { it.episode_number == episode }
    }

    private fun extractEpisodeNumber(element: Element, default: Int): Int {
        return element.attr("data-ep").toIntOrNull() ?:
               element.selectFirst(".ep-number, .number, .episode-number")?.text()?.toIntOrNull() ?:
               Regex("Ep\\.?\\s*(\\d+)").find(element.text())?.groupValues?.get(1)?.toIntOrNull() ?:
               Regex("Epis[oó]dio\\s*(\\d+)").find(element.text())?.groupValues?.get(1)?.toIntOrNull() ?:
               default
    }

    private fun findPlayerUrl(document: org.jsoup.nodes.Document): String? {
        // AnimeFire usa links diretos, então retornamos null
        // O extrator vai encontrar os links MP4
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return AnimeFireExtractor.extractVideoLinks(data, mainUrl, name, callback)
    }

    // ============ CLASSES DE DADOS TMDB ============

    private data class TMDBInfo(
        val id: Int,
        val title: String?,
        val year: Int?,
        val posterUrl: String?,
        val backdropUrl: String?,
        val
