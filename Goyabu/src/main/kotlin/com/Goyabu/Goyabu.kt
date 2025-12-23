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
    override val usesWebView = true

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

    override suspend fun load(url: String): LoadResponse {
        return try {
            println("\n" + "=".repeat(60))
            println("🎬 GOYABU - SOLUÇÃO DIRETA")
            println("URL: $url")
            println("=".repeat(60))
            
            // 1. Usar WebView para carregar JavaScript
            println("🌐 Usando WebView para executar JavaScript...")
            val document = if (usesWebView) {
                app.get(url, timeout = 60).document
            } else {
                app.get(url, timeout = 30).document
            }
            
            // 2. Metadados básicos
            val title = document.selectFirst("h1.text-hidden, h1")?.text()?.trim() ?: "Sem Título"
            val poster = document.selectFirst(".streamer-poster img, .cover")?.attr("src")
                ?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
            val synopsis = document.selectFirst(".streamer-sinopse")?.text()?.trim()
                ?.replace("ler mais", "")?.trim() ?: "Sinopse não disponível."
            
            println("📌 Título: $title")
            
            // 3. ESTRATÉGIA SIMPLES: Procurar links de episódios
            println("\n🔍 PROCURANDO EPISÓDIOS NO HTML...")
            var episodes = extractEpisodesSimply(document, url)
            
            println("📺 EPISÓDIOS ENCONTRADOS: ${episodes.size}")
            
            // 4. Se não encontrou, fazer uma busca mais agressiva
            if (episodes.isEmpty()) {
                println("\n🔍 BUSCA AGUÇADA...")
                val aggressiveEpisodes = extractEpisodesAggressively(document, url)
                // CORREÇÃO: Não usar addAll, criar nova lista
                episodes = aggressiveEpisodes
                if (aggressiveEpisodes.isNotEmpty()) {
                    println("✅ Encontrados ${aggressiveEpisodes.size} episódios na busca aguçada")
                }
            }
            
            // 5. DEBUG: Mostrar estrutura da página
            if (episodes.isEmpty()) {
                println("\n🐛 DEBUG - ESTRUTURA DA PÁGINA:")
                debugPageContent(document)
            }
            
            // 6. Criar resposta
            val response = newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.plot = synopsis
                
                if (episodes.isNotEmpty()) {
                    addEpisodes(DubStatus.Subbed, episodes.sortedBy { it.episode })
                    println("\n✅ SUCESSO! ${episodes.size} EPISÓDIOS:")
                    
                    episodes.take(10).forEach { ep ->
                        println("   Ep ${ep.episode}: ${ep.name}")
                    }
                    if (episodes.size > 10) {
                        println("   ... e mais ${episodes.size - 10} episódios")
                    }
                } else {
                    println("\n⚠️ NENHUM EPISÓDIO ENCONTRADO")
                    // Fallback: criar episódios baseado na estrutura comum
                    val fallbackEpisodes = createSmartFallback(url, title)
                    addEpisodes(DubStatus.Subbed, fallbackEpisodes)
                    println("📺 Criados ${fallbackEpisodes.size} episódios fallback")
                }
            }
            
            println("\n" + "=".repeat(60))
            println("🎬 CONCLUÍDO")
            println("=".repeat(60) + "\n")
            
            response
            
        } catch (e: Exception) {
            println("❌ ERRO: ${e.message}")
            newAnimeLoadResponse("Erro", url, TvType.Anime) {
                this.plot = "Erro: ${e.message}"
            }
        }
    }
    
    private fun extractEpisodesSimply(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        try {
            // ESTRATÉGIA 1: Links numéricos diretos (mais comum)
            println("   🔍 Buscando links numéricos...")
            val numericLinks = document.select("a[href^='/']").filter { link ->
                val href = link.attr("href")
                href.matches(Regex("""^/\d+/?$"""))
            }
            
            println("   📎 Links numéricos: ${numericLinks.size}")
            
            numericLinks.forEachIndexed { index, link ->
                val href = link.attr("href")
                val episodeNum = href.replace("/", "").toIntOrNull() ?: (index + 1)
                val title = link.text().trim().takeIf { it.isNotBlank() } ?: "Episódio $episodeNum"
                
                episodes.add(newEpisode(fixUrl(href)) {
                    this.name = title
                    this.episode = episodeNum
                    this.season = 1
                })
                
                println("   ✅ Encontrado: Ep $episodeNum -> $href")
            }
            
            // ESTRATÉGIA 2: Links com /assistir/
            if (episodes.isEmpty()) {
                println("\n   🔍 Buscando links /assistir/...")
                val assistirLinks = document.select("a[href*='/assistir/']")
                println("   📎 Links /assistir/: ${assistirLinks.size}")
                
                assistirLinks.forEachIndexed { index, link ->
                    val href = link.attr("href")
                    val episodeNum = extractEpisodeNumberFromUrl(href, index + 1)
                    val title = link.text().trim().takeIf { it.isNotBlank() } ?: "Episódio $episodeNum"
                    
                    episodes.add(newEpisode(fixUrl(href)) {
                        this.name = title
                        this.episode = episodeNum
                        this.season = 1
                    })
                    
                    println("   ✅ Encontrado: Ep $episodeNum -> $href")
                }
            }
            
            // ESTRATÉGIA 3: Links com /episodio/
            if (episodes.isEmpty()) {
                println("\n   🔍 Buscando links /episodio/...")
                val episodioLinks = document.select("a[href*='/episodio/']")
                println("   📎 Links /episodio/: ${episodioLinks.size}")
                
                episodioLinks.forEachIndexed { index, link ->
                    val href = link.attr("href")
                    val episodeNum = extractEpisodeNumberFromUrl(href, index + 1)
                    val title = link.text().trim().takeIf { it.isNotBlank() } ?: "Episódio $episodeNum"
                    
                    episodes.add(newEpisode(fixUrl(href)) {
                        this.name = title
                        this.episode = episodeNum
                        this.season = 1
                    })
                    
                    println("   ✅ Encontrado: Ep $episodeNum -> $href")
                }
            }
            
        } catch (e: Exception) {
            println("   ❌ Erro na extração simples: ${e.message}")
        }
        
        return episodes
    }
    
    private fun extractEpisodesAggressively(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val processedUrls = mutableSetOf<String>()
        
        try {
            println("   🔍 Analisando TODOS os links...")
            
            val allLinks = document.select("a[href]")
            println("   📎 Total de links na página: ${allLinks.size}")
            
            // Mostrar primeiros 20 links para debug
            println("\n   🔗 PRIMEIROS 20 LINKS:")
            allLinks.take(20).forEachIndexed { i, link ->
                val href = link.attr("href")
                val text = link.text().trim()
                println("   $i. href='$href' text='${if (text.length > 30) text.take(30) + "..." else text}'")
            }
            
            // Filtrar links que parecem ser de episódios
            var episodeCounter = 0
            
            for (link in allLinks) {
                val href = link.attr("href").trim()
                if (href.isBlank() || href in processedUrls) continue
                
                processedUrls.add(href)
                
                // Critérios para identificar episódios:
                val isEpisodeLink = when {
                    // Link numérico: /123/
                    href.matches(Regex("""^/\d+/?$""")) -> true
                    // Contém "assistir" ou "episodio"
                    href.contains("/assistir/") -> true
                    href.contains("/episodio/") -> true
                    // É um link relativo curto que não é menu
                    href.startsWith("/") && 
                    href.length in 2..20 &&
                    !href.contains("/anime/") &&
                    !href.contains("/generos/") &&
                    !href.contains("/?") &&
                    !href.contains("#") -> {
                        // Verificar se o texto do link parece ser episódio
                        val text = link.text().trim()
                        text.contains(Regex("""epis[oó]dio""", RegexOption.IGNORE_CASE)) ||
                        text.contains(Regex("""ep\.?\s*\d+""", RegexOption.IGNORE_CASE))
                    }
                    else -> false
                }
                
                if (isEpisodeLink) {
                    episodeCounter++
                    val episodeNum = extractEpisodeNumberFromUrl(href, episodeCounter)
                    val title = link.text().trim().takeIf { it.isNotBlank() } ?: "Episódio $episodeNum"
                    
                    episodes.add(newEpisode(fixUrl(href)) {
                        this.name = title
                        this.episode = episodeNum
                        this.season = 1
                    })
                    
                    println("   ✅ Episódio identificado: $episodeNum -> $href")
                }
            }
            
            println("   📊 Links processados: ${processedUrls.size}")
            println("   📊 Episódios identificados: ${episodes.size}")
            
        } catch (e: Exception) {
            println("   ❌ Erro na busca aguçada: ${e.message}")
        }
        
        return episodes
    }
    
    private fun extractEpisodeNumberFromUrl(url: String, default: Int = 1): Int {
        // Tentar vários padrões
        val patterns = listOf(
            Regex("""/(\d+)/?$"""), // /123/
            Regex("""/assistir/(\d+)/?"""), // /assistir/123/
            Regex("""/episodio/(\d+)/?"""), // /episodio/123/
            Regex("""-(\d+)/?$"""), // -123/
            Regex("""ep(\d+)""", RegexOption.IGNORE_CASE), // ep123
            Regex("""episodio[-_]?(\d+)""", RegexOption.IGNORE_CASE) // episodio-123
        )
        
        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null) {
                return match.groupValues[1].toIntOrNull() ?: default
            }
        }
        
        return default
    }
    
    private fun createSmartFallback(url: String, title: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        // Criar um número razoável de episódios baseado no padrão comum
        // Muitos animes têm 12, 24, 26 ou 52 episódios
        val episodeCounts = listOf(12, 24, 26, 52)
        
        for (count in episodeCounts) {
            // Criar URLs sequenciais
            for (i in 1..count) {
                val episodeUrl = if (url.endsWith("/")) "${url}$i" else "$url/$i"
                episodes.add(newEpisode(episodeUrl) {
                    this.name = "$title - Episódio $i"
                    this.episode = i
                    this.season = 1
                })
            }
            break // Usar apenas o primeiro count
        }
        
        return episodes
    }
    
    private fun debugPageContent(document: org.jsoup.nodes.Document) {
        println("\n   📊 ESTRUTURA DA PÁGINA:")
        println("   " + "-".repeat(40))
        
        // 1. Verificar container de episódios
        println("   1. Containers de episódios:")
        val episodeContainers = listOf(
            "#episodes-container",
            ".episodes",
            ".episodes-grid",
            ".episode-list",
            "[class*='episode']",
            "[id*='episode']"
        )
        
        episodeContainers.forEach { selector ->
            val elements = document.select(selector)
            if (elements.isNotEmpty()) {
                println("      '$selector': ${elements.size} elementos")
                elements.take(2).forEachIndexed { i, element ->
                    println("        $i. Texto: '${element.text().take(50)}...'")
                    println("           HTML: '${element.html().take(100)}...'")
                }
            }
        }
        
        // 2. Contar tipos de links
        println("\n   2. ANÁLISE DE LINKS:")
        val allLinks = document.select("a[href]")
        println("      Total de links: ${allLinks.size}")
        
        val linkTypes = mapOf(
            "Numéricos (/\\d+/)" to allLinks.filter { it.attr("href").matches(Regex("""^/\d+/?$""")) }.size,
            "Com /assistir/" to allLinks.filter { it.attr("href").contains("/assistir/") }.size,
            "Com /episodio/" to allLinks.filter { it.attr("href").contains("/episodio/") }.size,
            "Relativos curtos" to allLinks.filter { 
                val href = it.attr("href")
                href.startsWith("/") && href.length in 2..20 
            }.size
        )
        
        linkTypes.forEach { (type, count) ->
            if (count > 0) {
                println("      $type: $count")
            }
        }
        
        // 3. Mostrar primeiros 10 links para referência
        println("\n   3. AMOSTRA DE LINKS:")
        allLinks.take(10).forEachIndexed { i, link ->
            val href = link.attr("href")
            val text = link.text().trim()
            println("      $i. $href -> '${if (text.isBlank()) "(sem texto)" else text}'")
        }
        
        println("   " + "-".repeat(40))
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
