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
            // 1. PRIMEIRO: Tentar com WebView com timeout maior
            println("🔄 Iniciando WebView (30s) para interceptar JavaScript...")
            
            // Interceptar várias possibilidades:
            // - .m3u8
            // - .mp4 (pode ser o arquivo antes do index.m3u8)
            // - /index.m3u8
            // - cdn- (geralmente usado por CDNs de vídeo)
            // - stream/ (pasta comum para streams)
            val streamResolver = WebViewResolver(
                interceptUrl = Regex("""(\.m3u8|\.mp4/index\.m3u8|/index\.m3u8|cdn-|stream/)"""),
                useOkhttp = false,
                timeout = 30_000L // 30 segundos para o JavaScript carregar
            )

            val response = app.get(url, interceptor = streamResolver)
            val interceptedUrl = response.url
            
            println("📡 URL interceptada pelo WebView: $interceptedUrl")
            
            // 2. VERIFICAR O QUE FOI INTERCEPTADO
            if (interceptedUrl.contains(".m3u8")) {
                println("🎯 M3U8 interceptado via JavaScript: $interceptedUrl")
                
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
                
                println("✅ GOYABU M3U8 EXTRACTOR: Links extraídos via JavaScript!")
                return true
            }
            
            // 3. SEGUNDA TENTATIVA: Analisar o HTML da página original
            // Mesmo sendo carregado via JS, às vezes a URL está em algum script
            println("⚠️ WebView não interceptou M3U8, analisando HTML...")
            val htmlResponse = app.get(url)
            val html = htmlResponse.text
            
            // Procurar por padrões JavaScript que possam conter a URL
            val jsPatterns = listOf(
                // Procura em scripts JavaScript
                """(https?://[^"'\s]+\.mp4/index\.m3u8)""".toRegex(),
                """["'](https?://[^"']+\.mp4/index\.m3u8)["']""".toRegex(),
                // Procura por variáveis JavaScript
                """video(?:_?url|Url|URL)\s*[=:]\s*["']([^"']+)["']""".toRegex(),
                """src\s*[=:]\s*["']([^"']+)["']""".toRegex(),
                // Procura por URLs em objetos JSON
                """["']url["']\s*:\s*["']([^"']+)["']""".toRegex(),
                """["']source["']\s*:\s*["']([^"']+)["']""".toRegex(),
                // Procura por iframes ou embeds
                """<iframe[^>]+src=["']([^"']+)["'][^>]*>""".toRegex(),
                """embed\s+src=["']([^"']+)["']""".toRegex()
            )
            
            for (pattern in jsPatterns) {
                val matches = pattern.findAll(html).toList()
                if (matches.isNotEmpty()) {
                    for (match in matches) {
                        val foundUrl = match.groupValues[1]
                        println("🔍 URL encontrada no HTML/JS: $foundUrl")
                        
                        // Se encontrou um M3U8
                        if (foundUrl.contains(".m3u8")) {
                            println("🎯 M3U8 encontrado no JS: $foundUrl")
                            
                            val headers = mapOf(
                                "Referer" to url,
                                "Origin" to mainUrl,
                                "User-Agent" to "Mozilla/5.0"
                            )
                            
                            M3u8Helper.generateM3u8(
                                name,
                                foundUrl,
                                mainUrl,
                                headers = headers
                            ).forEach(callback)
                            
                            return true
                        }
                        
                        // Se encontrou uma URL que parece ser de player
                        if (foundUrl.contains("cdn") || foundUrl.contains("stream")) {
                            println("🔄 Fazendo requisição para URL suspeita: $foundUrl")
                            try {
                                val subResponse = app.get(foundUrl, referer = url)
                                val subHtml = subResponse.text
                                
                                // Procurar M3U8 nesta subpágina
                                val subM3u8Pattern = """(https?://[^"'\s]+\.m3u8)""".toRegex()
                                val subMatch = subM3u8Pattern.find(subHtml)
                                
                                if (subMatch != null) {
                                    val m3u8Url = subMatch.groupValues[1]
                                    println("🎯 M3U8 encontrado na subpágina: $m3u8Url")
                                    
                                    val headers = mapOf(
                                        "Referer" to foundUrl,
                                        "Origin" to mainUrl,
                                        "User-Agent" to "Mozilla/5.0"
                                    )
                                    
                                    M3u8Helper.generateM3u8(
                                        name,
                                        m3u8Url,
                                        mainUrl,
                                        headers = headers
                                    ).forEach(callback)
                                    
                                    return true
                                }
                            } catch (e: Exception) {
                                println("⚠️ Erro ao acessar subpágina: ${e.message}")
                            }
                        }
                    }
                }
            }
            
            println("❌ GOYABU M3U8 EXTRACTOR: Não encontrou M3U8 no JavaScript")
            false
            
        } catch (e: Exception) {
            println("❌ GOYABU M3U8 EXTRACTOR: Erro: ${e.message}")
            e.printStackTrace()
            false
        }
    }
}
