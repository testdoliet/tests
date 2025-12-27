package com.example.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.WebViewResolver

object SuperFlixExtractor {
    
    // Lista de padrões para IGNORAR (ads, trackers, redirecionamentos)
    private val ignorePatterns = listOf(
        // Ads
        Regex("""adsco\.re""", RegexOption.IGNORE_CASE),
        Regex("""doubleclick\.net""", RegexOption.IGNORE_CASE),
        Regex("""googleads""", RegexOption.IGNORE_CASE),
        Regex("""googlesyndication""", RegexOption.IGNORE_CASE),
        Regex("""amazon-adsystem""", RegexOption.IGNORE_CASE),
        Regex("""ads\.""", RegexOption.IGNORE_CASE),
        Regex("""adserver""", RegexOption.IGNORE_CASE),
        Regex("""analytics""", RegexOption.IGNORE_CASE),
        Regex("""tracking""", RegexOption.IGNORE_CASE),
        Regex("""pixel""", RegexOption.IGNORE_CASE),
        Regex("""beacon""", RegexOption.IGNORE_CASE),
        
        // Trackers
        Regex("""facebook\.com/tr""", RegexOption.IGNORE_CASE),
        Regex("""connect\.facebook\.net""", RegexOption.IGNORE_CASE),
        Regex("""googletagmanager""", RegexOption.IGNORE_CASE),
        Regex("""googletagservices""", RegexOption.IGNORE_CASE),
        
        // CDNs de ads
        Regex("""cdn4ads""", RegexOption.IGNORE_CASE),
        Regex("""adnxs""", RegexOption.IGNORE_CASE),
        Regex("""casalemedia""", RegexOption.IGNORE_CASE),
        
        // Redirecionadores
        Regex("""redirect""", RegexOption.IGNORE_CASE),
        Regex("""click""", RegexOption.IGNORE_CASE),
        Regex("""link""", RegexOption.IGNORE_CASE),
        
        // Social
        Regex("""facebook\.com/plugins""", RegexOption.IGNORE_CASE),
        Regex("""twitter\.com/widgets""", RegexOption.IGNORE_CASE),
        
        // Estáticos desnecessários
        Regex(""".*\.css""", RegexOption.IGNORE_CASE),
        Regex(""".*\.js""", RegexOption.IGNORE_CASE),
        Regex(""".*\.png""", RegexOption.IGNORE_CASE),
        Regex(""".*\.jpg""", RegexOption.IGNORE_CASE),
        Regex(""".*\.gif""", RegexOption.IGNORE_CASE),
        Regex(""".*\.ico""", RegexOption.IGNORE_CASE),
        Regex("""favicon""", RegexOption.IGNORE_CASE),
        
        // Domínios específicos que você identificou
        Regex("""tynt\.com""", RegexOption.IGNORE_CASE),
        Regex("""dtscout\.com""", RegexOption.IGNORE_CASE),
        Regex("""mrktmtrcs""", RegexOption.IGNORE_CASE),
        Regex("""amung\.us""", RegexOption.IGNORE_CASE),
        Regex("""onaudience""", RegexOption.IGNORE_CASE),
        Regex("""eyeota\.net""", RegexOption.IGNORE_CASE)
    )
    
    // Padrões para CAPTURAR (M3U8 real)
    private val capturePatterns = listOf(
        Regex("""https?://[^/]+\.i8yz83pn\.com/.+\.m3u8""", RegexOption.IGNORE_CASE),
        Regex("""https?://[^/]+/hls2/.+\.m3u8""", RegexOption.IGNORE_CASE),
        Regex("""master\.m3u8""", RegexOption.IGNORE_CASE),
        Regex("""index\.m3u8""", RegexOption.IGNORE_CASE),
        Regex("""\.m3u8\?[^"\s]*""", RegexOption.IGNORE_CASE)
    )
    
    // Headers para fingir ser um navegador real
    private fun getStealthHeaders(): Map<String, String> {
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
            "Accept-Encoding" to "gzip, deflate, br",
            "Cache-Control" to "no-cache",
            "Pragma" to "no-cache",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "none",
            "Sec-Fetch-User" to "?1",
            "Upgrade-Insecure-Requests" to "1",
            "sec-ch-ua" to "\"Chromium\";v=\"127\", \"Not)A;Brand\";v=\"99\"",
            "sec-ch-ua-mobile" to "?1",
            "sec-ch-ua-platform" to "\"Android\""
        )
    }
    
    // Headers específicos para o M3U8
    private fun getM3u8Headers(referer: String): Map<String, String> {
        return mapOf(
            "Accept" to "*/*",
            "Accept-Encoding" to "gzip, deflate, br, zstd",
            "Accept-Language" to "pt-BR",
            "Cache-Control" to "no-cache",
            "Connection" to "keep-alive",
            "Origin" to "https://g9r6.com",
            "Pragma" to "no-cache",
            "Referer" to referer,
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
            "sec-ch-ua" to "\"Chromium\";v=\"127\", \"Not)A;Brand\";v=\"99\"",
            "sec-ch-ua-mobile" to "?1",
            "sec-ch-ua-platform" to "\"Android\""
        )
    }
    
    suspend fun extractWithSmartWebView(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🎯 SuperFlixWebViewExtractor iniciado")
        println("🌐 URL alvo: $url")
        
        val capturedM3u8Urls = mutableListOf<String>()
        var m3u8FinalUrl: String? = null
        
        try {
            val streamResolver = WebViewResolver(
                // Regex que captura TUDO, mas filtramos depois
                interceptUrl = Regex("""https?://[^"\s<>]+"""),
                useOkhttp = false,
                timeout = 120_000L, // 2 minutos timeout
                enableJavaScript = true,
                enableDomStorage = true,
                userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                
                // Callback para CADA requisição interceptada
                onUrlIntercepted = { interceptedUrl ->
                    // Debug: mostra todas as URLs (opcional)
                    // println("🔗 URL interceptada: $interceptedUrl")
                    
                    // Verifica se deve IGNORAR
                    val shouldIgnore = ignorePatterns.any { it.containsMatchIn(interceptedUrl) }
                    
                    if (shouldIgnore) {
                        // IGNORA completamente esta requisição
                        println("⛔ IGNORADO: $interceptedUrl")
                        return@WebViewResolver null // Não faz a requisição
                    }
                    
                    // Verifica se é um M3U8 que queremos CAPTURAR
                    val isM3u8 = capturePatterns.any { it.containsMatchIn(interceptedUrl) }
                    
                    if (isM3u8) {
                        println("🎯 M3U8 CAPTURADO: $interceptedUrl")
                        capturedM3u8Urls.add(interceptedUrl)
                        
                        // Verifica se é o M3U8 FINAL (baseado no padrão que você encontrou)
                        if (interceptedUrl.contains("i8yz83pn.com") && 
                            interceptedUrl.contains("/hls2/") &&
                            interceptedUrl.contains("master.m3u8")) {
                            m3u8FinalUrl = interceptedUrl
                            println("✅ M3U8 FINAL ENCONTRADO!")
                        }
                        
                        // Continua carregando a página, não interrompe
                        return@WebViewResolver null
                    }
                    
                    // Para outras URLs, permite o carregamento normal
                    println("✅ Permitindo: ${interceptedUrl.take(80)}...")
                    null
                }
            )
            
            // PRIMEIRO: Deixa a página carregar completamente
            println("⏳ Carregando página (pode levar até 2 minutos devido aos ads)...")
            
            val response = app.get(
                url,
                headers = getStealthHeaders(),
                interceptor = streamResolver,
                timeout = 125_000L // 125 segundos
            )
            
            println("📊 Estatísticas:")
            println("   - URLs M3U8 capturadas: ${capturedM3u8Urls.size}")
            println("   - HTML final: ${response.text.length} caracteres")
            
            // PRIORIDADE 1: Usa o M3U8 final encontrado durante interceptação
            if (m3u8FinalUrl != null) {
                println("🚀 Usando M3U8 final capturado: $m3u8FinalUrl")
                return generateM3u8Links(m3u8FinalUrl!!, name, callback)
            }
            
            // PRIORIDADE 2: Testa todas as URLs M3U8 capturadas (do último para o primeiro)
            if (capturedM3u8Urls.isNotEmpty()) {
                println("🔍 Testando ${capturedM3u8Urls.size} URLs M3U8 capturadas...")
                
                // Ordena: as últimas URLs são as mais prováveis de serem o stream final
                val urlsToTest = capturedM3u8Urls.takeLast(5).reversed()
                
                for ((index, m3u8Url) in urlsToTest.withIndex()) {
                    println("🧪 Testando M3U8 #${index + 1}: ${m3u8Url.take(100)}...")
                    
                    if (testAndGenerateM3u8(m3u8Url, name, callback)) {
                        return true
                    }
                }
            }
            
            // PRIORIDADE 3: Procura M3U8 no HTML final
            println("🔍 Procurando M3U8 no HTML final...")
            val htmlM3u8Urls = extractM3u8FromHtml(response.text)
            
            for (m3u8Url in htmlM3u8Urls.take(3)) {
                println("🧪 Testando M3U8 do HTML: ${m3u8Url.take(100)}...")
                
                if (testAndGenerateM3u8(m3u8Url, name, callback)) {
                    return true
                }
            }
            
            println("❌ Nenhum M3U8 funcionou")
            return false
            
        } catch (e: Exception) {
            println("❌ Erro no WebView: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
    
    private fun extractM3u8FromHtml(html: String): List<String> {
        val m3u8Urls = mutableListOf<String>()
        
        val patterns = listOf(
            Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""(https?://[^\s<>"']+\.m3u8)""", RegexOption.IGNORE_CASE),
            Regex("""src\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""hls\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""videoUrl\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
        )
        
        patterns.forEach { pattern ->
            val matches = pattern.findAll(html)
            matches.forEach { match ->
                var url = match.groupValues.getOrNull(1) ?: match.value
                
                // Limpeza da URL
                url = url.replace("\\/", "/")
                    .replace("\\\"", "")
                    .replace("\\\\", "")
                    .trim()
                
                if (url.isNotBlank() && !url.contains("ads") && url.startsWith("http")) {
                    if (!m3u8Urls.contains(url)) {
                        m3u8Urls.add(url)
                    }
                }
            }
        }
        
        return m3u8Urls
    }
    
    private suspend fun testAndGenerateM3u8(
        m3u8Url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🔄 Testando URL: ${m3u8Url.take(80)}...")
            
            // Teste rápido HEAD para ver se a URL existe
            val headers = getM3u8Headers("https://g9r6.com/")
            val testResponse = app.head(
                m3u8Url,
                headers = headers,
                timeout = 10_000L
            )
            
            if (testResponse.code in 200..299) {
                println("✅ URL válida, gerando links M3U8...")
                generateM3u8Links(m3u8Url, name, callback)
                return true
            } else {
                println("⚠️ URL retornou status ${testResponse.code}")
                false
            }
        } catch (e: Exception) {
            println("❌ Falha no teste: ${e.message}")
            false
        }
    }
    
    private suspend fun generateM3u8Links(
        m3u8Url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val headers = getM3u8Headers("https://g9r6.com/")
            
            M3u8Helper.generateM3u8(
                name,
                m3u8Url,
                referer = "https://g9r6.com/",
                headers = headers,
                quality = null // Detecta qualidade automaticamente
            ).forEach(callback)
            
            println("🎉 Links M3U8 gerados com sucesso!")
            true
        } catch (e: Exception) {
            println("❌ Erro ao gerar links M3U8: ${e.message}")
            false
        }
    }
    
    // Função principal do extrator
    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("=========================================")
        println("🚀 SuperFlix Extractor v2.0 (WebView Otimizado)")
        println("=========================================")
        
        // Tenta com WebView inteligente primeiro
        val success = extractWithSmartWebView(url, name, callback)
        
        if (!success) {
            println("⚠️ WebView falhou, tentando método alternativo...")
            // Pode adicionar fallback aqui se necessário
        }
        
        return success
    }
}
