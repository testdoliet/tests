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
            // Estratégia SIMPLES: WebView com timeout grande
            // Deixa carregar TUDO - redirecionamentos, ads, player
            
            val streamResolver = WebViewResolver(
                interceptUrl = Regex(""".*\.m3u8.*"""), // Qualquer m3u8
                useOkhttp = false,
                timeout = 30000L // 30 SEGUNDOS - tempo para tudo carregar
            )
            
            // Headers para parecer um navegador real
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; SM-G973F) AppleWebKit/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
                "Referer" to "https://superflix21.lol/"
            )
            
            val response = app.get(url, headers = headers, interceptor = streamResolver)
            val intercepted = response.url
            
            if (intercepted.contains(".m3u8")) {
                println("✅ M3U8 encontrado: $intercepted")
                
                // Headers para o CDN - ESSENCIAIS!
                val cdnHeaders = mapOf(
                    "Referer" to "https://g9r6.com/",
                    "Origin" to "https://g9r6.com/",
                    "User-Agent" to "Mozilla/5.0"
                )
                
                M3u8Helper.generateM3u8(
                    name,
                    intercepted,
                    "https://g9r6.com/",
                    headers = cdnHeaders,
                    prefix = "SuperFlix"
                ).forEach(callback)
                
                true
            } else {
                println("❌ Nenhum M3U8 encontrado. URL final: $intercepted")
                false
            }
        } catch (e: Exception) {
            println("❌ Erro no extractor: ${e.message}")
            false
        }
    }
}
