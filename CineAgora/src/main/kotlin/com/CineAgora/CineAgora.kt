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

    private fun Element.toSearchResult(): SearchResponse? {
        // Pegar o link principal
        val linkElement = this.selectFirst("a")
        val href = linkElement?.attr("href")?.takeIf { it.isNotBlank() } ?: return null
        
        // Título do card
        val titleElement = selectFirst(".item-footer .title")
        val title = titleElement?.text()?.trim() ?: return null
        
        // Extrair ano
        val year = selectFirst(".info span:first-child")?.text()?.toIntOrNull()
            ?: Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        
        // Limpar título (remover ano e outros detalhes)
        val cleanTitle = title
            .replace(Regex("\\(\\d{4}\\)"), "")
            .replace(Regex("\\d{4}$"), "")
            .trim()
        
        // Imagem/poster
        val imgElement = selectFirst("img.thumbnail")
        val posterUrl = imgElement?.attr("src")?.let { fixUrl(it) }
        
        // 1. Qualidade (HD, TS, etc.) - Primeiro .item-info
        val qualityBadge = select(".item-info").firstOrNull()?.selectFirst("div:first-child")?.text()?.trim()
        
        // 2. Idioma (Dublado/Legendado) - Primeiro .item-info
        val languageBadge = select(".item-info").firstOrNull()?.selectFirst("div:nth-child(2)")?.text()?.trim()
        
        // 3. Score/Rating (com estrela) - .item-info-ust .rating
        val ratingText = selectFirst(".item-info-ust .rating")?.ownText()?.trim()
        val rating = ratingText?.toFloatOrNull()
        
        // 4. Último episódio adicionado (para séries) - Segundo .item-info ou .data
        val lastEpisodeInfo = select(".item-info").getOrNull(1)?.selectFirst("small")?.text()?.trim()
            ?: selectFirst(".data")?.text()?.trim()
        
        // Determinar se é filme ou série
        val isSerie = href.contains("/series-") || href.contains("/serie-") || href.contains("/tv-") || 
                      lastEpisodeInfo?.contains(Regex("S\\d+.*E\\d+")) == true
        
        // Construir descrição com badges
        val description = buildString {
            if (qualityBadge != null && qualityBadge.isNotBlank()) {
                append("Qualidade: $qualityBadge")
            }
            if (languageBadge != null && languageBadge.isNotBlank()) {
                if (isNotEmpty()) append(" | ")
                append("Idioma: $languageBadge")
            }
            if (rating != null) {
                if (isNotEmpty()) append(" | ")
                append("⭐ $rating")
            }
            if (lastEpisodeInfo != null && lastEpisodeInfo.isNotBlank()) {
                if (isNotEmpty()) append(" | ")
                append("Último: $lastEpisodeInfo")
            }
        }.takeIf { it.isNotBlank() }
        
        // Determinar qualidade baseada na badge
        val quality = when {
            qualityBadge?.contains("HD", ignoreCase = true) == true -> SearchQuality.HD
            qualityBadge?.contains("4K", ignoreCase = true) == true -> SearchQuality.HD
            qualityBadge?.contains("FULLHD", ignoreCase = true) == true -> SearchQuality.HD
            qualityBadge?.contains("TS", ignoreCase = true) == true -> SearchQuality.Cam
            else -> null
        }
        
        return if (isSerie) {
            newTvSeriesSearchResponse(cleanTitle, fixUrl(href)) {
                this.posterUrl = posterUrl
                this.year = year
                if (quality != null) {
                    this.quality = quality
                }
                // Adicionar descrição com badges
                if (description != null) {
                    // Podemos adicionar como um campo extra ou metadata
                    // O Cloudstream geralmente mostra isso no hover/card
                }
            }
        } else {
            newMovieSearchResponse(cleanTitle, fixUrl(href)) {
                this.posterUrl = posterUrl
                this.year = year
                if (quality != null) {
                    this.quality = quality
                }
                // Adicionar descrição com badges
                if (description != null) {
                    // Podemos adicionar como um campo extra ou metadata
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
