package com.CineAgora

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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
        
        // Construir badges para mostrar no Cloudstream
        val badges = mutableListOf<String>()
        
        if (qualityBadge != null && qualityBadge.isNotBlank()) {
            badges.add("📀 $qualityBadge")
        }
        
        if (languageBadge != null && languageBadge.isNotBlank()) {
            badges.add("🗣️ $languageBadge")
        }
        
        if (scoreText != null && scoreText.isNotBlank() && scoreText != "N/A") {
            badges.add("⭐ $scoreText")
        }
        
        if (lastEpisodeInfo != null && lastEpisodeInfo.isNotBlank()) {
            badges.add("📺 $lastEpisodeInfo")
        }
        
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
                // Adicionar badges como metadata
                if (badges.isNotEmpty()) {
                    this.description = badges.joinToString(" • ")
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
                // Adicionar badges como metadata
                if (badges.isNotEmpty()) {
                    this.description = badges.joinToString(" • ")
                }
            }
        }
    }

    // SISTEMA DE RECOMENDAÇÕES DO CLOUDSTREAM
    override suspend fun getRecommendations(url: String): List<SearchResponse> {
        return try {
            // Carregar a página para obter recomendações
            val document = app.get(url).document
            
            // Primeiro tentar encontrar a seção "Últimos lançamentos do cinema" que você identificou
            val recommendationsSection = document.select(".title--area:contains(Últimos lançamentos do cinema) + .content, " +
                                                       ".title--area:contains(Recomendações) + .content, " +
                                                       ".title--area:contains(Populares) + .content, " +
                                                       ".section-recommendations, " +
                                                       ".recommended, " +
                                                       ".similar")
            
            val recommendations = if (recommendationsSection.isNotEmpty()) {
                // Extrair itens da seção de recomendações
                recommendationsSection.first().select(".item, .item-relative .item, .poster, .movie-item, .serie-item")
                    .mapNotNull { it.toSearchResult() }
            } else {
                // Fallback: procurar por seções que pareçam recomendações
                val allSections = document.select(".content, .section, .widget")
                val recommendedItems = mutableListOf<SearchResponse>()
                
                for (section in allSections) {
                    val sectionTitle = section.select(".title, h2, h3, h4").text()
                    if (sectionTitle.contains(Regex("(?i)(recomenda|similar|relacionado|também|outros|lançamento)"))) {
                        val items = section.select(".item, .item-relative .item, .poster, .movie-item, .serie-item")
                            .mapNotNull { it.toSearchResult() }
                        recommendedItems.addAll(items)
                    }
                }
                
                // Se não encontrou recomendações específicas, pegar alguns itens aleatórios da página
                if (recommendedItems.isEmpty()) {
                    document.select(".item, .item-relative .item, .poster, .movie-item, .serie-item")
                        .shuffled()
                        .take(10)
                        .mapNotNull { it.toSearchResult() }
                } else {
                    recommendedItems.distinctBy { it.url }
                }
            }
            
            // Limitar a 15 recomendações no máximo
            recommendations.take(15)
            
        } catch (e: Exception) {
            // Se falhar, retornar lista vazia
            emptyList()
        }
    }

    // Melhorar a função load para incluir recomendações quando possível
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
        val description = document.selectFirst(".description, .sinopse, .plot, .content p")?.text()?.trim()
        
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
        
        // Extrair atores/diretores se disponível
        val actors = document.select(".actors a, .cast a, .elenco a")
            .mapNotNull { it.text().trim() }
            .takeIf { it.isNotEmpty() }
        
        // Extrair duração (para filmes)
        val duration = document.selectFirst(".duration, .runtime, .time")?.text()?.trim()
        
        // Extrair temporadas/episódios (para séries)
        val seasons = mutableListOf<Episode>()
        if (isSerie) {
            val episodeElements = document.select(".episodes-list .episode, .episode-item, [data-episode]")
            if (episodeElements.isNotEmpty()) {
                // Se houver lista de episódios, criar temporadas
                episodesToSeasons(episodeElements)
            }
        }
        
        return if (isSerie) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, seasons) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = genres
                this.actors = actors
                this.recommendations = getRecommendations(url)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = genres
                this.actors = actors
                this.duration = duration?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
                this.recommendations = getRecommendations(url)
            }
        }
    }
    
    private fun episodesToSeasons(episodeElements: org.jsoup.select.Elements): List<Episode> {
        val seasons = mutableListOf<Episode>()
        val seasonMap = mutableMapOf<Int, MutableList<Episode>>()
        
        episodeElements.forEach { element ->
            val episodeNumber = element.attr("data-episode")?.toIntOrNull() 
                ?: Regex("E(\\d+)").find(element.text())?.groupValues?.get(1)?.toIntOrNull()
                ?: 1
            
            val seasonNumber = element.attr("data-season")?.toIntOrNull()
                ?: Regex("S(\\d+)").find(element.text())?.groupValues?.get(1)?.toIntOrNull()
                ?: 1
            
            val title = element.selectFirst(".title, .name")?.text()?.trim()
                ?: element.text().trim()
            
            val episodeUrl = element.selectFirst("a")?.attr("href")?.let { fixUrl(it) }
            
            if (episodeUrl != null) {
                val episode = newEpisode(episodeUrl) {
                    this.name = title
                    this.season = seasonNumber
                    this.episode = episodeNumber
                }
                
                if (!seasonMap.containsKey(seasonNumber)) {
                    seasonMap[seasonNumber] = mutableListOf()
                }
                seasonMap[seasonNumber]?.add(episode)
            }
        }
        
        // Ordenar por temporada e episódio
        seasonMap.keys.sorted().forEach { seasonNum ->
            seasonMap[seasonNum]?.sortedBy { it.episode }?.forEach { seasons.add(it) }
        }
        
        return seasons
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return false
    }
}
