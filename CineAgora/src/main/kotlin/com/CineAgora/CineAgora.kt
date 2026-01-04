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

    // IMPLEMENTAÇÃO DA PESQUISA COM BASE NA SUA DESCOBERTA
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        
        // De acordo com sua análise, o site usa POST para a raiz com parâmetros específicos
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
            
            // Processar os resultados da pesquisa
            return extractSearchResults(document)
            
        } catch (e: Exception) {
            // Fallback: tentar com GET se POST falhar
            try {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val fallbackUrl = "$mainUrl/?do=search&subaction=search&story=$encodedQuery"
                
                val document = app.get(fallbackUrl).document
                return extractSearchResults(document)
            } catch (e2: Exception) {
                // Retornar lista vazia se ambas as tentativas falharem
                return emptyList()
            }
        }
    }

    private fun extractSearchResults(document: org.jsoup.nodes.Document): List<SearchResponse> {
        // Primeiro tentar seletores específicos da página de busca
        val searchItems = document.select(".film-list .content .col-6.col-sm-4.col-md-3.col-lg-2 .item-relative > a.item")
        
        return if (searchItems.isNotEmpty()) {
            searchItems.mapNotNull { it.toSearchResult() }
        } else {
            // Fallback: seletores gerais (os mesmos da página principal)
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
        // Pegar o link principal
        val linkElement = this.selectFirst("a")
        val href = linkElement?.attr("href")?.takeIf { it.isNotBlank() } ?: return null
        
        // Título do card
        val titleElement = selectFirst(".item-footer .title, .title, .poster-title, h3, h4")
        val title = titleElement?.text()?.trim() ?: return null
        
        // Extrair ano
        val year = selectFirst(".info span:first-child, .year, .date")?.text()?.toIntOrNull()
            ?: Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        
        // Limpar título (remover ano e outros detalhes)
        val cleanTitle = title
            .replace(Regex("\\(\\d{4}\\)"), "")
            .replace(Regex("\\d{4}$"), "")
            .trim()
        
        // Imagem/poster
        val imgElement = selectFirst("img.thumbnail, img.poster, img")
        val posterUrl = imgElement?.attr("src")?.let { fixUrl(it) }
        
        // 1. Qualidade (HD, TS, etc.) - Primeiro .item-info
        val qualityBadge = select(".item-info, .quality, .badge").firstOrNull()?.selectFirst("div:first-child, span")?.text()?.trim()
        
        // 2. Idioma (Dublado/Legendado) - Primeiro .item-info
        val languageBadge = select(".item-info, .language, .badge").firstOrNull()?.selectFirst("div:nth-child(2), .lang")?.text()?.trim()
        
        // 3. Score/Rating (usando a função avançada do AnimeFire)
        val scoreResult = extractScoreAdvanced(this)
        val scoreText = scoreResult.first
        val score = when {
            scoreText == null || scoreText == "N/A" -> null
            else -> scoreText.toFloatOrNull()?.let { Score.from10(it) }
        }
        
        // 4. Último episódio adicionado (para séries) - Segundo .item-info ou .data
        val lastEpisodeInfo = select(".item-info, .episode, .data").getOrNull(1)?.selectFirst("small, .last-ep")?.text()?.trim()
            ?: selectFirst(".data, .episode-info")?.text()?.trim()
        
        // Determinar se é filme ou série
        val isSerie = href.contains("/series-") || href.contains("/serie-") || href.contains("/tv-") || 
                      href.contains("/series-online") ||
                      lastEpisodeInfo?.contains(Regex("S\\d+.*E\\d+")) == true ||
                      title.contains(Regex("(?i)(temporada|episódio|season|episode)"))
        
        // Determinar qualidade baseada na badge
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
    // FUNÇÕES AUXILIARES (PRIVADAS DA CLASSE)
    // =============================================

    private fun extractBannerUrl(doc: org.jsoup.nodes.Document): String? {
        // Procurar banner em várias fontes
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
        // Procurar por trailer no YouTube
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

    private fun extractSeriesSlug(url: String): String {
        // Extrair slug da URL (ex: pluribus de https://cineagora.net/series-online-hd-gratis/2984-pluribus.html)
        return url
            .substringAfterLast("/")           // 2984-pluribus.html
            .substringAfter("-")               // pluribus.html
            .substringBefore(".html")          // pluribus
            .trim()
    }

    // =============================================
    // FUNÇÃO PRINCIPAL PARA EXTRAIR EPISÓDIOS
    // =============================================

    private suspend fun extractEpisodesFromPage(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()

        // 1. Extrair o src do iframe do player (o link watch.brplayer.cc)
        val playerIframe = document.selectFirst("iframe[src*='watch.brplayer.cc'], iframe[src*='brplayer']")
            ?: document.selectFirst("iframe[src*='player']") // fallback genérico

        if (playerIframe == null) {
            println("[CineAgora] Nenhum iframe de player encontrado.")
            // Criar pelo menos um episódio com a URL da página como fallback
            episodes.add(
                newEpisode(baseUrl) {
                    name = "Conteúdo Principal"
                    episode = 1
                    season = 1
                }
            )
            return episodes
        }

        var watchUrl = playerIframe.attr("src")
        if (!watchUrl.startsWith("http")) watchUrl = "https://watch.brplayer.cc$watchUrl"
        watchUrl = fixUrl(watchUrl).removeSuffix("/").removeSuffix("?ref=&d=null") // limpar params extras

        println("[CineAgora] Link do player extraído: $watchUrl")

        // 2. Detectar se é série (pelo caminho /tv/slug)
        val isSeriesPath = watchUrl.contains("/tv/")

        if (!isSeriesPath) {
            // É FILME ou episódio único → apenas uma Episode com a URL do watch
            episodes.add(
                newEpisode(watchUrl) {
                    name = "Filme Completo"
                    episode = 1
                    season = 1
                }
            )
            println("[CineAgora] Detectado como filme → 1 episódio criado: $watchUrl")
            return episodes
        }

        // 3. É SÉRIE → extrair seriesSlug e chamar a API
        val seriesSlug = watchUrl.split("/tv/").lastOrNull()?.split("?").firstOrNull()
            ?: return episodes.also { println("[CineAgora] Não encontrou seriesSlug") }

        val apiUrl = "https://watch.brplayer.cc/fetch_series_data.php?seriesSlug=$seriesSlug"

        try {
            val jsonResponse = app.get(apiUrl, referer = watchUrl).text
            val responseMap: Map<String, Any>? = AppUtils.parseJson(jsonResponse)

            val seasonsMap = responseMap?.get("seasons") as? Map<String, List<Map<String, Any>>>
                ?: return episodes.also { println("[CineAgora] JSON inválido ou sem seasons") }

            println("[CineAgora] API carregada com sucesso. ${seasonsMap.keys.size} temporada(s) encontrada(s)")

            seasonsMap.forEach { (seasonStr, episodeList) ->
                val seasonNum = seasonStr.toIntOrNull() ?: 1

                episodeList.forEach { epMap ->
                    val videoSlug = epMap["video_slug"] as? String ?: return@forEach
                    val epNumberStr = epMap["episode_number"] as? String ?: "1"
                    val epNumber = epNumberStr.toIntOrNull() ?: 1

                    // Título opcional (pode ser nome do arquivo MKV)
                    val epTitleRaw = epMap["episode_title"] as? String ?: ""
                    val epTitle = try {
                        java.net.URLDecoder.decode(epTitleRaw, "UTF-8")
                            .let { java.net.URLDecoder.decode(it, "UTF-8") } // double encoded às vezes
                    } catch (e: Exception) { "Episódio $epNumber" }

                    val finalWatchUrl = "https://watch.brplayer.cc/watch/$videoSlug"

                    episodes.add(
                        newEpisode(finalWatchUrl) {
                            name = epTitle.ifBlank { "Episódio $epNumber" }
                            season = seasonNum
                            episode = epNumber
                            description = "Temporada $seasonNum • Episódio $epNumber"
                        }
                    )
                }
            }

            println("[CineAgora] ${episodes.size} episódios criados a partir da API!")

        } catch (e: Exception) {
            println("[CineAgora] Erro ao acessar API de séries: ${e.message}")
            // Fallback: pelo menos criar o episódio padrão (temporada 1 episódio 1)
            episodes.add(
                newEpisode(watchUrl) {
                    name = "Temporada 1 - Episódio 1"
                    season = 1
                    episode = 1
                }
            )
        }

        return episodes
    }

    // =============================================
    // FUNÇÃO LOAD PRINCIPAL (UNIFICADA)
    // =============================================

    override suspend fun load(url: String): LoadResponse? {
        println("[CineAgora] Carregando URL: $url")
        
        val doc = app.get(url).document
        
        // 1. EXTRAIR BANNER/POSTER - VERSÃO MELHOR QUALIDADE
        val bannerUrl = extractBannerUrl(doc)
        // Pega a imagem do og:image ou do #info--box .cover-img para melhor qualidade
        val posterUrl = doc.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }
            ?: doc.selectFirst("#info--box .cover-img")?.attr("src")?.let { fixUrl(it) }
            ?: bannerUrl
        
        // 2. TÍTULO
        val title = doc.selectFirst("h1.title, h1, .title, h2")?.text()?.trim() ?: "Título não encontrado"
        
        // 3. Extrair episódios (funciona para filmes e séries)
        val episodes = extractEpisodesFromPage(doc, url)
        
        if (episodes.isEmpty()) {
            println("[CineAgora] Nenhum episódio encontrado")
            return null
        }
        
        // 4. DETERMINAR SE É SÉRIE OU FILME
        val isSerie = url.contains("/series-") || url.contains("/serie-") || url.contains("/tv-") || 
                     url.contains("/series-online") ||
                     doc.select(".player-controls, #episodeDropdown, .seasons").isNotEmpty() ||
                     episodes.size > 1 // Se tem múltiplos episódios, é série
        
        println("[CineAgora] É série? $isSerie (${episodes.size} episódios encontrados)")
        
        // 5. INFORMAÇÕES ADICIONAIS
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
                
                // Extrair e adicionar trailer
                val trailer = extractTrailer(doc)
                if (trailer != null) {
                    addTrailer(trailer)
                }
            }
        } else {
            val duration = doc.selectFirst(".duration, .runtime, .time")?.text()?.trim()
            
            // Para filmes, usar o primeiro episódio como data
            return newMovieLoadResponse(title, url, TvType.Movie, episodes.first().data) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = bannerUrl
                this.year = year
                this.plot = plot
                this.tags = genres
                this.duration = duration?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
                
                // Extrair e adicionar trailer
                val trailer = extractTrailer(doc)
                if (trailer != null) {
                    addTrailer(trailer)
                }
            }
        }
    }

    // =============================================
    // FUNÇÃO LOADLINKS (SIMPLIFICADA)
    // =============================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[CineAgora] loadLinks chamado com data: $data")
        println("[CineAgora] isCasting: $isCasting")
        
        // Ignorar URLs do YouTube
        if (data.contains("youtube.com") || data.contains("youtu.be")) {
            println("[CineAgora] URL do YouTube ignorada")
            return false
        }
        
        // Extrair título para nome dos links
        val name = if (data.contains("/watch/")) {
            data.substringAfterLast("/").replace("-", " ").replace("_", " ")
        } else {
            "Conteúdo"
        }
        
        // Usar o extrator otimizado diretamente
        return CineAgoraExtractor.extractVideoLinks(data, name, callback)
    }
}
