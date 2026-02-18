package com.MendigoFlix

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.util.regex.Pattern

class YouTubeTrailerExtractor : ExtractorApi() {
    override val name = "YouTube"
    override val mainUrl = "https://www.youtube.com"
    override val requiresReferer = false

    private companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private val HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Accept-Encoding" to "gzip, deflate",
            "DNT" to "1",
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1"
        )
        
        // Itags estáveis de MP4 (mais compatíveis)
        private val STABLE_ITAGS = mapOf(
            // Resolução -> itag
            "144p" to 17,
            "240p" to 5,
            "360p" to 18,  // MAIS ESTÁVEL - funciona sempre
            "480p" to 59,
            "720p" to 22,  // Bom para 720p
            "720p60" to 91,
            "1080p" to 37, // 1080p MP4
            "1080p60" to 96,
            "4K" to 138,
            "4K60" to 137
        )
    }

    private fun debugLog(message: String) {
        println("🔍 YouTubeExt: $message")
    }

    private suspend fun getVideoInfo(videoId: String): JSONObject? {
        return withContext(Dispatchers.IO) {
            try {
                // Primeiro tenta pegar informações básicas via embed
                val embedUrl = "https://www.youtube.com/embed/$videoId"
                debugLog("Trying embed URL: $embedUrl")
                
                val embedResponse = app.get(embedUrl, headers = HEADERS)
                if (embedResponse.isSuccessful) {
                    val html = embedResponse.text
                    
                    // Extrair dados do ytInitialPlayerResponse
                    val pattern = Pattern.compile("ytInitialPlayerResponse\\s*=\\s*(\\{.+?\\})\\s*;")
                    val matcher = pattern.matcher(html)
                    if (matcher.find()) {
                        return@withContext JSONObject(matcher.group(1))
                    }
                }
                
                // Fallback para watch page
                debugLog("Trying watch page")
                val watchUrl = "https://www.youtube.com/watch?v=$videoId"
                val watchResponse = app.get(watchUrl, headers = HEADERS)
                if (watchResponse.isSuccessful) {
                    val html = watchResponse.text
                    
                    // Tenta vários padrões comuns
                    val patterns = listOf(
                        "ytInitialPlayerResponse\\s*=\\s*(\\{.+?\\})\\s*;",
                        "var\\s+ytInitialPlayerResponse\\s*=\\s*(\\{.+?\\})\\s*;",
                        "\"player_response\":\"(.*?)\""
                    )
                    
                    for (patternStr in patterns) {
                        val pattern = Pattern.compile(patternStr)
                        val matcher = pattern.matcher(html)
                        if (matcher.find()) {
                            val match = matcher.group(1)
                            if (match.startsWith("{")) {
                                return@withContext JSONObject(match)
                            } else {
                                // Pode estar encoded
                                try {
                                    val decoded = java.net.URLDecoder.decode(match, "UTF-8")
                                    return@withContext JSONObject(decoded)
                                } catch (e: Exception) {
                                    continue
                                }
                            }
                        }
                    }
                }
                
                null
            } catch (e: Exception) {
                debugLog("Error in getVideoInfo: ${e.message}")
                null
            }
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            debugLog("Processing URL: $url")
            
            // Extrair videoId
            val videoId = extractVideoId(url)
            if (videoId.isEmpty()) {
                debugLog("Could not extract videoId")
                return
            }

            debugLog("Video ID: $videoId")

            // Obter informações do vídeo
            val videoInfo = getVideoInfo(videoId)
            if (videoInfo == null) {
                debugLog("Could not get video info")
                sendFallbackUrl(videoId, callback)
                return
            }

            // Extrair streamingData
            val streamingData = videoInfo.optJSONObject("streamingData")
            if (streamingData == null) {
                debugLog("No streamingData found")
                sendFallbackUrl(videoId, callback)
                return
            }

            // Extrair URLs de formato adaptativo (MP4/WebM direto)
            val formats = streamingData.optJSONArray("formats")
            val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
            
            val allFormats = mutableListOf<JSONObject>()
            
            formats?.let { 
                for (i in 0 until it.length()) {
                    it.optJSONObject(i)?.let { format -> allFormats.add(format) }
                }
            }
            
            adaptiveFormats?.let {
                for (i in 0 until it.length()) {
                    it.optJSONObject(i)?.let { format -> allFormats.add(format) }
                }
            }

            debugLog("Found ${allFormats.size} formats")

            // Procurar por formatos MP4 estáveis primeiro
            val stableLinks = mutableListOf<ExtractorLink>()
            
            for (format in allFormats) {
                try {
                    val itag = format.optInt("itag")
                    val mimeType = format.optString("mimeType", "").lowercase()
                    val urlStr = format.optString("url", "")
                    val contentLength = format.optLong("contentLength", 0)
                    
                    // Pular se não tiver URL
                    if (urlStr.isBlank()) continue
                    
                    // Filtrar apenas MP4 estáveis
                    if (mimeType.contains("mp4")) {
                        val quality = when (itag) {
                            18 -> "360p"  // Mais estável
                            22 -> "720p"
                            37 -> "1080p"
                            59 -> "480p"
                            17 -> "144p"
                            5 -> "240p"
                            else -> "MP4"
                        }
                        
                        val link = newExtractorLink(
                            source = name,
                            name = "YouTube $quality",
                            url = urlStr,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "https://www.youtube.com"
                            this.quality = getQualityNumber(quality)
                        }
                        
                        debugLog("Found MP4 format: itag=$itag, quality=$quality, size=${contentLength/1024/1024}MB")
                        stableLinks.add(link)
                    }
                } catch (e: Exception) {
                    debugLog("Error processing format: ${e.message}")
                }
            }

            // Se encontrou links MP4, enviá-los
            if (stableLinks.isNotEmpty()) {
                debugLog("Sending ${stableLinks.size} MP4 links")
                stableLinks.forEach { callback(it) }
                return
            }
            
            // Fallback para HLS se não encontrar MP4
            val hlsUrl = streamingData.optString("hlsManifestUrl", "")
            if (hlsUrl.isNotBlank()) {
                debugLog("No MP4 found, trying HLS: $hlsUrl")
                try {
                    val m3u8Links = com.lagradost.cloudstream3.utils.M3u8Helper2.generateM3u8(
                        name,
                        hlsUrl,
                        mainUrl
                    )
                    
                    m3u8Links?.forEach { link ->
                        callback(link)
                    }
                    
                    if (m3u8Links != null && m3u8Links.isNotEmpty()) {
                        return
                    }
                } catch (e: Exception) {
                    debugLog("HLS failed: ${e.message}")
                }
            }
            
            // Último fallback
            debugLog("All extraction methods failed, using direct URL")
            sendFallbackUrl(videoId, callback)
            
        } catch (e: Exception) {
            debugLog("Error: ${e.message}")
            e.printStackTrace()
            
            // Fallback em caso de erro
            try {
                val videoId = extractVideoId(url)
                if (videoId.isNotEmpty()) {
                    sendFallbackUrl(videoId, callback)
                }
            } catch (e2: Exception) {
                debugLog("Fallback also failed: ${e2.message}")
            }
        }
    }

    private fun getQualityNumber(qualityStr: String): Int {
        return when {
            qualityStr.contains("4K") -> 2160
            qualityStr.contains("1440") -> 1440
            qualityStr.contains("1080") -> 1080
            qualityStr.contains("720") -> 720
            qualityStr.contains("480") -> 480
            qualityStr.contains("360") -> 360
            qualityStr.contains("240") -> 240
            qualityStr.contains("144") -> 144
            else -> 720
        }
    }

    private suspend fun sendFallbackUrl(videoId: String, callback: (ExtractorLink) -> Unit) {
        debugLog("Using fallback URL")
        
        // URL direta do YouTube (o CloudStream pode processar)
        val youtubeUrl = "https://www.youtube.com/watch?v=$videoId"
        
        val link = newExtractorLink(
            source = name,
            name = "YouTube Player",
            url = youtubeUrl,
            type = ExtractorLinkType.VIDEO
        ) {
            this.referer = mainUrl
            quality = 1080
        }

        callback(link)
    }
    
    private fun extractVideoId(url: String): String {
        return try {
            // Padrão 1: v=ID
            val pattern1 = Pattern.compile("v=([a-zA-Z0-9_-]{11})")
            val matcher1 = pattern1.matcher(url)
            if (matcher1.find()) {
                return matcher1.group(1) ?: ""
            }
            
            // Padrão 2: youtu.be/ID
            val pattern2 = Pattern.compile("youtu\\.be/([a-zA-Z0-9_-]{11})")
            val matcher2 = pattern2.matcher(url)
            if (matcher2.find()) {
                return matcher2.group(1) ?: ""
            }
            
            // Padrão 3: /embed/ID
            val pattern3 = Pattern.compile("/embed/([a-zA-Z0-9_-]{11})")
            val matcher3 = pattern3.matcher(url)
            if (matcher3.find()) {
                return matcher3.group(1) ?: ""
            }
            
            // Se já for apenas um ID
            if (url.length == 11 && url.matches(Regex("[a-zA-Z0-9_-]+"))) {
                return url
            }
            
            ""
        } catch (e: Exception) {
            ""
        }
    }
}
