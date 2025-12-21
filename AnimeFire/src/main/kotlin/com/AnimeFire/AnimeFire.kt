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
        
        // Extrair tipo de áudio do título
        val audioType = forceAudioType ?: when {
            rawTitle.contains("dublado", ignoreCase = true) -> "Dub"
            rawTitle.contains("legendado", ignoreCase = true) -> "Leg"
            else -> null
        }
        
        // Extrair número do episódio se disponível
        val epNumber = selectFirst(".numEp")?.text()?.toIntOrNull()
        
        // Criar display title com info de episódio
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
            element.toSearchResponse()
        }.take(30)
    }

    // ============ LOAD PRINCIPAL COM EXTRATOR DE EPISÓDIOS CORRIGIDO ============
    
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // DEBUG: Ver todo o HTML da página
        println("DEBUG - Carregando URL: $url")
        println("DEBUG - Título da página: ${document.title()}")

        val titleElement = document.selectFirst("h1.quicksand400, .main_div_anime_info h1, h1") ?: 
            throw ErrorLoadingException("Não foi possível encontrar o título")
        val rawTitle = titleElement.text().trim()
        
        println("DEBUG - Título bruto: $rawTitle")
        
        val year = Regex("\\((\\d{4})\\)").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = rawTitle.replace(Regex("\\(\\d{4}\\)"), "").trim()
        
        val isMovie = url.contains("/filmes/") || rawTitle.contains("Movie", ignoreCase = true)
        val type = if (isMovie) TvType.Movie else TvType.Anime

        println("DEBUG - É filme? $isMovie")
        println("DEBUG - Título limpo: $cleanTitle")

        // CORREÇÃO: Extrair status do anime de forma mais robusta
        val statusText = if (!isMovie) {
            // Primeiro, tentar encontrar todos os div.animeInfo e ver o que eles contêm
            val animeInfoDivs = document.select("div.animeInfo")
            println("DEBUG - Número de div.animeInfo encontrados: ${animeInfoDivs.size}")
            
            animeInfoDivs.forEachIndexed { index, div ->
                val text = div.text().trim()
                println("DEBUG - div.animeInfo[$index]: $text")
            }
            
            // Procurar por "Status:" em qualquer lugar
            val statusDiv = animeInfoDivs.firstOrNull { it.text().contains("Status:", ignoreCase = true) }
            if (statusDiv != null) {
                val status = statusDiv.select("span.spanAnimeInfo").firstOrNull()?.text()?.trim()
                println("DEBUG - Status encontrado no div específico: '$status'")
                status
            } else {
                // Procurar por texto que contenha "Em lançamento" ou "Concluído"
                val possibleStatus = animeInfoDivs.firstOrNull { div ->
                    val text = div.text().trim()
                    text.contains("em lançamento", ignoreCase = true) || 
                    text.contains("concluído", ignoreCase = true) ||
                    text.contains("completo", ignoreCase = true)
                }
                
                val status = possibleStatus?.select("span.spanAnimeInfo")?.firstOrNull()?.text()?.trim()
                println("DEBUG - Status encontrado por texto: '$status'")
                status
            } ?: "Desconhecido"
        } else {
            null
        }
        
        println("DEBUG - Status extraído: '$statusText'")
        
        val showStatus = if (!isMovie) getStatus(statusText) else null
        println("DEBUG - ShowStatus convertido: $showStatus")

        // CORREÇÃO: Extrair tipo de áudio disponível
        val audioText = if (!isMovie) {
            // Procurar por "Audio:" em qualquer div.animeInfo
            val audioDiv = document.select("div.animeInfo").firstOrNull { 
                it.text().contains("Audio:", ignoreCase = true) 
            }
            
            if (audioDiv != null) {
                val audio = audioDiv.select("span.spanAnimeInfo").firstOrNull()?.text()?.trim()
                println("DEBUG - Audio encontrado no div específico: '$audio'")
                audio
            } else {
                // Procurar por "Dublado" ou "Legendado"
                val possibleAudio = document.select("div.animeInfo").firstOrNull { div ->
                    val text = div.text().trim()
                    text.contains("dublado", ignoreCase = true) || 
                    text.contains("legendado", ignoreCase = true)
                }
                
                val audio = possibleAudio?.select("span.spanAnimeInfo")?.firstOrNull()?.text()?.trim()
                println("DEBUG - Audio encontrado por texto: '$audio'")
                audio
            } ?: "Legendado"
        } else {
            "Legendado"
        }
        
        println("DEBUG - Audio text extraído: '$audioText'")
        
        val hasSub = audioText.contains("Legendado", ignoreCase = true)
        val hasDub = audioText.contains("Dublado", ignoreCase = true)
        
        println("DEBUG - Tem legendado? $hasSub")
        println("DEBUG - Tem dublado? $hasDub")

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
        
        // CORREÇÃO: Extrair episódios de forma mais robusta
        val episodes = if (!isMovie) {
            println("DEBUG - Extraindo episódios...")
            val (subEpisodes, dubEpisodes) = extractAllEpisodes(document, hasDub)
            println("DEBUG - Episódios legendados encontrados: ${subEpisodes.size}")
            println("DEBUG - Episódios dublados encontrados: ${dubEpisodes.size}")
            Pair(subEpisodes, dubEpisodes)
        } else {
            println("DEBUG - É filme, não extrai episódios")
            Pair(emptyList(), emptyList())
        }

        val recommendations = document.select(".owl-carousel-anime .divArticleLancamentos a.item")
            .mapNotNull { element -> 
                element.toSearchResponse()
            }

        return if (isMovie) {
            // PARA FILMES: não adicionar showStatus
            println("DEBUG - Criando MovieLoadResponse")
            newMovieLoadResponse(cleanTitle, url, type, url) {
                this.year = finalYear
                this.plot = plot
                this.tags = tags?.distinct()?.take(10)
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
            }
        } else {
            // PARA ANIMES: adicionar showStatus
            println("DEBUG - Criando AnimeLoadResponse")
            newAnimeLoadResponse(cleanTitle, url, type) {
                if (hasDub && episodes.second.isNotEmpty()) {
                    println("DEBUG - Adicionando episódios dublados: ${episodes.second.size}")
                    addEpisodes(DubStatus.Dubbed, episodes.second)
                }
                if (hasSub && episodes.first.isNotEmpty()) {
                    println("DEBUG - Adicionando episódios legendados: ${episodes.first.size}")
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
                    println("DEBUG - Definindo showStatus: $showStatus")
                    this.showStatus = showStatus
                }
            }
        }
    }

    // ============ FUNÇÃO PARA EXTRAIR TODOS OS EPISÓDIOS CORRIGIDA ============
    
    private suspend fun extractAllEpisodes(
        document: org.jsoup.nodes.Document,
        hasDub: Boolean
    ): Pair<List<Episode>, List<Episode>> {
        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()
        
        // CORREÇÃO: Usar seletores mais específicos
        val episodeElements = document.select("a.lEp.epT, a.lEp, .divListaEps a, a[href*='/video/'], a[href*='/episodio/']")
        
        println("DEBUG - Elementos de episódios encontrados: ${episodeElements.size}")
        
        if (episodeElements.isEmpty()) {
            // Tentar seletores alternativos
            val altElements = document.select("div.episodios-list a, .lista-episodios a, .episodes-list a")
            println("DEBUG - Elementos alternativos encontrados: ${altElements.size}")
            
            episodeElements.addAll(altElements)
        }
        
        episodeElements.forEachIndexed { index, element ->
            try {
                val href = element.attr("href")
                if (href.isBlank()) {
                    println("DEBUG - Elemento $index: href vazio")
                    return@forEachIndexed
                }
                
                val text = element.text().trim()
                if (text.isBlank()) {
                    println("DEBUG - Elemento $index: texto vazio")
                    return@forEachIndexed
                }
                
                println("DEBUG - Elemento $index: texto='$text', href='$href'")
                
                val episodeNumber = extractEpisodeNumber(text) ?: (index + 1)
                val seasonNumber = 1
                
                val episodeName = element.selectFirst(".ep-name, .title, .nome-episodio")?.text()?.trim()
                    ?: text.substringAfterLast("-").trim()
                    ?: text.substringAfterLast("|").trim()
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
                    println("DEBUG - Adicionado episódio dublado $episodeNumber: $finalEpisodeName")
                }
                if (isSubEpisode) {
                    subEpisodes.add(episode)
                    println("DEBUG - Adicionado episódio legendado $episodeNumber: $finalEpisodeName")
                }
                
            } catch (e: Exception) {
                println("DEBUG - Erro ao processar elemento $index: ${e.message}")
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
                val number = match.groupValues[1].toIntOrNull()
                println("DEBUG - Número do episódio encontrado: $number no texto '$text'")
                return number
            }
        }
        println("DEBUG - Nenhum número encontrado no texto: '$text'")
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
