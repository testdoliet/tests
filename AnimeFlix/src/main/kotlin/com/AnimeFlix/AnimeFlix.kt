package com.AnimesFlix

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import org.jsoup.nodes.Element
import java.net.URLDecoder

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

    companion object {
        private const val SEARCH_PATH = "/search/?q="

        // Seletores baseados no HTML
        private const val ANIME_CARD = ".anime-item"
        private const val EPISODE_CARD = ".episode-item"
        private const val TITLE_SELECTOR = ".anime-title, .episode-title"
        private const val POSTER_SELECTOR = "img"
        private const val EPISODE_NUMBER_SELECTOR = ".episode-badge"
        private const val EPISODE_META_SELECTOR = ".episode-meta span"

        private const val ANIME_TITLE = "h1.anime-title-large"
        private const val ANIME_POSTER = ".anime-poster-large img"
        private const val ANIME_BACKDROP = ".anime-hero-bg"
        private const val ANIME_SYNOPSIS = ".overview-text"
        private const val ANIME_TAGS = ".tags-container .tag"
        private const val ANIME_METADATA = ".anime-meta-item"
        private const val EPISODE_LIST = ".episodes-list .episode-row"

        private const val RECOMMENDATIONS = ".content-grid .anime-item"
    }

    private fun cleanTitle(dirtyTitle: String): String {
        return dirtyTitle
            .replace("(?i)\\s*\\(dublado\\)".toRegex(), "")
            .replace("(?i)\\s*\\(legendado\\)".toRegex(), "")
            .replace("(?i)\\s*dublado\\s*$".toRegex(), "")
            .replace("(?i)\\s*legendado\\s*$".toRegex(), "")
            .replace("(?i)\\s*–\\s*todos os epis[oó]dios".toRegex(), "")
            .replace("\\s+".toRegex(), " ")
            .trim()
            .ifBlank { dirtyTitle }
    }

    private fun extractEpisodeNumber(title: String): Int? {
        return listOf(
            "Epis[oó]dio\\s*(\\d+)".toRegex(RegexOption.IGNORE_CASE),
            "Ep\\.?\\s*(\\d+)".toRegex(RegexOption.IGNORE_CASE),
            "E(\\d+)".toRegex(RegexOption.IGNORE_CASE),
            "\\b(\\d{1,3})\\b".toRegex()
        ).firstNotNullOfOrNull { it.find(title)?.groupValues?.get(1)?.toIntOrNull() }
    }

    private fun extractAnimeTitleFromEpisode(episodeTitle: String): String {
        var clean = episodeTitle
            .replace("(?i)Epis[oó]dio\\s*\\d+".toRegex(), "")
            .replace("(?i)Ep\\.?\\s*\\d+".toRegex(), "")
            .replace("(?i)E\\d+".toRegex(), "")
            .replace("–", "")
            .replace("-", "")
            .replace("(?i)\\s*\\(dublado\\)".toRegex(), "")
            .replace("(?i)\\s*\\(legendado\\)".toRegex(), "")
            .replace("\\s+".toRegex(), " ")
            .trim()

        clean = clean.replace("\\s*\\d+\\s*$".toRegex(), "").trim()

        return clean.ifBlank { "Anime" }
    }

    private fun isDubbed(element: Element): Boolean {
        return element.text().contains("Dublado", true) ||
               element.select(EPISODE_META_SELECTOR).any { it.text().contains("Dublado", true) }
    }

    private fun isSubbed(element: Element): Boolean {
        return element.text().contains("Legendado", true) ||
               element.select(EPISODE_META_SELECTOR).any { it.text().contains("Legendado", true) }
    }

    private fun Element.toEpisodeSearchResponse(): AnimeSearchResponse? {
        val linkElement = selectFirst("a[href*='/series/']") ?: return null
        val href = linkElement.attr("href")
        if (!href.contains("/series/")) return null

        val episodeBadge = selectFirst(EPISODE_NUMBER_SELECTOR)?.text()
        val episodeNumber = episodeBadge?.replace("E", "")?.toIntOrNull() ?: 1

        val titleElement = selectFirst(".episode-title a") ?: selectFirst(".episode-title")
        val rawTitle = titleElement?.text()?.trim() ?: return null
        val animeTitle = extractAnimeTitleFromEpisode(rawTitle)
        val cleanedTitle = cleanTitle(animeTitle)

        val posterUrl = selectFirst(POSTER_SELECTOR)?.attr("src")?.let { fixUrl(it) }
        val isDubbed = isDubbed(this)

        val urlWithPoster = if (posterUrl != null) {
            "$href|poster=$posterUrl"
        } else {
            href
        }

        return newAnimeSearchResponse(cleanedTitle, fixUrl(urlWithPoster), TvType.Anime) {
            this.posterUrl = posterUrl
            addDubStatus(if (isDubbed) DubStatus.Dubbed else DubStatus.Subbed, episodeNumber)
        }
    }

    private fun Element.toAnimeSearchResponse(): AnimeSearchResponse? {
        val linkElement = selectFirst("a[href*='/series/']") ?: return null
        val href = linkElement.attr("href")

        val titleElement = selectFirst(".anime-title")
        val rawTitle = titleElement?.text()?.trim() ?: return null
        val cleanedTitle = cleanTitle(rawTitle)

        val posterUrl = selectFirst(POSTER_SELECTOR)?.attr("src")?.let { fixUrl(it) }
        val isDubbed = isDubbed(this)

        return newAnimeSearchResponse(cleanedTitle, fixUrl(href), TvType.Anime) {
            this.posterUrl = posterUrl
            addDubStatus(if (isDubbed) DubStatus.Dubbed else DubStatus.Subbed, null)
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl" to "Episódios Recentes",
        "$mainUrl" to "Animes Online",
        "$mainUrl" to "Filmes Animes"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document

        return when (request.name) {
            "Episódios Recentes" -> {
                val episodes = document.select(".episodes-grid $EPISODE_CARD")
                    .mapNotNull { it.toEpisodeSearchResponse() }
                    .distinctBy { it.url }

                newHomePageResponse(request.name, episodes, false)
            }
            "Animes Online" -> {
                // Pega a primeira seção de animes
                val animes = document.select(".content-grid $ANIME_CARD")
                    .mapNotNull { it.toAnimeSearchResponse() }
                    .distinctBy { it.url }
                    .take(20)

                newHomePageResponse(request.name, animes, false)
            }
            "Filmes Animes" -> {
                // Pega a segunda seção (filmes)
                val sections = document.select(".section")
                val movieSection = sections.firstOrNull { section ->
                    section.select(".section-title").text().contains("Filmes Animes", true)
                }

                val movies = movieSection?.select(".content-grid $ANIME_CARD")
                    ?.mapNotNull { it.toAnimeSearchResponse() }
                    ?.distinctBy { it.url }
                    ?: emptyList()

                newHomePageResponse(request.name, movies, false)
            }
            else -> newHomePageResponse(request.name, emptyList(), false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()

        val searchUrl = "$mainUrl$SEARCH_PATH${java.net.URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document

        val results = mutableListOf<SearchResponse>()

        // Busca por animes na grid
        document.select(".content-grid $ANIME_CARD").mapNotNullTo(results) { it.toAnimeSearchResponse() }

        // Busca por episódios
        document.select(".episodes-grid $EPISODE_CARD").mapNotNullTo(results) { it.toEpisodeSearchResponse() }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val parts = url.split("|poster=")
        val actualUrl = parts[0]
        val thumbPoster = parts.getOrNull(1)?.let { if (it.isNotBlank()) fixUrl(it) else null }

        val document = app.get(actualUrl).document

        // Título
        val rawTitle = document.selectFirst(ANIME_TITLE)?.text()?.trim() ?: 
                      document.select("meta[property='og:title']").attr("content") ?: "Sem Título"
        val title = cleanTitle(rawTitle)

        // Poster
        val poster = thumbPoster ?: document.selectFirst(ANIME_POSTER)?.attr("src")?.let { fixUrl(it) } ?:
                    document.select("meta[property='og:image']").attr("content")?.let { fixUrl(it) }

        // Backdrop
        val backdrop = document.selectFirst(ANIME_BACKDROP)?.attr("style")?.let { style ->
            Regex("url\\((.*?)\\)").find(style)?.groupValues?.get(1)?.let { fixUrl(it) }
        }

        // Sinopse
        val synopsis = document.selectFirst(ANIME_SYNOPSIS)?.text()?.trim() ?:
                      document.select("meta[name='description']").attr("content")

        // Tags/Gêneros
        val tags = document.select(ANIME_TAGS).map { it.text().trim() }

        // Metadados
        var year: Int? = null
        var duration: Int? = null
        var seasonCount = 1
        var episodeCount = 0

        document.select(ANIME_METADATA).forEach { element ->
            val text = element.text()
            when {
                text.contains("202") -> {
                    year = Regex("(\\d{4})").find(text)?.groupValues?.get(1)?.toIntOrNull()
                }
                text.contains("min") -> {
                    duration = Regex("(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
                }
                text.contains("Temporada") -> {
                    seasonCount = Regex("(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                }
                text.contains("Episódios") -> {
                    episodeCount = Regex("(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                }
            }
        }

        // Episódios
        val episodeRows = document.select(EPISODE_LIST)
        val episodesList = mutableListOf<Episode>()

        if (episodeRows.isNotEmpty()) {
            episodeRows.forEach { row ->
                val episodeLink = row.attr("href")
                val episodeNumber = row.attr("data-episode-number").toIntOrNull() ?:
                                   row.select(".episode-number").text().toIntOrNull()
                val seasonNumber = row.attr("data-temporada").toIntOrNull() ?: 1

                if (episodeNumber != null && episodeLink.isNotBlank()) {
                    val isDubbed = row.text().contains("Dublado", true)
                    val episodeName = row.select(".episode-name").text()?.trim() ?: "Episódio $episodeNumber"

                    val episode = newEpisode(fixUrl(episodeLink)) {
                        this.name = episodeName
                        this.season = seasonNumber
                        this.episode = episodeNumber
                        this.posterUrl = poster
                        addDubStatus(if (isDubbed) DubStatus.Dubbed else DubStatus.Subbed, episodeNumber)
                    }
                    episodesList.add(episode)
                }
            }
        }

        // Recomendações
        val recommendations = document.select(RECOMMENDATIONS).mapNotNull { element ->
            element.toAnimeSearchResponse()
        }.take(20)

        // Determina se é filme ou série
        val isMovie = actualUrl.contains("/filmes/") || episodesList.isEmpty()

        return if (isMovie) {
            newMovieLoadResponse(title, actualUrl, TvType.AnimeMovie, actualUrl) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = synopsis
                this.tags = tags
                this.duration = duration
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
            }
        } else {
            val sortedEpisodes = episodesList.sortedBy { it.episode }
            val showStatus = if (episodeCount > 0 && sortedEpisodes.size >= episodeCount) {
                ShowStatus.Completed
            } else {
                ShowStatus.Ongoing
            }

            newAnimeLoadResponse(title, actualUrl, TvType.Anime) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = synopsis
                this.tags = tags
                this.showStatus = showStatus
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }

                // Agrupa episódios por temporada
                val episodesBySeason = sortedEpisodes.groupBy { it.season }
                episodesBySeason.forEach { (season, episodes) ->
                    addEpisodes(if (episodes.any { it.name.contains("Dublado", true) }) DubStatus.Dubbed else DubStatus.Subbed, episodes)
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
        // Por enquanto, retorna false para testes
        // Depois implementaremos a extração dos links de vídeo
        return false
    }
}
