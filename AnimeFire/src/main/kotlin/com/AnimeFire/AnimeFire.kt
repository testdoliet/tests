package com.AnimeFire

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
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
        
        // ============ TODAS AS CATEGORIAS ============
        private val ALL_CATEGORIES = listOf(
            "/em-lancamento" to "Em Lançamento",
            "/animes-atualizados" to "Atualizados",
            "/top-animes" to "Top Animes",
            "/lista-de-animes-legendados" to "Animes Legendados",
            "/lista-de-animes-dublados" to "Animes Dublados",
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
            "/genero/vampiros" to "Vampiros"
        )
        
        // ============ SELECIONA 5 CATEGORIAS ALEATÓRIAS ============
        fun getRandomCategories(): List<Pair<String, String>> {
            return ALL_CATEGORIES.shuffled().distinctBy { it.first }.take(5)
        }
    }

    init {
        println("ANIMEFIRE: Inicializando - 5 abas aleatórias")
    }

    // ============ PÁGINA INICIAL ============
    override val mainPage = mainPageOf(
        *getRandomCategories().map { (path, name) -> 
            "$mainUrl$path" to name 
        }.toTypedArray()
    )

    // ============ FUNÇÃO PRINCIPAL COM POSTER CORRIGIDO ============
    private fun Element.toSearchResponse(isUpcomingSection: Boolean = false): AnimeSearchResponse? {
        val href = attr("href") ?: return null
        if (href.isBlank() || (!href.contains("/animes/") && !href.contains("/filmes/"))) return null
        
        // TÍTULO
        val titleElement = selectFirst("h3.animeTitle, .animeTitle, h3, .card-title") ?: return null
        val rawTitle = titleElement.text().trim()
        
        val titleAttr = attr("title")?.trim() ?: ""
        val combinedTitle = if (titleAttr.isNotBlank() && titleAttr.length > 3) titleAttr else rawTitle
        
        if (combinedTitle.isBlank()) return null
        
        // ✅ BADGES CORRIGIDAS
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
                finalHasLeg = true  // Padrão: legendado
            }
        }
        
        // NOME LIMPO
        val cleanName = extractAnimeName(combinedTitle, selectFirst(".numEp")?.text())
        
        // FILTRO N/A
        val scoreText = selectFirst(".horaUltimosEps, .rating, .score")?.text()?.trim()
        
        if ((scoreText == "N/A" || scoreText == null) && !isUpcomingSection) {
            return null
        }
        
        val score = when {
            scoreText == null || scoreText == "N/A" -> Score.from10(0f)
            else -> scoreText.toFloatOrNull()?.let { Score.from10(it) } ?: Score.from10(0f)
        }
        
        val isMovie = href.contains("/filmes/") || combinedTitle.contains("filme", ignoreCase = true)
        
        // ✅ POSTER CORRIGIDO (SEM ERRO NULL)
        val sitePoster = try {
            selectFirst("img")?.let { img ->
                val src = when {
                    img.hasAttr("data-src") -> img.attr("data-src")
                    img.hasAttr("src") -> img.attr("src")
                    else -> null
                }?.takeIf { it.isNotBlank() }?.let { 
                    if (it.startsWith("//")) {
                        "https:$it"
                    } else if (it.startsWith("/")) {
                        "$mainUrl$it"
                    } else if (!it.startsWith("http")) {
                        "$mainUrl/$it"
                    } else {
                        it
                    }
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

    // ============ EXTRATOR DE NOME ============
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

    // ============ GET MAIN PAGE COM PAGINAÇÃO INFINITA FUNCIONANDO ============
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        try {
            // ✅ PAGINAÇÃO CORRETA: /pagina/2, /pagina/3, etc
            val basePath = request.data.removePrefix(mainUrl)
            val pageSuffix = if (page > 0) "/${page + 1}" else ""
            val pageUrl = "$mainUrl${basePath.removeSuffix("/")}$pageSuffix"
            
            println("ANIMEFIRE: Carregando $pageUrl (página ${page + 1})")
            
            val document = app.get(pageUrl, timeout = 10).document
            
            val isUpcomingSection = basePath.contains("/em-lancamento") || 
                                   basePath.contains("/animes-atualizados")
            
            // ✅ DETECTAR SE TEM PRÓXIMA PÁGINA
            val hasNextPage = document.select("""
                a[href*="${basePath}/"], 
                a[href*="/${page + 2}"], 
                .pagination a, 
                .next-page, 
                .load-more
            """).isNotEmpty()
            
            val elements = document.select("a[href*='/animes/'], a[href*='/filmes/']")
                .filter { 
                    it.hasAttr("href") && 
                    it.selectFirst("h3, .animeTitle, .card-title, img") != null
                }
                .take(20)
            
            val homeItems = elements.mapNotNull { element ->
                try {
                    element.toSearchResponse(isUpcomingSection = isUpcomingSection)
                } catch (e: Exception) {
                    null
                }
            }
            
            println("ANIMEFIRE: ${request.name} - ${homeItems.size} itens, próxima página: $hasNextPage")
            
            return newHomePageResponse(
                if (page > 0) "${request.name} (Página ${page + 1})" else request.name,
                homeItems,
                hasNext = hasNextPage && homeItems.isNotEmpty()
            )
            
        } catch (e: Exception) {
            println("ANIMEFIRE: ERRO em ${request.name} página $page: ${e.message}")
            return newHomePageResponse(request.name, emptyList(), false)
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

    // ============ LOAD ============
    override suspend fun load(url: String): LoadResponse {
        return try {
            val document = app.get(url, timeout = 20).document
            
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
                } catch (e: Exception) {
                    // Silencioso
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
