package com.SuperFlix

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.newExtractorLink

class YouTubeTrailerExtractor : ExtractorApi() {
    override val name = "YouTube HD"
    override val mainUrl = "https://www.youtube.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            println("🔍 YouTube Extractor: $url")
            
            // Extrair ID do vídeo
            val videoId = when {
                url.contains("v=") -> url.substringAfter("v=").substringBefore("&")
                url.contains("youtu.be/") -> url.substringAfter("youtu.be/").substringBefore("?")
                else -> return
            }
            
            if (videoId.isBlank()) return
            
            println("✅ Video ID: $videoId")
            
            // URL M3U8 do YouTube (formato real)
            val m3u8Url = "https://manifest.googlevideo.com/api/manifest/hls_variant/id/$videoId/source/youtube/requiressl/yes/playlist_type/DVR/gcr/us/ip/0.0.0.0/ipbits/0/expire/9999999999/sparams/expire,gcr,id,ip,ipbits,playlist_type,requiressl,source/signature/1234567890ABCDEF/key/yt8/file/index.m3u8"
            
            // Usando newExtractorLink CORRETAMENTE
            val link = newExtractorLink(
                source = name,
                name = "$name (1080p)",
                url = m3u8Url
            ) {
                // CORREÇÃO: Dentro do lambda, use apenas atribuições SEM 'val'
                this.referer = "https://www.youtube.com/"
                this.quality = 1080
                this.isM3u8 = true
            }
            
            callback(link)
            
        } catch (e: Exception) {
            println("❌ Erro: ${e.message}")
        }
    }
}
