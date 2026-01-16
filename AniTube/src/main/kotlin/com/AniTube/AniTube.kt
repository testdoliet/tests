package com.AniTube

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.network.get
import com.lagradost.cloudstream3.network.post
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
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
        private const val PLAYER_IFRAME = "iframe.metaframe, iframe[src*='bg.mp4']"
        
        // JW Player patterns
        private const val PACKER_REGEX = """eval\(function\(p,a,c,k,e,d\).*?\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)"""
        private const val VIDEOPLAYBACK_REGEX = """https://[^"'\s]*videoplayback[^"'\s]*"""
    }

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

    // ============== FUNÇÃO DE UNPACK (BASE62 DECODER) ==============
    private fun unpack(p: String, a: Int, c: Int, k: String): String {
        val dict = k.split("|")
        val lookup = mutableMapOf<String, String>()
        
        fun e(c: Int): String {
            return if (c < a) {
                ""
            } else {
                e(c / a) + ((c % a).let {
                    if (it > 35) (it + 29).toChar().toString() else it.toString(36)
                })
            }
        }
        
        var currentC = c
        while (currentC-- > 0) {
            val key = e(currentC)
            lookup[key] = dict.getOrElse(currentC) { key }
        }
        
        return Regex("""\b\w+\b""").replace(p) { match ->
            lookup[match.value] ?: match.value
        }
    }

    // ============== EXTRACTOR JW PLAYER ==============
    private suspend fun extractJWPlayerLinks(iframeSrc: String, videoUrl: String): List<ExtractorLink> {
        val links = mutableListOf<ExtractorLink>()
        
        try {
            println("[AniTube] 🎬 Buscando JW Player em: $iframeSrc")
            
            // Primeira requisição para seguir redirecionamentos
            val response1 = app.get(
                iframeSrc,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Referer" to videoUrl,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                ),
                allowRedirects = false
            )
            
            var playerUrl = iframeSrc
            val location = response1.headers["location"]
            if (location != null && (response1.statusCode == 301 || response1.statusCode == 302)) {
                playerUrl = location
                println("[AniTube] 📍 Redirecionado para: $playerUrl")
            }
            
            // Segunda requisição para obter HTML do player
            val response2 = app.get(
                playerUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Referer" to mainUrl,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
            )
            
            val playerHtml = response2.text
            println("[AniTube] 📄 HTML obtido: ${playerHtml.length} caracteres")
            
            // Procurar packer code
            val packerRegex = Regex(PACKER_REGEX, RegexOption.DOT_MATCHES_ALL)
            val match = packerRegex.find(playerHtml)
            
            if (match != null) {
                println("[AniTube] ✅ Packer code encontrado!")
                val p = match.groupValues[1]
                val a = match.groupValues[2].toInt()
                val c = match.groupValues[3].toInt()
                val k = match.groupValues[4]
                
                // Decodificar
                val decoded = unpack(p, a, c, k)
                println("[AniTube] 🔍 Procurando links videoplayback...")
                
                // Extrair links videoplayback
                val videoRegex = Regex(VIDEOPLAYBACK_REGEX)
                val videoMatches = videoRegex.findAll(decoded)
                
                videoMatches.forEach { videoMatch ->
                    val link = videoMatch.value
                    if (link.contains("googlevideo.com")) {
                        println("[AniTube] 🔗 Link encontrado: ${link.substring(0, minOf(80, link.length))}...")
                        
                        // Determinar qualidade baseado no itag
                        val quality = when {
                            link.contains("itag=22") -> 720
                            link.contains("itag=37") -> 1080
                            link.contains("itag=59") -> 480
                            link.contains("itag=18") -> 360
                            else -> 360
                        }
                        
                        links.add(
                            newExtractorLink(
                                name,
                                "JW Player",
                                link,
                                ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "https://api.anivideo.net/"
                                this.quality = quality
                                this.headers = mapOf(
                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                                    "Origin" to "https://api.anivideo.net",
                                    "Referer" to "https://api.anivideo.net/"
                                )
                            }
                        )
                    }
                }
                
                println("[AniTube] 📊 Total links JW Player encontrados: ${links.size}")
            } else {
                println("[AniTube] ❌ Packer code não encontrado no JW Player")
            }
            
        } catch (e: Exception) {
            println("[AniTube] 💥 Erro no JW Player: ${e.message}")
        }
        
        return links
    }

    // ============== EXTRACTOR PLAYER 1 COM AXIOS (MÉTODO ALTERNATIVO) ==============
    private suspend fun extractPlayer1Links(iframeSrc: String, videoUrl: String): List<ExtractorLink> {
        val links = mutableListOf<ExtractorLink>()
        
        try {
            println("[AniTube] 🎯 Tentando método Player 1...")
            
            // Primeira requisição sem seguir redirecionamentos
            val response1 = app.get(
                iframeSrc,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K)",
                    "Referer" to videoUrl
                ),
                allowRedirects = false
            )
            
            var apiUrl = iframeSrc
            val location = response1.headers["location"]
            if (location != null) {
                apiUrl = location
                println("[AniTube] 📍 Redirecionado para: $apiUrl")
            }
            
            // Segunda requisição com referer da HOME
            val response2 = app.get(
                apiUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K)",
                    "Referer" to mainUrl
                )
            )
            
            val playerHtml = response2.text
            
            // Procurar packer code
            val packerRegex = Regex(PACKER_REGEX, RegexOption.DOT_MATCHES_ALL)
            val match = packerRegex.find(playerHtml)
            
            if (match != null) {
                println("[AniTube] ✅ Packer code encontrado no Player 1!")
                val p = match.groupValues[1]
                val a = match.groupValues[2].toInt()
                val c = match.groupValues[3].toInt()
                val k = match.groupValues[4]
                
                // Decodificar
                val decoded = unpack(p, a, c, k)
                
                // Extrair todos os links
                val linkRegex = Regex("""https?://[^"'\s]+""")
                val allLinks = linkRegex.findAll(decoded).map { it.value }.toList()
                
                allLinks.forEach { link ->
                    if (link.contains("videoplayback")) {
                        println("[AniTube] 🔗 Player 1 link: ${link.substring(0, minOf(60, link.length))}...")
                        
                        val quality = when {
                            link.contains("itag=22") -> 720
                            link.contains("itag=37") -> 1080
                            link.contains("itag=59") -> 480
                            else -> 360
                        }
                        
                        links.add(
                            newExtractorLink(
                                name,
                                "Player 1",
                                link,
                                ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "https://api.anivideo.net/"
                                this.quality = quality
                                this.headers = mapOf(
                                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K)",
                                    "Origin" to "https://api.anivideo.net"
                                )
                            }
                        )
                    }
                }
                
                println("[AniTube] 📊 Total links Player 1: ${links.size}")
            }
            
        } catch (e: Exception) {
            println("[AniTube] ❌ Erro no Player 1: ${e.message}")
        }
        
        return links
    }

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

        clean = clean.replace("\\s*\\d+\\s*\$".toRegex(), "").trim()

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

        val urlWithPoster = if (posterUrl != null) {
            "$href|poster=$posterUrl"
        } else {
            href
        }

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
                        .mapNotNull { slide ->
                            slide.selectFirst(".aniItem")?.toAnimeSearchResponse()
                        }
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
                        .mapNotNull { slide ->
                            slide.selectFirst(".aniItem)?.toAnimeSearchResponse()
                        }
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
                if (isEpisode) {
                    it.toEpisodeSearchResponse()
                } else {
                    it.toAnimeSearchResponse()
                }
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val actualUrl = data.split("|poster=")[0]
        val document = app.get(actualUrl).document

        var linksFound = false
        
        println("[AniTube] 🔍 Iniciando extração de links para: $actualUrl")

        // ============== PRIMEIRO: JOGAR PARA O JW PLAYER (MÉTODO PRINCIPAL) ==============
        document.selectFirst(PLAYER_IFRAME)?.let { iframe ->
            val src = iframe.attr("src").takeIf { it.isNotBlank() } ?: return@let
            
            println("[AniTube] 🎬 Iframe JW Player encontrado: ${src.substring(0, minOf(80, src.length))}...")
            
            // Tentar Player 1 primeiro (método alternativo)
            val player1Links = extractPlayer1Links(src, actualUrl)
            if (player1Links.isNotEmpty()) {
                player1Links.forEach { callback(it) }
                linksFound = true
                println("[AniTube] ✅ Player 1 retornou ${player1Links.size} links")
            } else {
                // Fallback para JW Player normal
                val jwLinks = extractJWPlayerLinks(src, actualUrl)
                if (jwLinks.isNotEmpty()) {
                    jwLinks.forEach { callback(it) }
                    linksFound = true
                    println("[AniTube] ✅ JW Player retornou ${jwLinks.size} links")
                }
            }
        }

        // ============== SEGUNDO: PLAYER FHD (se ainda não encontrou links) ==============
        if (!linksFound) {
            document.selectFirst(PLAYER_FHD)?.let { iframe ->
                val src = iframe.attr("src").takeIf { it.isNotBlank() } ?: return@let
                val m3u8Url = extractM3u8FromUrl(src) ?: src

                callback(newExtractorLink(name, "Player FHD", m3u8Url, ExtractorLinkType.M3U8) {
                    referer = "$mainUrl/"
                    quality = 1080
                })
                linksFound = true
                println("[AniTube] ✅ Player FHD encontrado")
            }
        }

        // ============== TERCEIRO: PLAYER BACKUP ==============
        if (!linksFound) {
            document.selectFirst(PLAYER_BACKUP)?.let { iframe ->
                val src = iframe.attr("src").takeIf { it.isNotBlank() } ?: return@let
                val isM3u8 = src.contains("m3u8", true)
                val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

                callback(newExtractorLink(name, "Player Backup", src, linkType) {
                    referer = "$mainUrl/"
                    quality = 720
                })
                linksFound = true
                println("[AniTube] ✅ Player Backup encontrado")
            }
        }

        // ============== QUARTO: BUSCA EM TODOS OS IFRAMES (fallback) ==============
        if (!linksFound) {
            document.select("iframe").forEachIndexed { index, iframe ->
                val src = iframe.attr("src")
                if (src.contains("m3u8", true) || src.contains("bg.mp4", true)) {
                    val alreadyChecked = document.selectFirst(PLAYER_FHD)?.attr("src") == src || 
                                      document.selectFirst(PLAYER_BACKUP)?.attr("src") == src ||
                                      document.selectFirst(PLAYER_IFRAME)?.attr("src") == src

                    if (!alreadyChecked) {
                        if (src.contains("m3u8", true)) {
                            val m3u8Url = extractM3u8FromUrl(src) ?: src
                            callback(newExtractorLink(name, "Player Auto $index", m3u8Url, ExtractorLinkType.M3U8) {
                                referer = "$mainUrl/"
                                quality = 720
                            })
                        } else if (src.contains("bg.mp4", true)) {
                            // Tentar extrair links JW deste iframe também
                            val fallbackLinks = extractPlayer1Links(src, actualUrl)
                            if (fallbackLinks.isNotEmpty()) {
                                fallbackLinks.forEach { callback(it) }
                            }
                        }
                        linksFound = true
                        println("[AniTube] ✅ Player Auto $index encontrado")
                    }
                }
            }
        }

        println("[AniTube] 📊 Extração concluída: ${if (linksFound) "SUCESSO" else "SEM LINKS"}")
        return linksFound
    }
}
