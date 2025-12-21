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
        
        // ============ SISTEMA DE PÁGINAS POR CARREGAMENTO ============
        private val PAGE_SYSTEM = mapOf(
            // PÁGINA 1 - LANÇAMENTOS E TOP
            0 to listOf(
                "/em-lancamento" to "Em Lançamento",
                "/animes-atualizados" to "Atualizados",
                "/top-animes" to "Top Animes"
            ),
            // PÁGINA 2 - ÁUDIO
            1 to listOf(
                "/lista-de-animes-legendados" to "Animes Legendados",
                "/lista-de-animes-dublados" to "Animes Dublados",
                "/lista-de-filmes-legendados" to "Filmes Legendados",
                "/lista-de-filmes-dublados" to "Filmes Dublados"
            ),
            // PÁGINA 3 - GÊNEROS A-D
            2 to listOf(
                "/genero/acao" to "Ação",
                "/genero/aventura" to "Aventura",
                "/genero/comedia" to "Comédia",
                "/genero/drama" to "Drama",
                "/genero/fantasia" to "Fantasia",
                "/genero/romance" to "Romance"
            ),
            // PÁGINA 4 - GÊNEROS E-S
            3 to listOf(
                "/genero/shounen" to "Shounen",
                "/genero/seinen" to "Seinen",
                "/genero/esporte" to "Esporte",
                "/genero/misterio" to "Mistério",
                "/genero/artes-marciais" to "Artes Marciais",
                "/genero/demonios" to "Demônios"
            ),
            // PÁGINA 5 - GÊNEROS T-Z
            4 to listOf(
                "/genero/ecchi" to "Ecchi",
                "/genero/ficcao-cientifica" to "Ficção Científica",
                "/genero/harem" to "Harém",
                "/genero/horror" to "Horror",
                "/genero/magia" to "Magia",
                "/genero/mecha" to "Mecha"
            ),
            // PÁGINA 6 - GÊNEROS DIVERSOS
            5 to listOf(
                "/genero/militar" to "Militar",
                "/genero/psicologico" to "Psicológico",
                "/genero/slice-of-life" to "Slice of Life",
                "/genero/sobrenatural" to "Sobrenatural",
                "/genero/superpoder" to "Superpoder",
                "/genero/vampiros" to "Vampiros"
            )
        )
    }

    // ============ MAIN PAGE (CARREGA UMA PÁGINA POR VEZ) ============
    override val mainPage = mainPageOf(
        // Apenas a primeira página carrega inicialmente
        *PAGE_SYSTEM[0]!!.map { (path, name) -> 
            "$mainUrl$path" to name 
        }.toTypedArray()
    )

    // ============ FUNÇÃO PRINCIPAL OTIMIZADA ============
    private fun Element.toSearchResponse(): AnimeSearchResponse? {
        val href = attr("href") ?: return null
        if (href.isBlank() || !href.contains("/animes/") && !href.contains("/filmes/")) return null
        
        // TÍTULO
        val titleElement = selectFirst("h3.animeTitle, .animeTitle, h3") ?: return null
        val rawTitle = titleElement.text().trim()
        
        val titleAttr = attr("title")?.trim() ?: ""
        val combinedTitle = if (titleAttr.isNotBlank() && titleAttr.length > 3) titleAttr else rawTitle
        
        if (combinedTitle.isBlank()) return null
        
        // DETECTAR ÁUDIO
        val hasDub = combinedTitle.contains("dublado", ignoreCase = true)
        val hasLeg = combinedTitle.contains("legendado", ignoreCase = true)
        
        // NOME LIMPO
        val cleanName = extractAnimeName(combinedTitle, selectFirst(".numEp")?.text())
        
        // NOTA
        val scoreText = selectFirst(".horaUltimosEps, .rating")?.text()?.trim()
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
            
            if (hasDub || hasLeg) {
                addDubStatus(dubExist = hasDub, subExist = hasLeg)
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

    // ============ GET MAIN PAGE COM PAGINAÇÃO ============
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Usa o sistema de páginas: page=0,1,2,3,4,5
        val pageItems = PAGE_SYSTEM[page] ?: return newHomePageResponse("", emptyList(), false)
        
        val allItems = mutableListOf<SearchResponse>()
        
        // CARREGA UMA LISTA DE CADA VEZ
        for ((path, listName) in pageItems) {
            try {
                val document = app.get("$mainUrl$path", timeout = 10).document
                
                // SELETOR SIMPLES
                val elements = document.select("a[href*='/animes/'], a[href*='/filmes/']")
                    .filter { 
                        it.hasAttr("href") && 
                        it.selectFirst("h3, .animeTitle") != null
                    }
                    .take(15) // LIMITE POR LISTA
                
                val items = elements.mapNotNull { it.toSearchResponse() }
                    .distinctBy { it.url }
                    .take(12)
                
                allItems.addAll(items)
                
                // PEQUENA PAUSA ENTRE REQUISIÇÕES
                kotlinx.coroutines.delay(100)
                
            } catch (e: Exception) {
                println("AnimeFire: Erro ao carregar $path: ${e.message}")
            }
        }
        
        // Nome da página baseado no primeiro item
        val pageName = when(page) {
            0 -> "Lançamentos e Top"
            1 -> "Áudio"
            2 -> "Gêneros A-F"
            3 -> "Gêneros G-M"
            4 -> "Gêneros N-S"
            5 -> "Gêneros T-Z"
            else -> "Página ${page + 1}"
        }
        
        return newHomePageResponse(pageName, allItems.distinctBy { it.url }, hasNext = page < PAGE_SYSTEM.size - 1)
    }

    // ============ BUSCA ============
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
            .take(20)
    }

    // ============ LOAD SIMPLIFICADO ============
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
            
            // EPISÓDIOS
            episodes.forEach { episode ->
                addEpisode(episode)
            }
        }
    }

    // ============ EXTRAIR EPISÓDIOS ============
    private fun extractAllEpisodes(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        document.select("a.lEp, .episode-item a").forEach { element ->
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
