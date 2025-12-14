package com.AnimeFire

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnimeFire : MainAPI() {
    override var mainUrl = "https://animefire.plus"
    override var name = "AnimeFire"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)
    override val usesWebView = false

    companion object {
        private const val SEARCH_PATH = "/pesquisar"
        private const val ALL_EPISODES_SUFFIX = "-todos-os-episodios"
    }

    override val mainPage = mainPageOf(
        "$mainUrl" to "Lançamentos",
        "$mainUrl/animes" to "Animes Populares",
        "$mainUrl/filmes" to "Filmes de Anime"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        
        val homeItems = when (request.name) {
            "Lançamentos" -> extractLatestReleases(document)
            "Animes Populares" -> extractPopularAnimes(document)
            "Filmes de Anime" -> extractAnimeMovies(document)
            else -> emptyList()
        }
        
        return newHomePageResponse(request.name, homeItems)
    }

    private fun extractLatestReleases(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        
        // 1. Seção "Em lançamento" (Carousel Principal)
        document.select(".divArticleLancamentos a.item").forEach { item ->
            item.toSearchResult()?.let { items.add(it) }
        }
        
        // 2. Seção "Últimos Episódios Adicionados"
        document.select(".divCardUltimosEpsHome").forEach { card ->
            card.toEpisodeSearchResult()?.let { items.add(it) }
        }
        
        // 3. Seção "Destaques da semana"
        document.select(".owl-carousel-semana .divArticleLancamentos a.item").forEach { item ->
            item.toSearchResult()?.let { items.add(it) }
        }
        
        return items.distinctBy { it.url }
    }

    private fun extractPopularAnimes(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        
        // Seção "Últimos animes adicionados"
        document.select(".owl-carousel-l_dia .divArticleLancamentos a.item").forEach { item ->
            item.toSearchResult()?.let { items.add(it) }
        }
        
        // Também incluir animes da seção "Em lançamento"
        document.select(".owl-carousel-home .divArticleLancamentos a.item").forEach { item ->
            item.toSearchResult()?.let { items.add(it) }
        }
        
        return items.distinctBy { it.url }
    }

    private fun extractAnimeMovies(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        
        // Procurar por filmes nas várias seções
        document.select(".divArticleLancamentos a.item").forEach { item ->
            val href = item.attr("href") ?: ""
            val title = item.selectFirst("h3.animeTitle")?.text()?.trim() ?: ""
            
            if (href.contains("/filmes/") || title.contains("Movie", ignoreCase = true)) {
                item.toSearchResult()?.let { items.add(it) }
            }
        }
        
        return items.distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("href") ?: return null
        if (href.isBlank()) return null
        
        val titleElement = selectFirst("h3.animeTitle") ?: return null
        val title = titleElement.text().trim()
        
        // Extrair imagem (lazy loading)
        val imgElement = selectFirst("img.imgAnimes, img.owl-lazy")
        val poster = when {
            imgElement?.hasAttr("data-src") == true -> imgElement.attr("data-src")
            imgElement?.hasAttr("src") == true -> imgElement.attr("src")
            else -> selectFirst("img")?.attr("src")
        } ?: return null
        
        val cleanTitle = title.replace(Regex("(?i)(dublado|legendado|todos os episódios|\\(\\d{4}\\))$"), "").trim()
        
        val isMovie = href.contains("/filmes/") || title.contains("Movie", ignoreCase = true)
        
        return if (isMovie) {
            newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                this.posterUrl = fixUrl(poster)
            }
        } else {
            newAnimeSearchResponse(cleanTitle, fixUrl(href), TvType.Anime) {
                this.posterUrl = fixUrl(poster)
            }
        }
    }

    private fun Element.toEpisodeSearchResult(): SearchResponse? {
        val link = selectFirst("article.card a") ?: return null
        val href = link.attr("href") ?: return null
        if (href.isBlank()) return null
        
        val titleElement = selectFirst("h3.animeTitle") ?: return null
        val title = titleElement.text().trim()
        
        // Extrair número do episódio
        val epNumberElement = selectFirst(".numEp")
        val epNumber = epNumberElement?.text()?.toIntOrNull()
        
        // Extrair imagem do episódio
        val imgElement = selectFirst("img.imgAnimesUltimosEps, img.transitioning_src")
        val poster = when {
            imgElement?.hasAttr("data-src") == true -> imgElement.attr("data-src")
            imgElement?.hasAttr("src") == true -> imgElement.attr("src")
            else -> selectFirst("img")?.attr("src")
        } ?: return null
        
        val cleanTitle = if (epNumber != null) {
            "${title} - Episódio $epNumber"
        } else {
            title
        }
        
        return newAnimeSearchResponse(cleanTitle, fixUrl(href), TvType.Anime) {
            this.posterUrl = fixUrl(poster)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl$SEARCH_PATH/${URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document

        return document.select("article.containerAnimes a.item").mapNotNull { element ->
            try {
                element.toSearchResultFromSearch()
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun Element.toSearchResultFromSearch(): SearchResponse? {
        val titleElement = selectFirst("h3.animeTitle") ?: return null
        val title = titleElement.text().trim()
        val href = attr("href") ?: return null

        val imgElement = selectFirst("img.imgAnimes")
        val poster = when {
            imgElement?.hasAttr("data-src") == true -> imgElement.attr("data-src")
            imgElement?.hasAttr("src") == true -> imgElement.attr("src")
            else -> selectFirst("img")?.attr("src")
        } ?: ""

        val cleanTitle = title.replace(Regex("(?i)(dublado|legendado|todos os episódios|\\(\\d{4}\\))$"), "").trim()

        val isMovie = href.contains("/filmes/") || title.contains("Movie", ignoreCase = true)

        return if (isMovie) {
            newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                this.posterUrl = if (poster.isNotEmpty()) fixUrl(poster) else null
            }
        } else {
            newAnimeSearchResponse(cleanTitle, fixUrl(href), TvType.Anime) {
                this.posterUrl = if (poster.isNotEmpty()) fixUrl(poster) else null
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // Container principal
        val mainContainer = document.selectFirst("div.col-lg-9.text-white.divDivAnimeInfo") ?: return null

        // 1. Título Principal
        val titleElement = mainContainer.selectFirst(".div_anime_names h1.quicksand400") ?: 
                          mainContainer.selectFirst(".main_div_anime_info h1") ?: return null
        val title = titleElement.text().trim()

        // 2. Imagem Principal (Poster)
        val posterDiv = mainContainer.selectFirst(".sub_animepage_img, .divImgAnimePageInfo")
        val posterImg = posterDiv?.selectFirst("img.transitioning_src")
        val posterUrl = when {
            posterImg?.hasAttr("src") == true -> posterImg.attr("src")
            posterImg?.hasAttr("data-src") == true -> posterImg.attr("data-src")
            else -> null
        }

        // 3. Gêneros
        val genreElements = mainContainer.select(".animeInfo .spanGenerosLink")
        val genres = if (genreElements.size > 1) {
            genreElements.drop(1).map { it.text().trim() }
        } else {
            genreElements.map { it.text().trim() }
        }

        // 4. Informações Gerais
        val infoElements = mainContainer.select(".divAnimePageInfo .animeInfo")
        val infoMap = mutableMapOf<String, String>()
        
        infoElements.forEach { element ->
            val keyElement = element.selectFirst("b")
            val valueElement = element.selectFirst("span.spanAnimeInfo")
            
            if (keyElement != null && valueElement != null) {
                val key = keyElement.text().replace(":", "").trim()
                val value = valueElement.text().trim()
                infoMap[key] = value
            }
        }

        // Extrair informações específicas
        val studio = infoMap["Estúdios"]
        val status = infoMap["Status"]
        val yearText = infoMap["Ano"]
        val year = yearText?.toIntOrNull()

        // 5. Sinopse
        val synopsisElement = mainContainer.selectFirst(".divSinopse span.spanAnimeInfo")
        val plot = synopsisElement?.text()?.trim()

        // 6. Lista de Episódios
        val episodes = mutableListOf<Episode>()
        val videoListSection = mainContainer.selectFirst("section.mt-3.mb-2")
        val episodeElements = videoListSection?.select(".div_video_list a.lEp") ?: emptyList()
        
        episodeElements.forEach { episodeElement ->
            try {
                val episodeText = episodeElement.text().trim()
                val episodeHref = episodeElement.attr("href") ?: return@forEach
                
                // Extrair número do episódio
                val episodeNumber = Regex("Epis[oó]dio\\s*(\\d+)").find(episodeText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("Ep\\.?\\s*(\\d+)").find(episodeText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: 0
                
                // Extrair título do episódio
                val episodeName = episodeText.replace(Regex("\\s*-\\s*Epis[oó]dio\\s*\\d+.*"), "").trim()
                
                if (episodeHref.isNotBlank()) {
                    episodes.add(
                        newEpisode(fixUrl(episodeHref)) {
                            this.name = if (episodeName.isNotBlank() && episodeName != title) episodeName else "Episódio $episodeNumber"
                            this.episode = episodeNumber
                            this.season = 1
                        }
                    )
                }
            } catch (e: Exception) {
                // Ignorar episódios com erro
            }
        }

        // Ordenar episódios
        val sortedEpisodes = episodes.sortedBy { it.episode }

        // Determinar se é filme
        val isMovie = url.contains("/filmes/") || title.contains("Movie", ignoreCase = true) || episodes.isEmpty()

        if (!isMovie) {
            return newTvSeriesLoadResponse(title, url, TvType.Anime, sortedEpisodes) {
                this.posterUrl = posterUrl?.let { fixUrl(it) }
                this.plot = plot
                this.tags = if (genres.isNotEmpty()) genres else null
                this.year = year
                
                this.recommendations = extractRecommendations(document)
            }
        } else {
            val videoLink = if (episodes.isNotEmpty()) {
                episodes.first().data
            } else {
                val firstVideo = videoListSection?.selectFirst("a[href*='/animes/'], a[href*='/filmes/']")
                firstVideo?.attr("href") ?: url
            }
            
            return newMovieLoadResponse(title, url, TvType.Movie, fixUrl(videoLink)) {
                this.posterUrl = posterUrl?.let { fixUrl(it) }
                this.plot = plot
                this.tags = if (genres.isNotEmpty()) genres else null
                this.year = year
                this.recommendations = extractRecommendations(document)
            }
        }
    }

    private fun extractRecommendations(document: org.jsoup.nodes.Document): List<SearchResponse> {
        return document.select(".owl-carousel-anime .divArticleLancamentos a.item").mapNotNull { element ->
            try {
                element.toSearchResult()
            } catch (e: Exception) {
                null
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return AnimeFireExtractor.extractVideoLinks(data, mainUrl, name, callback)
    }
}
