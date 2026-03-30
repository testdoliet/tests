package com.PobreFlix

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import org.jsoup.nodes.Element
import java.net.URLEncoder
import com.PobreFlix.extractor.PobreFlixExtractor

@CloudstreamPlugin
class PobreFlixProvider : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(PobreFlix())
    }
}

class PobreFlix : MainAPI() {
    override var mainUrl = "https://lospobreflix.site"
    override var name = "PobreFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)
    override val usesWebView = true

    companion object {
        private const val SEARCH_PATH = "/pesquisar"
        
        private val MAIN_SECTIONS = listOf(
            "" to "Em Alta",
            "/filmes" to "Filmes",
            "/series" to "Séries",
            "/animes" to "Animes",
            "/doramas" to "Doramas"
        )
    }

    override val mainPage = mainPageOf(
        *MAIN_SECTIONS.map { (path, name) ->
            if (path.isEmpty()) mainUrl to name
            else "$mainUrl$path" to name
        }.toTypedArray()
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        println("=== getMainPage INICIADO ===")
        println("Page: $page, Request: ${request.name}")
        
        var url = request.data
        
        if (request.name != "Em Alta" && page > 1) {
            url = if (url.contains("?")) "$url&page=$page" else "$url?page=$page"
        }
        
        if (request.name == "Em Alta") {
            val document = app.get(url).document
            val elements = document.select(".swiper_top10_home .swiper-slide, .top-10-items .item")
            println("Elementos Top 10: ${elements.size}")
            val items = elements.mapNotNull { it.toSearchResult() }
            return newHomePageResponse(request.name, items, hasNext = false)
        }
        
        val document = app.get(url).document
        val elements = document.select("article.group, .group\\/card, .grid article")
        println("Elementos encontrados: ${elements.size}")
        
        val items = elements.mapNotNull { element ->
            try {
                element.toSearchResult()
            } catch (e: Exception) {
                println("ERRO ao processar elemento: ${e.message}")
                null
            }
        }
        
        val hasNextPage = document.select("a[href*='?page=${page + 1}'], a[href*='&page=${page + 1}'], a:contains(Próxima)").isNotEmpty()
        
        println("Items: ${items.size}, HasNext: $hasNextPage")
        
        return newHomePageResponse(request.name, items, hasNext = hasNextPage)
    }
    
    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = selectFirst("a[href*='/filme/'], a[href*='/serie/'], a[href*='/anime/'], a[href*='/dorama/']") 
            ?: selectFirst("a") ?: return null
        
        var href = linkElement.attr("href")
        if (href.isBlank()) return null
        if (!href.startsWith("http")) {
            href = if (href.startsWith("/")) "$mainUrl$href" else "$mainUrl/$href"
        }
        
        val imgElement = selectFirst("img")
        var poster: String? = null
        
        if (imgElement != null) {
            poster = imgElement.attr("src")
            if (poster.isNullOrBlank()) poster = imgElement.attr("data-src")
            if (!poster.isNullOrBlank()) {
                poster = fixImageUrl(poster)
            }
        }
        
        var title = selectFirst("h3")?.text()
        if (title.isNullOrBlank()) title = selectFirst(".line-clamp-1")?.text()
        if (title.isNullOrBlank()) title = imgElement?.attr("alt")
        if (title.isNullOrBlank()) return null
        
        var year: Int? = null
        val yearSpan = selectFirst(".text-white\\/70.text-xs, .text-xs")
        yearSpan?.text()?.let { 
            if (it.matches(Regex("\\d{4}"))) {
                year = it.toIntOrNull()
            }
        }
        
        if (year == null) {
            val yearMatch = Regex("\\((\\d{4})\\)").find(title)
            if (yearMatch != null) {
                year = yearMatch.groupValues[1].toIntOrNull()
                title = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
            }
        }
        
        val finalTitle = title.trim()
        
        var scoreValue: Float? = null
        val scoreText = selectFirst("text[x='18'][y='21']")?.text()
        if (!scoreText.isNullOrBlank()) {
            val percent = scoreText.replace("%", "").trim().toFloatOrNull()
            scoreValue = percent?.let { it / 10 }
        }
        
        val isAnime = href.contains("/anime/")
        val isSerie = href.contains("/serie/") || href.contains("/dorama/")
        
        return when {
            isAnime -> newAnimeSearchResponse(finalTitle, href, TvType.Anime) {
                this.posterUrl = poster
                this.year = year
                if (scoreValue != null) this.score = Score.from10(scoreValue)
            }
            isSerie -> newTvSeriesSearchResponse(finalTitle, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
                if (scoreValue != null) this.score = Score.from10(scoreValue)
            }
            else -> newMovieSearchResponse(finalTitle, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                if (scoreValue != null) this.score = Score.from10(scoreValue)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        println("=== search INICIADO: $query")
        
        if (query.length < 2) return emptyList()
        
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl$SEARCH_PATH?s=$encodedQuery"
        
        try {
            val document = app.get(searchUrl).document
            println("Título da página: ${document.title()}")
            
            val results = document.select("article.group, .grid article, .group\\/card")
                .mapNotNull { element ->
                    try {
                        element.toSearchResult()
                    } catch (e: Exception) {
                        println("ERRO no resultado: ${e.message}")
                        null
                    }
                }
            
            println("Resultados encontrados: ${results.size}")
            return results
            
        } catch (e: Exception) {
            println("ERRO na busca: ${e.message}")
            return emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        println("=== load INICIADO ===")
        println("URL: $url")
        
        try {
            val document = app.get(url).document
            println("Título da página: ${document.title()}")

            // ========== TÍTULO ==========
            val titleElement = document.selectFirst("h1.text-3xl.text-lead.font-bold")
            val title = titleElement?.text()?.trim() ?: return null
            println("Título: $title")

            // ========== ANO ==========
            var year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
            val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
            println("Título limpo: $cleanTitle, ano: $year")

            // ========== TIPO ==========
            val isAnime = url.contains("/anime/")
            val isSerie = url.contains("/serie/") || url.contains("/dorama/")
            println("isAnime: $isAnime, isSerie: $isSerie")

            // ========== POSTER ==========
            var poster = document.selectFirst("meta[property='og:image']")?.attr("content")
            if (poster.isNullOrBlank()) {
                poster = document.selectFirst("img.w-full.aspect-\\[2\\/3\\]")?.attr("src")
            }
            poster = fixImageUrl(poster)
            println("Poster: $poster")
            
            // ========== BACKDROP/BANNER ==========
            var backdrop: String? = null
            
            backdrop = document.selectFirst("#movie-player-container")?.attr("data-backdrop")
            
            if (backdrop.isNullOrBlank()) {
                backdrop = document.selectFirst("#movie-player-container img")?.attr("src")
            }
            
            if (backdrop.isNullOrBlank()) {
                backdrop = document.selectFirst("img[alt*='backdrop']")?.attr("src")
            }
            
            backdrop = fixImageUrl(backdrop)
            println("Backdrop: $backdrop")
            
            // ========== RATING ==========
            var rating: Float? = null
            
            val ratingSvg = document.selectFirst(".inline-flex.items-center.gap-3.rounded-2xl .text-\\[12px\\].font-extrabold")
            if (ratingSvg != null) {
                rating = ratingSvg.text().trim().toFloatOrNull()
                println("Rating do SVG: $rating")
            }
            
            if (rating == null) {
                val infoBar = document.selectFirst(".flex.gap-2.text-sm.flex-wrap.items-center")
                if (infoBar != null) {
                    val ratingSpan = infoBar.selectFirst(".text-lead")
                    if (ratingSpan != null) {
                        rating = ratingSpan.text().trim().toFloatOrNull()
                        println("Rating da barra: $rating")
                    }
                }
            }
            
            // ========== DURAÇÃO (apenas para filmes) ==========
            var duration: Int? = null
            if (!isAnime && !isSerie) {
                val infoBar = document.selectFirst(".flex.gap-2.text-sm.flex-wrap.items-center")
                if (infoBar != null) {
                    val durationSpan = infoBar.select("span").lastOrNull()
                    if (durationSpan != null) {
                        val durationText = durationSpan.text()
                        duration = parseDuration(durationText)
                        println("Duração: $duration minutos")
                    }
                }
            }
            
            // ========== ANO (se não encontrou no título) ==========
            if (year == null) {
                val infoBar = document.selectFirst(".flex.gap-2.text-sm.flex-wrap.items-center")
                if (infoBar != null) {
                    val yearSpan = infoBar.select("span").firstOrNull { it.text().matches(Regex("\\d{4}")) }
                    if (yearSpan != null) {
                        year = yearSpan.text().toIntOrNull()
                        println("Ano da barra: $year")
                    }
                }
            }
            
            // ========== SINOPSE ==========
            var synopsis = document.selectFirst(".text-slate-700.dark\\:text-slate-200.md\\:text-lg")?.text()?.trim()
            if (synopsis.isNullOrBlank()) {
                synopsis = document.selectFirst("meta[name='description']")?.attr("content")?.trim()
            }
            synopsis = synopsis?.replace(Regex("\\|.*$"), "")?.trim()
            println("Sinopse: ${synopsis?.take(100)}...")
            
            // ========== GÊNEROS/TAGS ==========
            val tags = document.select(".flex.flex-wrap.gap-2.pt-4 a")
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
                .takeIf { it.isNotEmpty() }
            println("Tags: $tags")
            
            // ========== ELENCO ==========
            val cast = document.select("#cast-section .swiper-slide, .cast-swiper .swiper-slide")
                .mapNotNull { element ->
                    try {
                        val name = element.selectFirst(".text-sm.font-bold")?.text()?.trim() ?: return@mapNotNull null
                        val imageUrl = element.selectFirst("img")?.attr("src")?.let { fixImageUrl(it) }
                        
                        Actor(
                            name = name,
                            image = imageUrl
                        )
                    } catch (e: Exception) { null }
                }
                .takeIf { it.isNotEmpty() }
            println("Elenco: ${cast?.size} atores")
            
            // ========== TRAILER ==========
            val trailerKey = document.selectFirst("script:containsData(window.__trailerKeys)")?.data()
                ?.let { Regex("window\\.__trailerKeys\\s*=\\s*\\[\"([^\"]+)\"\\]").find(it)?.groupValues?.get(1) }
            println("Trailer key: $trailerKey")
            
            // ========== RECOMENDAÇÕES ==========
            val recommendations = document.select("#relatedSection .swiper-slide a, .related-swiper .swiper-slide a")
                .mapNotNull { element ->
                    try {
                        val recUrl = element.attr("href")
                        if (recUrl.isBlank()) return@mapNotNull null
                        
                        val recImg = element.selectFirst("img")?.attr("src")?.let { fixImageUrl(it) }
                        val recTitle = element.selectFirst("h3, .text-white.font-bold")?.text()?.trim()
                            ?: return@mapNotNull null
                        
                        val recIsAnime = recUrl.contains("/anime/")
                        val recIsSerie = recUrl.contains("/serie/") || recUrl.contains("/dorama/")
                        val recYear = element.selectFirst(".text-white\\/70.text-xs")?.text()?.toIntOrNull()
                        
                        when {
                            recIsAnime -> newAnimeSearchResponse(recTitle, fixUrl(recUrl), TvType.Anime) {
                                this.posterUrl = recImg
                                this.year = recYear
                            }
                            recIsSerie -> newTvSeriesSearchResponse(recTitle, fixUrl(recUrl), TvType.TvSeries) {
                                this.posterUrl = recImg
                                this.year = recYear
                            }
                            else -> newMovieSearchResponse(recTitle, fixUrl(recUrl), TvType.Movie) {
                                this.posterUrl = recImg
                                this.year = recYear
                            }
                        }
                    } catch (e: Exception) { null }
                }
            println("Recomendações: ${recommendations.size}")
            
            // ========== PLAYER URL ==========
            val playerUrl = findPlayerUrl(document)
            println("Player URL: $playerUrl")
            
            // ========== RETORNAR RESPOSTA ==========
            if (!isAnime && !isSerie) {
                return newMovieLoadResponse(cleanTitle, url, TvType.Movie, playerUrl ?: url) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backdrop
                    this.year = year
                    this.plot = synopsis
                    this.tags = tags
                    this.duration = duration
                    this.recommendations = recommendations.takeIf { it.isNotEmpty() }
                    
                    if (rating != null) {
                        this.score = Score.from10(rating)
                        println("Score definido: $rating")
                    }
                    
                    if (cast != null && cast.isNotEmpty()) addActors(cast)
                    if (trailerKey != null) addTrailer("https://www.youtube.com/watch?v=$trailerKey")
                }
            }
            
            // SÉRIES/ANIMES - GUARDAR A URL DA SÉRIE NO DATA DO EPISÓDIO
            val episodes = extractEpisodesFromSite(document, url)
            val type = if (isAnime) TvType.Anime else TvType.TvSeries
            
            return newTvSeriesLoadResponse(cleanTitle, url, type, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = synopsis
                this.tags = tags
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
                
                if (rating != null) this.score = Score.from10(rating)
                if (cast != null && cast.isNotEmpty()) addActors(cast)
                if (trailerKey != null) addTrailer("https://www.youtube.com/watch?v=$trailerKey")
            }
            
        } catch (e: Exception) {
            println("ERRO em load: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    private suspend fun extractEpisodesFromSite(
        document: org.jsoup.nodes.Document,
        seriesUrl: String  // ← URL da série passada como parâmetro
    ): List<Episode> {
        println("  >>> extractEpisodesFromSite INICIADO")
        println("  URL: $seriesUrl")
        
        val episodes = mutableListOf<Episode>()

        val scriptData = document.selectFirst("script:containsData(window.allEpisodes)")?.data()
        if (scriptData != null) {
            println("  Script com allEpisodes encontrado")
            try {
                val jsonMatch = Regex("window\\.allEpisodes\\s*=\\s*(\\{[^;]+\\})").find(scriptData)
                val jsonString = jsonMatch?.groupValues?.get(1)
                
                if (jsonString != null) {
                    println("  JSON encontrado, tamanho: ${jsonString.length}")
                    val seasonPattern = Regex("\"(\\d+)\":\\s*\\[([^\\]]+)\\]")
                    val seasonMatches = seasonPattern.findAll(jsonString)
                    
                    var episodeCount = 0
                    for (seasonMatch in seasonMatches) {
                        val seasonNum = seasonMatch.groupValues[1].toIntOrNull() ?: 1
                        val episodesJson = seasonMatch.groupValues[2]
                        
                        val episodePattern = Regex("\\{[^}]*\"epi_num\"\\s*:\\s*(\\d+)[^}]*\"title\"\\s*:\\s*\"([^\"]*)\"[^}]*\"sinopse\"\\s*:\\s*\"([^\"]*)\"[^}]*\"thumb_url\"\\s*:\\s*\"([^\"]*)\"[^}]*\"duration\"\\s*:\\s*(\\d+)[^}]*\"air_date\"\\s*:\\s*\"([^\"]*)\"[^}]*\"has_dub\"\\s*:\\s*(true|false)[^}]*\"has_leg\"\\s*:\\s*(true|false)[^}]*\\}")
                        val episodeMatches = episodePattern.findAll(episodesJson)
                        
                        for (epMatch in episodeMatches) {
                            val epNum = epMatch.groupValues[1].toIntOrNull() ?: continue
                            val epTitle = epMatch.groupValues[2].ifEmpty { "Episódio $epNum" }
                            val sinopse = epMatch.groupValues[3].ifEmpty { null }
                            var thumbUrl = epMatch.groupValues[4].takeIf { it.isNotEmpty() }
                            val durationMin = epMatch.groupValues[5].toIntOrNull()
                            val airDate = epMatch.groupValues[6].takeIf { it.isNotEmpty() }
                            val hasDub = epMatch.groupValues[7].toBoolean()
                            val hasLeg = epMatch.groupValues[8].toBoolean()
                            
                            if (hasDub || hasLeg) {
                                thumbUrl = thumbUrl?.let { 
                                    if (it.startsWith("//")) {
                                        "https:$it"
                                    } else if (it.startsWith("/")) {
                                        "$mainUrl$it"
                                    } else if (!it.startsWith("http")) {
                                        "https://image.tmdb.org/t/p/w500$it"
                                    } else {
                                        it
                                    }
                                }
                                
                                if (thumbUrl.isNullOrBlank()) {
                                    val backdropElement = document.selectFirst("#movie-player-container")?.attr("data-backdrop")
                                    thumbUrl = backdropElement?.let { fixImageUrl(it) }
                                }
                                
                                val episodeUrl = "$seriesUrl/$seasonNum/$epNum"
                                
                                episodes.add(newEpisode(fixUrl(episodeUrl)) {
                                    this.name = epTitle
                                    this.season = seasonNum
                                    this.episode = epNum
                                    this.posterUrl = thumbUrl
                                    this.description = sinopse
                                    this.runTime = durationMin
                                    this.data = seriesUrl  // ← GUARDA A URL DA SÉRIE NO DATA
                                    if (airDate != null) {
                                        this.addDate(airDate)
                                    }
                                })
                                episodeCount++
                                println("    Episódio $epNum adicionado (DUB=$hasDub, LEG=$hasLeg)")
                            } else {
                                println("    Episódio $epNum ignorado - sem áudio disponível")
                            }
                        }
                    }
                    
                    if (episodes.isNotEmpty()) {
                        println("  Extraídos $episodeCount episódios do JSON")
                        return episodes
                    }
                }
            } catch (e: Exception) {
                println("  ERRO ao processar JSON: ${e.message}")
            }
        }

        // Fallback: extrair do HTML
        val episodeElements = document.select("#episodes-list article, .episode-card, .episode-item")
        println("  Elementos de episódio encontrados: ${episodeElements.size}")
        
        episodeElements.forEachIndexed { index, element ->
            try {
                val hasAudio = element.select(".absolute.start-3.bottom-3 .inline-flex").isNotEmpty()
                
                if (!hasAudio) {
                    println("    Episódio ${index + 1} ignorado - sem áudio disponível")
                    return@forEachIndexed
                }
                
                val link = element.selectFirst("a[href]") ?: return@forEachIndexed
                val episodeUrl = link.attr("href")
                if (episodeUrl.isBlank()) return@forEachIndexed

                val epNumberText = element.selectFirst(".text-lead.shrink-0")?.text() ?: "E${index + 1}"
                val epMatch = Regex("E(\\d+)", RegexOption.IGNORE_CASE).find(epNumberText)
                val epNumber = epMatch?.groupValues?.get(1)?.toIntOrNull() ?: (index + 1)
                
                var epTitle = element.selectFirst("h2, .truncate")?.text()?.trim()
                if (epTitle.isNullOrBlank()) {
                    epTitle = "Episódio $epNumber"
                }
                
                val sinopse = element.selectFirst(".line-clamp-2.text-xs")?.text()?.trim()
                
                var thumb: String? = null
                val imgElement = element.selectFirst("img")
                if (imgElement != null) {
                    thumb = imgElement.attr("data-src")
                    if (thumb.isNullOrBlank()) thumb = imgElement.attr("src")
                    
                    thumb = thumb?.let {
                        if (it.startsWith("//")) {
                            "https:$it"
                        } else if (it.startsWith("/")) {
                            "$mainUrl$it"
                        } else if (!it.startsWith("http")) {
                            "https://image.tmdb.org/t/p/w500$it"
                        } else {
                            it
                        }
                    }
                }
                
                var durationMin: Int? = null
                val durationText = element.selectFirst(".text-\\[11px\\].font-bold.absolute.end-3.bottom-3")?.text()
                if (!durationText.isNullOrBlank()) {
                    durationMin = durationText.replace("min", "").trim().toIntOrNull()
                }
                
                var airDate: String? = null
                val badgeText = element.selectFirst(".absolute.start-3.top-3")?.text()
                if (badgeText != null && badgeText.contains("Em breve")) {
                    airDate = badgeText.replace("Em breve •", "").trim()
                }

                episodes.add(newEpisode(fixUrl(episodeUrl)) {
                    this.name = epTitle
                    this.season = 1
                    this.episode = epNumber
                    this.posterUrl = thumb
                    this.description = sinopse
                    this.runTime = durationMin
                    this.data = seriesUrl  // ← GUARDA A URL DA SÉRIE NO DATA
                    if (airDate != null) {
                        this.addDate(airDate)
                    }
                })
                println("    Episódio $epNumber adicionado via HTML")
            } catch (e: Exception) {
                println("  ERRO ao processar elemento de episódio: ${e.message}")
            }
        }
        
        println("  Total de episódios extraídos: ${episodes.size}")
        return episodes
    }

    private fun findPlayerUrl(document: org.jsoup.nodes.Document): String? {
        val iframe = document.selectFirst("iframe[src*='player'], iframe[src*='embed'], iframe[src*='fembed'], iframe[src*='filemoon']")
        return iframe?.attr("src")
    }

    override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    println("=== loadLinks INICIADO ===")
    println("Data: $data")
    
    return try {
        val document = app.get(data).document
        println("Documento carregado")
        
        // Extrair season e episode da URL
        val seasonEpisodeMatch = Regex("-(\\d+)x(\\d+)$").find(data)
        val season = seasonEpisodeMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val episode = seasonEpisodeMatch?.groupValues?.get(2)?.toIntOrNull() ?: 1
        println("Season: $season, Episode: $episode")
        
        // EXTRAIR A URL DA SÉRIE - pegar o link dentro do header (primeiro link com ícone de seta)
        var seriesUrl: String? = null
        
        // Método 1: link com ícone de seta dentro do header (é o link "Voltar")
        val backLink = document.selectFirst(".flex.items-start.gap-4.flex-wrap .fa-arrow-left")?.parent()
        if (backLink != null) {
            seriesUrl = backLink.attr("href")
            println("Link Voltar encontrado: $seriesUrl")
        }
        
        // Método 2: se não encontrou, tenta pelo breadcrumb
        if (seriesUrl == null) {
            seriesUrl = document.selectFirst(".flex.items-start.gap-4.flex-wrap header a[href]")?.attr("href")
            println("Link do header encontrado: $seriesUrl")
        }
        
        // Método 3: fallback - extrair slug da URL do episódio
        if (seriesUrl == null) {
            val slugMatch = Regex("/episodio/(.+)-\\d+x\\d+").find(data)
            val slug = slugMatch?.groupValues?.get(1)
            if (slug != null) {
                val type = when {
                    data.contains("/anime/") -> "anime"
                    data.contains("/dorama/") -> "dorama"
                    else -> "serie"
                }
                seriesUrl = "$mainUrl/$type/$slug"
                println("URL construída do slug: $seriesUrl")
            }
        }
        
        if (seriesUrl != null) {
            seriesUrl = fixUrl(seriesUrl)
            println("URL da série final: $seriesUrl")
            
            val streams = PobreFlixExtractor.getStreams(seriesUrl, season, episode)
            
            if (streams.isEmpty()) {
                println("Nenhum stream encontrado")
                return false
            }
            
            println("Extractor encontrou ${streams.size} streams")
            streams.forEach { stream ->
                callback(stream)
            }
            true
        } else {
            println("Não foi possível encontrar a URL da série")
            false
        }
        
    } catch (e: Exception) {
        println("ERRO em loadLinks: ${e.message}")
        e.printStackTrace()
        false
    }
}
}
