package com.SuperFlix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

object SuperFlixExtractor {
    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // HEADERS ANTI-ADS - Solução 4
            val antiAdHeaders = mapOf(
                "Referer" to mainUrl,
                "Origin" to mainUrl,
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "DNT" to "1", // Do Not Track - sinaliza que não quer ser rastreado
                "Accept" to "video/*,*/*;q=0.8", // Prefere conteúdo de vídeo
                "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
                "Sec-Fetch-Dest" to "video", // Indica que queremos vídeo
                "Sec-Fetch-Mode" to "no-cors", // Modo CORS relaxado
                "Sec-Fetch-Site" to "cross-site",
                "Upgrade-Insecure-Requests" to "1",
                "Cache-Control" to "no-cache",
                "Pragma" to "no-cache"
            )
            
            // Regex para detectar URLs de vídeo
            val videoRegex = Regex("""\.(m3u8|mp4|mkv)(\?.*)?$""")
            
            val streamResolver = WebViewResolver(
                interceptUrl = videoRegex,
                useOkhttp = false,
                timeout = 10_000L
            )

            // Usa os headers anti-ads na requisição
            val response = app.get(url, headers = antiAdHeaders, interceptor = streamResolver)
            val intercepted = response.url

            if (intercepted.isNotEmpty() && intercepted.contains(".m3u8")) {
                // Headers para o player de vídeo
                val videoHeaders = mapOf(
                    "Referer" to url,
                    "Origin" to mainUrl,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Accept" to "*/*",
                    "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7"
                )

                M3u8Helper.generateM3u8(
                    name,
                    intercepted,
                    mainUrl,
                    headers = videoHeaders
                ).forEach(callback)

                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
}
