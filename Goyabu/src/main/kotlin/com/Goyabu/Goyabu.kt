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
    override val usesWebView = true

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
            println("\n" + "=".repeat(60))
            println("🎬 GOYABU: Carregando: $url")
            println("=".repeat(60))
            
            // Primeiro, pegar a página normalmente
            val document = app.get(url, timeout = 30).document
            
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
            
            // EPISÓDIOS - Nova abordagem: tentar várias estratégias
            println("\n🔍 BUSCANDO EPISÓDIOS (múltiplas estratégias)...")
            val episodes = mutableListOf<Episode>()
            
            // Estratégia 1: Tentar API/AJAX endpoint
            val apiEpisodes = tryExtractFromAPI(url)
            if (apiEpisodes.isNotEmpty()) {
                println("✅ API: ${apiEpisodes.size} episódios encontrados")
                episodes.addAll(apiEpisodes)
            }
            
            // Estratégia 2: Tentar dados embutidos no HTML
            if (episodes.isEmpty()) {
                val embeddedEpisodes = tryExtractFromEmbeddedData(document, url)
                if (embeddedEpisodes.isNotEmpty()) {
                    println("✅ Dados embutidos: ${embeddedEpisodes.size} episódios encontrados")
                    episodes.addAll(embeddedEpisodes)
                }
            }
            
            // Estratégia 3: Tentar por seletor de container de episódios
            if (episodes.isEmpty()) {
                val containerEpisodes = tryExtractFromContainer(document, url)
                if (containerEpisodes.isNotEmpty()) {
                    println("✅ Container: ${containerEpisodes.size} episódios encontrados")
                    episodes.addAll(containerEpisodes)
                }
            }
            
            println("📺 Total de episódios extraídos: ${episodes.size}")
            
            // CRIAR RESPOSTA
            val response = newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.year = year
                this.plot = synopsis
                this.tags = genres
                this.score = score
                
                if (episodes.isNotEmpty()) {
                    addEpisodes(DubStatus.Subbed, episodes.sortedBy { it.episode })
                    println("✅ ${episodes.size} episódios adicionados à resposta")
                    
                    // Mostrar primeiros 3 episódios para debug
                    episodes.take(3).forEach { ep ->
                        println("   📝 Ep ${ep.episode}: ${ep.name} -> ${ep.data}")
                    }
                } else {
                    println("⚠️ Nenhum episódio encontrado")
                    // Fallback: criar um episódio placeholder
                    addEpisodes(DubStatus.Subbed, listOf(
                        newEpisode(url) {
                            this.name = "Episódio 1"
                            this.episode = 1
                            this.season = 1
                        }
                    ))
                    println("📌 Criado episódio placeholder")
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
    
    // ============ ESTRATÉGIA 1: API/AJAX ============
    private suspend fun tryExtractFromAPI(url: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        try {
            // Padrões comuns de endpoints API
            val possibleEndpoints = listOf(
                "/ajax/episodes/",
                "/api/episodes/",
                "/wp-json/",
                "/ajax_load_episodes/",
                "/wp-admin/admin-ajax.php"
            )
            
            // Tentar extrair ID do anime da URL
            val animeId = url.substringAfter("/anime/").substringBefore("/").substringBefore("?")
            if (animeId.isNotBlank()) {
                println("   🔍 Tentando endpoints API com ID: $animeId")
                
                // Tentar diferentes endpoints
                for (endpoint in possibleEndpoints) {
                    try {
                        val apiUrl = "$mainUrl$endpoint$animeId"
                        println("   📡 Testando API: $apiUrl")
                        
                        val response = app.get(apiUrl, timeout = 15, headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Referer" to url,
                            "Accept" to "application/json, text/javascript, */*; q=0.01"
                        ))
                        
                        val responseText = response.text
                        if (responseText.isNotBlank() && responseText.length > 10) {
                            println("   ✅ Resposta API recebida (${responseText.length} chars)")
                            
                            // Tentar parsear JSON
                            if (responseText.contains("{") && responseText.contains("}")) {
                                println("   📄 Parece ser JSON, analisando...")
                                // Aqui você precisaria implementar o parse do JSON específico
                                // Vou deixar como TODO por enquanto
                            }
                            
                            // Tentar parsear HTML
                            val apiDoc = response.document
                            val apiEpisodes = extractEpisodesFromHTML(apiDoc, url)
                            if (apiEpisodes.isNotEmpty()) {
                                println("   ✅ ${apiEpisodes.size} episódios da API")
                                return apiEpisodes
                            }
                        }
                    } catch (e: Exception) {
                        // Ignorar erros e continuar
                    }
                }
            }
        } catch (e: Exception) {
            println("   ❌ Erro na API: ${e.message}")
        }
        
        return episodes
    }
    
    // ============ ESTRATÉGIA 2: DADOS EMBUTIDOS ============
    private fun tryExtractFromEmbeddedData(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        try {
            println("   🔍 Procurando dados embutidos...")
            
            // 1. Procurar por script tags com dados de episódios
            val scriptTags = document.select("script")
            var episodeDataFound = false
            
            scriptTags.forEachIndexed { index, script ->
                val scriptContent = script.html()
                if (scriptContent.isNotBlank()) {
                    // Procurar por padrões comuns
                    val patterns = listOf(
                        "episodes.*\\[",
                        "var.*episodes.*=",
                        "data-episodes=",
                        "episodeList.*=",
                        "ajaxEpisodes.*="
                    )
                    
                    patterns.forEach { pattern ->
                        if (scriptContent.contains(pattern, ignoreCase = true)) {
                            println("   📄 Script encontrado com padrão '$pattern'")
                            episodeDataFound = true
                            
                            // Extrair dados do script
                            extractFromScript(scriptContent, baseUrl)?.let { extractedEpisodes ->
                                episodes.addAll(extractedEpisodes)
                            }
                        }
                    }
                }
            }
            
            if (!episodeDataFound) {
                println("   ⚠️ Nenhum dado de episódios encontrado em scripts")
            }
            
        } catch (e: Exception) {
            println("   ❌ Erro nos dados embutidos: ${e.message}")
        }
        
        return episodes
    }
    
    private fun extractFromScript(scriptContent: String, baseUrl: String): List<Episode>? {
        val episodes = mutableListOf<Episode>()
        
        try {
            // Tentar extrair JSON - simplificar regex sem DOTALL
            val jsonRegex = Regex("""episodes\s*:\s*(\[[^\]]*?\])""")
            val jsonMatch = jsonRegex.find(scriptContent)
            
            if (jsonMatch != null) {
                val jsonStr = jsonMatch.groupValues[1]
                println("   📦 JSON de episódios encontrado (${jsonStr.length} chars)")
                // TODO: Parse JSON específico
                return episodes
            }
            
            // Tentar extrair HTML de episódios - simplificar regex
            val htmlRegex = Regex("""<div[^>]*class=["']episode-item["'][^>]*>.*?</div>""")
            val htmlMatches = htmlRegex.findAll(scriptContent)
            val matchesList = htmlMatches.toList()
            
            if (matchesList.isNotEmpty()) {
                println("   🏗️ HTML de episódios em script: ${matchesList.size} matches")
                
                matchesList.forEachIndexed { index: Int, matchResult: MatchResult ->
                    try {
                        val html = matchResult.value
                        // Criar elemento temporário
                        val tempDoc = org.jsoup.Jsoup.parse(html)
                        val extracted = extractEpisodesFromHTML(tempDoc, baseUrl)
                        if (extracted != null) {
                            episodes.addAll(extracted)
                        }
                    } catch (e: Exception) {
                        // Ignorar erro
                    }
                }
            }
            
        } catch (e: Exception) {
            println("   ❌ Erro ao extrair do script: ${e.message}")
        }
        
        return if (episodes.isNotEmpty()) episodes else null
    }
    
    // ============ ESTRATÉGIA 3: CONTAINER DE EPISÓDIOS ============
    private fun tryExtractFromContainer(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        try {
            println("   🔍 Procurando no container principal...")
            
            // Primeiro, ver se o container tem dados
            val container = document.selectFirst("#episodes-container, .episodes-container")
            if (container != null) {
                val containerHtml = container.html()
                println("   📏 Container encontrado (${containerHtml.length} chars)")
                
                // Se o container tem o comentário de JS, significa que não carregou
                if (containerHtml.contains("<!-- Episódios serão carregados via JavaScript -->")) {
                    println("   ⚠️ Container ainda vazio (JS não executado)")
                } else {
                    // Tentar extrair episódios do container
                    val containerEpisodes = extractEpisodesFromHTML(container, baseUrl)
                    if (containerEpisodes.isNotEmpty()) {
                        return containerEpisodes
                    }
                }
            }
            
            // Procurar por qualquer elemento que possa conter episódios
            val possibleSelectors = listOf(
                "[class*='episode']",
                ".boxEP",
                ".episode-grid",
                ".episode-list",
                "[id*='episode']"
            )
            
            possibleSelectors.forEach { selector ->
                val elements = document.select(selector)
                if (elements.isNotEmpty()) {
                    println("   🔎 Seletor '$selector': ${elements.size} elementos")
                    
                    elements.forEach { element ->
                        try {
                            // Verificar se parece ser um item de episódio
                            val text = element.text()
                            val hasEpisodeNumber = Regex("""epis[oó]dio\s+\d+""", RegexOption.IGNORE_CASE).containsMatchIn(text)
                            val hasEpisodeClass = element.classNames().any { it.contains("episode", ignoreCase = true) }
                            
                            if (hasEpisodeNumber || hasEpisodeClass) {
                                // Extrair link
                                val link = element.selectFirst("a[href]")
                                link?.attr("href")?.let { href ->
                                    val episodeNum = extractEpisodeNumberFromText(text) ?: 1
                                    episodes.add(
                                        newEpisode(fixUrl(href)) {
                                            this.name = element.text().trim().takeIf { it.isNotBlank() } ?: "Episódio $episodeNum"
                                            this.episode = episodeNum
                                            this.season = 1
                                        }
                                    )
                                    println("   ✅ Ep $episodeNum encontrado via seletor '$selector'")
                                }
                            }
                        } catch (e: Exception) {
                            // Ignorar erro
                        }
                    }
                }
            }
            
        } catch (e: Exception) {
            println("   ❌ Erro no container: ${e.message}")
        }
        
        return episodes
    }
    
    // ============ FUNÇÕES AUXILIARES ============
    private fun extractEpisodesFromHTML(element: Element, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        try {
            // Procurar por links de episódios
            val episodeLinks = element.select("a[href]").filter { link ->
                val href = link.attr("href")
                href.isNotBlank() && (href.contains("/episodio/") || href.matches(Regex("""^/\d+/$""")))
            }
            
            if (episodeLinks.isNotEmpty()) {
                println("   🔗 ${episodeLinks.size} links de episódios encontrados")
                
                episodeLinks.forEachIndexed { index, link ->
                    try {
                        val href = link.attr("href").trim()
                        val episodeNum = extractEpisodeNumberFromHref(href, index + 1)
                        
                        episodes.add(
                            newEpisode(fixUrl(href)) {
                                this.name = link.text().trim().takeIf { it.isNotBlank() } ?: "Episódio $episodeNum"
                                this.episode = episodeNum
                                this.season = 1
                            }
                        )
                    } catch (e: Exception) {
                        // Ignorar erro
                    }
                }
            }
        } catch (e: Exception) {
            println("   ❌ Erro ao extrair HTML: ${e.message}")
        }
        
        return episodes
    }
    
    private fun extractEpisodeNumberFromText(text: String): Int? {
        val regex = Regex("""epis[oó]dio\s+(\d+)""", RegexOption.IGNORE_CASE)
        val match = regex.find(text)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }
    
    private fun extractEpisodeNumberFromHref(href: String, default: Int): Int {
        val regex1 = Regex("""/(\d+)/?$""")
        val regex2 = Regex("""/episodio[-_]?(\d+)/?$""", RegexOption.IGNORE_CASE)
        
        regex1.find(href)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        regex2.find(href)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        
        return default
    }
    
    // ============ DEBUG PAGE CONTENT ============
    private fun debugPageContent(document: org.jsoup.nodes.Document) {
        println("\n🔍 DEBUG: Verificando conteúdo da página...")
        
        // 1. Ver se a sinopse está lá
        val synopsis = document.selectFirst(".streamer-sinopse")?.text()
        println("📄 Sinopse encontrada? ${!synopsis.isNullOrBlank()}")
        
        // 2. Procurar por script tags com dados
        val scriptTags = document.select("script")
        var dataScripts = 0
        
        scriptTags.forEachIndexed { index, script ->
            val content = script.html()
            if (content.contains("episode", ignoreCase = true) || 
                content.contains("Episodes", ignoreCase = true)) {
                dataScripts++
                if (dataScripts <= 3) {
                    println("   📜 Script $index: ${content.take(50)}...")
                }
            }
        }
        
        if (dataScripts > 0) {
            println("   📊 Scripts com dados de episódios: $dataScripts")
        }
        
        // 3. Verificar container
        val container = document.selectFirst("#episodes-container")
        if (container != null) {
            val containerHtml = container.html()
            println("   📦 Container de episódios: ${containerHtml.length} chars")
            println("   📝 Conteúdo: ${containerHtml.take(100)}")
        }
        
        println("🔍 FIM DEBUG\n")
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
