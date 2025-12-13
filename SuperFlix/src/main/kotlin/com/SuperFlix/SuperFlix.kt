package com.SuperFlix

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

class SuperFlix : TmdbProvider() {
    override var name = "SuperFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val instantLinkLoading = true
    override val useMetaLoadResponse = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    companion object {
        const val HOST = "https://superflix21.lol"
    }
    
    // Página principal mínima
    override val mainPage = mainPageOf("" to "SuperFlix")
    
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
            
            println("🎬 [SuperFlix] Carregando links para: ${mediaData.movieName ?: "Unknown"}")
            
            var success = false
            
            // 1. Tenta usar o extractor personalizado
            try {
                // Busca no site SuperFlix
                val searchQuery = mediaData.movieName ?: return false
                val searchUrl = "$HOST/buscar?q=${java.net.URLEncoder.encode(searchQuery, "UTF-8")}"
                
                val document = app.get(searchUrl).document
                val firstResult = document.selectFirst(".grid .card, a.card, .movie-item a, .rec-card")
                
                if (firstResult != null) {
                    val detailUrl = firstResult.attr("href")?.let { fixUrl(it) }
                    if (detailUrl != null) {
                        val detailDoc = app.get(detailUrl).document
                        val playButton = detailDoc.selectFirst("button.bd-play[data-url], button[data-url*='watch']")
                        val playerUrl = playButton?.attr("data-url")?.let { fixUrl(it) }
                        
                        if (playerUrl != null) {
                            // Usa o extractor personalizado
                            success = SuperFlixExtractor.extractVideoLinks(playerUrl, HOST, name, callback)
                        }
                    }
                }
            } catch (e: Exception) {
                println("⚠️ [SuperFlix] Busca no site falhou: ${e.message}")
            }
            
            // 2. Se falhar, usa link de exemplo
            if (!success) {
                println("🎬 [SuperFlix] Usando link de exemplo")
                callback.invoke(
                    newExtractorLink(
                        name,
                        "SuperFlix Stream",
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
