package com.AnimeFire

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnimeFire : MainAPI() {
    // ATUALIZADO: Mudado para animefire.io
    override var mainUrl = "https://animefire.io"
    override var name = "ANIMEFIRE"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie, TvType.OVA)
    override val usesWebView = false

    // API GraphQL do AniList
    private val aniListApiUrl = "https://graphql.anilist.co"
    
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

    // ============ FUNÇÕES ANILIST SIMPLIFICADAS ============
    
    private suspend fun getAniListTrending(page: Int): HomePageResponse {
        val query = """
            query {
                Page(page: $page, perPage: 20) {
                    media(sort: TRENDING_DESC, type: ANIME, format_in: [TV, TV_SHORT, OVA, MOVIE, SPECIAL, ONA]) {
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
                        synonyms
                    }
                }
            }
        """.trimIndent()
        
        return executeAniListQuery(query, "Em Alta", page)
    }

    private suspend fun getAniListSeason(page: Int): HomePageResponse {
        val query = """
            query {
                Page(page: $page, perPage: 20) {
                    media(season: WINTER, seasonYear: 2025, type: ANIME, sort: POPULARITY_DESC, format_in: [TV, TV_SHORT, OVA, MOVIE, SPECIAL, ONA]) {
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
                        synonyms
                    }
                }
            }
        """.trimIndent()
        
        return executeAniListQuery(query, "Populares Nessa Temporada", page)
    }

    private suspend fun getAniListPopular(page: Int): HomePageResponse {
        val query = """
            query {
                Page(page: $page, perPage: 20) {
                    media(sort: POPULARITY_DESC, type: ANIME, format_in: [TV, TV_SHORT, OVA, MOVIE, SPECIAL, ONA]) {
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
                        synonyms
                    }
                }
            }
        """.trimIndent()
        
        return executeAniListQuery(query, "Sempre Populares", page)
    }

    private suspend fun getAniListTop(page: Int): HomePageResponse {
        val query = """
            query {
                Page(page: $page, perPage: 20) {
                    media(sort: SCORE_DESC, type: ANIME, format_in: [TV, TV_SHORT, OVA, MOVIE, SPECIAL, ONA], minScore: 70) {
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
                        synonyms
                    }
                }
            }
        """.trimIndent()
        
        return executeAniListQuery(query, "Top 100", page, showRank = true)
    }

    private suspend fun getAniListUpcoming(page: Int): HomePageResponse {
        val query = """
            query {
                Page(page: $page, perPage: 20) {
                    media(season: SPRING, seasonYear: 2025, type: ANIME, sort: POPULARITY_DESC, format_in: [TV, TV_SHORT, OVA, MOVIE, SPECIAL, ONA]) {
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
                        synonyms
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
            println("📡 [ANILIST] Buscando: $pageName")
            
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
                val aniListResponse = response.parsedSafe<AniListApiResponse>()
                val mediaList = aniListResponse?.data?.Page?.media ?: emptyList()
                
                println("📊 [ANILIST] ${mediaList.size} resultados encontrados para $pageName")
                
                // Criar lista de resultados sem filtragem
                val filteredItems = mutableListOf<SearchResponse>()
                
                mediaList.forEachIndexed { index, media ->
                    val aniListTitle = media.title?.userPreferred ?: 
                                      media.title?.romaji ?: 
                                      media.title?.english ?: 
                                      "Sem Título"
                    
                    val finalTitle = if (showRank) "#${index + 1} $aniListTitle" else aniListTitle
                    val finalPoster = media.coverImage?.extraLarge ?: media.coverImage?.large
                    val specialUrl = "anilist:${media.id}:$aniListTitle"
                    
                    filteredItems.add(newAnimeSearchResponse(finalTitle, specialUrl) {
                        this.posterUrl = finalPoster
                        this.type = TvType.Anime
                        this.quality = SearchQuality.HIGH
                    })
                    
                    println("✅ [ANILIST] Adicionado: $aniListTitle")
                }
                
                newHomePageResponse(pageName, filteredItems, hasNext = filteredItems.isNotEmpty())
            } else {
                println("❌ [ANILIST] Erro na API: ${response.code}")
                newHomePageResponse(pageName, emptyList(), false)
            }
        } catch (e: Exception) {
            println("❌ [ANILIST] Exception: ${e.message}")
            newHomePageResponse(pageName, emptyList(), false)
        }
    }

    // ============ FUNÇÃO SEARCH FIXA ============
    
    override suspend fun search(query: String): List<SearchResponse> {
        println("🚀 [SEARCH] Iniciando busca por: '$query'")
        
        return try {
            // ATUALIZADO: Usa a nova mainUrl (animefire.io)
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "$mainUrl/pesquisar/$encodedQuery"
            println("🔗 [SEARCH] URL: $searchUrl")
            
            val response = app.get(searchUrl, timeout = 10_000)
            println("📡 [SEARCH] Status: ${response.code}")
            
            if (response.code != 200) {
                println("⚠️ [SEARCH] Página de busca não encontrada (${response.code}), tentando método alternativo...")
                // Se não encontrar página de busca, retorna resultados vazios
                return emptyList()
            }
            
            val document = response.document
            
            // Seletores comuns do AnimeFire
            val elements = document.select("article.card, .item, a[href*='/animes/'], a[href*='/filmes/']")
            println("🔍 [SEARCH] Elementos encontrados: ${elements.size}")
            
            return elements.mapNotNull { element ->
                try {
                    val href = element.attr("href")
                    if (href.isBlank()) return@mapNotNull null

                    val titleElement = element.selectFirst("h3, h4, .title, .anime-title, .card-title")
                    val rawTitle = titleElement?.text()?.trim() ?: element.ownText().trim()
                    
                    val cleanTitle = rawTitle
                        .replace(Regex("\\s*-\\s*Todos os Episódios$"), "")
                        .replace(Regex("\\(Dublado\\)|\\(Legendado\\)"), "")
                        .replace(Regex("\\(\\d{4}\\)"), "")
                        .trim()

                    val imgElement = element.selectFirst("img")
                    val posterUrl = when {
                        imgElement?.hasAttr("data-src") == true -> imgElement.attr("data-src")
                        imgElement?.hasAttr("src") == true -> imgElement.attr("src")
                        else -> null
                    }

                    val isMovie = href.contains("/filmes/") || 
                                 cleanTitle.contains("filme", ignoreCase = true) ||
                                 cleanTitle.contains("movie", ignoreCase = true)

                    if (cleanTitle.isNotBlank()) {
                        newAnimeSearchResponse(cleanTitle, fixUrl(href)) {
                            this.posterUrl = posterUrl?.let { fixUrl(it) }
                            this.type = if (isMovie) TvType.Movie else TvType.Anime
                        }
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    println("❌ [SEARCH] Erro ao processar elemento: ${e.message}")
                    null
                }
            }.distinctBy { it.url }.take(20)
            
        } catch (e: Exception) {
            println("❌ [SEARCH] Erro na busca: ${e.message}")
            emptyList()
        }
    }

    // ============ LOAD FUNCTIONS ============
    
    override suspend fun load(url: String): LoadResponse {
        println("\n🚀 AnimeFire.load() para URL: $url")
        
        if (url.startsWith("anilist:")) {
            return loadAniListContent(url)
        }
        
        return try {
            // ATUALIZADO: Usa a nova mainUrl para carregar conteúdo
            val document = app.get(url).document
            
            val titleElement = document.selectFirst("h1, .title-anime, .anime-title")
            val title = titleElement?.text()?.trim() ?: "Sem Título"
            
            val plotElement = document.selectFirst(".sinopse, .description, .plot")
            val plot = plotElement?.text()?.trim() ?: "Sem descrição"
            
            val posterElement = document.selectFirst(".anime-poster img, .poster img, .cover img")
            val poster = posterElement?.attr("src")?.let { fixUrl(it) } ?:
                       posterElement?.attr("data-src")?.let { fixUrl(it) }
            
            newAnimeLoadResponse(title, url, TvType.Anime) {
                this.plot = plot
                this.posterUrl = poster
            }
        } catch (e: Exception) {
            println("❌ Erro ao carregar: ${e.message}")
            newAnimeLoadResponse("Erro", url, TvType.Anime) {
                this.plot = "Não foi possível carregar este anime."
            }
        }
    }

    private suspend fun loadAniListContent(url: String): LoadResponse {
        println("🌐 Carregando conteúdo do AniList: $url")
        
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
                    title {
                        romaji
                        english
                        native
                        userPreferred
                    }
                    description
                    episodes
                    duration
                    coverImage {
                        extraLarge
                        large
                    }
                    bannerImage
                    genres
                    status
                    startDate {
                        year
                        month
                        day
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
                    val title = media.title?.userPreferred ?: 
                               media.title?.romaji ?: 
                               media.title?.english ?: 
                               titleFromUrl
                    
                    val description = media.description?.replace(Regex("<[^>]*>"), "") ?: "Sem descrição"
                    
                    return newAnimeLoadResponse(title, url, TvType.Anime) {
                        this.plot = description
                        this.posterUrl = media.coverImage?.extraLarge ?: media.coverImage?.large
                        this.backgroundPosterUrl = media.bannerImage
                        this.year = media.startDate?.year
                        this.tags = media.genres
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
        @JsonProperty("synonyms") val synonyms: List<String>? = null
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
        @JsonProperty("extraLarge") val extraLarge: String? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListDate(
        @JsonProperty("year") val year: Int? = null,
        @JsonProperty("month") val month: Int? = null,
        @JsonProperty("day") val day: Int? = null
    )
}
