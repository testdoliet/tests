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
            println("🎬 GOYABU - EXTRAINDO DO JAVASCRIPT")
            println("URL: $url")
            println("=".repeat(60))
            
            // 1. Carregar a página
            val document = app.get(url, timeout = 30).document
            
            // 2. Metadados básicos
            val title = document.selectFirst("h1.text-hidden, h1")?.text()?.trim() ?: "Sem Título"
            val poster = document.selectFirst(".streamer-poster img, .cover")?.attr("src")
                ?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
            val synopsis = document.selectFirst(".streamer-sinopse")?.text()?.trim()
                ?.replace("ler mais", "")?.trim() ?: "Sinopse não disponível."
            
            println("📌 Título: $title")
            
            // 3. EXTRAIR EPISÓDIOS DO ARRAY JAVASCRIPT
            println("\n🔍 PROCURANDO ARRAY 'allEpisodes' NO JAVASCRIPT...")
            val episodes = extractEpisodesFromJavaScript(document, url)
            
            if (episodes.isNotEmpty()) {
                println("✅ ENCONTRADOS ${episodes.size} EPISÓDIOS NO JAVASCRIPT!")
            } else {
                println("⚠️ Nenhum episódio encontrado no JavaScript")
            }
            
            // 4. Ordenar episódios
            val sortedEpisodes = episodes.sortedBy { it.episode }
            
            // 5. Criar resposta
            val response = newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.plot = synopsis
                
                if (sortedEpisodes.isNotEmpty()) {
                    addEpisodes(DubStatus.Subbed, sortedEpisodes)
                    println("\n✅ SUCESSO! ${sortedEpisodes.size} EPISÓDIOS:")
                    
                    sortedEpisodes.take(10).forEach { ep ->
                        println("   Ep ${ep.episode}: ${ep.name} (${ep.data})")
                    }
                    if (sortedEpisodes.size > 10) {
                        println("   ... e mais ${sortedEpisodes.size - 10} episódios")
                    }
                } else {
                    println("\n⚠️ NENHUM EPISÓDIO ENCONTRADO")
                    println("📝 Tente acessar: $url e verifique se há episódios na página")
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
    
    private fun extractEpisodesFromJavaScript(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        try {
            // Procurar por todos os scripts na página
            val scripts = document.select("script")
            println("📊 Encontrados ${scripts.size} scripts na página")
            
            for ((index, script) in scripts.withIndex()) {
                val scriptContent = script.html()
                
                // Verificar se este script contém o array de episódios
                if (scriptContent.contains("allEpisodes") || 
                    scriptContent.contains("\"episodio\"") || 
                    scriptContent.contains("\"id\"")) {
                    
                    println("\n🔍 Analisando script #$index...")
                    
                    // MÉTODO 1: Extrair array completo allEpisodes = [...]
                    if (scriptContent.contains("allEpisodes")) {
                        println("✅ Encontrado 'allEpisodes' no script")
                        
                        // Padrão: allEpisodes = [ {...}, {...}, ... ]
                        // Não usar DOTALL, usar abordagem alternativa
                        val arrayContent = extractArrayContent(scriptContent, "allEpisodes")
                        
                        if (arrayContent.isNotBlank()) {
                            println("📦 Array extraído (${arrayContent.length} caracteres)")
                            
                            // Extrair objetos individuais do array
                            val episodeObjects = extractJsonObjects(arrayContent)
                            println("📈 ${episodeObjects.size} objetos encontrados no array")
                            
                            // Processar cada objeto
                            episodeObjects.forEachIndexed { objIndex, jsonObj ->
                                try {
                                    val epNumber = extractValueFromJson(jsonObj, "episodio", "episode", "number")?.toIntOrNull() ?: (objIndex + 1)
                                    val epId = extractValueFromJson(jsonObj, "id") ?: ""
                                    val epTitle = extractValueFromJson(jsonObj, "title", "name") ?: "Episódio $epNumber"
                                    
                                    // Construir URL do episódio
                                    val epUrl = buildEpisodeUrl(epId, epNumber)
                                    
                                    // Usar newEpisode em vez do construtor depreciado
                                    episodes.add(newEpisode(epUrl) {
                                        this.name = epTitle
                                        this.episode = epNumber
                                        this.season = 1
                                    })
                                    
                                    if (objIndex < 3) { // Mostrar apenas os primeiros 3 para debug
                                        println("   📺 Ep $epNumber: $epTitle -> $epUrl")
                                    }
                                    
                                } catch (e: Exception) {
                                    println("   ❌ Erro ao processar objeto $objIndex: ${e.message}")
                                }
                            }
                            
                            if (episodes.isNotEmpty()) {
                                return episodes // Retorna imediatamente se encontrou
                            }
                        }
                    }
                    
                    // MÉTODO 2: Extrair episódios individuais mesmo sem o array completo
                    if (episodes.isEmpty()) {
                        println("🔍 Tentando extrair episódios individualmente...")
                        
                        // Procurar por padrões de objeto individual: {"id":"123","episodio":"1",...}
                        val episodePattern = Regex("""\{"id":"(\d+)","episodio":"(\d+)".*?\}""")
                        val episodeMatches = episodePattern.findAll(scriptContent)
                        
                        var matchCount = 0
                        episodeMatches.forEach { match ->
                            matchCount++
                            try {
                                val id = match.groupValues.getOrNull(1) ?: ""
                                val epNum = match.groupValues.getOrNull(2)?.toIntOrNull() ?: matchCount
                                
                                if (id.isNotBlank()) {
                                    // Usar newEpisode em vez do construtor depreciado
                                    episodes.add(newEpisode("$mainUrl/$id") {
                                        this.name = "Episódio $epNum"
                                        this.episode = epNum
                                        this.season = 1
                                    })
                                    
                                    if (matchCount <= 3) {
                                        println("   📺 Ep $epNum -> $mainUrl/$id")
                                    }
                                }
                            } catch (e: Exception) {
                                println("   ❌ Erro no match $matchCount: ${e.message}")
                            }
                        }
                        
                        if (matchCount > 0) {
                            println("✅ Encontrados $matchCount episódios via padrão individual")
                        }
                    }
                }
            }
            
            // MÉTODO 3: Se ainda não encontrou, procurar por dados-episodes
            if (episodes.isEmpty()) {
                println("🔍 Procurando por atributos data-episodes...")
                val episodeContainers = document.select("[data-episodes], [data-episode]")
                
                episodeContainers.forEach { container ->
                    val dataEpisodes = container.attr("data-episodes")
                    if (dataEpisodes.isNotBlank()) {
                        println("✅ Encontrado data-episodes: ${dataEpisodes.take(100)}...")
                        // Processar similar ao array
                    }
                }
            }
            
        } catch (e: Exception) {
            println("❌ Erro ao extrair episódios do JavaScript: ${e.message}")
        }
        
        return episodes
    }
    
    private fun extractArrayContent(scriptContent: String, arrayName: String): String {
        // Encontrar o início do array
        val startIndex = scriptContent.indexOf("$arrayName = [")
        if (startIndex == -1) return ""
        
        var braceCount = 0
        var inString = false
        var escapeNext = false
        var i = startIndex + arrayName.length + 3 // Pular "allEpisodes = ["
        
        while (i < scriptContent.length) {
            val char = scriptContent[i]
            
            when {
                escapeNext -> {
                    escapeNext = false
                }
                char == '\\' -> {
                    escapeNext = true
                }
                char == '"' -> {
                    inString = !inString
                }
                !inString && char == '[' -> {
                    braceCount++
                }
                !inString && char == ']' -> {
                    braceCount--
                    if (braceCount == 0) {
                        return scriptContent.substring(startIndex + arrayName.length + 3, i)
                    }
                }
            }
            i++
        }
        
        return ""
    }
    
    private fun extractJsonObjects(jsonArray: String): List<String> {
        val objects = mutableListOf<String>()
        var depth = 0
        var currentObject = StringBuilder()
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
                    if (depth == 0) {
                        currentObject = StringBuilder("{")
                    } else {
                        currentObject.append(char)
                    }
                    depth++
                }
                !inString && char == '}' -> {
                    depth--
                    currentObject.append(char)
                    if (depth == 0) {
                        objects.add(currentObject.toString())
                    }
                }
                else -> {
                    if (depth > 0) currentObject.append(char)
                }
            }
        }
        
        return objects
    }
    
    private fun extractValueFromJson(json: String, vararg keys: String): String? {
        for (key in keys) {
            // Padrão: "key": "value"
            val pattern1 = Regex(""""$key"\s*:\s*"([^"]*)"""")
            val match1 = pattern1.find(json)
            if (match1 != null) return match1.groupValues.getOrNull(1)
            
            // Padrão: "key": number
            val pattern2 = Regex(""""$key"\s*:\s*(\d+)""")
            val match2 = pattern2.find(json)
            if (match2 != null) return match2.groupValues.getOrNull(1)
        }
        return null
    }
    
    private fun buildEpisodeUrl(idOrPath: String, episodeNumber: Int): String {
        return when {
            idOrPath.matches(Regex("""^\d+$""")) -> "$mainUrl/$idOrPath"
            idOrPath.startsWith("/") -> "$mainUrl$idOrPath"
            idOrPath.startsWith("http") -> idOrPath
            idOrPath.isNotBlank() -> fixUrl(idOrPath)
            else -> "$mainUrl/$episodeNumber" // Fallback
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Não implementado por enquanto
        return false
    }
}
