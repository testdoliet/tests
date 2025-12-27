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
            // Estratégia: Esperar a página carregar COMPLETAMENTE
            // antes de interceptar
            
            val streamResolver = DelayedWebViewResolver(
                initialDelay = 5000L, // 5 segundos de delay
                interceptPattern = Regex("""\.(m3u8|mp4|mkv)"""),
                timeout = 20_000L // Total 20s
            )

            val response = app.get(url, interceptor = streamResolver)
            val intercepted = response.url

            if (intercepted.isNotEmpty() && intercepted.contains(".m3u8")) {
                println("✅ M3U8 encontrado após delay: $intercepted")
                
                // Headers CORRETOS baseados na sua análise
                val headers = mapOf(
                    "Referer" to "https://g9r6.com/",
                    "Origin" to "https://g9r6.com",
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                    "Accept" to "*/*",
                    "Accept-Language" to "pt-BR",
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "cross-site"
                )

                M3u8Helper.generateM3u8(
                    name,
                    intercepted,
                    "https://g9r6.com/", // Referer CORRETO
                    headers = headers
                ).forEach(callback)

                true
            } else {
                println("❌ Nenhum M3U8 encontrado após delay")
                false
            }
        } catch (e: Exception) {
            println("💥 Erro no extractor: ${e.message}")
            false
        }
    }
}

// WebViewResolver personalizado com DELAY inicial
class DelayedWebViewResolver(
    private val initialDelay: Long = 5000L,
    interceptPattern: Regex,
    useOkhttp: Boolean = false,
    timeout: Long = 15000L
) : WebViewResolver(interceptPattern, useOkhttp, timeout) {
    
    private var startTime = System.currentTimeMillis()
    private var delayPassed = false
    
    override fun shouldIntercept(requestUrl: String): Boolean {
        val currentTime = System.currentTimeMillis()
        val elapsed = currentTime - startTime
        
        // Se ainda não passou o delay, NÃO intercepta
        if (elapsed < initialDelay) {
            return false
        }
        
        // Passou o delay, marca como true
        if (!delayPassed) {
            println("⏰ Delay de ${initialDelay}ms passado, começando a interceptar...")
            delayPassed = true
        }
        
        // Agora intercepta normalmente
        return super.shouldIntercept(requestUrl)
    }
}
