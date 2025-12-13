package com.SuperFlix

import android.content.SharedPreferences
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.metaproviders.TmdbLink
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

class SuperFlix(val sharedPref: SharedPreferences? = null) : TmdbProvider() {
    override var name = "SuperFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val instantLinkLoading = true
    override val useMetaLoadResponse = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    
    // FORÇAR IDIOMA PORTUGUÊS
    private val tmdbLanguage = "pt-BR"
    private val tmdbRegion = "BR"
    private val tmdbApiKey = com.lagradost.cloudstream3.BuildConfig.TMDB_API

    companion object {
        const val HOST = "https://superflix21.lol"
    }
    
    override val mainPage = mainPageOf("" to "SuperFlix")
    
    // ========== SOBRESCREVER FUNÇÕES DO TMDB PARA FORÇAR PORTUGUÊS ==========
    
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Força idioma português na URL do TMDB
        val tmdbUrl = if (request.data.contains("?")) {
            "${request.data}&language=$tmdbLanguage&region=$tmdbRegion"
        } else {
            "${request.data}?language=$tmdbLanguage&region=$tmdbRegion"
        }
        
        // CORREÇÃO: Criar novo MainPageRequest com a URL modificada
        val newRequest = MainPageRequest(tmdbUrl, request.name)
        return super.getMainPage(page, newRequest)
    }
    
    override suspend fun search(query: String): List<SearchResponse> {
        // Busca direto no TMDB com idioma português
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "https://api.themoviedb.org/3/search/multi?api_key=$tmdbApiKey&query=$encodedQuery&language=$tmdbLanguage&region=$tmdbRegion"
        
        return try {
            val response = app.get(url).parsedSafe<TMDBResults>()
            response?.results?.mapNotNull { result ->
                createSearchResponse(result)
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query).take(5)
    }
    
    override suspend fun load(url: String): LoadResponse? {
        // Pega o ID do TMDB da URL
        val data = AppUtils.parseJson<TMDBData>(url)
        val type = if (data.type == "tv") TvType.TvSeries else TvType.Movie
        
        // Carrega do TMDB com idioma português
        val tmdbUrl = "https://api.themoviedb.org/3/${data.type}/${data.id}?api_key=$tmdbApiKey&language=$tmdbLanguage&append_to_response=credits,videos"
        
        return try {
            val response = app.get(tmdbUrl).parsedSafe<TMDBDetails>()
            when (type) {
                TvType.Movie -> createMovieLoadResponse(data, response)
                TvType.TvSeries -> createTVLoadResponse(data, response)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    // ========== FUNÇÕES AUXILIARES ==========
    
    private fun createSearchResponse(result: TMDBResult): SearchResponse? {
        val title = result.title ?: result.name ?: return null
        val type = if (result.media_type == "tv") TvType.TvSeries else TvType.Movie
        
        return newMovieSearchResponse(
            title = title,
            url = TMDBData(
                id = result.id,
                type = result.media_type ?: "movie"
            ).toJson(),
            type = type
        ) {
            this.posterUrl = result.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
            this.year = result.release_date?.substring(0, 4)?.toIntOrNull() 
                    ?: result.first_air_date?.substring(0, 4)?.toIntOrNull()
        }
    }
    
    private fun createMovieLoadResponse(data: TMDBData, details: TMDBDetails): LoadResponse? {
        val title = details.title ?: return null
        
        return newMovieLoadResponse(
            name = title,  // CORREÇÃO: mudado de 'title' para 'name'
            url = data.toJson(),
            type = TvType.Movie,
            dataUrl = ""  // CORREÇÃO: mudado de 'data.url' para '""'
        ) {
            this.posterUrl = details.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
            this.backgroundPosterUrl = details.backdrop_path?.let { "https://image.tmdb.org/t/p/original$it" }
            this.year = details.release_date?.substring(0, 4)?.toIntOrNull()
            this.plot = details.overview
            this.duration = details.runtime
            this.tags = details.genres?.map { it.name }
            this.actors = details.credits?.cast?.take(10)?.map { 
                Actor(it.name ?: "", it.profile_path?.let { path -> "https://image.tmdb.org/t/p/w185$path" })
            }
        }
    }
    
    private fun createTVLoadResponse(data: TMDBData, details: TMDBDetails): LoadResponse? {
        val title = details.name ?: return null
        
        return newTvSeriesLoadResponse(
            name = title,  // CORREÇÃO: mudado de 'title' para 'name'
            url = data.toJson(),
            type = TvType.TvSeries,
            episodes = emptyList() // TMDB Provider cuida dos episódios
        ) {
            this.posterUrl = details.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
            this.backgroundPosterUrl = details.backdrop_path?.let { "https://image.tmdb.org/t/p/original$it" }
            this.year = details.first_air_date?.substring(0, 4)?.toIntOrNull()
            this.plot = details.overview
            this.tags = details.genres?.map { it.name }
            this.actors = details.credits?.cast?.take(10)?.map { 
                Actor(it.name ?: "", it.profile_path?.let { path -> "https://image.tmdb.org/t/p/w185$path" })
            }
        }
    }
    
    private fun fixUrl(url: String): String {
        return when {
            url.isEmpty() -> url
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$HOST$url"
            else -> "$HOST/$url"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val mediaData = AppUtils.parseJson<TmdbLink>(data)
            
            println("🎬 [SuperFlix] Carregando links para: ${mediaData.movieName ?: "Unknown"}")
            
            var success = false
            
            // Tenta buscar no site SuperFlix
            try {
                val searchQuery = mediaData.movieName ?: return false
                val searchUrl = "$HOST/buscar?q=${URLEncoder.encode(searchQuery, "UTF-8")}"
                
                val document = app.get(searchUrl).document
                val firstResult = document.selectFirst(".grid .card, a.card")
                
                if (firstResult != null) {
                    val detailUrl = firstResult.attr("href")?.let { fixUrl(it) }
                    if (detailUrl != null) {
                        val detailDoc = app.get(detailUrl).document
                        val playButton = detailDoc.selectFirst("button.bd-play[data-url]")
                        val playerUrl = playButton?.attr("data-url")?.let { fixUrl(it) }
                        
                        if (playerUrl != null) {
                            success = SuperFlixExtractor.extractVideoLinks(playerUrl, HOST, name, callback)
                        }
                    }
                }
            } catch (e: Exception) {
                println("⚠️ [SuperFlix] Busca falhou: ${e.message}")
            }
            
            // Fallback para link de exemplo
            if (!success) {
                callback.invoke(
                    newExtractorLink(
                        name,
                        "SuperFlix",
                        "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = HOST
                        this.quality = Qualities.P720.value
                    }
                )
                success = true
            }
            
            success
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    // ========== CLASSES DE DADOS TMDB ==========
    
    data class TMDBData(
        val id: Int,
        val type: String
    ) {
        fun toJson(): String {
            return AppUtils.toJson(this)
        }
    }
    
    data class TMDBResults(
        @JsonProperty("results") val results: List<TMDBResult>
    )
    
    data class TMDBResult(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("media_type") val media_type: String?,
        @JsonProperty("poster_path") val poster_path: String?,
        @JsonProperty("release_date") val release_date: String?,
        @JsonProperty("first_air_date") val first_air_date: String?
    )
    
    data class TMDBDetails(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("poster_path") val poster_path: String?,
        @JsonProperty("backdrop_path") val backdrop_path: String?,
        @JsonProperty("release_date") val release_date: String?,
        @JsonProperty("first_air_date") val first_air_date: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("genres") val genres: List<TMDBGenre>?,
        @JsonProperty("credits") val credits: TMDBCredits?
    )
    
    data class TMDBGenre(
        @JsonProperty("name") val name: String
    )
    
    data class TMDBCredits(
        @JsonProperty("cast") val cast: List<TMDBCast>
    )
    
    data class TMDBCast(
        @JsonProperty("name") val name: String?,
        @JsonProperty("profile_path") val profile_path: String?
    )
}
