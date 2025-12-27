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
            // PRIMEIRO: Detecta para onde a página redireciona
            val finalUrl = getFinalRedirect(url)
            
            // SEGUNDO: Usa WebView APENAS no player final (bysevepoin.com)
            if (finalUrl.contains("bysevepoin.com")) {
                extractFromPlayer(finalUrl, url, mainUrl, name, callback)
            } else {
                // Fallback: extração tradicional
                traditionalExtraction(url, mainUrl, name, callback)
            }
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun getFinalRedirect(initialUrl: String): String {
        return try {
            // Faz uma requisição HEAD para seguir redirecionamentos
            var currentUrl = initialUrl
            var redirectCount = 0
            val maxRedirects = 5
            
            while (redirectCount < maxRedirects) {
                val response = app.get(currentUrl, allowRedirects = false)
                
                // Verifica se há redirecionamento
                val location = response.headers["location"] ?: break
                
                currentUrl = location
                redirectCount++
                
                // Se chegou no bysevepoin.com, para aqui
                if (currentUrl.contains("bysevepoin.com")) {
                    break
                }
            }
            
            currentUrl
        } catch (e: Exception) {
            initialUrl
        }
    }
    
    private suspend fun extractFromPlayer(
        playerUrl: String,
        referer: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // WebView APENAS no player real (menos ads)
            val streamResolver = WebViewResolver(
                interceptUrl = Regex("""\.m3u8(\?.*)?$"""), // Apenas m3u8
                useOkhttp = false,
                timeout = 10_000L
            )
            
            // Headers específicos para o player
            val headers = mapOf(
                "Referer" to referer,
                "User-Agent" to "Mozilla/5.0",
                "Accept" to "video/*, */*;q=0.8",
                "Accept-Language" to "pt-BR"
            )
            
            val response = app.get(playerUrl, headers = headers, interceptor = streamResolver)
            val intercepted = response.url
            
            if (intercepted.isNotEmpty() && intercepted.contains(".m3u8")) {
                // Headers CORRETOS para o CDN
                val cdnHeaders = mapOf(
                    "Referer" to "https://g9r6.com/", // ESSENCIAL!
                    "Origin" to "https://g9r6.com/", // ESSENCIAL!
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                    "Accept" to "*/*",
                    "Accept-Language" to "pt-BR"
                )
                
                M3u8Helper.generateM3u8(
                    name,
                    intercepted,
                    "https://g9r6.com/", // Referer correto para o CDN
                    headers = cdnHeaders
                ).forEach(callback)
                
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun traditionalExtraction(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val streamResolver = WebViewResolver(
                interceptUrl = Regex("""\.m3u8"""),
                useOkhttp = false,
                timeout = 8_000L
            )
            
            val response = app.get(url, interceptor = streamResolver)
            val intercepted = response.url
            
            if (intercepted.isNotEmpty() && intercepted.contains(".m3u8")) {
                val headers = mapOf(
                    "Referer" to url,
                    "Origin" to mainUrl
                )
                
                M3u8Helper.generateM3u8(
                    name,
                    intercepted,
                    mainUrl,
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
