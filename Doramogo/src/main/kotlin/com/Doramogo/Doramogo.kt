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
        "$mainUrl/" to "Em Destaque",
        "$mainUrl/episodios" to "Episódios Recentes",
        "$mainUrl/dorama?slug=&status=&ano=&classificacao_idade=&idiomar=DUB" to "Dublados",
        "$mainUrl/dorama?slug=&status=&ano=&classificacao_idade=&idiomar=LEG" to "Legendados",
        "$mainUrl/genero/dorama-acao" to "Ação",
        "$mainUrl/genero/dorama-comedia" to "Comédia",
        "$mainUrl/genero/dorama-drama" to "Drama",
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

        // Para páginas de episódios
        if (request.data.contains("/episodios")) {
            document.select(".episode-card").forEach { card ->
                val aTag = card.selectFirst("a") ?: return@forEach
                val titleElement = card.selectFirst("h3, .episode-title")
                val episodeTitle = titleElement?.text()?.trim() ?: return@forEach
                
                // Extrair nome do dorama
                val doramaName = episodeTitle.replace(Regex("\\s*-\\s*Episódio\\s*\\d+.*$"), "").trim()
                val href = aTag.attr("href")
                
                val imgElement = card.selectFirst("img")
                val posterUrl = when {
                    imgElement?.hasAttr("src") == true -> fixUrl(imgElement.attr("src"))
                    imgElement?.hasAttr("data-src") == true -> fixUrl(imgElement.attr("data-src"))
                    else -> null
                }
                
                items.add(newTvSeriesSearchResponse(doramaName, fixUrl(href), TvType.TvSeries) {
                    this.posterUrl = posterUrl
                })
            }
        } else {
            // Para páginas normais
            document.select("div.archive, .drama-card").forEach { card ->
                val aTag = card.selectFirst("a") ?: return@forEach
                val title = card.selectFirst("h2, .title")?.text()?.trim() 
                    ?: aTag.attr("title")?.trim()
                    ?: return@forEach
                
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
            .pagination a:contains(›), .next-page""").isNotEmpty()
        
        return newHomePageResponse(request.name, items, hasNextPage)
    }

    // --- Search ---
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document
        
        return document.select("div.archive, .search-result").mapNotNull { card ->
            val aTag = card.selectFirst("a") ?: return@mapNotNull null
            val title = card.selectFirst("h2, h3")?.text()?.trim() 
                ?: aTag.attr("title") 
                ?: return@mapNotNull null
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
        
        val title = fullTitle.replace(Regex("\\s*-\\s*(Dublado|Legendado|Online).*"), "").trim()
        
        // Descrição
        val description = document.selectFirst("meta[property='og:description']")?.attr("content")
            ?: document.selectFirst("#sinopse-text")?.text()?.trim()
            ?: ""
        
        // Poster
        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }
            ?: document.selectFirst("#w-55")?.attr("src")?.let { fixUrl(it) }
        
        // Ano
        val infoText = document.selectFirst(".detail p.text-white")?.text() ?: ""
        val year = Regex("""/(\d{4})/""").find(infoText)?.groupValues?.get(1)?.toIntOrNull()
            ?: title.findYear()
        
        // Gêneros
        val tags = document.select(".gens a").map { it.text().trim() }
        
        // Extrair informações adicionais
        val castsInfo = mutableMapOf<String, String>()
        document.select(".casts div").forEach { div ->
            val text = div.text()
            if (text.contains(":")) {
                val parts = text.split(":", limit = 2)
                if (parts.size == 2) {
                    val key = parts[0].trim().lowercase()
                    val value = parts[1].trim()
                    castsInfo[key] = value
                }
            }
        }
        
        // Verificar se é filme ou série
        val isMovie = url.contains("/filmes/")
        val type = if (isMovie) TvType.Movie else TvType.TvSeries
        
        if (type == TvType.TvSeries) {
            // Extrair episódios
            val episodes = mutableListOf<Episode>()
            document.select(".dorama-one-episode-item").forEach { episodeItem ->
                val episodeUrl = episodeItem.attr("href")?.let { fixUrl(it) } ?: return@forEach
                val episodeTitle = episodeItem.selectFirst(".episode-title")?.text()?.trim() ?: "Episódio"
                
                val episodeNumber = extractEpisodeNumber(episodeTitle)
                val seasonNumber = extractSeasonNumberFromUrl(url) ?: 1
                
                episodes.add(newEpisode(episodeUrl) {
                    this.name = episodeTitle
                    this.season = seasonNumber
                    this.episode = episodeNumber
                })
            }
            
            return newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                
                // Status da série (usando ShowStatus como no AnimeFire)
                castsInfo["status"]?.let { statusText ->
                    this.status = when {
                        statusText.contains("Em Produção", ignoreCase = true) -> ShowStatus.Ongoing
                        statusText.contains("Concluído", ignoreCase = true) -> ShowStatus.Completed
                        else -> null
                    }
                }
                
                // Informações de áudio
                castsInfo["áudio"]?.let { audioText ->
                    if (audioText.contains("Dublado", ignoreCase = true)) {
                        addDubStatus(dubExist = true, subExist = audioText.contains("Legendado", ignoreCase = true))
                    }
                }
            }
        } else {
            // Para filmes
            return newMovieLoadResponse(title, url, type, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                
                // Duração
                castsInfo["duraçã"]?.let { durationText ->
                    this.duration = durationText.parseDuration()
                }
                
                // Informações de áudio
                castsInfo["áudio"]?.let { audioText ->
                    if (audioText.contains("Dublado", ignoreCase = true)) {
                        addDubStatus(dubExist = true, subExist = audioText.contains("Legendado", ignoreCase = true))
                    }
                }
            }
        }
    }
    
    private fun extractEpisodeNumber(text: String): Int {
        val pattern = Regex("""Episódio\s*(\d+)|Episode\s*(\d+)|EP\s*(\d+)""", RegexOption.IGNORE_CASE)
        val match = pattern.find(text)
        return match?.groupValues?.get(1)?.toIntOrNull()
            ?: match?.groupValues?.get(2)?.toIntOrNull()
            ?: match?.groupValues?.get(3)?.toIntOrNull()
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
