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
        private const val LANCAMENTOS_PATH = "/lancamentos"
        private val loadingMutex = Mutex()
        
        // LISTA COMPLETA DE GÊNEROS (REMOVIDOS: /18, /generos/gore, /generos/ecchi)
        private val ALL_GENRES = listOf(
            "/generos/china" to "China",
            "/generos/aventura" to "Aventura",
            "/generos/artes-marciais" to "Artes Marciais",
            "/generos/acao" to "Ação",
            "/generos/comedia" to "Comédia",
            "/generos/escolar" to "Escolar",
            "/generos/drama" to "Drama",
            "/generos/demonios" to "Demônios",
            "/generos/crime" to "Crime",
            "/generos/ficcao-cientifica" to "Ficção Científica",
            "/generos/fantasia" to "Fantasia",
            "/generos/esporte" to "Esporte",
            "/generos/familia" to "Família",
            "/generos/harem" to "Harém",
            "/generos/guerra" to "Guerra"
        )
        
        private val SYNOPSIS_JUNK_PATTERNS = listOf(
            Regex("""(?i)assistir.*?online"""),
            Regex("""(?i)anime completo"""),
            Regex("""(?i)todos os episodios"""),
            Regex("""(?i)dublado.*?online"""),
            Regex("""(?i)legendado.*?online"""),
            Regex("""(?i)assistir.*?gratis"""),
            Regex("""(?i)veja.*?de graça"""),
            Regex("""(?i)streaming.*?(online|gratis)"""),
            Regex("""(?i)assistir anime"""),
            Regex("""(?i)epis[oó]dio.*?dublado"""),
            Regex("""(?i)baixar.*?(torrent|mega)"""),
            Regex("""(?i)download.*?anime"""),
            Regex("""Visite.*?site""", RegexOption.IGNORE_CASE),
            Regex("""Confira.*?canal""", RegexOption.IGNORE_CASE),
            Regex("""(?i)\bhd\b.*?(720p|1080p)"""),
            Regex("""(?i)qualidade.*?(alta|hd)"""),
            Regex("""(?i)sinopse.*?:""", RegexOption.IGNORE_CASE)
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
        println("🎬 GOYABU: Plugin inicializado - ${ALL_GENRES.size} gêneros + Lançamentos")
    }

    override val mainPage = mainPageOf(
        "$mainUrl$LANCAMENTOS_PATH" to "Lançamentos",
        *ALL_GENRES.map { (path, name) -> 
            "$mainUrl$path" to name 
        }.toTypedArray()
    )

    // ========== FUNÇÕES AUXILIARES ==========
    
    private fun cleanTitle(dirtyTitle: String): String {
        var clean = dirtyTitle.trim()
        TITLE_CLEANUP_PATTERNS.forEach { pattern ->
            clean = pattern.replace(clean, "")
        }
        clean = clean.replace(Regex("\\s+"), " ").trim()
        return if (clean.isBlank()) dirtyTitle else clean
    }

    private fun cleanSynopsis(dirtySynopsis: String): String {
        var clean = dirtySynopsis.trim()
        
        // Primeiro: tentar detectar e remover o bloco de texto promocional no início
        val promotionalPatterns = listOf(
            Regex("""^(?:Assistir.*?Online.*?(?:Anime.*?Completo.*?)?(?:Dublado.*?Online\.)?\s*)+""", RegexOption.IGNORE_CASE),
            Regex("""^(?:Todos os Episodios.*?Online.*?(?:Anime.*?Completo.*?)?\.?\s*)+""", RegexOption.IGNORE_CASE),
            Regex("""^(?:Assistir.*?Anime.*?Completo.*?(?:Dublado.*?Online\.)?\s*)+""", RegexOption.IGNORE_CASE),
            Regex("""^(?:Assistir.*?Dublado.*?Online.*?\.\s*)+""", RegexOption.IGNORE_CASE),
            Regex("""^(?:.*?Online.*?(?:Completo|Dublado|Legendado).*?\.\s*)+""", RegexOption.IGNORE_CASE),
        )
        
        // Tentar remover bloco promocional no início
        for (pattern in promotionalPatterns) {
            val match = pattern.find(clean)
            if (match != null) {
                println("🧹 Removendo bloco promocional: '${match.value}'")
                clean = pattern.replaceFirst(clean, "").trim()
                
                // Se começar com ponto, remover
                if (clean.startsWith(".")) {
                    clean = clean.substring(1).trim()
                }
                // Se começar com vírgula, remover
                if (clean.startsWith(",")) {
                    clean = clean.substring(1).trim()
                }
                break
            }
        }
        
        // Segunda abordagem: dividir por frases e remover as promocionais
        val sentences = clean.split(".").map { it.trim() }
        if (sentences.size > 1) {
            val filteredSentences = mutableListOf<String>()
            var foundRealSynopsis = false
            
            for (sentence in sentences) {
                val trimmedSentence = sentence.trim()
                if (trimmedSentence.isEmpty()) continue
                
                // Verificar se é uma frase promocional
                val isPromotional = trimmedSentence.matches(Regex("(?i)^(assistir|veja|confira|visite|baixar|download|streaming|online|gratis|de graça|todos os episódios|anime completo|dublado|legendado).*")) ||
                                   trimmedSentence.contains(Regex("(?i)assistir.*?online")) ||
                                   trimmedSentence.contains(Regex("(?i)anime.*?completo")) ||
                                   trimmedSentence.contains(Regex("(?i)todos os episódios")) ||
                                   trimmedSentence.contains(Regex("(?i)dublado.*?online"))
                
                // Se já encontramos a sinopse real ou esta frase não é promocional, adicionar
                if (foundRealSynopsis || !isPromotional) {
                    filteredSentences.add(trimmedSentence)
                    // Marcar que encontramos sinopse real a partir da primeira frase não promocional
                    if (!isPromotional) {
                        foundRealSynopsis = true
                    }
                } else if (isPromotional) {
                    println("🧹 Removendo frase promocional: '$trimmedSentence'")
                }
            }
            
            // Juntar as frases, garantindo que a primeira comece com maiúscula
            if (filteredSentences.isNotEmpty()) {
                clean = filteredSentences.joinToString(". ")
                
                // Garantir que a primeira letra seja maiúscula
                if (clean.isNotEmpty()) {
                    clean = clean.first().uppercaseChar() + clean.drop(1)
                }
            }
        }
        
        // Remover padrões específicos que possam ter sobrado
        SYNOPSIS_JUNK_PATTERNS.forEach { pattern ->
            clean = pattern.replace(clean, "")
        }
        
        // Limpeza final
        clean = clean.replace(Regex("\\s+"), " ")
        clean = clean.replace(Regex("\\.\\s*\\.+"), ".")
        clean = clean.replace(Regex("^[.,]\\s*"), "") // Remover ponto ou vírgula no início
        clean = clean.trim()
        
        // Adicionar ponto final se necessário
        if (clean.isNotEmpty() && !clean.endsWith(".") && !clean.endsWith("!") && !clean.endsWith("?")) {
            clean += "."
        }
        
        // Caso de sinopse vazia ou muito curta
        return when {
            clean.length < 20 -> "Sinopse não disponível."
            clean == dirtySynopsis -> dirtySynopsis
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

        val genreElement = selectFirst(".genre-tag, .tag")
        val genres = genreElement?.text()?.split(",")?.map { it.trim() } ?: emptyList()
        
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

    private fun extractEpisodeNumberFromText(text: String): Int {
        val regex = Regex("""Epis[oó]dio\s+(\d+)""", RegexOption.IGNORE_CASE)
        val match = regex.find(text)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    // ========== EXTRATOR DE LANÇAMENTOS ==========
    
    private fun extractLancamentosItems(document: org.jsoup.nodes.Document): List<SearchResponse> {
    val items = mutableListOf<SearchResponse>()
    
    println("🔍 Extraindo itens da página de Lançamentos...")
    
    // Selecionar os episódios da página de lançamentos
    val episodeElements = document.select("article.boxEP.grid-view a")
    
    episodeElements.forEachIndexed { index, element ->
        try {
            val href = element.attr("href") ?: return@forEachIndexed
            val isEpisodePage = href.matches(Regex("""^/\d+/?$"""))
            
            if (!isEpisodePage) return@forEachIndexed
            
            // Extrair título do episódio
            val titleElement = element.selectFirst(".title.hidden-text")
            val rawTitle = titleElement?.text()?.trim() ?: return@forEachIndexed
            
            // Extrair número do episódio
            val episodeNumElement = element.selectFirst(".ep-type b")
            val episodeText = episodeNumElement?.text()?.trim() ?: ""
            val episodeNum = extractEpisodeNumberFromText(episodeText)
            
            // Criar título limpo
            val cleanedTitle = cleanTitle(rawTitle)
            
            // Criar título completo com número do episódio
            val fullTitle = if (episodeNum > 0) "$cleanedTitle - Episódio $episodeNum" else cleanedTitle
            
            // Extrair poster (thumbnail do episódio)
            val posterUrl = element.selectFirst(".coverImg")?.attr("style")?.let { style ->
                val regex = Regex("""url\(['"]?([^'"()]+)['"]?\)""")
                regex.find(style)?.groupValues?.get(1)?.let { url ->
                    fixUrl(url)
                }
            }
            
            // Verificar se é dublado
            val hasDubBadge = element.selectFirst(".audio-box.dublado") != null
            
            if (cleanedTitle.isNotBlank()) {
                val response = newAnimeSearchResponse(fullTitle, fixUrl(href)) {
                    this.posterUrl = posterUrl
                    this.type = TvType.Anime
                }
                
                // Adicionar status de dublagem
                if (hasDubBadge) {
                    response.addDubStatus(dubExist = true, subExist = false)
                }
                
                items.add(response)
                
                if (index < 3) {
                    println("   🎬 Lançamento: $fullTitle -> $href")
                }
            }
        } catch (e: Exception) {
            println("   ❌ Erro ao extrair lançamento ${index + 1}: ${e.message}")
        }
    }
    
    return items
    }
    // ========== FUNÇÕES PRINCIPAIS ==========
    
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return loadingMutex.withLock {
            try {
                println("🎬 GOYABU: '${request.name}' - Página $page")
                
                // Verificar se é a página de Lançamentos
                val isLancamentosPage = request.name == "Lançamentos"
                
                val url = if (page > 1) "${request.data}page/$page/" else request.data
                val document = app.get(url, timeout = 20).document
                
                val items = if (isLancamentosPage) {
                    // Para página de Lançamentos, extrair episódios como itens de pesquisa
                    extractLancamentosItems(document)
                } else {
                    // Para outras páginas, usar a extração normal
                    extractRegularItems(document)
                }
                
                println("📊 ${items.size} itens encontrados em '${request.name}'")
                
                val hasNextPage = if (isLancamentosPage) {
                    // Verificar se há mais páginas nos Lançamentos
                    document.selectFirst(".pagination") != null
                } else {
                    false
                }
                
                newHomePageResponse(request.name, items, hasNextPage)
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

    // ========== FUNÇÕES DE EXTRACTION DE EPISÓDIOS ==========
    
    private fun extractEpisodeNumberFromHref(href: String, default: Int): Int {
        val regex1 = Regex("""/(\d+)/?$""")
        val regex2 = Regex("""/episodio[-_]?(\d+)/?$""", RegexOption.IGNORE_CASE)
        
        regex1.find(href)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        regex2.find(href)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }

        return default
    }

    private fun extractEpisodeFromBoxEP(boxEP: Element, index: Int, episodes: MutableList<Episode>) {
        val linkElement = boxEP.selectFirst("a[href]") ?: return
        val href = linkElement.attr("href").trim()
        if (href.isBlank()) return
        
        var episodeNum = index + 1

        // Extrair número do episódio
        val epTypeElement = linkElement.selectFirst(".ep-type b")
        epTypeElement?.text()?.trim()?.let { text ->
            val regex = Regex("""Epis[oó]dio\s+(\d+)""", RegexOption.IGNORE_CASE)
            val match = regex.find(text)
            match?.groupValues?.get(1)?.toIntOrNull()?.let { foundNum ->
                episodeNum = foundNum
            }
        }

        // Tentar extrair do data-episode-number do pai
        boxEP.parent()?.attr("data-episode-number")?.toIntOrNull()?.let { parentNum ->
            episodeNum = parentNum
        }
        
        val finalEpisodeNum = extractEpisodeNumberFromHref(href, episodeNum)
        
        // EXTRAIR THUMB DO EPISÓDIO
        val thumb = extractEpisodeThumbnail(linkElement)
        
        val episodeTitle = epTypeElement?.text()?.trim() ?: "Episódio $finalEpisodeNum"
        
        episodes.add(newEpisode(fixUrl(href)) {
            this.name = episodeTitle
            this.episode = finalEpisodeNum
            this.season = 1
            this.posterUrl = thumb // ADICIONANDO A THUMB
        })

        println("   ✅ Ep $finalEpisodeNum: $episodeTitle -> $href")
        if (thumb != null) {
            println("   🖼️  Thumb: $thumb")
        }
    }

    private fun extractEpisodeThumbnail(linkElement: Element): String? {
        // Tentar múltiplos seletores para encontrar a thumbnail
        val selectors = listOf(
            "figure.thumb.contentImg div.coverImg",
            ".coverImg",
            ".thumb img",
            ".contentImg img",
            "div[data-thumb]",
            "img[src]",
            "figure img"
        )
        
        for (selector in selectors) {
            try {
                // Primeiro, tentar extrair do atributo style (background-image)
                val styleElement = linkElement.selectFirst("$selector[style]")
                styleElement?.attr("style")?.let { style ->
                    val regex = Regex("""url\(['"]?([^'"()]+)['"]?\)""")
                    val match = regex.find(style)
                    match?.groupValues?.get(1)?.let { url ->
                        val cleanUrl = url.replace("&quot;", "").trim()
                        if (cleanUrl.isNotBlank()) {
                            println("      🖼️ Encontrada thumb via style: $cleanUrl")
                            return fixUrl(cleanUrl)
                        }
                    }
                }
                
                // Tentar data-thumb
                val dataThumbElement = linkElement.selectFirst("$selector[data-thumb]")
                dataThumbElement?.attr("data-thumb")?.let { url ->
                    val cleanUrl = url.replace("&quot;", "").trim()
                    if (cleanUrl.isNotBlank()) {
                        println("      🖼️ Encontrada thumb via data-thumb: $cleanUrl")
                        return fixUrl(cleanUrl)
                    }
                }
                
                // Tentar src direto
                val imgElement = linkElement.selectFirst("$selector img")
                imgElement?.attr("src")?.let { url ->
                    val cleanUrl = url.trim()
                    if (cleanUrl.isNotBlank()) {
                        println("      🖼️ Encontrada thumb via src: $cleanUrl")
                        return fixUrl(cleanUrl)
                    }
                }
                
                // Tentar data-src (lazy loading)
                imgElement?.attr("data-src")?.let { url ->
                    val cleanUrl = url.trim()
                    if (cleanUrl.isNotBlank()) {
                        println("      🖼️ Encontrada thumb via data-src: $cleanUrl")
                        return fixUrl(cleanUrl)
                    }
                }
                
            } catch (e: Exception) {
                println("      ⚠️ Erro ao extrair thumb com seletor '$selector': ${e.message}")
            }
        }
        
        return null
    }

    private fun findEpisodeThumbsInDocument(document: org.jsoup.nodes.Document): Map<Int, String> {
        val thumbMap = mutableMapOf<Int, String>()
        
        try {
            println("\n🔍 Buscando thumbs de episódios no documento...")
            
            // Procurar em várias estruturas possíveis
            val episodeContainers = listOf(
                "#episodes-container",
                ".episodes-slide",
                ".episodes-grid",
                ".episode-list",
                ".episodios-list"
            )
            
            for (container in episodeContainers) {
                val containerElement = document.selectFirst(container)
                if (containerElement != null) {
                    println("✅ Encontrado container: $container")
                    
                    // Buscar todas as thumbs
                    val thumbElements = containerElement.select(".coverImg, [data-thumb], .thumb img")
                    println("📊 ${thumbElements.size} elementos de thumb encontrados")
                    
                    thumbElements.forEachIndexed { index, element ->
                        try {
                            // Extrair URL da thumb
                            val thumbUrl = extractThumbUrlFromElement(element)
                            if (!thumbUrl.isNullOrBlank()) {
                                thumbMap[index + 1] = fixUrl(thumbUrl)
                                if (index < 3) {
                                    println("   📸 Thumb para episódio ${index + 1}: $thumbUrl")
                                }
                            }
                        } catch (e: Exception) {
                            println("   ⚠️ Erro ao processar thumb $index: ${e.message}")
                        }
                    }
                    
                    break
                }
            }
            
        } catch (e: Exception) {
            println("❌ Erro ao buscar thumbs no documento: ${e.message}")
        }
        
        println("📊 Total de thumbs encontradas: ${thumbMap.size}")
        return thumbMap
    }

    private fun extractThumbUrlFromElement(element: Element): String? {
        // Tentar múltiplas fontes
        try {
            // Tentar style attribute first
            element.attr("style")?.let { style ->
                if (style.contains("url(")) {
                    val regex = Regex("""url\(['"]?([^'"()]+)['"]?\)""")
                    val match = regex.find(style)
                    match?.groupValues?.get(1)?.let { url ->
                        val cleanUrl = url.replace("&quot;", "").trim()
                        if (cleanUrl.isNotBlank()) return cleanUrl
                    }
                }
            }
            
            // Tentar data-thumb
            element.attr("data-thumb")?.let { url ->
                val cleanUrl = url.trim()
                if (cleanUrl.isNotBlank()) return cleanUrl
            }
            
            // Tentar data-src
            element.attr("data-src")?.let { url ->
                val cleanUrl = url.trim()
                if (cleanUrl.isNotBlank()) return cleanUrl
            }
            
            // Tentar src
            element.attr("src")?.let { url ->
                val cleanUrl = url.trim()
                if (cleanUrl.isNotBlank()) return cleanUrl
            }
            
            // Tentar img dentro do elemento
            element.selectFirst("img")?.let { img ->
                img.attr("src")?.let { url ->
                    val cleanUrl = url.trim()
                    if (cleanUrl.isNotBlank()) return cleanUrl
                }
                
                img.attr("data-src")?.let { url ->
                    val cleanUrl = url.trim()
                    if (cleanUrl.isNotBlank()) return cleanUrl
                }
            }
            
        } catch (e: Exception) {
            // Continuar para próxima fonte
        }
        
        return null
    }

    private fun extractEpisodesFallback(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        println("🔍 Fallback: Procurando episódios via HTML...")
        
        val episodeItems = document.select("#episodes-container .episode-item, .episode-item")
        
        if (episodeItems.isEmpty()) {
            println("   ⚠️ Nenhum .episode-item encontrado")
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
                    val boxEP = episodeItem.selectFirst(".boxEP") ?: episodeItem
                    extractEpisodeFromBoxEP(boxEP, index, episodes)
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

    // ========== LOAD FUNCTION ==========
    
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
                ?.replace("Leia mais", "")
                ?.trim()
                ?: "Sinopse não disponível."
            
            val synopsis = cleanSynopsis(rawSynopsis)
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
            val episodes = extractEpisodesFallback(document, url)
            
            // BUSCAR THUMBS ADICIONAIS DO DOCUMENTO
            val additionalThumbs = findEpisodeThumbsInDocument(document)
            if (additionalThumbs.isNotEmpty()) {
                println("\n🔍 Aplicando thumbs adicionais aos episódios...")
                episodes.forEach { episode ->
                    val thumbUrl = additionalThumbs[episode.episode]
                    if (thumbUrl != null && episode.posterUrl == null) {
                        episode.posterUrl = thumbUrl
                        println("   🖼️ Adicionada thumb para Ep ${episode.episode}")
                    }
                }
            }

            val sortedEpisodes = episodes.sortedBy { it.episode }

            // Log de resumo das thumbs
            val episodesWithThumbs = sortedEpisodes.count { it.posterUrl != null }
            println("\n📊 RESUMO DE EPISÓDIOS:")
            println("   Total: ${sortedEpisodes.size}")
            println("   Com thumbs: $episodesWithThumbs")
            println("   Sem thumbs: ${sortedEpisodes.size - episodesWithThumbs}")

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
                    println("📝 Tente acessar: $url e verifique se há episódios na página")
                }
            }
            
            println("\n" + "=".repeat(60))
            println("🎬 GOYABU: Load concluído para '$title'")
            println("=".repeat(60) + "\n")
            
            response
            
        } catch (e: Exception) {
            println("❌ ERRO no load: ${e.message}")
            return newAnimeLoadResponse("Erro", url, TvType.Anime) {
                this.plot = "Erro: ${e.message}"
            }
        }
    }

    // ========== LOAD LINKS ==========
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("\n🎬 GOYABU loadLinks: URL recebida: $data")
        return false
    }
}
