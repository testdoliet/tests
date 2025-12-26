package com.SuperFlix

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
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

            // Extrai o videoId
            val videoId = Regex("(?:youtube\\.com/(?:watch\\?v=|embed/)|youtu\\.be/)([A-Za-z0-9_-]{11})")
                .find(url)?.groupValues?.get(1) ?: return

            // Baixa a página do vídeo pra pegar ytcfg
            val pageResponse = app.get("https://www.youtube.com/watch?v=$videoId")
            val html = pageResponse.text

            val ytCfgJson = Regex("ytcfg\\.set\\(\\s*(\\{.*?\\})\\s*\\);")
                .find(html)?.groupValues?.get(1) ?: return

            val cfg = JSONObject(ytCfgJson)
            val apiKey = cfg.optString("INNERTUBE_API_KEY").takeIf { it.isNotEmpty() } ?: return

            // POST na API player
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

            val headers = mapOf("Content-Type" to "application/json")
            
            val response = app.post(
                apiUrl,
                json = jsonBody
            )
            if (!response.isSuccessful) {
                println("❌ Erro na API player: ${response.code}")
                return
            }

            val playerJson = JSONObject(response.text)
            val streamingData = playerJson.optJSONObject("streamingData") ?: return
            val hlsUrl = streamingData.optString("hlsManifestUrl")
            if (hlsUrl.isBlank()) {
                println("❌ Nenhum HLS encontrado")
                return
            }

            println("✅ Encontrado HLS: $hlsUrl")

            val headers = mapOf(
                "User-Agent" to userAgent,
                "Referer" to "https://www.youtube.com/",
                "Origin" to "https://www.youtube.com"
            )

            M3u8Helper.generateM3u8(
                name,
                hlsUrl,
                mainUrl,
                headers = headers
            ).forEach(callback)

            println("✅ Links M3U8 enviados ao player")

        } catch (e: Exception) {
            println("❌ Erro no YouTube Extractor: ${e.message}")
            e.printStackTrace()
        }
    }
}
