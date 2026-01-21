package com.BetterFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element

class BetterFlix : MainAPI() {
    override var mainUrl = "https://betterflix.vercel.app"
    override var name = "BetterFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Live)
    override val usesWebView = false

    private val apiHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
        "Accept-Language" to "pt-BR",
        "Referer" to "https://betterflix.vercel.app/",
        "sec-ch-ua" to "\"Chromium\";v=\"127\", \"Not)A;Brand\";v=\"99\", \"Microsoft Edge Simulate\";v=\"127\", \"Lemur\";v=\"127\"",
        "sec-ch-ua-mobile" to "?1",
        "sec-ch-ua-platform" to "\"Android\""
    )

    override val mainPage = mainPageOf(
        "$mainUrl/api/trending?type=all" to "Em Alta",
        "$mainUrl/api/preview-genre?id=28" to "Ação e Aventura",
        "$mainUrl/api/preview-genre?id=35" to "Comédia",
        "$mainUrl/api/preview-genre?id=27" to "Terror e Suspense",
        "$mainUrl/api/preview-genre?id=99" to "Documentário",
        "$mainUrl/api/preview-genre?id=10751" to "Para a Família",
        "$mainUrl/api/preview-genre?id=80" to "Crime",
        "$mainUrl/api/preview-genre?id=10402" to "Musical",
        "$mainUrl/api/preview-genre?id=10749" to "Romance",
        "$mainUrl/api/list-animes" to "Animes"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data
        println("=== DEBUG: getMainPage chamado ===")
        println("URL: $url")
        println("Name: ${request.name}")
        println("Page: $page")
        
        val home = mutableListOf<HomePageList>()
        
        try {
            val items = when {
                url.contains("/api/trending") -> {
                    println("DEBUG: Buscando Trending")
                    fetchTrending()
                }
                url.contains("/api/preview-genre") -> {
                    println("DEBUG: Buscando Gênero")
                    fetchGenre(url)
                }
                url.contains("/api/list-animes") -> {
                    println("DEBUG: Buscando Animes")
                    fetchAnimes()
                }
                else -> {
                    println("ERROR: URL não reconhecida: $url")
                    emptyList()
                }
            }
            
            println("DEBUG: ${request.name} - ${items.size} itens encontrados")
            
            if (items.isNotEmpty()) {
                home.add(HomePageList(request.name, items))
                println("DEBUG: Lista '${request.name}' adicionada com ${items.size} itens")
                
                // Log dos primeiros 3 itens para verificação
                items.take(3).forEachIndexed { index, item ->
                    println("Item ${index + 1}: ${item.title} - ${item.url}")
                }
            } else {
                println("WARNING: Lista '${request.name}' está vazia")
            }
        } catch (e: Exception) {
            println("ERROR: Erro em getMainPage: ${e.message}")
            e.printStackTrace()
        }
        
        val response = newHomePageResponse(
            name = request.name,
            list = home.flatMap { it.list },
            hasNext = false
        )
        
        println("DEBUG: HomePageResponse criada com ${response.list.size} itens totais")
        println("=== DEBUG: getMainPage finalizado ===\n")
        return response
    }

    private suspend fun fetchTrending(): List<SearchResponse> {
        val url = "$mainUrl/api/trending?type=all"
        println("DEBUG fetchTrending - URL: $url")
        
        try {
            val response = app.get(url, headers = apiHeaders)
            println("DEBUG fetchTrending - Status: ${response.code}")
            println("DEBUG fetchTrending - Response size: ${response.text.length} chars")
            
            // Verificar se a resposta é JSON válido
            if (response.text.startsWith("{") || response.text.startsWith("[")) {
                println("DEBUG fetchTrending - Resposta parece ser JSON")
            } else {
                println("ERROR fetchTrending - Resposta não parece ser JSON")
                println("DEBUG fetchTrending - Primeiros 200 chars: ${response.text.take(200)}")
            }
            
            return try {
                val json = response.parsedSafe<TrendingResponse>()
                println("DEBUG fetchTrending - JSON parseado: ${json != null}")
                
                if (json == null) {
                    println("ERROR fetchTrending - Falha ao parsear JSON")
                    return emptyList()
                }
                
                println("DEBUG fetchTrending - ${json.results?.size ?: 0} resultados encontrados")
                
                val items = json.results?.mapNotNull { item ->
                    val searchResponse = createSearchResponse(item)
                    if (searchResponse == null) {
                        println("WARNING fetchTrending - Item nulo: ${item.title ?: item.name}")
                    }
                    searchResponse
                } ?: emptyList()
                
                println("DEBUG fetchTrending - ${items.size} SearchResponse criados")
                return items
            } catch (e: Exception) {
                println("ERROR fetchTrending - Erro ao parsear JSON: ${e.message}")
                e.printStackTrace()
                emptyList()
            }
        } catch (e: Exception) {
            println("ERROR fetchTrending - Erro na requisição: ${e.message}")
            e.printStackTrace()
            return emptyList()
        }
    }

    private suspend fun fetchGenre(url: String): List<SearchResponse> {
        println("DEBUG fetchGenre - URL: $url")
        
        try {
            val response = app.get(url, headers = apiHeaders)
            println("DEBUG fetchGenre - Status: ${response.code}")
            println("DEBUG fetchGenre - Response size: ${response.text.length} chars")
            
            return try {
                val json = response.parsedSafe<TrendingResponse>()
                println("DEBUG fetchGenre - JSON parseado: ${json != null}")
                
                val items = json?.results?.mapNotNull { item ->
                    createSearchResponse(item)
                } ?: emptyList()
                
                println("DEBUG fetchGenre - ${items.size} itens criados")
                return items
            } catch (e: Exception) {
                println("ERROR fetchGenre - Erro ao parsear JSON: ${e.message}")
                emptyList()
            }
        } catch (e: Exception) {
            println("ERROR fetchGenre - Erro na requisição: ${e.message}")
            return emptyList()
        }
    }

    private suspend fun fetchAnimes(): List<SearchResponse> {
        val url = "$mainUrl/api/list-animes"
        println("DEBUG fetchAnimes - URL: $url")
        
        try {
            val response = app.get(url, headers = apiHeaders)
            println("DEBUG fetchAnimes - Status: ${response.code}")
            println("DEBUG fetchAnimes - Response size: ${response.text.length} chars")
            
            return try {
                val json = response.parsedSafe<TrendingResponse>()
                println("DEBUG fetchAnimes - JSON parseado: ${json != null}")
                
                val items = json?.results?.mapNotNull { item ->
                    createSearchResponse(item)
                } ?: emptyList()
                
                println("DEBUG fetchAnimes - ${items.size} itens criados")
                return items
            } catch (e: Exception) {
                println("ERROR fetchAnimes - Erro ao parsear JSON: ${e.message}")
                emptyList()
            }
        } catch (e: Exception) {
            println("ERROR fetchAnimes - Erro na requisição: ${e.message}")
            return emptyList()
        }
    }

    private fun createSearchResponse(item: MediaItem): SearchResponse? {
        try {
            val title = item.title ?: item.name
            if (title == null) {
                println("WARNING createSearchResponse - Título nulo para item ID: ${item.id}")
                return null
            }
            
            println("DEBUG createSearchResponse - Processando: $title (ID: ${item.id}, Type: ${item.media_type})")
            
            val poster = item.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
            val year = item.release_date?.take(4)?.toIntOrNull() ?: 
                      item.first_air_date?.take(4)?.toIntOrNull()
            
            // Determinar tipo
            val type = when (item.media_type) {
                "movie" -> TvType.Movie
                "tv" -> TvType.TvSeries
                "anime" -> TvType.Anime
                else -> {
                    println("WARNING createSearchResponse - Tipo desconhecido: ${item.media_type}, usando TvSeries")
                    TvType.TvSeries
                }
            }
            
            // Criar URL para a página de detalhes
            val detailsUrl = when (type) {
                TvType.Movie -> "$mainUrl/?id=${item.id}&type=movie"
                TvType.Anime -> "$mainUrl/?id=${item.id}&type=anime"
                else -> "$mainUrl/?id=${item.id}&type=tv"
            }
            
            println("DEBUG createSearchResponse - URL criada: $detailsUrl")
            
            return when (type) {
                TvType.Movie -> newMovieSearchResponse(title, detailsUrl, type) {
                    this.posterUrl = poster
                    this.year = year
                    println("DEBUG createSearchResponse - Movie criado: $title")
                }
                TvType.Anime -> newAnimeSearchResponse(title, detailsUrl, type) {
                    this.posterUrl = poster
                    this.year = year
                    println("DEBUG createSearchResponse - Anime criado: $title")
                }
                else -> newTvSeriesSearchResponse(title, detailsUrl, type) {
                    this.posterUrl = poster
                    this.year = year
                    println("DEBUG createSearchResponse - TV Series criado: $title")
                }
            }
        } catch (e: Exception) {
            println("ERROR createSearchResponse - Erro: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        println("=== DEBUG: search chamado ===")
        println("Query: $query")
        
        val searchUrl = "$mainUrl/search?q=${query.encodeSearchQuery()}"
        println("DEBUG search - URL: $searchUrl")
        
        val document = app.get(searchUrl).document
        println("DEBUG search - Document carregado")

        val results = document.select("a[href*='?id=']").mapNotNull { element ->
            try {
                val href = element.attr("href") ?: return@mapNotNull null
                if (href.startsWith("/canal")) return@mapNotNull null

                val imgElement = element.selectFirst("img")
                val title = imgElement?.attr("alt") ?: 
                           element.selectFirst(".text-white")?.text() ?:
                           return@mapNotNull null

                val poster = imgElement?.attr("src")?.let { fixUrl(it) }
                val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
                val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()

                // Determinar tipo
                val isSeries = href.contains("type=tv")
                val isMovie = href.contains("type=movie")
                val isAnime = title.contains("(Anime)", ignoreCase = true)

                val result = when {
                    isAnime -> newAnimeSearchResponse(cleanTitle, fixUrl(href), TvType.Anime) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    isSeries -> newTvSeriesSearchResponse(cleanTitle, fixUrl(href), TvType.TvSeries) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    isMovie -> newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    else -> null
                }
                
                if (result != null) {
                    println("DEBUG search - Resultado encontrado: $cleanTitle")
                }
                
                result
            } catch (e: Exception) {
                println("ERROR search - Erro processando elemento: ${e.message}")
                null
            }
        }
        
        println("DEBUG search - ${results.size} resultados encontrados")
        println("=== DEBUG: search finalizado ===\n")
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        println("=== DEBUG: load chamado ===")
        println("URL: $url")
        
        try {
            val document = app.get(url).document
            println("DEBUG load - Document carregado")

            // Extrair título
            val titleElement = document.selectFirst("h1, .title, header h1")
            val title = titleElement?.text()
            
            if (title == null) {
                println("ERROR load - Título não encontrado")
                return null
            }
            
            println("DEBUG load - Título encontrado: $title")
            
            // Extrair informações básicas
            val year = extractYear(document)
            val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
            
            // Determinar tipo
            val isSeries = url.contains("type=tv") || document.select(".episode-list, .season-list").isNotEmpty()
            val isMovie = url.contains("type=movie") || (!isSeries && document.select(".movie-player").isNotEmpty())
            val isAnime = cleanTitle.contains("(Anime)", ignoreCase = true)
            
            println("DEBUG load - Tipo: ${if (isSeries) "Série" else if (isMovie) "Filme" else if (isAnime) "Anime" else "Desconhecido"}")
            
            // Extrair sinopse
            val synopsis = document.selectFirst("p.text-gray-200, .synopsis, .description, .plot")?.text()
            println("DEBUG load - Sinopse: ${synopsis?.take(50)}...")
            
            // Extrair gêneros
            val genres = document.select("span.bg-purple-600\\/80, .genre, .tags, .category").map { it.text().trim() }
                .takeIf { it.isNotEmpty() }
            println("DEBUG load - Gêneros: ${genres?.joinToString(", ")}")
            
            // Extrair poster
            val poster = extractPoster(document)
            println("DEBUG load - Poster: $poster")
            
            if (isSeries || isAnime) {
                val type = if (isAnime) TvType.Anime else TvType.TvSeries
                
                // Para séries, tentar extrair episódios
                val episodes = tryExtractEpisodes(document, url)
                println("DEBUG load - ${episodes.size} episódios encontrados")
                
                return newTvSeriesLoadResponse(cleanTitle, url, type, episodes) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = synopsis
                    this.tags = genres
                }
            } else {
                // Para filmes
                return newMovieLoadResponse(cleanTitle, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = synopsis
                    this.tags = genres
                }
            }
        } catch (e: Exception) {
            println("ERROR load - Erro: ${e.message}")
            e.printStackTrace()
            return tryAlternativeLoad(url)
        }
    }

    private suspend fun tryAlternativeLoad(url: String): LoadResponse? {
        println("DEBUG tryAlternativeLoad - Tentando carregamento alternativo")
        return try {
            // Tentar extrair informações da URL direto
            val tmdbMatch = Regex("[?&]id=(\\d+)").find(url)
            val tmdbId = tmdbMatch?.groupValues?.get(1)
            val type = if (url.contains("type=tv")) "tv" else "movie"
            
            if (tmdbId != null) {
                val title = "Conteúdo TMDB $tmdbId"
                println("DEBUG tryAlternativeLoad - TMDB ID encontrado: $tmdbId")
                
                if (type == "tv") {
                    newTvSeriesLoadResponse(title, url, TvType.TvSeries, emptyList()) {
                        this.posterUrl = "https://image.tmdb.org/t/p/w500"
                    }
                } else {
                    newMovieLoadResponse(title, url, TvType.Movie, url) {
                        this.posterUrl = "https://image.tmdb.org/t/p/w500"
                    }
                }
            } else {
                println("ERROR tryAlternativeLoad - TMDB ID não encontrado na URL")
                null
            }
        } catch (e: Exception) {
            println("ERROR tryAlternativeLoad - Erro: ${e.message}")
            null
        }
    }

    private suspend fun tryExtractEpisodes(document: org.jsoup.nodes.Document, url: String): List<Episode> {
        println("DEBUG tryExtractEpisodes - Tentando extrair episódios")
        val episodes = mutableListOf<Episode>()
        
        try {
            // Tentar extrair botões de episódio
            val elements = document.select("button[data-url], a[href*='episode'], .episode-item, .episode-link")
            println("DEBUG tryExtractEpisodes - ${elements.size} elementos encontrados")
            
            elements.forEachIndexed { index, element ->
                try {
                    val dataUrl = element.attr("data-url") ?: element.attr("href") ?: ""
                    if (dataUrl.isBlank()) {
                        println("DEBUG tryExtractEpisodes - Elemento $index: URL vazia")
                        return@forEachIndexed
                    }
                    
                    println("DEBUG tryExtractEpisodes - Elemento $index: URL encontrada")
                    
                    val epNumber = extractEpisodeNumber(element, index + 1)
                    val seasonNumber = element.attr("data-season").toIntOrNull() ?: 1
                    
                    val episode = newEpisode(fixUrl(dataUrl)) {
                        this.name = "Episódio $epNumber"
                        this.season = seasonNumber
                        this.episode = epNumber
                        
                        // Tentar extrair descrição
                        element.selectFirst(".ep-desc, .description")?.text()?.trim()?.let { desc ->
                            if (desc.isNotBlank()) {
                                this.description = desc
                            }
                        }
                    }
                    
                    episodes.add(episode)
                    println("DEBUG tryExtractEpisodes - Episódio $epNumber (Temporada $seasonNumber) adicionado")
                } catch (e: Exception) {
                    println("ERROR tryExtractEpisodes - Erro no elemento $index: ${e.message}")
                }
            }
        } catch (e: Exception) {
            println("ERROR tryExtractEpisodes - Erro geral: ${e.message}")
        }
        
        println("DEBUG tryExtractEpisodes - Total de episódios extraídos: ${episodes.size}")
        return episodes
    }

    private fun extractYear(document: org.jsoup.nodes.Document): Int? {
        // Tenta extrair do grid de informações
        document.select("div.bg-gray-800\\/50, .info-grid, .metadata").forEach { div ->
            val label = div.selectFirst("p.text-gray-400, .label, .info-label")?.text()
            if (label?.contains("Ano") == true || label?.contains("Year") == true) {
                val yearText = div.selectFirst("p.text-white, .value, .info-value")?.text()
                return yearText?.toIntOrNull()
            }
        }
        
        // Tenta extrair do título
        val title = document.selectFirst("h1, .title")?.text() ?: ""
        return Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractPoster(document: org.jsoup.nodes.Document): String? {
        // Tenta meta tag primeiro
        val ogImage = document.selectFirst("meta[property='og:image']")?.attr("content")
        if (ogImage != null) {
            println("DEBUG extractPoster - Encontrado og:image: $ogImage")
            return fixUrl(ogImage)
        }
        
        // Tenta qualquer imagem grande
        val img = document.select("img[src*='tmdb.org'], img[src*='poster'], .poster img").firstOrNull()
        val src = img?.attr("src")?.let { fixUrl(it) }
        
        println("DEBUG extractPoster - Imagem encontrada: $src")
        return src
    }

    private fun extractEpisodeNumber(element: Element, default: Int): Int {
        return element.attr("data-ep").toIntOrNull() ?:
               element.selectFirst(".ep-number, .number, .episode-number")?.text()?.toIntOrNull() ?:
               Regex("Ep\\.?\\s*(\\d+)").find(element.text())?.groupValues?.get(1)?.toIntOrNull() ?:
               Regex("Epis[oó]dio\\s*(\\d+)").find(element.text())?.groupValues?.get(1)?.toIntOrNull() ?:
               default
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("=== DEBUG: loadLinks chamado ===")
        println("Data: $data")
        
        return try {
            // Tentar extrair links da página
            val document = app.get(data).document
            println("DEBUG loadLinks - Document carregado")
            
            // Procurar por iframes de player
            val iframeSrc = document.selectFirst("iframe[src*='embed'], iframe[src*='player']")?.attr("src")
            if (iframeSrc != null) {
                println("DEBUG loadLinks - Iframe encontrado: $iframeSrc")
                return extractFromIframe(fixUrl(iframeSrc), callback)
            }
            
            // Procurar por scripts com m3u8
            val scripts = document.select("script")
            println("DEBUG loadLinks - ${scripts.size} scripts encontrados")
            
            for (script in scripts) {
                val html = script.html()
                val m3u8Pattern = Regex("""(https?://[^"\s]+\.m3u8[^"\s]*)""")
                val match = m3u8Pattern.find(html)
                if (match != null) {
                    val m3u8Url = match.groupValues[1]
                    println("DEBUG loadLinks - M3U8 encontrado em script: $m3u8Url")
                    return createM3u8Link(m3u8Url, callback)
                }
            }
            
            // Procurar por data-url em botões
            val playButton = document.selectFirst("button[data-url], a[data-url]")
            val dataUrl = playButton?.attr("data-url")
            if (dataUrl != null) {
                println("DEBUG loadLinks - data-url encontrado: $dataUrl")
                return extractVideoLinks(dataUrl, subtitleCallback, callback)
            }
            
            println("ERROR loadLinks - Nenhum link encontrado")
            false
        } catch (e: Exception) {
            println("ERROR loadLinks - Erro: ${e.message}")
            false
        }
    }
    
    private suspend fun extractFromIframe(
        iframeUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("DEBUG extractFromIframe - Iframe URL: $iframeUrl")
        
        return try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                "Referer" to "https://betterflix.vercel.app/"
            )
            
            val response = app.get(iframeUrl, headers = headers)
            println("DEBUG extractFromIframe - Status: ${response.code}")
            
            val html = response.text
            println("DEBUG extractFromIframe - HTML size: ${html.length} chars")
            
            // Procurar por m3u8
            val patterns = listOf(
                Regex("""file["']?\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                Regex("""src=["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                Regex("""(https?://[^"\s]+\.m3u8[^"\s]*)""")
            )
            
            for (pattern in patterns) {
                val match = pattern.find(html)
                if (match != null) {
                    val m3u8Url = match.groupValues[1]
                    println("DEBUG extractFromIframe - M3U8 encontrado: $m3u8Url")
                    createM3u8Link(m3u8Url, callback)
                    return true
                }
            }
            
            println("ERROR extractFromIframe - Nenhum M3U8 encontrado no iframe")
            false
        } catch (e: Exception) {
            println("ERROR extractFromIframe - Erro: ${e.message}")
            false
        }
    }
    
    private suspend fun createM3u8Link(
        m3u8Url: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("DEBUG createM3u8Link - M3U8 URL: $m3u8Url")
        
        return try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                "Referer" to "https://betterflix.vercel.app/",
                "Origin" to "https://betterflix.vercel.app"
            )
            
            // Gerar múltiplas qualidades
            println("DEBUG createM3u8Link - Chamando M3u8Helper.generateM3u8")
            val links = M3u8Helper.generateM3u8(
                source = name,
                streamUrl = m3u8Url,
                referer = "https://betterflix.vercel.app/",
                headers = headers
            )
            
            if (links.isNotEmpty()) {
                println("DEBUG createM3u8Link - ${links.size} links gerados")
                links.forEach { callback(it) }
                true
            } else {
                println("WARNING createM3u8Link - M3u8Helper não gerou links, criando link direto")
                // Link direto se M3u8Helper falhar
                val link = newExtractorLink(
                    source = name,
                    name = "Video",
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "https://betterflix.vercel.app/"
                    this.quality = 720
                    this.headers = headers
                }
                callback(link)
                true
            }
        } catch (e: Exception) {
            println("ERROR createM3u8Link - Erro: ${e.message}")
            false
        }
    }
    
    private suspend fun extractVideoLinks(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("DEBUG extractVideoLinks - URL: $url")
        
        // Tentar extrair do URL direto
        if (url.contains(".m3u8")) {
            println("DEBUG extractVideoLinks - URL contém .m3u8")
            return createM3u8Link(url, callback)
        }
        
        // Se não for m3u8, tentar seguir o link
        return try {
            val document = app.get(url).document
            val iframe = document.selectFirst("iframe[src]")
            if (iframe != null) {
                println("DEBUG extractVideoLinks - Iframe encontrado")
                extractFromIframe(iframe.attr("src"), callback)
            } else {
                println("ERROR extractVideoLinks - Nenhum iframe encontrado")
                false
            }
        } catch (e: Exception) {
            println("ERROR extractVideoLinks - Erro: ${e.message}")
            false
        }
    }
}

// Modelos de dados
private data class TrendingResponse(
    val results: List<MediaItem>
)

private data class MediaItem(
    val id: Int,
    val title: String?,
    val name: String?,
    val overview: String?,
    val poster_path: String?,
    val backdrop_path: String?,
    val media_type: String,
    val release_date: String?,
    val first_air_date: String?,
    val vote_average: Double?,
    val vote_count: Int?
)

// Função de extensão para codificar query
private fun String.encodeSearchQuery(): String {
    return java.net.URLEncoder.encode(this, "UTF-8")
}
