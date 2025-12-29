package com.AniTube

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
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
        // URLs
        private const val SEARCH_PATH = "/?s="
        private const val DUBLADOS_PATH = "/lista-de-animes-dublados-online"
        private const val LEGENDADOS_PATH = "/lista-de-animes-legendados-online"
        
        // Seletores CSS
        private const val ANIME_CARD = ".aniItem"
        private const val EPISODE_CARD = ".epiItem"
        private const val TITLE_SELECTOR = ".aniItemNome, .epiItemNome"
        private const val POSTER_SELECTOR = ".aniItemImg img, .epiItemImg img"
        private const val AUDIO_BADGE_SELECTOR = ".aniCC, .epiCC"
        private const val ANIME_LINK_SELECTOR = ".aniItem > a"
        private const val EPISODE_LINK_SELECTOR = ".epiItem > a"
        
        // Página do anime
        private const val ANIME_TITLE = "h1"
        private const val ANIME_POSTER = "#capaAnime img"
        private const val ANIME_SYNOPSIS = "#sinopse2"
        private const val ANIME_METADATA = ".boxAnimeSobre .boxAnimeSobreLinha"
        private const val EPISODE_LIST = ".pagAniListaContainer > a"
        
        // Página do episódio
        private const val PLAYER_FHD = "#blog2 iframe"
        private const val PLAYER_BACKUP = "#blog1 iframe"
        
        // Limpeza de títulos
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

    override val mainPage = mainPageOf(
        "https://www.anitube.news" to "Página Inicial",
        "$mainUrl$DUBLADOS_PATH" to "Animes Dublados",
        "$mainUrl$LEGENDADOS_PATH" to "Animes Legendados",
        "$mainUrl/?s=acao" to "Ação",
        "$mainUrl/?s=aventura" to "Aventura",
        "$mainUrl/?s=comedia" to "Comédia",
        "$mainUrl/?s=drama" to "Drama",
        "$mainUrl/?s=fantasia" to "Fantasia",
        "$mainUrl/?s=romance" to "Romance",
        "$mainUrl/?s=shounen" to "Shounen",
        "$mainUrl/?s=slice+of+life" to "Slice of Life",
        "$mainUrl/?s=sobrenatural" to "Sobrenatural"
    )

    // ============ FUNÇÕES AUXILIARES ============
    
    private fun cleanTitle(dirtyTitle: String): String {
        var clean = dirtyTitle.trim()
        TITLE_CLEANUP_PATTERNS.forEach { pattern ->
            clean = pattern.replace(clean, "")
        }
        clean = clean.replace(Regex("\\s+"), " ").trim()
        return if (clean.isBlank()) dirtyTitle else clean
    }
    
    private fun extractEpisodeNumber(title: String): Int? {
        val patterns = listOf(
            "Epis[oó]dio\\s*(\\d+)".toRegex(RegexOption.IGNORE_CASE),
            "Ep\\.?\\s*(\\d+)".toRegex(RegexOption.IGNORE_CASE),
            "E(\\d+)".toRegex(RegexOption.IGNORE_CASE),
            "(\\d+)\\s*$".toRegex()
        )
        
        patterns.forEach { pattern ->
            pattern.find(title)?.groupValues?.get(1)?.toIntOrNull()?.let {
                return it
            }
        }
        
        return null
    }
    
    private fun isDubbed(element: Element): Boolean {
        val badge = element.selectFirst(AUDIO_BADGE_SELECTOR)
        return badge?.text()?.contains("Dublado", true) ?: false
    }
    
    private fun getAudioType(element: Element): String {
        val badge = element.selectFirst(AUDIO_BADGE_SELECTOR)
        return badge?.text()?.trim() ?: "Legendado"
    }
    
    private fun extractM3u8FromUrl(url: String): String? {
        return try {
            if (url.contains("d=")) {
                // Formato: https://api.anivideo.net/videohls.php?d=URL_ENCODED
                val encoded = url.substringAfter("d=").substringBefore("&")
                URLDecoder.decode(encoded, "UTF-8")
            } else {
                url
            }
        } catch (e: Exception) {
            null
        }
    }
    
    private fun Element.toAnimeSearchResponse(): AnimeSearchResponse? {
        val href = this.selectFirst("a")?.attr("href") ?: return null
        
        // Verificar se é uma página de anime válida
        if (!href.contains("/video/") && !href.contains("anitube.news")) {
            return null
        }
        
        val titleElement = selectFirst(TITLE_SELECTOR)
        val rawTitle = titleElement?.text()?.trim() ?: return null
        val cleanedTitle = cleanTitle(rawTitle)
        
        if (cleanedTitle.isBlank()) return null
        
        val posterUrl = selectFirst(POSTER_SELECTOR)?.attr("src")?.let { fixUrl(it) }
        val isDubbed = isDubbed(this)
        
        return newAnimeSearchResponse(cleanedTitle, fixUrl(href)) {
            this.posterUrl = posterUrl
            this.type = TvType.Anime
            
            if (isDubbed) {
                addDubStatus(dubExist = true, subExist = false)
            } else {
                addDubStatus(dubExist = false, subExist = true)
            }
        }
    }

    // ============ PÁGINA INICIAL ============
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page > 1) {
            if (request.data.contains("?")) {
                "${request.data}&page=$page"
            } else {
                "${request.data}page/$page/"
            }
        } else {
            request.data
        }
        
        val document = app.get(url).document
        
        // Combinar animes e episódios da página inicial
        val allItems = mutableListOf<SearchResponse>()
        
        // Animes em destaque
        val animeCards = document.select(ANIME_CARD)
        animeCards.forEach { card ->
            card.toAnimeSearchResponse()?.let { allItems.add(it) }
        }
        
        // Últimos episódios (apenas na página inicial)
        if (request.data == mainUrl || page == 1) {
            val episodeCards = document.select(EPISODE_CARD)
            episodeCards.forEach { card ->
                card.toAnimeSearchResponse()?.let { allItems.add(it) }
            }
        }
        
        // Remover duplicados por URL
        val uniqueItems = allItems.distinctBy { it.url }
        
        return newHomePageResponse(request.name, uniqueItems, hasNext = true)
    }

    // ============ BUSCA ============
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()
        
        val searchUrl = "$mainUrl$SEARCH_PATH${query.replace(" ", "+")}"
        val document = app.get(searchUrl).document
        
        return document.select("$ANIME_CARD, $EPISODE_CARD")
            .mapNotNull { it.toAnimeSearchResponse() }
            .distinctBy { it.url }
    }

    // ============ CARREGAR ANIME ============
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        // Título
        val titleElement = document.selectFirst(ANIME_TITLE)
        val rawTitle = titleElement?.text()?.trim() ?: "Sem Título"
        val title = cleanTitle(rawTitle)
        
        // Poster
        val poster = document.selectFirst(ANIME_POSTER)?.attr("src")?.let { fixUrl(it) }
        
        // Sinopse
        val synopsis = document.selectFirst(ANIME_SYNOPSIS)?.text()?.trim()
            ?: "Sinopse não disponível."
        
        // Metadados
        val metadata = document.select(ANIME_METADATA)
        var year: Int? = null
        var episodes: Int? = null
        var genres: List<String> = emptyList()
        var audioType = ""
        var author = ""
        
        metadata.forEach { element ->
            val text = element.text()
            when {
                text.contains("Gênero:", true) -> {
                    genres = text.substringAfter("Gênero:").trim().split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                }
                text.contains("Ano:", true) -> {
                    year = text.substringAfter("Ano:").trim().toIntOrNull()
                }
                text.contains("Episódios:", true) -> {
                    episodes = text.substringAfter("Episódios:").trim().toIntOrNull()
                }
                text.contains("Tipo de Episódio:", true) -> {
                    audioType = text.substringAfter("Tipo de Episódio:").trim()
                }
                text.contains("Autor:", true) -> {
                    author = text.substringAfter("Autor:").trim()
                }
            }
        }
        
        // Detectar áudio baseado no título e metadados
        val isDubbed = rawTitle.contains("dublado", true) || 
                      audioType.contains("dublado", true) ||
                      url.contains("dublado", true)
        
        // Lista de episódios
        val episodesList = document.select(EPISODE_LIST).mapNotNull { element ->
            val episodeTitle = element.text().trim()
            val episodeUrl = element.attr("href")
            
            // Extrair número do episódio
            val episodeNumber = extractEpisodeNumber(episodeTitle) ?: 1
            
            newEpisode(episodeUrl) {
                this.name = episodeTitle
                this.episode = episodeNumber
                this.posterUrl = poster
            }
        }
        
        // Se não encontrou episódios na lista, verificar se é uma página de episódio único
        val allEpisodes = if (episodesList.isEmpty() && url.contains("/video/")) {
            // É uma página de episódio único
            listOf(
                newEpisode(url) {
                    this.name = rawTitle
                    this.episode = 1
                    this.posterUrl = poster
                }
            )
        } else {
            episodesList
        }
        
        // Ordenar episódios
        val sortedEpisodes = allEpisodes.sortedBy { it.episode }
        
        // Status (inferido)
        val showStatus = if (episodes != null && sortedEpisodes.size >= episodes) {
            ShowStatus.Completed
        } else {
            ShowStatus.Ongoing
        }
        
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.year = year
            this.plot = synopsis
            this.tags = genres
            this.showStatus = showStatus
            
            if (author.isNotBlank()) {
                this.actors = listOf(Actor(author))  // CORRIGIDO: Actor em vez de ActorData
            }
            
            if (sortedEpisodes.isNotEmpty()) {
                val dubStatus = if (isDubbed) DubStatus.Dubbed else DubStatus.Subbed
                addEpisodes(dubStatus, sortedEpisodes)
            }
        }
    }

    // ============ CARREGAR LINKS (VÍDEO) ============
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        // Tentar primeiro o player FHD (blog2)
        val fhdIframe = document.selectFirst(PLAYER_FHD)
        fhdIframe?.let { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                val m3u8Url = extractM3u8FromUrl(src) ?: src
                
                val link = newExtractorLink(
                    source = name,
                    name = "1080p",
                    url = m3u8Url,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.P1080.value
                    this.isM3u8 = m3u8Url.contains("m3u8", true)
                }
                
                callback(link)
                return true
            }
        }
        
        // Fallback para player 1 (blog1)
        val backupIframe = document.selectFirst(PLAYER_BACKUP)
        backupIframe?.let { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                val link = newExtractorLink(
                    source = name,
                    name = "Backup",
                    url = src,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.P720.value
                    this.isM3u8 = src.contains("m3u8", true)
                }
                
                callback(link)
                return true
            }
        }
        
        // Procurar qualquer iframe com m3u8
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.contains("m3u8", true)) {
                val m3u8Url = extractM3u8FromUrl(src) ?: src
                
                val link = newExtractorLink(
                    source = name,
                    name = "Auto",
                    url = m3u8Url,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                    this.isM3u8 = true
                }
                
                callback(link)
                return true
            }
        }
        
        return false
    }
}
