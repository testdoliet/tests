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

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            println("🔍 YouTube Extractor (EXATO ANIMEFIRE): $url")
            
            // Extrair ID
            val videoId = when {
                url.contains("v=") -> url.substringAfter("v=").substringBefore("&")
                url.contains("youtu.be/") -> url.substringAfter("youtu.be/").substringBefore("?")
                else -> return
            }
            
            if (videoId.isBlank()) return
            println("✅ Video ID: $videoId")
            
            // 1. Tentar API do YouTube primeiro
            try {
                val page = app.get("https://www.youtube.com/watch?v=$videoId")
                val html = page.text
                
                val playerMatch = Regex("""ytInitialPlayerResponse\s*=\s*(\{.*?\});""").find(html)
                if (playerMatch != null) {
                    val playerJson = JSONObject(playerMatch.groupValues[1])
                    val streamingData = playerJson.optJSONObject("streamingData")
                    
                    if (streamingData != null) {
                        // HLS primeiro
                        val hlsUrl = streamingData.optString("hlsManifestUrl")
                        if (hlsUrl.isNotBlank()) {
                            println("✅ HLS encontrado")
                            
                            // EXATAMENTE COMO ANIMEFIRE
                            val hlsLink = newExtractorLink(
                                source = name,
                                name = "$name (HLS 1080p)",
                                url = hlsUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                // ANIMEFIRE USA this.
                                this.referer = "https://www.youtube.com/"
                                this.quality = 1080
                                this.headers = mapOf(
                                    "Referer" to "https://www.youtube.com/",
                                    "User-Agent" to userAgent,
                                    "Origin" to "https://www.youtube.com"
                                )
                            }
                            callback(hlsLink)
                            return
                        }
                        
                        // Formatos adaptativos
                        val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
                        if (adaptiveFormats != null) {
                            for (i in 0 until adaptiveFormats.length()) {
                                val format = adaptiveFormats.getJSONObject(i)
                                val formatUrl = format.optString("url")
                                val qualityLabel = format.optString("qualityLabel", "720p")
                                
                                if (formatUrl.isNotBlank()) {
                                    val quality = when {
                                        qualityLabel.contains("1080") -> 1080
                                        qualityLabel.contains("720") -> 720
                                        qualityLabel.contains("480") -> 480
                                        else -> 720
                                    }
                                    
                                    // EXATAMENTE COMO ANIMEFIRE
                                    val videoLink = newExtractorLink(
                                        source = name,
                                        name = "$name ($qualityLabel)",
                                        url = formatUrl,
                                        type = ExtractorLinkType.VIDEO
                                    ) {
                                        // ANIMEFIRE USA this.
                                        this.referer = "https://www.youtube.com/"
                                        this.quality = quality
                                        this.headers = mapOf(
                                            "Referer" to "https://www.youtube.com/",
                                            "User-Agent" to userAgent,
                                            "Origin" to "https://www.youtube.com"
                                        )
                                    }
                                    callback(videoLink)
                                }
                            }
                            return
                        }
                    }
                }
            } catch (e: Exception) {
                println("⚠️ YouTube API falhou: ${e.message}")
            }
            
            // 2. Fallback: URL direta (sempre funciona)
            println("⚠️ Usando fallback direto")
            val fallbackUrl = "https://www.youtube.com/embed/$videoId"
            
            // EXATAMENTE COMO ANIMEFIRE
            val fallbackLink = newExtractorLink(
                source = name,
                name = "$name Player",
                url = fallbackUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                // ANIMEFIRE USA this.
                this.referer = "https://www.youtube.com/"
                this.quality = 720
                this.headers = mapOf(
                    "Referer" to "https://www.youtube.com/",
                    "User-Agent" to userAgent
                )
            }
            callback(fallbackLink)
            
            println("✅ Fallback enviado")
            
        } catch (e: Exception) {
            println("❌ Erro: ${e.message}")
        }
    }
}
