package com.Superflix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty

class SuperflixProvider : MainAPI() {
    override var mainUrl = "https://superflix1.cloud"
    override var name = "Superflix"
    override val hasMainPage = true
    override var lang = "pt"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val usesWebView = false

    companion object {
        private val GENRE_URLS = mapOf(
            "Ação" to "https://superflix1.cloud/category/acao/",
            "Animação" to "https://superflix1.cloud/category/animacao/",
            "Aventura" to "https://superflix1.cloud/category/aventura/",
            "Action & Adventure" to "https://superflix1.cloud/category/action-adventure/",
            "Cinema TV" to "https://superflix1.cloud/category/cinema-tv/",
            "Crime" to "https://superflix1.cloud/category/crime/",
            "Fantasia" to "https://superflix1.cloud/category/fantasia/",
            "Ficção Científica" to "https://superflix1.cloud/category/ficcao-cientifica/",
            "História" to "https://superflix1.cloud/category/historia/",
            "Mistério" to "https://superflix1.cloud/category/misterio/",
            "Romance" to "https://superflix1.cloud/category/romance/",
            "News" to "https://superflix1.cloud/category/news/",
            "Soap" to "https://superflix1.cloud/category/soap/",
            "Terror" to "https://superflix1.cloud/category/terror/",
            "War & Politics" to "https://superflix1.cloud/category/war-politics/",
            "Talk" to "https://superflix1.cloud/category/talk/",
            "Thriller" to "https://superflix1.cloud/category/thriller/",
            "Sci-fi & Fantasy" to "https://superflix1.cloud/category/sci-fi-fantasy/",
            "Reality" to "https://superflix1.cloud/category/reality/",
            "Música" to "https://superflix1.cloud/category/musica/",
            "Kids" to "https://superflix1.cloud/category/kids/",
            "Guerra" to "https://superflix1.cloud/category/guerra/",
            "Faroeste" to "https://superflix1.cloud/category/faroeste/",
            "Família" to "https://superflix1.cloud/category/familia/",
            "Documentário" to "https://superflix1.cloud/category/documentario/",
            "Comédia" to "https://superflix1.cloud/category/comedia/",
            "Drama" to "https://superflix1.cloud/category/drama/"
        )

        private val FIXED_TABS = listOf(
            "https://superflix1.cloud/filmes" to "Últimos Filmes",
            "https://superflix1.cloud/series" to "Últimas Séries",
            "https://superflix1.cloud/category/lancamentos/?type=movies" to "Lançamentos Filmes",
            "https://superflix1.cloud/category/lancamentos/?type=series" to "Lançamentos Séries"
        )
    }

    override val mainPage = mainPageOf(
        *FIXED_TABS.toTypedArray(),
        *GENRE_URLS.map { (name, url) -> url to name }.toTypedArray()
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page > 1) {
            "${request.data}page/$page/"
        } else {
            request.data
        }

        val document = app.get(url).document
        
        val home = document.select("article.post, .post-lst li, .owl-item article, .post").mapNotNull { element ->
            element.toSearchResult()
        }.distinctBy { it.url }
        
        val hasNext = document.select("a.next.page-numbers, a[rel='next'], .pagination a:contains(›)").isNotEmpty()

        return newHomePageResponse(
            list = HomePageList(request.name, home, isHorizontalImages = false),
            hasNext = hasNext
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("h2.entry-title, .entry-title, h2")?.text() ?: return null
        
        val href = selectFirst("a.lnk-blk, a[href]")?.attr("href") ?: return null
        
        val posterUrl = selectFirst("img")?.attr("src")?.let { fixUrl(it) }
        
        val year = selectFirst("span.year, .year")?.text()?.toIntOrNull()
        
        val tmdbRating = selectFirst("span.vote, .vote")?.text()?.substringBefore(" ")?.toFloatOrNull()
        val rating = tmdbRating?.times(1000)?.toInt()
        
        val isMovie = href.contains("/filme/") || selectFirst(".watch")?.text()?.contains("Ver Filme") == true
        val isSeries = href.contains("/serie/") || selectFirst(".watch")?.text()?.contains("Ver Série") == true
        
        return when {
            isMovie -> {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = posterUrl
                    this.year = year
                    this.rating = rating
                }
            }
            isSeries -> {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                    this.year = year
                    this.rating = rating
                }
            }
            else -> null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.encodeCleanQuery()}"
        val document = app.get(searchUrl).document
        
        return document.select("article.post, .post").mapNotNull { 
            it.toSearchResult() 
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1.entry-title")?.text() ?: throw ErrorLoadingException("Título não encontrado")
        val poster = document.selectFirst("div.post-thumbnail img, .post-thumbnail img")?.attr("src")?.let { fixUrl(it) }
        val background = document.selectFirst(".TPostBg, .bghd img, .bgft img")?.attr("src")?.let { fixUrl(it) }
        
        val year = document.selectFirst("span.year, .year")?.text()?.toIntOrNull()
        val duration = document.selectFirst("span.duration, .duration")?.text()?.parseDuration()
        val description = document.selectFirst("div.description p, .description p, .sinopse")?.text()
        
        val tmdbRating = document.selectFirst("span.vote span.num, .vote .num")?.text()?.toFloatOrNull()
        val rating = tmdbRating?.times(1000)?.toInt()
        
        val genres = document.select("span.genres a, .genres a, a[href*='/category/']").map { it.text() }
            .filter { it.isNotBlank() && it.length < 30 }
        
        val directors = mutableListOf<String>()
        val actors = mutableListOf<Actor>()
        
        document.select("ul.cast-lst li, .cast-lst li").forEach { li ->
            val spanText = li.selectFirst("span")?.text()?.lowercase() ?: ""
            when {
                spanText.contains("diretor") -> {
                    li.select("a").forEach { a ->
                        directors.add(a.text())
                    }
                }
                spanText.contains("elenco") -> {
                    li.select("a").take(10).forEach { a ->
                        actors.add(Actor(a.text()))
                    }
                }
            }
        }
        
        val recommendations = document.select("section.episodes article.post, .owl-item article, .post").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }
        
        val isMovie = url.contains("/filme/")
        
        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.year = year
                this.plot = description
                this.duration = duration
                this.rating = rating
                this.tags = genres
                this.recommendations = recommendations
                if (directors.isNotEmpty()) this.directors = directors
                if (actors.isNotEmpty()) addActors(actors)
            }
        } else {
            val episodes = extractEpisodes(document, url)
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.year = year
                this.plot = description
                this.rating = rating
                this.tags = genres
                this.recommendations = recommendations
                if (directors.isNotEmpty()) this.directors = directors
                if (actors.isNotEmpty()) addActors(actors)
            }
        }
    }

    private fun extractEpisodes(document: org.jsoup.nodes.Document, url: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        val episodeElements = document.select("div.episode, .episode-item, [class*='episode']")
        
        if (episodeElements.isNotEmpty()) {
            episodeElements.forEachIndexed { index, element ->
                try {
                    val epTitle = element.selectFirst("h3, h4, .title, span")?.text() ?: "Episódio ${index + 1}"
                    val epData = element.selectFirst("a")?.attr("href") ?: url
                    val epNumber = element.selectFirst("span.ep-num, .number, .episode")?.text()?.toIntOrNull() ?: (index + 1)
                    val season = element.selectFirst("span.se-num, .season")?.text()?.toIntOrNull() ?: 1
                    
                    episodes.add(
                        newEpisode(epData) {
                            this.name = epTitle
                            this.season = season
                            this.episode = epNumber
                        }
                    )
                } catch (e: Exception) {
                    // Ignora erro
                }
            }
        } else {
            episodes.add(
                newEpisode(url) {
                    this.name = "Assistir Série"
                    this.season = 1
                    this.episode = 1
                }
            )
        }
        
        return episodes
    }

    private fun String.parseDuration(): Int? {
        return try {
            val pattern = """(\d+)h\s*(\d+)?m?""".toRegex()
            val match = pattern.find(this)
            
            match?.let {
                val hours = it.groups[1]?.value?.toIntOrNull() ?: 0
                val minutes = it.groups[2]?.value?.toIntOrNull() ?: 0
                hours * 60 + minutes
            }
        } catch (e: Exception) {
            null
        }
    }

    // Função de encode para queries
    private fun String.encodeCleanQuery(): String {
        return this.replace(" ", "+")
    }

    // loadLinks desativado por enquanto
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return false
    }
}
