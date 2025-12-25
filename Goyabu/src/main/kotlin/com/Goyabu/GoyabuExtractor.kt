// ============ GOYABU UNIFIED EXTRACTOR ============
object GoyabuExtractor {
    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🎬 GOYABU UNIFIED EXTRACTOR: Analisando $url")
        
        return try {
            // ESTRATÉGIA 1: WebView para interceptar QUALQUER iframe/vídeo
            val webViewSuccess = tryWebViewExtraction(url, mainUrl, name, callback)
            if (webViewSuccess) return true
            
            // ESTRATÉGIA 2: Analisar HTML para encontrar iframes/scripts
            return analyzeHtmlForVideos(url, mainUrl, name, callback)
            
        } catch (e: Exception) {
            println("❌ Erro no extractor: ${e.message}")
            false
        }
    }
    
    private suspend fun tryWebViewExtraction(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // Regex que captura TUDO: anivideo, googlevideo, m3u8, mp4, etc.
            val streamResolver = WebViewResolver(
                interceptUrl = Regex("""(anivideo\.net|googlevideo\.com|\.m3u8|\.mp4|videohls\.php|blogger\.com)"""),
                useOkhttp = false,
                timeout = 45_000L  // 45 segundos
            )
            
            println("🌐 WebView iniciado (45s timeout)...")
            val response = app.get(url, interceptor = streamResolver)
            val interceptedUrl = response.url
            
            println("📡 URL interceptada: $interceptedUrl")
            
            when {
                interceptedUrl.contains("anivideo.net") -> {
                    println("✅ API anivideo interceptada!")
                    return processAnivideoUrl(interceptedUrl, url, mainUrl, name, callback)
                }
                interceptedUrl.contains("googlevideo.com") -> {
                    println("✅ Googlevideo interceptado!")
                    return processGoogleVideoUrl(interceptedUrl, url, mainUrl, name, callback)
                }
                interceptedUrl.contains(".m3u8") -> {
                    println("✅ M3U8 interceptado!")
                    return processM3u8Url(interceptedUrl, url, mainUrl, name, callback)
                }
                interceptedUrl.contains(".mp4") -> {
                    println("✅ MP4 interceptado!")
                    return processDirectVideoUrl(interceptedUrl, url, mainUrl, name, callback)
                }
            }
            
            false
        } catch (e: Exception) {
            println("⚠️ WebView falhou: ${e.message}")
            false
        }
    }
    
    private suspend fun analyzeHtmlForVideos(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔍 Analisando HTML para vídeos...")
        
        return try {
            val response = app.get(url)
            val html = response.text
            val doc = Jsoup.parse(html)
            
            var found = false
            
            // 1. Procurar iframes
            val iframes = doc.select("iframe[src]")
            println("📊 ${iframes.size} iframes encontrados")
            
            for (iframe in iframes) {
                val src = iframe.attr("src")
                if (src.isNotBlank()) {
                    println("🎯 Iframe encontrado: ${src.take(80)}...")
                    
                    when {
                        src.contains("anivideo.net") -> {
                            if (processAnivideoUrl(src, url, mainUrl, name, callback)) found = true
                        }
                        src.contains("blogger.com") || src.contains("googlevideo") -> {
                            if (processGoogleVideoIframe(src, url, mainUrl, name, callback)) found = true
                        }
                    }
                }
            }
            
            // 2. Procurar em scripts JavaScript
            if (!found) {
                println("🔍 Procurando em scripts JS...")
                val scripts = doc.select("script")
                
                for (script in scripts) {
                    val content = script.html()
                    
                    // Padrão para anivideo
                    val anivideoPattern = """(https?://api\.anivideo\.net/[^"'\s]+)""".toRegex()
                    anivideoPattern.findAll(content).forEach { match ->
                        val apiUrl = match.value
                        println("🎯 API encontrada no JS: $apiUrl")
                        if (processAnivideoUrl(apiUrl, url, mainUrl, name, callback)) found = true
                    }
                    
                    // Padrão para googlevideo
                    val googlePattern = """(https?://[^"'\s]*\.googlevideo\.com/[^"'\s]*)""".toRegex()
                    googlePattern.findAll(content).forEach { match ->
                        val videoUrl = match.value
                        println("🎯 Googlevideo no JS: ${videoUrl.take(80)}...")
                        if (processGoogleVideoUrl(videoUrl, url, mainUrl, name, callback)) found = true
                    }
                    
                    // Padrão para M3U8
                    val m3u8Pattern = """(https?://[^"'\s]*\.m3u8[^"'\s]*)""".toRegex()
                    m3u8Pattern.findAll(content).forEach { match ->
                        val m3u8Url = match.value
                        println("🎯 M3U8 no JS: $m3u8Url")
                        if (processM3u8Url(m3u8Url, url, mainUrl, name, callback)) found = true
                    }
                }
            }
            
            found
            
        } catch (e: Exception) {
            println("❌ Erro ao analisar HTML: ${e.message}")
            false
        }
    }
    
    // ============ PROCESSADORES ESPECÍFICOS ============
    
    private suspend fun processAnivideoUrl(
        apiUrl: String,
        referer: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔗 Processando API anivideo: ${apiUrl.take(100)}...")
        
        try {
            // Extrair parâmetro d=
            val dParamPattern = """[?&]d=([^&]+)""".toRegex()
            val match = dParamPattern.find(apiUrl)
            
            if (match != null) {
                val encodedM3u8 = match.groupValues[1]
                val m3u8Url = URLDecoder.decode(encodedM3u8, "UTF-8")
                println("✅ M3U8 extraído: ${m3u8Url.take(100)}...")
                return processM3u8Url(m3u8Url, referer, mainUrl, name, callback)
            }
            
            // Se não tem d=, fazer requisição
            val apiResponse = app.get(apiUrl, headers = mapOf("Referer" to referer))
            val m3u8Pattern = """(https?://[^"'\s]+\.m3u8[^"'\s]*)""".toRegex()
            val m3u8Match = m3u8Pattern.find(apiResponse.text)
            
            if (m3u8Match != null) {
                val m3u8Url = m3u8Match.groupValues[1]
                println("✅ M3U8 na resposta: $m3u8Url")
                return processM3u8Url(m3u8Url, apiUrl, mainUrl, name, callback)
            }
            
        } catch (e: Exception) {
            println("❌ Erro ao processar anivideo: ${e.message}")
        }
        
        return false
    }
    
    private suspend fun processGoogleVideoUrl(
        videoUrl: String,
        referer: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔗 Processando googlevideo: ${videoUrl.take(80)}...")
        
        return try {
            // Mapa de qualidade
            val itagQualityMap = mapOf(
                5 to 240, 18 to 360, 22 to 720, 37 to 1080,
                59 to 480, 133 to 240, 134 to 360, 135 to 480,
                136 to 720, 137 to 1080, 160 to 144
            )
            
            // Extrair itag
            val itagPattern = """[?&]itag=(\d+)""".toRegex()
            val match = itagPattern.find(videoUrl)
            val itag = match?.groupValues?.get(1)?.toIntOrNull() ?: 18
            val quality = itagQualityMap[itag] ?: 360
            
            val qualityLabel = when {
                quality >= 1080 -> "FHD"
                quality >= 720 -> "HD"
                quality >= 480 -> "SD"
                else -> "SD"
            }
            
            val extractorLink = newExtractorLink(
                source = "Goyabu",
                name = "$name ($qualityLabel)",
                url = videoUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = quality
                this.headers = mapOf(
                    "Referer" to referer,
                    "Origin" to mainUrl,
                    "User-Agent" to "Mozilla/5.0"
                )
            }
            
            callback(extractorLink)
            println("✅ Googlevideo adicionado: $qualityLabel")
            true
            
        } catch (e: Exception) {
            println("❌ Erro no googlevideo: ${e.message}")
            false
        }
    }
    
    private suspend fun processGoogleVideoIframe(
        iframeUrl: String,
        referer: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔗 Processando iframe: ${iframeUrl.take(80)}...")
        
        return try {
            // Acessar iframe
            val iframeResponse = app.get(iframeUrl, headers = mapOf("Referer" to referer))
            val iframeHtml = iframeResponse.text
            
            // Procurar googlevideo no iframe
            val pattern = """(https?://[^"'\s]*\.googlevideo\.com/[^"'\s]*)""".toRegex()
            val matches = pattern.findAll(iframeHtml)
            
            var found = false
            matches.forEach { match ->
                val videoUrl = match.value
                if (processGoogleVideoUrl(videoUrl, iframeUrl, mainUrl, name, callback)) {
                    found = true
                }
            }
            
            found
            
        } catch (e: Exception) {
            println("❌ Erro no iframe: ${e.message}")
            false
        }
    }
    
    private suspend fun processM3u8Url(
        m3u8Url: String,
        referer: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔗 Processando M3U8: $m3u8Url")
        
        return try {
            val headers = mapOf(
                "Referer" to referer,
                "Origin" to mainUrl,
                "User-Agent" to "Mozilla/5.0"
            )
            
            M3u8Helper.generateM3u8(
                name,
                m3u8Url,
                mainUrl,
                headers = headers
            ).forEach(callback)
            
            println("✅ M3U8 processado!")
            true
            
        } catch (e: Exception) {
            println("❌ Erro no M3U8: ${e.message}")
            false
        }
    }
    
    private suspend fun processDirectVideoUrl(
        videoUrl: String,
        referer: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔗 Processando vídeo direto: ${videoUrl.take(80)}...")
        
        return try {
            val extractorLink = newExtractorLink(
                source = "Goyabu",
                name = name,
                url = videoUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = 720
                this.headers = mapOf(
                    "Referer" to referer,
                    "Origin" to mainUrl,
                    "User-Agent" to "Mozilla/5.0"
                )
            }
            
            callback(extractorLink)
            println("✅ Vídeo direto adicionado")
            true
            
        } catch (e: Exception) {
            println("❌ Erro no vídeo direto: ${e.message}")
            false
        }
    }
}
