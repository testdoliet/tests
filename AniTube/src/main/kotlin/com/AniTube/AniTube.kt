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

    // ==========================================
    // DECODIFICADOR PACKER (Com Logs)
    // ==========================================
    private fun decodePacked(packed: String): String? {
        try {
            println("📦 [AniTube] Iniciando processo de unpack...")
            val regex = "eval\\(function\\(p,a,c,k,e,d\\).*?\\}\\('(.*?)',(\\d+),(\\d+),'(.*?)'\\.split\\('\\|'\\)".toRegex()
            val match = regex.find(packed) 
            
            if (match == null) {
                println("❌ [AniTube] Padrão Packer não encontrado no script.")
                return null
            }
            
            val (p, aStr, cStr, kStr) = match.destructured
            val payload = p
            val radix = aStr.toInt()
            val count = cStr.toInt()
            val keywords = kStr.split("|")

            fun encodeBase(num: Int): String {
                val charset = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
                return if (num < radix) {
                    charset[num].toString()
                } else {
                    encodeBase(num / radix) + charset[num % radix]
                }
            }

            val dict = HashMap<String, String>()
            for (i in 0 until count) {
                val key = encodeBase(i)
                val value = keywords.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: key
                dict[key] = value
            }

            println("🔓 [AniTube] Script desembrulhado com sucesso!")
            return payload.replace(Regex("\\b\\w+\\b")) { result ->
                dict[result.value] ?: result.value
            }
        } catch (e: Exception) {
            println("💥 [AniTube] Erro fatal no unpacker: ${e.message}")
            return null
        }
    }

    // ... (Mapas de Gêneros - Mantidos iguais) ...
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

    // ... (Helpers de limpeza e extração - Mantidos iguais) ...
    private fun cleanTitle(dirtyTitle: String): String {
        return dirtyTitle.replace("(?i)\\s*–\\s*todos os epis[oó]dios".toRegex(), "")
            .replace("(?i)\\s*\\(dublado\\)".toRegex(), "").replace("(?i)\\s*\\(legendado\\)".toRegex(), "")
            .replace("(?i)\\s*dublado\\s*$".toRegex(), "").replace("(?i)\\s*legendado\\s*$".toRegex(), "")
            .replace("(?i)\\s*-\\s*epis[oó]dio\\s*\\d+".toRegex(), "").replace("(?i)\\s*–\\s*Epis[oó]dio\\s*\\d+".toRegex(), "")
            .replace("(?i)\\s*epis[oó]dio\\s*\\d+".toRegex(), "").replace("(?i)\\s*Ep\\.\\s*\\d+".toRegex(), "")
            .replace("\\s+".toRegex(), " ").trim().ifBlank { dirtyTitle }
    }

    private fun extractEpisodeNumber(title: String): Int? {
        return listOf("Epis[oó]dio\\s*(\\d+)".toRegex(RegexOption.IGNORE_CASE), "Ep\\.?\\s*(\\d+)".toRegex(RegexOption.IGNORE_CASE), "E(\\d+)".toRegex(RegexOption.IGNORE_CASE), "\\b(\\d{3,})\\b".toRegex(), "\\b(\\d{1,2})\\b".toRegex())
            .firstNotNullOfOrNull { it.find(title)?.groupValues?.get(1)?.toIntOrNull() }
    }

    private fun extractAnimeTitleFromEpisode(episodeTitle: String): String {
        return episodeTitle.replace("(?i)Epis[oó]dio\\s*\\d+".toRegex(), "").replace("(?i)Ep\\.?\\s*\\d+".toRegex(), "").replace("(?i)E\\d+".toRegex(), "").replace("–", "").replace("-", "").replace("(?i)\\s*\\(dublado\\)".toRegex(), "").replace("(?i)\\s*\\(legendado\\)".toRegex(), "").replace("\\s+".toRegex(), " ").replace("\\s*\\d+\\s*$".toRegex(), "").trim().ifBlank { "Anime" }
    }

    private fun isDubbed(element: Element): Boolean = element.selectFirst(AUDIO_BADGE_SELECTOR)?.text()?.contains("Dublado", true) ?: false

    private fun extractM3u8FromUrl(url: String): String? {
        return if (url.contains("d=")) try { URLDecoder.decode(url.substringAfter("d=").substringBefore("&"), "UTF-8") } catch (e: Exception) { null } else url
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

    // ... (Métodos principais: Main Page, Search, Load - Mantidos iguais) ...
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = request.data
        if (baseUrl.contains("/?s=")) {
            val url = if (page > 1) baseUrl.replace("/?s=", "/page/$page/?s=") else baseUrl
            val document = app.get(url).document
            val allItems = document.select("$ANIME_CARD, $EPISODE_CARD").mapNotNull { 
                if (it.selectFirst(EPISODE_NUMBER_SELECTOR) != null) it.toEpisodeSearchResponse() else it.toAnimeSearchResponse()
            }.distinctBy { it.url }
            return newHomePageResponse(request.name, allItems, hasNext = true)
        }
        val document = app.get(baseUrl).document
        return when (request.name) {
            "Últimos Episódios" -> {
                val items = document.select("$LATEST_EPISODES_SECTION $EPISODE_CARD").mapNotNull { it.toEpisodeSearchResponse() }.distinctBy { it.url }
                newHomePageResponse(HomePageList(request.name, items, isHorizontalImages = true), false)
            }
            "Animes Mais Vistos" -> { /* Lógica abreviada para focar no player */
                 val slides = document.select("#splide01 .splide__slide").filterNot { it.hasClass("splide__slide--clone") }
                 val items = slides.mapNotNull { it.selectFirst(".aniItem")?.toAnimeSearchResponse() }.distinctBy { it.url }.take(10)
                 newHomePageResponse(HomePageList(request.name, items, isHorizontalImages = false), false)
            }
            "Animes Recentes" -> {
                 val slides = document.select("#splide02 .splide__slide").filterNot { it.hasClass("splide__slide--clone") }
                 val items = slides.mapNotNull { it.selectFirst(".aniItem")?.toAnimeSearchResponse() }.distinctBy { it.url }.take(10)
                 newHomePageResponse(HomePageList(request.name, items, isHorizontalImages = false), false)
            }
            else -> newHomePageResponse(request.name, emptyList(), false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()
        val document = app.get("$mainUrl$SEARCH_PATH${query.replace(" ", "+")}").document
        return document.select("$ANIME_CARD, $EPISODE_CARD").mapNotNull { 
            if (it.selectFirst(EPISODE_NUMBER_SELECTOR) != null) it.toEpisodeSearchResponse() else it.toAnimeSearchResponse()
        }.distinctBy { it.url }
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
        val synopsis = if (actualUrl.contains("/video/")) siteSynopsis ?: "Episódio $episodeNumber de $title" else siteSynopsis ?: "Sinopse não disponível."
        
        var year: Int? = null; var episodes: Int? = null; var genres = emptyList<String>(); var audioType = ""
        document.select(ANIME_METADATA).forEach { 
            val t = it.text()
            if (t.contains("Gênero:", true)) genres = t.substringAfter("Gênero:").split(",").map { it.trim() }
            if (t.contains("Ano:", true)) year = t.substringAfter("Ano:").trim().toIntOrNull()
            if (t.contains("Episódios:", true)) episodes = t.substringAfter("Episódios:").trim().toIntOrNull()
            if (t.contains("Tipo de Episódio:", true)) audioType = t.substringAfter("Tipo de Episódio:").trim()
        }
        val isDubbed = rawTitle.contains("dublado", true) || audioType.contains("dublado", true)
        val episodesList = document.select(EPISODE_LIST).mapNotNull { element ->
            val epNum = extractEpisodeNumber(element.text()) ?: 1
            newEpisode(element.attr("href")) { this.name = "Episódio $epNum"; this.episode = epNum; this.posterUrl = poster }
        }
        val allEpisodes = if (episodesList.isEmpty() && actualUrl.contains("/video/")) listOf(newEpisode(actualUrl) { this.name = "Episódio $episodeNumber"; this.episode = episodeNumber; this.posterUrl = poster }) else episodesList
        val sortedEpisodes = allEpisodes.sortedBy { it.episode }
        return newAnimeLoadResponse(title, actualUrl, TvType.Anime) {
            this.posterUrl = poster; this.year = year; this.plot = synopsis; this.tags = genres
            this.showStatus = if (episodes != null && sortedEpisodes.size >= episodes!!) ShowStatus.Completed else ShowStatus.Ongoing
            if (sortedEpisodes.isNotEmpty()) addEpisodes(if (isDubbed) DubStatus.Dubbed else DubStatus.Subbed, sortedEpisodes)
        }
    }

    // ==========================================
    // EXTRAÇÃO DE LINKS (COM LOGS DETALHADOS)
    // ==========================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val actualUrl = data.split("|poster=")[0]
        println("🚀 [AniTube] === INICIANDO LOAD LINKS ===")
        println("🔗 [AniTube] URL do episódio: $actualUrl")

        val document = app.get(actualUrl).document
        var linksFound = false

        // 1. Extração JWPlayer (bg.mp4 e Packer)
        println("🔍 [AniTube] Procurando iframe 'bg.mp4'...")
        document.select("iframe[src*='bg.mp4']").firstOrNull()?.let { iframe ->
            val src = iframe.attr("src")
            println("🕵️ [AniTube] Iframe encontrado! SRC: $src")
            
            try {
                val headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Referer" to actualUrl
                )

                println("📥 [AniTube] Baixando conteúdo do iframe com Referer correto...")
                val response = app.get(src, headers = headers)
                val packedContent = response.text

                // Decodifica o script packer
                val decoded = decodePacked(packedContent)

                if (decoded != null) {
                    println("✅ [AniTube] Script pronto para análise regex.")
                    
                    // Busca links do Google Video
                    val videoRegex = Regex("https?://[^\\s'\"]+videoplayback[^\\s'\"]*")
                    var mp4Count = 0
                    videoRegex.findAll(decoded).forEach { match ->
                        val link = match.value
                        val quality = when {
                            link.contains("itag=37") -> 1080
                            link.contains("itag=22") -> 720
                            link.contains("itag=59") -> 480
                            link.contains("itag=18") -> 360
                            else -> 360
                        }

                        println("🎬 [AniTube] Link MP4 encontrado ($quality): ${link.substring(0, 50)}...")
                        callback(newExtractorLink(name, "JWPlayer MP4", link, ExtractorLinkType.VIDEO) {
                            this.referer = "https://api.anivideo.net/"
                            this.quality = quality
                        })
                        linksFound = true
                        mp4Count++
                    }
                    if (mp4Count == 0) println("⚠️ [AniTube] Nenhum link 'videoplayback' encontrado no script decodificado.")

                    // Busca links M3U8 no script
                    val m3u8Regex = Regex("https?://[^\\s'\"]+\\.m3u8[^\\s'\"]*")
                    m3u8Regex.findAll(decoded).forEach { match ->
                        println("📡 [AniTube] Link HLS (Packed) encontrado: ${match.value}")
                        callback(newExtractorLink(name, "AniTube (HLS)", match.value, ExtractorLinkType.M3U8) {
                            this.referer = "https://api.anivideo.net/"
                        })
                        linksFound = true
                    }
                } else {
                    println("❌ [AniTube] Falha ao decodificar (decodePacked retornou null).")
                }
            } catch (e: Exception) {
                println("💥 [AniTube] Erro na extração JWPlayer: ${e.message}")
                e.printStackTrace()
            }
        } ?: println("⚠️ [AniTube] Iframe 'bg.mp4' NÃO encontrado na página.")

        // 2. Extração Player FHD (Original)
        document.selectFirst(PLAYER_FHD)?.let { iframe ->
            val src = iframe.attr("src").takeIf { it.isNotBlank() } ?: return@let
            val m3u8Url = extractM3u8FromUrl(src) ?: src

            if (!m3u8Url.contains("bg.mp4")) {
                println("📺 [AniTube] Player FHD encontrado (não-bg): $m3u8Url")
                callback(newExtractorLink(name, "Player FHD", m3u8Url, ExtractorLinkType.M3U8) {
                    referer = "$mainUrl/"
                    quality = 1080
                })
                linksFound = true
            } else {
                println("⏭️ [AniTube] Player FHD ignorado (é duplicata bg.mp4).")
            }
        }

        // 3. Extração Player Backup (Original)
        document.selectFirst(PLAYER_BACKUP)?.let { iframe ->
            val src = iframe.attr("src").takeIf { it.isNotBlank() } ?: return@let
            val isM3u8 = src.contains("m3u8", true)
            val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

            if (!src.contains("bg.mp4")) {
                println("📺 [AniTube] Player Backup encontrado: $src")
                callback(newExtractorLink(name, "Player Backup", src, linkType) {
                    referer = "$mainUrl/"
                    quality = 720
                })
                linksFound = true
            }
        }

        // 4. Varredura Final (Original)
        if (!linksFound) {
            println("🔎 [AniTube] Nenhuma link encontrado, tentando varredura geral...")
            document.select("iframe").forEachIndexed { index, iframe ->
                val src = iframe.attr("src")
                if (src.contains("m3u8", true)) {
                    val alreadyAdded = document.selectFirst(PLAYER_FHD)?.attr("src") == src || 
                                      document.selectFirst(PLAYER_BACKUP)?.attr("src") == src ||
                                      src.contains("bg.mp4")

                    if (!alreadyAdded) {
                        println("✨ [AniTube] Iframe M3U8 extra encontrado: $src")
                        val m3u8Url = extractM3u8FromUrl(src) ?: src
                        callback(newExtractorLink(name, "Player Auto", m3u8Url, ExtractorLinkType.M3U8) {
                            referer = "$mainUrl/"
                            quality = 720
                        })
                        linksFound = true
                    }
                }
            }
        }

        println("🏁 [AniTube] Fim de loadLinks. Sucesso? $linksFound")
        return linksFound
    }
}
