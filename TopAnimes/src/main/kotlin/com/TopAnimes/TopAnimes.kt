package com.TopAnimes

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import org.jsoup.nodes.Element

@CloudstreamPlugin
class TopAnimesPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TopAnimes())
    }
}

class TopAnimes : MainAPI() {
    override var mainUrl = "https://topanimes.net"
    override var name = "TopAnimes"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie, TvType.OVA)
    override val usesWebView = false

    companion object {
        private const val SEARCH_PATH = "/?s="

        // Função para mapear status
        private fun getStatus(statusText: String): ShowStatus {
            return when {
                statusText.contains("em andamento", ignoreCase = true) ||
                statusText.contains("lançando", ignoreCase = true) -> ShowStatus.Ongoing
                
                statusText.contains("concluído", ignoreCase = true) ||
                statusText.contains("completo", ignoreCase = true) -> ShowStatus.Completed
                
                else -> ShowStatus.Completed
            }
        }

        // Categorias fixas (5 abas fixas + 10 gêneros populares = 15 total)
        private val ALL_CATEGORIES = listOf(
            // 5 abas fixas
            "/animes" to "Todos os Animes",
            "/episodio" to "Últimos Episódios",
            "/tipo/legendado" to "Legendados",
            "/tipo/dublado" to "Dublados",
            "/tipo/donghua" to "Donghua",
            
            // 10 gêneros populares
            "/genero/acao" to "Ação",
            "/genero/aventura" to "Aventura",
            "/genero/comedia" to "Comédia",
            "/genero/drama" to "Drama",
            "/genero/fantasia" to "Fantasia",
            "/genero/romance" to "Romance",
            "/genero/ficcao-cientifica" to "Ficção Científica",
            "/genero/misterio" to "Mistério",
            "/genero/terror" to "Terror",
            "/genero/sobrenatural" to "Sobrenatural"
        )
    }

    override val mainPage = mainPageOf(
        *ALL_CATEGORIES.map { (path, name) -> 
            "$mainUrl$path" to name 
        }.toTypedArray()
    )

    private fun extractPoster(element: Element): String? {
        return try {
            // Método 1: Procura imagem dentro de .poster
            val posterDiv = element.selectFirst(".poster")
            
            if (posterDiv != null) {
                val img = posterDiv.selectFirst("img")
                img?.let {
                    // PRIMEIRO tenta data-src (lazy loading)
                    var src = it.attr("data-src")
                    
                    // Se data-src estiver vazio, tenta src normal
                    if (src.isBlank()) {
                        src = it.attr("src")
                    }
                    
                    // Se for data URI (base64), ignora
                    if (src.startsWith("data:image")) {
                        return null
                    }
                    
                    if (src.isNotBlank() && !src.startsWith("data:")) {
                        return src
                    }
                }
            }
            
            // Método 2: Procura qualquer imagem no elemento
            val img = element.selectFirst("img")
            
            img?.let {
                // PRIMEIRO tenta data-src
                var src = it.attr("data-src")
                if (src.isBlank()) {
                    src = it.attr("src")
                }
                
                // Ignora data URIs
                if (src.isNotBlank() && !src.startsWith("data:")) {
                    return src
                }
            }
            
            null
        } catch (e: Exception) {
            null
        }
    }

    // Função específica para extrair episódios
    private fun Element.toEpisodeItem(): AnimeSearchResponse? {
        return try {
            // Encontra o link principal
            val linkElement = selectFirst("a[href]") ?: return null
            val href = linkElement.attr("href")
            if (href.isBlank()) return null

            // Verifica se é um episódio (contém /episodio/ na URL)
            if (!href.contains("/episodio/")) return null

            // Extrai o nome da série do elemento <span class="serie">
            val serieElement = selectFirst(".serie")
            val serieName = serieElement?.text()?.trim() ?: return null

            // Extrai o número/título do episódio do elemento <h3>
            val episodeElement = selectFirst("h3")
            val episodeTitle = episodeElement?.text()?.trim() ?: "Episódio"

            // Extrai número do episódio do título
            val episodeNumber = extractEpisodeNumber(episodeTitle) ?: 1

            // Determina se é DUB ou LEG pela badge
            val hasDubBadge = selectFirst(".buttonextra.dub-ep") != null
            val hasLegBadge = selectFirst(".buttonextra.leg-ep") != null
            
            val audioType = when {
                hasDubBadge -> "DUB"
                hasLegBadge -> "LEG"
                else -> "LEG" // padrão
            }

            // Extrai qualidade (se disponível)
            val qualityElement = selectFirst(".quality")
            val quality = qualityElement?.text()?.trim() ?: ""

            // Extrai poster
            val posterUrl = extractPoster(this)

            // Para episódios, usamos um título mais descritivo
            val displayTitle = "$serieName - Ep $episodeNumber"

            // Cria o item de busca
            return newAnimeSearchResponse(displayTitle, fixUrl(href)) {
                this.posterUrl = posterUrl
                
                // Define tipo como Anime
                this.type = TvType.Anime
                
                // Adiciona status de dublagem COM número do episódio
                val hasDub = audioType == "DUB"
                if (hasDub) {
                    addDubStatus(DubStatus.Dubbed, episodeNumber)
                } else {
                    addDubStatus(DubStatus.Subbed, episodeNumber)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    // Função para itens normais (animes)
    private fun Element.toAnimeItem(): AnimeSearchResponse? {
        return try {
            // Verifica se é um elemento article com classe .item
            val isItem = hasClass("item") || hasClass("tvshows") || hasClass("movies") || tagName() == "article"
            
            if (!isItem) {
                return null
            }

            // Tenta encontrar o link dentro do elemento
            val linkElement = selectFirst("a[href]")
            if (linkElement == null) return null
            
            val href = linkElement.attr("href")
            if (href.isBlank() || (!href.contains("/animes/") && !href.contains("/filmes/"))) {
                return null
            }

            // Extrai título
            val titleElement = selectFirst(".data h3, h3, .serie")
            if (titleElement == null) return null
            
            val rawTitle = titleElement.text().trim()
            if (rawTitle.isBlank()) return null

            // Extrai ano (apenas)
            val yearElement = selectFirst(".data span:last-child, span:last-child, .year")
            val yearText = yearElement?.text()?.trim()
            val year = extractYear(yearText)

            // Determina se é dublado ou legendado
            val hasExplicitDub = rawTitle.contains("dublado", ignoreCase = true) || 
                                href.contains("dublado", ignoreCase = true)
            val hasExplicitLeg = rawTitle.contains("legendado", ignoreCase = true) || 
                                href.contains("legendado", ignoreCase = true)

            val finalHasDub: Boolean
            val finalHasLeg: Boolean

            when {
                hasExplicitDub && !hasExplicitLeg -> {
                    finalHasDub = true
                    finalHasLeg = false
                }
                !hasExplicitDub && hasExplicitLeg -> {
                    finalHasDub = false
                    finalHasLeg = true
                }
                hasExplicitDub && hasExplicitLeg -> {
                    finalHasDub = true
                    finalHasLeg = true
                }
                else -> {
                    finalHasDub = false
                    finalHasLeg = true
                }
            }

            val cleanName = extractAnimeName(rawTitle)
            val isMovie = href.contains("/filmes/") || 
                         rawTitle.contains("filme", ignoreCase = true) ||
                         rawTitle.contains("movie", ignoreCase = true)
            
            // Extrai poster
            val sitePoster = extractPoster(this)

            return newAnimeSearchResponse(cleanName, fixUrl(href)) {
                this.posterUrl = sitePoster
                this.type = if (isMovie) TvType.Movie else TvType.Anime
                this.year = year

                if (finalHasDub || finalHasLeg) {
                    addDubStatus(dubExist = finalHasDub, subExist = finalHasLeg)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    // Função unificada que decide qual tipo de item extrair
    private fun Element.toSearchResponse(): AnimeSearchResponse? {
        // Verifica se é um episódio primeiro
        val isEpisode = hasClass("se") && hasClass("episodes")
        
        return if (isEpisode) {
            toEpisodeItem()
        } else {
            toAnimeItem()
        }
    }

    private fun extractAnimeName(fullText: String): String {
        var cleanName = fullText

        val patterns = listOf(
            Regex("(?i)\\(dublado\\)"),
            Regex("(?i)\\(legendado\\)"),
            Regex("(?i)dublado"),
            Regex("(?i)legendado"),
            Regex("(?i)todos os episódios"),
            Regex("\\s*-\\s*$"),
            Regex("\\(\\d{4}\\)"),
            Regex("\\s*\\[.*?\\]")
        )

        patterns.forEach { pattern ->
            cleanName = cleanName.replace(pattern, "")
        }

        return cleanName.trim().replace(Regex("\\s+"), " ")
    }

    private fun extractYear(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val yearRegex = Regex("""(\d{4})""")
        val match = yearRegex.find(text)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractEpisodeNumber(title: String): Int? {
        val patterns = listOf(
            Regex("""Epis[oó]dio\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""Ep\.?\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""E(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""\b(\d{1,3})\b""")
        )
        
        for (pattern in patterns) {
            pattern.find(title)?.groupValues?.get(1)?.toIntOrNull()?.let {
                return it
            }
        }
        return null
    }

    private suspend fun detectHasNextPage(document: org.jsoup.nodes.Document, currentPageNum: Int): Boolean {
        return try {
            val pagination = document.select(".pagination")
            if (pagination.isNotEmpty()) {
                val hasNextLink = pagination.select("a[href*='page/${currentPageNum + 1}'], a:contains(Próxima), .arrow_pag").isNotEmpty()
                return hasNextLink
            }
            
            val respPages = document.select(".resppages a")
            if (respPages.isNotEmpty()) {
                return true
            }
            
            false
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        try {
            val basePath = request.data.removePrefix(mainUrl)
            val sitePageNumber = page + 1

            val pageUrl = if (sitePageNumber == 1) {
                "$mainUrl$basePath"
            } else {
                "$mainUrl$basePath/page/$sitePageNumber"
            }

            val document = app.get(pageUrl, timeout = 20).document
            
            // Seletores para TODOS os tipos de itens
            val elements = document.select("article.item, article.se.episodes, .items article, article.tvshows, article.movies")
            
            val homeItems = mutableListOf<SearchResponse>()

            elements.forEachIndexed { index, element ->
                try {
                    val item = element.toSearchResponse()
                    if (item != null) {
                        homeItems.add(item)
                    }
                } catch (e: Exception) {
                    // Ignora erros
                }
            }

            val hasNextPage = detectHasNextPage(document, sitePageNumber)

            // CORREÇÃO: Para a aba "Últimos Episódios", usamos isHorizontalImages = true
            return if (request.name == "Últimos Episódios") {
                newHomePageResponse(
                    list = HomePageList(request.name, homeItems.distinctBy { it.url }, isHorizontalImages = true),
                    hasNext = hasNextPage
                )
            } else {
                newHomePageResponse(
                    request.name,
                    homeItems.distinctBy { it.url },
                    hasNext = hasNextPage
                )
            }

        } catch (e: Exception) {
            return newHomePageResponse(request.name, emptyList(), false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()

        val searchUrl = "$mainUrl$SEARCH_PATH${query.trim().replace(" ", "+")}"

        return try {
            val document = app.get(searchUrl, timeout = 10).document

            document.select("article.item, article.se.episodes, article.tvshows, article.movies")
                .mapNotNull { it.toSearchResponse() }
                .distinctBy { it.url }
                .take(30)

        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun extractGenres(document: org.jsoup.nodes.Document): List<String> {
        val genres = mutableListOf<String>()
        
        document.select(".sgeneros a").forEach { element ->
            element.text().trim().takeIf { it.isNotBlank() }?.let { 
                genres.add(it) 
            }
        }

        if (genres.isEmpty()) {
            document.select(".custom_fields:contains(Gênero) span.valor, .animeInfo:contains(Gênero) span")
                .firstOrNull()
                ?.text()
                ?.trim()
                ?.split(",", ";")
                ?.forEach { genre ->
                    genre.trim().takeIf { it.isNotBlank() }?.let { 
                        genres.add(it) 
                    }
                }
        }

        return genres
    }

    private fun extractAudioType(document: org.jsoup.nodes.Document): Pair<Boolean, Boolean> {
        val url = document.location() ?: ""
        val title = document.selectFirst("h1")?.text() ?: ""
        
        val hasDub = url.contains("dublado", ignoreCase = true) || 
                    title.contains("dublado", ignoreCase = true)
        val hasSub = !hasDub || url.contains("legendado", ignoreCase = true) || 
                    title.contains("legendado", ignoreCase = true)
        
        return Pair(hasDub, hasSub)
    }

    // Função para imprimir informações de debug
    private fun printDebugInfo(document: org.jsoup.nodes.Document, baseUrl: String) {
        println("\n🔍 DEBUG INFO PARA: $baseUrl")
        println("=".repeat(50))
        
        // Mostra título da página
        val title = document.selectFirst("h1")?.text()?.trim()
        println("Título da página: $title")
        
        // Mostra todas as classes usadas na página
        val allElements = document.select("*")
        val allClasses = mutableSetOf<String>()
        
        allElements.forEach { element ->
            element.classNames().forEach { className ->
                allClasses.add(className)
            }
        }
        
        println("\nClasses encontradas na página (${allClasses.size} total):")
        allClasses.filter { 
            it.contains("episod", ignoreCase = true) || 
            it.contains("season", ignoreCase = true) ||
            it.contains("temp", ignoreCase = true) ||
            it.contains("lista", ignoreCase = true) ||
            it.contains("list", ignoreCase = true)
        }.sorted().forEach { className ->
            println("  - $className")
        }
        
        // Mostra estrutura HTML relevante
        println("\nEstrutura HTML relevante:")
        val possibleEpisodeContainers = document.select(".episodios, .se-a, #serie_contenido, [class*='episode'], [class*='episodio']")
        if (possibleEpisodeContainers.isNotEmpty()) {
            possibleEpisodeContainers.forEachIndexed { i, container ->
                println("\nContainer $i (tag: ${container.tagName()}, classes: ${container.classNames()}):")
                println(container.html().take(800))
            }
        } else {
            println("Nenhum container de episódios encontrado")
        }
        
        println("=".repeat(50) + "\n")
    }

    override suspend fun load(url: String): LoadResponse {
        return try {
            val document = app.get(url, timeout = 30).document
            
            // DEBUG: Mostra informações da página
            printDebugInfo(document, url)

            val title = document.selectFirst("h1")?.text()?.trim() 
                ?: document.selectFirst(".data h1")?.text()?.trim() 
                ?: document.selectFirst(".sheader h1")?.text()?.trim()
                ?: "Sem Título"

            // Poster na página de detalhes
            val poster = document.selectFirst(".sheader .poster img, .poster img")?.let { img ->
                var src = img.attr("data-src")
                if (src.isBlank()) {
                    src = img.attr("src")
                }
                if (src.isNotBlank() && !src.startsWith("data:")) {
                    src
                } else null
            } ?: document.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }

            val background = document.selectFirst("meta[property='og:image']")?.attr("content")
                ?.takeIf { it.isNotBlank() }
                ?.let { fixUrl(it) }

            val synopsis = document.selectFirst(".wp-content p")?.text()?.trim()
                ?: document.selectFirst(".sbox .wp-content")?.text()?.trim()
                ?: document.selectFirst(".synopsis")?.text()?.trim()
                ?: "Sinopse não disponível."

            val yearText = document.selectFirst(".extra .date")?.text()?.trim()
                ?: document.selectFirst(".custom_fields:contains(Ano) span.valor")?.text()?.trim()
            val year = extractYear(yearText)

            val statusElement = document.selectFirst(".custom_fields:contains(Status) span.valor")
            val statusText = statusElement?.text()?.trim() ?: "Desconhecido"
            val showStatus = getStatus(statusText)

            val genres = extractGenres(document)

            val (hasDub, hasSub) = extractAudioType(document)

            val isMovie = url.contains("/filmes/") || 
                         title.contains("filme", ignoreCase = true) ||
                         document.selectFirst(".custom_fields:contains(Tipo)")?.text()?.contains("Filme", ignoreCase = true) == true

            val episodes = extractEpisodesFromDocument(document, url)

            val response = newAnimeLoadResponse(
                extractAnimeName(title), 
                url, 
                if (isMovie) TvType.Movie else TvType.Anime
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.year = year
                this.plot = synopsis
                this.tags = genres
                this.showStatus = showStatus

                if (episodes.isNotEmpty()) {
                    if (hasDub && hasSub) {
                        addEpisodes(DubStatus.Subbed, episodes)
                        
                        val dubbedEpisodes = episodes.map { episode ->
                            newEpisode(episode.data) {
                                this.name = "${episode.name} (Dublado)"
                                this.season = episode.season
                                this.episode = episode.episode
                                this.posterUrl = episode.posterUrl
                            }
                        }
                        addEpisodes(DubStatus.Dubbed, dubbedEpisodes)
                    } else if (hasDub) {
                        addEpisodes(DubStatus.Dubbed, episodes)
                    } else {
                        addEpisodes(DubStatus.Subbed, episodes)
                    }
                }
            }

            response

        } catch (e: Exception) {
            println("DEBUG: ❌ ERRO CRÍTICO em load(): ${e.message}")
            e.printStackTrace()
            newAnimeLoadResponse("Erro ao carregar", url, TvType.Anime) {
                this.plot = "Não foi possível carregar esta página: ${e.message}"
            }
        }
    }

    // FUNÇÃO MELHORADA PARA EXTRAIR EPISÓDIOS (COM LOGS MELHORADOS)
    private fun extractEpisodesFromDocument(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        println("DEBUG: Iniciando extração de episódios de: $baseUrl")
        println("DEBUG: HTML total (primeiros 1000 chars): ${document.html().take(1000)}...")

        // TENTATIVA 1: Estrutura principal com .episodios > li
        val episodeElements1 = document.select(".episodios li")
        println("DEBUG: TENTATIVA 1 - Seletor '.episodios li': ${episodeElements1.size} elementos encontrados")
        
        if (episodeElements1.isNotEmpty()) {
            val firstElement = episodeElements1.first()
            println("DEBUG: Exemplo do primeiro elemento HTML (estrutura 1):")
            println(firstElement?.html()?.take(500) ?: "N/A")
            
            episodeElements1.forEachIndexed { index, element ->
                try {
                    println("DEBUG: Processando elemento $index da estrutura 1")
                    
                    val linkElement = element.selectFirst("a")
                    val href = linkElement?.attr("href") ?: ""
                    println("DEBUG: href encontrado: $href")
                    
                    if (href.isBlank()) {
                        println("DEBUG: href vazio, pulando")
                        return@forEachIndexed
                    }

                    var episodeTitle = linkElement.text().trim()
                    if (episodeTitle.isBlank()) {
                        episodeTitle = element.selectFirst(".episodiotitle a")?.text()?.trim() ?: ""
                        println("DEBUG: Título extraído de .episodiotitle: $episodeTitle")
                    }
                    
                    if (episodeTitle.isBlank()) {
                        episodeTitle = element.attr("title")?.trim() ?: ""
                        println("DEBUG: Título extraído do atributo title: $episodeTitle")
                    }
                    
                    if (episodeTitle.isBlank()) {
                        episodeTitle = "Episódio ${index + 1}"
                        println("DEBUG: Título padrão: $episodeTitle")
                    }

                    // Extrai número do episódio de várias fontes possíveis
                    val episodeNumber = extractEpisodeNumberFromMultipleSources(element, href) ?: (index + 1)
                    println("DEBUG: Número do episódio determinado: $episodeNumber")

                    val fixedHref = fixEpisodeUrl(href)
                    println("DEBUG: URL final do episódio: $fixedHref")

                    val episode = newEpisode(fixedHref) {
                        this.name = episodeTitle
                        this.episode = episodeNumber
                        this.season = 1
                    }

                    episodes.add(episode)
                    println("DEBUG: ✅ Episódio adicionado: $episodeTitle (#$episodeNumber)")

                } catch (e: Exception) {
                    println("DEBUG: ❌ Erro extraindo episódio (estrutura 1): ${e.message}")
                    println("DEBUG: Stack trace: ${e.stackTraceToString().take(300)}")
                }
            }
        }

        // TENTATIVA 2: Estrutura alternativa com .se-a .episodios li
        if (episodes.isEmpty()) {
            val episodeElements2 = document.select(".se-a ul.episodios li")
            println("DEBUG: TENTATIVA 2 - Seletor '.se-a ul.episodios li': ${episodeElements2.size} elementos")
            
            if (episodeElements2.isNotEmpty()) {
                val firstElement = episodeElements2.first()
                println("DEBUG: Exemplo do primeiro elemento HTML (estrutura 2):")
                println(firstElement?.html()?.take(500) ?: "N/A")
                
                episodeElements2.forEachIndexed { index, element ->
                    try {
                        println("DEBUG: Processando elemento $index da estrutura 2")
                        
                        val linkElement = element.selectFirst("a")
                        val href = linkElement?.attr("href") ?: ""
                        println("DEBUG: href encontrado: $href")
                        
                        if (href.isBlank()) {
                            println("DEBUG: href vazio, pulando")
                            return@forEachIndexed
                        }

                        var episodeTitle = linkElement.text().trim()
                        if (episodeTitle.isBlank()) {
                            episodeTitle = element.selectFirst(".episodiotitle a")?.text()?.trim() ?: ""
                        }
                        
                        if (episodeTitle.isBlank()) {
                            episodeTitle = element.attr("title")?.trim() ?: ""
                        }
                        
                        if (episodeTitle.isBlank()) {
                            episodeTitle = "Episódio ${index + 1}"
                        }

                        val episodeNumber = extractEpisodeNumberFromMultipleSources(element, href) ?: (index + 1)

                        val fixedHref = fixEpisodeUrl(href)

                        val episode = newEpisode(fixedHref) {
                            this.name = episodeTitle
                            this.episode = episodeNumber
                            this.season = 1
                        }

                        episodes.add(episode)
                        println("DEBUG: ✅ Episódio adicionado (estrutura 2): $episodeTitle (#$episodeNumber)")

                    } catch (e: Exception) {
                        println("DEBUG: ❌ Erro extraindo episódio (estrutura 2): ${e.message}")
                    }
                }
            }
        }

        // TENTATIVA 3: Buscar qualquer link que contenha /episodio/
        if (episodes.isEmpty()) {
            val episodeLinks = document.select("a[href*='/episodio/']")
            println("DEBUG: TENTATIVA 3 - Seletor 'a[href*=/episodio/]': ${episodeLinks.size} links encontrados")
            
            if (episodeLinks.isNotEmpty()) {
                val firstLink = episodeLinks.first()
                println("DEBUG: Exemplo do primeiro link HTML (estrutura 3):")
                println(firstLink?.html() ?: "N/A")
                
                episodeLinks.forEachIndexed { index, element ->
                    try {
                        val href = element.attr("href")
                        if (href.isBlank()) return@forEachIndexed

                        var episodeTitle = element.text().trim()
                        if (episodeTitle.isBlank()) {
                            episodeTitle = element.attr("title")?.trim() ?: "Episódio ${index + 1}"
                        }

                        val episodeNumber = extractEpisodeNumberFromTitle(episodeTitle) ?: 
                                          extractEpisodeNumberFromUrl(href) ?: (index + 1)

                        val fixedHref = fixEpisodeUrl(href)

                        // Verifica se já não foi adicionado
                        if (!episodes.any { it.data == fixedHref }) {
                            val episode = newEpisode(fixedHref) {
                                this.name = episodeTitle
                                this.episode = episodeNumber
                                this.season = 1
                            }

                            episodes.add(episode)
                            println("DEBUG: ✅ Episódio adicionado (links): $episodeTitle (#$episodeNumber)")
                        }

                    } catch (e: Exception) {
                        println("DEBUG: ❌ Erro extraindo episódio (links): ${e.message}")
                    }
                }
            }
        }

        // TENTATIVA 4: Buscar dentro de #serie_contenido (para casos especiais)
        if (episodes.isEmpty()) {
            val serieContenido = document.selectFirst("#serie_contenido")
            println("DEBUG: TENTATIVA 4 - Procurando #serie_contenido: ${serieContenido != null}")
            
            serieContenido?.let { container ->
                val containerLinks = container.select("a[href]")
                println("DEBUG: Links encontrados em #serie_contenido: ${containerLinks.size}")
                
                containerLinks.forEachIndexed { index, element ->
                    try {
                        val href = element.attr("href")
                        if (href.isBlank() || !href.contains("/episodio/")) return@forEachIndexed

                        var episodeTitle = element.text().trim()
                        if (episodeTitle.isBlank()) {
                            episodeTitle = element.parent()?.selectFirst(".episodiotitle")?.text()?.trim() ?: 
                                           element.parent()?.parent()?.selectFirst(".episodiotitle")?.text()?.trim() ?: 
                                           "Episódio ${index + 1}"
                        }

                        val episodeNumber = extractEpisodeNumberFromTitle(episodeTitle) ?: 
                                          extractEpisodeNumberFromUrl(href) ?: (index + 1)

                        val fixedHref = fixEpisodeUrl(href)

                        if (!episodes.any { it.data == fixedHref }) {
                            val episode = newEpisode(fixedHref) {
                                this.name = episodeTitle
                                this.episode = episodeNumber
                                this.season = 1
                            }

                            episodes.add(episode)
                            println("DEBUG: ✅ Episódio adicionado (serie_contenido): $episodeTitle (#$episodeNumber)")
                        }

                    } catch (e: Exception) {
                        println("DEBUG: ❌ Erro extraindo episódio (serie_contenido): ${e.message}")
                    }
                }
            }
        }

        // TENTATIVA 5: Procurar por qualquer elemento com classe relacionada a episódios
        if (episodes.isEmpty()) {
            println("DEBUG: TENTATIVA 5 - Procurando por todas as classes possíveis")
            
            val allPossibleSelectors = listOf(
                ".episode-item",
                ".episode",
                ".ep",
                ".epi",
                ".listaepisodios",
                ".lista-episodios",
                ".list-episodes",
                ".list_episodes",
                ".all_episodes",
                ".episodes-list",
                ".episodes_list",
                ".episodelist",
                ".episode-list",
                ".season-a",
                ".season",
                ".temporada",
                ".seasons",
                ".seasons-a",
                ".seasons-b",
                "ul li a[href*='episodio']",
                "ul li a[href*='episode']",
                "div[class*='episodio']",
                "div[class*='episode']",
                "li[class*='episodio']",
                "li[class*='episode']"
            )
            
            for (selector in allPossibleSelectors) {
                val elements = document.select(selector)
                if (elements.isNotEmpty()) {
                    println("DEBUG: Seletor '$selector' encontrou ${elements.size} elementos")
                    val firstElement = elements.first()
                    println("DEBUG: Exemplo HTML do seletor '$selector':")
                    println(firstElement?.html()?.take(300) ?: "N/A")
                }
            }
        }

        // TENTATIVA 6: Procurar por scripts que contenham dados de episódios
        if (episodes.isEmpty()) {
            println("DEBUG: TENTATIVA 6 - Procurando scripts com dados de episódios")
            
            val scripts = document.select("script")
            var foundEpisodesInScript = false
            
            scripts.forEachIndexed { index, script ->
                val scriptContent = script.html()
                if (scriptContent.contains("episodio", ignoreCase = true) || 
                    scriptContent.contains("episode", ignoreCase = true) ||
                    scriptContent.contains("episodes", ignoreCase = true)) {
                    
                    println("DEBUG: Script $index pode conter dados de episódios")
                    println("DEBUG: Conteúdo do script (primeiros 500 chars):")
                    println(scriptContent.take(500))
                    foundEpisodesInScript = true
                }
            }
            
            if (!foundEpisodesInScript) {
                println("DEBUG: Nenhum script encontrado com dados de episódios")
            }
        }

        println("DEBUG: Total de episódios extraídos: ${episodes.size}")
        
        if (episodes.isEmpty()) {
            println("DEBUG: ❌ Nenhum episódio encontrado! Verifique se a página realmente tem episódios.")
            println("DEBUG: Pode ser que a página use JavaScript para carregar os episódios, o que requer WebView.")
        } else {
            println("DEBUG: ✅ Episódios encontrados:")
            episodes.forEachIndexed { i, episode ->
                println("DEBUG:   $i. ${episode.name} (Ep ${episode.episode}) -> ${episode.data}")
            }
        }
        
        return episodes.sortedBy { it.episode }
    }

    // Função auxiliar para extrair número do episódio de múltiplas fontes
    private fun extractEpisodeNumberFromMultipleSources(element: Element, href: String): Int? {
        // Tenta do elemento .numerando
        val numberElement = element.selectFirst(".numerando, .epnumber, .numerando")
        numberElement?.text()?.let { text ->
            // Padrão: "1 - 1" ou "1-1"
            val match = Regex("""(\d+)\s*[-–]\s*\d+""").find(text)
            if (match != null) {
                return match.groupValues[1].toIntOrNull()
            }
            // Tenta apenas números
            val simpleMatch = Regex("""\b(\d+)\b""").find(text)
            if (simpleMatch != null) {
                return simpleMatch.groupValues[1].toIntOrNull()
            }
        }

        // Tenta do atributo data-id (se existir)
        val dataId = element.attr("data-id")
        if (dataId.isNotBlank()) {
            val match = Regex("""\b(\d+)\b""").find(dataId)
            if (match != null) {
                return match.groupValues[1].toIntOrNull()
            }
        }

        // Tenta da URL
        return extractEpisodeNumberFromUrl(href)
    }

    // Função auxiliar para extrair número da URL
    private fun extractEpisodeNumberFromUrl(url: String): Int? {
        val patterns = listOf(
            Regex("""episodio-(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""/episodio/(\d+)/?""", RegexOption.IGNORE_CASE),
            Regex("""episode-(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""e(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""\bep\.?(\d+)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(\d{1,3})\b(?!.*\d)""") // Últimos 1-3 dígitos
        )
        
        for (pattern in patterns) {
            pattern.find(url)?.groupValues?.get(1)?.toIntOrNull()?.let {
                return it
            }
        }
        return null
    }

    // Função auxiliar para extrair número do título
    private fun extractEpisodeNumberFromTitle(title: String): Int? {
        val patterns = listOf(
            Regex("""Epis[oó]dio\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""Ep\.?\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""E(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""\b(\d{1,3})\b(?!.*\d)""") // Últimos 1-3 dígitos
        )
        
        for (pattern in patterns) {
            pattern.find(title)?.groupValues?.get(1)?.toIntOrNull()?.let {
                return it
            }
        }
        return null
    }

    // Função auxiliar para corrigir URLs
    private fun fixEpisodeUrl(href: String): String {
        return when {
            href.startsWith("//") -> "https:$href"
            href.startsWith("/") -> "$mainUrl$href"
            !href.startsWith("http") -> "$mainUrl/$href"
            else -> href
        }
    }

    // LoadLinks desativado
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return false
    }
}
