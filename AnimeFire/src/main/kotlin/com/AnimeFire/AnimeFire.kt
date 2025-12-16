package com.AnimeFire

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import java.net.URLEncoder
import kotlinx.coroutines.delay

class AnimeFire : MainAPI() {
    override var mainUrl = "https://animefire.io"
    override var name = "AnimeFire"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)
    override val usesWebView = true

    companion object {
        private const val SEARCH_PATH = "/pesquisar"
        private const val MAX_TRIES = 3
        private const val RETRY_DELAY = 1000L
        private const val TMDB_PROXY_URL = "https://lawliet.euluan1912.workers.dev"
        private const val tmdbImageUrl = "https://image.tmdb.org/t/p"
    }

    override val mainPage = mainPageOf(
        "$mainUrl" to "Lançamentos",
        "$mainUrl" to "Destaques da Semana",
        "$mainUrl" to "Últimos Animes Adicionados",
        "$mainUrl" to "Últimos Episódios Adicionados"
    )

    // ============ BUSCAR THUMB DE ALTA QUALIDADE PARA PESQUISA ============
    private suspend fun getHighQualityThumbForSearch(title: String, year: Int?, isTv: Boolean): String? {
        // PRIORIDADE: TMDB > AniZip > Site
        val tmdbThumb = getTMDBThumbForSearch(title, year, isTv)
        if (tmdbThumb != null) return tmdbThumb
        
        // Se não encontrou no TMDB, tentar AniZip
        val malId = searchMALIdByName(title)
        if (malId != null) {
            val aniZipData = fetchAniZipData(malId)
            val aniZipPoster = aniZipData?.images?.find { it.coverType.equals("Poster", ignoreCase = true) }?.url
            if (aniZipPoster != null) {
                return fixUrl(aniZipPoster)
            }
        }
        
        // Retornar null para usar o poster do site (fallback)
        return null
    }

    private suspend fun getTMDBThumbForSearch(title: String, year: Int?, isTv: Boolean): String? {
        return try {
            val type = if (isTv) "tv" else "movie"
            val encodedQuery = URLEncoder.encode(title, "UTF-8")
            val yearParam = year?.let { "&year=$it" } ?: ""

            val searchUrl = "$TMDB_PROXY_URL/search?query=$encodedQuery&type=$type$yearParam"
            val response = app.get(searchUrl, timeout = 10_000)

            if (response.code != 200) return null

            val searchResult = response.parsedSafe<TMDBSearchResponse>() ?: return null
            val result = searchResult.results.firstOrNull() ?: return null

            result.poster_path?.let { "$tmdbImageUrl/w300$it" } // Tamanho menor para thumbs
        } catch (e: Exception) {
            null
        }
    }

    // ============ FUNÇÃO AUXILIAR PARA SEARCH RESPONSE ============
    private suspend fun Element.toSearchResponse(): AnimeSearchResponse? {
        val href = attr("href") ?: return null
        if (href.isBlank()) return null
        
        val titleElement = selectFirst("h3.animeTitle") ?: return null
        val rawTitle = titleElement.text().trim()
        
        // Limpar título
        val cleanTitle = rawTitle.replace(Regex("(?i)(dublado|legendado|todos os episódios|\\(\\d{4}\\))$"), "").trim()
        
        // Verificar se é filme
        val isMovie = href.contains("/filmes/") || rawTitle.contains("Movie", ignoreCase = true)
        
        // Extrair ano do título se disponível
        val year = Regex("\\((\\d{4})\\)").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
        
        // Buscar thumb de alta qualidade (TMDB > AniZip > Site)
        val highQualityThumb = getHighQualityThumbForSearch(cleanTitle, year, !isMovie)
        
        // Poster do site (fallback)
        val sitePoster = selectFirst("img.imgAnimes, img.owl-lazy, img[src*='animes']")?.let { img ->
            when {
                img.hasAttr("data-src") -> img.attr("data-src")
                img.hasAttr("src") -> img.attr("src")
                else -> null
            }?.takeIf { !it.contains("logo", ignoreCase = true) }
        } ?: selectFirst("img:not([src*='logo']):not([src*='Logo'])")?.attr("src")

        return newAnimeSearchResponse(cleanTitle, fixUrl(href)) {
            this.posterUrl = highQualityThumb ?: sitePoster?.let { fixUrl(it) }
            this.type = if (isMovie) TvType.Movie else TvType.Anime
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        
        val homeItems = when (request.name) {
            "Lançamentos" -> 
                document.select(".owl-carousel-home .divArticleLancamentos a.item")
                    .mapNotNull { element -> 
                        runCatching { element.toSearchResponse() }.getOrNull()
                    }
            "Destaques da Semana" -> 
                document.select(".owl-carousel-semana .divArticleLancamentos a.item")
                    .mapNotNull { element -> 
                        runCatching { element.toSearchResponse() }.getOrNull()
                    }
            "Últimos Animes Adicionados" -> 
                document.select(".owl-carousel-l_dia .divArticleLancamentos a.item")
                    .mapNotNull { element -> 
                        runCatching { element.toSearchResponse() }.getOrNull()
                    }
            "Últimos Episódios Adicionados" -> {
                document.select(".divCardUltimosEpsHome").mapNotNull { card ->
                    runCatching {
                        val link = card.selectFirst("article.card a") ?: return@runCatching null
                        val href = link.attr("href") ?: return@runCatching null
                        
                        val titleElement = card.selectFirst("h3.animeTitle") ?: return@runCatching null
                        val rawTitle = titleElement.text().trim()
                        
                        val epNumber = card.selectFirst(".numEp")?.text()?.toIntOrNull() ?: 1
                        
                        val cleanTitle = rawTitle.replace(Regex("(?i)(dublado|legendado|todos os episódios|\\(\\d{4}\\))$"), "").trim()
                        val displayTitle = "${cleanTitle} - Episódio $epNumber"
                        
                        // Buscar thumb de alta qualidade
                        val year = Regex("\\((\\d{4})\\)").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
                        val highQualityThumb = getHighQualityThumbForSearch(cleanTitle, year, true)
                        
                        // Poster do site (fallback)
                        val sitePoster = card.selectFirst("img.imgAnimesUltimosEps, img[src*='animes']")?.let { img ->
                            when {
                                img.hasAttr("data-src") -> img.attr("data-src")
                                img.hasAttr("src") -> img.attr("src")
                                else -> null
                            }?.takeIf { !it.contains("logo", ignoreCase = true) }
                        } ?: card.selectFirst("img:not([src*='logo'])")?.attr("src")
                        
                        newAnimeSearchResponse(displayTitle, fixUrl(href)) {
                            this.posterUrl = highQualityThumb ?: sitePoster?.let { fixUrl(it) }
                            this.type = TvType.Anime
                        }
                    }.getOrNull()
                }
            }
            else -> emptyList()
        }
        
        return newHomePageResponse(request.name, homeItems.distinctBy { it.url }, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl$SEARCH_PATH/${URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document

        return document.select("article.containerAnimes a.item")
            .mapNotNull { element -> 
                runCatching { element.toSearchResponse() }.getOrNull()
            }
            .take(30)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // Título
        val titleElement = document.selectFirst("h1.quicksand400, .main_div_anime_info h1, h1") ?: 
            throw ErrorLoadingException("Não foi possível encontrar o título")
        val rawTitle = titleElement.text().trim()
        
        // Limpar título
        val year = Regex("\\((\\d{4})\\)").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = rawTitle.replace(Regex("\\(\\d{4}\\)"), "").trim()
        
        // Determinar tipo
        val isMovie = url.contains("/filmes/") || rawTitle.contains("Movie", ignoreCase = true)
        val type = if (isMovie) TvType.Movie else TvType.Anime

        // 1. BUSCAR MAL ID PELO NOME DO ANIME (para AniZip)
        val malId = searchMALIdByName(cleanTitle)

        // 2. BUSCAR DADOS DA ANI.ZIP (para elenco e próximo episódio)
        var aniZipData: AniZipData? = null
        if (malId != null) {
            aniZipData = fetchAniZipData(malId)
        }

        // 3. BUSCAR NO TMDB (apenas para poster, sinopse, ano, tags e trailer)
        val tmdbInfo = searchOnTMDB(cleanTitle, year, !isMovie)

        // 4. EXTRAIR METADADOS DO SITE
        val siteMetadata = extractSiteMetadata(document)
        
        // 5. EXTRAIR EPISÓDIOS (apenas do site)
        val episodes = if (!isMovie) {
            extractEpisodesFromSite(document)
        } else {
            emptyList()
        }

        // 6. EXTRAIR RECOMENDAÇÕES
        val recommendations = extractRecommendations(document)

        // 7. CRIAR RESPOSTA COM AS PRIORIDADES DEFINIDAS
        return createLoadResponseWithPriorities(
            url = url,
            cleanTitle = cleanTitle,
            year = year,
            isMovie = isMovie,
            type = type,
            siteMetadata = siteMetadata,
            aniZipData = aniZipData,
            tmdbInfo = tmdbInfo,
            episodes = episodes,
            recommendations = recommendations
        )
    }

    // ============ BUSCA MAL ID ============
    private suspend fun searchMALIdByName(animeName: String): Int? {
        return try {
            val cleanName = animeName
                .replace(Regex("(?i)\\s*-\\s*Todos os Episódios"), "")
                .replace(Regex("(?i)\\s*\\(Dublado\\)"), "")
                .replace(Regex("(?i)\\s*\\(Legendado\\)"), "")
                .trim()
            
            val query = """
                query {
                    Page(page: 1, perPage: 5) {
                        media(search: "$cleanName", type: ANIME) {
                            title { romaji english native }
                            idMal
                        }
                    }
                }
            """.trimIndent()
            
            val response = app.post(
                "https://graphql.anilist.co",
                data = mapOf("query" to query),
                headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
                timeout = 10_000
            )
            
            if (response.code == 200) {
                val data = response.parsedSafe<AniListResponse>()
                data?.data?.Page?.media?.firstOrNull()?.idMal
            } else null
        } catch (e: Exception) {
            null
        }
    }

    // ============ BUSCA ANI.ZIP ============
    private suspend fun fetchAniZipData(malId: Int): AniZipData? {
        for (attempt in 1..MAX_TRIES) {
            try {
                val response = app.get("https://api.ani.zip/mappings?mal_id=$malId", timeout = 10_000)
                
                if (response.code == 200) {
                    return response.parsedSafe<AniZipData>()
                }
            } catch (e: Exception) {
                delay(RETRY_DELAY * attempt)
            }
        }
        return null
    }

    // ============ METADADOS DO SITE ============
    private data class SiteMetadata(
        val poster: String? = null,
        val plot: String? = null,
        val tags: List<String>? = null,
        val year: Int? = null
    )

    private fun extractSiteMetadata(document: org.jsoup.nodes.Document): SiteMetadata {
        // 1. POSTER
        val posterImg = document.selectFirst(".sub_animepage_img img.transitioning_src")
        val poster = when {
            posterImg?.hasAttr("src") == true -> fixUrl(posterImg.attr("src"))
            posterImg?.hasAttr("data-src") == true -> fixUrl(posterImg.attr("data-src"))
            else -> document.selectFirst("img[src*='/img/animes/']:not([src*='logo'])")
                ?.attr("src")?.let { fixUrl(it) }
        }

        // 2. SINOPSE
        val plot = document.selectFirst("div.divSinopse span.spanAnimeInfo")
            ?.text()
            ?.trim()
            ?.replace(Regex("^Sinopse:\\s*"), "")

        // 3. TAGS/GÊNEROS
        val tags = document.select("a.spanAnimeInfo.spanGeneros")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .takeIf { it.isNotEmpty() }?.toList()

        // 4. ANO
        val year = document.selectFirst("div.animeInfo:contains(Ano:) span.spanAnimeInfo")
            ?.text()
            ?.trim()
            ?.toIntOrNull()

        return SiteMetadata(poster, plot, tags, year)
    }

    // ============ TMDB FUNCTIONS (APENAS PARA CERTAS INFORMAÇÕES) ============
    private suspend fun searchOnTMDB(
        query: String, 
        year: Int?, 
        isTv: Boolean
    ): TMDBInfo? {
        return try {
            val type = if (isTv) "tv" else "movie"
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val yearParam = year?.let { "&year=$it" } ?: ""

            val searchUrl = "$TMDB_PROXY_URL/search?query=$encodedQuery&type=$type$yearParam"
            val response = app.get(searchUrl, timeout = 10_000)

            if (response.code != 200) return null

            val searchResult = response.parsedSafe<TMDBSearchResponse>() ?: return null
            val result = searchResult.results.firstOrNull() ?: return null

            // Buscar detalhes completos
            val details = getTMDBDetails(result.id, isTv)

            // Buscar trailer (SOMENTE TRAILER DO TMDB - PRIORIDADE TMDB)
            val youtubeTrailer = getHighQualityTrailer(details?.videos?.results)

            // Extrair sinopse do TMDB (para prioridade TMDB > site > AniZip)
            val overview = details?.overview?.takeIf { it.isNotBlank() }

            // Extrair gêneros do TMDB (para prioridade TMDB > site)
            val genres = details?.genres?.map { it.name }?.takeIf { it.isNotEmpty() }

            TMDBInfo(
                id = result.id,
                title = if (isTv) result.name else result.title,
                year = if (isTv) {
                    result.first_air_date?.substring(0, 4)?.toIntOrNull()
                } else {
                    result.release_date?.substring(0, 4)?.toIntOrNull()
                },
                posterUrl = result.poster_path?.let { "$tmdbImageUrl/w500$it" }, // Para prioridade poster
                backdropUrl = details?.backdrop_path?.let { "$tmdbImageUrl/w1280$it" },
                overview = overview, // Para prioridade sinopse
                genres = genres, // Para prioridade tags
                youtubeTrailer = youtubeTrailer, // Para prioridade trailer
                duration = details?.runtime
            )
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun getTMDBDetails(id: Int, isTv: Boolean): TMDBDetailsResponse? {
        return try {
            val type = if (isTv) "tv" else "movie"
            val url = "$TMDB_PROXY_URL/$type/$id?append_to_response=videos" // Só videos para trailer

            val response = app.get(url, timeout = 10_000)
            if (response.code != 200) return null

            response.parsedSafe<TMDBDetailsResponse>()
        } catch (e: Exception) {
            null
        }
    }

    private fun getHighQualityTrailer(videos: List<TMDBVideo>?): String? {
        if (videos.isNullOrEmpty()) return null

        return videos.mapNotNull { video ->
            when {
                video.site == "YouTube" && video.type == "Trailer" && video.official == true ->
                    Triple(video.key, 10, "YouTube Trailer Oficial")
                video.site == "YouTube" && video.type == "Trailer" ->
                    Triple(video.key, 9, "YouTube Trailer")
                video.site == "YouTube" && video.type == "Teaser" && video.official == true ->
                    Triple(video.key, 8, "YouTube Teaser Oficial")
                video.site == "YouTube" && video.type == "Teaser" ->
                    Triple(video.key, 7, "YouTube Teaser")
                else -> null
            }
        }
        ?.sortedByDescending { it.second }
        ?.firstOrNull()
        ?.let { (key, _, _) -> "https://www.youtube.com/watch?v=$key" }
    }

    // ============ EPISÓDIOS APENAS DO SITE ============
    private fun extractEpisodesFromSite(document: org.jsoup.nodes.Document): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        val episodeElements = document.select("a.lEp.epT, a.lEp, .divListaEps a, [href*='/video/'], [href*='/episodio/']")
        
        episodeElements.forEachIndexed { index, element ->
            try {
                val href = element.attr("href")
                if (href.isBlank()) return@forEachIndexed
                
                val text = element.text().trim()
                if (text.isBlank()) return@forEachIndexed
                
                val episodeNumber = extractEpisodeNumber(text)
                val seasonNumber = 1
                
                val episodeName = element.selectFirst(".ep-name, .title")?.text()?.trim()
                    ?: text.substringAfterLast("-").trim()
                    ?: "Episódio $episodeNumber"

                episodes.add(
                    newEpisode(fixUrl(href)) {
                        this.name = episodeName
                        this.season = seasonNumber
                        this.episode = episodeNumber
                    }
                )
                
            } catch (e: Exception) {
                // Ignorar erro e continuar
            }
        }
        
        return episodes.sortedBy { it.episode }
    }

    private fun extractEpisodeNumber(text: String): Int {
        val patterns = listOf(
            Regex("Epis[oó]dio\\s*(\\d+)"),
            Regex("Ep\\.?\\s*(\\d+)"),
            Regex("(\\d{1,3})\\s*-"),
            Regex("#(\\d+)"),
            Regex("\\b(\\d{1,4})\\b")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                val num = match.groupValues[1].toIntOrNull()
                if (num != null) return num
            }
        }
        
        return Regex("\\d+").find(text)?.value?.toIntOrNull() ?: 1
    }

    // ============ CRIAR RESPOSTA COM PRIORIDADES ESPECÍFICAS ============
    private suspend fun createLoadResponseWithPriorities(
        url: String,
        cleanTitle: String,
        year: Int?,
        isMovie: Boolean,
        type: TvType,
        siteMetadata: SiteMetadata,
        aniZipData: AniZipData?,
        tmdbInfo: TMDBInfo?,
        episodes: List<Episode>,
        recommendations: List<SearchResponse>
    ): LoadResponse {
        
        // ============ APLICAR PRIORIDADES DEFINIDAS ============
        
        // 1. POSTER FINAL: TMDB > AniZip > Site
        val finalPoster = tmdbInfo?.posterUrl ?:
                         aniZipData?.images?.find { it.coverType.equals("Poster", ignoreCase = true) }?.url?.let { fixUrl(it) } ?:
                         siteMetadata.poster
        
        // 2. BACKDROP: TMDB > AniZip > Site
        val finalBackdrop = tmdbInfo?.backdropUrl ?:
                           aniZipData?.images?.find { it.coverType.equals("Fanart", ignoreCase = true) }?.url?.let { fixUrl(it) } ?:
                           siteMetadata.poster?.let { fixUrl(it) }
        
        // 3. SINOPSE: TMDB > Site > AniZip
        val finalPlot = tmdbInfo?.overview?.takeIf { it.isNotBlank() } ?:
                       siteMetadata.plot?.takeIf { it.isNotBlank() } ?:
                       aniZipData?.episodes?.values?.firstOrNull()?.overview?.takeIf { it.isNotBlank() }
        
        // 4. ANO: TMDB > Site > AniZip
        val finalYear = tmdbInfo?.year ?:
                       year ?:
                       siteMetadata.year ?:
                       aniZipData?.episodes?.values?.firstOrNull()?.airDateUtc?.substring(0, 4)?.toIntOrNull()
        
        // 5. TAGS: TMDB > Site
        val finalTags = (tmdbInfo?.genres ?: emptyList()) + 
                       (siteMetadata.tags ?: emptyList())
        
        // 6. ELENCO: AniZip > TMDB
        val actors = extractActorsWithPriority(aniZipData, tmdbInfo)
        
        // 7. LANÇAMENTO DO PRÓXIMO EPISÓDIO (apenas do AniZip)
        val nextEpisodeDate = extractNextEpisodeDate(aniZipData)
        
        // 8. TRAILER: TMDB (apenas)
        val trailer = tmdbInfo?.youtubeTrailer
        
        return if (isMovie) {
            newMovieLoadResponse(cleanTitle, url, type, url) {
                this.year = finalYear
                this.plot = finalPlot
                this.tags = finalTags.distinct().take(10)
                this.posterUrl = finalPoster
                this.backgroundPosterUrl = finalBackdrop
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
                
                // Adicionar atores com prioridade AniZip > TMDB
                if (actors.isNotEmpty()) {
                    addActors(actors)
                }
                
                // Adicionar trailer do TMDB (apenas TMDB)
                trailer?.let { trailerUrl ->
                    addTrailer(trailerUrl)
                }
            }
        } else {
            newAnimeLoadResponse(cleanTitle, url, type) {
                addEpisodes(DubStatus.Subbed, episodes)
                
                this.year = finalYear
                this.plot = finalPlot
                this.tags = finalTags.distinct().take(10)
                this.posterUrl = finalPoster
                this.backgroundPosterUrl = finalBackdrop
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
                
                // Adicionar atores com prioridade AniZip > TMDB
                if (actors.isNotEmpty()) {
                    addActors(actors)
                }
                
                // Adicionar trailer do TMDB (apenas TMDB)
                trailer?.let { trailerUrl ->
                    addTrailer(trailerUrl)
                }
            }
        }
    }

    // ============ EXTRAIR ATORES COM PRIORIDADE ANIZIP > TMDB ============
    private fun extractActorsWithPriority(aniZipData: AniZipData?, tmdbInfo: TMDBInfo?): List<Actor> {
        val actors = mutableListOf<Actor>()
        
        // 1. PRIMEIRO: Extrair atores do AniZip (PRIORIDADE ANIZIP)
        if (aniZipData != null) {
            val aniZipActors = extractActorsFromAniZip(aniZipData)
            actors.addAll(aniZipActors)
        }
        
        // 2. DEPOIS: Se não tem atores suficientes do AniZip, pegar do TMDB
        if (actors.size < 5 && tmdbInfo != null) {
            // Extrair atores do TMDB (removido porque elenco tem prioridade AniZip > TMDB)
            // Mas podemos usar se não tiver nenhum do AniZip
        }
        
        return actors.take(10)
    }

    private fun extractActorsFromAniZip(aniZipData: AniZipData?): List<Actor> {
        val actors = mutableListOf<Actor>()
        
        if (aniZipData == null) return actors
        
        // Procurar por informações de atores/dubladores nos episódios
        aniZipData.episodes?.values?.forEach { episode ->
            episode.overview?.let { overview ->
                // Procurar por padrões comuns de informações de dubladores (em japonês/inglês)
                val actorPatterns = listOf(
                    Regex("Voice Actor[:\\.]\\s*(.+)", RegexOption.IGNORE_CASE),
                    Regex("Seiyū[:\\.]\\s*(.+)", RegexOption.IGNORE_CASE),
                    Regex("Cast[:\\.]\\s*(.+)", RegexOption.IGNORE_CASE),
                    Regex("Starring[:\\.]\\s*(.+)", RegexOption.IGNORE_CASE),
                    Regex("Performed by[:\\.]\\s*(.+)", RegexOption.IGNORE_CASE),
                    Regex("声優[:\\.]\\s*(.+)"), // Japonês para seiyū
                    Regex("CV[:\\.]\\s*(.+)") // Character Voice
                )
                
                actorPatterns.forEach { pattern ->
                    val match = pattern.find(overview)
                    if (match != null) {
                        val actorsText = match.groupValues[1].trim()
                        // Tentar extrair múltiplos atores separados por vírgula, "and", "、", "・"
                        val actorNames = actorsText.split(",", " and ", "&", "、", "・", "/")
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                        
                        actorNames.forEach { actorName ->
                            if (actorName.isNotBlank() && !actors.any { it.name.equals(actorName, ignoreCase = true) }) {
                                actors.add(Actor(name = actorName))
                            }
                        }
                    }
                }
            }
        }
        
        return actors
    }

    // ============ EXTRAIR DATA DO PRÓXIMO EPISÓDIO DO ANIZIP ============
    private fun extractNextEpisodeDate(aniZipData: AniZipData?): Long? {
        if (aniZipData == null) return null
        
        // Procurar por episódios futuros no AniZip
        aniZipData.episodes?.values?.forEach { episode ->
            episode.airDateUtc?.let { airDateStr ->
                try {
                    // Formato esperado: "2024-01-01T00:00:00Z"
                    val dateFormatter = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
                    val date = dateFormatter.parse(airDateStr)
                    val now = System.currentTimeMillis()
                    
                    // Se a data do episódio for no futuro, retornar
                    if (date.time > now) {
                        return date.time
                    }
                } catch (e: Exception) {
                    // Ignorar se não conseguir parsear a data
                }
            }
        }
        
        return null
    }

    private suspend fun extractRecommendations(document: org.jsoup.nodes.Document): List<SearchResponse> {
        return document.select(".owl-carousel-anime .divArticleLancamentos a.item")
            .mapNotNull { element -> 
                runCatching { element.toSearchResponse() }.getOrNull()
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

    // ============ CLASSES DE DADOS ============
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListResponse(
        @JsonProperty("data") val data: AniListData? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListData(
        @JsonProperty("Page") val Page: AniListPage? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListPage(
        @JsonProperty("media") val media: List<AniListMedia>? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListMedia(
        @JsonProperty("idMal") val idMal: Int? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniZipData(
        @JsonProperty("titles") val titles: Map<String, String>? = null,
        @JsonProperty("images") val images: List<AniZipImage>? = null,
        @JsonProperty("episodes") val episodes: Map<String, AniZipEpisode>? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniZipImage(
        @JsonProperty("coverType") val coverType: String? = null,
        @JsonProperty("url") val url: String? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniZipEpisode(
        @JsonProperty("episode") val episode: String? = null,
        @JsonProperty("title") val title: Map<String, String>? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("runtime") val runtime: Int? = null,
        @JsonProperty("rating") val rating: String? = null,
        @JsonProperty("airDateUtc") val airDateUtc: String? = null
    )

    // ============ CLASSES TMDB ============
    private data class TMDBInfo(
        val id: Int,
        val title: String?,
        val year: Int?,
        val posterUrl: String?,
        val backdropUrl: String?,
        val overview: String?,
        val genres: List<String>?,
        val youtubeTrailer: String?,
        val duration: Int?
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBSearchResponse(
        @JsonProperty("results") val results: List<TMDBResult>
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBResult(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("release_date") val release_date: String? = null,
        @JsonProperty("first_air_date") val first_air_date: String? = null,
        @JsonProperty("poster_path") val poster_path: String?
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBDetailsResponse(
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("backdrop_path") val backdrop_path: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("genres") val genres: List<TMDBGenre>?,
        @JsonProperty("videos") val videos: TMDBVideos?
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBGenre(
        @JsonProperty("name") val name: String
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBVideos(
        @JsonProperty("results") val results: List<TMDBVideo>
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBVideo(
        @JsonProperty("key") val key: String,
        @JsonProperty("site") val site: String,
        @JsonProperty("type") val type: String,
        @JsonProperty("official") val official: Boolean? = false
    )
}
