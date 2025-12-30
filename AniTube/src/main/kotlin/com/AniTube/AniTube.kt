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
        
        // Seletores específicos para cada seção
        private const val ANIME_CARD = ".aniItem"
        private const val EPISODE_CARD = ".epiItem"
        private const val TITLE_SELECTOR = ".aniItemNome, .epiItemNome"
        private const val POSTER_SELECTOR = ".aniItemImg img, .epiItemImg img"
        private const val AUDIO_BADGE_SELECTOR = ".aniCC, .epiCC"
        private const val EPISODE_NUMBER_SELECTOR = ".epiItemInfos .epiItemNome"
        
        // Para página inicial - seletores das diferentes seções
        private const val LATEST_EPISODES_SECTION = ".epiContainer"
        private const val POPULAR_ANIME_SECTION = "#splide01 .aniItem"  // Animes Mais Vistos
        private const val RECENT_ANIME_SECTION = "#splide02 .aniItem"   // Animes Recentes
        
        // Para página de anime
        private const val ANIME_TITLE = "h1"
        private const val ANIME_POSTER = "#capaAnime img"
        private const val ANIME_SYNOPSIS = "#sinopse2"
        private const val ANIME_METADATA = ".boxAnimeSobre .boxAnimeSobreLinha"
        private const val EPISODE_LIST = ".pagAniListaContainer > a"
        
        // Para player
        private const val PLAYER_FHD = "#blog2 iframe"
        private const val PLAYER_BACKUP = "#blog1 iframe"
        
        private val TITLE_CLEANUP_PATTERNS = listOf(
            "(?i)\\s*–\\s*todos os epis[oó]dios".toRegex(),
            "(?i)\\s*\\(dublado\\)".toRegex(),
            "(?i)\\s*\\(legendado\\)".toRegex(),
            "(?i)\\s*dublado\\s*$".toRegex(),
            "(?i)\\s*legendado\\s*$".toRegex(),
            "(?i)\\s*-\\s*epis[oó]dio\\s*\\d+".toRegex(),
            "(?i)\\s*–\\s*Epis[oó]dio\\s*\\d+".toRegex(),
            "(?i)\\s*epis[oó]dio\\s*\\d+".toRegex(),
            "(?i)\\s*Ep\\.\\s*\\d+".toRegex()
        )
    }

    // Mapeamento de gêneros
    private val genresMap = mapOf(
        "Ação" to "acao",
        "Artes Marciais" to "artes%20marciais",
        "Aventura" to "aventura",
        "Comédia" to "comedia",
        "Comédia Romântica" to "comedia%20romantica",
        "Drama" to "drama",
        "Ecchi" to "ecchi",
        "Esporte" to "esporte",
        "Fantasia" to "fantasia",
        "Ficção Científica" to "ficcao%20cientifica",
        "Jogos" to "jogos",
        "Magia" to "magia",
        "Mecha" to "mecha",
        "Mistério" to "misterio",
        "Musical" to "musical",
        "Romance" to "romance",
        "Seinen" to "seinen",
        "Shoujo-ai" to "shoujo%20ai",
        "Shounen" to "shounen",
        "Slice Of Life" to "slice%20of%20life",
        "Sobrenatural" to "sobrenatural",
        "Superpoder" to "superpoder",
        "Terror" to "terror",
        "Vida Escolar" to "vida%20escolar"
    )

    override val mainPage = mainPageOf(
        "$mainUrl" to "Últimos Episódios",
        "$mainUrl" to "Animes Mais Vistos",  // Renomeado de "Mais Populares"
        "$mainUrl" to "Animes Recentes",     // Já estava certo
        *genresMap.map { (genre, slug) -> "$mainUrl/?s=$slug" to genre }.toTypedArray()
    )

    private fun cleanTitle(dirtyTitle: String): String {
        var clean = dirtyTitle.trim()
        TITLE_CLEANUP_PATTERNS.forEach { pattern ->
            clean = pattern.replace(clean, "")
        }
        return clean.replace(Regex("\\s+"), " ").trim().ifBlank { dirtyTitle }
    }
    
    private fun extractEpisodeNumber(title: String): Int? {
        val patterns = listOf(
            "Epis[oó]dio\\s*(\\d+)".toRegex(RegexOption.IGNORE_CASE),
            "Ep\\.?\\s*(\\d+)".toRegex(RegexOption.IGNORE_CASE),
            "E(\\d+)".toRegex(RegexOption.IGNORE_CASE),
            "\\b(\\d+)\\b".toRegex()
        )
        
        return patterns.firstNotNullOfOrNull { pattern ->
            pattern.find(title)?.groupValues?.get(1)?.toIntOrNull()
        }
    }
    
    private fun extractAnimeTitleFromEpisode(episodeTitle: String): String {
        // Remove padrões de episódio do título
        var clean = episodeTitle
            .replace("(?i)Epis[oó]dio\\s*\\d+".toRegex(), "")
            .replace("(?i)Ep\\.?\\s*\\d+".toRegex(), "")
            .replace("(?i)E\\d+".toRegex(), "")
            .replace("–", "")
            .replace("-", "")
            .trim()
        
        // Remove qualquer número solto no final
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
                URLDecoder.decode(
                    url.substringAfter("d=").substringBefore("&"),
                    "UTF-8"
                )
            } catch (e: Exception) {
                null
            }
        } else {
            url
        }
    }
    
    // Método específico para episódios (estilo AnimesDigital)
    private fun Element.toEpisodeSearchResponse(): AnimeSearchResponse? {
        val href = selectFirst("a")?.attr("href") ?: return null
        if (!href.contains("/video/")) return null
        
        // Extrair título do episódio
        val episodeTitle = selectFirst(EPISODE_NUMBER_SELECTOR)?.text()?.trim() ?: return null
        
        // Extrair título do anime e número do episódio
        val animeTitle = extractAnimeTitleFromEpisode(episodeTitle)
        val episodeNumber = extractEpisodeNumber(episodeTitle) ?: 1
        
        val posterUrl = selectFirst(POSTER_SELECTOR)?.attr("src")?.let { fixUrl(it) }
        val isDubbed = isDubbed(this)
        
        // Formato igual ao AnimesDigital: "Anime - Episódio X"
        val episodeText = if (isDubbed) "Dublado Episódio $episodeNumber" else "Legendado Episódio $episodeNumber"
        val displayName = "$animeTitle - $episodeText"
        
        return newAnimeSearchResponse(displayName, fixUrl(href)) {
            this.posterUrl = posterUrl
            this.type = TvType.Anime
            
            // CORREÇÃO: Não podemos acessar 'episode' diretamente no AnimeSearchResponse
            // Em vez disso, formatamos o nome para incluir o episódio
            // O CloudStream vai detectar automaticamente "Episódio X" no nome
            
            // Usando o mesmo padrão do AnimesDigital
            addDubStatus(dubExist = isDubbed, subExist = !isDubbed)
        }
    }
    
    // Método para animes (sem episódios específicos)
    private fun Element.toAnimeSearchResponse(): AnimeSearchResponse? {
        val href = selectFirst("a")?.attr("href") ?: return null
        // Não filtrar por /video/ aqui, pois pode ser página de anime completo
        
        val rawTitle = selectFirst(TITLE_SELECTOR)?.text()?.trim() ?: return null
        val cleanedTitle = cleanTitle(rawTitle).ifBlank { return null }
        
        val posterUrl = selectFirst(POSTER_SELECTOR)?.attr("src")?.let { fixUrl(it) }
        val isDubbed = isDubbed(this)
        
        return newAnimeSearchResponse(cleanedTitle, fixUrl(href)) {
            this.posterUrl = posterUrl
            this.type = TvType.Anime
            
            addDubStatus(dubExist = isDubbed, subExist = !isDubbed)
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val baseUrl = request.data
        
        // Se for uma página de gênero
        if (baseUrl.contains("/?s=")) {
            val url = if (page > 1) {
                baseUrl.replace("/?s=", "/page/$page/?s=")
            } else {
                baseUrl
            }
            
            val document = app.get(url).document
            
            val allItems = document.select("$ANIME_CARD, $EPISODE_CARD")
                .mapNotNull { it.toAnimeSearchResponse() }
                .distinctBy { it.url }
            
            return newHomePageResponse(request.name, allItems, hasNext = true)
        }
        
        // Para página inicial (primeira página)
        val document = app.get(baseUrl).document
        
        return when (request.name) {
            "Últimos Episódios" -> {
                // Extrair episódios da seção específica - usar estilo AnimesDigital
                val episodeElements = document.select("$LATEST_EPISODES_SECTION $EPISODE_CARD")
                val items = episodeElements
                    .mapNotNull { it.toEpisodeSearchResponse() }
                    .distinctBy { it.url }
                
                // Para Últimos Episódios, usar layout horizontal como AnimesDigital
                newHomePageResponse(
                    list = HomePageList(
                        name = request.name,
                        list = items,
                        isHorizontalImages = true
                    ),
                    hasNext = false
                )
            }
            "Animes Mais Vistos" -> {
                // Extrair animes populares do primeiro carousel
                val items = document.select(POPULAR_ANIME_SECTION)
                    .mapNotNull { it.toAnimeSearchResponse() }
                    .distinctBy { it.url }
                
                newHomePageResponse(
                    list = HomePageList(
                        name = request.name,
                        list = items,
                        isHorizontalImages = true  // Também horizontal
                    ),
                    hasNext = false
                )
            }
            "Animes Recentes" -> {
                // Extrair animes recentes do segundo carousel
                val items = document.select(RECENT_ANIME_SECTION)
                    .mapNotNull { it.toAnimeSearchResponse() }
                    .distinctBy { it.url }
                
                newHomePageResponse(
                    list = HomePageList(
                        name = request.name,
                        list = items,
                        isHorizontalImages = true  // Também horizontal
                    ),
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
                // Tentar identificar se é episódio ou anime completo
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
        val document = app.get(url).document
        
        val rawTitle = document.selectFirst(ANIME_TITLE)?.text()?.trim() ?: "Sem Título"
        val title = cleanTitle(rawTitle)
        val poster = document.selectFirst(ANIME_POSTER)?.attr("src")?.let { fixUrl(it) }
        val synopsis = document.selectFirst(ANIME_SYNOPSIS)?.text()?.trim() ?: "Sinopse não disponível."
        
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
            val episodeNumber = extractEpisodeNumber(episodeTitle) ?: 1
            
            newEpisode(episodeUrl) {
                this.name = episodeTitle
                this.episode = episodeNumber
                this.posterUrl = poster
            }
        }
        
        val allEpisodes = if (episodesList.isEmpty() && url.contains("/video/")) {
            // Se for uma página de episódio individual
            val episodeNumber = extractEpisodeNumber(rawTitle) ?: 1
            listOf(newEpisode(url) {
                this.name = rawTitle
                this.episode = episodeNumber
                this.posterUrl = poster
            })
        } else {
            episodesList
        }
        
        val sortedEpisodes = allEpisodes.sortedBy { it.episode }
        val showStatus = if (episodes != null && sortedEpisodes.size >= episodes) ShowStatus.Completed else ShowStatus.Ongoing
        
        return newAnimeLoadResponse(title, url, TvType.Anime) {
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
        val document = app.get(data).document
        
        // Player FHD (blog2)
        document.selectFirst(PLAYER_FHD)?.let { iframe ->
            val src = iframe.attr("src").takeIf { it.isNotBlank() } ?: return@let
            val m3u8Url = extractM3u8FromUrl(src) ?: src
            
            val link = newExtractorLink(
                source = name,
                name = "1080p",
                url = m3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "$mainUrl/"
                this.quality = 1080
            }
            
            callback(link)
            return true
        }
        
        // Player backup (blog1)
        document.selectFirst(PLAYER_BACKUP)?.let { iframe ->
            val src = iframe.attr("src").takeIf { it.isNotBlank() } ?: return@let
            val isM3u8 = src.contains("m3u8", true)
            
            val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            
            val link = newExtractorLink(
                source = name,
                name = "Backup",
                url = src,
                type = linkType
            ) {
                this.referer = "$mainUrl/"
                this.quality = 720
            }
            
            callback(link)
            return true
        }
        
        // Qualquer iframe com m3u8
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.contains("m3u8", true)) {
                val m3u8Url = extractM3u8FromUrl(src) ?: src
                
                val link = newExtractorLink(
                    source = name,
                    name = "Auto",
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = 720
                }
                
                callback(link)
                return true
            }
        }
        
        return false
    }
}
