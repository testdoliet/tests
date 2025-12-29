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

        // ✅ EM USO: Lista de gêneros para página principal
        private val ALL_GENRES = listOf(
            "/generos/aventura" to "Aventura",
            "/generos/acao" to "Ação",
            "/generos/comedia" to "Comédia",
            "/generos/escolar" to "Escolar",
            "/generos/drama" to "Drama",
            "/generos/demonios" to "Demônios",
            "/generos/crime" to "Crime",
            "/generos/ficcao-cientifica" to "Ficção Científica",
            "/generos/fantasia" to "Fantasia",
            "/generos/esporte" to "Esporte",
            "/generos/harem" to "Harém",
            "/generos/guerra" to "Guerra"
        )

        // ✅ EM USO: Padrões para limpar títulos
        private val TITLE_CLEANUP_PATTERNS = listOf(
            "(?i)\\s*\\(dublado\\)".toRegex(),
            "(?i)\\s*\\(legendado\\)".toRegex(),
            "(?i)\\s*\\(\\d{4}\\)".toRegex(),
            "(?i)\\s*dublado\\s*$".toRegex(),
            "(?i)\\s*legendado\\s*$".toRegex(),
            "(?i)\\s*online\\s*$".toRegex(),
            "(?i)\\s*assistir\\s*".toRegex(),
            "(?i)\\s*anime\\s*$".toRegex(),
            "(?i)\\s*-\\s*todos os epis[oó]dios".toRegex(),
            "(?i)\\s*-\\s*completo".toRegex(),
            "(?i)\\s*\\|.*".toRegex()
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

    // ✅ EM USO: Limpar títulos
    private fun cleanTitle(dirtyTitle: String): String {
        println("🧼 cleanTitle: Chamada")
        var clean = dirtyTitle.trim()
        TITLE_CLEANUP_PATTERNS.forEach { pattern ->
            clean = pattern.replace(clean, "")
        }
        clean = clean.replace(Regex("\\s+"), " ").trim()
        return if (clean.isBlank()) dirtyTitle else clean
    }

    // ✅ EM USO: Limpar sinopse
    private fun cleanSynopsis(dirtySynopsis: String): String {
        println("📖 cleanSynopsis: Chamada")
        var clean = dirtySynopsis.trim()
        
        if (clean.isBlank()) {
            return "Sinopse não disponível."
        }
        
        // Remover frases inteiras que contenham palavras-chave
        val phrasesToRemove = listOf(
            "Assistir.*?Online",
            "Todos os Episodios.*?Online", 
            "Anime.*?Completo",
            "assistir.*?online",
            "todos os episodios.*?online",
            "anime.*?completo",
            "Spy x Family.*?Online",
            "Spy.*?Family.*?Online"
        )
        
        phrasesToRemove.forEach { phrasePattern ->
            val regex = Regex("""[^.!?]*$phrasePattern[^.!?]*([.!?]|\.\.\.)?\s*""", RegexOption.IGNORE_CASE)
            clean = regex.replace(clean, "")
        }
        
        // Remover frases que terminam com ... e estão incompletas
        clean = clean.replace(Regex("""[^.!?]*\.\.\.\s*$"""), "")
        
        // Remover repetições de frases inteiras
        val sentences = clean.split(Regex("""[.!?]+""")).map { it.trim() }.filter { it.isNotBlank() }
        val uniqueSentences = mutableListOf<String>()
        
        sentences.forEach { sentence ->
            val normalizedSentence = sentence.lowercase().replace(Regex("\\s+"), " ")
            if (uniqueSentences.none { existing -> 
                existing.lowercase().replace(Regex("\\s+"), " ").contains(normalizedSentence) ||
                normalizedSentence.contains(existing.lowercase().replace(Regex("\\s+"), " "))
            }) {
                uniqueSentences.add(sentence)
            }
        }
        
        // Reconstruir sinopse sem repetições
        clean = uniqueSentences.joinToString(". ") + "."
        
        // Remover vírgulas seguidas de ponto
        clean = clean.replace(Regex(",\\.\\s*$"), ".")
        
        // Remover pontos múltiplos
        clean = clean.replace(Regex("\\.\\.+\$"), ".")
        clean = clean.replace(Regex("\\.\\s+\\.\$"), ".")
        
        // Limpar espaços extras
        clean = clean.replace(Regex("\\s+"), " ").trim()
        
        // Se terminar com reticências sem sentido, remover
        if (clean.endsWith("...") && clean.length < 50) {
            clean = clean.replace(Regex("\\.\\.\\.\$"), ".")
        }
        
        // Garantir ponto final se necessário
        if (clean.isNotEmpty() && !clean.endsWith(".") && !clean.endsWith("!") && !clean.endsWith("?") && clean.length > 10) {
            clean += "."
        }
        
        // Se ficou muito curta ou vazia após limpeza
        return when {
            clean.length < 20 -> "Sinopse não disponível."
            else -> clean
        }
    }

    // ✅ EM USO: Extrair status do anime
    private fun extractGoyabuStatus(doc: org.jsoup.nodes.Document): ShowStatus? {
        println("📊 extractGoyabuStatus: Chamada")
        return try {
            println("🔍 Procurando status do anime...")
            var statusText = ""

            val statusSelectors = listOf(
                "li.status",
                ".status",
                "[class*='status']",
                ".streamer-info li:contains(Status)",
                ".streamer-info-list li",
                "li:contains('Completo')",
                "li:contains('Lançamento')"
            )

            for (selector in statusSelectors) {
                val element = doc.selectFirst(selector)
                if (element != null) {
                    statusText = element.text().trim().lowercase()
                    println("✅ Status encontrado via '$selector': '$statusText'")
                    break
                }
            }

            if (statusText.isEmpty()) {
                doc.select("li, span, div").forEach { element ->
                    val text = element.text().trim().lowercase()
                    if (text.contains("complet") || text.contains("lançament")) {
                        statusText = text
                        println("✅ Status via fallback: '$statusText'")
                        return@forEach
                    }
                }
            }

            when {
                statusText.contains("complet") -> {
                    println("📊 Status: COMPLETED")
                    ShowStatus.Completed
                }
                statusText.contains("lançament") -> {
                    println("📊 Status: ONGOING")
                    ShowStatus.Ongoing
                }
                else -> {
                    println("📊 Status não reconhecido ou não encontrado")
                    null
                }
            }
        } catch (e: Exception) {
            println("❌ Erro ao extrair status: ${e.message}")
            null
        }
    }

    // ✅ EM USO: Parsear score
    private fun parseScore(text: String?): Score? {
        println("⭐ parseScore: Chamada")
        if (text.isNullOrBlank()) {
            println("📊 Score: Nenhum score encontrado")
            return null
        }

        try {
            val regex = Regex("""(\d+\.?\d*)""")
            val match = regex.find(text)

            return match?.groupValues?.get(1)?.toFloatOrNull()?.let { rawScore ->
                val multipliedScore = rawScore * 2
                println("📊 Score: $rawScore (site) → $multipliedScore (CloudStream)")
                val finalScore = multipliedScore.coerceAtMost(10.0f)
                Score.from10(finalScore)
            }
        } catch (e: Exception) {
            println("❌ Erro ao processar score '$text': ${e.message}")
            return null
        }
    }

    // ✅ EM USO: Converter Element para SearchResponse
    private fun Element.toSearchResponse(): AnimeSearchResponse? {
        println("🔍 Element.toSearchResponse: Chamada")
        val href = attr("href") ?: return null
        val isEpisodePage = href.matches(Regex("""^/\d+/?$"""))
        val isAnimePage = href.contains("/anime/")
        if (!isAnimePage || isEpisodePage) return null

        val titleElement = selectFirst(".title, .hidden-text")
        val rawTitle = titleElement?.text()?.trim() ?: return null
        val cleanedTitle = cleanTitle(rawTitle)

        if (rawTitle != cleanedTitle) {
            println("🧹 Título limpo: '$rawTitle' → '$cleanedTitle'")
        }

        val posterUrl = extractPosterUrl()
        val scoreElement = selectFirst(".rating-score-box, .rating")
        val scoreText = scoreElement?.text()?.trim()
        val score = parseScore(scoreText)
        val hasDubBadge = selectFirst(".audio-box.dublado, .dublado") != null

        if (cleanedTitle.isBlank()) return null

        return newAnimeSearchResponse(cleanedTitle, fixUrl(href)) {
            this.posterUrl = posterUrl
            this.type = TvType.Anime
            this.score = score

            if (hasDubBadge) {
                addDubStatus(dubExist = true, subExist = false)
                println("🎭 Badge: DUB (dublado detectado)")
            } else {
                addDubStatus(dubExist = false, subExist = true)
                println("🎭 Badge: LEG (apenas legendado)")
            }
        }
    }

    // ✅ EM USO: Extrair poster URL
    private fun Element.extractPosterUrl(): String? {
        println("🖼️ Element.extractPosterUrl: Chamada")
        selectFirst(".coverImg")?.attr("style")?.let { style ->
            val regex = Regex("""url\(['"]?([^'"()]+)['"]?\)""")
            regex.find(style)?.groupValues?.get(1)?.let { url ->
                return fixUrl(url)
            }
        }
        selectFirst("[data-thumb]")?.attr("data-thumb")?.let { url ->
            return fixUrl(url)
        }
        selectFirst("img[src]")?.attr("src")?.let { url ->
            return fixUrl(url)
        }
        return null
    }

    // ✅ EM USO: Página principal
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        println("📺 getMainPage: Chamada - '${request.name}' página $page")
        return loadingMutex.withLock {
            try {
                println("🎬 GOYABU: '${request.name}' - Página $page")
                val url = if (page > 1) "${request.data}page/$page/" else request.data
                val document = app.get(url, timeout = 20).document

                val elements = document.select("article a, .boxAN a, a[href*='/anime/']")
                println("📊 ${elements.size} links encontrados em '${request.name}'")

                val homeItems = elements.mapNotNull { it.toSearchResponse() }
                    .distinctBy { it.url }
                    .take(30)

                val hasNextPage = false
                newHomePageResponse(request.name, homeItems, hasNextPage)
            } catch (e: Exception) {
                println("❌ ERRO: ${request.name} - ${e.message}")
                newHomePageResponse(request.name, emptyList(), false)
            }
        }
    }

    // ✅ EM USO: Busca
    override suspend fun search(query: String): List<SearchResponse> {
        println("🔎 search: Chamada - '$query'")
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

    // ✅ EM USO: Carregar anime
    override suspend fun load(url: String): LoadResponse {
        println("🎬 load: Chamada - URL: $url")
        return try {
            println("\n" + "=".repeat(60))
            println("🎬 GOYABU: Carregando anime: $url")
            println("=".repeat(60))

            val document = app.get(url, timeout = 30).document

            val rawTitle = document.selectFirst("h1.text-hidden, h1")?.text()?.trim() ?: "Sem Título"
            val title = cleanTitle(rawTitle)
            if (rawTitle != title) {
                println("🧹 Título limpo: '$rawTitle' → '$title'")
            }
            println("📌 Título: $title")

            val poster = document.selectFirst(".streamer-poster img, .cover")?.attr("src")
                ?.takeIf { it.isNotBlank() }
                ?.let { fixUrl(it) }
            println("🖼️ Poster: ${poster != null}")

            val rawSynopsis = document.selectFirst(".streamer-sinopse")?.text()?.trim()
                ?.replace("ler mais", "")
                ?.trim()
                ?: "Sinopse não disponível."

            val synopsis = cleanSynopsis(rawSynopsis)
            if (rawSynopsis != synopsis && synopsis != "Sinopse não disponível.") {
                println("🧹 Sinopse limpa (frases removidas e sem repetições):")
                println("   ANTES: ${rawSynopsis.take(100)}...")
                println("   DEPOIS: ${synopsis.take(100)}...")
            }
            println("📖 Sinopse (${synopsis.length} chars)")

            val yearElement = document.selectFirst("li#year")
            val year = yearElement?.text()?.trim()?.toIntOrNull()
            println("📅 Ano: $year")

            val status = extractGoyabuStatus(document)

            val genres = mutableListOf<String>()
            document.select(".filter-btn.btn-style, a[href*='/generos/']").forEach { element ->
                element.text().trim().takeIf { it.isNotBlank() }?.let { 
                    if (it.length > 1 && !genres.contains(it)) genres.add(it) 
                }
            }
            println("🏷️ Gêneros: ${genres.size}")

            val scoreElement = document.selectFirst(".rating-total, .rating-score")
            val scoreText = scoreElement?.text()?.trim()
            val score = parseScore(scoreText)

            val isDubbed = rawTitle.contains("dublado", ignoreCase = true) ||
                          document.selectFirst(".audio-box.dublado, .dublado") != null
            println("🎭 Dublado: $isDubbed")

            println("\n🔍 BUSCANDO EPISÓDIOS...")
            
            // SIMPLIFICADO: Extrair episódios apenas do JavaScript
            val episodes = extractEpisodesFromJavaScript(document, url)
            
            // Se não encontrar pelo JavaScript, tentar método simples
            val fallbackEpisodes = if (episodes.isEmpty()) {
                extractEpisodesFallback(document, url)
            } else {
                emptyList()
            }
            
            val allEpisodes = episodes + fallbackEpisodes
            val sortedEpisodes = allEpisodes.sortedBy { it.episode }

            val response = newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.year = year
                this.plot = synopsis
                this.tags = genres
                this.score = score
                this.showStatus = status

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

    // ✅ EM USO: Extrair episódios do JavaScript
    private fun extractEpisodesFromJavaScript(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        println("📜 extractEpisodesFromJavaScript: Chamada")
        val episodes = mutableListOf<Episode>()

        try {
            val scripts = document.select("script")
            println("📊 Encontrados ${scripts.size} scripts na página")

            for ((index, script) in scripts.withIndex()) {
                val scriptContent = script.html()

                if (scriptContent.contains("allEpisodes")) {
                    println("\n🔍 Analisando script #$index...")

                    // Tentar extrair do array allEpisodes
                    val arrayContent = extractArrayContent(scriptContent, "allEpisodes")
                    
                    if (arrayContent.isNotBlank()) {
                        println("📦 Array allEpisodes extraído (${arrayContent.length} caracteres)")
                        
                        val episodeObjects = extractJsonObjects(arrayContent)
                        println("📈 ${episodeObjects.size} objetos encontrados no array")

                        episodeObjects.forEachIndexed { objIndex, jsonObj ->
                            try {
                                val epNumber = extractValueFromJson(jsonObj, "episodio", "episode", "number")?.toIntOrNull() ?: (objIndex + 1)
                                val epId = extractValueFromJson(jsonObj, "id") ?: ""
                                val epTitle = extractValueFromJson(jsonObj, "title", "name") ?: "Episódio $epNumber"
                                
                                val epUrl = buildEpisodeUrl(epId, epNumber)

                                episodes.add(newEpisode(epUrl) {
                                    this.name = epTitle
                                    this.episode = epNumber
                                    this.season = 1
                                })

                            } catch (e: Exception) {
                                println("   ❌ Erro ao processar objeto $objIndex: ${e.message}")
                            }
                        }

                        if (episodes.isNotEmpty()) {
                            return episodes
                        }
                    }
                }
            }

        } catch (e: Exception) {
            println("❌ Erro ao extrair episódios do JavaScript: ${e.message}")
        }

        return episodes
    }

    // ✅ EM USO: Método fallback para episódios
    private fun extractEpisodesFallback(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        println("🔄 extractEpisodesFallback: Chamada")
        val episodes = mutableListOf<Episode>()

        println("🔍 Fallback: Procurando episódios via HTML...")

        // Procurar links diretos de episódios
        val episodeLinks = document.select("a[href]").filter { 
            val href = it.attr("href")
            href.matches(Regex("""^/\d+/$""")) || href.contains("/episodio/")
        }

        episodeLinks.forEachIndexed { index, link ->
            try {
                val href = link.attr("href").trim()
                if (href.isBlank()) return@forEachIndexed

                val episodeNum = extractEpisodeNumberFromHref(href, index + 1)

                episodes.add(newEpisode(fixUrl(href)) {
                    this.name = "Episódio $episodeNum"
                    this.episode = episodeNum
                    this.season = 1
                })

            } catch (e: Exception) {
                println("   ⚠️ Erro no link ${index + 1}: ${e.message}")
            }
        }

        println("   📊 Total de episódios via fallback: ${episodes.size}")
        return episodes
    }

    // ✅ EM USO: Extrair número do episódio do href
    private fun extractEpisodeNumberFromHref(href: String, default: Int): Int {
        println("#️⃣ extractEpisodeNumberFromHref: Chamada")
        val regex1 = Regex("""/(\d+)/?$""")
        val regex2 = Regex("""/episodio[-_]?(\d+)/?$""", RegexOption.IGNORE_CASE)
        
        regex1.find(href)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        regex2.find(href)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }

        return default
    }

    // ✅ EM USO: Extrair conteúdo do array JavaScript
    private fun extractArrayContent(scriptContent: String, arrayName: String): String {
        println("📋 extractArrayContent: Chamada")
        val startIndex = scriptContent.indexOf("$arrayName = [")
        if (startIndex == -1) return ""
        
        var braceCount = 0
        var inString = false
        var escapeNext = false
        var i = startIndex + arrayName.length + 3
        
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

    // ✅ EM USO: Extrair objetos JSON do array
    private fun extractJsonObjects(jsonArray: String): List<String> {
        println("🧩 extractJsonObjects: Chamada")
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

    // ✅ EM USO: Extrair valor do JSON
    private fun extractValueFromJson(json: String, vararg keys: String): String? {
        println("🔑 extractValueFromJson: Chamada")
        for (key in keys) {
            val pattern1 = Regex(""""$key"\s*:\s*"([^"]*)"""")
            val match1 = pattern1.find(json)
            if (match1 != null) return match1.groupValues.getOrNull(1)
            
            val pattern2 = Regex(""""$key"\s*:\s*(\d+)""")
            val match2 = pattern2.find(json)
            if (match2 != null) return match2.groupValues.getOrNull(1)
        }
        return null
    }

    // ✅ EM USO: Construir URL do episódio
    private fun buildEpisodeUrl(idOrPath: String, episodeNumber: Int): String {
        println("🔗 buildEpisodeUrl: Chamada")
        return when {
            idOrPath.matches(Regex("""^\d+$""")) -> "$mainUrl/$idOrPath"
            idOrPath.startsWith("/") -> "$mainUrl$idOrPath"
            idOrPath.startsWith("http") -> idOrPath
            idOrPath.isNotBlank() -> fixUrl(idOrPath)
            else -> "$mainUrl/$episodeNumber"
        }
    }

    // ✅ EM USO: Load links (para vídeo)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🎬 loadLinks: Chamada - URL: $data")

        return GoyabuExtractor.extractVideoLinks(
            url = data,
            name = "Vídeo Goyabu",
            callback = callback
        )
    }

    // ⚠️ FUNÇÕES REMOVIDAS (não utilizadas):
    // 1. fixThumbnailUrl() - removida (thumbs desativadas)
    // 2. testThumbnailUrl() - removida (thumbs desativadas)
    // 3. extractThumbnailFromJsonObject() - removida (thumbs desativadas)
    // 4. extractIndividualEpisodesFromScript() - removida (integrada na principal)
    // 5. extractThumbnailsFromScriptVariables() - removida (thumbs desativadas)
    // 6. tryAlternativeThumbnails() - removida (thumbs desativadas)
    // 7. extractEpisodeFromBoxEPWithThumb() - removida (thumbs desativadas)
    // 8. extractThumbFromElement() - removida (thumbs desativadas)
    // 9. Todas as funções relacionadas a thumbnails foram removidas
}

// Extractor precisa estar em um arquivo separado normalmente
// Mas vou manter aqui para referência
object GoyabuExtractor {
    suspend fun extractVideoLinks(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🎬 GoyabuExtractor: Extraindo links de $url")
        // Implementação do extrator de vídeo
        return true
    }
}
