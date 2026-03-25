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
            
            // ========== METADADOS DA BARRA SUPERIOR ==========
            var rating: Float? = null
            var duration: Int? = null
            
            val infoBar = document.selectFirst(".flex.gap-2.text-sm.flex-wrap.items-center")
            if (infoBar != null) {
                val ratingSpan = infoBar.selectFirst(".text-lead")
                if (ratingSpan != null) {
                    rating = ratingSpan.text().trim().toFloatOrNull()
                    println("Rating: $rating")
                }
                
                val yearSpan = infoBar.select("span").firstOrNull { it.text().matches(Regex("\\d{4}")) }
                if (yearSpan != null && year == null) {
                    year = yearSpan.text().toIntOrNull()
                    println("Ano da barra: $year")
                }
                
                val durationSpan = infoBar.select("span").lastOrNull()
                if (durationSpan != null) {
                    val durationText = durationSpan.text()
                    duration = parseDuration(durationText)
                    println("Duração: $duration minutos")
                }
            }
            
            // ========== SINOPSE ==========
            var synopsis = document.selectFirst(".text-slate-700.dark\\:text-slate-200.md\\:text-lg")?.text()?.trim()
            if (synopsis.isNullOrBlank()) {
                synopsis = document.selectFirst("meta[name='description']")?.attr("content")?.trim()
            }
            // Limpar metadados da sinopse
            synopsis = synopsis?.replace(Regex("\\|.*$"), "")?.trim()
            println("Sinopse: ${synopsis?.take(100)}...")
            
            // ========== GÊNEROS/TAGS - APENAS GÊNEROS, NADA MAIS ==========
            val tags = document.select(".flex.flex-wrap.gap-2 a, .flex.flex-wrap.gap-2 .px-3")
                .filter { element ->
                    // Excluir elementos que são do bloco de títulos alternativos
                    val parent = element.parent()
                    val isInDetails = parent?.attr("class")?.contains("details") == true ||
                                     parent?.parent()?.attr("class")?.contains("details") == true
                    
                    // Excluir elementos que não são gêneros (HD, ano, temporada, etc)
                    val text = element.text().trim()
                    val isGenre = text !in listOf("HD", "4K", "Full HD") &&
                                  !text.matches(Regex("\\d{4}")) &&
                                  !text.contains("Temporada", ignoreCase = true) &&
                                  !text.contains("Season", ignoreCase = true) &&
                                  !text.contains("Episódio", ignoreCase = true) &&
                                  !text.contains("Episode", ignoreCase = true)
                    
                    !isInDetails && isGenre
                }
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
                .takeIf { it.isNotEmpty() }
            println("Tags: $tags")
            
            // ========== ELENCO COM FOTOS ==========
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
            
            // Para séries/animes
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
        url: String
    ): List<Episode> {
        println("  >>> extractEpisodesFromSite INICIADO")
        println("  URL: $url")
        
        val episodes = mutableListOf<Episode>()

        // Tentar extrair do JSON window.allEpisodes
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
                        
                        // Padrão para extrair campos do JSON
                        val episodePattern = Regex("\\{[^}]*\"epi_num\"\\s*:\\s*(\\d+)[^}]*\"title\"\\s*:\\s*\"([^\"]*)\"[^}]*\"sinopse\"\\s*:\\s*\"([^\"]*)\"[^}]*\"thumb_url\"\\s*:\\s*\"([^\"]*)\"[^}]*\"duration\"\\s*:\\s*(\\d+)[^}]*\"air_date\"\\s*:\\s*\"([^\"]*)\"[^}]*\\}")
                        val episodeMatches = episodePattern.findAll(episodesJson)
                        
                        for (epMatch in episodeMatches) {
                            val epNum = epMatch.groupValues[1].toIntOrNull() ?: continue
                            val epTitle = epMatch.groupValues[2].ifEmpty { "Episódio $epNum" }
                            val sinopse = epMatch.groupValues[3].ifEmpty { null }
                            var thumbUrl = epMatch.groupValues[4].takeIf { it.isNotEmpty() }
                            
                            // CORREÇÃO: Construir URL correta da thumbnail
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
                            
                            val durationMin = epMatch.groupValues[5].toIntOrNull()
                            val airDate = epMatch.groupValues[6].takeIf { it.isNotEmpty() }
                            
                            val episodeUrl = "$url/$seasonNum/$epNum"
                            
                            episodes.add(newEpisode(fixUrl(episodeUrl)) {
                                this.name = epTitle
                                this.season = seasonNum
                                this.episode = epNum
                                this.posterUrl = thumbUrl
                                this.description = sinopse
                                this.runTime = durationMin
                                if (airDate != null) {
                                    this.addDate(airDate)
                                }
                            })
                            episodeCount++
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

        // Fallback: extrair do HTML (#episodes-list)
        val episodeElements = document.select("#episodes-list article, .episode-card, .episode-item")
        println("  Elementos de episódio encontrados: ${episodeElements.size}")
        
        episodeElements.forEachIndexed { index, element ->
            try {
                val link = element.selectFirst("a[href]") ?: return@forEachIndexed
                val dataUrl = link.attr("href")
                if (dataUrl.isBlank()) return@forEachIndexed

                // Extrair número do episódio do texto T1:E1
                val epNumberText = element.selectFirst(".text-lead.shrink-0")?.text() ?: "E${index + 1}"
                val epMatch = Regex("E(\\d+)", RegexOption.IGNORE_CASE).find(epNumberText)
                val epNumber = epMatch?.groupValues?.get(1)?.toIntOrNull() ?: (index + 1)
                
                // Extrair título do episódio
                var epTitle = element.selectFirst("h2, .truncate")?.text()?.trim()
                if (epTitle.isNullOrBlank()) {
                    epTitle = "Episódio $epNumber"
                }
                
                // Extrair sinopse
                val sinopse = element.selectFirst(".line-clamp-2.text-xs")?.text()?.trim()
                
                // CORREÇÃO: Extrair thumbnail com URL correta
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
                
                // Extrair duração
                var durationMin: Int? = null
                val durationText = element.selectFirst(".text-\\[11px\\].font-bold.absolute.end-3.bottom-3")?.text()
                if (!durationText.isNullOrBlank()) {
                    durationMin = durationText.replace("min", "").trim().toIntOrNull()
                }
                
                // Extrair data de exibição
                var airDate: String? = null
                val badgeText = element.selectFirst(".absolute.start-3.top-3")?.text()
                if (badgeText != null && badgeText.contains("Em breve")) {
                    airDate = badgeText.replace("Em breve •", "").trim()
                }

                episodes.add(newEpisode(fixUrl(dataUrl)) {
                    this.name = epTitle
                    this.season = 1
                    this.episode = epNumber
                    this.posterUrl = thumb
                    this.description = sinopse
                    this.runTime = durationMin
                    if (airDate != null) {
                        this.addDate(airDate)
                    }
                })
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
            
            val iframe = document.selectFirst("iframe[src*='player'], iframe[src*='embed'], iframe[src*='fembed'], iframe[src*='filemoon']")
            if (iframe != null) {
                val playerUrl = iframe.attr("src")
                println("Iframe encontrado: $playerUrl")
                
                val links = M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = playerUrl,
                    referer = playerUrl
                )
                
                if (links.isNotEmpty()) {
                    println("Links M3U8 gerados: ${links.size}")
                    links.forEach { callback(it) }
                    true
                } else {
                    println("Nenhum link M3U8, usando URL direta")
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = playerUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = playerUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    true
                }
            } else {
                val videoUrl = document.selectFirst("video source, source[src]")?.attr("src")
                if (videoUrl != null) {
                    println("Video URL encontrada: $videoUrl")
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = videoUrl,
                            type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else null
                        ) {
                            this.referer = data
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    true
                } else {
                    println("Nenhum iframe ou video encontrado")
                    false
                }
            }
        } catch (e: Exception) {
            println("ERRO em loadLinks: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    // ========== FUNÇÕES AUXILIARES ==========
    
    private fun fixImageUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        
        var fixedUrl = url.trim()
        
        // URLs relativas
        if (fixedUrl.startsWith("//")) {
            fixedUrl = "https:$fixedUrl"
        } else if (fixedUrl.startsWith("/") && !fixedUrl.startsWith("//")) {
            fixedUrl = "$mainUrl$fixedUrl"
        }
        
        // Remover wrapper do CDN e construir URL correta
        if (fixedUrl.contains("d1muf25xaso8hp.cloudfront.net/")) {
            val afterCdn = fixedUrl.substringAfter("d1muf25xaso8hp.cloudfront.net/")
            if (afterCdn.startsWith("https://")) {
                fixedUrl = afterCdn
            } else {
                fixedUrl = "https://image.tmdb.org/t/p/w500$afterCdn"
            }
        }
        
        return fixedUrl
    }
    
    private fun parseDuration(durationStr: String): Int? {
        val str = durationStr.lowercase().trim()
        
        val hoursMatch = Regex("(\\d+)\\s*h").find(str)
        val minutesMatch = Regex("(\\d+)\\s*m(?:in)?").find(str)
        
        val hours = hoursMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val minutes = minutesMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
        
        if (hours > 0 || minutes > 0) {
            return (hours * 60) + minutes
        }
        
        val justMinutes = Regex("(\\d+)\\s*m(?:in)?").find(str)
        return justMinutes?.groupValues?.get(1)?.toIntOrNull()
    }
}
