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

    // Função para extrair o banner em full HD (baseado na análise)
    private fun extractBannerUrl(document: org.jsoup.nodes.Document): String? {
        try {
            // 1º Prioridade: Imagem dentro de <picture> (full HD conforme análise)
            // Primeiro tentar o source mobile (ainda HD)
            document.selectFirst("picture source[srcset]")?.attr("srcset")?.let { srcset ->
                // Pode ter múltiplas URLs no srcset, pegar a primeira
                val url = srcset.split(',').firstOrNull()?.trim()
                if (!url.isNullOrBlank()) {
                    return fixUrl(url)
                }
            }
            
            // 2º Prioridade: Imagem principal dentro do picture
            document.selectFirst("picture img")?.attr("src")?.let { src ->
                if (src.isNotBlank()) {
                    return fixUrl(src)
                }
            }
            
            // 3º Prioridade: Meta tag og:image (sempre tem a imagem em alta qualidade)
            document.selectFirst("meta[property='og:image']")?.attr("content")?.let { ogImage ->
                if (ogImage.isNotBlank()) {
                    return fixUrl(ogImage)
                }
            }
            
            // 4º Prioridade: Imagens com classes específicas do banner
            val bannerSelectors = listOf(
                ".player__cover img",
                ".cover-img",
                ".hero img",
                ".backdrop img",
                "[class*='banner'] img",
                ".player-cover img",
                ".poster-large img"
            )
            
            for (selector in bannerSelectors) {
                document.selectFirst(selector)?.attr("src")?.let { src ->
                    if (src.isNotBlank() && !src.contains("logo") && !src.contains("icon")) {
                        return fixUrl(src)
                    }
                }
            }
            
            // 5º Prioridade: Qualquer imagem grande (largura > 500px no atributo)
            document.select("img[width]").forEach { img ->
                val width = img.attr("width").toIntOrNull()
                if (width != null && width > 500) {
                    img.attr("src")?.let { src ->
                        if (src.isNotBlank()) {
                            return fixUrl(src)
                        }
                    }
                }
            }
            
            // 6º Prioridade: Fallback para qualquer imagem de poster
            document.selectFirst("img.poster, .poster img")?.attr("src")?.let { src ->
                if (src.isNotBlank()) {
                    return fixUrl(src)
                }
            }
            
        } catch (e: Exception) {
            // Log do erro se necessário
        }
        
        return null
    }

    // Função load focada em trailer e episódios
    override suspend fun load(url: String): LoadResponse? {
        // Primeiro, tentar carregar a página para analisar o conteúdo
        val document = try {
            app.get(url).document
        } catch (e: Exception) {
            return null
        }

        // Extrair informações básicas da página
        val title = document.selectFirst("h1, .title, h2")?.text()?.trim() ?: "Título não encontrado"
        
        // EXTRAIR BANNER EM FULL HD (prioridade conforme sua análise)
        val bannerUrl = extractBannerUrl(document)
        val poster = bannerUrl ?: 
            document.selectFirst("img.poster, .poster img, img.thumbnail, .thumbnail img")?.attr("src")?.let { fixUrl(it) }
        
        val plot = document.selectFirst(".description, .sinopse, .plot, .content p")?.text()?.trim()

        // Determinar se é filme ou série baseado na URL e conteúdo
        val isSerie = url.contains("/series-") || url.contains("/serie-") || url.contains("/tv-") || 
            url.contains("/series-online") ||
            document.select(".episodes, .seasons, .temporada, .episodio").isNotEmpty() ||
            document.text().contains(Regex("(?i)(temporada|episódio|season|episode)"))

        // Extrair ano
        val year = document.selectFirst(".year, .date, time")?.text()?.toIntOrNull()
            ?: Regex("(\\d{4})").find(title)?.groupValues?.get(1)?.toIntOrNull()

        // Extrair gêneros
        val genres = document.select(".genres a, .genre a, .category a, a[href*='genero'], a[href*='categoria']")
            .mapNotNull { it.text().trim() }
            .takeIf { it.isNotEmpty() }

        // Extrair atores
        val actorElements = document.select(".actors a, .cast a, .elenco a")
        val actors = if (actorElements.isNotEmpty()) {
            actorElements.mapNotNull { element ->
                val name = element.text().trim()
                if (name.isNotBlank()) {
                    val actorImg = element.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                    Actor(name, actorImg)
                } else {
                    null
                }
            }
        } else {
            null
        }

        // Extrair duração (para filmes)
        val duration = document.selectFirst(".duration, .runtime, .time")?.text()?.trim()

        // BUSCAR TRAILER (baseado na análise)
        val trailerUrl = findTrailer(document)

        // Para séries, extrair episódios
        val episodes = if (isSerie) {
            extractEpisodesFromPage(document, url)
        } else {
            emptyList()
        }

        // Construir LoadResponse
        return if (isSerie) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = genres

                // Adicionar atores
                actors?.let { addActors(it) }

                // Adicionar trailer se encontrado
                trailerUrl?.let { addTrailer(it) }
            }
        } else {
            // Para filmes, precisamos do dataUrl (player)
            val playerUrl = findPlayerUrl(document, url)

            newMovieLoadResponse(title, url, TvType.Movie, playerUrl ?: url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = genres
                this.duration = duration?.replace(Regex("[^0-9]"), "")?.toIntOrNull()

                // Adicionar atores
                actors?.let { addActors(it) }

                // Adicionar trailer se encontrado
                trailerUrl?.let { addTrailer(it) }
            }
        }
    }

    // Função para encontrar trailer
    private fun findTrailer(document: org.jsoup.nodes.Document): String? {
        // Procurar por botão "Trailer" ou link com trailer
        val trailerButton = document.selectFirst("a:contains(Trailer), .button:contains(Trailer), .trailer-button, .btn-trailer")

        if (trailerButton != null) {
            // Verificar se tem atributo onclick
            val onClick = trailerButton.attr("onclick")
            if (onClick.isNotBlank()) {
                // Extrair URL do onclick
                val youtubeRegex = Regex("""youtube\.com/watch\?v=([^&'"]+)""")
                val youtubeMatch = youtubeRegex.find(onClick)
                if (youtubeMatch != null) {
                    val videoId = youtubeMatch.groupValues[1]
                    return "https://www.youtube.com/watch?v=$videoId"
                }

                // Tentar extrair qualquer URL do onclick
                val urlRegex = Regex("""['"](https?://[^'"]+)['"]""")
                val urlMatch = urlRegex.find(onClick)
                if (urlMatch != null) {
                    return urlMatch.groupValues[1]
                }
            }

            // Verificar se tem data-src ou href
            val dataSrc = trailerButton.attr("data-src") 
            val href = trailerButton.attr("href")

            if (dataSrc.isNotBlank() && dataSrc != "#") {
                return fixUrl(dataSrc)
            }

            if (href.isNotBlank() && href != "#") {
                return fixUrl(href)
            }
        }

        // Procurar por iframe de trailer
        val trailerIframe = document.selectFirst("iframe[src*='youtube'], iframe[src*='trailer'], iframe[src*='embed']")
        trailerIframe?.let { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                return fixUrl(src)
            }
        }

        // Procurar por script que contenha trailer
        val scripts = document.select("script:not([src])")
        for (script in scripts) {
            val scriptContent = script.data()
            if (scriptContent.contains("trailer", ignoreCase = true) || scriptContent.contains("youtube", ignoreCase = true)) {
                // Tentar extrair URL do YouTube
                val youtubeRegex = Regex("""(https?://(?:www\.)?youtube\.com/(?:embed/|watch\?v=)[^"'\s]+)""")
                val match = youtubeRegex.find(scriptContent)
                if (match != null) {
                    return match.groupValues[1]
                }
            }
        }

        return null
    }

    // Função para extrair episódios (baseado na análise detalhada do HTML)
private fun extractEpisodesFromPage(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
    val episodes = mutableListOf<Episode>()
    
    println("[CineAgora] Buscando episódios na página")

    // ESTRUTURA ESPECÍFICA IDENTIFICADA: #seasons .content.row
    val seasonsContainer = document.selectFirst("#seasons.tv--section")
    if (seasonsContainer == null) {
        println("[CineAgora] Container de temporadas não encontrado")
        return emptyList()
    }

    println("[CineAgora] Container de temporadas encontrado")

    // 1. Extrair temporadas
    val seasonElements = seasonsContainer.select(".col-lg-3 ul.list.seasons li")
    val seasonsList = mutableListOf<String>()
    
    seasonElements.forEachIndexed { index, seasonElement ->
        val seasonText = seasonElement.text().trim()
        if (seasonText.isNotBlank()) {
            seasonsList.add(seasonText)
            println("[CineAgora] Temporada encontrada: $seasonText")
        }
    }

    // 2. Extrair episódios da coluna direita
    val episodesContainer = seasonsContainer.selectFirst(".col-lg-9 ul.list.episodes")
    if (episodesContainer == null) {
        println("[CineAgora] Container de episódios vazio")
        return emptyList()
    }

    val episodeElements = episodesContainer.select("li")
    println("[CineAgora] ${episodeElements.size} episódio(s) encontrado(s)")

    episodeElements.forEachIndexed { index, episodeElement ->
        try {
            // Encontrar link do episódio
            val linkElement = episodeElement.selectFirst("a")
            if (linkElement == null) {
                println("[CineAgora] Episódio ${index + 1} sem link")
                return@forEachIndexed
            }

            val episodeHref = linkElement.attr("href")
            if (episodeHref.isBlank()) {
                println("[CineAgora] Episódio ${index + 1} tem href vazio")
                return@forEachIndexed
            }

            // Extrair número do episódio
            val episodeNumberElement = episodeElement.selectFirst(".episode-number")
            val episodeNumberText = episodeNumberElement?.text()?.trim() ?: "Episódio ${index + 1}"
            
            // Extrair número como inteiro
            val episodeNumber = try {
                val numMatch = Regex("""Episódio\s+(\d+)""").find(episodeNumberText)
                numMatch?.groupValues?.get(1)?.toIntOrNull() ?: index + 1
            } catch (e: Exception) {
                index + 1
            }

            // Extrair nome do episódio
            val episodeNameElement = episodeElement.selectFirst(".episode-name")
            val episodeName = episodeNameElement?.text()?.trim() ?: episodeNumberText

            // Extrair status (opcional)
            val episodeStatusElement = episodeElement.selectFirst(".episode-not")
            val episodeStatus = episodeStatusElement?.text()?.trim()

            // Extrair data (opcional)
            val episodeDateElement = episodeElement.selectFirst(".episode-date")
            val episodeDate = episodeDateElement?.text()?.trim()

            // Determinar temporada (começa com 1 se não encontrada)
            val seasonNumber = if (seasonsList.isNotEmpty()) {
                // Tentar extrair número da temporada do primeiro elemento
                val seasonMatch = Regex("""Temporada\s+(\d+)""").find(seasonsList.firstOrNull() ?: "")
                seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
            } else {
                1
            }

            // Construir URL completa do episódio
            val episodeUrl = if (episodeHref.startsWith("http")) {
                episodeHref
            } else {
                fixUrl(episodeHref)
            }

            println("[CineAgora] Criando episódio: S${seasonNumber}E${episodeNumber} - $episodeName")

            // Criar objeto Episode
            val episode = newEpisode(episodeUrl) {
                this.name = episodeName
                this.season = seasonNumber
                this.episode = episodeNumber
                
                // Adicionar descrição com status e data se disponíveis
                val descriptionBuilder = StringBuilder()
                episodeStatus?.let { descriptionBuilder.append("Status: $it\n") }
                episodeDate?.let { descriptionBuilder.append("Data: $it") }
                
                if (descriptionBuilder.isNotEmpty()) {
                    this.description = descriptionBuilder.toString()
                }
            }
            
            episodes.add(episode)
            println("[CineAgora] Episódio adicionado com sucesso")

        } catch (e: Exception) {
            println("[CineAgora] Erro ao processar episódio ${index + 1}: ${e.message}")
        }
    }

    // 3. Fallback: Procurar por outras estruturas comuns de episódios
    if (episodes.isEmpty()) {
        println("[CineAgora] Nenhum episódio encontrado, tentando fallback...")
        
        // Fallback 1: Estrutura simples com links
        val simpleEpisodes = document.select("a[href*='episodio'], a[href*='episode'], .episode a")
        simpleEpisodes.forEachIndexed { index, element ->
            try {
                val href = element.attr("href")
                val text = element.text().trim()
                
                if (href.isNotBlank() && !href.startsWith("#") && !href.contains("javascript:")) {
                    val episodeUrl = if (href.startsWith("http")) href else fixUrl(href)
                    
                    // Tentar extrair número do episódio do texto
                    val epNumber = extractEpisodeNumber(text) ?: index + 1
                    
                    val episode = newEpisode(episodeUrl) {
                        this.name = if (text.isNotBlank()) text else "Episódio ${index + 1}"
                        this.season = 1
                        this.episode = epNumber
                    }
                    
                    episodes.add(episode)
                    println("[CineAgora] Episódio fallback adicionado: ${episode.name}")
                }
            } catch (e: Exception) {
                // Ignorar erro
            }
        }
    }

    println("[CineAgora] Total de episódios extraídos: ${episodes.size}")
    return episodes
}

// Função auxiliar para extrair número do episódio do texto
private fun extractEpisodeNumber(text: String): Int? {
    val patterns = listOf(
        Regex("""Epis[oó]dio\s+(\d+)""", RegexOption.IGNORE_CASE),
        Regex("""Ep\.?\s*(\d+)""", RegexOption.IGNORE_CASE),
        Regex("""E(\d+)""", RegexOption.IGNORE_CASE),
        Regex("""\b(\d+)\b""")
    )
    
    for (pattern in patterns) {
        val match = pattern.find(text)
        if (match != null) {
            return match.groupValues[1].toIntOrNull()
        }
    }
    
    return null
}
    // Função para encontrar URL do player (para filmes)
    private fun findPlayerUrl(document: org.jsoup.nodes.Document, baseUrl: String): String? {
        // Procurar por elementos do player
        val playerElements = document.select("iframe[src], video source[src], [data-url], button[data-url]")

        for (element in playerElements) {
            // Verificar iframe
            val iframeSrc = element.attr("src")
            if (iframeSrc.isNotBlank() && (iframeSrc.contains("embed") || iframeSrc.contains("player") || 
                iframeSrc.contains("m3u8") || iframeSrc.contains("mp4"))) {
                return fixUrl(iframeSrc)
            }

            // Verificar data-url
            val dataUrl = element.attr("data-url")
            if (dataUrl.isNotBlank() && (dataUrl.contains("m3u8") || dataUrl.contains("mp4") || 
                dataUrl.contains("embed"))) {
                return fixUrl(dataUrl)
            }

            // Verificar source
            val sourceSrc = element.selectFirst("source")?.attr("src")
            if (sourceSrc?.isNotBlank() == true && (sourceSrc.contains("m3u8") || sourceSrc.contains("mp4"))) {
                return fixUrl(sourceSrc)
            }
        }

        // Procurar em scripts
        val scripts = document.select("script:not([src])")
        for (script in scripts) {
            val scriptContent = script.data()
            if (scriptContent.contains("m3u8") || scriptContent.contains("mp4") || 
                scriptContent.contains("player") || scriptContent.contains("video")) {

                // Tentar extrair URL
                val urlRegex = Regex("""(https?://[^"' \s]*\.(?:m3u8|mp4)[^"' \s]*)""")
                val match = urlRegex.find(scriptContent)
                if (match != null) {
                    return match.groupValues[1]
                }

                // Tentar extrair URL de embed
                val embedRegex = Regex("""['"](https?://[^'"]*(?:embed|player)[^'"]*)['"]""")
                val embedMatch = embedRegex.find(scriptContent)
                if (embedMatch != null) {
                    return embedMatch.groupValues[1]
                }
            }
        }

        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[CineAgora] loadLinks chamado com data: $data")
        println("[CineAgora] isCasting: $isCasting")

        // Se for uma URL do YouTube, deixar o sistema padrão do CloudStream lidar
        if (data.contains("youtube.com") || data.contains("youtu.be")) {
            println("[CineAgora] URL do YouTube detectada, ignorando...")
            return false
        }
        
        // Determinar se é série
        val isSerie = data.contains("/series-") || 
                     data.contains("/serie-") || 
                     data.contains("/series-online") ||
                     data.contains("/tv-")

        println("[CineAgora] É série: $isSerie")

        // Usar o extractor otimizado
        return CineAgoraExtractor.extractVideoLinks(data, name, callback)
    }
}
