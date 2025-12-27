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
            // Versão SIMPLES e FUNCIONAL baseada no padrão que você encontrou
            val headers = mapOf(
                "Referer" to "https://g9r6.com/",
                "Origin" to "https://g9r6.com/",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                "Accept" to "*/*",
                "Accept-Language" to "pt-BR",
                "Cache-Control" to "no-cache",
                "Pragma" to "no-cache"
            )
            
            // 1. Tenta extração DIRETA sem WebView
            val response = app.get(url, headers = headers)
            val html = response.text
            
            // Procura pelos padrões do CDN específico
            val patterns = listOf(
                // Padrão exato do CDN que você encontrou
                Regex("""https?://[a-z0-9]+\.rcr82\.waw05\.i8yz83pn\.com/[^"\s]+\.m3u8[^"\s]*"""),
                // Padrão mais genérico do mesmo CDN
                Regex("""https?://[a-z0-9]+\.[a-z0-9]+\.[a-z0-9]+\.[a-z0-9]+/hls2/[^"\s]+\.m3u8[^"\s]*"""),
                // Padrão com parâmetros t, s, e, f
                Regex("""https?://[^"\s]+\.m3u8\?[^"\s]*t=[^&\s]+[^"\s]*"""),
                // Qualquer m3u8 que não seja de ad
                Regex("""https?://(?!.*ad)(?!.*track)(?!.*analytics)[^"\s]+\.m3u8[^"\s]*""")
            )
            
            for (pattern in patterns) {
                val matches = pattern.findAll(html)
                for (match in matches) {
                    val m3u8Url = match.value
                    if (m3u8Url.isNotBlank() && !m3u8Url.contains("ad", ignoreCase = true)) {
                        // Headers para o m3u8
                        val m3u8Headers = mapOf(
                            "Referer" to "https://g9r6.com/",
                            "Origin" to "https://g9r6.com/",
                            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
                        )
                        
                        M3u8Helper.generateM3u8(
                            name,
                            m3u8Url,
                            "https://g9r6.com/",
                            headers = m3u8Headers
                        ).forEach(callback)
                        
                        return true
                    }
                }
            }
            
            // 2. Se não encontrou, tenta WebView com Regex SIMPLES
            try {
                val streamResolver = WebViewResolver(
                    interceptUrl = Regex("""\.(m3u8|mp4|mkv)"""),
                    useOkhttp = false,
                    timeout = 10_000L
                )
                
                val webViewResponse = app.get(url, headers = headers, interceptor = streamResolver)
                val intercepted = webViewResponse.url
                
                if (intercepted.isNotEmpty() && intercepted.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(
                        name,
                        intercepted,
                        "https://g9r6.com/",
                        headers = headers
                    ).forEach(callback)
                    
                    return true
                }
            } catch (e: Exception) {
                // Ignora erro do WebView
            }
            
            false
            
        } catch (e: Exception) {
            false
        }
    }
}
