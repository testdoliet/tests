package com.AnimeQ

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AnimeQ : MainAPI() {
    override var mainUrl = "https://animeq.net"
    override var name = "AnimeQ"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    // Usa o mesmo interceptor do AnimesCloud
    private val cloudflareInterceptor = CloudflareKiller()

    companion object {
        // USER-AGENT DESKTOP igual ao AnimesCloud
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        
        private val locker = Mutex()
        private var isInitialized = false
        
        // Página de busca
        private const val SEARCH_PATH = "/?s="
        
        // Página de episódios
        private const val EPISODE_PAGE_ITEM = ".item.se.episodes"
        
        // Página de gêneros/categorias
        private const val GENRE_PAGE_ITEM = ".items.full .item.tvshows, .items.full .item.movies"
        
        // Elementos comuns
        private const val ITEM_TITLE = ".data h3 a"
        private const val ITEM_POSTER = ".poster img"
        private const val ITEM_LINK = "a[href]"
        private const val EPISODE_SERIE = ".data .serie"
        private const val ANIME_YEAR = ".data span"
        private const val ANIME_SCORE = ".rating"
        
        // Página de detalhes do anime
        private const val DETAIL_TITLE = "h1"
        private const val DETAIL_POSTER = ".poster img"
        private const val DETAIL_SYNOPSIS = ".wp-content p"
        private const val DETAIL_GENRES = ".sgeneros a[rel=tag]"
        private const val DETAIL_YEAR = ".date"
        private const val DETAIL_SCORE = ".dt_rating_vgs"
        private const val EPISODE_LIST = ".episodios li .episodiotitle a"
        private const val EPISODE_IMAGES = ".episodios li .imagen img"
        private const val EPISODE_NUMBER = ".episodios li .numerando"
    }

    private var persistedCookies: String? = null

    // Headers IDÊNTICOS ao AnimesCloud (DESKTOP)
    private val defaultHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
            "Accept-Encoding" to "gzip, deflate, br",
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1",
            "Sec-Fetch-Dest" to "document",  // IMPORTANTE: document, NÃO iframe
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "same-origin",  // IMPORTANTE: same-origin
            "Sec-Fetch-User" to "?1",
            "Cache-Control" to "max-age=0",
            "Referer" to "$mainUrl/"
        )

    // Mapeamento de categorias (mantido do código original)
    private val mainCategories = mapOf(
        "Últimos Episódios" to "$mainUrl/episodio/",
        "Animes Mais Vistos" to "$mainUrl/",
    )

    private val genresMap = mapOf(
        "Ação" to "genre/acao",
        "Aventura" to "genre/aventura", 
        "Animação" to "genre/animacao",
        "Drama" to "genre/drama",
        "Crime" to "genre/crime",
        "Mistério" to "genre/misterio",
        "Fantasia" to "genre/fantasia",
        "Terror" to "genre/terror",
        "Comédia" to "genre/comedia",
        "Romance" to "genre/romance",
        "Sci-Fi" to "genre/ficcao-cientifica",
        "Seinen" to "genre/seinen",
        "Shounen" to "genre/shounen",
        "Ecchi" to "genre/ecchi",
        "Esporte" to "genre/esporte",
        "Sobrenatural" to "genre/sobrenatural",
        "Vida Escolar" to "genre/vida-escolar"
    )

    private val typeMap = mapOf(
        "Legendado" to "tipo/legendado",
        "Dublado" to "tipo/dublado"
    )

    private val specialCategories = mapOf(
        "Filmes" to "filme",
        "Manhwa" to "genre/Manhwa",
        "Donghua" to "genre/Donghua"
    )

    override val mainPage = mainPageOf(
        *mainCategories.map { (name, url) -> url to name }.toTypedArray(),
        *genresMap.map { (genre, slug) -> "$mainUrl/$slug" to genre }.toTypedArray(),
        *typeMap.map { (type, slug) -> "$mainUrl/$slug" to type }.toTypedArray(),
        *specialCategories.map { (cat, slug) -> "$mainUrl/$slug" to cat }.toTypedArray()
    )

    // FUNÇÃO DE REQUEST IDÊNTICA ao AnimesCloud
    private suspend fun request(url: String): Document {
        println("🔗 Request to: $url")
        
        // Inicialização igual ao AnimesCloud
        if (!isInitialized) {
            locker.withLock {
                if (!isInitialized) {
                    try {
                        println("🔄 Inicializando cookies para AnimeQ...")
                        val resMain = app.get(
                            mainUrl, 
                            headers = defaultHeaders, 
                            interceptor = cloudflareInterceptor, 
                            timeout = 60
                        )
                        
                        if (resMain.code == 200) {
                            println("✅ Cookie initialization successful")
                            val cookieList = mutableListOf<String>()
                            resMain.okhttpResponse.headers("Set-Cookie").forEach { 
                                cookieList.add(it.split(";")[0]) 
                            }
                            resMain.okhttpResponse.request.header("Cookie")?.let { 
                                cookieList.add(it) 
                            }
                            persistedCookies = cookieList.distinct().joinToString("; ")
                            isInitialized = true
                        } else {
                            println("⚠️ Initial request code: ${resMain.code}")
                        }
                    } catch (e: Exception) {
                        println("❌ Initialization error: ${e.message}")
                        // Tenta novamente com headers mais simples
                        trySimpleInitialization()
                    }
                }
            }
        }

        // Headers com cookies
        val finalHeaders = defaultHeaders.toMutableMap()
        persistedCookies?.takeIf { it.isNotBlank() }?.let {
            finalHeaders["Cookie"] = it
        }

        println("📡 Fazendo request para: $url")
        val response = app.get(
            url, 
            headers = finalHeaders, 
            interceptor = cloudflareInterceptor,
            timeout = 45
        )
        
        println("📊 Response code: ${response.code}")
        println("📄 Response length: ${response.text.length} chars")
        
        // Verifica se ainda tem Cloudflare
        if (response.text.contains("challenge-platform") || 
            response.text.contains("Checking your Browser") ||
            response.text.contains("cf-browser-verification")) {
            println("🛡️ DETECTADO CLOUDFLARE! Tentando bypass alternativo...")
            
            // Tenta método alternativo
            return tryAlternativeBypass(url)
        }
        
        return response.document
    }

    private suspend fun trySimpleInitialization() {
        try {
            println("🔄 Tentando inicialização simples...")
            val simpleHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
            
            val resMain = app.get(
                mainUrl,
                headers = simpleHeaders,
                interceptor = cloudflareInterceptor,
                timeout = 30
            )
            
            if (resMain.code == 200) {
                val cookieList = mutableListOf<String>()
                resMain.okhttpResponse.headers("Set-Cookie").forEach { 
                    cookieList.add(it.split(";")[0]) 
                }
                persistedCookies = cookieList.distinct().joinToString("; ")
                isInitialized = true
                println("✅ Simple initialization successful")
            }
        } catch (e: Exception) {
            println("❌ Simple initialization failed: ${e.message}")
        }
    }

    // Método alternativo se ainda der Cloudflare
    private suspend fun tryAlternativeBypass(url: String): Document {
        println("🔄 Tentando método alternativo de bypass...")
        
        // Lista de User-Agents alternativos (desktop)
        val userAgents = listOf(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/120.0",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36 Edg/119.0.0.0"
        )
        
        for ((index, ua) in userAgents.withIndex()) {
            println("🔄 Tentativa ${index + 1} com UA diferente...")
            
            try {
                val headers = defaultHeaders.toMutableMap().apply {
                    put("User-Agent", ua)
                    // Headers específicos para evitar detecção de bot
                    put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                    put("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
                    remove("Cookie") // Tenta sem cookie primeiro
                }
                
                val response = app.get(
                    url,
                    headers = headers,
                    interceptor = cloudflareInterceptor,
                    timeout = 30
                )
                
                if (!response.text.contains("challenge-platform") &&
                    !response.text.contains("Checking your Browser") &&
                    !response.text.contains("cf-browser-verification") &&
                    response.text.length > 500) {
                    println("✅ Bypass funcionou com UA alternativo!")
                    
                    // Atualiza cookies se conseguir
                    val cookieList = mutableListOf<String>()
                    response.okhttpResponse.headers("Set-Cookie").forEach { 
                        cookieList.add(it.split(";")[0]) 
                    }
                    if (cookieList.isNotEmpty()) {
                        persistedCookies = cookieList.distinct().joinToString("; ")
                    }
                    
                    return response.document
                }
                
                kotlinx.coroutines.delay(1500)
            } catch (e: Exception) {
                println("⚠️ Erro na tentativa ${index + 1}: ${e.message}")
            }
        }
        
        throw ErrorLoadingException("Não foi possível contornar o Cloudflare do AnimeQ")
    }

    // Funções auxiliares do código original
    private fun cleanTitle(dirtyTitle: String): String {
        return dirtyTitle
            .replace("(?i)\\s*–\\s*todos os epis[oó]dios".toRegex(), "")
            .replace("(?i)\\s*\\(dublado\\)".toRegex(), "")
            .replace("(?i)\\s*\\(legendado\\)".toRegex(), "")
            .replace("(?i)\\s*dublado\\s*$".toRegex(), "")
            .replace("(?i)\\s*legendado\\s*$".toRegex(), "")
            .replace("(?i)\\s*-\\s*epis[oó]dio\\s*\\d+".toRegex(), "")
            .replace("(?i)\\s*–\\s*Epis[oó]dio\\s*\\d+".toRegex(), "")
            .replace("(?i)\\s*epis[oó]dio\\s*\\d+".toRegex(), "")
            .replace("(?i)\\s*Ep\\.\\s*\\d+".toRegex(), "")
            .replace("\\s+".toRegex(), " ")
            .trim()
            .ifBlank { dirtyTitle }
    }

    private fun extractEpisodeNumber(title: String): Int? {
        return listOf(
            "Epis[oó]dio\\s*(\\d+)".toRegex(RegexOption.IGNORE_CASE),
            "Ep\\.?\\s*(\\d+)".toRegex(RegexOption.IGNORE_CASE),
            "E(\\d+)".toRegex(RegexOption.IGNORE_CASE),
            "\\b(\\d{3,})\\b".toRegex(),
            "\\b(\\d{1,3})\\b".toRegex()
        ).firstNotNullOfOrNull { it.find(title)?.groupValues?.get(1)?.toIntOrNull() } ?: 1
    }

    private fun extractAnimeTitleFromEpisode(episodeTitle: String): String {
        var clean = episodeTitle
            .replace("(?i)Epis[oó]dio\\s*\\d+".toRegex(), "")
            .replace("(?i)Ep\\.?\\s*\\d+".toRegex(), "")
            .replace("(?i)E\\d+".toRegex(), "")
            .replace("–", "")
            .replace("-", "")
            .replace("(?i)\\s*\\(dublado\\)".toRegex(), "")
            .replace("(?i)\\s*\\(legendado\\)".toRegex(), "")
            .replace("\\s+".toRegex(), " ")
            .trim()

        clean = clean.replace("\\s*\\d+\\s*$".toRegex(), "").trim()

        return clean.ifBlank { "Anime" }
    }

    private fun isDubbed(title: String): Boolean {
        return title.contains("dublado", true) || 
               title.contains("dublada", true) ||
               title.contains("dublados", true) ||
               title.contains("dubladas", true)
    }

    private fun Element.toEpisodeSearchResponse(): SearchResponse? {
        val href = selectFirst(ITEM_LINK)?.attr("href") ?: return null
        val episodeTitle = selectFirst(ITEM_TITLE)?.text()?.trim() ?: return null
        val episodeNumber = extractEpisodeNumber(episodeTitle)
        val animeTitle = extractAnimeTitleFromEpisode(episodeTitle)
        val posterUrl = selectFirst(ITEM_POSTER)?.attr("src")?.let { fixUrl(it) }
        val isDubbed = isDubbed(episodeTitle)
        val serieName = selectFirst(EPISODE_SERIE)?.text()?.trim() ?: animeTitle

        val cleanTitle = cleanTitle(serieName)

        return newAnimeSearchResponse(cleanTitle, fixUrl(href)) {
            this.posterUrl = posterUrl
            this.type = TvType.Anime
            // Corrigido: episode não é uma propriedade direta de SearchResponse
            // Vamos usar outras formas de identificar episódios
        }
    }

    private fun Element.toAnimeSearchResponse(): SearchResponse? {
        val href = selectFirst(ITEM_LINK)?.attr("href") ?: return null
        val rawTitle = selectFirst(ITEM_TITLE)?.text()?.trim() ?: return null
        val cleanedTitle = cleanTitle(rawTitle).ifBlank { return null }
        val posterUrl = selectFirst(ITEM_POSTER)?.attr("src")?.let { fixUrl(it) }
        val isDubbed = isDubbed(rawTitle)
        val year = selectFirst(ANIME_YEAR)?.text()?.trim()?.toIntOrNull()
        val scoreText = selectFirst(ANIME_SCORE)?.text()?.trim()

        val score = scoreText?.toFloatOrNull()?.let { 
            Score.from10(it)
        }

        val isMovie = href.contains("/filme/") || cleanedTitle.contains("filme", true)
        val type = if (isMovie) TvType.AnimeMovie else TvType.Anime

        return newAnimeSearchResponse(cleanedTitle, fixUrl(href)) {
            this.posterUrl = posterUrl
            this.type = type
            this.year = year
            this.score = score
            // Corrigido: dubStatus usa setOf() para EnumSet
            if (isDubbed) {
                this.dubStatus = setOf(DubStatus.Dubbed)
            } else {
                this.dubStatus = setOf(DubStatus.Subbed)
            }
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val baseUrl = request.data
        var url = baseUrl

        if (page > 1) {
            url = when {
                baseUrl == "$mainUrl/episodio/" -> "$baseUrl/page/$page/"
                baseUrl == "$mainUrl/" -> baseUrl
                baseUrl.contains("/?s=") -> baseUrl.replace("/?s=", "/page/$page/?s=")
                else -> "$baseUrl/page/$page/"
            }
        }

        val document = request(url)

        return when (request.name) {
            "Últimos Episódios" -> {
                val episodeElements = document.select(EPISODE_PAGE_ITEM)
                val items = episodeElements
                    .mapNotNull { it.toEpisodeSearchResponse() }
                    .distinctBy { it.url }

                newHomePageResponse(
                    list = HomePageList(request.name, items, isHorizontalImages = true),
                    hasNext = episodeElements.isNotEmpty()
                )
            }
            "Animes Mais Vistos" -> {
                val popularItems = mutableListOf<SearchResponse>()

                val sliderItems = document.select("#genre_acao .item.tvshows, #genre_acao .item.movies")
                popularItems.addAll(sliderItems.take(10).mapNotNull { it.toAnimeSearchResponse() })

                if (popularItems.isEmpty()) {
                    val allItems = document.select(".item.tvshows, .item.movies")
                        .take(10)
                        .mapNotNull { it.toAnimeSearchResponse() }
                    popularItems.addAll(allItems)
                }

                newHomePageResponse(
                    list = HomePageList(request.name, popularItems.distinctBy { it.url }, isHorizontalImages = false),
                    hasNext = false
                )
            }
            else -> {
                val isEpisodePage = baseUrl.contains("/episodio/")
                val isGenrePage = baseUrl.contains("/genre/") || 
                                 baseUrl.contains("/tipo/") || 
                                 baseUrl == "$mainUrl/filme/"

                val items = if (isEpisodePage) {
                    document.select(EPISODE_PAGE_ITEM)
                        .mapNotNull { it.toEpisodeSearchResponse() }
                        .distinctBy { it.url }
                } else if (isGenrePage) {
                    document.select(GENRE_PAGE_ITEM)
                        .mapNotNull { it.toAnimeSearchResponse() }
                        .distinctBy { it.url }
                } else {
                    document.select(".item.tvshows, .item.movies")
                        .mapNotNull { it.toAnimeSearchResponse() }
                        .distinctBy { it.url }
                }

                val hasNext = when {
                    isEpisodePage -> document.select(".pagination a").isNotEmpty()
                    isGenrePage -> document.select(".pagination a").isNotEmpty()
                    else -> false
                }

                newHomePageResponse(
                    list = HomePageList(request.name, items, isHorizontalImages = false),
                    hasNext = hasNext
                )
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()

        val document = request("$mainUrl$SEARCH_PATH${query.replace(" ", "+")}")

        return document.select(".item.tvshows, .item.movies, .item.se.episodes")
            .mapNotNull { element ->
                if (element.hasClass("episodes")) {
                    element.toEpisodeSearchResponse()
                } else {
                    element.toAnimeSearchResponse()
                }
            }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = request(url)

        val rawTitle = document.selectFirst(DETAIL_TITLE)?.text()?.trim() ?: "Sem Título"
        val title = cleanTitle(rawTitle)

        val poster = document.selectFirst(DETAIL_POSTER)?.attr("src")?.let { fixUrl(it) }

        var synopsis = "Sinopse não disponível."

        val wpContent = document.selectFirst(".wp-content")
        wpContent?.let { content ->
            val synopsisElements = content.select("p")
            for (element in synopsisElements) {
                val text = element.text()
                if (text.contains("Sinopse:", true)) {
                    synopsis = text.replace("Sinopse:", "").trim()
                    break
                } else if (text.contains("Sinopse", true) && text.length > 50) {
                    synopsis = text.trim()
                    break
                }
            }

            if (synopsis == "Sinopse não disponível." && synopsisElements.isNotEmpty()) {
                for (element in synopsisElements) {
                    val text = element.text().trim()
                    if (text.length > 50 && !text.contains("Título Alternativo") && 
                        !text.contains("Ano de Lançamento")) {
                        synopsis = text
                        break
                    }
                }
            }
        }

        val genres = document.select(DETAIL_GENRES)
            .mapNotNull { it.text().trim() }
            .filter { !it.contains("Letra") && !it.contains("tipo") }

        var year: Int? = null
        val yearText = document.selectFirst(DETAIL_YEAR)?.text()?.trim()
        if (yearText != null) {
            val yearMatch = "\\b(\\d{4})\\b".toRegex().find(yearText)
            year = yearMatch?.groupValues?.get(1)?.toIntOrNull()
        }

        var score: Score? = null
        val scoreText = document.selectFirst(DETAIL_SCORE)?.text()?.trim()
        if (scoreText != null) {
            val scoreValue = scoreText.toFloatOrNull()
            score = scoreValue?.let { Score.from10(it) }
        }

        val isDubbed = rawTitle.contains("dublado", true) || url.contains("dublado", true)
        val isMovie = url.contains("/filme/") || rawTitle.contains("filme", true)

        val episodesList = if (!isMovie) {
            val episodeElements = document.select(EPISODE_LIST)
            val episodeImages = document.select(EPISODE_IMAGES)
            val episodeNumbers = document.select(EPISODE_NUMBER)

            episodeElements.mapIndexed { index, element ->
                val episodeTitle = element.text().trim()
                val episodeUrl = element.attr("href")

                var epNumber = extractEpisodeNumber(episodeTitle) ?: (index + 1)

                if (index < episodeNumbers.size) {
                    val numberText = episodeNumbers[index].text().trim()
                    val numberMatch = "\\d+".toRegex().findAll(numberText).lastOrNull()
                    numberMatch?.let {
                        val extractedNumber = it.value.toIntOrNull()
                        if (extractedNumber != null) {
                            epNumber = extractedNumber
                        }
                    }
                }

                var episodePoster: String? = null
                if (index < episodeImages.size) {
                    episodePoster = episodeImages[index].attr("src")?.let { fixUrl(it) }
                }

                newEpisode(episodeUrl) {
                    this.name = "Episódio $epNumber"
                    this.episode = epNumber
                    this.posterUrl = episodePoster ?: poster
                }
            }.sortedBy { it.episode }
        } else {
            listOf(newEpisode(url) {
                this.name = "Filme Completo"
                this.episode = 1
                this.posterUrl = poster
            })
        }

        val showStatus = if (isMovie || episodesList.size >= 50) {
            ShowStatus.Completed
        } else {
            ShowStatus.Ongoing
        }

        return newAnimeLoadResponse(title, url, if (isMovie) TvType.AnimeMovie else TvType.Anime) {
            this.posterUrl = poster
            this.year = year
            this.plot = synopsis
            this.tags = genres
            this.score = score
            this.showStatus = showStatus

            if (episodesList.isNotEmpty()) {
                addEpisodes(if (isDubbed) DubStatus.Dubbed else DubStatus.Subbed, episodesList)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Chama o extractor original do AnimeQ como você pediu
        return AnimeQVideoExtractor.extractVideoLinks(data, callback = callback)
    }
}
