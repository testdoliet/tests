package com.AnimeFire

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element

class AnimeFire : MainAPI() {
    override var mainUrl = "https://animefire.io"
    override var name = "AnimeFire"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie, TvType.OVA)
    override val usesWebView = true

    companion object {
        private const val SEARCH_PATH = "/pesquisar"
    }

    // ============ PÁGINA INICIAL ============
    override val mainPage = mainPageOf(
        "$mainUrl" to "Lançamentos",
        "$mainUrl" to "Destaques da Semana",
        "$mainUrl" to "Últimos Animes Adicionados",
        "$mainUrl" to "Últimos Episódios Adicionados"
    )

    // ============ FUNÇÃO AUXILIAR DE BUSCA ============
    
    private fun Element.toSearchResponse(): AnimeSearchResponse? {
        val href = attr("href") ?: return null
        if (href.isBlank()) return null
        
        val titleElement = selectFirst("h3.animeTitle, .text-block h3, .animeTitle, h3") ?: return null
        val rawTitle = titleElement.text().trim()
        
        // Limpar título
        val cleanTitle = rawTitle
            .replace(Regex("(?i)(dublado|legendado|todos os episódios|\\(\\d{4}\\)|\\s*-\\s*$)"), "")
            .trim()
        
        val isMovie = href.contains("/filmes/") || rawTitle.contains("filme", ignoreCase = true)
        
        // Extrair tipo de áudio do título
        val hasDub = rawTitle.contains("dublado", ignoreCase = true)
        val hasLeg = rawTitle.contains("legendado", ignoreCase = true)
        
        // Extrair número do episódio se disponível
        val epNumber = selectFirst(".numEp")?.text()?.toIntOrNull()
        
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
            
            // CORREÇÃO: Usar addDubStatus corretamente
            if (hasDub || hasLeg) {
                // Para o AnimeSearchResponse, a assinatura correta é:
                // fun addDubStatus(isDub: Boolean, episodes: Int? = null)
                // OU
                // fun addDubStatus(dubExist: Boolean, subExist: Boolean, dubEpisodes: Int? = null, subEpisodes: Int? = null)
                
                // Vou usar a segunda opção que permite mostrar ambos
                if (hasDub && hasLeg) {
                    // Se tem ambos, mostrar os dois contadores
                    addDubStatus(
                        dubExist = true,
                        subExist = true,
                        dubEpisodes = if (epNumber != null) epNumber else null,
                        subEpisodes = if (epNumber != null) epNumber else null
                    )
                } else if (hasDub) {
                    // Só tem dublado
                    addDubStatus(
                        dubExist = true,
                        subExist = false,
                        dubEpisodes = if (epNumber != null) epNumber else null
                    )
                } else if (hasLeg) {
                    // Só tem legendado
                    addDubStatus(
                        dubExist = false,
                        subExist = true,
                        subEpisodes = if (epNumber != null) epNumber else null
                    )
                }
            }
        }
    }

    // ============ GET MAIN PAGE ============
    
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        
        val homeItems = when (request.name) {
            "Lançamentos" -> {
                document.select(".owl-carousel-home .divArticleLancamentos a.item")
                    .mapNotNull { it.toSearchResponse() }
            }
            "Destaques da Semana" -> {
                document.select(".owl-carousel-semana .divArticleLancamentos a.item")
                    .mapNotNull { it.toSearchResponse() }
            }
            "Últimos Animes Adicionados" -> {
                document.select(".owl-carousel-l_dia .divArticleLancamentos a.item")
                    .mapNotNull { it.toSearchResponse() }
            }
            "Últimos Episódios Adicionados" -> {
                document.select(".divCardUltimosEpsHome article.card a")
                    .mapNotNull { it.toSearchResponse() }
            }
            else -> emptyList()
        }
        
        return newHomePageResponse(request.name, homeItems.distinctBy { it.url }, false)
    }

    // ============ SEARCH ============
    
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

    // ============ LOAD PRINCIPAL ============
    
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

        // 2. Extrair status (seletor: div.animeInfo:nth-child(11))
        val statusText = if (!isMovie) {
            val animeInfoDivs = document.select("div.animeInfo")
            val statusDiv = if (animeInfoDivs.size >= 11) animeInfoDivs[10] else null
            
            statusDiv?.select("span.spanAnimeInfo")?.firstOrNull()?.text()?.trim()
                ?: document.select("div.animeInfo:contains(Status:) span.spanAnimeInfo").firstOrNull()?.text()?.trim()
                ?: "Desconhecido"
        } else {
            null
        }
        
        val showStatus = if (!isMovie) getStatus(statusText) else null

        // 3. Extrair áudio disponível (seletor: div.animeInfo:nth-child(7))
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

        // 8. Extrair episódios e pegar o ÚLTIMO de cada tipo
        val (lastLegEp, lastDubEp, allEpisodes) = if (!isMovie) {
            extractEpisodesWithLastNumbers(document, hasDub)
        } else {
            Triple(null, null, Pair(emptyList(), emptyList()))
        }

        // 9. Recomendações
        val recommendations = document.select(".owl-carousel-anime .divArticleLancamentos a.item")
            .mapNotNull { it.toSearchResponse() }

        return if (isMovie) {
            newMovieLoadResponse(cleanTitle, url, type, url) {
                this.year = finalYear
                this.plot = plot
                this.tags = tags
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
            }
        } else {
            newAnimeLoadResponse(cleanTitle, url, type) {
                // Separar episódios
                if (hasDub && allEpisodes.second.isNotEmpty()) {
                    addEpisodes(DubStatus.Dubbed, allEpisodes.second)
                }
                if (hasLeg && allEpisodes.first.isNotEmpty()) {
                    addEpisodes(DubStatus.Subbed, allEpisodes.first)
                }
                
                this.year = finalYear
                this.plot = plot
                this.tags = tags
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
                
                // CORREÇÃO: Para AnimeLoadResponse, usar showStatus corretamente
                if (showStatus != null) {
                    this.showStatus = showStatus
                }
            }
        }
    }

    // ============ EXTRATOR DE EPISÓDIOS QUE RETORNA OS ÚLTIMOS NÚMEROS ============
    
    private suspend fun extractEpisodesWithLastNumbers(
        document: org.jsoup.nodes.Document,
        hasDub: Boolean
    ): Triple<Int?, Int?, Pair<List<Episode>, List<Episode>>> {
        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()
        var lastLegEp: Int? = null
        var lastDubEp: Int? = null
        
        val episodeElements = document.select("a.lEp.epT, a.lEp, .divListaEps a")
        
        episodeElements.forEachIndexed { index, element ->
            try {
                val href = element.attr("href").takeIf { it.isNotBlank() } ?: return@forEachIndexed
                val text = element.text().trim().takeIf { it.isNotBlank() } ?: return@forEachIndexed
                
                val episodeNumber = extractEpisodeNumber(text) ?: (index + 1)
                
                // Determinar se é dub ou leg
                val isDub = text.contains("dublado", ignoreCase = true)
                val isLeg = text.contains("legendado", ignoreCase = true) || (!isDub && !hasDub)
                
                // Atualizar últimos episódios
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
            lastLegEp,
            lastDubEp,
            Pair(
                subEpisodes.sortedBy { it.episode },
                dubEpisodes.sortedBy { it.episode }
            )
        )
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

    // ============ LOAD LINKS ============
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return AnimeFireVideoExtractor.extractVideoLinks(data, mainUrl, name, callback)
    }
}
