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
            println("🎯 Estratégia: Ignorar ads iniciais por 5 segundos")
            
            // Variável para controlar quando começar a interceptar
            var interceptActive = false
            val startTime = System.currentTimeMillis()
            
            val streamResolver = WebViewResolver(
                // Usando a assinatura correta do construtor
                useOkhttp = false,
                timeout = 20000L,
                shouldIntercept = { requestUrl ->
                    val elapsed = System.currentTimeMillis() - startTime
                    
                    // Só começa a interceptar após 5 segundos
                    if (elapsed < 5000) {
                        // Log a cada segundo
                        if (elapsed % 1000 < 50) {
                            val secondsLeft = (5000 - elapsed) / 1000
                            if (secondsLeft > 0) {
                                println("⏳ Aguardando: ${secondsLeft}s - IGNORANDO requisições")
                            }
                        }
                        return@WebViewResolver false
                    }
                    
                    // Após 5s, verifica se é m3u8
                    if (!interceptActive) {
                        interceptActive = true
                        println("✅ Delay de 5s finalizado! Agora interceptando m3u8...")
                    }
                    
                    val isM3u8 = requestUrl.contains(".m3u8")
                    if (isM3u8) {
                        println("🎯 Interceptando m3u8: ${requestUrl.take(80)}...")
                    }
                    
                    return@WebViewResolver isM3u8
                }
            )
            
            val response = app.get(url, interceptor = streamResolver)
            val intercepted = response.url

            if (intercepted.contains(".m3u8")) {
                println("✅ M3U8 encontrado após delay: $intercepted")
                
                // Headers baseados na sua análise
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
                    "https://g9r6.com/",
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
