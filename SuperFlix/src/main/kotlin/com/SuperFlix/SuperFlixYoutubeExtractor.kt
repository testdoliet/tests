package com.SuperFlix

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink  // ← Import obrigatório pro builder
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
            if (!response.isSuccessful) {
                println("❌ Erro na API: ${response.code}")
                return
            }

            val playerJson = JSONObject(response.text)
            val streamingData = playerJson.optJSONObject("streamingData") ?: return
            val hlsUrl = streamingData.optString("hlsManifestUrl")

            if (hlsUrl.isNotBlank()) {
                println("✅ HLS encontrado: $hlsUrl")

                val streamHeaders = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to "https://www.youtube.com/"
                )

                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = hlsUrl,
                    referer = mainUrl,
                    headers = streamHeaders
                ).forEach(callback)
            } else {
                println("⚠️ Sem HLS, usando fallback DASH/MP4")

                val formatsArray = streamingData.optJSONArray("adaptiveFormats")
                    ?: streamingData.optJSONArray("formats") ?: return

                val validFormats = mutableListOf<JSONObject>()
                for (i in 0 until formatsArray.length()) {
                    val f = formatsArray.getJSONObject(i)
                    val fUrl = f.optString("url")
                    if (fUrl.isNotBlank()) validFormats.add(f)
                }

                validFormats.sortByDescending { it.optInt("bitrate") }

                validFormats.take(6).forEach { format ->
                    val fUrl = format.optString("url")
                    val quality = format.optString("qualityLabel", "HD")
                    val bitrate = format.optInt("bitrate") / 1000

                    newExtractorLink {
                        this.source = name
                        this.name = "$name - \( quality ( \){bitrate}kbps)"
                        this.url = fUrl
                        this.referer = "https://www.youtube.com/"
                        this.quality = quality.toIntOrNull() ?: 1080
                        this.isM3u8 = false
                    }.let(callback)
                }
            }

        } catch (e: Exception) {
            println("❌ Erro: ${e.message}")
            e.printStackTrace()
        }
    }
}
