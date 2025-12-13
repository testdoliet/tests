package com.SuperFlix

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.metaproviders.TmdbLink
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import kotlinx.coroutines.runBlocking

class SuperFlix : TmdbProvider() {
    override var mainUrl = "https://superflix21.lol"
    override var name = "SuperFlix"
    override val hasMainPage = false
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val usesWebView = false
    override var lang = "pt-br"
    override val useMetaLoadResponse = true
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
        return try {
            val mediaData = AppUtils.parseJson<TmdbLink>(data).toLinkData()
            
            // Para teste, vamos retornar um link de exemplo baseado no título
            // Na implementação real, você buscaria no site SuperFlix
            val searchQuery = "${mediaData.title} ${mediaData.year}"
            println("🔍 [SuperFlix] Buscando: $searchQuery")
            
            // Exemplo simplificado - retorna um link de teste
            callback.invoke(
                newExtractorLink(
                    name,
                    "SuperFlix Stream",
                    url = "https://example.com/test.m3u8", // Substitua por link real
                    ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.P720.value
                }
            )
            
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    // Função auxiliar para encontrar player (não suspensa)
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
    
    // Função suspensa para extrair vídeo
    private suspend fun extractVideoFromUrl(playerUrl: String): String? {
        return try {
            val document = app.get(playerUrl).document
            
            // Lógica de extração real aqui
            // Por enquanto, retorna o próprio URL se for m3u8
            if (playerUrl.contains(".m3u8")) {
                playerUrl
            } else {
                // Tenta encontrar m3u8 na página
                val videoElement = document.selectFirst("video source[src*='.m3u8']")
                videoElement?.attr("src") ?: playerUrl
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun TmdbLink.toLinkData(): LinkData {
        return LinkData(
            imdbId = imdbID,
            tmdbId = tmdbID,
            title = movieName,
            season = season,
            episode = episode,
            year = movieYear
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
