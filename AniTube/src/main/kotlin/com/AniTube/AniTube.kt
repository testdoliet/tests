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
        private const val COMMON_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
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
        } catch (e: Exception) { 
            println("❌ [AniTube] Error decoding packed: ${e.message}")
            return null 
        }
    }

    private val genresMap = mapOf(
        "Ação" to "acao", 
        "Aventura" to "aventura", 
        "Comédia" to "comedia", 
        "Drama" to "drama", 
        "Ecchi" to "ecchi", 
        "Fantasia" to "fantasia", 
        "Romance" to "romance", 
        "Seinen" to "seinen", 
        "Shounen" to "shounen", 
        "Slice Of Life" to "slice%20of%20life", 
        "Sobrenatural" to "sobrenatural", 
        "Terror" to "terror", 
        "Isekai" to "isekai"
    )

    override val mainPage = mainPageOf(
        "$mainUrl" to "Últimos Episódios", 
        "$mainUrl" to "Animes Mais Vistos", 
        *genresMap.map { (genre, slug) -> "$mainUrl/?s=$slug" to genre }.toTypedArray()
    )

    private fun extractEpisodeNumber(title: String): Int? = Regex("\\d+").find(title)?.value?.toIntOrNull()
    
    private fun extractM3u8FromUrl(url: String): String? = 
        if (url.contains("d=")) 
            try { 
                URLDecoder.decode(url.substringAfter("d=").substringBefore("&"), "UTF-8") 
            } catch (e: Exception) { 
                null 
            } 
        else 
            url

    private fun Element.toEpisodeSearchResponse(): AnimeSearchResponse? {
        val href = selectFirst("a")?.attr("href") ?: return null
        val title = selectFirst(TITLE_SELECTOR)?.text() ?: return null
        val poster = selectFirst(POSTER_SELECTOR)?.attr("src")
        return newAnimeSearchResponse(title, fixUrl(href)) { 
            this.posterUrl = poster 
        }
    }
    
    private fun Element.toAnimeSearchResponse(): AnimeSearchResponse? {
        val href = selectFirst("a")?.attr("href") ?: return null
        val title = selectFirst(TITLE_SELECTOR)?.text() ?: return null
        val poster = selectFirst(POSTER_SELECTOR)?.attr("src")
        return newAnimeSearchResponse(title, fixUrl(href)) { 
            this.posterUrl = poster 
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1 && request.data.contains("/?s=")) 
            request.data.replace("/?s=", "/page/$page/?s=") 
        else 
            request.data
            
        val document = app.get(url).document
        val items = document.select("$ANIME_CARD, $EPISODE_CARD").mapNotNull { 
            if (it.selectFirst(EPISODE_NUMBER_SELECTOR) != null) 
                it.toEpisodeSearchResponse() 
            else 
                it.toAnimeSearchResponse() 
        }.distinctBy { it.url }
        
        return newHomePageResponse(request.name, items, true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl$SEARCH_PATH${query.replace(" ", "+")}").document
            .select("$ANIME_CARD, $EPISODE_CARD")
            .mapNotNull { 
                if (it.selectFirst(EPISODE_NUMBER_SELECTOR) != null) 
                    it.toEpisodeSearchResponse() 
                else 
                    it.toAnimeSearchResponse() 
            }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val actualUrl = data.split("|poster=")[0]
        println("\n🛑 [AniTube] LOAD LINKS: $actualUrl")

        // Criar uma sessão persistente para manter cookies
        val sessionClient = app.newSession()
        var linksFound = false

        try {
            // 1. Primeiro acesso à página do episódio
            val headers = mapOf(
                "User-Agent" to COMMON_USER_AGENT,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
                "Accept-Encoding" to "gzip, deflate, br",
                "Connection" to "keep-alive",
                "Upgrade-Insecure-Requests" to "1"
            )
            
            val document = sessionClient.get(actualUrl, headers = headers).document
            
            // 2. Tentar primeiro o iframe principal
            document.select("iframe[src*='bg.mp4']").firstOrNull()?.let { iframe ->
                val initialSrc = iframe.attr("src")
                println("🔗 [AniTube] Iframe src: $initialSrc")
                
                if (initialSrc.isNotBlank()) {
                    try {
                        // 3. Acessar o iframe com a mesma sessão
                        val iframeHeaders = mapOf(
                            "User-Agent" to COMMON_USER_AGENT,
                            "Referer" to actualUrl,
                            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                            "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
                            "Accept-Encoding" to "gzip, deflate, br",
                            "Connection" to "keep-alive",
                            "Upgrade-Insecure-Requests" to "1"
                        )
                        
                        val response = sessionClient.get(initialSrc, headers = iframeHeaders, allowRedirects = true)
                        println("📄 [AniTube] Response status: ${response.code}")
                        println("📍 [AniTube] Final URL: ${response.url}")
                        
                        // 4. Obter conteúdo e decodificar
                        val content = response.text
                        val decoded = decodePacked(content) ?: content
                        
                        // 5. Log do conteúdo para debug (apenas se necessário)
                        if (decoded.length < 5000) {
                            println("📝 [AniTube] Decoded content preview: ${decoded.take(500)}")
                        }
                        
                        // 6. Preparar headers para o player
                        val playerHeaders = mapOf(
                            "User-Agent" to COMMON_USER_AGENT,
                            "Referer" to response.url.toString(),
                            "Origin" to URLUtil.getBaseUrl(response.url.toString()),
                            "Accept" to "*/*",
                            "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
                            "Accept-Encoding" to "gzip, deflate, br",
                            "Connection" to "keep-alive",
                            "Sec-Fetch-Dest" to "empty",
                            "Sec-Fetch-Mode" to "cors",
                            "Sec-Fetch-Site" to "cross-site"
                        )
                        
                        // 7. Buscar links de vídeo
                        val videoPatterns = listOf(
                            Regex("""https?://[^"\s]+\.mp4[^"\s]*"""),
                            Regex("""https?://[^"\s]+\.m3u8[^"\s]*"""),
                            Regex("""https?://[^"\s]+videoplayback[^"\s]*"""),
                            Regex("""["'](https?://[^"']+\.mp4(?:\?[^"']*)?)["']"""),
                            Regex("""["'](https?://[^"']+\.m3u8(?:\?[^"']*)?)["']"""),
                            Regex("""file\s*:\s*["']([^"']+)["']"""),
                            Regex("""src\s*:\s*["']([^"']+)["']""")
                        )
                        
                        for (pattern in videoPatterns) {
                            pattern.findAll(decoded).forEach { match ->
                                val link = match.groups[1]?.value?.trim() ?: match.value.trim()
                                if (link.isNotBlank() && 
                                    (link.contains(".mp4") || 
                                     link.contains(".m3u8") || 
                                     link.contains("videoplayback") ||
                                     link.contains("googlevideo") ||
                                     link.contains("manifest"))) {
                                    
                                    println("🎬 [AniTube] Found video link: $link")
                                    
                                    // Determinar qualidade
                                    val quality = when {
                                        link.contains("1080") || link.contains("itag=37") || link.contains("hd1080") -> Qualities.FullHDP.value
                                        link.contains("720") || link.contains("itag=22") || link.contains("hd720") -> Qualities.P720.value
                                        link.contains("480") || link.contains("itag=18") -> Qualities.P480.value
                                        link.contains("360") || link.contains("itag=17") -> Qualities.P360.value
                                        link.contains("240") || link.contains("itag=5") -> Qualities.P240.value
                                        else -> Qualities.Unknown.value
                                    }
                                    
                                    // Determinar tipo
                                    val type = when {
                                        link.contains(".m3u8") -> ExtractorLinkType.M3U8
                                        link.contains(".mpd") -> ExtractorLinkType.DASH
                                        else -> ExtractorLinkType.VIDEO
                                    }
                                    
                                    val name = when {
                                        link.contains("googlevideo") -> "Google Video"
                                        link.contains(".m3u8") -> "HLS Stream"
                                        else -> "AniTube Video"
                                    }
                                    
                                    callback(newExtractorLink(this.name, name, link, type) {
                                        this.headers = playerHeaders
                                        this.quality = quality
                                        this.isM3u8DirectUrl = type == ExtractorLinkType.M3U8
                                    })
                                    
                                    linksFound = true
                                }
                            }
                        }
                        
                        // 8. Se ainda não encontrou, buscar em scripts específicos
                        if (!linksFound) {
                            val scriptPattern = Regex("""(?:var|let|const)\s+\w+\s*=\s*["']([^"']+\.(?:mp4|m3u8|mpeg)[^"']*)["']""")
                            scriptPattern.findAll(decoded).forEach { match ->
                                val link = match.groupValues[1].trim()
                                if (link.isNotBlank()) {
                                    println("📜 [AniTube] Found in script variable: $link")
                                    
                                    callback(newExtractorLink(this.name, "AniTube Script", link, 
                                        if (link.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    ) {
                                        this.headers = playerHeaders
                                        this.quality = Qualities.Unknown.value
                                    })
                                    
                                    linksFound = true
                                }
                            }
                        }
                        
                    } catch (e: Exception) {
                        println("❌ [AniTube] Error processing iframe: ${e.message}")
                        e.printStackTrace()
                    }
                }
            }
            
            // 9. Fallback para players alternativos
            if (!linksFound) {
                println("🔄 [AniTube] Trying fallback players...")
                
                // Player FHD
                document.selectFirst(PLAYER_FHD)?.let { 
                    val src = it.attr("src")
                    if (src.isNotBlank() && !src.contains("bg.mp4")) {
                        val link = extractM3u8FromUrl(src) ?: src
                        println("🔄 [AniTube] Fallback FHD: $link")
                        
                        callback(newExtractorLink(name, "Player FHD", link, ExtractorLinkType.M3U8) {
                            this.headers = mapOf(
                                "User-Agent" to COMMON_USER_AGENT,
                                "Referer" to actualUrl
                            )
                            this.quality = Qualities.P1080.value
                        })
                        
                        linksFound = true
                    }
                }
                
                // Player Backup
                if (!linksFound) {
                    document.selectFirst(PLAYER_BACKUP)?.let { 
                        val src = it.attr("src")
                        if (src.isNotBlank() && !src.contains("bg.mp4")) {
                            val link = extractM3u8FromUrl(src) ?: src
                            println("🔄 [AniTube] Fallback Backup: $link")
                            
                            callback(newExtractorLink(name, "Player Backup", link, ExtractorLinkType.M3U8) {
                                this.headers = mapOf(
                                    "User-Agent" to COMMON_USER_AGENT,
                                    "Referer" to actualUrl
                                )
                                this.quality = Qualities.P720.value
                            })
                            
                            linksFound = true
                        }
                    }
                }
            }
            
        } catch (e: Exception) {
            println("❌ [AniTube] General error in loadLinks: ${e.message}")
            e.printStackTrace()
        } finally {
            // Fechar a sessão
            try {
                sessionClient.close()
            } catch (e: Exception) {
                // Ignorar erro ao fechar
            }
        }
        
        println("✅ [AniTube] Links found: $linksFound")
        return linksFound
    }
}
