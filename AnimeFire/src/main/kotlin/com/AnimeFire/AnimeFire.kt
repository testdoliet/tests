package com.AnimeFire

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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
        
        // MUTEX SIMPLES - sem delays complexos
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

    // ============ PÁGINA INICIAL ============
    override val mainPage = mainPageOf(
        *getRandomTabs().map { (path, name) -> 
            "$mainUrl$path" to name 
        }.toTypedArray()
    )

    // ============ GET MAIN PAGE SIMPLES COM MUTEX ============
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return loadingMutex.withLock {
            try {
                // Log básico
                println("📺 AnimeFire: Carregando '${request.name}' - página $page")
                
                // URL SIMPLES - igual SuperFlix
                val basePath = request.data.removePrefix(mainUrl)
                val pageUrl = if (page == 0) {
                    // Primeira página
                    "$mainUrl$basePath"
                } else {
                    // Páginas seguintes
                    "$mainUrl$basePath/${page + 1}"
                }
                
                println("📺 AnimeFire: URL: $pageUrl")
                
                // Carregar página
                val document = app.get(pageUrl, timeout = 30).document
                
                // Parse SIMPLES - igual SuperFlix
                val homeItems = document.select("""
                    article a,
                    .card a,
                    .anime-item a,
                    a[href*='/animes/'],
                    a[href*='/filmes/']
                """).take(30).mapNotNull { element ->
                    element.toSearchResponse()
                }.distinctBy { it.url }
                
                // Detectar próxima página SIMPLES
                val nextPageNum = page + 2
                val hasNextPage = document.select("a[href*='/$nextPageNum']").isNotEmpty()
                
                println("📺 AnimeFire: ✅ '${request.name}' - ${homeItems.size} itens, próxima? $hasNextPage")
                
                // Retorno SIMPLES - igual SuperFlix
                newHomePageResponse(
                    request.name,
                    homeItems,
                    hasNext = hasNextPage
                )
                
            } catch (e: Exception) {
                println("📺 AnimeFire: ❌ Erro em '${request.name}': ${e.message}")
                newHomePageResponse(request.name, emptyList(), false)
            }
        }
    }

    // ============ FUNÇÃO DE PARSE SIMPLES ============
    private fun Element.toSearchResponse(): AnimeSearchResponse? {
        val href = attr("href") ?: return null
        if (href.isBlank() || (!href.contains("/animes/") && !href.contains("/filmes/"))) return null
        
        // Título
        val title = attr("title")?.trim() ?: 
                   selectFirst("h3, .card-title, .animeTitle")?.text()?.trim() ?: 
                   return null
        
        // Badges Dub/Leg
        val hasDub = title.contains("dublado", ignoreCase = true)
        val hasLeg = title.contains("legendado", ignoreCase = true) || !hasDub
        
        // Nome limpo
        val cleanName = title
            .replace(Regex("(?i)\\(dublado\\)"), "")
            .replace(Regex("(?i)\\(legendado\\)"), "")
            .replace(Regex("\\(\\d{4}\\)"), "")
            .trim()
        
        // Poster
        val poster = selectFirst("img")?.attr("src")?.let { 
            if (it.startsWith("//")) "https:$it"
            else if (it.startsWith("/")) "$mainUrl$it"
            else it
        }?.let { fixUrl(it) }
        
        // Tipo
        val isMovie = href.contains("/filmes/")
        
        return newAnimeSearchResponse(cleanName, fixUrl(href)) {
            this.posterUrl = poster
            this.type = if (isMovie) TvType.Movie else TvType.Anime
            
            if (hasDub || hasLeg) {
                addDubStatus(dubExist = hasDub, subExist = hasLeg)
            }
        }
    }

    // ============ BUSCA SIMPLES ============
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

    // ============ LOAD SIMPLES ============
    override suspend fun load(url: String): LoadResponse {
        return try {
            val document = app.get(url, timeout = 25).document
            
            val title = document.selectFirst("h1.animeTitle, h1")?.text()?.trim() ?: "Sem Título"
            val poster = document.selectFirst("img.imgAnimes, .poster img")?.attr("src")?.let { fixUrl(it) }
            val synopsis = document.selectFirst("p.sinopse, .description")?.text()?.trim()
                ?: "Sinopse não disponível."
            
            val isMovie = url.contains("/filmes/") || title.contains("filme", ignoreCase = true)
            
            // Episódios
            val episodes = document.select("a.lEp, .episode-item a, a[href*='/episodio']")
                .mapNotNull { element ->
                    try {
                        val epUrl = element.attr("href")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val epText = element.text().trim()
                        val epNumber = Regex("(\\d{1,4})").find(epText)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
                        
                        newEpisode(fixUrl(epUrl)) {
                            this.name = "Episódio $epNumber"
                            this.episode = epNumber
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
                .sortedBy { it.episode }
            
            newAnimeLoadResponse(title, url, if (isMovie) TvType.Movie else TvType.Anime) {
                this.posterUrl = poster
                this.plot = synopsis
                
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return false
    }
}
