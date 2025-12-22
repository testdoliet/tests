package com.AnimeFire

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Element
import com.AnimeFire.AnimeFireVideoExtractor

class AnimeFire : MainAPI() {
    override var mainUrl = "https://animefire.io"
    override var name = "AnimeFire"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie, TvType.OVA)
    override val usesWebView = true

    companion object {
        private const val SEARCH_PATH = "/pesquisar"
        
        private val loadingMutex = Mutex()
        
        // LISTA COMPLETA DE TODAS AS CATEGORIAS
        private val ALL_CATEGORIES = listOf(
            "/em-lancamento" to "Lançamentos",
            "/animes-atualizados" to "Atualizados",
            "/top-animes" to "Top Animes",
            "/lista-de-animes-legendados" to "Legendados",
            "/lista-de-animes-dublados" to "Dublados",
            "/lista-de-filmes-legendados" to "Filmes Legendados",
            "/lista-de-filmes-dublados" to "Filmes Dublados",
            "/genero/acao" to "Ação",
            "/genero/aventura" to "Aventura",
            "/genero/comedia" to "Comédia",
            "/genero/drama" to "Drama",
            "/genero/fantasia" to "Fantasia",
            "/genero/romance" to "Romance",
            "/genero/shounen" to "Shounen",
            "/genero/seinen" to "Seinen",
            "/genero/esporte" to "Esporte",
            "/genero/misterio" to "Mistério",
            "/genero/artes-marciais" to "Artes Marciais",
            "/genero/demonios" to "Demônios",
            "/genero/ecchi" to "Ecchi",
            "/genero/ficcao-cientifica" to "Ficção Científica",
            "/genero/harem" to "Harém",
            "/genero/horror" to "Horror",
            "/genero/magia" to "Magia",
            "/genero/mecha" to "Mecha",
            "/genero/militar" to "Militar",
            "/genero/psicologico" to "Psicológico",
            "/genero/slice-of-life" to "Slice of Life",
            "/genero/sobrenatural" to "Sobrenatural",
            "/genero/superpoder" to "Superpoder",
            "/genero/vampiros" to "Vampiros",
            "/genero/vida-escolar" to "Vida Escolar"
        )
        
        private var cachedTabs: List<Pair<String, String>>? = null
        private var cacheTime: Long = 0
        private const val CACHE_DURATION = 300000L // 5 minutos
        
        fun getRandomTabs(count: Int = 8): List<Pair<String, String>> {
            val currentTime = System.currentTimeMillis()
            
            if (cachedTabs != null && (currentTime - cacheTime) < CACHE_DURATION) {
                return cachedTabs!!
            }
            
            val randomTabs = ALL_CATEGORIES.shuffled().take(count)
            cachedTabs = randomTabs
            cacheTime = currentTime
            
            return randomTabs
        }
    }

    init {
        println("🔥 ANIMEFIRE: Plugin inicializado")
    }

    override val mainPage = mainPageOf(
        *getRandomTabs().map { (path, name) -> 
            "$mainUrl$path" to name 
        }.toTypedArray()
    )

    // ============ FUNÇÃO DE EXTRACTION ============
    private fun Element.toSearchResponse(debugMode: Boolean = true): AnimeSearchResponse? {
        val href = attr("href") ?: return null
        if (href.isBlank() || (!href.contains("/animes/") && !href.contains("/filmes/"))) return null
        
        if (debugMode) {
            println("\n" + "=".repeat(60))
            println("🔍 DEBUG EXTRACTION")
            println("=".repeat(60))
        }
        
        // TÍTULO
        val titleElement = selectFirst("h3.animeTitle, .animeTitle, h3, .card-title") ?: return null
        val rawTitle = titleElement.text().trim()
        
        val titleAttr = attr("title")?.trim() ?: ""
        val combinedTitle = if (titleAttr.isNotBlank() && titleAttr.length > 3) titleAttr else rawTitle
        
        if (combinedTitle.isBlank()) return null
        
        // AVALIAÇÃO
        val scoreResult = extractScoreAdvanced(this, debugMode)
        val scoreText = scoreResult.first
        
        if (debugMode) {
            println("\n📊 AVALIAÇÃO:")
            println("   • Texto: '$scoreText'")
        }
        
        // PROCESSAR SCORE FINAL
        val score = when {
            scoreText == null || scoreText == "N/A" -> null
            else -> scoreText.toFloatOrNull()?.let { Score.from10(it) }
        }
        
        // DUB/LEG
        val hasExplicitDub = combinedTitle.contains("dublado", ignoreCase = true)
        val hasExplicitLeg = combinedTitle.contains("legendado", ignoreCase = true)
        
        val finalHasDub: Boolean
        val finalHasLeg: Boolean
        
        when {
            hasExplicitDub && !hasExplicitLeg -> {
                finalHasDub = true
                finalHasLeg = false
            }
            !hasExplicitDub && hasExplicitLeg -> {
                finalHasDub = false
                finalHasLeg = true
            }
            hasExplicitDub && hasExplicitLeg -> {
                finalHasDub = true
                finalHasLeg = true
            }
            else -> {
                finalHasDub = false
                finalHasLeg = true
            }
        }
        
        // NOME LIMPO
        val cleanName = extractAnimeName(combinedTitle, selectFirst(".numEp")?.text())
        
        val isMovie = href.contains("/filmes/") || combinedTitle.contains("filme", ignoreCase = true)
        
        // POSTER - AQUI É ONDE VAMOS MUDAR!
        val sitePoster = try {
            // MÉTODO 1: Primeiro tentar pelas meta tags (og:image)
            selectFirst("meta[property='og:image']")?.attr("content")?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
                ?:
            // MÉTODO 2: Tentar pela tag img com data-src
            selectFirst("img[data-src]")?.attr("data-src")?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
                ?:
            // MÉTODO 3: Tentar pela tag img com src
            selectFirst("img[src]")?.attr("src")?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
        } catch (e: Exception) {
            null
        }

        if (debugMode) {
            println("\n🎯 ITEM FINAL:")
            println("   • Nome: $cleanName")
            println("   • URL: $href")
            println("   • Score: ${score?.toString() ?: "null"}")
            println("   • Dub: $finalHasDub, Leg: $finalHasLeg")
            println("   • Poster: ${sitePoster?.take(80)}...")
            println("=".repeat(60))
        }

        return newAnimeSearchResponse(cleanName, fixUrl(href)) {
            this.posterUrl = sitePoster
            this.type = if (isMovie) TvType.Movie else TvType.Anime
            
            // AVALIAÇÃO (badge acima)
            this.score = score
            
            // DUB/LEG (badge abaixo)
            if (finalHasDub || finalHasLeg) {
                addDubStatus(dubExist = finalHasDub, subExist = finalHasLeg)
            }
        }
    }

    // ============ EXTRACTION DE SCORE ============
    private fun extractScoreAdvanced(element: Element, debugMode: Boolean = true): Pair<String?, String?> {
        val selectors = listOf(
            ".horaUltimosEps" to "Seletor padrão .horaUltimosEps",
            ".rating" to "Seletor .rating",
            ".score" to "Seletor .score",
            ".numEp + span" to "Próximo ao .numEp",
            ".episodes + span" to "Próximo a .episodes",
            ".card-footer span" to "No rodapé do card",
            "span:contains(★)" to "Span com estrela",
            "span:contains(/10)" to "Span com /10",
            "[class*='rating']" to "Classe contém 'rating'",
            "[class*='score']" to "Classe contém 'score'",
            "small" to "Tag small",
            "b" to "Tag bold"
        )

        // Buscar no próprio elemento
        for ((selector, description) in selectors) {
            val found = element.selectFirst(selector)?.text()?.trim()
            if (!found.isNullOrBlank() && isScoreLike(found)) {
                if (debugMode) println("✅ Score: '$found' (via: $description)")
                return found to selector
            }
        }

        // Buscar no elemento pai
        element.parent()?.let { parent ->
            for ((selector, description) in selectors) {
                val found = parent.selectFirst(selector)?.text()?.trim()
                if (!found.isNullOrBlank() && isScoreLike(found)) {
                    if (debugMode) println("✅ Score no pai: '$found'")
                    return found to "parent.$selector"
                }
            }
        }

        // Buscar via regex
        val html = element.outerHtml()
        val scoreRegexes = listOf(
            Regex("""(\d+\.\d+|\d+)\s*(?:★|/10|pontos)"""),
            Regex("""class="[^"]*(?:rating|score)[^"]*">([^<]+)""")
        )

        for (regex in scoreRegexes) {
            val match = regex.find(html)
            if (match != null) {
                val found = match.groupValues[1].trim()
                if (isScoreLike(found)) {
                    if (debugMode) println("✅ Score via regex: '$found'")
                    return found to "regex"
                }
            }
        }

        if (debugMode) println("❌ Nenhum score encontrado")
        return null to null
    }

    // ============ IDENTIFICAR SCORE ============
    private fun isScoreLike(text: String): Boolean {
        return when {
            text.equals("N/A", ignoreCase = true) -> true
            text.matches(Regex("""^\d+(\.\d+)?$""")) -> true
            text.matches(Regex("""^\d+(\.\d+)?/10$""")) -> true
            text.contains("★") -> true
            text.contains("pontos", ignoreCase = true) -> true
            else -> false
        }
    }

    // ============ LIMPAR NOME ============
    private fun extractAnimeName(fullText: String, episodeText: String?): String {
        var cleanName = fullText
        
        episodeText?.let {
            cleanName = cleanName.replace(it, "").trim()
        }
        
        val patterns = listOf(
            Regex("(?i)\\(dublado\\)"),
            Regex("(?i)\\(legendado\\)"),
            Regex("(?i)todos os episódios"),
            Regex("\\s*-\\s*$"),
            Regex("\\(\\d{4}\\)")
        )
        
        patterns.forEach { pattern ->
            cleanName = cleanName.replace(pattern, "")
        }
        
        return cleanName.trim().replace(Regex("\\s+"), " ")
    }

    // ============ DETECTAR SE TEM PRÓXIMA PÁGINA ============
    private suspend fun detectHasNextPage(document: org.jsoup.nodes.Document, currentPageNum: Int): Boolean {
        try {
            val hasElements = document.select("article, .card, .anime-item").isNotEmpty()
            if (!hasElements) return false
            
            val hasPagination = document.select(".pagination, .page-numbers, .paginacao").isNotEmpty()
            val hasNextLink = document.select("a:contains(Próxima), a:contains(›), a:contains(>), a[href*='/${currentPageNum + 1}']").isNotEmpty()
            
            return hasElements && (hasPagination || hasNextLink)
            
        } catch (e: Exception) {
            return false
        }
    }

    // ============ GET MAIN PAGE ============
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return loadingMutex.withLock {
            try {
                println("\n" + "=".repeat(70))
                println("🔥 ANIMEFIRE: Carregando página $page")
                println("=".repeat(70))
                println("📊 Aba: '${request.name}'")
                println("📊 URL base: ${request.data}")
                
                // PAGINAÇÃO
                val basePath = request.data.removePrefix(mainUrl)
                val sitePageNumber = page + 1
                
                val pageUrl = if (sitePageNumber == 1) {
                    "$mainUrl$basePath"
                } else {
                    "$mainUrl$basePath/$sitePageNumber"
                }
                
                println("📊 URL da página: $pageUrl")
                println("-".repeat(70))
                
                kotlinx.coroutines.delay(200)
                
                val document = app.get(pageUrl, timeout = 25).document
                
                // VERIFICAR TODOS OS ELEMENTOS
                val allElements = document.select("article, .card, .anime-item")
                println("📊 Elementos container: ${allElements.size}")
                
                // PROCESSAR ITENS
                val elements = document.select("""
                    article a,
                    .card a,
                    .anime-item a,
                    a[href*='/animes/'],
                    a[href*='/filmes/']
                """).take(50)
                
                println("📊 Links encontrados: ${elements.size}")
                
                val homeItems = mutableListOf<SearchResponse>()
                
                elements.forEachIndexed { index, element ->
                    try {
                        // Debug apenas para os primeiros 2 itens
                        val debugMode = index < 2
                        val item = element.toSearchResponse(debugMode = debugMode)
                        if (item != null) {
                            homeItems.add(item)
                        }
                    } catch (e: Exception) {
                        // Ignorar erro
                    }
                }
                
                // DETECTAR PRÓXIMA PÁGINA
                val hasNextPage = detectHasNextPage(document, sitePageNumber)
                
                // RESULTADO
                println("\n" + "=".repeat(70))
                println("📊 RESULTADO PÁGINA $sitePageNumber:")
                println("   • Aba: '${request.name}'")
                println("   • Itens válidos: ${homeItems.size}")
                println("   • Tem próxima página? $hasNextPage")
                
                if (homeItems.isNotEmpty()) {
                    println("   • Primeiros itens:")
                    homeItems.take(3).forEachIndexed { i, item ->
                        println("     ${i + 1}. ${item.name}")
                    }
                }
                println("=".repeat(70) + "\n")
                
                kotlinx.coroutines.delay(150)
                
                // Retornar com o MESMO nome da aba
                newHomePageResponse(
                    request.name, // Nome original
                    homeItems.distinctBy { it.url },
                    hasNext = hasNextPage
                )
                
            } catch (e: Exception) {
                println("\n❌ ERRO na página $page: ${e.message}")
                newHomePageResponse(request.name, emptyList(), false)
            }
        }
    }

    // ============ SEARCH ============
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()
        
        val searchUrl = "$mainUrl$SEARCH_PATH/${query.trim().replace(" ", "-").lowercase()}"
        
        return try {
            val document = app.get(searchUrl, timeout = 15).document
            
            document.select("a[href*='/animes/'], a[href*='/filmes/']")
                .mapNotNull { it.toSearchResponse(debugMode = false) }
                .distinctBy { it.url }
                .take(30)
                
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ============ FUNÇÃO AUXILIAR PARA EXTRAIR TEXTO ============
    private fun extractTextAfterLabel(document: org.jsoup.nodes.Document, label: String): String? {
        return document.select("div.animeInfo:contains($label) span.spanAnimeInfo")
            .firstOrNull()?.text()?.trim()
    }

    // ============ FUNÇÃO PARA EXTRAIR ANO CORRETO ============
    private fun extractYear(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        
        // Extrair ano de datas como "Oct 8, 2025"
        val yearRegex = Regex("""(\d{4})""")
        val match = yearRegex.find(text)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    // ============ LOAD ============
    override suspend fun load(url: String): LoadResponse {
        return try {
            println("\n" + "=".repeat(80))
            println("🔥 ANIMEFIRE: Carregando anime")
            println("=".repeat(80))
            println("📊 URL: $url")
            
            val document = app.get(url, timeout = 35).document
            
            // ============ TÍTULO ============
            val title = document.selectFirst("h1.quicksand400")?.text()?.trim() 
                ?: document.selectFirst("h1.animeTitle, h1")?.text()?.trim() 
                ?: "Sem Título"
            
            println("📊 Título: $title")
            
            // ============ POSTER (CORREÇÃO - IGUAL AO SUPERFLIX) ============
            val poster = try {
                // MÉTODO 1: Primeiro tentar pelas meta tags (og:image) - MAIS CONFIÁVEL
                document.selectFirst("meta[property='og:image']")?.attr("content")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { fixUrl(it) }
                ?:
                // MÉTODO 2: Imagem principal da página
                document.selectFirst(".sub_animepage_img img, .poster img, .anime-poster img")?.let { img ->
                    when {
                        img.hasAttr("src") -> img.attr("src").takeIf { it.isNotBlank() }?.let { fixUrl(it) }
                        img.hasAttr("data-src") -> img.attr("data-src").takeIf { it.isNotBlank() }?.let { fixUrl(it) }
                        else -> null
                    }
                }
                ?:
                // MÉTODO 3: Qualquer imagem de anime
                document.selectFirst("img[src*='/animes/'], img[src*='/img/animes/']")
                    ?.attr("src")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { fixUrl(it) }
            } catch (e: Exception) {
                println("⚠️ Erro ao extrair poster: ${e.message}")
                null
            }
            
            println("📊 Poster encontrado: ${poster ?: "NÃO ENCONTRADO"}")
            if (poster != null) {
                println("   • URL: ${poster.take(100)}...")
            }
            
            // ============ BANNER/BACKGROUND ============
            val background = poster
            
            // ============ SINOPSE ============
            val synopsis = document.selectFirst("div.divSinopse span.spanAnimeInfo")
                ?.text()
                ?.trim()
                ?: document.selectFirst("div.divSinopse")
                ?.text()
                ?.replace("Sinopse:", "")
                ?.trim()
                ?: document.selectFirst("p.sinopse, .description")
                ?.text()
                ?.trim()
                ?: "Sinopse não disponível."
            
            println("📊 Sinopse (primeiros 100 chars): ${synopsis.take(100)}...")
            
            // ============ ANO ============
            val yearText = extractTextAfterLabel(document, "Ano:")
            val year = extractYear(yearText)
            println("📊 Ano: $year (texto: '$yearText')")
            
            // ============ STATUS DO ANIME ============
            val statusElement = document.selectFirst("div.animeInfo:contains(Status do Anime:) span.spanAnimeInfo")
            val statusText = statusElement?.text()?.trim() ?: "Desconhecido"
            println("📊 Status do Anime: '$statusText'")
            
            // Converter para ShowStatus do CloudStream
            val showStatus = when (statusText.lowercase()) {
                "em lançamento", "lançando", "lançamento", "em andamento" -> ShowStatus.Ongoing
                "completo", "finalizado", "concluído", "completado" -> ShowStatus.Completed
                else -> null
            }
            println("📊 Status convertido: $showStatus")
            
            // ============ GÊNEROS/TAGS ============
            val genres = mutableListOf<String>()
            
            // Extrair gêneros dos links
            document.select("div.animeInfo a[href*='/genero/']").forEach { element ->
                element.text().trim().takeIf { it.isNotBlank() }?.let { 
                    genres.add(it) 
                }
            }
            
            // Se não encontrou pelos links, tentar extrair do texto
            if (genres.isEmpty()) {
                document.select("div.animeInfo:contains(Gênero:) span.spanAnimeInfo")
                    .firstOrNull()
                    ?.text()
                    ?.trim()
                    ?.split(",", ";")
                    ?.forEach { genre ->
                        genre.trim().takeIf { it.isNotBlank() }?.let { 
                            genres.add(it) 
                        }
                    }
            }
            
            println("📊 Gêneros: ${genres.joinToString(", ")}")
            
            // ============ ÁUDIO (DUB/LEG) ============
            val audioText = extractTextAfterLabel(document, "Áudio:")
            val hasDub = audioText?.contains("dublado", ignoreCase = true) ?: false
            val hasSub = audioText?.contains("legendado", ignoreCase = true) ?: true
            
            println("📊 Áudio: $audioText (Dub: $hasDub, Leg: $hasSub)")
            
            // ============ ESTÚDIO ============
            val studio = extractTextAfterLabel(document, "Estúdios:")
            println("📊 Estúdio: $studio")
            
            // ============ SCORE ============
            val scoreText = document.selectFirst("#anime_score")?.text()?.trim()
            val score = scoreText?.toFloatOrNull()?.let { Score.from10(it) }
            println("📊 Score: $scoreText -> ${score?.toString()}")
            
            // ============ DETECTAR SE É FILME ============
            val isMovie = url.contains("/filmes/") || 
                         title.contains("filme", ignoreCase = true)
            
            println("📊 É filme? $isMovie")
            
            // ============ EXTRAIR EPISÓDIOS - FUNCIONAL ============
            val episodes = extractAllEpisodesFuncional(document, url)
            println("📊 Episódios extraídos: ${episodes.size}")
            
            // ============ CRIAR LOAD RESPONSE ============
            val response = newAnimeLoadResponse(
                title, 
                url, 
                if (isMovie) TvType.Movie else TvType.Anime
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.year = year
                this.plot = synopsis
                this.tags = genres
                this.score = score
                
                // Adicionar estúdio (se disponível)
                studio?.let { 
                    try {
                        // Usando reflexão para ser compatível com CloudStream 3
                        val studioField = this::class.members.find { it.name == "studio" }
                        if (studioField != null) {
                            studioField.call(this, it)
                            println("✅ Estúdio adicionado: $it")
                        }
                    } catch (e: Exception) {
                        println("⚠️ Erro ao adicionar estúdio: ${e.message}")
                    }
                }
                
                // ADICIONAR EPISÓDIOS CORRETAMENTE
                if (episodes.isNotEmpty()) {
                    addEpisodes(DubStatus.Subbed, episodes)
                    println("✅ Episódios adicionados via addEpisodes: ${episodes.size}")
                } else {
                    println("⚠️ Nenhum episódio para adicionar")
                }
            }
            
            // ============ DEBUG FINAL ============
            println("\n" + "=".repeat(80))
            println("📊 LOAD COMPLETO:")
            println("   • Título: ${response.name}")
            println("   • Tipo: ${response.type}")
            println("   • Ano: ${response.year}")
            println("   • Score: ${response.score?.toString()}")
            println("   • Status: $statusText")
            println("   • É filme? $isMovie")
            println("   • Episódios: ${episodes.size}")
            println("   • Poster URL: ${poster?.take(50)}...")
            println("   • Background URL: ${background?.take(50)}...")
            println("=".repeat(80) + "\n")
            
            response
            
        } catch (e: Exception) {
            println("\n❌ ERRO no load: ${e.message}")
            e.printStackTrace()
            
            newAnimeLoadResponse("Erro ao carregar", url, TvType.Anime) {
                this.plot = "Não foi possível carregar esta página. Erro: ${e.message}"
            }
        }
    }

    // ============ EXTRACT EPISODES - FUNCIONAL ============
    private fun extractAllEpisodesFuncional(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        println("\n🔍 EXTRACTING EPISODES")
        println("📊 URL base: $baseUrl")
        
        val episodes = mutableListOf<Episode>()
        
        // Extrair nome do anime para remover dos títulos dos episódios
        val animeTitle = document.selectFirst("h1.quicksand400")?.text()?.trim() ?: ""
        println("📊 Nome do anime para limpar: '$animeTitle'")
        
        // TENTAR VÁRIOS SELETORES EM ORDEM
        val selectors = listOf(
            "div.div_video_list a.lEp.epT",
            "a.lEp.epT, a.lEp, .divListaEps a, [href*='/video/'], [href*='/episodio/']"
        )
        
        var episodeElements: org.jsoup.select.Elements? = null
        
        for (selector in selectors) {
            val elements = document.select(selector)
            if (elements.isNotEmpty()) {
                episodeElements = elements
                println("✅ Encontrados com seletor: '$selector'")
                break
            }
        }
        
        // Se ainda não encontrou, buscar links específicos
        if (episodeElements == null || episodeElements.isEmpty()) {
            val allLinks = document.select("a[href]")
            val filteredElements = mutableListOf<org.jsoup.nodes.Element>()
            
            allLinks.forEach { element ->
                val href = element.attr("href")
                if (href.matches(Regex(".*/animes/[^/]+/\\d+/?$"))) {
                    filteredElements.add(element)
                }
            }
            
            episodeElements = org.jsoup.select.Elements(filteredElements)
        }
        
        println("📊 Elementos de episódio encontrados: ${episodeElements?.size ?: 0}")
        
        if (episodeElements == null || episodeElements.isEmpty()) {
            println("❌ NENHUM EPISÓDIO ENCONTRADO!")
            return emptyList()
        }
        
        println("✅ PROCESSANDO EPISÓDIOS...")
        
        episodeElements.forEachIndexed { index, element ->
            try {
                val href = element.attr("href")
                var text = element.text().trim()
                
                if (href.isBlank() || text.isBlank()) {
                    println("   ⚠️ Episódio ${index + 1}: href ou texto vazio")
                    return@forEachIndexed
                }
                
                // LIMPAR NOME DO EPISÓDIO - REMOVER NOME DO ANIME
                if (animeTitle.isNotBlank()) {
                    text = text.replace(animeTitle, "").trim()
                    text = text.replace(Regex("^\\s*-\\s*"), "").trim()
                }
                
                // Se ainda tiver muito texto, tentar simplificar
                if (text.length > 30) {
                    // Tentar extrair apenas "Episódio X"
                    val epMatch = Regex("""Epis[oó]dio\s*\d+""", RegexOption.IGNORE_CASE).find(text)
                    if (epMatch != null) {
                        text = epMatch.value
                    }
                }
                
                // Garantir que tenha pelo menos "Episódio X"
                val epNum = extractEpisodeNumberFuncional(text, href) ?: (index + 1)
                if (!text.contains(Regex("""Epis[oó]dio""", RegexOption.IGNORE_CASE))) {
                    text = "Episódio $epNum"
                }
                
                // EXTRAIR NÚMERO DO EPISÓDIO
                val episodeNumber = epNum
                
                // CORRIGIR URL
                val fixedHref = when {
                    href.startsWith("//") -> "https:$href"
                    href.startsWith("/") -> "$mainUrl$href"
                    !href.startsWith("http") -> "$mainUrl/$href"
                    else -> href
                }
                
                // CRIAR EPISÓDIO
                val episode = newEpisode(fixedHref) {
                    this.name = text
                    this.episode = episodeNumber
                    this.season = 1
                }
                
                episodes.add(episode)
                
                // DEBUG: Mostrar apenas os primeiros 3
                if (index < 3) {
                    println("   ✅ Ep $episodeNumber: '$text'")
                }
                
            } catch (e: Exception) {
                println("   ❌ Erro no episódio ${index + 1}: ${e.message}")
            }
        }
        
        // Ordenar por número do episódio
        val sortedEpisodes = episodes.sortedBy { it.episode }
        
        println("\n📊 RESULTADO FINAL:")
        println("   • Total de episódios extraídos: ${sortedEpisodes.size}")
        if (sortedEpisodes.isNotEmpty()) {
            println("   • Primeiro episódio: ${sortedEpisodes.first().episode} - '${sortedEpisodes.first().name}'")
            println("   • Último episódio: ${sortedEpisodes.last().episode} - '${sortedEpisodes.last().name}'")
        }
        
        return sortedEpisodes
    }

    // ============ EXTRACT EPISODE NUMBER - FUNCIONAL ============
    private fun extractEpisodeNumberFuncional(text: String, href: String = ""): Int? {
        // Tentar extrair do texto primeiro
        val textPatterns = listOf(
            Regex("""Epis[oó]dio\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""Ep\.?\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""\b(\d+)$""")
        )
        
        for (pattern in textPatterns) {
            val match = pattern.find(text)
            if (match != null) {
                return match.groupValues[1].toIntOrNull()
            }
        }
        
        // Tentar da URL
        val urlPattern = Regex("""/animes/[^/]+/(\d+)$""")
        val urlMatch = urlPattern.find(href)
        if (urlMatch != null) {
            return urlMatch.groupValues[1].toIntOrNull()
        }
        
        return null
    }

    // ============ LOAD LINKS ============
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("\n" + "=".repeat(80))
        println("🔥 ANIMEFIRE: Carregando links para $data")
        println("=".repeat(80))
        
        return try {
            // Como AnimeFireVideoExtractor é um object, chame diretamente
            AnimeFireVideoExtractor.extractVideoLinks(data, mainUrl, name, callback)
        } catch (e: Exception) {
            println("❌ Erro no loadLinks: ${e.message}")
            false
        }
    }
}
