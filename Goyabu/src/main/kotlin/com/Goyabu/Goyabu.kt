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
        
        private val ALL_GENRES = listOf(
            "/generos/18" to "+18",
            "/generos/aventura" to "Aventura",
            "/generos/acao" to "Ação",
            "/generos/comedia" to "Comédia",
            "/generos/drama" to "Drama"
        )
    }

    override val mainPage = mainPageOf(
        *ALL_GENRES.map { (path, name) -> 
            "$mainUrl$path" to name 
        }.toTypedArray()
    )

    private fun Element.toSearchResponse(): AnimeSearchResponse? {
        val href = attr("href") ?: return null
        if (href.matches(Regex("""^/\d+/?$"""))) return null
        if (!href.contains("/anime/")) return null

        val titleElement = selectFirst(".title, .hidden-text")
        val rawTitle = titleElement?.text()?.trim() ?: return null
        
        val posterUrl = selectFirst("img[src]")?.attr("src")?.let { fixUrl(it) }

        return newAnimeSearchResponse(rawTitle, fixUrl(href)) {
            this.posterUrl = posterUrl
            this.type = TvType.Anime
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return loadingMutex.withLock {
            try {
                val url = if (page > 1) "${request.data}page/$page/" else request.data
                val document = app.get(url, timeout = 20).document
                
                val elements = document.select("article a, .boxAN a, a[href*='/anime/']")
                val homeItems = elements.mapNotNull { it.toSearchResponse() }
                    .distinctBy { it.url }
                    .take(30)
                
                newHomePageResponse(request.name, homeItems, false)
            } catch (e: Exception) {
                newHomePageResponse(request.name, emptyList(), false)
            }
        }
    }

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

    override suspend fun load(url: String): LoadResponse {
        return try {
            println("\n" + "=".repeat(60))
            println("🎬 GOYABU: Carregando: $url")
            
            // 1. Pegar metadados básicos
            val document = app.get(url, timeout = 30).document
            
            val title = document.selectFirst("h1.text-hidden, h1")?.text()?.trim() ?: "Sem Título"
            val poster = document.selectFirst(".streamer-poster img, .cover")?.attr("src")
                ?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
            val synopsis = document.selectFirst(".streamer-sinopse")?.text()?.trim()
                ?.replace("ler mais", "")?.trim() ?: "Sinopse não disponível."
            
            println("📌 Título: $title")
            
            // 2. EXTRAIR EPISÓDIOS ANALISANDO O HTML
            println("\n🔍 ANALISANDO HTML DA API...")
            val episodes = analyzeAndExtractEpisodes(url)
            println("📺 EPISÓDIOS ENCONTRADOS: ${episodes.size}")
            
            // 3. Criar resposta
            val response = newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.plot = synopsis
                
                if (episodes.isNotEmpty()) {
                    addEpisodes(DubStatus.Subbed, episodes.sortedBy { it.episode })
                    println("\n✅ ${episodes.size} EPISÓDIOS ADICIONADOS!")
                    
                    // Mostrar todos os episódios
                    episodes.take(10).forEach { ep ->
                        println("   Ep ${ep.episode}: ${ep.name}")
                    }
                    if (episodes.size > 10) {
                        println("   ... e mais ${episodes.size - 10} episódios")
                    }
                } else {
                    println("\n⚠️ NENHUM EPISÓDIO ENCONTRADO")
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
            println("🎬 GOYABU: Load concluído")
            println("=".repeat(60) + "\n")
            
            response
            
        } catch (e: Exception) {
            println("❌ ERRO: ${e.message}")
            newAnimeLoadResponse("Erro", url, TvType.Anime) {
                this.plot = "Erro: ${e.message}"
            }
        }
    }
    
    // ANÁLISE DETALHADA DO HTML
    private suspend fun analyzeAndExtractEpisodes(url: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        try {
            val animeSlug = url.substringAfter("/anime/").substringBefore("/").substringBefore("?")
            if (animeSlug.isBlank()) return emptyList()
            
            println("   🔍 Slug: $animeSlug")
            
            // Chamar API
            val apiUrl = "$mainUrl/ajax/episodes/$animeSlug"
            val response = app.get(apiUrl, timeout = 30, headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to url
            ))
            
            val responseText = response.text
            println("   📊 API retornou: ${responseText.length} chars")
            
            if (responseText.length < 100) {
                println("   ⚠️ Resposta muito curta, mostrando tudo:")
                println("   '$responseText'")
            } else {
                // MOSTRAR AMOSTRA DO HTML PARA DEBUG
                println("\n   📋 AMOSTRA DO HTML (primeiros 1500 chars):")
                println("   " + "─".repeat(50))
                val sample = responseText.take(1500)
                println("   $sample")
                println("   " + "─".repeat(50))
                
                // Procurar padrões específicos
                analyzeHtmlPatterns(responseText)
            }
            
            // Parsear HTML
            val doc = org.jsoup.Jsoup.parse(responseText)
            
            // ESTRATÉGIA 1: Procurar links numéricos (padrão mais comum)
            println("\n   🔎 BUSCANDO LINKS NUMÉRICOS...")
            val numericLinks = doc.select("a[href]").filter { link ->
                val href = link.attr("href")
                href.matches(Regex("""^/\d+/?$"""))
            }
            
            println("   📎 Links numéricos encontrados: ${numericLinks.size}")
            
            if (numericLinks.isNotEmpty()) {
                numericLinks.forEachIndexed { index, link ->
                    val href = link.attr("href")
                    val episodeNum = href.replace("/", "").toIntOrNull() ?: (index + 1)
                    val title = link.text().trim().takeIf { it.isNotBlank() } ?: "Episódio $episodeNum"
                    
                    episodes.add(newEpisode(fixUrl(href)) {
                        this.name = title
                        this.episode = episodeNum
                        this.season = 1
                    })
                    
                    println("   ✅ Ep $episodeNum: $title -> $href")
                }
            }
            
            // ESTRATÉGIA 2: Procurar por /assistir/
            if (episodes.isEmpty()) {
                println("\n   🔎 BUSCANDO LINKS /assistir/...")
                val assistirLinks = doc.select("a[href*='/assistir/']")
                println("   📎 Links /assistir/ encontrados: ${assistirLinks.size}")
                
                assistirLinks.forEachIndexed { index, link ->
                    val href = link.attr("href")
                    // Tentar extrair número da URL: /assistir/123/
                    val episodeNum = Regex("""/assistir/(\d+)/?""").find(href)
                        ?.groupValues?.get(1)?.toIntOrNull() ?: (index + 1)
                    
                    val title = link.text().trim().takeIf { it.isNotBlank() } ?: "Episódio $episodeNum"
                    
                    episodes.add(newEpisode(fixUrl(href)) {
                        this.name = title
                        this.episode = episodeNum
                        this.season = 1
                    })
                    
                    println("   ✅ Ep $episodeNum: $title -> $href")
                }
            }
            
            // ESTRATÉGIA 3: Procurar por /episodio/
            if (episodes.isEmpty()) {
                println("\n   🔎 BUSCANDO LINKS /episodio/...")
                val episodioLinks = doc.select("a[href*='/episodio/']")
                println("   📎 Links /episodio/ encontrados: ${episodioLinks.size}")
                
                episodioLinks.forEachIndexed { index, link ->
                    val href = link.attr("href")
                    // Tentar extrair número: /episodio/123/ ou /episodio-nome-123/
                    val episodeNum = extractEpisodeNumberFromUrl(href, index + 1)
                    val title = link.text().trim().takeIf { it.isNotBlank() } ?: "Episódio $episodeNum"
                    
                    episodes.add(newEpisode(fixUrl(href)) {
                        this.name = title
                        this.episode = episodeNum
                        this.season = 1
                    })
                    
                    println("   ✅ Ep $episodeNum: $title -> $href")
                }
            }
            
            // ESTRATÉGIA 4: Procurar TODOS os links e filtrar
            if (episodes.isEmpty()) {
                println("\n   🔎 ANALISANDO TODOS OS LINKS...")
                val allLinks = doc.select("a[href]")
                println("   📎 Total de links: ${allLinks.size}")
                
                // Mostrar primeiros 20 links para debug
                allLinks.take(20).forEachIndexed { i, link ->
                    val href = link.attr("href")
                    val text = link.text().trim()
                    println("   $i. href='$href' text='$text'")
                }
                
                // Filtrar links que parecem ser episódios
                val episodeLinks = allLinks.filter { link ->
                    val href = link.attr("href")
                    href.startsWith("/") && 
                    href.length in 3..20 && // Links razoavelmente curtos
                    !href.contains("/anime/") && // Não são páginas de anime
                    !href.contains("/generos/") && // Não são gêneros
                    !href.contains("/?s=") && // Não são busca
                    !href.contains("facebook") && // Não são redes sociais
                    !href.contains("twitter") &&
                    !href.contains("instagram")
                }
                
                println("   📎 Links filtrados: ${episodeLinks.size}")
                
                episodeLinks.forEachIndexed { index, link ->
                    val href = link.attr("href")
                    val episodeNum = extractEpisodeNumberFromUrl(href, index + 1)
                    val title = link.text().trim().takeIf { it.isNotBlank() } ?: "Episódio $episodeNum"
                    
                    episodes.add(newEpisode(fixUrl(href)) {
                        this.name = title
                        this.episode = episodeNum
                        this.season = 1
                    })
                }
            }
            
            println("\n   🎯 TOTAL DE EPISÓDIOS EXTRAÍDOS: ${episodes.size}")
            
        } catch (e: Exception) {
            println("   ❌ ERRO na análise: ${e.message}")
        }
        
        return episodes
    }
    
    // Função para analisar padrões no HTML
    private fun analyzeHtmlPatterns(html: String) {
        println("\n   🔍 ANALISANDO PADRÕES NO HTML...")
        
        // Procurar por padrões comuns
        val patterns = mapOf(
            "Links numéricos" to Regex("""href=["']/(\d+)/["']"""),
            "Links /assistir/" to Regex("""href=["'](/assistir/\d+/?)["']"""),
            "Links /episodio/" to Regex("""href=["'](/episodio[^"']*)["']"""),
            "Data attributes" to Regex("""data-[^=]+=["'][^"']*["']"""),
            "IDs numéricos" to Regex("""id=["'](\d+)["']"""),
            "data-id" to Regex("""data-id=["'](\d+)["']"""),
            "data-episode" to Regex("""data-episode=["'](\d+)["']""")
        )
        
        patterns.forEach { (name, regex) ->
            val matches = regex.findAll(html).toList()
            if (matches.isNotEmpty()) {
                println("   📍 $name: ${matches.size} encontrados")
                matches.take(3).forEach { match ->
                    println("     - '${match.value}'")
                }
                if (matches.size > 3) {
                    println("     ... e mais ${matches.size - 3}")
                }
            }
        }
    }
    
    private fun extractEpisodeNumberFromUrl(url: String, default: Int): Int {
        // Tentar vários padrões
        val patterns = listOf(
            Regex("""/(\d+)/?$"""), // /123/
            Regex("""/assistir/(\d+)/?"""), // /assistir/123/
            Regex("""/episodio/(\d+)/?"""), // /episodio/123/
            Regex("""-(\d+)/?$"""), // -123/
            Regex("""ep(\d+)""", RegexOption.IGNORE_CASE), // ep123
            Regex("""episodio[-_]?(\d+)""", RegexOption.IGNORE_CASE) // episodio-123
        )
        
        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null) {
                return match.groupValues[1].toIntOrNull() ?: default
            }
        }
        
        return default
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
