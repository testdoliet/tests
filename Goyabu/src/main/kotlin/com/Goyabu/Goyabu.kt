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

        // ADICIONADO: Padrões para limpar sinopse
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
        
        private val ADULT_GENRES = setOf("+18", "Hentai", "Adulto", "Erótico", "Yaoi", "Yuri")
        private val SUGGESTIVE_GENRES = setOf("Ecchi", "Harém", "Harem")
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

    private fun cleanTitle(dirtyTitle: String): String {
        var clean = dirtyTitle.trim()
        TITLE_CLEANUP_PATTERNS.forEach { pattern ->
            clean = pattern.replace(clean, "")
        }
        clean = clean.replace(Regex("\\s+"), " ").trim()
        return if (clean.isBlank()) dirtyTitle else clean
    }

    // CORRIGIDO: Função para limpar sinopse MELHORADA
    private fun cleanSynopsis(dirtySynopsis: String): String {
        var clean = dirtySynopsis.trim()
        
        // ========== REMOVER SPAM NO INÍCIO ==========
        // Encontra onde começa a sinopse real (procura por palavras-chave de início de história)
        val synopsisStartKeywords = listOf(
            "Era uma vez",
            "Em um mundo",
            "A história",
            "Acompanhe",
            "Descubra",
            "Conheça",
            "Em uma época",
            "Ambientado",
            "Situado",
            "Baseado",
            "Adaptado",
            "Conta a história",
            "Narra",
            "Um dia",
            "Certa vez",
            "Há muito tempo",
            "Num futuro",
            "Num passado",
            "Num universo"
        )
        
        // Procura o ponto onde começa a sinopse real
        var realSynopsisStart = -1
        for (keyword in synopsisStartKeywords) {
            val index = clean.indexOf(keyword, ignoreCase = true)
            if (index > 0 && (realSynopsisStart == -1 || index < realSynopsisStart)) {
                realSynopsisStart = index
            }
        }
        
        // Se encontrou onde começa a sinopse real, corta o spam do início
        if (realSynopsisStart > 0) {
            clean = clean.substring(realSynopsisStart).trim()
        }
        
        // ========== REMOVER PADRÕES DE SPAM ==========
        SYNOPSIS_JUNK_PATTERNS.forEach { pattern ->
            clean = pattern.replace(clean, "")
        }
        
        // ========== REMOVER FRASES DE SPAM ==========
        val sentences = clean.split(".").map { it.trim() }
        val filteredSentences = sentences.filter { sentence ->
            // Remove frases que são spam
            val lowerSentence = sentence.lowercase()
            !(lowerSentence.contains("assistir") && 
              (lowerSentence.contains("online") || 
               lowerSentence.contains("dublado") || 
               lowerSentence.contains("legendado"))) &&
            !lowerSentence.contains("anime completo") &&
            !lowerSentence.contains("todos os episodios") &&
            !lowerSentence.contains("baixar") &&
            !lowerSentence.contains("download") &&
            !lowerSentence.contains("streaming") &&
            !lowerSentence.contains("gratis") &&
            !lowerSentence.contains("de graça") &&
            !lowerSentence.matches(Regex("""(?i)^(visite|confira|acesse|clique|veja|assista).*"""))
        }
        
        clean = filteredSentences.joinToString(". ")
        
        // ========== CORREÇÃO: LIMPAR PONTOS DUPLOS E PONTOS SOZINHOS ==========
        // Remove pontos duplos (..) mantendo apenas um ponto
        clean = clean.replace(Regex("\\.\\.+"), ".")
        
        // Remove pontos sozinhos no final de frases (que não fazem sentido)
        clean = clean.replace(Regex("""\s+\.\s*"""), " ")
        
        // Remove pontos no início de frases
        clean = clean.replace(Regex("""^\.+\s*"""), "")
        
        // Garante que tenha apenas um espaço entre palavras
        clean = clean.replace(Regex("\\s+"), " ")
        
        // Remove pontos que ficaram soltos no meio do texto
        clean = clean.replace(Regex("""\.\s*\.\s*"""), ". ")
        
        // Adiciona ponto final se não tiver
        clean = clean.trim()
        if (clean.isNotEmpty() && !clean.endsWith(".") && !clean.endsWith("!") && !clean.endsWith("?") && clean.length > 10) {
            clean += "."
        }
        
        // Remove ponto final se estiver muito próximo do início
        if (clean.length < 30 && clean.endsWith(".")) {
            clean = clean.dropLast(1)
        }
        
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

    private fun hasAdultContent(genres: List<String>): Boolean {
        val lowerGenres = genres.map { it.lowercase().trim() }
        val hasExplicit = ADULT_GENRES.any { explicitGenre ->
            lowerGenres.any { it == explicitGenre.lowercase() }
        }
        
        if (hasExplicit) {
            println("🔞 CONTEÚDO ADULTO DETECTADO: $genres")
            return true
        }
        
        return false
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
        if (hasAdultContent(genres)) {
            println("⚠️ Anime adulto na lista: $cleanedTitle")
        }

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

                // TRATAMENTO ESPECIAL PARA PÁGINA DE LANÇAMENTOS
                val elements = if (request.name == "Lançamentos") {
                    // Na página de lançamentos, extraímos episódios em vez de animes
                    document.select(".boxEP.grid-view a")
                } else {
                    document.select("article a, .boxAN a, a[href*='/anime/']")
                }

                println("📊 ${elements.size} links encontrados em '${request.name}'")

                val homeItems = if (request.name == "Lançamentos") {
                    // Para lançamentos, precisamos transformar episódios em itens de anime
                    extractLancamentosItems(document, elements)
                } else {
                    elements.mapNotNull { it.toSearchResponse() }
                        .distinctBy { it.url }
                        .take(30)
                }

                val hasNextPage = if (request.name == "Lançamentos") {
                    // Verificar paginação para lançamentos
                    document.select(".pagination .page-numbers a").any { it.text() != "1" }
                } else {
                    false
                }

                newHomePageResponse(request.name, homeItems, hasNextPage)
            } catch (e: Exception) {
                println("❌ ERRO: ${request.name} - ${e.message}")
                newHomePageResponse(request.name, emptyList(), false)
            }
        }
    }

    // MODIFICADO: Função para extrair itens da página de lançamentos
    private fun extractLancamentosItems(document: org.jsoup.nodes.Document, elements: List<Element>): List<AnimeSearchResponse> {
        val items = mutableListOf<AnimeSearchResponse>()
        
        // Extrair todos os animes da página para pesquisa de thumbs
        val allAnimeElements = document.select("article a, .boxAN a, a[href*='/anime/']")
        val animeMap = mutableMapOf<String, AnimeInfo>()
        
        // Criar mapa de animes para pesquisa rápida
        allAnimeElements.forEach { element ->
            try {
                val titleElement = element.selectFirst(".title, .hidden-text")
                val rawTitle = titleElement?.text()?.trim() ?: return@forEach
                val cleanedTitle = cleanTitle(rawTitle)
                
                if (cleanedTitle.isNotBlank()) {
                    val posterUrl = element.extractPosterUrl()
                    val hasDubBadge = element.selectFirst(".audio-box.dublado, .dublado") != null
                    val href = element.attr("href")
                    
                    animeMap[cleanedTitle.lowercase()] = AnimeInfo(
                        title = cleanedTitle,
                        posterUrl = posterUrl,
                        url = href,
                        isDubbed = hasDubBadge
                    )
                }
            } catch (e: Exception) {
                println("⚠️ Erro ao mapear anime: ${e.message}")
            }
        }
        
        println("📊 Mapeados ${animeMap.size} animes para pesquisa de thumbs")
        
        elements.forEach { element ->
            try {
                val href = element.attr("href") ?: return@forEach
                
                // Extrair informações do episódio
                val titleElement = element.selectFirst(".title.hidden-text")
                val rawTitle = titleElement?.text()?.trim() ?: return@forEach
                
                val epTypeElement = element.selectFirst(".ep-type b")
                val episodeNumber = epTypeElement?.text()?.trim()?.let { text ->
                    val regex = Regex("""Epis[oó]dio\s+(\d+)""", RegexOption.IGNORE_CASE)
                    val match = regex.find(text)
                    match?.groupValues?.get(1)?.toIntOrNull() ?: 1
                } ?: 1
                
                // Extrair thumb do próprio elemento (fallback)
                val localThumb = element.selectFirst(".coverImg")?.attr("style")?.let { style ->
                    val regex = Regex("""url\(['"]?([^'"()]+)['"]?\)""")
                    regex.find(style)?.groupValues?.get(1)?.let { fixUrl(it) }
                }
                
                // Determinar se é dublado
                val isDubbed = element.selectFirst(".audio-box.dublado") != null
                
                // Extrair título base do anime (remover - Ep X e Dublado)
                val baseTitle = extractBaseAnimeTitle(rawTitle, isDubbed)
                
                // Procurar anime correspondente no mapa
                val animeInfo = findBestMatchingAnime(baseTitle, animeMap)
                
                // Usar thumb do anime encontrado ou thumb local
                val bestThumb = animeInfo?.posterUrl ?: localThumb
                val animeUrl = animeInfo?.url ?: href
                
                // Limpar título final (remover episódio)
                val cleanDisplayTitle = cleanTitle(baseTitle)
                
                // Adicionar episódio ao título para exibição
                val displayTitleWithEpisode = "$cleanDisplayTitle (Ep $episodeNumber)"
                
                // Criar resposta
                val searchResponse = newAnimeSearchResponse(displayTitleWithEpisode, fixUrl(animeUrl)) {
                    this.posterUrl = bestThumb
                    this.type = TvType.Anime
                    
                    if (isDubbed) {
                        addDubStatus(dubExist = true, subExist = false)
                        println("🎭 Lançamento Dublado: $cleanDisplayTitle - Ep $episodeNumber")
                    } else {
                        addDubStatus(dubExist = false, subExist = true)
                        println("🎭 Lançamento Legendado: $cleanDisplayTitle - Ep $episodeNumber")
                    }
                }
                
                items.add(searchResponse)
                
            } catch (e: Exception) {
                println("❌ Erro ao processar item de lançamento: ${e.message}")
            }
        }
        
        return items.distinctBy { it.url }.take(30)
    }
    
    // NOVO: Classe auxiliar para informações do anime
    private data class AnimeInfo(
        val title: String,
        val posterUrl: String?,
        val url: String,
        val isDubbed: Boolean
    )
    
    // NOVO: Extrair título base do anime
    private fun extractBaseAnimeTitle(rawTitle: String, isDubbed: Boolean): String {
        var title = rawTitle.trim()
        
        // Remover " - Ep X" ou " Episódio X"
        title = title.replace(Regex("""\s*[-–]\s*Ep(?:is[oó]dio)?\s*\d+""", RegexOption.IGNORE_CASE), "")
        
        // Remover "(Dublado)" se já tivermos a informação
        if (isDubbed) {
            title = title.replace("(Dublado)", "", ignoreCase = true)
            title = title.replace("Dublado", "", ignoreCase = true)
        }
        
        // Remover " (Legendado)" se existir
        title = title.replace("(Legendado)", "", ignoreCase = true)
        title = title.replace("Legendado", "", ignoreCase = true)
        
        return title.trim()
    }
    
    // NOVO: Encontrar melhor correspondência de anime
    private fun findBestMatchingAnime(baseTitle: String, animeMap: Map<String, AnimeInfo>): AnimeInfo? {
        val searchTitle = baseTitle.lowercase().trim()
        
        // Tentar correspondência exata primeiro
        animeMap[searchTitle]?.let { return it }
        
        // Tentar correspondência parcial
        for ((key, anime) in animeMap) {
            if (searchTitle.contains(key, ignoreCase = true) || key.contains(searchTitle, ignoreCase = true)) {
                return anime
            }
        }
        
        // Tentar remover palavras comuns e buscar novamente
        val simplifiedTitle = searchTitle
            .replace("(dublado)", "", ignoreCase = true)
            .replace("(legendado)", "", ignoreCase = true)
            .replace("online", "", ignoreCase = true)
            .replace("anime", "", ignoreCase = true)
            .replace("\\s+".toRegex(), " ")
            .trim()
        
        if (simplifiedTitle.isNotBlank() && simplifiedTitle != searchTitle) {
            animeMap[simplifiedTitle]?.let { return it }
            
            for ((key, anime) in animeMap) {
                if (simplifiedTitle.contains(key, ignoreCase = true) || key.contains(simplifiedTitle, ignoreCase = true)) {
                    return anime
                }
            }
        }
        
        return null
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

            // CORRIGIDO: Usar sinopse limpa
            val synopsis = cleanSynopsis(rawSynopsis)
            if (rawSynopsis != synopsis && synopsis != "Sinopse não disponível.") {
                println("🧹 Sinopse limpa:")
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

            val isAdultContent = hasAdultContent(genres)
            if (isAdultContent) {
                println("⚠️ AVISO: Este anime contém conteúdo adulto (+18)")
            }

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
                this.plot = synopsis // USANDO SINOPSE LIMPA
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
            newAnimeLoadResponse("Erro", url, TvType.Anime) {
                this.plot = "Erro: ${e.message}"
            }
        }
    }

    private fun extractEpisodesFromJavaScript(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()

        try {
            val scripts = document.select("script")
            println("📊 Encontrados ${scripts.size} scripts na página")

            for ((index, script) in scripts.withIndex()) {
                val scriptContent = script.html()

                if (scriptContent.contains("allEpisodes") || 
                    scriptContent.contains("\"episodio\"") || 
                    scriptContent.contains("\"id\"")) {

                    println("\n🔍 Analisando script #$index...")

                    if (scriptContent.contains("allEpisodes")) {
                        println("✅ Encontrado 'allEpisodes' no script")

                        val arrayContent = extractArrayContent(scriptContent, "allEpisodes")

                        if (arrayContent.isNotBlank()) {
                            println("📦 Array extraído (${arrayContent.length} caracteres)")

                            val episodeObjects = extractJsonObjects(arrayContent)
                            println("📈 ${episodeObjects.size} objetos encontrados no array")

                            episodeObjects.forEachIndexed { objIndex, jsonObj ->
                                try {
                                    val epNumber = extractValueFromJson(jsonObj, "episodio", "episode", "number")?.toIntOrNull() ?: (objIndex + 1)
                                    val epId = extractValueFromJson(jsonObj, "id") ?: ""
                                    val epTitle = extractValueFromJson(jsonObj, "title", "name") ?: "Episódio $epNumber"

                                    val epUrl = buildEpisodeUrl(epId, epNumber)

                                    // ADICIONADO: Thumb para episódios do JavaScript
                                    val epThumb = extractValueFromJson(jsonObj, "thumb", "image", "poster")
                                    
                                    episodes.add(newEpisode(epUrl) {
                                        this.name = epTitle
                                        this.episode = epNumber
                                        this.season = 1
                                        // ADICIONA THUMB SE EXISTIR
                                        if (epThumb != null) {
                                            this.posterUrl = fixUrl(epThumb)
                                        }
                                    })

                                    if (objIndex < 3) {
                                        println("   📺 Ep $epNumber: $epTitle -> $epUrl")
                                    }

                                } catch (e: Exception) {
                                    println("   ❌ Erro ao processar objeto $objIndex: ${e.message}")
                                }
                            }

                            if (episodes.isNotEmpty()) {
                                return episodes
                            }
                        }
                    }

                    if (episodes.isEmpty()) {
                        println("🔍 Tentando extrair episódios individualmente...")

                        val episodePattern = Regex("""\{"id":"(\d+)","episodio":"(\d+)".*?"(thumb|image)":"([^"]*)".*?\}""")
                        val episodeMatches = episodePattern.findAll(scriptContent)

                        var matchCount = 0
                        episodeMatches.forEach { match ->
                            matchCount++
                            try {
                                val id = match.groupValues.getOrNull(1) ?: ""
                                val epNum = match.groupValues.getOrNull(2)?.toIntOrNull() ?: matchCount
                                val thumb = match.groupValues.getOrNull(4)

                                if (id.isNotBlank()) {
                                    episodes.add(newEpisode("$mainUrl/$id") {
                                        this.name = "Episódio $epNum"
                                        this.episode = epNum
                                        this.season = 1
                                        // ADICIONA THUMB SE EXISTIR
                                        if (thumb != null) {
                                            this.posterUrl = fixUrl(thumb)
                                        }
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

                    // ADICIONADO: Tentar extrair thumb do link
                    val thumb = link.selectFirst("img[src]")?.attr("src")?.let { fixUrl(it) }

                    episodes.add(newEpisode(fixUrl(href)) {
                        this.name = "Episódio $episodeNum"
                        this.episode = episodeNum
                        this.season = 1
                        // ADICIONA THUMB SE EXISTIR
                        if (thumb != null) {
                            this.posterUrl = thumb
                        }
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

        var episodeNum = index + 1

        val epTypeElement = linkElement.selectFirst(".ep-type b")
        epTypeElement?.text()?.trim()?.let { text ->
            val regex = Regex("""Epis[oó]dio\s+(\d+)""", RegexOption.IGNORE_CASE)
            val match = regex.find(text)
            match?.groupValues?.get(1)?.toIntOrNull()?.let { episodeNum = it }
        }

        boxEP.parent()?.attr("data-episode-number")?.toIntOrNull()?.let { episodeNum = it }
        episodeNum = extractEpisodeNumberFromHref(href, episodeNum)

        // MELHORADO: Extrair thumb de mais lugares
        var thumb: String? = null
        
        // Método 1: Do estilo background-image
        linkElement.selectFirst(".coverImg")?.attr("style")?.let { style ->
            val regex = Regex("""url\(['"]?([^'"()]+)['"]?\)""")
            regex.find(style)?.groupValues?.get(1)?.replace("&quot;", "")?.trim()?.let { 
                thumb = fixUrl(it)
            }
        }
        
        // Método 2: De imagem direta
        if (thumb == null) {
            linkElement.selectFirst("img[src]")?.attr("src")?.let { src ->
                thumb = fixUrl(src.trim())
            }
        }
        
        // Método 3: Do boxEP pai
        if (thumb == null) {
            boxEP.selectFirst("img[src]")?.attr("src")?.let { src ->
                thumb = fixUrl(src.trim())
            }
        }
        
        // Método 4: Do data-thumb
        if (thumb == null) {
            linkElement.selectFirst("[data-thumb]")?.attr("data-thumb")?.let { dataThumb ->
                thumb = fixUrl(dataThumb.trim())
            }
        }

        val episodeTitle = epTypeElement?.text()?.trim() ?: "Episódio $episodeNum"

        episodes.add(newEpisode(fixUrl(href)) {
            this.name = episodeTitle
            this.episode = episodeNum
            this.season = 1
            // ADICIONA THUMB SE ENCONTRADO
            if (thumb != null) {
                this.posterUrl = thumb
                println("   🖼️ Thumb encontrada para Ep $episodeNum")
            }
        })

        println("   ✅ Ep $episodeNum: $episodeTitle -> $href")
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
