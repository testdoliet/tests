package com.TopAnimes

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

object ChPlayExtractor {
    
    // Lista para armazenar todas as requisições capturadas
    private val capturedRequests = mutableListOf<String>()
    
    suspend fun extractVideoLinks(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔍 CHPLAY EXTRACTOR - CAPTURA COMPLETA DE REQUISIÇÕES")
        capturedRequests.clear() // Limpa lista anterior
        
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
            
            // 5. PRIMEIRO: CAPTURA TODAS AS REQUISIÇÕES SEM FILTRO
            println("\n📡 CAPTURANDO TODAS AS REQUISIÇÕES DISPONÍVEIS...")
            println("==================================================")
            
            // Versão simplificada - primeiro captura todas as requisições
            // Fazendo múltiplas tentativas com diferentes padrões
            
            // Padrões comuns para testar
            val testPatterns = listOf(
                "Tudo" to Regex(".*"),
                "M3U8" to Regex(".*\\.m3u8.*"),
                "MP4" to Regex(".*\\.mp4.*"),
                "Master" to Regex(".*master.*"),
                "Stream" to Regex(".*stream.*"),
                "Video" to Regex(".*video.*"),
                "CF-Master" to Regex(".*cf-master.*"),
                "Padrão 9a" to Regex(".*/9a/.*"),
                "Padrão v" to Regex(".*/v/.*"),
                "TS files" to Regex(".*\\.ts.*")
            )
            
            val allFoundUrls = mutableSetOf<String>()
            
            for ((patternName, pattern) in testPatterns) {
                println("\n🧪 Testando captura com padrão: $patternName")
                
                try {
                    val captureResolver = WebViewResolver(
                        interceptUrl = pattern,
                        additionalUrls = listOf(pattern),
                        useOkhttp = false,
                        timeout = 5_000L
                    )
                    
                    val result = app.get(finalUrl, interceptor = captureResolver)
                    println("   Status: ${result.code}")
                    
                    // Adiciona a URL interceptada (se diferente da original)
                    if (result.url.isNotEmpty() && result.url != finalUrl) {
                        allFoundUrls.add(result.url)
                        println("   ✅ URL encontrada: ${result.url.take(120)}...")
                    } else {
                        println("   ⚠️ Nenhuma URL diferente encontrada")
                    }
                    
                    // Também verifica o texto da resposta por URLs
                    val textResponse = result.text
                    val urlPatterns = listOf(
                        Regex("""["'](https?://[^"']*\.m3u8[^"']*)["']"""),
                        Regex("""["'](https?://[^"']*\.mp4[^"']*)["']"""),
                        Regex("""["'](https?://[^"']*\.ts[^"']*)["']"""),
                        Regex("""["'](//[^"']*\.m3u8[^"']*)["']"""),
                        Regex("""file\s*:\s*["']([^"']+)["']"""),
                        Regex("""src\s*:\s*["']([^"']+)["']"""),
                        Regex("""url\s*:\s*["']([^"']+)["']"""),
                        Regex("""["'](/[^"']*\.m3u8[^"']*)["']"""),
                        Regex("""["'](/[^"']*\.mp4[^"']*)["']""")
                    )
                    
                    for (urlRegex in urlPatterns) {
                        val matches = urlRegex.findAll(textResponse)
                        for (match in matches) {
                            val foundUrl = match.groupValues.getOrNull(1) ?: continue
                            val fullUrl = when {
                                foundUrl.startsWith("//") -> "https:$foundUrl"
                                foundUrl.startsWith("/") -> "https://png.strp2p.com$foundUrl"
                                foundUrl.startsWith("http") -> foundUrl
                                else -> null
                            }
                            
                            if (fullUrl != null) {
                                allFoundUrls.add(fullUrl)
                                println("   🔍 Encontrado no HTML: ${fullUrl.take(100)}...")
                            }
                        }
                    }
                    
                } catch (e: Exception) {
                    println("   ❌ Erro: ${e.message}")
                }
            }
            
            // 6. EXIBE TODAS AS URLs ENCONTRADAS
            println("\n📊 TODAS AS URLs ENCONTRADAS (${allFoundUrls.size} total):")
            println("==================================================")
            
            val categorizedUrls = allFoundUrls.groupBy { url ->
                when {
                    url.contains(".m3u8") -> "M3U8"
                    url.contains(".mp4") -> "MP4"
                    url.contains(".ts") -> "TS"
                    url.contains("master") -> "MASTER"
                    url.contains("stream") -> "STREAM"
                    url.contains("video") -> "VIDEO"
                    url.contains("cf-master") -> "CF-MASTER"
                    else -> "OUTROS"
                }
            }
            
            categorizedUrls.forEach { (category, urls) ->
                println("\n🎯 $category (${urls.size}):")
                urls.forEachIndexed { index, url ->
                    println("   ${index + 1}. ${url.take(100)}...")
                }
            }
            
            // 7. TESTA AS URLs DE VÍDEO
            println("\n🎬 TESTANDO URLs DE VÍDEO ENCONTRADAS:")
            println("==================================================")
            
            val videoUrls = allFoundUrls.filter { url ->
                url.contains(".m3u8") || 
                url.contains(".mp4") || 
                url.contains(".ts") ||
                url.contains("master") ||
                url.contains("stream") ||
                url.contains("video") ||
                url.contains("cf-master")
            }.distinct()
            
            for ((index, videoUrl) in videoUrls.withIndex()) {
                println("\n🔬 Testando vídeo ${index + 1}/${videoUrls.size}:")
                println("   URL: ${videoUrl.take(100)}...")
                
                try {
                    val headers = mapOf(
                        "Accept" to "*/*",
                        "Connection" to "keep-alive",
                        "Referer" to finalUrl,
                        "Origin" to "https://png.strp2p.com",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                    )
                    
                    if (videoUrl.contains(".m3u8") || videoUrl.contains("master") || videoUrl.contains("cf-master")) {
                        M3u8Helper.generateM3u8(
                            "$name (test-${index + 1})",
                            videoUrl,
                            "https://topanimes.net",
                            headers = headers
                        ).forEach(callback)
                        
                        println("   ✅ M3U8 FUNCIONOU!")
                        return true
                    }
                    
                    else if (videoUrl.contains(".mp4")) {
                        callback.invoke(
                            newExtractorLink(
                                source = "ChPlay",
                                name = "ChPlay MP4",
                                url = videoUrl,
                                referer = "https://topanimes.net",
                                quality = Qualities.Unknown.value
                            )
                        )
                        println("   ✅ MP4 DIRETO FUNCIONOU!")
                        return true
                    }
                    
                } catch (e: Exception) {
                    println("   ⚠️ Falhou: ${e.message}")
                }
            }
            
            // 8. TENTA INTERCEPTAÇÃO COM PADRÕES COMUNS
            println("\n🔄 TENTANDO INTERCEPTAÇÃO COM PADRÕES ESPECÍFICOS...")
            println("==================================================")
            
            val commonVideoPatterns = listOf(
                Regex(""".*/9a/.*\.m3u8.*"""),
                Regex(""".*\.m3u8.*"""),
                Regex(""".*cf-master.*"""),
                Regex(""".*master.*"""),
                Regex(""".*\.mp4.*"""),
                Regex(""".*/v/.*"""),
                Regex(""".*/stream/.*"""),
                Regex(""".*/video/.*""")
            )
            
            for ((i, pattern) in commonVideoPatterns.withIndex()) {
                println("\n🧪 Interceptação ${i + 1}: ${pattern.pattern}")
                
                try {
                    val videoResolver = WebViewResolver(
                        interceptUrl = pattern,
                        additionalUrls = listOf(pattern),
                        useOkhttp = false,
                        timeout = 7_000L
                    )
                    
                    val intercepted = app.get(finalUrl, interceptor = videoResolver).url
                    
                    if (intercepted.isNotEmpty() && intercepted != finalUrl) {
                        println("   ✅ Interceptou: ${intercepted.take(150)}...")
                        
                        // Testa se é um vídeo válido
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
                                    println("   🎉 VÍDEO ENCONTRADO!")
                                    return true
                                } else if (intercepted.contains(".mp4")) {
                                    callback.invoke(
                                        newExtractorLink(
                                            source = "ChPlay",
                                            name = "ChPlay MP4",
                                            url = intercepted,
                                            referer = "https://topanimes.net",
                                            quality = Qualities.Unknown.value
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
            
            // 9. ÚLTIMA TENTATIVA: ANÁLISE PROFUNDA DO HTML
            println("\n🔍 ANÁLISE PROFUNDA DO HTML DO PLAYER...")
            println("==================================================")
            
            try {
                val playerHtml = app.get(finalUrl).text
                println("📄 HTML obtido: ${playerHtml.length} caracteres")
                
                // Procura por scripts que possam conter URLs
                val scriptPattern = Regex("""<script[^>]*>([\s\S]*?)</script>""")
                val scripts = scriptPattern.findAll(playerHtml)
                
                scripts.forEachIndexed { scriptIndex, scriptMatch ->
                    val scriptContent = scriptMatch.groupValues[1]
                    if (scriptContent.length < 5000) { // Ignora scripts muito grandes
                        // Procura por URLs em variáveis JavaScript
                        val jsUrlPatterns = listOf(
                            Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                            Regex("""["'](https?://[^"']+\.mp4[^"']*)["']"""),
                            Regex("""file\s*:\s*["']([^"']+)["']"""),
                            Regex("""src\s*:\s*["']([^"']+)["']"""),
                            Regex("""source\s*:\s*["']([^"']+)["']"""),
                            Regex("""url\s*:\s*["']([^"']+)["']"""),
                            Regex("""= \["'"]?([^"'\s,]+\.m3u8)["'"]?"""),
                            Regex("""= \["'"]?([^"'\s,]+\.mp4)["'"]?""")
                        )
                        
                        jsUrlPatterns.forEach { pattern ->
                            val matches = pattern.findAll(scriptContent)
                            matches.forEach { match ->
                                val foundUrl = match.groupValues.getOrNull(1) ?: return@forEach
                                val fullUrl = when {
                                    foundUrl.startsWith("//") -> "https:$foundUrl"
                                    foundUrl.startsWith("/") -> "https://png.strp2p.com$foundUrl"
                                    foundUrl.startsWith("http") -> foundUrl
                                    else -> "https://$foundUrl"
                                }
                                
                                println("   🔍 Script $scriptIndex - URL: ${fullUrl.take(100)}...")
                                
                                // Testa a URL
                                try {
                                    if (fullUrl.contains(".m3u8")) {
                                        val headers = mapOf(
                                            "Accept" to "*/*",
                                            "Connection" to "keep-alive",
                                            "Referer" to finalUrl,
                                            "Origin" to "https://png.strp2p.com",
                                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                                        )
                                        
                                        M3u8Helper.generateM3u8(
                                            name,
                                            fullUrl,
                                            "https://topanimes.net",
                                            headers = headers
                                        ).forEach(callback)
                                        println("   🎉 VÍDEO ENCONTRADO NO SCRIPT!")
                                        return true
                                    }
                                } catch (e: Exception) {
                                    // Ignora erros e continua
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                println("   ⚠️ Erro na análise: ${e.message}")
            }
            
            println("\n❌ NENHUMA URL DE VÍDEO FUNCIONOU")
            println("📊 Total de URLs analisadas: ${allFoundUrls.size}")
            
            false
            
        } catch (e: Exception) {
            println("💥 ERRO GERAL: ${e.message}")
            e.printStackTrace()
            false
        }
    }
}
