package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.M3U8 // M3U8 import desnecessário, mas não causa erro
import org.jsoup.nodes.Element

class SuperFlix : MainAPI() {
    override var mainUrl = "https://superflix20.lol"
    override var name = "SuperFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("a") ?: return null
        val href = fixUrl(link.attr("href"))

        val title = link.selectFirst("img")?.attr("alt")?.trim()
            ?: link.selectFirst("h3")?.text()?.trim()
            ?: return null

        val posterUrl = link.selectFirst("img")?.attr("src")

        val type = when {
            href.contains("/filme/") -> TvType.Movie
            href.contains("/serie/") -> TvType.TvSeries
            else -> TvType.Movie
        }

        // CORREÇÃO CRÍTICA (Erro: Interface SearchResponse does not have constructors)
        // Usamos o helper específico (newMovieSearchResponse ou newTvSeriesSearchResponse)
        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
            }
        } else {
            newTvSeriesSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
            }
        }
    }

    private suspend fun getListItems(url: String): List<SearchResponse> {
        val document = app.get(url).document
        return document.select("div.lista-de-filmes-e-series > a").mapNotNull { it.toSearchResult() }
    }

    override val mainPage = mainPageOf(
        "/lancamentos?page=1&f=filmes" to "Lançamentos (Filmes)",
        "/lancamentos?page=1&f=series" to "Lançamentos (Séries)",
        "/filmes?page=1" to "Filmes Populares",
        "/series?page=1" to "Séries Populares"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl${request.data}"
        } else {
            val baseUrl = "$mainUrl${request.data.substringBeforeLast("page=")}page=$page"
            baseUrl
        }

        val home = getListItems(url)

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true

