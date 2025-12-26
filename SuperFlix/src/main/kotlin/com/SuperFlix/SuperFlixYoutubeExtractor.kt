package com.SuperFlix

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app

class YouTubeTrailerExtractor : ExtractorApi() {
    override val name = "YouTube Trailer"
    override val mainUrl = "https://www.youtube.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            println("🔍 YouTube Trailer Extractor: $url")
            
            // Extrair ID
            val videoId = url.substringAfter("v=").substringBefore("&")
            if (videoId.isBlank()) return
            
            // MÉTODO que funciona: Usar o player do YouTube via iframe
            // O CloudStream tem suporte nativo para URLs do YouTube
            val youtubeUrl = "https://www.youtube.com/watch?v=$videoId"
            
            // Retornar a URL original do YouTube
            // O CloudStream saberá lidar com ela usando seu player interno
            val link = newExtractorLink(
                source = name,
                name = "YouTube Player",
                url = youtubeUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                // Configuração mínima
                referer = "https://www.youtube.com/"
                quality = 1080
            }
            
            callback(link)
            println("✅ YouTube link enviado")
            
        } catch (e: Exception) {
            println("❌ Erro: ${e.message}")
        }
    }
}
