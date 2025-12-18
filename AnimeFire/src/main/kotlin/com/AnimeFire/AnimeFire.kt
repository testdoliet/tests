package com.AnimeFire

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class AnimeFire : MainAPI() {
    override var mainUrl = "https://animefire.plus"
    override var name = "AnimeFire (com AniList API)"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie, TvType.OVA)
    override val usesWebView = false

    // API GraphQL do AniList
    private val aniListApiUrl = "https://graphql.anilist.co"
    
    override val mainPage = mainPageOf(
        "trending" to "AniList: Em Alta",
        "season" to "AniList: Esta Temporada", 
        "popular" to "AniList: Populares",
        "top" to "AniList: Top 100"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return when (request.name) {
            "AniList: Em Alta" -> getAniListTrending(page)
            "AniList: Esta Temporada" -> getAniListSeason(page)
            "AniList: Populares" -> getAniListPopular(page)
            "AniList: Top 100" -> getAniListTop(page)
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
        
        return executeAniListQuery(query, "AniList: Em Alta", page)
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
        
        return executeAniListQuery(query, "AniList: Esta Temporada", page)
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
        
        return executeAniListQuery(query, "AniList: Populares", page)
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
        
        return executeAniListQuery(query, "AniList: Top 100", page, showRank = true)
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
                
                val items = mediaList.mapIndexed { index, media ->
                    val title = media.title?.userPreferred ?: 
                               media.title?.romaji ?: 
                               media.title?.english ?: 
                               "Sem Título"
                    
                    // Calcular ranking baseado na página
                    val rank = index + 1 + ((page - 1) * 20)
                    val finalTitle = if (showRank) {
                        "#$rank $title"
                    } else {
                        title
                    }
                    
                    val specialUrl = "anilist:${media.id}:$title"
                    
                    newAnimeSearchResponse(finalTitle, specialUrl) {
                        this.posterUrl = media.coverImage?.extraLarge ?: media.coverImage?.large
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
        val title = parts.getOrNull(2) ?: "Anime do AniList"
        
        if (aniListId == null) {
            return newAnimeLoadResponse(title, url, TvType.Anime) {
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
                    val finalTitle = media.title?.userPreferred ?: 
                                    media.title?.romaji ?: 
                                    media.title?.english ?: 
                                    title
                    
                    val description = media.description?.replace(Regex("<[^>]*>"), "") ?: "Sem descrição"
                    
                    // Preparar trailer se disponível
                    val trailerId = media.trailer?.id
                    val trailerSite = media.trailer?.site
                    val trailerUrl = if (trailerSite == "youtube" && trailerId != null) {
                        "https://www.youtube.com/watch?v=$trailerId"
                    } else {
                        null
                    }
                    
                    return newAnimeLoadResponse(finalTitle, url, TvType.Anime) {
                        this.plot = description
                        this.posterUrl = media.coverImage?.extraLarge ?: media.coverImage?.large
                        this.backgroundPosterUrl = media.bannerImage
                        this.year = media.startDate?.year
                        this.tags = media.genres
                        
                        // Adicionar trailer através do método direto
                        // Nota: addTrailer não está disponível diretamente aqui
                        // Vamos adicionar como uma informação extra se possível
                    }.apply {
                        // Tentar adicionar trailer se disponível
                        // (algumas versões do Cloudstream podem ter métodos diferentes)
                        if (trailerUrl != null) {
                            println("🎬 Trailer disponível: $trailerUrl")
                            // Podemos tentar adicionar de outra forma se necessário
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("❌ Erro ao carregar do AniList: ${e.message}")
        }
        
        return newAnimeLoadResponse(title, url, TvType.Anime) {
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
}
