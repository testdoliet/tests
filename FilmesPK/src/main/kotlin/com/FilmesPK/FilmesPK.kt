package com.FilmesPK

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class FilmesPK : MainAPI() {

    override var mainUrl = "https://www.filmesrave.online"
    override var name = "Filmes P K"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override val mainPage = mainPageOf(
        "$mainUrl" to "Top 10 da Semana",
        "$mainUrl/search/label/S%C3%A9rie" to "Série",
        "$mainUrl/search/label/Doramas" to "Doramas",
        "$mainUrl/search/label/Disney" to "Disney",
        "$mainUrl/search/label/Anime" to "Anime",
        "$mainUrl/search/label/A%C3%A7%C3%A3o" to "Ação",
        "$mainUrl/search/label/Anima%C3%A7%C3%A3o" to "Animação",
        "$mainUrl/search/label/Aventura" to "Aventura",
        "$mainUrl/search/label/Com%C3%A9dia" to "Comédia",
        "$mainUrl/search/label/Drama" to "Drama",
        "$mainUrl/search/label/Romance" to "Romance",
        "$mainUrl/search/label/Terror" to "Terror"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isHomePage = request.data == mainUrl
        
        // Para páginas com botão "Carregar mais postagens", precisamos simular o clique
        var url = request.data
        var document = app.get(url).document
        
        // Se for página 2 ou mais, precisamos simular múltiplos cliques no botão jsLd
        if (page > 1) {
            // O site usa um sistema de paginação via JavaScript com token de continuidade
            // Precisamos extrair o token atual e usá-lo para carregar mais
            var currentToken: String? = null
            var currentItems = mutableListOf<Element>()
            
            // Primeiro, obter o documento inicial
            document = app.get(url).document
            currentToken = extractContinuationToken(document)
            
            // Simular os cliques necessários para chegar à página desejada
            for (i in 1 until page) {
                if (currentToken != null) {
                    // Carregar mais itens usando o token
                    val moreContent = loadMoreWithToken(currentToken)
                    if (moreContent != null) {
                        // Extrair novos itens do conteúdo carregado
                        val newItems = moreContent.select(".ntry, .stream-card, .movie-card")
                        currentItems.addAll(newItems)
                        
                        // Extrair novo token para próxima página
                        currentToken = extractContinuationToken(moreContent)
                    } else {
                        break
                    }
                } else {
                    break
                }
            }
            
            // Se conseguimos carregar itens extras, processá-los
            if (currentItems.isNotEmpty() && !isHomePage) {
                // Para categorias (não-homepage)
                val items = currentItems.mapNotNull { it.toSearchResult() }.map { item ->
                    // Ajustar thumbs para modo horizontal
                    item.posterUrl = item.posterUrl?.let { imgUrl ->
                        imgUrl.replace("/w240-h240-p-k-no-nu", "/w320-h180-p-k-no-nu")
                              .replace("=w240-h240", "=w320-h180")
                              .replace("/w600-h337-p-k-no-nu", "/w320-h180-p-k-no-nu")
                              .replace("=s240", "=s320")
                    }
                    item
                }
                
                val home = mutableListOf<HomePageList>()
                home.add(HomePageList(
                    request.name,
                    items,
                    isHorizontalImages = true
                ))
                
                // Verificar se ainda há mais conteúdo para carregar
                val hasNextPage = currentToken != null && currentToken.isNotBlank()
                return newHomePageResponse(home, hasNextPage)
            }
        }

        val home = mutableListOf<HomePageList>()
        var itemsCount = 0

        // Para a página inicial (Top 10 da Semana)
        if (isHomePage) {
            document.select(".stream-carousel").forEach { carousel ->
                val title = carousel.previousElementSibling()?.selectFirst(".stream-title, h2")?.text() 
                    ?: carousel.attr("id").replace("-carousel", "").capitalize()
                val items = carousel.select(".stream-card").mapNotNull { it.toSearchResult() }
                if (items.isNotEmpty()) {
                    home.add(HomePageList(title, items))
                    itemsCount += items.size
                }
            }
            
            // Também pegamos movie-cards avulsos se houver
            val otherItems = document.select(".movie-card").mapNotNull { it.toSearchResult() }
            if (otherItems.isNotEmpty()) {
                home.add(HomePageList("Destaques", otherItems))
                itemsCount += otherItems.size
            }
        } else {
            // Para páginas de categoria/label - layout horizontal
            val items = document.select(".ntry").mapNotNull { 
                val item = it.toSearchResult()
                // Ajustar thumbs para modo horizontal
                item?.posterUrl = item?.posterUrl?.let { imgUrl ->
                    imgUrl.replace("/w240-h240-p-k-no-nu", "/w320-h180-p-k-no-nu")
                          .replace("=w240-h240", "=w320-h180")
                          .replace("/w600-h337-p-k-no-nu", "/w320-h180-p-k-no-nu")
                          .replace("=s240", "=s320")
                          .replace("-rw-e90", "")
                          .replace("-p-k-no-nu-rw-e90", "")
                }
                item
            }
            itemsCount = items.size
            
            home.add(HomePageList(
                request.name,
                items,
                isHorizontalImages = true
            ))
        }

        // Verificar se há botão "Carregar mais postagens"
        val hasNextPage = document.select("button.jsLd").isNotEmpty() || 
                         document.select("#blog-pager .jsLd").isNotEmpty() ||
                         document.select("a.blog-pager-older-link").isNotEmpty() ||
                         extractContinuationToken(document) != null

        return newHomePageResponse(home, hasNextPage)
    }

    // Função para extrair o token de continuidade do documento
    private fun extractContinuationToken(document: org.jsoup.nodes.Document): String? {
        // Procura por scripts que contenham tokens de continuidade
        document.select("script").forEach { script ->
            val scriptContent = script.html()
            // Padrões comuns para tokens de continuidade
            val patterns = listOf(
                """continuation=["']([^"']+)["']""",
                """continuation:["']([^"']+)["']""",
                """token["']?\s*:\s*["']([^"']+)["']""",
                """loadMoreToken["']?\s*:\s*["']([^"']+)["']""",
                """["']continuation["']\s*:\s*["']([^"']+)["']"""
            )
            
            for (pattern in patterns) {
                val match = Regex(pattern).find(scriptContent)
                match?.groupValues?.get(1)?.let { token ->
                    if (token.isNotBlank() && token.length > 10) {
                        return token
                    }
                }
            }
        }
        
        // Também verifica por atributos data-*
        document.select("[data-continuation], [data-token]").forEach { element ->
            element.attr("data-continuation")?.takeIf { it.isNotBlank() }?.let { return it }
            element.attr("data-token")?.takeIf { it.isNotBlank() }?.let { return it }
        }
        
        return null
    }

    // Função para carregar mais conteúdo usando o token
    private suspend fun loadMoreWithToken(token: String): org.jsoup.nodes.Document? {
        return try {
            // Tentar carregar usando uma requisição POST similar ao que o site faz
            val response = app.post(
                url = "$mainUrl/api/more",
                data = mapOf(
                    "continuation" to token,
                    "ctoken" to token,
                    "type" to "posts"
                ),
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json",
                    "Content-Type" to "application/x-www-form-urlencoded"
                )
            )
            
            // O site pode retornar HTML ou JSON
            val responseText = response.text
            if (responseText.contains("<div") || responseText.contains("class=")) {
                // Parece ser HTML
                org.jsoup.Jsoup.parse(responseText)
            } else {
                // Pode ser JSON, tentar extrair HTML do JSON
                try {
                    val json = response.parsedSafe<Map<String, Any>>()
                    val htmlContent = (json?.get("content") as? String) ?: 
                                     (json?.get("html") as? String) ?:
                                     (json?.get("items") as? String)
                    
                    if (htmlContent != null) {
                        org.jsoup.Jsoup.parse(htmlContent)
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val article = this
        
        // Extrair título do link dentro de .pTtl
        val titleElement = article.selectFirst(".pTtl a") ?: return null
        val title = titleElement.text() ?: return null
        
        // Extrair URL
        val href = titleElement.attr("href") ?: return null
        
        // Extrair imagem - prioridade para data-src, depois src
        val imgElement = article.selectFirst("img")
        val posterUrl = when {
            imgElement?.hasAttr("data-src") == true -> {
                val src = imgElement.attr("data-src")
                src.replace("-rw-e90", "")
                   .replace("-p-k-no-nu-rw-e90", "")
                   .replace("/w600-h337-p-k-no-nu", "/w240-h240-p-k-no-nu")
                   .replace("/w[0-9]+-h[0-9]+-p-k-no-nu", "/w240-h240-p-k-no-nu")
            }
            imgElement?.hasAttr("src") == true -> {
                val src = imgElement.attr("src")
                src.replace("-rw-e90", "")
                   .replace("-p-k-no-nu-rw-e90", "")
                   .replace("/w600-h337-p-k-no-nu", "/w240-h240-p-k-no-nu")
                   .replace("/w[0-9]+-h[0-9]+-p-k-no-nu", "/w240-h240-p-k-no-nu")
            }
            else -> null
        }

        // Extrair descrição
        val description = article.selectFirst(".pSnpt")?.text() ?: ""
        
        // Determinar tipo baseado no conteúdo
        val isSerie = description.contains("Série", ignoreCase = true) ||
                      title.contains("Série", ignoreCase = true) ||
                      href.contains("/search/label/S%C3%A9rie") ||
                      article.select(".pLbls a").any { 
                          it.text().contains("Série", ignoreCase = true) 
                      }
        
        val isAnime = description.contains("Anime", ignoreCase = true) ||
                      title.contains("Anime", ignoreCase = true) ||
                      href.contains("/search/label/Anime") ||
                      article.select(".pLbls a").any { 
                          it.text().contains("Anime", ignoreCase = true) 
                      }

        return when {
            isAnime -> newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) { 
                this.posterUrl = posterUrl
            }
            isSerie -> newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) { 
                this.posterUrl = posterUrl
            }
            else -> newMovieSearchResponse(title, fixUrl(href), TvType.Movie) { 
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val url = "$mainUrl/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
            val document = app.get(url).document
            document.select(".ntry").mapNotNull { it.toSearchResult() }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val document = app.get(url).document
            
            // Extrair título - tema Plus UI
            val title = document.selectFirst("h1.post-title")?.text() 
                ?: document.selectFirst(".pTtl.itm")?.text()
                ?: document.selectFirst(".pTtl a")?.text() 
                ?: return null
            
            // Extrair imagem do artigo - tema Plus UI (alta resolução)
            val poster = document.selectFirst("meta[property='og:image']")?.attr("content")
                ?.replace(Regex("""/s\d+(-c)?/"""), "/s1600/") // Garante resolução máxima
                ?: document.selectFirst(".pThmb img")?.let { img ->
                    when {
                        img.hasAttr("data-src") -> img.attr("data-src")
                            .replace("-rw-e90", "")
                            .replace("-p-k-no-nu-rw-e90", "")
                            .replace(Regex("""/s\d+(-c)?/"""), "/s1600/")
                        img.hasAttr("src") -> img.attr("src")
                            .replace("-rw-e90", "")
                            .replace("-p-k-no-nu-rw-e90", "")
                            .replace(Regex("""/s\d+(-c)?/"""), "/s1600/")
                        else -> null
                    }
                }
                ?: document.selectFirst("img.imgThm")?.attr("src")?.let { src ->
                    src.replace("-rw-e90", "")
                       .replace("-p-k-no-nu-rw-e90", "")
                       .replace(Regex("""/s\d+(-c)?/"""), "/s1600/")
                }
            
            // Extrair descrição LIMPA - tema Plus UI
            val description = cleanDescription(document)
            
            // Extrair ano da descrição
            val year = extractYearFromDocument(document) ?: description?.let { 
                Regex("""\b(19|20)\d{2}\b""").find(it)?.value?.toIntOrNull()
            }
            
            // Extrair avaliação e converter para score - tema Plus UI
            val score = extractScoreFromDocument(document)
            
            // Extrair duração - tema Plus UI (já é Int? correto)
            val duration = extractDurationFromDocument(document)
            
            // Extrair classificação indicativa (PG) - tema Plus UI
            val pgRating = extractPGRatingFromDocument(document)
            
            // Determinar se é série baseado no tema Plus UI
            val hasTabs = document.select(".tabs").isNotEmpty()
            val isSerie = hasTabs || 
                          url.contains("Série", ignoreCase = true) || 
                          document.select(".post-labels a").any { 
                              it.text().contains("Séries", ignoreCase = true) || 
                              it.text().contains("Série", ignoreCase = true)
                          } ||
                          title.contains("Série", ignoreCase = true) ||
                          description?.contains("Temporada", ignoreCase = true) == true ||
                          description?.contains("Episódio", ignoreCase = true) == true

            return if (isSerie) {
                // Extrair episódios corretamente
                val episodes = extractEpisodesFromDocument(document, url)
                
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = description
                    this.year = year
                    this.score = score
                    this.duration = duration
                    this.tags = if (pgRating != null) listOf(pgRating) else emptyList()
                }
            } else {
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.plot = description
                    this.year = year
                    this.score = score
                    this.duration = duration
                    this.tags = if (pgRating != null) listOf(pgRating) else emptyList()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    private fun cleanDescription(document: org.jsoup.nodes.Document): String? {
        // Primeiro tenta pegar a sinopse limpa
        val postBody = document.selectFirst(".post-body")
        
        // Remove elementos que não são sinopse
        postBody?.let { body ->
            // Remove botões, players, lista de episódios, etc
            body.select("button, .button, .player, iframe, script, .tabs, .episodes-list, .season-list").remove()
            
            // Procura por texto que parece sinopse (parágrafos com mais de 20 caracteres)
            val paragraphs = body.select("p").map { it.text().trim() }
                .filter { it.length > 20 && 
                         !it.contains("★") && 
                         !it.contains("min :") &&
                         !it.contains("Temporada") &&
                         !it.contains("Episódio") &&
                         !it.contains("ASSISTIR") &&
                         !it.contains("Player") &&
                         !it.contains("VPN") }
            
            if (paragraphs.isNotEmpty()) {
                return paragraphs.joinToString("\n\n")
            }
            
            // Se não encontrou parágrafos, pega o texto completo e limpa
            val fullText = body.text()
            if (fullText.isNotBlank()) {
                // Divide por linhas e filtra
                val lines = fullText.split("\n").map { it.trim() }
                    .filter { line ->
                        line.isNotBlank() &&
                        !line.contains("★") &&
                        !line.contains("/10") &&
                        !line.contains("min :") &&
                        !line.contains("Temporada") &&
                        !line.contains("Episódio") &&
                        !line.contains("ASSISTIR") &&
                        !line.contains("▶") &&
                        !line.contains("Player") &&
                        !line.contains("VPN") &&
                        line.length > 30 // Linhas muito curtas provavelmente são títulos/links
                    }
                
                if (lines.isNotEmpty()) {
                    return lines.joinToString("\n")
                }
            }
        }
        
        // Fallback para .pEnt ou .pSnpt
        return document.selectFirst(".pEnt")?.text()?.trim()
            ?: document.selectFirst(".pSnpt")?.text()?.trim()
    }
    
    private fun extractYearFromDocument(document: org.jsoup.nodes.Document): Int? {
        // Tenta extrair do título
        val title = document.selectFirst("h1.post-title")?.text() ?: ""
        val yearFromTitle = Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()
        if (yearFromTitle != null) return yearFromTitle
        
        // Tenta extrair de elementos específicos do tema Plus UI
        val yearElement = document.selectFirst(".pInf .pYr, .year, .date, time[datetime*='20'], time[datetime*='19']")
        yearElement?.text()?.let { text ->
            val yearMatch = Regex("""\b(19|20)\d{2}\b""").find(text)
            if (yearMatch != null) return yearMatch.value.toIntOrNull()
        }
        
        return null
    }
    
    private fun extractScoreFromDocument(document: org.jsoup.nodes.Document): Score? {
        // Tenta extrair do elemento .tfxC .pV (tema Plus UI)
        val scoreElement = document.selectFirst(".tfxC .pV")
        scoreElement?.text()?.let { text ->
            // Tentar extrair valor numérico (ex: "8.5/10" ou "4.2 ★")
            val numericMatch = Regex("""(\d+(\.\d+)?)""").find(text)
            numericMatch?.value?.toFloatOrNull()?.let { numericValue ->
                // Converter para score (0-100)
                return when {
                    text.contains("/10") -> Score.from10(numericValue)
                    text.contains("★") && numericValue <= 5 -> Score.from5(numericValue)
                    numericValue <= 10 -> Score.from10(numericValue)
                    else -> Score.from100(numericValue.toInt())
                }
            }
        }
        
        return null
    }
    
    private fun extractDurationFromDocument(document: org.jsoup.nodes.Document): Int? {
        // Tenta extrair do elemento data-minutes (tema Plus UI)
        val durationElement = document.selectFirst(".pInf .pRd span[data-minutes]")
        durationElement?.attr("data-minutes")?.toIntOrNull()?.let { return it }
        
        // Tenta extrair do texto
        durationElement?.text()?.let { text ->
            // Procura por padrões como "1h30", "90 min", etc
            val patterns = listOf(
                Regex("""(\d+)\s*h\s*(\d+)\s*min"""), // 1h 30 min
                Regex("""(\d+)\s*h(\d+)"""),          // 1h30
                Regex("""(\d+)\s*min"""),             // 90 min
                Regex("""(\d+)\s*minutos""")          // 90 minutos
            )
            
            for (pattern in patterns) {
                val match = pattern.find(text)
                if (match != null) {
                    return when (match.groupValues.size) {
                        3 -> { // Tem horas e minutos
                            val hours = match.groupValues[1].toIntOrNull() ?: 0
                            val minutes = match.groupValues[2].toIntOrNull() ?: 0
                            hours * 60 + minutes
                        }
                        2 -> { // Apenas minutos
                            match.groupValues[1].toIntOrNull()
                        }
                        else -> null
                    }
                }
            }
        }
        
        return null
    }
    
    private fun extractPGRatingFromDocument(document: org.jsoup.nodes.Document): String? {
        // Tenta extrair do meta tag
        val metaRating = document.selectFirst("meta[name='rating']")?.attr("content")
        if (metaRating != null && metaRating.isNotBlank() && metaRating != "general") {
            return metaRating
        }
        
        // Tenta extrair do texto
        val ratingText = document.selectFirst(".pInf .pRd")?.text()
        ratingText?.let { text ->
            return when {
                text.contains("Livre", ignoreCase = true) -> "L"
                text.contains("10", ignoreCase = true) -> "10"
                text.contains("12", ignoreCase = true) -> "12"
                text.contains("14", ignoreCase = true) -> "14"
                text.contains("16", ignoreCase = true) -> "16"
                text.contains("18", ignoreCase = true) -> "18"
                else -> null
            }
        }
        
        return null
    }
    
    private fun extractEpisodesFromDocument(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        // Verificar se tem sistema de abas (tabs) do tema Plus UI
        val tabs = document.select(".tabs")
        if (tabs.isNotEmpty()) {
            // Sistema de abas (temporadas) do tema Plus UI
            // Selecionar labels das temporadas
            val seasonLabels = tabs.select("> div:first-of-type label")
            // Selecionar conteúdo das temporadas
            val seasonContents = tabs.select("> div:not(:first-of-type)")
            
            seasonContents.forEachIndexed { seasonIndex, seasonDiv ->
                val seasonNumber = seasonIndex + 1
                
                // Extrair episódios desta temporada
                seasonDiv.select("a").forEachIndexed { episodeIndex, element ->
                    val epUrl = element.attr("href")
                    val epText = element.text().trim()
                    
                    if (epUrl.isNotBlank()) {
                        val episodeNumber = extractEpisodeNumber(epText) ?: (episodeIndex + 1)
                        
                        episodes.add(newEpisode(fixUrl(epUrl)) {
                            this.name = if (epText.isNotBlank()) cleanEpisodeTitle(epText) else "Episódio $episodeNumber"
                            this.episode = episodeNumber
                            this.season = seasonNumber
                        })
                    }
                }
            }
        } else {
            // Sistema antigo - extrair do post-body
            val postBody = document.selectFirst(".post-body")
            if (postBody != null) {
                var currentSeason = 1
                var episodeCount = 0
                
                // Procura por padrões de temporada
                val lines = postBody.text().split("\n")
                for (line in lines) {
                    val trimmedLine = line.trim()
                    
                    // Detecta nova temporada
                    if (trimmedLine.contains("Temporada", ignoreCase = true) ||
                        trimmedLine.contains("Season", ignoreCase = true)) {
                        val seasonMatch = Regex("""(?i)temporada\s*(\d+)|season\s*(\d+)""").find(trimmedLine)
                        seasonMatch?.let {
                            val seasonNum = it.groupValues[1].toIntOrNull() ?: it.groupValues[2].toIntOrNull()
                            if (seasonNum != null) {
                                currentSeason = seasonNum
                                episodeCount = 0
                            }
                        }
                    }
                    
                    // Detecta episódios
                    if ((trimmedLine.contains("E") && Regex("""E\d+""").containsMatchIn(trimmedLine)) ||
                        trimmedLine.contains("Episódio", ignoreCase = true)) {
                        
                        // Procura por links na linha ou próximo
                        val linkElement = findEpisodeLinkNearText(postBody, trimmedLine)
                        if (linkElement != null) {
                            val epUrl = linkElement.attr("href")
                            if (epUrl.isNotBlank()) {
                                episodeCount++
                                val episodeNumber = extractEpisodeNumber(trimmedLine) ?: episodeCount
                                
                                episodes.add(newEpisode(fixUrl(epUrl)) {
                                    this.name = cleanEpisodeTitle(trimmedLine)
                                    this.episode = episodeNumber
                                    this.season = currentSeason
                                })
                            }
                        }
                    }
                }
            }
        }
        
        // Se não encontrou episódios específicos, criar um episódio com o link da página
        if (episodes.isEmpty()) {
            episodes.add(newEpisode(baseUrl) { 
                this.name = "Assistir"
                this.season = 1
                this.episode = 1
            })
        }

        return episodes
    }
    
    private fun findEpisodeLinkNearText(container: Element, text: String): Element? {
        // Procura por um link próximo ao texto
        val elements = container.select("*")
        for (element in elements) {
            if (element.text().contains(text) && element.tagName() == "a") {
                return element
            }
            if (element.text().contains(text)) {
                val link = element.selectFirst("a")
                if (link != null) return link
                
                // Procura no próximo elemento
                element.nextElementSibling()?.selectFirst("a")?.let { return it }
            }
        }
        return null
    }
    
    private fun cleanEpisodeTitle(title: String): String {
        var cleaned = title.trim()
        
        // Remove avaliações
        cleaned = cleaned.replace(Regex("""★\s*\d+(\.\d+)?/10"""), "")
        cleaned = cleaned.replace(Regex("""\d+(\.\d+)?/10"""), "")
        
        // Remove durações
        cleaned = cleaned.replace(Regex("""\d+h\d*\s*min\s*:?"""), "")
        cleaned = cleaned.replace(Regex("""\d+\s*min\s*:?"""), "")
        
        // Remove caracteres especiais
        cleaned = cleaned.replace("🎁", "")
        cleaned = cleaned.replace("▶", "")
        cleaned = cleaned.replace(":", "")
        cleaned = cleaned.replace("v", "")
        
        // Limpa espaços extras
        cleaned = cleaned.replace(Regex("""\s+"""), " ").trim()
        
        return if (cleaned.isBlank()) "Episódio" else cleaned
    }
    
    private fun extractEpisodeNumber(text: String): Int? {
        val patterns = listOf(
            Regex("""Episódio\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""EP?\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""E(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""(\d+)\s*ª?\s*Temp""", RegexOption.IGNORE_CASE),
            Regex("""[Tt]emp\s*(\d+)"""),
            Regex("""\b(\d{1,3})\b""")
        )
        
        for (pattern in patterns) {
            pattern.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let {
                return it
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
        return try {
            val document = app.get(data).document
            var foundLinks = false
            
            document.select("iframe[src*='embed'], iframe[src*='player'], iframe[src*='video']").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank()) {
                    loadExtractor(fixUrl(src), data, subtitleCallback, callback)
                    foundLinks = true
                }
            }

            document.select("[data-url]").forEach { element ->
                val url = element.attr("data-url")
                if (url.contains("http")) {
                    loadExtractor(fixUrl(url), data, subtitleCallback, callback)
                    foundLinks = true
                }
            }

            document.select(".post-body a[href*='player'], .post-body a[href*='embed'], .post-body a[href*='watch']").forEach { link ->
                val href = link.attr("href")
                if (href.isNotBlank() && href.contains("http")) {
                    loadExtractor(fixUrl(href), data, subtitleCallback, callback)
                    foundLinks = true
                }
            }

            document.select("script").forEach { script ->
                val content = script.html()
                val videoUrls = Regex("""(https?://[^"' ]*\.(?:mp4|m3u8|mkv|avi|mov|flv)[^"' ]*)""").findAll(content)
                videoUrls.forEach { match ->
                    loadExtractor(fixUrl(match.value), data, subtitleCallback, callback)
                    foundLinks = true
                }
                
                val embedUrls = Regex("""(https?://[^"' ]*\.(?:com|net|org)/[^"' ]*embed[^"' ]*)""").findAll(content)
                embedUrls.forEach { match ->
                    loadExtractor(fixUrl(match.value), data, subtitleCallback, callback)
                    foundLinks = true
                }
            }

            foundLinks
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
