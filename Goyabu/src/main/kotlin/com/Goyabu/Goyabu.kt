package com.Goyabu

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Element
import com.fasterxml.jackson.module.kotlin.*
import com.fasterxml.jackson.databind.JsonNode

class Goyabu : MainAPI() {
    override var mainUrl = "https://goyabu.io"
    override var name = "Goyabu"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Anime)
    override val usesWebView = false // Não precisa mais de WebView

    companion object {
        private const val SEARCH_PATH = "/?s="
        private val loadingMutex = Mutex()
        private val mapper = jacksonObjectMapper()
        
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
            
            // Primeiro, pegar a página normalmente para metadata
            val document = app.get(url, timeout = 30).document
            
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
            
            // EPISÓDIOS - Usar API
            println("\n🔍 BUSCANDO EPISÓDIOS via API...")
            val episodes = tryExtractEpisodesFromAPI(url)
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
                    // Fallback
                    addEpisodes(DubStatus.Subbed, listOf(
                        newEpisode(url) {
                            this.name = "Episódio 1"
                            this.episode = 1
                            this.season = 1
                        }
                    ))
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
    
    // ============ EXTRACT EPISODES FROM API ============
    private suspend fun tryExtractEpisodesFromAPI(url: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        try {
            // Extrair ID/slug do anime da URL
            val animeSlug = url.substringAfter("/anime/").substringBefore("/").substringBefore("?")
            if (animeSlug.isBlank()) return emptyList()
            
            println("   🔍 Buscando episódios para slug: $animeSlug")
            
            // Tentar diferentes endpoints de API
            val apiEndpoints = listOf(
                "/ajax/episodes/$animeSlug",
                "/api/episodes/$animeSlug",
                "/ajax_load_episodes/$animeSlug"
            )
            
            for (endpoint in apiEndpoints) {
                try {
                    val apiUrl = "$mainUrl$endpoint"
                    println("   📡 Chamando API: $apiUrl")
                    
                    val response = app.get(apiUrl, timeout = 20, headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to url,
                        "Accept" to "application/json, text/javascript, */*; q=0.01"
                    ))
                    
                    val responseText = response.text
                    println("   ✅ Resposta recebida (${responseText.length} chars)")
                    
                    if (responseText.isBlank()) continue
                    
                    // Analisar a resposta
                    val extractedEpisodes = parseApiResponse(responseText, url)
                    if (extractedEpisodes.isNotEmpty()) {
                        println("   🎉 ${extractedEpisodes.size} episódios extraídos!")
                        return extractedEpisodes
                    }
                    
                } catch (e: Exception) {
                    println("   ⚠️ Erro no endpoint $endpoint: ${e.message}")
                    continue
                }
            }
            
        } catch (e: Exception) {
            println("   ❌ Erro geral na API: ${e.message}")
        }
        
        return episodes
    }
    
    private fun parseApiResponse(responseText: String, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        try {
            println("   📊 Analisando resposta da API...")
            
            // Tentar diferentes formatos de resposta
            
            // FORMATO 1: JSON com HTML
            if (responseText.contains("""{"html":""") || responseText.contains(""""content":""")) {
                println("   📦 Formato JSON detectado")
                return parseJsonResponse(responseText, baseUrl)
            }
            
            // FORMATO 2: HTML puro
            if (responseText.contains("<div") || responseText.contains("<a href")) {
                println("   🏗️ Formato HTML detectado")
                return parseHtmlResponse(responseText, baseUrl)
            }
            
            // FORMATO 3: JSON array
            if (responseText.trim().startsWith("[") && responseText.trim().endsWith("]")) {
                println("   📋 Formato JSON array detectado")
                return parseJsonArrayResponse(responseText, baseUrl)
            }
            
            // Se não reconheceu o formato, tentar parsear como JSON genérico
            try {
                val jsonNode = mapper.readTree(responseText)
                return parseJsonNode(jsonNode, baseUrl)
            } catch (e: Exception) {
                println("   ⚠️ Não é JSON válido")
            }
            
            // Tentar como HTML de último recurso
            return parseHtmlResponse(responseText, baseUrl)
            
        } catch (e: Exception) {
            println("   ❌ Erro ao parsear resposta: ${e.message}")
        }
        
        return episodes
    }
    
    private fun parseJsonResponse(jsonText: String, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        try {
            // Tentar parsear como JSON
            val jsonNode = mapper.readTree(jsonText)
            
            // Procurar por HTML nos campos comuns
            val htmlFields = listOf("html", "content", "data", "episodes", "result")
            
            for (field in htmlFields) {
                val htmlValue = jsonNode[field]?.asText()
                if (htmlValue != null && htmlValue.isNotBlank()) {
                    println("   📄 HTML encontrado no campo '$field' (${htmlValue.length} chars)")
                    
                    // Parsear o HTML
                    val doc = org.jsoup.Jsoup.parse(htmlValue)
                    val extracted = extractEpisodesFromHtmlDoc(doc, baseUrl)
                    if (extracted.isNotEmpty()) {
                        episodes.addAll(extracted)
                        break
                    }
                }
            }
            
            // Se não encontrou HTML, procurar por array de episódios
            if (episodes.isEmpty()) {
                return parseJsonNode(jsonNode, baseUrl)
            }
            
        } catch (e: Exception) {
            println("   ❌ Erro no parse JSON: ${e.message}")
        }
        
        return episodes
    }
    
    private fun parseHtmlResponse(htmlText: String, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        try {
            println("   🏗️ Parseando HTML (${htmlText.length} chars)")
            
            val doc = org.jsoup.Jsoup.parse(htmlText)
            return extractEpisodesFromHtmlDoc(doc, baseUrl)
            
        } catch (e: Exception) {
            println("   ❌ Erro no parse HTML: ${e.message}")
        }
        
        return episodes
    }
    
    private fun parseJsonArrayResponse(jsonArrayText: String, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        try {
            val jsonArray = mapper.readTree(jsonArrayText)
            
            if (jsonArray.isArray) {
                println("   📋 Array JSON com ${jsonArray.size()} elementos")
                
                for (item in jsonArray) {
                    try {
                        // Tentar extrair dados do episódio
                        val episode = parseEpisodeFromJson(item, baseUrl)
                        if (episode != null) {
                            episodes.add(episode)
                        }
                    } catch (e: Exception) {
                        // Ignorar erros em itens individuais
                    }
                }
            }
            
        } catch (e: Exception) {
            println("   ❌ Erro no parse JSON array: ${e.message}")
        }
        
        return episodes
    }
    
    private fun parseJsonNode(jsonNode: JsonNode, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        try {
            // Procurar por array de episódios
            if (jsonNode.isArray) {
                return parseJsonArrayResponse(jsonNode.toString(), baseUrl)
            }
            
            // Procurar por campos de episódios
            val episodeFields = listOf("episodes", "episode_list", "items", "results")
            
            for (field in episodeFields) {
                val episodeArray = jsonNode[field]
                if (episodeArray != null && episodeArray.isArray) {
                    println("   📋 Campo '$field' encontrado com ${episodeArray.size()} episódios")
                    
                    for (item in episodeArray) {
                        try {
                            val episode = parseEpisodeFromJson(item, baseUrl)
                            if (episode != null) {
                                episodes.add(episode)
                            }
                        } catch (e: Exception) {
                            // Ignorar erros
                        }
                    }
                    
                    if (episodes.isNotEmpty()) break
                }
            }
            
        } catch (e: Exception) {
            println("   ❌ Erro no parse JSON node: ${e.message}")
        }
        
        return episodes
    }
    
    private fun parseEpisodeFromJson(jsonNode: JsonNode, baseUrl: String): Episode? {
        try {
            // Extrair dados comuns
            val urlNode = jsonNode["url"] ?: jsonNode["link"] ?: jsonNode["href"]
            val titleNode = jsonNode["title"] ?: jsonNode["name"] ?: jsonNode["episode_title"]
            val numberNode = jsonNode["number"] ?: jsonNode["episode"] ?: jsonNode["episode_number"]
            
            val url = urlNode?.asText()?.trim()
            val title = titleNode?.asText()?.trim()
            
            // CORREÇÃO AQUI: Usar asInt() e lidar com nulos manualmente
            val number = try {
                numberNode?.asInt() ?: 1
            } catch (e: Exception) {
                1
            }
            
            if (url.isNullOrBlank()) return null
            
            val episodeUrl = if (url.startsWith("http")) url else fixUrl(url)
            
            return newEpisode(episodeUrl) {
                this.name = title ?: "Episódio $number"
                this.episode = number
                this.season = 1
            }
            
        } catch (e: Exception) {
            return null
        }
    }
    
    private fun extractEpisodesFromHtmlDoc(doc: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        try {
            // Estratégias de seleção
            val selectors = listOf(
                ".episode-item",
                ".boxEP",
                "[class*='episode']",
                ".episode-list li",
                ".episodes-grid div",
                "a[href*='/episodio/']",
                "a[href*='/assistir/']",
                "a[href^='/']"
            )
            
            var episodeCount = 0
            
            for (selector in selectors) {
                val elements = doc.select(selector)
                if (elements.isNotEmpty() && episodeCount == 0) {
                    println("   🔍 Seletor '$selector': ${elements.size} elementos")
                    
                    for (element in elements) {
                        try {
                            // Procurar link
                            val linkElement = if (element.tagName() == "a") element else element.selectFirst("a[href]")
                            val href = linkElement?.attr("href")?.trim()
                            
                            if (href.isNullOrBlank()) continue
                            
                            // Filtrar links que não são episódios
                            val isEpisodeLink = href.contains("/episodio/") || 
                                                href.contains("/assistir/") ||
                                                href.matches(Regex("""^/\d+/?$"""))
                            
                            if (!isEpisodeLink) continue
                            
                            // Extrair número do episódio
                            var episodeNum = episodeCount + 1
                            
                            // Tentar do texto
                            val text = element.text()
                            val numRegex = Regex("""epis[oó]dio\s+(\d+)""", RegexOption.IGNORE_CASE)
                            val numMatch = numRegex.find(text)
                            numMatch?.groupValues?.get(1)?.toIntOrNull()?.let { episodeNum = it }
                            
                            // Tentar da URL
                            val urlNumRegex = Regex("""/episodio[-_]?(\d+)/?$""", RegexOption.IGNORE_CASE)
                            val urlMatch = urlNumRegex.find(href)
                            urlMatch?.groupValues?.get(1)?.toIntOrNull()?.let { episodeNum = it }
                            
                            // Tentar de data attributes
                            element.attr("data-episode-number")?.toIntOrNull()?.let { episodeNum = it }
                            
                            // Extrair título
                            val episodeTitle = element.selectFirst(".ep-type b, .title, .episode-title")?.text()?.trim()
                                ?: "Episódio $episodeNum"
                            
                            episodes.add(newEpisode(fixUrl(href)) {
                                this.name = episodeTitle
                                this.episode = episodeNum
                                this.season = 1
                            })
                            
                            episodeCount++
                            println("   ✅ Ep $episodeNum: $episodeTitle -> ${fixUrl(href)}")
                            
                            // Limitar para debug
                            if (episodeCount >= 50) break
                            
                        } catch (e: Exception) {
                            // Ignorar erros
                        }
                    }
                    
                    if (episodes.isNotEmpty()) break
                }
            }
            
            println("   📊 Total encontrado: ${episodes.size}")
            
        } catch (e: Exception) {
            println("   ❌ Erro no parse HTML doc: ${e.message}")
        }
        
        return episodes
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
