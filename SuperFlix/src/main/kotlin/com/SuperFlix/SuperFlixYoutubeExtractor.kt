package com.SuperFlix

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.RequestBody.Companion.toRequestBody
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
            "Accept-Language" to "en-US,en;q=0.9",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        )
    }

    private suspend fun getPageConfig(videoId: String): Map<String, String>? {
        return withContext(Dispatchers.IO) {
            try {
                val url = "https://www.youtube.com/watch?v=$videoId"
                
                val response = app.get(url, headers = HEADERS)
                
                if (response.isSuccessful) {
                    val html = response.text
                    extractYtCfg(html)
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun extractYtCfg(html: String): Map<String, String>? {
        return try {
            val pattern = Pattern.compile("ytcfg\\.set\\(\\s*(\\{.*?\\})\\s*\\)\\s*;")
            val matcher = pattern.matcher(html)
            
            if (matcher.find()) {
                val jsonStr = matcher.group(1)
                val json = JSONObject(jsonStr)
                
                val apiKey = json.optString("INNERTUBE_API_KEY")
                val clientVersion = json.optString("INNERTUBE_CLIENT_VERSION")
                val visitorData = json.optString("VISITOR_DATA")
                
                if (apiKey.isNotBlank() && clientVersion.isNotBlank()) {
                    mapOf(
                        "apiKey" to apiKey,
                        "clientVersion" to clientVersion,
                        "visitorData" to visitorData
                    )
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            println("🔍 YouTube Extractor: $url")
            
            // Extrair videoId
            val videoId = extractVideoId(url)
            if (videoId.isBlank()) {
                println("❌ Não consegui extrair videoId")
                return
            }
            
            println("✅ Video ID: $videoId")
            
            // Método 1: Usar API do YouTube
            val config = getPageConfig(videoId)
            
            if (config != null) {
                val apiKey = config["apiKey"]
                val clientVersion = config["clientVersion"]
                val visitorData = config["visitorData"]
                
                if (apiKey != null && clientVersion != null) {
                    val apiUrl = "https://www.youtube.com/youtubei/v1/player?key=$apiKey"
                    
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
                    
                    val requestBody = jsonBody.toRequestBody(
                        okhttp3.MediaType.parse("application/json; charset=utf-8")
                    )
                    
                    val response = app.post(
                        apiUrl,
                        headers = HEADERS,
                        requestBody = requestBody
                    )
                    
                    if (response.isSuccessful) {
                        val jsonResponse = JSONObject(response.text)
                        val streamingData = jsonResponse.optJSONObject("streamingData")
                        
                        if (streamingData != null) {
                            val hlsUrl = streamingData.optString("hlsManifestUrl")
                            
                            if (hlsUrl.isNotBlank()) {
                                println("✅ HLS URL encontrada: $hlsUrl")
                                
                                val link = newExtractorLink(
                                    source = name,
                                    name = "YouTube HLS",
                                    url = hlsUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    referer = mainUrl
                                    quality = 1080
                                }
                                
                                callback(link)
                                return
                            }
                        }
                    }
                }
            }
            
            // Método 2: Fallback - URL direta do YouTube
            println("⚠️ Usando fallback para URL direta")
            
            val youtubeUrl = "https://www.youtube.com/watch?v=$videoId"
            val link = newExtractorLink(
                source = name,
                name = "YouTube Video",
                url = youtubeUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                referer = mainUrl
                quality = 1080
            }
            
            callback(link)
            
        } catch (e: Exception) {
            println("❌ Erro no YouTube Extractor: ${e.message}")
        }
    }
    
    private fun extractVideoId(url: String): String {
        return try {
            val patterns = listOf(
                "v=([a-zA-Z0-9_-]{11})",
                "youtu\\.be/([a-zA-Z0-9_-]{11})",
                "/embed/([a-zA-Z0-9_-]{11})"
            )
            
            for (pattern in patterns) {
                val matcher = Pattern.compile(pattern).matcher(url)
                if (matcher.find()) {
                    return matcher.group(1) ?: ""
                }
            }
            
            // Se já for um ID
            if (url.length == 11 && url.matches(Regex("[a-zA-Z0-9_-]+"))) {
                return url
            }
            
            ""
        } catch (e: Exception) {
            ""
        }
    }
}
