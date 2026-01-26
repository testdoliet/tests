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
        
        return try {
            // 1. ENCONTRA A URL DO PNG NO HTML
            println("🔎 Buscando URL do player PNG...")
            val htmlResponse = app.get(url)
            val html = htmlResponse.text
            
            // Procura a URL do iframe PNG - vários padrões possíveis
            var pngUrl: String? = null
            
            // Padrão 1: src="https://topanimes.net/aviso/?url=URL_ENCODED"
            val avisoPattern = Regex("""src=["']https?://[^"']*/aviso/\?url=([^"']*)["']""")
            val avisoMatch = avisoPattern.find(html)
            
            if (avisoMatch != null) {
                val encodedUrl = avisoMatch.groupValues[1]
                println("✅ URL codificada encontrada: ${encodedUrl.take(50)}...")
                
                // Decodifica a URL
                val decodedUrl = java.net.URLDecoder.decode(encodedUrl, "UTF-8")
                println("🔗 URL decodificada: ${decodedUrl.take(100)}...")
                
                if (decodedUrl.contains("strp2p.com")) {
                    pngUrl = decodedUrl
                }
            }
            
            // Se não encontrou pelo padrão acima, tenta outros padrões
            if (pngUrl == null) {
                val otherPatterns = listOf(
                    Regex("""src=["'](https?://png\.strp2p\.com/[^"']*)["']"""),
                    Regex("""<iframe[^>]*src=["']([^"']*strp2p[^"']*)["']"""),
                    Regex("""id=["']source-player-1["'][^>]*>.*?<iframe[^>]*src=["'](https?://[^"']*)["']""", RegexOption.DOT_MATCHES_ALL)
                )
                
                for (pattern in otherPatterns) {
                    val match = pattern.find(html)
                    if (match != null && match.groupValues[1].contains("strp2p")) {
                        pngUrl = match.groupValues[1]
                        break
                    }
                }
            }
            
            if (pngUrl == null) {
                println("❌ URL PNG não encontrada")
                return false
            }
            
            println("✅ URL PNG final: ${pngUrl.take(100)}...")
            
            // Verifica se a URL precisa ser corrigida
            val finalPngUrl = when {
                pngUrl.startsWith("//") -> "https:$pngUrl"
                pngUrl.startsWith("/") -> "https://topanimes.net$pngUrl"
                pngUrl.startsWith("http") -> pngUrl
                else -> "https://$pngUrl"
            }
            
            println("🎯 URL final para WebViewResolver: ${finalPngUrl.take(100)}...")
            
            // 2. USA WEBVIEWRESOLVER PARA INTERCEPTAR M3U8
            println("🎮 Iniciando WebViewResolver...")
            
            val m3u8Resolver = WebViewResolver(
                // Procura por cf-master.XXXXXXXXXX.txt ou master.m3u8
                interceptUrl = Regex("""cf-master\.\d+\.txt|\.m3u8"""),
                additionalUrls = listOf(
                    Regex("""cf-master"""),
                    Regex("""\.m3u8"""),
                    Regex("""master\.m3u8""")
                ),
                useOkhttp = false,
                timeout = 30_000L
            )
            
            // 3. ACESSA O PNG PARA INTERCEPTAR O M3U8
            println("🔄 Acessando player PNG com WebViewResolver...")
            val intercepted = app.get(finalPngUrl, interceptor = m3u8Resolver).url
            
            println("🔗 URL interceptada: $intercepted")
            
            // 4. VERIFICA SE TEM M3U8
            if (intercepted.isNotEmpty() && (intercepted.contains("cf-master") || intercepted.contains(".m3u8"))) {
                println("✅ M3U8 interceptado!")
                
                // Headers baseados no exemplo
                val headers = mapOf(
                    "Accept" to "*/*",
                    "Connection" to "keep-alive",
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "cross-site",
                    "Referer" to finalPngUrl,
                    "Origin" to "https://png.strp2p.com",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
                
                // URL base do site
                val mainUrl = "https://topanimes.net"
                
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
            e.printStackTrace()
            false
        }
    }
}
