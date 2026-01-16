package com.AniTube

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.*
import org.jsoup.nodes.Element
import java.net.URLDecoder
import kotlin.math.min

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
    }

    private fun logDebug(message: String) {
        println("[AniTube-DEBUG] $message")
    }

    // ============== FUNÇÃO DE UNPACK IDÊNTICA AO JAVASCRIPT ==============
    private fun unpack(p: String, a: Int, c: Int, k: String): String {
        logDebug("🚀 Iniciando unpack EXATO (JS style): a=$a, c=$c, k length=${k.length}")
        
        val dict = k.split("|")
        val lookup = mutableMapOf<String, String>()
        
        // Função e(c) EXATA como no JavaScript
        fun e(c: Int): String {
            return if (c < a) {
                ""
            } else {
                e(c / a) + ((c % a).let { remainder ->
                    if (remainder > 35) {
                        (remainder + 29).toChar().toString()
                    } else {
                        remainder.toString(36)
                    }
                })
            }
        }
        
        // Preencher lookup table EXATAMENTE como no JavaScript
        var currentC = c
        while (currentC-- > 0) {
            val key = e(currentC)
            lookup[key] = dict.getOrElse(currentC) { key }
        }
        
        logDebug("📊 Lookup table size: ${lookup.size}")
        
        // Replace EXATO como no JavaScript: /\b\w+\b/g
        val wordRegex = Regex("""\b\w+\b""")
        var result = p
        var matchCount = 0
        
        // Processar como o JavaScript faz (iterar sobre todos os matches)
        val matches = wordRegex.findAll(p).toList()
        matches.forEach { match ->
            val token = match.value
            val replacement = lookup[token]
            if (replacement != null && replacement != token) {
                // Substituir apenas este token específico
                result = result.replaceFirst(Regex("""\b$token\b"""), replacement)
                matchCount++
            }
        }
        
        logDebug("✅ Unpack completo: ${matchCount} tokens substituídos")
        logDebug("📝 Resultado length: ${result.length}")
        
        return result
    }

    // ============== FUNÇÃO MELHORADA PARA EXTRAIR LINKS ==============
    private fun extractVideoLinksFromDecoded(decoded: String): List<String> {
        val links = mutableListOf<String>()
        
        try {
            logDebug("🔍 Analisando decoded para links de vídeo...")
            
            // Padrão 1: URLs completas do Google Video
            val pattern1 = Regex("""https?://[^"'\s]*videoplayback[^"'\s]*""", RegexOption.IGNORE_CASE)
            val matches1 = pattern1.findAll(decoded)
            
            matches1.forEachIndexed { index, match ->
                val url = match.value
                logDebug("🎬 URL encontrada ${index + 1}: ${url.substring(0, min(80, url.length))}...")
                if (url.contains("googlevideo.com")) {
                    links.add(url)
                }
            }
            
            logDebug("📊 Total de links encontrados: ${links.size}")
            
        } catch (e: Exception) {
            logDebug("💥 Erro ao extrair links: ${e.message}")
        }
        
        return links
    }

    // ============== FUNÇÃO PRINCIPAL DE EXTRAÇÃO JW PLAYER ==============
    private suspend fun extractJWPlayerLinks(iframeSrc: String, videoUrl: String): List<ExtractorLink> {
        val links = mutableListOf<ExtractorLink>()
        
        try {
            logDebug("🚀 Iniciando extração JW Player")
            logDebug("📌 Iframe SRC: $iframeSrc")
            
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
            
            logDebug("📊 Status primeira requisição: ${response1.code}")
            
            var playerUrl = iframeSrc
            val location = response1.headers["location"]
            if (location != null && (response1.code == 301 || response1.code == 302)) {
                playerUrl = location
                logDebug("📍 Redirecionado para: $playerUrl")
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
            logDebug("📄 HTML obtido: ${playerHtml.length} caracteres")
            
            // Procurar packer code - MESMA REGEX DO JAVASCRIPT
            val packerRegex = Regex(PACKER_REGEX, RegexOption.DOT_MATCHES_ALL)
            val match = packerRegex.find(playerHtml)
            
            if (match != null) {
                logDebug("✅ Packer code encontrado!")
                val p = match.groupValues[1]
                val a = match.groupValues[2].toInt()
                val c = match.groupValues[3].toInt()
                val k = match.groupValues[4]
                
                logDebug("📦 Parâmetros: p length=${p.length}, a=$a, c=$c, k length=${k.length}")
                
                // Decodificar com nossa função IDÊNTICA ao JavaScript
                val decoded = unpack(p, a, c, k)
                
                // Log do decoded (apenas os primeiros 1000 caracteres para debug)
                val preview = if (decoded.length > 1000) decoded.substring(0, 1000) + "..." else decoded
                logDebug("📄 Decoded preview:\n$preview")
                
                // Extrair links usando função melhorada
                val videoLinks = extractVideoLinksFromDecoded(decoded)
                
                videoLinks.forEach { url ->
                    try {
                        logDebug("🔗 Processando URL: ${url.substring(0, min(80, url.length))}...")
                        
                        // Determinar qualidade baseado no itag
                        val quality = when {
                            url.contains("itag=37") -> 1080
                            url.contains("itag=22") -> 720
                            url.contains("itag=59") -> 480
                            url.contains("itag=18") -> 360
                            else -> 360
                        }
                        
                        links.add(
                            newExtractorLink(
                                name,
                                "JW Player",
                                url,
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
                        
                        logDebug("✅ Link adicionado: qualidade $quality")
                        
                    } catch (e: Exception) {
                        logDebug("⚠️  Erro ao processar URL: ${e.message}")
                    }
                }
                
                logDebug("📊 Total links JW Player encontrados: ${links.size}")
                
            } else {
                logDebug("❌ Packer code não encontrado no HTML")
            }
            
        } catch (e: Exception) {
            logDebug("💥 Erro no JW Player: ${e.message}")
        }
        
        return links
    }

    // ============== FUNÇÃO ALTERNATIVA: USAR M3U8 SE JW PLAYER FALHAR ==============
    private fun extractM3u8LinksFromPage(document: org.jsoup.nodes.Document): List<ExtractorLink> {
        val links = mutableListOf<ExtractorLink>()
        
        try {
            logDebug("🔍 Buscando links M3U8 na página...")
            
            // 1. Verificar player FHD (#blog2 iframe)
            document.selectFirst(PLAYER_FHD)?.let { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank() && src.contains("m3u8")) {
                    logDebug("✅ Player FHD encontrado: $src")
                    links.add(
                        newExtractorLink(
                            name,
                            "Player FHD",
                            src,
                            ExtractorLinkType.M3U8
                        ) {
                            referer = "$mainUrl/"
                            quality = 1080
                        }
                    )
                }
            }
            
            // 2. Verificar scripts que contenham m3u8
            document.select("script").forEach { script ->
                val scriptContent = script.html()
                if (scriptContent.contains("m3u8")) {
                    val m3u8Regex = Regex("""https?://[^"'\s]*\.m3u8[^"'\s]*""")
                    val matches = m3u8Regex.findAll(scriptContent)
                    
                    matches.forEach { match ->
                        val m3u8Url = match.value
                        if (!m3u8Url.contains("anivideo.net")) { // Ignorar links da API
                            logDebug("🎬 M3U8 em script: $m3u8Url")
                            links.add(
                                newExtractorLink(
                                    name,
                                    "M3U8 Script",
                                    m3u8Url,
                                    ExtractorLinkType.M3U8
                                ) {
                                    referer = "$mainUrl/"
                                    quality = 720
                                }
                            )
                        }
                    }
                }
            }
            
            logDebug("📊 Total links M3U8 encontrados: ${links.size}")
            
        } catch (e: Exception) {
            logDebug("💥 Erro ao extrair M3U8: ${e.message}")
        }
        
        return links
    }

    // ============== FUNÇÕES AUXILIARES ==============
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
            } catch (e: Exception) { 
                logDebug("❌ Erro ao decodificar m3u8: ${e.message}")
                null 
            }
        } else { url }
    }

    // ============== LOAD LINKS (PRINCIPAL) ==============
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val actualUrl = data.split("|poster=")[0]
        logDebug("🚀 ============== INICIANDO EXTRACTION ==============")
        logDebug("📌 URL: $actualUrl")
        logDebug("📌 Data completa: $data")

        val document = app.get(actualUrl).document
        var linksFound = false

        // ============== LOG DE TODOS OS IFRAMES ==============
        val allIframes = document.select("iframe")
        logDebug("📊 Total iframes encontrados: ${allIframes.size}")
        allIframes.forEachIndexed { index, iframe ->
            val src = iframe.attr("src")
            val id = iframe.attr("id")
            val cls = iframe.attr("class")
            logDebug("📋 Iframe $index: src='$src', id='$id', class='$cls'")
        }

        // ============== PRIMEIRA TENTATIVA: JW PLAYER ==============
        logDebug("\n🎯 PRIMEIRA TENTATIVA: JW Player")
        document.selectFirst(PLAYER_IFRAME)?.let { iframe ->
            val src = iframe.attr("src").takeIf { it.isNotBlank() } ?: return@let
            logDebug("✅ Iframe JW Player encontrado: ${src.substring(0, min(100, src.length))}...")
            
            val jwLinks = extractJWPlayerLinks(src, actualUrl)
            if (jwLinks.isNotEmpty()) {
                logDebug("🎉 JW Player retornou ${jwLinks.size} links!")
                jwLinks.forEach { 
                    callback(it)
                    linksFound = true
                }
                return@loadLinks linksFound
            } else {
                logDebug("❌ JW Player não retornou links")
            }
        }

        // ============== SEGUNDA TENTATIVA: M3U8 ==============
        if (!linksFound) {
            logDebug("\n🎯 SEGUNDA TENTATIVA: M3U8")
            val m3u8Links = extractM3u8LinksFromPage(document)
            if (m3u8Links.isNotEmpty()) {
                logDebug("🎉 M3U8 retornou ${m3u8Links.size} links!")
                m3u8Links.forEach {
                    callback(it)
                    linksFound = true
                }
                return@loadLinks linksFound
            }
        }

        // ============== TERCEIRA TENTATIVA: PLAYER BACKUP ==============
        if (!linksFound) {
            logDebug("\n🎯 TERCEIRA TENTATIVA: Player Backup")
            document.selectFirst(PLAYER_BACKUP)?.let { iframe ->
                val src = iframe.attr("src").takeIf { it.isNotBlank() } ?: return@let
                logDebug("✅ Player Backup encontrado: $src")
                
                callback(newExtractorLink(name, "Player Backup", src, ExtractorLinkType.VIDEO) {
                    referer = "$mainUrl/"
                    quality = 720
                })
                linksFound = true
            }
        }

        // ============== VERIFICAÇÃO FINAL ==============
        logDebug("\n📊 ============== RESULTADO FINAL ==============")
        logDebug("✅ Links encontrados: $linksFound")
        logDebug("🎬 Processo finalizado")

        return linksFound
    }

    // ============== RESTANTE DO CÓDIGO (SEM ALTERAÇÕES) ==============

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
}
