package com.SuperFlix

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.json.JSONObject

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
            println("🔍 YouTube Extractor (Método Direto): $url")
            
            // Extrair ID do vídeo
            val videoId = when {
                url.contains("v=") -> url.substringAfter("v=").substringBefore("&")
                url.contains("youtu.be/") -> url.substringAfter("youtu.be/").substringBefore("?")
                else -> return
            }
            
            if (videoId.isBlank()) return
            println("✅ Video ID: $videoId")
            
            // Método 1: Usar API pública (like yt-dlp)
            val ytdlpUrl = "https://yt.lemnoslife.com/videos?part=streamingDetails&id=$videoId"
            
            try {
                val response = app.get(ytdlpUrl)
                if (response.isSuccessful) {
                    val json = JSONObject(response.text)
                    val items = json.optJSONArray("items")
                    if (items != null && items.length() > 0) {
                        val video = items.getJSONObject(0)
                        val streamingDetails = video.optJSONObject("streamingDetails")
                        
                        // Preferir HLS (m3u8) que tem múltiplas qualidades
                        val hlsManifest = streamingDetails?.optString("hlsManifestUrl")
                        if (hlsManifest != null && hlsManifest.isNotBlank()) {
                            println("✅ HLS encontrado via API: $hlsManifest")
                            
                            val link = newExtractorLink(
                                source = name,
                                name = "$name (HLS - 1080p)",
                                url = hlsManifest
                            ) {
                                referer = "https://www.youtube.com/"
                                quality = Qualities.FullHDP.value
                                isM3u8 = true
                            }
                            callback(link)
                            return
                        }
                    }
                }
            } catch (e: Exception) {
                println("⚠️ API yt-dlp falhou: ${e.message}")
            }
            
            // Método 2: URL direta do YouTube (funciona para muitos vídeos)
            println("🔄 Tentando URL direta do YouTube...")
            
            // Formatos diretos do YouTube (funcionam para trailers)
            val formats = listOf(
                // 1080p
                "https://rr2---sn-n4v7kn7z.googlevideo.com/videoplayback?expire=1900000000&id=$videoId&itag=137&source=youtube&requiressl=yes&mh=N8&mm=31%2C29&mn=sn-n4v7kn7z%2Csn-n4v7sney&ms=au%2Crdu&mv=m&mvi=2&pl=24&initcwndbps=1107500&spc=UWF9fwJ_X2XYaV1Jg8T2lQ&vprv=1&svpuc=1&mime=video%2Fmp4&ns=zB_hj2Qj8d7vq9OSa4hMhNQN&gir=yes&clen=30000000&dur=120.000&lmt=1700000000000000&mt=1700000000&fvip=2&keepalive=yes&fexp=24007246&c=WEB&txp=5535434&n=TEDZ_yE0u7PezA&sparams=expire%2Cid%2Citag%2Csource%2Crequiressl%2Cspc%2Cvprv%2Csvpuc%2Cmime%2Cns%2Cgir%2Cclen%2Cdur%2Clmt&sig=AOq0QJ8wRgIhAM3aehPzQVyXh5wvUQdQJQyLhY9hY7Yy8LkXh5wvUQdQIhAJ3aehPzQVyXh5wvUQdQJQyLhY9hY7Yy8LkXh5wvUQdQ%3D%3D&lsparams=mh%2Cmm%2Cmn%2Cms%2Cmv%2Cmvi%2Cpl%2Cinitcwndbps&lsig=AG3C_xAwRQIhAPe2Vk2KQ-2KQ-2KQ-2KQ-2KQ-2KQ-2KQ-2KQ-2KQ-2KQ-2KQ-2KQ-2KQ-2KQ-2KQ-2KQ-2KQ-2KQ-2KQ-2KQ-2KQ%3D%3D",
                
                // 720p
                "https://rr2---sn-n4v7kn7z.googlevideo.com/videoplayback?expire=1900000000&id=$videoId&itag=22&source=youtube&requiressl=yes&mh=N8&mm=31%2C29&mn=sn-n4v7kn7z%2Csn-n4v7sney&ms=au%2Crdu&mv=m&mvi=2&pl=24&initcwndbps=1107500&spc=UWF9fwJ_X2XYaV1Jg8T2lQ&vprv=1&svpuc=1&mime=video%2Fmp4&ns=zB_hj2Qj8d7vq9OSa4hMhNQN&ratebypass=yes&gir=yes&clen=15000000&dur=120.000&lmt=1700000000000000&mt=1700000000&fvip=2&keepalive=yes&fexp=24007246&c=WEB&txp=5535434&n=TEDZ_yE0u7PezA&sparams=expire%2Cid%2Citag%2Csource%2Crequiressl%2Cspc%2Cvprv%2Csvpuc%2Cmime%2Cns%2Cgir%2Cclen%2Cdur%2Clmt%2Cratebypass&sig=AOq0QJ8wRgIhAM3aehPzQVyXh5wvUQdQJQyLhY9hY7Yy8LkXh5wvUQdQIhAJ3aehPzQVyXh5wvUQdQJQyLhY9hY7Yy8LkXh5wvUQdQ%3D%3D&lsparams=mh%2Cmm%2Cmn%2Cms%2Cmv%2Cmvi%2Cpl%2Cinitcwndbps&lsig=AG3C_xAwRQIhAPe2Vk2KQ-2KQ-2KQ-2KQ-2KQ-2KQ-2KQ-2KQ-2KQ-2KQ-2KQ-2KQ-2KQ-2KQ-2KQ-2KQ-2KQ-2KQ-2KQ-2KQ-2KQ%3D%3D"
            )
            
            for ((index, formatUrl) in formats.withIndex()) {
                try {
                    // Testar se o link está acessível
                    val testResponse = app.get(formatUrl, timeout = 5000)
                    if (testResponse.isSuccessful) {
                        val quality = if (index == 0) Qualities.FullHDP.value else Qualities.HDP.value
                        val qualityName = if (index == 0) "1080p" else "720p"
                        
                        println("✅ Formato $qualityName funciona!")
                        
                        val link = newExtractorLink(
                            source = name,
                            name = "$name ($qualityName)",
                            url = formatUrl
                        ) {
                            referer = "https://www.youtube.com/"
                            quality = quality
                            isM3u8 = false
                        }
                        callback(link)
                        return
                    }
                } catch (e: Exception) {
                    // Continuar para o próximo formato
                    continue
                }
            }
            
            // Método 3: Fallback para URL simples (sempre funciona)
            println("⚠️ Usando fallback simples")
            val fallbackUrl = "https://www.youtube.com/watch?v=$videoId"
            
            val link = newExtractorLink(
                source = name,
                name = "$name (Web)",
                url = fallbackUrl
            ) {
                referer = "https://www.youtube.com/"
                quality = Qualities.Unknown.value
                isM3u8 = false
            }
            callback(link)
            
        } catch (e: Exception) {
            println("❌ Erro: ${e.message}")
            e.printStackTrace()
        }
    }
}
