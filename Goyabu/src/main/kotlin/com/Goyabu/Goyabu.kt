package com.Goyabu

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Element
import java.io.File

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
        
        private val ALL_GENRES = listOf(
            "/generos/18" to "+18",
            "/generos/aventura" to "Aventura",
            "/generos/acao" to "Ação",
            "/generos/comedia" to "Comédia",
            "/generos/drama" to "Drama"
        )
    }

    override val mainPage = mainPageOf(
        *ALL_GENRES.map { (path, name) -> 
            "$mainUrl$path" to name 
        }.toTypedArray()
    )

    // Função simples para extrair resultados de busca
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

    // Página principal
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

    // Busca
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

    // CARREGAR ANIME - VERSÃO AGUÇADA
    override suspend fun load(url: String): LoadResponse {
        return try {
            println("\n" + "=".repeat(60))
            println("🎬 GOYABU DEBUG: Iniciando load para: $url")
            println("=".repeat(60))
            
            // 1. Pegar metadados básicos
            val document = app.get(url, timeout = 30).document
            
            val title = document.selectFirst("h1.text-hidden, h1")?.text()?.trim() ?: "Sem Título"
            val poster = document.selectFirst(".streamer-poster img, .cover")?.attr("src")
                ?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
            val synopsis = document.selectFirst(".streamer-sinopse")?.text()?.trim()
                ?.replace("ler mais", "")?.trim() ?: "Sinopse não disponível."
            
            println("📌 Título: $title")
            println("🖼️ Poster: ${poster != null}")
            println("📖 Sinopse: ${synopsis.take(50)}...")
            
            // 2. EXTRAIR EPISÓDIOS COM ESTRATÉGIA AGUÇADA
            println("\n🔍 INICIANDO EXTRAÇÃO AGUÇADA DE EPISÓDIOS")
            val episodes = extractEpisodesAggressively(url)
            println("📺 EPISÓDIOS ENCONTRADOS: ${episodes.size}")
            
            // 3. Criar resposta
            val response = newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.plot = synopsis
                
                if (episodes.isNotEmpty()) {
                    addEpisodes(DubStatus.Subbed, episodes.sortedBy { it.episode })
                    println("\n✅ ${episodes.size} EPISÓDIOS ADICIONADOS COM SUCESSO!")
                    
                    // Mostrar todos os episódios encontrados
                    episodes.forEachIndexed { index, ep ->
                        println("   ${index + 1}. Ep ${ep.episode}: ${ep.name}")
                    }
                } else {
                    println("\n⚠️ NENHUM EPISÓDIO ENCONTRADO - USANDO FALLBACK")
                    addEpisodes(DubStatus.Subbed, listOf(
                        newEpisode(url) {
                            this.name = "Episódio 1"
                            this.episode = 1
                            this.season = 1
                        }
                    ))
                }
            }
            
            println("\n" + "=".repeat(60))
            println("🎬 GOYABU DEBUG: Load concluído para '$title'")
            println("=".repeat(60) + "\n")
            
            response
            
        } catch (e: Exception) {
            println("❌ ERRO CRÍTICO no load: ${e.message}")
            newAnimeLoadResponse("Erro", url, TvType.Anime) {
                this.plot = "Erro: ${e.message}"
            }
        }
    }
    
    // EXTRAÇÃO AGUÇADA DE EPISÓDIOS
    private suspend fun extractEpisodesAggressively(url: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        try {
            // Extrair slug do anime
            val animeSlug = url.substringAfter("/anime/").substringBefore("/").substringBefore("?")
            if (animeSlug.isBlank()) {
                println("   ❌ Não consegui extrair slug da URL")
                return emptyList()
            }
            
            println("   🔍 Slug extraído: $animeSlug")
            
            // Endpoint da API
            val apiUrl = "$mainUrl/ajax/episodes/$animeSlug"
            println("   📡 Chamando API: $apiUrl")
            
            // Fazer requisição com headers específicos
            val response = app.get(apiUrl, timeout = 30, headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to url,
                "Accept" to "text/html, */*",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            ))
            
            val responseText = response.text
            println("   📊 Resposta da API: ${responseText.length} caracteres")
            
            if (responseText.isBlank()) {
                println("   ⚠️ Resposta vazia da API")
                return emptyList()
            }
            
            // SALVAR HTML PARA ANÁLISE (apenas debug)
            try {
                val debugFile = File("/data/data/com.lagradost.cloudstream3/files/debug_goyabu.html")
                debugFile.writeText(responseText)
                println("   💾 HTML salvo em: ${debugFile.path}")
                println("   📋 Primeiros 500 chars: ${responseText.take(500)}...")
            } catch (e: Exception) {
                println("   ⚠️ Não consegui salvar debug file: ${e.message}")
            }
            
            // Parsear o HTML
            val doc = org.jsoup.Jsoup.parse(responseText)
            
            // ESTRATÉGIA 1: BUSCAR TODOS OS LINKS E FILTRAR
            println("\n   🔎 ESTRATÉGIA 1: Buscando todos os links...")
            val allLinks = doc.select("a[href]")
            println("   📎 Total de links encontrados: ${allLinks.size}")
            
            var episodeCounter = 0
            val processedUrls = mutableSetOf<String>()
            
            for (link in allLinks) {
                try {
                    val href = link.attr("href").trim()
                    if (href.isBlank() || href in processedUrls) continue
                    
                    processedUrls.add(href)
                    
                    // VERIFICAR SE É LINK DE EPISÓDIO
                    val isEpisodeLink = isEpisodeLink(href)
                    
                    if (isEpisodeLink) {
                        episodeCounter++
                        
                        // Extrair número do episódio
                        val episodeNum = extractEpisodeNumber(href, link.text(), episodeCounter)
                        
                        // Extrair título
                        val episodeTitle = extractEpisodeTitle(link, episodeNum)
                        
                        // Criar episódio
                        val episodeUrl = if (href.startsWith("http")) href else fixUrl(href)
                        
                        episodes.add(newEpisode(episodeUrl) {
                            this.name = episodeTitle
                            this.episode = episodeNum
                            this.season = 1
                        })
                        
                        println("   ✅ Ep $episodeNum: $episodeTitle -> $href")
                        
                        // Limitar para debug
                        if (episodeCounter >= 100) break
                    }
                    
                } catch (e: Exception) {
                    // Ignorar erro neste link
                }
            }
            
            println("   📊 Estratégia 1: ${episodes.size} episódios encontrados")
            
            // ESTRATÉGIA 2: Se não encontrou nada, procurar por padrões específicos
            if (episodes.isEmpty()) {
                println("\n   🔎 ESTRATÉGIA 2: Procurando por padrões específicos...")
                
                // Padrões comuns de URLs de episódios
                val patterns = listOf(
                    """href=["'](/[^"' >]+)["']""",
                    """data-url=["']([^"' >]+)["']""",
                    """data-href=["']([^"' >]+)["']""",
                    """episode-url=["']([^"' >]+)["']"""
                )
                
                for (pattern in patterns) {
                    try {
                        val regex = Regex(pattern)
                        val matches = regex.findAll(responseText)
                        
                        for (match in matches) {
                            val href = match.groupValues[1].trim()
                            if (href.isBlank() || href in processedUrls) continue
                            
                            if (isEpisodeLink(href)) {
                                episodeCounter++
                                
                                val episodeNum = extractEpisodeNumber(href, "", episodeCounter)
                                val episodeUrl = if (href.startsWith("http")) href else fixUrl(href)
                                
                                episodes.add(newEpisode(episodeUrl) {
                                    this.name = "Episódio $episodeNum"
                                    this.episode = episodeNum
                                    this.season = 1
                                })
                                
                                processedUrls.add(href)
                                println("   🔗 Padrão '$pattern': $href")
                            }
                        }
                    } catch (e: Exception) {
                        // Ignorar erro neste padrão
                    }
                }
            }
            
            // ESTRATÉGIA 3: Procurar por números em sequência
            if (episodes.isEmpty()) {
                println("\n   🔎 ESTRATÉGIA 3: Criando episódios sequenciais...")
                
                // Se não encontrou links, criar episódios com base no padrão comum do site
                for (i in 1..12) { // Tentar 12 episódios
                    val episodeUrl = "$mainUrl/$animeSlug-$i"
                    episodes.add(newEpisode(episodeUrl) {
                        this.name = "Episódio $i"
                        this.episode = i
                        this.season = 1
                    })
                }
                println("   📺 Criados 12 episódios sequenciais")
            }
            
            println("\n   🎯 EXTRAÇÃO FINALIZADA: ${episodes.size} episódios")
            
        } catch (e: Exception) {
            println("   ❌ ERRO na extração aguçada: ${e.message}")
        }
        
        return episodes
    }
    
    // Função para verificar se é link de episódio
    private fun isEpisodeLink(href: String): Boolean {
        if (href.isBlank()) return false
        
        // Padrões comuns de episódios
        val patterns = listOf(
            href.contains("/episodio/", ignoreCase = true),
            href.contains("/assistir/", ignoreCase = true),
            href.contains("/watch/", ignoreCase = true),
            href.contains("/ep-", ignoreCase = true),
            href.contains("-episode-", ignoreCase = true),
            href.matches(Regex("""^/\d+/?$""")),
            href.matches(Regex("""^/\d+/assistir/?$""")),
            href.matches(Regex("""^/\d+/episodio/?$""")),
            href.contains("-episodio-", ignoreCase = true),
            href.contains("episodio", ignoreCase = true) && href.contains(Regex("""\d+"""))
        )
        
        return patterns.any { it }
    }
    
    // Função para extrair número do episódio
    private fun extractEpisodeNumber(href: String, text: String, default: Int): Int {
        // Tentar da URL primeiro
        val urlPatterns = listOf(
            Regex("""/episodio[-_]?(\d+)/?$""", RegexOption.IGNORE_CASE),
            Regex("""/ep[-_]?(\d+)/?$""", RegexOption.IGNORE_CASE),
            Regex("""/assistir/(\d+)/?$""", RegexOption.IGNORE_CASE),
            Regex("""^/(\d+)/?$"""),
            Regex("""-episodio[-_]?(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""-ep[-_]?(\d+)""", RegexOption.IGNORE_CASE)
        )
        
        for (pattern in urlPatterns) {
            val match = pattern.find(href)
            if (match != null) {
                return match.groupValues[1].toIntOrNull() ?: default
            }
        }
        
        // Tentar do texto
        val textPatterns = listOf(
            Regex("""epis[oó]dio\s+(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""ep\.?\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""(\d+)\s*-\s*epis[oó]dio""", RegexOption.IGNORE_CASE)
        )
        
        for (pattern in textPatterns) {
            val match = pattern.find(text)
            if (match != null) {
                return match.groupValues[1].toIntOrNull() ?: default
            }
        }
        
        return default
    }
    
    // Função para extrair título do episódio
    private fun extractEpisodeTitle(element: Element, episodeNum: Int): String {
        // Tentar extrair de elementos específicos
        val titleSelectors = listOf(
            ".title", 
            ".episode-title",
            ".ep-type b",
            ".ep-name",
            ".name",
            "h3", "h4",
            "[class*='title']"
        )
        
        for (selector in titleSelectors) {
            val titleElement = element.selectFirst(selector)
            val title = titleElement?.text()?.trim()
            if (!title.isNullOrBlank() && title.length < 100) {
                return title
            }
        }
        
        // Se não encontrou, usar texto do elemento
        val elementText = element.text().trim()
        if (elementText.isNotBlank() && elementText.length < 100 && 
            !elementText.matches(Regex("""^\d+$"""))) {
            return elementText
        }
        
        return "Episódio $episodeNum"
    }

    // LOAD LINKS (desabilitado)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return false
    }
}
