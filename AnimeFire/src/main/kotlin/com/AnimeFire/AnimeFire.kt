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
        
        // ============ MUTEX PARA UMA ABA POR VEZ ============
        private val loadingMutex = Mutex()
        
        // ============ SISTEMA DE SESSÃO ============
        private var currentSessionTabs: List<Pair<String, String>> = emptyList()
        private var sessionId = System.currentTimeMillis()
        
        // ============ TODAS CATEGORIAS DISPONÍVEIS ============
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
        
        // ============ GERA 8 ABAS ALEATÓRIAS POR SESSÃO ============
        fun getRandomTabs(count: Int = 8): List<Pair<String, String>> {
            // Se já temos abas nesta sessão, usa elas
            if (currentSessionTabs.isNotEmpty()) {
                return currentSessionTabs
            }
            
            // Gera novas abas
            val newTabs = ALL_CATEGORIES.shuffled().take(count)
            currentSessionTabs = newTabs
            
            println("🎲 ANIMEFIRE: Nova sessão $sessionId - ${newTabs.size} abas")
            newTabs.forEachIndexed { i, (_, name) ->
                println("  $i. $name")
            }
            
            return newTabs
        }
        
        // Função para forçar novas abas
        fun resetTabs() {
            currentSessionTabs = emptyList()
            sessionId = System.currentTimeMillis()
            println("🔄 ANIMEFIRE: Resetando abas, nova sessão: $sessionId")
        }
    }

    init {
        println("🔥 ANIMEFIRE: Plugin inicializado - Sessão: $sessionId")
    }

    // ============ PÁGINA INICIAL DINÂMICA ============
    override val mainPage = mainPageOf(
        *getRandomTabs().map { (path, name) -> 
            "$mainUrl$path?session=$sessionId" to name 
        }.toTypedArray()
    )

    // ============ FUNÇÃO PRINCIPAL DE PARSING ============
    private fun Element.toSearchResponse(isUpcomingSection: Boolean = false): AnimeSearchResponse? {
        val href = attr("href") ?: return null
        if (href.isBlank() || (!href.contains("/animes/") && !href.contains("/filmes/"))) return null
        
        // TÍTULO
        val titleElement = selectFirst("h3.animeTitle, .animeTitle, h3, .card-title") ?: return null
        val rawTitle = titleElement.text().trim()
        
        val titleAttr = attr("title")?.trim() ?: ""
        val combinedTitle = if (titleAttr.isNotBlank() && titleAttr.length > 3) titleAttr else rawTitle
        
        if (combinedTitle.isBlank()) return null
        
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
        
        // FILTRO N/A (exceto em Lançamentos/Atualizados)
        val scoreText = selectFirst(".horaUltimosEps, .rating, .score")?.text()?.trim()
        
        if ((scoreText == "N/A" || scoreText == null) && !isUpcomingSection) {
            return null
        }
        
        val score = when {
            scoreText == null || scoreText == "N/A" -> Score.from10(0f)
            else -> scoreText.toFloatOrNull()?.let { Score.from10(it) } ?: Score.from10(0f)
        }
        
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

        return newAnimeSearchResponse(cleanName, fixUrl(href)) {
            this.posterUrl = sitePoster
            this.type = if (isMovie) TvType.Movie else TvType.Anime
            this.score = score
            
            if (finalHasDub || finalHasLeg) {
                addDubStatus(dubExist = finalHasDub, subExist = finalHasLeg)
            }
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

    // ============ GET MAIN PAGE - PAGINAÇÃO TOTALMENTE CORRIGIDA ============
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return loadingMutex.withLock {
            try {
                // ============ DEBUG EXTREMAMENTE DETALHADO ============
                println("\n" + "=".repeat(80))
                println("🔥 ANIMEFIRE DEBUG - PAGINAÇÃO")
                println("=".repeat(80))
                println("📊 Parâmetros recebidos do Cloudstream:")
                println("   • Cloudstream Page: $page")
                println("   • Request Name: '${request.name}'")
                println("   • Request Data: ${request.data}")
                println("   • Session ID: $sessionId")
                println("-".repeat(80))
                
                // Remove query string da URL
                val cleanData = request.data.substringBefore("?session=")
                val basePath = cleanData.removePrefix(mainUrl)
                
                println("📊 Processando URL:")
                println("   • URL Completa: ${request.data}")
                println("   • Base Path: '$basePath'")
                
                // ============ PAGINAÇÃO CORRETA DEFINITIVA ============
                // IMPORTANTE: Cloudstream SEMPRE começa com page=1
                // page=1 → Página 1 do site (sem número)
                // page=2 → Página 2 do site (/2)
                // page=3 → Página 3 do site (/3)
                
                val sitePageNumber = page  // page=1 → página 1 do site
                val pageUrl = if (page == 1) {
                    // Primeira página: SEM número no final
                    "$mainUrl$basePath"
                } else {
                    // Páginas seguintes: COM número
                    "$mainUrl$basePath/$page"
                }
                
                println("\n📊 PAGINAÇÃO CALCULADA:")
                println("   • Cloudstream Page: $page")
                println("   • Site Page Number: $sitePageNumber")
                println("   • URL Gerada: $pageUrl")
                println("   • É primeira página? ${page == 1}")
                println("-".repeat(80))
                
                // Delay mínimo
                kotlinx.coroutines.delay(300)
                
                println("🌐 Fazendo requisição para: $pageUrl")
                val document = app.get(pageUrl, timeout = 30).document
                println("✅ Página carregada com sucesso")
                
                // Verificar se é seção de lançamentos
                val isUpcomingSection = basePath.contains("/em-lancamento") || 
                                       basePath.contains("/animes-atualizados")
                
                // Coletar todos os elementos
                val elements = document.select("""
                    article a,
                    .card a,
                    .anime-item a,
                    a[href*='/animes/'],
                    a[href*='/filmes/']
                """).take(30)
                
                println("📊 Encontrados ${elements.size} elementos na página")
                
                val homeItems = mutableListOf<SearchResponse>()
                
                // Processar elementos
                elements.forEachIndexed { index, element ->
                    try {
                        val item = element.toSearchResponse(isUpcomingSection = isUpcomingSection)
                        if (item != null) {
                            homeItems.add(item)
                            if (index < 3) { // Log dos primeiros 3 itens
                                println("   • Item ${index + 1}: ${item.name}")
                            }
                        }
                    } catch (e: Exception) {
                        // Ignorar erro em item específico
                    }
                }
                
                // ============ DETECTAR PRÓXIMA PÁGINA ============
                val nextSitePage = page + 1  // page=1 → próxima é 2, page=2 → próxima é 3
                
                println("\n🔍 PROCURANDO PRÓXIMA PÁGINA:")
                println("   • Página atual: $page")
                println("   • Próxima página: $nextSitePage")
                
                // Procura por links da próxima página
                val linksForNextPage = document.select("a[href*='/$nextSitePage']")
                val hasNumericLink = linksForNextPage.isNotEmpty()
                
                // Procura por botões "Próxima"
                val nextButtons = document.select("""
                    a:contains(Próxima),
                    a:contains(Próximo),
                    a:contains(>),
                    a:contains(›)
                """)
                val hasNextButton = nextButtons.isNotEmpty()
                
                val hasNextPage = hasNumericLink || hasNextButton
                
                println("   • Links para /$nextSitePage: ${linksForNextPage.size}")
                println("   • Botões 'Próxima': ${nextButtons.size}")
                println("   • Tem próxima página? $hasNextPage")
                
                // ============ NOME DA ABA ============
                val tabName = if (page > 1) {
                    "${request.name} (P$page)"
                } else {
                    request.name
                }
                
                println("\n📊 RESULTADO FINAL:")
                println("   • Nome da aba: '$tabName'")
                println("   • Itens encontrados: ${homeItems.size}")
                println("   • Tem próxima página? $hasNextPage")
                println("=".repeat(80) + "\n")
                
                // Delay final antes de liberar mutex
                kotlinx.coroutines.delay(200)
                
                newHomePageResponse(
                    tabName,
                    homeItems.distinctBy { it.url },
                    hasNext = hasNextPage
                )
                
            } catch (e: Exception) {
                println("\n❌ ANIMEFIRE: ERRO CRÍTICO")
                println("   • Aba: '${request.name}'")
                println("   • Cloudstream Page: $page")
                println("   • Erro: ${e.message}")
                println("   • Stacktrace: ${e.stackTrace.take(3).joinToString("\n       ")}")
                println("=".repeat(80) + "\n")
                
                // Liberar rápido em caso de erro
                kotlinx.coroutines.delay(100)
                newHomePageResponse(request.name, emptyList(), false)
            }
        }
    }

    // ============ BUSCA ============
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()
        
        val searchUrl = "$mainUrl$SEARCH_PATH/${query.trim().replace(" ", "-").lowercase()}"
        
        return try {
            val document = app.get(searchUrl, timeout = 15).document
            
            document.select("a[href*='/animes/'], a[href*='/filmes/']")
                .mapNotNull { it.toSearchResponse() }
                .distinctBy { it.url }
                .take(30)
                
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ============ LOAD (PÁGINA DO ANIME) ============
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
                
                // Adicionar episódios (se disponível)
                try {
                    val episodesField = this::class.members.find { it.name == "episodes" }
                    episodesField?.call(this, episodes)
                } catch (e: Exception) {
                    // Campo pode não existir em algumas versões
                }
            }
            
        } catch (e: Exception) {
            newAnimeLoadResponse("Erro ao carregar", url, TvType.Anime) {
                this.plot = "Não foi possível carregar esta página."
            }
        }
    }

    // ============ EXTRAIR EPISÓDIOS ============
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
                // Ignorar episódio com erro
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
