package com.SuperFlix

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.app
import org.json.JSONObject

class YouTubeTrailerExtractor : ExtractorApi() {
    override val name = "YouTube HD"
    override val mainUrl = "https://www.youtube.com"
    override val requiresReferer = false

    private val userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15"
    private val headers = mapOf(
        "User-Agent" to userAgent,
        "Accept-Language" to "en-US,en;q=0.5"
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Extrai videoId de qualquer formato
        val videoId = Regex("(?:youtube\\.com/(?:watch\\?v=|embed/)|youtu\\.be/)([A-Za-z0-9_-]{11})")
            .find(url)?.groupValues?.get(1) ?: return

        try {
            // 1. Baixa a página do vídeo
            val pageUrl = "https://www.youtube.com/watch?v=$videoId"
            val pageResponse = app.get(pageUrl, headers = headers)
            val html = pageResponse.text

            // 2. Extrai ytcfg.set({...})
            val ytCfgMatch = Regex("ytcfg\\.set\\(\\s*(\\{.*?\\})\\s*\\);").find(html)
            val ytCfgJson = ytCfgMatch?.groupValues?.get(1) ?: return

            val cfg = JSONObject(ytCfgJson)

            val apiKey = cfg.optString("INNERTUBE_API_KEY")
            val clientVersion = cfg.optString("INNERTUBE_CLIENT_VERSION", "2.20241201.01.00")
            val visitorData = cfg.optString("VISITOR_DATA", "")

            if (apiKey.isEmpty()) return

            // 3. Monta POST para /youtubei/v1/player
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

            val playerResponse = JSONObject(response.text)
            val streamingData = playerResponse.optJSONObject("streamingData") ?: return
            val hlsUrl = streamingData.optString("hlsManifestUrl")

            if (hlsUrl.isNotEmpty()) {
                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = hlsUrl,
                    referer = "https://www.youtube.com/"
                ).forEach { link ->
                    callback(link)
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
            // Em caso de erro, deixa o CloudStream tentar outros extractors
        }
    }
}
