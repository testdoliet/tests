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
    // FUNÇÃO PRINCIPAL PARA EXTRAIR SERIESLUG (CORRIGIDA)
    // =============================================

    private fun extractSeriesSlugFromScript(doc: org.jsoup.nodes.Document): String? {
        println("[CineAgora] Extraindo seriesSlug dos scripts")
        
        val scripts = doc.select("script:not([src])")
        
        for (script in scripts) {
            val scriptText = script.html()
            
            // Padrão 1: var seriesSlug = "severance";
            val slugRegex1 = Regex("""var\s+seriesSlug\s*=\s*["']([^"']+)["']""")
            val match1 = slugRegex1.find(scriptText)
            if (match1 != null) {
                val slug = match1.groupValues[1].trim()
                println("[CineAgora] Encontrado seriesSlug via padrão 1: $slug")
                return slug
            }
            
            // Padrão 2: seriesSlug: "severance"
            val slugRegex2 = Regex("""seriesSlug\s*:\s*["']([^"']+)["']""")
            val match2 = slugRegex2.find(scriptText)
            if (match2 != null) {
                val slug = match2.groupValues[1].trim()
                println("[CineAgora] Encontrado seriesSlug via padrão 2: $slug")
                return slug
            }
            
            // Padrão 3: em objetos JavaScript
            val slugRegex3 = Regex("""["']?seriesSlug["']?\s*:\s*["']([^"']+)["']""")
            val match3 = slugRegex3.find(scriptText)
            if (match3 != null) {
                val slug = match3.groupValues[1].trim()
                println("[CineAgora] Encontrado seriesSlug via padrão 3: $slug")
                return slug
            }
        }
        
        println("[CineAgora] Não encontrou seriesSlug nos scripts")
        return null
    }

    // =============================================
    // FUNÇÃO PARA BUSCAR EPISÓDIOS DA API (COM HEADERS CORRETOS)
    // =============================================

    private suspend fun fetchEpisodesFromApi(seriesSlug: String, referer: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        val apiUrl = "https://watch.brplayer.cc/fetch_series_data.php?seriesSlug=$seriesSlug"
        println("[CineAgora] Chamando API: $apiUrl")
        println("[CineAgora] Referer: $referer")
        
        try {
            // Headers exatamente como no curl
            val headers = mapOf(
                "accept" to "application/json, text/javascript, */*; q=0.01",
                "accept-language" to "pt-BR",
                "priority" to "u=1, i",
                "referer" to referer,
                "sec-ch-ua" to "\"Chromium\";v=\"127\", \"Not)A;Brand\";v=\"99\", \"Microsoft Edge Simulate\";v=\"127\", \"Lemur\";v=\"127\"",
                "sec-ch-ua-mobile" to "?1",
                "sec-ch-ua-platform" to "\"Android\"",
                "sec-fetch-dest" to "empty",
                "sec-fetch-mode" to "cors",
                "sec-fetch-site" to "same-origin",
                "user-agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                "x-requested-with" to "XMLHttpRequest"
            )
            
            val response = app.get(apiUrl, headers = headers, timeout = 30)
            
            if (!response.isSuccessful) {
                println("[CineAgora] API retornou erro: ${response.code}")
                return episodes
            }
            
            val jsonText = response.text
            println("[CineAgora] Resposta da API (primeiros 500 chars): ${jsonText.take(500)}")
            
            val responseMap: Map<String, Any>? = AppUtils.parseJson(jsonText)
            
            if (responseMap == null) {
                println("[CineAgora] Erro ao parsear JSON da API")
                return episodes
            }
            
            val seasonsMap = responseMap["seasons"] as? Map<String, List<Map<String, Any>>>
            
            if (seasonsMap == null) {
                println("[CineAgora] Não encontrou 'seasons' no JSON")
                // Tentar estrutura alternativa
                val allEpisodes = responseMap["episodes"] as? List<Map<String, Any>>
                if (allEpisodes != null) {
                    println("[CineAgora] Encontrou estrutura alternativa 'episodes' com ${allEpisodes.size} itens")
                    allEpisodes.forEachIndexed { index, epMap ->
                        try {
                            extractEpisodeFromMap(epMap, 1, index + 1, episodes)
                        } catch (e: Exception) {
                            println("[CineAgora] Erro ao processar episódio alternativo: ${e.message}")
                        }
                    }
                }
                return episodes
            }
            
            println("[CineAgora] API carregada com sucesso. ${seasonsMap.keys.size} temporada(s) encontrada(s)")
            
            seasonsMap.forEach { (seasonStr, episodeList) ->
                val seasonNum = seasonStr.toIntOrNull() ?: 1
                println("[CineAgora] Processando temporada $seasonNum com ${episodeList.size} episódios")
                
                episodeList.forEachIndexed { index, epMap ->
                    try {
                        extractEpisodeFromMap(epMap, seasonNum, index + 1, episodes)
                    } catch (e: Exception) {
                        println("[CineAgora] Erro ao processar episódio ${index + 1}: ${e.message}")
                    }
                }
            }
            
            println("[CineAgora] Total de ${episodes.size} episódios criados a partir da API!")
            
        } catch (e: Exception) {
            println("[CineAgora] Erro na chamada à API: ${e.message}")
            println("[CineAgora] Stack trace: ${e.stackTraceToString()}")
        }
        
        return episodes
    }
    
    private fun extractEpisodeFromMap(epMap: Map<String, Any>, seasonNum: Int, defaultEpisodeNum: Int, episodes: MutableList<Episode>) {
        val videoSlug = epMap["video_slug"] as? String ?: return
        val epNumberStr = epMap["episode_number"] as? String
        val epTitleRaw = epMap["episode_title"] as? String
        
        // Determinar número do episódio
        val epNumber = epNumberStr?.toIntOrNull() ?: defaultEpisodeNum
        
        // Limpar título do episódio
        val episodeTitle = cleanEpisodeTitle(epTitleRaw, seasonNum, epNumber)
        
        // URL final do episódio
        val episodeUrl = "https://watch.brplayer.cc/watch/$videoSlug"
        
        episodes.add(
            newEpisode(episodeUrl) {
                name = episodeTitle
                season = seasonNum
                episode = epNumber
                description = "Temporada $seasonNum • Episódio $epNumber"
            }
        )
        
        println("[CineAgora] Adicionado: Temporada $seasonNum, Episódio $epNumber - $episodeTitle")
    }
    
    private fun cleanEpisodeTitle(rawTitle: String?, seasonNum: Int, episodeNum: Int): String {
        if (rawTitle.isNullOrBlank()) {
            return "Episódio $episodeNum"
        }
        
        return try {
            // Remover padrões comuns de nome de arquivo
            var title = rawTitle
                .replace(Regex("(?i)\\.S\\d+E\\d+\\."), " ") // Remove .S01E01.
                .replace(Regex("(?i)\\.\\d+p\\."), " ") // Remove .1080p.
                .replace(Regex("(?i)\\.WEB-DL\\."), " ") // Remove .WEB-DL.
                .replace(Regex("(?i)\\.DUAL\\."), " ") // Remove .DUAL.
                .replace(Regex("(?i)\\.mkv$"), "") // Remove .mkv
                .replace(Regex("(?i)\\.mp4$"), "") // Remove .mp4
                .replace(Regex("\\."), " ") // Troca pontos por espaços
                .trim()
            
            // Se ficou vazio ou muito curto, usar padrão
            if (title.length < 3 || title == "Severance") {
                title = "Episódio $episodeNum"
            }
            
            title
        } catch (e: Exception) {
            "Episódio $episodeNum"
        }
    }

    // =============================================
    // FUNÇÃO PRINCIPAL PARA EXTRAIR EPISÓDIOS
    // =============================================

    private suspend fun extractEpisodes(doc: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        println("[CineAgora] Extraindo episódios para: $baseUrl")
        
        // 1. Tentar extrair o seriesSlug
        val seriesSlug = extractSeriesSlugFromScript(doc)
        
        if (seriesSlug == null) {
            println("[CineAgora] Não encontrou seriesSlug, tentando fallback HTML")
            // Fallback: extrair do HTML se possível
            return extractEpisodesFromHtmlFallback(doc, baseUrl)
        }
        
        println("[CineAgora] Series Slug encontrado: $seriesSlug")
        
        // 2. Criar referer correto para a API
        val referer = "https://watch.brplayer.cc/tv/$seriesSlug"
        
        // 3. Buscar episódios da API
        val apiEpisodes = fetchEpisodesFromApi(seriesSlug, referer)
        
        if (apiEpisodes.isNotEmpty()) {
            return apiEpisodes.sortedWith(compareBy({ it.season }, { it.episode }))
        }
        
        // 4. Se API falhou, tentar fallback HTML
        println("[CineAgora] API falhou ou retornou vazio, tentando fallback HTML")
        return extractEpisodesFromHtmlFallback(doc, baseUrl)
    }
    
    private fun extractEpisodesFromHtmlFallback(doc: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        // Tentar extrair do dropdown de episódios
        val episodeDropdown = doc.selectFirst("#episodeDropdown")
        if (episodeDropdown != null) {
            val episodeButtons = episodeDropdown.select("button[data-id]")
            println("[CineAgora] Encontrados ${episodeButtons.size} episódios no dropdown HTML")
            
            episodeButtons.forEachIndexed { index, button ->
                try {
                    val episodeNum = index + 1
                    val videoSlug = button.attr("data-id")
                    val buttonText = button.text().trim()
                    
                    // Extrair número do episódio do texto
                    val epNumberFromText = extractEpisodeNumberFromText(buttonText)
                    val finalEpisodeNum = epNumberFromText ?: episodeNum
                    
                    val episodeTitle = if (buttonText.isNotBlank() && buttonText != "Episódio $finalEpisodeNum") {
                        buttonText
                    } else {
                        "Episódio $finalEpisodeNum"
                    }
                    
                    val episodeUrl = "https://watch.brplayer.cc/watch/$videoSlug"
                    
                    episodes.add(
                        newEpisode(episodeUrl) {
                            name = episodeTitle
                            season = 1
                            episode = finalEpisodeNum
                            description = "Episódio $finalEpisodeNum"
                        }
                    )
                    
                    println("[CineAgora] Adicionado do HTML: $episodeTitle")
                    
                } catch (e: Exception) {
                    println("[CineAgora] Erro ao extrair episódio do HTML: ${e.message}")
                }
            }
        }
        
        // Se não encontrou nada, criar pelo menos um episódio
        if (episodes.isEmpty()) {
            println("[CineAgora] Fallback final: criando episódio básico")
            episodes.add(
                newEpisode(baseUrl) {
                    name = "Episódio 1"
                    season = 1
                    episode = 1
                }
            )
        }
        
        return episodes
    }
    
    private fun extractEpisodeNumberFromText(text: String): Int? {
        try {
            // Padrões comuns: "Episódio 1", "Episódio 01", "E01", "EP 1"
            val patterns = listOf(
                Regex("""Episódio\s+(\d+)""", RegexOption.IGNORE_CASE),
                Regex("""Episode\s+(\d+)""", RegexOption.IGNORE_CASE),
                Regex("""E(\d+)""", RegexOption.IGNORE_CASE),
                Regex("""EP\s*(\d+)""", RegexOption.IGNORE_CASE),
                Regex("""\b(\d{1,3})\b""") // Qualquer número de 1-3 dígitos
            )
            
            for (pattern in patterns) {
                val match = pattern.find(text)
                if (match != null) {
                    return match.groupValues[1].toIntOrNull()
                }
            }
        } catch (e: Exception) {
            // Ignorar erro
        }
        
        return null
    }

    // =============================================
    // FUNÇÃO LOAD PRINCIPAL
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
        
        // 2. Extrair episódios
        val episodes = extractEpisodes(doc, url)
        
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
