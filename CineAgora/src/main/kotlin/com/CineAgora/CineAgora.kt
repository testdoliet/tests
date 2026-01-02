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
        val poster = document.selectFirst("img.poster, .poster img, img.thumbnail, .thumbnail img")?.attr("src")?.let { fixUrl(it) }
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

    // Função para extrair episódios (baseado na análise)
    private fun extractEpisodesFromPage(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        // TENTAR DIFERENTES ESTRUTURAS DE EPISÓDIOS
        
        // 1. Estrutura com temporadas e episódios (#seasons .list.episodes)
        val seasonsContainer = document.selectFirst("#seasons, .seasons, .season-list")
        if (seasonsContainer != null) {
            // Extrair temporadas
            val seasonElements = seasonsContainer.select(".list.seasons li, .season-tab, .season-item")
            val seasonsMap = mutableMapOf<Int, MutableList<Element>>()
            
            // Processar cada temporada
            seasonElements.forEachIndexed { seasonIndex, seasonElement ->
                val seasonNumber = seasonIndex + 1
                
                // Encontrar episódios desta temporada
                val episodeElements = seasonElement.parent()?.select(".list.episodes li, .episode-item") ?: 
                                     seasonsContainer.select(".list.episodes.show li, .episode-item")
                
                episodeElements.forEach { episodeElement ->
                    seasonsMap.getOrPut(seasonNumber) { mutableListOf() }.add(episodeElement)
                }
            }
            
override suspend fun load(url: String): LoadResponse? {
        println("[CineAgora] Carregando URL: $url")
        
        val doc = app.get(url).document
        
        // 1. EXTRAIR BANNER/POSTER
        val bannerUrl = extractBannerUrl(doc)
        val posterUrl = doc.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }
        
        // 2. TÍTULO
        val title = doc.selectFirst("h1.title, h1, .title, h2")?.text()?.trim() ?: "Título não encontrado"
        
        // 3. DETERMINAR SE É SÉRIE OU FILME
        val isSerie = url.contains("/series-") || url.contains("/serie-") || url.contains("/tv-") || 
                     url.contains("/series-online") ||
                     doc.select(".player-controls, #episodeDropdown, .seasons").isNotEmpty()
        
        println("[CineAgora] É série? $isSerie")
        
        if (isSerie) {
            // 4. PARA SÉRIES: Extrair episódios via API
            val episodes = extractSeriesEpisodes(url, title)
            
            // 5. INFORMAÇÕES ADICIONAIS
            val year = extractYear(doc)
            val plot = doc.selectFirst(".info-description, .description, .sinopse, .plot")?.text()?.trim()
            val genres = extractGenres(doc)
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = bannerUrl
                this.year = year
                this.plot = plot
                this.tags = genres
                
                // Extrair e adicionar trailer
                extractTrailer(doc)?.let { addTrailer(it) }
            }
        } else {
            // 6. PARA FILMES: Extrair URL do player
            val playerUrl = extractPlayerUrl(doc)
            
            // 7. INFORMAÇÕES ADICIONAIS
            val year = extractYear(doc)
            val plot = doc.selectFirst(".info-description, .description, .sinopse, .plot")?.text()?.trim()
            val genres = extractGenres(doc)
            val duration = doc.selectFirst(".duration, .runtime, .time")?.text()?.trim()
            
            return newMovieLoadResponse(title, url, TvType.Movie, playerUrl ?: url) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = bannerUrl
                this.year = year
                this.plot = plot
                this.tags = genres
                this.duration = duration?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
                
                // Extrair e adicionar trailer
                extractTrailer(doc)?.let { addTrailer(it) }
            }
        }
    }
    
    /**
     * Extrai episódios de uma série usando a API brplayer
     */
    private suspend fun extractSeriesEpisodes(seriesUrl: String, seriesTitle: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        try {
            // Extrair slug da série da URL
            val seriesSlug = extractSeriesSlug(seriesUrl)
            println("[CineAgora] Slug da série: $seriesSlug")
            
            // Chamar API para obter episódios
            val apiUrl = "https://watch.brplayer.cc/fetch_series_data.php?seriesSlug=$seriesSlug"
            val response = app.get(apiUrl)
            
            val json = tryParseJson<Map<String, Any>>(response.text)
            if (json == null) {
                println("[CineAgora] API retornou JSON inválido")
                return episodes
            }
            
            val seasons = json["seasons"] as? Map<String, List<Map<String, Any>>> ?: emptyMap()
            println("[CineAgora] Encontradas ${seasons.size} temporadas")
            
            seasons.forEach { (seasonNum, seasonEpisodes) ->
                println("[CineAgora] Temporada $seasonNum com ${seasonEpisodes.size} episódios")
                
                seasonEpisodes.forEach { episode ->
                    val episodeNumber = episode["episode_number"]?.toString()?.toIntOrNull() ?: 0
                    val videoSlug = episode["video_slug"]?.toString()
                    
                    if (videoSlug != null && episodeNumber > 0) {
                        // Construir URL do episódio (exatamente como mostrado no HTML)
                        val episodeUrl = "https://watch.brplayer.cc/watch/$videoSlug"
                        val episodeName = "$seriesTitle S${seasonNum.padStart(2, '0')}E${episodeNumber.toString().padStart(2, '0')}"
                        
                        val episodeItem = newEpisode(episodeUrl) {
                            this.name = episodeName
                            this.season = seasonNum.toIntOrNull() ?: 1
                            this.episode = episodeNumber
                        }
                        
                        episodes.add(episodeItem)
                        println("[CineAgora] Adicionado episódio: $episodeName")
                    }
                }
            }
            
        } catch (e: Exception) {
            println("[CineAgora] Erro ao extrair episódios: ${e.message}")
        }
        
        return episodes.sortedBy { it.episode }
    }
    
    private fun extractSeriesSlug(url: String): String {
        // Extrair slug da URL (ex: pluribus de https://cineagora.net/series-online-hd-gratis/2984-pluribus.html)
        return url
            .substringAfterLast("/")           // 2984-pluribus.html
            .substringAfter("-")               // pluribus.html
            .substringBefore(".html")          // pluribus
            .trim()
    }
    
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
    
    private fun extractPlayerUrl(doc: org.jsoup.nodes.Document): String? {
        // Procurar iframe do player
        val iframe = doc.selectFirst("iframe[src*='watch.brplayer.cc']")
        if (iframe != null) {
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                return fixUrl(src)
            }
        }
        
        // Procurar em scripts
        val scripts = doc.select("script:not([src])")
        for (script in scripts) {
            val content = script.html()
            if (content.contains("watch.brplayer.cc")) {
                val regex = Regex("""(https?://watch\.brplayer\.cc/watch/[A-Z0-9]+)""")
                val match = regex.find(content)
                if (match != null) {
                    return match.groupValues[1]
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
        
        // Ignorar URLs do YouTube
        if (data.contains("youtube.com") || data.contains("youtu.be")) {
            println("[CineAgora] URL do YouTube ignorada")
            return false
        }
        
        // Extrair o nome do título da URL (para mostrar nos links)
        val name = data.substringAfterLast("/").substringBefore("?").replace("-", " ").replace("_", " ")
        
        // Usar o extrator otimizado
        return CineAgoraExtractor.extractVideoLinks(data, name, callback)
    }
}
