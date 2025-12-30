package com.AniTube

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLDecoder

class AniTube : MainAPI() {
    override var mainUrl = "https://www.anitube.news"
    override var name = "AniTube"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime)
    override val usesWebView = false

    companion object {
        private const val SEARCH_PATH = "/?s="
        private const val ANIME_CARD = ".aniItem"
        private const val EPISODE_CARD = ".epiItem"
        private const val TITLE_SELECTOR = ".aniItemNome, .epiItemNome"
        private const val POSTER_SELECTOR = ".aniItemImg img, .epiItemImg img"
        private const val AUDIO_BADGE_SELECTOR = ".aniCC, .epiCC"
        private const val EPISODE_NUMBER_SELECTOR = ".epiItemInfos .epiItemNome"
        private const val LATEST_EPISODES_SECTION = ".epiContainer"
        private const val ANIME_TITLE = "h1"
        private const val ANIME_POSTER = "#capaAnime img"
        private const val ANIME_SYNOPSIS = "#sinopse2"
        private const val ANIME_METADATA = ".boxAnimeSobre .boxAnimeSobreLinha"
        private const val EPISODE_LIST = ".pagAniListaContainer > a"
        private const val PLAYER_FHD = "#blog2 iframe"
        private const val PLAYER_BACKUP = "#blog1 iframe"

        // URL base para busca de imagens de alta qualidade
        private const val ANILIST_API = "https://graphql.anilist.co"
        private const val TMDB_API = "https://api.themoviedb.org/3"
        
        // Lista de palavras para remover durante a busca
        private val REMOVE_WORDS = listOf(
            "dublado", "legendado", "episodio", "ep", "ep.", "temporada",
            "season", "part", "parte", "filme", "movie", "ova", "special",
            "especial", "complete", "completo"
        )
    }

    // 📊 Mapa de gêneros para a página principal
    private val genresMap = mapOf(
        "Ação" to "acao", "Artes Marciais" to "artes%20marciais", "Aventura" to "aventura",
        "Comédia" to "comedia", "Comédia Romântica" to "comedia%20romantica", "Drama" to "drama",
        "Ecchi" to "ecchi", "Esporte" to "esporte", "Fantasia" to "fantasia",
        "Ficção Científica" to "ficcao%20cientifica", "Jogos" to "jogos", "Magia" to "magia",
        "Mecha" to "mecha", "Mistério" to "misterio", "Musical" to "musical",
        "Romance" to "romance", "Seinen" to "seinen", "Shoujo-ai" to "shoujo%20ai",
        "Shounen" to "shounen", "Slice Of Life" to "slice%20of%20life", "Sobrenatural" to "sobrenatural",
        "Superpoder" to "superpoder", "Terror" to "terror", "Vida Escolar" to "vida%20escolar",
        "Shoujo" to "shoujo", "Shounen-ai" to "shounen%20ai", "Yaoi" to "yaoi",
        "Yuri" to "yuri", "Harem" to "harem", "Isekai" to "isekai", "Militar" to "militar",
        "Policial" to "policial", "Psicológico" to "psicologico", "Samurai" to "samurai",
        "Vampiros" to "vampiros", "Zumbi" to "zumbi", "Histórico" to "historico",
        "Mágica" to "magica", "Cyberpunk" to "cyberpunk", "Espaço" to "espaco",
        "Demônios" to "demônios", "Vida Cotidiana" to "vida%20cotidiana"
    )

    override val mainPage = mainPageOf(
        "$mainUrl" to "Últimos Episódios",
        "$mainUrl" to "Animes Mais Vistos",
        "$mainUrl" to "Animes Recentes",
        *genresMap.map { (genre, slug) -> "$mainUrl/?s=$slug" to genre }.toTypedArray()
    )

    // 🔧 FUNÇÃO: Limpar título para busca
    private fun cleanTitleForSearch(dirtyTitle: String): String {
        var clean = dirtyTitle
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

        // Remove números no final
        clean = clean.replace("\\s*\\d+\\s*$".toRegex(), "").trim()

        // Remove palavras comuns que atrapalham a busca
        REMOVE_WORDS.forEach { word ->
            val regex = "(?i)\\b$word\\b".toRegex()
            clean = clean.replace(regex, "").trim()
        }

        return clean.ifBlank { dirtyTitle }
    }

    // 🔧 FUNÇÃO: Buscar imagens no AniList (GraphQL)
    private suspend fun searchAniList(animeTitle: String): AniListResult? {
        val searchTitle = cleanTitleForSearch(animeTitle)
        if (searchTitle.length < 2) return null

        try {
            val query = """
                query {
                    Page(page: 1, perPage: 5) {
                        media(search: "$searchTitle", type: ANIME) {
                            id
                            title {
                                romaji
                                english
                                native
                            }
                            coverImage {
                                extraLarge
                                large
                                medium
                            }
                            bannerImage
                            description
                            seasonYear
                            genres
                            status
                            averageScore
                        }
                    }
                }
            """.trimIndent()

            val response = app.post(
                ANILIST_API,
                data = mapOf("query" to query),
                headers = mapOf("Content-Type" to "application/json")
            )

            val json = response.parsedSafe<AniListResponse>()
            val media = json?.data?.page?.media?.firstOrNull()
            
            return if (media != null) {
                AniListResult(
                    title = media.title.romaji ?: media.title.english ?: media.title.native ?: animeTitle,
                    posterUrl = media.coverImage?.extraLarge ?: media.coverImage?.large ?: media.coverImage?.medium,
                    bannerUrl = media.bannerImage,
                    description = media.description?.replace(Regex("<.*?>"), ""),
                    year = media.seasonYear,
                    genres = media.genres ?: emptyList(),
                    status = media.status,
                    rating = media.averageScore?.toFloat()?.div(10f)
                )
            } else {
                null
            }
        } catch (e: Exception) {
            return null
        }
    }

    // 🔧 FUNÇÃO: Buscar imagens no TMDB (fallback)
    private suspend fun searchTMDB(animeTitle: String): TMDBResult? {
        val searchTitle = cleanTitleForSearch(animeTitle)
        if (searchTitle.length < 2) return null

        try {
            // TMDB não precisa de API key para busca básica
            val searchUrl = "$TMDB_API/search/tv?query=${searchTitle.replace(" ", "+")}&language=pt-BR"
            val response = app.get(searchUrl)
            val json = response.parsedSafe<TMDBSearchResponse>()
            
            val result = json?.results?.firstOrNull()
            return if (result != null) {
                TMDBResult(
                    title = result.name ?: animeTitle,
                    posterUrl = if (result.poster_path != null) "https://image.tmdb.org/t/p/w500${result.poster_path}" else null,
                    bannerUrl = if (result.backdrop_path != null) "https://image.tmdb.org/t/p/original${result.backdrop_path}" else null,
                    year = result.first_air_date?.substring(0, 4)?.toIntOrNull(),
                    rating = result.vote_average
                )
            } else {
                null
            }
        } catch (e: Exception) {
            return null
        }
    }

    // 🔧 FUNÇÃO: Buscar metadados de alta qualidade
    private suspend fun getEnhancedMetadata(animeTitle: String): EnhancedMetadata? {
        // 1. Tenta AniList primeiro (melhor para animes)
        val anilistResult = searchAniList(animeTitle)
        if (anilistResult != null && anilistResult.posterUrl != null) {
            return EnhancedMetadata(
                title = anilistResult.title,
                posterUrl = anilistResult.posterUrl,
                bannerUrl = anilistResult.bannerUrl,
                description = anilistResult.description,
                year = anilistResult.year,
                genres = anilistResult.genres,
                status = anilistResult.status,
                rating = anilistResult.rating
            )
        }

        // 2. Fallback para TMDB
        val tmdbResult = searchTMDB(animeTitle)
        if (tmdbResult != null && tmdbResult.posterUrl != null) {
            return EnhancedMetadata(
                title = tmdbResult.title,
                posterUrl = tmdbResult.posterUrl,
                bannerUrl = tmdbResult.bannerUrl,
                description = null,
                year = tmdbResult.year,
                genres = emptyList(),
                status = null,
                rating = tmdbResult.rating
            )
        }

        return null
    }

    // 📦 Classes para armazenar resultados
    data class AniListResult(
        val title: String,
        val posterUrl: String?,
        val bannerUrl: String?,
        val description: String?,
        val year: Int?,
        val genres: List<String>,
        val status: String?,
        val rating: Float?
    )

    data class TMDBResult(
        val title: String,
        val posterUrl: String?,
        val bannerUrl: String?,
        val year: Int?,
        val rating: Float?
    )

    data class EnhancedMetadata(
        val title: String,
        val posterUrl: String?,
        val bannerUrl: String?,
        val description: String?,
        val year: Int?,
        val genres: List<String>,
        val status: String?,
        val rating: Float?
    )

    // Classes para parse do JSON
    data class AniListResponse(
        val data: AniListData?
    )

    data class AniListData(
        val page: AniListPage?
    )

    data class AniListPage(
        val media: List<AniListMedia>?
    )

    data class AniListMedia(
        val id: Int,
        val title: AniListTitle,
        val coverImage: AniListCoverImage?,
        val bannerImage: String?,
        val description: String?,
        val seasonYear: Int?,
        val genres: List<String>?,
        val status: String?,
        val averageScore: Int?
    )

    data class AniListTitle(
        val romaji: String?,
        val english: String?,
        val native: String?
    )

    data class AniListCoverImage(
        val extraLarge: String?,
        val large: String?,
        val medium: String?
    )

    data class TMDBSearchResponse(
        val results: List<TMDBResultItem>?
    )

    data class TMDBResultItem(
        val id: Int,
        val name: String?,
        val poster_path: String?,
        val backdrop_path: String?,
        val first_air_date: String?,
        val vote_average: Float?
    )

    // 🔧 FUNÇÕES ORIGINAIS (mantidas)
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
            "\\b(\\d{1,2})\\b".toRegex()
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
        return element.selectFirst(AUDIO_BADGE_SELECTOR)
            ?.text()
            ?.contains("Dublado", true) ?: false
    }
    
    private fun extractM3u8FromUrl(url: String): String? {
        return if (url.contains("d=")) {
            try {
                URLDecoder.decode(url.substringAfter("d=").substringBefore("&"), "UTF-8")
            } catch (e: Exception) { null }
        } else { url }
    }
    
    private fun Element.toEpisodeSearchResponse(): AnimeSearchResponse? {
        val href = selectFirst("a")?.attr("href") ?: return null
        if (!href.contains("/video/")) return null
        
        val episodeTitle = selectFirst(EPISODE_NUMBER_SELECTOR)?.text()?.trim() ?: return null
        val episodeNumber = extractEpisodeNumber(episodeTitle) ?: 1
        val animeTitle = extractAnimeTitleFromEpisode(episodeTitle)
        val posterUrl = selectFirst(POSTER_SELECTOR)?.attr("src")?.let { fixUrl(it) }
        val isDubbed = isDubbed(this)
        
        val displayName = cleanTitle(animeTitle)
        
        val urlWithPoster = if (posterUrl != null) {
            "$href|poster=$posterUrl"
        } else {
            href
        }
        
        return newAnimeSearchResponse(displayName, fixUrl(urlWithPoster)) {
            this.posterUrl = posterUrl
            this.type = TvType.Anime
            
            val dubStatus = if (isDubbed) DubStatus.Dubbed else DubStatus.Subbed
            addDubStatus(dubStatus, episodeNumber)
        }
    }
    
    private fun Element.toAnimeSearchResponse(): AnimeSearchResponse? {
        val href = selectFirst("a")?.attr("href") ?: return null
        
        val rawTitle = selectFirst(TITLE_SELECTOR)?.text()?.trim() ?: return null
        val cleanedTitle = cleanTitle(rawTitle).ifBlank { return null }
        
        val posterUrl = selectFirst(POSTER_SELECTOR)?.attr("src")?.let { fixUrl(it) }
        val isDubbed = isDubbed(this)
        
        return newAnimeSearchResponse(cleanedTitle, fixUrl(href)) {
            this.posterUrl = posterUrl
            this.type = TvType.Anime
            addDubStatus(isDubbed, null)
        }
    }

    // 🏠 FUNÇÃO: Página principal
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val baseUrl = request.data
        
        if (baseUrl.contains("/?s=")) {
            val url = if (page > 1) baseUrl.replace("/?s=", "/page/$page/?s=") else baseUrl
            val document = app.get(url).document
            
            val allItems = document.select("$ANIME_CARD, $EPISODE_CARD")
                .mapNotNull { 
                    val isEpisode = it.selectFirst(EPISODE_NUMBER_SELECTOR) != null
                    if (isEpisode) {
                        it.toEpisodeSearchResponse()
                    } else {
                        it.toAnimeSearchResponse()
                    }
                }
                .distinctBy { it.url }
            
            return newHomePageResponse(request.name, allItems, hasNext = true)
        }
        
        val document = app.get(baseUrl).document
        
        return when (request.name) {
            "Últimos Episódios" -> {
                val episodeElements = document.select("$LATEST_EPISODES_SECTION $EPISODE_CARD")
                val items = episodeElements
                    .mapNotNull { it.toEpisodeSearchResponse() }
                    .distinctBy { it.url }
                
                newHomePageResponse(
                    list = HomePageList(request.name, items, isHorizontalImages = true),
                    hasNext = false
                )
            }
            "Animes Mais Vistos" -> {
                var popularItems = listOf<AnimeSearchResponse>()
                
                for (container in document.select(".aniContainer")) {
                    val titleElement = container.selectFirst(".aniContainerTitulo")
                    if (titleElement != null && titleElement.text().contains("Animes Mais Vistos", true)) {
                        popularItems = container.select(".aniItem")
                            .mapNotNull { it.toAnimeSearchResponse() }
                            .distinctBy { it.url }
                            .take(10)
                        break
                    }
                }
                
                if (popularItems.isEmpty()) {
                    val slides = document.select("#splide01 .splide__slide")
                        .filterNot { it.hasClass("splide__slide--clone") }
                    
                    popularItems = slides
                        .mapNotNull { slide ->
                            slide.selectFirst(".aniItem")?.toAnimeSearchResponse()
                        }
                        .distinctBy { it.url }
                        .take(10)
                }
                
                newHomePageResponse(
                    list = HomePageList(request.name, popularItems, isHorizontalImages = false),
                    hasNext = false
                )
            }
            "Animes Recentes" -> {
                var recentItems = listOf<AnimeSearchResponse>()
                
                for (container in document.select(".aniContainer")) {
                    val titleElement = container.selectFirst(".aniContainerTitulo")
                    if (titleElement != null && titleElement.text().contains("ANIMES RECENTES", true)) {
                        recentItems = container.select(".aniItem")
                            .mapNotNull { it.toAnimeSearchResponse() }
                            .distinctBy { it.url }
                            .take(10)
                        break
                    }
                }
                
                if (recentItems.isEmpty()) {
                    val slides = document.select("#splide02 .splide__slide")
                        .filterNot { it.hasClass("splide__slide--clone") }
                    
                    recentItems = slides
                        .mapNotNull { slide ->
                            slide.selectFirst(".aniItem")?.toAnimeSearchResponse()
                        }
                        .distinctBy { it.url }
                        .take(10)
                }
                
                newHomePageResponse(
                    list = HomePageList(request.name, recentItems, isHorizontalImages = false),
                    hasNext = false
                )
            }
            else -> newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    // 🔍 FUNÇÃO: Busca
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()
        
        val document = app.get("$mainUrl$SEARCH_PATH${query.replace(" ", "+")}").document
        
        return document.select("$ANIME_CARD, $EPISODE_CARD")
            .mapNotNull { 
                val isEpisode = it.selectFirst(EPISODE_NUMBER_SELECTOR) != null
                if (isEpisode) {
                    it.toEpisodeSearchResponse()
                } else {
                    it.toAnimeSearchResponse()
                }
            }
            .distinctBy { it.url }
    }

    // 📥 FUNÇÃO: Carregar anime (MODIFICADA com imagens de alta qualidade)
    override suspend fun load(url: String): LoadResponse {
        val parts = url.split("|poster=")
        val actualUrl = parts[0]
        val thumbPoster = parts.getOrNull(1)?.let { if (it.isNotBlank()) fixUrl(it) else null }
        
        val document = app.get(actualUrl).document
        
        val rawTitle = document.selectFirst(ANIME_TITLE)?.text()?.trim() ?: "Sem Título"
        val episodeNumber = extractEpisodeNumber(rawTitle) ?: 1
        val title = cleanTitle(rawTitle)
        
        // 🔧 BUSCA IMAGENS DE ALTA QUALIDADE
        var poster = thumbPoster ?: document.selectFirst(ANIME_POSTER)?.attr("src")?.let { fixUrl(it) }
        var banner: String? = null
        var enhancedSynopsis: String? = null
        var enhancedYear: Int? = null
        var enhancedGenres = emptyList<String>()
        
        // Busca metadados melhorados (apenas se o título for razoável)
        if (title.length >= 3 && !title.matches(Regex(".*\\d+.*"))) {
            val enhancedMetadata = getEnhancedMetadata(title)
            if (enhancedMetadata != null) {
                // Usa imagens de alta qualidade se disponíveis
                if (enhancedMetadata.posterUrl != null) {
                    poster = enhancedMetadata.posterUrl
                }
                banner = enhancedMetadata.bannerUrl
                enhancedSynopsis = enhancedMetadata.description
                enhancedYear = enhancedMetadata.year
                enhancedGenres = enhancedMetadata.genres
                
                // Adiciona rating à sinopse se disponível
                val ratingText = enhancedMetadata.rating?.let { 
                    "⭐ **Avaliação:** ${String.format("%.1f", it)}/10\n\n" 
                } ?: ""
                
                if (ratingText.isNotEmpty() && enhancedSynopsis != null) {
                    enhancedSynopsis = ratingText + enhancedSynopsis
                }
            }
        }
    
        val siteSynopsis = document.selectFirst(ANIME_SYNOPSIS)?.text()?.trim()
        
        // Usa sinopse melhorada se disponível, senão usa a do site
        val synopsis = when {
            actualUrl.contains("/video/") -> {
                siteSynopsis ?: "Episódio $episodeNumber de $title"
            }
            enhancedSynopsis != null -> {
                enhancedSynopsis
            }
            else -> {
                siteSynopsis ?: "Sinopse não disponível."
            }
        }
        
        var year: Int? = enhancedYear
        var episodes: Int? = null
        var genres = enhancedGenres
        var audioType = ""
        
        // Complementa com dados do site se necessário
        if (genres.isEmpty() || year == null) {
            document.select(ANIME_METADATA).forEach { element ->
                val text = element.text()
                when {
                    text.contains("Gênero:", true) && genres.isEmpty() -> 
                        genres = text.substringAfter("Gênero:").split(",").map { it.trim() }
                    text.contains("Ano:", true) && year == null -> 
                        year = text.substringAfter("Ano:").trim().toIntOrNull()
                    text.contains("Episódios:", true) -> 
                        episodes = text.substringAfter("Episódios:").trim().toIntOrNull()
                    text.contains("Tipo de Episódio:", true) -> 
                        audioType = text.substringAfter("Tipo de Episódio:").trim()
                }
            }
        }
        
        val isDubbed = rawTitle.contains("dublado", true) || audioType.contains("dublado", true)
        
        val episodesList = document.select(EPISODE_LIST).mapNotNull { element ->
            val episodeTitle = element.text().trim()
            val episodeUrl = element.attr("href")
            val epNumber = extractEpisodeNumber(episodeTitle) ?: 1
            
            newEpisode(episodeUrl) {
                this.name = "Episódio $epNumber"
                this.episode = epNumber
                this.posterUrl = poster
            }
        }
        
        val allEpisodes = if (episodesList.isEmpty() && actualUrl.contains("/video/")) {
            listOf(newEpisode(actualUrl) {
                this.name = "Episódio $episodeNumber"
                this.episode = episodeNumber
                this.posterUrl = poster
            })
        } else {
            episodesList
        }
        
        val sortedEpisodes = allEpisodes.sortedBy { it.episode }
        val showStatus = if (episodes != null && sortedEpisodes.size >= episodes) ShowStatus.Completed else ShowStatus.Ongoing
        
        // 🔧 RETORNA COM IMAGENS DE ALTA QUALIDADE
        return newAnimeLoadResponse(title, actualUrl, TvType.Anime) {
            this.posterUrl = poster
            this.backgroundPosterUrl = banner // BANNER DE ALTA QUALIDADE
            this.year = year
            this.plot = synopsis
            this.tags = genres
            this.showStatus = showStatus
            
            if (sortedEpisodes.isNotEmpty()) addEpisodes(if (isDubbed) DubStatus.Dubbed else DubStatus.Subbed, sortedEpisodes)
        }
    }

    // ▶️ FUNÇÃO: Carregar links
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val actualUrl = data.split("|poster=")[0]
        val document = app.get(actualUrl).document
        
        document.selectFirst(PLAYER_FHD)?.let { iframe ->
            val src = iframe.attr("src").takeIf { it.isNotBlank() } ?: return@let
            val m3u8Url = extractM3u8FromUrl(src) ?: src
            
            callback(newExtractorLink(name, "1080p", m3u8Url, ExtractorLinkType.M3U8) {
                referer = "$mainUrl/"
                quality = 1080
            })
            return true
        }
        
        document.selectFirst(PLAYER_BACKUP)?.let { iframe ->
            val src = iframe.attr("src").takeIf { it.isNotBlank() } ?: return@let
            val isM3u8 = src.contains("m3u8", true)
            
            val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            
            callback(newExtractorLink(name, "Backup", src, linkType) {
                referer = "$mainUrl/"
                quality = 720
            })
            return true
        }
        
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.contains("m3u8", true)) {
                val m3u8Url = extractM3u8FromUrl(src) ?: src
                
                callback(newExtractorLink(name, "Auto", m3u8Url, ExtractorLinkType.M3U8) {
                    referer = "$mainUrl/"
                    quality = 720
                })
                return true
            }
        }
        
        return false
    }
}
