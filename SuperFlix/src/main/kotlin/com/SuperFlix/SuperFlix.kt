package com.SuperFlix

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.metaproviders.TmdbLink
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.safeApiCall
import org.jsoup.nodes.Element
import java.net.URLEncoder

class SuperFlix : TmdbProvider() {
    override var mainUrl = "https://superflix21.lol"
    override var name = "SuperFlix"
    override val hasMainPage = false // CloudStream cuida da página principal
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val usesWebView = false
    override var lang = "pt-br"
    override val useMetaLoadResponse = true // CloudStream cuida dos metadados
    override val hasQuickSearch = true
    override val instantLinkLoading = true

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val mediaData = AppUtils.parseJson<TmdbLink>(data)
            
            // Log para debug
            println("🎬 [SuperFlix] Buscando: ${mediaData.movieName ?: "Unknown"}")
            println("🎬 [SuperFlix] TMDB ID: ${mediaData.tmdbID}")
            println("🎬 [SuperFlix] Tipo: ${if (mediaData.season != null) "Série" else "Filme"}")
            
            // 1. Buscar no site SuperFlix
            val searchResults = searchOnSuperFlix(mediaData.movieName ?: return false)
            val detailUrl = searchResults.firstOrNull()?.url ?: return false
            
            // 2. Carregar página de detalhes
            val detailDoc = app.get(detailUrl).document
            
            // 3. Encontrar player
            val playerUrl = findPlayerUrl(detailDoc) ?: return false
            
            // 4. Extrair vídeo (similar ao Tamilian)
            extractVideoFromPlayer(playerUrl, callback)
            
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    private suspend fun searchOnSuperFlix(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/buscar?q=${URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document
        
        return document.select(".grid .card, a.card").mapNotNull { card ->
            try {
                val title = card.attr("title") ?: card.selectFirst("img")?.attr("alt") ?: return@mapNotNull null
                val href = card.attr("href") ?: return@mapNotNull null
                
                val poster = card.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
                val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
                
                val badge = card.selectFirst(".badge-kind")?.text()?.lowercase() ?: ""
                val isAnime = badge.contains("anime") || href.contains("/anime/")
                val isSerie = badge.contains("série") || badge.contains("serie") || href.contains("/serie/")
                
                when {
                    isAnime -> newAnimeSearchResponse(cleanTitle, fixUrl(href), TvType.Anime) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    isSerie -> newTvSeriesSearchResponse(cleanTitle, fixUrl(href), TvType.TvSeries) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    else -> newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                        this.posterUrl = poster
                        this.year = year
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
    }
    
    private fun findPlayerUrl(document: org.jsoup.nodes.Document): String? {
        // Usa sua lógica existente
        val playButton = document.selectFirst("button.bd-play[data-url]")
        if (playButton != null) {
            return playButton.attr("data-url")
        }
        
        val iframe = document.selectFirst("iframe[src*='fembed'], iframe[src*='filemoon']")
        if (iframe != null) {
            return iframe.attr("src")
        }
        
        val videoLink = document.selectFirst("a[href*='.m3u8'], a[href*='.mp4']")
        return videoLink?.attr("href")
    }
    
    private suspend fun extractVideoFromPlayer(playerUrl: String, callback: (ExtractorLink) -> Unit) {
        safeApiCall {
            val document = app.get(playerUrl).document
            
            // Tenta método tipo Tamilian (FirePlayer)
            val script = document.selectFirst("script:containsData(function(p,a,c,k,e,d))")
                ?.data()?.let { getAndUnpack(it) }
            
            if (script != null) {
                val token = script.substringAfter("FirePlayer(\"").substringBefore("\",")
                val videoApiUrl = "$mainUrl/player/index.php?data=$token&do=getVideo"
                
                val videoData = app.post(videoApiUrl, headers = mapOf("X-Requested-With" to "XMLHttpRequest"))
                    .parsedSafe<VideoData>()
                
                videoData?.videoSource?.let { videoUrl ->
                    callback.invoke(
                        newExtractorLink(
                            name,
                            name,
                            url = videoUrl,
                            ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$mainUrl/"
                            this.quality = Qualities.P1080.value
                            this.headers = mapOf("Origin" to mainUrl)
                        }
                    )
                    return@safeApiCall
                }
            }
            
            // Fallback: procura m3u8 diretamente
            val m3u8Link = document.select("source[src*='.m3u8'], script:containsData(.m3u8)").firstOrNull()
            val m3u8Url = m3u8Link?.attr("src") ?: 
                         Regex("(https?:[^\"']+\\.m3u8[^\"']*)").find(document.html())?.value
            
            if (m3u8Url != null) {
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        url = m3u8Url,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = playerUrl
                        this.quality = Qualities.P1080.value
                    }
                )
            }
        }
    }
    
    data class VideoData(
        @JsonProperty("hls") val hls: Boolean,
        @JsonProperty("videoImage") val videoImage: String,
        @JsonProperty("videoSource") val videoSource: String,
        @JsonProperty("securedLink") val securedLink: String,
        @JsonProperty("downloadLinks") val downloadLinks: List<Any?>,
        @JsonProperty("attachmentLinks") val attachmentLinks: List<Any?>,
        @JsonProperty("ck") val ck: String,
    )
}
