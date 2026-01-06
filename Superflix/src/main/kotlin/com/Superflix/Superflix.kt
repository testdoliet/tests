package com.Superflix

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.SubtitleFile
import org.jsoup.nodes.Element

class SuperflixMain : MainAPI() {

    override var mainUrl = "https://superflix1.cloud"
    override var name = "Superflix"
    override var lang = "pt"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val usesWebView = false

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    companion object {

        /** Abas fixas */
        private val FIXED_TABS = listOf(
            "/" to "Filmes Recomendados",
            "/" to "Séries Recomendadas",
            "/category/lancamentos/" to "Lançamentos"
        )

        /** Gêneros reais do site */
        private val GENRE_URLS = listOf(
            "/category/acao/" to "Ação",
            "/category/action-adventure/" to "Action & Adventure",
            "/category/animacao/" to "Animação",
            "/category/aventura/" to "Aventura",
            "/category/cinema-tv/" to "Cinema TV",
            "/category/comedia/" to "Comédia",
            "/category/crime/" to "Crime",
            "/category/documentario/" to "Documentário",
            "/category/drama/" to "Drama",
            "/category/familia/" to "Família",
            "/category/fantasia/" to "Fantasia",
            "/category/faroeste/" to "Faroeste",
            "/category/ficcao-cientifica/" to "Ficção Científica",
            "/category/guerra/" to "Guerra",
            "/category/historia/" to "História",
            "/category/kids/" to "Kids",
            "/category/misterio/" to "Mistério",
            "/category/musica/" to "Música",
            "/category/news/" to "News",
            "/category/reality/" to "Reality",
            "/category/romance/" to "Romance",
            "/category/sci-fi-fantasy/" to "Sci-Fi & Fantasy",
            "/category/soap/" to "Soap",
            "/category/talk/" to "Talk",
            "/category/terror/" to "Terror",
            "/category/thriller/" to "Thriller",
            "/category/war-politics/" to "War & Politics"
        )
    }

    override val mainPage = mainPageOf(
        *FIXED_TABS.map { "$mainUrl${it.first}" to it.second }.toTypedArray(),
        *GENRE_URLS.map { "$mainUrl${it.first}" to it.second }.toTypedArray()
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        /** Recomendados vêm apenas da HOME (sem paginação) */
        if (request.name == "Filmes Recomendados" || request.name == "Séries Recomendadas") {

            val document = app.get(mainUrl).document

            val selector = when (request.name) {
                "Filmes Recomendados" ->
                    "#widget_list_movies_series-6 article.post"
                "Séries Recomendadas" ->
                    "#widget_list_movies_series-8 article.post"
                else -> "article.post"
            }

            val items = document.select(selector)
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }

            return newHomePageResponse(
                list = HomePageList(
                    name = request.name,
                    list = items,
                    isHorizontalImages = false
                ),
                hasNext = false
            )
        }

        /** Gêneros e lançamentos COM paginação */
        val url = if (page == 1) {
            request.data
        } else {
            "${request.data}page/$page/"
        }

        val document = app.get(url).document

        val items = document.select("article.post")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        val hasNext = document.select("a.next.page-numbers").isNotEmpty()

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = false
            ),
            hasNext = hasNext
        )
    }

    /** Search */
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = app.get(url).document

        return document.select("article.post")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    /** Load */
    override suspend fun load(url: String): LoadResponse {

        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()
            ?: throw ErrorLoadingException("Título não encontrado")

        val poster = document.selectFirst("div.post-thumbnail img")
            ?.attr("src")
            ?.let { fixUrl(it) }

        val year = document.selectFirst("span.year")?.text()?.toIntOrNull()
        val plot = document.selectFirst("div.description p")?.text()
        val tags = document.select("span.genres a").map { it.text() }

        val isMovie = url.contains("/filme/")

        return if (isMovie) {

            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
            }

        } else {

            val episodes = listOf(
                newEpisode(url) {
                    name = "Assistir Série"
                    season = 1
                    episode = 1
                }
            )

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
            }
        }
    }

    /** Ainda não implementado (streams) */
    override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    return false
    }

@CloudstreamPlugin
class SuperflixPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SuperflixMain())
    }
}
