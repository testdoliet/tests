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
    override val usesWebView = false // Não precisa de WebView por enquanto

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
        if (!href.contains("/anime/")) return null

        val titleElement = selectFirst(".title, .hidden-text, h3")
        val rawTitle = titleElement?.text()?.trim() ?: return null
        
        val posterUrl = selectFirst("img[src]")?.attr("src")?.let { fixUrl(it) }

        return newAnimeSearchResponse(rawTitle, fixUrl(href)) {
            this.posterUrl = posterUrl
            this.type = TvType.Anime
            this.dubStatus = if (rawTitle.contains("dublado", true)) 
                EnumSet.of(DubStatus.Dubbed) 
            else 
                EnumSet.of(DubStatus.Subbed)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return loadingMutex.withLock {
            try {
                val url = if (page > 1) "${request.data}page/$page/" else request.data
                val document = app.get(url).document
                
                val elements = document.select("article a, .boxAN a, a[href*='/anime/']")
                val homeItems = elements.mapNotNull { it.toSearchResponse() }
                    .distinctBy { it.url }
                    .take(20)
                
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
            val document = app.get(searchUrl).document
            
            document.select("article a, .boxAN a, a[href*='/anime/']")
                .mapNotNull { it.toSearchResponse() }
                .distinctBy { it.url }
                .take(20)
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        return try {
            println("📦 Carregando anime: $url")
            
            // 1. Carregar a página
            val document = app.get(url).document
            
            // 2. Metadados básicos
            val title = document.selectFirst("h1")?.text()?.trim() ?: "Sem Título"
            val poster = document.selectFirst(".streamer-poster img, .cover")?.attr("src")
                ?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
            val synopsis = document.selectFirst(".streamer-sinopse")?.text()?.trim()
                ?.replace("ler mais", "")?.trim() ?: "Sinopse não disponível."
            
            println("✅ Título: $title")
            println("🎭 Dublado: ${title.contains("dublado", ignoreCase = true)}")
            
            // 3. EXTRAIR EPISÓDIOS - Método principal
            println("🔍 Extraindo episódios...")
            val episodes = extractEpisodesFromPage(document, url)
            
            if (episodes.isEmpty()) {
                println("⚠️ Nenhum episódio encontrado. Tentando método alternativo...")
                return createFallbackResponse(title, url, poster, synopsis)
            }
            
            // 4. Ordenar episódios
            val sortedEpisodes = episodes.sortedBy { it.episode }
            println("✅ Encontrados ${sortedEpisodes.size} episódios")
            
            // 5. Criar resposta
            val isDubbed = title.contains("dublado", ignoreCase = true)
            val dubStatus = if (isDubbed) DubStatus.Dubbed else DubStatus.Subbed
            
            newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.plot = synopsis
                addEpisodes(dubStatus, sortedEpisodes)
                
                // Mostrar alguns episódios no log
                sortedEpisodes.take(3).forEach { ep ->
                    println("📺 Ep ${ep.episode}: ${ep.name}")
                }
            }
            
        } catch (e: Exception) {
            println("❌ Erro: ${e.message}")
            newAnimeLoadResponse("Erro", url, TvType.Anime) {
                this.plot = "Erro: ${e.message}"
            }
        }
    }
    
    private fun extractEpisodesFromPage(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        try {
            // MÉTODO 1: Buscar no JavaScript (allEpisodes)
            println("🔍 Buscando array allEpisodes no JavaScript...")
            val scripts = document.select("script")
            
            for (script in scripts) {
                val content = script.html()
                if (content.contains("allEpisodes") || content.contains("episodes")) {
                    println("✅ Script com episódios encontrado!")
                    
                    // Padrões para extrair o array JSON
                    val patterns = listOf(
                        Regex("""allEpisodes\s*=\s*\[(.*?)\];""", RegexOption.DOTALL),
                        Regex("""episodes\s*:\s*\[(.*?)\]""", RegexOption.DOTALL),
                        Regex("""const\s+episodes\s*=\s*\[(.*?)\];""", RegexOption.DOTALL)
                    )
                    
                    for (pattern in patterns) {
                        val match = pattern.find(content)
                        if (match != null) {
                            val jsonData = match.groupValues[1]
                            println("📊 Dados JSON encontrados (${jsonData.length} caracteres)")
                            
                            // Extrair objetos individuais
                            val jsonObjects = extractJsonObjects(jsonData)
                            println("📈 ${jsonObjects.size} objetos JSON extraídos")
                            
                            jsonObjects.forEachIndexed { index, obj ->
                                // Extrair número do episódio
                                val epNum = extractJsonValue(obj, "episodio", "episode", "number")
                                    ?.toIntOrNull() ?: (index + 1)
                                
                                // Extrair ID ou link
                                val epId = extractJsonValue(obj, "id", "link", "url") ?: ""
                                
                                // Extrair título
                                val epTitle = extractJsonValue(obj, "title", "name") ?: "Episódio $epNum"
                                
                                // Construir URL
                                val epUrl = buildEpisodeUrl(epId, epNum, baseUrl)
                                
                                episodes.add(newEpisode(epUrl) {
                                    this.name = epTitle
                                    this.episode = epNum
                                    this.season = 1
                                })
                                
                                if (index < 3) {
                                    println("   Ep $epNum: $epTitle -> $epUrl")
                                }
                            }
                            
                            if (episodes.isNotEmpty()) {
                                println("✅ ${episodes.size} episódios extraídos do JavaScript")
                                return episodes
                            }
                        }
                    }
                }
            }
            
            // MÉTODO 2: Buscar links numéricos
            println("🔍 Buscando links numéricos...")
            document.select("a[href]").forEach { link ->
                val href = link.attr("href")
                if (href.matches(Regex("""^/\d+/?$"""))) {
                    val epNum = href.replace("/", "").toIntOrNull() ?: return@forEach
                    val epTitle = link.text().trim().takeIf { it.isNotBlank() } ?: "Episódio $epNum"
                    val epUrl = fixUrl(href)
                    
                    episodes.add(newEpisode(epUrl) {
                        this.name = epTitle
                        this.episode = epNum
                        this.season = 1
                    })
                }
            }
            
            // MÉTODO 3: Buscar links com /assistir/ ou /episodio/
            if (episodes.isEmpty()) {
                println("🔍 Buscando links /assistir/ ou /episodio/...")
                document.select("a[href*='/assistir/'], a[href*='/episodio/']").forEach { link ->
                    val href = link.attr("href")
                    val epNum = extractNumberFromUrl(href) ?: (episodes.size + 1)
                    val epTitle = link.text().trim().takeIf { it.isNotBlank() } ?: "Episódio $epNum"
                    val epUrl = fixUrl(href)
                    
                    episodes.add(newEpisode(epUrl) {
                        this.name = epTitle
                        this.episode = epNum
                        this.season = 1
                    })
                }
            }
            
        } catch (e: Exception) {
            println("❌ Erro na extração: ${e.message}")
        }
        
        return episodes
    }
    
    private fun extractJsonObjects(jsonArray: String): List<String> {
        val objects = mutableListOf<String>()
        var currentObject = StringBuilder()
        var braceCount = 0
        var inString = false
        var escapeNext = false
        
        for (char in jsonArray) {
            when {
                escapeNext -> {
                    currentObject.append(char)
                    escapeNext = false
                }
                char == '\\' -> {
                    currentObject.append(char)
                    escapeNext = true
                }
                char == '"' -> {
                    currentObject.append(char)
                    inString = !inString
                }
                !inString && char == '{' -> {
                    if (braceCount == 0) {
                        currentObject = StringBuilder("{")
                    } else {
                        currentObject.append(char)
                    }
                    braceCount++
                }
                !inString && char == '}' -> {
                    braceCount--
                    currentObject.append(char)
                    if (braceCount == 0) {
                        objects.add(currentObject.toString())
                    }
                }
                else -> {
                    if (braceCount > 0) currentObject.append(char)
                }
            }
        }
        
        return objects.filter { it.length > 10 } // Filtrar objetos muito pequenos
    }
    
    private fun extractJsonValue(json: String, vararg keys: String): String? {
        for (key in keys) {
            // Padrão: "key": "value"
            val pattern1 = Regex(""""$key"\s*:\s*"([^"]*)"""")
            val match1 = pattern1.find(json)
            if (match1 != null) return match1.groupValues[1]
            
            // Padrão: "key": number
            val pattern2 = Regex(""""$key"\s*:\s*(\d+)""")
            val match2 = pattern2.find(json)
            if (match2 != null) return match2.groupValues[1]
        }
        return null
    }
    
    private fun extractNumberFromUrl(url: String): Int? {
        val patterns = listOf(
            Regex("""/(\d+)/?$"""),
            Regex("""/assistir/(\d+)/?"""),
            Regex("""/episodio/(\d+)/?"""),
            Regex("""-(\d+)/?$"""),
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
    
    private fun buildEpisodeUrl(idOrPath: String, episodeNumber: Int, baseUrl: String): String {
        return when {
            idOrPath.matches(Regex("""^\d+$""")) -> "$mainUrl/$idOrPath"
            idOrPath.startsWith("/") -> "$mainUrl$idOrPath"
            idOrPath.startsWith("http") -> idOrPath
            idOrPath.isNotBlank() -> fixUrl(idOrPath)
            else -> "$baseUrl/$episodeNumber"
        }
    }
    
    private fun createFallbackResponse(title: String, url: String, poster: String?, synopsis: String): LoadResponse {
        // Criar alguns episódios fallback
        val fallbackEpisodes = (1..12).map { epNum ->
            val epUrl = if (url.endsWith("/")) "${url}episodio-$epNum" else "$url/episodio-$epNum"
            newEpisode(epUrl) {
                this.name = "Episódio $epNum"
                this.episode = epNum
                this.season = 1
            }
        }
        
        val isDubbed = title.contains("dublado", ignoreCase = true)
        val dubStatus = if (isDubbed) DubStatus.Dubbed else DubStatus.Subbed
        
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = "$synopsis\n\n⚠️ Nota: Episódios criados automaticamente."
            addEpisodes(dubStatus, fallbackEpisodes)
            println("📺 Criados 12 episódios fallback")
        }
    }
}
