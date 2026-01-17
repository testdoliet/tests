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
        private const val LATEST_EPISODES_SECTION = ".epiContainer"
        private const val ANIME_TITLE = "h1"
        private const val ANIME_POSTER = "#capaAnime img"
        private const val ANIME_SYNOPSIS = "#sinopse2"
        private const val ANIME_METADATA = ".boxAnimeSobre .boxAnimeSobreLinha"
        private const val EPISODE_LIST = ".pagAniListaContainer > a"
        private const val PLAYER_FHD = "#blog2 iframe"
        private const val PLAYER_BACKUP = "#blog1 iframe"

        // =================================================================
        // HEADERS EXATOS (BASEADO NO SEU EXEMPLO JS/CURL)
        // =================================================================
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"

        private val VIDEO_HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Accept-Language" to "pt-BR",
            "Priority" to "i",
            "Sec-Ch-Ua" to "\"Chromium\";v=\"127\", \"Not)A;Brand\";v=\"99\"",
            "Sec-Ch-Ua-Mobile" to "?1",
            "Sec-Ch-Ua-Platform" to "\"Android\"",
            "Sec-Fetch-Dest" to "video",
            "Sec-Fetch-Mode" to "no-cors",
            "Sec-Fetch-Site" to "cross-site",
            "Referer" to "https://api.anivideo.net/",
            "X-Client-Data" to "COD2ygE="
        )

        // Headers para navegação interna (Extração do HTML)
        private val EXTRACTION_HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
            "Upgrade-Insecure-Requests" to "1"
        )
    }

    // ======================================================================
    // 1. DECODIFICADOR PACKER
    // ======================================================================
    private fun decodePacked(packed: String): String? {
        try {
            val regex = "eval\\s*\\(\\s*function\\s*\\(p,a,c,k,e,d\\).*?\\}\\('(.*?)',(\\d+),(\\d+),'(.*?)'\\.split\\('\\|'\\)".toRegex()
            val match = regex.find(packed) ?: return null
            
            val (p, aStr, cStr, kStr) = match.destructured
            val payload = p
            val radix = aStr.toInt()
            val count = cStr.toInt()
            val keywords = kStr.split("|")

            fun encodeBase(num: Int): String {
                val charset = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
                return if (num < radix) {
                    charset[num].toString()
                } else {
                    encodeBase(num / radix) + charset[num % radix]
                }
            }

            val dict = HashMap<String, String>()
            for (i in 0 until count) {
                val key = encodeBase(i)
                val value = keywords.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: key
                dict[key] = value
            }

            return payload.replace(Regex("\\b\\w+\\b")) { r -> dict[r.value] ?: r.value }
        } catch (e: Exception) {
            return null
        }
    }

    // ======================================================================
    // 2. CONFIGURAÇÕES E MAPAS
    // ======================================================================
    private val genresMap = mapOf(
        "Ação" to "acao", "Artes Marciais" to "artes%20marciais", "Aventura" to "aventura",
        "Comédia" to "comedia", "Drama" to "drama", "Ecchi" to "ecchi", "Fantasia" to "fantasia",
        "Romance" to "romance", "Seinen" to "seinen", "Shounen" to "shounen", 
        "Slice Of Life" to "slice%20of%20life", "Sobrenatural" to "sobrenatural",
        "Terror" to "terror", "Vida Escolar" to "vida%20escolar", "Isekai" to "isekai"
    )

    override val mainPage = mainPageOf(
        "$mainUrl" to "Últimos Episódios",
        "$mainUrl" to "Animes Mais Vistos",
        *genresMap.map { (genre, slug) -> "$mainUrl/?s=$slug" to genre }.toTypedArray()
    )

    private fun cleanTitle(dirtyTitle: String): String = dirtyTitle.trim()
    private fun extractEpisodeNumber(title: String): Int? = Regex("\\d+").find(title)?.value?.toIntOrNull()
    private fun isDubbed(element: Element): Boolean = element.selectFirst(AUDIO_BADGE_SELECTOR)?.text()?.contains("Dublado", true) ?: false
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

    // ======================================================================
    // 3. MÉTODOS PRINCIPAIS
    // ======================================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1 && request.data.contains("/?s=")) request.data.replace("/?s=", "/page/$page/?s=") else request.data
        val document = app.get(url).document
        val items = document.select("$ANIME_CARD, $EPISODE_CARD").mapNotNull { 
            if (it.selectFirst(EPISODE_NUMBER_SELECTOR) != null) it.toEpisodeSearchResponse() else it.toAnimeSearchResponse()
        }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl$SEARCH_PATH${query.replace(" ", "+")}").document
            .select("$ANIME_CARD, $EPISODE_CARD")
            .mapNotNull { if (it.selectFirst(EPISODE_NUMBER_SELECTOR) != null) it.toEpisodeSearchResponse() else it.toAnimeSearchResponse() }
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
    // 4. EXTRAÇÃO DE LINKS
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

        // -----------------------------------------------------------
        // 1. Extração JWPlayer (bg.mp4) - MANUAL
        // -----------------------------------------------------------
        document.select("iframe[src*='bg.mp4']").firstOrNull()?.let { iframe ->
            val initialSrc = iframe.attr("src")
            println("🔎 [AniTube] Iframe encontrado: $initialSrc")
            
            try {
                // Passo 1: Headers para navegação inicial
                val headersStep1 = EXTRACTION_HEADERS.toMutableMap()
                headersStep1["Referer"] = actualUrl 

                // Request 1: Pega o redirect manualmente
                val response1 = app.get(initialSrc, headers = headersStep1, allowRedirects = false)
                var contentHtml = ""

                if (response1.code in 300..399) {
                    val location = response1.headers["location"] ?: response1.headers["Location"]
                    if (location != null) {
                        println("🔄 [AniTube] Redirect: $location")
                        
                        // Passo 2: Request final
                        val headersStep2 = EXTRACTION_HEADERS.toMutableMap()
                        headersStep2["Referer"] = "https://www.anitube.news/"

                        val response2 = app.get(location, headers = headersStep2)
                        contentHtml = response2.text
                    }
                } else if (response1.code == 200) {
                    contentHtml = response1.text
                }

                if (contentHtml.isNotBlank()) {
                    val decoded = decodePacked(contentHtml)
                    if (decoded != null) {
                        // Regex MP4 (Google Video)
                        val mp4Regex = Regex("https?://[^\\s'\"]+videoplayback[^\\s'\"]*")
                        mp4Regex.findAll(decoded).forEach { match ->
                            var link = match.value
                            
                            // 🚀 FORÇAR RR3 (Opcional, mas estava no seu teste anterior)
                            link = link.replace(Regex("https://rr\\d+"), "https://rr3")
                            
                            println("🎬 [AniTube] MP4 (rr3): $link")
                            
                            val quality = when {
                                link.contains("itag=37") -> 1080
                                link.contains("itag=22") -> 720
                                link.contains("itag=18") -> 360
                                else -> 360
                            }

                            // 🚨 USANDO HEADERS EXATOS (X-Client-Data, Sec-Ch-Ua, etc)
                            callback(newExtractorLink(name, "JWPlayer MP4 (rr3)", link, ExtractorLinkType.VIDEO) {
                                this.headers = VIDEO_HEADERS
                                this.quality = quality
                            })
                            linksFound = true
                        }
                        
                        // Regex HLS
                        val m3u8Regex = Regex("https?://[^\\s'\"]+\\.m3u8[^\\s'\"]*")
                        m3u8Regex.findAll(decoded).forEach { match ->
                            val link = match.value.replace(Regex("https://rr\\d+"), "https://rr3")
                            println("📡 [AniTube] HLS (rr3): $link")
                            
                            callback(newExtractorLink(name, "AniTube HLS (rr3)", link, ExtractorLinkType.M3U8) {
                                this.headers = VIDEO_HEADERS
                            })
                            linksFound = true
                        }
                    }
                }
            } catch (e: Exception) {
                println("💥 [AniTube] Erro: ${e.message}")
            }
        }

        // -----------------------------------------------------------
        // 2. Fallbacks (FHD/Backup)
        // -----------------------------------------------------------
        document.selectFirst(PLAYER_FHD)?.let { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && !src.contains("bg.mp4")) {
                val m3u8Url = extractM3u8FromUrl(src) ?: src
                callback(newExtractorLink(name, "Player FHD", m3u8Url, ExtractorLinkType.M3U8) {
                    referer = "$mainUrl/"
                    quality = 1080
                })
                linksFound = true
            }
        }

        document.selectFirst(PLAYER_BACKUP)?.let { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && !src.contains("bg.mp4")) {
                callback(newExtractorLink(name, "Player Backup", src, ExtractorLinkType.VIDEO) {
                    referer = "$mainUrl/"
                    quality = 720
                })
                linksFound = true
            }
        }

        return linksFound
    }
}
