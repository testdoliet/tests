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

    // ============ FUNÇÃO PRINCIPAL DE BUSCA COM BADGES INTELIGENTES ============
    
    private fun Element.toSearchResponse(isEpisodesSection: Boolean = false): AnimeSearchResponse? {
        val href = attr("href") ?: return null
        if (href.isBlank()) return null
        
        // ============ EXTRAIR TÍTULO E ATRIBUTOS ============
        val titleElement = selectFirst("h3.animeTitle, .text-block h3, .animeTitle, h3") ?: return null
        val rawTitle = titleElement.text().trim()
        
        val titleAttr = attr("title")?.trim() ?: ""
        val combinedTitle = if (titleAttr.isNotBlank()) titleAttr else rawTitle
        
        // ============ DETECTAR DUBLADO/LEGENDADO ============
        val hasDub = combinedTitle.contains("dublado", ignoreCase = true)
        val hasLeg = combinedTitle.contains("legendado", ignoreCase = true)
        
        // ============ EXTRAIR NÚMERO DO EPISÓDIO ============
        var episodeNumber: Int? = null
        var episodeText: String? = null
        
        // 1. Do elemento .numEp
        val numEpElement = selectFirst(".numEp")
        if (numEpElement != null) {
            episodeText = numEpElement.text().trim()
            episodeNumber = extractEpisodeNumber(episodeText)
        }
        
        // 2. Do título (ex: "Episódio 12")
        if (episodeNumber == null) {
            val epMatch = Regex("Epis[oó]dio\\s*(\\d+)", RegexOption.IGNORE_CASE).find(combinedTitle)
            episodeNumber = epMatch?.groupValues?.get(1)?.toIntOrNull()
            episodeText = epMatch?.value
        }
        
        // 3. Da URL (ex: /anime/12)
        if (episodeNumber == null) {
            val urlMatch = Regex("/(\\d+)$").find(href)
            episodeNumber = urlMatch?.groupValues?.get(1)?.toIntOrNull()
            episodeText = "Ep $episodeNumber"
        }
        
        // ============ EXTRAIR NOME LIMPO DO ANIME ============
        val cleanAnimeName = extractAnimeName(combinedTitle, episodeText)
        
        // ============ EXTRAIR NOTA/AVALIAÇÃO ============
        val scoreText = selectFirst(".horaUltimosEps")?.text()?.trim()
        val score = when {
            scoreText == null || scoreText == "N/A" -> null
            else -> scoreText.toFloatOrNull()?.let { Score.from10(it) }
        }
        
        // ============ EXTRAIR CLASSIFICAÇÃO ETÁRIA ============
        val ageRating = selectFirst(".text-blockCapaAnimeTags span")?.text()?.trim()
        
        val isMovie = href.contains("/filmes/") || combinedTitle.contains("filme", ignoreCase = true)
        
        // ============ EXTRAIR POSTER ============
        val sitePoster = selectFirst("img.imgAnimes, img.card-img-top, img.transitioning_src, img.owl-lazy, .imgAnimesUltimosEps")?.let { img ->
            when {
                img.hasAttr("data-src") -> img.attr("data-src")
                img.hasAttr("src") -> img.attr("src")
                else -> null
            }
        }

        // ============ CRIAR RESPOSTA DE BUSCA ============
        return newAnimeSearchResponse(cleanAnimeName, fixUrl(href)) {
            this.posterUrl = sitePoster?.let { fixUrl(it) }
            this.type = if (isMovie) TvType.Movie else TvType.Anime
            
            // ============ SISTEMA DE BADGES INTELIGENTE ============
            if (isEpisodesSection) {
                // ✅ SEÇÃO DE EPISÓDIOS: Badge com episódio + áudio
                if (episodeNumber != null) {
                    addDubStatus(
                        dubExist = hasDub,
                        subExist = hasLeg,
                        dubEpisodes = if (hasDub) episodeNumber else null,
                        subEpisodes = if (hasLeg) episodeNumber else null
                    )
                }
                
                // Adicionar nota como informação extra
                if (score != null) {
                    this.score = score
                }
            } else {
                // ✅ OUTRAS SEÇÕES: Badge com avaliação
                if (score != null) {
                    this.score = score
                }
                
                // Se tiver episódio e for dublado/legendado, mostrar também
                if (episodeNumber != null && (hasDub || hasLeg)) {
                    addDubStatus(
                        dubExist = hasDub,
                        subExist = hasLeg,
                        dubEpisodes = if (hasDub) episodeNumber else null,
                        subEpisodes = if (hasLeg) episodeNumber else null
                    )
                }
            }
            
            // DEBUG - CORRIGIDO: usar score?.toString() em vez de score?.value
            println("ANIMEFIRE CARD - Section: ${if (isEpisodesSection) "Episodes" else "Animes"}, " +
                   "Name: '$cleanAnimeName', Ep: $episodeNumber, " +
                   "Dub: $hasDub, Leg: $hasLeg, Score: ${score?.toString()}")
        }
    }

    // ============ FUNÇÃO PARA EXTRAIR NOME DO ANIME ============
    private fun extractAnimeName(fullText: String, episodeText: String?): String {
        var cleanName = fullText
        
        // Remover indicação de episódio
        episodeText?.let {
            cleanName = cleanName.replace(it, "")
        }
        
        // Padrões de limpeza
        val patterns = listOf(
            Regex("(?i)\\(dublado\\)"),
            Regex("(?i)\\(legendado\\)"),
            Regex("(?i)todos os episódios"),
            Regex("\\(\\d{4}\\)"),
            Regex("\\s*-\\s*$"),
            Regex("\\s*:\\s*$")
        )
        
        patterns.forEach { pattern ->
            cleanName = cleanName.replace(pattern, "")
        }
        
        // Limpar espaços extras
        cleanName = cleanName.trim().replace(Regex("\\s+"), " ")
        
        // Se ainda tiver " - " no final, remover
        if (cleanName.endsWith("-")) {
            cleanName = cleanName.substringBeforeLast("-").trim()
        }
        
        return cleanName
    }

    // ============ EXTRATOR DE NÚMERO DE EPISÓDIO ============
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

    // ============ GET MAIN PAGE ATUALIZADA ============
    
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        
        val homeItems = when (request.name) {
            "Lançamentos" -> {
                document.select(".owl-carousel-home .divArticleLancamentos a.item")
                    .mapNotNull { it.toSearchResponse(isEpisodesSection = false) }
            }
            "Destaques da Semana" -> {
                document.select(".owl-carousel-semana .divArticleLancamentos a.item")
                    .mapNotNull { it.toSearchResponse(isEpisodesSection = false) }
            }
            "Últimos Animes Adicionados" -> {
                document.select(".owl-carousel-l_dia .divArticleLancamentos a.item")
                    .mapNotNull { it.toSearchResponse(isEpisodesSection = false) }
            }
            "Últimos Episódios Adicionados" -> {
                // ✅ DIFERENCIAR: Esta é a seção de episódios
                document.select(".divCardUltimosEpsHome a, .cardUltimosEps a")
                    .mapNotNull { it.toSearchResponse(isEpisodesSection = true) }
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

        val elements = document.select("div.divCardUltimosEps article.card a")
        
        return elements.mapNotNull { element ->
            // Na busca, detectar automaticamente se é episódio
            val isEpisodeCard = element.selectFirst(".numEp") != null
            element.toSearchResponse(isEpisodesSection = isEpisodeCard)
        }.take(30)
    }

    // ============ LOAD PRINCIPAL (PÁGINA DE DETALHES) ============
    
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // DEBUG
        println("ANIMEFIRE LOAD - Carregando URL: $url")
        println("ANIMEFIRE LOAD - Título da página: ${document.title()}")

        // ============ EXTRAIR TÍTULO ============
        val titleElement = document.selectFirst("h1.quicksand400, .main_div_anime_info h1, h1") 
            ?: throw ErrorLoadingException("Não foi possível encontrar o título")
        val rawTitle = titleElement.text().trim()
        
        val year = Regex("\\((\\d{4})\\)").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = rawTitle.replace(Regex("\\(\\d{4}\\)"), "").trim()
        
        val isMovie = url.contains("/filmes/") || rawTitle.contains("Movie", ignoreCase = true)
        val type = if (isMovie) TvType.Movie else TvType.Anime

        // ============ EXTRAIR STATUS DO ANIME ============
        val showStatus = if (!isMovie) {
            val animeInfoDivs = document.select("div.animeInfo")
            val statusDiv = animeInfoDivs.firstOrNull { 
                it.text().contains("Status:", ignoreCase = true) 
            }
            
            val statusText = statusDiv?.select("span.spanAnimeInfo")?.firstOrNull()?.text()?.trim()
                ?: "Desconhecido"
            
            getStatus(statusText)
        } else {
            ShowStatus.Completed
        }

        // ============ EXTRAIR TIPO DE ÁUDIO ============
        val (hasLeg, hasDub) = if (!isMovie) {
            val animeInfoDivs = document.select("div.animeInfo")
            val audioDiv = animeInfoDivs.firstOrNull { 
                it.text().contains("Audio:", ignoreCase = true) 
            }
            
            val audioText = audioDiv?.select("span.spanAnimeInfo")?.firstOrNull()?.text()?.trim() 
                ?: "Legendado"
            
            Pair(
                audioText.contains("Legendado", ignoreCase = true),
                audioText.contains("Dublado", ignoreCase = true)
            )
        } else {
            Pair(false, false)
        }

        // ============ EXTRAIR POSTER ============
        val posterImg = document.selectFirst(".sub_animepage_img img.transitioning_src")
        val poster = when {
            posterImg?.hasAttr("src") == true -> fixUrl(posterImg.attr("src"))
            posterImg?.hasAttr("data-src") == true -> fixUrl(posterImg.attr("data-src"))
            else -> document.selectFirst("img[src*='/img/animes/']:not([src*='logo'])")
                ?.attr("src")?.let { fixUrl(it) }
        }

        // ============ EXTRAIR SINOPSE ============
        val plot = document.selectFirst("div.divSinopse span.spanAnimeInfo")
            ?.text()
            ?.trim()
            ?.replace(Regex("^Sinopse:\\s*"), "")

        // ============ EXTRAIR TAGS ============
        val tags = document.select("a.spanAnimeInfo.spanGeneros")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .takeIf { it.isNotEmpty() }?.toList()

        // ============ EXTRAIR ANO ============
        val siteYear = document.selectFirst("div.animeInfo:contains(Ano:) span.spanAnimeInfo")
            ?.text()
            ?.trim()
            ?.toIntOrNull()
        val finalYear = year ?: siteYear
        
        // ============ EXTRAIR EPISÓDIOS ============
        val episodes = if (!isMovie) {
            println("ANIMEFIRE LOAD - Extraindo episódios...")
            val (subEpisodes, dubEpisodes) = extractAllEpisodes(document, hasDub)
            println("ANIMEFIRE LOAD - Episódios encontrados: Sub=${subEpisodes.size}, Dub=${dubEpisodes.size}")
            Pair(subEpisodes, dubEpisodes)
        } else {
            Pair(emptyList(), emptyList())
        }

        // ============ EXTRAIR RECOMENDAÇÕES ============
        val recommendations = document.select(".owl-carousel-anime .divArticleLancamentos a.item")
            .mapNotNull { element -> 
                element.toSearchResponse(isEpisodesSection = false)
            }

        // ============ CRIAR RESPOSTA FINAL ============
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
                // Adicionar episódios por tipo de áudio
                if (hasDub && episodes.second.isNotEmpty()) {
                    println("ANIMEFIRE LOAD - Adicionando episódios dublados: ${episodes.second.size}")
                    addEpisodes(DubStatus.Dubbed, episodes.second)
                }
                if (hasLeg && episodes.first.isNotEmpty()) {
                    println("ANIMEFIRE LOAD - Adicionando episódios legendados: ${episodes.first.size}")
                    addEpisodes(DubStatus.Subbed, episodes.first)
                }
                
                this.year = finalYear
                this.plot = plot
                this.tags = tags?.distinct()?.take(10)
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
                this.showStatus = showStatus
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
        
        // Seletores para episódios
        val episodeElements = document.select("a.lEp.epT, a.lEp, .divListaEps a, a[href*='/video/'], a[href*='/episodio/']")
        
        println("ANIMEFIRE EPISODES - Elementos encontrados: ${episodeElements.size}")
        
        if (episodeElements.isEmpty()) {
            // Tentar seletores alternativos
            val altElements = document.select("div.episodios-list a, .lista-episodios a, .episodes-list a")
            println("ANIMEFIRE EPISODES - Elementos alternativos: ${altElements.size}")
            // Não podemos usar addAll em uma lista imutável, então processamos separadamente
            return extractAllEpisodesFromElements(altElements, hasDub)
        }
        
        return extractAllEpisodesFromElements(episodeElements, hasDub)
    }
    
    private fun extractAllEpisodesFromElements(
        elements: org.jsoup.select.Elements,
        hasDub: Boolean
    ): Pair<List<Episode>, List<Episode>> {
        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()
        
        elements.forEachIndexed { index, element ->
            try {
                val href = element.attr("href")
                if (href.isBlank()) return@forEachIndexed
                
                val text = element.text().trim()
                if (text.isBlank()) return@forEachIndexed
                
                val episodeNumber = extractEpisodeNumber(text) ?: (index + 1)
                val seasonNumber = 1
                
                // Nome do episódio
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

                // Criar episódio
                val episode = newEpisode(fixUrl(href)) {
                    this.name = finalEpisodeName
                    this.season = seasonNumber
                    this.episode = episodeNumber
                    this.description = "Episódio $episodeNumber"
                }

                // Adicionar à lista correta
                if (isDubEpisode) {
                    dubEpisodes.add(episode)
                }
                if (isSubEpisode) {
                    subEpisodes.add(episode)
                }
                
            } catch (e: Exception) {
                println("ANIMEFIRE EPISODES - Erro no elemento $index: ${e.message}")
            }
        }
        
        // Ordenar por número do episódio
        return Pair(
            subEpisodes.sortedBy { it.episode },
            dubEpisodes.sortedBy { it.episode }
        )
    }

    // ============ LOAD LINKS (CHAMAR EXTRACTOR) ============
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return AnimeFireVideoExtractor.extractVideoLinks(data, mainUrl, name, callback)
    }
}

// ============ FUNÇÃO GETSTATUS SEPARADA (na mesma classe ou em arquivo separado) ============
fun getStatus(t: String?): ShowStatus {
    if (t == null) {
        return ShowStatus.Completed
    }
    
    val status = t.trim()
    
    return when {
        status.contains("em lançamento", ignoreCase = true) ||
        status.contains("lançando", ignoreCase = true) ||
        status.contains("em andamento", ignoreCase = true) ||
        status.contains("ongoing", ignoreCase = true) ||
        status.contains("atualizando", ignoreCase = true) -> ShowStatus.Ongoing
        
        status.contains("concluído", ignoreCase = true) ||
        status.contains("completo", ignoreCase = true) ||
        status.contains("completado", ignoreCase = true) ||
        status.contains("terminado", ignoreCase = true) ||
        status.contains("finished", ignoreCase = true) -> ShowStatus.Completed
        
        else -> ShowStatus.Completed
    }
}
