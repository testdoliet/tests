package com.AniTube

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLDecoder

class AniTube : MainAPI() {
    override var mainUrl = "https://www.anitube.news"
    override var name = "AniTube"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Anime)
    override val usesWebView = false

    companion object {
        private const val SEARCH_PATH = "/?s="
        private const val ANIME_CARD = ".aniItem"
        private const val EPISODE_CARD = ".epiItem"
        private const val TITLE_SELECTOR = ".aniItemNome, .epiItemNome"
        private const val POSTER_SELECTOR = ".aniItemImg img, .epiItemImg img"
        private const val AUDIO_BADGE_SELECTOR = ".aniCC, .epiCC"
        private const val EPISODE_NUMBER_SELECTOR = ".epiItemInfos .epiItemNome"
        private const val ANIME_TITLE = "h1"
        private const val ANIME_POSTER = "#capaAnime img"
        private const val ANIME_SYNOPSIS = "#sinopse2"
        private const val EPISODE_LIST = ".pagAniListaContainer > a"
        private const val PLAYER_FHD = "#blog2 iframe"
        private const val PLAYER_BACKUP = "#blog1 iframe"

        // =================================================================
        // DADOS DE NAVEGAÇÃO CONSTANTES
        // =================================================================
        // Usamos este UA para TUDO: Extração e Player
        private const val COMMON_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val REFERER_API = "https://api.anivideo.net/"
    }

    private fun decodePacked(packed: String): String? {
        try {
            val regex = "eval\\s*\\(\\s*function\\s*\\(p,a,c,k,e,d\\).*?\\}\\('(.*?)',(\\d+),(\\d+),'(.*?)'\\.split\\('\\|'\\)".toRegex()
            val match = regex.find(packed) ?: return null
            val (p, aStr, cStr, kStr) = match.destructured
            val payload = p
            val radix = aStr.toInt()
            val count = cStr.toInt()
            val keywords = kStr.split("|")
            fun encodeBase(num: Int): String = if (num < radix) charset[num].toString() else encodeBase(num / radix) + charset[num % radix]
            val charset = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
            val dict = HashMap<String, String>()
            for (i in 0 until count) {
                val key = encodeBase(i)
                val value = keywords.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: key
                dict[key] = value
            }
            return payload.replace(Regex("\\b\\w+\\b")) { r -> dict[r.value] ?: r.value }
        } catch (e: Exception) { return null }
    }

    private val genresMap = mapOf("Ação" to "acao", "Aventura" to "aventura", "Comédia" to "comedia", "Drama" to "drama", "Ecchi" to "ecchi", "Fantasia" to "fantasia", "Romance" to "romance", "Seinen" to "seinen", "Shounen" to "shounen", "Slice Of Life" to "slice%20of%20life", "Sobrenatural" to "sobrenatural", "Terror" to "terror", "Isekai" to "isekai")

    override val mainPage = mainPageOf("$mainUrl" to "Últimos Episódios", "$mainUrl" to "Animes Mais Vistos", *genresMap.map { (genre, slug) -> "$mainUrl/?s=$slug" to genre }.toTypedArray())

    private fun extractEpisodeNumber(title: String): Int? = Regex("\\d+").find(title)?.value?.toIntOrNull()
    private fun extractM3u8FromUrl(url: String): String? = if (url.contains("d=")) try { URLDecoder.decode(url.substringAfter("d=").substringBefore("&"), "UTF-8") } catch (e: Exception) { null } else url

    private fun Element.toEpisodeSearchResponse(): AnimeSearchResponse? {
        val href = selectFirst("a")?.attr("href") ?: return null
        val title = selectFirst(TITLE_SELECTOR)?.text() ?: return null
        val poster = selectFirst(POSTER_SELECTOR)?.attr("src")
        return newAnimeSearchResponse(title, fixUrl(href)) { this.posterUrl = poster }
    }
    
    private fun Element.toAnimeSearchResponse(): AnimeSearchResponse? {
        val href = selectFirst("a")?.attr("href") ?: return null
        val title = selectFirst(TITLE_SELECTOR)?.text() ?: return null
        val poster = selectFirst(POSTER_SELECTOR)?.attr("src")
        return newAnimeSearchResponse(title, fixUrl(href)) { this.posterUrl = poster }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1 && request.data.contains("/?s=")) request.data.replace("/?s=", "/page/$page/?s=") else request.data
        val document = app.get(url).document
        val items = document.select("$ANIME_CARD, $EPISODE_CARD").mapNotNull { if (it.selectFirst(EPISODE_NUMBER_SELECTOR) != null) it.toEpisodeSearchResponse() else it.toAnimeSearchResponse() }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl$SEARCH_PATH${query.replace(" ", "+")}").document.select("$ANIME_CARD, $EPISODE_CARD").mapNotNull { if (it.selectFirst(EPISODE_NUMBER_SELECTOR) != null) it.toEpisodeSearchResponse() else it.toAnimeSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst(ANIME_TITLE)?.text() ?: "Anime"
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = doc.selectFirst(ANIME_POSTER)?.attr("src")
            this.plot = doc.selectFirst(ANIME_SYNOPSIS)?.text()
            val episodes = doc.select(EPISODE_LIST).mapNotNull { 
                val epNum = extractEpisodeNumber(it.text()) ?: 1
                newEpisode(it.attr("href")) { this.episode = epNum; this.name = "Episódio $epNum" }
            }
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ======================================================================
    // 4. EXTRAÇÃO DE LINKS (SINCRONIZADA)
    // ======================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val actualUrl = data.split("|poster=")[0]
        println("\n🛑 [AniTube] LOAD LINKS: $actualUrl")

        val document = app.get(actualUrl).document
        var linksFound = false

        document.select("iframe[src*='bg.mp4']").firstOrNull()?.let { iframe ->
            val initialSrc = iframe.attr("src")
            
            try {
                // Etapa 1: Obter Redirect
                val headers1 = mapOf("User-Agent" to COMMON_USER_AGENT, "Referer" to actualUrl)
                val response1 = app.get(initialSrc, headers = headers1, allowRedirects = false)
                
                // IMPORTANTE: Captura cookies iniciais
                val cookies = mutableMapOf<String, String>()
                cookies.putAll(response1.cookies)

                if (response1.code in 300..399) {
                    val location = response1.headers["location"] ?: response1.headers["Location"]
                    if (location != null) {
                        // Etapa 2: Acessar a API com os dados da Etapa 1
                        val headers2 = mapOf(
                            "User-Agent" to COMMON_USER_AGENT,
                            "Referer" to "https://www.anitube.news/",
                            "Accept" to "*/*"
                        )
                        
                        // Passamos os cookies capturados aqui
                        val response2 = app.get(location, headers = headers2, cookies = cookies)
                        
                        // Atualizamos os cookies com a resposta da API (O "crachá" final)
                        cookies.putAll(response2.cookies)
                        val contentHtml = response2.text

                        if (contentHtml.isNotBlank()) {
                            val decoded = decodePacked(contentHtml)
                            if (decoded != null) {
                                
                                // Preparar Headers do Player
                                // O SEGREDO: Usar o MESMO UA, o Referer da API e os Cookies da sessão
                                val playerHeaders = mutableMapOf(
                                    "User-Agent" to COMMON_USER_AGENT,
                                    "Referer" to REFERER_API, // https://api.anivideo.net/
                                    "Accept" to "*/*"
                                )

                                if (cookies.isNotEmpty()) {
                                    val cookieStr = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                                    playerHeaders["Cookie"] = cookieStr
                                    println("🍪 [AniTube] Cookies passados ao Player: $cookieStr")
                                }

                                // Regex para links
                                Regex("https?://[^\\s'\"]+videoplayback[^\\s'\"]*").findAll(decoded).forEach { match ->
                                    val link = match.value // Link ORIGINAL (sem forçar rr3 para não quebrar sig)
                                    println("🎬 [AniTube] MP4: $link")
                                    
                                    val quality = if (link.contains("itag=37")) 1080 else 720

                                    callback(newExtractorLink(name, "JWPlayer MP4", link, ExtractorLinkType.VIDEO) {
                                        this.headers = playerHeaders
                                        this.quality = quality
                                    })
                                    linksFound = true
                                }
                                
                                Regex("https?://[^\\s'\"]+\\.m3u8[^\\s'\"]*").findAll(decoded).forEach { match ->
                                     val link = match.value
                                     println("📡 [AniTube] HLS: $link")
                                     callback(newExtractorLink(name, "AniTube HLS", link, ExtractorLinkType.M3U8) {
                                         this.headers = playerHeaders
                                     })
                                     linksFound = true
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Fallbacks
        document.selectFirst(PLAYER_FHD)?.let { 
             val src = it.attr("src")
             if(src.isNotBlank() && !src.contains("bg.mp4")) {
                 callback(newExtractorLink(name, "Player FHD", extractM3u8FromUrl(src) ?: src, ExtractorLinkType.M3U8))
                 linksFound = true
             }
        }
        
        document.selectFirst(PLAYER_BACKUP)?.let { 
             val src = it.attr("src")
             if(src.isNotBlank() && !src.contains("bg.mp4")) {
                 callback(newExtractorLink(name, "Player Backup", src, ExtractorLinkType.VIDEO))
                 linksFound = true
             }
        }

        return linksFound
    }
}
