package com.Goyabu

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

object GoyabuM3u8Extractor {
    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔍 GOYABU M3U8 EXTRACTOR: Analisando URL: $url")
        
        return try {
            // Configurar WebViewResolver para interceptar M3U8
            val streamResolver = WebViewResolver(
                interceptUrl = Regex("""\.m3u8"""),
                useOkhttp = false,
                timeout = 10_000L // Reduzir timeout para 10 segundos
            )

            println("🔄 Iniciando WebView para interceptar M3U8...")
            val response = app.get(url, interceptor = streamResolver)
            val interceptedUrl = response.url
            
            println("📡 URL interceptada: $interceptedUrl")
            
            if (interceptedUrl.contains(".m3u8")) {
                println("🎯 M3U8 encontrado: $interceptedUrl")
                
                val headers = mapOf(
                    "Referer" to url,
                    "Origin" to mainUrl,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                )
                
                M3u8Helper.generateM3u8(
                    name,
                    interceptedUrl,
                    mainUrl,
                    headers = headers
                ).forEach(callback)
                
                println("✅ GOYABU M3U8 EXTRACTOR: Links M3U8 extraídos com sucesso!")
                true
            } else {
                println("⚠️ Nenhum M3U8 interceptado. Tentando buscar no HTML...")
                
                // Fallback: buscar no HTML da página
                val htmlResponse = app.get(url)
                val html = htmlResponse.text
                
                // Procurar por URLs de vídeo no HTML
                val m3u8Patterns = listOf(
                    """["'](https?://[^"']+\.m3u8[^"']*)["']""".toRegex(),
                    """["'](https?://[^"']+\.mp4/index\.m3u8[^"']*)["']""".toRegex(),
                    """source\s+src\s*=\s*["']([^"']+)["']""".toRegex(),
                    """video\s+src\s*=\s*["']([^"']+)["']""".toRegex()
                )
                
                for (pattern in m3u8Patterns) {
                    val match = pattern.find(html)
                    if (match != null) {
                        val videoUrl = match.groupValues[1]
                        if (videoUrl.contains(".m3u8")) {
                            println("🎯 M3U8 encontrado no HTML: $videoUrl")
                            
                            val headers = mapOf(
                                "Referer" to url,
                                "Origin" to mainUrl,
                                "User-Agent" to "Mozilla/5.0"
                            )
                            
                            M3u8Helper.generateM3u8(
                                name,
                                videoUrl,
                                mainUrl,
                                headers = headers
                            ).forEach(callback)
                            
                            return true
                        }
                    }
                }
                
                println("❌ GOYABU M3U8 EXTRACTOR: Nenhum M3U8 encontrado")
                false
            }
            
        } catch (e: Exception) {
            println("❌ GOYABU M3U8 EXTRACTOR: Erro: ${e.message}")
            e.printStackTrace()
            false
        }
    }
}
