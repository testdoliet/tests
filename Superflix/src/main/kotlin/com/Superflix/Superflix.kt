package com.Superflix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import org.jsoup.nodes.Element
import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

class SuperflixMain : MainAPI() {
    override var mainUrl = "https://superflix1.cloud"
    override var name = "Superflix"
    override val hasMainPage = true
    override var lang = "pt"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val usesWebView = false

    companion object {

        /** TODAS as categorias reais do site (sem repetir) */
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

        /** Abas fixas da HOME */
        private val FIXED_TABS = listOf(
            "/" to "Filmes Recomendados",
            "/" to "Séries Recomendadas",
            "/category/lancamentos/" to "Lançamentos"
        )
    }

    override val mainPage = mainPageOf(
        *FIXED_TABS.map { (path, name) -> "$mainUrl$path" to name }.toTypedArray(),
        *GENRE_URLS.map { (path, name) -> "$mainUrl$path" to name }.toTypedArray()
    )

    
    override suspend fun getMainPage(
    page: Int,
    request: MainPageRequest
): HomePageResponse {

    // Recomendados NÃO têm paginação (HOME)
    if (request.name == "Filmes Recomendados" || request.name == "Séries Recomendadas") {

        val document = app.get(mainUrl).document

        val selector = if (request.name == "Filmes Recomendados") {
            "#widget_list_movies_series-6 article.post"
        } else {
            "#widget_list_movies_series-8 article.post"
        }

        val home = document.select(selector)
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(
            list = HomePageList(request.name, home, isHorizontalImages = false),
            hasNext = false
        )
    }

    // --- PAGINAÇÃO CORRETA ---
    val baseUrl = if (request.data.endsWith("/")) {
        request.data
    } else {
        "${request.data}/"
    }

    val url = if (page > 1) {
        "${baseUrl}page/$page/"
    } else {
        baseUrl
    }

    val document = app.get(url).document

    val home = document.select("article.post")
        .mapNotNull { it.toSearchResult() }
        .distinctBy { it.url }

    val hasNext = document.select(
        "a.next.page-numbers, .page-numbers a"
    ).isNotEmpty()

    return newHomePageResponse(
        list = (HomePageList(request.name, home, isHorizontalImages = false)),
        hasNext = hasNext
    )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("h2.entry-title")?.text() ?: return null
        val href = selectFirst("a.lnk-blk")?.attr("href") ?: return null
        val posterUrl = selectFirst("img")?.attr("src")?.let { fixUrl(it) }
        val year = selectFirst("span.year")?.text()?.toIntOrNull()

        return when {
            href.contains("/filme/") ->
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = posterUrl
                    this.year = year
                }

            href.contains("/serie/") ->
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                    this.year = year
                }

            else -> null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = app.get(searchUrl).document

        return document.select("article.post")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()
            ?: throw ErrorLoadingException("Título não encontrado")

        val poster = document.selectFirst("div.post-thumbnail img")
            ?.attr("src")?.let { fixUrl(it) }

        val year = document.selectFirst("span.year")?.text()?.toIntOrNull()
        val description = document.selectFirst("div.description p")?.text()
        val genres = document.select("span.genres a").map { it.text() }.filter { it.isNotBlank() }

        return if (url.contains("/filme/")) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = genres
            }
        } else {
            val episodes = listOf(
                newEpisode(url) {
                    this.name = "Assistir Série"
                    this.season = 1
                    this.episode = 1
                }
            )

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = genres
            }
        }
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

@CloudstreamPlugin
class SuperflixPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SuperflixMain())
    }
}
