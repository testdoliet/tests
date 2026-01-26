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
        println("🔍 CHPLAY EXTRACTOR - VERSÃO DEBUG")
        
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
            
            println("🎯 URL final: ${finalUrl.take(100)}...")
            println("\n🔍 TESTANDO DIFERENTES PADRÕES DE INTERCEPTAÇÃO:")
            println("==============================================")
            
            // 6. TESTA VÁRIOS PADRÕES DIFERENTES
            val patternsToTest = listOf(
                "cf-master" to Regex("""cf-master\.\d+\.txt"""),
                "master.m3u8" to Regex("""master\.m3u8"""),
                "qualquer .m3u8" to Regex(""".*\.m3u8.*"""),
                "qualquer .mp4" to Regex(""".*\.mp4.*"""),
                "padrão /9a/" to Regex(""".*/9a/.*"""),
                "padrão /v/" to Regex(""".*/v/.*"""),
                "video" to Regex(""".*video.*"""),
                "stream" to Regex(""".*stream.*"""),
                "manifest" to Regex(""".*manifest.*"""),
                "qualquer coisa" to Regex(""".*""")  // Captura TUDO
            )
            
            var lastSuccessfulPattern = ""
            var interceptedUrl = ""
            
            for ((patternName, pattern) in patternsToTest) {
                println("\n🧪 Testando padrão: $patternName")
                println("   Regex: ${pattern.pattern}")
                
                try {
                    val m3u8Resolver = WebViewResolver(
                        interceptUrl = pattern,
                        additionalUrls = listOf(pattern),
                        useOkhttp = false,
                        timeout = 5_000L // 5 segundos por teste
                    )
                    
                    println("   🔄 Acessando com este padrão...")
                    val intercepted = app.get(finalUrl, interceptor = m3u8Resolver).url
                    
                    println("   🔗 Resultado: ${intercepted.take(150)}...")
                    
                    if (intercepted.isNotEmpty() && intercepted != finalUrl) {
                        lastSuccessfulPattern = patternName
                        interceptedUrl = intercepted
                        println("   ✅ Padrão FUNCIONOU!")
                        
                        // Analisa o que foi interceptado
                        println("   📊 Análise da URL interceptada:")
                        println("   - Tamanho: ${intercepted.length} chars")
                        println("   - Contém .m3u8? ${intercepted.contains(".m3u8")}")
                        println("   - Contém .mp4? ${intercepted.contains(".mp4")}")
                        println("   - Contém cf-master? ${intercepted.contains("cf-master")}")
                        println("   - Contém master? ${intercepted.contains("master")}")
                        println("   - Contém /9a/? ${intercepted.contains("/9a/")}")
                        
                        // Se parece vídeo, tenta processar
                        if (intercepted.contains(".m3u8") || intercepted.contains("cf-master") || 
                            intercepted.contains("master") || intercepted.contains(".mp4")) {
                            
                            println("\n🎬 TENTANDO PROCESSAR COMO VÍDEO...")
                            
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
                                if (intercepted.contains(".m3u8") || intercepted.contains("cf-master") || intercepted.contains("master")) {
                                    M3u8Helper.generateM3u8(
                                        "$name ($patternName)",
                                        intercepted,
                                        "https://topanimes.net",
                                        headers = headers
                                    ).forEach(callback)
                                    println("   ✅ VÍDEO PROCESSADO COM SUCESSO!")
                                    return true
                                }
                            } catch (e: Exception) {
                                println("   ⚠️ Erro ao processar: ${e.message}")
                            }
                        }
                    } else {
                        println("   ⚠️ Nada interceptado ou mesma URL")
                    }
                    
                } catch (e: Exception) {
                    println("   ❌ Erro: ${e.message}")
                }
            }
            
            println("\n📊 RESUMO DOS TESTES:")
            println("======================")
            if (lastSuccessfulPattern.isNotEmpty()) {
                println("✅ Último padrão que funcionou: $lastSuccessfulPattern")
                println("🔗 URL interceptada: ${interceptedUrl.take(200)}...")
            } else {
                println("❌ Nenhum padrão interceptou nada útil")
            }
            
            // 7. TENTA ANALISAR DIRETAMENTE O HTML
            println("\n🔍 ANALISANDO HTML DIRETAMENTE...")
            try {
                val response = app.get(finalUrl)
                val responseText = response.text
                println("✅ HTML obtido: ${responseText.length} chars")
                
                // Procura padrões específicos no HTML
                val searchPatterns = listOf(
                    """["'](https?://[^"']*\.m3u8[^"']*)["']""",
                    """["'](https?://[^"']*\.mp4[^"']*)["']""",
                    """file\s*:\s*["'](https?://[^"']+)["']""",
                    """src\s*:\s*["'](https?://[^"']+)["']""",
                    """["'](/[^"']*\.m3u8[^"']*)["']""",
                    """["'](/[^"']*\.mp4[^"']*)["']""",
                    """["'](/[^"']*/9a/[^"']*)["']""",
                    """["'](/\w+/\w+/\w+/\w+\.\w+)["']""" // Padrão: /xxx/xxx/xxx/xxx.xxx
                )
                
                println("🔎 Procurando URLs no HTML...")
                for (patternStr in searchPatterns) {
                    val pattern = Regex(patternStr)
                    val matches = pattern.findAll(responseText)
                    var count = 0
                    
                    for (match in matches) {
                        count++
                        val foundUrl = match.groupValues.getOrNull(1) ?: continue
                        println("   🔍 Encontrado ($patternStr): ${foundUrl.take(100)}...")
                        
                        // Tenta completar a URL se for relativa
                        val fullUrl = when {
                            foundUrl.startsWith("//") -> "https:$foundUrl"
                            foundUrl.startsWith("/") -> "https://png.strp2p.com$foundUrl"
                            foundUrl.startsWith("http") -> foundUrl
                            else -> null
                        }
                        
                        if (fullUrl != null && (fullUrl.contains(".m3u8") || fullUrl.contains(".mp4"))) {
                            println("   🎬 TENTANDO URL COMPLETA: ${fullUrl.take(150)}...")
                            
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
                                if (fullUrl.contains(".m3u8")) {
                                    M3u8Helper.generateM3u8(
                                        name,
                                        fullUrl,
                                        "https://topanimes.net",
                                        headers = headers
                                    ).forEach(callback)
                                    println("   ✅ VÍDEO DO HTML FUNCIONOU!")
                                    return true
                                }
                            } catch (e: Exception) {
                                println("   ⚠️ Erro ao processar URL do HTML: ${e.message}")
                            }
                        }
                    }
                    
                    if (count > 0) {
                        println("   📈 Encontrou $count matches com padrão: $patternStr")
                    }
                }
                
            } catch (e: Exception) {
                println("⚠️ Erro ao analisar HTML: ${e.message}")
            }
            
            println("\n❌ Nenhuma abordagem funcionou")
            false
            
        } catch (e: Exception) {
            println("💥 ERRO GERAL: ${e.message}")
            e.printStackTrace()
            false
        }
    }
}
