package com.StreamFlix

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import org.jsoup.nodes.Element

@CloudstreamPlugin
class StreamFlixProvider : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(StreamFlix())
    }
}

class StreamFlix : MainAPI() {
    override var mainUrl = "https://streamflix.live/"
    override var name = "StreamFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val usesWebView = true

    // Definindo as categorias da página inicial
    override val mainPage = mainPageOf(
        "$mainUrl" to "Início",
        "$mainUrl/filmes" to "Filmes",
        "$mainUrl/series" to "Séries"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data).document
        val items = mutableListOf<SearchResponse>()

        when {
            request.data == mainUrl -> {
                // Página inicial: extrai das seções "Filmes em Alta" e "Séries em Alta"
                items.addAll(extractMoviesFromSection(doc, "Filmes em Alta"))
                items.addAll(extractSeriesFromSection(doc, "Séries em Alta"))
            }
            request.data.contains("/filmes") -> {
                // Página de filmes
                items.addAll(extractMoviesFromSection(doc, "Filmes"))
            }
            request.data.contains("/series") -> {
                // Página de séries
                items.addAll(extractSeriesFromSection(doc, "Séries"))
            }
        }

        return newHomePageResponse(request.name, items, false)
    }

    private fun extractMoviesFromSection(doc: org.jsoup.nodes.Document, sectionTitle: String): List<SearchResponse> {
        val movies = mutableListOf<SearchResponse>()
        
        // Procura os cards de filmes
        val cards = doc.select("div.group\\/card")
        cards.forEach { card ->
            extractFromCard(card)?.let { movies.add(it) }
        }
        
        return movies
    }

    private fun extractSeriesFromSection(doc: org.jsoup.nodes.Document, sectionTitle: String): List<SearchResponse> {
        val series = mutableListOf<SearchResponse>()
        
        // Procura os cards de séries
        val cards = doc.select("div.group\\/card")
        cards.forEach { card ->
            extractFromCard(card)?.let { series.add(it) }
        }
        
        return series
    }

    private fun extractFromCard(card: Element): SearchResponse? {
        // Extrai os dados do atributo onclick que contém o JSON
        val onclick = card.selectFirst("[onclick]")?.attr("onclick") ?: return null
        val jsonData = extractJsonFromOnclick(onclick) ?: return null
        
        val title = extractJsonValue(jsonData, "name") ?: 
                   extractJsonValue(jsonData, "title") ?: return null
        val id = extractJsonValue(jsonData, "stream_id") ?: return null
        val streamType = extractJsonValue(jsonData, "stream_type")
        
        val isSerie = streamType == "series"
        val tvType = if (isSerie) TvType.TvSeries else TvType.Movie
        
        val poster = extractJsonValue(jsonData, "stream_icon")?.let { fixUrl(it) }
        val year = extractJsonValue(jsonData, "year")?.toIntOrNull()
        
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
        val url = if (isSerie) {
            "$mainUrl/series/$id"
        } else {
            "$mainUrl/movie/$id"
        }

        return newMovieSearchResponse(cleanTitle, url, tvType) {
            this.posterUrl = poster
            this.year = year
        }
    }

    private fun extractJsonFromOnclick(onclick: String): String? {
        // Extrai o JSON do onclick: openContent(JSON.parse(decodeURIComponent('...')))
        val regex = "openContent\\(JSON.parse\\(decodeURIComponent\\(['\"](.*?)['\"]\\)\\)\\)".toRegex()
        val matchResult = regex.find(onclick)
        
        return matchResult?.groupValues?.get(1)?.let { encodedJson ->
            java.net.URLDecoder.decode(encodedJson, "UTF-8")
        }
    }

    private fun extractJsonValue(json: String, key: String): String? {
        val regex = "\"$key\"\\s*:\\s*\"?([^\",}]+)\"?".toRegex()
        return regex.find(json)?.groupValues?.get(1)?.replace("\\\"", "\"")?.trim()
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Busca usando a URL com query
        val searchUrl = "$mainUrl?search=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val doc = app.get(searchUrl).document
        
        return doc.select("div.group\\/card").mapNotNull { card ->
            extractFromCard(card)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        
        // Extrai o JSON do onclick do elemento principal
        val mainCard = doc.selectFirst("[onclick*='openContent']")
        val onclick = mainCard?.attr("onclick") ?: return null
        val jsonData = extractJsonFromOnclick(onclick) ?: return null
        
        val title = extractJsonValue(jsonData, "name") ?: 
                   extractJsonValue(jsonData, "title") ?: return null
        val id = extractJsonValue(jsonData, "stream_id") ?: return null
        val streamType = extractJsonValue(jsonData, "stream_type")
        
        val isSerie = streamType == "series"
        val poster = extractJsonValue(jsonData, "stream_icon")?.let { fixUrl(it) }
        val year = extractJsonValue(jsonData, "year")?.toIntOrNull()
        
        // Tenta extrair sinopse da página
        val plot = doc.selectFirst("p:contains(Sinopse)")?.nextElementSibling()?.text() ?:
                   doc.selectFirst(".description, .plot")?.text()
        
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
        val videoUrl = "$mainUrl/player/$id"
        
        return if (isSerie) {
            // Para séries, extrai os episódios
            val episodes = extractEpisodes(doc, id.toIntOrNull() ?: 0)
            newTvSeriesLoadResponse(cleanTitle, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(cleanTitle, url, TvType.Movie, videoUrl) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
            }
        }
    }

    private suspend fun extractEpisodes(document: org.jsoup.nodes.Document, seriesId: Int): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        // Tenta encontrar os episódios na página
        val episodeElements = document.select("[data-episode], .episode-item, .season-episodes a")
        
        episodeElements.forEachIndexed { index, element ->
            val episodeNum = element.attr("data-episode").toIntOrNull() ?: (index + 1)
            val seasonNum = element.attr("data-season").toIntOrNull() ?: 1
            
            val episodeUrl = element.attr("href").ifEmpty { 
                "$mainUrl/player/$seriesId?season=$seasonNum&episode=$episodeNum"
            }
            
            episodes.add(newEpisode(episodeUrl) {
                name = element.selectFirst(".episode-name, .title")?.text() ?: "Episódio $episodeNum"
                season = seasonNum
                episode = episodeNum
            })
        }
        
        return episodes.sortedBy { it.episode }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Implementar extração de links do player posteriormente
        return false
    }
}
