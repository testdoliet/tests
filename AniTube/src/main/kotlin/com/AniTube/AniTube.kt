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

        // Headers e Referers consistentes
        private const val COMMON_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private val API_REFERER = "https://api.anivideo.net/"
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
            
            // CORREÇÃO: Usar string para o charset
            val charset = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
            
            fun encodeBase(num: Int): String = if (num < radix) {
                charset[num].toString() // CORREÇÃO: acessar char por índice
            } else {
                encodeBase(num / radix) + charset[num % radix]
            }
            
            val dict = HashMap<String, String>()
            for (i in 0 until count) {
                val key = encodeBase(i)
                val value = keywords.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: key
                dict[key] = value
            }
            
            return payload.replace(Regex("\\b\\w+\\b")) { r -> 
                dict[r.value] ?: r.value 
            }
        } catch (e: Exception) { 
            e.printStackTrace()
            return null 
        }
    }

    private val genresMap = mapOf(
        "Ação" to "acao", "Aventura" to "aventura", "Comédia" to "comedia", 
        "Drama" to "drama", "Ecchi" to "ecchi", "Fantasia" to "fantasia", 
        "Romance" to "romance", "Seinen" to "seinen", "Shounen" to "shounen", 
        "Slice Of Life" to "slice%20of%20life", "Sobrenatural" to "sobrenatural", 
        "Terror" to "terror", "Isekai" to "isekai"
    )

    override val mainPage = mainPageOf(
        "$mainUrl" to "Últimos Episódios", 
        "$mainUrl" to "Animes Mais Vistos", 
        *genresMap.map { (genre, slug) -> "$mainUrl/?s=$slug" to genre }.toTypedArray()
    )

    private fun extractEpisodeNumber(title: String): Int? = 
        Regex("\\d+").find(title)?.value?.toIntOrNull()
    
    private fun extractM3u8FromUrl(url: String): String? = 
        if (url.contains("d=")) {
            try { 
                URLDecoder.decode(url.substringAfter("d=").substringBefore("&"), "UTF-8") 
            } catch (e: Exception) { 
                null 
            }
        } else url

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
        else request.data
        
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
        return app.get("$mainUrl$SEARCH_PATH${query.replace(" ", "+")}")
            .document.select("$ANIME_CARD, $EPISODE_CARD")
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
        println("🔗 [AniTube] LoadLinks iniciado: $actualUrl")

        val document = app.get(actualUrl).document
        var linksFound = false

        document.select("iframe[src*='bg.mp4']").firstOrNull()?.let { iframe ->
            val initialSrc = iframe.attr("src")
            println("🔄 [AniTube] Iframe encontrado: $initialSrc")

            try {
                // =====================================================
                // ETAPA 1: Primeira requisição para obter cookies
                // =====================================================
                val headers1 = mapOf(
                    "User-Agent" to COMMON_USER_AGENT,
                    "Referer" to actualUrl,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                    "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
                    "Accept-Encoding" to "gzip, deflate, br",
                    "Connection" to "keep-alive",
                    "Upgrade-Insecure-Requests" to "1"
                )

                // Primeira requisição com allowRedirects = true para seguir todos os redirecionamentos
                val response1 = app.get(initialSrc, headers = headers1, allowRedirects = true)
                val cookies = response1.cookies.toMutableMap()
                println("🍪 [AniTube] Cookies após primeira requisição: ${cookies.size}")

                // =====================================================
                // ETAPA 2: Analisar conteúdo para encontrar player
                // =====================================================
                val content1 = response1.text
                if (content1.isBlank()) {
                    println("❌ [AniTube] Conteúdo vazio na primeira requisição")
                    return@let
                }

                // Tentar encontrar a URL do player no conteúdo
                val playerUrlRegex = Regex("""src=["'](https?://[^"']+?/player\.php[^"']*)["']""")
                val playerUrlMatch = playerUrlRegex.find(content1)

                val playerUrl = if (playerUrlMatch != null) {
                    playerUrlMatch.groupValues[1]
                } else {
                    // Se não encontrar player.php, usar a URL final após redirecionamentos
                    response1.url
                }

                println("🎬 [AniTube] URL do player: $playerUrl")

                // =====================================================
                // ETAPA 3: Acessar o player com cookies e headers
                // =====================================================
                val headers2 = mapOf(
                    "User-Agent" to COMMON_USER_AGENT,
                    "Referer" to initialSrc, // Referer do iframe inicial
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                    "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
                    "Origin" to getBaseUrl(initialSrc), // CORREÇÃO: função personalizada
                    "Connection" to "keep-alive"
                )

                val response2 = app.get(playerUrl, headers = headers2, cookies = cookies)
                cookies.putAll(response2.cookies)
                val content2 = response2.text

                println("📄 [AniTube] Tamanho do conteúdo do player: ${content2.length}")

                // =====================================================
                // ETAPA 4: Decodificar e extrair links
                // =====================================================
                val decoded = decodePacked(content2) ?: content2
                
                // Headers para os links de vídeo (IMPORTANTE: manter consistência)
                val videoHeaders = mutableMapOf(
                    "User-Agent" to COMMON_USER_AGENT,
                    "Referer" to playerUrl, // Referer do player
                    "Accept" to "*/*",
                    "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
                    "Origin" to getBaseUrl(playerUrl), // CORREÇÃO: função personalizada
                    "Connection" to "keep-alive"
                )

                // Adicionar cookies se existirem
                if (cookies.isNotEmpty()) {
                    val cookieStr = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                    videoHeaders["Cookie"] = cookieStr
                    println("🍪 [AniTube] Cookies enviados com vídeo: $cookieStr")
                }

                // Procurar por links de vídeo
                val videoRegex = Regex("""https?://[^"'\s<>]+(?:videoplayback|\.m3u8|\.mp4)[^"'\s<>]*""")
                val matches = videoRegex.findAll(decoded).toList()

                println("🔍 [AniTube] ${matches.size} links de vídeo encontrados")

                matches.forEachIndexed { index, match ->
                    val videoUrl = match.value.trim()
                    println("📹 [AniTube] Link ${index + 1}: $videoUrl")
                    
                    val quality = when {
                        videoUrl.contains("itag=37") || videoUrl.contains("1080") -> 1080
                        videoUrl.contains("itag=22") || videoUrl.contains("720") -> 720
                        videoUrl.contains("itag=18") || videoUrl.contains("360") -> 360
                        else -> 720
                    }
                    
                    val isHls = videoUrl.contains(".m3u8")
                    val type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    val name = if (isHls) "AniTube HLS" else "AniTube MP4"
                    
                    callback(
                        newExtractorLink(this.name, name, videoUrl, type) {
                            this.headers = videoHeaders.toMap() // Converter para mapa imutável
                            this.quality = quality
                            this.referer = playerUrl // Definir referer explicitamente
                        }
                    )
                    
                    linksFound = true
                }

            } catch (e: Exception) {
                println("💥 [AniTube] Erro na extração: ${e.message}")
                e.printStackTrace()
            }
        }

        // Fallback para outros players se necessário
        if (!linksFound) {
            println("⚠️ [AniTube] Tentando fallback players...")
            
            document.selectFirst(PLAYER_FHD)?.let { 
                val src = it.attr("src")
                if(src.isNotBlank() && !src.contains("bg.mp4")) {
                    val m3u8Url = extractM3u8FromUrl(src) ?: src
                    println("🎯 [AniTube] Fallback FHD: $m3u8Url")
                    
                    callback(
                        newExtractorLink(name, "Player FHD", m3u8Url, ExtractorLinkType.M3U8) {
                            this.headers = mapOf(
                                "User-Agent" to COMMON_USER_AGENT,
                                "Referer" to actualUrl
                            )
                        }
                    )
                    linksFound = true
                }
            }
            
            if (!linksFound) {
                document.selectFirst(PLAYER_BACKUP)?.let { 
                    val src = it.attr("src")
                    if(src.isNotBlank() && !src.contains("bg.mp4")) {
                        println("🎯 [AniTube] Fallback Backup: $src")
                        
                        callback(
                            newExtractorLink(name, "Player Backup", src, ExtractorLinkType.VIDEO) {
                                this.headers = mapOf(
                                    "User-Agent" to COMMON_USER_AGENT,
                                    "Referer" to actualUrl
                                )
                            }
                        )
                        linksFound = true
                    }
                }
            }
        }

        println("${if (linksFound) "✅" else "❌"} [AniTube] LoadLinks finalizado: ${if (linksFound) "Links encontrados" else "Nenhum link encontrado"}")
        return linksFound
    }

    // Função auxiliar para obter a URL base
    private fun getBaseUrl(url: String): String {
        return try {
            val uri = java.net.URI(url)
            "${uri.scheme}://${uri.host}"
        } catch (e: Exception) {
            "https://www.anitube.news"
        }
    }
}
