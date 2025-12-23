package com.Goyabu

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Element
import org.json.JSONObject
import org.json.JSONArray

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
            println("🎬 GOYABU - CAPTURANDO DADOS REAIS")
            println("URL: $url")
            println("=".repeat(60))
            
            // 1. Baixar a página completa
            val response = app.get(url, timeout = 40)
            val html = response.text
            val document = response.document
            
            // 2. Metadados básicos
            val title = document.selectFirst("h1.text-hidden, h1")?.text()?.trim() ?: "Sem Título"
            val poster = document.selectFirst(".streamer-poster img, .cover")?.attr("src")
                ?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
            val synopsis = document.selectFirst(".streamer-sinopse")?.text()?.trim()
                ?.replace("ler mais", "")?.trim() ?: "Sinopse não disponível."
            
            println("📌 Título: $title")
            println("📝 Tamanho do HTML: ${html.length} caracteres")
            
            // 3. ESTRATÉGIA PRINCIPAL: Extrair dados dos scripts
            println("\n🔍 EXTRAINDO DADOS DOS SCRIPTS...")
            var episodes = extractEpisodesFromScripts(html, url)
            
            println("📺 EPISÓDIOS ENCONTRADOS NOS SCRIPTS: ${episodes.size}")
            
            // 4. Se não encontrou, tentar outras estratégias
            if (episodes.isEmpty()) {
                println("\n🔍 PROCURANDO HTML RENDERIZADO...")
                val htmlEpisodes = extractEpisodesFromRenderedHTML(document, url)
                episodes = htmlEpisodes // CORREÇÃO: não usa addAll
                println("📺 EPISÓDIOS NO HTML: ${htmlEpisodes.size}")
            }
            
            // 5. Mostrar debug detalhado
            if (episodes.isEmpty()) {
                println("\n🔍 DEBUG: Analisando estrutura da página...")
                debugPageStructure(document, html)
            }
            
            // 6. Criar resposta
            val responseObj = newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.plot = synopsis
                
                if (episodes.isNotEmpty()) {
                    addEpisodes(DubStatus.Subbed, episodes.sortedBy { it.episode })
                    println("\n✅ SUCESSO! ${episodes.size} EPISÓDIOS ADICIONADOS")
                    
                    // Mostrar todos os episódios
                    episodes.forEachIndexed { i, ep ->
                        println("   ${i + 1}. Ep ${ep.episode}: ${ep.name.take(40)}...")
                    }
                } else {
                    println("\n⚠️ NENHUM EPISÓDIO ENCONTRADO")
                    // Fallback mínimo
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
            println("🎬 CONCLUÍDO")
            println("=".repeat(60) + "\n")
            
            responseObj
            
        } catch (e: Exception) {
            println("❌ ERRO: ${e.message}")
            newAnimeLoadResponse("Erro", url, TvType.Anime) {
                this.plot = "Erro: ${e.message}"
            }
        }
    }
    
    // ESTRATÉGIA PRINCIPAL: Extrair dos scripts
    private fun extractEpisodesFromScripts(html: String, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        try {
            println("   📜 Analisando scripts...")
            
            // Padrão 1: JSON com episódios
            val jsonPatterns = listOf(
                """episodes\s*:\s*(\[[^\]]*?\])""",
                """episodes\s*=\s*(\[[^\]]*?\])""",
                """var\s+episodes\s*=\s*(\[[^\]]*?\])""",
                """data-episodes\s*=\s*['"]([^'"]+)['"]""",
                """episodeList\s*:\s*(\[[^\]]*?\])""",
                """"episodes"\s*:\s*(\[[^\]]*?\])"""
            )
            
            for (pattern in jsonPatterns) {
                val regex = Regex(pattern, RegexOption.IGNORE_CASE)
                val match = regex.find(html)
                
                if (match != null) {
                    println("   ✅ JSON encontrado com padrão: ${pattern.take(30)}...")
                    val jsonStr = match.groupValues[1]
                    println("   📦 Tamanho do JSON: ${jsonStr.length} chars")
                    
                    try {
                        val jsonArray = JSONArray(jsonStr)
                        println("   📋 Array JSON com ${jsonArray.length()} itens")
                        
                        for (i in 0 until jsonArray.length()) {
                            try {
                                val item = jsonArray.getJSONObject(i)
                                val episode = parseJsonEpisode(item, baseUrl)
                                if (episode != null) {
                                    episodes.add(episode)
                                }
                            } catch (e: Exception) {
                                // Ignorar item inválido
                            }
                        }
                        
                        if (episodes.isNotEmpty()) return episodes
                        
                    } catch (e: Exception) {
                        println("   ⚠️ Não é JSON válido: ${e.message}")
                    }
                }
            }
            
            // Padrão 2: URLs de episódios em scripts
            println("\n   🔍 Procurando URLs de episódios...")
            
            // Padrões comuns de URLs
            val urlPatterns = listOf(
                """["'](/\d+/?)["']""",
                """["'](/assistir/\d+/?)["']""",
                """["'](/episodio/\d+/?)["']""",
                """href\s*=\s*["'](/\d+/?)["']""",
                """data-url\s*=\s*["'](/\d+/?)["']""",
                """data-href\s*=\s*["'](/\d+/?)["']"""
            )
            
            val uniqueUrls = mutableSetOf<String>()
            
            for (pattern in urlPatterns) {
                val regex = Regex(pattern, RegexOption.IGNORE_CASE)
                val matches = regex.findAll(html)
                
                matches.forEach { match ->
                    val url = match.groupValues[1]
                    if (url !in uniqueUrls) {
                        uniqueUrls.add(url)
                        println("   🔗 URL encontrada: $url")
                        
                        // Extrair número do episódio
                        val episodeNum = extractEpisodeNumberFromUrl(url)
                        if (episodeNum > 0) {
                            episodes.add(newEpisode(fixUrl(url)) {
                                this.name = "Episódio $episodeNum"
                                this.episode = episodeNum
                                this.season = 1
                            })
                        }
                    }
                }
            }
            
            println("   📊 URLs únicas encontradas: ${uniqueUrls.size}")
            
        } catch (e: Exception) {
            println("   ❌ Erro nos scripts: ${e.message}")
        }
        
        return episodes
    }
    
    private fun parseJsonEpisode(json: JSONObject, baseUrl: String): Episode? {
        try {
            // Campos possíveis
            val url = json.optString("url", 
                     json.optString("link", 
                     json.optString("href", "")))
            
            val title = json.optString("title", 
                       json.optString("name", 
                       json.optString("episode_title", "")))
            
            val number = json.optInt("number", 
                     json.optInt("episode", 
                     json.optInt("episode_number", 1)))
            
            if (url.isBlank()) return null
            
            val episodeUrl = if (url.startsWith("http")) url else fixUrl(url)
            
            return newEpisode(episodeUrl) {
                this.name = if (title.isNotBlank()) title else "Episódio $number"
                this.episode = number
                this.season = 1
            }
            
        } catch (e: Exception) {
            return null
        }
    }
    
    // Extrair do HTML já renderizado
    private fun extractEpisodesFromRenderedHTML(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        try {
            println("   🏗️ Procurando no HTML renderizado...")
            
            // Estratégia: Procurar por qualquer coisa que pareça episódio
            val possibleSelectors = listOf(
                // Containers
                "#episodes-container",
                ".episodes",
                ".episode-list",
                ".episodes-grid",
                // Itens individuais
                ".episode-item",
                ".boxEP",
                "[class*='episode']",
                "[class*='ep-']",
                // Links específicos
                "a[href*='/episodio/']",
                "a[href*='/assistir/']",
                "a[href^='/']"
            )
            
            for (selector in possibleSelectors) {
                val elements = document.select(selector)
                if (elements.isNotEmpty()) {
                    println("   🔍 Seletor '$selector': ${elements.size} elementos")
                    
                    // Limitar para debug
                    elements.take(5).forEachIndexed { i, element ->
                        println("     $i. ${element.text().take(50)}...")
                    }
                    
                    // Extrair episódios destes elementos
                    val extracted = extractFromElements(elements, baseUrl)
                    if (extracted.isNotEmpty()) {
                        episodes.addAll(extracted) // CORREÇÃO: usar addAll aqui é OK
                        println("   ✅ ${extracted.size} episódios extraídos")
                        break
                    }
                }
            }
            
        } catch (e: Exception) {
            println("   ❌ Erro no HTML: ${e.message}")
        }
        
        return episodes
    }
    
    private fun extractFromElements(elements: org.jsoup.select.Elements, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val processedUrls = mutableSetOf<String>()
        
        try {
            for (element in elements) {
                // Procurar links dentro do elemento
                val links = element.select("a[href]")
                
                for (link in links) {
                    val href = link.attr("href").trim()
                    if (href.isBlank() || href in processedUrls) continue
                    
                    processedUrls.add(href)
                    
                    // Verificar se é episódio
                    val isEpisode = when {
                        href.matches(Regex("""^/\d+/?$""")) -> true
                        href.contains("/episodio/") -> true
                        href.contains("/assistir/") -> true
                        element.classNames().any { it.contains("episode", ignoreCase = true) } -> true
                        else -> false
                    }
                    
                    if (isEpisode) {
                        val episodeNum = extractEpisodeNumberFromUrl(href)
                        val title = link.text().trim().takeIf { it.isNotBlank() } ?: "Episódio $episodeNum"
                        
                        episodes.add(newEpisode(fixUrl(href)) {
                            this.name = title
                            this.episode = episodeNum
                            this.season = 1
                        })
                        
                        println("   📺 Ep $episodeNum: $title")
                    }
                }
            }
            
        } catch (e: Exception) {
            // Ignorar erro
        }
        
        return episodes
    }
    
    private fun extractEpisodeNumberFromUrl(url: String): Int {
        // Padrões de URLs de episódios
        val patterns = listOf(
            Regex("""/(\d+)/?$"""),                    // /123/
            Regex("""/assistir/(\d+)/?"""),            // /assistir/123/
            Regex("""/episodio/(\d+)/?"""),            // /episodio/123/
            Regex("""/ep-(\d+)/?"""),                  // /ep-123/
            Regex("""-episodio-(\d+)""", RegexOption.IGNORE_CASE), // -episodio-123
            Regex("""-(\d+)/?$""")                     // -123/
        )
        
        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null) {
                return match.groupValues[1].toIntOrNull() ?: 1
            }
        }
        
        return 1
    }
    
    private fun debugPageStructure(document: org.jsoup.nodes.Document, html: String) {
        println("\n   🐛 DEBUG DETALHADO:")
        println("   -------------------")
        
        // 1. Ver scripts
        val scripts = document.select("script")
        println("   📜 Scripts na página: ${scripts.size}")
        
        scripts.forEachIndexed { i, script ->
            val content = script.html()
            if (content.contains("episode", ignoreCase = true) || 
                content.contains("episodio", ignoreCase = true)) {
                println("   👉 Script $i contém 'episode' (${content.length} chars)")
                println("   📄 Amostra: ${content.take(100)}...")
            }
        }
        
        // 2. Ver divs importantes
        val divs = document.select("div[class], div[id]")
        println("\n   🏗️ Divs importantes:")
        
        val filteredDivs = divs.filter { div ->
            val className = div.attr("class")
            val id = div.attr("id")
            className.contains("episode", ignoreCase = true) ||
            className.contains("ep-", ignoreCase = true) ||
            id.contains("episode", ignoreCase = true) ||
            id.contains("ep-", ignoreCase = true)
        }
        
        filteredDivs.take(5).forEach { div ->
            println("   🏷️ Classe: '${div.attr("class")}', ID: '${div.attr("id")}'")
            println("   📝 Conteúdo: ${div.text().take(50)}...")
        }
        
        // 3. Links numéricos
        val numericLinks = document.select("a[href]").filter { 
            it.attr("href").matches(Regex("""^/\d+/?$"""))
        }
        println("\n   🔢 Links numéricos: ${numericLinks.size}")
        numericLinks.take(5).forEach { link ->
            println("   🔗 ${link.attr("href")} - '${link.text()}'")
        }
        
        // 4. Amostra do HTML onde tem "episode"
        val episodeIndex = html.indexOf("episode", ignoreCase = true)
        if (episodeIndex > 0) {
            val start = maxOf(0, episodeIndex - 100)
            val end = minOf(html.length, episodeIndex + 200)
            println("\n   📍 Contexto de 'episode' no HTML:")
            println("   ${html.substring(start, end)}")
        }
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
