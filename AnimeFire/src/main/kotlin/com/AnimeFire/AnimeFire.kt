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
        
        val sitePoster = selectFirst("img.imgAnimes, img.card-img-top, img.transitioning_src, img.owl-lazy, img[src*='animes']")?.let { img ->
            when {
                img.hasAttr("data-src") -> img.attr("data-src")
                img.hasAttr("src") -> img.attr("src")
                else -> null
            }?.takeIf { !it.contains("logo", ignoreCase = true) }
        } ?: selectFirst("img:not([src*='logo']):not([src*='Logo'])")?.attr("src")

        return newAnimeSearchResponse(cleanTitle, fixUrl(href)) {
            this.posterUrl = sitePoster?.let { fixUrl(it) }
            this.type = if (isMovie) TvType.Movie else TvType.Anime
        }
    }

    // ============ GET MAIN PAGE ATUALIZADA COM EP INFO ============
    
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
                        val link = card.selectFirst("article.card a") ?: return@runCatching null
                        val href = link.attr("href") ?: return@runCatching null
                        
                        val titleElement = card.selectFirst("h3.animeTitle") ?: return@runCatching null
                        val rawTitle = titleElement.text().trim()
                        
                        val epNumber = card.selectFirst(".numEp")?.text()?.toIntOrNull() ?: 1
                        val cleanTitle = rawTitle.replace(Regex("(?i)(dublado|legendado|todos os episódios|\\(\\d{4}\\))$"), "").trim()
                        
                        // Extrair tipo de áudio (dub/leg) do card
                        val audioType = when {
                            rawTitle.contains("dublado", ignoreCase = true) -> "Dub"
                            rawTitle.contains("legendado", ignoreCase = true) -> "Leg"
                            else -> null
                        }
                        
                        // Criar display title com info do episódio
                        val displayTitle = if (audioType != null) {
                            "$cleanTitle ($audioType Ep $epNumber)"
                        } else {
                            "$cleanTitle - Episódio $epNumber"
                        }
                        
                        val sitePoster = card.selectFirst("img.imgAnimesUltimosEps, img[src*='animes']")?.let { img ->
                            when {
                                img.hasAttr("data-src") -> img.attr("data-src")
                                img.hasAttr("src") -> img.attr("src")
                                else -> null
                            }?.takeIf { !it.contains("logo", ignoreCase = true) }
                        } ?: card.selectFirst("img:not([src*='logo'])")?.attr("src")
                        
                        newAnimeSearchResponse(displayTitle, fixUrl(href)) {
                            this.posterUrl = sitePoster?.let { fixUrl(it) }
                            this.type = TvType.Anime
                        }
                    }.getOrNull()
                }
            }
            else -> emptyList()
        }
        
        return newHomePageResponse(request.name, homeItems.distinctBy { it.url }, false)
    }

    // ============ SEARCH CORRIGIDO ============
    
    override suspend fun search(query: String): List<SearchResponse> {
        // CORREÇÃO: Converter "to be hero" para "to-be-hero"
        val formattedQuery = query
            .trim()
            .replace(Regex("\\s+"), "-")  // Substitui espaços por hífens
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

    // ============ LOAD PRINCIPAL ATUALIZADA ============
    
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val titleElement = document.selectFirst("h1.quicksand400, .main_div_anime_info h1, h1") ?: 
            throw ErrorLoadingException("Não foi possível encontrar o título")
        val rawTitle = titleElement.text().trim()
        
        val year = Regex("\\((\\d{4})\\)").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = rawTitle.replace(Regex("\\(\\d{4}\\)"), "").trim()
        
        val isMovie = url.contains("/filmes/") || rawTitle.contains("Movie", ignoreCase = true)
        val type = if (isMovie) TvType.Movie else TvType.Anime

        // CORREÇÃO: Extrair status do anime usando o seletor correto
        val statusText = if (!isMovie) {
            // Primeiro método: procurar elemento que contém "Status:" e pegar o próximo span
            val statusDiv = document.select("div.animeInfo:contains(Status:)").firstOrNull()
            if (statusDiv != null) {
                statusDiv.select("span.spanAnimeInfo").firstOrNull()?.text()?.trim()
            } else {
                // Segundo método: procurar texto específico
                val statusElement = document.select("span.spanAnimeInfo:contains(Em lançamento), span.spanAnimeInfo:contains(Concluído)")
                    .firstOrNull()
                statusElement?.text()?.trim()
            } ?: "Desconhecido"
        } else {
            null
        }
        
        // DEBUG: Ver o que está sendo extraído
        println("DEBUG - Status extraído: '$statusText' para URL: $url")
        
        val showStatus = if (!isMovie) getStatus(statusText) else null

        // Extrair tipo de áudio disponível
        val audioElement = document.selectFirst("div.animeInfo:contains(Audio:) span.spanAnimeInfo")
        val audioText = audioElement?.text()?.trim() ?: "Legendado"
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
        
        val episodes = if (!isMovie) {
            extractEpisodesFromSite(document, hasDub)
        } else {
            emptyList()
        }

        val recommendations = document.select(".owl-carousel-anime .divArticleLancamentos a.item")
            .mapNotNull { element -> 
                element.toSearchResponse()
            }

        return if (isMovie) {
            // PARA FILMES: não adicionar showStatus
            newMovieLoadResponse(cleanTitle, url, type, url) {
                this.year = finalYear
                this.plot = plot
                this.tags = tags?.distinct()?.take(10)
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
                // Não adicionar showStatus para filmes
            }
        } else {
            // PARA ANIMES: adicionar showStatus
            newAnimeLoadResponse(cleanTitle, url, type) {
                if (hasDub) {
                    addEpisodes(DubStatus.Dubbed, episodes.filter { it.name?.contains("(Dub)", ignoreCase = true) == true })
                }
                if (hasSub) {
                    addEpisodes(DubStatus.Subbed, episodes.filter { it.name?.contains("(Dub)", ignoreCase = true) == false })
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

    private suspend fun extractEpisodesFromSite(
        document: org.jsoup.nodes.Document,
        hasDub: Boolean
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
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
                val audioType = when {
                    text.contains("dublado", ignoreCase = true) -> " (Dub)"
                    text.contains("legendado", ignoreCase = true) -> " (Leg)"
                    hasDub -> " (Dub)" // Assumir dublado se o anime tem dub
                    else -> ""
                }

                val finalEpisodeName = "$episodeName$audioType"

                episodes.add(
                    newEpisode(fixUrl(href)) {
                        this.name = finalEpisodeName
                        this.season = seasonNumber
                        this.episode = episodeNumber
                        this.description = "Episódio $episodeNumber"
                    }
                )
                
            } catch (e: Exception) {
                // Ignorar erro
            }
        }
        
        return episodes.sortedBy { it.episode }
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
