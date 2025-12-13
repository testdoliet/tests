package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat

class SuperFlix : MainAPI() {
    override var mainUrl = "https://superflix21.lol"
    override var name = "SuperFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val usesWebView = true

    // Vamos usar o Tmdb do CloudStream para buscar metadados
    private val tmdb = com.lagradost.cloudstream3.Tmdb()
    private val tmdbImageUrl = "https://image.tmdb.org/t/p"

    override val mainPage = mainPageOf(
        "$mainUrl/lancamentos" to "Lançamentos",
        "$mainUrl/filmes" to "Últimos Filmes",
        "$mainUrl/series" to "Últimas Séries",
        "$mainUrl/animes" to "Últimas Animes"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        println("🔍 [SUPERFLIX] getMainPage: ${request.name}, page $page")
        
        val url = request.data + if (page > 1) "?page=$page" else ""
        val document = app.get(url).document

        val home = document.select("a.card, div.recs-grid a.rec-card").mapNotNull { element ->
            try {
                val title = element.attr("title") ?: element.selectFirst("img")?.attr("alt") ?: return@mapNotNull null
                val href = element.attr("href") ?: return@mapNotNull null

                val poster = element.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
                val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()

                val badge = element.selectFirst(".badge-kind")?.text()?.lowercase() ?: ""
                val isAnime = badge.contains("anime") || href.contains("/anime/")
                val isSerie = badge.contains("série") || badge.contains("serie") ||
                             href.contains("/serie/") || href.contains("/tv/")
                val isMovie = !isSerie && !isAnime

                val response = when {
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
                
                println("✅ [SUPERFLIX] Adicionado: $cleanTitle ($year)")
                response
            } catch (e: Exception) {
                println("❌ [SUPERFLIX] Erro: ${e.message}")
                null
            }
        }

        return newHomePageResponse(request.name, home.distinctBy { it.url })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        println("🔍 [SUPERFLIX] Buscando: $query")
        
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
                val isAnime = badge.contains("anime") || href.contains("/anime/")
                val isSerie = badge.contains("série") || badge.contains("serie") ||
                             href.contains("/serie/") || href.contains("/tv/")

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
        println("🎬 [SUPERFLIX] Carregando: $url")
        
        val document = app.get(url).document

        val titleElement = document.selectFirst("h1, .title")
        val title = titleElement?.text() ?: return null
        println("📝 [SUPERFLIX] Título: $title")

        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
        println("📅 [SUPERFLIX] Ano: $year, Título limpo: $cleanTitle")

        val isAnime = url.contains("/anime/") || title.contains("(Anime)", ignoreCase = true)
        val isSerie = url.contains("/serie/") || url.contains("/tv/") ||
                     (!isAnime && document.selectFirst(".episode-list, .season-list, .seasons") != null)
        println("🎬 [SUPERFLIX] Tipo: ${if (isAnime) "Anime" else if (isSerie) "Série" else "Filme"}")

        // AGORA VAMOS BUSCAR NO TMDB
        println("🔍 [SUPERFLIX] Buscando no TMDB...")
        val tmdbInfo = searchOnTMDB(cleanTitle, year, isSerie || isAnime)
        
        if (tmdbInfo != null) {
            println("✅ [SUPERFLIX] TMDB encontrado: ${tmdbInfo.title}")
            return createLoadResponseWithTMDB(tmdbInfo, url, document, isAnime, isSerie)
        } else {
            println("⚠️ [SUPERFLIX] TMDB não encontrado, usando dados do site")
            return createLoadResponseFromSite(document, url, cleanTitle, year, isAnime, isSerie)
        }
    }
    
    private suspend fun searchOnTMDB(query: String, year: Int?, isTv: Boolean): TMDBInfo? {
        println("🔍 [TMDB] Buscando: '$query' (ano: $year, tipo: ${if (isTv) "TV" else "Movie"})")
        
        return try {
            val searchResults = if (isTv) {
                tmdb.searchTv(query, 1, year)
            } else {
                tmdb.search(query, 1, year, "movie")
            }
            
            if (searchResults.isEmpty()) {
                println("❌ [TMDB] Nenhum resultado")
                return null
            }
            
            val result = searchResults.first()
            println("✅ [TMDB] Encontrado: ID ${result.id}, Nome: ${result.name ?: result.title}")
            
            // Buscar detalhes
            val details = if (isTv) {
                tmdb.getTvDetails(result.id, lang)
            } else {
                tmdb.getMovieDetails(result.id, lang)
            }
            
            if (details == null) {
                println("❌ [TMDB] Detalhes não encontrados")
                return null
            }
            
            // Buscar créditos
            val credits = if (isTv) {
                tmdb.getTvCredits(result.id, lang)
            } else {
                tmdb.getMovieCredits(result.id, lang)
            }
            
            // Buscar trailer
            val videos = if (isTv) {
                tmdb.getTvVideos(result.id, lang)
            } else {
                tmdb.getMovieVideos(result.id, lang)
            }
            
            val youtubeTrailer = videos?.results?.firstOrNull { video ->
                video.site == "YouTube" && (video.type == "Trailer" || video.type == "Teaser")
            }?.let { "https://www.youtube.com/watch?v=${it.key}" }
            
            TMDBInfo(
                id = result.id,
                title = if (isTv) details.name else details.title,
                year = year,
                posterUrl = details.poster_path?.let { "$tmdbImageUrl/w500$it" },
                backdropUrl = details.backdrop_path?.let { "$tmdbImageUrl/original$it" },
                overview = details.overview,
                genres = details.genres?.map { it.name },
                actors = credits?.cast?.take(15)?.map { actor ->
                    Actor(actor.name, actor.character, actor.profile_path?.let { "$tmdbImageUrl/w185$it" })
                },
                youtubeTrailer = youtubeTrailer,
                duration = if (!isTv) details.runtime else null
            )
        } catch (e: Exception) {
            println("❌ [TMDB] Erro: ${e.message}")
            null
        }
    }
    
    private fun createLoadResponseFromSite(
        document: org.jsoup.nodes.Document,
        url: String,
        title: String,
        year: Int?,
        isAnime: Boolean,
        isSerie: Boolean
    ): LoadResponse {
        println("🏗️ [SUPERFLIX] Criando resposta do site")
        
        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }
        val plot = document.selectFirst("meta[name='description']")?.attr("content") ?:
                   document.selectFirst(".syn, .description, .sinopse")?.text()
        
        return if (isAnime || isSerie) {
            val episodes = extractEpisodesFromSite(document, url)
            val type = if (isAnime) TvType.Anime else TvType.TvSeries
            
            newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
            }
        } else {
            val playerUrl = findPlayerUrl(document)
            
            newMovieLoadResponse(title, url, TvType.Movie, playerUrl ?: url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
            }
        }
    }
    
    private suspend fun createLoadResponseWithTMDB(
        tmdbInfo: TMDBInfo,
        url: String,
        document: org.jsoup.nodes.Document,
        isAnime: Boolean,
        isSerie: Boolean
    ): LoadResponse {
        println("🏗️ [SUPERFLIX] Criando resposta com TMDB")
        
        val episodes = if (isAnime || isSerie) {
            extractEpisodesFromSite(document, url)
        } else {
            emptyList()
        }
        
        return if (isAnime || isSerie) {
            val type = if (isAnime) TvType.Anime else TvType.TvSeries
            
            newTvSeriesLoadResponse(tmdbInfo.title ?: "", url, type, episodes) {
                this.posterUrl = tmdbInfo.posterUrl
                this.backgroundPosterUrl = tmdbInfo.backdropUrl
                this.year = tmdbInfo.year
                this.plot = tmdbInfo.overview
                this.tags = tmdbInfo.genres
                
                tmdbInfo.actors?.let { addActors(it) }
                tmdbInfo.youtubeTrailer?.let { addTrailer(it) }
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
                
                tmdbInfo.actors?.let { addActors(it) }
                tmdbInfo.youtubeTrailer?.let { addTrailer(it) }
            }
        }
    }
    
    private fun extractEpisodesFromSite(document: org.jsoup.nodes.Document, url: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        val episodeElements = document.select("button.bd-play[data-url], a.episode-card, .episode-item")
        episodeElements.forEachIndexed { index, element ->
            val dataUrl = element.attr("data-url") ?: element.attr("href") ?: return@forEachIndexed
            val epNumber = index + 1
            val seasonNumber = element.attr("data-season").toIntOrNull() ?: 1
            
            episodes.add(newEpisode(fixUrl(dataUrl)) {
                this.name = "Episódio $epNumber"
                this.season = seasonNumber
                this.episode = epNumber
            })
        }
        
        return episodes
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
        
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔗 [SUPERFLIX] Carregando links: ${data.take(50)}...")
        return SuperFlixExtractor.extractVideoLinks(data, mainUrl, name, callback)
    }
    
    // Classes de dados
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
        val duration: Int?
    )
}
