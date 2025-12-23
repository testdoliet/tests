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
    }

    init {
        println("🎬 GOYABU: Plugin inicializado - ${ALL_GENRES.size} gêneros")
    }

    override val mainPage = mainPageOf(
        *ALL_GENRES.map { (path, name) -> 
            "$mainUrl$path" to name 
        }.toTypedArray()
    )

    // ============ EXTRACTION PARA LISTAGEM ============
    private fun Element.toSearchResponse(): AnimeSearchResponse? {
        val href = attr("href") ?: return null
        
        // FILTRAR: Só queremos séries, não episódios individuais
        val isEpisodePage = href.matches(Regex("""^/\d+/?$"""))
        val isAnimePage = href.contains("/anime/")
        if (!isAnimePage || isEpisodePage) return null

        // TÍTULO
        val titleElement = selectFirst(".title, .hidden-text")
        val rawTitle = titleElement?.text()?.trim() ?: return null
        
        // THUMBNAIL
        val posterUrl = extractPosterUrl()
        
        // SCORE
        val scoreElement = selectFirst(".rating-score-box, .rating")
        val scoreText = scoreElement?.text()?.trim()
        val score = parseScore(scoreText)
        
        // DUBLADO (badge)
        val hasDubBadge = selectFirst(".audio-box.dublado, .dublado") != null
        val hasSub = true

        if (rawTitle.isBlank()) return null

        return newAnimeSearchResponse(rawTitle, fixUrl(href)) {
            this.posterUrl = posterUrl
            this.type = TvType.Anime
            this.score = score
            addDubStatus(dubExist = hasDubBadge, subExist = hasSub)
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
    
    private fun parseScore(text: String?): Score? {
        if (text.isNullOrBlank()) return null
        val regex = Regex("""(\d+\.?\d*)""")
        val match = regex.find(text)
        return match?.groupValues?.get(1)?.toFloatOrNull()?.let { Score.from10(it) }
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
            println("🎬 GOYABU: Carregando: $url")
            val document = app.get(url, timeout = 30).document
            
            // TÍTULO
            val title = document.selectFirst("h1.text-hidden, h1")?.text()?.trim() ?: "Sem Título"
            
            // POSTER
            val poster = document.selectFirst(".streamer-poster img, .cover")?.attr("src")
                ?.takeIf { it.isNotBlank() }
                ?.let { fixUrl(it) }
            
            // SINOPSE
            val synopsis = document.selectFirst(".streamer-sinopse")?.text()?.trim()
                ?.replace("ler mais", "") // Remover "ler mais"
                ?.trim()
                ?: "Sinopse não disponível."
            
            // ANO
            val yearElement = document.selectFirst("li#year")
            val year = yearElement?.text()?.trim()?.toIntOrNull()
            
            // STATUS
            val statusElement = document.selectFirst(".status")
            val statusText = statusElement?.text()?.trim() ?: "Desconhecido"
            val showStatus = when {
                statusText.contains("Completo", true) -> ShowStatus.Completed
                statusText.contains("Lançamento", true) -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
            
            // GÊNEROS (da página individual)
            val genres = mutableListOf<String>()
            document.select(".filter-btn.btn-style, a[href*='/generos/']").forEach { element ->
                element.text().trim().takeIf { it.isNotBlank() }?.let { 
                    if (it.length > 1 && !genres.contains(it)) genres.add(it) 
                }
            }
            
            // SCORE
            val scoreElement = document.selectFirst(".rating-total, .rating-score")
            val scoreText = scoreElement?.text()?.trim()
            val score = parseScore(scoreText)
            
            // EPISÓDIOS
            val episodes = extractEpisodes(document, url)
            
            // CRIAR RESPOSTA
            newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.year = year
                this.plot = synopsis
                this.tags = genres
                this.score = score
                this.status = showStatus
                
                if (episodes.isNotEmpty()) {
                    addEpisodes(DubStatus.Subbed, episodes)
                    println("✅ ${episodes.size} episódios")
                } else {
                    println("⚠️ Nenhum episódio encontrado")
                }
            }
            
        } catch (e: Exception) {
            println("❌ ERRO no load: ${e.message}")
            newAnimeLoadResponse("Erro", url, TvType.Anime) {
                this.plot = "Erro: ${e.message}"
            }
        }
    }
    
    private fun extractEpisodes(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        // Procurar por elementos de episódio
        val episodeElements = document.select("#episodes-container .boxEP, .episodes-grid .boxEP, .boxEP")
            .takeIf { it.isNotEmpty() }
            ?: document.select("[href~=^/\\d+/$]").takeIf { it.isNotEmpty() }
            ?: return emptyList()
        
        println("📊 ${episodeElements.size} elementos de episódio")
        
        episodeElements.forEachIndexed { index, element ->
            try {
                val href = element.attr("href").takeIf { it.isNotBlank() } ?: return@forEachIndexed
                
                // Número do episódio
                val episodeNum = element.selectFirst(".ep-type b")?.text()
                    ?.let { Regex("""(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                    ?: href.takeIf { it.matches(Regex("""^/\d+/$""")) }
                        ?.replace(Regex("""[^0-9]"""), "")?.toIntOrNull()
                    ?: (index + 1)
                
                // Título do episódio
                val episodeTitle = element.selectFirst(".ep-type b, .title")?.text()?.trim()
                    ?: "Episódio $episodeNum"
                
                // Thumbnail
                val thumb = element.selectFirst(".coverImg")?.attr("style")?.let { style ->
                    Regex("""url\(['"]?([^'"()]+)['"]?\)""").find(style)?.groupValues?.get(1)
                }?.let { fixUrl(it) }
                
                episodes.add(newEpisode(fixUrl(href)) {
                    this.name = episodeTitle
                    this.episode = episodeNum
                    this.season = 1
                    this.posterUrl = thumb
                })
                
            } catch (e: Exception) {
                println("⚠️ Erro no episódio ${index + 1}: ${e.message}")
            }
        }
        
        return episodes.sortedBy { it.episode }
    }

    // ============ LOAD LINKS (desabilitado) ============
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🎬 GOYABU: loadLinks desabilitado temporariamente")
        return false // Retorna false para indicar que não há links
    }
}
