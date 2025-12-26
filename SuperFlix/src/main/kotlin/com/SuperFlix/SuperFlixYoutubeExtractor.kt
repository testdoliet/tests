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

            // Extrai o videoId de qualquer formato YouTube
            val videoId = Regex("(?:youtube\\.com/(?:watch\\?v=|embed/)|youtu\\.be/)([A-Za-z0-9_-]{11})")
                .find(url)?.groupValues?.get(1) ?: return

            // Baixa a página do vídeo para pegar o ytcfg (contém a API key)
            val pageResponse = app.get("https://www.youtube.com/watch?v=$videoId")
            val html = pageResponse.text

            val ytCfgJson = Regex("ytcfg\\.set\\(\\s*(\\{.*?\\})\\s*\\);")
                .find(html)?.groupValues?.get(1) ?: return

            val cfg = JSONObject(ytCfgJson)
            val apiKey = cfg.optString("INNERTUBE_API_KEY").takeIf { it.isNotEmpty() } ?: return

            // Monta a URL da API player
            val apiUrl = "https://www.youtube.com/youtubei/v1/player?key=$apiKey"

            // Corpo JSON da requisição (simulando cliente WEB)
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

            // Headers para indicar que estamos enviando JSON
            val requestHeaders = mapOf(
                "Content-Type" to "application/json",
                "User-Agent" to userAgent
            )

            // POST com json = (parâmetro correto para body JSON)
            val response = app.post(
                url = apiUrl,
                headers = requestHeaders,
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
                println("❌ Nenhum HLS encontrado na resposta")
                return
            }

            println("✅ HLS encontrado: $hlsUrl")

            // Headers para o player M3U8 (compatível com o seu exemplo que compila)
            val streamHeaders = mapOf(
                "User-Agent" to userAgent,
                "Referer" to "https://www.youtube.com/",
                "Origin" to "https://www.youtube.com"
            )

            M3u8Helper.generateM3u8(
                source = name,
                streamUrl = hlsUrl,
                referer = mainUrl,
                headers = streamHeaders
            ).forEach(callback)

            println("✅ Links M3U8 enviados ao player com sucesso")

        } catch (e: Exception) {
            println("❌ Erro no YouTube Extractor: ${e.message}")
            e.printStackTrace()
        }
    }
}
