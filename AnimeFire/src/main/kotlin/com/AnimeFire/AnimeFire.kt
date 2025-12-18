package com.AnimeFire

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import kotlinx.coroutines.delay
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.gson.JsonParser
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

class AnimeFire : MainAPI() {
    override var mainUrl = "https://animefire.io"
    override var name = "AnimeFire"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie, TvType.OVA)
    override val usesWebView = true

    companion object {
        private const val SEARCH_PATH = "/pesquisar"
        private const val MAX_TRIES = 3
        private const val RETRY_DELAY = 1000L
        private const val tmdbImageUrl = "https://image.tmdb.org/t/p"
        private const val TRANSLATION_ENABLED = true
        private const val WORKERS_URL = "https://animefire.euluan1912.workers.dev" // Proxy APENAS para tradução
        private const val MAX_CACHE_SIZE = 500
    }

    // ============ TMDB COM BuildConfig ============
    private val tmdbApiKey = BuildConfig.TMDB_API_KEY
    private val tmdbAccessToken = BuildConfig.TMDB_ACCESS_TOKEN
    private val tmdbBaseUrl = "https://api.themoviedb.org/3"

    override val mainPage = mainPageOf(
        "$mainUrl" to "Lançamentos",
        "$mainUrl" to "Destaques da Semana",
        "$mainUrl" to "Últimos Animes Adicionados",
        "$mainUrl" to "Últimos Episódios Adicionados"
    )

    // ============ CACHE SIMPLES EM MEMÓRIA ============
    private val translationCache = mutableMapOf<String, String>()
    private val cacheHits = mutableMapOf<String, Int>()
   
    private suspend fun translateWithCache(text: String): String {
        if (!TRANSLATION_ENABLED || text.isBlank() || text.length < 3) return text
        if (isProbablyPortuguese(text)) return text
        
        val cached = translationCache[text]
        if (cached != null) {
            cacheHits[text] = (cacheHits[text] ?: 0) + 1
            return cached
        }
        
        val translated = translateText(text)
        
        if (translated != text && translated.isNotBlank()) {
            if (translationCache.size >= MAX_CACHE_SIZE) {
                val leastUsed = cacheHits.entries.sortedBy { it.value }.firstOrNull()
                leastUsed?.key?.let { 
                    translationCache.remove(it)
                    cacheHits.remove(it)
                }
            }
            
            translationCache[text] = translated
            cacheHits[text] = 0
        }
        
        return translated
    }
    
    private suspend fun translateText(text: String): String {
        if (!TRANSLATION_ENABLED || text.isBlank() || text.length < 3) return text
        if (isProbablyPortuguese(text)) return text
        
        return try {
            val workersTranslated = translateWithWorkers(text)
            if (workersTranslated != text) return workersTranslated
            
            translateDirectGoogle(text)
        } catch (e: Exception) {
            text
        }
    }
    
    private fun isProbablyPortuguese(text: String): Boolean {
        val portugueseWords = listOf("episódio", "temporada", "sinopse", "dublado", 
            "legendado", "assistir", "anime", "filme", "série", "ação", "aventura")
        
        val lowerText = text.lowercase()
        return portugueseWords.any { lowerText.contains(it) } ||
               lowerText.contains(Regex("[áéíóúãõç]"))
    }
    
    private suspend fun translateWithWorkers(text: String): String {
        return try {
            val encodedText = URLEncoder.encode(text, "UTF-8")
            val url = "$WORKERS_URL/translate?text=$encodedText&target=pt"
            
            val response = app.get(url, timeout = 5000)
            
            if (response.code == 200) {
                val json = JsonParser.parseString(response.text)
                
                val translated = when {
                    json.isJsonObject -> {
                        json.asJsonObject.get("translatedText")?.asString
                            ?: json.asJsonObject.get("text")?.asString
                    }
                    json.isJsonPrimitive -> json.asString
                    else -> null
                }
                
                translated?.takeIf { it.isNotBlank() && it != text } ?: text
            } else {
                text
            }
        } catch (e: Exception) {
            text
        }
    }
    
    private suspend fun translateDirectGoogle(text: String): String {
        return try {
            val encodedText = URLEncoder.encode(text, "UTF-8")
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=pt&dt=t&q=$encodedText"
            
            val response = app.get(url, timeout = 3000)
            
            if (response.code == 200) {
                val json = JsonParser.parseString(response.text)
                val translated = StringBuilder()
                
                json.asJsonArray?.get(0)?.asJsonArray?.forEach { arrayElement ->
                    arrayElement.asJsonArray?.get(0)?.asString?.let { 
                        translated.append(it) 
                    }
                }
                
                translated.toString().ifBlank { text }
            } else {
                text
            }
        } catch (e: Exception) {
            text
        }
    }

    // ============ CLASSES DE DADOS ============
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniZipImage(
        @JsonProperty("coverType") val coverType: String?,
        @JsonProperty("url") val url: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniZipEpisode(
        @JsonProperty("episode") val episode: String?,
        @JsonProperty("title") val title: Map<String, String>?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("image") val image: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("rating") val rating: String?,
        @JsonProperty("airDateUtc") val airDateUtc: String?,
        @JsonProperty("finaleType") val finaleType: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniZipData(
        @JsonProperty("titles") val titles: Map<String, String>? = null,
        @JsonProperty("images") val images: List<AniZipImage>? = null,
        @JsonProperty("episodes") val episodes: Map<String, AniZipEpisode>? = null
    )

    // ============ FUNÇÃO AUXILIAR DE BUSCA ============
    
    private suspend fun Element.toSearchResponse(): AnimeSearchResponse? {
        val href = attr("href") ?: return null
        if (href.isBlank()) return null
        
        val titleElement = when {
            selectFirst("h3.animeTitle") != null -> selectFirst("h3.animeTitle")
            selectFirst(".text-block h3") != null -> selectFirst(".text-block h3")
            selectFirst(".animeTitle") != null -> selectFirst(".animeTitle")
            else -> selectFirst("h3")
        } ?: return null
        
        val rawTitle = titleElement.text().trim()
        
        val cleanTitle = rawTitle
            .replace(Regex("(?i)(dublado|legendado|todos os episódios|\\(\\d{4}\\)|\\s*-\\s*$|\\(movie\\))"), "")
            .trim()
        
        val isMovie = href.contains("/filmes/") || 
                      rawTitle.contains("filme", ignoreCase = true) ||
                      rawTitle.contains("movie", ignoreCase = true)
        
        // PRIORIDADE 1: AniZip (buscar via AniList)
        val anizipPoster = try {
            val malId = searchMALIdByName(cleanTitle)
            if (malId != null) {
                val syncMetaData = app.get("https://api.ani.zip/mappings?mal_id=$malId", timeout = 5000).text
                val aniZipData = parseAnimeData(syncMetaData)
                aniZipData?.images?.find { it.coverType.equals("Poster", ignoreCase = true) }?.url?.let { fixUrl(it) }
            } else null
        } catch (e: Exception) { null }
        
        // PRIORIDADE 2: TMDB (usar build config)
        val tmdbPoster = try {
            if (tmdbApiKey != "dummy_api_key") {
                val tmdbInfo = searchOnTMDB(cleanTitle, null, !isMovie)
                tmdbInfo?.posterUrl
            } else null
        } catch (e: Exception) { null }
        
        // PRIORIDADE 3: Site
        val sitePoster = selectFirst("img.imgAnimes, img.card-img-top, img.transitioning_src, img.owl-lazy, img[src*='animes']")?.let { img ->
            when {
                img.hasAttr("data-src") -> img.attr("data-src")
                img.hasAttr("src") -> img.attr("src")
                else -> null
            }?.takeIf { !it.contains("logo", ignoreCase = true) }
        } ?: selectFirst("img:not([src*='logo']):not([src*='Logo'])")?.attr("src")

        // Prioridade: AniZip > TMDB > Site
        val finalPoster = anizipPoster ?: tmdbPoster ?: sitePoster?.let { fixUrl(it) }

        return newAnimeSearchResponse(cleanTitle, fixUrl(href)) {
            this.posterUrl = finalPoster
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
                        
                        // Prioridade para posters de episódios
                        val anizipPoster = try {
                            val malId = searchMALIdByName(cleanTitle)
                            if (malId != null) {
                                val syncMetaData = app.get("https://api.ani.zip/mappings?mal_id=$malId", timeout = 5000).text
                                val aniZipData = parseAnimeData(syncMetaData)
                                aniZipData?.episodes?.get(epNumber.toString())?.image?.let { fixUrl(it) }
                            } else null
                        } catch (e: Exception) { null }
                        
                        val tmdbPoster = try {
                            if (tmdbApiKey != "dummy_api_key") {
                                val tmdbInfo = searchOnTMDB(cleanTitle, null, true)
                                tmdbInfo?.posterUrl
                            } else null
                        } catch (e: Exception) { null }
                        
                        val sitePoster = card.selectFirst("img.imgAnimesUltimosEps, img[src*='animes']")?.let { img ->
                            when {
                                img.hasAttr("data-src") -> img.attr("data-src")
                                img.hasAttr("src") -> img.attr("src")
                                else -> null
                            }?.takeIf { !it.contains("logo", ignoreCase = true) }
                        } ?: card.selectFirst("img:not([src*='logo'])")?.attr("src")
                        
                        val finalPoster = anizipPoster ?: tmdbPoster ?: sitePoster?.let { fixUrl(it) }

                        newAnimeSearchResponse(displayTitle, fixUrl(href)) {
                            this.posterUrl = finalPoster
                            this.type = TvType.Anime
                        }
                    }.getOrNull()
                }
            }
            else -> emptyList()
        }
        
        return newHomePageResponse(request.name, homeItems.distinctBy { it.url }, false)
    }

    // ============ FUNÇÃO SEARCH ============
    
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl$SEARCH_PATH/${URLEncoder.encode(query, "UTF-8")}"
        
        val document = app.get(searchUrl).document
        val elements = document.select("div.divCardUltimosEps article.card a")

        return elements.mapNotNull { element ->
            runCatching {
                val href = element.attr("href")
                if (href.isBlank()) return@runCatching null

                val titleElement = element.selectFirst("h3.animeTitle, .text-block h3, .animeTitle")
                val rawTitle = titleElement?.text()?.trim() ?: "Sem Título"
                
                val cleanTitle = rawTitle
                    .replace(Regex("\\s*-\\s*Todos os Episódios$"), "")
                    .replace(Regex("\\(Dublado\\)"), "")
                    .replace(Regex("\\(Legendado\\)"), "")
                    .trim()

                // Prioridade: AniZip > TMDB > Site
                val anizipPoster = try {
                    val malId = searchMALIdByName(cleanTitle)
                    if (malId != null) {
                        val syncMetaData = app.get("https://api.ani.zip/mappings?mal_id=$malId", timeout = 5000).text
                        val aniZipData = parseAnimeData(syncMetaData)
                        aniZipData?.images?.find { it.coverType.equals("Poster", ignoreCase = true) }?.url?.let { fixUrl(it) }
                    } else null
                } catch (e: Exception) { null }
                
                val tmdbPoster = try {
                    if (tmdbApiKey != "dummy_api_key") {
                        val tmdbInfo = searchOnTMDB(cleanTitle, null, true)
                        tmdbInfo?.posterUrl
                    } else null
                } catch (e: Exception) { null }
                
                val sitePoster = element.selectFirst("img.imgAnimes, img.card-img-top, img.transitioning_src")?.let { img ->
                    when {
                        img.hasAttr("data-src") -> img.attr("data-src")
                        img.hasAttr("src") -> img.attr("src")
                        else -> null
                    }
                }
                
                val finalPoster = anizipPoster ?: tmdbPoster ?: sitePoster?.let { fixUrl(it) }

                val isMovie = href.contains("/filmes/") || 
                             cleanTitle.contains("filme", ignoreCase = true) ||
                             rawTitle.contains("filme", ignoreCase = true) ||
                             rawTitle.contains("movie", ignoreCase = true)

                newAnimeSearchResponse(cleanTitle, fixUrl(href)) {
                    this.posterUrl = finalPoster
                    this.type = if (isMovie) TvType.Movie else TvType.Anime
                }
            }.getOrElse { null }
        }.take(30)
    }

    // ============ LOAD PRINCIPAL ============
    
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val titleElement = document.selectFirst("h1.quicksand400, .main_div_anime_info h1, h1") ?: 
            throw ErrorLoadingException("Não foi possível encontrar o título")
        val rawTitle = titleElement.text().trim()
        
        val year = Regex("\\((\\d{4})\\)").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = rawTitle.replace(Regex("\\(\\d{4}\\)"), "").trim()
        
        val isMovie = url.contains("/filmes/") || rawTitle.contains("Movie", ignoreCase = true)
        val type = if (isMovie) TvType.Movie else TvType.Anime

        val malId = searchMALIdByName(cleanTitle)
        val aniZipData = if (malId != null) {
            try {
                val syncMetaData = app.get("https://api.ani.zip/mappings?mal_id=$malId", timeout = 5000).text
                parseAnimeData(syncMetaData)
            } catch (e: Exception) { null }
        } else null

        // TMDB - filtrar para evitar live actions
        val tmdbInfo = searchOnTMDB(cleanTitle, year, !isMovie)

        val siteMetadata = extractSiteMetadata(document)
        
        val episodes = if (!isMovie) {
            extractEpisodesFromSite(document, cleanTitle, aniZipData, tmdbInfo)
        } else {
            emptyList()
        }

        val recommendations = extractRecommendations(document)

        val data = document.selectFirst("div#media-info, div.anime-info")
        val genres = data?.select("div:contains(Genre:), div:contains(Gênero:) > span > a")?.map { it.text() }

        return createLoadResponse(
            url = url,
            cleanTitle = cleanTitle,
            year = year,
            isMovie = isMovie,
            type = type,
            siteMetadata = siteMetadata,
            aniZipData = aniZipData,
            tmdbInfo = tmdbInfo,
            episodes = episodes,
            recommendations = recommendations,
            genres = genres
        )
    }

    private fun parseAnimeData(jsonString: String): AniZipData? {
        return try {
            val objectMapper = ObjectMapper()
            objectMapper.readValue(jsonString, AniZipData::class.java)
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun createLoadResponse(
        url: String,
        cleanTitle: String,
        year: Int?,
        isMovie: Boolean,
        type: TvType,
        siteMetadata: SiteMetadata,
        aniZipData: AniZipData?,
        tmdbInfo: TMDBInfo?,
        episodes: List<Episode>,
        recommendations: List<SearchResponse>,
        genres: List<String>?
    ): LoadResponse {
        
        // PRIORIDADE SINOPSE: TMDB > AniZip > Site
        val finalPlot = if (TRANSLATION_ENABLED) {
            val synopsisToTranslate = when {
                tmdbInfo?.overview != null -> tmdbInfo.overview
                aniZipData?.episodes?.values?.firstOrNull()?.overview != null -> 
                    aniZipData.episodes.values.firstOrNull()?.overview
                else -> siteMetadata.plot
            }
            
            synopsisToTranslate?.let { 
                if (!isProbablyPortuguese(it)) {
                    translateWithCache(it)
                } else {
                    it
                }
            }
        } else {
            tmdbInfo?.overview ?: 
            aniZipData?.episodes?.values?.firstOrNull()?.overview ?: 
            siteMetadata.plot
        }
        
        // PRIORIDADE POSTER: AniZip > TMDB > Site
        val finalPoster = aniZipData?.images?.find { it.coverType.equals("Poster", ignoreCase = true) }?.url?.let { fixUrl(it) } ?:
                         tmdbInfo?.posterUrl ?:
                         siteMetadata.poster
        
        // PRIORIDADE BACKDROP: TMDB > AniZip > Site
        val finalBackdrop = tmdbInfo?.backdropUrl ?:
                           aniZipData?.images?.find { it.coverType.equals("Fanart", ignoreCase = true) }?.url?.let { fixUrl(it) } ?:
                           siteMetadata.poster?.let { fixUrl(it) }
        
        // PRIORIDADE ANO: TMDB > Site > AniZip
        val finalYear = tmdbInfo?.year ?: 
                       year ?: 
                       siteMetadata.year ?:
                       aniZipData?.episodes?.values?.firstOrNull()?.airDateUtc?.substring(0, 4)?.toIntOrNull()

        // Juntar tags
        val finalTags = (tmdbInfo?.genres ?: emptyList()) + 
                       (genres ?: emptyList()) + 
                       (siteMetadata.tags ?: emptyList())

        // Usar título do TMDB se disponível, senão usar do site
        val finalTitle = tmdbInfo?.title ?: cleanTitle

        return if (isMovie) {
            newMovieLoadResponse(finalTitle, url, type, url) {
                this.year = finalYear
                this.plot = finalPlot
                this.tags = finalTags.distinct().take(10)
                this.posterUrl = finalPoster
                this.backgroundPosterUrl = finalBackdrop
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
                
                // Trailer apenas do TMDB
                tmdbInfo?.youtubeTrailer?.let { trailerUrl ->
                    addTrailer(trailerUrl)
                }
            }
        } else {
            newAnimeLoadResponse(finalTitle, url, type) {
                addEpisodes(DubStatus.Subbed, episodes)
                
                this.year = finalYear
                this.plot = finalPlot
                this.tags = finalTags.distinct().take(10)
                this.posterUrl = finalPoster
                this.backgroundPosterUrl = finalBackdrop
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
                
                // Trailer apenas do TMDB
                tmdbInfo?.youtubeTrailer?.let { trailerUrl ->
                    addTrailer(trailerUrl)
                }
            }
        }
    }

    private suspend fun extractEpisodesFromSite(
        document: org.jsoup.nodes.Document,
        animeTitle: String,
        aniZipData: AniZipData?,
        tmdbInfo: TMDBInfo?
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        val episodeElements = document.select("a.lEp.epT, a.lEp, .divListaEps a, [href*='/video/'], [href*='/episodio/']")
        
        episodeElements.forEachIndexed { index, element ->
            try {
                val href = element.attr("href")
                if (href.isBlank()) return@forEachIndexed
                
                val text = element.text().trim()
                if (text.isBlank()) return@forEachIndexed
                
                val episodeNumber = extractEpisodeNumber(text) ?: (index + 1)
                val seasonNumber = 1
                
                // PRIORIDADE DETALHES EPISÓDIO: TMDB > AniZip > Site
                val aniZipEpisode = aniZipData?.episodes?.get(episodeNumber.toString())
                
                val episodeName = element.selectFirst(".ep-name, .title")?.text()?.trim()
                    ?: text.substringAfterLast("-").trim()
                    ?: "Episódio $episodeNumber"
                
                // Traduzir nome do episódio se necessário
                val finalEpisodeName = if (TRANSLATION_ENABLED && !isProbablyPortuguese(episodeName)) {
                    translateWithCache(episodeName)
                } else {
                    episodeName
                }
                
                // Descrição com prioridade TMDB > AniZip
                val episodeDescription = when {
                    tmdbInfo?.overview != null && episodeNumber == 1 -> {
                        // Para primeiro episódio, usar sinopse geral do TMDB
                        if (TRANSLATION_ENABLED && !isProbablyPortuguese(tmdbInfo.overview)) {
                            translateWithCache(tmdbInfo.overview)
                        } else {
                            tmdbInfo.overview
                        }
                    }
                    aniZipEpisode?.overview != null -> {
                        if (TRANSLATION_ENABLED && !isProbablyPortuguese(aniZipEpisode.overview)) {
                            translateWithCache(aniZipEpisode.overview)
                        } else {
                            aniZipEpisode.overview
                        }
                    }
                    else -> "Nenhuma descrição disponível"
                }

                // Poster do episódio com prioridade AniZip
                val episodePoster = aniZipEpisode?.image ?: 
                                   aniZipData?.images?.firstOrNull()?.url

                episodes.add(
                    newEpisode(fixUrl(href)) {
                        this.name = finalEpisodeName
                        this.season = seasonNumber
                        this.episode = episodeNumber
                        this.description = episodeDescription
                        this.posterUrl = episodePoster
                        this.score = Score.from10(aniZipEpisode?.rating)
                        this.runTime = aniZipEpisode?.runtime ?: tmdbInfo?.duration
                        
                        aniZipEpisode?.airDateUtc?.let { dateStr ->
                            try {
                                val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
                                val date = formatter.parse(dateStr)
                                this.date = date.time
                            } catch (e: Exception) {
                                // Ignorar erro de parsing
                            }
                        }
                    }
                )
                
            } catch (e: Exception) {
                // Silenciosamente ignorar erros em episódios
            }
        }
        
        return episodes.sortedBy { it.episode }
    }

    private fun extractEpisodeNumber(text: String): Int? {
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
                return match.groupValues[1].toIntOrNull()
            }
        }
        return null
    }
    
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

    private data class SiteMetadata(
        val poster: String? = null,
        val plot: String? = null,
        val tags: List<String>? = null,
        val year: Int? = null
    )

    private fun extractSiteMetadata(document: org.jsoup.nodes.Document): SiteMetadata {
        val posterImg = document.selectFirst(".sub_animepage_img img.transitioning_src")
        val poster = when {
            posterImg?.hasAttr("src") == true -> fixUrl(posterImg.attr("src"))
            posterImg?.hasAttr("data-src") == true -> fixUrl(posterImg.attr("data-src"))
            else -> document.selectFirst("img[src*='/img/animes/']:not([src*='logo'])")
                ?.attr("src")?.let { fixUrl(it) }
        }

        val plot = document.selectFirst("div.divSinopse span.spanAnimeInfo")
            ?.text()
            ?.trim()
            ?.replace(Regex("^Sinopse:\\s*"), "")

        val tags = document.select("a.spanAnimeInfo.spanGeneros")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .takeIf { it.isNotEmpty() }?.toList()

        val year = document.selectFirst("div.animeInfo:contains(Ano:) span.spanAnimeInfo")
            ?.text()
            ?.trim()
            ?.toIntOrNull()

        return SiteMetadata(poster, plot, tags, year)
    }

    private suspend fun searchOnTMDB(query: String, year: Int?, isTv: Boolean): TMDBInfo? {
        if (tmdbApiKey == "dummy_api_key" || tmdbAccessToken == "dummy_access_token") {
            return null // Não usar fallback, apenas retornar null
        }

        return try {
            val type = if (isTv) "tv" else "movie"
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            
            // Adicionar filtro para evitar séries live action
            var searchUrl = "$tmdbBaseUrl/search/$type?query=$encodedQuery&api_key=$tmdbApiKey&language=pt-BR"
            if (year != null) searchUrl += "&year=$year"
            searchUrl += "&include_adult=false" // Evitar conteúdo adulto

            val response = app.get(searchUrl, timeout = 10_000)

            if (response.code != 200) {
                return null
            }

            val searchResult = response.parsedSafe<TMDBSearchResponse>() ?: return null
            
            // Filtrar para preferir anime em vez de live action
            val result = searchResult.results.firstOrNull() ?: return null
            
            // Verificar se é anime (TMDB tem campo para isso)
            val details = getTMDBDetailsDirect(result.id, isTv) ?: return null
            
            // Filtrar por gênero - evitar "Action & Adventure" que pode ser live action
            val isLikelyAnime = details.genres?.any { 
                it.name.contains("Animation", ignoreCase = true) || 
                it.name.contains("Anime", ignoreCase = true) ||
                it.name.contains("Fantasy", ignoreCase = true)
            } == true
            
            // Se não parece anime, pular
            if (!isLikelyAnime && isTv) {
                return null
            }

            // Buscar trailer
            val youtubeTrailer = getHighQualityTrailer(details.videos?.results)

            TMDBInfo(
                id = result.id,
                title = if (isTv) result.name else result.title,
                year = if (isTv) {
                    result.first_air_date?.substring(0, 4)?.toIntOrNull()
                } else {
                    result.release_date?.substring(0, 4)?.toIntOrNull()
                },
                posterUrl = result.poster_path?.let { "$tmdbImageUrl/w500$it" },
                backdropUrl = details.backdrop_path?.let { "$tmdbImageUrl/original$it" },
                overview = details.overview,
                genres = details.genres?.map { it.name },
                youtubeTrailer = youtubeTrailer,
                duration = details.runtime
            )
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun getTMDBDetailsDirect(id: Int, isTv: Boolean): TMDBDetailsResponse? {
        return try {
            val type = if (isTv) "tv" else "movie"
            val url = "$tmdbBaseUrl/$type/$id?append_to_response=videos&language=pt-BR"
            
            val headers = mapOf(
                "Authorization" to "Bearer $tmdbAccessToken",
                "accept" to "application/json"
            )
            
            val response = app.get(url, headers = headers, timeout = 10_000)

            if (response.code != 200) {
                return null
            }

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
        return AnimeFireExtractor.extractVideoLinks(data, mainUrl, name, callback)
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
}
