package com.SuperFlix

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.M3u8Helper2
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.regex.Pattern

class YoutubeExtractor : ExtractorApi() {
    override val name = "YouTube"
    override val mainUrl = "https://www.youtube.com"
    override val requiresReferer = false

    private companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15"
        private val HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept-Language" to "en-US,en;q=0.5"
        )
        private const val DEBUG_TAG = "youtubeExt"
    }

    private fun debugLog(message: String) {
        println("[$DEBUG_TAG] $message")
    }

    private fun extractYtCfg(html: String): JSONObject? {
        return try {
            val pattern = Pattern.compile("ytcfg\\.set\\(\\s*(\\{.*?\\})\\s*\\)\\s*;")
            val matcher = pattern.matcher(html)
            if (matcher.find()) {
                JSONObject(matcher.group(1))
            } else {
                null
            }
        } catch (e: Exception) {
            debugLog("ytcfg hatası: ${e.message}")
            null
        }
    }

    private suspend fun getPageConfig(videoId: String? = null): Map<String, String>? {
        return withContext(Dispatchers.IO) {
            try {
                val url = if (videoId != null) {
                    "https://www.youtube.com/watch?v=$videoId"
                } else {
                    "https://www.youtube.com"
                }

                debugLog("Fetching page: $url")
                val response = app.get(url, headers = HEADERS)
                
                if (response.isSuccessful) {
                    val html = response.text
                    val ytCfg = extractYtCfg(html)
                    
                    if (ytCfg != null) {
                        val apiKey = ytCfg.optString("INNERTUBE_API_KEY")
                        val clientVersion = ytCfg.optString("INNERTUBE_CLIENT_VERSION")
                        val visitorData = ytCfg.optString("VISITOR_DATA")
                        
                        if (apiKey.isNotBlank() && clientVersion.isNotBlank()) {
                            mapOf(
                                "apiKey" to apiKey,
                                "clientVersion" to clientVersion,
                                "visitorData" to visitorData
                            )
                        } else {
                            debugLog("API key or client version not found in ytcfg")
                            null
                        }
                    } else {
                        debugLog("ytcfg not found in page")
                        null
                    }
                } else {
                    debugLog("Failed to fetch page: ${response.code}")
                    null
                }
            } catch (e: Exception) {
                debugLog("Error in getPageConfig: ${e.message}")
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
            
            // Extrair videoId da URL
            val videoId = extractVideoId(url)
            if (videoId.isBlank()) {
                debugLog("Could not extract videoId from URL")
                return
            }
            
            debugLog("Video ID: $videoId")
            
            // Obter configuração da página
            val config = getPageConfig(videoId)
            if (config == null) {
                debugLog("Failed to get page config")
                return
            }
            
            val apiKey = config["apiKey"]
            val clientVersion = config["clientVersion"]
            val visitorData = config["visitorData"]
            
            if (apiKey.isNullOrBlank() || clientVersion.isNullOrBlank()) {
                debugLog("Missing API key or client version")
                return
            }
            
            // URL da API do YouTube
            val apiUrl = "https://www.youtube.com/youtubei/v1/player?key=$apiKey"
            
            // Corpo da requisição JSON
            val jsonBody = """
            {
                "context": {
                    "client": {
                        "hl": "en",
                        "gl": "US",
                        "clientName": "WEB",
                        "clientVersion": "$clientVersion",
                        "visitorData": "$visitorData",
                        "platform": "DESKTOP",
                        "userAgent": "$USER_AGENT"
                    }
                },
                "videoId": "$videoId",
                "playbackContext": {
                    "contentPlaybackContext": {
                        "html5Preference": "HTML5_PREF_WANTS"
                    }
                }
            }
            """.trimIndent()
            
            debugLog("Making API request to: $apiUrl")
            
            // Fazer requisição para a API do YouTube
            val response: NiceResponse = app.post(
                apiUrl,
                headers = HEADERS,
                data = jsonBody,
                requestBody = true
            )
            
            if (response.isSuccessful) {
                val jsonResponse = JSONObject(response.text)
                val streamingData = jsonResponse.optJSONObject("streamingData")
                
                if (streamingData != null) {
                    val hlsUrl = streamingData.optString("hlsManifestUrl")
                    
                    if (hlsUrl.isNotBlank()) {
                        debugLog("HLS URL found: $hlsUrl")
                        
                        // Usar o M3u8Helper2 para processar o stream HLS
                        val m3u8Links = M3u8Helper2.generateM3u8(
                            "Youtube",
                            hlsUrl,
                            mainUrl,
                            null,
                            null,
                            null
                        )
                        
                        m3u8Links?.forEach { link ->
                            callback(link)
                        }
                    } else {
                        debugLog("No HLS URL in streamingData")
                        
                        // Tentar fallback para formatos adaptativos
                        val formats = streamingData.optJSONArray("formats")
                        val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
                        
                        processFormats(formats, callback)
                        processFormats(adaptiveFormats, callback)
                    }
                } else {
                    debugLog("No streamingData in response")
                }
            } else {
                debugLog("API request failed: ${response.code}")
            }
            
        } catch (e: Exception) {
            debugLog("Error in getUrl: ${e.message}")
            e.printStackTrace()
        }
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
            
            // Padrão 3: apenas o ID (11 caracteres)
            if (url.length == 11 && url.matches(Regex("[a-zA-Z0-9_-]+"))) {
                return url
            }
            
            // Padrão 4: último segmento da URL
            url.substringAfterLast("/")
        } catch (e: Exception) {
            ""
        }
    }
    
    private fun processFormats(formats: org.json.JSONArray?, callback: (ExtractorLink) -> Unit) {
        formats?.let {
            for (i in 0 until it.length()) {
                try {
                    val format = it.getJSONObject(i)
                    val url = format.optString("url")
                    val mimeType = format.optString("mimeType")
                    val qualityLabel = format.optString("qualityLabel")
                    
                    if (url.isNotBlank() && mimeType.contains("video")) {
                        val quality = when {
                            qualityLabel.contains("1080") -> 1080
                            qualityLabel.contains("720") -> 720
                            qualityLabel.contains("480") -> 480
                            qualityLabel.contains("360") -> 360
                            else -> 720
                        }
                        
                        val link = newExtractorLink(
                            source = name,
                            name = "YouTube ($qualityLabel)",
                            url = url,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            referer = mainUrl
                            this.quality = quality
                        }
                        
                        callback(link)
                    }
                } catch (e: Exception) {
                    debugLog("Error processing format: ${e.message}")
                }
            }
        }
    }
}
