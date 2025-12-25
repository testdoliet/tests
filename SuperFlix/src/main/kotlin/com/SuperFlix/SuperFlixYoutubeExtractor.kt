package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app

object SuperFlixYoutubeExtractor {
    suspend fun getUrl(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🎬 YouTubeExtractor processando: $url")
            
            // Extrair ID do vídeo
            val videoId = extractYouTubeId(url) ?: return false
            
            println("📹 Video ID encontrado: $videoId")
            
            // Método principal: Usar serviços externos
            if (extractWithYtdlWeb(videoId, referer, subtitleCallback, callback)) {
                return true
            }
            
            // Fallback para embed
            extractWithYouTubeEmbed(videoId, referer, callback)
            
        } catch (e: Exception) {
            println("❌ YouTubeExtractor erro: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    private fun extractYouTubeId(url: String): String? {
        val patterns = listOf(
            "youtube\\.com/watch\\?v=([a-zA-Z0-9_-]{11})",
            "youtu\\.be/([a-zA-Z0-9_-]{11})",
            "youtube\\.com/embed/([a-zA-Z0-9_-]{11})",
            "v=([a-zA-Z0-9_-]{11})"
        )
        
        for (pattern in patterns) {
            Regex(pattern, RegexOption.IGNORE_CASE).find(url)?.let {
                return it.groupValues[1]
            }
        }
        return null
    }

    private suspend fun extractWithYtdlWeb(
        videoId: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // Serviços web para extrair links do YouTube
            val services = listOf(
                "https://yt.lemnoslife.com/videos?part=streamingDetails&id=$videoId",
                "https://pipedapi.kavin.rocks/streams/$videoId", // API do Piped
                "https://inv.riverside.rocks/api/v1/videos/$videoId" // API do Invidious
            )
            
            for (serviceUrl in services) {
                println("🔗 Tentando serviço: $serviceUrl")
                
                try {
                    val response = app.get(serviceUrl, timeout = 15000)
                    if (response.code == 200) {
                        val content = response.text
                        
                        // Tentar extrair URLs de vídeo
                        var found = false
                        
                        // Padrão para URLs MP4 diretas
                        val videoPatterns = listOf(
                            Regex(""""(https?://[^"\s]*googlevideo[^"\s]*videoplayback[^"\s]*)"""),
                            Regex(""""url":"(https?://[^"\s]*\.m3u8[^"\s]*)"""),
                            Regex(""""url":"(https?://[^"\s]*\.mp4[^"\s]*)"""),
                            Regex(""""videoUrl":"(https?://[^"\s]*)""")
                        )
                        
                        videoPatterns.forEach { pattern ->
                            pattern.findAll(content).forEach { match ->
                                val videoUrl = match.groupValues[1]
                                if (videoUrl.isNotBlank() && 
                                    (videoUrl.contains("googlevideo") || 
                                     videoUrl.contains(".m3u8") || 
                                     videoUrl.contains(".mp4"))) {
                                    
                                    println("✅ Link de vídeo encontrado: ${videoUrl.take(100)}...")
                                    
                                    // Determinar tipo
                                    val type = when {
                                        videoUrl.contains(".m3u8") -> LoadExtractorConfig.HLS
                                        videoUrl.contains(".mpd") -> LoadExtractorConfig.DASH
                                        else -> LoadExtractorConfig.VIDEO
                                    }
                                    
                                    // Determinar qualidade
                                    val quality = extractQualityFromUrl(videoUrl)
                                    val qualityName = when (quality) {
                                        144 -> "144p"
                                        240 -> "240p"
                                        360 -> "360p"
                                        480 -> "480p"
                                        720 -> "720p"
                                        1080 -> "1080p"
                                        1440 -> "1440p"
                                        2160 -> "4K"
                                        else -> "720p"
                                    }
                                    
                                    val headers = mapOf(
                                        "Referer" to referer,
                                        "User-Agent" to "Mozilla/5.0",
                                        "Origin" to "https://www.youtube.com"
                                    )
                                    
                                    val extractorLink = ExtractorLink(
                                        source = name,
                                        name = "YouTube ($qualityName)",
                                        url = videoUrl,
                                        referer = referer,
                                        quality = quality,
                                        isM3u8 = type == LoadExtractorConfig.HLS,
                                        headers = headers
                                    )
                                    
                                    callback(extractorLink)
                                    found = true
                                }
                            }
                        }
                        
                        // Tentar extrair legendas
                        try {
                            val subPattern = Regex(""""url":"(https?://[^"\s]*\.vtt[^"\s]*)""")
                            subPattern.findAll(content).forEach { match ->
                                val subUrl = match.groupValues[1]
                                if (subUrl.contains("timedtext")) {
                                    println("📝 Legenda encontrada: $subUrl")
                                    subtitleCallback(SubtitleFile("Português", subUrl))
                                }
                            }
                        } catch (e: Exception) {
                            // Ignorar erros de legendas
                        }
                        
                        if (found) return true
                    }
                } catch (e: Exception) {
                    println("⚠️ Serviço falhou: ${e.message}")
                    continue
                }
            }
            
            false
        } catch (e: Exception) {
            println("❌ ytdl web erro: ${e.message}")
            false
        }
    }

    private fun extractQualityFromUrl(url: String): Int {
        // Extrair qualidade da URL ou itag
        return when {
            url.contains("itag=17") || url.contains("/144/") -> 144
            url.contains("itag=18") || url.contains("/360/") -> 360
            url.contains("itag=22") || url.contains("/720/") -> 720
            url.contains("itag=37") || url.contains("/1080/") -> 1080
            url.contains("itag=313") || url.contains("/2160/") -> 2160
            url.contains("itag=271") || url.contains("/1440/") -> 1440
            url.contains("quality=hd1080") -> 1080
            url.contains("quality=hd720") -> 720
            url.contains("quality=large") -> 480
            url.contains("quality=medium") -> 360
            url.contains("quality=small") -> 240
            else -> 720 // Qualidade padrão
        }
    }

    private suspend fun extractWithYouTubeEmbed(
        videoId: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            // URL do embed do YouTube
            val embedUrl = "https://www.youtube.com/embed/$videoId"
            println("🔗 Usando YouTube Embed: $embedUrl")
            
            val extractorLink = ExtractorLink(
                source = "YouTubeEmbed",
                name = "YouTube (Embed)",
                url = embedUrl,
                referer = referer,
                quality = 720,
                isM3u8 = false
            )
            
            callback(extractorLink)
            return true
        } catch (e: Exception) {
            println("❌ Embed falhou: ${e.message}")
            return false
        }
    }

    // Variável de instância para o nome
    val name = "SuperFlixYouTube"
}
