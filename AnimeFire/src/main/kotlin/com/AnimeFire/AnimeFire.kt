package com.AnimeFire

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Element

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
        
        fun getRandomTabs(count: Int = 8): List<Pair<String, String>> {
            return ALL_CATEGORIES.shuffled().take(count)
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

    // ============ FUNÇÃO AVANÇADA DE EXTRACTION ============
    private fun Element.toSearchResponse(isUpcomingSection: Boolean = false, debugMode: Boolean = true): AnimeSearchResponse? {
        val href = attr("href") ?: return null
        if (href.isBlank() || (!href.contains("/animes/") && !href.contains("/filmes/"))) return null
        
        if (debugMode) {
            println("\n" + "=".repeat(60))
            println("🔍 DEBUG EXTRACTION - Elemento encontrado")
            println("=".repeat(60))
            println("📄 HTML (resumido): ${outerHtml().take(200)}...")
        }
        
        // TÍTULO
        val titleElement = selectFirst("h3.animeTitle, .animeTitle, h3, .card-title") ?: return null
        val rawTitle = titleElement.text().trim()
        
        val titleAttr = attr("title")?.trim() ?: ""
        val combinedTitle = if (titleAttr.isNotBlank() && titleAttr.length > 3) titleAttr else rawTitle
        
        if (combinedTitle.isBlank()) return null
        
        // ============ DETECÇÃO DE AVALIAÇÃO AVANÇADA ============
        val scoreResult = extractScoreAdvanced(this, debugMode)
        val scoreText = scoreResult.first
        val scoreSelector = scoreResult.second
        
        if (debugMode) {
            println("\n📊 RESULTADO AVALIAÇÃO:")
            println("   • Texto encontrado: '$scoreText'")
            println("   • Selector usado: '${scoreSelector ?: "NENHUM"}'")
            println("   • É upcoming section? $isUpcomingSection")
        }
        
        // ============ NOVA LÓGICA DE FILTRO ============
        val shouldKeepItem = when {
            // 1. Se é seção de lançamentos, sempre mantém
            isUpcomingSection -> {
                if (debugMode) println("✅ Mantido: É seção de lançamentos")
                true
            }
            
            // 2. Se encontrou avaliação e não é N/A
            scoreText != null && scoreText != "N/A" -> {
                if (debugMode) println("✅ Mantido: Tem avaliação válida: $scoreText")
                true
            }
            
            // 3. Se não encontrou avaliação (scoreText == null)
            scoreText == null -> {
                if (debugMode) println("⚠️ AVISO: Não encontrou avaliação")
                // TESTE: Manter mesmo sem avaliação
                true
            }
            
            // 4. Se é N/A
            else -> {
                if (debugMode) println("❌ Filtrado: Avaliação N/A em seção normal")
                false
            }
        }
        
        if (!shouldKeepItem) return null
        
        // ============ PROCESSAR SCORE FINAL ============
        val score = when {
            scoreText == null || scoreText == "N/A" -> null
            else -> scoreText.toFloatOrNull()?.let { Score.from10(it) }
        }
        
        if (debugMode) {
            println("   • Score processado: ${score?.toString() ?: "null"}")
        }
        
        // BADGES (DUB/LEG)
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
        
        // POSTER
        val sitePoster = try {
            selectFirst("img")?.let { img ->
                val src = when {
                    img.hasAttr("data-src") -> img.attr("data-src")
                    img.hasAttr("src") -> img.attr("src")
                    else -> null
                }?.takeIf { it.isNotBlank() }?.let { 
                    if (it.startsWith("//")) "https:$it"
                    else if (it.startsWith("/")) "$mainUrl$it"
                    else if (!it.startsWith("http")) "$mainUrl/$it"
                    else it
                }
                src
            }
        } catch (e: Exception) {
            null
        }?.let { fixUrl(it) }

        if (debugMode) {
            println("\n🎯 ITEM FINAL:")
            println("   • Nome: $cleanName")
            println("   • URL: $href")
            println("   • Score: ${score?.toString() ?: "null"}")
            println("   • Poster: ${sitePoster?.take(50) ?: "null"}...")
            println("=".repeat(60))
        }

        return newAnimeSearchResponse(cleanName, fixUrl(href)) {
            this.posterUrl = sitePoster
            this.type = if (isMovie) TvType.Movie else TvType.Anime
            this.score = score
            
            if (finalHasDub || finalHasLeg) {
                addDubStatus(dubExist = finalHasDub, subExist = finalHasLeg)
            }
        }
    }

    // ============ FUNÇÃO AVANÇADA DE EXTRACTION DE SCORE ============
    private fun extractScoreAdvanced(element: Element, debugMode: Boolean = true): Pair<String?, String?> {
        val selectors = listOf(
            // Seletores primários
            ".horaUltimosEps" to "Seletor padrão .horaUltimosEps",
            ".rating" to "Seletor .rating",
            ".score" to "Seletor .score",
            
            // Seletores secundários
            ".numEp + span" to "Próximo ao .numEp",
            ".episodes + span" to "Próximo a .episodes",
            ".card-footer span" to "No rodapé do card",
            ".card-body span:last-child" to "Último span do corpo",
            
            // Seletores por conteúdo
            "span:contains(★)" to "Span com estrela",
            "span:contains(⭐)" to "Span com emoji estrela",
            "span:contains(/10)" to "Span com /10",
            "span:contains(pontos)" to "Span com 'pontos'",
            
            // Seletores por classe parcial
            "[class*='rating']" to "Classe contém 'rating'",
            "[class*='score']" to "Classe contém 'score'",
            "[class*='rate']" to "Classe contém 'rate'",
            
            // Seletores genéricos
            "small" to "Tag small",
            "i + span" to "Span após ícone",
            "b" to "Tag bold",
            "strong" to "Tag strong"
        )

        // TENTATIVA 1: Buscar no próprio elemento
        for ((selector, description) in selectors) {
            val found = element.selectFirst(selector)?.text()?.trim()
            if (!found.isNullOrBlank() && isScoreLike(found)) {
                if (debugMode) println("✅ Score encontrado no elemento: '$found' (via: $description)")
                return found to selector
            }
        }

        // TENTATIVA 2: Buscar no elemento pai
        element.parent()?.let { parent ->
            for ((selector, description) in selectors) {
                val found = parent.selectFirst(selector)?.text()?.trim()
                if (!found.isNullOrBlank() && isScoreLike(found)) {
                    if (debugMode) println("✅ Score encontrado no pai: '$found' (via: $description)")
                    return found to "parent.$selector"
                }
            }
        }

        // TENTATIVA 3: Buscar em elementos irmãos
        element.siblingElements().forEach { sibling ->
            for ((selector, description) in selectors) {
                val found = sibling.selectFirst(selector)?.text()?.trim()
                if (!found.isNullOrBlank() && isScoreLike(found)) {
                    if (debugMode) println("✅ Score encontrado em irmão: '$found' (via: $description)")
                    return found to "sibling.$selector"
                }
            }
        }

        // TENTATIVA 4: Buscar no HTML com regex
        val html = element.outerHtml()
        val scoreRegexes = listOf(
            Regex("""(\d+\.\d+|\d+)\s*(?:★|⭐|/10|pontos)"""),
            Regex("""class="[^"]*(?:rating|score|rate)[^"]*">([^<]+)"""),
            Regex("""<span[^>]*>(.*?\d+\.?\d*.*?)</span>"""),
            Regex("""<small[^>]*>(.*?\d+\.?\d*.*?)</small>""")
        )

        for (regex in scoreRegexes) {
            val match = regex.find(html)
            if (match != null) {
                val found = match.groupValues[1].trim()
                if (isScoreLike(found)) {
                    if (debugMode) println("✅ Score encontrado via regex: '$found'")
                    return found to "regex"
                }
            }
        }

        // TENTATIVA 5: Verificar elementos próximos visualmente
        val nearbyElements = element.parent()?.children() ?: emptyList()
        for (nearby in nearbyElements) {
            if (nearby != element) {
                val text = nearby.text().trim()
                if (isScoreLike(text)) {
                    if (debugMode) println("✅ Score encontrado em elemento próximo: '$text'")
                    return text to "nearby"
                }
            }
        }

        if (debugMode) println("❌ Nenhum score encontrado")
        return null to null
    }

    // ============ FUNÇÃO PARA IDENTIFICAR SE É UM SCORE ============
    private fun isScoreLike(text: String): Boolean {
        return when {
            // É "N/A"
            text.equals("N/A", ignoreCase = true) -> true
            
            // É número (inteiro ou decimal)
            text.matches(Regex("""^\d+(\.\d+)?$""")) -> true
            
            // É número com /10
            text.matches(Regex("""^\d+(\.\d+)?/10$""")) -> true
            
            // Tem estrela
            text.contains("★") || text.contains("⭐") -> true
            
            // Palavras-chave
            text.contains("pontos", ignoreCase = true) -> true
            
            else -> false
        }
    }

    // ============ LIMPAR NOME DO ANIME ============
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

    // ============ GET MAIN PAGE ============
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return loadingMutex.withLock {
            try {
                println("\n" + "=".repeat(80))
                println("🔥 ANIMEFIRE: Carregando página")
                println("=".repeat(80))
                println("📊 Detalhes:")
                println("   • Aba: '${request.name}'")
                println("   • Cloudstream Page: $page")
                println("   • É página 0? ${page == 0}")
                
                // ============ PAGINAÇÃO ============
                val basePath = request.data.removePrefix(mainUrl)
                
                val pageUrl = if (page == 0) {
                    "$mainUrl$basePath"
                } else {
                    "$mainUrl$basePath/${page + 1}"
                }
                
                println("   • URL: $pageUrl")
                println("-".repeat(80))
                
                kotlinx.coroutines.delay(300)
                
                val document = app.get(pageUrl, timeout = 30).document
                
                val isUpcomingSection = basePath.contains("/em-lancamento") || 
                                       basePath.contains("/animes-atualizados")
                
                // ============ DEBUG: VER ESTRUTURA DA PÁGINA ============
                println("\n🔍 ESTRUTURA DA PÁGINA $pageUrl:")
                val allArticles = document.select("article, .card, .anime-item")
                println("   • Total de elementos container: ${allArticles.size}")
                
                if (allArticles.size > 0) {
                    // Analisar primeiro elemento
                    val firstElement = allArticles[0]
                    println("   • HTML do primeiro elemento (resumido):")
                    println("     ${firstElement.html().take(300)}...")
                    
                    // Verificar onde está a avaliação
                    val ratingElements = firstElement.select(".rating, .score, .horaUltimosEps")
                    println("   • Elementos de rating no primeiro: ${ratingElements.size}")
                    ratingElements.forEachIndexed { i, el ->
                        println("     $i. ${el.text()} -> ${el.className()}")
                    }
                }
                
                // ============ PROCESSAMENTO ============
                val elements = document.select("""
                    article a,
                    .card a,
                    .anime-item a,
                    a[href*='/animes/'],
                    a[href*='/filmes/']
                """).take(30)
                
                println("   • Links encontrados: ${elements.size}")
                
                val homeItems = mutableListOf<SearchResponse>()
                
                // Usar debugMode apenas para os primeiros 3 itens
                elements.forEachIndexed { index, element ->
                    try {
                        val debugMode = index < 3  // Debug apenas primeiros 3
                        val item = element.toSearchResponse(
                            isUpcomingSection = isUpcomingSection,
                            debugMode = debugMode
                        )
                        if (item != null) {
                            homeItems.add(item)
                        }
                    } catch (e: Exception) {
                        println("❌ Erro no item $index: ${e.message}")
                    }
                }
                
                // ============ DETECTAR PRÓXIMA PÁGINA ============
                val hasNextPage = if (page == 0) {
                    document.select("a[href*='/2']").isNotEmpty()
                } else {
                    val nextPageNum = page + 2
                    document.select("a[href*='/$nextPageNum']").isNotEmpty()
                }
                
                // ============ NOME DA ABA ============
                val sitePageNumber = if (page == 0) 1 else page + 1
                val tabName = if (page > 0) "${request.name} (P$sitePageNumber)" else request.name
                
                // ============ RESULTADO FINAL ============
                println("\n" + "=".repeat(80))
                println("📊 RESULTADO:")
                println("   • Aba: '$tabName'")
                println("   • Itens processados: ${homeItems.size}")
                println("   • Itens válidos: ${homeItems.size}")
                println("   • Próxima página? $hasNextPage")
                
                if (homeItems.isNotEmpty()) {
                    println("   • Exemplos:")
                    homeItems.take(3).forEachIndexed { i, item ->
                        println("     ${i + 1}. ${item.name} (score: ${item.score?.toString() ?: "null"})")
                    }
                } else {
                    println("   ⚠️ NENHUM ITEM RETORNADO!")
                    println("   Possíveis causas:")
                    println("     1. Filtro N/A removendo todos")
                    println("     2. Selector de título não funciona")
                    println("     3. Estrutura HTML diferente")
                }
                println("=".repeat(80) + "\n")
                
                kotlinx.coroutines.delay(200)
                
                newHomePageResponse(
                    tabName,
                    homeItems.distinctBy { it.url },
                    hasNext = hasNextPage
                )
                
            } catch (e: Exception) {
                println("\n❌ ERRO: ${e.message}")
                newHomePageResponse(request.name, emptyList(), false)
            }
        }
    }

    // ============ FUNÇÕES RESTANTES (MESMAS) ============
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

    override suspend fun load(url: String): LoadResponse {
        return try {
            val document = app.get(url, timeout = 25).document
            
            val title = document.selectFirst("h1.animeTitle, h1")?.text()?.trim() ?: "Sem Título"
            val poster = document.selectFirst("img.imgAnimes, .poster img")?.attr("src")?.let { fixUrl(it) }
            val synopsis = document.selectFirst("p.sinopse, .description")?.text()?.trim()
                ?: "Sinopse não disponível."
            
            val year = document.select("div.animeInfo:contains(Ano:) span.spanAnimeInfo")
                .firstOrNull()?.text()?.trim()?.toIntOrNull()
            
            val genres = document.select("div.animeInfo:contains(Gênero:) a")
                .map { it.text().trim() }
            
            val isMovie = url.contains("/filmes/") || title.contains("filme", ignoreCase = true)
            
            val episodes = extractAllEpisodes(document, url)
            
            newAnimeLoadResponse(title, url, if (isMovie) TvType.Movie else TvType.Anime) {
                this.posterUrl = poster
                this.year = year
                this.plot = synopsis
                this.tags = genres
                
                try {
                    val episodesField = this::class.members.find { it.name == "episodes" }
                    episodesField?.call(this, episodes)
                } catch (e: Exception) {}
            }
            
        } catch (e: Exception) {
            newAnimeLoadResponse("Erro ao carregar", url, TvType.Anime) {
                this.plot = "Não foi possível carregar esta página."
            }
        }
    }

    private fun extractAllEpisodes(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        document.select("a.lEp, .episode-item a, a[href*='/episodio']").forEach { element ->
            try {
                val episodeUrl = element.attr("href")?.takeIf { it.isNotBlank() } ?: return@forEach
                val episodeText = element.text().trim()
                val episodeNumber = Regex("(\\d{1,4})").find(episodeText)?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
                
                val audioType = when {
                    episodeText.contains("dublado", ignoreCase = true) -> " (Dub)"
                    episodeText.contains("legendado", ignoreCase = true) -> " (Leg)"
                    else -> ""
                }
                
                episodes.add(
                    newEpisode(Pair("Episódio $episodeNumber$audioType", fixUrl(episodeUrl))) {
                        this.name = "Episódio $episodeNumber$audioType"
                        this.episode = episodeNumber
                    }
                )
            } catch (e: Exception) {
                // Ignorar
            }
        }
        
        return episodes.sortedBy { it.episode }
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
