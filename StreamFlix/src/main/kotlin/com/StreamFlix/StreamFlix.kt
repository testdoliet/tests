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

    private val apiUrl = "$mainUrl/api_proxy.php"
    private val tmdbImageUrl = "https://image.tmdb.org/t/p"

    companion object {
        // Categorias da página inicial
        private val homeCategories = listOf(
            // Usa a API para trending (funciona)
            "tmdb_trending" to "Em Alta",
            // Usa as páginas HTML para filmes e séries
            "html_movies" to "Filmes",
            "html_series" to "Séries"
        )
    }

    override val mainPage = mainPageOf(
        *homeCategories.map { (category, name) ->
            when (category) {
                "tmdb_trending" -> "$apiUrl?action=$category" to name
                "html_movies" -> "$mainUrl/filmes" to name
                "html_series" -> "$mainUrl/series" to name
                else -> "" to name
            }
        }.toTypedArray()
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<SearchResponse>()
        
        when {
            // Para a aba "Em Alta" - usa a API (funciona)
            request.data.contains("tmdb_trending") -> {
                val url = if (page > 1) "$request.data&page=$page" else request.data
                val response = app.get(url)
                items.addAll(parseTrendingJson(response.text))
            }
            // Para a aba "Filmes" - extrai do HTML
            request.data.contains("/filmes") -> {
                val doc = app.get(request.data).document
                items.addAll(extractMoviesFromHtml(doc))
            }
            // Para a aba "Séries" - extrai do HTML
            request.data.contains("/series") -> {
                val doc = app.get(request.data).document
                items.addAll(extractSeriesFromHtml(doc))
            }
        }

        return newHomePageResponse(request.name, items, false)
    }

    private fun parseTrendingJson(json: String): List<SearchResponse> {
        return try {
            val response = mapper.readValue(json, TrendingResponse::class.java)
            val items = mutableListOf<SearchResponse>()
            
            response.movies?.forEach { movie ->
                movie.toSearchResponse()?.let { items.add(it) }
            }
            
            response.series?.forEach { series ->
                series.toSearchResponse()?.let { items.add(it) }
            }
            
            items
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun extractMoviesFromHtml(doc: org.jsoup.nodes.Document): List<SearchResponse> {
        val movies = mutableListOf<SearchResponse>()
        
        // Procura os cards na página de filmes
        val cards = doc.select("div.group\\/card")
        cards.forEach { card ->
            extractFromCard(card)?.let { movies.add(it) }
        }
        
        return movies
    }

    private fun extractSeriesFromHtml(doc: org.jsoup.nodes.Document): List<SearchResponse> {
        val series = mutableListOf<SearchResponse>()
        
        // Procura os cards na página de séries
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

    private fun TMDBMovie.toSearchResponse(): SearchResponse? {
        val title = title ?: return null
        val year = release_date?.substring(0, 4)?.toIntOrNull()
        val poster = poster_path?.let { "$tmdbImageUrl/w500$it" }
        val url = "$mainUrl/movie/$id"

        return newMovieSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = poster
            this.year = year
        }
    }

    private fun TMDBSeries.toSearchResponse(): SearchResponse? {
        val title = name ?: return null
        val year = first_air_date?.substring(0, 4)?.toIntOrNull()
        val poster = poster_path?.let { "$tmdbImageUrl/w500$it" }
        val url = "$mainUrl/series/$id"

        return newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
            this.posterUrl = poster
            this.year = year
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Busca usando a página com query
        val searchUrl = "$mainUrl?search=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val doc = app.get(searchUrl).document
        
        return doc.select("div.group\\/card").mapNotNull { card ->
            extractFromCard(card)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        
        // Extrai o JSON do onclick
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
        return false
    }

    // Classes de dados para a API
    data class TrendingResponse(
        @JsonProperty("movies") val movies: List<TMDBMovie>?,
        @JsonProperty("series") val series: List<TMDBSeries>?
    )

    data class TMDBMovie(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: String?,
        @JsonProperty("release_date") val release_date: String?,
        @JsonProperty("poster_path") val poster_path: String?
    )

    data class TMDBSeries(
        @JsonProperty("id") val id: Int,
        @JsonProperty("name") val name: String?,
        @JsonProperty("first_air_date") val first_air_date: String?,
        @JsonProperty("poster_path") val poster_path: String?
    )
}
