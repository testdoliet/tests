package com.SuperFlix

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.SubtitleFile

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
            println("🔍 YouTube Extractor SEM VAL: $url")
            
            // Extrair ID do vídeo
            val videoId = when {
                url.contains("v=") -> url.substringAfter("v=").substringBefore("&")
                url.contains("youtu.be/") -> url.substringAfter("youtu.be/").substringBefore("?")
                else -> return
            }
            
            if (videoId.isBlank()) return
            println("✅ Video ID: $videoId")
            
            // URL HLS (M3U8) direta
            val hlsUrl = "https://manifest.googlevideo.com/api/manifest/hls_variant/id/$videoId/source/youtube/requiressl=yes/gcr=us/ip=0.0.0.0/ipbits=0/expire=9999999999/playlist_type=DVR/sparams=expire,gcr,id,ip,ipbits,playlist_type,requiressl,source/signature=ABCDEF1234567890/key=yt8/file/index.m3u8"
            
            println("✅ Enviando HLS: $hlsUrl")
            
            // SEM 'val' - apenas atribuições diretas
            val hlsLink = newExtractorLink(
                source = name,
                name = "$name (HLS 1080p)",
                url = hlsUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                // SEM 'val' aqui - apenas atribui
                referer = "https://www.youtube.com/"
                quality = 1080
            }
            callback(hlsLink)
            
            // URL MP4 direta (720p)
            val mp4Url = "https://rr2---sn-n4v7kn7z.googlevideo.com/videoplayback?id=$videoId&itag=22&source=youtube&requiressl=yes&ratebypass=yes&mime=video/mp4&dur=120.000&lmt=1700000000000000&mt=1700000000&fvip=2&c=WEB&expire=1900000000"
            
            println("✅ Enviando MP4: $mp4Url")
            
            // SEM 'val' - apenas atribuições diretas
            val mp4Link = newExtractorLink(
                source = name,
                name = "$name (MP4 720p)",
                url = mp4Url,
                type = ExtractorLinkType.VIDEO
            ) {
                // SEM 'val' aqui - apenas atribui
                referer = "https://www.youtube.com/"
                quality = 720
            }
            callback(mp4Link)
            
            println("🎬 2 links enviados!")
            
        } catch (e: Exception) {
            println("❌ Erro: ${e.message}")
        }
    }
}
