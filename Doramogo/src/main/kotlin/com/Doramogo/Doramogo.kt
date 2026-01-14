package com.Doramogo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class DoramogoPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Doramogo())
    }
}

class Doramogo : MainAPI() {
    override var mainUrl = "https://www.doramogo.net"
    override var name = "Doramogo"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override val usesWebView = false

    override val mainPage = mainPageOf(
        "$mainUrl/episodios" to "Episódios Recentes",
        "$mainUrl/dorama?slug=&status=&ano=&classificacao_idade=&idiomar=DUB" to "Doramas Dublados",
        "$mainUrl/dorama?slug=&status=&ano=&classificacao_idade=&idiomar=LEG" to "Doramas Legendados",
        "$mainUrl/genero/dorama-acao" to "Ação",
        "$mainUrl/genero/dorama-aventura" to "Aventura",
        "$mainUrl/genero/dorama-comedia" to "Comédia",
        "$mainUrl/genero/dorama-crime" to "Crime",
        "$mainUrl/genero/dorama-drama" to "Drama",
        "$mainUrl/genero/dorama-familia" to "Família",
        "$mainUrl/genero/dorama-fantasia" to "Fantasia",
        "$mainUrl/genero/dorama-ficcao-cientifica" to "Ficção Científica",
        "$mainUrl/genero/dorama-misterio" to "Mistério",
        "$mainUrl/genero/dorama-reality" to "Reality Shows",
        "$mainUrl/genero/dorama-sci-fi" to "Sci-Fi",
        "$mainUrl/filmes" to "Filmes"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) {
            when {
                request.data.contains("/dorama?") || request.data.contains("/filmes") -> 
                    "${request.data}&pagina=$page"
                request.data.contains("/genero/") -> 
                    "${request.data}/pagina/$page"
                request.data.contains("/episodios") -> 
                    "${request.data}?pagina=$page"
                else -> "${request.data}?page=$page"
            }
        } else {
            request.data
        }

        val document = app.get(url).document
        val items = ArrayList<SearchResponse>()

        // Verificar se é a aba de episódios recentes
        val isEpisodesPage = request.data.contains("/episodios") || request.name.contains("Episódios")

        if (isEpisodesPage) {
            // Para página de episódios recentes - layout HORIZONTAL
            document.select(".episode-card").forEach { card ->
                val aTag = card.selectFirst("a") ?: return@forEach
                val titleElement = card.selectFirst("h3")
                val episodeTitle = titleElement?.text()?.trim() ?: return@forEach
                
                // Extrair nome do dorama removendo parênteses
                val cleanTitle = episodeTitle.replace(Regex("\\s*\\(.*\\)"), "").trim()
                
                // Extrair nome base sem "Episódio X"
                val doramaName = cleanTitle.replace(Regex("\\s*-\\s*Episódio\\s*\\d+.*$"), "").trim()
                val href = aTag.attr("href")
                
                val imgElement = card.selectFirst("img")
                val posterUrl = when {
                    imgElement?.hasAttr("src") == true -> fixUrl(imgElement.attr("src"))
                    imgElement?.hasAttr("data-src") == true -> fixUrl(imgElement.attr("data-src"))
                    else -> null
                }
                
                // Extrair número do episódio
                val episodeMatch = Regex("Episódio\\s*(\\d+)", RegexOption.IGNORE_CASE).find(cleanTitle)
                val episodeNumber = episodeMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                
                // Determinar se é DUB ou LEG
                val isDub = href.contains("/dub/") || request.data.contains("idiomar=DUB") || 
                           episodeTitle.contains("Dublado", ignoreCase = true)
                val isLeg = href.contains("/leg/") || request.data.contains("idiomar=LEG") || 
                           episodeTitle.contains("Legendado", ignoreCase = true)
                
                // Formatar título final: Dorama - EP X LEG/DUB
                val audioType = when {
                    isDub -> "DUB"
                    isLeg -> "LEG"
                    else -> ""
                }
                val finalTitle = if (audioType.isNotEmpty()) {
                    "$doramaName - EP $episodeNumber $audioType"
                } else {
                    "$doramaName - EP $episodeNumber"
                }
                
                items.add(newTvSeriesSearchResponse(finalTitle, fixUrl(href), TvType.TvSeries) {
                    this.posterUrl = posterUrl
                })
            }
        } else {
            // Para outras páginas (doramas, filmes, gêneros) - layout VERTICAL
            document.select(".episode-card").forEach { card ->
                val aTag = card.selectFirst("a") ?: return@forEach
                val titleElement = card.selectFirst("h3")
                var title = titleElement?.text()?.trim() 
                    ?: aTag.attr("title")?.trim()
                    ?: return@forEach
                
                // REMOVER (Legendado) e (Dublado) dos títulos
                title = cleanTitle(title)
                
                val href = aTag.attr("href")
                
                val imgElement = card.selectFirst("img")
                val posterUrl = when {
                    imgElement?.hasAttr("data-src") == true -> fixUrl(imgElement.attr("data-src"))
                    imgElement?.hasAttr("src") == true -> fixUrl(imgElement.attr("src"))
                    else -> null
                }
                
                val isMovie = href.contains("/filmes/") || request.name.contains("Filmes")
                val type = if (isMovie) TvType.Movie else TvType.TvSeries
                
                if (type == TvType.Movie) {
                    items.add(newMovieSearchResponse(title, fixUrl(href), type) {
                        this.posterUrl = posterUrl
                    })
                } else {
                    items.add(newTvSeriesSearchResponse(title, fixUrl(href), type) { 
                        this.posterUrl = posterUrl
                    })
                }
            }
        }

        val hasNextPage = document.select("""a[href*="pagina/"], a[href*="?page="], 
            .pagination a, .next-btn, a:contains(PRÓXIMA)""").isNotEmpty()
        
        // Criar HomePageList com configuração de layout
        val homePageList = HomePageList(
            request.name,
            items.distinctBy { it.url },
            // Episódios recentes são HORIZONTAIS (true), outros são VERTICAIS (false)
            isHorizontalImages = isEpisodesPage
        )
        
        return newHomePageResponse(listOf(homePageList), hasNextPage)
    }

    // --- Search ---
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document
        
        return document.select(".episode-card, .search-result").mapNotNull { card ->
            val aTag = card.selectFirst("a") ?: return@mapNotNull null
            var title = card.selectFirst("h3, h2")?.text()?.trim() 
                ?: aTag.attr("title") 
                ?: return@mapNotNull null
            
            // Limpar título
            title = cleanTitle(title)
            
            val href = aTag.attr("href")
            
            val imgElement = card.selectFirst("img")
            val posterUrl = when {
                imgElement?.hasAttr("data-src") == true -> fixUrl(imgElement.attr("data-src"))
                imgElement?.hasAttr("src") == true -> fixUrl(imgElement.attr("src"))
                else -> null
            }
            
            val type = if (href.contains("/filmes/")) TvType.Movie else TvType.TvSeries
            
            if (type == TvType.Movie) {
                newMovieSearchResponse(title, fixUrl(href), type) { 
                    this.posterUrl = posterUrl 
                }
            } else {
                newTvSeriesSearchResponse(title, fixUrl(href), type) { 
                    this.posterUrl = posterUrl 
                }
            }
        }
    }

    // --- Load ---
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        // Título
        val fullTitle = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")
            ?: return null
        
        // Limpar título
        var title = cleanTitle(fullTitle)
        
        // Descrição
        val description = document.selectFirst("meta[property='og:description']")?.attr("content")
            ?: document.selectFirst("#sinopse-text")?.text()?.trim()
            ?: ""
        
        // Poster
        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }
            ?: document.selectFirst("#w-55")?.attr("src")?.let { fixUrl(it) }
        
        // Extrair informações detalhadas
        val infoMap = extractInfoMap(document)
        
        // Ano - pegar do infoMap ou extrair da URL
        val year = infoMap["ano"]?.toIntOrNull()
            ?: extractYearFromUrl(url)
            ?: title.findYear()
        
        // Gêneros principais
        val mainTags = document.select(".gens a").map { it.text().trim() }
        
        // Tags adicionais: áudio e status
        val additionalTags = mutableListOf<String>()
        
        // Adicionar áudio como tag
        infoMap["áudio"]?.let { audio ->
            when {
                audio.contains("Dublado", ignoreCase = true) -> additionalTags.add("Dublado")
                audio.contains("Legendado", ignoreCase = true) -> additionalTags.add("Legendado")
                else -> additionalTags.add(audio)
            }
        }
        
        // Adicionar status como tag
        infoMap["status"]?.let { status ->
            additionalTags.add(status)
        }
        
        // Tags combinadas
        val allTags = (mainTags + additionalTags).distinct()
        
        // Duração (para filmes)
        val durationText = infoMap["duração"] ?: infoMap["duraçã"]
        val duration = durationText?.parseDuration()
        
        // Verificar se é filme ou série
        val isMovie = url.contains("/filmes/")
        val type = if (isMovie) TvType.Movie else TvType.TvSeries
        
        if (type == TvType.TvSeries) {
            // Extrair episódios de múltiplas temporadas
            val episodes = mutableListOf<Episode>()
            
            // Extrair cada temporada
            document.select(".dorama-one-season-block").forEach { seasonBlock ->
                // Extrair número da temporada do título
                val seasonTitle = seasonBlock.selectFirst(".dorama-one-season-title")?.text()?.trim() ?: "1° Temporada"
                val seasonNumber = extractSeasonNumber(seasonTitle)
                
                // Extrair episódios desta temporada
                seasonBlock.select(".dorama-one-episode-item").forEach { episodeItem ->
                    val episodeUrl = episodeItem.attr("href")?.let { fixUrl(it) } ?: return@forEach
                    val episodeTitle = episodeItem.selectFirst(".episode-title")?.text()?.trim() ?: "Episódio"
                    
                    // Extrair número do episódio
                    val episodeNumber = extractEpisodeNumberFromEpisodeItem(episodeItem)
                    
                    episodes.add(newEpisode(episodeUrl) {
                        this.name = episodeTitle
                        this.season = seasonNumber
                        this.episode = episodeNumber
                    })
                }
            }
            
            // Se não encontrou episódios estruturados por temporada, tentar método alternativo
            if (episodes.isEmpty()) {
                document.select(".dorama-one-episode-item").forEach { episodeItem ->
                    val episodeUrl = episodeItem.attr("href")?.let { fixUrl(it) } ?: return@forEach
                    val episodeTitle = episodeItem.selectFirst(".episode-title")?.text()?.trim() ?: "Episódio"
                    
                    val episodeNumber = extractEpisodeNumberFromEpisodeItem(episodeItem)
                    // Tentar extrair temporada da URL (ex: /temporada-1/)
                    val seasonNumber = extractSeasonNumberFromUrl(episodeUrl) ?: 1
                    
                    episodes.add(newEpisode(episodeUrl) {
                        this.name = episodeTitle
                        this.season = seasonNumber
                        this.episode = episodeNumber
                    })
                }
            }
            
            return newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = allTags
                
                // Adicionar informações extras
                infoMap["estúdio"]?.let { studio ->
                    this.recommendations = listOf(Recommendation(studio))
                }
            }
        } else {
            // Para filmes
            return newMovieLoadResponse(title, url, type, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = allTags
                this.duration = duration
                
                // Adicionar informações extras
                infoMap["estúdio"]?.let { studio ->
                    this.recommendations = listOf(Recommendation(studio))
                }
            }
        }
    }
    
    // --- Funções auxiliares ---
    
    // Extrair informações detalhadas do HTML
    private fun extractInfoMap(document: Element): Map<String, String> {
        val infoMap = mutableMapOf<String, String>()
        
        // Procurar em .detail p.text-white para ano
        document.selectFirst(".detail p.text-white")?.text()?.trim()?.let { detailText ->
            // Extrair ano do formato "킹덤 / 2026 / 12 Episodes"
            val yearMatch = Regex("""\s*/\s*(\d{4})\s*/\s*""").find(detailText)
            yearMatch?.groupValues?.get(1)?.let { year ->
                infoMap["ano"] = year
            }
            
            // Extrair número de episódios se existir
            val epMatch = Regex("""(\d+)\s*Episodes?""").find(detailText)
            epMatch?.groupValues?.get(1)?.let { eps ->
                infoMap["episódios"] = eps
            }
        }
        
        // Extrair das divs .casts div (Status, Estúdio, Áudio, Duração)
        document.select(".casts div").forEach { div ->
            val text = div.text()
            if (text.contains(":")) {
                val parts = text.split(":", limit = 2)
                if (parts.size == 2) {
                    val key = parts[0].trim().lowercase().removePrefix("b").removeSuffix(":")
                    val value = parts[1].trim()
                    
                    // Normalizar as chaves
                    val normalizedKey = when (key) {
                        "status" -> "status"
                        "estúdio", "estudio", "studio" -> "estúdio"
                        "áudio", "audio" -> "áudio"
                        "duração", "duracao", "duration" -> "duração"
                        else -> key
                    }
                    
                    infoMap[normalizedKey] = value
                }
            }
        }
        
        return infoMap
    }
    
    // Extrair ano da URL (ex: /kingdom-2019/)
    private fun extractYearFromUrl(url: String): Int? {
        val pattern = Regex("""/(?:series|filmes)/[^/]+-(\d{4})/""")
        val match = pattern.find(url)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }
    
    // Função auxiliar para limpar títulos
    private fun cleanTitle(title: String): String {
        return title.replace(Regex("\\s*\\(Legendado\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\(Dublado\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*-\\s*(Dublado|Legendado|Online|e|Dublado e Legendado).*"), "")
            .replace(Regex("\\s*\\(.*\\)"), "")
            .trim()
    }
    
    // Extrair número do episódio do elemento correto
    private fun extractEpisodeNumberFromEpisodeItem(episodeItem: Element): Int {
        // Primeiro tentar do span .dorama-one-episode-number (ex: "EP 01")
        val episodeNumberSpan = episodeItem.selectFirst(".dorama-one-episode-number")
        episodeNumberSpan?.text()?.let { spanText ->
            val match = Regex("""EP\s*(\d+)""", RegexOption.IGNORE_CASE).find(spanText)
            if (match != null) {
                return match.groupValues[1].toIntOrNull() ?: 1
            }
        }
        
        // Depois tentar do span .episode-title
        val episodeTitle = episodeItem.selectFirst(".episode-title")?.text() ?: ""
        val pattern = Regex("""Episódio\s*(\d+)|Episode\s*(\d+)""", RegexOption.IGNORE_CASE)
        val match = pattern.find(episodeTitle)
        return match?.groupValues?.get(1)?.toIntOrNull()
            ?: match?.groupValues?.get(2)?.toIntOrNull()
            ?: 1
    }
    
    // Extrair número da temporada do título
    private fun extractSeasonNumber(seasonTitle: String): Int {
        val pattern = Regex("""(\d+)°\s*Temporada|Temporada\s*(\d+)""", RegexOption.IGNORE_CASE)
        val match = pattern.find(seasonTitle)
        return match?.groupValues?.get(1)?.toIntOrNull()
            ?: match?.groupValues?.get(2)?.toIntOrNull()
            ?: 1
    }
    
    private fun extractSeasonNumberFromUrl(url: String): Int? {
        val pattern = Regex("""temporada[_-](\d+)""", RegexOption.IGNORE_CASE)
        val match = pattern.find(url)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }
    
    private fun String.findYear(): Int? {
        val pattern = Regex("""\b(19\d{2}|20\d{2})\b""")
        return pattern.find(this)?.value?.toIntOrNull()
    }
    
    private fun String?.parseDuration(): Int? {
        if (this.isNullOrBlank()) return null
        val pattern = Regex("""(\d+)\s*(min|minutes|minutos)""", RegexOption.IGNORE_CASE)
        val match = pattern.find(this)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    // --- Load Links ---
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Temporariamente desativado
        return false
    }
}
