package com.TopAnimes

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType

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
            
            // 4. CORRIGE A URL SE NECESSÁRIO
            val finalUrl = when {
                iframeUrl.startsWith("//") -> "https:$iframeUrl"
                iframeUrl.startsWith("/") -> "https://topanimes.net$iframeUrl"
                iframeUrl.startsWith("http") -> iframeUrl
                else -> "https://$iframeUrl"
            }
            
            println("🎯 URL final para análise: $finalUrl")
            
            // 5. LISTA PARA ARMAZENAR TODAS AS REQUISIÇÕES ENCONTRADAS
            val allRequests = mutableListOf<String>()
            
            // 6. FUNÇÃO PARA CAPTURAR REQUISIÇÕES COM PADRÃO ESPECÍFICO
            fun captureWithPattern(pattern: Regex, patternName: String): Boolean {
                println("\n🧪 Testando padrão: $patternName")
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
                    println("   URL final: ${result.url.take(100)}...")
                    
                    if (result.url.isNotEmpty() && result.url != finalUrl) {
                        allRequests.add(result.url)
                        println("   ✅ URL interceptada!")
                        return true
                    }
                    
                    println("   ⚠️ Nenhuma URL interceptada")
                    return false
                    
                } catch (e: Exception) {
                    println("   ❌ Erro: ${e.message}")
                    return false
                }
            }
            
            // 7. TESTA VÁRIOS PADRÕES
            println("\n📡 TESTANDO DIFERENTES PADRÕES DE INTERCEPTAÇÃO:")
            println("==================================================")
            
            val patterns = listOf(
                Regex(".*") to "TUDO",
                Regex(".*\\.m3u8.*") to "M3U8",
                Regex(".*\\.mp4.*") to "MP4", 
                Regex(".*master.*") to "MASTER",
                Regex(".*cf-master.*") to "CF-MASTER",
                Regex(".*/9a/.*") to "PADRÃO /9a/",
                Regex(".*/v/.*") to "PADRÃO /v/",
                Regex(".*stream.*") to "STREAM",
                Regex(".*video.*") to "VIDEO",
                Regex(".*\\.ts.*") to "TS FILES"
            )
            
            patterns.forEach { (pattern, name) ->
                captureWithPattern(pattern, name)
            }
            
            // 8. TAMBÉM PROCURA NO HTML DIRETAMENTE
            println("\n🔍 PROCURANDO URLs NO HTML DIRETAMENTE:")
            println("==================================================")
            
            try {
                val response = app.get(finalUrl)
                val text = response.text
                println("📄 HTML obtido: ${text.length} caracteres")
                
                // Procura por URLs de vídeo
                val urlPatterns = listOf(
                    """["'](https?://[^"']*\.m3u8[^"']*)["']""",
                    """["'](https?://[^"']*\.mp4[^"']*)["']""",
                    """["'](//[^"']*\.m3u8[^"']*)["']""",
                    """["'](//[^"']*\.mp4[^"']*)["']""",
                    """file\s*:\s*["']([^"']+)["']""",
                    """src\s*:\s*["']([^"']+)["']""",
                    """url\s*:\s*["']([^"']+)["']""",
                    """source\s*:\s*["']([^"']+)["']""",
                    """["'](/[^"']*\.m3u8[^"']*)["']""",
                    """["'](/[^"']*\.mp4[^"']*)["']"""
                )
                
                urlPatterns.forEach { patternStr ->
                    val pattern = Regex(patternStr)
                    val matches = pattern.findAll(text)
                    
                    matches.forEach { match ->
                        val foundUrl = match.groupValues.getOrNull(1) ?: return@forEach
                        
                        val fullUrl = when {
                            foundUrl.startsWith("//") -> "https:$foundUrl"
                            foundUrl.startsWith("/") -> "https://png.strp2p.com$foundUrl"
                            foundUrl.startsWith("http") -> foundUrl
                            else -> return@forEach
                        }
                        
                        if (!allRequests.contains(fullUrl)) {
                            allRequests.add(fullUrl)
                            println("   🔍 Encontrado no HTML: ${fullUrl.take(80)}...")
                        }
                    }
                }
                
            } catch (e: Exception) {
                println("⚠️ Erro ao analisar HTML: ${e.message}")
            }
            
            // 9. EXIBE TODAS AS REQUISIÇÕES ENCONTRADAS
            println("\n📊 TODAS AS REQUISIÇÕES ENCONTRADAS (${allRequests.size} total):")
            println("==================================================")
            
            if (allRequests.isEmpty()) {
                println("❌ Nenhuma requisição encontrada")
                return false
            }
            
            // Agrupa por tipo
            val m3u8Urls = allRequests.filter { it.contains(".m3u8") }
            val mp4Urls = allRequests.filter { it.contains(".mp4") }
            val masterUrls = allRequests.filter { it.contains("master") && !it.contains(".m3u8") }
            val otherUrls = allRequests.filter { 
                !it.contains(".m3u8") && !it.contains(".mp4") && !it.contains("master")
            }
            
            if (m3u8Urls.isNotEmpty()) {
                println("\n🎬 URLs M3U8:")
                m3u8Urls.forEachIndexed { i, url ->
                    println("   ${i + 1}. ${url.take(70)}...")
                }
            }
            
            if (mp4Urls.isNotEmpty()) {
                println("\n🎬 URLs MP4:")
                mp4Urls.forEachIndexed { i, url ->
                    println("   ${i + 1}. ${url.take(70)}...")
                }
            }
            
            if (masterUrls.isNotEmpty()) {
                println("\n🎯 URLs com 'master':")
                masterUrls.forEachIndexed { i, url ->
                    println("   ${i + 1}. ${url.take(70)}...")
                }
            }
            
            if (otherUrls.isNotEmpty()) {
                println("\n📄 Outras URLs:")
                otherUrls.take(10).forEachIndexed { i, url ->
                    println("   ${i + 1}. ${url.take(70)}...")
                }
                if (otherUrls.size > 10) {
                    println("   ... e mais ${otherUrls.size - 10} outras")
                }
            }
            
            // 10. TESTA AS URLs DE VÍDEO
            println("\n🎬 TESTANDO URLs DE VÍDEO ENCONTRADAS:")
            println("==================================================")
            
            val videoUrls = allRequests.filter { 
                it.contains(".m3u8") || it.contains(".mp4")
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
                            source = "ChPlay",
                            streamUrl = videoUrl,
                            referer = "https://topanimes.net",
                            headers = headers
                        ).forEach(callback)
                        
                        println("   ✅ M3U8 FUNCIONOU!")
                        return true
                        
                    } else if (videoUrl.contains(".mp4")) {
                        // Tenta como MP4 direto
                        val extractorLink = newExtractorLink(
                            source = "ChPlay",
                            name = "$name [MP4]",
                            url = videoUrl,
                            type = ExtractorLinkType.MP4
                        ) {
                            this.referer = "https://topanimes.net"
                            this.quality = 720
                            this.headers = headers
                        }
                        
                        callback(extractorLink)
                        println("   ✅ MP4 DIRETO FUNCIONOU!")
                        return true
                    }
                    
                } catch (e: Exception) {
                    println("   ⚠️ Falhou: ${e.message}")
                }
            }
            
            // 11. SE NENHUMA URL FUNCIONAR, TENTA INTERCEPTAÇÃO DIRETA COM OS PADRÕES MAIS PROMISSORES
            println("\n🔄 TENTANDO INTERCEPTAÇÃO DIRETA COM PADRÕES ESPECÍFICOS:")
            println("==================================================")
            
            // Baseado nas URLs encontradas, cria padrões mais específicos
            val specificPatterns = mutableListOf<Regex>()
            
            // Analisa padrões nas URLs encontradas
            allRequests.forEach { foundUrl ->
                if (foundUrl.contains("/9a/")) {
                    specificPatterns.add(Regex(""".*/9a/.*"""))
                }
                if (foundUrl.contains("/v/")) {
                    specificPatterns.add(Regex(""".*/v/.*"""))
                }
                if (foundUrl.contains("cf-master")) {
                    specificPatterns.add(Regex(""".*cf-master.*"""))
                }
                if (foundUrl.contains(".m3u8")) {
                    // Extrai o domínio e caminho para criar padrão específico
                    val domainMatch = Regex("""https?://([^/]+)""").find(foundUrl)
                    val domain = domainMatch?.groupValues?.get(1) ?: ""
                    if (domain.isNotEmpty()) {
                        specificPatterns.add(Regex(""".*$domain.*\.m3u8.*"""))
                    }
                }
            }
            
            // Adiciona padrões genéricos também
            specificPatterns.addAll(listOf(
                Regex(""".*\.m3u8.*"""),
                Regex(""".*\.mp4.*"""),
                Regex(""".*master.*\..*""")
            ))
            
            // Remove duplicados
            val uniquePatterns = specificPatterns.distinctBy { it.pattern }
            
            for ((i, pattern) in uniquePatterns.withIndex()) {
                println("\n🎯 Testando interceptação direta ${i + 1}:")
                println("   Padrão: ${pattern.pattern}")
                
                try {
                    val directResolver = WebViewResolver(
                        interceptUrl = pattern,
                        additionalUrls = listOf(pattern),
                        useOkhttp = false,
                        timeout = 7_000L
                    )
                    
                    val intercepted = app.get(finalUrl, interceptor = directResolver).url
                    
                    if (intercepted.isNotEmpty() && intercepted != finalUrl) {
                        println("   ✅ Interceptou: ${intercepted.take(100)}...")
                        
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
                                        source = "ChPlay",
                                        streamUrl = intercepted,
                                        referer = "https://topanimes.net",
                                        headers = headers
                                    ).forEach(callback)
                                    println("   🎉 VÍDEO ENCONTRADO!")
                                    return true
                                } else if (intercepted.contains(".mp4")) {
                                    val extractorLink = newExtractorLink(
                                        source = "ChPlay",
                                        name = "$name [Intercepted MP4]",
                                        url = intercepted,
                                        type = ExtractorLinkType.MP4
                                    ) {
                                        this.referer = "https://topanimes.net"
                                        this.quality = 720
                                        this.headers = headers
                                    }
                                    
                                    callback(extractorLink)
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
            println("📊 Total de URLs analisadas: ${allRequests.size}")
            println("\n📝 SUGESTÕES BASEADAS NAS URLs ENCONTRADAS:")
            println("   - URLs M3U8: ${m3u8Urls.size}")
            println("   - URLs MP4: ${mp4Urls.size}")
            println("   - URLs Master: ${masterUrls.size}")
            println("   - Domínios encontrados: ${allRequests.map { 
                Regex("""https?://([^/]+)""").find(it)?.groupValues?.get(1) ?: "desconhecido"
            }.distinct().joinToString(", ")}")
            
            false
            
        } catch (e: Exception) {
            println("💥 ERRO GERAL: ${e.message}")
            e.printStackTrace()
            false
        }
    }
}
