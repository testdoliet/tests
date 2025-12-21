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

    // ============ FUNÇÃO AUXILIAR DE BUSCA COM TAGS DE EP ============
    
    private fun Element.toSearchResponse(forceAudioType: String? = null): AnimeSearchResponse? {
        val href = attr("href") ?: return null
        if (href.isBlank()) return null
        
        val titleElement = when {
            selectFirst("h3.animeTitle") != null -> selectFirst("h3.animeTitle")
            selectFirst(".text-block h3") != null -> selectFirst(".text-block h3")
            selectFirst(".animeTitle") != null -> selectFirst(".animeTitle")
            else -> selectFirst("h3")
        } ?: return null
        
        val rawTitle = titleElement.text().trim()
        
        val cleanTitle = rawTitle
            .replace(Regex("(?i)(dublado|legendado|todos os episódios|\\(\\d{4}\\)|\\s*-\\s*$|\\(movie\\))"), "")
            .trim()
        
        val isMovie = href.contains("/filmes/") || 
                      rawTitle.contains("filme", ignoreCase = true) ||
                      rawTitle.contains("movie", ignoreCase = true)
        
        // Extrair tipo de áudio do título ou usar o fornecido
        val audioType = forceAudioType ?: when {
            rawTitle.contains("dublado", ignoreCase = true) -> "Dub"
            rawTitle.contains("legendado", ignoreCase = true) -> "Leg"
            else -> null
        }
        
        // Extrair número do episódio se disponível
        val epNumber = selectFirst(".numEp")?.text()?.toIntOrNull()
        
        // Criar display title com info de episódio (se disponível)
        val displayTitle = if (audioType != null && epNumber != null) {
            "$cleanTitle ($audioType Ep $epNumber)"
        } else if (epNumber != null) {
            "$cleanTitle - Episódio $epNumber"
        } else {
            cleanTitle
        }
        
        val sitePoster = selectFirst("img.imgAnimes, img.card-img-top, img.transitioning_src, img.owl-lazy, img[src*='animes']")?.let { img ->
            when {
                img.hasAttr("data-src") -> img.attr("data-src")
                img.hasAttr("src") -> img.attr("src")
                else -> null
            }?.takeIf { !it.contains("logo", ignoreCase = true) }
        } ?: selectFirst("img:not([src*='logo']):not([src*='Logo'])")?.attr("src")

        return newAnimeSearchResponse(displayTitle, fixUrl(href)) {
            this.posterUrl = sitePoster?.let { fixUrl(it) }
            this.type = if (isMovie) TvType.Movie else TvType.Anime
        }
    }

    // ============ GET MAIN PAGE ATUALIZADA ============
    
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        
        val homeItems = when (request.name) {
            "Lançamentos" -> {
                document.select(".owl-carousel-home .divArticleLancamentos a.item")
                    .mapNotNull { element -> 
                        element.toSearchResponse()
                    }
            }
            "Destaques da Semana" -> {
                document.select(".owl-carousel-semana .divArticleLancamentos a.item")
                    .mapNotNull { element -> 
                        element.toSearchResponse()
                    }
            }
            "Últimos Animes Adicionados" -> {
                document.select(".owl-carousel-l_dia .divArticleLancamentos a.item")
                    .mapNotNull { element -> 
                        element.toSearchResponse()
                    }
            }
            "Últimos Episódios Adicionados" -> {
                document.select(".divCardUltimosEpsHome").mapNotNull { card ->
                    runCatching {
                        card.selectFirst("article.card a")?.toSearchResponse()
                    }.getOrNull()
                }
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

        val elements = document.select("div.divCardUltimosEps article.card a")
        
        return elements.mapNotNull { element ->
            val href = element.attr("href")
            if (href.isBlank()) {
                return@mapNotNull null
            }

            val titleElement = element.selectFirst("h3.animeTitle, .text-block h3, .animeTitle")
            val rawTitle = titleElement?.text()?.trim() ?: "Sem Título"
            
            val cleanTitle = rawTitle
                .replace(Regex("\\s*-\\s*Todos os Episódios$"), "")
                .replace(Regex("\\(Dublado\\)"), "")
                .replace(Regex("\\(Legendado\\)"), "")
                .trim()

            val imgElement = element.selectFirst("img.imgAnimes, img.card-img-top, img.transitioning_src")
            val posterUrl = when {
                imgElement?.hasAttr("data-src") == true -> imgElement.attr("data-src")
                imgElement?.hasAttr("src") == true -> imgElement.attr("src")
                else -> null
            }

            val isMovie = href.contains("/filmes/") || 
                         cleanTitle.contains("filme", ignoreCase = true) ||
                         rawTitle.contains("filme", ignoreCase = true) ||
                         rawTitle.contains("movie", ignoreCase = true)

            newAnimeSearchResponse(cleanTitle, fixUrl(href)) {
                this.posterUrl = posterUrl?.let { fixUrl(it) }
                this.type = if (isMovie) {
                    TvType.Movie
                } else {
                    TvType.Anime
                }
            }
        }.take(30)
    }

    // ============ LOAD PRINCIPAL COM EXTRATOR DE EPISÓDIOS ============
    
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val titleElement = document.selectFirst("h1.quicksand400, .main_div_anime_info h1, h1") ?: 
            throw ErrorLoadingException("Não foi possível encontrar o título")
        val rawTitle = titleElement.text().trim()
        
        val year = Regex("\\((\\d{4})\\)").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = rawTitle.replace(Regex("\\(\\d{4}\\)"), "").trim()
        
        val isMovie = url.contains("/filmes/") || rawTitle.contains("Movie", ignoreCase = true)
        val type = if (isMovie) TvType.Movie else TvType.Anime

        // Extrair status do anime (seletor: div.animeInfo:nth-child(11))
        val statusText = if (!isMovie) {
            // Procurar pelo 11º div.animeInfo ou por "Status:"
            document.select("div.animeInfo").getOrNull(10)?.select("span.spanAnimeInfo")?.text()?.trim()
                ?: document.select("div.animeInfo:contains(Status:) span.spanAnimeInfo").firstOrNull()?.text()?.trim()
                ?: "Desconhecido"
        } else {
            null
        }
        
        val showStatus = if (!isMovie) getStatus(statusText) else null

        // Extrair tipo de áudio disponível (seletor: div.animeInfo:nth-child(7))
        val audioText = if (!isMovie) {
            document.select("div.animeInfo").getOrNull(6)?.select("span.spanAnimeInfo")?.text()?.trim()
                ?: document.select("div.animeInfo:contains(Audio:) span.spanAnimeInfo").firstOrNull()?.text()?.trim()
                ?: "Legendado"
        } else {
            "Legendado"
        }
        
        val hasSub = audioText.contains("Legendado", ignoreCase = true)
        val hasDub = audioText.contains("Dublado", ignoreCase = true)

        // Extrair metadados do site
        val posterImg = document.selectFirst(".sub_animepage_img img.transitioning_src")
        val poster = when {
            posterImg?.hasAttr("src") == true -> fixUrl(posterImg.attr("src"))
            posterImg?.hasAttr("data-src") == true -> fixUrl(posterImg.attr("data-src"))
            else -> document.selectFirst("img[src*='/img/animes/']:not([src*='logo'])")
                ?.attr("src")?.let { fixUrl(it) }
        }

        val plot = document.selectFirst("div.divSinopse span.spanAnimeInfo")
            ?.text()
            ?.trim()
            ?.replace(Regex("^Sinopse:\\s*"), "")

        val tags = document.select("a.spanAnimeInfo.spanGeneros")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .takeIf { it.isNotEmpty() }?.toList()

        val siteYear = document.selectFirst("div.animeInfo:contains(Ano:) span.spanAnimeInfo")
            ?.text()
            ?.trim()
            ?.toIntOrNull()

        val finalYear = year ?: siteYear
        
        // Extrair episódios
        val episodes = if (!isMovie) {
            val (subEpisodes, dubEpisodes) = extractAllEpisodes(document, hasDub)
            Pair(subEpisodes, dubEpisodes)
        } else {
            Pair(emptyList(), emptyList())
        }

        val recommendations = document.select(".owl-carousel-anime .divArticleLancamentos a.item")
            .mapNotNull { element -> 
                element.toSearchResponse()
            }

        return if (isMovie) {
            newMovieLoadResponse(cleanTitle, url, type, url) {
                this.year = finalYear
                this.plot = plot
                this.tags = tags?.distinct()?.take(10)
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
            }
        } else {
            newAnimeLoadResponse(cleanTitle, url, type) {
                if (hasDub && episodes.second.isNotEmpty()) {
                    addEpisodes(DubStatus.Dubbed, episodes.second)
                }
                if (hasSub && episodes.first.isNotEmpty()) {
                    addEpisodes(DubStatus.Subbed, episodes.first)
                }
                
                this.year = finalYear
                this.plot = plot
                this.tags = tags?.distinct()?.take(10)
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
                
                // Adicionar showStatus apenas para animes
                if (showStatus != null) {
                    this.showStatus = showStatus
                }
            }
        }
    }

    // ============ FUNÇÃO PARA EXTRAIR TODOS OS EPISÓDIOS ============
    
    private suspend fun extractAllEpisodes(
        document: org.jsoup.nodes.Document,
        hasDub: Boolean
    ): Pair<List<Episode>, List<Episode>> {
        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()
        
        val episodeElements = document.select("a.lEp.epT, a.lEp, .divListaEps a, [href*='/video/'], [href*='/episodio/']")
        
        episodeElements.forEachIndexed { index, element ->
            try {
                val href = element.attr("href")
                if (href.isBlank()) return@forEachIndexed
                
                val text = element.text().trim()
                if (text.isBlank()) return@forEachIndexed
                
                val episodeNumber = extractEpisodeNumber(text) ?: (index + 1)
                val seasonNumber = 1
                
                val episodeName = element.selectFirst(".ep-name, .title")?.text()?.trim()
                    ?: text.substringAfterLast("-").trim()
                    ?: "Episódio $episodeNumber"

                // Determinar se é dublado ou legendado
                val isDubEpisode = text.contains("dublado", ignoreCase = true) || 
                                 (hasDub && !text.contains("legendado", ignoreCase = true))
                val isSubEpisode = text.contains("legendado", ignoreCase = true) || 
                                 (!hasDub && !text.contains("dublado", ignoreCase = true))

                val audioType = if (isDubEpisode) " (Dub)" else " (Leg)"
                val finalEpisodeName = "$episodeName$audioType"

                val episode = newEpisode(fixUrl(href)) {
                    this.name = finalEpisodeName
                    this.season = seasonNumber
                    this.episode = episodeNumber
                    this.description = "Episódio $episodeNumber"
                }

                if (isDubEpisode) {
                    dubEpisodes.add(episode)
                }
                if (isSubEpisode) {
                    subEpisodes.add(episode)
                }
                
            } catch (e: Exception) {
                // Ignorar erro
            }
        }
        
        // Ordenar por número do episódio
        val sortedSub = subEpisodes.sortedBy { it.episode }
        val sortedDub = dubEpisodes.sortedBy { it.episode }
        
        return Pair(sortedSub, sortedDub)
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
