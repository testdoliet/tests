package com.BetterFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty

class BetterFlixMetadata : MainAPI() {
    override var mainUrl = "https://betterflix.vercel.app"
    override var name = "BetterFlix Metadata"
    override val hasMainPage = false
    override var lang = "pt-br"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val usesWebView = false

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "https://betterflix.vercel.app/",
        "Origin" to "https://betterflix.vercel.app"
    )

    private val cookies = mapOf(
        "dom3ic8zudi28v8lr6fgphwffqoz0j6c" to "33de42d8-3e93-4249-b175-d6bf5346ae91%3A2%3A1",
        "pp_main_80d9775bdcedfb8fd29914d950374a08" to "1"
    )

    // =============================================
    // 1. BADGES DE AVALIAÇÃO (RATING)
    // =============================================
    fun extractRatingFromJson(jsonResponse: String): Score? {
        try {
            val data = AppUtils.parseJson<TMDBResponse>(jsonResponse)
            data?.results?.firstOrNull()?.let { item ->
                item.vote_average?.let { rating ->
                    println("⭐ [RATING] Avaliação encontrada: $rating")
                    return Score.from10(rating)
                }
            }
        } catch (e: Exception) {
            println("❌ [RATING] Erro ao extrair rating: ${e.message}")
        }
        return null
    }

    // =============================================
    // 2. RECOMENDAÇÕES (via API /api/recommendations)
    // =============================================
    suspend fun fetchRecommendations(tmdbId: String, type: String): List<SearchResponse> {
        return try {
            val apiUrl = "$mainUrl/api/recommendations?id=$tmdbId&type=$type"
            println("🤝 [RECOMMENDATIONS] Buscando: $apiUrl")
            
            val response = app.get(apiUrl, headers = headers, cookies = cookies, timeout = 30)
            
            if (response.code != 200) {
                println("❌ [RECOMMENDATIONS] Falha: ${response.code}")
                return emptyList()
            }
            
            val data = response.parsedSafe<RecommendationsResponse>()
            if (data == null || data.results.isEmpty()) {
                println("⚠️ [RECOMMENDATIONS] Sem recomendações")
                return emptyList()
            }
            
            println("✅ [RECOMMENDATIONS] ${data.results.size} recomendações encontradas")
            
            data.results.mapNotNull { item ->
                createSearchResponseFromItem(item)
            }
            
        } catch (e: Exception) {
            println("❌ [RECOMMENDATIONS] Erro: ${e.message}")
            emptyList()
        }
    }

    private fun createSearchResponseFromItem(item: ContentItem): SearchResponse? {
        return try {
            val title = item.title ?: item.name ?: item.originalTitle ?: item.originalName ?: return null
            val year = getYearFromDate(item.releaseDate ?: item.firstAirDate)
            val poster = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
            val id = item.id.toString()

            val type = when (item.mediaType) {
                "movie" -> TvType.Movie
                "tv" -> TvType.TvSeries
                "anime" -> TvType.Anime
                else -> when {
                    title.contains("(Anime)", ignoreCase = true) -> TvType.Anime
                    item.releaseDate != null -> TvType.Movie
                    item.firstAirDate != null -> TvType.TvSeries
                    else -> TvType.Movie
                }
            }

            val slug = generateSlug(title)
            val url = when (type) {
                TvType.Movie -> "$mainUrl/$slug?id=$id&type=movie"
                TvType.TvSeries -> "$mainUrl/$slug?id=$id&type=tv"
                TvType.Anime -> "$mainUrl/$slug?id=$id&type=anime"
                else -> "$mainUrl/$slug?id=$id&type=movie"
            }

            when (type) {
                TvType.Movie -> newMovieSearchResponse(title, url, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year
                }
                TvType.TvSeries -> newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = year
                }
                TvType.Anime -> newAnimeSearchResponse(title, url, TvType.Anime) {
                    this.posterUrl = poster
                    this.year = year
                }
                else -> null
            }
        } catch (e: Exception) {
            println("❌ [RECOMMENDATIONS] Erro ao criar item: ${e.message}")
            null
        }
    }

    // =============================================
    // 3. CANAIS AO VIVO (do site)
    // =============================================
    suspend fun fetchLiveChannels(): List<SearchResponse> {
        return try {
            // URL da página de canais ao vivo (você precisa me dizer qual é)
            val liveUrl = "$mainUrl/canais-ao-vivo" // ou a URL correta
            
            println("📺 [LIVE] Buscando canais ao vivo: $liveUrl")
            
            val response = app.get(liveUrl, headers = headers, cookies = cookies, timeout = 30)
            
            if (response.code != 200) {
                println("❌ [LIVE] Falha: ${response.code}")
                return emptyList()
            }
            
            val document = response.document
            
            // Extrair canais - você precisa me mostrar a estrutura HTML
            val channelElements = document.select("a[href*='canal'], .channel-item, .live-channel")
            
            println("📺 [LIVE] ${channelElements.size} canais encontrados")
            
            channelElements.mapNotNull { element ->
                extractLiveChannel(element)
            }
            
        } catch (e: Exception) {
            println("❌ [LIVE] Erro: ${e.message}")
            emptyList()
        }
    }

    private fun extractLiveChannel(element: org.jsoup.nodes.Element): SearchResponse? {
        return try {
            val href = element.attr("href") ?: return null
            val url = fixUrl(href)
            
            val title = element.selectFirst("img")?.attr("alt") ?:
                       element.selectFirst(".channel-name, .title")?.text() ?:
                       element.text().trim().takeIf { it.isNotBlank() } ?:
                       return null
            
            val poster = element.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
            
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
                // Canais ao vivo são considerados "filmes" no Cloudstream
                this.year = null
            }
            
        } catch (e: Exception) {
            println("❌ [LIVE] Erro ao extrair canal: ${e.message}")
            null
        }
    }

    // =============================================
    // FUNÇÕES AUXILIARES
    // =============================================
    private fun getYearFromDate(dateString: String?): Int? {
        return try {
            dateString?.substring(0, 4)?.toIntOrNull()
        } catch (e: Exception) {
            null
        }
    }

    private fun generateSlug(title: String): String {
        return title
            .lowercase()
            .replace(Regex("[^a-z0-9\\s-]"), "")
            .replace(Regex("\\s+"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
    }

    private fun fixUrl(url: String): String {
        return if (url.startsWith("http")) url else "$mainUrl${if (url.startsWith("/")) "" else "/"}$url"
    }

    // =============================================
    // MODELOS DE DADOS
    // =============================================
    data class TMDBResponse(
        @JsonProperty("results") val results: List<TMDBItem>
    )

    data class TMDBItem(
        @JsonProperty("vote_average") val vote_average: Double?
    )

    data class RecommendationsResponse(
        @JsonProperty("results") val results: List<ContentItem>
    )

    data class ContentItem(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("original_title") val originalTitle: String?,
        @JsonProperty("original_name") val originalName: String?,
        @JsonProperty("poster_path") val posterPath: String?,
        @JsonProperty("media_type") val mediaType: String?,
        @JsonProperty("release_date") val releaseDate: String?,
        @JsonProperty("first_air_date") val firstAirDate: String?
    )
}
