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
        println("🔥 ANIMEFIRE: Plugin inicializado com paginação corrigida")
    }

    override val mainPage = mainPageOf(
        *getRandomTabs().map { (path, name) -> 
            "$mainUrl$path" to name 
        }.toTypedArray()
    )

    // ============ FUNÇÃO DE EXTRACTION ============
    private fun Element.toSearchResponse(isUpcomingSection: Boolean = false, debugMode: Boolean = true): AnimeSearchResponse? {
        val href = attr("href") ?: return null
        if (href.isBlank() || (!href.contains("/animes/") && !href.contains("/filmes/"))) return null
        
        if (debugMode) {
            println("\n" + "=".repeat(60))
            println("🔍 DEBUG EXTRACTION")
            println("=".repeat(60))
            println("📄 HTML (resumido): ${outerHtml().take(200)}...")
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
            println("   • É upcoming? $isUpcomingSection")
        }
        
        // LÓGICA DE FILTRO
        val shouldKeepItem = when {
            isUpcomingSection -> {
                if (debugMode) println("✅ Mantido: Seção de lançamentos")
                true
            }
            scoreText != null && scoreText != "N/A" -> {
                if (debugMode) println("✅ Mantido: Tem avaliação válida")
                true
            }
            scoreText == null -> {
                if (debugMode) println("⚠️ Sem avaliação, mantendo")
                true
            }
            else -> {
                if (debugMode) println("❌ Filtrado: Avaliação N/A")
                false
            }
        }
        
        if (!shouldKeepItem) return null
        
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
    private suspend fun detectHasNextPage(document: org.jsoup.nodes.Document, currentUrl: String, currentPageNum: Int): Boolean {
        try {
            // Se não tem elementos nesta página, não tem próxima
            val hasElements = document.select("article, .card, .anime-item").isNotEmpty()
            if (!hasElements) return false
            
            // Verificar se existe paginação no site
            val hasPagination = document.select(".pagination, .page-numbers, .paginacao").isNotEmpty()
            val hasNextLink = document.select("a:contains(Próxima), a:contains(›), a:contains(>), a[href*='/${currentPageNum + 1}']").isNotEmpty()
            
            // Se tem elementos E tem links de paginação, provavelmente tem próxima
            return hasElements && (hasPagination || hasNextLink)
            
        } catch (e: Exception) {
            return false
        }
    }

    // ============ GET MAIN PAGE - CORRIGIDO ============
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return loadingMutex.withLock {
            try {
                println("\n" + "=".repeat(70))
                println("🔥 ANIMEFIRE: Carregando página $page")
                println("=".repeat(70))
                println("📊 Aba: '${request.name}'")
                println("📊 URL base: ${request.data}")
                
                // ============ PAGINAÇÃO CORRETA ============
                val basePath = request.data.removePrefix(mainUrl)
                
                // CloudStream page 0 = primeira página do site
                // CloudStream page 1 = segunda página do site (/2)
                // CloudStream page 2 = terceira página do site (/3)
                val sitePageNumber = page + 1
                
                val pageUrl = if (sitePageNumber == 1) {
                    // Primeira página: URL sem número
                    "$mainUrl$basePath"
                } else {
                    // Páginas seguintes: /2, /3, etc.
                    "$mainUrl$basePath/$sitePageNumber"
                }
                
                println("📊 URL da página: $pageUrl")
                println("-".repeat(70))
                
                // Delay para não sobrecarregar
                kotlinx.coroutines.delay(200)
                
                val document = app.get(pageUrl, timeout = 25).document
                
                val isUpcomingSection = basePath.contains("/em-lancamento") || 
                                       basePath.contains("/animes-atualizados")
                
                // ============ PROCESSAR ITENS ============
                val elements = document.select("""
                    article a,
                    .card a,
                    .anime-item a,
                    a[href*='/animes/'],
                    a[href*='/filmes/']
                """).take(50) // Aumentei para 50 itens por página
                
                println("📊 Elementos encontrados: ${elements.size}")
                
                val homeItems = mutableListOf<SearchResponse>()
                
                elements.forEachIndexed { index, element ->
                    try {
                        // Debug apenas para os primeiros 2 itens
                        val debugMode = index < 2
                        val item = element.toSearchResponse(
                            isUpcomingSection = isUpcomingSection,
                            debugMode = debugMode
                        )
                        if (item != null) {
                            homeItems.add(item)
                        }
                    } catch (e: Exception) {
                        // Ignorar erro
                    }
                }
                
                // ============ DETECTAR PRÓXIMA PÁGINA ============
                val hasNextPage = detectHasNextPage(document, pageUrl, sitePageNumber)
                
                // ============ IMPORTANTE: MANTER MESMO NOME ============
                // NÃO muda o nome da aba!
                val tabName = request.name
                
                // ============ RESULTADO ============
                println("\n" + "=".repeat(70))
                println("📊 RESULTADO PÁGINA $sitePageNumber:")
                println("   • Aba: '$tabName'")
                println("   • Itens processados: ${homeItems.size}")
                println("   • Tem próxima página? $hasNextPage")
                
                if (homeItems.isNotEmpty()) {
                    println("   • Primeiros itens:")
                    homeItems.take(3).forEachIndexed { i, item ->
                        println("     ${i + 1}. ${item.name}")
                    }
                } else {
                    println("   ⚠️ Nenhum item encontrado!")
                }
                println("=".repeat(70) + "\n")
                
                // Delay antes de retornar
                kotlinx.coroutines.delay(150)
                
                // Retornar com o MESMO nome da aba
                newHomePageResponse(
                    tabName, // ← Nome original, sem (P2), (P3), etc.
                    homeItems.distinctBy { it.url },
                    hasNext = hasNextPage
                )
                
            } catch (e: Exception) {
                println("\n❌ ERRO na página $page: ${e.message}")
                // Em caso de erro, retorna lista vazia SEM próxima página
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

    // ============ LOAD ============
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
                
                // Adicionar episódios
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

    // ============ EXTRACT EPISODES ============
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

    // ============ LOAD LINKS ============
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return false
    }
}
