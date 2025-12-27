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
            // Lista agressiva de domínios para bloquear (baseado nos seus logs)
            val blockedPatterns = listOf(
                "adsco\\.re",
                "tynt\\.com",
                "dtscout\\.com",
                "dtscdn\\.com",
                "cloudflareinsights\\.com",
                "eyeota\\.net",
                "cdn4ads\\.com",
                "mrktmtrcs\\.net",
                "onaudience\\.com",
                "amung\\.us",
                "waust\\.at",
                "\\.js$", // Bloqueia todos os scripts
                "\\.css$", // Bloqueia CSS
                "\\.png$|\\.jpg$|\\.gif$|\\.webp$", // Bloqueia imagens
                "beacon",
                "pixel",
                "tracking",
                "analytics"
            ).joinToString("|")
            
            val streamResolver = WebViewResolver(
                interceptUrl = { interceptedUrl ->
                    // Filtro extremamente agressivo
                    val lowerUrl = interceptedUrl.lowercase()
                    
                    // Primeiro, bloqueia tudo que parece ad/tracking
                    if (Regex(blockedPatterns, RegexOption.IGNORE_CASE).containsMatchIn(lowerUrl)) {
                        return@WebViewResolver false
                    }
                    
                    // Só aceita URLs que claramente são de vídeo
                    val isVideo = lowerUrl.contains(".m3u8") || 
                                  lowerUrl.contains(".mp4") || 
                                  lowerUrl.contains(".mkv") ||
                                  lowerUrl.contains("/video/") ||
                                  lowerUrl.contains("/stream/") ||
                                  (lowerUrl.contains("api") && lowerUrl.contains("video"))
                    
                    return@WebViewResolver isVideo
                },
                useOkhttp = false,
                timeout = 15_000L // Aumenta timeout para sites lentos
            )

            // Headers mínimos - sem muitos detalhes que possam ativar ads
            val headers = mapOf(
                "Referer" to mainUrl,
                "User-Agent" to "Mozilla/5.0"
            )

            val response = app.get(url, headers = headers, interceptor = streamResolver)
            val intercepted = response.url

            if (intercepted.isNotEmpty() && intercepted.contains(".m3u8")) {
                M3u8Helper.generateM3u8(
                    name,
                    intercepted,
                    mainUrl,
                    headers = mapOf("Referer" to url)
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
