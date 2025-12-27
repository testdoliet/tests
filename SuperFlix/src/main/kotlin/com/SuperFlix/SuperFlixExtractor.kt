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
            // Estratégia em 2 etapas:
            
            // ETAPA 1: Detectar o player final
            val playerUrl = detectFinalPlayer(url)
            
            // ETAPA 2: Extrair do player
            if (playerUrl != null) {
                extractFromPlayer(playerUrl, name, callback)
            } else {
                // Fallback: extração tradicional
                traditionalExtraction(url, name, callback)
            }
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun detectFinalPlayer(initialUrl: String): String? {
        return try {
            // WebView que só intercepta URLs do bysevepoin.com
            val playerResolver = WebViewResolver(
                interceptUrl = Regex("""bysevepoin\.com/e/[a-zA-Z0-9]+(?:\?.*)?"""),
                useOkhttp = false,
                timeout = 10_000L,
                allowRedirects = true
            )
            
            val response = app.get(initialUrl, interceptor = playerResolver)
            val intercepted = response.url
            
            // Retorna a URL do player se for bysevepoin.com
            if (intercepted.contains("bysevepoin.com/e/")) {
                println("✅ Player detectado: $intercepted")
                intercepted
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    private suspend fun extractFromPlayer(
        playerUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // WebView que procura APENAS m3u8 DENTRO do player
            val m3u8Resolver = WebViewResolver(
                interceptUrl = Regex(""".*\.m3u8(?:\?.*)?"""),
                useOkhttp = false,
                timeout = 8_000L
            )
            
            // Headers específicos para o player
            val playerHeaders = mapOf(
                "Referer" to "https://superflix21.lol/",
                "User-Agent" to "Mozilla/5.0",
                "Accept" to "video/*"
            )
            
            val response = app.get(playerUrl, headers = playerHeaders, interceptor = m3u8Resolver)
            val intercepted = response.url
            
            if (intercepted.isNotEmpty() && intercepted.contains(".m3u8")) {
                // Headers CORRETOS para o CDN
                val cdnHeaders = mapOf(
                    "Referer" to "https://g9r6.com/",
                    "Origin" to "https://g9r6.com/",
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
                false
            }
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun traditionalExtraction(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // WebView tradicional (fallback)
            val streamResolver = WebViewResolver(
                interceptUrl = Regex(""".*\.m3u8(?:\?.*)?"""),
                useOkhttp = false,
                timeout = 15_000L
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
