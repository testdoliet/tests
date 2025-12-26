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
            println("🔍 YouTube Extractor: $url")

            // Extrair ID do vídeo
            val videoId = Regex("(?:youtube\\.com/(?:watch\\?v=|embed/)|youtu\\.be/)([A-Za-z0-9_-]{11})")
                .find(url)?.groupValues?.get(1) ?: return

            println("✅ Video ID: $videoId")

            // 1. Tentar pegar da página do YouTube
            val pageResponse = app.get("https://www.youtube.com/watch?v=$videoId", headers = mapOf(
                "User-Agent" to userAgent
            ))
            val html = pageResponse.text

            // Extrair ytcfg
            val ytCfgJson = Regex("ytcfg\\.set\\(\\s*(\\{.*?\\})\\s*\\);")
                .find(html)?.groupValues?.get(1) ?: return

            val cfg = JSONObject(ytCfgJson)
            val apiKey = cfg.optString("INNERTUBE_API_KEY").takeIf { it.isNotEmpty() } ?: return

            // Fazer requisição à API do YouTube
            val apiUrl = "https://www.youtube.com/youtubei/v1/player?key=$apiKey"
            val jsonBody = """
            {
                "context": {
                    "client": {
                        "hl": "en",
                        "gl": "US",
                        "clientName": "WEB",
                        "clientVersion": "2.20241226.01.00",
                        "userAgent": "$userAgent"
                    }
                },
                "videoId": "$videoId"
            }
            """.trimIndent()

            val response = app.post(apiUrl, headers = mapOf(
                "Content-Type" to "application/json",
                "User-Agent" to userAgent
            ), json = jsonBody)

            if (!response.isSuccessful) return

            val playerJson = JSONObject(response.text)
            val streamingData = playerJson.optJSONObject("streamingData") ?: return
            val hlsUrl = streamingData.optString("hlsManifestUrl")

            if (hlsUrl.isNotBlank()) {
                println("✅ HLS encontrado: $hlsUrl")

                // EXATAMENTE como o AnimeFire faz
                val extractorLink = newExtractorLink(
                    source = name,
                    name = "$name (1080p HLS)",
                    url = hlsUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    // MESMO padrão do AnimeFire
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

            // Se não encontrar HLS, tentar formatos diretos
            val formatsArray = streamingData.optJSONArray("adaptiveFormats")
                ?: streamingData.optJSONArray("formats") ?: return

            // Mapa de itags para qualidade (como no AnimeFire)
            val itagQualityMap = mapOf(
                18 to 360,   // 360p
                22 to 720,   // 720p
                37 to 1080,  // 1080p
                59 to 480,   // 480p
                137 to 1080, // 1080p (DASH video)
                248 to 1080, // 1080p (webm)
                136 to 720,  // 720p (DASH video)
                247 to 720,  // 720p (webm)
            )

            var found = false
            for (i in 0 until formatsArray.length()) {
                try {
                    val format = formatsArray.getJSONObject(i)
                    val fUrl = format.optString("url")
                    if (fUrl.isNotBlank()) {
                        val itag = format.optInt("itag", 0)
                        val quality = itagQualityMap[itag] ?: 720 // padrão 720p
                        
                        val qualityLabel = when {
                            quality >= 1080 -> "FHD"
                            quality >= 720 -> "HD"
                            quality >= 480 -> "SD"
                            else -> "SD"
                        }

                        // EXATAMENTE como o AnimeFire faz
                        val extractorLink = newExtractorLink(
                            source = name,
                            name = "$name ($qualityLabel)",
                            url = fUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            // MESMO padrão do AnimeFire
                            this.referer = "https://www.youtube.com/"
                            this.quality = quality
                            this.headers = mapOf(
                                "Referer" to "https://www.youtube.com/",
                                "User-Agent" to userAgent,
                                "Origin" to "https://www.youtube.com"
                            )
                        }

                        callback(extractorLink)
                        found = true
                    }
                } catch (e: Exception) {
                    continue
                }
            }

            if (!found) {
                println("⚠️ Nenhum formato encontrado")
            }

        } catch (e: Exception) {
            println("❌ Erro YouTube Extractor: ${e.message}")
            e.printStackTrace()
        }
    }
}
