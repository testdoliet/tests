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
        // URLs específicas para cada seção
        private val SECTION_URLS = mapOf(
            // Seções da página principal
            "ultimos-filmes" to "$mainUrl/",
            "ultimas-series" to "$mainUrl/series-online-hd-gratis/",
            
            // Seções com URLs específicas
            "filmes-populares" to "$mainUrl/filmes-hd-online/filmes-populares-hd/",
            "series-populares" to "$mainUrl/series-online-hd-gratis/series-populares-hd/",
            "netflix" to "$mainUrl/netflix/",
            "paramount" to "$mainUrl/paramount/",
            "disney" to "$mainUrl/disney/",
            "apple" to "$mainUrl/apple/",
            "hbo" to "$mainUrl/hbo/",
            "acao" to "$mainUrl/filmes-hd-online/filmes-de-acao-hd/",
            "aventura" to "$mainUrl/filmes-hd-online/filmes-de-aventura-gratis/",
            "animacao" to "$mainUrl/filmes-hd-online/filmes-de-animacao-online/",
            "biograficos" to "$mainUrl/filmes-hd-online/assistir-filmes-biograficos/",
            "comedia" to "$mainUrl/filmes-hd-online/comedia-filmes-online/",
            "crime" to "$mainUrl/filmes-hd-online/crime-filmes-online/",
            "documentarios" to "$mainUrl/filmes-hd-online/documentarios-em-portugues/",
            "esporte" to "$mainUrl/filmes-hd-online/filmes-de-esporte-hd/",
            "drama" to "$mainUrl/filmes-hd-online/filmes-drama-online-hd/",
            "familia" to "$mainUrl/filmes-hd-online/filmes-familia-online/",
            "fantasia" to "$mainUrl/filmes-hd-online/filmes-fantasia-magia/",
            "historicos" to "$mainUrl/filmes-hd-online/filmes-historicos-hd/",
            "terror" to "$mainUrl/filmes-hd-online/filmes-terror-horror/",
            "musicais" to "$mainUrl/filmes-hd-online/filmes-musicais-online/",
            "misterio" to "$mainUrl/filmes-hd-online/filmes-misterio-suspense/",
            "romanticos" to "$mainUrl/filmes-hd-online/filmes-romanticos-online/",
            "suspense" to "$mainUrl/filmes-hd-online/filmes-suspense-hd/",
            "sci-fi" to "$mainUrl/filmes-hd-online/ficcao-cientifica-hd/",
            "tv" to "$mainUrl/filmes-hd-online/filmes-para-tv-hd/",
            "thriller" to "$mainUrl/filmes-hd-online/thriller-suspense-online/",
            "guerra" to "$mainUrl/filmes-hd-online/filmes-guerra-epicas/",
            "faroeste" to "$mainUrl/filmes-hd-online/filmes-faroeste-online/"
        )
    }

    override val mainPage = mainPageOf(
        *SECTION_URLS.map { (section, url) ->
            "section_$section" to getSectionName(section)
        }.toTypedArray()
    )

    private fun getSectionName(section: String): String {
        return when (section) {
            "ultimos-filmes" -> "Últimos Filmes"
            "ultimas-series" -> "Últimas Séries"
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
        val sectionId = request.data.removePrefix("section_")
        val baseUrl = SECTION_URLS[sectionId] ?: mainUrl
        
        // Construir URL com paginação
        val url = when {
            page == 0 -> baseUrl
            baseUrl.endsWith("/") -> "${baseUrl}page/${page + 1}/"
            else -> "$baseUrl/page/${page + 1}/"
        }
        
        val document = app.get(url).document
        val items = extractSectionItems(document, sectionId)
        
        // Verificar se tem próxima página (procura por links de paginação)
        val hasNextPage = document.select(".pagination a, .page-numbers a, .paginacao a")
            .any { it.text().contains((page + 2).toString()) || 
                   it.text().contains("Próxima", ignoreCase = true) ||
                   it.text().contains(">") || it.text().contains("›") }
        
        return newHomePageResponse(request.name, items.distinctBy { it.url }, hasNextPage)
    }

    private fun extractSectionItems(document: org.jsoup.nodes.Document, sectionId: String): List<SearchResponse> {
        val items = document.select(".item, .item-relative .item")
        
        // Para "Últimos Filmes", filtrar apenas filmes
        // Para "Últimas Séries", filtrar apenas séries
        // Para outras seções, deixar todos os itens
        return when (sectionId) {
            "ultimos-filmes" -> items.filter { item ->
                val href = item.selectFirst("a")?.attr("href") ?: ""
                !href.contains("/series-") && !href.contains("/serie-") && !href.contains("/tv-")
            }.mapNotNull { it.toSearchResult() }
            
            "ultimas-series" -> items.filter { item ->
                val href = item.selectFirst("a")?.attr("href") ?: ""
                href.contains("/series-") || href.contains("/serie-") || href.contains("/tv-")
            }.mapNotNull { it.toSearchResult() }
            
            "series-populares" -> items.filter { item ->
                val href = item.selectFirst("a")?.attr("href") ?: ""
                href.contains("/series-") || href.contains("/serie-") || href.contains("/tv-")
            }.mapNotNull { it.toSearchResult() }
            
            else -> items.mapNotNull { it.toSearchResult() }
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
        
        // Extrair ano (permite números no título)
        val year = selectFirst(".info span:first-child")?.text()?.toIntOrNull()
            ?: Regex("\\((\\d{4})\\)").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
        
        // Limpar título (APENAS remove informações extras, mantém números)
        val cleanTitle = rawTitle
            .replace(Regex("\\(\\d{4}\\)"), "")
            .replace(Regex("\\(dublado\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\(legendado\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("-\\s*epis[oó]dio\\s*\\d+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("S\\d+\\s*E\\d+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("Temporada\\s*\\d+.*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        
        // Imagem/poster
        val imgElement = selectFirst("img.thumbnail")
        val posterUrl = imgElement?.attr("src")?.let { fixUrl(it) }
        
        // 1. Qualidade (HD, TS, etc.)
        val qualityBadge = select(".item-info").firstOrNull()?.selectFirst("div:first-child")?.text()?.trim()
        
        // 2. Idioma (Dublado/Legendado)
        val languageBadge = select(".item-info").firstOrNull()?.selectFirst("div:nth-child(2)")?.text()?.trim()
        
        // 3. Score/Rating
        val scoreResult = extractScoreAdvanced(this)
        val scoreText = scoreResult.first
        val score = when {
            scoreText == null || scoreText == "N/A" -> null
            else -> scoreText.toFloatOrNull()?.let { Score.from10(it) }
        }
        
        // 4. Último episódio adicionado (para séries)
        val lastEpisodeInfo = select(".item-info").getOrNull(1)?.selectFirst("small")?.text()?.trim()
            ?: selectFirst(".data")?.text()?.trim()
        
        // Extrair número do episódio
        val episodeNumber = lastEpisodeInfo?.let { extractEpisodeNumber(it) }
        
        // Determinar se é filme ou série baseado na URL e informações
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
        
        return if (isSerie) {
            // Para séries
            newTvSeriesSearchResponse(cleanTitle, urlWithPoster) {
                this.posterUrl = posterUrl
                this.year = year
                this.score = score
                if (quality != null) {
                    this.quality = quality
                }
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
