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
            
            // Processar episódios por temporada
            seasonsMap.forEach { (seasonNum, epElements) ->
                epElements.forEachIndexed { epIndex, epElement ->
                    try {
                        val episodeNumber = epIndex + 1
                        
                        // Extrair informações do episódio
                        val titleElement = epElement.selectFirst(".episode-name, .title, .name")
                        val title = titleElement?.text()?.trim() ?: "Episódio $episodeNumber"
                        
                        // Tentar extrair número do episódio
                        val epNumElement = epElement.selectFirst(".episode-number, .number, .ep-num")
                        val epNum = epNumElement?.text()?.toIntOrNull() ?: episodeNumber
                        
                        // Tentar extrair URL do episódio
                        val onclick = epElement.attr("onclick") ?: epElement.selectFirst("a")?.attr("onclick")
                        val dataId = epElement.attr("data-id")
                        val dataUrl = epElement.attr("data-url")
                        
                        // Construir URL do episódio
                        val episodeUrl = when {
                            onclick?.isNotBlank() == true && onclick.contains("loadEpisode") -> {
                                // Extrair parâmetros da função loadEpisode
                                val paramsRegex = Regex("""loadEpisode\s*\(\s*['"]([^'"]+)['"]""")
                                val match = paramsRegex.find(onclick)
                                if (match != null) {
                                    // Construir URL baseada nos parâmetros
                                    val params = match.groupValues[1]
                                    "$mainUrl/episode/$params"
                                } else {
                                    null
                                }
                            }
                            dataUrl.isNotBlank() -> fixUrl(dataUrl)
                            dataId.isNotBlank() -> "$mainUrl/episode/$dataId"
                            else -> null
                        }
                        
                        if (episodeUrl != null) {
                            val episode = newEpisode(episodeUrl) {
                                this.name = title
                                this.season = seasonNum
                                this.episode = epNum
                                
                                // Tentar extrair descrição/data se disponível
                                epElement.selectFirst(".episode-date, .date")?.text()?.trim()?.let { dateStr ->
                                    this.description = dateStr
                                }
                                
                                epElement.selectFirst(".episode-desc, .description")?.text()?.trim()?.let { desc ->
                                    this.description = desc
                                }
                            }
                            episodes.add(episode)
                        }
                    } catch (e: Exception) {
                        // Ignorar episódio com erro
                    }
                }
            }
        }
        
        // 2. Fallback: Estrutura simples com lista de episódios
        if (episodes.isEmpty()) {
            val simpleEpisodeElements = document.select(".episode-list li, .episodes li, [data-episode], .episode")
            simpleEpisodeElements.forEachIndexed { index, element ->
                try {
                    val episodeNumber = index + 1
                    
                    // Extrair número da temporada
                    val seasonNumber = element.attr("data-season").toIntOrNull() ?: 
                                      element.selectFirst("[data-season]")?.attr("data-season")?.toIntOrNull() ?: 1
                    
                    // Extrair número do episódio
                    val epNum = element.attr("data-episode").toIntOrNull() ?: 
                               element.selectFirst("[data-episode]")?.attr("data-episode")?.toIntOrNull() ?: episodeNumber
                    
                    // Extrair título
                    val titleElement = element.selectFirst(".title, .name, .episode-title")
                    val title = titleElement?.text()?.trim() ?: "Episódio $epNum"
                    
                    // Tentar extrair URL
                    val dataUrl = element.attr("data-url")
                    val href = element.selectFirst("a")?.attr("href")
                    val onclick = element.attr("onclick")
                    
                    val episodeUrl = when {
                        dataUrl.isNotBlank() -> fixUrl(dataUrl)
                        href?.isNotBlank() == true && href != "#" -> fixUrl(href)
                        onclick?.isNotBlank() == true && onclick.contains("http") -> {
                            val urlRegex = Regex("""['"](https?://[^'"]+)['"]""")
                            val match = urlRegex.find(onclick)
                            match?.groupValues?.get(1)
                        }
                        else -> null
                    }
                    
                    if (episodeUrl != null) {
                        val episode = newEpisode(episodeUrl) {
                            this.name = title
                            this.season = seasonNumber
                            this.episode = epNum
                        }
                        episodes.add(episode)
                    }
                } catch (e: Exception) {
                    // Ignorar episódio com erro
                }
            }
        }
        
        return episodes
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
    
    // Adiciona um pequeno delay para evitar rate limiting
    kotlinx.coroutines.delay(1000)
    
    // Usar o extractor separado
    val success = CineAgoraExtractor.extractVideoLinks(data, name, callback)
    
    if (!success) {
        println("[CineAgora] Extração falhou, tentando método alternativo...")
        // Pode tentar um método alternativo aqui se necessário
    }
    
    return success
}
