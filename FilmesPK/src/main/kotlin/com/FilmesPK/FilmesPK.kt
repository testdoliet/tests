package com.FilmesPK

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class FilmesPK : MainAPI() {
    override var mainUrl = "https://www.filmesrave.online"
    override var name = "Filmes P K"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override val mainPage = mainPageOf(
        "$mainUrl" to "Início",
        "$mainUrl/search/label/Filmes" to "Filmes",
        "$mainUrl/search/label/Séries" to "Séries",
        "$mainUrl/search/label/Animes" to "Animes",
        "$mainUrl/search/label/Lançamentos" to "Lançamentos"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val home = mutableListOf<HomePageList>()

        // Para a página inicial, processamos os carrosséis
        if (request.data == mainUrl) {
            document.select(".stream-carousel").forEach { carousel ->
                val title = carousel.previousElementSibling()?.selectFirst(".stream-title, h2")?.text() 
                    ?: carousel.attr("id").replace("-carousel", "").capitalize()
                val items = carousel.select(".stream-card").mapNotNull { it.toSearchResult() }
                if (items.isNotEmpty()) {
                    home.add(HomePageList(title, items))
                }
            }
            
            // Também pegamos movie-cards avulsos se houver
            val otherItems = document.select(".movie-card").mapNotNull { it.toSearchResult() }
            if (otherItems.isNotEmpty()) {
                home.add(HomePageList("Destaques", otherItems))
            }
        } else {
            // Para páginas de categoria/label
            val items = document.select(".stream-card, .movie-card, .post").mapNotNull { it.toSearchResult() }
            home.add(HomePageList(request.name, items))
        }

        return newHomePageResponse(home, request.data == mainUrl && document.select(".blog-pager-older-link").isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst(".stream-name, .movie-title, h2")?.text() 
            ?: selectFirst("img")?.attr("alt") 
            ?: return null
        val href = selectFirst("a.stream-btn, a.movie-link, a")?.attr("href") ?: return null
        val posterUrl = selectFirst("img")?.attr("src")

        val isSerie = title.contains("Série", ignoreCase = true) || 
                      select(".stream-genre").any { it.text().contains("Série", ignoreCase = true) }
        val isAnime = title.contains("Anime", ignoreCase = true) || 
                      select(".stream-genre").any { it.text().contains("Anime", ignoreCase = true) }

        return when {
            isAnime -> newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) { this.posterUrl = posterUrl }
            isSerie -> newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) { this.posterUrl = posterUrl }
            else -> newMovieSearchResponse(title, fixUrl(href), TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(url).document
        return document.select(".stream-card, .movie-card, .post").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.post-title, .stream-name")?.text() ?: return null
        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: document.selectFirst(".post-body img")?.attr("src")
        
        val isSerie = url.contains("Série", ignoreCase = true) || document.select(".post-labels a").any { it.text().contains("Séries", ignoreCase = true) }
        val type = if (isSerie) TvType.TvSeries else TvType.Movie

        return if (isSerie) {
            val episodes = mutableListOf<Episode>()
            // Tenta extrair episódios se houver uma lista ou botões
            document.select("a[href*='episodio'], button[data-url*='episodio']").forEachIndexed { index, element ->
                val epUrl = element.attr("href").ifBlank { element.attr("data-url") }
                episodes.add(newEpisode(epUrl) {
                    this.name = element.text().ifBlank { "Episódio ${index + 1}" }
                    this.episode = index + 1
                })
            }
            
            // Se não achou episódios, trata como um único link (estilo Blogger de série com um player só)
            if (episodes.isEmpty()) {
                episodes.add(newEpisode(url) { this.name = "Assistir" })
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = document.selectFirst(".post-body, .description")?.text()
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = document.selectFirst(".post-body, .description")?.text()
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        // Procura por iframes de vídeo
        document.select("iframe[src*='embed'], iframe[src*='player'], iframe[src*='video']").forEach { iframe ->
            val src = iframe.attr("src")
            loadExtractor(src, data, subtitleCallback, callback)
        }

        // Procura por botões com data-url
        document.select("[data-url]").forEach { element ->
            val url = element.attr("data-url")
            if (url.contains("http")) {
                loadExtractor(url, data, subtitleCallback, callback)
            }
        }

        return true
    }
}
