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
        // O ÚNICO HEADER QUE SERÁ USADO EM TUDO
        // =================================================================
        private const val MY_USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"
        
        // Mapa contendo APENAS o User-Agent
        private val ONLY_UA_HEADER = mapOf(
            "User-Agent" to MY_USER_AGENT
        )
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

    // APLICANDO USER-AGENT NA HOME
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1 && request.data.contains("/?s=")) request.data.replace("/?s=", "/page/$page/?s=") else request.data
        val document = app.get(url, headers = ONLY_UA_HEADER).document
        val items = document.select("$ANIME_CARD, $EPISODE_CARD").mapNotNull { if (it.selectFirst(EPISODE_NUMBER_SELECTOR) != null) it.toEpisodeSearchResponse() else it.toAnimeSearchResponse() }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, true)
    }

    // APLICANDO USER-AGENT NA BUSCA
    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl$SEARCH_PATH${query.replace(" ", "+")}", headers = ONLY_UA_HEADER).document.select("$ANIME_CARD, $EPISODE_CARD").mapNotNull { if (it.selectFirst(EPISODE_NUMBER_SELECTOR) != null) it.toEpisodeSearchResponse() else it.toAnimeSearchResponse() }
    }

    // APLICANDO USER-AGENT NO LOAD DO ANIME
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = ONLY_UA_HEADER).document
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
    // EXTRAÇÃO E PLAYER (TUDO COM O MESMO USER AGENT)
    // ======================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val actualUrl = data.split("|poster=")[0]
        
        // 1. Carrega a página do episódio com o UA
        val document = app.get(actualUrl, headers = ONLY_UA_HEADER).document
        var linksFound = false

        document.select("iframe[src*='bg.mp4']").firstOrNull()?.let { iframe ->
            val initialSrc = iframe.attr("src")
            println("🔎 [AniTube] Iframe encontrado: $initialSrc")
            
            try {
                // 2. Acessa o Iframe com o MESMO UA
                // Importante: allowRedirects=false para garantir controle, 
                // mas se o app.get seguir, ele precisa usar os mesmos headers.
                val response1 = app.get(initialSrc, headers = ONLY_UA_HEADER, allowRedirects = false)
                var contentHtml = ""

                if (response1.code in 300..399) {
                    val location = response1.headers["location"] ?: response1.headers["Location"]
                    if (location != null) {
                        println("🔄 [AniTube] Redirect: $location")
                        // 3. Acessa o Redirect (API) com o MESMO UA
                        val response2 = app.get(location, headers = ONLY_UA_HEADER)
                        contentHtml = response2.text
                    }
                } else if (response1.code == 200) {
                    contentHtml = response1.text
                }

                if (contentHtml.isNotBlank()) {
                    val decoded = decodePacked(contentHtml)
                    if (decoded != null) {
                        // Regex MP4
                        Regex("https?://[^\\s'\"]+videoplayback[^\\s'\"]*").findAll(decoded).forEach { match ->
                            val link = match.value
                            println("🎬 [AniTube] MP4: $link")
                            
                            val quality = if (link.contains("itag=37")) 1080 else 720

                            // 4. Entrega para o Player com o MESMO UA (e nada mais)
                            callback(newExtractorLink(name, "JWPlayer MP4", link, ExtractorLinkType.VIDEO) {
                                this.headers = ONLY_UA_HEADER
                                this.quality = quality
                            })
                            linksFound = true
                        }
                        
                        // Regex HLS
                        Regex("https?://[^\\s'\"]+\\.m3u8[^\\s'\"]*").findAll(decoded).forEach { match ->
                             val link = match.value
                             println("📡 [AniTube] HLS: $link")
                             
                             // 4. Entrega para o Player com o MESMO UA
                             callback(newExtractorLink(name, "AniTube HLS", link, ExtractorLinkType.M3U8) {
                                 this.headers = ONLY_UA_HEADER
                             })
                             linksFound = true
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Fallbacks (Também usando o UA se possível, embora o extractorLink do Cloudstream gerencie isso internamente na maioria dos casos)
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
