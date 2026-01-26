package com.TopAnimes

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

object ChPlayExtractor {

    suspend fun extractVideoLinks(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔍 CHPLAY EXTRACTOR - DEBUG DE REQUISIÇÕES")
        
        return try {
            // 1. PEGA O HTML DA PÁGINA DO EPISÓDIO
            val html = app.get(url).text
            
            // 2. PROCURA O IFRAME DO PLAYER 1 (CHPLAY)
            val iframePattern = Regex("""id=["']source-player-1["'][^>]*>.*?<iframe[^>]*src=["']([^"']*)["']""", RegexOption.DOT_MATCHES_ALL)
            val iframeMatch = iframePattern.find(html)
            
            if (iframeMatch == null) {
                println("❌ Iframe do player 1 não encontrado")
                return false
            }
            
            var iframeUrl = iframeMatch.groupValues[1]
            println("✅ Iframe encontrado: ${iframeUrl.take(100)}...")
            
            // 3. SE FOR AVISO, PEGA O URL PARÂMETRO
            if (iframeUrl.contains("/aviso/?url=")) {
                val urlParamPattern = Regex("""url=([^&]*)""")
                val urlParamMatch = urlParamPattern.find(iframeUrl)
                
                if (urlParamMatch != null) {
                    val encodedUrl = urlParamMatch.groupValues[1]
                    iframeUrl = java.net.URLDecoder.decode(encodedUrl, "UTF-8")
                    println("🔗 URL decodificada: ${iframeUrl.take(100)}...")
                }
            }
            
            // 4. VERIFICA SE É A URL DO PNG
            if (!iframeUrl.contains("strp2p.com")) {
                println("❌ Não é URL do PNG: $iframeUrl")
                return false
            }
            
            // 5. CORRIGE A URL SE NECESSÁRIO
            val finalUrl = when {
                iframeUrl.startsWith("//") -> "https:$iframeUrl"
                iframeUrl.startsWith("/") -> "https://topanimes.net$iframeUrl"
                iframeUrl.startsWith("http") -> iframeUrl
                else -> "https://$iframeUrl"
            }
            
            println("🎯 URL final para WebViewResolver: ${finalUrl.take(100)}...")
            
            // 6. LISTA PARA ARMAZENAR TODAS AS REQUISIÇÕES INTERCEPTADAS
            val allRequests = mutableListOf<String>()
            
            // 7. WEBVIEWRESOLVER QUE REGISTRA TODAS AS REQUISIÇÕES
            val m3u8Resolver = WebViewResolver(
                // Intercepta quase tudo para debug
                interceptUrl = Regex(""".*"""),
                additionalUrls = listOf(
                    Regex(""".*""") // Captura tudo
                ),
                useOkhttp = false,
                timeout = 15_000L,
                onRequestIntercepted = { interceptedUrl ->
                    // Log de TODAS as requisições interceptadas
                    println("📡 REQUISIÇÃO INTERCEPTADA: $interceptedUrl")
                    allRequests.add(interceptedUrl)
                    
                    // Verifica se é vídeo
                    val isVideo = interceptedUrl.contains(".m3u8") || 
                                  interceptedUrl.contains(".mp4") ||
                                  interceptedUrl.contains("video") ||
                                  interceptedUrl.contains("stream") ||
                                  interceptedUrl.contains("master") ||
                                  interceptedUrl.contains("cf-master") ||
                                  interceptedUrl.contains("/9a/") || // Padrão do poster
                                  interceptedUrl.contains(".ts") ||
                                  interceptedUrl.contains("manifest")
                    
                    if (isVideo) {
                        println("🎬 POSSÍVEL VÍDEO ENCONTRADO!")
                    }
                }
            )
            
            println("🔄 Iniciando WebViewResolver para capturar TODAS as requisições...")
            println("⏱️ Timeout: 15 segundos")
            
            val intercepted = app.get(finalUrl, interceptor = m3u8Resolver).url
            
            println("\n📊 RESUMO DAS REQUISIÇÕES CAPTURADAS:")
            println("========================================")
            println("Total de requisições: ${allRequests.size}")
            
            // Agrupa por tipo
            val videoRequests = allRequests.filter { 
                it.contains(".m3u8") || it.contains(".mp4") || 
                it.contains("video") || it.contains("stream") ||
                it.contains("master") || it.contains("cf-master") ||
                it.contains(".ts") || it.contains("manifest")
            }
            
            val jsRequests = allRequests.filter { it.contains(".js") }
            val cssRequests = allRequests.filter { it.contains(".css") }
            val imageRequests = allRequests.filter { it.contains(".png") || it.contains(".jpg") || it.contains(".jpeg") || it.contains(".webp") }
            val apiRequests = allRequests.filter { it.contains("/api/") || it.contains("/v1/") || it.contains("/v2/") }
            val otherRequests = allRequests.filter { 
                !it.contains(".js") && !it.contains(".css") && 
                !it.contains(".png") && !it.contains(".jpg") && 
                !it.contains(".jpeg") && !it.contains(".webp") &&
                !it.contains(".m3u8") && !it.contains(".mp4") &&
                !it.contains("/api/") && !it.contains("/v1/") &&
                !it.contains("/v2/") && !it.contains("video") &&
                !it.contains("stream")
            }
            
            println("🎬 Requisições de vídeo (${videoRequests.size}):")
            videoRequests.forEachIndexed { index, req ->
                println("  ${index + 1}. ${req.take(150)}...")
            }
            
            println("\n📜 Requisições de JS (${jsRequests.size}):")
            jsRequests.take(5).forEachIndexed { index, req ->
                println("  ${index + 1}. ${req.take(100)}...")
            }
            if (jsRequests.size > 5) println("  ... e mais ${jsRequests.size - 5} JS")
            
            println("\n🎨 Requisições de CSS (${cssRequests.size}):")
            cssRequests.take(3).forEachIndexed { index, req ->
                println("  ${index + 1}. ${req.take(100)}...")
            }
            
            println("\n🖼️ Requisições de imagem (${imageRequests.size}):")
            imageRequests.take(3).forEachIndexed { index, req ->
                println("  ${index + 1}. ${req.take(100)}...")
            }
            
            println("\n🔌 Requisições de API (${apiRequests.size}):")
            apiRequests.forEachIndexed { index, req ->
                println("  ${index + 1}. ${req.take(150)}...")
            }
            
            println("\n❓ Outras requisições (${otherRequests.size}):")
            otherRequests.take(10).forEachIndexed { index, req ->
                println("  ${index + 1}. ${req.take(100)}...")
            }
            if (otherRequests.size > 10) println("  ... e mais ${otherRequests.size - 10}")
            
            println("\n🔗 URL final interceptada: $intercepted")
            
            // 8. TENTA PROCESSAR QUALQUER URL DE VÍDEO ENCONTRADA
            val videoUrls = videoRequests + listOf(intercepted)
            
            for (videoUrl in videoUrls) {
                if (videoUrl.isNotEmpty() && 
                    (videoUrl.contains(".m3u8") || videoUrl.contains(".mp4") || 
                     videoUrl.contains("cf-master") || videoUrl.contains("master"))) {
                    
                    println("\n🎯 TENTANDO PROCESSAR: ${videoUrl.take(150)}...")
                    
                    val headers = mapOf(
                        "Accept" to "*/*",
                        "Connection" to "keep-alive",
                        "Sec-Fetch-Dest" to "empty",
                        "Sec-Fetch-Mode" to "cors",
                        "Sec-Fetch-Site" to "cross-site",
                        "Referer" to finalUrl,
                        "Origin" to "https://png.strp2p.com",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    )
                    
                    try {
                        if (videoUrl.contains(".m3u8") || videoUrl.contains("cf-master") || videoUrl.contains("master")) {
                            M3u8Helper.generateM3u8(
                                name,
                                videoUrl,
                                "https://topanimes.net",
                                headers = headers
                            ).forEach(callback)
                            println("✅ M3U8 PROCESSADO COM SUCESSO!")
                            return true
                        }
                    } catch (e: Exception) {
                        println("⚠️ Erro ao processar M3U8: ${e.message}")
                    }
                }
            }
            
            println("\n❌ Nenhum vídeo válido encontrado nas requisições")
            
            // 9. TENTA ANALISAR O HTML DA RESPOSTA PARA ENCONTRAR URLs ESCONDIDAS
            println("\n🔍 Analisando HTML da resposta para URLs ocultas...")
            try {
                val response = app.get(finalUrl)
                val responseText = response.text
                
                // Procura por padrões comuns de URLs de vídeo
                val videoPatterns = listOf(
                    Regex("""["'](https?://[^"']*\.m3u8[^"']*)["']"""),
                    Regex("""["'](https?://[^"']*\.mp4[^"']*)["']"""),
                    Regex("""file\s*:\s*["'](https?://[^"']+)["']"""),
                    Regex("""src\s*:\s*["'](https?://[^"']+)["']"""),
                    Regex("""sources\s*:\s*\[.*?\{\s*file\s*:\s*["']([^"']+)["']""", RegexOption.DOT_MATCHES_ALL),
                    Regex("""playlist\s*:\s*\[.*?\{\s*file\s*:\s*["']([^"']+)["']""", RegexOption.DOT_MATCHES_ALL),
                    Regex("""["'](https?://[^"']*/9a/[^"']*)["']""") // Padrão /9a/
                )
                
                for (pattern in videoPatterns) {
                    val matches = pattern.findAll(responseText)
                    for (match in matches) {
                        val foundUrl = match.groupValues.getOrNull(1) ?: continue
                        if (foundUrl.contains(".m3u8") || foundUrl.contains(".mp4") || foundUrl.contains("/9a/")) {
                            println("🔍 URL encontrada no HTML: ${foundUrl.take(150)}...")
                            
                            // Tenta acessar
                            val fullUrl = if (foundUrl.startsWith("//")) "https:$foundUrl"
                                          else if (foundUrl.startsWith("/")) "https://png.strp2p.com$foundUrl"
                                          else foundUrl
                            
                            println("🔗 Tentando acessar: ${fullUrl.take(100)}...")
                        }
                    }
                }
            } catch (e: Exception) {
                println("⚠️ Erro ao analisar HTML: ${e.message}")
            }
            
            false
            
        } catch (e: Exception) {
            println("💥 ERRO GERAL: ${e.message}")
            e.printStackTrace()
            false
        }
    }
}
