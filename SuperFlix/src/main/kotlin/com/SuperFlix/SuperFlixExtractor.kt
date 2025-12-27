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
            // WebView ESPECIAL que detecta bysevepoin.com
            val streamResolver = SmartWebViewResolver(
                onPlayerFound = { playerUrl ->
                    // Quando encontrar o player real, podemos logar ou fazer algo
                    println("✅ PLAYER ENCONTRADO: $playerUrl")
                }
            )
            
            val response = app.get(url, interceptor = streamResolver)
            val intercepted = response.url
            
            if (intercepted.isNotEmpty() && intercepted.contains(".m3u8")) {
                // Headers MÁGICOS para o CDN
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
}

// WebViewResolver INTELIGENTE que:
// 1. IGNORA fembed.sx (não intercepta lá)
// 2. ESPERA chegar em bysevepoin.com
// 3. SÓ ENTÃO começa a procurar m3u8
class SmartWebViewResolver(
    private val onPlayerFound: ((String) -> Unit)? = null
) : WebViewResolver(
    interceptUrl = Regex("""\.m3u8(\?.*)?$"""), // Padrão normal para m3u8
    useOkhttp = false,
    timeout = 15_000L // 15 segundos para seguir todos redirecionamentos
) {
    private var foundPlayer = false
    
    override fun shouldIntercept(requestUrl: String): Boolean {
        // Se ainda não encontrou o player...
        if (!foundPlayer) {
            // Verifica se chegou no player real
            if (requestUrl.contains("bysevepoin.com/e/")) {
                foundPlayer = true
                onPlayerFound?.invoke(requestUrl)
                println("🎯 PLAYER DETECTADO: $requestUrl")
                // A partir de agora, pode interceptar m3u8
                return super.shouldIntercept(requestUrl)
            }
            
            // Se for fembed.sx, NÃO intercepta (muitos ads)
            if (requestUrl.contains("fembed.sx")) {
                println("⏭️  PULANDO fembed.sx: $requestUrl")
                return false
            }
            
            // Para qualquer outra URL (inicial, ads, etc), segue sem interceptar
            return false
        }
        
        // Se já encontrou o player, procura m3u8 normalmente
        return super.shouldIntercept(requestUrl)
    }
}
