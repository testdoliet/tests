package com.PobreFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.nodes.Element

class PobreFlix : MainAPI() {
    override var mainUrl = "https://lospobreflix.site/"
    override var name = "PobreFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val usesWebView = true

    companion object {
        private const val SEARCH_PATH = "/pesquisar"
        
        // Gêneros de Filmes
        private val MOVIE_GENRES = listOf(
            "acao" to "Ação",
            "animacao" to "Animação",
            "aventura" to "Aventura",
            "cinema-tv" to "Cinema TV",
            "comedia" to "Comédia",
            "crime" to "Crime",
            "documentario" to "Documentário",
            "drama" to "Drama",
            "familia" to "Família",
            "fantasia" to "Fantasia",
            "faroeste" to "Faroeste",
            "ficcao-cientifica" to "Ficção Científica",
            "guerra" to "Guerra",
            "historia" to "História",
            "horror" to "Horror",
            "misterio" to "Mistério",
            "musica" to "Música",
            "romance" to "Romance",
            "terror" to "Terror"
        )
        
        // Gêneros de Séries
        private val SERIES_GENRES = listOf(
            "animacao" to "Animação",
            "comedia" to "Comédia",
            "crime" to "Crime",
            "documentario" to "Documentário",
            "dorama" to "Dorama",
            "drama" to "Drama",
            "familia" to "Família",
            "faroeste" to "Faroeste",
            "historia" to "História",
            "misterio" to "Mistério",
            "reality" to "Reality",
            "romance" to "Romance"
        )
        
        // Gêneros de Animes
        private val ANIME_GENRES = listOf(
            "acao" to "Ação",
            "artes-marciais" to "Artes Marciais",
            "animacao" to "Animação",
            "aventura" to "Aventura",
            "comedia" to "Comédia",
            "crime" to "Crime",
            "detetive" to "Detetive",
            "drama" to "Drama",
            "documentario" to "Documentário",
            "escolar" to "Escolar",
            "esportes" to "Esportes",
            "familia" to "Família",
            "fantasia" to "Fantasia",
            "faroeste" to "Faroeste",
            "infantil" to "Infantil",
            "jogo-de-estrategia" to "Jogos de Estratégia",
            "mitologia" to "Mitologia",
            "misterio" to "Mistério",
            "musica" to "Música",
            "reencarnacao" to "Reencarnação",
            "romance" to "Romance",
            "samurai" to "Samurai",
            "sobrenatural" to "Sobrenatural",
            "superpoder" to "Superpoder",
            "suspense" to "Suspense",
            "terror" to "Terror"
        )
        
        // Gêneros de Doramas
        private val DORAMA_GENRES = listOf(
            "comedia" to "Comédia",
            "crime" to "Crime",
            "documentario" to "Documentário",
            "drama" to "Drama",
            "misterio" to "Mistério",
            "familia" to "Família",
            "reality" to "Reality",
            "romance" to "Romance"
        )
        
        // Novos Episódios por tipo
        private val NEW_EPISODES = listOf(
            "/episodios?tipo=series" to "Novos Episódios - Séries",
            "/episodios?tipo=animes" to "Novos Episódios - Animes",
            "/episodios?tipo=doramas" to "Novos Episódios - Doramas"
        )
    }

    override val mainPage = mainPageOf(
        // Em Alta (página principal)
        mainUrl to "Em Alta",
        
        // Gêneros de Filmes
        *MOVIE_GENRES.map { (genre, name) ->
            "$mainUrl/filmes?genre=$genre" to "Filmes - $name"
        }.toTypedArray(),
        
        // Gêneros de Séries
        *SERIES_GENRES.map { (genre, name) ->
            "$mainUrl/series?genre=$genre" to "Séries - $name"
        }.toTypedArray(),
        
        // Gêneros de Animes
        *ANIME_GENRES.map { (genre, name) ->
            "$mainUrl/animes?genre=$genre" to "Animes - $name"
        }.toTypedArray(),
        
        // Gêneros de Doramas
        *DORAMA_GENRES.map { (genre, name) ->
            "$mainUrl/doramas?genre=$genre" to "Doramas - $name"
        }.toTypedArray(),
        
        // Novos Episódios (horizontais)
        *NEW_EPISODES.map { (path, name) ->
            "$mainUrl$path" to name
        }.toTypedArray()
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data
        
        // Para a seção "Em Alta", usamos a página principal
        val isHomePage = url == mainUrl
        
        val document = if (isHomePage) {
            app.get(url).document
        } else {
            // Para outras seções, adicionamos paginação se necessário
            val finalUrl = if (page > 1) {
                if (url.contains("?")) "$url&page=$page" else "$url?page=$page"
            } else {
                url
            }
            app.get(finalUrl).document
        }
        
        val items = if (isHomePage) {
            // Em Alta: pegar os cards do swiper da página principal
            document.select(".swiper-slide article")
                .mapNotNull { element ->
                    element.toSearchResult()
                }
        } else {
            // Para gêneros e novos episódios, usar o seletor genérico
            document.select(".grid article, .swiper-slide article")
                .mapNotNull { element ->
                    element.toSearchResult()
                }
        }
        
        // Verificar se há próxima página (para seções que não são a home)
        val hasNextPage = if (!isHomePage) {
            document.select("a:contains(Próxima), .page-numbers a[href*='page'], .pagination a:contains(Próxima)").isNotEmpty()
        } else {
            false
        }
        
        // Para Novos Episódios, usar layout horizontal
        val isHorizontal = request.name.contains("Novos Episódios")
        
        return newHomePageResponse(request.name, items.distinctBy { it.url }, hasNextPage) {
            this.isHorizontalImages = isHorizontal
        }
    }
    
    private fun Element.toSearchResult(): SearchResponse? {
        // Busca o link dentro do elemento
        val linkElement = selectFirst("a")
        val href = linkElement?.attr("href") ?: return null
        
        // Busca o título
        val imgElement = linkElement?.selectFirst("img")
        val title = imgElement?.attr("alt") ?: attr("title") ?: return null
        
        val poster = imgElement?.attr("src")?.let { fixUrl(it) }
        
        // Extrai ano do título se disponível
        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
        
        // Determina o tipo baseado na URL
        val isAnime = href.contains("/anime/")
        val isSerie = href.contains("/serie/") || href.contains("/dorama/")
        
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
        val searchUrl = "$mainUrl$SEARCH_PATH?s=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document

        return document.select(".grid article, .swiper-slide article").mapNotNull { card ->
            try {
                card.toSearchResult()
            } catch (e: Exception) {
                null
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val titleElement = document.selectFirst("h1, .text-3xl.text-lead.font-bold")
        val title = titleElement?.text() ?: return null

        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()

        val isAnime = url.contains("/anime/") || title.contains("(Anime)", ignoreCase = true)
        val isSerie = url.contains("/serie/") || url.contains("/dorama/") || 
                     (!isAnime && document.selectFirst("#episodes-list, .season-dropdown, .episode-card") != null)

        val ogImage = document.selectFirst("meta[property='og:image']")?.attr("content")
        val poster = ogImage?.let { fixUrl(it) }

        val synopsis = document.selectFirst(".text-slate-700.dark\\:text-slate-200.md\\:text-lg, .text-slate-900\\/90.dark\\:text-slate-100\\/90, meta[name='description']")?.attr("content")?.trim()
        val plot = synopsis

        val tags = document.select(".flex.flex-wrap.gap-2 a, .px-3.py-1.rounded-full.text-xs.bg-slate-200")
            .map { it.text() }
            .takeIf { it.isNotEmpty() }

        val ratingPercent = document.selectFirst("text[x='18'][y='21']")?.text()?.replace("%", "")?.toFloatOrNull()
        val ratingValue = ratingPercent?.let { it / 10 }
        val score = ratingValue?.let { Score.from10(it) }

        val backdrop = document.selectFirst(".absolute.left-1\\/2 img, .blur-\\[4px\\] img")?.attr("src")?.let { fixUrl(it) }

        val durationText = document.selectFirst(".bg-slate-200.dark\\:bg-slate-700.rounded-lg.p-3:contains(min) .font-medium, .inline-flex.items-center.rounded-full.px-3.py-1:contains(min)")?.text()
        val duration = durationText?.let { 
            Regex("(\\d+)\\s*min").find(it)?.groupValues?.get(1)?.toIntOrNull()
        }

        val cast = document.select("#cast-section .swiper-slide .text-sm.font-bold, .swiper-slide .text-sm.font-bold")
            .map { it.text() }
            .takeIf { it.isNotEmpty() }
            ?.map { Actor(name = it) }

        val trailerKey = document.selectFirst("script:containsData(window.__trailerKeys)")?.data()?.let { script ->
            Regex("window\\.__trailerKeys\\s*=\\s*\\[\"([^\"]+)\"\\]").find(script)?.groupValues?.get(1)
        }

        val siteRecommendations = document.select("#relatedSection .swiper-slide a, .related-swiper .swiper-slide a")
            .mapNotNull { element ->
                try {
                    val href = element.attr("href") ?: return@mapNotNull null
                    val imgElement = element.selectFirst("img")
                    val titleRec = imgElement?.attr("alt") ?: element.selectFirst("h3, .text-white.font-bold")?.text() ?: return@mapNotNull null
                    val posterRec = imgElement?.attr("src")?.let { fixUrl(it) }
                    val yearRec = element.selectFirst(".text-white\\/70.text-xs")?.text()?.toIntOrNull()
                    val cleanTitleRec = titleRec.replace(Regex("\\(\\d{4}\\)"), "").trim()

                    val isAnimeRec = href.contains("/anime/")
                    val isSerieRec = href.contains("/serie/") || href.contains("/dorama/")

                    when {
                        isAnimeRec -> newAnimeSearchResponse(cleanTitleRec, fixUrl(href), TvType.Anime) {
                            this.posterUrl = posterRec
                            this.year = yearRec
                        }
                        isSerieRec -> newTvSeriesSearchResponse(cleanTitleRec, fixUrl(href), TvType.TvSeries) {
                            this.posterUrl = posterRec
                            this.year = yearRec
                        }
                        else -> newMovieSearchResponse(cleanTitleRec, fixUrl(href), TvType.Movie) {
                            this.posterUrl = posterRec
                            this.year = yearRec
                        }
                    }
                } catch (e: Exception) { null }
            }

        return if (isAnime || isSerie) {
            val type = if (isAnime) TvType.Anime else TvType.TvSeries
            val episodes = extractEpisodesFromSite(document, url, isAnime, isSerie)

            newTvSeriesLoadResponse(cleanTitle, url, type, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
                this.score = score

                if (cast != null && cast.isNotEmpty()) {
                    addActors(cast)
                }

                if (trailerKey != null) {
                    addTrailer("https://www.youtube.com/watch?v=$trailerKey")
                }
            }
        } else {
            val playerUrl = findPlayerUrl(document)

            newMovieLoadResponse(cleanTitle, url, TvType.Movie, playerUrl ?: url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = plot
                this.tags = tags
                this.duration = duration
                this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
                this.score = score

                if (cast != null && cast.isNotEmpty()) {
                    addActors(cast)
                }

                if (trailerKey != null) {
                    addTrailer("https://www.youtube.com/watch?v=$trailerKey")
                }
            }
        }
    }

    private suspend fun extractEpisodesFromSite(
        document: org.jsoup.nodes.Document,
        url: String,
        isAnime: Boolean,
        isSerie: Boolean
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()

        val scriptData = document.selectFirst("script:containsData(window.allEpisodes)")?.data()
        if (scriptData != null) {
            try {
                val jsonMatch = Regex("window\\.allEpisodes\\s*=\\s*(\\{[^;]+\\})").find(scriptData)
                val jsonString = jsonMatch?.groupValues?.get(1)
                
                if (jsonString != null) {
                    val seasonPattern = Regex("\"(\\d+)\":\\s*\\[([^\\]]+)\\]")
                    val seasonMatches = seasonPattern.findAll(jsonString)
                    
                    for (seasonMatch in seasonMatches) {
                        val seasonNum = seasonMatch.groupValues[1].toIntOrNull() ?: 1
                        val episodesJson = seasonMatch.groupValues[2]
                        
                        val episodePattern = Regex("\\{[^}]*\"epi_num\"\\s*:\\s*(\\d+)[^}]*\"title\"\\s*:\\s*\"([^\"]*)\"[^}]*\"thumb_url\"\\s*:\\s*\"([^\"]*)\"[^}]*\"duration\"\\s*:\\s*(\\d+)[^}]*\"air_date\"\\s*:\\s*\"([^\"]*)\"[^}]*\\}")
                        val episodeMatches = episodePattern.findAll(episodesJson)
                        
                        for (epMatch in episodeMatches) {
                            val epNum = epMatch.groupValues[1].toIntOrNull() ?: continue
                            val epTitle = epMatch.groupValues[2].ifEmpty { "Episódio $epNum" }
                            val thumbUrl = epMatch.groupValues[3].takeIf { it.isNotEmpty() }?.let { fixUrl(it) }
                            val durationMin = epMatch.groupValues[4].toIntOrNull()
                            val airDate = epMatch.groupValues[5].takeIf { it.isNotEmpty() }
                            
                            val episodeUrl = "$url/$seasonNum/$epNum"
                            
                            episodes.add(newEpisode(fixUrl(episodeUrl)) {
                                this.name = epTitle
                                this.season = seasonNum
                                this.episode = epNum
                                this.posterUrl = thumbUrl
                                this.description = buildString {
                                    if (durationMin != null && durationMin > 0) append("Duração: ${durationMin}min\n")
                                    if (airDate != null) append("Data: $airDate")
                                }.trim()
                            })
                        }
                    }
                    
                    if (episodes.isNotEmpty()) return episodes
                }
            } catch (e: Exception) { }
        }

        val episodeElements = document.select("#episodes-list article, .episode-card, .episode-item")
        
        episodeElements.forEachIndexed { index, element ->
            try {
                val link = element.selectFirst("a[href]") ?: return@forEachIndexed
                val dataUrl = link.attr("href")
                if (dataUrl.isBlank()) return@forEachIndexed

                val seasonText = element.selectFirst(".text-lead.shrink-0")?.text() ?: "T1:E${index + 1}"
                val seasonMatch = Regex("T(\\d+):E(\\d+)").find(seasonText)
                val seasonNumber = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val epNumber = seasonMatch?.groupValues?.get(2)?.toIntOrNull() ?: (index + 1)
                
                val epTitle = element.selectFirst("h2, .truncate")?.text() ?: "Episódio $epNumber"
                val thumb = element.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                val description = element.selectFirst(".line-clamp-2.text-xs")?.text()

                episodes.add(newEpisode(fixUrl(dataUrl)) {
                    this.name = epTitle
                    this.season = seasonNumber
                    this.episode = epNumber
                    this.posterUrl = thumb
                    this.description = description
                })
            } catch (e: Exception) { }
        }

        return episodes
    }

    private fun findPlayerUrl(document: org.jsoup.nodes.Document): String? {
        val iframe = document.selectFirst("iframe[src*='player'], iframe[src*='embed'], iframe[src*='fembed'], iframe[src*='filemoon']")
        if (iframe != null) {
            return iframe.attr("src")
        }

        val videoLink = document.selectFirst("a[href*='.m3u8'], a[href*='.mp4'], a[href*='watch']")
        return videoLink?.attr("href")
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
                } else false
            }
        } catch (e: Exception) {
            false
        }
    }
}
