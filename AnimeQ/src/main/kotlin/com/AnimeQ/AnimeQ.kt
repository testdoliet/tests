package com.AnimeQ

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import java.net.URI

class AnimeQ : MainAPI() {
    override var mainUrl = "https://animeq.net"
    override var name = "AnimeQ"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override val usesWebView = false

    // CloudflareKiller instance
    private val cloudflareInterceptor = CloudflareKiller()

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        
        // Mutex para controle de inicialização
        private val locker = Mutex()
        private var isInitialized = false
        
        // Timeout global para requisições (30 segundos)
        private const val REQUEST_TIMEOUT_MS = 30000L
        
        // Contador para rodízio entre APIs de poster
        private var requestCounter = 0
        private val posterLock = Mutex()
        
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

    // Cookies persistidos após bypass do Cloudflare
    private var persistedCookies: String? = null

    // Headers padrão para todas as requisições
    private val defaultHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$mainUrl/",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
            "Cookie" to (persistedCookies ?: "")
        )

    // Cache simples para posters (evita requisições repetidas)
    private val posterCache = mutableMapOf<String, String?>()

    // Categorias principais
    private val mainCategories = mapOf(
        "Últimos Episódios" to "$mainUrl/episodio/",
        "Animes Mais Vistos" to "$mainUrl/",
        "Dublado" to "$mainUrl/tipo/dublado",
        "Legendado" to "$mainUrl/tipo/legendado",
        "Filmes" to "$mainUrl/filme",
        "Ação" to "$mainUrl/genre/acao",
        "Aventura" to "$mainUrl/genre/aventura", 
        "Animação" to "$mainUrl/genre/animacao",
        "Drama" to "$mainUrl/genre/drama",
        "Crime" to "$mainUrl/genre/crime",
        "Mistério" to "$mainUrl/genre/misterio",
        "Fantasia" to "$mainUrl/genre/fantasia",
        "Terror" to "$mainUrl/genre/terror",
        "Comédia" to "$mainUrl/genre/comedia",
        "Romance" to "$mainUrl/genre/romance",
        "Sci-Fi" to "$mainUrl/genre/ficcao-cientifica",
        "Seinen" to "$mainUrl/genre/seinen",
        "Shounen" to "$mainUrl/genre/shounen",
        "Ecchi" to "$mainUrl/genre/ecchi",
        "Esporte" to "$mainUrl/genre/esporte",
        "Sobrenatural" to "$mainUrl/genre/sobrenatural",
        "Vida Escolar" to "$mainUrl/genre/vida-escolar",
        "Manhwa" to "$mainUrl/genre/Manhwa",
        "Donghua" to "$mainUrl/genre/Donghua"
    )

    override val mainPage = mainPageOf(
        *mainCategories.map { (name, url) -> url to name }.toTypedArray()
    )

    /**
     * Função para buscar poster em APIs externas (Kitsu.io e Jikan.moe)
     * Igual ao AnimesCloud faz
     */
    private suspend fun getPosterFromApi(title: String?): String? {
        if (title.isNullOrBlank()) return null
        
        // Verifica cache primeiro
        val cacheKey = title.lowercase().trim()
        posterCache[cacheKey]?.let { return it }
        
        // Limpa o título removendo palavras desnecessárias
        val cleanTitle = title
            .replace(Regex("(?i)^(Home|Animes|Filmes|Online)\\s+"), "")
            .replace(Regex("(?i)(Dublado|Legendado|Online|HD|TV|Todos os Episódios|Filme|\\d+ª Temporada|\\d+ª|Completo|\\d+\$)"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        
        if (cleanTitle.length < 3) return null

        // Rodízio entre APIs para não sobrecarregar
        return posterLock.withLock {
            delay(150) // Pequeno delay para rate limiting
            
            val turn = requestCounter % 9
            val useKitsu = (turn == 1 || turn == 2 || turn == 4 || turn == 5 || turn == 7 || turn == 8)
            requestCounter++
            
            val posterUrl = if (useKitsu) {
                // Tenta Kitsu.io primeiro
                try {
                    val url = "https://kitsu.io/api/edge/anime?filter[text]=${cleanTitle.replace(" ", "%20")}"
                    val response = app.get(url, timeout = 10)
                    if (response.code == 200) {
                        Regex("""posterImage[^}]*original":"(https:[^"]+)""")
                            .find(response.text)?.groupValues?.get(1)?.replace("\\/", "/")
                    } else null
                } catch (e: Exception) {
                    println("⚠️ [POSTER] Erro no Kitsu para '$cleanTitle': ${e.message}")
                    null
                }
            } else {
                // Tenta Jikan.moe (MyAnimeList)
                try {
                    val url = "https://api.jikan.moe/v4/anime?q=${cleanTitle.replace(" ", "%20")}&limit=1"
                    val response = app.get(url, timeout = 10)
                    if (response.code == 200) {
                        Regex("""large_image_url":"(https:[^"]+)""")
                            .find(response.text)?.groupValues?.get(1)?.replace("\\/", "/")
                    } else null
                } catch (e: Exception) {
                    println("⚠️ [POSTER] Erro no Jikan para '$cleanTitle': ${e.message}")
                    null
                }
            }
            
            // Salva no cache
            posterCache[cacheKey] = posterUrl
            return@withLock posterUrl
        }
    }

    /**
     * Função centralizada para fazer requisições com tratamento Cloudflare
     */
    private suspend fun request(url: String, debugTag: String = "REQUEST"): Document {
        val startTime = System.currentTimeMillis()
        println("🔵 [$debugTag] Iniciando requisição para: $url")
        
        var tentativas = 0
        val maxTentativas = 3
        
        while (tentativas < maxTentativas) {
            try {
                if (!isInitialized) {
                    locker.withLock {
                        if (!isInitialized) {
                            try {
                                println("🟡 [$debugTag] Primeira requisição - tentando resolver Cloudflare para: $mainUrl")
                                
                                val resMain = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                                    app.get(
                                        url = mainUrl, 
                                        headers = mapOf("User-Agent" to USER_AGENT), 
                                        interceptor = cloudflareInterceptor, 
                                        timeout = 30
                                    )
                                }
                                
                                if (resMain == null) {
                                    println("⚠️ [$debugTag] TIMEOUT na requisição inicial")
                                    return@withLock
                                }
                                
                                if (resMain.code == 200) {
                                    val cookieList = mutableListOf<String>()
                                    resMain.okhttpResponse.headers("Set-Cookie").forEach { 
                                        val cookiePart = it.split(";")[0]
                                        cookieList.add(cookiePart)
                                        println("🍪 [$debugTag] Set-Cookie recebido: $cookiePart")
                                    }
                                    
                                    if (cookieList.isNotEmpty()) {
                                        persistedCookies = cookieList.distinct().joinToString("; ")
                                        println("🍪 [$debugTag] Cookies persistidos: $persistedCookies")
                                    }
                                    
                                    isInitialized = true
                                    println("🟢 [$debugTag] Cloudflare resolvido com sucesso!")
                                }
                            } catch (e: Exception) {
                                println("🔴 [$debugTag] Erro ao resolver Cloudflare: ${e.message}")
                            }
                        }
                    }
                }

                println("🟡 [$debugTag] Fazendo requisição principal: $url")
                
                val response = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                    app.get(
                        url = url, 
                        headers = defaultHeaders, 
                        interceptor = cloudflareInterceptor,
                        timeout = 30
                    )
                }
                
                val elapsed = System.currentTimeMillis() - startTime
                println("⏱️ [$debugTag] Tempo total: ${elapsed}ms")
                
                if (response == null) {
                    tentativas++
                    println("⚠️ [$debugTag] TIMEOUT (tentativa $tentativas/$maxTentativas)")
                    if (tentativas < maxTentativas) {
                        delay(2000)
                        continue
                    }
                    return Document("")
                }
                
                println("🟢 [$debugTag] Resposta OK - Código: ${response.code}, Tamanho: ${response.text.length} chars")
                return response.document
                
            } catch (e: Exception) {
                tentativas++
                println("🔴 [$debugTag] Erro na tentativa $tentativas: ${e.message}")
                if (tentativas >= maxTentativas) {
                    return Document("")
                }
                delay(2000)
            }
        }
        
        return Document("")
    }

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

    private suspend fun Element.toEpisodeSearchResponse(): AnimeSearchResponse? {
        val href = selectFirst(ITEM_LINK)?.attr("href") ?: return null
        val episodeTitle = selectFirst(ITEM_TITLE)?.text()?.trim() ?: return null
        val episodeNumber = extractEpisodeNumber(episodeTitle)
        val animeTitle = extractAnimeTitleFromEpisode(episodeTitle)
        val isDubbed = isDubbed(episodeTitle)
        val serieName = selectFirst(EPISODE_SERIE)?.text()?.trim() ?: animeTitle

        val cleanTitle = cleanTitle(serieName)
        
        // Tenta buscar poster da API primeiro
        val posterFromApi = getPosterFromApi(cleanTitle)
        val posterUrl = posterFromApi ?: selectFirst(ITEM_POSTER)?.attr("src")?.let { fixUrl(it) }

        return newAnimeSearchResponse(cleanTitle, fixUrl(href)) {
            this.posterUrl = posterUrl
            this.type = TvType.Anime

            val dubStatus = if (isDubbed) DubStatus.Dubbed else DubStatus.Subbed
            addDubStatus(dubStatus, episodeNumber)
        }
    }

    private suspend fun Element.toAnimeSearchResponse(): AnimeSearchResponse? {
        val href = selectFirst(ITEM_LINK)?.attr("href") ?: return null
        val rawTitle = selectFirst(ITEM_TITLE)?.text()?.trim() ?: return null
        val cleanedTitle = cleanTitle(rawTitle).ifBlank { return null }
        val isDubbed = isDubbed(rawTitle)
        val year = selectFirst(ANIME_YEAR)?.text()?.trim()?.toIntOrNull()
        val scoreText = selectFirst(ANIME_SCORE)?.text()?.trim()

        val score = scoreText?.toFloatOrNull()?.let { 
            Score.from10(it)
        }

        val isMovie = href.contains("/filme/") || cleanedTitle.contains("filme", true)
        val type = if (isMovie) TvType.AnimeMovie else TvType.Anime
        
        // Tenta buscar poster da API primeiro
        val posterFromApi = getPosterFromApi(cleanedTitle)
        val posterUrl = posterFromApi ?: selectFirst(ITEM_POSTER)?.attr("src")?.let { fixUrl(it) }

        return newAnimeSearchResponse(cleanedTitle, fixUrl(href)) {
            this.posterUrl = posterUrl
            this.type = type
            this.year = year
            this.score = score
            addDubStatus(isDubbed, null)
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val baseUrl = request.data
        var url = baseUrl
        val tag = "MAINPAGE-${request.name}"

        println("🏠 [$tag] Carregando página principal - Página: $page, URL base: $baseUrl")

        if (page > 1) {
            url = when {
                baseUrl == "$mainUrl/episodio/" -> "$baseUrl/page/$page/"
                baseUrl == "$mainUrl/" -> baseUrl
                baseUrl.contains("/?s=") -> baseUrl.replace("/?s=", "/page/$page/?s=")
                else -> "$baseUrl/page/$page/"
            }
        }

        return try {
            val document = request(url, tag)
            
            if (document.text().isBlank()) {
                println("⚠️ [$tag] Documento vazio recebido")
                return newHomePageResponse(HomePageList(request.name, emptyList(), false), false)
            }

            when (request.name) {
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
                    val popularItems = mutableListOf<AnimeSearchResponse>()

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
        } catch (e: Exception) {
            println("🔴 [$tag] Erro ao carregar página: ${e.message}")
            newHomePageResponse(HomePageList(request.name, emptyList(), false), false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()
        
        println("🔍 [SEARCH] Buscando por: $query")
        
        val searchUrl = "$mainUrl$SEARCH_PATH${query.replace(" ", "+")}"
        val document = request(searchUrl, "SEARCH")

        if (document.text().isBlank()) {
            return emptyList()
        }

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
        println("📺 [LOAD] Carregando detalhes de: $url")
        
        val document = request(url, "LOAD")
        
        if (document.text().isBlank()) {
            return newAnimeLoadResponse("Erro ao carregar", url, TvType.Anime) {
                this.plot = "Não foi possível carregar os detalhes. O site pode estar lento ou indisponível."
            }
        }

        val rawTitle = document.selectFirst(DETAIL_TITLE)?.text()?.trim() ?: "Sem Título"
        val title = cleanTitle(rawTitle)

        // Tenta buscar poster da API primeiro
        val posterFromApi = getPosterFromApi(title)
        val poster = posterFromApi ?: document.selectFirst(DETAIL_POSTER)?.attr("src")?.let { fixUrl(it) }

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
        println("🔗 [LINKS] Extraindo links de: $data")
        return AnimeQVideoExtractor.extractVideoLinks(data, callback = callback)
    }
}
