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
        // HEADERS EXATOS DO SEU JSON
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
            // Headers adicionais ESSENCIAIS que faltam no seu JSON:
            "referer" to "https://www.blogger.com/",  // CRÍTICO para Google Video!
            "origin" to "https://www.anitube.news",    // Importante
            "connection" to "keep-alive"               // Importante
        )
        
        // Headers para navegação no site
        private val NAV_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to "https://www.anitube.news/"
        )

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
        private const val ANIME_METADATA = ".boxAnimeSobre .boxAnimeSobreLinha"
        private const val EPISODE_LIST = ".pagAniListaContainer > a"
        private const val PLAYER_IFRAME = "iframe[src*='bg.mp4']"
    }

    // ======================================================================
    // FUNÇÕES DE NAVEGAÇÃO (usando NAV_HEADERS)
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
    // LOADLINKS - COM HEADERS EXATOS DO SEU JSON
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

        // 2. Encontrar iframe do player
        document.select(PLAYER_IFRAME).firstOrNull()?.let { iframe ->
            val iframeSrc = iframe.attr("src")
            println("🔗 [AniTube] Iframe encontrado: $iframeSrc")
            
            try {
                // 3. Acessar iframe para extrair links
                println("📡 [AniTube] Acessando iframe...")
                val iframeResponse = app.get(iframeSrc, headers = NAV_HEADERS)
                
                if (iframeResponse.code == 200) {
                    val html = iframeResponse.text
                    linksFound = extractLinksFromHtml(html, callback)
                } else if (iframeResponse.code in 300..399) {
                    // Seguir redirect
                    val redirectUrl = iframeResponse.headers["location"] ?: iframeResponse.headers["Location"]
                    if (redirectUrl != null) {
                        println("🔄 [AniTube] Redirect para: $redirectUrl")
                        val finalResponse = app.get(redirectUrl, headers = NAV_HEADERS)
                        linksFound = extractLinksFromHtml(finalResponse.text, callback)
                    }
                }
            } catch (e: Exception) {
                println("💥 [AniTube] Erro: ${e.message}")
            }
        }

        if (!linksFound) {
            println("⚠️ [AniTube] Nenhum link encontrado")
        }
        
        return linksFound
    }

    private fun extractLinksFromHtml(html: String, callback: (ExtractorLink) -> Unit): Boolean {
        var found = false
        
        // Decodificar conteúdo (se necessário)
        val content = decodePacked(html) ?: html
        
        // Extrair links do Google Video
        val googleVideoRegex = Regex("""https?://[^"'\s]+?googlevideo\.com/videoplayback[^"'\s]*""")
        
        googleVideoRegex.findAll(content).forEach { match ->
            val videoUrl = match.value.trim()
            
            // Filtrar apenas links válidos
            if (videoUrl.contains("expire=") && videoUrl.contains("itag=")) {
                println("✅ [AniTube] Link do Google Video: $videoUrl")
                
                // Usar EXACT_VIDEO_HEADERS do seu JSON
                callback(
                    ExtractorLink(
                        name,
                        "AniTube Player",
                        videoUrl,
                        "$mainUrl/",
                        Qualities.Unknown.value,
                        false,
                        EXACT_VIDEO_HEADERS.toMutableMap().apply {
                            // Adicionar referer específico se necessário
                            putIfAbsent("referer", "https://www.blogger.com/")
                        }
                    )
                )
                found = true
            }
        }
        
        // Também procurar links M3U8
        val m3u8Regex = Regex("""https?://[^"'\s]+?\.m3u8[^"'\s]*""")
        m3u8Regex.findAll(content).forEach { match ->
            val m3u8Url = match.value.trim()
            println("✅ [AniTube] Link M3U8: $m3u8Url")
            
            callback(
                ExtractorLink(
                    name,
                    "AniTube Player",
                    m3u8Url,
                    "$mainUrl/",
                    Qualities.Unknown.value,
                    true, // isM3u8 = true
                    EXACT_VIDEO_HEADERS
                )
            )
            found = true
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
