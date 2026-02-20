package com.dattebayo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import org.jsoup.nodes.Element

@CloudstreamPlugin
class DattebayoBRPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DattebayoBR())
    }
}

class DattebayoBR : MainAPI() {
    override var mainUrl = "https://www.dattebayo-br.com"
    override var name = "Dattebayo BR"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime)
    override val usesWebView = false

    companion object {
        // Constantes de seletores
        private const val HOME_ITEM = ".ultimosAnimesHomeItem, .ultimosEpisodiosHomeItem"
        private const val HOME_LINK = "a"
        private const val HOME_TITLE = ".ultimosAnimesHomeItemInfosNome, .ultimosEpisodiosHomeItemInfosNome"
        private const val HOME_IMG = ".ultimosAnimesHomeItemImg img, .ultimosEpisodiosHomeItemImg img"
        private const val HOME_EP_NUM = ".ultimosEpisodiosHomeItemInfosNum"
        private const val HOME_EP_TOTAL = ".ultimosAnimesHomeItemQntEps"

        // Seletores de detalhes
        private const val DETAIL_TITLE = "h1"
        private const val DETAIL_POSTER = ".aniInfosSingleCapa img"
        private const val DETAIL_SYNOPSIS = ".aniInfosSingleSinopse p"
        private const val DETAIL_GENRES = ".aniInfosSingleGeneros span"
        private const val DETAIL_STATUS = "#completed"
        private const val DETAIL_EPISODES_INFO = ".aniInfosSingleNumsItem"
        private const val EPISODE_CONTAINER = ".aniContainer"
        private const val EPISODE_ITEM = ".ultimosEpisodiosHomeItem"
        private const val EPISODE_LINK = "a"
        private const val EPISODE_TITLE_ATTR = "title"
        
        // Seletores de vídeo
        private const val VIDEO_META_TAG = "meta[itemprop=\"contentURL\"]"
        private const val VIDEO_SCRIPT_VAR = "var vid = '(.*?)'"
    }

    // Página principal
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Últimos Episódios",
        "$mainUrl/animes" to "Animes (AZ)",
        "$mainUrl/anime-dublado" to "Animes Dublados",
    )

    // === FUNÇÕES AUXILIARES ===
    private fun cleanTitle(title: String): String {
        return title.replace("(?i)\\s*ep\\s*\\d+".toRegex(), "")
            .replace("(?i)\\s*-\\s*final".toRegex(), "")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    private fun extractEpisodeNumber(title: String): Int? {
        return "ep(?:is[oó]dio)?\\s*(\\d+)".toRegex(RegexOption.IGNORE_CASE)
            .find(title)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractAnimeNameFromEpisode(episodeItem: Element): String {
        val fullTitle = episodeItem.selectFirst(EPISODE_LINK)?.attr(EPISODE_TITLE_ATTR) ?: return ""
        return fullTitle.replace("(?i)\\s+ep\\s+\\d+(\\s*-\\s*final)?".toRegex(), "").trim()
    }

    // === FUNÇÕES DE MAPEAMENTO ===
    private fun Element.toSearchResponse(): AnimeSearchResponse? {
        val link = selectFirst(HOME_LINK) ?: return null
        val href = fixUrl(link.attr("href"))
        val title = selectFirst(HOME_TITLE)?.text()?.trim() ?: return null
        val poster = selectFirst(HOME_IMG)?.attr("src")?.let { fixUrl(it) }
        val episodeNum = selectFirst(HOME_EP_NUM)

        return if (episodeNum != null) {
            // É um episódio
            val epNumber = extractEpisodeNumber(title) ?: 1
            val animeName = extractAnimeNameFromEpisode(this)
            
            // Passa o poster junto na URL para usar em load()
            val urlWithPoster = if (poster != null) "$href|poster=$poster" else href
            
            newAnimeSearchResponse(animeName, urlWithPoster, TvType.Anime) {
                this.posterUrl = poster
                addDubStatus(DubStatus.Subbed, epNumber)
            }
        } else {
            // É um anime
            newAnimeSearchResponse(cleanTitle(title), href, TvType.Anime) {
                this.posterUrl = poster
                addDubStatus(DubStatus.Subbed, null)
            }
        }
    }

    // === PÁGINA PRINCIPAL ===
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val items = document.select(HOME_ITEM).mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items.distinctBy { it.url })
    }

    // === PESQUISA ===
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()
        val searchUrl = "$mainUrl/busca?busca=${query.replace(" ", "+")}"
        val document = app.get(searchUrl).document
        return document.select(HOME_ITEM).mapNotNull { it.toSearchResponse() }
    }

    // === CARREGAR DETALHES ===
    override suspend fun load(url: String): LoadResponse {
        val (actualUrl, thumbPoster) = url.split("|poster=").let { 
            it[0] to it.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
        }

        val document = app.get(actualUrl).document
        val title = document.selectFirst(DETAIL_TITLE)?.text()?.trim() ?: "Sem título"
        
        // Poster
        val poster = thumbPoster ?: document.selectFirst(DETAIL_POSTER)?.attr("src")?.let { fixUrl(it) }
        
        // Sinopse
        val synopsis = document.selectFirst(DETAIL_SYNOPSIS)?.text()?.trim() ?: "Sinopse não disponível"
        
        // Gêneros
        val genres = document.select(DETAIL_GENRES).map { it.text() }.filter { it.isNotBlank() }
        
        // Ano e status
        var year: Int? = null
        var totalEpisodes: Int? = null
        var showStatus = ShowStatus.Unknown

        document.select(DETAIL_EPISODES_INFO).forEach { element ->
            val text = element.text()
            when {
                text.contains("Ano") -> year = text.substringAfter("Ano").trim().toIntOrNull()
                text.contains("Episódios") -> {
                    "(\\d+)/(\\d+)".toRegex().find(text)?.let {
                        totalEpisodes = it.groupValues[2].toIntOrNull()
                    }
                }
            }
        }

        // Status (Completo ou Emissão)
        if (document.selectFirst(DETAIL_STATUS)?.text()?.contains("Completo") == true) {
            showStatus = ShowStatus.Completed
        } else {
            showStatus = ShowStatus.Ongoing
        }

        // Lista de episódios
        val episodes = mutableListOf<Episode>()
        document.select(EPISODE_CONTAINER).select(EPISODE_ITEM).forEach { element ->
            val link = element.selectFirst(EPISODE_LINK) ?: return@forEach
            val episodeUrl = fixUrl(link.attr("href"))
            val episodeTitle = link.attr(EPISODE_TITLE_ATTR)
            val episodeNumber = extractEpisodeNumber(episodeTitle) ?: return@forEach
            val episodePoster = element.selectFirst("img")?.attr("src")?.let { fixUrl(it) }

            episodes.add(
                newEpisode(episodeUrl) {
                    name = "Episódio $episodeNumber"
                    episode = episodeNumber
                    posterUrl = episodePoster ?: poster
                }
            )
        }

        episodes.sortBy { it.episode }

        return newAnimeLoadResponse(cleanTitle(title), actualUrl, TvType.Anime) {
            this.posterUrl = poster
            this.year = year
            this.plot = synopsis
            this.tags = genres
            this.showStatus = showStatus
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // === CARREGAR LINKS DE VÍDEO (PARTE MAIS IMPORTANTE) ===
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 1. Pega a URL da página do episódio
        val episodePageUrl = data.split("|poster=")[0]
        
        // 2. FAZ UMA REQUISIÇÃO NOVA para a página (link fresco!)
        val document = app.get(episodePageUrl, referer = mainUrl).document
        var linksFound = false

        // 3. Tenta extrair da META TAG (mais fácil e confiável)
        val metaVideoUrl = document.selectFirst(VIDEO_META_TAG)?.attr("content")
        if (!metaVideoUrl.isNullOrBlank()) {
            callback(
                newExtractorLink(
                    source = name,
                    name = "Servidor Principal",
                    url = metaVideoUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    referer = "https://playembedapi.site/"  // Header essencial!
                    quality = 1080
                    addHeader("Range", "bytes=0-")
                    addHeader("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
                }
            )
            linksFound = true
        }

        // 4. Fallback: extrair do SCRIPT (caso a meta tag não exista)
        if (!linksFound) {
            document.select("script").forEach { script ->
                script.data().let { content ->
                    val regex = VIDEO_SCRIPT_VAR.toRegex()
                    val match = regex.find(content)
                    val scriptUrl = match?.groupValues?.get(1)
                    
                    if (!scriptUrl.isNullOrBlank()) {
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "Servidor (Script)",
                                url = scriptUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                referer = "https://playembedapi.site/"
                                quality = 720
                                addHeader("Range", "bytes=0-")
                            }
                        )
                        linksFound = true
                    }
                }
            }
        }

        // 5. Se ainda não achou, tenta os players por qualidade
        if (!linksFound) {
            val players = listOf(
                "jwContainer_0" to "SD",
                "jwContainer_1" to "HD",
                "jwContainer_2" to "FULLHD"
            )
            
            players.forEach { (containerId, qualityName) ->
                document.select("#$containerId script").forEach { script ->
                    script.data().let { content ->
                        val regex = "var vid = '(.*?)'".toRegex()
                        val match = regex.find(content)
                        val playerUrl = match?.groupValues?.get(1)
                        
                        if (!playerUrl.isNullOrBlank()) {
                            callback(
                                newExtractorLink(
                                    source = name,
                                    name = "Player $qualityName",
                                    url = playerUrl,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    referer = "https://playembedapi.site/"
                                    quality = when (qualityName) {
                                        "FULLHD" -> 1080
                                        "HD" -> 720
                                        else -> 480
                                    }
                                    addHeader("Range", "bytes=0-")
                                }
                            )
                            linksFound = true
                        }
                    }
                }
            }
        }

        return linksFound
    }
}
