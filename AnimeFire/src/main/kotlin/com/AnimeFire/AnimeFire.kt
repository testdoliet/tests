package com.AnimeFire

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element

class AnimeFire : MainAPI() {
    override var mainUrl = "https://animefire.plus"
    override var name = "AnimeFire"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie, TvType.OVA)
    override val usesWebView = false

    companion object {
        private const val SEARCH_PATH = "/pesquisar"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/animes" to "Todos os Animes",
        "$mainUrl/lancamentos" to "Lançamentos",
        "$mainUrl/mais-vistos" to "Mais Vistos",
        "$mainUrl/dublados" to "Dublados"
    )

    // ============ ESTRUTURA PARA BADGES ============
    
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
        
        // Detectar tipo de áudio do título
        val hasDub = rawTitle.contains("dublado", ignoreCase = true)
        val hasLeg = rawTitle.contains("legendado", ignoreCase = true)
        
        // Tentar extrair número do episódio
        var lastEpNumber: Int? = null
        val epSelectors = listOf(".numEp", ".episode", ".eps", ".badge", "span")
        
        for (selector in epSelectors) {
            val epText = selectFirst(selector)?.text()?.trim()
            if (epText != null) {
                val epMatch = Regex("(\\d+)").find(epText)
                if (epMatch != null) {
                    lastEpNumber = epMatch.value.toIntOrNull()
                    if (lastEpNumber != null) break
                }
            }
        }
        
        // Tentar extrair do título
        if (lastEpNumber == null) {
            val epMatch = Regex("Ep[\\s.]*(\\d+)", RegexOption.IGNORE_CASE).find(rawTitle)
            if (epMatch != null) {
                lastEpNumber = epMatch.groupValues[1].toIntOrNull()
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
            
            // BADGES IGUAL ALLWISH
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

        // 2. Extrair status do anime
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

        // 3. Extrair tipo de áudio
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

        // 8. Extrair episódios com informações de badges
        val episodeData = if (!isMovie) {
            extractEpisodesWithBadgeInfo(document, hasDub)
        } else {
            EpisodeData(emptyList(), emptyList(), null, null)
        }

        // 9. Extrair nota/score
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
                if (hasDub && episodeData.dubEpisodes.isNotEmpty()) {
                    addEpisodes(DubStatus.Dubbed, episodeData.dubEpisodes)
                }
                if (hasLeg && episodeData.subEpisodes.isNotEmpty()) {
                    addEpisodes(DubStatus.Subbed, episodeData.subEpisodes)
                }
                
                this.year = finalYear
                this.plot = plot
                this.tags = tags
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.score = score
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
                this.showStatus = showStatus
            }
        }
    }

    // ============ ESTRUTURA PARA DADOS DE EPISÓDIOS ============
    private data class EpisodeData(
        val subEpisodes: List<Episode>,
        val dubEpisodes: List<Episode>,
        val lastLegEp: Int?,
        val lastDubEp: Int?
    )

    // ============ EXTRATOR DE EPISÓDIOS ============
    
    private suspend fun extractEpisodesWithBadgeInfo(
        document: org.jsoup.nodes.Document,
        hasDub: Boolean
    ): EpisodeData {
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
                if (isLeg) {
                    if (lastLegEp == null || episodeNumber > lastLegEp!!) {
                        lastLegEp = episodeNumber
                    }
                }
                if (isDub) {
                    if (lastDubEp == null || episodeNumber > lastDubEp!!) {
                        lastDubEp = episodeNumber
                    }
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
        
        return EpisodeData(
            subEpisodes = subEpisodes.sortedBy { it.episode },
            dubEpisodes = dubEpisodes.sortedBy { it.episode },
            lastLegEp = lastLegEp,
            lastDubEp = lastDubEp
        )
    }

    // ============ EXTRATOR DE LINKS DE VÍDEO (APENAS CHAMA O EXTRACTOR EXISTENTE) ============
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Se você tem um arquivo AnimeFireExtractor.kt, chame-o aqui
        // Exemplo: return AnimeFireExtractor().extract(data, callback, subtitleCallback)
        
        // Por enquanto, vamos retornar false e você pode implementar depois
        return false
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
            val match = pattern.find(text)
            if (match != null) {
                return match.groupValues[1].toIntOrNull()
            }
        }
        return null
    }
}
