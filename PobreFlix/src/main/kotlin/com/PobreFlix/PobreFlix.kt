package com.PobreFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.nodes.Element
import java.net.URLEncoder

class PobreFlix : MainAPI() {
    override var mainUrl = "https://lospobreflix.site"
    override var name = "PobreFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Documentary)
    override val usesWebView = true

    companion object {
        private const val SEARCH_PATH = "/pesquisar"
        
        // Apenas as 6 seções principais
        private val MAIN_SECTIONS = listOf(
            "/episodios" to "Novos Episódios",
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
        println("Page: $page")
        println("Request name: ${request.name}")
        println("Request data: ${request.data}")
        
        var url = request.data
        
        // CONSTRUÇÃO CORRETA DA URL COM PAGINAÇÃO
        // Para todas as seções exceto "Em Alta" e "Novos Episódios" que já tratamos separadamente
        if (request.name != "Em Alta" && page > 1) {
            url = if (url.contains("?")) {
                "$url&page=$page"
            } else {
                "$url?page=$page"
            }
            println("URL com paginação: $url")
        }
        
        // Tratamento especial para a seção "Em Alta" (Top 10) - não tem paginação
        if (request.name == "Em Alta") {
            println(">>> Processando seção: Em Alta")
            val document = app.get(url).document
            println("URL carregada: $url")
            
            val elements = document.select(".swiper_top10_home .swiper-slide")
            println("Elementos encontrados no Top 10: ${elements.size}")
            
            val items = elements.mapNotNull { element ->
                element.toSearchResult(isEpisodePage = false)
            }
            
            return newHomePageResponse(request.name, items, hasNext = false)
        }
        
        // Tratamento especial para "Novos Episódios"
        if (request.name == "Novos Episódios") {
            println(">>> Processando seção: Novos Episódios")
            val document = app.get(url).document
            println("URL carregada: $url")
            
            // Na página de episódios, os cards são <article> diretos dentro do grid
            val elements = document.select(".grid article")
            println("Elementos encontrados com '.grid article': ${elements.size}")
            
            val items = elements.mapNotNull { element ->
                element.toSearchResult(isEpisodePage = true)
            }
            println("Items processados: ${items.size}")
            
            // Verificar se existe próxima página - o site tem paginação em /episodios?page=2
            val hasNextPage = document.select("a:contains(Próxima), .page-numbers a[href*='page='], .pagination a[href*='page=']").isNotEmpty()
            println("Has next page: $hasNextPage")
            
            return newHomePageResponse(
                list = HomePageList(request.name, items, isHorizontalImages = true),
                hasNext = hasNextPage
            )
        }
        
        // Para as outras seções (Filmes, Séries, Animes, Doramas)
        println(">>> Processando seção genérica: ${request.name}")
        val document = app.get(url).document
        println("URL carregada: $url")
        println("Título da página: ${document.title()}")
        
        // Seletores para diferentes tipos de página
        val elements = document.select(".grid .group\\/card")
        println("Elementos encontrados com '.grid .group/card': ${elements.size}")
        
        val items = elements.mapNotNull { element ->
            element.toSearchResult(isEpisodePage = false)
        }
        println("Items processados: ${items.size}")
        
        // Verificar se existe próxima página - procurando links com ?page=2, ?page=3 etc.
        val hasNextPage = document.select("a[href*='?page=']").any { link ->
            val href = link.attr("href")
            // Verifica se existe link para página page+1
            href.contains("?page=${page + 1}") || href.contains("&page=${page + 1}")
        } || document.select("a:contains(Próxima), .page-numbers a:contains(Próxima), .pagination a:contains(Próxima)").isNotEmpty()
        
        println("Has next page: $hasNextPage")
        println("=== getMainPage FINALIZADO ===\n")
        
        return newHomePageResponse(request.name, items, hasNext = hasNextPage)
    }
    
    private fun Element.toSearchResult(isEpisodePage: Boolean = false): SearchResponse? {
        println("  >>> toSearchResult INICIADO (isEpisodePage=$isEpisodePage)")
        
        // Busca o link dentro do elemento
        val linkElement = selectFirst("a[href]")
        if (linkElement == null) {
            println("  ERRO: linkElement é null")
            return null
        }
        
        var href = linkElement.attr("href")
        if (href.isBlank()) {
            println("  ERRO: href está em branco")
            return null
        }
        
        // Se for href relativo, torna absoluto
        if (!href.startsWith("http")) {
            href = if (href.startsWith("/")) {
                "$mainUrl$href"
            } else {
                "$mainUrl/$href"
            }
        }
        println("  href: $href")
        
        // Busca a imagem
        val imgElement = selectFirst("img")
        var poster: String? = null
        
        if (imgElement != null) {
            poster = imgElement.attr("data-src")
            if (poster.isNullOrBlank()) {
                poster = imgElement.attr("src")
            }
            println("  poster original: $poster")
            
            if (!poster.isNullOrBlank()) {
                if (poster.contains("d1muf25xaso8hp.cloudfront.net/")) {
                    poster = poster.substringAfter("d1muf25xaso8hp.cloudfront.net/")
                }
                poster = fixUrl(poster)
            }
            println("  poster corrigido: $poster")
        }
        
        // Variáveis para extrair informações
        var title: String? = null
        var animeTitle: String? = null
        var episodeNumber: Int? = null
        var seasonNumber: Int? = null
        var isDubbed = false
        var scoreValue: Float? = null
        
        if (isEpisodePage) {
            // ========== PÁGINA DE EPISÓDIOS ==========
            println("  --- Processando card de EPISÓDIO ---")
            
            // 1. Extrai temporada e episódio do badge T1:E12
            val seasonText = selectFirst(".text-lead.font-black")?.text()
            if (seasonText != null) {
                val seasonMatch = Regex("T(\\d+):E(\\d+)").find(seasonText)
                if (seasonMatch != null) {
                    seasonNumber = seasonMatch.groupValues[1].toIntOrNull() ?: 1
                    episodeNumber = seasonMatch.groupValues[2].toIntOrNull() ?: 1
                    println("  Temporada: $seasonNumber, Episódio: $episodeNumber")
                }
            }
            
            // 2. Extrai o título do anime (que está no hover - div com line-clamp-1)
            val hoverDiv = selectFirst(".absolute.inset-x-0.bottom-0.z-10")
            if (hoverDiv != null) {
                animeTitle = hoverDiv.selectFirst(".line-clamp-1")?.text()
                println("  título do anime (hover): $animeTitle")
            }
            
            // 3. Se não encontrou no hover, tenta no título do episódio
            if (animeTitle.isNullOrBlank()) {
                val episodeTitleElement = selectFirst(".line-clamp-2.text-white") ?: selectFirst(".line-clamp-2")
                val episodeTitle = episodeTitleElement?.text()
                println("  título do episódio: $episodeTitle")
                
                if (episodeTitle != null) {
                    animeTitle = episodeTitle
                        .replace(Regex("Epis[oó]dio\\s*\\d+", RegexOption.IGNORE_CASE), "")
                        .replace(Regex("E\\d+", RegexOption.IGNORE_CASE), "")
                        .replace(Regex("T\\d+:E\\d+", RegexOption.IGNORE_CASE), "")
                        .trim()
                }
            }
            
            // 4. Se ainda não tem título, usa o alt da imagem
            if (animeTitle.isNullOrBlank() && imgElement != null) {
                animeTitle = imgElement.attr("alt")
                println("  título do anime (alt): $animeTitle")
            }
            
            // 5. Extrai badge de idioma (DUB/LEG) - badge superior
            val languageElement = selectFirst(".absolute.inset-x-0.top-0 .inline-flex.items-center.gap-1")
            if (languageElement != null) {
                val languageText = languageElement.text()
                isDubbed = languageText.contains("DUB", ignoreCase = true)
                println("  idioma: ${if (isDubbed) "DUB" else "LEG"}")
            }
            
            // 6. Extrai badge "Novo" se existir
            val isNew = selectFirst(".bg-emerald-500") != null
            println("  é novo: $isNew")
            
            // Título final para exibição
            title = if (episodeNumber != null && episodeNumber > 0) {
                "$animeTitle - Episódio $episodeNumber"
            } else {
                animeTitle
            }
            
        } else {
            // ========== PÁGINAS NORMAIS (Filmes, Séries, Animes, Doramas) ==========
            println("  --- Processando card NORMAL ---")
            
            // Título está no alt da imagem ou no h3
            if (imgElement != null) {
                title = imgElement.attr("alt")
                println("  título original (alt): $title")
            }
            if (title.isNullOrBlank()) {
                title = selectFirst("h3")?.text()
                println("  título original (h3): $title")
            }
            
            // Extrai badge de idioma (DUB/LEG) - badge inferior esquerdo
            val languageElement = selectFirst(".absolute.bottom-2.left-2 .inline-flex")
            if (languageElement != null) {
                val languageText = languageElement.text()
                isDubbed = languageText.contains("DUB", ignoreCase = true)
                println("  idioma: ${if (isDubbed) "DUB" else "LEG"}")
            }
            
            // Extrai score (porcentagem) - badge superior direito
            val scoreElement = selectFirst(".absolute.top-2.right-2 svg text")
            if (scoreElement != null) {
                val scoreText = scoreElement.text().replace("%", "").trim()
                scoreValue = scoreText.toFloatOrNull()
                println("  score: $scoreText%")
            }
        }
        
        if (title.isNullOrBlank()) {
            println("  ERRO: título está em branco")
            return null
        }
        
        // Remove a palavra "poster" do título se existir
        val cleanedTitle = title!!.replace(Regex("\\s+poster$", RegexOption.IGNORE_CASE), "").trim()
        println("  título após remover 'poster': $cleanedTitle")
        
        // Extrai ano do título se disponível
        val year = Regex("\\((\\d{4})\\)").find(cleanedTitle)?.groupValues?.get(1)?.toIntOrNull()
        val finalTitle = cleanedTitle.replace(Regex("\\(\\d{4}\\)"), "").trim()
        println("  título final: $finalTitle, ano: $year")
        
        // Determina o tipo baseado na URL
        val isAnime = href.contains("/anime/") || (isEpisodePage && href.contains("/episodio/") && href.contains("anime"))
        val isSerie = href.contains("/serie/") || href.contains("/dorama/") || (!isAnime && !isEpisodePage && href.contains("/serie/"))
        val isEpisode = href.contains("/episodio/")
        
        println("  isAnime: $isAnime, isSerie: $isSerie, isEpisode: $isEpisode")
        
        // Cria a resposta
        val result = when {
            isAnime -> {
                println("  >>> Criando resposta de ANIME")
                newAnimeSearchResponse(finalTitle, href, TvType.Anime) {
                    this.posterUrl = poster
                    this.year = year
                    if (scoreValue != null) {
                        this.score = Score.from10(scoreValue / 10)
                    }
                }
            }
            isSerie -> {
                println("  >>> Criando resposta de SÉRIE")
                newTvSeriesSearchResponse(finalTitle, href, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = year
                    if (scoreValue != null) {
                        this.score = Score.from10(scoreValue / 10)
                    }
                }
            }
            else -> {
                println("  >>> Criando resposta de FILME")
                newMovieSearchResponse(finalTitle, href, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year
                    if (scoreValue != null) {
                        this.score = Score.from10(scoreValue / 10)
                    }
                }
            }
        }
        
        // Adiciona o status de dublagem após a criação
        if (result is AnimeSearchResponse) {
            result.addDubStatus(if (isDubbed) DubStatus.Dubbed else DubStatus.Subbed, null)
        }
        
        println("  <<< toSearchResult FINALIZADO com sucesso")
        return result
    }

    override suspend fun search(query: String): List<SearchResponse> {
        println("=== search INICIADO ===")
        println("Query: $query")
        
        val searchUrl = "$mainUrl$SEARCH_PATH?s=${URLEncoder.encode(query, "UTF-8")}"
        println("URL de busca: $searchUrl")
        
        val document = app.get(searchUrl).document
        
        val elements = document.select(".grid .group\\/card, .grid article, .swiper-slide article")
            .filter { element ->
                val link = element.selectFirst("a")
                val href = link?.attr("href") ?: ""
                !href.contains("/episodio/")
            }
        println("Elementos encontrados na busca: ${elements.size}")
        
        val results = elements.mapNotNull { card ->
            try {
                card.toSearchResult(isEpisodePage = false)
            } catch (e: Exception) {
                println("ERRO ao processar resultado de busca: ${e.message}")
                null
            }
        }
        
        println("Resultados processados: ${results.size}")
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        println("=== load INICIADO ===")
        println("URL: $url")
        
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
                this.plot = synopsis
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
                this.plot = synopsis
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
                            var thumbUrl = epMatch.groupValues[3].takeIf { it.isNotEmpty() }
                            thumbUrl = thumbUrl?.let { fixUrl(it) }
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
                    
                    if (episodes.isNotEmpty()) {
                        return episodes
                    }
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
                var thumb: String? = null
                val imgElement = element.selectFirst("img")
                if (imgElement != null) {
                    thumb = imgElement.attr("data-src")
                    if (thumb.isNullOrBlank()) {
                        thumb = imgElement.attr("src")
                    }
                    if (!thumb.isNullOrBlank()) {
                        thumb = fixUrl(thumb)
                    }
                }
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
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            false
        }
    }
}
