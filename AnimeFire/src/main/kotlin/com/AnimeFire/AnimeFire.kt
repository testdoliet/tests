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
        
        // ============ CATEGORIAS COM PRIORIDADE ============
        private val PRIORITY_CATEGORIES = listOf(
            "/em-lancamento" to "Em Lançamento",
            "/animes-atualizados" to "Atualizados"
        )
        
        private val OTHER_CATEGORIES = listOf(
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
            // Sempre inclui as prioritárias
            val selected = PRIORITY_CATEGORIES.toMutableList()
            
            // Adiciona 3 aleatórias das outras (5 total)
            selected.addAll(OTHER_CATEGORIES.shuffled().take(3))
            
            return selected.shuffled() // Embaralha a ordem final
        }
    }

    // ============ PÁGINA INICIAL COM 5 ABAS ============
    override val mainPage = mainPageOf(
        *getRandomCategories().map { (path, name) -> 
            "$mainUrl$path" to name 
        }.toTypedArray()
    )

    // ============ FUNÇÃO COM BADGES CORRIGIDAS ============
    private fun Element.toSearchResponse(isUpcomingSection: Boolean = false): AnimeSearchResponse? {
        val href = attr("href") ?: return null
        if (href.isBlank() || (!href.contains("/animes/") && !href.contains("/filmes/"))) return null
        
        // TÍTULO
        val titleElement = selectFirst("h3.animeTitle, .animeTitle, h3, .card-title") ?: return null
        val rawTitle = titleElement.text().trim()
        
        val titleAttr = attr("title")?.trim() ?: ""
        val combinedTitle = if (titleAttr.isNotBlank() && titleAttr.length > 3) titleAttr else rawTitle
        
        if (combinedTitle.isBlank()) return null
        
        // ✅ BADGES CORRIGIDAS: DETECTAR ÁUDIO PRECISAMENTE
        val hasExplicitDub = combinedTitle.contains("dublado", ignoreCase = true)
        val hasExplicitLeg = combinedTitle.contains("legendado", ignoreCase = true)
        
        // Lógica: AnimeFire padrão é legendado, a não ser que especifique dublado
        val finalHasDub: Boolean
        val finalHasLeg: Boolean
        
        when {
            hasExplicitDub && !hasExplicitLeg -> {
                finalHasDub = true   // Só dublado
                finalHasLeg = false
            }
            !hasExplicitDub && hasExplicitLeg -> {
                finalHasDub = false
                finalHasLeg = true    // Só legendado
            }
            hasExplicitDub && hasExplicitLeg -> {
                finalHasDub = true    // Ambos disponíveis
                finalHasLeg = true
            }
            else -> {
                // Padrão do site: se não especificar, assume legendado
                finalHasDub = false
                finalHasLeg = true
            }
        }
        
        // NOME LIMPO
        val cleanName = extractAnimeName(combinedTitle, selectFirst(".numEp")?.text())
        
        // FILTRO N/A (exceto em lançamento/atualizados)
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
        val sitePoster = selectFirst("img.imgAnimes, img.card-img-top, img[src*='.jpg'], img[src*='.png']")?.let { img ->
            when {
                img.hasAttr("data-src") && img.attr("data-src").contains("http") -> img.attr("data-src")
                img.hasAttr("src") && img.attr("src").contains("http") -> img.attr("src")
                else -> null
            }
        }?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }

        return newAnimeSearchResponse(cleanName, fixUrl(href)) {
            this.posterUrl = sitePoster
            this.type = if (isMovie) TvType.Movie else TvType.Anime
            this.score = score
            
            // ✅ BADGES APLICADAS CORRETAMENTE
            if (finalHasDub || finalHasLeg) {
                addDubStatus(dubExist = finalHasDub, subExist = finalHasLeg)
            }
            
            // DEBUG
            println("ANIMEFIRE: '$cleanName' - Dub: $finalHasDub, Leg: $finalHasLeg")
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

    // ============ GET MAIN PAGE COM PAGINAÇÃO INFINITA ============
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Detectar se é aba de lançamento/atualizados
        val isUpcomingSection = request.data.contains("/em-lancamento") || 
                               request.data.contains("/animes-atualizados")
        
        // ✅ PAGINAÇÃO: /2, /3, etc para páginas seguintes
        val pageUrl = if (page > 0) {
            // Adiciona número da página: /em-lancamento/2, /em-lancamento/3, etc
            val baseUrl = request.data.removeSuffix("/")
            "$baseUrl/${page + 1}"
        } else {
            request.data
        }
        
        val document = try {
            app.get(pageUrl, timeout = 10).document
        } catch (e: Exception) {
            return newHomePageResponse(request.name, emptyList(), false)
        }
        
        // Verificar se há mais páginas (scrolling infinito)
        val hasNextPage = document.select("a[href*='/${page + 2}'], .pagination a:contains(${page + 2})")
            .isNotEmpty() || document.select(".load-more").isNotEmpty()
        
        // EMBARALHAR DINAMICAMENTE
        val elements = document.select("a[href*='/animes/'], a[href*='/filmes/']")
            .filter { 
                it.hasAttr("href") && 
                it.selectFirst("h3, .animeTitle, .card-title") != null
            }
            .shuffled()
            .take(20) // Mais itens por página
        
        val homeItems = elements.mapNotNull { element ->
            element.toSearchResponse(isUpcomingSection = isUpcomingSection)
        }
        
        return newHomePageResponse(
            if (page > 0) "${request.name} - Página ${page + 1}" else request.name,
            homeItems,
            hasNext = hasNextPage && homeItems.isNotEmpty()
        )
    }

    // ============ BUSCA COM PAGINAÇÃO ============
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()
        
        val searchUrl = "$mainUrl$SEARCH_PATH/${query.trim().replace(" ", "-").lowercase()}"
        val document = try {
            app.get(searchUrl, timeout = 15).document
        } catch (e: Exception) {
            return emptyList()
        }
        
        return document.select("a[href*='/animes/'], a[href*='/filmes/']")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
            .take(30)
    }

    // ============ LOAD COM EPISÓDIOS ============
    override suspend fun load(url: String): LoadResponse {
        val document = try {
            app.get(url, timeout = 20).document
        } catch (e: Exception) {
            return newAnimeLoadResponse("Erro ao carregar", url, TvType.Anime) {
                this.plot = "Não foi possível carregar esta página."
            }
        }
        
        // TÍTULO
        val title = document.selectFirst("h1.animeTitle, h1")?.text()?.trim() ?: "Sem Título"
        
        // POSTER
        val poster = document.selectFirst("img.imgAnimes, .poster img")?.attr("src")?.let { fixUrl(it) }
        
        // SINOPSE
        val synopsis = document.selectFirst("p.sinopse, .description")?.text()?.trim()
            ?: "Sinopse não disponível."
        
        // INFORMAÇÕES
        val year = document.select("div.animeInfo:contains(Ano:) span.spanAnimeInfo")
            .firstOrNull()?.text()?.trim()?.toIntOrNull()
        
        val genres = document.select("div.animeInfo:contains(Gênero:) a")
            .map { it.text().trim() }
        
        val isMovie = url.contains("/filmes/") || title.contains("filme", ignoreCase = true)
        
        // EPISÓDIOS
        val episodes = extractAllEpisodes(document, url)
        
        return newAnimeLoadResponse(title, url, if (isMovie) TvType.Movie else TvType.Anime) {
            this.posterUrl = poster
            this.year = year
            this.plot = synopsis
            this.tags = genres
            
            // Adicionar episódios
            try {
                val episodesField = this::class.members.find { it.name == "episodes" }
                episodesField?.call(this, episodes)
            } catch (e: Exception) {
                // Fallback silencioso
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
                // Ignorar erro
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
