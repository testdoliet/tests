package com.CineAgora

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element

class CineAgora : MainAPI() {
    override var mainUrl = "https://cineagora.net"
    override var name = "CineAgora"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val usesWebView = false

    companion object {
        // Seções que estão na página principal (sem URLs específicas)
        private val HOME_SECTIONS = listOf(
            "ultimos-filmes" to "Últimos Filmes",
            "ultimas-series" to "Últimas Séries"
        )
        
        // Seções com URLs específicas
        private val SECTION_URLS = mapOf(
            "filmes-populares" to "https://cineagora.net/filmes-hd-online/filmes-populares-hd/",
            "series-populares" to "https://cineagora.net/series-online-hd-gratis/series-populares-hd/",
            "netflix" to "https://cineagora.net/netflix/",
            "paramount" to "https://cineagora.net/paramount/",
            "disney" to "https://cineagora.net/disney/",
            "apple" to "https://cineagora.net/apple/",
            "hbo" to "https://cineagora.net/hbo/",
            "acao" to "https://cineagora.net/filmes-hd-online/filmes-de-acao-hd/",
            "aventura" to "https://cineagora.net/filmes-hd-online/filmes-de-aventura-gratis/",
            "animacao" to "https://cineagora.net/filmes-hd-online/filmes-de-animacao-online/",
            "biograficos" to "https://cineagora.net/filmes-hd-online/assistir-filmes-biograficos/",
            "comedia" to "https://cineagora.net/filmes-hd-online/comedia-filmes-online/",
            "crime" to "https://cineagora.net/filmes-hd-online/crime-filmes-online/",
            "documentarios" to "https://cineagora.net/filmes-hd-online/documentarios-em-portugues/",
            "esporte" to "https://cineagora.net/filmes-hd-online/filmes-de-esporte-hd/",
            "drama" to "https://cineagora.net/filmes-hd-online/filmes-drama-online-hd/",
            "familia" to "https://cineagora.net/filmes-hd-online/filmes-familia-online/",
            "fantasia" to "https://cineagora.net/filmes-hd-online/filmes-fantasia-magia/",
            "historicos" to "https://cineagora.net/filmes-hd-online/filmes-historicos-hd/",
            "terror" to "https://cineagora.net/filmes-hd-online/filmes-terror-horror/",
            "musicais" to "https://cineagora.net/filmes-hd-online/filmes-musicais-online/",
            "misterio" to "https://cineagora.net/filmes-hd-online/filmes-misterio-suspense/",
            "romanticos" to "https://cineagora.net/filmes-hd-online/filmes-romanticos-online/",
            "suspense" to "https://cineagora.net/filmes-hd-online/filmes-suspense-hd/",
            "sci-fi" to "https://cineagora.net/filmes-hd-online/ficcao-cientifica-hd/",
            "tv" to "https://cineagora.net/filmes-hd-online/filmes-para-tv-hd/",
            "thriller" to "https://cineagora.net/filmes-hd-online/thriller-suspense-online/",
            "guerra" to "https://cineagora.net/filmes-hd-online/filmes-guerra-epicas/",
            "faroeste" to "https://cineagora.net/filmes-hd-online/filmes-faroeste-online/"
        )
    }

    override val mainPage = mainPageOf(
        *HOME_SECTIONS.map { (section, name) -> 
            "home_$section" to name 
        }.toTypedArray(),
        *SECTION_URLS.map { (section, url) ->
            "section_$section" to getSectionName(section)
        }.toTypedArray()
    )

    private fun getSectionName(section: String): String {
        return when (section) {
            "filmes-populares" -> "Filmes Populares"
            "series-populares" -> "Séries Populares"
            "netflix" -> "Netflix"
            "paramount" -> "Paramount+"
            "disney" -> "Disney+"
            "apple" -> "Apple TV+"
            "hbo" -> "HBO Max"
            "acao" -> "Ação"
            "aventura" -> "Aventura"
            "animacao" -> "Animação"
            "biograficos" -> "Biográficos"
            "comedia" -> "Comédia"
            "crime" -> "Crime"
            "documentarios" -> "Documentários"
            "esporte" -> "Esporte"
            "drama" -> "Drama"
            "familia" -> "Família"
            "fantasia" -> "Fantasia"
            "historicos" -> "Históricos"
            "terror" -> "Terror"
            "musicais" -> "Musicais"
            "misterio" -> "Mistério"
            "romanticos" -> "Românticos"
            "suspense" -> "Suspense"
            "sci-fi" -> "Sci-Fi"
            "tv" -> "TV"
            "thriller" -> "Thriller"
            "guerra" -> "Guerra"
            "faroeste" -> "Faroeste"
            else -> section.replace("-", " ").split(" ").joinToString(" ") { 
                it.replaceFirstChar { char -> char.uppercase() }
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sectionId = request.data.removePrefix("home_").removePrefix("section_")
        
        val document = if (request.data.startsWith("home_")) {
            // Seções da página principal
            app.get(mainUrl).document
        } else {
            // Seções com URLs específicas
            val url = SECTION_URLS[sectionId] ?: mainUrl
            app.get(url).document
        }
        
        val items = extractSectionItems(document, sectionId, request.data.startsWith("home_"))
        return newHomePageResponse(request.name, items.distinctBy { it.url }, false)
    }

    private fun extractSectionItems(document: org.jsoup.nodes.Document, sectionId: String, isHomeSection: Boolean): List<SearchResponse> {
        // Para seções da home, precisamos filtrar por tipo
        val items = document.select(".item, .item-relative .item")
        
        return if (isHomeSection) {
            when (sectionId) {
                "ultimos-filmes" -> items.filter { item ->
                    val href = item.selectFirst("a")?.attr("href") ?: ""
                    !href.contains("/series-") && !href.contains("/serie-") && !href.contains("/tv-")
                }.mapNotNull { it.toSearchResult() }
                
                "ultimas-series" -> items.filter { item ->
                    val href = item.selectFirst("a")?.attr("href") ?: ""
                    href.contains("/series-") || href.contains("/serie-") || href.contains("/tv-") ||
                    item.selectFirst(".data")?.text()?.contains(Regex("Temporada|Episódio")) == true
                }.mapNotNull { it.toSearchResult() }
                
                else -> items.mapNotNull { it.toSearchResult() }
            }
        } else {
            items.mapNotNull { it.toSearchResult() }
        }
    }

    private fun extractScoreAdvanced(element: Element): Pair<String?, String?> {
        val selectors = listOf(
            ".item-info-ust .rating" to "Seletor rating principal",
            ".rating" to "Seletor .rating",
            ".score" to "Seletor .score",
            ".item-info + div" to "Próximo ao item-info",
            ".item-footer span" to "No rodapé do item",
            "span:contains(★)" to "Span com estrela",
            "span:contains(/10)" to "Span com /10",
            "[class*='rating']" to "Classe contém 'rating'",
            "[class*='score']" to "Classe contém 'score'",
            ".item-info-ust div" to "Div dentro de item-info-ust",
            "small" to "Tag small",
            "b" to "Tag bold",
            "i" to "Tag italic"
        )

        for ((selector, _) in selectors) {
            val found = element.selectFirst(selector)?.text()?.trim()
            if (!found.isNullOrBlank() && isScoreLike(found)) {
                return found to selector
            }
        }

        element.parent()?.let { parent ->
            for ((selector, _) in selectors) {
                val found = parent.selectFirst(selector)?.text()?.trim()
                if (!found.isNullOrBlank() && isScoreLike(found)) {
                    return found to "parent.$selector"
                }
            }
        }

        val html = element.outerHtml()
        val scoreRegexes = listOf(
            Regex("""(\d+\.\d+|\d+)\s*(?:★|/10|pontos)"""),
            Regex("""class="[^"]*(?:rating|score)[^"]*">([^<]+)""")
        )

        for (regex in scoreRegexes) {
            val match = regex.find(html)
            if (match != null) {
                val found = match.groupValues[1].trim()
                if (isScoreLike(found)) {
                    return found to "regex"
                }
            }
        }

        return null to null
    }

    private fun isScoreLike(text: String): Boolean {
        return when {
            text.equals("N/A", ignoreCase = true) -> true
            text.matches(Regex("""^\d+(\.\d+)?$""")) -> true
            text.matches(Regex("""^\d+(\.\d+)?/10$""")) -> true
            text.contains("★") -> true
            text.contains("pontos", ignoreCase = true) -> true
            else -> false
        }
    }

    private fun extractEpisodeNumber(text: String): Int? {
        return listOf(
            Regex("""S\d+\s*E(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""Temporada\s*\d+\s*/\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""Ep[\.\s]*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""Epis[oó]dio\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""E(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""(\d{3,})"""),
            Regex("""(\d{1,2})$""")
        ).firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1)?.toIntOrNull() }
    }

    private fun extractSeasonNumber(text: String): Int? {
        return listOf(
            Regex("""S(\d+)\s*E\d+""", RegexOption.IGNORE_CASE),
            Regex("""Temporada\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""Temp\s*(\d+)""", RegexOption.IGNORE_CASE)
        ).firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1)?.toIntOrNull() } ?: 1
    }

    private fun cleanTitle(title: String): String {
        return title
            .replace(Regex("\\(\\d{4}\\)"), "")
            .replace(Regex("\\d{4}$"), "")
            .replace(Regex("\\(dublado\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\(legendado\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("-\\s*epis[oó]dio\\s*\\d+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("S\\d+\\s*E\\d+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("Temporada\\s*\\d+.*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Pegar o link principal
        val linkElement = this.selectFirst("a")
        val href = linkElement?.attr("href")?.takeIf { it.isNotBlank() } ?: return null
        
        // Título do card
        val titleElement = selectFirst(".item-footer .title")
        val rawTitle = titleElement?.text()?.trim() ?: return null
        
        // Extrair ano
        val year = selectFirst(".info span:first-child")?.text()?.toIntOrNull()
            ?: Regex("\\((\\d{4})\\)").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
        
        // Limpar título
        val cleanTitle = cleanTitle(rawTitle)
        
        // Imagem/poster
        val imgElement = selectFirst("img.thumbnail")
        val posterUrl = imgElement?.attr("src")?.let { fixUrl(it) }
        
        // 1. Qualidade (HD, TS, etc.)
        val qualityBadge = select(".item-info").firstOrNull()?.selectFirst("div:first-child")?.text()?.trim()
        
        // 2. Idioma (Dublado/Legendado)
        val languageBadge = select(".item-info").firstOrNull()?.selectFirst("div:nth-child(2)")?.text()?.trim()
        val isDubbed = languageBadge?.contains("dublado", ignoreCase = true) == true
        val isSubtitled = languageBadge?.contains("legendado", ignoreCase = true) == true
        
        // 3. Score/Rating
        val scoreResult = extractScoreAdvanced(this)
        val scoreText = scoreResult.first
        val score = when {
            scoreText == null || scoreText == "N/A" -> null
            else -> scoreText.toFloatOrNull()?.let { Score.from10(it) }
        }
        
        // 4. Último episódio adicionado
        val lastEpisodeInfo = select(".item-info").getOrNull(1)?.selectFirst("small")?.text()?.trim()
            ?: selectFirst(".data")?.text()?.trim()
        
        // Extrair número do episódio
        val episodeNumber = lastEpisodeInfo?.let { extractEpisodeNumber(it) }
        
        // Determinar se é filme ou série
        val isSerie = href.contains("/series-") || href.contains("/serie-") || href.contains("/tv-") || 
                      lastEpisodeInfo?.contains(Regex("S\\d+.*E\\d+")) == true ||
                      episodeNumber != null
        
        // Determinar qualidade baseada na badge
        val quality = when {
            qualityBadge?.contains("HD", ignoreCase = true) == true -> SearchQuality.HD
            qualityBadge?.contains("4K", ignoreCase = true) == true -> SearchQuality.HD
            qualityBadge?.contains("FULLHD", ignoreCase = true) == true -> SearchQuality.HD
            qualityBadge?.contains("TS", ignoreCase = true) == true -> SearchQuality.Cam
            else -> null
        }
        
        // Formatar URL com poster
        val urlWithPoster = if (posterUrl != null) {
            "${fixUrl(href)}|poster=$posterUrl"
        } else {
            fixUrl(href)
        }
        
        // Criar descrição com badges (para aparecer no hover ou como metadata)
        val description = buildString {
            if (qualityBadge != null && qualityBadge.isNotBlank()) {
                append("📀 $qualityBadge")
            }
            if (languageBadge != null && languageBadge.isNotBlank()) {
                if (isNotEmpty()) append(" • ")
                append("🗣️ $languageBadge")
            }
            if (scoreText != null && scoreText != "N/A") {
                if (isNotEmpty()) append(" • ")
                append("⭐ $scoreText")
            }
            if (lastEpisodeInfo != null && lastEpisodeInfo.isNotBlank()) {
                if (isNotEmpty()) append(" • ")
                append("📺 $lastEpisodeInfo")
            }
        }.takeIf { it.isNotBlank() }
        
        return if (isSerie) {
            // Para séries
            newTvSeriesSearchResponse(cleanTitle, urlWithPoster) {
                this.posterUrl = posterUrl
                this.year = year
                this.score = score
                if (quality != null) {
                    this.quality = quality
                }
                // Descrição com badges
                this.description = description
            }
        } else {
            // Para filmes
            newMovieSearchResponse(cleanTitle, urlWithPoster) {
                this.posterUrl = posterUrl
                this.year = year
                this.score = score
                if (quality != null) {
                    this.quality = quality
                }
                // Descrição com badges
                this.description = description
            }
        }
    }

    // As outras funções retornam false/nulo por enquanto conforme solicitado
    
    override suspend fun search(query: String): List<SearchResponse> {
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return false
    }
}
