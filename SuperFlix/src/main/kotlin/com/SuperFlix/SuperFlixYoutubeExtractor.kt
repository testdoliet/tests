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
            val videoId = Regex("""v=([A-Za-z0-9_-]{11})""").find(url)?.groupValues?.get(1) 
                ?: Regex("""youtu\.be/([A-Za-z0-9_-]{11})""").find(url)?.groupValues?.get(1) 
                ?: return

            println("🔍 YouTube Extractor: $videoId")

            // 1. Buscar página do vídeo
            val page = app.get(
                url = "https://www.youtube.com/watch?v=$videoId",
                headers = mapOf("User-Agent" to userAgent)
            ).text

            // 2. Extrair configurações
            val ytCfgJson = Regex("""ytcfg\.set\((\{.*?\})\);""").find(page)?.groupValues?.get(1) ?: return
            val cfg = JSONObject(ytCfgJson)
            val apiKey = cfg.optString("INNERTUBE_API_KEY").ifBlank { return }

            // 3. Fazer requisição para API do YouTube
            val apiUrl = "https://www.youtube.com/youtubei/v1/player?key=$apiKey"
            val jsonBody = """{"videoId":"$videoId","context":{"client":{"hl":"en","gl":"US","clientName":"WEB","clientVersion":"2.20241226.01.00"}}}"""

            // FORMA CORRETA baseada no exemplo que funciona
            val response = app.post(
                url = apiUrl,
                data = jsonBody.toByteArray(),  // ← CORREÇÃO AQUI: usar toByteArray()
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Content-Type" to "application/json",
                    "Accept" to "*/*"
                )
            )

            if (!response.isSuccessful) return

            // 4. Extrair URL M3U8
            val playerJson = JSONObject(response.text)
            val hlsUrl = playerJson.optJSONObject("streamingData")?.optString("hlsManifestUrl") ?: return

            if (hlsUrl.isBlank()) return

            println("✅ Encontrado stream M3U8: $hlsUrl")

            // 5. Gerar links M3U8 - EXATAMENTE como no exemplo funcional
            val headers = mapOf(
                "Referer" to "https://www.youtube.com/",
                "Origin" to "https://www.youtube.com",
                "User-Agent" to userAgent
            )

            M3u8Helper.generateM3u8(
                name,      // String - nome do extrator
                hlsUrl,    // String - URL do M3U8
                mainUrl,   // String - URL principal
                headers = headers  // Map<String, String> - headers (parâmetro nomeado)
            ).forEach(callback)

        } catch (e: Exception) {
            println("❌ Erro YouTube Extractor: ${e.message}")
        }
    }
}
