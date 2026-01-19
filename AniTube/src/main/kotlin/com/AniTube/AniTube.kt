package com.AniTube

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.util.Base64

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

        // Headers para Google Video (os que FUNCIONARAM no yt-dlp)
        private val GOOGLE_VIDEO_HEADERS = mapOf(
            "accept" to "*/*",
            "accept-language" to "pt-BR",
            "priority" to "i",
            "range" to "bytes=0-",
            "referer" to "https://api.anivideo.net/",  // ESSENCIAL!
            "sec-ch-ua" to "\"Chromium\";v=\"127\", \"Not)A;Brand\";v=\"99\", \"Microsoft Edge Simulate\";v=\"127\", \"Lemur\";v=\"127\"",
            "sec-ch-ua-mobile" to "?1",
            "sec-ch-ua-platform" to "\"Android\"",
            "sec-fetch-dest" to "video",
            "sec-fetch-mode" to "no-cors",
            "sec-fetch-site" to "cross-site",
            "user-agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
            "x-client-data" to "COD2ygE="
        )

        // Headers para navegação no site
        private const val USER_AGENT_PC = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        
        private val EXTRACTION_HEADERS = mapOf(
            "User-Agent" to USER_AGENT_PC,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to "https://www.anitube.news/"
        )

        // Headers SEM cache
        private val NO_CACHE_HEADERS = mapOf(
            "Cache-Control" to "no-cache, no-store, must-revalidate",
            "Pragma" to "no-cache",
            "Expires" to "0"
        )
    }

    // ======================================================================
    // 1. DECODIFICADOR CORRIGIDO
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
            println("❌ [AniTube] Erro no decodePacked: ${e.message}")
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
    // 4. EXTRAÇÃO DE LINKS - VERSÃO CORRIGIDA COMPLETA
    // ======================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val actualUrl = data.split("|poster=")[0]
        println("\n🔄 [AniTube] INICIANDO EXTRAÇÃO FRESCA para: $actualUrl")
        
        var linksFound = false
        val currentTime = System.currentTimeMillis() / 1000 // timestamp atual em segundos
        
        try {
            // ============================================================
            // PASSO 1: Acessar página do episódio SEM CACHE
            // ============================================================
            println("📄 [AniTube] Acessando página do episódio...")
            val allHeaders = EXTRACTION_HEADERS + NO_CACHE_HEADERS
            val document = app.get(actualUrl, headers = allHeaders).document
            
            // ============================================================
            // PASSO 2: Encontrar iframe do player
            // ============================================================
            val iframe = document.select("iframe[src*='bg.mp4']").firstOrNull()
            if (iframe == null) {
                println("❌ [AniTube] Iframe bg.mp4 não encontrado")
                // Fallback para outros players
                return tryFallbackPlayers(document, callback)
            }
            
            val iframeSrc = iframe.attr("src")
            println("🔗 [AniTube] Iframe encontrado: $iframeSrc")
            
            // ============================================================
            // PASSO 3: Acessar iframe e seguir TODOS os redirects
            // ============================================================
            println("🔄 [AniTube] Acessando iframe (seguindo redirects)...")
            val iframeResponse = app.get(
                iframeSrc,
                headers = mapOf(
                    "Referer" to actualUrl,
                    "User-Agent" to USER_AGENT_PC,
                    "Accept" to "*/*"
                ) + NO_CACHE_HEADERS,
                allowRedirects = true,  // 🔥 CRÍTICO: Seguir todos redirects!
                timeout = 30
            )
            
            println("📊 [AniTube] Response code: ${iframeResponse.code}")
            println("📊 [AniTube] Final URL: ${iframeResponse.url}")
            
            val iframeContent = iframeResponse.text
            if (iframeContent.isBlank()) {
                println("❌ [AniTube] Conteúdo do iframe vazio")
                return tryFallbackPlayers(document, callback)
            }
            
            // ============================================================
            // PASSO 4: Decodificar código packed
            // ============================================================
            println("🔓 [AniTube] Decodificando código packed...")
            val decoded = decodePacked(iframeContent)
            if (decoded == null) {
                println("❌ [AniTube] Não conseguiu decodificar packed code")
                return tryFallbackPlayers(document, callback)
            }
            
            // ============================================================
            // PASSO 5: Extrair links e filtrar os mais FRESCOS
            // ============================================================
            println("🔍 [AniTube] Procurando links videoplayback...")
            val links = Regex("https?://[^\\s'\"]+videoplayback[^\\s'\"]*").findAll(decoded).toList()
            
            if (links.isEmpty()) {
                println("❌ [AniTube] Nenhum link videoplayback encontrado")
                return tryFallbackPlayers(document, callback)
            }
            
            println("✅ [AniTube] Encontrados ${links.size} links")
            
            // Filtrar links válidos e FRESCOS
            val validLinks = mutableListOf<Pair<String, Long>>() // (link, expireTimestamp)
            
            links.forEach { match ->
                val link = match.value
                val expireMatch = Regex("expire=(\\d+)").find(link)
                val expireTime = expireMatch?.groupValues?.get(1)?.toLongOrNull()
                
                if (expireTime != null) {
                    // Verificar se o link ainda é válido (tem pelo menos 5 minutos de vida)
                    val timeRemaining = expireTime - currentTime
                    val isValid = timeRemaining > 300 // 5 minutos
                    
                    println("🔗 [AniTube] Link analisado:")
                    println("   Expire: $expireTime (atual: $currentTime)")
                    println("   Restante: ${timeRemaining}s (${timeRemaining/60}min)")
                    println("   Válido: $isValid")
                    
                    if (isValid) {
                        validLinks.add(link to expireTime)
                    }
                } else {
                    println("⚠️ [AniTube] Link sem timestamp expire=")
                }
            }
            
            // Ordenar por timestamp (mais fresco primeiro)
            validLinks.sortByDescending { it.second }
            // ============================================================
// PASSO 6: Usar os links válidos
// ============================================================
if (validLinks.isEmpty()) {
    println("❌ [AniTube] Nenhum link FRESCO encontrado (todos expirados)")
    return tryFallbackPlayers(document, callback)
}

println("🎯 [AniTube] Usando ${validLinks.size} link(s) fresco(s)")

validLinks.forEach { (link, expireTime) ->
    println("🎬 [AniTube] Link fresco selecionado (expire: $expireTime)")
    println("   URL: ${link.take(100)}...")
    
    // Verificar parâmetros importantes
    val hasIpbypass = link.contains("ipbypass=yes")
    val hasRedirectCounter = link.contains("redirect_counter=")
    
    if (!hasIpbypass) {
        println("⚠️ [AniTube] Link não tem ipbypass=yes - pode precisar de redirect")
    }
    
    // Determinar qualidade
    val quality = when {
        link.contains("itag=37") -> 1080
        link.contains("itag=22") -> 720
        link.contains("itag=18") -> 360
        else -> 360
    }
    
    // 🔥 CRIAR EXTRACTOR LINK COM HEADERS CORRETOS
    callback(newExtractorLink(name, "AniTube Google Video", link, ExtractorLinkType.VIDEO) {
        this.headers = GOOGLE_VIDEO_HEADERS
        this.quality = quality
        // 🔥 CORREÇÃO: String vazia em vez de null
        this.referer = ""
    })
    
    linksFound = true
}
            
                
    
    // ======================================================================
    // 5. FALLBACK PARA OUTROS PLAYERS
    // ======================================================================
    private suspend fun tryFallbackPlayers(
        document: org.jsoup.nodes.Document,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔄 [AniTube] Tentando fallback players...")
        var found = false
        
        // Player FHD
        document.selectFirst(PLAYER_FHD)?.let { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && !src.contains("bg.mp4")) {
                val m3u8Url = extractM3u8FromUrl(src) ?: src
                println("🎬 [AniTube] Fallback FHD: $m3u8Url")
                
                callback(newExtractorLink(name, "Player FHD", m3u8Url, ExtractorLinkType.M3U8) {
                    referer = "$mainUrl/"
                    quality = 1080
                })
                found = true
            }
        }
        
        // Player Backup
        if (!found) {
            document.selectFirst(PLAYER_BACKUP)?.let { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank() && !src.contains("bg.mp4")) {
                    println("🎬 [AniTube] Fallback Backup: $src")
                    
                    callback(newExtractorLink(name, "Player Backup", src, ExtractorLinkType.VIDEO) {
                        referer = "$mainUrl/"
                        quality = 720
                    })
                    found = true
                }
            }
        }
        
        return found
    }
}
