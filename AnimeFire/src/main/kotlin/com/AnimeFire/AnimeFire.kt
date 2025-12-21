package com.AnimeFire

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element

class AnimeFire : MainAPI() {
    override var mainUrl = "https://animefire.plus"
    override var name = "AnimeFire"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie, TvType.OVA)
    override val usesWebView = true

    companion object {
        private const val SEARCH_PATH = "/pesquisar"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/animes" to "Todos os Animes",
        "$mainUrl/lancamentos" to "Lançamentos",
        "$mainUrl/mais-vistos" to "Mais Vistos",
        "$mainUrl/dublados" to "Dublados"
    )

    // ============ ESTRUTURA PARA BADGES (igual AllWish) ============
    
    private fun Element.toSearchResponse(): AnimeSearchResponse? {
        val href = attr("href") ?: return null
        if (href.isBlank()) return null
        
        val titleElement = selectFirst("h3.animeTitle, .text-block h3, .animeTitle, h3") ?: return null
        val rawTitle = titleElement.text().trim()
        
        // Extrair informações do título
        val cleanTitle = rawTitle
            .replace(Regex("(?i)(dublado|legendado|todos os episódios|\\(\\d{4}\\)|\\s*-\\s*$)"), "")
            .trim()
        
        val isMovie = href.contains("/filmes/") || rawTitle.contains("filme", ignoreCase = true)
        
        // 1. Detectar tipo de áudio do título
        val hasDub = rawTitle.contains("dublado", ignoreCase = true)
        val hasLeg = rawTitle.contains("legendado", ignoreCase = true)
        
        // 2. Tentar extrair número do episódio de múltiplas fontes
        var lastEpNumber: Int? = null
        val epSelectors = listOf(".numEp", ".episode", ".eps", ".badge", "span")
        
        for (selector in epSelectors) {
            val epText = selectFirst(selector)?.text()?.trim()
            if (epText != null) {
                val epMatch = Regex("(\\d+)").find(epText)
                epMatch?.let {
                    lastEpNumber = it.value.toIntOrNull()
                    if (lastEpNumber != null) break
                }
            }
        }
        
        // 3. Tentar extrair do título
        if (lastEpNumber == null) {
            val epMatch = Regex("Ep[\\s.]*(\\d+)", RegexOption.IGNORE_CASE).find(rawTitle)
            epMatch?.let {
                lastEpNumber = it.groupValues[1].toIntOrNull()
            }
        }
        
        val sitePoster = selectFirst("img.imgAnimes, img.card-img-top, img.transitioning_src, img.owl-lazy")?.let { img ->
            when {
                img.hasAttr("data-src") -> img.attr("data-src")
                img.hasAttr("src") -> img.attr("src")
                else -> null
            }
        }

        return newAnimeSearchResponse(cleanTitle, fixUrl(href)) {
            this.posterUrl = sitePoster?.let { fixUrl(it) }
            this.type = if (isMovie) TvType.Movie else TvType.Anime
            
            // ============ BADGES IGUAL ALLWISH ============
            // Usar addDubStatus com contadores (igual AllWish faz)
            addDubStatus(
                dubExist = hasDub,
                subExist = hasLeg,
                dubEpisodes = if (hasDub) lastEpNumber else null,
                subEpisodes = if (hasLeg) lastEpNumber else null
            )
        }
    }

    // ============ PÁGINA INICIAL ============
    
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        
        val homeItems = when (request.name) {
            "Lançamentos" -> {
                document.select(".owl-carousel-home .divArticleLancamentos a.item")
                    .mapNotNull { it.toSearchResponse() }
            }
            "Todos os Animes" -> {
                document.select("div.divCardUltimosEps article.card a")
                    .mapNotNull { it.toSearchResponse() }
            }
            "Mais Vistos" -> {
                document.select(".owl-carousel-semana .divArticleLancamentos a.item")
                    .mapNotNull { it.toSearchResponse() }
            }
            "Dublados" -> {
                document.select(".owl-carousel-l_dia .divArticleLancamentos a.item")
                    .mapNotNull { it.toSearchResponse() }
            }
            else -> emptyList()
        }
        
        return newHomePageResponse(request.name, homeItems.distinctBy { it.url }, false)
    }

    // ============ BUSCA ============
    
    override suspend fun search(query: String): List<SearchResponse> {
        val formattedQuery = query
            .trim()
            .replace(Regex("\\s+"), "-")
            .lowercase()
        
        val searchUrl = "$mainUrl$SEARCH_PATH/$formattedQuery"
        val document = app.get(searchUrl).document

        return document.select("div.divCardUltimosEps article.card a")
            .mapNotNull { it.toSearchResponse() }
            .take(30)
    }

    // ============ CARREGAR DETALHES ============
    
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // 1. Extrair título
        val titleElement = document.selectFirst("h1.quicksand400, h1") 
            ?: throw ErrorLoadingException("Título não encontrado")
        val rawTitle = titleElement.text().trim()
        
        val year = Regex("\\((\\d{4})\\)").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = rawTitle.replace(Regex("\\(\\d{4}\\)"), "").trim()
        
        val isMovie = url.contains("/filmes/") || rawTitle.contains("Movie", ignoreCase = true)
        val type = if (isMovie) TvType.Movie else TvType.Anime

        // 2. Extrair status do anime (seletor: div.animeInfo:nth-child(11))
        val showStatus = if (!isMovie) {
            val animeInfoDivs = document.select("div.animeInfo")
            val statusDiv = if (animeInfoDivs.size >= 11) animeInfoDivs[10] else null
            
            val statusText = statusDiv?.select("span.spanAnimeInfo")?.firstOrNull()?.text()?.trim()
                ?: document.select("div.animeInfo:contains(Status:) span.spanAnimeInfo").firstOrNull()?.text()?.trim()
                ?: "Desconhecido"
            
            getStatus(statusText)
        } else {
            ShowStatus.Completed
        }

        // 3. Extrair tipo de áudio (seletor: div.animeInfo:nth-child(7))
        val (hasLeg, hasDub) = if (!isMovie) {
            val animeInfoDivs = document.select("div.animeInfo")
            val audioDiv = if (animeInfoDivs.size >= 7) animeInfoDivs[6] 
                          else document.select("div.animeInfo:contains(Audio:)").firstOrNull()
            
            val audioText = audioDiv?.select("span.spanAnimeInfo")?.firstOrNull()?.text()?.trim() ?: "Legendado"
            
            Pair(
                audioText.contains("Legendado", ignoreCase = true),
                audioText.contains("Dublado", ignoreCase = true)
            )
        } else {
            Pair(false, false)
        }

        // 4. Extrair poster
        val poster = document.selectFirst(".sub_animepage_img img, img[src*='/img/animes/']")?.let { img ->
            when {
                img.hasAttr("src") -> fixUrl(img.attr("src"))
                img.hasAttr("data-src") -> fixUrl(img.attr("data-src"))
                else -> null
            }
        }

        // 5. Extrair sinopse
        val plot = document.selectFirst("div.divSinopse, .sinopse, [itemprop='description']")
            ?.text()
            ?.trim()

        // 6. Extrair tags
        val tags = document.select("a.spanAnimeInfo.spanGeneros, .generos a, .tags a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .takeIf { it.isNotEmpty() }
            ?.distinct()
            ?.take(10)

        // 7. Extrair ano
        val finalYear = year ?: document.select("div.animeInfo:contains(Ano:) span.spanAnimeInfo")
            .firstOrNull()
            ?.text()
            ?.trim()
            ?.toIntOrNull()

        // 8. Extrair episódios (com os últimos números para badges)
        val (subEpisodes, dubEpisodes, lastLegEp, lastDubEp) = if (!isMovie) {
            extractEpisodesWithLastNumbers(document, hasDub)
        } else {
            Triple(emptyList(), emptyList(), null, null)
        }

        // 9. Extrair nota/score (se disponível)
        val score = document.selectFirst("div.score, .rating, [itemprop='ratingValue']")
            ?.text()
            ?.trim()
            ?.toFloatOrNull()
            ?.let { Score.from10(it) }

        // 10. Recomendações
        val recommendations = document.select(".owl-carousel-anime .divArticleLancamentos a.item")
            .mapNotNull { it.toSearchResponse() }

        return if (isMovie) {
            newMovieLoadResponse(cleanTitle, url, type, url) {
                this.year = finalYear
                this.plot = plot
                this.tags = tags
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.score = score
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
            }
        } else {
            newAnimeLoadResponse(cleanTitle, url, type) {
                // Separar episódios por tipo de áudio
                if (hasDub && dubEpisodes.isNotEmpty()) {
                    addEpisodes(DubStatus.Dubbed, dubEpisodes)
                }
                if (hasLeg && subEpisodes.isNotEmpty()) {
                    addEpisodes(DubStatus.Subbed, subEpisodes)
                }
                
                this.year = finalYear
                this.plot = plot
                this.tags = tags
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.score = score
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
                this.showStatus = showStatus
                
                // DEBUG: Log dos últimos episódios
                println("DEBUG - AnimeFire - Last Episodes: Leg=$lastLegEp, Dub=$lastDubEp")
            }
        }
    }

    // ============ EXTRATOR DE EPISÓDIOS COM ÚLTIMOS NÚMEROS ============
    
    private suspend fun extractEpisodesWithLastNumbers(
        document: org.jsoup.nodes.Document,
        hasDub: Boolean
    ): Triple<List<Episode>, List<Episode>, Int?, Int?> {
        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()
        var lastLegEp: Int? = null
        var lastDubEp: Int? = null
        
        val episodeElements = document.select("a.lEp.epT, a.lEp, .divListaEps a")
        
        episodeElements.forEachIndexed { index, element ->
            try {
                val href = element.attr("href").takeIf { it.isNotBlank() } ?: return@forEachIndexed
                val text = element.text().trim().takeIf { it.isNotBlank() } ?: return@forEachIndexed
                
                // Extrair número do episódio
                val episodeNumber = extractEpisodeNumber(text) ?: (index + 1)
                
                // Determinar tipo de áudio
                val isDub = text.contains("dublado", ignoreCase = true)
                val isLeg = text.contains("legendado", ignoreCase = true) || (!isDub && !hasDub)
                
                // Atualizar últimos episódios (para badges)
                if (isLeg && (lastLegEp == null || episodeNumber > lastLegEp!!)) {
                    lastLegEp = episodeNumber
                }
                if (isDub && (lastDubEp == null || episodeNumber > lastDubEp!!)) {
                    lastDubEp = episodeNumber
                }
                
                // Nome do episódio
                val episodeName = element.selectFirst(".ep-name, .title")?.text()?.trim()
                    ?: text.substringAfterLast("-").trim()
                    ?: "Episódio $episodeNumber"
                
                // Limpar nome
                val cleanEpisodeName = episodeName
                    .replace(Regex("(?i)\\(?dublado\\)?"), "")
                    .replace(Regex("(?i)\\(?legendado\\)?"), "")
                    .trim()
                
                // Criar episódio
                val episode = newEpisode(fixUrl(href)) {
                    this.name = cleanEpisodeName
                    this.season = 1
                    this.episode = episodeNumber
                    this.description = "Episódio $episodeNumber"
                }
                
                // Adicionar à lista correta
                if (isDub) {
                    dubEpisodes.add(episode)
                } else if (isLeg) {
                    subEpisodes.add(episode)
                }
                
            } catch (e: Exception) {
                // Ignorar erro
            }
        }
        
        return Triple(
            subEpisodes.sortedBy { it.episode },
            dubEpisodes.sortedBy { it.episode },
            lastLegEp,
            lastDubEp
        )
    }

    // ============ EXTRATOR DE LINKS DE VÍDEO ============
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val document = app.get(data).document
            
            // Extrair iframe do vídeo
            val iframe = document.selectFirst("iframe[src*='animefire'], iframe[src*='embed']")
            val iframeSrc = iframe?.attr("src") ?: return false
            
            // Carregar iframe
            val iframeDoc = app.get(fixUrl(iframeSrc)).document
            
            // Tentar encontrar vídeo
            val videoSources = iframeDoc.select("source[src], video source[src]")
            if (videoSources.isNotEmpty()) {
                videoSources.forEach { source ->
                    val videoUrl = source.attr("src")
                    val type = source.attr("type")
                    val quality = source.attr("title")?.let { 
                        Regex("(\\d+)p").find(it)?.value 
                    } ?: "Unknown"
                    
                    callback.invoke(
                        ExtractorLink(
                            name,
                            quality,
                            videoUrl,
                            referer = mainUrl,
                            quality = extractQuality(quality),
                            type = if (type.contains("m3u8")) ExtractorLinkType.HLS else ExtractorLinkType.VIDEO
                        )
                    )
                }
                true
            } else {
                // Fallback: tentar extrair de scripts
                val scriptText = iframeDoc.select("script").toString()
                val m3u8Pattern = Regex("(https?:[^\"']+\\.m3u8[^\"']*)")
                val m3u8Match = m3u8Pattern.find(scriptText)
                
                m3u8Match?.let { match ->
                    callback.invoke(
                        ExtractorLink(
                            name,
                            "HLS",
                            match.value,
                            referer = mainUrl,
                            quality = Qualities.Unknown.value,
                            type = ExtractorLinkType.HLS
                        )
                    )
                    true
                } ?: false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    private fun extractEpisodeNumber(text: String): Int? {
        val patterns = listOf(
            Regex("Epis[oó]dio\\s*(\\d+)"),
            Regex("Ep\\.?\\s*(\\d+)"),
            Regex("(\\d{1,3})\\s*-"),
            Regex("#(\\d+)"),
            Regex("\\b(\\d{1,4})\\b")
        )
        
        for (pattern in patterns) {
            pattern.find(text)?.let { match ->
                return match.groupValues[1].toIntOrNull()
            }
        }
        return null
    }
    
    private fun extractQuality(qualityStr: String): Int {
        return when {
            qualityStr.contains("1080") -> Qualities.P1080.value
            qualityStr.contains("720") -> Qualities.P720.value
            qualityStr.contains("480") -> Qualities.P480.value
            qualityStr.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}
