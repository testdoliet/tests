package com.SuperFlix

import com.fasterxml.jackson.annotation.JsonProperty
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

    companion object
    {
        const val HOST="https://superflix21.lol"
    }
    
    // Função para corrigir URLs (similar ao fixUrl do Tamilian)
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
        val mediaData = AppUtils.parseJson<TmdbLink>(data).toLinkData()
        
        // Busca no site SuperFlix
        val searchQuery = mediaData.title ?: return false
        val searchUrl = "$HOST/buscar?q=${java.net.URLEncoder.encode(searchQuery, "UTF-8")}"
        
        val document = app.get(searchUrl).document
        val firstResult = document.selectFirst(".grid .card, a.card")
        val detailUrl = firstResult?.attr("href")?.let { fixUrl(it) } ?: return false
        
        // Carrega página do filme/série
        val detailDoc = app.get(detailUrl).document
        
        // Encontra botão de play
        val playButton = detailDoc.selectFirst("button.bd-play[data-url], .play-btn[data-url], [data-url*='watch']")
        val playerUrl = playButton?.attr("data-url")?.let { fixUrl(it) } ?: return false
        
        // Extrai vídeo (similar ao Tamilian)
        val playerDoc = app.get(playerUrl).document
        val script = playerDoc.selectFirst("script:containsData(function(p,a,c,k,e,d))")
            ?.data()?.let { getAndUnpack(it) }

        val token = script?.substringAfter("FirePlayer(\"")?.substringBefore("\",")
        val m3u8 = app.post("$HOST/player/index.php?data=$token&do=getVideo", headers = mapOf("X-Requested-With" to "XMLHttpRequest"))
            .parsedSafe<VideoData>()
        
        val headers = mapOf("Origin" to HOST)
        
        m3u8?.let {
            callback.invoke(
                newExtractorLink(
                    name,
                    name,
                    url = it.videoSource,
                    ExtractorLinkType.M3U8
                ) {
                    this.referer = "$HOST/"
                    this.quality = Qualities.P1080.value
                    this.headers = headers
                }
            )
        }
        
        return m3u8 != null
    }


    private fun TmdbLink.toLinkData(): LinkData {
        return LinkData(
            imdbId = imdbID,
            tmdbId = tmdbID,
            title = movieName,
            season = season,
            episode = episode
        )
    }


    data class LinkData(
        @JsonProperty("simklId") val simklId: Int? = null,
        @JsonProperty("traktId") val traktId: Int? = null,
        @JsonProperty("imdbId") val imdbId: String? = null,
        @JsonProperty("tmdbId") val tmdbId: Int? = null,
        @JsonProperty("tvdbId") val tvdbId: Int? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("episode") val episode: Int? = null,
        @JsonProperty("aniId") val aniId: String? = null,
        @JsonProperty("malId") val malId: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("year") val year: Int? = null,
        @JsonProperty("orgTitle") val orgTitle: String? = null,
        @JsonProperty("isAnime") val isAnime: Boolean = false,
        @JsonProperty("airedYear") val airedYear: Int? = null,
        @JsonProperty("lastSeason") val lastSeason: Int? = null,
        @JsonProperty("epsTitle") val epsTitle: String? = null,
        @JsonProperty("jpTitle") val jpTitle: String? = null,
        @JsonProperty("date") val date: String? = null,
        @JsonProperty("airedDate") val airedDate: String? = null,
        @JsonProperty("isAsian") val isAsian: Boolean = false,
        @JsonProperty("isBollywood") val isBollywood: Boolean = false,
        @JsonProperty("isCartoon") val isCartoon: Boolean = false,
    )


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
