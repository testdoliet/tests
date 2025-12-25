package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import java.net.URI

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
            
            // Método 1: Usar ytdl web service (funciona melhor)
            if (extractWithYtdlWeb(videoId, referer, subtitleCallback, callback)) {
                return true
            }
            
            // Método 2: Fallback para embed simples
            extractWithYouTubeEmbed(videoId, referer, callback)
            
        } catch (e: Exception) {
            println("❌ YouTubeExtractor erro: ${e.message}")
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

    // MÉTODO MELHOR: Usar serviço web ytdl que já extrai todas as qualidades
    private suspend fun extractWithYtdlWeb(
        videoId: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // Serviços web que extraem todas as qualidades do YouTube
            val services = listOf(
                "https://yt.lemnoslife.com/videos?part=streamingDetails&id=$videoId",
                "https://ytdl.cutelab.space/video/$videoId",
                "https://ytdl.ari-web.xyz/watch?v=$videoId"
            )
            
            for (serviceUrl in services) {
                println("🔗 Tentando serviço: $serviceUrl")
                
                try {
                    val response = app.get(serviceUrl, timeout = 15000)
                    if (response.code == 200) {
                        val content = response.text
                        
                        // Extrair URLs M3U8 que contêm todas as qualidades
                        val m3u8Pattern = Regex("""(https?://[^"\s]*\.m3u8[^"\s]*)""")
                        val m3u8Matches = m3u8Pattern.findAll(content)
                        
                        var found = false
                        m3u8Matches.forEach { match ->
                            val m3u8Url = match.value
                            if (m3u8Url.contains("googlevideo") || m3u8Url.contains("youtube")) {
                                println("✅ Encontrado M3U8 master: $m3u8Url")
                                
                                // M3U8 master contém todas as qualidades
                                val headers = mapOf(
                                    "Referer" to referer,
                                    "User-Agent" to "Mozilla/5.0",
                                    "Origin" to "https://www.youtube.com"
                                )
                                
                                val extractorLink = newExtractorLink(
                                    source = "YouTube HLS",
                                    name = "Trailer YouTube (Múltiplas Qualidades)",
                                    url = m3u8Url,
                                    type = ExtractorLinkType.HLS
                                ) {
                                    this.referer = referer
                                    this.quality = Qualities.P1080.value // Qualidade padrão alta
                                    this.headers = headers
                                }
                                
                                callback(extractorLink)
                                found = true
                            }
                        }
                        
                        if (found) {
                            // Procurar também por links MP4 diretos (qualidades individuais)
                            extractDirectMP4Links(content, videoId, referer, callback)
                            return true
                        }
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

    // Extrair links MP4 diretos para qualidades específicas
    private suspend fun extractDirectMP4Links(
        content: String,
        videoId: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Padrão para URLs MP4 do YouTube
            val mp4Pattern = Regex("""(https?://[^"\s]*googlevideo[^"\s]*videoplayback[^"\s]*itag=\d+[^"\s]*)""")
            val matches = mp4Pattern.findAll(content)
            
            // Mapa de itag para qualidade
            val itagQualityMap = mapOf(
                // Qualidades baixas
                "17" to 144,  // 144p
                "18" to 360,  // 360p
                "43" to 360,  // 360p WebM
                // Qualidades médias
                "22" to 720,  // 720p
                "59" to 480,  // 480p
                "78" to 480,  // 480p
                // Qualidades altas
                "37" to 1080, // 1080p
                "137" to 1080, // 1080p (video only)
                "248" to 1080, // 1080p WebM
                // 4K
                "313" to 2160, // 2160p (4K)
                "315" to 2160, // 2160p (4K)
                "271" to 1440  // 1440p (2K)
            )
            
            matches.forEach { match ->
                val videoUrl = match.value
                // Extrair itag da URL
                val itagMatch = Regex("itag=(\\d+)").find(videoUrl)
                itagMatch?.let {
                    val itag = it.groupValues[1]
                    val quality = itagQualityMap[itag] ?: 720
                    
                    println("📹 MP4 encontrado: ${quality}p (itag: $itag)")
                    
                    val headers = mapOf(
                        "Referer" to referer,
                        "User-Agent" to "Mozilla/5.0",
                        "Origin" to "https://www.youtube.com"
                    )
                    
                    val extractorLink = newExtractorLink(
                        source = "YouTube Direct",
                        name = "Trailer YouTube (${quality}p)",
                        url = videoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = referer
                        this.quality = mapQualityToValue(quality)
                        this.headers = headers
                    }
                    
                    callback(extractorLink)
                }
            }
        } catch (e: Exception) {
            println("⚠️ Erro extraindo MP4: ${e.message}")
        }
    }

    // Método alternativo: Usar yt-dlp via API pública
    private suspend fun extractWithYtdlpApi(
        videoId: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val ytdlpApi = "https://ytdlp.deno.dev/?url=https://youtube.com/watch?v=$videoId"
            println("🔗 Usando yt-dlp API: $ytdlpApi")
            
            val response = app.get(ytdlpApi, timeout = 20000)
            if (response.code == 200) {
                val document = response.document
                
                // Procurar por links de vídeo
                val videoLinks = document.select("a[href*='googlevideo.com'], a[href*='.m3u8']")
                var found = false
                
                videoLinks.forEach { link ->
                    val href = link.attr("href")
                    val text = link.text()
                    
                    if (href.isNotBlank() && (href.contains("googlevideo") || href.contains(".m3u8"))) {
                        // Tentar extrair qualidade do texto
                        val quality = extractQualityFromText(text)
                        
                        println("🎥 yt-dlp encontrou: ${quality}p")
                        
                        val headers = mapOf(
                            "Referer" to referer,
                            "User-Agent" to "Mozilla/5.0",
                            "Origin" to "https://www.youtube.com"
                        )
                        
                        val isM3u8 = href.contains(".m3u8")
                        
                        val extractorLink = newExtractorLink(
                            source = "YouTube via yt-dlp",
                            name = "Trailer YouTube (${quality}p)",
                            url = href,
                            type = if (isM3u8) ExtractorLinkType.HLS else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = referer
                            this.quality = mapQualityToValue(quality)
                            this.headers = headers
                        }
                        
                        callback(extractorLink)
                        found = true
                    }
                }
                
                found
            } else {
                false
            }
        } catch (e: Exception) {
            println("❌ yt-dlp API erro: ${e.message}")
            false
        }
    }

    private fun extractQualityFromText(text: String): Int {
        return when {
            text.contains("144") -> 144
            text.contains("240") -> 240
            text.contains("360") -> 360
            text.contains("480") -> 480
            text.contains("720") -> 720
            text.contains("1080") -> 1080
            text.contains("1440") -> 1440
            text.contains("2160") || text.contains("4K") -> 2160
            else -> 720
        }
    }

    private suspend fun extractWithYouTubeEmbed(
        videoId: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Fallback: usar embed do YouTube
        val embedUrl = "https://www.youtube-nocookie.com/embed/$videoId?autoplay=1&rel=0"
        println("🔗 Fallback para YouTube Embed")
        
        val host = try {
            URI(referer).host ?: "www.youtube-nocookie.com"
        } catch (e: Exception) {
            "www.youtube-nocookie.com"
        }
        
        val headers = mapOf(
            "Referer" to referer,
            "User-Agent" to "Mozilla/5.0",
            "Origin" to "https://$host"
        )
        
        val extractorLink = newExtractorLink(
            source = "YouTube Embed",
            name = "Trailer YouTube (720p)",
            url = embedUrl,
            type = ExtractorLinkType.VIDEO
        ) {
            this.referer = referer
            this.quality = Qualities.P720.value
            this.headers = headers
        }
        
        callback(extractorLink)
        return true
    }

    private fun mapQualityToValue(quality: Int): Int {
        return when (quality) {
            144 -> Qualities.P144.value
            240 -> Qualities.P240.value
            360 -> Qualities.P360.value
            480 -> Qualities.P480.value
            720 -> Qualities.P720.value
            1080 -> Qualities.P1080.value
            1440 -> Qualities.P1440.value
            2160 -> Qualities.P2160.value
            else -> Qualities.Unknown.value
        }
    }
}
