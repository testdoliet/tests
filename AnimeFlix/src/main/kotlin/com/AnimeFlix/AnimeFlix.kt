package com.AnimesFlix

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
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

    companion object {
        private const val SEARCH_PATH = "/search/?q="
        private const val GENRES_PATH = "/generos"

        // Seletores baseados no HTML
        private const val ANIME_CARD = ".anime-item"
        private const val EPISODE_CARD = ".episode-item"
        private const val POSTER_SELECTOR = "img"
        private const val EPISODE_NUMBER_SELECTOR = ".episode-badge"
        private const val EPISODE_META_SELECTOR = ".episode-meta span"
        private const val GENRE_ITEM = ".genre-item"
        private const val GENRE_NAME = ".genre-name"
        private const val GENRE_COUNT = ".genre-count"
    }

    private val genresMap = mapOf(
        "Ação" to "animes-acao",
        "Aventura" to "animes-aventura",
        "Comédia" to "animes-comedia",
        "Drama" to "animes-drama",
        "Fantasia" to "animes-fantasia",
        "Ficção Científica" to "animes-ficcao-cientifica",
        "Horror" to "animes-horror",
        "Mistério" to "animes-misterio",
        "Romance" to "animes-romance",
        "Sci-Fi" to "animes-sci-fi",
        "Seinen" to "animes-seinen",
        "Shounen" to "animes-shounen",
        "Slice of life" to "slice-of-life1",
        "Sobrenatural" to "sobrenatural1",
        "Suspense" to "animes-suspense",
        "Vida Escolar" to "animes-vida-escolar",
        "Animes" to "animes",
        "Artes Marciais" to "animes-artes-marciais",
        "Demônios" to "animes-demonios",
        "Ecchi" to "animes-ecchi",
        "Esporte" to "animes-esportes",
        "Jogos" to "animes-jogos",
        "Magia" to "animes-magia",
        "Militar" to "animes-militar",
        "Psicológico" to "animes-psicologico",
        "Superpoder" to "animes-superpoder"
    )

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
            val dubStatus = if (isDubbed) DubStatus.Dubbed else DubStatus.Subbed
            addDubStatus(dubStatus, episodeNumber)
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
            addDubStatus(isDubbed, null)
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl" to "Últimos Episódios",
        *getAvailableGenres().toTypedArray()
    )

    private suspend fun getAvailableGenres(): List<Pair<String, String>> {
        val document = app.get("$mainUrl$GENRES_PATH").document
        val genres = mutableListOf<Pair<String, String>>()

        document.select(GENRE_ITEM).forEach { element ->
            val genreName = element.select(GENRE_NAME).text().trim()
            val genreCount = element.select(GENRE_COUNT).text().toIntOrNull() ?: 0
            val genreLink = element.attr("href")

            // Só adiciona se tiver pelo menos 20 animes e não for "Animes" (já temos na main)
            if (genreCount >= 20 && genreName != "Animes" && genreName != "Dublado" && genreName != "Legendado") {
                genres.add(genreLink to genreName)
            }
        }

        return genres
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return if (request.name == "Últimos Episódios") {
            // Para Últimos Episódios, usa layout horizontal
            val document = app.get(mainUrl).document
            val episodes = document.select(".episodes-grid $EPISODE_CARD")
                .mapNotNull { it.toEpisodeSearchResponse() }
                .distinctBy { it.url }

            newHomePageResponse(
                list = HomePageList(request.name, episodes, isHorizontalImages = true),
                hasNext = false
            )
        } else {
            // Para categorias de gêneros
            val url = if (page > 1) {
                if (request.data.contains("?")) {
                    "$request.data&page=$page"
                } else {
                    "$request.data?page=$page"
                }
            } else {
                request.data
            }

            val document = app.get(url).document
            val items = document.select(".content-grid $ANIME_CARD")
                .mapNotNull { it.toAnimeSearchResponse() }
                .distinctBy { it.url }

            val hasNext = document.select(".pagination .next, .pagination a:contains(Próxima)").isNotEmpty()

            newHomePageResponse(request.name, items, hasNext)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()

        val searchUrl = "$mainUrl$SEARCH_PATH${java.net.URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document

        val results = mutableListOf<SearchResponse>()

        document.select(".content-grid $ANIME_CARD").mapNotNullTo(results) { it.toAnimeSearchResponse() }
        document.select(".episodes-grid $EPISODE_CARD").mapNotNullTo(results) { it.toEpisodeSearchResponse() }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val parts = url.split("|poster=")
        val actualUrl = parts[0]
        val thumbPoster = parts.getOrNull(1)?.let { if (it.isNotBlank()) fixUrl(it) else null }

        val document = app.get(actualUrl).document

        val rawTitle = document.selectFirst("h1.anime-title-large")?.text()?.trim() ?: 
                      document.select("meta[property='og:title']").attr("content") ?: "Sem Título"
        val title = cleanTitle(rawTitle)

        val poster = thumbPoster ?: document.selectFirst(".anime-poster-large img")?.attr("src")?.let { fixUrl(it) } ?:
                    document.select("meta[property='og:image']").attr("content")?.let { fixUrl(it) }

        val backdrop = document.selectFirst(".anime-hero-bg")?.attr("style")?.let { style ->
            Regex("url\\((.*?)\\)").find(style)?.groupValues?.get(1)?.let { fixUrl(it) }
        }

        val synopsis = document.selectFirst(".overview-text")?.text()?.trim() ?:
                      document.select("meta[name='description']").attr("content")

        val tags = document.select(".tags-container .tag").map { it.text().trim() }

        var year: Int? = null
        var duration: Int? = null
        var episodeCount = 0

        document.select(".anime-meta-item").forEach { element ->
            val text = element.text()
            when {
                text.contains("202") -> {
                    year = Regex("(\\d{4})").find(text)?.groupValues?.get(1)?.toIntOrNull()
                }
                text.contains("min") -> {
                    duration = Regex("(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
                }
                text.contains("Episódios") -> {
                    episodeCount = Regex("(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                }
            }
        }

        val episodeRows = document.select(".episodes-list .episode-row")
        val episodesList = mutableListOf<Episode>()

        if (episodeRows.isNotEmpty()) {
            episodeRows.forEach { row ->
                val episodeLink = row.attr("href")
                val episodeNumber = row.attr("data-episode-number").toIntOrNull() ?:
                                   row.select(".episode-number").text().toIntOrNull()
                val seasonNumber = row.attr("data-temporada").toIntOrNull() ?: 1

                if (episodeNumber != null && episodeLink.isNotBlank()) {
                    val episodeName = row.select(".episode-name").text()?.trim() ?: "Episódio $episodeNumber"

                    val episode = newEpisode(fixUrl(episodeLink)) {
                        this.name = episodeName
                        this.season = seasonNumber
                        this.episode = episodeNumber
                        this.posterUrl = poster
                    }
                    episodesList.add(episode)
                }
            }
        }

        val recommendations = document.select(".content-grid .anime-item").mapNotNull { element ->
            element.toAnimeSearchResponse()
        }.take(20)

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

                if (sortedEpisodes.isNotEmpty()) {
                    val isDubbedOverall = sortedEpisodes.any { it.name?.contains("Dublado", true) == true }
                    addEpisodes(if (isDubbedOverall) DubStatus.Dubbed else DubStatus.Subbed, sortedEpisodes)
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
        return false
    }
}
