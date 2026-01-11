package com.AniTube

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.get
import org.jsoup.nodes.Element
import java.net.URLDecoder

class AniTube : MainAPI() {
    override var mainUrl = "https://www.anitube.news"
    override var name = "AniTube"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
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
                            slide.selectFirst(".aniItem")?.toAnimeSearchResponse()
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
            
            Episode(episodeUrl, "Episódio $epNumber").apply {
                episode = epNumber
                posterUrl = poster
            }
        }
        
        val allEpisodes = if (episodesList.isEmpty() && actualUrl.contains("/video/")) {
            listOf(Episode(actualUrl, "Episódio $episodeNumber").apply {
                episode = episodeNumber
                posterUrl = poster
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
            
            if (sortedEpisodes.isNotEmpty()) {
                addEpisodes(if (isDubbed) DubStatus.Dubbed else DubStatus.Subbed, sortedEpisodes)
            }
        }
    }
    
    // ============== FUNÇÕES DE EXTRAÇÃO DE STREAMS ==============
    
    private suspend fun extractPlayerJW(html: String, videoUrl: String): List<ExtractorLink> {
        return try {
            val document = app.parseDocument(html)
            
            // Encontrar iframe - procura pelo iframe com classe metaframe ou que contenha bg.mp4 no src
            val iframeSrc = document.selectFirst("iframe.metaframe")?.attr("src") ?: 
                           document.selectFirst("iframe[src*=\"bg.mp4\"]")?.attr("src")
            
            if (iframeSrc.isNullOrEmpty()) return emptyList()
            
            // Primeiro salto: obter a URL de redirecionamento
            val resPonte = app.get(
                iframeSrc,
                referer = videoUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K)",
                    "Referer" to videoUrl
                ),
                allowRedirects = false
            )
            
            val urlApi = resPonte.headers["location"] ?: iframeSrc
            
            // Segundo salto: acessar a API do player com referer do anitube
            val resApi = app.get(
                urlApi,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K)",
                    "Referer" to "https://www.anitube.news/"
                )
            )
            
            val playerHtml = resApi.text
            
            // Buscar por código packer (eval(function(p,a,c,k,e,d))
            val packerRegex = """eval\(function\(p,a,c,k,e,d\).*?}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)""".toRegex(
                RegexOption.DOT_MATCHES_ALL
            )
            
            val match = packerRegex.find(playerHtml)
            
            if (match != null) {
                val (packed, aStr, cStr, kStr) = match.destructured
                val a = aStr.toInt()
                val c = cStr.toInt()
                val dict = kStr.split("|")
                
                // Função unpack similar à do JavaScript
                val decoded = unpack(packed, a, c, dict)
                
                // Buscar links de vídeo
                val videoRegex = """https?://[^\s'"]+""".toRegex()
                val videoLinks = videoRegex.findAll(decoded)
                    .map { it.value }
                    .filter { it.contains("videoplayback") }
                    .toList()
                
                videoLinks.mapNotNull { link ->
                    val quality = when {
                        link.contains("itag=22") -> "720p"
                        link.contains("itag=59") -> "480p"
                        link.contains("itag=18") -> "360p"
                        else -> "360p"
                    }
                    
                    newExtractorLink(
                        name,
                        "JWPlayer ($quality)",
                        link,
                        mainUrl,
                        getQualityFromString(quality),
                        isM3u8 = false,
                        headers = mapOf(
                            "Referer" to "https://api.anivideo.net/",
                            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K)",
                            "Origin" to "https://api.anivideo.net"
                        )
                    )
                }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    // Função unpack para decodificar base62
    private fun unpack(packed: String, a: Int, c: Int, dict: List<String>): String {
        val e = object : Function1<Int, String> {
            override fun invoke(c: Int): String {
                return if (c < a) {
                    ""
                } else {
                    invoke(c / a) + (c % a).let { 
                        if (it > 35) (it + 29).toChar() 
                        else it.toString(36).single() 
                    }
                }
            }
        }
        
        val lookup = mutableMapOf<String, String>()
        for (i in 0 until c) {
            val key = e(i)
            lookup[key] = dict.getOrNull(i) ?: key
        }
        
        val tokenRegex = """\b\w+\b""".toRegex()
        return tokenRegex.replace(packed) { match ->
            lookup[match.value] ?: match.value
        }
    }
    
    // Função para extrair Player FHD (anivideo.net)
    private fun extractPlayerFHD(html: String, videoUrl: String): List<ExtractorLink> {
        val streams = mutableListOf<ExtractorLink>()
        
        // Método 1: API anivideo.net direta
        val apiRegex = """https?://api\.anivideo\.net/videohls\.php\?[^"'\s]+""".toRegex()
        val apiMatches = apiRegex.findAll(html)
        
        apiMatches.forEach { match ->
            val apiLink = match.value
            val cleanLink = extractM3u8FromUrl(apiLink)
            if (cleanLink != null) {
                val quality = extractQualityFromUrl(cleanLink)
                streams.add(
                    newExtractorLink(
                        name,
                        "FHD Player ($quality)",
                        cleanLink,
                        mainUrl,
                        getQualityFromString(quality),
                        isM3u8 = true,
                        headers = mapOf(
                            "Referer" to "https://api.anivideo.net/",
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                        )
                    )
                )
            }
        }
        
        // Método 2: Iframe FHD
        val document = app.parseDocument(html)
        document.select("iframe[src*=\"api.anivideo.net\"]").forEach { iframe ->
            val src = iframe.attr("src")
            val m3u8Url = extractM3u8FromUrl(src)
            if (m3u8Url != null) {
                val quality = extractQualityFromUrl(m3u8Url)
                streams.add(
                    newExtractorLink(
                        name,
                        "FHD Iframe ($quality)",
                        m3u8Url,
                        mainUrl,
                        getQualityFromString(quality),
                        isM3u8 = true,
                        headers = mapOf(
                            "Referer" to "https://api.anivideo.net/",
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                        )
                    )
                )
            }
        }
        
        return streams
    }
    
    // Função para extrair Player M3U8 de scripts
    private fun extractPlayerM3U8(html: String, videoUrl: String): List<ExtractorLink> {
        val streams = mutableListOf<ExtractorLink>()
        
        // Buscar m3u8 em scripts
        val m3u8Regex = """https?://[^\s'"]+\.m3u8[^\s'"]*""".toRegex(RegexOption.IGNORE_CASE)
        val matches = m3u8Regex.findAll(html)
        
        matches.forEach { match ->
            val m3u8Url = match.value
            // Ignorar links já tratados no Player FHD
            if (!m3u8Url.contains("anivideo.net")) {
                val quality = extractQualityFromUrl(m3u8Url)
                streams.add(
                    newExtractorLink(
                        name,
                        "M3U8 Script ($quality)",
                        m3u8Url,
                        mainUrl,
                        getQualityFromString(quality),
                        isM3u8 = true,
                        headers = mapOf(
                            "Referer" to videoUrl,
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                        )
                    )
                )
            }
        }
        
        return streams
    }
    
    // Função para detectar tipo de player
    private fun detectPlayerType(html: String): String {
        return when {
            html.contains("api.anivideo.net/videohls.php") -> "FHD"
            html.contains("<iframe") && html.contains("api.anivideo.net") -> "FHD"
            html.contains("jwplayer().setup") -> "JWPLAYER"
            html.contains("jwplayer(") -> "JWPLAYER"
            html.contains("sources") && html.contains("googlevideo.com") -> "JWPLAYER"
            html.contains(".m3u8") && !html.contains("api.anivideo.net") -> "M3U8_SCRIPT"
            else -> "UNKNOWN"
        }
    }
    
    // Função para extrair qualidade da URL
    private fun extractQualityFromUrl(url: String): String {
        return when {
            url.contains("1080") -> "1080p"
            url.contains("720") -> "720p"
            url.contains("480") -> "480p"
            url.contains("360") -> "360p"
            url.contains("hd") -> "720p"
            url.contains("itag=37") -> "1080p"
            url.contains("itag=22") -> "720p"
            url.contains("itag=59") -> "480p"
            url.contains("itag=18") -> "360p"
            else -> "SD"
        }
    }
    
    // Função auxiliar para converter string de qualidade para valor numérico
    private fun getQualityFromString(qualityStr: String): Int {
        return when (qualityStr.lowercase()) {
            "1080p" -> Qualities.P1080.value
            "720p" -> Qualities.P720.value
            "480p" -> Qualities.P480.value
            "360p" -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
    
    // Função de extração de streams unificada (método híbrido)
    private suspend fun extractAniTubeStreams(videoUrl: String, referer: String = ""): List<ExtractorLink> {
        val streams = mutableListOf<ExtractorLink>()
        val usedUrls = mutableSetOf<String>()
        
        try {
            val actualReferer = if (referer.isNotEmpty()) referer else mainUrl
            val html = app.get(videoUrl, referer = actualReferer).text
            
            // Detectar tipo de player
            val playerType = detectPlayerType(html)
            
            // Extrair de todos os players possíveis
            val extractionResults = mutableListOf<List<ExtractorLink>>()
            
            // Tentar Player FHD (anivideo.net iframe)
            if (html.contains("api.anivideo.net") || playerType == "FHD" || playerType == "UNKNOWN") {
                extractionResults.add(extractPlayerFHD(html, videoUrl))
            }
            
            // Tentar Player JW (Player 1)
            if (html.contains("jwplayer") || playerType == "JWPLAYER" || playerType == "UNKNOWN") {
                val jwStreams = extractPlayerJW(html, videoUrl)
                if (jwStreams.isNotEmpty()) {
                    extractionResults.add(jwStreams)
                }
            }
            
            // Tentar M3U8 em scripts (fallback)
            if (html.contains(".m3u8") || playerType == "M3U8_SCRIPT" || playerType == "UNKNOWN") {
                extractionResults.add(extractPlayerM3U8(html, videoUrl))
            }
            
            // Combinar resultados únicos
            extractionResults.forEach { result ->
                result.forEach { stream ->
                    if (!usedUrls.contains(stream.url)) {
                        usedUrls.add(stream.url)
                        streams.add(stream)
                    }
                }
            }
            
            // Fallback: buscar m3u8/mp4 diretamente no HTML
            if (streams.isEmpty()) {
                val mediaRegex = """https?://[^\s'"]+\.(m3u8|mp4)[^\s'"]*""".toRegex(RegexOption.IGNORE_CASE)
                val matches = mediaRegex.findAll(html)
                
                matches.forEach { match ->
                    val mediaUrl = match.value
                    // Ignorar links da ponte que terminam em /bg.mp4
                    if (!mediaUrl.contains("/bg.mp4") && !usedUrls.contains(mediaUrl)) {
                        usedUrls.add(mediaUrl)
                        
                        val quality = extractQualityFromUrl(mediaUrl)
                        val isM3u8 = mediaUrl.contains(".m3u8", ignoreCase = true)
                        
                        streams.add(
                            newExtractorLink(
                                name,
                                "AniTube ($quality)",
                                mediaUrl,
                                mainUrl,
                                getQualityFromString(quality),
                                isM3u8 = isM3u8,
                                headers = mapOf(
                                    "Referer" to if (mediaUrl.contains("anivideo.net")) 
                                        "https://api.anivideo.net/" 
                                    else mainUrl,
                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                                )
                            )
                        )
                    }
                }
            }
            
            // Ordenar por qualidade (1080p > 720p > 480p > 360p > SD)
            streams.sortByDescending { stream ->
                when {
                    stream.quality >= Qualities.P1080.value -> 5
                    stream.quality >= Qualities.P720.value -> 4
                    stream.quality >= Qualities.P480.value -> 3
                    stream.quality >= Qualities.P360.value -> 2
                    else -> 1
                }
            }
            
        } catch (e: Exception) {
            // Silenciar erro
        }
        
        return streams
    }
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val streams = extractAniTubeStreams(data, mainUrl)
            if (streams.isNotEmpty()) {
                streams.forEach(callback)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
}
