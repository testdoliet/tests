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
            // WebView que só intercepta URLs do CDN REAL
            val streamResolver = WebViewResolver(
                interceptUrl = Regex("""(g9r6\.com|be7713\.rcr82\.waw05\.i8yz83pn\.com|filemoon\.sx).*\.m3u8"""),
                useOkhttp = false,
                timeout = 15_000L
            )

            println("🎯 Interceptando apenas domínios do CDN...")
            
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                "Referer" to "https://g9r6.com/"
            )
            
            val response = app.get(url, headers = headers, interceptor = streamResolver)
            val intercepted = response.url
            
            println("🔗 URL interceptada: $intercepted")

            if (intercepted.isNotEmpty() && intercepted.contains(".m3u8")) {
                println("✅ M3U8 do CDN encontrado!")
                
                // Headers COMPLETOS do CDN
                val cdnHeaders = mapOf(
                    "Accept" to "*/*",
                    "Accept-Language" to "pt-BR",
                    "Cache-Control" to "no-cache",
                    "Pragma" to "no-cache",
                    "Referer" to "https://g9r6.com/",
                    "Origin" to "https://g9r6.com",
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "cross-site",
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
                )

                M3u8Helper.generateM3u8(
                    name,
                    intercepted,
                    "https://g9r6.com/",
                    headers = cdnHeaders
                ).forEach(callback)

                true
            } else {
                println("❌ Nenhum M3U8 do CDN encontrado")
                false
            }
        } catch (e: Exception) {
            println("💥 Erro: ${e.message}")
            false
        }
    }
}
