package com.AniTube

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class AniTube : MainAPI() {
    override var mainUrl = "https://www.anitube.news"
    override var name = "AniTube"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Anime)
    override val usesWebView = false

    companion object {
        // HEADERS EXATOS DO SEU JSON (exatamente como estão no seu JSON)
        private val EXACT_VIDEO_HEADERS = mapOf(
            "accept" to "*/*",
            "accept-language" to "pt-br",
            "priority" to "i",
            "range" to "",
            "sec-ch-ua" to "\"Chromium\";v=\"127\", \"Not)A;Brand\";v=\"99\", \"Microsoft Edge Simulate\";v=\"127\", \"Lemur\";v=\"127\"",
            "sec-ch-ua-mobile" to "?1",
            "sec-ch-ua-platform" to "\"Android\"",
            "sec-fetch-dest" to "video",
            "sec-fetch-mode" to "no-cors",
            "user-agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
            "x-client-data" to "COD2ygE=",
            // Headers adicionais que são necessários
            "referer" to "https://www.blogger.com/",
            "origin" to "https://www.anitube.news",
            "connection" to "keep-alive"
        )
        
        // Headers para navegação no site (mais simples)
        private val NAV_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to "$mainUrl/"
        )

        private const val SEARCH_PATH = "/?s="
        private const val ANIME_CARD = ".aniItem"
        private const val EPISODE_CARD = ".epiItem"
        private const val TITLE_SELECTOR = ".aniItemNome, .epiItemNome"
        private const val POSTER_SELECTOR = ".aniItemImg img, .epiItemImg img"
        private const val EPISODE_NUMBER_SELECTOR = ".epiItemInfos .epiItemNome"
        private const val ANIME_TITLE = "h1"
        private const val ANIME_POSTER = "#capaAnime img"
        private const val ANIME_SYNOPSIS = "#sinopse2"
        private const val EPISODE_LIST = ".pagAniListaContainer > a"
        private const val PLAYER_FHD = "#blog2 iframe"
        private const val PLAYER_BACKUP = "#blog1 iframe"
        private const val PLAYER_IFRAME = "iframe[src*='bg.mp4']"
    }

    // ======================================================================
    // FUNÇÕES DE NAVEGAÇÃO
    // ======================================================================
    override val mainPage = mainPageOf(
        "$mainUrl" to "Últimos Episódios",
        "$mainUrl" to "Animes Mais Vistos"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data, headers = NAV_HEADERS).document
        val items = document.select("$ANIME_CARD, $EPISODE_CARD").mapNotNull { 
            it.toSearchResponse()
        }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl$SEARCH_PATH${query.replace(" ", "+")}", headers = NAV_HEADERS).document
            .select("$ANIME_CARD, $EPISODE_CARD")
            .mapNotNull { it.toSearchResponse() }
    }

    private fun Element.toSearchResponse(): AnimeSearchResponse? {
        val href = selectFirst("a")?.attr("href") ?: return null
        val title = selectFirst(TITLE_SELECTOR)?.text() ?: return null
        val poster = selectFirst(POSTER_SELECTOR)?.attr("src")
        return newAnimeSearchResponse(title, fixUrl(href)) { this.posterUrl = poster }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = NAV_HEADERS).document
        val title = doc.selectFirst(ANIME_TITLE)?.text() ?: "Anime"
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = doc.selectFirst(ANIME_POSTER)?.attr("src")
            this.plot = doc.selectFirst(ANIME_SYNOPSIS)?.text()
            val episodes = doc.select(EPISODE_LIST).mapNotNull { 
                val epNum = extractEpisodeNumber(it.text()) ?: 1
                newEpisode(it.attr("href")) { 
                    this.episode = epNum
                    this.name = "Episódio $epNum" 
                }
            }
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    private fun extractEpisodeNumber(title: String): Int? = 
        Regex("\\d+").find(title)?.value?.toIntOrNull()

    // ======================================================================
    // LOADLINKS - USANDO newExtractorLink CORRETAMENTE
    // ======================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val actualUrl = data.split("|poster=")[0]
        println("\n🎯 [AniTube] Carregando links de: $actualUrl")

        // 1. Carregar página do episódio
        val document = app.get(actualUrl, headers = NAV_HEADERS).document
        var linksFound = false

        // 2. Primeiro tentar os players principais
        document.selectFirst(PLAYER_FHD)?.let { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                println("🔗 [AniTube] Player FHD encontrado: $src")
                
                callback(newExtractorLink(name, "Player FHD", src, ExtractorLinkType.M3U8) {
                    // USANDO OS HEADERS EXATOS AQUI
                    this.headers = EXACT_VIDEO_HEADERS
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                })
                linksFound = true
            }
        }

        // 3. Tentar player backup
        if (!linksFound) {
            document.selectFirst(PLAYER_BACKUP)?.let { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank()) {
                    println("🔗 [AniTube] Player Backup encontrado: $src")
                    
                    callback(newExtractorLink(name, "Player Backup", src, ExtractorLinkType.M3U8) {
                        this.headers = EXACT_VIDEO_HEADERS
                        this.referer = "$mainUrl/"
                        this.quality = 720
                    })
                    linksFound = true
                }
            }
        }

        // 4. Tentar iframe com bg.mp4
        if (!linksFound) {
            document.select(PLAYER_IFRAME).firstOrNull()?.let { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank()) {
                    println("🔗 [AniTube] Iframe bg.mp4 encontrado: $src")
                    
                    // Acessar o iframe para extrair links
                    try {
                        println("📡 [AniTube] Acessando iframe...")
                        val iframeResponse = app.get(src, headers = NAV_HEADERS)
                        
                        if (iframeResponse.code == 200) {
                            val html = iframeResponse.text
                            val decoded = decodePacked(html) ?: html
                            
                            // Extrair links do Google Video
                            extractGoogleVideoLinks(decoded, callback)?.let { found ->
                                linksFound = found
                            }
                        }
                    } catch (e: Exception) {
                        println("💥 [AniTube] Erro ao acessar iframe: ${e.message}")
                    }
                }
            }
        }

        // 5. Último recurso: verificar todos os iframes
        if (!linksFound) {
            document.select("iframe").forEachIndexed { index, iframe ->
                val src = iframe.attr("src")
                if (src.contains("m3u8", true) || src.contains("googlevideo", true)) {
                    val alreadyAdded = document.selectFirst(PLAYER_FHD)?.attr("src") == src || 
                                      document.selectFirst(PLAYER_BACKUP)?.attr("src") == src

                    if (!alreadyAdded) {
                        println("🔗 [AniTube] Iframe $index encontrado: $src")
                        
                        callback(newExtractorLink(name, "Player Auto", src, ExtractorLinkType.M3U8) {
                            this.headers = EXACT_VIDEO_HEADERS
                            this.referer = "$mainUrl/"
                            this.quality = 720
                        })
                        linksFound = true
                    }
                }
            }
        }

        if (!linksFound) {
            println("⚠️ [AniTube] Nenhum link encontrado")
        }
        
        return linksFound
    }

    private fun extractGoogleVideoLinks(html: String, callback: (ExtractorLink) -> Unit): Boolean {
        var found = false
        
        // Regex para links do Google Video
        val googleVideoRegex = Regex("""https?://[^"'\s]+?googlevideo\.com/videoplayback[^"'\s]*""")
        
        googleVideoRegex.findAll(html).forEach { match ->
            val videoUrl = match.value.trim()
            
            // Filtrar links válidos
            if (videoUrl.contains("expire=") && videoUrl.contains("itag=")) {
                println("✅ [AniTube] Link do Google Video encontrado: $videoUrl")
                
                // Detectar qualidade baseada no itag
                val quality = when {
                    videoUrl.contains("itag=37") || videoUrl.contains("itag=137") -> 1080
                    videoUrl.contains("itag=22") || videoUrl.contains("itag=136") -> 720
                    videoUrl.contains("itag=59") || videoUrl.contains("itag=135") -> 480
                    videoUrl.contains("itag=18") -> 360
                    else -> 360
                }
                
                callback(newExtractorLink(name, "AniTube Player", videoUrl, ExtractorLinkType.VIDEO) {
                    // HEADERS EXATOS DO JSON!
                    this.headers = EXACT_VIDEO_HEADERS
                    this.referer = "https://www.blogger.com/"
                    this.quality = quality
                })
                found = true
            }
        }
        
        return found
    }

    private fun decodePacked(packed: String): String? {
        try {
            val regex = Regex("""eval\s*\(\s*function\s*\(p,a,c,k,e,d\).*?\}('.*?',(\d+),(\d+),'(.*?)'\.split\('\|'\))""")
            val match = regex.find(packed) ?: return null
            
            val (payload, aStr, cStr, kStr) = match.destructured
            val radix = aStr.toInt()
            val count = cStr.toInt()
            val keywords = kStr.split("|")

            fun encodeBase(num: Int): String {
                val dict = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
                return if (num < radix) dict[num].toString()
                else encodeBase(num / radix) + dict[num % radix]
            }

            val dict = HashMap<String, String>()
            for (i in 0 until count) {
                dict[encodeBase(i)] = keywords.getOrNull(i) ?: encodeBase(i)
            }

            return payload.replace(Regex("""\b\w+\b""")) { dict[it.value] ?: it.value }
        } catch (e: Exception) {
            return null
        }
    }
}
