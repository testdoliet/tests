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
            println("🔍 YouTube Extractor (AnimeFire Style): $url")

            // Extrair ID do vídeo EXATAMENTE como no AnimeFire
            val videoId = when {
                url.contains("v=") -> url.substringAfter("v=").substringBefore("&")
                url.contains("youtu.be/") -> url.substringAfter("youtu.be/").substringBefore("?")
                else -> return
            }
            
            if (videoId.isBlank()) return
            println("✅ Video ID: $videoId")

            // 1. Primeiro tentar API do Piped (funciona como yt-dlp)
            val pipedUrl = "https://pipedapi.kavin.rocks/streams/$videoId"
            
            try {
                val response = app.get(pipedUrl, headers = mapOf(
                    "User-Agent" to userAgent
                ), timeout = 10000)
                
                if (response.isSuccessful) {
                    val json = JSONObject(response.text)
                    
                    // HLS tem prioridade (como no AnimeFire)
                    val hls = json.optString("hls")
                    if (hls.isNotBlank()) {
                        println("✅ HLS encontrado via Piped API")
                        
                        // EXATAMENTE como o AnimeFire faz (linha 71 do código deles)
                        val extractorLink = newExtractorLink(
                            source = name,
                            name = "$name (HLS 1080p)",
                            url = hls,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            // MESMO PADRÃO DO ANIMEFIRE
                            this.referer = "https://www.youtube.com/"
                            this.quality = 1080
                            this.headers = mapOf(
                                "Referer" to "https://www.youtube.com/",
                                "User-Agent" to userAgent,
                                "Origin" to "https://www.youtube.com"
                            )
                        }
                        
                        callback(extractorLink)
                        return
                    }
                    
                    // Se não tiver HLS, pegar vídeo direto
                    val videoStreams = json.optJSONArray("videoStreams")
                    if (videoStreams != null && videoStreams.length() > 0) {
                        for (i in 0 until videoStreams.length()) {
                            val stream = videoStreams.getJSONObject(i)
                            val videoUrl = stream.optString("url")
                            val qualityStr = stream.optString("quality", "720p")
                            
                            if (videoUrl.isNotBlank()) {
                                val quality = extractQualityFromString(qualityStr)
                                println("✅ Vídeo direto encontrado: $quality")
                                
                                val extractorLink = newExtractorLink(
                                    source = name,
                                    name = "$name ($quality)",
                                    url = videoUrl,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    // MESMO PADRÃO DO ANIMEFIRE
                                    this.referer = "https://www.youtube.com/"
                                    this.quality = quality
                                    this.headers = mapOf(
                                        "Referer" to "https://www.youtube.com/",
                                        "User-Agent" to userAgent,
                                        "Origin" to "https://www.youtube.com"
                                    )
                                }
                                
                                callback(extractorLink)
                            }
                        }
                        return
                    }
                }
            } catch (e: Exception) {
                println("⚠️ Piped API falhou: ${e.message}")
            }

            // 2. Fallback: API alternativa (Invidious)
            try {
                val invidiousUrl = "https://inv.riverside.rocks/api/v1/videos/$videoId"
                val response = app.get(invidiousUrl, headers = mapOf(
                    "User-Agent" to userAgent
                ))
                
                if (response.isSuccessful) {
                    val json = JSONObject(response.text)
                    
                    // Formatos adaptativos
                    val adaptiveFormats = json.optJSONArray("adaptiveFormats")
                    if (adaptiveFormats != null && adaptiveFormats.length() > 0) {
                        for (i in 0 until adaptiveFormats.length()) {
                            val format = adaptiveFormats.getJSONObject(i)
                            val formatUrl = format.optString("url")
                            val type = format.optString("type", "")
                            
                            if (formatUrl.isNotBlank() && (type.contains("video/mp4") || type.contains("video/webm"))) {
                                val qualityLabel = format.optString("qualityLabel", "720p")
                                val quality = extractQualityFromString(qualityLabel)
                                
                                val extractorLink = newExtractorLink(
                                    source = name,
                                    name = "$name ($quality)",
                                    url = formatUrl,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    // MESMO PADRÃO DO ANIMEFIRE
                                    this.referer = "https://www.youtube.com/"
                                    this.quality = quality
                                    this.headers = mapOf(
                                        "Referer" to "https://www.youtube.com/",
                                        "User-Agent" to userAgent,
                                        "Origin" to "https://www.youtube.com"
                                    )
                                }
                                
                                callback(extractorLink)
                            }
                        }
                        return
                    }
                }
            } catch (e: Exception) {
                println("⚠️ Invidious API falhou: ${e.message}")
            }

            // 3. Fallback final: URL do embed (sempre funciona)
            println("⚠️ Usando fallback do embed")
            val embedUrl = "https://www.youtube.com/embed/$videoId"
            
            val extractorLink = newExtractorLink(
                source = name,
                name = "$name (Embed)",
                url = embedUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                // MESMO PADRÃO DO ANIMEFIRE
                this.referer = "https://www.youtube.com/"
                this.quality = 720
                this.headers = mapOf(
                    "Referer" to "https://www.youtube.com/",
                    "User-Agent" to userAgent
                )
            }
            
            callback(extractorLink)
            println("✅ Fallback enviado")

        } catch (e: Exception) {
            println("❌ Erro YouTube Extractor: ${e.message}")
            e.printStackTrace()
        }
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
}
