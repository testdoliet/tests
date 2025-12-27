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
            // WebView com DELAY de 5 segundos antes de interceptar
            // Durante os primeiros 5s, IGNORA TUDO
            val streamResolver = DelayedInterceptorWebViewResolver(
                initialDelayMs = 5000L, // 5 segundos
                interceptPattern = Regex(""".*\.m3u8.*"""),
                totalTimeout = 20_000L // 20 segundos total
            )

            println("⏱️  Iniciando WebView com delay de 5s...")
            println("📡 Primeiros 5 segundos: IGNORANDO TODAS as requisições (ads)")

            val response = app.get(url, interceptor = streamResolver)
            val intercepted = response.url

            if (intercepted.isNotEmpty() && intercepted.contains(".m3u8")) {
                println("✅ M3U8 encontrado APÓS delay de 5s: $intercepted")
                
                // Headers baseados na sua análise
                val headers = mapOf(
                    "Referer" to "https://g9r6.com/",
                    "Origin" to "https://g9r6.com",
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                    "Accept" to "*/*",
                    "Accept-Language" to "pt-BR"
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

// WebViewResolver que IGNORA requisições durante um período inicial
class DelayedInterceptorWebViewResolver(
    private val initialDelayMs: Long = 5000L,
    interceptPattern: Regex,
    useOkhttp: Boolean = false,
    totalTimeout: Long = 15000L
) : WebViewResolver(interceptPattern, useOkhttp, totalTimeout) {
    
    private var startTime: Long = 0
    private var delayPassed = false
    
    init {
        startTime = System.currentTimeMillis()
        println("⏰ Delay configurado: ${initialDelayMs}ms")
    }
    
    override fun shouldIntercept(requestUrl: String): Boolean {
        val currentTime = System.currentTimeMillis()
        val elapsedTime = currentTime - startTime
        
        // Se ainda está no período de delay, IGNORA TUDO
        if (elapsedTime < initialDelayMs) {
            // Log apenas a cada segundo para não poluir
            if (elapsedTime % 1000 < 50) { // Aprox a cada segundo
                val secondsLeft = (initialDelayMs - elapsedTime) / 1000
                if (secondsLeft > 0) {
                    println("⏳ Delay ativo: ${secondsLeft}s restantes - IGNORANDO: ${getUrlSummary(requestUrl)}")
                }
            }
            return false // NÃO intercepta durante o delay
        }
        
        // Após o delay, começa a verificar
        if (!delayPassed) {
            delayPassed = true
            println("✅ Delay finalizado! Começando a interceptar m3u8...")
        }
        
        // Só intercepta se for m3u8 (após o delay)
        val shouldIntercept = super.shouldIntercept(requestUrl)
        if (shouldIntercept) {
            println("🎯 Interceptando APÓS delay: ${getUrlSummary(requestUrl)}")
        }
        
        return shouldIntercept
    }
    
    private fun getUrlSummary(url: String): String {
        return if (url.length > 60) {
            "${url.substring(0, 30)}...${url.substring(url.length - 30)}"
        } else {
            url
        }
    }
}
