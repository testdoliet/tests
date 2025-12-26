package com.SuperFlix

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
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
            println("🔍 YouTube Extractor processando: $url")

            val videoId = Regex("(?:youtube\\.com/(?:watch\\?v=|embed/)|youtu\\.be/)([A-Za-z0-9_-]{11})")
                .find(url)?.groupValues?.get(1) ?: return

            val pageResponse = app.get("https://www.youtube.com/watch?v=$videoId")
            val html = pageResponse.text

            val ytCfgJson = Regex("ytcfg\\.set\\(\\s*(\\{.*?\\})\\s*\\);")
                .find(html)?.groupValues?.get(1) ?: return

            val cfg = JSONObject(ytCfgJson)
            val apiKey = cfg.optString("INNERTUBE_API_KEY").takeIf { it.isNotEmpty() } ?: return

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

            val requestHeaders = mapOf("Content-Type" to "application/json")

            val response = app.post(apiUrl, headers = requestHeaders, json = jsonBody)
            if (!response.isSuccessful) return

            val playerJson = JSONObject(response.text)
            val streamingData = playerJson.optJSONObject("streamingData") ?: return
            val hlsUrl = streamingData.optString("hlsManifestUrl")

            if (hlsUrl.isNotBlank()) {
                println("✅ HLS encontrado: $hlsUrl")

                val streamHeaders = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to "https://www.youtube.com/"
                )

                // Método 1: Usando o bloco lambda CORRETAMENTE
                val link = newExtractorLink(
                    source = name,
                    name = "$name (HLS)",
                    url = hlsUrl
                ) {
                    // Dentro do bloco, apenas configuramos propriedades
                    referer = "https://www.youtube.com/"
                    quality = Qualities.Unknown.value
                    headers = streamHeaders
                    isM3u8 = true
                }
                
                callback(link)
                
            } else {
                println("⚠️ Sem HLS, tentando extrair URLs diretas")
                
                val formatsArray = streamingData.optJSONArray("adaptiveFormats")
                    ?: streamingData.optJSONArray("formats") ?: return

                for (i in 0 until formatsArray.length()) {
                    val format = formatsArray.getJSONObject(i)
                    val fUrl = format.optString("url")
                    if (fUrl.isNotBlank()) {
                        val qualityLabel = format.optString("qualityLabel", "Unknown")
                        val bitrate = format.optInt("bitrate") / 1000
                        
                        // Extrair qualidade numérica
                        val qualityNum = when {
                            qualityLabel.contains("1080") || qualityLabel.contains("FHD") -> 1080
                            qualityLabel.contains("720") || qualityLabel.contains("HD") -> 720
                            qualityLabel.contains("480") || qualityLabel.contains("SD") -> 480
                            qualityLabel.contains("360") -> 360
                            qualityLabel.contains("240") -> 240
                            qualityLabel.contains("144") -> 144
                            else -> Qualities.Unknown.value
                        }
                        
                        // Método 2: Criar link direto sem bloco lambda (alternativa)
                        val link = newExtractorLink(
                            source = name,
                            name = "$name - $qualityLabel (${bitrate}kbps)",
                            url = fUrl,
                            referer = "https://www.youtube.com/",
                            quality = qualityNum,
                            headers = mapOf(
                                "User-Agent" to userAgent,
                                "Referer" to "https://www.youtube.com/"
                            ),
                            isM3u8 = fUrl.contains(".m3u8")
                        )
                        
                        callback(link)
                    }
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
