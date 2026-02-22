package com.AnimeQ

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI

class AnimeQ : MainAPI() {
    override var mainUrl = "https://animeq.net"
    override var name = "AnimeQ"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override val usesWebView = false

    private val cloudflareInterceptor = CloudflareKiller()

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private val locker = Mutex()
        private var isInitialized = false
        private const val REQUEST_TIMEOUT_MS = 30000L
        
        // Selectors
        private const val SEARCH_PATH = "/?s="
        private const val EPISODE_PAGE_ITEM = ".item.se.episodes"
        private const val GENRE_PAGE_ITEM = ".items.full .item.tvshows, .items.full .item.movies"
        private const val ITEM_TITLE = ".data h3 a"
        private const val ITEM_POSTER = ".poster img"
        private const val ITEM_LINK = "a[href]"
        private const val EPISODE_SERIE = ".data .serie"
        private const val ANIME_YEAR = ".data span"
        private const val ANIME_SCORE = ".rating"
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

    private val defaultHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$mainUrl/",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
            "Cookie" to (persistedCookies ?: "")
        )

    // APENAS 5 CATEGORIAS - como estava funcionando
    private val mainCategories = mapOf(
        "Últimos Episódios" to "$mainUrl/episodio/",
        "Animes Mais Vistos" to "$mainUrl/",
        "Dublado" to "$mainUrl/tipo/dublado",
        "Legendado" to "$mainUrl/tipo/legendado",
        "Filmes" to "$mainUrl/filme"
    )

    override val mainPage = mainPageOf(
        *mainCategories.map { (name, url) -> url to name }.toTypedArray()
    )

    // Função SIMPLES para poster - usa o próprio site com headers
    private fun getPosterWithHeaders(element: Element): String? {
        val posterUrl = element.selectFirst(ITEM_POSTER)?.attr("src")?.let { fixUrl(it) }
        if (posterUrl != null && persistedCookies != null) {
            // Adiciona os cookies como query parameter (funciona em alguns casos)
            return "$posterUrl?cf-cookies=${persistedCookies?.hashCode()}"
        }
        return posterUrl
    }

    private suspend fun request(url: String, debugTag: String = "REQUEST"): Document {
        val startTime = System.currentTimeMillis()
        println("🔵 [$debugTag] Iniciando requisição para: $url")
        
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
                            
                            if (resMain != null && resMain.code == 200) {
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
                println("⚠️ [$debugTag] TIMEOUT - retornando documento vazio")
                return Document("")
            }
            
            println("🟢 [$debugTag] Resposta OK - Código: ${response.code}, Tamanho: ${response.text.length} chars")
            return response.document
            
        } catch (e: Exception) {
            println("🔴 [$debugTag] Erro: ${e.message}")
            return Document("")
        }
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
        
        // Usa função simples para poster
        val posterUrl = getPosterWithHeaders(this)

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
        val score = scoreText?.toFloatOrNull()?.let { Score.from10(it) }
        val isMovie = href.contains("/filme/") || cleanedTitle.contains("filme", true)
        val type = if (isMovie) TvType.AnimeMovie else TvType.Anime
        
        // Usa função simples para poster
        val posterUrl = getPosterWithHeaders(this)

        return newAnimeSearchResponse(cleanedTitle, fixUrl(href)) {
            this.posterUrl = posterUrl
            this.type = type
            this.year = year
            this.score = score
            addDubStatus(isDubbed, null)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
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
                println("⚠️ [$tag] Documento vazio")
                return newHomePageResponse(HomePageList(request.name, emptyList(), false), false)
            }

            when (request.name) {
                "Últimos Episódios" -> {
                    val episodeElements = document.select(EPISODE_PAGE_ITEM)
                    val items = episodeElements.mapNotNull { it.toEpisodeSearchResponse() }
                    newHomePageResponse(
                        list = HomePageList(request.name, items, true),
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
                        list = HomePageList(request.name, popularItems.distinctBy { it.url }, false),
                        hasNext = false
                    )
                }
                else -> {
                    val isEpisodePage = baseUrl.contains("/episodio/")
                    val isGenrePage = baseUrl.contains("/genre/") || baseUrl.contains("/tipo/") || baseUrl == "$mainUrl/filme/"

                    val items = if (isEpisodePage) {
                        document.select(EPISODE_PAGE_ITEM).mapNotNull { it.toEpisodeSearchResponse() }
                    } else if (isGenrePage) {
                        document.select(GENRE_PAGE_ITEM).mapNotNull { it.toAnimeSearchResponse() }
                    } else {
                        document.select(".item.tvshows, .item.movies").mapNotNull { it.toAnimeSearchResponse() }
                    }

                    val hasNext = when {
                        isEpisodePage -> document.select(".pagination a").isNotEmpty()
                        isGenrePage -> document.select(".pagination a").isNotEmpty()
                        else -> false
                    }

                    newHomePageResponse(
                        list = HomePageList(request.name, items.distinctBy { it.url }, false),
                        hasNext = hasNext
                    )
                }
            }
        } catch (e: Exception) {
            println("🔴 [$tag] Erro: ${e.message}")
            newHomePageResponse(HomePageList(request.name, emptyList(), false), false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()
        println("🔍 [SEARCH] Buscando por: $query")
        
        val searchUrl = "$mainUrl$SEARCH_PATH${query.replace(" ", "+")}"
        val document = request(searchUrl, "SEARCH")

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
        println("📺 [LOAD] Carregando: $url")
        val document = request(url, "LOAD")
        
        if (document.text().isBlank()) {
            return newAnimeLoadResponse("Erro", url, TvType.Anime) {
                this.plot = "Não foi possível carregar"
            }
        }

        val rawTitle = document.selectFirst(DETAIL_TITLE)?.text()?.trim() ?: "Sem Título"
        val title = cleanTitle(rawTitle)
        
        // Poster da página de detalhes
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
        }

        val genres = document.select(DETAIL_GENRES).mapNotNull { it.text().trim() }
        val year = document.selectFirst(DETAIL_YEAR)?.text()?.trim()?.let {
            "\\b(\\d{4})\\b".toRegex().find(it)?.groupValues?.get(1)?.toIntOrNull()
        }
        
        val score = document.selectFirst(DETAIL_SCORE)?.text()?.trim()?.toFloatOrNull()?.let {
            Score.from10(it)
        }

        val isDubbed = rawTitle.contains("dublado", true) || url.contains("dublado", true)
        val isMovie = url.contains("/filme/") || rawTitle.contains("filme", true)

        val episodesList = if (!isMovie) {
            val episodeElements = document.select(EPISODE_LIST)
            val episodeImages = document.select(EPISODE_IMAGES)
            val episodeNumbers = document.select(EPISODE_NUMBER)

            episodeElements.mapIndexed { index, element ->
                val episodeUrl = element.attr("href")
                val epNumber = (index + 1)
                
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

        val showStatus = if (isMovie || episodesList.size >= 50) ShowStatus.Completed else ShowStatus.Ongoing

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
