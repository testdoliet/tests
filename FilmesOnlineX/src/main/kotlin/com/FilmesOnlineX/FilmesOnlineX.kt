package com.FilmesOnlineX

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat

class FilmesOnlineX : MainAPI() {
    override var mainUrl = "https://filmesonlinex.wf/"
    override var name = "Filmes Online X"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val usesWebView = true

    companion object {
        private const val SEARCH_PATH = "/?s"

        private val ALL_CATEGORIES = listOf(
            "/movies" to "Filmes",
            "/series" to "Séries",
            "/lancamentos" to "Lançamentos",
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
            "/category/ficcao-cientifica/" to "Ficção científica",
            "/category/guerra/" to "Guerra",
            "/category/historia/" to "História",
            "/category/misterio/" to "Mistério",
            "/category/musica/" to "Música",
            "/category/romance/" to "Romance",
            "/category/sci-fi-fantasy/" to "Sci-Fi & Fantasy",
            "/category/terror/" to "Terror",
            "/category/thriller/" to "Thriller",
            "/category/war-politics/" to "War & Politics"
        )
    }

    override val mainPage = mainPageOf(
        *ALL_CATEGORIES.map { (path, name) -> 
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
        val items = document.select("ul.MovieList.Rows > li.TPostMv > article.TPost.B").mapNotNull { element ->
            element.toSearchResult()
        }

        val hasNextPage = document.select("a:contains(Próxima), .wp-pagenavi .next, .pagination a[href*='page']").isNotEmpty()

        return newHomePageResponse(request.name, items, hasNextPage)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = selectFirst("a[href]") ?: return null
        val href = fixUrl(linkElement.attr("href"))
        val title = linkElement.selectFirst("h2.Title")?.text() ?: return null

        val poster = selectFirst("img[src]")?.attr("src")?.let { fixUrl(it) }
        val yearElement = selectFirst(".Qlty.Yr")
        val year = yearElement?.text()?.toIntOrNull()
        val cleanTitle = title.replace(Regex("\\s*\\(\\d{4}\\)"), "").trim()

        val urlLower = href.lowercase()
        val isSerie = urlLower.contains("/series/") || urlLower.contains("/serie/")
        
        return if (isSerie) {
            newTvSeriesSearchResponse(cleanTitle, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(cleanTitle, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl$SEARCH_PATH=${java.net.URLEncoder.encode(query, "UTF-8")}"
        
        return try {
            val document = app.get(searchUrl).document
            document.select("ul.MovieList.Rows > li.TPostMv > article.TPost.B").mapNotNull { element ->
                element.toSearchResult()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.Title, h2.Title")?.text() ?: return null
        val cleanTitle = title.replace(Regex("\\s*\\(\\d{4}\\)"), "").trim()
        
        val year = document.selectFirst(".Info .Date")?.text()?.toIntOrNull()
        
        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) } ?:
                    document.selectFirst("img[src*='tmdb.org']")?.attr("src")?.let { fixUrl(it) }
        
        val plot = document.selectFirst(".Description p")?.text()?.trim()
        
        val tags = document.select(".Genre a").map { it.text() }.takeIf { it.isNotEmpty() }
        
        val castItems = document.select(".Cast a").map { it.text() }.takeIf { it.isNotEmpty() }

        val isSerie = url.contains("/series/") || document.selectFirst(".SeasonBx") != null

        val recommendations = document.select(".MovieList .TPost.B").mapNotNull { element ->
            element.toSearchResult()
        }.take(20)

        return if (isSerie) {
            val episodes = extractEpisodes(document)
            
            newTvSeriesLoadResponse(cleanTitle, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                castItems?.let { addActors(it.map { name -> Actor(name) }) }
                this.recommendations = recommendations
            }
        } else {
            val playerUrl = extractPlayerUrl(document) ?: url
            
            newMovieLoadResponse(cleanTitle, url, TvType.Movie, playerUrl) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                castItems?.let { addActors(it.map { name -> Actor(name) }) }
                this.recommendations = recommendations
            }
        }
    }

    private fun extractEpisodes(document: org.jsoup.nodes.Document): List<Episode> {
        val episodes = mutableListOf<Episode>()

        val seasonBoxes = document.select(".SeasonBx")
        
        if (seasonBoxes.isNotEmpty()) {
            seasonBoxes.forEach { seasonBox ->
                val seasonNumber = seasonBox.selectFirst(".Title span")?.text()?.toIntOrNull() ?: 1
                
                val episodeRows = seasonBox.select(".TPTblCn tbody tr")
                
                episodeRows.forEach { row ->
                    try {
                        val numberElement = row.selectFirst("td span.Num")
                        val episodeNumber = numberElement?.text()?.toIntOrNull()
                        
                        val linkElement = row.selectFirst("td.MvTbImg a[href]") ?: 
                                         row.selectFirst("td.MvTbTtl a[href]")
                        val episodeUrl = linkElement?.attr("href")?.let { fixUrl(it) }
                        
                        val titleElement = row.selectFirst("td.MvTbTtl a")
                        val episodeTitle = titleElement?.text()?.trim()
                        
                        val dateElement = row.selectFirst("td.MvTbTtl span")
                        val dateText = dateElement?.text()?.trim()
                        
                        val poster = row.selectFirst("td.MvTbImg img")?.attr("src")?.let { fixUrl(it) }

                        if (episodeUrl != null && episodeNumber != null) {
                            val episode = newEpisode(episodeUrl) {
                                this.name = episodeTitle ?: "Episódio $episodeNumber"
                                this.season = seasonNumber
                                this.episode = episodeNumber
                                this.posterUrl = poster
                                
                                dateText?.let {
                                    try {
                                        val formats = listOf("dd-MM-yyyy", "yyyy-MM-dd", "dd/MM/yyyy")
                                        for (format in formats) {
                                            try {
                                                val date = SimpleDateFormat(format).parse(it)
                                                this.date = date.time
                                                break
                                            } catch (e: Exception) {}
                                        }
                                    } catch (e: Exception) {}
                                }
                            }
                            episodes.add(episode)
                        }
                    } catch (e: Exception) {
                        // Ignora erro em um episódio específico
                    }
                }
            }
        }

        return episodes.distinctBy { it.url }
    }

    private fun extractPlayerUrl(document: org.jsoup.nodes.Document): String? {
        val playButton = document.selectFirst("a.Button.TPlay[href]")
        if (playButton != null) {
            return playButton.attr("href")
        }

        val iframe = document.selectFirst("iframe[src*='player'], iframe[src*='embed'], iframe[src*='video']")
        if (iframe != null) {
            return iframe.attr("src")
        }

        val videoLink = document.selectFirst("a[href*='.m3u8'], a[href*='.mp4']")
        return videoLink?.attr("href")
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
