package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat

class SuperFlix : MainAPI() {
    override var mainUrl = "https://superflix21.lol"
    override var name = "SuperFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val usesWebView = true

    // Usando o TMDB do CloudStream
    private val tmdbApi = com.lagradost.cloudstream3.Tmdb()
    private val tmdbImageUrl = "https://image.tmdb.org/t/p"

    override val mainPage = mainPageOf(
        "$mainUrl/lancamentos" to "Lançamentos",
        "$mainUrl/filmes" to "Últimos Filmes",
        "$mainUrl/series" to "Últimas Séries",
        "$mainUrl/animes" to "Últimas Animes"
    )

    // Função para corrigir URLs
    private fun fixUrl(url: String): String {
        return when {
            url.isEmpty() -> url
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }

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
        val searchUrl = "$mainUrl/buscar?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
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
        println("🔍 [SuperFlix] Carregando: $url")

        val document = app.get(url).document

        val titleElement = document.selectFirst("h1, .title")
        val title = titleElement?.text() ?: return null
        println("🔍 [SuperFlix] Título: $title")

        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
        println("🔍 [SuperFlix] Título limpo: $cleanTitle | Ano: $year")

        val isAnime = url.contains("/anime/") || title.contains("(Anime)", ignoreCase = true)
        val isSerie = url.contains("/serie/") || url.contains("/tv/") ||
                     (!isAnime && document.selectFirst(".episode-list, .season-list, .seasons") != null)
        println("🔍 [SuperFlix] Tipo: ${if (isAnime) "Anime" else if (isSerie) "Série" else "Filme"}")

        // Buscar no TMDB
        println("🔍 [SuperFlix] Buscando no TMDB...")
        val tmdbInfo = if (isAnime || isSerie) {
            searchOnTMDB(cleanTitle, year, true)
        } else {
            searchOnTMDB(cleanTitle, year, false)
        }

        val siteRecommendations = extractRecommendationsFromSite(document)

        return if (tmdbInfo != null) {
            println("✅ [SuperFlix] Usando dados do TMDB")
            createLoadResponseWithTMDB(tmdbInfo, url, document, isAnime, isSerie, siteRecommendations)
        } else {
            println("⚠️ [SuperFlix] Usando dados do site")
            createLoadResponseFromSite(document, url, cleanTitle, year, isAnime, isSerie, siteRecommendations)
        }
    }

    private suspend fun searchOnTMDB(query: String, year: Int?, isTv: Boolean): TMDBInfo? {
        return try {
            println("🔍 [TMDB] Buscando: '$query' (TV: $isTv, Ano: $year)")
            
            val searchResults = if (isTv) {
                tmdbApi.searchTv(query, 1, year)
            } else {
                tmdbApi.search(query, 1, year, "movie")
            }
            
            if (searchResults.isEmpty()) {
                println("❌ [TMDB] Nenhum resultado encontrado")
                return null
            }
            
            val result = searchResults.first()
            println("✅ [TMDB] Encontrado: ID ${result.id}, Nome: ${result.name ?: result.title}")
            
            // Buscar detalhes
            val details = if (isTv) {
                tmdbApi.getTvDetails(result.id, lang)
            } else {
                tmdbApi.getMovieDetails(result.id, lang)
            }
            
            if (details == null) {
                println("❌ [TMDB] Detalhes não encontrados")
                return null
            }
            
            // Buscar créditos
            val credits = if (isTv) {
                tmdbApi.getTvCredits(result.id, lang)
            } else {
                tmdbApi.getMovieCredits(result.id, lang)
            }
            
            // Buscar vídeos/trailer
            val videos = if (isTv) {
                tmdbApi.getTvVideos(result.id, lang)
            } else {
                tmdbApi.getMovieVideos(result.id, lang)
            }
            
            // Buscar temporadas (apenas para séries)
            val seasonsEpisodes = if (isTv) {
                getTMDBSeasons(result.id)
            } else {
                emptyMap()
            }
            
            // Extrair atores
            val actors = credits?.cast?.take(15)?.mapNotNull { actor ->
                Actor(
                    name = actor.name,
                    role = actor.character,
                    image = actor.profile_path?.let { "$tmdbImageUrl/w185$it" }
                )
            }
            
            // Extrair trailer do YouTube
            val youtubeTrailer = videos?.results?.firstOrNull { video ->
                video.site == "YouTube" && (video.type == "Trailer" || video.type == "Teaser")
            }?.let { "https://www.youtube.com/watch?v=${it.key}" }
            
            TMDBInfo(
                id = result.id,
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
                seasonsEpisodes = seasonsEpisodes
            )
        } catch (e: Exception) {
            println("❌ [TMDB] Erro: ${e.message}")
            null
        }
    }
    
    private suspend fun getTMDBSeasons(seriesId: Int): Map<Int, List<TMDBEpisode>> {
        return try {
            val details = tmdbApi.getTvDetails(seriesId, lang)
            val seasonsEpisodes = mutableMapOf<Int, List<TMDBEpisode>>()
            
            details?.seasons?.forEach { seasonInfo ->
                if (seasonInfo.season_number > 0) { // Ignorar temporada 0 (especiais)
                    val season = tmdbApi.getTvSeason(seriesId, seasonInfo.season_number, lang)
                    season?.episodes?.let { episodes ->
                        val tmdbEpisodes = episodes.map { episode ->
                            TMDBEpisode(
                                episode_number = episode.episode_number,
                                name = episode.name,
                                overview = episode.overview,
                                still_path = episode.still_path,
                                runtime = episode.runtime,
                                air_date = episode.air_date
                            )
                        }
                        seasonsEpisodes[seasonInfo.season_number] = tmdbEpisodes
                    }
                }
            }
            
            seasonsEpisodes
        } catch (e: Exception) {
            println("❌ [TMDB Seasons] Erro: ${e.message}")
            emptyMap()
        }
    }

    private fun extractRecommendationsFromSite(document: org.jsoup.nodes.Document): List<SearchResponse> {
        return document.select(".recs-grid .rec-card, .recs-grid a").mapNotNull { element ->
            try {
                val href = element.attr("href") ?: return@mapNotNull null
                if (href.isBlank() || href == "#") return@mapNotNull null

                val imgElement = element.selectFirst("img")
                val title = imgElement?.attr("alt") ?:
                           element.selectFirst(".rec-title")?.text() ?:
                           element.attr("title") ?:
                           return@mapNotNull null

                val poster = imgElement?.attr("src")?.let { fixUrl(it) }
                val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
                val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()

                val isAnime = href.contains("/anime/") || title.contains("(Anime)", ignoreCase = true)
                val isSerie = href.contains("/serie/") || href.contains("/tv/")
                val isMovie = !isSerie && !isAnime

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

    private suspend fun createLoadResponseFromSite(
        document: org.jsoup.nodes.Document,
        url: String,
        title: String,
        year: Int?,
        isAnime: Boolean,
        isSerie: Boolean,
        siteRecommendations: List<SearchResponse>
    ): LoadResponse {
        val ogImage = document.selectFirst("meta[property='og:image']")?.attr("content")
        val poster = ogImage?.let { fixUrl(it) } ?: document.selectFirst("img.poster")?.attr("src")?.let { fixUrl(it) }

        val description = document.selectFirst("meta[name='description']")?.attr("content")
        val synopsis = document.selectFirst(".syn, .description, .sinopse, .plot")?.text()
        val plot = (description ?: synopsis)?.trim()

        val tags = document.select("a.chip, .chip, .genre, .tags").map { it.text().trim() }
            .takeIf { it.isNotEmpty() && it.any { tag -> tag.isNotBlank() } }
            ?.filter { it.isNotBlank() }
            ?.toList()

        return if (isAnime || isSerie) {
            val type = if (isAnime) TvType.Anime else TvType.TvSeries
            val episodes = extractEpisodesFromSite(document, url)

            newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
            }
        } else {
            val playerUrl = findPlayerUrl(document)

            newMovieLoadResponse(title, url, TvType.Movie, playerUrl ?: url) {
                this.posterUrl = poster
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
        isSerie: Boolean,
        siteRecommendations: List<SearchResponse>
    ): LoadResponse {
        return if (isAnime || isSerie) {
            val type = if (isAnime) TvType.Anime else TvType.TvSeries
            val episodes = extractEpisodesWithTMDBInfo(document, url, tmdbInfo)

            newTvSeriesLoadResponse(tmdbInfo.title ?: "", url, type, episodes) {
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
            val playerUrl = findPlayerUrl(document)

            newMovieLoadResponse(tmdbInfo.title ?: "", url, TvType.Movie, playerUrl ?: url) {
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

        val episodeElements = document.select("button.bd-play[data-url], a.episode-card, .episode-item, .episode-link, [class*='episode']")
        
        episodeElements.forEachIndexed { index, element ->
            try {
                val dataUrl = element.attr("data-url") ?: element.attr("href") ?: ""
                if (dataUrl.isBlank()) return@forEachIndexed

                val epNumber = extractEpisodeNumber(element, index + 1)
                val seasonNumber = element.attr("data-season").toIntOrNull() ?: 1

                val episodeTitle = element.selectFirst(".ep-title, .title, .name")?.text()?.trim()
                    ?: "Episódio $epNumber"
                    
                val episodeDescription = element.selectFirst(".ep-desc, .description, .sinopse")?.text()?.trim()

                val episode = newEpisode(fixUrl(dataUrl)) {
                    this.name = episodeTitle
                    this.season = seasonNumber
                    this.episode = epNumber
                    
                    if (!episodeDescription.isNullOrBlank()) {
                        this.description = episodeDescription
                    }
                }

                episodes.add(episode)
            } catch (e: Exception) {
                // Ignorar erros
            }
        }

        return episodes.sortedWith(compareBy({ it.season }, { it.episode }))
    }

    private suspend fun extractEpisodesWithTMDBInfo(
        document: org.jsoup.nodes.Document,
        url: String,
        tmdbInfo: TMDBInfo?
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()

        val episodeElements = document.select("button.bd-play[data-url], a.episode-card, .episode-item, .episode-link, [class*='episode']")
        
        episodeElements.forEachIndexed { index, element ->
            try {
                val dataUrl = element.attr("data-url") ?: element.attr("href") ?: ""
                if (dataUrl.isBlank()) return@forEachIndexed

                val epNumber = extractEpisodeNumber(element, index + 1)
                val seasonNumber = element.attr("data-season").toIntOrNull() ?: 1

                // Tentar encontrar informações do episódio no TMDB
                val tmdbEpisode = findTMDBEpisode(tmdbInfo, seasonNumber, epNumber)

                val episode = if (tmdbEpisode != null) {
                    // Com dados do TMDB
                    val descriptionWithDuration = buildDescriptionWithDuration(
                        tmdbEpisode.overview,
                        tmdbEpisode.runtime
                    )

                    newEpisode(fixUrl(dataUrl)) {
                        this.name = tmdbEpisode.name ?: "Episódio $epNumber"
                        this.season = seasonNumber
                        this.episode = epNumber
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
                    // Sem dados do TMDB
                    val episodeTitle = element.selectFirst(".ep-title, .title, .name")?.text()?.trim()
                        ?: "Episódio $epNumber"

                    newEpisode(fixUrl(dataUrl)) {
                        this.name = episodeTitle
                        this.season = seasonNumber
                        this.episode = epNumber
                    }
                }

                episodes.add(episode)
            } catch (e: Exception) {
                // Ignorar erros
            }
        }

        return episodes.sortedWith(compareBy({ it.season }, { it.episode }))
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
        return tmdbInfo.seasonsEpisodes[season]?.find { it.episode_number == episode }
    }

    private fun extractEpisodeNumber(element: Element, default: Int): Int {
        return element.attr("data-ep").toIntOrNull() ?:
               element.attr("data-episode").toIntOrNull() ?:
               element.selectFirst(".ep-number, .number, .episode-number, .ep")?.text()?.toIntOrNull() ?:
               Regex("Ep\\.?\\s*(\\d+)").find(element.text())?.groupValues?.get(1)?.toIntOrNull() ?:
               Regex("Epis[oó]dio\\s*(\\d+)").find(element.text())?.groupValues?.get(1)?.toIntOrNull() ?:
               Regex("(\\d+)").find(element.text())?.groupValues?.get(1)?.toIntOrNull() ?:
               default
    }

    private fun findPlayerUrl(document: org.jsoup.nodes.Document): String? {
        val playButton = document.selectFirst("button.bd-play[data-url]")
        if (playButton != null) {
            return playButton.attr("data-url")
        }

        val iframe = document.selectFirst("iframe[src*='player'], iframe[src*='embed']")
        if (iframe != null) {
            return iframe.attr("src")
        }

        val videoLink = document.selectFirst("a[href*='.m3u8'], a[href*='assistir'], a[href*='watch']")
        return videoLink?.attr("href")
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return SuperFlixExtractor.extractVideoLinks(data, mainUrl, name, callback)
    }

    // ============ CLASSES DE DADOS ============

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

    private data class TMDBEpisode(
        val episode_number: Int,
        val name: String,
        val overview: String?,
        val still_path: String?,
        val runtime: Int?,
        val air_date: String?
    )
}
