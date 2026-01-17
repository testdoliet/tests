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
    }

    // ======================================================================
    // 1. DECODIFICADOR PACKER (DEAN EDWARDS)
    // ======================================================================
    private fun decodePacked(packed: String): String? {
        try {
            // Procura pelo padrão: eval(function(p,a,c,k,e,d)...
            val regex = "eval\\s*\\(\\s*function\\s*\\(p,a,c,k,e,d\\).*?\\}\\('(.*?)',(\\d+),(\\d+),'(.*?)'\\.split\\('\\|'\\)".toRegex()
            val match = regex.find(packed) ?: return null
            
            val (p, aStr, cStr, kStr) = match.destructured
            val payload = p
            val radix = aStr.toInt()
            val count = cStr.toInt()
            val keywords = kStr.split("|")

            fun encodeBase(num: Int): String {
                val charset = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
                return if (num < radix) charset[num].toString() else encodeBase(num / radix) + charset[num % radix]
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
    // 2. CONFIGURAÇÕES E MAPAS (COMPLETO)
    // ======================================================================
    private val genresMap = mapOf(
        "Ação" to "acao", "Artes Marciais" to "artes%20marciais", "Aventura" to "aventura",
        "Comédia" to "comedia", "Comédia Romântica" to "comedia%20romantica", "Drama" to "drama",
        "Ecchi" to "ecchi", "Esporte" to "esporte", "Fantasia" to "fantasia",
        "Ficção Científica" to "ficcao%20cientifica", "Jogos" to "jogos", "Magia" to "magia",
        "Mecha" to "mecha", "Mistério" to "misterio", "Musical" to "musical",
        "Romance" to "romance", "Seinen" to "seinen", "Shoujo-ai" to "shoujo%20ai",
        "Shounen" to "shounen", "Slice Of Life" to "slice%20of%20life", "Sobrenatural" to "sobrenatural",
        "Superpoder" to "superpoder", "Terror" to "terror", "Vida Escolar" to "vida%20escolar",
        "Shoujo" to "shoujo", "Shounen-ai" to "shounen%20ai", "Yaoi" to "yaoi",
        "Yuri" to "yuri", "Harem" to "harem", "Isekai" to "isekai", "Militar" to "militar",
        "Policial" to "policial", "Psicológico" to "psicologico", "Samurai" to "samurai",
        "Vampiros" to "vampiros", "Zumbi" to "zumbi", "Histórico" to "historico",
        "Mágica" to "magica", "Cyberpunk" to "cyberpunk", "Espaço" to "espaco",
        "Demônios" to "demônios", "Vida Cotidiana" to "vida%20cotidiana"
    )

    override val mainPage = mainPageOf(
        "$mainUrl" to "Últimos Episódios",
        "$mainUrl" to "Animes Mais Vistos",
        "$mainUrl" to "Animes Recentes",
        *genresMap.map { (genre, slug) -> "$mainUrl/?s=$slug" to genre }.toTypedArray()
    )

    // ======================================================================
    // 3. HELPERS E EXTENSIONS (COMPLETO)
    // ======================================================================
    private fun cleanTitle(dirtyTitle: String): String {
        return dirtyTitle
            .replace("(?i)\\s*–\\s*todos os epis[oó]dios".toRegex(), "")
            .replace("(?i)\\s*\\(dublado\\)".toRegex(), "")
            .replace("(?i)\\s*\\(legendado\\)".toRegex(), "")
            .replace("(?i)\\s*dublado\\s*$".toRegex(), "")
            .replace("(?i)\\s*legendado\\s*$".toRegex(), "")
            .replace("(?i)\\s*-\\s*epis[oó]dio\\s*\\d+".toRegex(), "")
            .replace("(?i)\\s*–\\s*Epis[oó]dio\\s*\\d+".toRegex(), "")
            .replace("(?i)\\s*epis[oó]dio\\s*\\d+".toRegex(), "")
            .replace("(?i)\\s*Ep\\.\\s*\\d+".toRegex(), "")
            .replace("\\s+".toRegex(), " ")
            .trim()
            .ifBlank { dirtyTitle }
    }

    private fun extractEpisodeNumber(title: String): Int? {
        return listOf(
            "Epis[oó]dio\\s*(\\d+)".toRegex(RegexOption.IGNORE_CASE),
            "Ep\\.?\\s*(\\d+)".toRegex(RegexOption.IGNORE_CASE),
            "E(\\d+)".toRegex(RegexOption.IGNORE_CASE),
            "\\b(\\d{3,})\\b".toRegex(),
            "\\b(\\d{1,2})\\b".toRegex()
        ).firstNotNullOfOrNull { it.find(title)?.groupValues?.get(1)?.toIntOrNull() }
    }

    private fun extractAnimeTitleFromEpisode(episodeTitle: String): String {
        var clean = episodeTitle
            .replace("(?i)Epis[oó]dio\\s*\\d+".toRegex(), "")
            .replace("(?i)Ep\\.?\\s*\\d+".toRegex(), "")
            .replace("(?i)E\\d+".toRegex(), "")
            .replace("–", "")
            .replace("-", "")
            .replace("(?i)\\s*\\(dublado\\)".toRegex(), "")
            .replace("(?i)\\s*\\(legendado\\)".toRegex(), "")
            .replace("\\s+".toRegex(), " ")
            .trim()

        clean = clean.replace("\\s*\\d+\\s*$".toRegex(), "").trim()
        return clean.ifBlank { "Anime" }
    }

    private fun isDubbed(element: Element): Boolean {
        return element.selectFirst(AUDIO_BADGE_SELECTOR)
            ?.text()
            ?.contains("Dublado", true) ?: false
    }

    private fun extractM3u8FromUrl(url: String): String? {
        return if (url.contains("d=")) {
            try {
                URLDecoder.decode(url.substringAfter("d=").substringBefore("&"), "UTF-8")
            } catch (e: Exception) { null }
        } else { url }
    }

    private fun Element.toEpisodeSearchResponse(): AnimeSearchResponse? {
        val href = selectFirst("a")?.attr("href") ?: return null
        if (!href.contains("/video/")) return null

        val episodeTitle = selectFirst(EPISODE_NUMBER_SELECTOR)?.text()?.trim() ?: return null
        val episodeNumber = extractEpisodeNumber(episodeTitle) ?: 1
        val animeTitle = extractAnimeTitleFromEpisode(episodeTitle)
        val posterUrl = selectFirst(POSTER_SELECTOR)?.attr("src")?.let { fixUrl(it) }
        val isDubbed = isDubbed(this)

        val displayName = cleanTitle(animeTitle)
        val urlWithPoster = if (posterUrl != null) "$href|poster=$posterUrl" else href

        return newAnimeSearchResponse(displayName, fixUrl(urlWithPoster)) {
            this.posterUrl = posterUrl
            this.type = TvType.Anime
            val dubStatus = if (isDubbed) DubStatus.Dubbed else DubStatus.Subbed
            addDubStatus(dubStatus, episodeNumber)
        }
    }

    private fun Element.toAnimeSearchResponse(): AnimeSearchResponse? {
        val href = selectFirst("a")?.attr("href") ?: return null
        val rawTitle = selectFirst(TITLE_SELECTOR)?.text()?.trim() ?: return null
        val cleanedTitle = cleanTitle(rawTitle).ifBlank { return null }
        val posterUrl = selectFirst(POSTER_SELECTOR)?.attr("src")?.let { fixUrl(it) }
        val isDubbed = isDubbed(this)

        return newAnimeSearchResponse(cleanedTitle, fixUrl(href)) {
            this.posterUrl = posterUrl
            this.type = TvType.Anime
            addDubStatus(isDubbed, null)
        }
    }

    // ======================================================================
    // 4. MÉTODOS DE PÁGINA (GET, SEARCH, LOAD) - COMPLETO
    // ======================================================================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val baseUrl = request.data

        if (baseUrl.contains("/?s=")) {
            val url = if (page > 1) baseUrl.replace("/?s=", "/page/$page/?s=") else baseUrl
            val document = app.get(url).document

            val allItems = document.select("$ANIME_CARD, $EPISODE_CARD")
                .mapNotNull { 
                    val isEpisode = it.selectFirst(EPISODE_NUMBER_SELECTOR) != null
                    if (isEpisode) {
                        it.toEpisodeSearchResponse()
                    } else {
                        it.toAnimeSearchResponse()
                    }
                }
                .distinctBy { it.url }

            return newHomePageResponse(request.name, allItems, hasNext = true)
        }

        val document = app.get(baseUrl).document

        return when (request.name) {
            "Últimos Episódios" -> {
                val episodeElements = document.select("$LATEST_EPISODES_SECTION $EPISODE_CARD")
                val items = episodeElements
                    .mapNotNull { it.toEpisodeSearchResponse() }
                    .distinctBy { it.url }

                newHomePageResponse(
                    list = HomePageList(request.name, items, isHorizontalImages = true),
                    hasNext = false
                )
            }
            "Animes Mais Vistos" -> {
                var popularItems = listOf<AnimeSearchResponse>()
                for (container in document.select(".aniContainer")) {
                    val titleElement = container.selectFirst(".aniContainerTitulo")
                    if (titleElement != null && titleElement.text().contains("Animes Mais Vistos", true)) {
                        popularItems = container.select(".aniItem")
                            .mapNotNull { it.toAnimeSearchResponse() }
                            .distinctBy { it.url }
                            .take(10)
                        break
                    }
                }
                if (popularItems.isEmpty()) {
                    val slides = document.select("#splide01 .splide__slide")
                        .filterNot { it.hasClass("splide__slide--clone") }
                    popularItems = slides
                        .mapNotNull { slide -> slide.selectFirst(".aniItem")?.toAnimeSearchResponse() }
                        .distinctBy { it.url }
                        .take(10)
                }
                newHomePageResponse(
                    list = HomePageList(request.name, popularItems, isHorizontalImages = false),
                    hasNext = false
                )
            }
            "Animes Recentes" -> {
                var recentItems = listOf<AnimeSearchResponse>()
                for (container in document.select(".aniContainer")) {
                    val titleElement = container.selectFirst(".aniContainerTitulo")
                    if (titleElement != null && titleElement.text().contains("ANIMES RECENTES", true)) {
                        recentItems = container.select(".aniItem")
                            .mapNotNull { it.toAnimeSearchResponse() }
                            .distinctBy { it.url }
                            .take(10)
                        break
                    }
                }
                if (recentItems.isEmpty()) {
                    val slides = document.select("#splide02 .splide__slide")
                        .filterNot { it.hasClass("splide__slide--clone") }
                    recentItems = slides
                        .mapNotNull { slide -> slide.selectFirst(".aniItem")?.toAnimeSearchResponse() }
                        .distinctBy { it.url }
                        .take(10)
                }
                newHomePageResponse(
                    list = HomePageList(request.name, recentItems, isHorizontalImages = false),
                    hasNext = false
                )
            }
            else -> newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()
        val document = app.get("$mainUrl$SEARCH_PATH${query.replace(" ", "+")}").document
        return document.select("$ANIME_CARD, $EPISODE_CARD")
            .mapNotNull { 
                val isEpisode = it.selectFirst(EPISODE_NUMBER_SELECTOR) != null
                if (isEpisode) it.toEpisodeSearchResponse() else it.toAnimeSearchResponse()
            }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val parts = url.split("|poster=")
        val actualUrl = parts[0]
        val thumbPoster = parts.getOrNull(1)?.let { if (it.isNotBlank()) fixUrl(it) else null }

        val document = app.get(actualUrl).document

        val rawTitle = document.selectFirst(ANIME_TITLE)?.text()?.trim() ?: "Sem Título"
        val episodeNumber = extractEpisodeNumber(rawTitle) ?: 1
        val title = cleanTitle(rawTitle)
        val poster = thumbPoster ?: document.selectFirst(ANIME_POSTER)?.attr("src")?.let { fixUrl(it) }
        val siteSynopsis = document.selectFirst(ANIME_SYNOPSIS)?.text()?.trim()
        val synopsis = if (actualUrl.contains("/video/")) {
            siteSynopsis ?: "Episódio $episodeNumber de $title"
        } else {
            siteSynopsis ?: "Sinopse não disponível."
        }

        var year: Int? = null
        var episodes: Int? = null
        var genres = emptyList<String>()
        var audioType = ""

        document.select(ANIME_METADATA).forEach { element ->
            val text = element.text()
            when {
                text.contains("Gênero:", true) -> genres = text.substringAfter("Gênero:").split(",").map { it.trim() }
                text.contains("Ano:", true) -> year = text.substringAfter("Ano:").trim().toIntOrNull()
                text.contains("Episódios:", true) -> episodes = text.substringAfter("Episódios:").trim().toIntOrNull()
                text.contains("Tipo de Episódio:", true) -> audioType = text.substringAfter("Tipo de Episódio:").trim()
            }
        }

        val isDubbed = rawTitle.contains("dublado", true) || audioType.contains("dublado", true)

        val episodesList = document.select(EPISODE_LIST).mapNotNull { element ->
            val episodeTitle = element.text().trim()
            val episodeUrl = element.attr("href")
            val epNumber = extractEpisodeNumber(episodeTitle) ?: 1
            newEpisode(episodeUrl) {
                this.name = "Episódio $epNumber"
                this.episode = epNumber
                this.posterUrl = poster
            }
        }

        val allEpisodes = if (episodesList.isEmpty() && actualUrl.contains("/video/")) {
            listOf(newEpisode(actualUrl) {
                this.name = "Episódio $episodeNumber"
                this.episode = episodeNumber
                this.posterUrl = poster
            })
        } else {
            episodesList
        }

        val sortedEpisodes = allEpisodes.sortedBy { it.episode }
        val showStatus = if (episodes != null && sortedEpisodes.size >= episodes) ShowStatus.Completed else ShowStatus.Ongoing

        return newAnimeLoadResponse(title, actualUrl, TvType.Anime) {
            this.posterUrl = poster
            this.year = year
            this.plot = synopsis
            this.tags = genres
            this.showStatus = showStatus
            if (sortedEpisodes.isNotEmpty()) addEpisodes(if (isDubbed) DubStatus.Dubbed else DubStatus.Subbed, sortedEpisodes)
        }
    }

    // ======================================================================
    // 5. EXTRAÇÃO DE LINKS (LÓGICA MANUAL + LOGS)
    // ======================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val actualUrl = data.split("|poster=")[0]
        println("\n🛑 [AniTube] =========================================")
        println("📍 [AniTube] URL Episódio: $actualUrl")

        val document = app.get(actualUrl).document
        var linksFound = false

        // -----------------------------------------------------------
        // 1. Extração JWPlayer (bg.mp4) - REDIRECIONAMENTO MANUAL
        // -----------------------------------------------------------
        document.select("iframe[src*='bg.mp4']").firstOrNull()?.let { iframe ->
            val initialSrc = iframe.attr("src")
            println("🔎 [AniTube] Iframe bg.mp4 encontrado: $initialSrc")
            
            try {
                // HEADERS REQUISIÇÃO 1 (Simulando o navegador no AniTube)
                val headersStep1 = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                    "Referer" to actualUrl, // Ex: https://www.anitube.news/video/xxx
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
                    "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
                    "Upgrade-Insecure-Requests" to "1",
                    "Sec-Fetch-Dest" to "iframe",
                    "Sec-Fetch-Mode" to "navigate",
                    "Sec-Fetch-Site" to "same-origin"
                )

                println("🚀 [AniTube] Request 1: GET $initialSrc (No Auto Redirect)")
                
                // IMPORTANTE: allowRedirects = false para capturar o 302 manualmente
                val response1 = app.get(initialSrc, headers = headersStep1, allowRedirects = false)
                
                println("⬅️ [AniTube] Response 1 Code: ${response1.code}")
                
                var contentHtml = ""

                // Lógica de Redirecionamento (301, 302, 307)
                if (response1.code == 301 || response1.code == 302 || response1.code == 307) {
                    val location = response1.headers["location"] ?: response1.headers["Location"]
                    
                    if (location != null) {
                        println("🔄 [AniTube] Redirect Location: $location")
                        
                        // HEADERS REQUISIÇÃO 2 (Simulando o request ao anivideo.net)
                        // AQUI É A MÁGICA: O Referer muda para a HOME
                        val headersStep2 = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                            "Referer" to "https://www.anitube.news/", // <--- CRUCIAL
                            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
                            "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
                            "Upgrade-Insecure-Requests" to "1",
                            "Sec-Fetch-Dest" to "iframe",
                            "Sec-Fetch-Mode" to "navigate",
                            "Sec-Fetch-Site" to "cross-site"
                        )

                        println("🚀 [AniTube] Request 2: GET $location")
                        val response2 = app.get(location, headers = headersStep2)
                        
                        println("⬅️ [AniTube] Response 2 Code: ${response2.code}")
                        contentHtml = response2.text
                        
                        // Debug do HTML
                        if (response2.code == 404) {
                            println("⚠️ [AniTube] ERRO 404 na segunda requisição!")
                        } else {
                            println("📄 [AniTube] HTML 2 (${contentHtml.length} chars)")
                        }
                    } else {
                        println("❌ [AniTube] Header 'Location' não encontrado no redirect.")
                    }
                } else if (response1.code == 200) {
                    println("⚠️ [AniTube] Sem redirect, usando corpo da resposta 1.")
                    contentHtml = response1.text
                } else {
                    println("❌ [AniTube] Falha na requisição 1: Code ${response1.code}")
                }

                // Processar o HTML final se houver
                if (contentHtml.isNotBlank()) {
                    // Debug se o packer está presente
                    if (contentHtml.contains("eval(function(p,a,c,k,e,d)")) {
                        println("✅ [AniTube] Packer encontrado! Decodificando...")
                        val decoded = decodePacked(contentHtml)
                        
                        if (decoded != null) {
                            // Extrai MP4 (Google Video)
                            val mp4Regex = Regex("https?://[^\\s'\"]+videoplayback[^\\s'\"]*")
                            mp4Regex.findAll(decoded).forEach { match ->
                                println("🎬 [AniTube] Link MP4: ${match.value}")
                                callback(newExtractorLink(name, "JWPlayer MP4", match.value, ExtractorLinkType.VIDEO) {
                                    referer = "https://api.anivideo.net/"
                                })
                                linksFound = true
                            }
                            
                            // Extrai M3U8
                            val m3u8Regex = Regex("https?://[^\\s'\"]+\\.m3u8[^\\s'\"]*")
                            m3u8Regex.findAll(decoded).forEach { match ->
                                println("📡 [AniTube] Link HLS: ${match.value}")
                                callback(newExtractorLink(name, "AniTube HLS", match.value, ExtractorLinkType.M3U8) {
                                    referer = "https://api.anivideo.net/"
                                })
                                linksFound = true
                            }
                        } else {
                            println("❌ [AniTube] Falha ao decodificar packer.")
                        }
                    } else {
                        println("❌ [AniTube] 'eval(function' não encontrado no HTML final.")
                    }
                }

            } catch (e: Exception) {
                println("💥 [AniTube] Exception: ${e.message}")
                e.printStackTrace()
            }
        } ?: println("⚠️ [AniTube] Iframe 'bg.mp4' não encontrado.")

        // -----------------------------------------------------------
        // 2. Extração Player FHD (Fallback)
        // -----------------------------------------------------------
        document.selectFirst(PLAYER_FHD)?.let { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && !src.contains("bg.mp4")) {
                println("📺 [AniTube] Player FHD encontrado: $src")
                val m3u8Url = extractM3u8FromUrl(src) ?: src
                callback(newExtractorLink(name, "Player FHD", m3u8Url, ExtractorLinkType.M3U8) {
                    referer = "$mainUrl/"
                    quality = 1080
                })
                linksFound = true
            }
        }

        // -----------------------------------------------------------
        // 3. Extração Player Backup (Fallback)
        // -----------------------------------------------------------
        document.selectFirst(PLAYER_BACKUP)?.let { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && !src.contains("bg.mp4")) {
                println("📺 [AniTube] Player Backup encontrado: $src")
                callback(newExtractorLink(name, "Player Backup", src, ExtractorLinkType.VIDEO) {
                    referer = "$mainUrl/"
                    quality = 720
                })
                linksFound = true
            }
        }

        // -----------------------------------------------------------
        // 4. Varredura final (Fallback)
        // -----------------------------------------------------------
        if (!linksFound) {
            document.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.contains("m3u8") && !src.contains("bg.mp4")) {
                    val url = extractM3u8FromUrl(src) ?: src
                    println("📺 [AniTube] Player Auto encontrado: $url")
                    callback(newExtractorLink(name, "Player Auto", url, ExtractorLinkType.M3U8) {
                        referer = "$mainUrl/"
                        quality = 720
                    })
                    linksFound = true
                }
            }
        }

        println("🛑 [AniTube] Fim loadLinks. Encontrou? $linksFound")
        return linksFound
    }
}
