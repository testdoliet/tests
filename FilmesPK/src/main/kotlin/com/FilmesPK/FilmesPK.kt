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
            
            // Extrair descrição - tema Plus UI
            val description = document.selectFirst(".post-body")?.text() 
                ?: document.selectFirst(".pEnt")?.text()
                ?: document.selectFirst(".pSnpt")?.text()
            
            // Extrair ano da descrição
            val year = description?.let { 
                Regex("""\b(19|20)\d{2}\b""").find(it)?.value?.toIntOrNull()
            }
            
            // Extrair avaliação e converter para score - tema Plus UI
            val score = document.selectFirst(".tfxC .pV")?.text()?.let { 
                // Tentar extrair valor numérico (ex: "8.5/10" ou "4.2 ★")
                val numericMatch = Regex("""(\d+(\.\d+)?)""").find(it)
                numericMatch?.value?.toFloatOrNull()?.let { numericValue ->
                    // Converter para score (0-100) se for de 0-10
                    when {
                        it.contains("/10") -> Score.from10(numericValue)
                        it.contains("★") && numericValue <= 5 -> Score.from5(numericValue)
                        numericValue <= 10 -> Score.from10(numericValue)
                        else -> Score.from100(numericValue.toInt())
                    }
                }
            }
            
            // Extrair duração - tema Plus UI
            val duration = document.selectFirst(".pInf .pRd span[data-minutes]")?.attr("data-minutes")?.toIntOrNull()
            
            // Extrair classificação indicativa (PG) - tema Plus UI
            val pgRating = document.selectFirst("meta[name='rating']")?.attr("content")
                ?: document.selectFirst(".pInf .pRd")?.text()?.let { text ->
                    when {
                        text.contains("Livre", ignoreCase = true) -> "L"
                        text.contains("10", ignoreCase = true) -> "10"
                        text.contains("12", ignoreCase = true) -> "12"
                        text.contains("14", ignoreCase = true) -> "14"
                        text.contains("16", ignoreCase = true) -> "16"
                        text.contains("18", ignoreCase = true) -> "18"
                        else -> null
                    }
                }
            
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
                        val seasonName = seasonLabels.getOrNull(seasonIndex)?.text() ?: "Temporada $seasonNumber"
                        
                        // Extrair episódios desta temporada
                        seasonDiv.select("a").forEachIndexed { episodeIndex, element ->
                            val epUrl = element.attr("href")
                            val epText = element.text().trim()
                            
                            if (epUrl.isNotBlank()) {
                                val episodeNumber = extractEpisodeNumber(epText) ?: (episodeIndex + 1)
                                
                                episodes.add(newEpisode(fixUrl(epUrl)) {
                                    this.name = if (epText.isNotBlank()) epText else "Episódio $episodeNumber"
                                    this.episode = episodeNumber
                                    this.season = seasonNumber
                                })
                            }
                        }
                    }
                } else {
                    // Sistema antigo (sem tabs)
                    document.select(".post-body a").forEachIndexed { index, element ->
                        val epUrl = element.attr("href")
                        val epText = element.text().trim()
                        
                        if (epUrl.isNotBlank() && (epUrl.contains("episodio") || 
                                                   epUrl.contains("episode") || 
                                                   epUrl.contains("temporada") ||
                                                   epText.contains("Episódio") ||
                                                   epText.contains("EP"))) {
                            
                            val episodeNumber = extractEpisodeNumber(epText) ?: (index + 1)
                            
                            episodes.add(newEpisode(fixUrl(epUrl)) {
                                this.name = if (epText.isNotBlank()) epText else "Episódio $episodeNumber"
                                this.episode = episodeNumber
                            })
                        }
                    }
                }
                
                // Se não encontrou episódios específicos, criar um episódio com o link da página
                if (episodes.isEmpty()) {
                    episodes.add(newEpisode(url) { 
                        this.name = "Assistir"
                    })
                }

                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = description
                    this.year = year
                    this.score = score
                    this.duration = duration?.toString()
                    this.tags = if (pgRating != null) listOf(pgRating) else emptyList()
                }
            } else {
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.plot = description
                    this.year = year
                    this.score = score
                    this.duration = duration?.toString()
                    this.tags = if (pgRating != null) listOf(pgRating) else emptyList()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    private fun extractEpisodeNumber(text: String): Int? {
        val patterns = listOf(
            Regex("""Episódio\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""EP\s*(\d+)""", RegexOption.IGNORE_CASE),
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
