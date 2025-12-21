package com.AnimeFire

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.gson.JsonParser
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

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
                        val response = element.toSearchResponse()
                        response?.let {
                            // Adicionar info de episódio se disponível
                            addEpisodeInfoToSearchResponse(it, element)
                        }
                    }
            }
            "Destaques da Semana" -> {
                document.select(".owl-carousel-semana .divArticleLancamentos a.item")
                    .mapNotNull { element -> 
                        val response = element.toSearchResponse()
                        response?.let {
                            addEpisodeInfoToSearchResponse(it, element)
                        }
                    }
            }
            "Últimos Animes Adicionados" -> {
                document.select(".owl-carousel-l_dia .divArticleLancamentos a.item")
                    .mapNotNull { element -> 
                        val response = element.toSearchResponse()
                        response?.let {
                            addEpisodeInfoToSearchResponse(it, element)
                        }
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
                        val displayTitle = "${cleanTitle} - Episódio $epNumber"
                        
                        val sitePoster = card.selectFirst("img.imgAnimesUltimosEps, img[src*='animes']")?.let { img ->
                            when {
                                img.hasAttr("data-src") -> img.attr("data-src")
                                img.hasAttr("src") -> img.attr("src")
                                else -> null
                            }?.takeIf { !it.contains("logo", ignoreCase = true) }
                        } ?: card.selectFirst("img:not([src*='logo'])")?.attr("src")
                        
                        // Extrair tipo de áudio (dub/leg) do card
                        val audioType = when {
                            rawTitle.contains("dublado", ignoreCase = true) -> "Dub"
                            rawTitle.contains("legendado", ignoreCase = true) -> "Leg"
                            else -> null
                        }
                        
                        val response = newAnimeSearchResponse(displayTitle, fixUrl(href)) {
                            this.posterUrl = sitePoster?.let { fixUrl(it) }
                            this.type = TvType.Anime
                        }
                        
                        // Adicionar info de episódio
                        addEpisodeInfoToSearchResponse(response, card, audioType, epNumber)
                    }.getOrNull()
                }
            }
            else -> emptyList()
        }
        
        return newHomePageResponse(request.name, homeItems.distinctBy { it.url }, false)
    }

    // ============ FUNÇÃO PARA ADICIONAR INFO DE EPISÓDIO ============
    
    private suspend fun addEpisodeInfoToSearchResponse(
        response: AnimeSearchResponse,
        element: Element,
        audioType: String? = null,
        epNumber: Int? = null
    ): AnimeSearchResponse {
        return try {
            // Se já temos audioType (do card), usamos
            val finalAudioType = audioType ?: extractAudioTypeFromElement(element)
            
            // Buscar último episódio se não temos número
            val finalEpNumber = epNumber ?: getLastEpisodeNumber(response.url)
            
            // Adicionar tag baseada no tipo de áudio
            if (finalAudioType != null && finalEpNumber != null) {
                val tag = "$finalAudioType Ep $finalEpNumber"
                response.name = "${response.name} ($tag)"
            }
            
            response
        } catch (e: Exception) {
            response // Retorna sem modificação se houver erro
        }
    }
    
    private fun extractAudioTypeFromElement(element: Element): String? {
        val text = element.text().lowercase()
        return when {
            text.contains("dublado") -> "Dub"
            text.contains("legendado") -> "Leg"
            else -> null
        }
    }
    
    private suspend fun getLastEpisodeNumber(url: String): Int? {
        return try {
            val document = app.get(url).document
            val episodeElements = document.select("a.lEp.epT, a.lEp, .divListaEps a, [href*='/video/'], [href*='/episodio/']")
            
            episodeElements.maxOfOrNull { element ->
                val text = element.text().trim()
                extractEpisodeNumber(text) ?: 0
            }
        } catch (e: Exception) {
            null
        }
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

            val response = newAnimeSearchResponse(cleanTitle, fixUrl(href)) {
                this.posterUrl = posterUrl?.let { fixUrl(it) }
                this.type = if (isMovie) {
                    TvType.Movie
                } else {
                    TvType.Anime
                }
            }
            
            // Adicionar info de episódio para resultados de busca também
            try {
                addEpisodeInfoToSearchResponse(response, element)
            } catch (e: Exception) {
                response // Retorna sem modificação se houver erro
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

        // Extrair status do anime
        val statusElement = document.selectFirst("div.animeInfo:contains(Status:) span.spanAnimeInfo")
        val statusText = statusElement?.text()?.trim() ?: "Desconhecido"
        val showStatus = when {
            statusText.contains("Completo", ignoreCase = true) -> ShowStatus.Completed
            statusText.contains("Em andamento", ignoreCase = true) -> ShowStatus.Ongoing
            statusText.contains("Lançando", ignoreCase = true) -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }

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
            newMovieLoadResponse(cleanTitle, url, type, url) {
                this.year = finalYear
                this.plot = plot
                this.tags = tags?.distinct()?.take(10)
                this.posterUrl = poster
                this.backgroundPosterUrl = poster // Usar mesma imagem para banner
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
                this.showStatus = showStatus
            }
        } else {
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
                this.backgroundPosterUrl = poster // Usar mesma imagem para banner
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
                this.showStatus = showStatus
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
