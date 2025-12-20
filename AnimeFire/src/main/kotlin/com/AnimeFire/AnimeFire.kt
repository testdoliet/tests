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
        private const val tmdbImageUrl = "https://image.tmdb.org/t/p"
        private const val ANILIST_API = "https://graphql.anilist.co"
    }

    // ============ TMDB COM BuildConfig ============
    private val tmdbApiKey = BuildConfig.TMDB_API_KEY
    private val tmdbAccessToken = BuildConfig.TMDB_ACCESS_TOKEN
    private val tmdbBaseUrl = "https://api.themoviedb.org/3"

    // ============ PÁGINA PRINCIPAL ============
    override val mainPage = mainPageOf(
        "$mainUrl" to "🔥 Lançamentos",
        "$mainUrl" to "🔥 Destaques da Semana", 
        "$mainUrl" to "🔥 Últimos Episódios"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        
        val homeItems = when (request.name) {
            "🔥 Lançamentos" -> 
                document.select(".owl-carousel-home .divArticleLancamentos a.item")
                    .mapNotNull { element -> element.toSearchResponse() }
            
            "🔥 Destaques da Semana" -> 
                document.select(".owl-carousel-semana .divArticleLancamentos a.item")
                    .mapNotNull { element -> element.toSearchResponse() }
            
            "🔥 Últimos Episódios" -> 
                document.select(".divCardUltimosEpsHome").mapNotNull { card ->
                    card.toEpisodeSearchResponse()
                }
            
            else -> emptyList()
        }
        
        return newHomePageResponse(request.name, homeItems.distinctBy { it.url }, false)
    }

    // ============ FUNÇÕES AUXILIARES PARA PÁGINA PRINCIPAL ============
    private fun Element.toSearchResponse(): SearchResponse? {
        val href = attr("href") ?: return null
        if (href.isBlank()) return null
        
        val titleElement = selectFirst("h3.animeTitle, .text-block h3, .animeTitle, h3") ?: return null
        val rawTitle = titleElement.text().trim()
        
        val cleanTitle = rawTitle
            .replace(Regex("(?i)(dublado|legendado|todos os episódios|\\(\\d{4}\\)|\\s*-\\s*$|\\(movie\\))"), "")
            .trim()
        
        val isMovie = href.contains("/filmes/") || 
                      rawTitle.contains("filme", ignoreCase = true) ||
                      rawTitle.contains("movie", ignoreCase = true)
        
        val sitePoster = selectFirst("img.imgAnimes, img.card-img-top, img.transitioning_src, img.owl-lazy, img[src*='animes']")?.let { img ->
            when {
                img.hasAttr("data-src") -> img.attr("data-src")
                img.hasAttr("src") -> img.attr("src")
                else -> null
            }?.takeIf { !it.contains("logo", ignoreCase = true) }
        }

        return newAnimeSearchResponse(cleanTitle, fixUrl(href)) {
            this.posterUrl = sitePoster?.let { fixUrl(it) }
            this.type = if (isMovie) TvType.Movie else TvType.Anime
        }
    }

    private fun Element.toEpisodeSearchResponse(): SearchResponse? {
        val link = selectFirst("article.card a") ?: return null
        val href = link.attr("href") ?: return null
        
        val titleElement = selectFirst("h3.animeTitle") ?: return null
        val rawTitle = titleElement.text().trim()
        
        val epNumber = selectFirst(".numEp")?.text()?.toIntOrNull() ?: 1
        val cleanTitle = rawTitle.replace(Regex("(?i)(dublado|legendado|todos os episódios|\\(\\d{4}\\))$"), "").trim()
        val displayTitle = "${cleanTitle} - Episódio $epNumber"
        
        val sitePoster = selectFirst("img.imgAnimesUltimosEps, img[src*='animes']")?.let { img ->
            when {
                img.hasAttr("data-src") -> img.attr("data-src")
                img.hasAttr("src") -> img.attr("src")
                else -> null
            }?.takeIf { !it.contains("logo", ignoreCase = true) }
        } ?: selectFirst("img:not([src*='logo'])")?.attr("src")
        
        return newAnimeSearchResponse(displayTitle, fixUrl(href)) {
            this.posterUrl = sitePoster?.let { fixUrl(it) }
            this.type = TvType.Anime
        }
    }

    // ============ SEARCH ============
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl$SEARCH_PATH/${URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document

        return document.select("div.divCardUltimosEps article.card a").mapNotNull { element ->
            try {
                val href = element.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val titleElement = element.selectFirst("h3.animeTitle, .text-block h3, .animeTitle")
                val rawTitle = titleElement?.text()?.trim() ?: "Sem Título"
                
                val cleanTitle = rawTitle
                    .replace(Regex("\\s*-\\s*Todos os Episódios$"), "")
                    .replace(Regex("\\(Dublado\\)"), "")
                    .replace(Regex("\\(Legendado\\)"), "")
                    .trim()

                val imgElement = element.selectFirst("img.imgAnimes, img.card-img-top, img.transitioning_src")
                val posterUrl = when {
                    imgElement?.hasAttr("data-src") == true -> imgElement.attr("data-src")
                    imgElement?.hasAttr("src") == true -> imgElement.attr("src")
                    else -> null
                }

                val isMovie = href.contains("/filmes/") || 
                             cleanTitle.contains("filme", ignoreCase = true) ||
                             rawTitle.contains("filme", ignoreCase = true) ||
                             rawTitle.contains("movie", ignoreCase = true)

                newAnimeSearchResponse(cleanTitle, fixUrl(href)) {
                    this.posterUrl = posterUrl?.let { fixUrl(it) }
                    this.type = if (isMovie) TvType.Movie else TvType.Anime
                }
            } catch (e: Exception) {
                null
            }
        }.take(30)
    }

    // ============ LOAD PRINCIPAL (ESTILO SUPERFLIX) ============
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // 1. Pegar título básico do site para busca no TMDB
        val titleElement = document.selectFirst("h1.quicksand400, .main_div_anime_info h1, h1")
        val title = titleElement?.text() ?: throw ErrorLoadingException("Título não encontrado")
        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
        
        val isMovie = url.contains("/filmes/") || title.contains("Movie", ignoreCase = true)

        println("🔍 AnimeFire.load() - Título: $cleanTitle | Ano: $year | Tipo: ${if (isMovie) "Filme" else "Anime"}")

        // 2. BUSCAR TMDB (METADADOS PRINCIPAIS)
        val tmdbInfo = searchOnTMDB(cleanTitle, year, !isMovie)
        
        if (tmdbInfo == null) {
            println("⚠️ TMDB não encontrado para: $cleanTitle")
        } else {
            println("✅ TMDB encontrado: ${tmdbInfo.title} (${tmdbInfo.year})")
            println("📖 Sinopse TMDB: ${tmdbInfo.overview?.take(50)}...")
            println("🏷️ Gêneros TMDB: ${tmdbInfo.genres?.joinToString()}")
            println("🎬 Trailer TMDB: ${tmdbInfo.youtubeTrailer?.take(30)}...")
        }

        // 3. BUSCAR ANIZIP (APENAS avaliação e elenco)
        val aniZipData = if (!isMovie) {
            val malId = searchMALIdByName(cleanTitle)
            if (malId != null) {
                try {
                    val syncMetaData = app.get("https://api.ani.zip/mappings?mal_id=$malId", timeout = 10000).text
                    val data = parseAnimeData(syncMetaData)
                    println("✅ AniZip carregado para avaliação/elenco")
                    data
                } catch (e: Exception) {
                    println("❌ Erro AniZip: ${e.message}")
                    null
                }
            } else {
                null
            }
        } else {
            null
        }

        // 4. Extrair episódios do site + dados AniZip
        val episodes = if (!isMovie) {
            extractEpisodesFromSite(document, aniZipData)
        } else {
            emptyList()
        }

        // 5. Recomendações do site
        val recommendations = extractRecommendations(document)

        // 6. CRIAR RESPOSTA (ESTILO SUPERFLIX)
        return if (tmdbInfo != null) {
            // USANDO DADOS DO TMDB (como SuperFlix)
            createResponseWithTMDB(tmdbInfo, url, isMovie, episodes, recommendations, aniZipData)
        } else {
            // Fallback: usar dados do site
            createResponseFromSite(document, url, cleanTitle, year, isMovie, episodes, recommendations, aniZipData)
        }
    }

    // ============ FUNÇÃO PRINCIPAL: CRIAR RESPOSTA COM TMDB ============
    private suspend fun createResponseWithTMDB(
        tmdbInfo: TMDBInfo,
        url: String,
        isMovie: Boolean,
        episodes: List<Episode>,
        recommendations: List<SearchResponse>,
        aniZipData: AniZipData?
    ): LoadResponse {
        
        println("🏗️ Criando resposta COM dados TMDB (estilo SuperFlix)")
        
        if (isMovie) {
            return newMovieLoadResponse(tmdbInfo.title ?: "", url, TvType.Movie, url) {
                // TUDO DO TMDB
                this.posterUrl = tmdbInfo.posterUrl
                this.backgroundPosterUrl = tmdbInfo.backdropUrl
                this.year = tmdbInfo.year
                this.plot = tmdbInfo.overview
                this.tags = tmdbInfo.genres
                this.duration = tmdbInfo.duration
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
                
                // Trailer do TMDB
                tmdbInfo.youtubeTrailer?.let { trailerUrl ->
                    addTrailer(trailerUrl)
                }
                
                // ELENCO do AniZip (se disponível)
                aniZipData?.images?.filter { it.coverType == "Actor" }?.take(10)?.forEach { actor ->
                    val actorName = extractActorName(actor.url)
                    addActors(listOf(Actor(actorName)))
                }
            }
        } else {
            return newAnimeLoadResponse(tmdbInfo.title ?: "", url, TvType.Anime) {
                // TUDO DO TMDB
                this.posterUrl = tmdbInfo.posterUrl
                this.backgroundPosterUrl = tmdbInfo.backdropUrl
                this.year = tmdbInfo.year
                this.plot = tmdbInfo.overview
                this.tags = tmdbInfo.genres
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
                
                // Trailer do TMDB
                tmdbInfo.youtubeTrailer?.let { trailerUrl ->
                    addTrailer(trailerUrl)
                }
                
                // ELENCO do AniZip (se disponível)
                aniZipData?.images?.filter { it.coverType == "Actor" }?.take(10)?.forEach { actor ->
                    val actorName = extractActorName(actor.url)
                    addActors(listOf(Actor(actorName)))
                }
                
                // Episódios (do site + dados AniZip)
                addEpisodes(DubStatus.Subbed, episodes)
                
                // Informações extras do AniZip (avaliação, etc.)
                aniZipData?.let {
                    println("⭐ AniZip disponível para informações extras")
                }
            }
        }
    }

    // ============ FUNÇÃO FALLBACK: SEM TMDB ============
    private suspend fun createResponseFromSite(
        document: org.jsoup.nodes.Document,
        url: String,
        title: String,
        year: Int?,
        isMovie: Boolean,
        episodes: List<Episode>,
        recommendations: List<SearchResponse>,
        aniZipData: AniZipData?
    ): LoadResponse {
        
        println("⚠️ Criando resposta APENAS com dados do site (fallback)")
        
        val siteMetadata = extractSiteMetadata(document)
        val data = document.selectFirst("div#media-info, div.anime-info")
        val genres = data?.select("div:contains(Genre:), div:contains(Gênero:) > span > a")?.map { it.text() }

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = siteMetadata.poster
                this.year = year ?: siteMetadata.year
                this.plot = siteMetadata.plot
                this.tags = (genres ?: emptyList()) + (siteMetadata.tags ?: emptyList())
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
                
                // ELENCO do AniZip
                aniZipData?.images?.filter { it.coverType == "Actor" }?.take(10)?.forEach { actor ->
                    val actorName = extractActorName(actor.url)
                    addActors(listOf(Actor(actorName)))
                }
            }
        } else {
            return newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = siteMetadata.poster
                this.year = year ?: siteMetadata.year
                this.plot = siteMetadata.plot
                this.tags = (genres ?: emptyList()) + (siteMetadata.tags ?: emptyList())
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
                
                // ELENCO do AniZip
                aniZipData?.images?.filter { it.coverType == "Actor" }?.take(10)?.forEach { actor ->
                    val actorName = extractActorName(actor.url)
                    addActors(listOf(Actor(actorName)))
                }
                
                addEpisodes(DubStatus.Subbed, episodes)
            }
        }
    }

    // ============ FUNÇÕES TMDB (EXATAMENTE COMO SUPERFLIX) ============
    private suspend fun searchOnTMDB(query: String, year: Int?, isTv: Boolean): TMDBInfo? {
        // Verificar se as chaves estão configuradas
        if (tmdbApiKey == "dummy_api_key" || tmdbAccessToken == "dummy_access_token") {
            println("⚠️ TMDB: BuildConfig não configurado")
            return null
        }

        return try {
            val type = if (isTv) "tv" else "movie"
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            
            // URL de busca DIRETA com API Key (igual SuperFlix)
            var searchUrl = "$tmdbBaseUrl/search/$type?query=$encodedQuery&api_key=$tmdbApiKey&language=pt-BR"
            if (year != null) searchUrl += "&year=$year"
            
            println("🔗 TMDB buscando: $searchUrl")

            val response = app.get(searchUrl, timeout = 10_000)

            if (response.code != 200) {
                println("❌ TMDB erro: ${response.code}")
                return null
            }

            val searchResult = response.parsedSafe<TMDBSearchResponse>() ?: return null
            val result = searchResult.results.firstOrNull() ?: return null

            // Buscar detalhes completos
            val details = getTMDBDetailsDirect(result.id, isTv) ?: return null

            // Buscar trailer
            val youtubeTrailer = getHighQualityTrailer(details.videos?.results)

            // Extrair atores do TMDB também (opcional)
            val tmdbActors = details.credits?.cast?.take(10)?.mapNotNull { actor ->
                if (actor.name.isNotBlank()) {
                    Actor(
                        name = actor.name,
                        image = actor.profile_path?.let { "$tmdbImageUrl/w185$it" }
                    )
                } else null
            }

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
                duration = details.runtime,
                actors = tmdbActors
            )
        } catch (e: Exception) {
            println("❌ TMDB ERRO: ${e.message}")
            null
        }
    }

    private suspend fun getTMDBDetailsDirect(id: Int, isTv: Boolean): TMDBDetailsResponse? {
        return try {
            val type = if (isTv) "tv" else "movie"
            val url = "$tmdbBaseUrl/$type/$id?append_to_response=credits,videos&language=pt-BR"
            
            val headers = mapOf(
                "Authorization" to "Bearer $tmdbAccessToken",
                "accept" to "application/json"
            )
            
            val response = app.get(url, headers = headers, timeout = 10_000)
            if (response.code != 200) return null
            
            response.parsedSafe<TMDBDetailsResponse>()
        } catch (e: Exception) {
            null
        }
    }

    // ============ FUNÇÕES ANIZIP ============
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
                            idMal
                        }
                    }
                }
            """.trimIndent()
            
            val response = app.post(
                ANILIST_API,
                data = mapOf("query" to query),
                headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
                timeout = 10_000
            )
            
            if (response.code == 200) {
                val json = JsonParser.parseString(response.text).asJsonObject
                val data = json.getAsJsonObject("data")
                val page = data?.getAsJsonObject("Page")
                val mediaArray = page?.getAsJsonArray("media")
                
                mediaArray?.firstOrNull()?.asJsonObject?.get("idMal")?.asInt
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    // ============ FUNÇÕES AUXILIARES ============
    private suspend fun extractEpisodesFromSite(
        document: org.jsoup.nodes.Document,
        aniZipData: AniZipData?
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        val episodeElements = document.select("a.lEp.epT, a.lEp, .divListaEps a, [href*='/video/'], [href*='/episodio/']")
        
        episodeElements.forEachIndexed { index, element ->
            try {
                val href = element.attr("href").takeIf { it.isNotBlank() } ?: return@forEachIndexed
                val text = element.text().trim().takeIf { it.isNotBlank() } ?: return@forEachIndexed
                
                val episodeNumber = extractEpisodeNumber(text) ?: (index + 1)
                val seasonNumber = 1
                
                // Usar dados do AniZip se disponível
                val aniZipEpisode = aniZipData?.episodes?.get(episodeNumber.toString())
                
                val episodeName = element.selectFirst(".ep-name, .title")?.text()?.trim()
                    ?: text.substringAfterLast("-").trim()
                    ?: "Episódio $episodeNumber"
                
                val episodeDescription = aniZipEpisode?.overview ?: "Nenhuma descrição disponível"

                episodes.add(
                    newEpisode(fixUrl(href)) {
                        this.name = episodeName
                        this.season = seasonNumber
                        this.episode = episodeNumber
                        this.description = episodeDescription
                        this.posterUrl = aniZipEpisode?.image ?: aniZipData?.images?.firstOrNull()?.url
                        
                        // AVALIAÇÃO do AniZip
                        aniZipEpisode?.rating?.let { ratingStr ->
                            val rating = ratingStr.toDoubleOrNull()
                            rating?.let { this.score = Score.from10(it) }
                        }
                        
                        this.runTime = aniZipEpisode?.runtime
                        
                        // DATA DO PRÓXIMO EPISÓDIO do AniZip
                        aniZipEpisode?.airDateUtc?.let { dateStr ->
                            try {
                                val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
                                val date = formatter.parse(dateStr)
                                this.date = date.time
                                println("📅 Próximo episódio: ${formatter.format(date)}")
                            } catch (e: Exception) {}
                        }
                    }
                )
                
            } catch (e: Exception) {}
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
            if (match != null) return match.groupValues[1].toIntOrNull()
        }
        return null
    }

    private suspend fun extractRecommendations(document: org.jsoup.nodes.Document): List<SearchResponse> {
        return document.select(".owl-carousel-anime .divArticleLancamentos a.item")
            .mapNotNull { element -> element.toSearchResponse() }
    }

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

    private fun extractActorName(url: String?): String {
        return url?.substringAfterLast("/")
            ?.replace("-", " ")
            ?.replace("_", " ")
            ?.split(" ")
            ?.joinToString(" ") { it.capitalize() }
            ?: "Ator"
    }

    private fun parseAnimeData(jsonString: String): AniZipData? {
        return try {
            val objectMapper = ObjectMapper()
            objectMapper.readValue(jsonString, AniZipData::class.java)
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return false
    }

    // ============ CLASSES DE DADOS ============
    private data class SiteMetadata(
        val poster: String? = null,
        val plot: String? = null,
        val tags: List<String>? = null,
        val year: Int? = null
    )

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
        @JsonProperty("credits") val credits: TMDBCredits?,
        @JsonProperty("videos") val videos: TMDBVideos?
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBGenre(
        @JsonProperty("name") val name: String
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBCredits(
        @JsonProperty("cast") val cast: List<TMDBCast>
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBCast(
        @JsonProperty("name") val name: String,
        @JsonProperty("character") val character: String?,
        @JsonProperty("profile_path") val profile_path: String?
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
        val duration: Int?,
        val actors: List<Actor>? = null
    )
}
