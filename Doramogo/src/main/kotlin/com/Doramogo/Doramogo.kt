package com.Doramogo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.SubtitleFile
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.lagradost.cloudstream3.utils.newExtractorLink

@CloudstreamPlugin
class DoramogoPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Doramogo())
    }
}

class Doramogo : MainAPI() {
    override var mainUrl = "https://www.doramogo.net"
    override var name = "Doramogo"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override val usesWebView = false

    override val mainPage = mainPageOf(
        "$mainUrl/episodios" to "Episódios Recentes",
        "$mainUrl/dorama?slug=&status=&ano=&classificacao_idade=&idiomar=DUB" to "Doramas Dublados",
        "$mainUrl/dorama?slug=&status=&ano=&classificacao_idade=&idiomar=LEG" to "Doramas Legendados",
        "$mainUrl/genero/dorama-acao" to "Ação",
        "$mainUrl/genero/dorama-aventura" to "Aventura",
        "$mainUrl/genero/dorama-comedia" to "Comédia",
        "$mainUrl/genero/dorama-crime" to "Crime",
        "$mainUrl/genero/dorama-drama" to "Drama",
        "$mainUrl/genero/dorama-familia" to "Família",
        "$mainUrl/genero/dorama-fantasia" to "Fantasia",
        "$mainUrl/genero/dorama-ficcao-cientifica" to "Ficção Científica",
        "$mainUrl/genero/dorama-misterio" to "Mistério",
        "$mainUrl/genero/dorama-reality" to "Reality Shows",
        "$mainUrl/genero/dorama-sci-fi" to "Sci-Fi",
        "$mainUrl/filmes" to "Filmes"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) {
            when {
                request.data.contains("/dorama?") || request.data.contains("/filmes") -> 
                    "${request.data}&pagina=$page"
                request.data.contains("/genero/") -> 
                    "${request.data}/pagina/$page"
                request.data.contains("/episodios") -> 
                    "${request.data}?pagina=$page"
                else -> "${request.data}?page=$page"
            }
        } else {
            request.data
        }

        val document = app.get(url).document
        val items = ArrayList<SearchResponse>()

        // Verificar se é a aba de episódios recentes
        val isEpisodesPage = request.data.contains("/episodios") || request.name.contains("Episódios")

        if (isEpisodesPage) {
            // Para página de episódios recentes - layout HORIZONTAL
            document.select(".episode-card").forEach { card ->
                val aTag = card.selectFirst("a") ?: return@forEach
                val titleElement = card.selectFirst("h3")
                val episodeTitle = titleElement?.text()?.trim() ?: return@forEach
                
                // Extrair nome do dorama removendo parênteses
                val cleanTitle = episodeTitle.replace(Regex("\\s*\\(.*\\)"), "").trim()
                
                // Extrair nome base sem "Episódio X"
                val doramaName = cleanTitle.replace(Regex("\\s*-\\s*Episódio\\s*\\d+.*$"), "").trim()
                val href = aTag.attr("href")
                
                val imgElement = card.selectFirst("img")
                val posterUrl = when {
                    imgElement?.hasAttr("src") == true -> fixUrl(imgElement.attr("src"))
                    imgElement?.hasAttr("data-src") == true -> fixUrl(imgElement.attr("data-src"))
                    else -> null
                }
                
                // Extrair número do episódio
                val episodeMatch = Regex("Episódio\\s*(\\d+)", RegexOption.IGNORE_CASE).find(cleanTitle)
                val episodeNumber = episodeMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                
                // Determinar se é DUB ou LEG
                val isDub = href.contains("/dub/") || request.data.contains("idiomar=DUB") || 
                           episodeTitle.contains("Dublado", ignoreCase = true)
                val isLeg = href.contains("/leg/") || request.data.contains("idiomar=LEG") || 
                           episodeTitle.contains("Legendado", ignoreCase = true)
                
                // Formatar título final: Dorama - EP X LEG/DUB
                val audioType = when {
                    isDub -> "DUB"
                    isLeg -> "LEG"
                    else -> ""
                }
                val finalTitle = if (audioType.isNotEmpty()) {
                    "$doramaName - EP $episodeNumber $audioType"
                } else {
                    "$doramaName - EP $episodeNumber"
                }
                
                items.add(newTvSeriesSearchResponse(finalTitle, fixUrl(href), TvType.TvSeries) {
                    this.posterUrl = posterUrl
                })
            }
        } else {
            // Para outras páginas (doramas, filmes, gêneros) - layout VERTICAL
            document.select(".episode-card").forEach { card ->
                val aTag = card.selectFirst("a") ?: return@forEach
                val titleElement = card.selectFirst("h3")
                var title = titleElement?.text()?.trim() 
                    ?: aTag.attr("title")?.trim()
                    ?: return@forEach
                
                // REMOVER (Legendado) e (Dublado) dos títulos
                title = cleanTitle(title)
                
                val href = aTag.attr("href")
                
                val imgElement = card.selectFirst("img")
                val posterUrl = when {
                    imgElement?.hasAttr("data-src") == true -> fixUrl(imgElement.attr("data-src"))
                    imgElement?.hasAttr("src") == true -> fixUrl(imgElement.attr("src"))
                    else -> null
                }
                
                val isMovie = href.contains("/filmes/") || request.name.contains("Filmes")
                val type = if (isMovie) TvType.Movie else TvType.TvSeries
                
                if (type == TvType.Movie) {
                    items.add(newMovieSearchResponse(title, fixUrl(href), type) {
                        this.posterUrl = posterUrl
                    })
                } else {
                    items.add(newTvSeriesSearchResponse(title, fixUrl(href), type) { 
                        this.posterUrl = posterUrl
                    })
                }
            }
        }

        val hasNextPage = document.select("""a[href*="pagina/"], a[href*="?page="], 
            .pagination a, .next-btn, a:contains(PRÓXIMA)""").isNotEmpty()
        
        // Criar HomePageList com configuração de layout
        val homePageList = HomePageList(
            request.name,
            items.distinctBy { it.url },
            // Episódios recentes são HORIZONTAIS (true), outros são VERTICAIS (false)
            isHorizontalImages = isEpisodesPage
        )
        
        return newHomePageResponse(listOf(homePageList), hasNextPage)
    }

    // --- Search ---
override suspend fun search(query: String): List<SearchResponse> {
    println("=== DORAMOGO DEBUG: Iniciando pesquisa por '$query' ===")
    
    val allResults = mutableListOf<SearchResponse>()
    var currentPage = 1
    var hasMorePages = true
    val maxPages = 7 // Limite de páginas para evitar muitas requisições
    
    try {
        while (hasMorePages && currentPage <= maxPages) {
            println("DEBUG: Buscando página $currentPage de resultados...")
            
            val pageResults = searchPage(query, currentPage)
            
            if (pageResults.isNotEmpty()) {
                allResults.addAll(pageResults)
                println("DEBUG: Encontrados ${pageResults.size} resultados na página $currentPage")
                
                // Verificar se há mais páginas disponíveis
                hasMorePages = checkIfHasNextPage(query, currentPage)
                
                // Incrementar para próxima página
                currentPage++
            } else {
                // Se não encontrou resultados nesta página, para a busca
                println("DEBUG: Nenhum resultado na página $currentPage, parando busca.")
                hasMorePages = false
            }
        }
    } catch (e: Exception) {
        println("ERRO durante pesquisa paginada: ${e.message}")
    }
    
    println("=== DORAMOGO DEBUG: Pesquisa concluída ===")
    println("Total de resultados encontrados: ${allResults.size}")
    
    // Remover duplicados por URL
    return allResults.distinctBy { it.url }
}

// Função para buscar uma página específica de resultados
private suspend fun searchPage(query: String, page: Int): List<SearchResponse> {
    val searchUrl = buildSearchUrl(query, page)
    
    println("DEBUG: URL da página $page: $searchUrl")
    
    val document = try {
        app.get(searchUrl).document
    } catch (e: Exception) {
        println("ERRO ao carregar página $page: ${e.message}")
        return emptyList()
    }
    
    // Extrair o total de resultados (opcional, para debug)
    val totalText = document.selectFirst(".doramogo-search-page-header p")?.text() ?: ""
    if (totalText.isNotEmpty()) {
        println("DEBUG: Info da página: $totalText")
    }
    
    // Os resultados estão em .doramogo-search-result-card
    val results = document.select(".doramogo-search-result-card").mapNotNull { card ->
        try {
            processSearchResultCard(card)
        } catch (e: Exception) {
            println("ERRO ao processar card: ${e.message}")
            null
        }
    }
    
    return results
}

// Função para construir a URL de busca baseada na página
private fun buildSearchUrl(query: String, page: Int): String {
    val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
    
    return if (page == 1) {
        // Primeira página: /search/?q=QUERY
        "$mainUrl/search/?q=$encodedQuery"
    } else {
        // Páginas subsequentes: /search/QUERY/pagina/NUMERO
        // Exemplo: /search/demon/pagina/2
        "$mainUrl/search/$encodedQuery/pagina/$page"
    }
}

// Função para processar um card de resultado individual
private fun processSearchResultCard(card: Element): SearchResponse? {
    // Link principal
    val linkElement = card.selectFirst("a[href^='/series/'], a[href^='/filmes/']") 
        ?: card.selectFirst(".doramogo-search-result-image-container a")
        ?: return null
    
    val href = linkElement.attr("href")
    if (href.isBlank() || href == "#") return null
    
    // Título do dorama/filme
    val titleElement = card.selectFirst("#doramogo-search-result-title a") 
        ?: card.selectFirst("h3 a") 
        ?: linkElement
    
    var title = titleElement.text().trim()
    if (title.isBlank()) {
        // Tentar do atributo title da imagem
        val imgElement = card.selectFirst("img")
        title = imgElement?.attr("title")?.trim() 
            ?: imgElement?.attr("alt")?.trim() 
            ?: return null
    }
    
    // Limpar o título
    title = cleanTitle(title)
    
    // Imagem/poster
    val imgElement = card.selectFirst("img")
    val posterUrl = when {
        imgElement?.hasAttr("data-src") == true -> fixUrl(imgElement.attr("data-src"))
        imgElement?.hasAttr("src") == true -> fixUrl(imgElement.attr("src"))
        else -> null
    }
    
    // Determinar o tipo (série ou filme)
    val type = when {
        href.contains("/filmes/") -> TvType.Movie
        else -> TvType.TvSeries
    }
    
    val year = extractYearFromUrl(href)
    
    if (type == TvType.Movie) {
        return newMovieSearchResponse(title, fixUrl(href), type) { 
            this.posterUrl = posterUrl
            this.year = year
        }
    } else {
        return newTvSeriesSearchResponse(title, fixUrl(href), type) { 
            this.posterUrl = posterUrl
            this.year = year
        }
    }
}

// Função para verificar se há mais páginas disponíveis
private suspend fun checkIfHasNextPage(query: String, currentPage: Int): Boolean {
    return try {
        // Primeiro tentar detectar elementos de paginação na página atual
        val currentPageUrl = buildSearchUrl(query, currentPage)
        val document = app.get(currentPageUrl).document
        
        // Verificar por elementos comuns de paginação
        val hasPaginationElements = document.select(""".pagination a, 
            a[href*="/pagina/"], 
            a:contains(Próxima), 
            a:contains(Next), 
            .next-btn, 
            .next-page""").isNotEmpty()
        
        // Verificar se há pelo menos um link para a próxima página
        val nextPageLink = document.select("""a[href*="/pagina/${currentPage + 1}"]""").first()
        
        if (hasPaginationElements || nextPageLink != null) {
            println("DEBUG: Página $currentPage tem elementos de paginação")
            true
        } else {
            // Tentar carregar a próxima página para ver se existe
            val nextPageUrl = buildSearchUrl(query, currentPage + 1)
            val testResponse = app.get(nextPageUrl, allowRedirects = false)
            
            if (testResponse.code == 200) {
                // Verificar se a próxima página tem resultados
                val nextDoc = app.get(nextPageUrl).document
                val nextPageResults = nextDoc.select(".doramogo-search-result-card").size
                
                if (nextPageResults > 0) {
                    println("DEBUG: Página ${currentPage + 1} existe com $nextPageResults resultados")
                    true
                } else {
                    println("DEBUG: Página ${currentPage + 1} existe mas não tem resultados")
                    false
                }
            } else {
                println("DEBUG: Página ${currentPage + 1} não encontrada (código ${testResponse.code})")
                false
            }
        }
    } catch (e: Exception) {
        println("DEBUG: Erro ao verificar próxima página: ${e.message}")
        false
    }
}

// Função auxiliar para limpar títulos
private fun cleanTitle(title: String): String {
    return title.replace(Regex("\\s*\\(Legendado\\)", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s*\\(Dublado\\)", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s*-\\s*(Dublado|Legendado|Online|e|Dublado e Legendado).*"), "")
        .replace(Regex("\\s*\\(.*\\)"), "")
        .replace(Regex("\\(\\d{4}\\)"), "") // Remover (2024)
        .trim()
}

// Função auxiliar para extrair ano da URL
private fun extractYearFromUrl(url: String): Int? {
    val pattern = Regex("""/(?:series|filmes)/[^/]+-(\d{4})/""")
    val match = pattern.find(url)
    return match?.groupValues?.get(1)?.toIntOrNull()
}
    // --- Load ---
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        // Título
        val fullTitle = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")
            ?: return null
        
        // Limpar título
        var title = cleanTitle(fullTitle)
        
        // Descrição
        val description = document.selectFirst("#sinopse-text")?.text()?.trim()
            ?: document.selectFirst("#synopsis p")?.text()?.trim()
            ?: document.selectFirst(".synopsis-text")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:description']")?.attr("content")?.trim()
            ?: ""
        
        // Poster
        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }
            ?: document.selectFirst("#w-55")?.attr("src")?.let { fixUrl(it) }
        
        // Extrair informações detalhadas
        val infoMap = extractInfoMap(document)
        
        // Ano - pegar do infoMap ou extrair da URL
        val year = infoMap["ano"]?.toIntOrNull()
            ?: extractYearFromUrl(url)
            ?: title.findYear()
        
        // Gêneros principais
        val mainTags = document.select(".gens a").map { it.text().trim() }
        
        // Tags adicionais: áudio e status
        val additionalTags = mutableListOf<String>()
        
        // Adicionar áudio como tag
        infoMap["áudio"]?.let { audio ->
            when {
                audio.contains("Dublado", ignoreCase = true) -> additionalTags.add("Dublado")
                audio.contains("Legendado", ignoreCase = true) -> additionalTags.add("Legendado")
                else -> additionalTags.add(audio)
            }
        }
        
        // Adicionar status como tag
        infoMap["status"]?.let { status ->
            additionalTags.add(status)
        }
        
        // Tags combinadas
        val allTags = (mainTags + additionalTags).distinct()
        
        // Duração (para filmes)
        val durationText = infoMap["duração"] ?: infoMap["duraçã"]
        val duration = durationText?.parseDuration()
        
        // Verificar se é filme ou série
        val isMovie = url.contains("/filmes/")
        val type = if (isMovie) TvType.Movie else TvType.TvSeries
        
        // Extrair recomendações (doramas relacionados)
        val recommendations = extractRecommendationsFromSite(document)
        
        if (type == TvType.TvSeries) {
            // Extrair episódios de múltiplas temporadas
            val episodes = mutableListOf<Episode>()
            
            // Extrair cada temporada
            document.select(".dorama-one-season-block").forEach { seasonBlock ->
                // Extrair número da temporada do título
                val seasonTitle = seasonBlock.selectFirst(".dorama-one-season-title")?.text()?.trim() ?: "1° Temporada"
                val seasonNumber = extractSeasonNumber(seasonTitle)
                
                // Extrair episódios desta temporada
                seasonBlock.select(".dorama-one-episode-item").forEach { episodeItem ->
                    val episodeUrl = episodeItem.attr("href")?.let { fixUrl(it) } ?: return@forEach
                    val episodeTitle = episodeItem.selectFirst(".episode-title")?.text()?.trim() ?: "Episódio"
                    
                    // Extrair número do episódio
                    val episodeNumber = extractEpisodeNumberFromEpisodeItem(episodeItem)
                    
                    episodes.add(newEpisode(episodeUrl) {
                        this.name = episodeTitle
                        this.season = seasonNumber
                        this.episode = episodeNumber
                    })
                }
            }
            
            // Se não encontrou episódios estruturados por temporada, tentar método alternativo
            if (episodes.isEmpty()) {
                document.select(".dorama-one-episode-item").forEach { episodeItem ->
                    val episodeUrl = episodeItem.attr("href")?.let { fixUrl(it) } ?: return@forEach
                    val episodeTitle = episodeItem.selectFirst(".episode-title")?.text()?.trim() ?: "Episódio"
                    
                    val episodeNumber = extractEpisodeNumberFromEpisodeItem(episodeItem)
                    // Tentar extrair temporada da URL (ex: /temporada-1/)
                    val seasonNumber = extractSeasonNumberFromUrl(episodeUrl) ?: 1
                    
                    episodes.add(newEpisode(episodeUrl) {
                        this.name = episodeTitle
                        this.season = seasonNumber
                        this.episode = episodeNumber
                    })
                }
            }
            
            return newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = allTags
                this.recommendations = recommendations
            }
        } else {
            // Para filmes
            return newMovieLoadResponse(title, url, type, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = allTags
                this.duration = duration
                this.recommendations = recommendations
            }
        }
    }
    
// --- Load Links ---
override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    var linksFound = false
    
    println("=== DORAMOGO DEBUG: loadLinks iniciado ===")
    println("URL recebida: $data")
    
    val document = app.get(data).document
    
    // Extrair informações da URL para construir o stream
    val urlParts = data.split("/")
    val slug = urlParts.getOrNull(urlParts.indexOf("series") + 1) 
        ?: urlParts.getOrNull(urlParts.indexOf("filmes") + 1)
        ?: run {
            println("ERRO: Não encontrou 'series' ou 'filmes' na URL")
            return false
        }
    
    println("DEBUG: Slug extraído: $slug")
    
    // Extrair temporada e episódio da URL
    val temporada = extractSeasonNumberFromUrl(data) ?: 1
    val episodio = extractEpisodeNumberFromUrl(data) ?: 1
    
    println("DEBUG: Temporada: $temporada, Episódio: $episodio")
    
    // Verificar se é filme ou dorama
    val isFilme = data.contains("/filmes/")
    val tipo = if (isFilme) "filmes" else "doramas"
    
    println("DEBUG: Tipo: $tipo, é filme: $isFilme")
    
    // Construir o path do stream conforme a lógica do JavaScript
    val streamPath = if (isFilme) {
        // Para filmes: P/slug/stream/stream.m3u8
        val pt = slug.first().uppercase()
        "$pt/$slug/stream/stream.m3u8?nocache=${System.currentTimeMillis()}"
    } else {
        // Para doramas: P/slug/XX-temporada/YY/stream.m3u8
        val pt = slug.first().uppercase()
        val tempNum = temporada.toString().padStart(2, '0')
        val epNum = episodio.toString().padStart(2, '0')
        "$pt/$slug/$tempNum-temporada/$epNum/stream.m3u8?nocache=${System.currentTimeMillis()}"
    }
    
    println("DEBUG: Stream path gerado: $streamPath")
    
    // URLs dos proxies (conforme definido no JavaScript)
    val PRIMARY_URL = "https://proxy-us-east1-outbound-series.xreadycf.site"
    val FALLBACK_URL = "https://proxy-us-east1-forks-doramas.xreadycf.site"
    
    // Construir URLs completas
    val primaryStreamUrl = "$PRIMARY_URL/$streamPath"
    val fallbackStreamUrl = "$FALLBACK_URL/$streamPath"
    
    println("DEBUG: URL primária: $primaryStreamUrl")
    println("DEBUG: URL fallback: $fallbackStreamUrl")
    
    // Headers baseados no curl que você forneceu
    val headers = mapOf(
        "accept" to "*/*",
        "accept-language" to "pt-BR",
        "origin" to "https://www.doramogo.net",
        "priority" to "u=1, i",
        "referer" to "https://www.doramogo.net/",
        "sec-ch-ua" to "\"Chromium\";v=\"127\", \"Not)A;Brand\";v=\"99\", \"Microsoft Edge Simulate\";v=\"127\", \"Lemur\";v=\"127\"",
        "sec-ch-ua-mobile" to "?1",
        "sec-ch-ua-platform" to "\"Android\"",
        "sec-fetch-dest" to "empty",
        "sec-fetch-mode" to "cors",
        "sec-fetch-site" to "cross-site",
        "user-agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"
    )
    
    // Função para tentar adicionar um link com verificação
    suspend fun tryAddLink(url: String, name: String): Boolean {
        return try {
            println("DEBUG: Tentando verificar URL: $url")
            
            // Tentar fazer uma requisição para verificar se a URL está acessível
            val testResponse = app.get(
                url,
                headers = headers,
                allowRedirects = true,
                timeout = 15
            )
            
            println("DEBUG: Resposta HTTP - Código: ${testResponse.code}, Tamanho: ${testResponse.text.length}")
            
            if (testResponse.code in 200..299) {
                // URL parece estar acessível
                callback(newExtractorLink(name, "Doramogo ($name)", url, ExtractorLinkType.M3U8) {
                    referer = mainUrl
                    quality = Qualities.P720.value
                })
                
                println("DEBUG: Link adicionado com sucesso: $name")
                true
            } else {
                println("DEBUG: URL não acessível (código ${testResponse.code}): $url")
                false
            }
        } catch (e: Exception) {
            println("ERRO ao verificar URL $url: ${e.message}")
            false
        }
    }
    
    // Tentar primeiro com a URL primária
    println("=== TENTANDO URL PRIMÁRIA ===")
    if (tryAddLink(primaryStreamUrl, "Primário")) {
        linksFound = true
    }
    
    // Se não encontrou, tentar com a URL de fallback
    if (!linksFound) {
        println("=== TENTANDO URL FALLBACK ===")
        if (tryAddLink(fallbackStreamUrl, "Alternativo")) {
            linksFound = true
        }
    }
    
    // Se ainda não encontrou links, adicionar sem verificação (deixar o player tentar)
    if (!linksFound) {
        println("=== ADICIONANDO LINKS SEM VERIFICAÇÃO ===")
        
        // Adicionar URL primária mesmo sem verificar
        callback(newExtractorLink(name, "Doramogo (Auto)", primaryStreamUrl, ExtractorLinkType.M3U8) {
            referer = mainUrl
            quality = Qualities.P720.value
        })
        
        // Adicionar URL fallback também
        callback(newExtractorLink(name, "Doramogo (Backup)", fallbackStreamUrl, ExtractorLinkType.M3U8) {
            referer = mainUrl
            quality = Qualities.P720.value
        })
        
        linksFound = true
        println("DEBUG: Links adicionados sem verificação")
    }
    
    // Se ainda não encontrou links, tentar extrair do JavaScript
    if (!linksFound) {
        println("=== TENTANDO EXTRAIR DO JAVASCRIPT ===")
        val scriptContent = document.select("script").find { 
            it.html().contains("construirStreamPath") 
        }?.html()
        
        if (!scriptContent.isNullOrBlank()) {
            println("DEBUG: Script encontrado, tamanho: ${scriptContent.length}")
            // Tentar extrair URLs do JavaScript
            val urls = extractUrlsFromJavaScript(scriptContent)
            println("DEBUG: URLs extraídas do JS: ${urls.size}")
            urls.forEachIndexed { index, url ->
                println("DEBUG: URL $index: $url")
                if (url.contains(".m3u8") && !url.contains("jwplatform.com")) {
                    callback(newExtractorLink(name, "Doramogo (JS)", url, ExtractorLinkType.M3U8) {
                        referer = mainUrl
                        quality = Qualities.P720.value
                    })
                    linksFound = true
                    println("DEBUG: Link do JS adicionado")
                }
            }
        } else {
            println("DEBUG: Não encontrou script com 'construirStreamPath'")
        }
    }
    
    println("=== DORAMOGO DEBUG: loadLinks finalizado ===")
    println("Links encontrados: $linksFound")
    
    return linksFound
}
    
    // --- Funções auxiliares ---
    
    // Extrair número do episódio da URL
    private fun extractEpisodeNumberFromUrl(url: String): Int? {
        val patterns = listOf(
            Regex("""episodio-(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""ep-(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""ep(\d+)""", RegexOption.IGNORE_CASE)
        )
        
        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null) {
                return match.groupValues[1].toIntOrNull()
            }
        }
        
        return null
    }
    
    // Extrair URLs do JavaScript
    private fun extractUrlsFromJavaScript(script: String): List<String> {
        val urls = mutableListOf<String>()
        
        // Padrões para extrair URLs
        val patterns = listOf(
            Regex("""(https?://[^"' >]+\.m3u8[^"' >]*)"""),
            Regex("""PRIMARY_URL\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""FALLBACK_URL\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""url\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""file\s*:\s*['"]([^'"]+\.m3u8[^'"]*)['"]""")
        )
        
        patterns.forEach { pattern ->
            val matches = pattern.findAll(script)
            matches.forEach { match ->
                val url = match.groupValues[1]
                if (url.isNotBlank() && (url.contains("http") || url.contains("//"))) {
                    val fullUrl = if (url.startsWith("//")) "https:$url" else url
                    urls.add(fullUrl)
                }
            }
        }
        
        return urls.distinct()
    }
    
    // Extrair informações detalhadas do HTML
    private fun extractInfoMap(document: Element): Map<String, String> {
        val infoMap = mutableMapOf<String, String>()
        
        // Procurar em .detail p.text-white para ano
        document.selectFirst(".detail p.text-white")?.text()?.trim()?.let { detailText ->
            // Extrair ano do formato "킹덤 / 2026 / 12 Episodes"
            val yearMatch = Regex("""\s*/\s*(\d{4})\s*/\s*""").find(detailText)
            yearMatch?.groupValues?.get(1)?.let { year ->
                infoMap["ano"] = year
            }
            
            // Extrair número de episódios se existir
            val epMatch = Regex("""(\d+)\s*Episodes?""").find(detailText)
            epMatch?.groupValues?.get(1)?.let { eps ->
                infoMap["episódios"] = eps
            }
        }
        
        // Extrair das divs .casts div (Status, Estúdio, Áudio, Duração)
        document.select(".casts div").forEach { div ->
            val text = div.text()
            if (text.contains(":")) {
                val parts = text.split(":", limit = 2)
                if (parts.size == 2) {
                    val key = parts[0].trim().lowercase().removePrefix("b").removeSuffix(":")
                    val value = parts[1].trim()
                    
                    // Normalizar as chaves
                    val normalizedKey = when (key) {
                        "status" -> "status"
                        "estúdio", "estudio", "studio" -> "estúdio"
                        "áudio", "audio" -> "áudio"
                        "duração", "duracao", "duration" -> "duração"
                        else -> key
                    }
                    
                    infoMap[normalizedKey] = value
                }
            }
        }
        
        return infoMap
    }
    
    // Extrair recomendações (doramas relacionados) do site
    private fun extractRecommendationsFromSite(document: Element): List<SearchResponse> {
        val recommendations = mutableListOf<SearchResponse>()
        
        // Tentar diferentes seletores para recomendações
        val selectors = listOf(
            ".cover .thumbnail a", 
            ".grid .cover a", 
            ".rec-card a", 
            ".related-content a",
            "a[href*='/series/']", 
            "a[href*='/filmes/']"
        )
        
        for (selector in selectors) {
            document.select(selector).forEach { element ->
                try {
                    val href = element.attr("href")?.takeIf { it.isNotBlank() } ?: return@forEach
                    if (href == "#" || href.contains("javascript:")) return@forEach
                    
                    // Verificar se é uma URL do próprio site
                    if (!href.contains(mainUrl) && !href.startsWith("/")) return@forEach
                    
                    val imgElement = element.selectFirst("img")
                    val title = imgElement?.attr("alt")?.takeIf { it.isNotBlank() }
                        ?: imgElement?.attr("title")?.takeIf { it.isNotBlank() }
                        ?: element.attr("title")?.takeIf { it.isNotBlank() }
                        ?: return@forEach
                    
                    // Verificar se não é o mesmo item atual
                    if (title.equals(document.selectFirst("h1")?.text()?.trim(), ignoreCase = true)) {
                        return@forEach
                    }
                    
                    val poster = when {
                        imgElement?.hasAttr("data-src") == true -> fixUrl(imgElement.attr("data-src"))
                        imgElement?.hasAttr("src") == true -> fixUrl(imgElement.attr("src"))
                        else -> null
                    }
                    
                    val cleanTitle = cleanTitle(title)
                    val year = extractYearFromUrl(href)
                    
                    // Determinar tipo pelo URL
                    val type = when {
                        href.contains("/filmes/") -> TvType.Movie
                        else -> TvType.TvSeries
                    }
                    
                    if (type == TvType.Movie) {
                        recommendations.add(newMovieSearchResponse(cleanTitle, fixUrl(href), type) {
                            this.posterUrl = poster
                            this.year = year
                        })
                    } else {
                        recommendations.add(newTvSeriesSearchResponse(cleanTitle, fixUrl(href), type) {
                            this.posterUrl = poster
                            this.year = year
                        })
                    }
                    
                    // Limitar a 10 recomendações
                    if (recommendations.size >= 10) return recommendations
                } catch (e: Exception) {
                    // Ignorar erros e continuar
                }
            }
            
            if (recommendations.isNotEmpty()) break
        }
        
        return recommendations.distinctBy { it.url }.take(10)
    }
    
    // Extrair ano da URL (ex: /kingdom-2019/)
    private fun extractYearFromUrl(url: String): Int? {
        val pattern = Regex("""/(?:series|filmes)/[^/]+-(\d{4})/""")
        val match = pattern.find(url)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }
    
    // Função auxiliar para limpar títulos
    private fun cleanTitle(title: String): String {
        return title.replace(Regex("\\s*\\(Legendado\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\(Dublado\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*-\\s*(Dublado|Legendado|Online|e|Dublado e Legendado).*"), "")
            .replace(Regex("\\s*\\(.*\\)"), "")
            .replace(Regex("\\(\\d{4}\\)"), "") // Remover (2024)
            .trim()
    }
    
    // Extrair número do episódio do elemento correto
    private fun extractEpisodeNumberFromEpisodeItem(episodeItem: Element): Int {
        // Primeiro tentar do span .dorama-one-episode-number (ex: "EP 01")
        val episodeNumberSpan = episodeItem.selectFirst(".dorama-one-episode-number")
        episodeNumberSpan?.text()?.let { spanText ->
            val match = Regex("""EP\s*(\d+)""", RegexOption.IGNORE_CASE).find(spanText)
            if (match != null) {
                return match.groupValues[1].toIntOrNull() ?: 1
            }
        }
        
        // Depois tentar do span .episode-title
        val episodeTitle = episodeItem.selectFirst(".episode-title")?.text() ?: ""
        val pattern = Regex("""Episódio\s*(\d+)|Episode\s*(\d+)""", RegexOption.IGNORE_CASE)
        val match = pattern.find(episodeTitle)
        return match?.groupValues?.get(1)?.toIntOrNull()
            ?: match?.groupValues?.get(2)?.toIntOrNull()
            ?: 1
    }
    
    // Extrair número da temporada do título
    private fun extractSeasonNumber(seasonTitle: String): Int {
        val pattern = Regex("""(\d+)°\s*Temporada|Temporada\s*(\d+)""", RegexOption.IGNORE_CASE)
        val match = pattern.find(seasonTitle)
        return match?.groupValues?.get(1)?.toIntOrNull()
            ?: match?.groupValues?.get(2)?.toIntOrNull()
            ?: 1
    }
    
    private fun extractSeasonNumberFromUrl(url: String): Int? {
        val pattern = Regex("""temporada[_-](\d+)""", RegexOption.IGNORE_CASE)
        val match = pattern.find(url)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }
    
    private fun String.findYear(): Int? {
        val pattern = Regex("""\b(19\d{2}|20\d{2})\b""")
        return pattern.find(this)?.value?.toIntOrNull()
    }
    
    private fun String?.parseDuration(): Int? {
        if (this.isNullOrBlank()) return null
        val pattern = Regex("""(\d+)\s*(min|minutes|minutos)""", RegexOption.IGNORE_CASE)
        val match = pattern.find(this)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }
}
