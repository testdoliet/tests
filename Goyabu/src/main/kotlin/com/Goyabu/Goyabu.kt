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
        
        // PALAVRAS PARA REMOVER DAS SINOPSES
        private val SYNOPSIS_JUNK_PATTERNS = listOf(
            Regex("""(?i)assistir.*?online"""),
            Regex("""(?i)anime completo"""),
            Regex("""(?i)todos os episodios"""),
            Regex("""(?i)dublado.*?online"""),
            Regex("""(?i)legendado.*?online"""),
            Regex("""(?i)assista.*?gratis"""),
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
        
        // PALAVRAS PARA REMOVER DOS TÍTULOS
        private val TITLE_CLEANUP_PATTERNS = listOf(
            "(?i)\\s*\\(dublado\\)".toRegex(),
            "(?i)\\s*\\(legendado\\)".toRegex(),
            "(?i)\\s*\\(\\d{4}\\)".toRegex(), // Remove (2024)
            "(?i)\\s*dublado\\s*$".toRegex(),
            "(?i)\\s*legendado\\s*$".toRegex(),
            "(?i)\\s*online\\s*$".toRegex(),
            "(?i)\\s*assistir\\s*".toRegex(),
            "(?i)\\s*anime\\s*$".toRegex(),
            "(?i)\\s*-\\s*todos os epis[oó]dios".toRegex(),
            "(?i)\\s*-\\s*completo".toRegex(),
            "(?i)\\s*\\|.*".toRegex() // Remove tudo depois de |
        )
        
        // GÊNEROS ADULTOS/SUGESTIVOS (para referência interna)
        private val ADULT_GENRES = setOf("+18", "Hentai", "Adulto", "Erótico", "Yaoi", "Yuri")
        private val SUGGESTIVE_GENRES = setOf("Ecchi", "Harém", "Harem")
    }

    init {
        println("🎬 GOYABU: Plugin inicializado - ${ALL_GENRES.size} gêneros")
    }

    override val mainPage = mainPageOf(
        *ALL_GENRES.map { (path, name) -> 
            "$mainUrl$path" to name 
        }.toTypedArray()
    )

    // ============ FUNÇÕES DE LIMPEZA ============
    
    /**
     * Limpa títulos removendo "(Dublado)", "(2024)", "Online", etc.
     */
    private fun cleanTitle(dirtyTitle: String): String {
        var clean = dirtyTitle.trim()
        
        // Aplicar todos os padrões de limpeza
        TITLE_CLEANUP_PATTERNS.forEach { pattern ->
            clean = pattern.replace(clean, "")
        }
        
        // Remover múltiplos espaços
        clean = clean.replace(Regex("\\s+"), " ").trim()
        
        // Se ficar vazio, retorna o original
        return if (clean.isBlank()) dirtyTitle else clean
    }
    
    /**
     * Limpa sinopses removendo propaganda e textos de SEO.
     */
    private fun cleanSynopsis(dirtySynopsis: String): String {
        var clean = dirtySynopsis.trim()
        
        // Remover padrões de lixo
        SYNOPSIS_JUNK_PATTERNS.forEach { pattern ->
            clean = pattern.replace(clean, "")
        }
        
        // Remover frases que começam com palavras-chave de SEO
        val sentences = clean.split(".").map { it.trim() }
        val filteredSentences = sentences.filter { sentence ->
            !sentence.matches(Regex("(?i)^(assistir|veja|confira|visite|baixar|download|streaming|online|gratis|de graça).*"))
        }
        
        clean = filteredSentences.joinToString(". ")
        
        // Remover múltiplos espaços e pontos
        clean = clean.replace(Regex("\\s+"), " ")
        clean = clean.replace(Regex("\\.\\s*\\.+"), ".")
        clean = clean.trim()
        
        // Garantir que termine com ponto
        if (clean.isNotEmpty() && !clean.endsWith(".") && !clean.endsWith("!") && !clean.endsWith("?")) {
            clean += "."
        }
        
        // Se ficou muito curta ou vazia, retorna mensagem padrão
        return when {
            clean.length < 20 -> "Sinopse não disponível."
            clean == dirtySynopsis -> dirtySynopsis // Se não mudou nada
            else -> clean
        }
    }
    
    // ============ FUNÇÃO PARA EXTRAIR STATUS ============
    private fun extractGoyabuStatus(doc: org.jsoup.nodes.Document): ShowStatus? {
        return try {
            println("🔍 Procurando status do anime...")
            
            // Procurar em vários locais possíveis
            val statusSelectors = listOf(
                "li.status",
                ".status",
                "[class*='status']",
                ".streamer-info li:contains(Status)",
                ".streamer-info-list li",
                "li:contains('Completo')",
                "li:contains('Lançamento')"
            )
            
            var statusText = ""
            
            for (selector in statusSelectors) {
                val element = doc.selectFirst(selector)
                if (element != null) {
                    statusText = element.text().trim().lowercase()
                    println("✅ Status encontrado via '$selector': '$statusText'")
                    break
                }
            }
            
            if (statusText.isEmpty()) {
                // Fallback: procurar qualquer texto com "complet" ou "lançament"
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

    // ============ FUNÇÃO PARA DETECTAR CONTEÚDO ADULTO (sem ContentRating) ============
    private fun hasAdultContent(genres: List<String>): Boolean {
        val lowerGenres = genres.map { it.lowercase().trim() }
        
        // Verificar gêneros explícitos
        val hasExplicit = ADULT_GENRES.any { explicitGenre ->
            lowerGenres.any { it == explicitGenre.lowercase() }
        }
        
        if (hasExplicit) {
            println("🔞 CONTEÚDO ADULTO DETECTADO: $genres")
            return true
        }
        
        return false
    }

    // ============ FUNÇÃO PARA PROCESSAR SCORE (MULTIPLICAR POR 2) ============
    private fun parseScore(text: String?): Score? {
        if (text.isNullOrBlank()) {
            println("📊 Score: Nenhum score encontrado")
            return null
        }
        
        try {
            // Extrair número (pode ser decimal como "4.5")
            val regex = Regex("""(\d+\.?\d*)""")
            val match = regex.find(text)
            
            return match?.groupValues?.get(1)?.toFloatOrNull()?.let { rawScore ->
                // Multiplicar por 2 (ex: site mostra 5, CloudStream deve mostrar 10)
                val multipliedScore = rawScore * 2
                println("📊 Score: $rawScore (site) → $multipliedScore (CloudStream)")
                
                // Limitar a 10.0 no máximo
                val finalScore = multipliedScore.coerceAtMost(10.0f)
                Score.from10(finalScore)
            }
        } catch (e: Exception) {
            println("❌ Erro ao processar score '$text': ${e.message}")
            return null
        }
    }

    // ============ EXTRACTION PARA LISTAGEM ============
    private fun Element.toSearchResponse(): AnimeSearchResponse? {
        val href = attr("href") ?: return null
        
        // FILTRAR: Só queremos séries, não episódios individuais
        val isEpisodePage = href.matches(Regex("""^/\d+/?$"""))
        val isAnimePage = href.contains("/anime/")
        if (!isAnimePage || isEpisodePage) return null

        // TÍTULO (com limpeza)
        val titleElement = selectFirst(".title, .hidden-text")
        val rawTitle = titleElement?.text()?.trim() ?: return null
        val cleanedTitle = cleanTitle(rawTitle)
        
        if (rawTitle != cleanedTitle) {
            println("🧹 Título limpo: '$rawTitle' → '$cleanedTitle'")
        }
        
        // THUMBNAIL
        val posterUrl = extractPosterUrl()
        
        // SCORE (com multiplicação ×2)
        val scoreElement = selectFirst(".rating-score-box, .rating")
        val scoreText = scoreElement?.text()?.trim()
        val score = parseScore(scoreText)
        
        // CORREÇÃO DAS BADGES: 
        // Se tem badge dublado → só badge DUB no CloudStream
        // Se não tem badge dublado → só badge LEG no CloudStream
        val hasDubBadge = selectFirst(".audio-box.dublado, .dublado") != null
        val showSub = !hasDubBadge  // Só mostra LEG se NÃO for dublado
        
        if (cleanedTitle.isBlank()) return null

        // Verificar se é conteúdo adulto (apenas para log)
        val genreElement = selectFirst(".genre-tag, .tag")
        val genres = genreElement?.text()?.split(",")?.map { it.trim() } ?: emptyList()
        if (hasAdultContent(genres)) {
            println("⚠️ Anime adulto na lista: $cleanedTitle")
        }
        
        return newAnimeSearchResponse(cleanedTitle, fixUrl(href)) {
            this.posterUrl = posterUrl
            this.type = TvType.Anime
            this.score = score
            
            // Aplicar regra das badges
            if (hasDubBadge) {
                // Tem dublado → só badge DUB
                addDubStatus(dubExist = true, subExist = false)
                println("🎭 Badge: DUB (dublado detectado)")
            } else {
                // Não tem dublado → só badge LEG
                addDubStatus(dubExist = false, subExist = true)
                println("🎭 Badge: LEG (apenas legendado)")
            }
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
            
            // TÍTULO (com limpeza)
            val rawTitle = document.selectFirst("h1.text-hidden, h1")?.text()?.trim() ?: "Sem Título"
            val title = cleanTitle(rawTitle)
            if (rawTitle != title) {
                println("🧹 Título limpo: '$rawTitle' → '$title'")
            }
            println("📌 Título: $title")
            
            // POSTER
            val poster = document.selectFirst(".streamer-poster img, .cover")?.attr("src")
                ?.takeIf { it.isNotBlank() }
                ?.let { fixUrl(it) }
            println("🖼️ Poster: ${poster != null}")
            
            // SINOPSE (com limpeza)
            val rawSynopsis = document.selectFirst(".streamer-sinopse")?.text()?.trim()
                ?.replace("ler mais", "")
                ?.trim()
                ?: "Sinopse não disponível."
            
            val synopsis = cleanSynopsis(rawSynopsis)
            if (rawSynopsis != synopsis && synopsis != "Sinopse não disponível.") {
                println("🧹 Sinopse limpa:")
                println("   ANTES: ${rawSynopsis.take(100)}...")
                println("   DEPOIS: ${synopsis.take(100)}...")
            }
            println("📖 Sinopse (${synopsis.length} chars)")
            
            // ANO
            val yearElement = document.selectFirst("li#year")
            val year = yearElement?.text()?.trim()?.toIntOrNull()
            println("📅 Ano: $year")
            
            // STATUS DO ANIME
            val status = extractGoyabuStatus(document)
            
            // GÊNEROS
            val genres = mutableListOf<String>()
            document.select(".filter-btn.btn-style, a[href*='/generos/']").forEach { element ->
                element.text().trim().takeIf { it.isNotBlank() }?.let { 
                    if (it.length > 1 && !genres.contains(it)) genres.add(it) 
                }
            }
            println("🏷️ Gêneros: ${genres.size}")
            
            // DETECTAR CONTEÚDO ADULTO (apenas para log)
            val isAdultContent = hasAdultContent(genres)
            if (isAdultContent) {
                println("⚠️ AVISO: Este anime contém conteúdo adulto (+18)")
            }
            
            // SCORE (com multiplicação ×2)
            val scoreElement = document.selectFirst(".rating-total, .rating-score")
            val scoreText = scoreElement?.text()?.trim()
            val score = parseScore(scoreText)
            
            // DUBLADO/LEGENDADO (mesma lógica das badges)
            val isDubbed = rawTitle.contains("dublado", ignoreCase = true) ||
                           document.selectFirst(".audio-box.dublado, .dublado") != null
            println("🎭 Dublado: $isDubbed")
            
            // EPISÓDIOS
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
            
            // Ordenar episódios
            val sortedEpisodes = episodes.sortedBy { it.episode }
            
            // CRIAR RESPOSTA
            val response = newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.year = year
                this.plot = synopsis
                this.tags = genres
                this.score = score
                this.showStatus = status
                
                if (sortedEpisodes.isNotEmpty()) {
                    // CORREÇÃO: Só mostra badge DUB se for dublado, senão só LEG
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
            idOrPath
