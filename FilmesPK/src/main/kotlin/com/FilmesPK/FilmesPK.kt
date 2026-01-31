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
        "$mainUrl" to "Top 10 da Semana",
        "$mainUrl/search/label/S%C3%A9rie" to "Série",
        "$mainUrl/search/label/Doramas" to "Doramas",
        "$mainUrl/search/label/Disney" to "Disney",
        "$mainUrl/search/label/Anime" to "Anime",
        "$mainUrl/search/label/A%C3%A7%C3%A3o" to "Ação",
        "$mainUrl/search/label/Anima%C3%A7%C3%A3o" to "Animação",
        "$mainUrl/search/label/Aventura" to "Aventura",
        "$mainUrl/search/label/Com%C3%A9dia" to "Comédia",
        "$mainUrl/search/label/Drama" to "Drama",
        "$mainUrl/search/label/Romance" to "Romance",
        "$mainUrl/search/label/Terror" to "Terror"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val home = mutableListOf<HomePageList>()

        // Para a página inicial (Top 10 da Semana), processamos os carrosséis
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
            val items = document.select(".ntry, .stream-card, .movie-card, .post").mapNotNull { it.toSearchResult() }
            home.add(HomePageList(request.name, items))
        }

        return newHomePageResponse(home, request.data == mainUrl && document.select(".blog-pager-older-link").isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Procura por elementos de artigo (ntry)
        val article = this
        val title = selectFirst(".pTtl a")?.text() 
            ?: selectFirst(".stream-name, .movie-title, h2")?.text() 
            ?: selectFirst("img")?.attr("alt") 
            ?: return null
        
        val href = selectFirst(".pTtl a")?.attr("href")
            ?: selectFirst("a.thmb")?.attr("href")
            ?: selectFirst("a.stream-btn, a.movie-link, a")?.attr("href") 
            ?: return null
        
        val posterUrl = selectFirst("img")?.attr("src")?.let { src ->
            // Remove parâmetros de redimensionamento para obter imagem original
            src.replace("-rw-e90", "").replace("-p-k-no-nu-rw-e90", "-p-k-no-nu")
        }

        val description = selectFirst(".pSnpt")?.text() ?: ""
        val isSerie = title.contains("Série", ignoreCase = true) || 
                      description.contains("Série", ignoreCase = true) ||
                      select(".stream-genre").any { it.text().contains("Série", ignoreCase = true) }
        val isAnime = title.contains("Anime", ignoreCase = true) || 
                      description.contains("Anime", ignoreCase = true) ||
                      select(".stream-genre").any { it.text().contains("Anime", ignoreCase = true) }

        return when {
            isAnime -> newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) { 
                this.posterUrl = posterUrl
            }
            isSerie -> newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) { 
                this.posterUrl = posterUrl
            }
            else -> newMovieSearchResponse(title, fixUrl(href), TvType.Movie) { 
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(url).document
        return document.select(".ntry, .stream-card, .movie-card, .post").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.post-title, .stream-name, .pTtl")?.text() ?: return null
        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: document.selectFirst(".post-body img")?.attr("src")
            ?: document.selectFirst("img.imgThm")?.attr("src")?.let { src ->
                src.replace("-rw-e90", "").replace("-p-k-no-nu-rw-e90", "-p-k-no-nu")
            }
        
        val description = document.selectFirst(".post-body, .description, .pSnpt")?.text()
        
        val isSerie = url.contains("Série", ignoreCase = true) || 
                      document.select(".post-labels a").any { 
                          it.text().contains("Séries", ignoreCase = true) || 
                          it.text().contains("Série", ignoreCase = true)
                      } ||
                      title.contains("Série", ignoreCase = true)

        val type = if (isSerie) TvType.TvSeries else TvType.Movie

        return if (isSerie) {
            val episodes = mutableListOf<Episode>()
            
            // Tenta extrair episódios de diferentes formatos
            // 1. Links de episódios
            document.select("a[href*='episodio'], a[href*='episode'], a[href*='temporada']").forEachIndexed { index, element ->
                val epUrl = element.attr("href")
                if (epUrl.isNotBlank()) {
                    episodes.add(newEpisode(fixUrl(epUrl)) {
                        this.name = element.text().ifBlank { "Episódio ${index + 1}" }
                        this.episode = index + 1
                    })
                }
            }
            
            // 2. Botões com data-url
            document.select("[data-url]").forEachIndexed { index, element ->
                val epUrl = element.attr("data-url")
                if (epUrl.contains("http") && (epUrl.contains("episodio") || epUrl.contains("episode"))) {
                    episodes.add(newEpisode(fixUrl(epUrl)) {
                        this.name = element.text().ifBlank { "Episódio ${index + 1}" }
                        this.episode = index + 1
                    })
                }
            }
            
            // 3. Iframes de vídeo (para séries com um player só)
            document.select("iframe[src*='embed'], iframe[src*='player']").forEachIndexed { index, element ->
                val epUrl = element.attr("src")
                if (epUrl.isNotBlank()) {
                    episodes.add(newEpisode(fixUrl(epUrl)) {
                        this.name = "Episódio ${index + 1}"
                        this.episode = index + 1
                    })
                }
            }

            // Se não achou episódios, trata como um único episódio
            if (episodes.isEmpty()) {
                episodes.add(newEpisode(url) { 
                    this.name = "Assistir"
                })
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = description?.let { 
                    Regex("""\b(19|20)\d{2}\b""").find(it)?.value?.toIntOrNull()
                }
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = description?.let { 
                    Regex("""\b(19|20)\d{2}\b""").find(it)?.value?.toIntOrNull()
                }
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
        
        // 1. Procura por iframes de vídeo
        document.select("iframe[src*='embed'], iframe[src*='player'], iframe[src*='video']").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                loadExtractor(fixUrl(src), data, subtitleCallback, callback)
            }
        }

        // 2. Procura por botões com data-url
        document.select("[data-url]").forEach { element ->
            val url = element.attr("data-url")
            if (url.contains("http")) {
                loadExtractor(fixUrl(url), data, subtitleCallback, callback)
            }
        }

        // 3. Procura por links diretos em players
        document.select("a[href*='player'], a[href*='embed'], a[href*='watch']").forEach { link ->
            val href = link.attr("href")
            if (href.isNotBlank() && href.contains("http")) {
                loadExtractor(fixUrl(href), data, subtitleCallback, callback)
            }
        }

        // 4. Procura por scripts com URLs de vídeo
        document.select("script").forEach { script ->
            val content = script.html()
            val videoUrls = Regex("""(https?://[^"' ]*\.(?:mp4|m3u8|mkv|avi|mov)[^"' ]*)""").findAll(content)
            videoUrls.forEach { match ->
                loadExtractor(fixUrl(match.value), data, subtitleCallback, callback)
            }
        }

        return true
    }
}
