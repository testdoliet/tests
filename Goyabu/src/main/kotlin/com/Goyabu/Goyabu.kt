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

    // ============ LOAD (página do anime) - SEM STATUS ============
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
                ?.replace("ler mais", "")
                ?.trim()
                ?: "Sinopse não disponível."
            
            // ANO
            val yearElement = document.selectFirst("li#year")
            val year = yearElement?.text()?.trim()?.toIntOrNull()
            
            // GÊNEROS
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
            
            // CRIAR RESPOSTA - SEM STATUS
            newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.year = year
                this.plot = synopsis
                this.tags = genres
                this.score = score
                
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
    
    // SELEÇÃO DIRETA baseada no seu HTML
    val episodeItems = document.select("#episodes-container .episode-item")
    
    if (episodeItems.isEmpty()) {
        println("❌ Nenhum .episode-item encontrado em #episodes-container")
        // Fallback: tentar seleção direta
        val boxEPs = document.select(".boxEP.grid-view")
        println("📦 Fallback: ${boxEPs.size} .boxEP encontrados")
    } else {
        println("✅ ${episodeItems.size} .episode-item encontrados")
    }
    
    // Usar .episode-item OU .boxEP como fallback
    val elementsToProcess = if (episodeItems.isNotEmpty()) {
        episodeItems
    } else {
        document.select(".boxEP.grid-view")
    }
    
    elementsToProcess.forEachIndexed { index, container ->
        try {
            // Dentro de cada container, buscar o link
            val boxEP = if (container.className().contains("boxEP")) container else container.selectFirst(".boxEP")
            val linkElement = boxEP?.selectFirst("a[href]") ?: return@forEachIndexed
            
            val href = linkElement.attr("href").trim()
            if (href.isBlank()) return@forEachIndexed
            
            // NÚMERO DO EPISÓDIO (prioridade: data-episode-number > .ep-type > index)
            var episodeNum = index + 1
            
            // 1. Tentar data-episode-number no .episode-item
            container.attr("data-episode-number")?.toIntOrNull()?.let { episodeNum = it }
            
            // 2. Tentar do texto "Episódio X"
            val epTypeElement = linkElement.selectFirst(".ep-type b")
            epTypeElement?.text()?.trim()?.let { text ->
                val regex = Regex("""\b(\d+)\b""")
                val match = regex.find(text)
                match?.groupValues?.get(1)?.toIntOrNull()?.let { episodeNum = it }
            }
            
            // THUMBNAIL
            val thumb = linkElement.selectFirst(".coverImg")?.attr("style")?.let { style ->
                val regex = Regex("""url\(['"]?([^'"()]+)['"]?\)""")
                regex.find(style)?.groupValues?.get(1)?.replace("&quot;", "")?.trim()
            }?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
            
            // NOME DO EPISÓDIO
            val episodeTitle = "Episódio $episodeNum"
            
            episodes.add(newEpisode(fixUrl(href)) {
                this.name = episodeTitle
                this.episode = episodeNum
                this.season = 1
                this.posterUrl = thumb
            })
            
            println("✅ Ep $episodeNum: $href")
            
        } catch (e: Exception) {
            println("⚠️ Erro no item ${index + 1}: ${e.message}")
        }
    }
    
    return episodes.sortedBy { it.episode }
    }

    private fun debugPageContent(document: org.jsoup.nodes.Document) {
    println("🔍 DEBUG: Verificando conteúdo da página...")
    
    // 1. Ver se a sinopse está lá (confirma que a página carregou)
    val synopsis = document.selectFirst(".streamer-sinopse")?.text()
    println("📄 Sinopse encontrada? ${!synopsis.isNullOrBlank()}")
    
    // 2. Procurar por QUALQUER container que possa ter episódios
    val possibleContainers = listOf(
        "#episodes-container",
        ".episodes-grid",
        ".episodes-slide",
        "[class*='episode']",
        ".boxEP"
    )
    
    possibleContainers.forEach { selector ->
        val elements = document.select(selector)
        println("   Seletor '$selector': ${elements.size} elementos")
        if (elements.isNotEmpty() && selector == "#episodes-container") {
            // Se achou o container, mostrar um pedaço do HTML interno
            println("   HTML do container (primeiros 500 chars):")
            println(elements.first().html().take(500))
        }
    }
    
    // 3. Procurar links que pareçam ser de episódios (padrão /número/)
    val episodeLinks = document.select("a[href]").filter { it.attr("href").matches(Regex("""^/\d+/$""")) }
    println("🔗 Links com padrão de episódio (/número/): ${episodeLinks.size}")
    }
    // ============ LOAD LINKS (desabilitado) ============
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🎬 GOYABU: loadLinks desabilitado temporariamente")
        return false
    }
}
