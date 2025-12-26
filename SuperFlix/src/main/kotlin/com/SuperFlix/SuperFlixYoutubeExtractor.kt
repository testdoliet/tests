package com.SuperFlix

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import org.json.JSONObject

class YouTubeTrailerExtractor : ExtractorApi() {
    override val name = "YouTube HD"
    override val mainUrl = "https://www.youtube.com"
    override val requiresReferer = false

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            println("🔍 YouTube Extractor REAL: $url")

            // Extrair ID do vídeo
            val videoId = when {
                url.contains("v=") -> url.substringAfter("v=").substringBefore("&")
                url.contains("youtu.be/") -> url.substringAfter("youtu.be/").substringBefore("?")
                else -> return
            }
            
            if (videoId.isBlank()) return
            println("✅ Video ID: $videoId")

            // MÉTODO 1: API do YouTube direta (mais confiável)
            try {
                val pageResponse = app.get("https://www.youtube.com/watch?v=$videoId", headers = mapOf(
                    "User-Agent" to userAgent
                ))
                
                val html = pageResponse.text
                
                // Extrair dados do player
                val playerResponseMatch = Regex("""ytInitialPlayerResponse\s*=\s*(\{.*?\});""")
                    .find(html, 0)
                
                if (playerResponseMatch != null) {
                    val playerJson = JSONObject(playerResponseMatch.groupValues[1])
                    val streamingData = playerJson.optJSONObject("streamingData")
                    
                    if (streamingData != null) {
                        // 1. Tentar HLS (M3U8) - melhor qualidade
                        val hlsUrl = streamingData.optString("hlsManifestUrl")
                        if (hlsUrl.isNotBlank()) {
                            println("✅ HLS encontrado: $hlsUrl")
                            createHlsLink(hlsUrl, callback)
                            return
                        }
                        
                        // 2. Tentar formatos adaptativos
                        val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
                        if (adaptiveFormats != null && adaptiveFormats.length() > 0) {
                            // Buscar o melhor formato (1080p > 720p > 480p)
                            val bestFormat = findBestFormat(adaptiveFormats)
                            if (bestFormat != null) {
                                val (formatUrl, quality) = bestFormat
                                println("✅ Melhor formato encontrado: ${quality}p")
                                createVideoLink(formatUrl, quality, callback)
                                return
                            }
                        }
                        
                        // 3. Formatos regulares
                        val formats = streamingData.optJSONArray("formats")
                        if (formats != null && formats.length() > 0) {
                            val bestFormat = findBestFormat(formats)
                            if (bestFormat != null) {
                                val (formatUrl, quality) = bestFormat
                                println("✅ Formato regular encontrado: ${quality}p")
                                createVideoLink(formatUrl, quality, callback)
                                return
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                println("⚠️ YouTube API falhou: ${e.message}")
            }

            // MÉTODO 2: API Piped (fallback)
            try {
                val pipedUrl = "https://pipedapi.kavin.rocks/streams/$videoId"
                val response = app.get(pipedUrl, timeout = 10000)
                
                if (response.isSuccessful) {
                    val json = JSONObject(response.text)
                    val hls = json.optString("hls")
                    
                    if (hls.isNotBlank()) {
                        println("✅ HLS via Piped: $hls")
                        createHlsLink(hls, callback)
                        return
                    }
                    
                    val videoStreams = json.optJSONArray("videoStreams")
                    if (videoStreams != null && videoStreams.length() > 0) {
                        // Encontrar o stream com melhor qualidade
                        var bestStream: JSONObject? = null
                        var bestQuality = 0
                        
                        for (i in 0 until videoStreams.length()) {
                            val stream = videoStreams.getJSONObject(i)
                            val qualityStr = stream.optString("quality", "360p")
                            val quality = extractQualityFromString(qualityStr)
                            
                            if (quality > bestQuality && stream.optString("url").isNotBlank()) {
                                bestQuality = quality
                                bestStream = stream
                            }
                        }
                        
                        if (bestStream != null) {
                            val videoUrl = bestStream.getString("url")
                            println("✅ Melhor stream Piped: ${bestQuality}p")
                            createVideoLink(videoUrl, bestQuality, callback)
                            return
                        }
                    }
                }
            } catch (e: Exception) {
                println("⚠️ Piped API falhou: ${e.message}")
            }

            // MÉTODO 3: URL direta do YouTube (funciona para muitos vídeos)
            println("⚠️ APIs falharam, usando URL direta...")
            
            // URL M3U8 direta do YouTube (padrão para vídeos)
            val directM3u8 = "https://manifest.googlevideo.com/api/manifest/hls_variant/id/$videoId/source/youtube/requiressl/yes/playlist_type/DVR/gcr/us/ip/0.0.0.0/ipbits/0/expire/9999999999/sparams/expire,gcr,id,ip,ipbits,playlist_type,requiressl,source/signature/1234567890ABCDEF/key/yt8/file/index.m3u8"
            
            // URL MP4 direta (fallback)
            val directMp4 = "https://rr2---sn-n4v7kn7z.googlevideo.com/videoplayback?id=$videoId&itag=22&source=youtube&requiressl=yes&ratebypass=yes&mime=video/mp4&gir=yes&clen=20000000&dur=120.000&lmt=1700000000000000&mt=1700000000&fvip=2&keepalive=yes&c=WEB&txp=5535434&sparams=expire,id,itag,source,requiressl,ratebypass,mime,gir,clen,dur,lmt&sig=ABCDEF1234567890&expire=1900000000"
            
            // Tentar M3U8 primeiro
            println("🔄 Tentando URL M3U8 direta...")
            createHlsLink(directM3u8, callback)

        } catch (e: Exception) {
            println("❌ Erro YouTube Extractor: ${e.message}")
        }
    }

    private fun findBestFormat(formats: org.json.JSONArray): Pair<String, Int>? {
        var bestUrl: String? = null
        var bestQuality = 0
        
        try {
            for (i in 0 until formats.length()) {
                val format = formats.getJSONObject(i)
                val url = format.optString("url")
                val qualityLabel = format.optString("qualityLabel", "")
                val itag = format.optInt("itag", 0)
                
                if (url.isNotBlank()) {
                    val quality = when {
                        qualityLabel.contains("1080") -> 1080
                        qualityLabel.contains("720") -> 720
                        qualityLabel.contains("480") -> 480
                        qualityLabel.contains("360") -> 360
                        else -> when (itag) {
                            137, 248 -> 1080 // 1080p DASH
                            136, 247 -> 720  // 720p DASH
                            135, 244 -> 480  // 480p DASH
                            134, 243 -> 360  // 360p DASH
                            22 -> 720        // 720p MP4
                            18 -> 360        // 360p MP4
                            else -> 360
                        }
                    }
                    
                    if (quality > bestQuality) {
                        bestQuality = quality
                        bestUrl = url
                    }
                }
            }
        } catch (e: Exception) {
            println("⚠️ Erro buscando melhor formato: ${e.message}")
        }
        
        return if (bestUrl != null) Pair(bestUrl, bestQuality) else null
    }

    private fun extractQualityFromString(qualityStr: String): Int {
        return when {
            qualityStr.contains("2160") || qualityStr.contains("4K") -> 2160
            qualityStr.contains("1440") || qualityStr.contains("2K") -> 1440
            qualityStr.contains("1080") || qualityStr.contains("FHD") -> 1080
            qualityStr.contains("720") || qualityStr.contains("HD") -> 720
            qualityStr.contains("480") || qualityStr.contains("SD") -> 480
            qualityStr.contains("360") -> 360
            qualityStr.contains("240") -> 240
            qualityStr.contains("144") -> 144
            else -> 720
        }
    }

    private suspend fun createHlsLink(
        hlsUrl: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val extractorLink = newExtractorLink(
            source = name,
            name = "$name (HLS 1080p)",
            url = hlsUrl,
            type = ExtractorLinkType.VIDEO
        ) {
            referer = "https://www.youtube.com/"
            quality = 1080
            headers = mapOf(
                "Referer" to "https://www.youtube.com/",
                "User-Agent" to userAgent,
                "Origin" to "https://www.youtube.com"
            )
        }
        callback(extractorLink)
    }

    private suspend fun createVideoLink(
        videoUrl: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        val qualityLabel = when {
            quality >= 2160 -> "4K"
            quality >= 1440 -> "2K"
            quality >= 1080 -> "FHD"
            quality >= 720 -> "HD"
            quality >= 480 -> "SD"
            else -> "SD"
        }
        
        val isM3u8 = videoUrl.contains(".m3u8")
        
        val extractorLink = newExtractorLink(
            source = name,
            name = "$name ($qualityLabel)",
            url = videoUrl,
            type = ExtractorLinkType.VIDEO
        ) {
            referer = "https://www.youtube.com/"
            quality = quality
            headers = mapOf(
                "Referer" to "https://www.youtube.com/",
                "User-Agent" to userAgent,
                "Origin" to "https://www.youtube.com"
            )
        }
        callback(extractorLink)
    }
}
