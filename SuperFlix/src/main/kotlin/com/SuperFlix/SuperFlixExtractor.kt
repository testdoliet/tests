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
            // Estratégia: Deixa WebView seguir TODOS redirecionamentos
            // mas só intercepta URLs que contenham BOTH:
            // 1. bysevepoin.com E 2. .m3u8
            
            val streamResolver = WebViewResolver(
                interceptUrl = Regex("""bysevepoin\.com.*\.m3u8(\?.*)?$"""),
                useOkhttp = false,
                timeout = 12_000L
            )
            
            val response = app.get(url, interceptor = streamResolver)
            val intercepted = response.url
            
            if (intercepted.isNotEmpty() && intercepted.contains("bysevepoin.com") && intercepted.contains(".m3u8")) {
                // Headers para o CDN
                val cdnHeaders = mapOf(
                    "Referer" to "https://g9r6.com/",
                    "Origin" to "https://g9r6.com/",
                    "User-Agent" to "Mozilla/5.0"
                )
                
                M3u8Helper.generateM3u8(
                    name,
                    intercepted,
                    "https://g9r6.com/",
                    headers = cdnHeaders
                ).forEach(callback)
                
                true
            } else {
                // Fallback: tentar extração tradicional
                fallbackExtraction(url, name, callback)
            }
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun fallbackExtraction(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // WebView normal como fallback
            val streamResolver = WebViewResolver(
                interceptUrl = Regex("""\.m3u8"""),
                useOkhttp = false,
                timeout = 10_000L
            )
            
            val response = app.get(url, interceptor = streamResolver)
            val intercepted = response.url
            
            if (intercepted.isNotEmpty() && intercepted.contains(".m3u8")) {
                val headers = mapOf(
                    "Referer" to "https://g9r6.com/",
                    "Origin" to "https://g9r6.com/"
                )
                
                M3u8Helper.generateM3u8(
                    name,
                    intercepted,
                    "https://g9r6.com/",
                    headers = headers
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
