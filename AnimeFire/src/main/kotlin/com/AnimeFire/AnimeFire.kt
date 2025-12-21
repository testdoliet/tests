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

    // ============ FUNÇÃO PRINCIPAL DE BUSCA ATUALIZADA ============
    
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
            scoreText == null || scoreText == "N/A" -> Score.from10(0f)  // ✅ N/A vira 0
            else -> scoreText.toFloatOrNull()?.let { Score.from10(it) } ?: Score.from10(0f)
        }
        
        val isMovie = href.contains("/filmes/") || combinedTitle.contains("filme", ignoreCase = true)
        
        // ============ EXTRAIR POSTER ============
        val sitePoster = selectFirst("img.imgAnimes, img.card-img-top, img.transitioning_src, img.owl-lazy, .imgAnimesUltimosEps")?.let { img ->
            when {
                img.hasAttr("data-src") -> img.attr("data-src")
                img.hasAttr("src") -> img.attr("src")
                else -> null
            }
        }

        // ============ CORREÇÃO: PARA EPISÓDIOS, USAR URL DO ANIME ============
        val finalUrl = if (isEpisodesSection && episodeNumber != null) {
            // Converter URL de episódio para URL do anime
            // Ex: https://animefire.io/animes/one-piece/1085 → https://animefire.io/animes/one-piece
            convertEpisodeUrlToAnimeUrl(href)
        } else {
            fixUrl(href)
        }

        // ============ CRIAR RESPOSTA DE BUSCA ============
        return newAnimeSearchResponse(cleanAnimeName, finalUrl) {
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
                
                // Se não tiver Dub/Leg mas tiver nota, mostrar nota
                if (!hasDub && !hasLeg && score.value > 0) {
                    this.score = score
                }
            } else {
                // ✅ OUTRAS SEÇÕES: Badge com avaliação (mesmo que seja 0)
                this.score = score
                
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
            
            // DEBUG
            println("ANIMEFIRE CARD - Section: ${if (isEpisodesSection) "Episodes" else "Animes"}, " +
                   "Name: '$cleanAnimeName', AnimeURL: '$finalUrl', " +
                   "Ep: $episodeNumber, Dub: $hasDub, Leg: $hasLeg, Score: ${score.value}")
        }
    }

    // ============ FUNÇÃO PARA CONVERTER URL DE EPISÓDIO PARA URL DE ANIME ============
    private fun convertEpisodeUrlToAnimeUrl(episodeUrl: String): String {
        return try {
            // Padrão: /animes/nome-do-anime/numero-episodio
            val pattern = Regex("(https?://[^/]+/animes/[^/]+)/\\d+")
            val match = pattern.find(episodeUrl)
            
            if (match != null) {
                // Retorna a URL do anime (sem o número do episódio)
                match.groupValues[1]
            } else {
                // Se não encontrar padrão, tenta remover o último segmento numérico
                val urlWithoutLastNumber = episodeUrl.replace(Regex("/\\d+$"), "")
                // Se ainda tem "todos-os-episodios", remove também
                urlWithoutLastNumber.replace("/todos-os-episodios", "")
            }
        } catch (e: Exception) {
            // Em caso de erro, retorna a URL original
            fixUrl(episodeUrl)
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
    // ... [mantenha o código da função load() igual ao anterior]
    
    // ============ FUNÇÃO PARA EXTRAIR TODOS OS EPISÓDIOS ============
    // ... [mantenha o código da função extractAllEpisodes() igual ao anterior]
    
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

// ============ FUNÇÃO GETSTATUS ============
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
