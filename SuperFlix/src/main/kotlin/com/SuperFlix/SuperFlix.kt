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
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat

class SuperFlix : TmdbProvider() {
    override var mainUrl = "https://superflix21.lol"
    override var name = "SuperFlix"
    override val hasMainPage = false // Não temos página principal própria
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val usesWebView = false
    override var lang = "pt-br"
    override val useMetaLoadResponse = true // Usa a resposta meta do TMDB
    override val hasQuickSearch = true
    override val instantLinkLoading = true

    companion object {
        const val HOST = "https://superflix21.lol"
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val mediaData = AppUtils.parseJson<TmdbLink>(data).toLinkData()
        
        // Buscar o conteúdo no site SuperFlix
        val searchQuery = "${mediaData.title} ${mediaData.year}"
        val searchUrl = "$HOST/buscar?q=${java.net.URLEncoder.encode(searchQuery, "UTF-8")}"
        
        val document = app.get(searchUrl).document
        val firstResult = document.selectFirst(".grid .card, a.card")
        
        firstResult?.let { card ->
            val href = card.attr("href") ?: return@let
            val detailUrl = fixUrl(href)
            
            // Carregar página de detalhes
            val detailDoc = app.get(detailUrl).document
            
            // Encontrar player
            val playerUrl = findPlayerUrl(detailDoc)
            
            if (playerUrl != null) {
                // Extrair vídeo
                extractVideo(playerUrl, callback)
            }
        }
        
        return true
    }
    
    private fun findPlayerUrl(document: org.jsoup.nodes.Document): String? {
        // Primeiro tenta o botão de play
        val playButton = document.selectFirst("button.bd-play[data-url]")
        if (playButton != null) {
            return playButton.attr("data-url")
        }
        
        // Tenta iframe
        val iframe = document.selectFirst("iframe[src*='fembed'], iframe[src*='filemoon'], iframe[src*='player'], iframe[src*='embed']")
        if (iframe != null) {
            return iframe.attr("src")
        }
        
        // Tenta link direto
        val videoLink = document.selectFirst("a[href*='.m3u8'], a[href*='.mp4'], a[href*='watch']")
        return videoLink?.attr("href")
    }
    
    private fun extractVideo(playerUrl: String, callback: (ExtractorLink) -> Unit) {
        // Similar ao Tamilian, extrai o vídeo
        try {
            val document = app.get(playerUrl).document
            
            // Busca script com dados do vídeo (ajuste conforme o site)
            val script = document.selectFirst("script:containsData(function(p,a,c,k,e,d))")
                ?.data()?.let { getAndUnpack(it) }
            
            script?.let {
                // Extrai token e busca vídeo (adaptar conforme necessidade)
                val token = it.substringAfter("FirePlayer(\"").substringBefore("\",")
                val videoUrl = "$mainUrl/player/index.php?data=$token&do=getVideo"
                
                // Aqui você faria a requisição para obter o m3u8
                // Similar ao Tamilian
            }
            
        } catch (e: Exception) {
            // Fallback: tenta usar o playerUrl diretamente se for m3u8
            if (playerUrl.contains(".m3u8")) {
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        url = playerUrl,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.P1080.value
                    }
                )
            }
        }
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
        @JsonProperty("imdbId") val imdbId: String? = null,
        @JsonProperty("tmdbId") val tmdbId: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("episode") val episode: Int? = null,
        @JsonProperty("year") val year: Int? = null
    )
}
