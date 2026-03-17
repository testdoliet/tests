package com.AnimesFlix

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.fasterxml.jackson.annotation.JsonProperty
import org.jsoup.nodes.Element

@CloudstreamPlugin
class AnimesFlixProvider : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimesFlix())
    }
}

class AnimesFlix : MainAPI() {
    override var mainUrl = "https://www.animesflix.site/"
    override var name = "AnimesFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override val usesWebView = true

    private val cdnUrl = "https://cdn.animesflix.site/imagens"
    private val staticsUrl = "https://statics.animesflix.site"

    companion object {
        // Categorias principais baseadas no HTML
        private val homeCategories = listOf(
            "episodios" to "Episódios Recentes",
            "animes" to "Animes Online",
            "filmes" to "Filmes Animes"
        )
    }

    override val mainPage = mainPageOf(
        "$mainUrl" to "Início"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val items = mutableListOf<SearchResponse>()

        try {
            // Episódios Recentes
            val recentEpisodes = document.select(".episodes-grid .episode-item").mapNotNull { element ->
                element.toRecentEpisodeSearchResult()
            }
            if (recentEpisodes.isNotEmpty()) {
                items.addAll(recentEpisodes)
            }

            // Animes Online (grid principal)
            val animeGrid = document.select(".content-grid .anime-item").mapNotNull { element ->
                element.toAnimeSearchResult()
            }
            if (animeGrid.isNotEmpty()) {
                items.addAll(animeGrid)
            }

        } catch (e: Exception) {
            // Ignora erro
        }

        return newHomePageResponse(request.name, items.distinctBy { it.url }, false)
    }

    private fun Element.toRecentEpisodeSearchResult(): SearchResponse? {
        val linkElement = selectFirst("a[href*='/series/']") ?: return null
        val href = linkElement.attr("href")
        val fullUrl = fixUrl(href)
        
        val imgElement = selectFirst("img")
        val poster = imgElement?.attr("src")?.let { fixUrl(it) }
        
        val titleElement = selectFirst(".episode-title a") ?: selectFirst(".episode-title")
        var title = titleElement?.text()?.trim() ?: return null
        
        // Limpa o título removendo número do episódio
        title = title.replace(Regex("\\d+$"), "").trim()
        
        val episodeBadge = selectFirst(".episode-badge")?.text()
        val episodeNum = episodeBadge?.replace("E", "")?.toIntOrNull()
        
        return newAnimeSearchResponse(title, fullUrl, TvType.Anime) {
            this.posterUrl = poster
            this.addSub(Locale("pt", "BR"), "Legendado")
        }
    }

    private fun Element.toAnimeSearchResult(): SearchResponse? {
        val linkElement = selectFirst("a[href*='/series/']") ?: return null
        val href = linkElement.attr("href")
        val fullUrl = fixUrl(href)
        
        val imgElement = selectFirst("img")
        val poster = imgElement?.attr("src")?.let { fixUrl(it) }
        
        val titleElement = selectFirst(".anime-title")
        val title = titleElement?.text()?.trim() ?: return null
        
        // Detecta se é dublado ou legendado pelo título
        val isDubbed = title.contains("Dublado", ignoreCase = true)
        val isSubbed = title.contains("Legendado", ignoreCase = true)
        
        return newAnimeSearchResponse(title, fullUrl, TvType.Anime) {
            this.posterUrl = poster
            if (isDubbed) {
                this.addDub()
            } else if (isSubbed) {
                this.addSub(Locale("pt", "BR"), "Legendado")
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document
        
        return document.select(".content-grid .anime-item, .episodes-grid .episode-item").mapNotNull { element ->
            element.toAnimeSearchResult() ?: element.toRecentEpisodeSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        // Extrai informações do hero
        val title = document.select("h1.anime-title-large").text()?.trim() ?: 
                   document.select("meta[property='og:title']").attr("content") ?: return null
        
        val poster = document.select(".anime-poster-large img").attr("src")?.let { fixUrl(it) } ?:
                    document.select("meta[property='og:image']").attr("content")?.let { fixUrl(it) }
        
        val backdrop = document.select(".anime-hero-bg").attr("style")?.let { style ->
            Regex("url\\((.*?)\\)").find(style)?.groupValues?.get(1)?.let { fixUrl(it) }
        }
        
        // Extrai descrição
        val plot = document.select(".overview-text").text()?.trim() ?:
                  document.select("meta[name='description']").attr("content")
        
        // Extrai tags/gêneros
        val tags = document.select(".tags-container .tag").map { it.text().trim() }
        
        // Extrai informações de episódios/temporadas
        val episodeRows = document.select(".episodes-list .episode-row")
        val episodes = mutableListOf<Episode>()
        
        if (episodeRows.isNotEmpty()) {
            episodeRows.forEach { row ->
                val episodeLink = row.attr("href")
                val episodeNumber = row.attr("data-episode-number").toIntOrNull() ?:
                                   row.select(".episode-number").text().toIntOrNull()
                val seasonNumber = row.attr("data-temporada").toIntOrNull() ?: 1
                
                if (episodeNumber != null && episodeLink.isNotBlank()) {
                    val episode = newEpisode(fixUrl(episodeLink)) {
                        this.name = row.select(".episode-name").text()?.trim() ?: "Episódio $episodeNumber"
                        this.season = seasonNumber
                        this.episode = episodeNumber
                        
                        // Detecta se é dublado ou legendado
                        val meta = row.select(".episode-meta span").text()
                        if (meta.contains("Dublado", ignoreCase = true)) {
                            this.addDub()
                        } else if (meta.contains("Legendado", ignoreCase = true)) {
                            this.addSub(Locale("pt", "BR"), "Legendado")
                        }
                    }
                    episodes.add(episode)
                }
            }
        }
        
        // Extrai metadados
        val year = document.select(".anime-meta-item .fa-calendar + span").text().toIntOrNull() ?:
                  document.select(".anime-meta-item:contains(202)").text().toIntOrNull()
        
        val duration = document.select(".anime-meta-item .fa-clock + span").text().let { time ->
            Regex("(\\d+)").find(time)?.groupValues?.get(1)?.toIntOrNull()
        }
        
        val seasonCount = document.select(".anime-meta-item .fa-layer-group + span").text().let {
            Regex("(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        }
        
        val episodeCount = document.select(".anime-meta-item .fa-th + span").text().let {
            Regex("(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: episodes.size
        }
        
        // Extrai recomendações
        val recommendations = document.select(".content-grid .anime-item").mapNotNull { element ->
            element.toAnimeSearchResult()
        }.take(20)
        
        val cleanTitle = title.replace(Regex(" \\(.*\\)"), "").trim()
        
        return if (episodes.isNotEmpty()) {
            // É uma série/anime
            newTvSeriesLoadResponse(cleanTitle, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = plot
                this.tags = tags
                this.duration = duration
                
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
            }
        } else {
            // É um filme
            newMovieLoadResponse(cleanTitle, url, TvType.AnimeMovie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = plot
                this.tags = tags
                this.duration = duration
                
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Para testes iniciais, retorna false
        // Depois implementaremos a extração dos links de vídeo
        return false
    }
}
