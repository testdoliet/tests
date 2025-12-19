package com.AnimeFire

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class AnimeFire : MainAPI() {
    override var mainUrl = "https://animefire.plus"
    override var name = "𝙰𝙽𝙸𝙼𝙴𝙵𝙸𝚁𝙴"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie, TvType.OVA)
    override val usesWebView = false

    // API GraphQL do AniList
    private val aniListApiUrl = "https://graphql.anilist.co"
    
    // Configurações do TMDB - dentro do companion object
    companion object {
        private val tmdbApiKey = BuildConfig.TMDB_API_KEY
        private val tmdbAccessToken = BuildConfig.TMDB_ACCESS_TOKEN
        private const val tmdbBaseUrl = "https://api.themoviedb.org/3"
        private const val tmdbImageUrl = "https://image.tmdb.org/t/p"
    }
    
    override val mainPage = mainPageOf(
        "trending" to "Em Alta",
        "season" to "Populares Nessa Temporada", 
        "popular" to "Sempre Populares",
        "top" to "Top 100",
        "upcoming" to "Na Próxima Temporada"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return when (request.name) {
            "Em Alta" -> getAniListTrending(page)
            "Populares Nessa Temporada" -> getAniListSeason(page)
            "Sempre Populares" -> getAniListPopular(page)
            "Top 100" -> getAniListTop(page)
            "Na Próxima Temporada" -> getAniListUpcoming(page)
            else -> newHomePageResponse(request.name, emptyList(), false)
        }
    }

    private suspend fun getAniListTrending(page: Int): HomePageResponse {
        println("🌐 [ANILIST] Buscando Trending...")
        
        val query = """
            query {
                Page(page: $page, perPage: 20) {
                    media(sort: TRENDING_DESC, type: ANIME) {
                        id
                        title {
                            romaji
                            english
                            native
                            userPreferred
                        }
                        coverImage {
                            large
                            extraLarge
                        }
                        format
                        status
                        episodes
                        averageScore
                        seasonYear
                    }
                }
            }
        """.trimIndent()
        
        return executeAniListQuery(query, "Em Alta", page)
    }

    private suspend fun getAniListSeason(page: Int): HomePageResponse {
        println("🌐 [ANILIST] Buscando Esta Temporada...")
        
        val query = """
            query {
                Page(page: $page, perPage: 20) {
                    media(season: WINTER, seasonYear: 2025, type: ANIME, sort: POPULARITY_DESC) {
                        id
                        title {
                            romaji
                            english
                            native
                            userPreferred
                        }
                        coverImage {
                            large
                            extraLarge
                        }
                        format
                        status
                        episodes
                        averageScore
                    }
                }
            }
        """.trimIndent()
        
        return executeAniListQuery(query, "Populares Nessa Temporada", page)
    }

    private suspend fun getAniListPopular(page: Int): HomePageResponse {
        println("🌐 [ANILIST] Buscando Populares...")
        
        val query = """
            query {
                Page(page: $page, perPage: 20) {
                    media(sort: POPULARITY_DESC, type: ANIME) {
                        id
                        title {
                            romaji
                            english
                            native
                            userPreferred
                        }
                        coverImage {
                            large
                            extraLarge
                        }
                        format
                        status
                        episodes
                        averageScore
                        seasonYear
                    }
                }
            }
        """.trimIndent()
        
        return executeAniListQuery(query, "Sempre Populares", page)
    }

    private suspend fun getAniListTop(page: Int): HomePageResponse {
        println("🌐 [ANILIST] Buscando Top 100...")
        
        val query = """
            query {
                Page(page: $page, perPage: 20) {
                    media(sort: SCORE_DESC, type: ANIME) {
                        id
                        title {
                            romaji
                            english
                            native
                            userPreferred
                        }
                        coverImage {
                            large
                            extraLarge
                        }
                        format
                        status
                        episodes
                        averageScore
                        seasonYear
                    }
                }
            }
        """.trimIndent()
        
        return executeAniListQuery(query, "Top 100", page, showRank = true)
    }

    private suspend fun getAniListUpcoming(page: Int): HomePageResponse {
        println("🌐 [ANILIST] Buscando Próxima Temporada...")
        
        val query = """
            query {
                Page(page: $page, perPage: 20) {
                    media(season: SPRING, seasonYear: 2025, type: ANIME, sort: POPULARITY_DESC) {
                        id
                        title {
                            romaji
                            english
                            native
                            userPreferred
                        }
                        coverImage {
                            large
                            extraLarge
                        }
                        format
                        status
                        episodes
                        averageScore
                    }
                }
            }
        """.trimIndent()
        
        return executeAniListQuery(query, "Na Próxima Temporada", page)
    }

    private suspend fun executeAniListQuery(
        query: String, 
        pageName: String,
        page: Int,
        showRank: Boolean = false
    ): HomePageResponse {
        return try {
            println("📡 [ANILIST] Enviando query GraphQL para página $page...")
            
            val response = app.post(
                aniListApiUrl,
                data = mapOf("query" to query),
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json"
                ),
                timeout = 10_000
            )
            
            println("✅ [ANILIST] Resposta recebida: ${response.code}")
            
            if (response.code == 200) {
                val aniListResponse = response.parsedSafe<AniListApiResponse>()
                val mediaList = aniListResponse?.data?.Page?.media ?: emptyList()
                
                // Processar em paralelo para buscar dados do TMDB
                val items = mediaList.mapIndexed { index, media ->
                    val aniListTitle = media.title?.userPreferred ?: 
                                      media.title?.romaji ?: 
                                      media.title?.english ?: 
                                      "Sem Título"
                    
                    // Calcular ranking baseado na página
                    val rank = index + 1 + ((page - 1) * 20)
                    
                    // Buscar dados do TMDB em português para este anime
                    val tmdbData = getTMDBInfoForAnime(aniListTitle, media.seasonYear, true)
                    
                    // Usar título do TMDB se disponível, caso contrário usar do AniList
                    val finalTitle = if (tmdbData?.title != null) {
                        if (showRank) "#$rank ${tmdbData.title}" else tmdbData.title
                    } else {
                        if (showRank) "#$rank $aniListTitle" else aniListTitle
                    }
                    
                    // Usar poster do TMDB se disponível, caso contrário usar do AniList
                    val finalPoster = tmdbData?.posterUrl ?: 
                                     (media.coverImage?.extraLarge ?: media.coverImage?.large)
                    
                    val specialUrl = "anilist:${media.id}:$aniListTitle"
                    
                    newAnimeSearchResponse(finalTitle, specialUrl) {
                        this.posterUrl = finalPoster
                        this.type = TvType.Anime
                    }
                }
                
                println("✅ [ANILIST] ${items.size} itens encontrados para $pageName")
                newHomePageResponse(pageName, items, hasNext = mediaList.isNotEmpty())
            } else {
                println("❌ [ANILIST] Erro na API: ${response.code}")
                newHomePageResponse(pageName, emptyList(), false)
            }
        } catch (e: Exception) {
            println("❌ [ANILIST] Exception: ${e.message}")
            e.printStackTrace()
            newHomePageResponse(pageName, emptyList(), false)
        }
    }

    // ============ FUNÇÕES DO TMDB ============
    
    private suspend fun getTMDBInfoForAnime(
        query: String, 
        year: Int?, 
        isTv: Boolean
    ): TMDBInfo? {
        // Verificar se as chaves estão configuradas
        if (tmdbApiKey == "dummy_api_key" || tmdbAccessToken == "dummy_access_token") {
            println("⚠️ [TMDB] Chaves BuildConfig não configuradas")
            return null
        }

        return try {
            val type = if (isTv) "tv" else "movie"
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            
            // URL de busca com filtro para ANIME e em português
            var searchUrl = "$tmdbBaseUrl/search/$type?query=$encodedQuery&api_key=$tmdbApiKey&language=pt-BR&include_adult=false"
            if (year != null) searchUrl += "&year=$year"
            
            println("🔗 [TMDB] Buscando: \"$query\" em português...")

            val response = app.get(searchUrl, timeout = 10_000)
            println("📡 [TMDB] Status: ${response.code}")

            if (response.code != 200) {
                println("❌ [TMDB] Erro na busca")
                return null
            }

            val searchResult = response.parsedSafe<TMDBSearchResponse>() ?: return null
            println("✅ [TMDB] ${searchResult.results.size} resultados encontrados")

            // Filtrar para pegar apenas conteúdo relevante (anime)
            val result = searchResult.results.firstOrNull()
            if (result == null) {
                println("⚠️ [TMDB] Nenhum resultado encontrado para: $query")
                return null
            }

            // Buscar detalhes completos em português
            val details = getTMDBDetailsDirect(result.id, isTv) ?: return null

            // Verificar se é anime (geralmente tem gênero de animação ou é da categoria anime)
            val isAnime = details.genres?.any { 
                it.name.equals("Animação", ignoreCase = true) || 
                it.name.equals("Anime", ignoreCase = true) ||
                it.name.equals("Animation", ignoreCase = true)
            } ?: false

            if (!isAnime && isTv) {
                println("⚠️ [TMDB] Resultado não é anime, ignorando: $query")
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
            println("❌ [TMDB] ERRO: ${e.message}")
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
                println("❌ [TMDB] Erro detalhes: ${response.code}")
                return null
            }

            response.parsedSafe<TMDBDetailsResponse>()
        } catch (e: Exception) {
            println("❌ [TMDB] ERRO detalhes: ${e.message}")
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

    override suspend fun load(url: String): LoadResponse {
        println("\n🚀 AnimeFire.load() para URL: $url")
        
        if (url.startsWith("anilist:")) {
            return loadAniListContent(url)
        }
        
        return newAnimeLoadResponse("Conteúdo do AniList", url, TvType.Anime) {
            this.plot = "Conteúdo carregado do AniList. Para assistir, busque este anime diretamente no AnimeFire."
        }
    }

    private suspend fun loadAniListContent(url: String): LoadResponse {
        println("🌐 Carregando conteúdo do AniList: $url")
        
        // Extrair ID do AniList da URL
        val parts = url.split(":")
        val aniListId = parts.getOrNull(1)?.toIntOrNull()
        val titleFromUrl = parts.getOrNull(2) ?: "Anime do AniList"
        
        if (aniListId == null) {
            return newAnimeLoadResponse(titleFromUrl, url, TvType.Anime) {
                this.plot = "ID do AniList não encontrado na URL"
            }
        }
        
        val query = """
            query {
                Media(id: $aniListId, type: ANIME) {
                    id
                    title {
                        romaji
                        english
                        native
                        userPreferred
                    }
                    description
                    format
                    status
                    startDate {
                        year
                        month
                        day
                    }
                    episodes
                    duration
                    coverImage {
                        extraLarge
                        large
                        color
                    }
                    bannerImage
                    genres
                    averageScore
                    siteUrl
                    trailer {
                        id
                        site
                        thumbnail
                    }
                }
            }
        """.trimIndent()
        
        try {
            val response = app.post(
                aniListApiUrl,
                data = mapOf("query" to query),
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json"
                ),
                timeout = 10_000
            )
            
            if (response.code == 200) {
                val aniListResponse = response.parsedSafe<AniListMediaResponse>()
                val media = aniListResponse?.data?.Media
                
                if (media != null) {
                    val aniListTitle = media.title?.userPreferred ?: 
                                      media.title?.romaji ?: 
                                      media.title?.english ?: 
                                      titleFromUrl
                    
                    // Buscar dados do TMDB em português
                    val tmdbData = getTMDBInfoForAnime(aniListTitle, media.startDate?.year, true)
                    
                    // Usar título do TMDB se disponível, caso contrário usar do AniList
                    val finalTitle = tmdbData?.title ?: aniListTitle
                    
                    val description = media.description?.replace(Regex("<[^>]*>"), "") ?: "Sem descrição"
                    
                    // Usar poster do TMDB se disponível, caso contrário usar do AniList
                    val finalPoster = tmdbData?.posterUrl ?: 
                                     (media.coverImage?.extraLarge ?: media.coverImage?.large)
                    val finalBackdrop = tmdbData?.backdropUrl ?: media.bannerImage
                    
                    // Usar sinopse do TMDB se disponível (geralmente em português)
                    val finalDescription = tmdbData?.overview ?: description
                    
                    // Usar ano do TMDB se disponível
                    val finalYear = tmdbData?.year ?: media.startDate?.year
                    
                    // Usar gêneros do TMDB se disponível
                    val finalTags = tmdbData?.genres ?: media.genres
                    
                    // Usar trailer do TMDB se disponível
                    val trailerUrl = tmdbData?.youtubeTrailer
                    
                    return newAnimeLoadResponse(finalTitle, url, TvType.Anime) {
                        this.plot = finalDescription
                        this.posterUrl = finalPoster
                        this.backgroundPosterUrl = finalBackdrop
                        this.year = finalYear
                        this.tags = finalTags
                        
                        if (trailerUrl != null) {
                            println("🎬 Trailer disponível: $trailerUrl")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("❌ Erro ao carregar do AniList: ${e.message}")
        }
        
        return newAnimeLoadResponse(titleFromUrl, url, TvType.Anime) {
            this.plot = "Não foi possível carregar detalhes deste anime."
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Por enquanto, retornar lista vazia para simplificar
        return emptyList()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("⚠️ Extração de links para AniList não implementada ainda")
        return false
    }

    // ============ CLASSES DE DADOS ============
    
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListApiResponse(
        @JsonProperty("data") val data: AniListData? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListMediaResponse(
        @JsonProperty("data") val data: AniListMediaData? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListData(
        @JsonProperty("Page") val Page: AniListPage? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListMediaData(
        @JsonProperty("Media") val Media: AniListMedia? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListPage(
        @JsonProperty("media") val media: List<AniListMedia>? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListMedia(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: AniListTitle? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("format") val format: String? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("episodes") val episodes: Int? = null,
        @JsonProperty("duration") val duration: Int? = null,
        @JsonProperty("averageScore") val averageScore: Int? = null,
        @JsonProperty("seasonYear") val seasonYear: Int? = null,
        @JsonProperty("coverImage") val coverImage: AniListCoverImage? = null,
        @JsonProperty("bannerImage") val bannerImage: String? = null,
        @JsonProperty("genres") val genres: List<String>? = null,
        @JsonProperty("siteUrl") val siteUrl: String? = null,
        @JsonProperty("startDate") val startDate: AniListDate? = null,
        @JsonProperty("trailer") val trailer: AniListTrailer? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListTitle(
        @JsonProperty("romaji") val romaji: String? = null,
        @JsonProperty("english") val english: String? = null,
        @JsonProperty("native") val native: String? = null,
        @JsonProperty("userPreferred") val userPreferred: String? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListCoverImage(
        @JsonProperty("large") val large: String? = null,
        @JsonProperty("extraLarge") val extraLarge: String? = null,
        @JsonProperty("color") val color: String? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListDate(
        @JsonProperty("year") val year: Int? = null,
        @JsonProperty("month") val month: Int? = null,
        @JsonProperty("day") val day: Int? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListTrailer(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("site") val site: String? = null,
        @JsonProperty("thumbnail") val thumbnail: String? = null
    )

    // Classes TMDB
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
