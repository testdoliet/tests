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

        // LISTA REDUZIDA DE GÊNEROS
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

    private fun cleanTitle(dirtyTitle: String): String {
        var clean = dirtyTitle.trim()
        TITLE_CLEANUP_PATTERNS.forEach { pattern ->
            clean = pattern.replace(clean, "")
        }
        clean = clean.replace(Regex("\\s+"), " ").trim()
        return if (clean.isBlank()) dirtyTitle else clean
    }

    // CORREÇÃO MELHORADA: Remover frases inteiras e corrigir repetições
    private fun cleanSynopsis(dirtySynopsis: String): String {
        var clean = dirtySynopsis.trim()
        
        if (clean.isBlank()) {
            return "Sinopse não disponível."
        }
        
        // Remover frases inteiras que contenham palavras-chave (até a vírgula, ponto ou reticências)
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
            // CORREÇÃO: Também capturar frases que terminam com reticências
            val regex = Regex("""[^.!?]*$phrasePattern[^.!?]*([.!?]|\.\.\.)?\s*""", RegexOption.IGNORE_CASE)
            clean = regex.replace(clean, "")
        }
        
        // CORREÇÃO: Remover frases que terminam com ... e estão incompletas
        clean = clean.replace(Regex("""[^.!?]*\.\.\.\s*$"""), "")
        
        // CORREÇÃO CRÍTICA: Remover repetições de frases inteiras
        val sentences = clean.split(Regex("""[.!?]+""")).map { it.trim() }.filter { it.isNotBlank() }
        val uniqueSentences = mutableListOf<String>()
        
        sentences.forEach { sentence ->
            // Verificar se a frase já existe (ignorando variações menores)
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
        
        // CORREÇÃO: Se terminar com reticências sem sentido, remover
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

    private fun extractGoyabuStatus(doc: org.jsoup.nodes.Document): ShowStatus? {
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

    private fun parseScore(text: String?): Score? {
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

    private fun Element.toSearchResponse(): AnimeSearchResponse? {
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

    private fun Element.extractPosterUrl(): String? {
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
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
            println("🎬 GOYABU: Carregando com extração JavaScript: $url")
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

            // USAR SINOPSE LIMPA (CORREÇÃO: remove repetições)
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
            var episodes = extractEpisodesFromJavaScript(document, url)

            if (episodes.isEmpty()) {
                println("⚠️ Nenhum episódio encontrado no JavaScript, tentando métodos alternativos...")
                val fallbackEpisodes = extractEpisodesFallback(document, url)
                if (fallbackEpisodes.isNotEmpty()) {
                    println("✅ Encontrados ${fallbackEpisodes.size} episódios via fallback")
                    episodes = episodes + fallbackEpisodes
                }
            } else {
                println("✅ ENCONTRADOS ${episodes.size} EPISÓDIOS NO JAVASCRIPT!")
            }

            val sortedEpisodes = episodes.sortedBy { it.episode }

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
                        val thumbInfo = if (ep.posterUrl != null) " [COM THUMB]" else " [SEM THUMB]"
                        println("   📺 Ep ${ep.episode}: ${ep.name} -> ${ep.data}$thumbInfo")
                        if (ep.posterUrl != null) {
                            println("      🖼️ Thumb URL: ${ep.posterUrl}")
                        }
                    }
                    if (sortedEpisodes.size > 5) {
                        val withThumb = sortedEpisodes.count { it.posterUrl != null }
                        println("   ... e mais ${sortedEpisodes.size - 5} episódios ($withThumb com thumb)")
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

    // CORREÇÃO CRÍTICA: Consertar URLs de thumbnail
    private fun fixThumbnailUrl(thumbUrl: String?): String? {
        if (thumbUrl.isNullOrBlank()) return null
        
        var fixed = thumbUrl.trim()
        
        // CORREÇÃO: Remover barras duplicadas do início
        fixed = fixed.replace(Regex("""^(https?://[^/]+)//"""), "$1/")
        
        // CORREÇÃO: Remover \/ (barra escapada) - comum em JSON
        fixed = fixed.replace("\\/", "/")
        
        // CORREÇÃO: Garantir que comece com http
        if (!fixed.startsWith("http")) {
            // CORREÇÃO IMPORTANTE: As thumbnails podem ser caminhos relativos
            // No log vimos: \/miniatures\/68eab069925df.webp
            // Isso precisa virar: https://goyabu.io/miniatures/68eab069925df.webp
            fixed = fixed.trimStart('/')
            fixed = "$mainUrl/$fixed"
        }
        
        // CORREÇÃO: Remover barras duplicadas no meio
        fixed = fixed.replace(Regex("""(?<!:)/+"""), "/")
        
        // CORREÇÃO: Verificar se a URL parece válida
        if (!fixed.contains("miniatures") && !fixed.contains("thumb") && !fixed.contains("image")) {
            println("   ⚠️ URL de thumbnail suspeita: $fixed")
            return null
        }
        
        return fixed
    }

    // NOVA FUNÇÃO: Testar se a thumbnail existe
    private suspend fun testThumbnailUrl(thumbUrl: String): Boolean {
        return try {
            val response = app.get(thumbUrl, timeout = 10)
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    // CORREÇÃO PRINCIPAL: Extrair episódios com thumbnails do JavaScript
    private suspend fun extractEpisodesFromJavaScript(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()

        try {
            val scripts = document.select("script")
            println("📊 Encontrados ${scripts.size} scripts na página")

            for ((index, script) in scripts.withIndex()) {
                val scriptContent = script.html()

                if (scriptContent.contains("allEpisodes") || 
                    scriptContent.contains("\"episodio\"") || 
                    scriptContent.contains("\"id\"")) {

                    println("\n🔍 Analisando script #$index para episódios e thumbs...")

                    // PRIMEIRO: Tentar extrair do array allEpisodes
                    if (scriptContent.contains("allEpisodes")) {
                        println("✅ Encontrado 'allEpisodes' no script")
                        episodes.addAll(extractEpisodesFromAllEpisodesArray(scriptContent))
                    }

                    // SEGUNDO: Tentar extrair objetos JSON individuais
                    if (episodes.isEmpty()) {
                        println("🔍 Tentando extrair episódios individualmente do script...")
                        episodes.addAll(extractIndividualEpisodesFromScript(scriptContent))
                    }

                    // CORREÇÃO: Tentar encontrar thumbnails alternativas se as atuais não funcionarem
                    if (episodes.isNotEmpty()) {
                        episodes.addAll(tryAlternativeThumbnails(episodes, document))
                    }

                    if (episodes.isNotEmpty()) {
                        println("✅ Encontrados ${episodes.size} episódios no script #$index")
                        break
                    }
                }
            }

        } catch (e: Exception) {
            println("❌ Erro ao extrair episódios do JavaScript: ${e.message}")
        }

        return episodes
    }

    // CORREÇÃO: Função específica para extrair do array allEpisodes
    private suspend fun extractEpisodesFromAllEpisodesArray(scriptContent: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        try {
            val arrayContent = extractArrayContent(scriptContent, "allEpisodes")

            if (arrayContent.isNotBlank()) {
                println("📦 Array allEpisodes extraído (${arrayContent.length} caracteres)")

                val episodeObjects = extractJsonObjects(arrayContent)
                println("📈 ${episodeObjects.size} objetos encontrados no array allEpisodes")

                episodeObjects.forEachIndexed { objIndex, jsonObj ->
                    try {
                        // Extrair número do episódio
                        val epNumber = extractValueFromJson(jsonObj, "episodio", "episode", "number")?.toIntOrNull() ?: (objIndex + 1)
                        
                        // Extrair ID
                        val epId = extractValueFromJson(jsonObj, "id") ?: ""
                        
                        // Extrair título
                        val epTitle = extractValueFromJson(jsonObj, "title", "name") ?: "Episódio $epNumber"
                        
                        // CORREÇÃO: Extrair thumbnail e CONCERTAR URL
                        val rawThumb = extractThumbnailFromJsonObject(jsonObj)
                        var epThumb = fixThumbnailUrl(rawThumb)
                        
                        // CORREÇÃO: Testar se a thumbnail funciona
                        var isValidThumb = false
                        if (epThumb != null) {
                            println("   🔍 Testando thumbnail para Ep $epNumber: $epThumb")
                            isValidThumb = testThumbnailUrl(epThumb)
                            
                            if (!isValidThumb) {
                                println("   ❌ Thumbnail não acessível (404): $epThumb")
                                epThumb = null
                            }
                        }
                        
                        // Construir URL
                        val epUrl = buildEpisodeUrl(epId, epNumber)

                        episodes.add(newEpisode(epUrl) {
                            this.name = epTitle
                            this.episode = epNumber
                            this.season = 1
                            
                            // ADICIONAR THUMB SE ENCONTRADA E VÁLIDA
                            if (epThumb != null && isValidThumb) {
                                this.posterUrl = epThumb
                                println("   ✅ Ep $epNumber: Thumb VÁLIDA -> $epThumb")
                            } else {
                                println("   ⚠️ Ep $epNumber: Sem thumbnail válida")
                            }
                        })

                    } catch (e: Exception) {
                        println("   ❌ Erro ao processar objeto $objIndex: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            println("❌ Erro ao extrair do array allEpisodes: ${e.message}")
        }
        
        return episodes
    }

    // CORREÇÃO: Tentar thumbnails alternativas
    private suspend fun tryAlternativeThumbnails(existingEpisodes: List<Episode>, document: org.jsoup.nodes.Document): List<Episode> {
        val updatedEpisodes = mutableListOf<Episode>()
        
        println("🔍 Procurando thumbnails alternativas...")
        
        // Tentar extrair thumbnails da página HTML
        val htmlThumbs = mutableListOf<String>()
        
        // Procurar imagens que possam ser thumbnails de episódios
        document.select("img[src*='miniature'], img[src*='thumb'], .episode-item img, .boxEP img").forEach { img ->
            val src = img.attr("src") ?: img.attr("data-src")
            if (!src.isNullOrBlank() && src.contains(Regex("""miniature|thumb|episodio""", RegexOption.IGNORE_CASE))) {
                val thumb = fixUrl(src)
                if (!htmlThumbs.contains(thumb)) {
                    htmlThumbs.add(thumb)
                    println("   🔍 Thumb alternativa encontrada no HTML: $thumb")
                }
            }
        }
        
        // Associar thumbnails alternativas aos episódios
        existingEpisodes.forEachIndexed { index, episode ->
            var updatedEpisode = episode
            
            // Se o episódio não tem thumbnail, tentar uma alternativa
            if (episode.posterUrl == null && index < htmlThumbs.size) {
                val alternativeThumb = htmlThumbs[index]
                println("   🔄 Tentando thumbnail alternativa para Ep ${episode.episode}: $alternativeThumb")
                
                // Testar se a thumbnail alternativa funciona
                val isValid = testThumbnailUrl(alternativeThumb)
                
                if (isValid) {
                    updatedEpisode = newEpisode(episode.data) {
                        this.name = episode.name
                        this.episode = episode.episode
                        this.season = episode.season
                        this.posterUrl = alternativeThumb
                    }
                    println("   ✅ Thumb alternativa VÁLIDA para Ep ${episode.episode}")
                }
            }
            
            updatedEpisodes.add(updatedEpisode)
        }
        
        return updatedEpisodes
    }

    // NOVO: Função específica para extrair thumbnail de objeto JSON
    private fun extractThumbnailFromJsonObject(jsonObj: String): String? {
        // Tentar várias chaves possíveis para thumbnail
        val thumbKeys = listOf("thumb", "image", "poster", "thumbnail", "miniature", "img", "picture", "cover")
        
        for (key in thumbKeys) {
            val thumb = extractValueFromJson(jsonObj, key)
            if (!thumb.isNullOrBlank()) {
                return thumb
            }
        }
        
        // Tentar padrões mais complexos
        val patterns = listOf(
            Regex(""""thumb"\s*:\s*"([^"]+)""""),
            Regex(""""image"\s*:\s*"([^"]+)""""),
            Regex(""""poster"\s*:\s*"([^"]+)""""),
            Regex(""""thumbnail"\s*:\s*"([^"]+)""""),
            Regex(""",\s*"thumb"\s*:\s*"([^"]+)""""),
            Regex(""""img"\s*:\s*"([^"]+)"""")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(jsonObj)
            if (match != null) {
                val thumb = match.groupValues.getOrNull(1)
                if (!thumb.isNullOrBlank()) {
                    return thumb
                }
            }
        }
        
        return null
    }

    // CORREÇÃO: Extrair episódios individuais do script
    private suspend fun extractIndividualEpisodesFromScript(scriptContent: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        try {
            // Padrão para objetos de episódio completos
            val episodePattern = Regex("""\{[^{}]*"id"\s*:\s*"(\d+)"[^{}]*"episodio"\s*:\s*"(\d+)"[^{}]*\}""")
            val matches = episodePattern.findAll(scriptContent)
            
            var matchCount = 0
            matches.forEach { match ->
                matchCount++
                try {
                    val fullMatch = match.value
                    val id = extractValueFromJson(fullMatch, "id") ?: ""
                    val epNum = extractValueFromJson(fullMatch, "episodio", "episode")?.toIntOrNull() ?: matchCount
                    val title = extractValueFromJson(fullMatch, "title", "name") ?: "Episódio $epNum"
                    val rawThumb = extractThumbnailFromJsonObject(fullMatch)
                    val thumb = fixThumbnailUrl(rawThumb)
                    
                    var isValidThumb = false
                    if (thumb != null) {
                        isValidThumb = testThumbnailUrl(thumb)
                    }

                    if (id.isNotBlank()) {
                        episodes.add(newEpisode("$mainUrl/$id") {
                            this.name = title
                            this.episode = epNum
                            this.season = 1
                            
                            if (thumb != null && isValidThumb) {
                                this.posterUrl = thumb
                                println("   📺 Ep $epNum: Thumb via padrão individual -> $thumb")
                            }
                        })
                    }
                } catch (e: Exception) {
                    println("   ❌ Erro no match $matchCount: ${e.message}")
                }
            }
            
            if (matchCount > 0) {
                println("✅ Encontrados $matchCount episódios via padrão individual")
            }
            
        } catch (e: Exception) {
            println("❌ Erro ao extrair episódios individuais: ${e.message}")
        }
        
        return episodes
    }

    private suspend fun extractEpisodesFallback(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()

        println("🔍 Fallback: Procurando episódios via HTML...")

        val episodeItems = document.select("#episodes-container .episode-item, .episode-item")

        if (episodeItems.isEmpty()) {
            println("   ⚠️ Nenhum .episode-item encontrado")
            
            val boxEPs = document.select(".boxEP.grid-view, .boxEP")
            println("   🔄 Fallback: ${boxEPs.size} .boxEP encontrados")

            boxEPs.forEachIndexed { index, boxEP ->
                try {
                    extractEpisodeFromBoxEPWithThumb(boxEP, index, episodes)
                } catch (e: Exception) {
                    println("   ❌ Erro no boxEP ${index + 1}: ${e.message}")
                }
            }
        } else {
            println("   ✅ ${episodeItems.size} .episode-item encontrados")

            episodeItems.forEachIndexed { index, episodeItem ->
                try {
                    val boxEP = episodeItem.selectFirst(".boxEP") ?: episodeItem
                    extractEpisodeFromBoxEPWithThumb(boxEP, index, episodes)
                } catch (e: Exception) {
                    println("   ❌ Erro no episode-item ${index + 1}: ${e.message}")
                }
            }
        }

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

                    val episodeNum = extractEpisodeNumberFromHref(href, index + 1)

                    val rawThumb = extractThumbFromElement(link)
                    val thumb = fixThumbnailUrl(rawThumb)
                    
                    var isValidThumb = false
                    if (thumb != null) {
                        isValidThumb = testThumbnailUrl(thumb)
                    }

                    episodes.add(newEpisode(fixUrl(href)) {
                        this.name = "Episódio $episodeNum"
                        this.episode = episodeNum
                        this.season = 1
                        if (thumb != null && isValidThumb) {
                            this.posterUrl = thumb
                        }
                    })

                } catch (e: Exception) {
                    println("   ⚠️ Erro no link ${index + 1}: ${e.message}")
                }
            }
        }

        println("   📊 Total de episódios via fallback: ${episodes.size}")
        return episodes
    }

    private suspend fun extractEpisodeFromBoxEPWithThumb(boxEP: Element, index: Int, episodes: MutableList<Episode>) {
        val linkElement = boxEP.selectFirst("a[href]") ?: return
        val href = linkElement.attr("href").trim()
        if (href.isBlank()) return

        var episodeNum = index + 1

        val epTypeElement = linkElement.selectFirst(".ep-type b")
        epTypeElement?.text()?.trim()?.let { text ->
            val regex = Regex("""Epis[oó]dio\s+(\d+)""", RegexOption.IGNORE_CASE)
            val match = regex.find(text)
            match?.groupValues?.get(1)?.toIntOrNull()?.let { episodeNum = it }
        }

        boxEP.parent()?.attr("data-episode-number")?.toIntOrNull()?.let { episodeNum = it }
        
        episodeNum = extractEpisodeNumberFromHref(href, episodeNum)

        val rawThumb = extractThumbFromElement(linkElement)
        val thumb = fixThumbnailUrl(rawThumb)
        
        var isValidThumb = false
        if (thumb != null) {
            isValidThumb = testThumbnailUrl(thumb)
        }

        val episodeTitle = epTypeElement?.text()?.trim() ?: "Episódio $episodeNum"

        val isDubbed = linkElement.selectFirst(".audio-box.dublado") != null
        val titleWithDub = if (isDubbed) "$episodeTitle (Dublado)" else episodeTitle

        episodes.add(newEpisode(fixUrl(href)) {
            this.name = titleWithDub
            this.episode = episodeNum
            this.season = 1
            if (thumb != null && isValidThumb) {
                this.posterUrl = thumb
            }
        })
    }

    private fun extractThumbFromElement(element: Element): String? {
        element.selectFirst(".coverImg")?.attr("style")?.let { style ->
            val regex = Regex("""url\(['"]?([^'"()]+)['"]?\)""")
            regex.find(style)?.groupValues?.get(1)?.replace("&quot;", "")?.trim()?.let { 
                return it
            }
        }
        
        element.selectFirst("img[src]")?.attr("src")?.let { src ->
            if (src.isNotBlank() && !src.contains("data:image")) {
                return src.trim()
            }
        }
        
        element.selectFirst("img[data-src]")?.attr("data-src")?.let { dataSrc ->
            if (dataSrc.isNotBlank() && !dataSrc.contains("data:image")) {
                return dataSrc.trim()
            }
        }
        
        element.selectFirst("[data-thumb]")?.attr("data-thumb")?.let { dataThumb ->
            if (dataThumb.isNotBlank()) {
                return dataThumb.trim()
            }
        }
        
        element.selectFirst("[data-miniature-b64]")?.attr("data-miniature-b64")?.let { base64Path ->
            if (base64Path.isNotBlank()) {
                return base64Path
            }
        }
        
        return null
    }

    private fun extractEpisodeNumberFromHref(href: String, default: Int): Int {
        val regex1 = Regex("""/(\d+)/?$""")
        val regex2 = Regex("""/episodio[-_]?(\d+)/?$""", RegexOption.IGNORE_CASE)
        
        regex1.find(href)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        regex2.find(href)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }

        return default
    }

    private fun extractArrayContent(scriptContent: String, arrayName: String): String {
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
            val pattern1 = Regex(""""$key"\s*:\s*"([^"]*)"""")
            val match1 = pattern1.find(json)
            if (match1 != null) return match1.groupValues.getOrNull(1)
            
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("\n🎬 GOYABU loadLinks: URL recebida: $data")

        return GoyabuExtractor.extractVideoLinks(
            url = data,
            name = "Vídeo Goyabu",
            callback = callback
        )
    }
}
