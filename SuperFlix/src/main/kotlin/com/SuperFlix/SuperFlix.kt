package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import java.text.SimpleDateFormat

class SuperFlix : MainAPI() {
    override var mainUrl = "https://superflix30.lol"
    override var name = "SuperFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    companion object {
        private val mainPageCategories = listOf(
            "/categoria/lancamentos/" to "Lançamentos",
            "/assistir-filmes-online/" to "Filmes",
            "/assistir-series-online/" to "Séries"
        )
    }

    override val mainPage = mainPageOf(
        *mainPageCategories.map { (path, name) ->
            "$mainUrl$path" to name
        }.toTypedArray()
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) {
            if (request.data.contains("?")) {
                "${request.data}&page=$page"
            } else {
                "${request.data}?page=$page"
            }
        } else {
            request.data
        }

        val document = app.get(url).document
        val items = document.select("li.post").mapNotNull { it.toSearchResponse() }
        val hasNext = document.select("a.next, .pagination .next, a:contains(Próxima)").isNotEmpty()

        return newHomePageResponse(request.name, items, hasNext)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = selectFirst("a.lnk-blk") ?: return null
        val href = link.attr("href") ?: return null
        val title = selectFirst(".entry-title")?.text()?.trim() ?: return null
        
        // Pega o poster da imagem
        val poster = selectFirst("img")?.attr("src")?.let { fixUrl(it) }
        
        // Pega o ano
        val year = selectFirst(".year")?.text()?.toIntOrNull()
        
        // Determina o tipo pela URL
        val type = when {
            href.contains("/serie/") -> TvType.TvSeries
            href.contains("/filme/") -> TvType.Movie
            else -> TvType.Movie
        }

        return when (type) {
            TvType.TvSeries -> newTvSeriesSearchResponse(title, fixUrl(href), type) {
                this.posterUrl = poster
                this.year = year
            }
            else -> newMovieSearchResponse(title, fixUrl(href), type) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document
        return document.select("li.post").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        // Título
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: return null
        
        // Poster
        val poster = document.selectFirst(".post-thumbnail img")?.attr("src")?.let { fixUrl(it) }
        
        // Sinopse
        val plot = document.selectFirst(".description p")?.text()
        
        // Tags/Gêneros
        val tags = document.select(".genres a").map { it.text() }
        
        // Ano
        val year = document.selectFirst(".year")?.text()?.toIntOrNull()
        
        // Duração (para filmes)
        val durationText = document.selectFirst(".duration")?.text()
        val duration = durationText?.let {
            Regex("(\\d+)h\\s*(\\d+)?m?").find(it)?.let { match ->
                val hours = match.groupValues[1].toIntOrNull() ?: 0
                val minutes = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
                hours * 60 + minutes
            } ?: Regex("(\\d+) min").find(it)?.groupValues?.get(1)?.toIntOrNull()
        }

        // Recomendações
        val recommendations = document.select
