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

class SuperFlix : TmdbProvider() {
    override var mainUrl = "https://superflix21.lol"
    override var name = "SuperFlix"
    override val hasMainPage = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val usesWebView = false
    override var lang = "pt-br"
    override val useMetaLoadResponse = true
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
            
            // PASSO 1: Buscar no SuperFlix
            val title = mediaData.movieName ?: return false
            val encodedTitle = java.net.URLEncoder.encode(title, "UTF-8")
            val searchUrl = "$mainUrl/buscar?q=$encodedTitle"
            
            val searchDoc = app.get(searchUrl).document
            val firstCard = searchDoc.selectFirst("a.card, .grid a, .movie-item a")
            val detailUrl = firstCard?.attr("href")?.let { fixUrl(it) } ?: return false
            
            // PASSO 2: Carregar página do filme/série
            val detailDoc = app.get(detailUrl).document
            
            // PASSO 3: Encontrar botão de play (adaptar ao site real)
            val playButton = detailDoc.selectFirst("button.bd-play[data-url], .play-btn[data-url], [data-url*='watch']")
            val playerUrl = playButton?.attr("data-url")?.let { fixUrl(it) } ?: return false
            
            // PASSO 4: Extrair vídeo (similar ao Tamilian)
            val playerDoc = app.get(playerUrl).document
            val script = playerDoc.selectFirst("script:containsData(function(p,a,c,k,e,d))")
                ?.data()?.let { getAndUnpack(it) }
            
            val token = script?.substringAfter("FirePlayer(\"")?.substringBefore("\",")
            val videoApiUrl = "$mainUrl/player/index.php?data=$token&do=getVideo"
            
            // PASSO 5: Obter link m3u8
            val videoData = app.post(videoApiUrl, headers = mapOf("X-Requested-With" to "XMLHttpRequest"))
                .parsedSafe<VideoResponse>()
            
            videoData?.source?.let { videoUrl ->
                callback(
                    newExtractorLink(
                        name,
                        name,
                        url = videoUrl,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.P1080.value
                    }
                )
                true
            } ?: false
            
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    data class VideoResponse(
        @JsonProperty("source") val source: String?,
        @JsonProperty("hls") val hls: Boolean?,
        @JsonProperty("videoImage") val videoImage: String?
    )
}
