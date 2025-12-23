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
        
        // LISTA COMPLETA DE GÊNEROS
        private val ALL_GENRES = listOf(
            "/generos/18" to "+18",
            "/generos/china" to "China",
            "/generos/aventura" to "Aventura",
            "/generos/artes-marciais" to "Artes Marciais",
            "/generos/acao" to "Ação",
            "/generos/comedia" to "Comédia",
            "/generos/escolar" to "Escolar",
            "/generos/ecchi" to "Ecchi",
            "/generos/drama" to "Drama",
            "/generos/demonios" to "Demônios",
            "/generos/crime" to "Crime",
            "/generos/ficcao-cientifica" to "Ficção Científica",
            "/generos/fantasia" to "Fantasia",
            "/generos/esporte" to "Esporte",
            "/generos/familia" to "Família",
            "/generos/harem" to "Harém",
            "/generos/guerra" to "Guerra",
            "/generos/gore" to "Gore"
        )
    }

    init {
        println("🎬 GOYABU: Plugin inicializado - ${ALL_GENRES.size} gêneros")
    }

    override val mainPage = mainPageOf(
        *ALL_GENRES.map { (path, name) -> 
            "$mainUrl$path" to name 
        }.toTypedArray()
    )

    // ============ EXTRACTION PARA LISTAGEM ============
    private fun Element.toSearchResponse(): AnimeSearchResponse? {
        val href = attr("href") ?: return null
        
        // FILTRAR: Só queremos séries, não episódios individuais
        val isEpisodePage = href.matches(Regex("""^/\d+/?$"""))
        val isAnimePage = href.contains("/anime/")
        if (!isAnimePage || isEpisodePage) return null

        // TÍTULO
        val titleElement = selectFirst(".title, .hidden-text")
        val rawTitle = titleElement?.text()?.trim() ?: return null
        
        // THUMBNAIL
        val posterUrl = extractPosterUrl()
        
        // SCORE
        val scoreElement = selectFirst(".rating-score-box, .rating")
        val scoreText = scoreElement?.text()?.trim()
        val score = parseScore(scoreText)
        
        // DUBLADO (badge)
        val hasDubBadge = selectFirst(".audio-box.dublado, .dublado") != null
        val hasSub = true

        if (rawTitle.isBlank()) return null

        return newAnimeSearchResponse(rawTitle, fixUrl(href)) {
            this.posterUrl = posterUrl
            this.type = TvType.Anime
            this.score = score
            addDubStatus(dubExist = hasDubBadge, subExist = hasSub)
        }
    }
    
    private fun Element.extractPosterUrl(): String? {
        // 1. Background-image no .coverImg
        selectFirst(".coverImg")?.attr("style")?.let { style ->
            val regex = Regex("""url\(['"]?([^'"()]+)['"]?\)""")
            regex.find(style)?.groupValues?.get(1)?.let { url ->
                return fixUrl(url)
            }
        }
        // 2. data-thumb
        selectFirst("[data-thumb]")?.attr("data-thumb")?.let { url ->
            return fixUrl(url)
        }
        // 3. img src normal
        selectFirst("img[src]")?.attr("src")?.let { url ->
            return fixUrl(url)
        }
        return null
    }
    
    private fun parseScore(text: String?): Score? {
        if (text.isNullOrBlank()) return null
        val regex = Regex("""(\d+\.?\d*)""")
        val match = regex.find(text)
        return match?.groupValues?.get(1)?.toFloatOrNull()?.let { Score.from10(it) }
    }

    // ============ GET MAIN PAGE ============
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return loadingMutex.withLock {
            try {
                println("🎬 GOYABU: '${request.name}' - Página $page")
                val url = if (page > 1) "${request.data}page/$page/" else request.data
                val document = app.get(url, timeout = 20).document
                
                // Procurar séries
                val elements = document.select("article a, .boxAN a, a[href*='/anime/']")
                println("📊 ${elements.size} links encontrados em '${request.name}'")
                
                val homeItems = elements.mapNotNull { it.toSearchResponse() }
                    .distinctBy { it.url }
                    .take(30)
                
                // Sem paginação por enquanto
                val hasNextPage = false
                
                newHomePageResponse(request.name, homeItems, hasNextPage)
            } catch (e: Exception) {
                println("❌ ERRO: ${request.name} - ${e.message}")
                newHomePageResponse(request.name, emptyList(), false)
            }
        }
    }

    // ============ SEARCH ============
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

    // ============ LOAD (página do anime) ============
    override suspend fun load(url: String): LoadResponse {
        return try {
            println("\n" + "=".repeat(60))
            println("🎬 GOYABU: Carregando com extração JavaScript: $url")
            println("=".repeat(60))
            
            // Carregar página
            val document = app.get(url, timeout = 30).document
            
            // TÍTULO
            val title = document.selectFirst("h1.text-hidden, h1")?.text()?.trim() ?: "Sem Título"
            println("📌 Título: $title")
            
            // POSTER
            val poster = document.selectFirst(".streamer-poster img, .cover")?.attr("src")
                ?.takeIf { it.isNotBlank() }
                ?.let { fixUrl(it) }
            println("🖼️ Poster: ${poster != null}")
            
            // SINOPSE
            val synopsis = document.selectFirst(".streamer-sinopse")?.text()?.trim()
                ?.replace("ler mais", "")
                ?.trim()
                ?: "Sinopse não disponível."
            println("📖 Sinopse (${synopsis.length} chars)")
            
            // ANO
            val yearElement = document.selectFirst("li#year")
            val year = yearElement?.text()?.trim()?.toIntOrNull()
            println("📅 Ano: $year")
            
            // GÊNEROS
            val genres = mutableListOf<String>()
            document.select(".filter-btn.btn-style, a[href*='/generos/']").forEach { element ->
                element.text().trim().takeIf { it.isNotBlank() }?.let { 
                    if (it.length > 1 && !genres.contains(it)) genres.add(it) 
                }
            }
            println("🏷️ Gêneros: ${genres.size}")
            
            // SCORE
            val scoreElement = document.selectFirst(".rating-total, .rating-score")
            val scoreText = scoreElement?.text()?.trim()
            val score = parseScore(scoreText)
            println("⭐ Score: $scoreText")
            
            // DUBLADO/LEGENDADO
            val isDubbed = title.contains("dublado", ignoreCase = true)
            println("🎭 Dublado: $isDubbed")
            
            // EPISÓDIOS
            println("\n🔍 BUSCANDO EPISÓDIOS...")
            var episodes = extractEpisodesFromJavaScript(document, url)
            
            if (episodes.isEmpty()) {
                println("⚠️ Nenhum episódio encontrado no JavaScript, tentando métodos alternativos...")
                val fallbackEpisodes = extractEpisodesFallback(document, url)
                if (fallbackEpisodes.isNotEmpty()) {
                    println("✅ Encontrados ${fallbackEpisodes.size} episódios via fallback")
                    // CORREÇÃO: Não usar addAll, usar operador +
                    episodes = episodes + fallbackEpisodes
                }
            } else {
                println("✅ ENCONTRADOS ${episodes.size} EPISÓDIOS NO JAVASCRIPT!")
            }
            
            // Ordenar episódios
            val sortedEpisodes = episodes.sortedBy { it.episode }
            
            // CRIAR RESPOSTA
            val response = newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.year = year
                this.plot = synopsis
                this.tags = genres
                this.score = score
                
                if (sortedEpisodes.isNotEmpty()) {
                    val dubStatus = if (isDubbed) DubStatus.Dubbed else DubStatus.Subbed
                    addEpisodes(dubStatus, sortedEpisodes)
                    
                    println("\n✅ SUCESSO! ${sortedEpisodes.size} EPISÓDIOS:")
                    sortedEpisodes.take(5).forEach { ep ->
                        println("   📺 Ep ${ep.episode}: ${ep.name} -> ${ep.data}")
                    }
                    if (sortedEpisodes.size > 5) {
                        println("   ... e mais ${sortedEpisodes.size - 5} episódios")
                    }
                } else {
                    println("\n⚠️ NENHUM EPISÓDIO ENCONTRADO")
                    println("📝 Tente acessar: $url e verifique se há episódios na página")
                }
            }
            
            println("\n" + "=".repeat(60))
            println("🎬 GOYABU: Load concluído para '$title'")
            println("=".repeat(60) + "\n")
            
            response
            
        } catch (e: Exception) {
            println("❌ ERRO no load: ${e.message}")
            newAnimeLoadResponse("Erro", url, TvType.Anime) {
                this.plot = "Erro: ${e.message}"
            }
        }
    }
    
    // ============ EXTRATOR DE EPISÓDIOS DO JAVASCRIPT ============
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
            
        } catch (e: Exception) {
            println("❌ Erro ao extrair episódios do JavaScript: ${e.message}")
        }
        
        return episodes
    }
    
    // ============ FALLBACK PARA EXTRATOR DE EPISÓDIOS ============
    private fun extractEpisodesFallback(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        println("🔍 Fallback: Procurando episódios via HTML...")
        
        // ESTRATÉGIA PRINCIPAL: Procurar por .episode-item dentro do container
        val episodeItems = document.select("#episodes-container .episode-item, .episode-item")
        
        if (episodeItems.isEmpty()) {
            println("   ⚠️ Nenhum .episode-item encontrado")
            // Fallback: procurar por .boxEP diretamente
            val boxEPs = document.select(".boxEP.grid-view, .boxEP")
            println("   🔄 Fallback: ${boxEPs.size} .boxEP encontrados")
            
            boxEPs.forEachIndexed { index, boxEP ->
                try {
                    extractEpisodeFromBoxEP(boxEP, index, episodes)
                } catch (e: Exception) {
                    println("   ❌ Erro no boxEP ${index + 1}: ${e.message}")
                }
            }
        } else {
            println("   ✅ ${episodeItems.size} .episode-item encontrados")
            
            episodeItems.forEachIndexed { index, episodeItem ->
                try {
                    // Dentro do .episode-item, procurar o .boxEP
                    val boxEP = episodeItem.selectFirst(".boxEP") ?: episodeItem
                    extractEpisodeFromBoxEP(boxEP, index, episodes)
                } catch (e: Exception) {
                    println("   ❌ Erro no episode-item ${index + 1}: ${e.message}")
                }
            }
        }
        
        // Se ainda não encontrou nada, procurar links diretos
        if (episodes.isEmpty()) {
            println("   🔍 Procurando links diretos de episódios...")
            val episodeLinks = document.select("a[href]").filter { 
                val href = it.attr("href")
                href.matches(Regex("""^/\d+/$""")) || href.contains("/episodio/")
            }
            
            episodeLinks.forEachIndexed { index, link ->
                try {
                    val href = link.attr("href").trim()
                    if (href.isBlank()) return@forEachIndexed
                    
                    // Extrair número do episódio
                    val episodeNum = extractEpisodeNumberFromHref(href, index + 1)
                    
                    episodes.add(newEpisode(fixUrl(href)) {
                        this.name = "Episódio $episodeNum"
                        this.episode = episodeNum
                        this.season = 1
                    })
                    
                    println("   🔗 Link direto Ep $episodeNum: $href")
                } catch (e: Exception) {
                    println("   ⚠️ Erro no link ${index + 1}: ${e.message}")
                }
            }
        }
        
        println("   📊 Total de episódios via fallback: ${episodes.size}")
        return episodes
    }
    
    private fun extractEpisodeFromBoxEP(boxEP: Element, index: Int, episodes: MutableList<Episode>) {
        val linkElement = boxEP.selectFirst("a[href]") ?: return
        val href = linkElement.attr("href").trim()
        if (href.isBlank()) return
        
        // NÚMERO DO EPISÓDIO
        var episodeNum = index + 1
        
        // 1. Tentar do texto "Episódio X"
        val epTypeElement = linkElement.selectFirst(".ep-type b")
        epTypeElement?.text()?.trim()?.let { text ->
            val regex = Regex("""Epis[oó]dio\s+(\d+)""", RegexOption.IGNORE_CASE)
            val match = regex.find(text)
            match?.groupValues?.get(1)?.toIntOrNull()?.let { episodeNum = it }
        }
        
        // 2. Tentar data-episode-number
        boxEP.parent()?.attr("data-episode-number")?.toIntOrNull()?.let { episodeNum = it }
        
        // 3. Tentar da URL
        episodeNum = extractEpisodeNumberFromHref(href, episodeNum)
        
        // THUMBNAIL
        val thumb = linkElement.selectFirst(".coverImg")?.attr("style")?.let { style ->
            val regex = Regex("""url\(['"]?([^'"()]+)['"]?\)""")
            regex.find(style)?.groupValues?.get(1)?.replace("&quot;", "")?.trim()
        }?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
        
        // NOME DO EPISÓDIO
        val episodeTitle = epTypeElement?.text()?.trim() ?: "Episódio $episodeNum"
        
        episodes.add(newEpisode(fixUrl(href)) {
            this.name = episodeTitle
            this.episode = episodeNum
            this.season = 1
            this.posterUrl = thumb
        })
        
        println("   ✅ Ep $episodeNum: $episodeTitle -> $href")
    }
    
    // ============ FUNÇÕES AUXILIARES ============
    private fun extractEpisodeNumberFromHref(href: String, default: Int): Int {
        // Tentar extrair número da URL
        val regex1 = Regex("""/(\d+)/?$""")
        val regex2 = Regex("""/episodio[-_]?(\d+)/?$""", RegexOption.IGNORE_CASE)
        
        regex1.find(href)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        regex2.find(href)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        
        return default
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
            else -> "$mainUrl/$episodeNumber"
        }
    }

    // ============ LOAD LINKS (AGORA COM SUPORTE AO BLOGGER) ============
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("\n🎬 GOYABU: Iniciando extração de links para: $data")
        
        return try {
            // Usar o extrator específico do Blogger
            val success = GoyabuBloggerExtractor.extractVideoLinks(data, "Goyabu", callback)
            
            if (success) {
                println("✅ GOYABU: Extração concluída com sucesso!")
                return true
            } else {
                println("⚠️ GOYABU: Extrator Blogger falhou, tentando métodos alternativos...")
                
                // Fallback: Tentar extrair diretamente da página
                val response = app.get(data)
                val html = response.text
                
                // Procurar por URLs de vídeo
                val videoPatterns = listOf(
                    """https?://[^"'\s<>]*\.(?:mp4|m3u8|mkv|webm|avi)[^"'\s<>]*""".toRegex(),
                    """(https?://[^"'\s<>]*\.googlevideo\.com/[^"'\s<>]+)""".toRegex(),
                    """src\s*:\s*['"](https?://[^"']+)['"]""".toRegex(),
                    """url\s*:\s*['"](https?://[^"']+)['"]""".toRegex(),
                    """file\s*:\s*['"](https?://[^"']+)['"]""".toRegex()
                )
                
                var found = false
                for (pattern in videoPatterns) {
                    val matches = pattern.findAll(html).toList()
                    if (matches.isNotEmpty()) {
                        for (match in matches) {
                            val videoUrl = match.groupValues[1].takeIf { it.startsWith("http") } ?: continue
                            
                            if (videoUrl.contains(".mp4") || videoUrl.contains(".m3u8") || videoUrl.contains("googlevideo")) {
                                val extractorLink = newExtractorLink(
                                    source = "Goyabu",
                                    name = "Vídeo",
                                    url = videoUrl,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = data
                                    this.quality = 720
                                    this.headers = mapOf(
                                        "Referer" to data,
                                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                                    )
                                }
                                
                                callback(extractorLink)
                                found = true
                                println("✅ URL de vídeo encontrada: ${videoUrl.take(80)}...")
                            }
                        }
                    }
                }
                
                if (!found) {
                    println("❌ GOYABU: Nenhum link de vídeo encontrado")
                }
                
                found
            }
            
        } catch (e: Exception) {
            println("❌ GOYABU: Erro na extração de links: ${e.message}")
            e.printStackTrace()
            false
        }
    }
}
