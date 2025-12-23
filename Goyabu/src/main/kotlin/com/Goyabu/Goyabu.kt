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
    override val usesWebView = true // ⬅️ MUDANÇA CRÍTICA: ATIVAR WEBVIEW

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
        println("🎬 GOYABU: Plugin inicializado - ${ALL_GENRES.size} gêneros (WebView ATIVADO)")
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

    // ============ LOAD (página do anime) - COM WEBVIEW ============
    override suspend fun load(url: String): LoadResponse {
        return try {
            println("\n" + "=".repeat(60))
            println("🎬 GOYABU: Carregando com WebView: $url")
            println("=".repeat(60))
            
            // Usar WebView para renderizar JavaScript
            val document = if (usesWebView) {
                app.get(url, timeout = 45).document
            } else {
                app.get(url, timeout = 30).document
            }
            
            // DEBUG: Verificar conteúdo da página
            debugPageContent(document)
            
            // TÍTULO
            val title = document.selectFirst("h1.text-hidden, h1")?.text()?.trim() ?: "Sem Título"
            println("📌 Título encontrado: $title")
            
            // POSTER
            val poster = document.selectFirst(".streamer-poster img, .cover")?.attr("src")
                ?.takeIf { it.isNotBlank() }
                ?.let { fixUrl(it) }
            println("🖼️ Poster encontrado: ${poster != null}")
            
            // SINOPSE
            val synopsis = document.selectFirst(".streamer-sinopse")?.text()?.trim()
                ?.replace("ler mais", "")
                ?.trim()
                ?: "Sinopse não disponível."
            println("📖 Sinopse (primeiros 100 chars): ${synopsis.take(100)}...")
            
            // ANO
            val yearElement = document.selectFirst("li#year")
            val year = yearElement?.text()?.trim()?.toIntOrNull()
            println("📅 Ano: $year")
            
            // GÊNEROS
            val genres = mutableListOf<String>()
            document.select(".filter-btn.btn-style, a[href*='/generos/']").forEach { element ->
                element.text().trim().takeIf { it.isNotBlank() }?.let { 
                    if (it.length > 1 && !genres.contains(it)) genres.add(it) 
                }
            }
            println("🏷️ Gêneros encontrados: ${genres.size}")
            
            // SCORE
            val scoreElement = document.selectFirst(".rating-total, .rating-score")
            val scoreText = scoreElement?.text()?.trim()
            val score = parseScore(scoreText)
            println("⭐ Score: $scoreText -> $score")
            
            // EPISÓDIOS (AGORA DEVE ENCONTRAR!)
            println("\n🔍 BUSCANDO EPISÓDIOS (com WebView)...")
            val episodes = extractEpisodes(document, url)
            println("📺 Total de episódios extraídos: ${episodes.size}")
            
            // CRIAR RESPOSTA
            val response = newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.year = year
                this.plot = synopsis
                this.tags = genres
                this.score = score
                
                if (episodes.isNotEmpty()) {
                    addEpisodes(DubStatus.Subbed, episodes)
                    println("✅ ${episodes.size} episódios adicionados à resposta")
                    
                    // Mostrar primeiros 3 episódios para debug
                    episodes.take(3).forEach { ep ->
                        println("   📝 Ep ${ep.episode}: ${ep.name} -> ${ep.url}")
                    }
                } else {
                    println("⚠️ Nenhum episódio encontrado (mesmo com WebView)")
                }
            }
            
            println("=".repeat(60))
            println("🎬 GOYABU: Load concluído para '$title'")
            println("=".repeat(60) + "\n")
            
            response
            
        } catch (e: Exception) {
            println("❌ ERRO no load: ${e.message}")
            newAnimeLoadResponse("Erro", url, TvType.Anime) {
                this.plot = "Erro: ${e.message}"
            }
        }
    }
    
    // ============ DEBUG PAGE CONTENT ============
    private fun debugPageContent(document: org.jsoup.nodes.Document) {
        println("\n🔍 DEBUG: Verificando conteúdo da página...")
        
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
                // Verificar se ainda tem o comentário de JavaScript
                val firstElement = elements.firstOrNull()
                if (firstElement != null) {
                    val html = firstElement.html()
                    println("   📏 Tamanho do HTML do container: ${html.length} chars")
                    
                    if (html.contains("<!-- Episódios serão carregados via JavaScript -->")) {
                        println("   ⚠️ AINDA TEM COMENTÁRIO DE JAVASCRIPT!")
                    } else {
                        println("   ✅ JavaScript executado! HTML real carregado")
                        // Mostrar um pedaço do HTML para ver os episódios
                        val sampleHtml = html.take(800)
                        println("   📋 HTML (primeiros 800 chars):")
                        println("   $sampleHtml")
                    }
                }
            }
        }
        
        // 3. Procurar links que pareçam ser de episódios
        val episodeLinks = document.select("a[href]").filter { 
            val href = it.attr("href")
            href.matches(Regex("""^/\d+/$""")) || href.contains("/episodio/")
        }
        println("🔗 Links com padrão de episódio: ${episodeLinks.size}")
        
        if (episodeLinks.isNotEmpty()) {
            episodeLinks.take(3).forEachIndexed { i, link ->
                println("   Link ${i + 1}: ${link.attr("href")}")
            }
        }
        
        println("🔍 FIM DEBUG\n")
    }
    
    // ============ EXTRACT EPISODES (ATUALIZADA) ============
    private fun extractEpisodes(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        println("🔍 Extraindo episódios...")
        
        // ESTRATÉGIA PRINCIPAL: Procurar por .episode-item dentro do container
        val episodeItems = document.select("#episodes-container .episode-item, .episode-item")
        
        if (episodeItems.isEmpty()) {
            println("   ⚠️ Nenhum .episode-item encontrado")
            // Fallback: procurar por .boxEP diretamente
            val boxEPs = document.select(".boxEP.grid-view, .boxEP")
            println("   🔄 Fallback: ${boxEPs.size} .boxEP encontrados")
            
            boxEPs.forEachIndexed { index, boxEP ->
                try {
                    extractEpisodeFromBoxEP(boxEP, index, episodes)
                } catch (e: Exception) {
                    println("   ❌ Erro no boxEP ${index + 1}: ${e.message}")
                }
            }
        } else {
            println("   ✅ ${episodeItems.size} .episode-item encontrados")
            
            episodeItems.forEachIndexed { index, episodeItem ->
                try {
                    // Dentro do .episode-item, procurar o .boxEP
                    val boxEP = episodeItem.selectFirst(".boxEP") ?: episodeItem
                    extractEpisodeFromBoxEP(boxEP, index, episodes)
                } catch (e: Exception) {
                    println("   ❌ Erro no episode-item ${index + 1}: ${e.message}")
                }
            }
        }
        
        // Se ainda não encontrou nada, procurar links diretos
        if (episodes.isEmpty()) {
            println("   🔍 Procurando links diretos de episódios...")
            val episodeLinks = document.select("a[href]").filter { 
                val href = it.attr("href")
                href.matches(Regex("""^/\d+/$""")) || href.contains("/episodio/")
            }
            
            episodeLinks.forEachIndexed { index, link ->
                try {
                    val href = link.attr("href").trim()
                    if (href.isBlank()) return@forEachIndexed
                    
                    // Extrair número do episódio
                    val episodeNum = extractEpisodeNumberFromHref(href, index + 1)
                    
                    episodes.add(newEpisode(fixUrl(href)) {
                        this.name = "Episódio $episodeNum"
                        this.episode = episodeNum
                        this.season = 1
                    })
                    
                    println("   🔗 Link direto Ep $episodeNum: $href")
                } catch (e: Exception) {
                    println("   ⚠️ Erro no link ${index + 1}: ${e.message}")
                }
            }
        }
        
        println("   📊 Total de episódios extraídos: ${episodes.size}")
        return episodes.sortedBy { it.episode }
    }
    
    private fun extractEpisodeFromBoxEP(boxEP: Element, index: Int, episodes: MutableList<Episode>) {
        val linkElement = boxEP.selectFirst("a[href]") ?: return
        val href = linkElement.attr("href").trim()
        if (href.isBlank()) return
        
        // NÚMERO DO EPISÓDIO
        var episodeNum = index + 1
        
        // 1. Tentar do texto "Episódio X"
        val epTypeElement = linkElement.selectFirst(".ep-type b")
        epTypeElement?.text()?.trim()?.let { text ->
            val regex = Regex("""Epis[oó]dio\s+(\d+)""", RegexOption.IGNORE_CASE)
            val match = regex.find(text)
            match?.groupValues?.get(1)?.toIntOrNull()?.let { episodeNum = it }
        }
        
        // 2. Tentar data-episode-number
        boxEP.parent()?.attr("data-episode-number")?.toIntOrNull()?.let { episodeNum = it }
        
        // 3. Tentar da URL
        episodeNum = extractEpisodeNumberFromHref(href, episodeNum)
        
        // THUMBNAIL
        val thumb = linkElement.selectFirst(".coverImg")?.attr("style")?.let { style ->
            val regex = Regex("""url\(['"]?([^'"()]+)['"]?\)""")
            regex.find(style)?.groupValues?.get(1)?.replace("&quot;", "")?.trim()
        }?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
        
        // NOME DO EPISÓDIO
        val episodeTitle = epTypeElement?.text()?.trim() ?: "Episódio $episodeNum"
        
        episodes.add(newEpisode(fixUrl(href)) {
            this.name = episodeTitle
            this.episode = episodeNum
            this.season = 1
            this.posterUrl = thumb
        })
        
        println("   ✅ Ep $episodeNum: $episodeTitle -> $href")
    }
    
    private fun extractEpisodeNumberFromHref(href: String, default: Int): Int {
        // Tentar extrair número da URL (padrão: /12345/ ou /episodio-1/)
        val regex1 = Regex("""/(\d+)/?$""")
        val regex2 = Regex("""/episodio[-_]?(\d+)/?$""", RegexOption.IGNORE_CASE)
        
        regex1.find(href)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        regex2.find(href)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        
        return default
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
