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

    private val cloudflareInterceptor = CloudflareKiller()

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private val locker = Mutex()
        private var isInitialized = false
        private val posterLock = Mutex()
        private var requestCounter = 0
    }

    private var persistedCookies: String? = null

    private val defaultHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$mainUrl/",
            "Cookie" to (persistedCookies ?: "")
        )

    // FUNÇÃO REQUEST SIMPLES - igual ao AnimesCloud
    private suspend fun request(url: String): Document {
        if (!isInitialized) {
            locker.withLock {
                if (!isInitialized) {
                    try {
                        val resMain = app.get(
                            mainUrl, 
                            headers = mapOf("User-Agent" to USER_AGENT), 
                            interceptor = cloudflareInterceptor, 
                            timeout = 60
                        )
                        if (resMain.code == 200) {
                            val cookieList = mutableListOf<String>()
                            resMain.okhttpResponse.headers("Set-Cookie").forEach { 
                                cookieList.add(it.split(";")[0]) 
                            }
                            resMain.okhttpResponse.request.header("Cookie")?.let { 
                                cookieList.add(it) 
                            }
                            persistedCookies = cookieList.distinct().joinToString("; ")
                            isInitialized = true
                        }
                    } catch (_: Exception) {}
                }
            }
        }
        return app.get(url, headers = defaultHeaders, interceptor = cloudflareInterceptor).document
    }

    // FUNÇÃO DE POSTER IGUAL AO ANIMESCLOUD - usando Kitsu e Jikan
    private suspend fun getPoster(title: String?): String? {
        if (title.isNullOrBlank()) return null
        
        // Limpa o título igual ao AnimesCloud
        val cleanTitle = title
            .replace(Regex("(?i)^(Home|Animes|Filmes|Online)\\s+"), "")
            .replace(Regex("(?i)(Dublado|Legendado|Online|HD|TV|Todos os Episódios|Filme|\\d+ª Temporada|\\d+ª|Completo|\\d+\$)"), "")
            .trim()

        return posterLock.withLock {
            // Delay igual ao AnimesCloud
            kotlinx.coroutines.delay(111)
            
            // Rodízio entre APIs igual ao AnimesCloud
            val turn = requestCounter % 9
            val useKitsu = (turn == 1 || turn == 2 || turn == 4 || turn == 5 || turn == 7 || turn == 8)
            requestCounter++

            if (useKitsu) {
                try {
                    val url = "https://kitsu.io/api/edge/anime?filter[text]=${cleanTitle.replace(" ", "%20")}"
                    val response = app.get(url, timeout = 10)
                    if (response.code == 200) {
                        Regex("""posterImage[^}]*original":"(https:[^"]+)""")
                            .find(response.text)?.groupValues?.get(1)?.replace("\\/", "/")
                    } else null
                } catch (_: Exception) { null }
            } else {
                try {
                    val url = "https://api.jikan.moe/v4/anime?q=${cleanTitle.replace(" ", "%20")}&limit=1"
                    val response = app.get(url, timeout = 10)
                    if (response.code == 200) {
                        Regex("""large_image_url":"(https:[^"]+)""")
                            .find(response.text)?.groupValues?.get(1)?.replace("\\/", "/")
                    } else null
                } catch (_: Exception) { null }
            }
        }
    }

    // APENAS 2 CATEGORIAS - igual ao AnimesCloud
    override val mainPage = mainPageOf(
        "tipo/dublado" to "Dublados",
        "tipo/legendado" to "Legendados"
    )

    // MAIN PAGE - igual ao AnimesCloud
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = request("$mainUrl/${request.data}")
        val items = document.select("div.items article, div.content div.items article, .item.tvshows, .item.movies")
        val home = items.mapNotNull { it.toSearchResult() }
        return newHomePageResponse(HomePageList(request.name, home, false), false)
    }

    // CONVERSÃO PARA SEARCH RESPONSE - igual ao AnimesCloud
    private suspend fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("div.data h3 a, h3 a")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("div.data h3 a, h3 a")?.attr("href") ?: return null)
        val scoreValue = this.selectFirst("div.rating")?.text()?.toDoubleOrNull()
        val poster = getPoster(title)

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
            this.quality = SearchQuality.HD
            this.score = Score.from10(scoreValue)
        }
    }

    // BUSCA - igual ao AnimesCloud
    override suspend fun search(query: String): List<SearchResponse> {
        val document = request("$mainUrl/?s=${query.replace(" ", "+")}")
        return document.select("div.item, article, .item.tvshows, .item.movies, .item.se.episodes").mapNotNull { item ->
            val title = item.selectFirst("div.data h3 a, h3 a")?.text()?.trim() ?: return@mapNotNull null
            val href = fixUrl(item.selectFirst("div.data h3 a, h3 a")?.attr("href") ?: return@mapNotNull null)
            val yearVal = item.selectFirst("span.year, .data span")?.text()?.trim()?.toIntOrNull()
            val poster = getPoster(title)

            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
                this.year = yearVal
            }
        }
    }

    // LOAD - simplificado igual ao AnimesCloud
    override suspend fun load(url: String): LoadResponse {
        val document = request(url)
        val title = document.selectFirst("h1")?.text()?.trim() ?: ""
        
        // Pega os dados básicos
        val poster = getPoster(title) ?: document.selectFirst(".poster img, div.g-item a")?.attr("src")?.let { fixUrl(it) }
        val plot = document.selectFirst(".wp-content p, .wp-content")?.text()?.trim()
        val year = document.selectFirst(".date")?.text()?.trim()?.let {
            Regex("\\d{4}").find(it)?.value?.toIntOrNull()
        }
        val scoreValue = document.select(".dt_rating_vgs, .rating").text().toDoubleOrNull()
        
        val isMovie = url.contains("/filme/") || document.select(".episodios").isEmpty()
        val type = if (isMovie) TvType.AnimeMovie else TvType.Anime

        return if (isMovie) {
            newMovieLoadResponse(title, url, type, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.score = Score.from10(scoreValue)
            }
        } else {
            // Pega episódios
            val episodes = document.select(".episodios li a, .episodiotitle a").mapIndexed { index, element ->
                val epUrl = fixUrl(element.attr("href"))
                newEpisode(epUrl) {
                    this.name = "Episódio ${index + 1}"
                    this.episode = index + 1
                    this.posterUrl = poster
                }
            }

            newAnimeLoadResponse(title, url, type) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.score = Score.from10(scoreValue)
                addEpisodes(DubStatus.Subbed, episodes)
            }
        }
    }

    // LINKS - igual ao AnimesCloud
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return AnimeQVideoExtractor.extractVideoLinks(data, callback = callback)
    }
}
