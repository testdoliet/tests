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
        // Seletores
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
        
        // Seletores de Player (Atualizados)
        private const val PLAYER_CONTAINERS = "#blog1 iframe, #blog2 iframe, .player iframe, iframe[src*='anivideo']"
    }

    // ==========================================
    // DECODER PACKER (Otimizado)
    // ==========================================
    private fun decodePacked(packed: String): String? {
        return try {
            val regex = "eval\\(function\\(p,a,c,k,e,d\\).*?\\}\\('(.*?)',(\\d+),(\\d+),'(.*?)'\\.split\\('\\|'\\)".toRegex()
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

            val dict = mutableMapOf<String, String>()
            for (i in 0 until count) {
                val key = encodeBase(i)
                val value = keywords.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: key
                dict[key] = value
            }

            payload.replace(Regex("\\b\\w+\\b")) { dict[it.value] ?: it.value }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ... (Mantendo os mapas de gêneros e configurações de página iguais) ...
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

    // ... (Helpers de limpeza de título e extração de episódios iguais ao anterior) ...
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
        return element.selectFirst(AUDIO_BADGE_SELECTOR)?.text()?.contains("Dublado", true) ?: false
    }

    // ... (Helpers de conversão Element -> SearchResponse iguais ao anterior) ...
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

    // ... (getMainPage, search, load iguais ao anterior) ...
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
                val items = document.select("$LATEST_EPISODES_SECTION $EPISODE_CARD")
                    .mapNotNull { it.toEpisodeSearchResponse() }.distinctBy { it.url }
                newHomePageResponse(HomePageList(request.name, items, isHorizontalImages = true), false)
            }
            "Animes Mais Vistos" -> {
                // ... (Lógica simplificada para brevidade, igual ao seu código anterior) ...
                val slides = document.select("#splide01 .splide__slide:not(.splide__slide--clone) .aniItem")
                val items = slides.mapNotNull { it.toAnimeSearchResponse() }.take(10)
                newHomePageResponse(HomePageList(request.name, items, isHorizontalImages = false), false)
            }
            "Animes Recentes" -> {
                val slides = document.select("#splide02 .splide__slide:not(.splide__slide--clone) .aniItem")
                val items = slides.mapNotNull { it.toAnimeSearchResponse() }.take(10)
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
        
        // Metadata extraction
        var year: Int? = null
        var episodes: Int? = null
        var genres = emptyList<String>()
        var audioType = ""
        document.select(ANIME_METADATA).forEach { 
            val t = it.text()
            if (t.contains("Gênero:", true)) genres = t.substringAfter("Gênero:").split(",").map { g -> g.trim() }
            if (t.contains("Ano:", true)) year = t.substringAfter("Ano:").trim().toIntOrNull()
            if (t.contains("Episódios:", true)) episodes = t.substringAfter("Episódios:").trim().toIntOrNull()
            if (t.contains("Tipo de Episódio:", true)) audioType = t.substringAfter("Tipo de Episódio:").trim()
        }
        val isDubbed = rawTitle.contains("dublado", true) || audioType.contains("dublado", true)

        val episodesList = document.select(EPISODE_LIST).mapNotNull { element ->
            val epUrl = element.attr("href")
            val epNum = extractEpisodeNumber(element.text()) ?: 1
            newEpisode(epUrl) {
                this.name = "Episódio $epNum"
                this.episode = epNum
                this.posterUrl = poster
            }
        }
        val finalEpisodes = if (episodesList.isEmpty() && actualUrl.contains("/video/")) {
            listOf(newEpisode(actualUrl) { this.name = "Episódio $episodeNumber"; this.episode = episodeNumber; this.posterUrl = poster })
        } else episodesList

        return newAnimeLoadResponse(title, actualUrl, TvType.Anime) {
            this.posterUrl = poster
            this.year = year
            this.plot = synopsis
            this.tags = genres
            this.showStatus = if (episodes != null && finalEpisodes.size >= episodes!!) ShowStatus.Completed else ShowStatus.Ongoing
            if (finalEpisodes.isNotEmpty()) addEpisodes(if (isDubbed) DubStatus.Dubbed else DubStatus.Subbed, finalEpisodes.sortedBy { it.episode })
        }
    }

    // ==========================================
    // EXTRAÇÃO DE LINKS (CORRIGIDA)
    // ==========================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val actualUrl = data.split("|poster=")[0]
        val document = app.get(actualUrl).document
        var linksFound = false

        // Lista de todos os iframes potenciais
        val iframes = document.select(PLAYER_CONTAINERS)
        
        // Para cada iframe encontrado
        iframes.forEach { iframe ->
            val src = iframe.attr("src").takeIf { it.isNotBlank() }?.let { fixUrl(it) } ?: return@forEach
            
            // Caso 1: Iframe já é um m3u8 direto ou mp4
            if (src.contains(".m3u8") || src.contains(".mp4")) {
                 callback(newExtractorLink(name, "Player Direto", src, ExtractorLinkType.M3U8) {
                    referer = "$mainUrl/"
                    quality = 720
                })
                linksFound = true
                return@forEach
            }

            // Caso 2: Processar o conteúdo do iframe (Anivideo/Blogger/etc)
            try {
                // Importante: Passar o Referer da página do anime
                val response = app.get(src, headers = mapOf("Referer" to actualUrl))
                val content = response.text
                val finalUrl = response.url // A URL final após redirecionamentos

                // A. Tentar decodificar Packer (JWPlayer oculto)
                if (content.contains("eval(function(p,a,c,k,e,d)")) {
                    val unpacked = decodePacked(content)
                    if (unpacked != null) {
                        // Procurar links videoplayback (Google/YouTube)
                        val videoRegex = Regex("https?://[^\\s'\"]+videoplayback[^\\s'\"]*")
                        videoRegex.findAll(unpacked).forEach { match ->
                            val link = match.value
                            val quality = when {
                                link.contains("itag=37") -> 1080
                                link.contains("itag=22") -> 720
                                link.contains("itag=59") -> 480
                                else -> 360
                            }
                            callback(newExtractorLink(name, "AniTube Player (G)", link, ExtractorLinkType.VIDEO) {
                                referer = "https://api.anivideo.net/"
                                this.quality = quality
                            })
                            linksFound = true
                        }
                        
                        // Procurar links M3U8 dentro do descompactado
                        val m3u8Regex = Regex("https?://[^\\s'\"]+\\.m3u8[^\\s'\"]*")
                        m3u8Regex.findAll(unpacked).forEach { match ->
                             callback(newExtractorLink(name, "AniTube Player (HLS)", match.value, ExtractorLinkType.M3U8) {
                                referer = "https://api.anivideo.net/"
                            })
                            linksFound = true
                        }
                    }
                }

                // B. Tentar extrair M3U8 direto do HTML do iframe (sem packer)
                if (content.contains(".m3u8")) {
                     val m3u8Regex = Regex("https?://[^\\s'\"]+\\.m3u8[^\\s'\"]*")
                     m3u8Regex.findAll(content).forEach { match ->
                         // Filtra links internos de css/js que não são vídeo
                         if (!match.value.contains(".js") && !match.value.contains(".css")) {
                             callback(newExtractorLink(name, "Player Auto", match.value, ExtractorLinkType.M3U8) {
                                referer = finalUrl
                            })
                            linksFound = true
                         }
                     }
                }

            } catch (e: Exception) {
                // Falha ao carregar esse iframe específico, continua para o próximo
            }
        }

        return linksFound
    }
}
