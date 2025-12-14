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
    override val usesWebView = true

    companion object {
        private const val SEARCH_PATH = "/pesquisar"
    }

    // Todas as abas usam o mesmo mainUrl, mas extraem seções diferentes
    override val mainPage = mainPageOf(
        "$mainUrl" to "Lançamentos",           // Seção "Em lançamento"
        "$mainUrl" to "Destaques da semana",   // Seção "Destaques da semana"
        "$mainUrl" to "Últimos Animes",        // Seção "Últimos animes adicionados"
        "$mainUrl" to "Últimos Episódios"      // Seção "Últimos episódios adicionados"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        
        val homeItems = when (request.name) {
            "Lançamentos" -> extractLancamentos(document)
            "Destaques da semana" -> extractDestaquesSemana(document)
            "Últimos Animes" -> extractUltimosAnimes(document)
            "Últimos Episódios" -> extractUltimosEpisodios(document)
            else -> emptyList()
        }
        
        return newHomePageResponse(request.name, homeItems.distinctBy { it.url })
    }

    private fun extractLancamentos(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        
        // Extrair da seção "Em lançamento" (primeiro carrossel)
        document.select(".owl-carousel-home .divArticleLancamentos a.item").forEach { item ->
            item.toSearchResult()?.let { items.add(it) }
        }
        
        return items
    }

    private fun extractDestaquesSemana(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        
        // Extrair da seção "Destaques da semana"
        document.select(".owl-carousel-semana .divArticleLancamentos a.item").forEach { item ->
            item.toSearchResult()?.let { items.add(it) }
        }
        
        return items
    }

    private fun extractUltimosAnimes(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        
        // Extrair da seção "Últimos animes adicionados"
        document.select(".owl-carousel-l_dia .divArticleLancamentos a.item").forEach { item ->
            item.toSearchResult()?.let { items.add(it) }
        }
        
        return items
    }

    private fun extractUltimosEpisodios(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        
        // Extrair da seção "Últimos episódios adicionados"
        document.select(".divCardUltimosEpsHome").forEach { card ->
            card.toEpisodeSearchResult()?.let { items.add(it) }
        }
        
        return items
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("href") ?: return null
        val titleElement = selectFirst("h3.animeTitle") ?: return null
        val title = titleElement.text().trim()
        
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
        val epNumber = epNumberElement?.text()?.toIntOrNull() ?: 1
        
        // Extrair imagem do episódio
        val imgElement = selectFirst("img.imgAnimesUltimosEps, img.transitioning_src")
        val poster = when {
            imgElement?.hasAttr("data-src") == true -> imgElement.attr("data-src")
            imgElement?.hasAttr("src") == true -> imgElement.attr("src")
            else -> selectFirst("img")?.attr("src")
        } ?: return null
        
        val cleanTitle = "${title} - Episódio $epNumber"
        
        return newAnimeSearchResponse(cleanTitle, fixUrl(href), TvType.Anime) {
            this.posterUrl = fixUrl(poster)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl$SEARCH_PATH/${URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document

        return document.select("article.containerAnimes a.item, a.item").mapNotNull { element ->
            try {
                element.toSearchResult()
            } catch (e: Exception) {
                null
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // Título Principal
        val titleElement = document.selectFirst("h1.quicksand400, .main_div_anime_info h1, h1") ?: return null
        val title = titleElement.text().trim()
        
        // Poster
        val posterImg = document.selectFirst("img.transitioning_src, .sub_anime_img img, img")
        val posterUrl = posterImg?.attr("src") ?: posterImg?.attr("data-src")
        
        // Sinopse
        val plot = document.selectFirst(".divSinopse, .sinopse")?.text()?.trim()
        
        // Ano
        val yearText = document.selectFirst(".animeInfo span.snpanAnimeInfo:contains(Ano:)")?.text()
        val year = yearText?.let { 
            Regex("Ano:\\s*(\\d{4})").find(it)?.groupValues?.get(1)?.toIntOrNull()
        }
        
        // Gêneros
        val tags = document.select(".animeInfo a.spanAnimeInfo, .spanGeneros").map { it.text().trim() }
            .takeIf { it.isNotEmpty() }?.toList()
        
        // Lista de Episódios
        val episodes = mutableListOf<Episode>()
        val episodeElements = document.select(".div_video_list a.lEp, a[href*='/animes/'], a.lep")
        
        episodeElements.forEachIndexed { index, element ->
            try {
                val episodeHref = element.attr("href") ?: return@forEachIndexed
                val episodeText = element.text().trim()
                
                val episodeNumber = Regex("Epis[oó]dio\\s*(\\d+)").find(episodeText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("Ep\\.?\\s*(\\d+)").find(episodeText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: (index + 1)
                
                episodes.add(
                    newEpisode(fixUrl(episodeHref)) {
                        this.name = "Episódio $episodeNumber"
                        this.episode = episodeNumber
                        this.season = 1
                    }
                )
            } catch (e: Exception) {
                // Ignorar erro
            }
        }

        // Ordenar episódios
        val sortedEpisodes = episodes.sortedBy { it.episode }.distinctBy { it.episode }

        // Determinar tipo
        val isMovie = url.contains("/filmes/") || title.contains("Movie", ignoreCase = true) || sortedEpisodes.isEmpty()

        if (!isMovie && sortedEpisodes.isNotEmpty()) {
            // É uma série/anime
            return newTvSeriesLoadResponse(title, url, TvType.Anime, sortedEpisodes) {
                this.posterUrl = posterUrl?.let { fixUrl(it) }
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        } else {
            // É um filme
            val playerUrl = if (sortedEpisodes.isNotEmpty()) {
                sortedEpisodes.first().data
            } else {
                url
            }
            
            return newMovieLoadResponse(title, url, TvType.Movie, fixUrl(playerUrl)) {
                this.posterUrl = posterUrl?.let { fixUrl(it) }
                this.plot = plot
                this.year = year
                this.tags = tags
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
