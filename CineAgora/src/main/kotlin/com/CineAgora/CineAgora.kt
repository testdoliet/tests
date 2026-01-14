package com.CineAgora

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import java.net.URLEncoder

class CineAgora : MainAPI() {
    override var mainUrl = "https://cineagora.net"
    override var name = "CineAgora"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val usesWebView = false

    companion object {
        // Seções que estão na página principal (com URLs específicas)
        private val HOME_SECTIONS = listOf(
            "ultimos-filmes" to "Últimos Filmes",
            "ultimas-series" to "Últimas Séries"
        )
        
        // Seções com URLs específicas
        private val SECTION_URLS = mapOf(
            // Links específicos para as seções da home
            "ultimos-filmes" to "https://cineagora.net/filmes-hd-online/",
            "ultimas-series" to "https://cineagora.net/series-online-hd-gratis/",
            // Outras seções
            "filmes-populares" to "https://cineagora.net/filmes-hd-online/filmes-populares-hd/",
            "series-populares" to "https://cineagora.net/series-online-hd-gratis/series-populares-hd/",
            "netflix" to "https://cineagora.net/netflix/",
            "paramount" to "https://cineagora.net/paramount/",
            "disney" to "https://cineagora.net/disney/",
            "apple" to "https://cineagora.net/apple/",
            "hbo" to "https://cineagora.net/hbo/",
            "acao" to "https://cineagora.net/filmes-hd-online/filmes-de-acao-hd/",
            "aventura" to "https://cineagora.net/filmes-hd-online/filmes-de-aventura-gratis/",
            "animacao" to "https://cineagora.net/filmes-hd-online/filmes-de-animacao-online/",
            "biograficos" to "https://cineagora.net/filmes-hd-online/assistir-filmes-biograficos/",
            "comedia" to "https://cineagora.net/filmes-hd-online/comedia-filmes-online/",
            "crime" to "https://cineagora.net/filmes-hd-online/crime-filmes-online/",
            "documentarios" to "https://cineagora.net/filmes-hd-online/documentarios-em-portugues/",
            "esporte" to "https://cineagora.net/filmes-hd-online/filmes-de-esporte-hd/",
            "drama" to "https://cineagora.net/filmes-hd-online/filmes-drama-online-hd/",
            "familia" to "https://cineagora.net/filmes-hd-online/filmes-familia-online/",
            "fantasia" to "https://cineagora.net/filmes-hd-online/filmes-fantasia-magia/",
            "historicos" to "https://cineagora.net/filmes-hd-online/filmes-historicos-hd/",
            "terror" to "https://cineagora.net/filmes-hd-online/filmes-terror-horror/",
            "musicais" to "https://cineagora.net/filmes-hd-online/filmes-musicais-online/",
            "misterio" to "https://cineagora.net/filmes-hd-online/filmes-misterio-suspense/",
            "romanticos" to "https://cineagora.net/filmes-hd-online/filmes-romanticos-online/",
            "suspense" to "https://cineagora.net/filmes-hd-online/filmes-suspense-hd/",
            "sci-fi" to "https://cineagora.net/filmes-hd-online/ficcao-cientifica-hd/",
            "tv" to "https://cineagora.net/filmes-hd-online/filmes-para-tv-hd/",
            "thriller" to "https://cineagora.net/filmes-hd-online/thriller-suspense-online/",
            "guerra" to "https://cineagora.net/filmes-hd-online/filmes-guerra-epicas/",
            "faroeste" to "https://cineagora.net/filmes-hd-online/filmes-faroeste-online/"
        )
    }

    override val mainPage = mainPageOf(
        *HOME_SECTIONS.map { (section, name) -> 
            "home_$section" to name 
        }.toTypedArray(),
        *SECTION_URLS.filterKeys { it !in HOME_SECTIONS.map { it.first } }
                     .map { (section, _) ->
                         "section_$section" to getSectionName(section)
                     }.toTypedArray()
    )

    private fun getSectionName(section: String): String {
        return when (section) {
            "ultimos-filmes" -> "Últimos Filmes"
            "ultimas-series" -> "Últimas Séries"
            "filmes-populares" -> "Filmes Populares"
            "series-populares" -> "Séries Populares"
            "netflix" -> "Netflix"
            "paramount" -> "Paramount+"
            "disney" -> "Disney+"
            "apple" -> "Apple TV+"
            "hbo" -> "HBO Max"
            "acao" -> "Ação"
            "aventura" -> "Aventura"
            "animacao" -> "Animação"
            "biograficos" -> "Biográficos"
            "comedia" -> "Comédia"
            "crime" -> "Crime"
            "documentarios" -> "Documentários"
            "esporte" -> "Esporte"
            "drama" -> "Drama"
            "familia" -> "Família"
            "fantasia" -> "Fantasia"
            "historicos" -> "Históricos"
            "terror" -> "Terror"
            "musicais" -> "Musicais"
            "misterio" -> "Mistério"
            "romanticos" -> "Românticos"
            "suspense" -> "Suspense"
            "sci-fi" -> "Sci-Fi"
            "tv" -> "TV"
            "thriller" -> "Thriller"
            "guerra" -> "Guerra"
            "faroeste" -> "Faroeste"
            else -> section.replace("-", " ").split(" ").joinToString(" ") { 
                it.replaceFirstChar { char -> char.uppercase() }
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sectionId = request.data.removePrefix("home_").removePrefix("section_")
        
        // Usar URL específica para cada seção
        val baseUrl = SECTION_URLS[sectionId] ?: mainUrl
        
        // Verificar se a página atual é maior que 1 para adicionar /page/N/
        val url = if (page > 1) {
            // Verificar se a URL base já tem uma barra no final
            val cleanUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            "$cleanUrl/page/$page/"
        } else {
            baseUrl
        }
        
        val document = try {
            app.get(url).document
        } catch (e: Exception) {
            // Se falhar na paginação, pode ser que a seção não suporte
            if (page > 1) {
                // Retorna lista vazia se não houver mais páginas
                return newHomePageResponse(request.name, emptyList(), false)
            } else {
                throw e
            }
        }
        
        val items = extractSectionItems(document, sectionId)
        
        // Verificar se há botões de paginação para determinar se há mais páginas
        val hasNextPage = checkForNextPage(document, page)
        
        return newHomePageResponse(request.name, items.distinctBy { it.url }, hasNextPage)
    }

    private fun checkForNextPage(document: org.jsoup.nodes.Document, currentPage: Int): Boolean {
        // Verificar botões de paginação
        val pagination = document.select(".pagination, .nav-links, .page-numbers, a[href*='page/']")
        
        // Verificar se há algum link para a próxima página
        val nextPageLinks = pagination.filter { element ->
            val href = element.attr("href")
            val text = element.text().lowercase()
            href.contains("/page/${currentPage + 1}/") || 
            text.contains("próxima") || 
            text.contains("next") ||
            element.hasClass("next") ||
            element.hasClass("next-page")
        }
        
        // Ou verificar se há número da próxima página
        val pageNumbers = document.select(".page-numbers, .page-number, [class*='page']")
            .filter { it.text().matches(Regex("\\d+")) }
            .mapNotNull { it.text().toIntOrNull() }
            .sorted()
        
        // Se houver número maior que a página atual
        if (pageNumbers.any { it > currentPage }) {
            return true
        }
        
        return nextPageLinks.isNotEmpty()
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        
        val searchUrl = mainUrl
        
        try {
            val document = app.post(
                url = searchUrl,
                data = mapOf(
                    "do" to "search",
                    "subaction" to "search",
                    "story" to query
                ),
                referer = searchUrl,
                headers = mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "Origin" to mainUrl,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
                )
            ).document
            
            return extractSearchResults(document)
            
        } catch (e: Exception) {
            try {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val fallbackUrl = "$mainUrl/?do=search&subaction=search&story=$encodedQuery"
                
                val document = app.get(fallbackUrl).document
                return extractSearchResults(document)
            } catch (e2: Exception) {
                return emptyList()
            }
        }
    }

    private fun extractSearchResults(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val searchItems = document.select(".film-list .content .col-6.col-sm-4.col-md-3.col-lg-2 .item-relative > a.item")
        
        return if (searchItems.isNotEmpty()) {
            searchItems.mapNotNull { it.toSearchResult() }
        } else {
            document.select(".item, .item-relative .item, .poster, .movie-item, .serie-item")
                .mapNotNull { it.toSearchResult() }
        }
    }

    private fun extractSectionItems(document: org.jsoup.nodes.Document, sectionId: String): List<SearchResponse> {
        val items = document.select(".item, .item-relative .item, .poster, .movie-item, .serie-item")
        return items.mapNotNull { it.toSearchResult() }
    }

    private fun extractScoreAdvanced(element: Element): Pair<String?, String?> {
        val selectors = listOf(
            ".item-info-ust .rating" to "Seletor rating principal",
            ".rating" to "Seletor .rating",
            ".score" to "Seletor .score",
            ".item-info + div" to "Próximo ao item-info",
            ".item-footer span" to "No rodapé do item",
            "span:contains(★)" to "Span com estrela",
            "span:contains(/10)" to "Span com /10",
            "[class*='rating']" to "Classe contém 'rating'",
            "[class*='score']" to "Classe contém 'score'",
            ".item-info-ust div" to "Div dentro de item-info-ust",
            "small" to "Tag small",
            "b" to "Tag bold",
            "i" to "Tag italic"
        )

        for ((selector, _) in selectors) {
            val found = element.selectFirst(selector)?.text()?.trim()
            if (!found.isNullOrBlank() && isScoreLike(found)) {
                return found to selector
            }
        }

        element.parent()?.let { parent ->
            for ((selector, _) in selectors) {
                val found = parent.selectFirst(selector)?.text()?.trim()
                if (!found.isNullOrBlank() && isScoreLike(found)) {
                    return found to "parent.$selector"
                }
            }
        }

        val html = element.outerHtml()
        val scoreRegexes = listOf(
            Regex("""(\d+\.\d+|\d+)\s*(?:★|/10|pontos)"""),
            Regex("""class="[^"]*(?:rating|score)[^"]*">([^<]+)""")
        )

        for (regex in scoreRegexes) {
            val match = regex.find(html)
            if (match != null) {
                val found = match.groupValues[1].trim()
                if (isScoreLike(found)) {
                    return found to "regex"
                }
            }
        }

        return null to null
    }

    private fun isScoreLike(text: String): Boolean {
        return when {
            text.equals("N/A", ignoreCase = true) -> true
            text.matches(Regex("""^\d+(\.\d+)?$""")) -> true
            text.matches(Regex("""^\d+(\.\d+)?/10$""")) -> true
            text.contains("★") -> true
            text.contains("pontos", ignoreCase = true) -> true
            else -> false
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a")
        val href = linkElement?.attr("href")?.takeIf { it.isNotBlank() } ?: return null
        
        val titleElement = selectFirst(".item-footer .title, .title, .poster-title, h3, h4")
        val title = titleElement?.text()?.trim() ?: return null
        
        val year = selectFirst(".info span:first-child, .year, .date")?.text()?.toIntOrNull()
            ?: Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        
        val cleanTitle = title
            .replace(Regex("\\(\\d{4}\\)"), "")
            .replace(Regex("\\d{4}$"), "")
            .trim()
        
        val imgElement = selectFirst("img.thumbnail, img.poster, img")
        val posterUrl = imgElement?.attr("src")?.let { fixUrl(it) }
        
        val qualityBadge = select(".item-info, .quality, .badge").firstOrNull()?.selectFirst("div:first-child, span")?.text()?.trim()
        
        val languageBadge = select(".item-info, .language, .badge").firstOrNull()?.selectFirst("div:nth-child(2), .lang")?.text()?.trim()
        
        val scoreResult = extractScoreAdvanced(this)
        val scoreText = scoreResult.first
        val score = when {
            scoreText == null || scoreText == "N/A" -> null
            else -> scoreText.toFloatOrNull()?.let { Score.from10(it) }
        }
        
        val lastEpisodeInfo = select(".item-info, .episode, .data").getOrNull(1)?.selectFirst("small, .last-ep")?.text()?.trim()
            ?: selectFirst(".data, .episode-info")?.text()?.trim()
        
        val isSerie = href.contains("/series-") || href.contains("/serie-") || href.contains("/tv-") || 
                      href.contains("/series-online") ||
                      lastEpisodeInfo?.contains(Regex("S\\d+.*E\\d+")) == true ||
                      title.contains(Regex("(?i)(temporada|episódio|season|episode)"))
        
        val quality = when {
            qualityBadge?.contains("HD", ignoreCase = true) == true -> SearchQuality.HD
            qualityBadge?.contains("4K", ignoreCase = true) == true -> SearchQuality.HD
            qualityBadge?.contains("FULLHD", ignoreCase = true) == true -> SearchQuality.HD
            qualityBadge?.contains("TS", ignoreCase = true) == true -> SearchQuality.Cam
            else -> null
        }
        
        return if (isSerie) {
            newTvSeriesSearchResponse(cleanTitle, fixUrl(href)) {
                this.posterUrl = posterUrl
                this.year = year
                this.score = score
                if (quality != null) {
                    this.quality = quality
                }
            }
        } else {
            newMovieSearchResponse(cleanTitle, fixUrl(href)) {
                this.posterUrl = posterUrl
                this.year = year
                this.score = score
                if (quality != null) {
                    this.quality = quality
                }
            }
        }
    }

    // =============================================
    // FUNÇÕES AUXILIARES
    // =============================================

    private fun extractBannerUrl(doc: org.jsoup.nodes.Document): String? {
        val bannerSelectors = listOf(
            "meta[property='og:image']",
            ".cover-img",
            "img.banner",
            ".hero img",
            "[class*='banner'] img",
            "picture img",
            ".poster.large"
        )
        
        for (selector in bannerSelectors) {
            val element = doc.selectFirst(selector)
            if (element != null) {
                val url = when (selector) {
                    "meta[property='og:image']" -> element.attr("content")
                    else -> element.attr("src")
                }
                
                if (url.isNotBlank()) {
                    return fixUrl(url)
                }
            }
        }
        
        return null
    }

    private fun extractYear(doc: org.jsoup.nodes.Document): Int? {
        return doc.selectFirst(".year, .date, time")?.text()?.toIntOrNull()
            ?: Regex("(\\d{4})").find(doc.selectFirst("h1")?.text() ?: "")?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractGenres(doc: org.jsoup.nodes.Document): List<String>? {
        val genres = doc.select(".genres a, .genre a, .category a, a[href*='genero'], a[href*='categoria']")
            .mapNotNull { it.text().trim() }
            .filter { it.isNotBlank() }
        
        return if (genres.isNotEmpty()) genres else null
    }

    private fun extractTrailer(doc: org.jsoup.nodes.Document): String? {
        val youtubePatterns = listOf(
            """youtube\.com/embed/([^"']+)""",
            """youtube\.com/watch\?v=([^"']+)""",
            """youtu\.be/([^"']+)"""
        )
        
        val html = doc.html()
        for (pattern in youtubePatterns) {
            val regex = Regex(pattern)
            val match = regex.find(html)
            if (match != null) {
                val videoId = match.groupValues[1]
                return "https://www.youtube.com/watch?v=$videoId"
            }
        }
        
        return null
    }

    // =============================================
    // FUNÇÃO PARA OBTER SERIESLUG (CORRIGIDA)
    // =============================================

    private suspend fun getSeriesSlugFromCineAgoraPage(doc: org.jsoup.nodes.Document, baseUrl: String): String? {
        println("[CineAgora] Buscando seriesSlug da página do CineAgora")
        
        // Método 1: Tentar extrair dos scripts
        val scripts = doc.select("script")
        for (script in scripts) {
            val scriptText = script.html()
            if (scriptText.contains("seriesSlug")) {
                val slugRegex = Regex("""var\s+seriesSlug\s*=\s*["']([^"']+)["']""")
                val match = slugRegex.find(scriptText)
                if (match != null) {
                    val slug = match.groupValues[1].trim()
                    println("[CineAgora] Series Slug encontrado no script: $slug")
                    return slug
                }
            }
        }
        
        // Método 2: Tentar extrair da URL do iframe
        val iframe = doc.selectFirst("iframe[src*='watch.brplayer.cc']")
        if (iframe != null) {
            val iframeSrc = iframe.attr("src")
            println("[CineAgora] Iframe encontrado: $iframeSrc")
            
            // Extrair videoSlug do iframe
            val videoSlug = iframeSrc.substringAfter("/watch/").substringBefore("?")
            println("[CineAgora] Video slug do iframe: $videoSlug")
            
            // Usar a API para obter o seriesSlug do videoSlug
            try {
                val apiUrl = "https://watch.brplayer.cc/get_series_from_video.php?videoSlug=$videoSlug"
                val response = app.get(apiUrl, referer = baseUrl).text.trim()
                if (response.isNotBlank()) {
                    println("[CineAgora] Série obtida da API: $response")
                    return response
                }
            } catch (e: Exception) {
                println("[CineAgora] Erro ao obter série da API: ${e.message}")
            }
        }
        
        // Método 3: Tentar extrair da URL da página
        try {
            val slugFromUrl = baseUrl
                .substringAfterLast("/")           // 2845-dona-de-mim.html
                .substringAfter("-")               // dona-de-mim.html
                .substringBefore(".html")          // dona-de-mim
                .trim()
            
            if (slugFromUrl.isNotBlank()) {
                println("[CineAgora] Slug extraído da URL: $slugFromUrl")
                return slugFromUrl
            }
        } catch (e: Exception) {
            println("[CineAgora] Erro ao extrair slug da URL: ${e.message}")
        }
        
        println("[CineAgora] Não foi possível encontrar seriesSlug")
        return null
    }

// =============================================
// FUNÇÃO PRINCIPAL PARA EXTRAIR EPISÓDIOS (CORRIGIDA)
// =============================================

private suspend fun extractEpisodesFromCineAgoraPage(doc: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
    val episodes = mutableListOf<Episode>()
    
    println("[CineAgora] Extraindo episódios da página do CineAgora: $baseUrl")
    
    // 1. PRIMEIRO: Tentar extrair diretamente do HTML (SEM API)
    val episodesFromHtml = extractEpisodesFromHtml(doc, baseUrl)
    if (episodesFromHtml.isNotEmpty()) {
        println("[CineAgora] Encontrados ${episodesFromHtml.size} episódios no HTML")
        return episodesFromHtml.sortedWith(compareBy({ it.season }, { it.episode }))
    }
    
    // 2. SEGUNDO: Se não encontrou no HTML, tentar API
    println("[CineAgora] Nenhum episódio encontrado no HTML, tentando API...")
    val seriesSlug = getSeriesSlugFromCineAgoraPage(doc, baseUrl)
    
    if (!seriesSlug.isNullOrBlank()) {
        val episodesFromApi = extractEpisodesFromApi(seriesSlug, baseUrl)
        if (episodesFromApi.isNotEmpty()) {
            println("[CineAgora] Encontrados ${episodesFromApi.size} episódios via API")
            return episodesFromApi
        }
    }
    
    // 3. TERCEIRO: Fallback final
    println("[CineAgora] Fallback final: criando episódio básico")
    episodes.add(
        newEpisode(baseUrl) {
            name = "Episódio 1"
            season = 1
            episode = 1
        }
    )
    
    return episodes
}

// =============================================
// FUNÇÃO PARA EXTRAIR EPISÓDIOS DO HTML (NOVA)
// =============================================

private fun extractEpisodesFromHtml(doc: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
    val episodes = mutableListOf<Episode>()
    
    // CASO 1: Série com MÚLTIPLAS TEMPORADAS
    val seasonDropdown = doc.selectFirst("#seasonDropdown")
    if (seasonDropdown != null) {
        println("[CineAgora] Encontrado dropdown de temporadas")
        
        // Extrair todas as temporadas disponíveis
        val seasonButtons = seasonDropdown.select("button")
        println("[CineAgoras] Temporadas encontradas: ${seasonButtons.size}")
        
        // Verificar se há episódios no dropdown de episódios
        val episodeDropdown = doc.selectFirst("#episodeDropdown")
        if (episodeDropdown != null) {
            // Se houver apenas uma temporada no dropdown, pegar todos episódios
            if (seasonButtons.size == 1) {
                val episodeButtons = episodeDropdown.select("button[data-id]")
                println("[CineAgora] Encontrados ${episodeButtons.size} episódios para temporada única")
                
                episodeButtons.forEachIndexed { index, button ->
                    try {
                        val episodeNum = index + 1
                        val videoSlug = button.attr("data-id")
                        val buttonText = button.text().trim()
                        
                        val episodeTitle = if (buttonText.isNotBlank()) {
                            buttonText
                        } else {
                            "Episódio $episodeNum"
                        }
                        
                        val episodeUrl = "https://watch.brplayer.cc/watch/$videoSlug"
                        
                        episodes.add(
                            newEpisode(episodeUrl) {
                                name = episodeTitle
                                season = 1
                                episode = episodeNum
                                description = "Episódio $episodeNum"
                            }
                        )
                        
                    } catch (e: Exception) {
                        println("[CineAgora] Erro ao extrair episódio: ${e.message}")
                    }
                }
            } else {
                // Para múltiplas temporadas, precisamos da API
                println("[CineAgora] Múltiplas temporadas detectadas, será necessário usar API")
            }
        }
    } 
    // CASO 2: Série com APENAS EPISÓDIOS (sem dropdown de temporadas)
    else {
        val episodeDropdown = doc.selectFirst("#episodeDropdown")
        if (episodeDropdown != null) {
            val episodeButtons = episodeDropdown.select("button[data-id]")
            println("[CineAgora] Encontrados ${episodeButtons.size} episódios (sem temporadas)")
            
            episodeButtons.forEachIndexed { index, button ->
                try {
                    val episodeNum = index + 1
                    val videoSlug = button.attr("data-id")
                    val buttonText = button.text().trim()
                    
                    // Extrair número do episódio do texto do botão
                    val epNumberFromText = extractEpisodeNumberFromText(buttonText)
                    val finalEpisodeNum = epNumberFromText ?: episodeNum
                    
                    val episodeTitle = if (buttonText.isNotBlank()) {
                        buttonText
                    } else {
                        "Episódio $finalEpisodeNum"
                    }
                    
                    val episodeUrl = "https://watch.brplayer.cc/watch/$videoSlug"
                    
                    episodes.add(
                        newEpisode(episodeUrl) {
                            name = episodeTitle
                            season = 1  // Temporada 1 por padrão
                            episode = finalEpisodeNum
                            description = "Episódio $finalEpisodeNum"
                        }
                    )
                    
                    println("[CineAgora] Adicionado do HTML: $episodeTitle")
                    
                } catch (e: Exception) {
                    println("[CineAgora] Erro ao extrair episódio: ${e.message}")
                }
            }
        }
    }
    
    // CASO 3: Procurar episódios em outros lugares do HTML
    if (episodes.isEmpty()) {
        // Buscar por todos os botões com data-id que possam ser episódios
        val allEpisodeButtons = doc.select("button[data-id]")
        if (allEpisodeButtons.isNotEmpty()) {
            println("[CineAgora] Encontrados ${allEpisodeButtons.size} botões com data-id")
            
            allEpisodeButtons.forEachIndexed { index, button ->
                try {
                    val buttonText = button.text().trim()
                    if (buttonText.contains("Episódio", ignoreCase = true) || 
                        buttonText.contains("Episode", ignoreCase = true)) {
                        
                        val videoSlug = button.attr("data-id")
                        val episodeNum = extractEpisodeNumberFromText(buttonText) ?: (index + 1)
                        
                        val episodeUrl = "https://watch.brplayer.cc/watch/$videoSlug"
                        
                        episodes.add(
                            newEpisode(episodeUrl) {
                                name = buttonText
                                season = 1
                                episode = episodeNum
                                description = buttonText
                            }
                        )
                        
                        println("[CineAgora] Adicionado de botão genérico: $buttonText")
                    }
                } catch (e: Exception) {
                    println("[CineAgora] Erro ao extrair botão: ${e.message}")
                }
            }
        }
    }
    
    return episodes
}

// =============================================
// FUNÇÃO AUXILIAR: EXTRAIR NÚMERO DO EPISÓDIO DO TEXTO
// =============================================

private fun extractEpisodeNumberFromText(text: String): Int? {
    try {
        // Padrões comuns: "Episódio 1", "Episódio 01", "E01", "EP 1"
        val patterns = listOf(
            Regex("""Episódio\s+(\d+)"""),
            Regex("""Episode\s+(\d+)"""),
            Regex("""E(\d+)"""),
            Regex("""EP\s*(\d+)"""),
            Regex("""\b(\d{1,3})\b""") // Qualquer número de 1-3 dígitos
        )
        
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                return match.groupValues[1].toIntOrNull()
            }
        }
    } catch (e: Exception) {
        println("[CineAgora] Erro ao extrair número do episódio: ${e.message}")
    }
    
    return null
}

// =============================================
// FUNÇÃO PARA EXTRAIR EPISÓDIOS DA API (SIMPLIFICADA)
// =============================================

private suspend fun extractEpisodesFromApi(seriesSlug: String, baseUrl: String): List<Episode> {
    val episodes = mutableListOf<Episode>()
    
    val apiUrl = "https://watch.brplayer.cc/fetch_series_data.php?seriesSlug=$seriesSlug"
    println("[CineAgora] Chamando API: $apiUrl")
    
    try {
        val jsonResponse = app.get(apiUrl, referer = baseUrl).text
        val responseMap: Map<String, Any>? = AppUtils.parseJson(jsonResponse)
        
        val seasonsMap = responseMap?.get("seasons") as? Map<String, List<Map<String, Any>>>
        
        if (seasonsMap != null) {
            println("[CineAgora] API carregada. ${seasonsMap.size} temporada(s)")
            
            seasonsMap.forEach { (seasonStr, episodeList) ->
                val seasonNum = seasonStr.toIntOrNull() ?: 1
                println("[CineAgora] Temporada $seasonNum: ${episodeList.size} episódios")
                
                episodeList.forEachIndexed { index, epMap ->
                    try {
                        val videoSlug = epMap["video_slug"] as? String ?: return@forEach
                        val epNumberStr = epMap["episode_number"] as? String
                        val epTitleRaw = epMap["episode_title"] as? String
                        
                        val epNumber = epNumberStr?.toIntOrNull() ?: (index + 1)
                        val episodeTitle = epTitleRaw?.takeIf { it.isNotBlank() } ?: "Episódio $epNumber"
                        
                        val episodeUrl = "https://watch.brplayer.cc/watch/$videoSlug"
                        
                        episodes.add(
                            newEpisode(episodeUrl) {
                                name = episodeTitle
                                season = seasonNum
                                episode = epNumber
                                description = "Temporada $seasonNum • Episódio $epNumber"
                            }
                        )
                        
                    } catch (e: Exception) {
                        println("[CineAgora] Erro ao processar episódio da API: ${e.message}")
                    }
                }
            }
        }
    } catch (e: Exception) {
        println("[CineAgora] Erro na API: ${e.message}")
    }
    
    return episodes
}
    // =============================================
    // FUNÇÃO LOAD PRINCIPAL (SIMPLIFICADA)
    // =============================================

    override suspend fun load(url: String): LoadResponse? {
        println("[CineAgora] Carregando URL: $url")
        
        val doc = app.get(url).document
        
        // 1. Extrair informações básicas
        val bannerUrl = extractBannerUrl(doc)
        val posterUrl = doc.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }
            ?: doc.selectFirst("#info--box .cover-img")?.attr("src")?.let { fixUrl(it) }
            ?: bannerUrl
        
        println("[CineAgora] Banner URL: $bannerUrl")
        println("[CineAgora] Poster URL: $posterUrl")
        
        val title = doc.selectFirst("h1.title, h1, .title, h2")?.text()?.trim() ?: "Título não encontrado"
        
        // 2. Extrair episódios (agora direto da página do CineAgora)
        val episodes = extractEpisodesFromCineAgoraPage(doc, url)
        
        println("[CineAgora] Total de episódios extraídos: ${episodes.size}")
        
        if (episodes.isEmpty()) {
            println("[CineAgora] Nenhum episódio encontrado")
            return null
        }
        
        // 3. DETERMINAR SE É SÉRIE OU FILME
        val isSerie = url.contains("/series-") || url.contains("/serie-") || url.contains("/tv-") || 
                     url.contains("/series-online") ||
                     doc.select(".player-controls, #episodeDropdown, .seasons").isNotEmpty() ||
                     episodes.size > 1
        
        println("[CineAgora] É série? $isSerie (${episodes.size} episódios)")
        
        // 4. INFORMAÇÕES ADICIONAIS
        val year = extractYear(doc)
        val plot = doc.selectFirst(".info-description, .description, .sinopse, .plot")?.text()?.trim()
        val genres = extractGenres(doc)
        
        if (isSerie) {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = bannerUrl
                this.year = year
                this.plot = plot
                this.tags = genres
                
                val trailer = extractTrailer(doc)
                if (trailer != null) {
                    addTrailer(trailer)
                }
            }
        } else {
            val duration = doc.selectFirst(".duration, .runtime, .time")?.text()?.trim()
            
            return newMovieLoadResponse(title, url, TvType.Movie, episodes.first().data) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = bannerUrl
                this.year = year
                this.plot = plot
                this.tags = genres
                this.duration = duration?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
                
                val trailer = extractTrailer(doc)
                if (trailer != null) {
                    addTrailer(trailer)
                }
            }
        }
    }

    // =============================================
    // FUNÇÃO LOADLINKS
    // =============================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[CineAgora] loadLinks chamado com data: $data")
        println("[CineAgora] isCasting: $isCasting")
        
        if (data.contains("youtube.com") || data.contains("youtu.be")) {
            println("[CineAgora] URL do YouTube ignorada")
            return false
        }
        
        val name = if (data.contains("/watch/")) {
            data.substringAfterLast("/").replace("-", " ").replace("_", " ")
        } else {
            "Conteúdo"
        }
        
        return CineAgoraExtractor.extractVideoLinks(data, name, callback)
    }
}
