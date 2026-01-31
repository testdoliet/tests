package com.AnimeQ

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class AnimeQ : MainAPI() {
    override var mainUrl = "https://animeq.net"
    override var name = "AnimeQ"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override val usesWebView = false

    companion object {
        // Página de busca
        private const val SEARCH_PATH = "/?s="
        
        // Página de episódios
        private const val EPISODE_PAGE_ITEM = ".item.se.episodes"
        
        // Página de gêneros/categorias (ação, aventura, etc.)
        private const val GENRE_PAGE_ITEM = ".items.full .item.tvshows, .items.full .item.movies"
        
        // Elementos comuns
        private const val ITEM_TITLE = ".data h3 a"
        private const val ITEM_POSTER = ".poster img"
        private const val ITEM_LINK = "a[href]"
        private const val EPISODE_SERIE = ".data .serie"
        private const val ANIME_YEAR = ".data span"
        private const val ANIME_SCORE = ".rating"
        private const val ANIME_QUALITY = ".mepo .quality"
        
        // Página de detalhes do anime
        private const val DETAIL_TITLE = "h1"
        private const val DETAIL_POSTER = ".poster img"
        private const val DETAIL_SYNOPSIS = ".wp-content"
        private const val DETAIL_METADATA = ".dtinfo"
        private const val EPISODE_LIST = ".pag_episodes .item a"
    }

    // Mapeamento completo de todas as categorias
    private val mainCategories = mapOf(
        "Últimos Episódios" to "$mainUrl/episodio/",
        "Animes Mais Vistos" to "$mainUrl/",
    )

    private val genresMap = mapOf(
        "Ação" to "genre/acao",
        "Aventura" to "genre/aventura", 
        "Animação" to "genre/animacao",
        "Drama" to "genre/drama",
        "Crime" to "genre/crime",
        "Mistério" to "genre/misterio",
        "Fantasia" to "genre/fantasia",
        "Terror" to "genre/terror",
        "Comédia" to "genre/comedia",
        "Romance" to "genre/romance",
        "Sci-Fi" to "genre/ficcao-cientifica",
        "Seinen" to "genre/seinen",
        "Shounen" to "genre/shounen",
        "Ecchi" to "genre/ecchi",
        "Mecha" to "genre/mecha",
        "Esporte" to "genre/esporte",
        "Sobrenatural" to "genre/sobrenatural",
        "Superpoder" to "genre/superpoder",
        "Vida Escolar" to "genre/vida-escolar"
    )

    private val typeMap = mapOf(
        "Legendado" to "tipo/legendado",
        "Dublado" to "tipo/dublado"
    )

    private val specialCategories = mapOf(
        "Filmes" to "filme",
        "Manhwa" to "genre/Manhwa",
        "Donghua" to "genre/Donghua"
    )

    override val mainPage = mainPageOf(
        *mainCategories.map { (name, url) -> url to name }.toTypedArray(),
        *genresMap.map { (genre, slug) -> "$mainUrl/$slug" to genre }.toTypedArray(),
        *typeMap.map { (type, slug) -> "$mainUrl/$slug" to type }.toTypedArray(),
        *specialCategories.map { (cat, slug) -> "$mainUrl/$slug" to cat }.toTypedArray()
    )

    private fun cleanTitle(dirtyTitle: String): String {
        return dirtyTitle
            .replace("(?i)\\s*–\\s*todos os epis[oó]dios".toRegex(), "")
            .replace("(?i)\\s*\\(dublado\\)".toRegex(), "")
            .replace("(?i)\\s*\\(legendado\\)".toRegex(), "")
            .replace("(?i)\\s*dublado\\s*$".toRegex(), "")
            .replace("(?i)\\s*legendado\\s*$".toRegex(), "")
            .replace("(?i)\\s*-\\s*epis[oó]dio\\s*\\d+".toRegex(), "")
            .replace("(?i)\\s*–\\s*Epis[oó]dio\\s*\\d+".toRegex(), "")
            .replace("(?i)\\s*epis[oó]dio\\s*\\d+".toRegex(), "")
            .replace("(?i)\\s*Ep\\.\\s*\\d+".toRegex(), "")
            .replace("\\s+".toRegex(), " ")
            .trim()
            .ifBlank { dirtyTitle }
    }

    private fun extractEpisodeNumber(title: String): Int? {
        return listOf(
            "Epis[oó]dio\\s*(\\d+)".toRegex(RegexOption.IGNORE_CASE),
            "Ep\\.?\\s*(\\d+)".toRegex(RegexOption.IGNORE_CASE),
            "E(\\d+)".toRegex(RegexOption.IGNORE_CASE),
            "\\b(\\d{3,})\\b".toRegex(),
            "\\b(\\d{1,3})\\b".toRegex()
        ).firstNotNullOfOrNull { it.find(title)?.groupValues?.get(1)?.toIntOrNull() } ?: 1
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

    private fun isDubbed(title: String): Boolean {
        return title.contains("dublado", true) || 
               title.contains("dublada", true) ||
               title.contains("dublados", true) ||
               title.contains("dubladas", true)
    }

    private fun Element.toEpisodeSearchResponse(): AnimeSearchResponse? {
        val href = selectFirst(ITEM_LINK)?.attr("href") ?: return null
        val episodeTitle = selectFirst(ITEM_TITLE)?.text()?.trim() ?: return null
        val episodeNumber = extractEpisodeNumber(episodeTitle)
        val animeTitle = extractAnimeTitleFromEpisode(episodeTitle)
        val posterUrl = selectFirst(ITEM_POSTER)?.attr("src")?.let { fixUrl(it) }
        val isDubbed = isDubbed(episodeTitle)
        val serieName = selectFirst(EPISODE_SERIE)?.text()?.trim() ?: animeTitle

        val cleanTitle = cleanTitle(serieName)

        return newAnimeSearchResponse(cleanTitle, fixUrl(href)) {
            this.posterUrl = posterUrl
            this.type = TvType.Anime

            val dubStatus = if (isDubbed) DubStatus.Dubbed else DubStatus.Subbed
            addDubStatus(dubStatus, episodeNumber)
        }
    }

    private fun Element.toAnimeSearchResponse(): AnimeSearchResponse? {
        val href = selectFirst(ITEM_LINK)?.attr("href") ?: return null
        val rawTitle = selectFirst(ITEM_TITLE)?.text()?.trim() ?: return null
        val cleanedTitle = cleanTitle(rawTitle).ifBlank { return null }
        val posterUrl = selectFirst(ITEM_POSTER)?.attr("src")?.let { fixUrl(it) }
        val isDubbed = isDubbed(rawTitle)
        val year = selectFirst(ANIME_YEAR)?.text()?.trim()?.toIntOrNull()
        val scoreText = selectFirst(ANIME_SCORE)?.text()?.trim()
        
        // Converter score usando o método estático from10 como no AnimeFire
        val score = scoreText?.toFloatOrNull()?.let { 
            Score.from10(it)
        }

        // Determinar se é filme ou série
        val isMovie = href.contains("/filme/") || cleanedTitle.contains("filme", true)
        val type = if (isMovie) TvType.AnimeMovie else TvType.Anime

        return newAnimeSearchResponse(cleanedTitle, fixUrl(href)) {
            this.posterUrl = posterUrl
            this.type = type
            this.year = year
            this.score = score
            addDubStatus(isDubbed, null)
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val baseUrl = request.data
        var url = baseUrl
        
        // Tratar paginação
        if (page > 1) {
            url = when {
                baseUrl == "$mainUrl/episodio/" -> "$baseUrl/page/$page/"
                baseUrl == "$mainUrl/" -> baseUrl // Página inicial não tem paginação
                baseUrl.contains("/?s=") -> baseUrl.replace("/?s=", "/page/$page/?s=")
                else -> "$baseUrl/page/$page/"
            }
        }

        val document = app.get(url).document

        return when (request.name) {
            "Últimos Episódios" -> {
                val episodeElements = document.select(EPISODE_PAGE_ITEM)
                val items = episodeElements
                    .mapNotNull { it.toEpisodeSearchResponse() }
                    .distinctBy { it.url }

                // ÚNICA CATEGORIA COM LAYOUT HORIZONTAL
                newHomePageResponse(
                    list = HomePageList(request.name, items, isHorizontalImages = true),
                    hasNext = episodeElements.isNotEmpty()
                )
            }
            "Animes Mais Vistos" -> {
                // Na página inicial, procurar por seções populares
                val popularItems = mutableListOf<AnimeSearchResponse>()
                
                // Tentar pegar do slider de ação (geralmente tem animes populares)
                val sliderItems = document.select("#genre_acao .item.tvshows, #genre_acao .item.movies")
                popularItems.addAll(sliderItems.take(10).mapNotNull { it.toAnimeSearchResponse() })
                
                // Se não encontrou no slider, tentar outras seções
                if (popularItems.isEmpty()) {
                    val allItems = document.select(".item.tvshows, .item.movies")
                        .take(10)
                        .mapNotNull { it.toAnimeSearchResponse() }
                    popularItems.addAll(allItems)
                }

                // LAYOUT VERTICAL
                newHomePageResponse(
                    list = HomePageList(request.name, popularItems.distinctBy { it.url }, isHorizontalImages = false),
                    hasNext = false
                )
            }
            else -> {
                // Para todas as outras categorias (gêneros, tipos, categorias especiais)
                val isEpisodePage = baseUrl.contains("/episodio/")
                val isGenrePage = baseUrl.contains("/genre/") || 
                                 baseUrl.contains("/tipo/") || 
                                 baseUrl == "$mainUrl/filme/"
                
                val items = if (isEpisodePage) {
                    // Páginas de episódios
                    document.select(EPISODE_PAGE_ITEM)
                        .mapNotNull { it.toEpisodeSearchResponse() }
                        .distinctBy { it.url }
                } else if (isGenrePage) {
                    // Páginas de gêneros, tipos e categorias
                    document.select(GENRE_PAGE_ITEM)
                        .mapNotNull { it.toAnimeSearchResponse() }
                        .distinctBy { it.url }
                } else {
                    // Página inicial ou busca
                    document.select(".item.tvshows, .item.movies")
                        .mapNotNull { it.toAnimeSearchResponse() }
                        .distinctBy { it.url }
                }

                val hasNext = when {
                    isEpisodePage -> document.select(".pagination a").isNotEmpty()
                    isGenrePage -> document.select(".pagination a").isNotEmpty()
                    else -> false
                }

                // TODAS AS OUTRAS CATEGORIAS COM LAYOUT VERTICAL
                newHomePageResponse(
                    list = HomePageList(request.name, items, isHorizontalImages = false),
                    hasNext = hasNext
                )
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()

        val document = app.get("$mainUrl$SEARCH_PATH${query.replace(" ", "+")}").document

        return document.select(".item.tvshows, .item.movies, .item.se.episodes")
            .mapNotNull { element ->
                if (element.hasClass("episodes")) {
                    element.toEpisodeSearchResponse()
                } else {
                    element.toAnimeSearchResponse()
                }
            }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val rawTitle = document.selectFirst(DETAIL_TITLE)?.text()?.trim() ?: "Sem Título"
        val title = cleanTitle(rawTitle)
        
        val poster = document.selectFirst(DETAIL_POSTER)?.attr("src")?.let { fixUrl(it) }
        
        val synopsis = document.selectFirst(DETAIL_SYNOPSIS)?.text()?.trim() ?: "Sinopse não disponível."
        
        var year: Int? = null
        var episodes: Int? = null
        var genres = emptyList<String>()
        
        document.select(DETAIL_METADATA).forEach { element ->
            val text = element.text()
            when {
                text.contains("Gênero:", true) -> genres = text.substringAfter("Gênero:").split(",").map { it.trim() }
                text.contains("Ano:", true) -> year = text.substringAfter("Ano:").trim().toIntOrNull()
                text.contains("Episódios:", true) -> episodes = text.substringAfter("Episódios:").trim().toIntOrNull()
            }
        }

        val isDubbed = rawTitle.contains("dublado", true) || url.contains("dublado", true)
        val isMovie = url.contains("/filme/") || rawTitle.contains("filme", true)
        
        val episodesList = if (!isMovie) {
            document.select(EPISODE_LIST).mapNotNull { element ->
                val episodeTitle = element.text().trim()
                val episodeUrl = element.attr("href")
                val epNumber = extractEpisodeNumber(episodeTitle) ?: 1

                newEpisode(episodeUrl) {
                    this.name = "Episódio $epNumber"
                    this.episode = epNumber
                    this.posterUrl = poster
                }
            }.sortedBy { it.episode }
        } else {
            // Para filmes, criar um único episódio
            listOf(newEpisode(url) {
                this.name = "Filme Completo"
                this.episode = 1
                this.posterUrl = poster
            })
        }

        val showStatus = if (isMovie || (episodes != null && episodesList.size >= episodes)) {
            ShowStatus.Completed
        } else {
            ShowStatus.Ongoing
        }

        return newAnimeLoadResponse(title, url, if (isMovie) TvType.AnimeMovie else TvType.Anime) {
            this.posterUrl = poster
            this.year = year
            this.plot = synopsis
            this.tags = genres
            this.showStatus = showStatus

            if (episodesList.isNotEmpty()) {
                addEpisodes(if (isDubbed) DubStatus.Dubbed else DubStatus.Subbed, episodesList)
            }
        }
    }
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Por enquanto desativado como solicitado
        return false
    }
}
