package com.Goyabu

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Element

class Goyabu : MainAPI() {
    override var mainUrl = "https://goyabu.io"
    override var name = "Goyabu"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Anime)
    override val usesWebView = false

    companion object {
        private const val SEARCH_PATH = "/?s="
        private val loadingMutex = Mutex()
        
        // Lista de gêneros (mantida para a página principal)
        private val ALL_GENRES = listOf(
            "/generos/18" to "+18",
            "/generos/aventura" to "Aventura",
            "/generos/acao" to "Ação",
            "/generos/comedia" to "Comédia",
            "/generos/drama" to "Drama",
            "/generos/fantasia" to "Fantasia",
            "/generos/esporte" to "Esporte"
        )
    }

    override val mainPage = mainPageOf(
        *ALL_GENRES.map { (path, name) -> 
            "$mainUrl$path" to name 
        }.toTypedArray()
    )

    // Função para extrair respostas de busca
    private fun Element.toSearchResponse(): AnimeSearchResponse? {
        val href = attr("href") ?: return null
        if (href.matches(Regex("""^/\d+/?$"""))) return null
        if (!href.contains("/anime/")) return null

        val titleElement = selectFirst(".title, .hidden-text")
        val rawTitle = titleElement?.text()?.trim() ?: return null
        
        val posterUrl = selectFirst("img[src]")?.attr("src")?.let { fixUrl(it) }

        return newAnimeSearchResponse(rawTitle, fixUrl(href)) {
            this.posterUrl = posterUrl
            this.type = TvType.Anime
        }
    }

    // ============ PÁGINA PRINCIPAL ============
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return loadingMutex.withLock {
            try {
                val url = if (page > 1) "${request.data}page/$page/" else request.data
                val document = app.get(url, timeout = 20).document
                
                val elements = document.select("article a, .boxAN a, a[href*='/anime/']")
                val homeItems = elements.mapNotNull { it.toSearchResponse() }
                    .distinctBy { it.url }
                    .take(30)
                
                newHomePageResponse(request.name, homeItems, false)
            } catch (e: Exception) {
                newHomePageResponse(request.name, emptyList(), false)
            }
        }
    }

    // ============ BUSCA ============
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()
        
        val searchUrl = "$mainUrl$SEARCH_PATH${query.trim().replace(" ", "+")}"
        
        return try {
            val document = app.get(searchUrl, timeout = 20).document
            
            document.select("article a, .boxAN a, a[href*='/anime/']")
                .mapNotNull { it.toSearchResponse() }
                .distinctBy { it.url }
                .take(25)
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ============ CARREGAR ANIME (FUNCIONALIDADE PRINCIPAL) ============
    override suspend fun load(url: String): LoadResponse {
        return try {
            println("🎬 GOYABU: Carregando: $url")
            
            // 1. Pegar metadados básicos
            val document = app.get(url, timeout = 30).document
            
            val title = document.selectFirst("h1.text-hidden, h1")?.text()?.trim() ?: "Sem Título"
            val poster = document.selectFirst(".streamer-poster img, .cover")?.attr("src")
                ?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
            val synopsis = document.selectFirst(".streamer-sinopse")?.text()?.trim()
                ?.replace("ler mais", "")?.trim() ?: "Sinopse não disponível."
            
            // 2. Buscar episódios via API AJAX
            println("🔍 Buscando episódios via API AJAX...")
            val episodes = extractEpisodesDirectly(url)
            println("📺 Total de episódios encontrados: ${episodes.size}")
            
            // 3. Criar resposta
            newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.plot = synopsis
                
                if (episodes.isNotEmpty()) {
                    addEpisodes(DubStatus.Subbed, episodes.sortedBy { it.episode })
                    println("✅ ${episodes.size} episódios adicionados")
                    
                    // Debug: mostrar primeiros episódios
                    episodes.take(5).forEach { ep ->
                        println("   Ep ${ep.episode}: ${ep.name}")
                    }
                } else {
                    println("⚠️ Nenhum episódio encontrado, usando fallback")
                    addEpisodes(DubStatus.Subbed, listOf(
                        newEpisode(url) {
                            this.name = "Episódio 1"
                            this.episode = 1
                            this.season = 1
                        }
                    ))
                }
            }
            
        } catch (e: Exception) {
            println("❌ ERRO no load: ${e.message}")
            newAnimeLoadResponse("Erro", url, TvType.Anime) {
                this.plot = "Erro: ${e.message}"
            }
        }
    }
    
    // ============ EXTRAÇÃO DIRETA DE EPISÓDIOS ============
    private suspend fun extractEpisodesDirectly(url: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        try {
            // Extrair slug do anime
            val animeSlug = url.substringAfter("/anime/").substringBefore("/").substringBefore("?")
            if (animeSlug.isBlank()) return emptyList()
            
            println("   🔍 Slug do anime: $animeSlug")
            
            // Endpoint principal da API
            val apiUrl = "$mainUrl/ajax/episodes/$animeSlug"
            println("   📡 Acessando: $apiUrl")
            
            // Fazer requisição AJAX
            val response = app.get(apiUrl, timeout = 25, headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to url,
                "Accept" to "text/html, application/xhtml+xml, */*"
            ))
            
            val responseText = response.text
            println("   ✅ Resposta recebida (${responseText.length} chars)")
            
            if (responseText.isBlank()) {
                println("   ⚠️ Resposta vazia")
                return emptyList()
            }
            
            // Analisar o HTML retornado
            val doc = org.jsoup.Jsoup.parse(responseText)
            
            // ESTRATÉGIA 1: Procurar por container de episódios
            val container = doc.selectFirst("#episodes-container, .episodes-container, .episodes-grid")
            if (container != null) {
                println("   📦 Container encontrado")
                extractEpisodesFromContainer(container, episodes)
            }
            
            // ESTRATÉGIA 2: Procurar por links de episódios diretamente
            if (episodes.isEmpty()) {
                println("   🔍 Procurando links de episódios diretamente...")
                extractEpisodeLinksDirectly(doc, episodes)
            }
            
            // ESTRATÉGIA 3: Procurar por elementos com classe de episódio
            if (episodes.isEmpty()) {
                println("   🔍 Procurando por elementos de episódio...")
                extractEpisodeElements(doc, episodes)
            }
            
            println("   📊 Extração final: ${episodes.size} episódios")
            
        } catch (e: Exception) {
            println("   ❌ Erro na extração: ${e.message}")
        }
        
        return episodes
    }
    
    private fun extractEpisodesFromContainer(container: Element, episodes: MutableList<Episode>) {
        try {
            // Procurar todos os links dentro do container
            val links = container.select("a[href]")
            println("   🔗 ${links.size} links no container")
            
            var episodeCount = 0
            
            for (link in links) {
                try {
                    val href = link.attr("href").trim()
                    if (href.isBlank()) continue
                    
                    // Verificar se é link de episódio
                    val isEpisodeLink = href.contains("/episodio/") || 
                                        href.contains("/assistir/") ||
                                        href.matches(Regex("""^/\d+/?$"""))
                    
                    if (!isEpisodeLink) continue
                    
                    // Extrair número do episódio
                    var episodeNum = episodeCount + 1
                    
                    // Tentar extrair do texto do link
                    val linkText = link.text()
                    val numRegex = Regex("""epis[oó]dio\s+(\d+)""", RegexOption.IGNORE_CASE)
                    val numMatch = numRegex.find(linkText)
                    numMatch?.groupValues?.get(1)?.toIntOrNull()?.let { episodeNum = it }
                    
                    // Tentar extrair da URL
                    val urlNumRegex = Regex("""/episodio[-_]?(\d+)/?$""", RegexOption.IGNORE_CASE)
                    val urlMatch = urlNumRegex.find(href)
                    urlMatch?.groupValues?.get(1)?.toIntOrNull()?.let { episodeNum = it }
                    
                    // Nome do episódio
                    val episodeTitle = link.selectFirst(".ep-type b, .title, .episode-title")?.text()?.trim()
                        ?: "Episódio $episodeNum"
                    
                    episodes.add(newEpisode(fixUrl(href)) {
                        this.name = episodeTitle
                        this.episode = episodeNum
                        this.season = 1
                    })
                    
                    episodeCount++
                    
                    if (episodeCount >= 100) break // Limitar
                    
                } catch (e: Exception) {
                    // Ignorar erro neste link
                }
            }
            
            println("   ✅ ${episodes.size} episódios extraídos do container")
            
        } catch (e: Exception) {
            println("   ❌ Erro no container: ${e.message}")
        }
    }
    
    private fun extractEpisodeLinksDirectly(doc: org.jsoup.nodes.Document, episodes: MutableList<Episode>) {
        try {
            // Buscar todos os links que parecem ser de episódios
            val allLinks = doc.select("a[href]")
            var episodeCount = 0
            
            for (link in allLinks) {
                val href = link.attr("href").trim()
                if (href.isBlank()) continue
                
                // Critérios para identificar episódios
                val isEpisodeLink = href.contains("/episodio/") || 
                                    href.contains("/assistir/") ||
                                    href.matches(Regex("""^/\d+/?$"""))
                
                if (isEpisodeLink) {
                    // Extrair número do episódio
                    var episodeNum = episodeCount + 1
                    
                    // Tentar da URL primeiro
                    val urlNumRegex = Regex("""/episodio[-_]?(\d+)/?$""", RegexOption.IGNORE_CASE)
                    val urlMatch = urlNumRegex.find(href)
                    urlMatch?.groupValues?.get(1)?.toIntOrNull()?.let { episodeNum = it }
                    
                    // Nome do episódio
                    val linkText = link.text().trim()
                    val episodeTitle = if (linkText.isNotBlank()) linkText else "Episódio $episodeNum"
                    
                    episodes.add(newEpisode(fixUrl(href)) {
                        this.name = episodeTitle
                        this.episode = episodeNum
                        this.season = 1
                    })
                    
                    episodeCount++
                    println("   🔗 Link de episódio: $href")
                    
                    if (episodeCount >= 50) break
                }
            }
            
            println("   ✅ ${episodes.size} links diretos encontrados")
            
        } catch (e: Exception) {
            println("   ❌ Erro nos links diretos: ${e.message}")
        }
    }
    
    private fun extractEpisodeElements(doc: org.jsoup.nodes.Document, episodes: MutableList<Episode>) {
        try {
            // Seletores para elementos de episódio
            val selectors = listOf(
                ".episode-item",
                ".boxEP",
                "[class*='episode']",
                ".episode",
                ".ep"
            )
            
            var episodeCount = 0
            
            for (selector in selectors) {
                val elements = doc.select(selector)
                if (elements.isNotEmpty()) {
                    println("   🔍 Seletor '$selector': ${elements.size} elementos")
                    
                    for (element in elements) {
                        try {
                            // Procurar link dentro do elemento
                            val link = element.selectFirst("a[href]")
                            val href = link?.attr("href")?.trim()
                            
                            if (href.isNullOrBlank()) continue
                            
                            // Verificar se é episódio
                            if (!href.contains("/episodio/") && 
                                !href.contains("/assistir/") &&
                                !href.matches(Regex("""^/\d+/?$"""))) {
                                continue
                            }
                            
                            // Extrair número
                            var episodeNum = episodeCount + 1
                            
                            // Do elemento pai
                            element.attr("data-episode-number")?.toIntOrNull()?.let { episodeNum = it }
                            
                            // Do texto
                            val elementText = element.text()
                            val numRegex = Regex("""epis[oó]dio\s+(\d+)""", RegexOption.IGNORE_CASE)
                            val numMatch = numRegex.find(elementText)
                            numMatch?.groupValues?.get(1)?.toIntOrNull()?.let { episodeNum = it }
                            
                            // Nome
                            val titleElement = element.selectFirst(".title, .episode-title, .ep-type b")
                            val episodeTitle = titleElement?.text()?.trim() ?: "Episódio $episodeNum"
                            
                            episodes.add(newEpisode(fixUrl(href)) {
                                this.name = episodeTitle
                                this.episode = episodeNum
                                this.season = 1
                            })
                            
                            episodeCount++
                            
                            if (episodeCount >= 50) break
                            
                        } catch (e: Exception) {
                            // Ignorar erro
                        }
                    }
                    
                    if (episodes.isNotEmpty()) break
                }
            }
            
            println("   ✅ ${episodes.size} elementos de episódio encontrados")
            
        } catch (e: Exception) {
            println("   ❌ Erro nos elementos: ${e.message}")
        }
    }

    // ============ LOAD LINKS (desabilitado) ============
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return false
    }
}
