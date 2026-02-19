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

    // Main page - vamos detectar tudo dinamicamente
    override val mainPage = mainPageOf(
        "$mainUrl" to "Início"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val home = mutableListOf<HomePageList>()

        println("🔍 Escaneando site em busca de categorias...")

        // ============================================
        // PASSO 1: DETECTAR TODAS AS CATEGORIAS DO MENU
        // ============================================
        val categorias = mutableListOf<Pair<String, String>>() // Nome, Link
        
        document.select(".scroll-menu .genre-card").forEach { card ->
            val link = card.attr("href")
            val nome = card.select(".overlay").text().trim()
            
            if (link.isNotBlank() && nome.isNotBlank()) {
                println("📌 Categoria encontrada: $nome -> $link")
                categorias.add(Pair(nome, link))
            }
        }

        // ============================================
        // PASSO 2: PARA CADA CATEGORIA, EXTRAIR CONTEÚDOS
        // USANDO A MESMA LÓGICA QUE JÁ FUNCIONA
        // ============================================
        categorias.forEach { (nome, link) ->
            try {
                println("📂 Carregando categoria: $nome")
                
                // Carregar a página da categoria
                val catDoc = app.get(link).document
                
                // Extrair items usando os seletores que funcionam
                val items = catDoc.select(".ntry, .stream-card, .movie-card").mapNotNull { card ->
                    card.toSearchResult()
                }.map { item ->
                    // Aplicar as transformações de imagem
                    item.posterUrl = item.posterUrl?.let { imgUrl ->
                        imgUrl.replace("/w240-h240-p-k-no-nu", "/w320-h180-p-k-no-nu")
                              .replace("=w240-h240", "=w320-h180")
                              .replace("/w600-h337-p-k-no-nu", "/w320-h180-p-k-no-nu")
                              .replace("=s240", "=s320")
                              .replace("-rw-e90", "")
                              .replace("-p-k-no-nu-rw-e90", "")
                    }
                    item
                }
                
                if (items.isNotEmpty()) {
                    println("✅ ${nome}: ${items.size} itens encontrados")
                    home.add(HomePageList(nome, items, isHorizontalImages = true))
                } else {
                    println("⚠️ ${nome}: Nenhum item encontrado")
                }
                
            } catch (e: Exception) {
                println("❌ Erro ao carregar $nome: ${e.message}")
            }
        }

        // ============================================
        // PASSO 3: TAMBÉM CAPTURAR SEÇÕES ESPECIAIS DA HOME
        // ============================================
        
        // Seções de carrossel (Últimas Postagens, Natal, Netflix, etc.)
        document.select(".stream-section").forEach { section ->
            val title = section.selectFirst(".stream-header .stream-title")?.text() ?: return@forEach
            // Evitar duplicar categorias que já pegamos do menu
            if (categorias.none { it.first == title }) {
                val items = section.select(".stream-card").mapNotNull { it.toSearchResult() }
                if (items.isNotEmpty()) {
                    println("🎬 Seção especial: $title (${items.size} itens)")
                    home.add(HomePageList(title, items, isHorizontalImages = true))
                }
            }
        }

        // Top 10
        document.select("#top10-wrapper").forEach { wrapper ->
            val title = wrapper.selectFirst(".section-title")?.text() ?: "Top 10 da Semana"
            val items = wrapper.select(".movie-card").mapNotNull { it.toSearchResult() }
            if (items.isNotEmpty()) {
                println("🏆 $title (${items.size} itens)")
                home.add(HomePageList(title, items, isHorizontalImages = true))
            }
        }

        println("🎯 Total de seções carregadas: ${home.size}")
        return newHomePageResponse(home, false)
    }

    // ============================================
    // FUNÇÃO DE CONVERSÃO DE CARD PARA SEARCH RESPONSE
    // ============================================
    private fun Element.toSearchResult(): SearchResponse? {
        val article = this
        
        // Extrair título
        val titleElement = article.selectFirst(".pTtl a, .stream-name, .movie-title, .overlay") ?: return null
        val title = titleElement.text()?.trim() ?: return null
        
        // Extrair URL
        val href = when {
            article.hasAttr("href") -> article.attr("href")
            article.selectFirst("a") != null -> article.selectFirst("a")?.attr("href")
            article.selectFirst(".movie-link") != null -> article.selectFirst(".movie-link")?.attr("href")
            article.selectFirst("a.stream-btn") != null -> article.selectFirst("a.stream-btn")?.attr("href")
            else -> titleElement.attr("href")
        } ?: return null

        // Extrair imagem
        val imgElement = article.selectFirst("img")
        val posterUrl = when {
            imgElement?.hasAttr("data-src") == true -> {
                imgElement.attr("data-src")
                    .replace("-rw-e90", "")
                    .replace("-p-k-no-nu-rw-e90", "")
            }
            imgElement?.hasAttr("src") == true -> {
                imgElement.attr("src")
                    .replace("-rw-e90", "")
                    .replace("-p-k-no-nu-rw-e90", "")
            }
            else -> null
        }

        // Extrair descrição/tags
        val description = article.selectFirst(".pSnpt, .stream-genres, .movie-genres")?.text() ?: ""
        val tags = article.select(".pLbls a, .stream-genre, .movie-genres span").map { it.text() }
        
        // Determinar tipo
        val isSerie = tags.any { 
                          it.contains("Série", ignoreCase = true) ||
                          it.contains("Séries", ignoreCase = true)
                      } ||
                      description.contains("Série", ignoreCase = true) ||
                      description.contains("Temporada", ignoreCase = true) ||
                      title.contains("Série", ignoreCase = true) ||
                      href.contains("/search/label/S%C3%A9rie")
        
        val isAnime = tags.any { 
                          it.contains("Anime", ignoreCase = true) ||
                          it.contains("Animes", ignoreCase = true)
                      } ||
                      description.contains("Anime", ignoreCase = true) ||
                      title.contains("Anime", ignoreCase = true) ||
                      href.contains("/search/label/Anime")

        return when {
            isAnime -> newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) { 
                this.posterUrl = posterUrl
                this.plot = description
            }
            isSerie -> newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) { 
                this.posterUrl = posterUrl
                this.plot = description
            }
            else -> newMovieSearchResponse(title, fixUrl(href), TvType.Movie) { 
                this.posterUrl = posterUrl
                this.plot = description
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val url = "$mainUrl/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
            val document = app.get(url).document
            document.select(".ntry, .stream-card, .movie-card, .post .item").mapNotNull { it.toSearchResult() }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val document = app.get(url).document
            
            // Extrair título
            val title = document.selectFirst("h1.post-title")?.text() 
                ?: document.selectFirst(".pTtl.itm")?.text()
                ?: document.selectFirst(".pTtl a")?.text() 
                ?: document.selectFirst("title")?.text()?.replace(" - Filmes P K - Filmes e Séries", "")
                ?: return null
            
            // Extrair imagem
            val poster = document.selectFirst("meta[property='og:image']")?.attr("content")
                ?.replace(Regex("""/s\d+(-c)?/"""), "/s1600/")
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
            
            // Extrair descrição
            val description = document.selectFirst(".post-body p")?.text() 
                ?: document.selectFirst(".pEnt")?.text()
                ?: document.selectFirst(".pSnpt")?.text()
                ?: document.selectFirst("meta[name='description']")?.attr("content")
            
            // Extrair ano
            val year = document.selectFirst(".pInf .pYr, .year, time[datetime*='20'], time[datetime*='19']")?.text()
                ?.let { Regex("""\b(19|20)\d{2}\b""").find(it)?.value?.toIntOrNull() }
                ?: Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()
            
            // Extrair tags para determinar tipo
            val tags = document.select(".post-labels a, .pLbls a").map { it.text() }
            
            // Determinar se é série
            val isSerie = tags.any { it.contains("Série", ignoreCase = true) } ||
                          document.select(".tabs, .seasons, .episodes").isNotEmpty() ||
                          title.contains("Série", ignoreCase = true) ||
                          title.contains("Temporada", ignoreCase = true) ||
                          url.contains("Série", ignoreCase = true)
            
            if (isSerie) {
                // Extrair episódios
                val episodes = extractEpisodes(document, url)
                
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = description
                    this.year = year
                    this.tags = tags
                }
            } else {
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.plot = description
                    this.year = year
                    this.tags = tags
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun extractEpisodes(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()

        // Sistema de abas do tema Plus UI
        val tabsContainer = document.selectFirst("div.tabs")
        if (tabsContainer != null) {
            val seasonContents = tabsContainer.select("> div:not(:first-of-type)")
            
            seasonContents.forEachIndexed { index, seasonDiv ->
                val seasonNumber = index + 1
                
                seasonDiv.select("a[href]").forEachIndexed { epIndex, linkElement ->
                    val epUrl = linkElement.attr("href")
                    val epText = linkElement.text().trim()
                    
                    if (epUrl.isNotBlank()) {
                        val episodeNumber = extractEpisodeNumber(epText) ?: (epIndex + 1)
                        val episodeName = if (epText.isNotBlank()) {
                            epText.replace(Regex("""[Tt]emporada\s*\d+\s*[-|]"""), "")
                                  .replace(Regex("""[Ee]pis[óo]dio\s*\d+\s*[-|]"""), "")
                                  .replace(Regex("""(S\d+E\d+)\s*[-|]"""), "")
                                  .trim()
                        } else {
                            "Episódio $episodeNumber"
                        }
                        
                        episodes.add(
                            newEpisode(fixUrl(epUrl)) {
                                this.name = episodeName
                                this.episode = episodeNumber
                                this.season = seasonNumber
                            }
                        )
                    }
                }
            }
        }

        // Se não encontrou episódios, criar um padrão
        if (episodes.isEmpty()) {
            episodes.add(newEpisode(baseUrl) { 
                this.name = "Assistir"
                this.season = 1
                this.episode = 1
            })
        }

        return episodes
    }

    private fun extractEpisodeNumber(text: String): Int? {
        val patterns = listOf(
            Regex("""Epis[óo]dio\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""EP?\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""E(\d+)""", RegexOption.IGNORE_CASE)
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
            
            // Iframes
            document.select("iframe[src*='embed'], iframe[src*='player'], iframe[src*='video']").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank()) {
                    loadExtractor(fixUrl(src), data, subtitleCallback, callback)
                    foundLinks = true
                }
            }

            // Links diretos
            document.select("a[href*='player'], a[href*='embed'], a[href*='watch'], a[href*='videos']").forEach { link ->
                val href = link.attr("href")
                if (href.isNotBlank()) {
                    loadExtractor(fixUrl(href), data, subtitleCallback, callback)
                    foundLinks = true
                }
            }

            // Scripts com URLs
            document.select("script").forEach { script ->
                val content = script.html()
                Regex("""(https?://[^"' ]*\.(?:mp4|m3u8|mkv|avi|mov|flv)[^"' ]*)""").findAll(content).forEach { match ->
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
