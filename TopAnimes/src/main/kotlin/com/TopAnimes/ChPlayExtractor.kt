package com.TopAnimes

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

object ChPlayExtractor {
    
    suspend fun extractVideoLinks(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔍 CHPLAY EXTRACTOR - CAPTURA COMPLETA DE REQUISIÇÕES")
        
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
            
            // 4. CORRIGE A URL SE NECESSÁRIO
            val finalUrl = when {
                iframeUrl.startsWith("//") -> "https:$iframeUrl"
                iframeUrl.startsWith("/") -> "https://topanimes.net$iframeUrl"
                iframeUrl.startsWith("http") -> iframeUrl
                else -> "https://$iframeUrl"
            }
            
            println("🎯 URL final para análise: $finalUrl")
            
            // 5. LISTA DE PADRÕES PARA TESTAR
            val testPatterns = listOf(
                "Tudo" to Regex(".*"),
                "M3U8" to Regex(""".*\.m3u8.*"""),
                "MP4" to Regex(""".*\.mp4.*"""),
                "Master" to Regex(""".*master.*"""),
                "Stream" to Regex(""".*stream.*"""),
                "Video" to Regex(""".*video.*"""),
                "CF-Master" to Regex(""".*cf-master.*"""),
                "Padrão 9a" to Regex(""".*/9a/.*"""),
                "Padrão v" to Regex(""".*/v/.*"""),
                "TS files" to Regex(""".*\.ts.*""")
            )
            
            val allFoundUrls = mutableSetOf<String>()
            
            println("\n📡 TESTANDO PADRÕES DE INTERCEPTAÇÃO...")
            println("==================================================")
            
            // 6. TESTA CADA PADRÃO
            for ((patternName, pattern) in testPatterns) {
                println("\n🧪 Testando: $patternName")
                println("   Regex: ${pattern.pattern}")
                
                try {
                    val resolver = WebViewResolver(
                        interceptUrl = pattern,
                        additionalUrls = listOf(pattern),
                        useOkhttp = false,
                        timeout = 5_000L
                    )
                    
                    val result = app.get(finalUrl, interceptor = resolver)
                    
                    println("   Status: ${result.code}")
                    println("   URL final: ${result.url.take(80)}...")
                    
                    // Adiciona URL se for diferente e interessante
                    if (result.url.isNotEmpty() && result.url != finalUrl) {
                        allFoundUrls.add(result.url)
                        println("   ✅ URL interceptada!")
                    }
                    
                } catch (e: Exception) {
                    println("   ❌ Erro: ${e.message}")
                }
            }
            
            // 7. ANALISA O HTML DIRETAMENTE TAMBÉM
            println("\n🔍 ANALISANDO HTML DIRETAMENTE...")
            println("==================================================")
            
            try {
                val playerResponse = app.get(finalUrl)
                val playerText = playerResponse.text
                println("📄 Tamanho do HTML: ${playerText.length} caracteres")
                
                // Procura URLs no HTML
                val htmlPatterns = listOf(
                    Regex("""src=["']([^"']*\.m3u8[^"']*)["']"""),
                    Regex("""src=["']([^"']*\.mp4[^"']*)["']"""),
                    Regex("""file=["']([^"']+)["']"""),
                    Regex("""source=["']([^"']+)["']"""),
                    Regex("""url=["']([^"']+)["']"""),
                    Regex("""(https?://[^"'\s<>]*\.m3u8[^"'\s<>]*)"""),
                    Regex("""(https?://[^"'\s<>]*\.mp4[^"'\s<>]*)"""),
                    Regex("""(//[^"'\s<>]*\.m3u8[^"'\s<>]*)"""),
                    Regex("""(//[^"'\s<>]*\.mp4[^"'\s<>]*)""")
                )
                
                for (pattern in htmlPatterns) {
                    val matches = pattern.findAll(playerText)
                    for (match in matches) {
                        val foundUrl = match.groupValues.getOrNull(1) ?: continue
                        val fullUrl = when {
                            foundUrl.startsWith("//") -> "https:$foundUrl"
                            foundUrl.startsWith("/") -> "https://png.strp2p.com$foundUrl"
                            foundUrl.startsWith("http") -> foundUrl
                            else -> continue
                        }
                        
                        allFoundUrls.add(fullUrl)
                        println("   🔍 Encontrado: ${fullUrl.take(80)}...")
                    }
                }
                
            } catch (e: Exception) {
                println("   ⚠️ Erro ao analisar HTML: ${e.message}")
            }
            
            // 8. EXIBE TODAS AS URLs ENCONTRADAS
            println("\n📊 RESUMO DAS URLs ENCONTRADAS (${allFoundUrls.size} total):")
            println("==================================================")
            
            if (allFoundUrls.isEmpty()) {
                println("❌ Nenhuma URL encontrada")
                return false
            }
            
            // Categoriza as URLs
            val categories = mapOf(
                "M3U8" to allFoundUrls.filter { it.contains(".m3u8") },
                "MP4" to allFoundUrls.filter { it.contains(".mp4") },
                "TS" to allFoundUrls.filter { it.contains(".ts") },
                "Master" to allFoundUrls.filter { it.contains("master") && !it.contains(".m3u8") },
                "Outros" to allFoundUrls.filter { 
                    !it.contains(".m3u8") && 
                    !it.contains(".mp4") && 
                    !it.contains(".ts") && 
                    !it.contains("master")
                }
            )
            
            categories.forEach { (category, urls) ->
                if (urls.isNotEmpty()) {
                    println("\n🎯 $category (${urls.size}):")
                    urls.forEachIndexed { i, url ->
                        println("   ${i + 1}. ${url.take(70)}...")
                    }
                }
            }
            
            // 9. TESTA AS URLs DE VÍDEO
            println("\n🎬 TESTANDO URLs DE VÍDEO...")
            println("==================================================")
            
            val videoUrls = allFoundUrls.filter { 
                it.contains(".m3u8") || it.contains(".mp4") || it.contains(".ts") 
            }
            
            if (videoUrls.isEmpty()) {
                println("❌ Nenhuma URL de vídeo encontrada")
                return false
            }
            
            for ((index, videoUrl) in videoUrls.withIndex()) {
                println("\n🔬 Testando ${index + 1}/${videoUrls.size}:")
                println("   URL: ${videoUrl.take(80)}...")
                
                try {
                    val headers = mapOf(
                        "Accept" to "*/*",
                        "Connection" to "keep-alive",
                        "Referer" to finalUrl,
                        "Origin" to "https://png.strp2p.com",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                    )
                    
                    if (videoUrl.contains(".m3u8")) {
                        // Tenta como M3U8
                        M3u8Helper.generateM3u8(
                            name,
                            videoUrl,
                            "https://topanimes.net",
                            headers = headers
                        ).forEach(callback)
                        
                        println("   ✅ M3U8 FUNCIONOU!")
                        return true
                        
                    } else if (videoUrl.contains(".mp4")) {
                        // Tenta como MP4 direto
                        callback.invoke(
                            newExtractorLink(
                                name = "ChPlay",
                                url = videoUrl,
                                quality = Qualities.Unknown.value,
                                headers = headers,
                                referer = "https://topanimes.net"
                            )
                        )
                        
                        println("   ✅ MP4 DIRETO FUNCIONOU!")
                        return true
                    }
                    
                } catch (e: Exception) {
                    println("   ⚠️ Falhou: ${e.message}")
                }
            }
            
            // 10. SE NADA FUNCIONOU, TENTA COM PADRÕES MAIS ESPECÍFICOS
            println("\n🔄 TENTANDO INTERCEPTAÇÃO ESPECÍFICA...")
            println("==================================================")
            
            // Padrões específicos baseados nas URLs encontradas
            val specificPatterns = mutableListOf<Regex>()
            
            // Analisa padrões nas URLs encontradas
            allFoundUrls.forEach { url ->
                // Extrai partes do path para criar padrões
                val pathMatch = Regex("""https?://[^/]+(/[^?#]+)""").find(url)
                if (pathMatch != null) {
                    val path = pathMatch.groupValues[1]
                    
                    // Cria padrões baseados no path
                    if (path.contains("/9a/")) {
                        specificPatterns.add(Regex(""".*/9a/.*"""))
                    }
                    if (path.contains("/v/")) {
                        specificPatterns.add(Regex(""".*/v/.*"""))
                    }
                    if (path.contains("/stream/")) {
                        specificPatterns.add(Regex(""".*/stream/.*"""))
                    }
                    if (path.contains("/video/")) {
                        specificPatterns.add(Regex(""".*/video/.*"""))
                    }
                    
                    // Pega a extensão do arquivo
                    val extMatch = Regex("""\.(m3u8|mp4|ts)""").find(path)
                    if (extMatch != null) {
                        val ext = extMatch.groupValues[1]
                        specificPatterns.add(Regex(""".*\.$ext.*"""))
                    }
                }
            }
            
            // Adiciona padrões padrão também
            specificPatterns.addAll(listOf(
                Regex(""".*\.m3u8.*"""),
                Regex(""".*\.mp4.*"""),
                Regex(""".*cf-master.*"""),
                Regex(""".*master.*\..*""") // master com extensão
            ))
            
            // Remove duplicados
            val uniquePatterns = specificPatterns.distinctBy { it.pattern }
            
            for ((i, pattern) in uniquePatterns.withIndex()) {
                println("\n🎯 Interceptação específica ${i + 1}:")
                println("   Padrão: ${pattern.pattern}")
                
                try {
                    val specificResolver = WebViewResolver(
                        interceptUrl = pattern,
                        additionalUrls = listOf(pattern),
                        useOkhttp = false,
                        timeout = 7_000L
                    )
                    
                    val intercepted = app.get(finalUrl, interceptor = specificResolver).url
                    
                    if (intercepted.isNotEmpty() && intercepted != finalUrl) {
                        println("   ✅ Interceptou: ${intercepted.take(80)}...")
                        
                        // Testa se é vídeo
                        if (intercepted.contains(".m3u8") || intercepted.contains(".mp4")) {
                            try {
                                val headers = mapOf(
                                    "Accept" to "*/*",
                                    "Connection" to "keep-alive",
                                    "Referer" to finalUrl,
                                    "Origin" to "https://png.strp2p.com",
                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                                )
                                
                                if (intercepted.contains(".m3u8")) {
                                    M3u8Helper.generateM3u8(
                                        name,
                                        intercepted,
                                        "https://topanimes.net",
                                        headers = headers
                                    ).forEach(callback)
                                    println("   🎉 VÍDEO ENCONTRADO VIA INTERCEPTAÇÃO!")
                                    return true
                                } else if (intercepted.contains(".mp4")) {
                                    callback.invoke(
                                        newExtractorLink(
                                            name = "ChPlay",
                                            url = intercepted,
                                            quality = Qualities.Unknown.value,
                                            headers = headers,
                                            referer = "https://topanimes.net"
                                        )
                                    )
                                    println("   🎉 MP4 ENCONTRADO!")
                                    return true
                                }
                            } catch (e: Exception) {
                                println("   ⚠️ Erro ao processar: ${e.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    println("   ❌ Erro: ${e.message}")
                }
            }
            
            println("\n❌ NENHUMA DAS ABORDAGENS FUNCIONOU")
            println("📊 URLs analisadas: ${allFoundUrls.size}")
            
            false
            
        } catch (e: Exception) {
            println("💥 ERRO GERAL: ${e.message}")
            e.printStackTrace()
            false
        }
    }
}
