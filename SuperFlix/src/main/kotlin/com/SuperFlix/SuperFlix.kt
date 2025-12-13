package com.SuperFlix

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
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

class SuperFlix : TmdbProvider() {
    override var name = "SuperFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val instantLinkLoading = true
    override val useMetaLoadResponse = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
    )

    // Configuração específica para TMDB
    private val tmdbLang = "pt-BR"
    private val tmdbRegion = "BR"

    companion object {
        const val HOST = "https://superflix21.lol"
    }
    
    // Página principal mínima
    override val mainPage = mainPageOf("" to "SuperFlix")
    
    // Sobrescreve a função de busca para forçar idioma português
    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "https://api.themoviedb.org/3/search/multi?api_key=${com.lagradost.cloudstream3.BuildConfig.TMDB_API}&query=$encodedQuery&language=$tmdbLang&region=$tmdbRegion"
        
        return try {
            val response = app.get(url).parsedSafe<TMDBSearchResponse>()
            response?.results?.mapNotNull { result ->
                newMovieSearchResponse(
                    title = result.title ?: result.name ?: return@mapNotNull null,
                    url = Data(
                        id = result.id,
                        type = if (result.media_type == "tv") "tv" else "movie"
                    ).toJson(),
                    TvType.Movie
                ) {
                    posterUrl = result.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
                    year = result.release_date?.substring(0, 4)?.toIntOrNull() 
                            ?: result.first_air_date?.substring(0, 4)?.toIntOrNull()
                }
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    // Sobrescreve também o quickSearch
    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query).take(5)
    }
    
    // Função para corrigir URLs
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
            
            println("🎬 [SuperFlix] Buscando: ${mediaData.movieName ?: "Unknown"}")
            println("🎬 [SuperFlix] TMDB ID: ${mediaData.tmdbID}")
            
            // 1. Busca no site SuperFlix
            val searchQuery = mediaData.movieName ?: return false
            val searchUrl = "$HOST/buscar?q=${URLEncoder.encode(searchQuery, "UTF-8")}"
            
            println("🔍 [SuperFlix] Buscando em: $searchUrl")
            val document = app.get(searchUrl).document
            
            // 2. Encontra primeiro resultado
            val firstResult = document.selectFirst(".grid .card, a.card, .movie-item a, .rec-card")
            if (firstResult == null) {
                println("❌ [SuperFlix] Nenhum resultado encontrado")
                return false
            }
            
            val detailUrl = firstResult.attr("href")?.let { fixUrl(it) } ?: return false
            println("🔗 [SuperFlix] Página de detalhes: $detailUrl")
            
            // 3. Carrega página de detalhes
            val detailDoc = app.get(detailUrl).document
            
            // 4. Encontra player
            val playerUrl = findPlayerUrl(detailDoc)
            if (playerUrl == null) {
                println("❌ [SuperFlix] Player não encontrado")
                return false
            }
            
            println("🎥 [SuperFlix] Player URL: $playerUrl")
            
            // 5. Usa seu extractor personalizado
            val success = SuperFlixExtractor.extractVideoLinks(playerUrl, HOST, name, callback)
            
            if (!success) {
                println("⚠️ [SuperFlix] Extractor falhou, tentando método alternativo...")
                // Fallback
                extractVideoFallback(playerUrl, callback)
            }
            
            success
        } catch (e: Exception) {
            println("💥 [SuperFlix] Erro: ${e.message}")
            false
        }
    }
    
    private fun findPlayerUrl(document: org.jsoup.nodes.Document): String? {
        // Método 1: Botão com data-url
        val playButton = document.selectFirst("button.bd-play[data-url], button[data-url*='watch']")
        if (playButton != null) {
            return fixUrl(playButton.attr("data-url"))
        }
        
        // Método 2: Iframe
        val iframe = document.selectFirst("iframe[src]")
        if (iframe != null) {
            return fixUrl(iframe.attr("src"))
        }
        
        // Método 3: Link direto
        val videoLink = document.selectFirst("a[href*='.m3u8'], a[href*='watch']")
        if (videoLink != null) {
            return fixUrl(videoLink.attr("href"))
        }
        
        return null
    }
    
    private suspend fun extractVideoFallback(playerUrl: String, callback: (ExtractorLink) -> Unit) {
        try {
            callback.invoke(
                newExtractorLink(
                    name,
                    "SuperFlix",
                    playerUrl,
                    ExtractorLinkType.M3U8
                ) {
                    this.referer = HOST
                    this.quality = Qualities.P720.value
                }
            )
        } catch (e: Exception) {
            println("⚠️ [SuperFlix] Fallback falhou")
        }
    }

    // Classes de dados para TMDB
    data class TMDBSearchResponse(
        @JsonProperty("results") val results: List<TMDBResult>
    )

    data class TMDBResult(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("media_type") val media_type: String? = null,
        @JsonProperty("poster_path") val poster_path: String? = null,
        @JsonProperty("release_date") val release_date: String? = null,
        @JsonProperty("first_air_date") val first_air_date: String? = null
    )

    data class Data(
        val id: Int,
        val type: String
    )
}
