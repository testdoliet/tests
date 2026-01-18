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
        private const val ANIME_METADATA = ".boxAnimeSobre .boxAnimeSobreLinha"
        private const val EPISODE_LIST = ".pagAniListaContainer > a"
        private const val PLAYER_FHD = "#blog2 iframe"
        private const val PLAYER_BACKUP = "#blog1 iframe"

        // ✅ USER-AGENT CRÍTICO - USADO APENAS NO LOADLINKS
        private const val ANDROID_USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"
        
        // Headers para EXTRACTION (iframe/player) - COM User-Agent Android
        private val EXTRACTION_HEADERS = mapOf(
            "User-Agent" to ANDROID_USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to "https://www.anitube.news/"
        )

        // ✅ Headers CRÍTICOS para STREAMING (links de vídeo) - MÍNIMOS mas ESSENCIAIS
        private val STREAM_HEADERS = mapOf(
            "User-Agent" to ANDROID_USER_AGENT,  // ✅ ESSENCIAL - SEM ISSO DÁ 403
            "Accept" to "*/*",
            "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
            "Accept-Encoding" to "identity",
            "Referer" to "https://www.blogger.com/"  // ✅ Referer correto para Google Video
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

            fun encodeBase(num: Int): String {
                val dict = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
                return if (num < radix) {
                    dict[num].toString()
                } else {
                    encodeBase(num / radix) + dict[num % radix]
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
    // MÉTODOS PRINCIPAIS (SEM User-Agent Android aqui)
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
    // LOADLINKS - APENAS AQUI USAMOS USER-AGENT ANDROID
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
            println("🔎 [AniTube] Iframe encontrado: $initialSrc")
            
            try {
                // ✅ ETAPA 1: Extrair conteúdo do iframe (COM User-Agent Android)
                println("📥 [AniTube] Acessando iframe com User-Agent Android...")
                val iframeResponse = app.get(initialSrc, headers = EXTRACTION_HEADERS)
                
                if (iframeResponse.code in 300..399) {
                    // Lidar com redirect
                    val redirectUrl = iframeResponse.headers["location"] ?: iframeResponse.headers["Location"]
                    if (redirectUrl != null) {
                        println("🔄 [AniTube] Redirect para: $redirectUrl")
                        val finalResponse = app.get(redirectUrl, headers = EXTRACTION_HEADERS)
                        linksFound = processResponse(finalResponse.text, callback)
                    }
                } else if (iframeResponse.code == 200) {
                    linksFound = processResponse(iframeResponse.text, callback)
                }
                
            } catch (e: Exception) {
                println("💥 [AniTube] Erro na extração: ${e.message}")
            }
        }

        return linksFound
    }

    // ======================================================================
    // FUNÇÕES AUXILIARES (usadas apenas no loadLinks)
    // ======================================================================
    private suspend fun processResponse(html: String, callback: (ExtractorLink) -> Unit): Boolean {
        if (html.isBlank()) return false
        
        var linksFound = false
        val decoded = decodePacked(html)
        if (decoded != null) {
            println("✅ [AniTube] Conteúdo decodificado com sucesso")
            
            // Extrair todos os links de vídeo
            val videoLinks = extractVideoLinks(decoded)
            
            for (videoUrl in videoLinks) {
                println("🎬 [AniTube] Link encontrado: $videoUrl")
                
                // ✅ CORRIGIDO: Chamada suspend dentro de função suspend
                callback(newExtractorLink(
                    name, 
                    "AniTube Player", 
                    videoUrl, 
                    ExtractorLinkType.VIDEO
                ) {
                    // ✅ AQUI ESTÁ O SEGREDO: Headers com User-Agent Android
                    this.headers = STREAM_HEADERS
                    this.quality = extractQuality(videoUrl)
                })
                linksFound = true
            }
        } else {
            println("⚠️ [AniTube] Não foi possível decodificar o conteúdo")
        }
        
        return linksFound
    }

    private fun extractVideoLinks(html: String): List<String> {
        val links = mutableListOf<String>()
        
        // 1. Extrair links do Google Video (prioridade)
        val googleRegex = Regex("""https?://[^"'\s]+?googlevideo\.com/videoplayback[^"'\s]*""")
        googleRegex.findAll(html).forEach { match ->
            val link = match.value
            // Filtrar apenas links válidos (com parâmetros)
            if (link.contains("expire=") && link.contains("itag=")) {
                links.add(link)
            }
        }
        
        // 2. Extrair M3U8
        val m3u8Regex = Regex("""https?://[^"'\s]+?\.m3u8[^"'\s]*""")
        m3u8Regex.findAll(html).forEach { match ->
            links.add(match.value)
        }
        
        return links.distinct()
    }

    private fun extractQuality(url: String): Int {
        return when {
            url.contains("itag=37") || url.contains("itag=137") -> 1080
            url.contains("itag=22") || url.contains("itag=136") -> 720
            url.contains("itag=59") || url.contains("itag=135") -> 480
            url.contains("itag=18") -> 360
            url.contains("itag=43") -> 360
            else -> 360
        }
    }
}
