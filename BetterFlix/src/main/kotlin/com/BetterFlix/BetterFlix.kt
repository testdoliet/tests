package com.Betterflix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty

class BetterFlixProvider : MainAPI() {
    override var mainUrl = "https://betterflix.vercel.app"
    override var name = "BetterFlix"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    // Headers fixos para todas as requisições
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
        "Referer" to "$mainUrl/",
        "sec-ch-ua" to "\"Chromium\";v=\"127\", \"Not)A;Brand\";v=\"99\", \"Microsoft Edge Simulate\";v=\"127\", \"Lemur\";v=\"127\"",
        "sec-ch-ua-mobile" to "?1",
        "sec-ch-ua-platform" to "\"Android\""
    )

    // Mapeamento de gêneros
    private val genreMap = mapOf(
        "28" to "Ação e Aventura",
        "35" to "Comédia", 
        "27" to "Terror e Suspense",
        "99" to "Documentário",
        "10751" to "Para a Família",
        "80" to "Crime",
        "10402" to "Musical",
        "10749" to "Romance"
    )

    // Modelo da resposta da API
    data class ApiResponse(
        @JsonProperty("results") val results: List<MediaItem> = emptyList(),
        @JsonProperty("items") val items: List<MediaItem> = emptyList()
    )

    data class MediaItem(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("media_type") val mediaType: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("genre_ids") val genreIds: List<Int>? = null
    )

    // Páginas principais
    override val mainPage = mainPageOf(
        "trending" to "🔥 Em Alta",
        "28" to "🎬 Ação e Aventura",
        "35" to "😂 Comédia", 
        "27" to "😱 Terror e Suspense",
        "99" to "📚 Documentário",
        "10751" to "👨‍👩‍👧‍👦 Para a Família",
        "80" to "🔫 Crime",
        "10402" to "🎵 Musical",
        "10749" to "💖 Romance",
        "anime" to "🇯🇵 Animes"
    )

    override suspend fun getMainPage(
        page: Int, 
        request: MainPageRequest
    ): HomePageResponse {
        val items = mutableListOf<HomePageList>()
        
        try {
            when (request.name) {
                "trending" -> {
                    val data = app.get(
                        "$mainUrl/api/trending?type=all",
                        headers = headers
                    ).parsedSafe<ApiResponse>()
                    
                    items.add(HomePageList(
                        name = request.name,
                        list = (data?.results ?: emptyList()).mapNotNull { it.toSearchResponse() },
                        isHorizontalImages = true
                    ))
                }
                
                "anime" -> {
                    val data = app.get(
                        "$mainUrl/api/list-animes", 
                        headers = headers
                    ).parsedSafe<ApiResponse>()
                    
                    items.add(HomePageList(
                        name = request.name,
                        list = (data?.results ?: data?.items ?: emptyList())
                            .mapNotNull { it.toSearchResponse() },
                        isHorizontalImages = true
                    ))
                }
                
                else -> {
                    if (genreMap.containsKey(request.name)) {
                        val data = app.get(
                            "$mainUrl/api/preview-genre?id=${request.name}",
                            headers = headers
                        ).parsedSafe<ApiResponse>()
                        
                        items.add(HomePageList(
                            name = genreMap[request.name] ?: request.name,
                            list = (data?.results ?: emptyList())
                                .mapNotNull { it.toSearchResponse() },
                            isHorizontalImages = true
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return HomePageResponse(items)
    }

    // Converte item da API para SearchResponse do CloudStream
    private fun MediaItem.toSearchResponse(): SearchResponse? {
        val itemId = this.id ?: return null
        val itemTitle = this.title ?: this.name ?: return null
        val itemType = when (this.mediaType) {
            "movie" -> TvType.Movie
            "tv" -> TvType.TvSeries
            "anime" -> TvType.Anime
            else -> TvType.TvSeries
        }
        
        // URL da imagem
        val posterUrl = this.posterPath?.let { path ->
            if (path.startsWith("http")) path else "https://image.tmdb.org/t/p/w500$path"
        }
        
        val backdropUrl = this.backdropPath?.let { path ->
            if (path.startsWith("http")) path else "https://image.tmdb.org/t/p/w780$path"
        }
        
        // Ano de lançamento
        val year = (this.releaseDate ?: this.firstAirDate)?.take(4)?.toIntOrNull()
        
        // Qualidade baseada na avaliação
        val quality = when (this.voteAverage ?: 0.0) {
            in 8.0..10.0 -> SearchQuality.HD
            in 6.0..7.9 -> SearchQuality.SD
            else -> SearchQuality.Unknown
        }
        
        return newMovieSearchResponse(
            title = itemTitle,
            url = itemId.toString(),
            type = itemType
        ) {
            this.posterUrl = posterUrl
            this.backdropUrl = backdropUrl
            this.year = year
            this.quality = quality
            this.plot = this@toSearchResponse.overview
        }
    }

    // Busca simples (pode ser expandida depois)
    override suspend fun search(query: String): List<SearchResponse> {
        // Implementar se tiver endpoint de busca
        return emptyList()
    }

    // Carregar detalhes (placeholder)
    override suspend fun load(url: String): LoadResponse {
        val id = url.toIntOrNull() ?: throw ErrorLoadingException("ID inválido")
        
        return MovieLoadResponse(
            title = "Carregando...",
            url = url,
            posterUrl = null,
            plot = "Detalhes serão implementados posteriormente.\nID: $id",
            year = null
        )
    }

    // Links de streaming (placeholder)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return false
    }
}
