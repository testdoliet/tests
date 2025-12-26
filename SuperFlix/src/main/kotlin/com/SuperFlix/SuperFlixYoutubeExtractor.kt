package com.SuperFlix

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.SubtitleFile  // ← IMPORT CORRIGIDO
import com.lagradost.cloudstream3.app
import org.json.JSONObject

class YouTubeTrailerExtractor : ExtractorApi() {
    override val name = "YouTube HD"
    override val mainUrl = "https://www.youtube.com"
    override val requiresReferer = false

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    private val headers = mapOf(
        "User-Agent" to userAgent,
        "Accept-Language" to "en-US,en;q=0.9"
    )

    // Assinatura EXATA que o CloudStream espera em 2025
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Extrai o videoId
            val videoId = Regex("(?:youtube\\.com/(?:watch\\?v=|embed/)|youtu\\.be/)([A-Za-z0-9_-]{11})")
                .find(url)?.groupValues?.get(1) ?: return

            // Baixa a página do vídeo
            val pageResponse = app.get("https://www.youtube.com/watch?v=$videoId", headers = headers)
            val html = pageResponse.text

            // Extrai o objeto ytcfg.set({...})
            val ytCfgJson = Regex("ytcfg\\.set\\(\\s*(\\{.*?\\})\\s*\\);")
                .find(html)?.groupValues?.get(1) ?: return

            val cfg = JSONObject(ytCfgJson)
            val apiKey = cfg.optString("INNERTUBE_API_KEY").takeIf { it.isNotEmpty() } ?: return
            val clientVersion = cfg.optString("INNERTUBE_CLIENT_VERSION", "2.20241226.01.00")
            val visitorData = cfg.optString("VISITOR_DATA", "")

            // Monta a URL da API
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
                        "userAgent": "$userAgent"
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

            val response = app.post(
                url = apiUrl,
                headers = headers,
                data = jsonBody
            )

            if (!response.isSuccessful) return

            val playerJson = JSONObject(response.text)
            val streamingData = playerJson.optJSONObject("streamingData") ?: return
            val hlsUrl = streamingData.optString("hlsManifestUrl")
            if (hlsUrl.isBlank()) return

            // CORREÇÃO DO ERRO: headers é Map<String, String>
            M3u8Helper.generateM3u8(
                source = name,
                streamUrl = hlsUrl,
                referer = "https://www.youtube.com/",
                headers = headers  // ← Agora é Map, não String
            ).forEach { link ->
                callback(link)
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
