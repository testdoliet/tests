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
            println("DEBUG POSTER: Iniciando extractPoster")
            
            // Método 1: Procura imagem dentro de .poster
            val posterDiv = element.selectFirst(".poster")
            println("DEBUG POSTER: posterDiv encontrado? ${posterDiv != null}")
            
            if (posterDiv != null) {
                val img = posterDiv.selectFirst("img")
                println("DEBUG POSTER: img encontrado? ${img != null}")
                
                img?.let {
                    // PRIMEIRO tenta data-src (lazy loading)
                    var src = it.attr("data-src")
                    println("DEBUG POSTER: data-src: '$src'")
                    
                    // Se data-src estiver vazio, tenta src normal
                    if (src.isBlank()) {
                        src = it.attr("src")
                        println("DEBUG POSTER: src: '$src'")
                    }
                    
                    // Se for data URI (base64), ignora
                    if (src.startsWith("data:image")) {
                        println("DEBUG POSTER: Ignorando data URI (placeholder)")
                        return null
                    }
                    
                    if (src.isNotBlank() && !src.startsWith("data:")) {
                        println("DEBUG POSTER: Retornando src: $src")
                        return src
                    }
                }
            }
            
            // Método 2: Procura qualquer imagem no elemento
            val img = element.selectFirst("img")
            println("DEBUG POSTER: img geral encontrado? ${img != null}")
            
            img?.let {
                // PRIMEIRO tenta data-src
                var src = it.attr("data-src")
                println("DEBUG POSTER: data-src (geral): '$src'")
                if (src.isBlank()) {
                    src = it.attr("src")
                    println("DEBUG POSTER: src (geral): '$src'")
                }
                
                // Ignora data URIs
                if (src.isNotBlank() && !src.startsWith("data:")) {
                    println("DEBUG POSTER: Retornando src (geral): $src")
                    return src
                }
            }
            
            println("DEBUG POSTER: Nenhuma imagem válida encontrada")
            null
        } catch (e: Exception) {
            println("DEBUG POSTER: Erro: ${e.message}")
            null
        }
    }

    // Função específica para extrair episódios
    private fun Element.toEpisodeItem(): AnimeSearchResponse? {
        return try {
            println("DEBUG EPISODE ITEM: Iniciando toEpisodeItem")
            
            // Encontra o link principal
            val linkElement = selectFirst("a[href]")
            println("DEBUG EPISODE ITEM: linkElement encontrado? ${linkElement != null}")
            if (linkElement == null) return null
            
            val href = linkElement.attr("href")
            println("DEBUG EPISODE ITEM: href: '$href'")
            if (href.isBlank()) return null

            // Verifica se é um episódio (contém /episodio/ na URL)
            if (!href.contains("/episodio/")) {
                println("DEBUG EPISODE ITEM: Não contém /episodio/ na URL")
                return null
            }

            // Extrai o nome da série do elemento <span class="serie">
            val serieElement = selectFirst(".serie")
            println("DEBUG EPISODE ITEM: serieElement encontrado? ${serieElement != null}")
            val serieName = serieElement?.text()?.trim() ?: return null
            println("DEBUG EPISODE ITEM: serieName: '$serieName'")

            // Extrai o número/título do episódio do elemento <h3>
            val episodeElement = selectFirst("h3")
            println("DEBUG EPISODE ITEM: episodeElement encontrado? ${episodeElement != null}")
            val episodeTitle = episodeElement?.text()?.trim() ?: "Episódio"
            println("DEBUG EPISODE ITEM: episodeTitle: '$episodeTitle'")

            // Extrai número do episódio do título
            val episodeNumber = extractEpisodeNumber(episodeTitle) ?: 1
            println("DEBUG EPISODE ITEM: episodeNumber: $episodeNumber")

            // Determina se é DUB ou LEG pela badge
            val hasDubBadge = selectFirst(".buttonextra.dub-ep") != null
            val hasLegBadge = selectFirst(".buttonextra.leg-ep") != null
            println("DEBUG EPISODE ITEM: hasDubBadge: $hasDubBadge, hasLegBadge: $hasLegBadge")
            
            val audioType = when {
                hasDubBadge -> "DUB"
                hasLegBadge -> "LEG"
                else -> "LEG" // padrão
            }
            println("DEBUG EPISODE ITEM: audioType: $audioType")

            // Extrai qualidade (se disponível)
            val qualityElement = selectFirst(".quality")
            val quality = qualityElement?.text()?.trim() ?: ""
            println("DEBUG EPISODE ITEM: quality: '$quality'")

            // Extrai poster
            val posterUrl = extractPoster(this)
            println("DEBUG EPISODE ITEM: posterUrl: '$posterUrl'")

            // Para episódios, usamos um título mais descritivo
            val displayTitle = "$serieName - Ep $episodeNumber"
            println("DEBUG EPISODE ITEM: displayTitle: '$displayTitle'")

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
            println("DEBUG EPISODE ITEM: Erro: ${e.message}")
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

            println("DEBUG MAINPAGE: Carregando página: $pageUrl")
            val document = app.get(pageUrl, timeout = 20).document
            
            // Seletores para TODOS os tipos de itens
            val elements = document.select("article.item, article.se.episodes, .items article, article.tvshows, article.movies")
            println("DEBUG MAINPAGE: Elementos encontrados: ${elements.size}")
            
            val homeItems = mutableListOf<SearchResponse>()

            elements.forEachIndexed { index, element ->
                try {
                    println("DEBUG MAINPAGE: Processando elemento $index")
                    val item = element.toSearchResponse()
                    if (item != null) {
                        homeItems.add(item)
                    }
                } catch (e: Exception) {
                    println("DEBUG MAINPAGE: Erro processando elemento $index: ${e.message}")
                }
            }

            val hasNextPage = detectHasNextPage(document, sitePageNumber)
            println("DEBUG MAINPAGE: Itens encontrados: ${homeItems.size}, Tem próxima página? $hasNextPage")

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
            println("DEBUG MAINPAGE: Erro geral: ${e.message}")
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

    override suspend fun load(url: String): LoadResponse {
        return try {
            println("DEBUG LOAD: Carregando URL: $url")
            val document = app.get(url, timeout = 30).document

            val title = document.selectFirst("h1")?.text()?.trim() 
                ?: document.selectFirst(".data h1")?.text()?.trim() 
                ?: document.selectFirst(".sheader h1")?.text()?.trim()
                ?: "Sem Título"
            println("DEBUG LOAD: Título encontrado: '$title'")

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
            println("DEBUG LOAD: Poster encontrado: '$poster'")

            val background = document.selectFirst("meta[property='og:image']")?.attr("content")
                ?.takeIf { it.isNotBlank() }
                ?.let { fixUrl(it) }
            println("DEBUG LOAD: Background encontrado: '$background'")

            val synopsis = document.selectFirst(".wp-content p")?.text()?.trim()
                ?: document.selectFirst(".sbox .wp-content")?.text()?.trim()
                ?: document.selectFirst(".synopsis")?.text()?.trim()
                ?: "Sinopse não disponível."
            println("DEBUG LOAD: Sinopse encontrada (primeiros 100 chars): '${synopsis.take(100)}...'")

            val yearText = document.selectFirst(".extra .date")?.text()?.trim()
                ?: document.selectFirst(".custom_fields:contains(Ano) span.valor")?.text()?.trim()
            val year = extractYear(yearText)
            println("DEBUG LOAD: Ano encontrado: $year")

            val statusElement = document.selectFirst(".custom_fields:contains(Status) span.valor")
            val statusText = statusElement?.text()?.trim() ?: "Desconhecido"
            val showStatus = getStatus(statusText)
            println("DEBUG LOAD: Status encontrado: $statusText -> $showStatus")

            val genres = extractGenres(document)
            println("DEBUG LOAD: Gêneros encontrados: $genres")

            val (hasDub, hasSub) = extractAudioType(document)
            println("DEBUG LOAD: Audio - Dub: $hasDub, Sub: $hasSub")

            val isMovie = url.contains("/filmes/") || 
                         title.contains("filme", ignoreCase = true) ||
                         document.selectFirst(".custom_fields:contains(Tipo)")?.text()?.contains("Filme", ignoreCase = true) == true
            println("DEBUG LOAD: É filme? $isMovie")

            val episodes = extractEpisodesFromDocument(document, url)
            println("DEBUG LOAD: Episódios extraídos: ${episodes.size}")

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
                    println("DEBUG LOAD: Adicionando ${episodes.size} episódios")
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
                } else {
                    println("DEBUG LOAD: NENHUM EPISÓDIO ENCONTRADO!")
                }
            }

            response

        } catch (e: Exception) {
            println("DEBUG LOAD: Erro ao carregar: ${e.message}")
            newAnimeLoadResponse("Erro ao carregar", url, TvType.Anime) {
                this.plot = "Não foi possível carregar esta página: ${e.message}"
            }
        }
    }

    // FUNÇÃO MELHORADA PARA EXTRAIR EPISÓDIOS COM LOGS DETALHADOS
    private fun extractEpisodesFromDocument(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        println("DEBUG EXTRACT EPISODES: Iniciando extração de episódios")

        // TENTATIVA 1: Estrutura principal com .episodios > li
        val episodeElements1 = document.select(".episodios li")
        println("DEBUG EXTRACT EPISODES: Tentativa 1 - .episodios li encontrados: ${episodeElements1.size}")
        
        if (episodeElements1.isNotEmpty()) {
            println("DEBUG EXTRACT EPISODES: HTML da primeira estrutura .episodios li (primeiros 500 chars):")
            println(episodeElements1.first()?.html()?.take(500) ?: "VAZIO")
            
            episodeElements1.forEachIndexed { index, element ->
                try {
                    println("DEBUG EXTRACT EPISODES: Processando elemento $index da tentativa 1")
                    
                    val linkElement = element.selectFirst("a")
                    println("DEBUG EXTRACT EPISODES: linkElement encontrado? ${linkElement != null}")
                    val href = linkElement?.attr("href") ?: {
                        println("DEBUG EXTRACT EPISODES: Sem href, pulando")
                        return@forEachIndexed
                    }()
                    
                    println("DEBUG EXTRACT EPISODES: href: '$href'")

                    var episodeTitle = linkElement.text().trim()
                    if (episodeTitle.isBlank()) {
                        episodeTitle = element.selectFirst(".episodiotitle a")?.text()?.trim() ?: ""
                    }
                    
                    if (episodeTitle.isBlank()) {
                        episodeTitle = "Episódio ${index + 1}"
                    }
                    println("DEBUG EXTRACT EPISODES: episodeTitle: '$episodeTitle'")

                    // Extrai número do episódio de várias fontes possíveis
                    val episodeNumber = extractEpisodeNumberFromMultipleSources(element, href) ?: (index + 1)
                    println("DEBUG EXTRACT EPISODES: episodeNumber: $episodeNumber")

                    val fixedHref = fixEpisodeUrl(href)
                    println("DEBUG EXTRACT EPISODES: fixedHref: '$fixedHref'")

                    val episode = newEpisode(fixedHref) {
                        this.name = episodeTitle
                        this.episode = episodeNumber
                        this.season = 1
                    }

                    episodes.add(episode)
                    println("DEBUG EXTRACT EPISODES: Episódio $index adicionado: $episodeTitle (#$episodeNumber)")

                } catch (e: Exception) {
                    println("DEBUG EXTRACT EPISODES: Erro extraindo episódio (estrutura 1): ${e.message}")
                }
            }
        }

        // TENTATIVA 2: Estrutura alternativa com .se-a .episodios li
        if (episodes.isEmpty()) {
            val episodeElements2 = document.select(".se-a ul.episodios li")
            println("DEBUG EXTRACT EPISODES: Tentativa 2 - .se-a .episodios li encontrados: ${episodeElements2.size}")
            
            if (episodeElements2.isNotEmpty()) {
                episodeElements2.forEachIndexed { index, element ->
                    try {
                        println("DEBUG EXTRACT EPISODES: Processando elemento $index da tentativa 2")
                        
                        val linkElement = element.selectFirst("a")
                        val href = linkElement?.attr("href") ?: return@forEachIndexed
                        
                        if (href.isBlank()) return@forEachIndexed

                        var episodeTitle = linkElement.text().trim()
                        if (episodeTitle.isBlank()) {
                            episodeTitle = element.selectFirst(".episodiotitle a")?.text()?.trim() ?: ""
                        }
                        
                        if (episodeTitle.isBlank()) {
                            episodeTitle = "Episódio ${index + 1}"
                        }
                        println("DEBUG EXTRACT EPISODES: episodeTitle (tentativa 2): '$episodeTitle'")

                        val episodeNumber = extractEpisodeNumberFromMultipleSources(element, href) ?: (index + 1)
                        println("DEBUG EXTRACT EPISODES: episodeNumber (tentativa 2): $episodeNumber")

                        val fixedHref = fixEpisodeUrl(href)
                        println("DEBUG EXTRACT EPISODES: fixedHref (tentativa 2): '$fixedHref'")

                        val episode = newEpisode(fixedHref) {
                            this.name = episodeTitle
                            this.episode = episodeNumber
                            this.season = 1
                        }

                        episodes.add(episode)
                        println("DEBUG EXTRACT EPISODES: Episódio $index adicionado (tentativa 2): $episodeTitle (#$episodeNumber)")

                    } catch (e: Exception) {
                        println("DEBUG EXTRACT EPISODES: Erro extraindo episódio (estrutura 2): ${e.message}")
                    }
                }
            }
        }

        // TENTATIVA 3: Buscar dentro de #serie_contenido
        if (episodes.isEmpty()) {
            val serieContenido = document.selectFirst("#serie_contenido")
            println("DEBUG EXTRACT EPISODES: Tentativa 3 - #serie_contenido encontrado? ${serieContenido != null}")
            
            serieContenido?.let { container ->
                val containerEpisodes = container.select("li, .episodios li, .episode-item")
                println("DEBUG EXTRACT EPISODES: Elementos dentro de #serie_contenido: ${containerEpisodes.size}")
                
                if (containerEpisodes.isEmpty()) {
                    println("DEBUG EXTRACT EPISODES: HTML de #serie_contenido (primeiros 1000 chars):")
                    println(container.html()?.take(1000) ?: "VAZIO")
                }
                
                containerEpisodes.forEachIndexed { index, element ->
                    try {
                        println("DEBUG EXTRACT EPISODES: Processando elemento $index da tentativa 3")
                        
                        val linkElement = element.selectFirst("a[href*='/episodio/']")
                        if (linkElement == null) {
                            println("DEBUG EXTRACT EPISODES: Nenhum link com /episodio/ encontrado no elemento")
                            return@forEachIndexed
                        }
                        
                        val href = linkElement.attr("href")
                        if (href.isBlank()) {
                            println("DEBUG EXTRACT EPISODES: href vazio")
                            return@forEachIndexed
                        }
                        println("DEBUG EXTRACT EPISODES: href (tentativa 3): '$href'")

                        var episodeTitle = linkElement.text().trim()
                        if (episodeTitle.isBlank()) {
                            episodeTitle = element.selectFirst(".episodiotitle")?.text()?.trim() ?: 
                                         element.selectFirst(".title")?.text()?.trim() ?: 
                                         "Episódio ${index + 1}"
                        }
                        println("DEBUG EXTRACT EPISODES: episodeTitle (tentativa 3): '$episodeTitle'")

                        val episodeNumber = extractEpisodeNumberFromTitle(episodeTitle) ?: 
                                          extractEpisodeNumberFromUrl(href) ?: (index + 1)
                        println("DEBUG EXTRACT EPISODES: episodeNumber (tentativa 3): $episodeNumber")

                        val fixedHref = fixEpisodeUrl(href)
                        println("DEBUG EXTRACT EPISODES: fixedHref (tentativa 3): '$fixedHref'")

                        // Verifica se já não foi adicionado
                        if (!episodes.any { it.data == fixedHref }) {
                            val episode = newEpisode(fixedHref) {
                                this.name = episodeTitle
                                this.episode = episodeNumber
                                this.season = 1
                            }

                            episodes.add(episode)
                            println("DEBUG EXTRACT EPISODES: Episódio $index adicionado (tentativa 3): $episodeTitle (#$episodeNumber)")
                        }

                    } catch (e: Exception) {
                        println("DEBUG EXTRACT EPISODES: Erro extraindo episódio (tentativa 3): ${e.message}")
                    }
                }
            }
        }

        // TENTATIVA 4: Buscar qualquer link com /episodio/
        if (episodes.isEmpty()) {
            val allEpisodeLinks = document.select("a[href*='/episodio/']")
            println("DEBUG EXTRACT EPISODES: Tentativa 4 - Links com /episodio/ encontrados: ${allEpisodeLinks.size}")
            
            if (allEpisodeLinks.isNotEmpty()) {
                allEpisodeLinks.forEachIndexed { index, element ->
                    try {
                        if (index >= 20) return@forEachIndexed // Limita para não ficar muito pesado
                        
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
                            println("DEBUG EXTRACT EPISODES: Episódio $index adicionado (tentativa 4): $episodeTitle (#$episodeNumber)")
                        }

                    } catch (e: Exception) {
                        println("DEBUG EXTRACT EPISODES: Erro extraindo episódio (tentativa 4): ${e.message}")
                    }
                }
            }
        }

        println("DEBUG EXTRACT EPISODES: Total de episódios extraídos: ${episodes.size}")
        
        if (episodes.isEmpty()) {
            println("DEBUG EXTRACT EPISODES: NENHUM EPISÓDIO ENCONTRADO! HTML da página (primeiros 2000 chars):")
            println(document.html()?.take(2000) ?: "VAZIO")
        }
        
        return episodes.sortedBy { it.episode }
    }

    // Função auxiliar para extrair número do episódio de múltiplas fontes
    private fun extractEpisodeNumberFromMultipleSources(element: Element, href: String): Int? {
        println("DEBUG EXTRACT NUMBER: Extraindo número do episódio")
        
        // Tenta do elemento .numerando
        val numberElement = element.selectFirst(".numerando, .epnumber, .numerando")
        println("DEBUG EXTRACT NUMBER: numberElement encontrado? ${numberElement != null}")
        numberElement?.text()?.let { text ->
            println("DEBUG EXTRACT NUMBER: Texto do numberElement: '$text'")
            // Padrão: "1 - 1" ou "1-1"
            val match = Regex("""(\d+)\s*[-–]\s*\d+""").find(text)
            if (match != null) {
                val result = match.groupValues[1].toIntOrNull()
                println("DEBUG EXTRACT NUMBER: Número encontrado no padrão 1-1: $result")
                return result
            }
            // Tenta apenas números
            val simpleMatch = Regex("""\b(\d+)\b""").find(text)
            if (simpleMatch != null) {
                val result = simpleMatch.groupValues[1].toIntOrNull()
                println("DEBUG EXTRACT NUMBER: Número encontrado no padrão simples: $result")
                return result
            }
        }

        // Tenta do atributo data-id (se existir)
        val dataId = element.attr("data-id")
        println("DEBUG EXTRACT NUMBER: data-id: '$dataId'")
        if (dataId.isNotBlank()) {
            val match = Regex("""\b(\d+)\b""").find(dataId)
            if (match != null) {
                val result = match.groupValues[1].
