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
            // Regex MUITO específica - só pega URLs que são claramente de vídeo
            // e NÃO contém padrões de anúncios
            val videoRegex = Regex("""^https?://(?!.*(?:adsco|tynt|dtscout|dtscdn|cloudflareinsights|eyeota|cnd4ads|mrktmtrcs|onaudience|amung|waust|beacon|pixel|tracking|analytics|\.js\b|\.css\b|\.png\b|\.jpg\b|\.gif\b|\.webp\b)).*\.(m3u8|mp4|mkv)(\?.*)?$""", RegexOption.IGNORE_CASE)
            
            val streamResolver = WebViewResolver(
                interceptUrl = videoRegex,
                useOkhttp = false,
                timeout = 8_000L // Timeout mais curto para evitar ads
            )

            // Headers simples
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
