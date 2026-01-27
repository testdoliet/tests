package com.TopAnimes

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

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
            println("=" * 50)
            
            val captureResolver = WebViewResolver(
                interceptUrl = Regex(".*"), // INTERCEPTA TUDO
                additionalUrls = listOf(Regex(".*")),
                useOkhttp = false,
                timeout = 10_000L,
                requestInterceptor = { requestUrl, headers ->
                    // CAPTURA CADA REQUISIÇÃO
                    synchronized(capturedRequests) {
                        capturedRequests.add(requestUrl)
                    }
                    println("🌐 Request: ${requestUrl.take(150)}...")
                    
                    // NÃO BLOQUEIA NENHUMA REQUISIÇÃO
                    null
                }
            )
            
            // Executa a requisição para capturar tudo
            val captureResult = app.get(finalUrl, interceptor = captureResolver)
            println("\n✅ Captura concluída!")
            
            // 6. EXIBE TODAS AS REQUISIÇÕES CAPTURADAS
            println("\n📊 REQUISIÇÕES CAPTURADAS (${capturedRequests.size} total):")
            println("=" * 50)
            
            val videoRequests = mutableListOf<String>()
            val m3u8Requests = mutableListOf<String>()
            val mp4Requests = mutableListOf<String>()
            val suspiciousRequests = mutableListOf<String>()
            
            for ((index, request) in capturedRequests.withIndex()) {
                val isVideo = request.contains(".m3u8") || 
                             request.contains(".mp4") || 
                             request.contains(".mkv") ||
                             request.contains(".webm")
                
                val type = when {
                    request.contains(".m3u8") -> "🎬 M3U8"
                    request.contains(".mp4") -> "🎬 MP4"
                    request.contains(".ts") -> "🎬 TS"
                    request.contains("master") -> "🎯 MASTER"
                    request.contains("stream") -> "🌊 STREAM"
                    request.contains("video") -> "📺 VIDEO"
                    request.contains("cf-master") -> "☁️ CF-MASTER"
                    else -> "📄 OUTRO"
                }
                
                println("${index + 1}. $type: ${request.take(120)}...")
                
                // Categoriza as requisições
                when {
                    request.contains(".m3u8") -> m3u8Requests.add(request)
                    request.contains(".mp4") -> mp4Requests.add(request)
                    isVideo -> videoRequests.add(request)
                    request.contains("master") || 
                    request.contains("stream") || 
                    request.contains("video") -> suspiciousRequests.add(request)
                }
            }
            
            // 7. ANALISA OS PADRÕES ENCONTRADOS
            println("\n📈 ANÁLISE DAS REQUISIÇÕES:")
            println("=" * 50)
            println("🎬 Requisições M3U8: ${m3u8Requests.size}")
            m3u8Requests.forEachIndexed { i, req ->
                println("   ${i + 1}. ${req.take(100)}...")
            }
            
            println("\n🎬 Requisições MP4: ${mp4Requests.size}")
            mp4Requests.forEachIndexed { i, req ->
                println("   ${i + 1}. ${req.take(100)}...")
            }
            
            println("\n🎯 Requisições suspeitas (master/stream/video): ${suspiciousRequests.size}")
            suspiciousRequests.forEachIndexed { i, req ->
                println("   ${i + 1}. ${req.take(100)}...")
            }
            
            // 8. PROCURA PADRÕES ESPECÍFICOS NAS URLs
            println("\n🔍 PADRÕES IDENTIFICADOS NAS URLs:")
            println("=" * 50)
            
            val patternsFound = mutableSetOf<String>()
            capturedRequests.forEach { req ->
                // Extrai domínios
                val domainMatch = Regex("""https?://([^/]+)""").find(req)
                val domain = domainMatch?.groupValues?.get(1) ?: ""
                
                // Extrai caminhos padrão
                when {
                    req.contains("/9a/") -> patternsFound.add("Padrão /9a/")
                    req.contains("/v/") -> patternsFound.add("Padrão /v/")
                    req.contains("/stream/") -> patternsFound.add("Padrão /stream/")
                    req.contains("/video/") -> patternsFound.add("Padrão /video/")
                    req.contains("/player/") -> patternsFound.add("Padrão /player/")
                    domain.contains("cloudfront") -> patternsFound.add("Domínio: CloudFront")
                    domain.contains("akamai") -> patternsFound.add("Domínio: Akamai")
                    domain.contains("strp2p") -> patternsFound.add("Domínio: strp2p")
                }
            }
            
            patternsFound.forEach { println("✅ $it") }
            
            // 9. TESTA AS REQUISIÇÕES DE VÍDEO ENCONTRADAS
            println("\n🎬 TESTANDO REQUISIÇÕES DE VÍDEO ENCONTRADAS:")
            println("=" * 50)
            
            val allVideoUrls = (m3u8Requests + mp4Requests + suspiciousRequests).distinct()
            
            for ((index, videoUrl) in allVideoUrls.withIndex()) {
                println("\n🔬 Testando vídeo ${index + 1}/${allVideoUrls.size}:")
                println("   URL: ${videoUrl.take(100)}...")
                
                try {
                    // Tenta processar como M3U8
                    if (videoUrl.contains(".m3u8") || videoUrl.contains("master") || videoUrl.contains("cf-master")) {
                        val headers = mapOf(
                            "Accept" to "*/*",
                            "Connection" to "keep-alive",
                            "Referer" to finalUrl,
                            "Origin" to "https://png.strp2p.com",
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                        )
                        
                        M3u8Helper.generateM3u8(
                            "$name (found-${index + 1})",
                            videoUrl,
                            "https://topanimes.net",
                            headers = headers
                        ).forEach(callback)
                        
                        println("   ✅ VÍDEO FUNCIONOU! Usando esta URL")
                        return true
                    }
                    
                    // Tenta como MP4 direto
                    else if (videoUrl.contains(".mp4")) {
                        callback.invoke(
                            ExtractorLink(
                                "ChPlay",
                                "ChPlay MP4",
                                videoUrl,
                                "https://topanimes.net",
                                Qualities.Unknown.value,
                                false
                            )
                        )
                        println("   ✅ MP4 DIRETO FUNCIONOU!")
                        return true
                    }
                    
                } catch (e: Exception) {
                    println("   ⚠️ Falhou: ${e.message}")
                }
            }
            
            // 10. SE NADA FUNCIONOU, TENTA INTERCEPTAR COM PADRÕES ESPECÍFICOS
            println("\n🔄 TENTANDO INTERCEPTAÇÃO DIRETA COM PADRÕES IDENTIFICADOS...")
            
            val commonPatterns = mutableListOf<Regex>()
            
            // Cria regex baseado nos padrões encontrados
            capturedRequests.forEach { req ->
                when {
                    req.contains("/9a/") -> {
                        commonPatterns.add(Regex(""".*/9a/.*"""))
                        commonPatterns.add(Regex(""".*9a.*"""))
                    }
                    req.contains(".m3u8") -> {
                        commonPatterns.add(Regex(""".*\.m3u8.*"""))
                    }
                    req.contains("master") -> {
                        commonPatterns.add(Regex(""".*master.*"""))
                        commonPatterns.add(Regex(""".*cf-master.*"""))
                    }
                }
            }
            
            // Adiciona padrões genéricos
            commonPatterns.addAll(listOf(
                Regex(""".*video.*"""),
                Regex(""".*stream.*"""),
                Regex(""".*\.mp4.*"""),
                Regex(""".*\.m3u8.*""")
            ))
            
            // Remove duplicados
            val uniquePatterns = commonPatterns.distinctBy { it.pattern }
            
            for ((i, pattern) in uniquePatterns.withIndex()) {
                println("\n🧪 Testando interceptação direta com padrão ${i + 1}: ${pattern.pattern}")
                
                try {
                    val directResolver = WebViewResolver(
                        interceptUrl = pattern,
                        additionalUrls = listOf(pattern),
                        useOkhttp = false,
                        timeout = 5_000L
                    )
                    
                    val intercepted = app.get(finalUrl, interceptor = directResolver).url
                    
                    if (intercepted.isNotEmpty() && intercepted != finalUrl) {
                        println("   ✅ Interceptou: ${intercepted.take(150)}...")
                        
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
            
            println("\n❌ Nenhuma abordagem funcionou")
            println("📊 Total de requisições analisadas: ${capturedRequests.size}")
            
            false
            
        } catch (e: Exception) {
            println("💥 ERRO GERAL: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    // Função auxiliar para gerar separadores
    private operator fun String.times(times: Int): String {
        return this.repeat(times)
    }
}
