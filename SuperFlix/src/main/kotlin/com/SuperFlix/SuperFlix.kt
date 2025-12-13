package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.metaproviders.TmdbLink
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class SuperFlix : TmdbProvider() {
    override var mainUrl = "https://superflix21.lol"
    override var name = "SuperFlix"
    override val hasMainPage = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val mediaData = AppUtils.parseJson<TmdbLink>(data)
            
            // Log básico - mostra o que temos
            println("🔗 [SuperFlix] Processando: ${mediaData.movieName ?: "Unknown"}")
            println("🔗 [SuperFlix] TMDB ID: ${mediaData.tmdbID}")
            println("🔗 [SuperFlix] IMDB ID: ${mediaData.imdbID}")
            
            // Para debug, imprima o JSON completo
            println("🔗 [SuperFlix] Dados completos: $data")
            
            // Retorna link de teste - sempre o mesmo para simplificar
            callback(
                newExtractorLink(
                    name,
                    "SuperFlix Video",
                    "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
                    ExtractorLinkType.M3U8
                ) {
                    quality = Qualities.P720.value
                    referer = mainUrl
                }
            )
            
            return true
        } catch (e: Exception) {
            println("❌ [SuperFlix] Erro: ${e.message}")
            return false
        }
    }
}
