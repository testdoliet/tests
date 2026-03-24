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
        
        // Seções principais
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
        println("Page: $page")
        println("Request name: ${request.name}")
        println("Request data: ${request.data}")
        
        var url = request.data
        
        // Construção da URL com paginação
        if (request.name != "Em Alta" && page > 1) {
            url = if (url.contains("?")) {
                "$url&page=$page"
            } else {
                "$url?page=$page"
            }
            println("URL com paginação: $url")
        }
        
        // Seção "Em Alta" (Top 10)
        if (request.name == "Em Alta") {
            println(">>> Processando seção: Em Alta")
            val document = app.get(url).document
            val elements = document.select(".swiper_top10_home .swiper-slide")
            println("Elementos encontrados no Top 10: ${elements.size}")
            
            val items = elements.mapNotNull { element ->
                element.toSearchResult()
            }
            
            return newHomePageResponse(request.name, items, hasNext = false)
        }
        
        // Seções genéricas (Filmes, Séries, Animes, Doramas)
        println(">>> Processando seção genérica: ${request.name}")
        val document = app.get(url).document
        println("URL carregada: $url")
        
        // Seletor correto para os cards das seções principais
        val elements = document.select(".grid .group\\/card")
        println("Elementos encontrados: ${elements.size}")
        
        val items = elements.mapNotNull { element ->
            element.toSearchResult()
        }
        
        val hasNextPage = document.select("a[href*='?page=']").any { link ->
            val href = link.attr("href")
            href.contains("?page=${page + 1}") || href.contains("&page=${page + 1}")
        } || document.select("a:contains(Próxima), .pagination a:contains(Próxima)").isNotEmpty()
        
        println("Has next page: $hasNextPage")
        
        return newHomePageResponse(request.name, items, hasNext = hasNextPage)
    }
    
    private fun Element.toSearchResult(): SearchResponse? {
        println("  >>> toSearchResult INICIADO")
        
        // Busca o link dentro do elemento
        var linkElement = selectFirst("a")
        if (linkElement == null) {
            linkElement = selectFirst("figure a")
        }
        if (linkElement == null) {
            println("  ERRO: linkElement é null")
            return null
        }
        
        var href = linkElement.attr("href")
        if (href.isBlank()) {
            println("  ERRO: href está em branco")
            return null
        }
        
        if (!href.startsWith("http")) {
            href = if (href.startsWith("/")) "$mainUrl$href" else "$mainUrl/$href"
        }
        println("  href: $href")
        
        // Busca a imagem
        var imgElement = selectFirst("img")
        if (imgElement == null) {
            imgElement = selectFirst("figure img")
        }
        
        var poster: String? = null
        
        if (imgElement != null) {
            poster = imgElement.attr("src")
            if (poster.isNullOrBlank()) {
                poster = imgElement.attr("data-src")
            }
            if (!poster.isNullOrBlank()) {
                // Remove o prefixo do CDN se existir
                if (poster.contains("d1muf25xaso8hp.cloudfront.net/")) {
                    poster = poster.substringAfter("d1muf25xaso8hp.cloudfront.net/")
                }
                poster = fixUrl(poster)
            }
            println("  poster: $poster")
        }
        
        // Busca o título - está no h3
        var title = selectFirst("h3")?.text()
        if (title.isNullOrBlank()) {
            title = selectFirst(".line-clamp-1")?.text()
        }
        if (title.isNullOrBlank()) {
            title = imgElement?.attr("alt")
        }
        println("  título original: $title")
        
        if (title.isNullOrBlank()) {
            println("  ERRO: título em branco")
            return null
        }
        
        // Extrai ano e tipo dos spans dentro da div de informações
        // Estrutura: <div class="flex justify-between items-center text-xs ...">
        //   <span>2020</span>
        //   <span>Filme</span>
        // </div>
        val infoDiv = selectFirst(".flex.justify-between.items-center.text-xs")
        var year: Int? = null
        var typeText = ""
        
        if (infoDiv != null) {
            val spans = infoDiv.select("span")
            if (spans.size >= 2) {
                // Primeiro span é o ano
                val yearStr = spans[0].text().trim()
                year = yearStr.toIntOrNull()
                println("  ano encontrado: $year")
                
                // Segundo span é o tipo
                typeText = spans[1].text().lowercase().trim()
                println("  tipo texto: $typeText")
            } else if (spans.size == 1) {
                // Se só tem um span, tenta identificar se é ano ou tipo
                val text = spans[0].text().trim()
                if (text.matches(Regex("\\d{4}"))) {
                    year = text.toIntOrNull()
                } else {
                    typeText = text.lowercase()
                }
            }
        }
        
        // Se não encontrou o ano no infoDiv, tenta extrair do título
        if (year == null) {
            val yearMatch = Regex("\\((\\d{4})\\)").find(title)
            if (yearMatch != null) {
                year = yearMatch.groupValues[1].toIntOrNull()
                println("  ano do título: $year")
                // Remove o ano do título
                title = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
            }
        }
        
        // Limpa o título
        val finalTitle = title!!.trim()
        println("  título final: '$finalTitle'")
        
        // Extrai score do SVG se existir
        var scoreValue: Float? = null
        val scoreElement = selectFirst(".absolute.top-2.right-2 svg text")
        if (scoreElement != null) {
            val scoreText = scoreElement.text().replace("%", "").trim()
            scoreValue = scoreText.toFloatOrNull()?.let { it / 10 }
            println("  score: $scoreValue")
        }
        
        // Determina o tipo baseado na URL e no texto
        val isAnime = href.contains("/anime/") || typeText.contains("anime")
        val isSerie = href.contains("/serie/") || href.contains("/dorama/") || 
                      typeText.contains("série") || typeText.contains("serie") || 
                      typeText.contains("dorama")
        
        println("  Tipo final: isAnime=$isAnime, isSerie=$isSerie")
        
        // Cria a resposta
        val result = when {
            isAnime -> {
                println("  >>> Criando ANIME")
                newAnimeSearchResponse(finalTitle, href, TvType.Anime) {
                    this.posterUrl = poster
                    this.year = year
                    if (scoreValue != null) this.score = Score.from10(scoreValue)
                }
            }
            isSerie -> {
                println("  >>> Criando SÉRIE")
                newTvSeriesSearchResponse(finalTitle, href, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = year
                    if (scoreValue != null) this.score = Score.from10(scoreValue)
                }
            }
            else -> {
                println("  >>> Criando FILME")
                newMovieSearchResponse(finalTitle, href, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year
                    if (scoreValue != null) this.score = Score.from10(scoreValue)
                }
            }
        }
        
        println("  <<< toSearchResult FINALIZADO")
        return result
    }

    override suspend fun search(query: String): List<SearchResponse> {
        println("=== search INICIADO ===")
        println("Query: $query")
        
        if (query.length < 2) return emptyList()
        
        return searchPage(query, 1)
    }
    
    private suspend fun searchPage(query: String, page: Int): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = if (page > 1) {
            "$mainUrl$SEARCH_PATH?s=$encodedQuery&page=$page"
        } else {
            "$mainUrl$SEARCH_PATH?s=$encodedQuery"
        }
        println("URL de busca: $searchUrl")
        
        val document = app.get(searchUrl).document
        println("Título da página: ${document.title()}")
        
        // Na página de pesquisa, os cards usam a classe .grid article.relative.group-item
        val elements = document.select(".grid article.relative.group-item, .grid article")
        println("Elementos encontrados na busca: ${elements.size}")
        
        val results = elements.mapNotNull { element ->
            try {
                element.toSearchResult()
            } catch (e: Exception) {
                println("ERRO ao processar resultado: ${e.message}")
                null
            }
        }
        
        // Verifica se há próxima página
        val hasNextPage = document.select("a[href*='page=${page + 1}']").isNotEmpty() ||
                          document.select("a:contains(Próxima), .pagination a:contains(Próxima)").isNotEmpty()
        
        println("Resultados processados: ${results.size}")
        println("Has next page: $hasNextPage")
        
        if (hasNextPage) {
            val nextPageResults = searchPage(query, page + 1)
            return results + nextPageResults
        }
        
        println("=== search FINALIZADO ===\n")
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        println("=== load INICIADO ===")
        println("URL: $url")
        
        val document = app.get(url).document
        println("Título da página: ${document.title()}")

        val titleElement = document.selectFirst("h1, .text-3xl.text-lead.font-bold")
        val title = titleElement?.text() ?: return null
        println("Título encontrado: $title")

        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
        println("Título limpo: $cleanTitle, ano: $year")

        val isAnime = url.contains("/anime/") || title.contains("(Anime)", ignoreCase = true)
        val isSerie = url.contains("/serie/") || url.contains("/dorama/") || 
                     (!isAnime && document.selectFirst("#episodes-list, .season-dropdown, .episode-card") != null)
        println("isAnime: $isAnime, isSerie: $isSerie")

        val ogImage = document.selectFirst("meta[property='og:image']")?.attr("content")
        val poster = ogImage?.let { fixUrl(it) }
        println("Poster: $poster")

        val synopsis = document.selectFirst(".text-slate-700.dark\\:text-slate-200.md\\:text-lg, .text-slate-900\\/90.dark\\:text-slate-100\\/90, meta[name='description']")?.attr("content")?.trim()
        println("Sinopse encontrada: ${synopsis?.take(100)}...")

        val tags = document.select(".flex.flex-wrap.gap-2 a, .px-3.py-1.rounded-full.text-xs.bg-slate-200")
            .map { it.text() }
            .takeIf { it.isNotEmpty() }
        println("Tags: $tags")

        val ratingPercent = document.selectFirst("text[x='18'][y='21']")?.text()?.replace("%", "")?.toFloatOrNull()
        val ratingValue = ratingPercent?.let { it / 10 }
        val score = ratingValue?.let { Score.from10(it) }
        println("Rating: $ratingPercent%, score: $score")

        val backdrop = document.selectFirst(".absolute.left-1\\/2 img, .blur-\\[4px\\] img")?.attr("src")?.let { fixUrl(it) }
        println("Backdrop: $backdrop")

        val durationText = document.selectFirst(".bg-slate-200.dark\\:bg-slate-700.rounded-lg.p-3:contains(min) .font-medium, .inline-flex.items-center.rounded-full.px-3.py-1:contains(min)")?.text()
        val duration = durationText?.let { 
            Regex("(\\d+)\\s*min").find(it)?.groupValues?.get(1)?.toIntOrNull()
        }
        println("Duração: $duration")

        val cast = document.select("#cast-section .swiper-slide .text-sm.font-bold, .swiper-slide .text-sm.font-bold")
            .map { it.text() }
            .takeIf { it.isNotEmpty() }
            ?.map { Actor(name = it) }
        println("Elenco: ${cast?.size} atores")

        val trailerKey = document.selectFirst("script:containsData(window.__trailerKeys)")?.data()?.let { script ->
            Regex("window\\.__trailerKeys\\s*=\\s*\\[\"([^\"]+)\"\\]").find(script)?.groupValues?.get(1)
        }
        println("Trailer key: $trailerKey")

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
        println("Recomendações: ${siteRecommendations.size}")

        return if (isAnime || isSerie) {
            val type = if (isAnime) TvType.Anime else TvType.TvSeries
            println("Carregando como Série/Anime, tipo: $type")
            val episodes = extractEpisodesFromSite(document, url)

            newTvSeriesLoadResponse(cleanTitle, url, type, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = synopsis
                this.tags = tags
                this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
                this.score = score

                if (cast != null && cast.isNotEmpty()) addActors(cast)
                if (trailerKey != null) addTrailer("https://www.youtube.com/watch?v=$trailerKey")
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

                if (cast != null && cast.isNotEmpty()) addActors(cast)
                if (trailerKey != null) addTrailer("https://www.youtube.com/watch?v=$trailerKey")
            }
        }
    }

    private suspend fun extractEpisodesFromSite(
        document: org.jsoup.nodes.Document,
        url: String
    ): List<Episode> {
        println("  >>> extractEpisodesFromSite INICIADO")
        println("  URL: $url")
        
        val episodes = mutableListOf<Episode>()

        // Tenta extrair do JSON primeiro
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

        // Fallback: extrair do HTML
        val episodeElements = document.select("#episodes-list article, .episode-card, .episode-item")
        println("  Elementos de episódio encontrados: ${episodeElements.size}")
        
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
                    if (thumb.isNullOrBlank()) thumb = imgElement.attr("src")
                    if (!thumb.isNullOrBlank()) thumb = fixUrl(thumb)
                }
                val description = element.selectFirst(".line-clamp-2.text-xs")?.text()

                episodes.add(newEpisode(fixUrl(dataUrl)) {
                    this.name = epTitle
                    this.season = seasonNumber
                    this.episode = epNumber
                    this.posterUrl = thumb
                    this.description = description
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
                            this.quality = 720
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
                            this.quality = 720
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
}
