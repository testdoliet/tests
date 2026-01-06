package com.Superflix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import org.jsoup.nodes.Element
import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

// O provedor principal - MUDEI O NOME PARA SuperflixMain
class SuperflixMain : MainAPI() {
    override var mainUrl = "https://superflix1.cloud"
    override var name = "Superflix"
    override val hasMainPage = true
    override var lang = "pt"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val usesWebView = false

    companion object {
        private val GENRE_URLS = listOf(
            "/category/acao/" to "Ação",
            "/category/animacao/" to "Animação", 
            "/category/aventura/" to "Aventura",
            "/category/comedia/" to "Comédia",
            "/category/drama/" to "Drama",
            "/category/crime/" to "Crime",
            "/category/documentario/" to "Documentário",
            "/category/familia/" to "Família",
            "/category/fantasia/" to "Fantasia",
            "/category/ficcao-cientifica/" to "Ficção Científica",
            "/category/terror/" to "Terror",
            "/category/thriller/" to "Thriller"
        )

        private val FIXED_TABS = listOf(
            "/filmes" to "Últimos Filmes",
            "/series" to "Últimas Séries",
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
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val document = app.get(url).document
        
        val home = document.select("article.post").mapNotNull { element ->
            element.toSearchResult()
        }.distinctBy { it.url }
        
        val hasNext = document.select("a.next.page-numbers").isNotEmpty()

        return newHomePageResponse(
            list = HomePageList(request.name, home, isHorizontalImages = false),
            hasNext = hasNext
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("h2.entry-title")?.text() ?: return null
        val href = selectFirst("a.lnk-blk")?.attr("href") ?: return null
        val posterUrl = selectFirst("img")?.attr("src")?.let { fixUrl(it) }
        val year = selectFirst("span.year")?.text()?.toIntOrNull()
        
        val isMovie = href.contains("/filme/")
        val isSeries = href.contains("/serie/")
        
        return when {
            isMovie -> newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.year = year
            }
            isSeries -> newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.year = year
            }
            else -> null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = app.get(searchUrl).document
        
        return document.select("article.post").mapNotNull { 
            it.toSearchResult() 
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1.entry-title")?.text() ?: throw ErrorLoadingException("Título não encontrado")
        val poster = document.selectFirst("div.post-thumbnail img")?.attr("src")?.let { fixUrl(it) }
        val year = document.selectFirst("span.year")?.text()?.toIntOrNull()
        val description = document.selectFirst("div.description p")?.text()
        val genres = document.select("span.genres a").map { it.text() }.filter { it.isNotBlank() }
        
        val isMovie = url.contains("/filme/")
        
        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
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
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
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

// O plugin - COM NOME DIFERENTE do provedor
@CloudstreamPlugin
class SuperflixPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SuperflixMain())
    }
}
