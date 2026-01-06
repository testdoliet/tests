package com.Superflix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import org.jsoup.nodes.Element

class SuperflixProvider : MainAPI() {
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
            "/category/action-adventure/" to "Action & Adventure",
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
            "/category/sci-fi-fantasy/" to "Sci-fi & Fantasy",
            "/category/soap/" to "Soap",
            "/category/talk/" to "Talk",
            "/category/terror/" to "Terror",
            "/category/thriller/" to "Thriller",
            "/category/war-politics/" to "War & Politics"
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
        val url = if (page > 1) {
            "${request.data}page/$page/"
        } else {
            request.data
        }

        val document = app.get(url).document
        
        val home = document.select("article.post, .post-lst li").mapNotNull { element ->
            element.toSearchResult()
        }.distinctBy { it.url }
        
        val hasNext = document.select("a.next.page-numbers, .page-numbers a:contains('›')").isNotEmpty()

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
        
        // Extrai a nota do TMDB
        val tmdbRatingText = selectFirst("span.vote")?.text()
        val tmdbRating = tmdbRatingText?.substringAfter(" ")?.toFloatOrNull()
        val score = tmdbRating?.let { Score(it, name = "TMDB") }
        
        val isMovie = href.contains("/filme/") || selectFirst(".watch")?.text()?.contains("Ver Filme") == true
        val isSeries = href.contains("/serie/") || selectFirst(".watch")?.text()?.contains("Ver Série") == true
        
        return when {
            isMovie -> {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = posterUrl
                    this.year = year
                    this.score = score
                }
            }
            isSeries -> {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                    this.year = year
                    this.score = score
                }
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
        val background = document.selectFirst(".TPostBg")?.attr("src")?.let { fixUrl(it) }
        
        val year = document.selectFirst("span.year")?.text()?.toIntOrNull()
        val duration = document.selectFirst("span.duration")?.text()?.parseDuration()
        val description = document.selectFirst("div.description p")?.text()
        
        // Extrai nota do TMDB
        val tmdbRatingText = document.selectFirst("span.vote span.num")?.text()
        val tmdbRating = tmdbRatingText?.toFloatOrNull()
        val score = tmdbRating?.let { Score(it, name = "TMDB") }
        
        val genres = document.select("span.genres a").map { it.text() }
            .filter { it.isNotBlank() }
        
        val directors = mutableListOf<String>()
        val actors = mutableListOf<Actor>()
        
        document.select("ul.cast-lst li").forEach { li ->
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
        
        val recommendations = document.select("section.episodes article.post, .owl-item article").mapNotNull {
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
                this.score = score
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
                this.score = score
                this.tags = genres
                this.recommendations = recommendations
                if (directors.isNotEmpty()) this.directors = directors
                if (actors.isNotEmpty()) addActors(actors)
            }
        }
    }

    private fun extractEpisodes(document: org.jsoup.nodes.Document, url: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        val episodeElements = document.select("div.episode, .episode-item")
        
        if (episodeElements.isNotEmpty()) {
            episodeElements.forEachIndexed { index, element ->
                try {
                    val epTitle = element.selectFirst("h3, h4, .title")?.text() ?: "Episódio ${index + 1}"
                                        val epData = element.selectFirst("a")?.attr("href") ?: url
                    val epNumber = element.selectFirst("span.ep-num, .number")?.text()?.toIntOrNull() ?: (index + 1)
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
