package com.TopAnimes

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

object ChPlayExtractor {

    suspend fun extractVideoLinks(
        url: String,  // URL da página do episódio
        name: String, // Nome do player (ex: "ChPlay")
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔍 CHPLAY EXTRACTOR INICIADO")
        println("📄 URL do episódio: $url")
        println("🏷️ Nome: $name")
        
        // URL base fixa do site
        val mainUrl = "https://topanimes.net"
        
        return try {
            // 1. ENCONTRA A URL DO PNG NO HTML
            println("🔎 Buscando URL do player PNG...")
            val htmlResponse = app.get(url)
            val html = htmlResponse.text
            
            // Procura a URL do iframe PNG
            var pngUrl: String? = null
            
            // Padrões para encontrar a URL
            val patterns = listOf(
                Regex("""src=["'](https?://png\.strp2p\.com/[^"']*)["']"""),
                Regex("""<iframe[^>]*src=["'](https?://[^"']*strp2p[^"']*)["']"""),
                Regex("""id=["']source-player-1["'][^>]*>.*?<iframe[^>]*src=["'](https?://[^"']*)["']""", RegexOption.DOT_MATCHES_ALL)
            )
            
            for (pattern in patterns) {
                val match = pattern.find(html)
                if (match != null) {
                    pngUrl = match.groupValues[1]
                    break
                }
            }
            
            if (pngUrl == null) {
                println("❌ URL PNG não encontrada")
                return false
            }
            
            println("✅ URL PNG encontrada: ${pngUrl.take(100)}...")
            
            // 2. USA WEBVIEWRESOLVER PARA INTERCEPTAR M3U8
            println("🎮 Iniciando WebViewResolver...")
            
            val m3u8Resolver = WebViewResolver(
                interceptUrl = Regex("""cf-master\.\d+\.txt|\.m3u8"""),
                additionalUrls = listOf(
                    Regex("""cf-master"""),
                    Regex("""\.m3u8""")
                ),
                useOkhttp = false,
                timeout = 15_000L
            )
            
            // 3. ACESSA O PNG
            println("🔄 Acessando player PNG...")
            val intercepted = app.get(pngUrl, interceptor = m3u8Resolver).url
            
            println("🔗 URL interceptada: $intercepted")
            
            // 4. VERIFICA SE TEM M3U8
            if (intercepted.isNotEmpty() && (intercepted.contains("cf-master") || intercepted.contains(".m3u8"))) {
                println("✅ M3U8 interceptado!")
                
                // Headers
                val headers = mapOf(
                    "Accept" to "*/*",
                    "Connection" to "keep-alive",
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "cross-site",
                    "Referer" to pngUrl,
                    "Origin" to "https://png.strp2p.com",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
                
                // Gera links M3U8
                M3u8Helper.generateM3u8(
                    "$name Player",
                    intercepted,
                    mainUrl,
                    headers = headers
                ).forEach(callback)
                
                return true
            }
            
            println("❌ Nenhum M3U8 interceptado")
            false
            
        } catch (e: Exception) {
            println("💥 Erro no ChPlay: ${e.message}")
            false
        }
    }
}
