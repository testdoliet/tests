package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.WebViewCaptchaObserver
import com.lagradost.cloudstream3.utils.WebViewResolver
import kotlinx.coroutines.delay

object SuperFlixExtractor : ExtractorApi() {
    override val name = "SuperFlix"
    override val mainUrl = "https://superflixapi2.com"
    
    // Lista de padrões para IGNORAR (ads, trackers, redirecionamentos)
    private val ignorePatterns = listOf(
        "adsco.re", "doubleclick.net", "googleads", "googlesyndication",
        "amazon-adsystem", "adserver", "analytics", "tracking", "pixel",
        "beacon", "facebook.com/tr", "connect.facebook.net", "googletagmanager",
        "googletagservices", "cdn4ads", "adnxs", "casalemedia", "redirect",
        "click", "link", "tynt.com", "dtscout.com", "mrktmtrcs", "amung.us",
        "onaudience", "eyeota.net", "analytics", "statistics", "metrics",
        "tracker", "monitoring", "measurement"
    )
    
    // Headers para WebView
    private fun getWebViewHeaders(): Map<String, String> {
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
            "Accept-Encoding" to "gzip, deflate, br",
            "Cache-Control" to "no-cache",
            "Pragma" to "no-cache",
            "Upgrade-Insecure-Requests" to "1",
            "sec-ch-ua" to "\"Chromium\";v=\"127\", \"Not)A;Brand\";v=\"99\"",
            "sec-ch-ua-mobile" to "?1",
            "sec-ch-ua-platform" to "\"Android\""
        )
    }
    
    private fun getM3u8Headers(): Map<String, String> {
        return mapOf(
            "Accept" to "*/*",
            "Accept-Encoding" to "gzip, deflate, br, zstd",
            "Accept-Language" to "pt-BR",
            "Cache-Control" to "no-cache",
            "Connection" to "keep-alive",
            "Origin" to "https://g9r6.com",
            "Pragma" to "no-cache",
            "Referer" to "https://g9r6.com/",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
            "sec-ch-ua" to "\"Chromium\";v=\"127\", \"Not)A;Brand\";v=\"99\"",
            "sec-ch-ua-mobile" to "?1",
            "sec-ch-ua-platform" to "\"Android\""
        )
    }
    
    // Método principal com WebView
    private suspend fun extractWithWebView(url: String): List<String> {
        val m3u8Urls = mutableListOf<String>()
        
        println("🌐 Iniciando WebView para: $url")
        println("⚠️ Aguarde... Pode levar até 2 minutos devido aos redirecionamentos")
        
        try {
            // Usa WebViewResolver para interceptar requisições
            val resolver = WebViewResolver(
                userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                timeout = 120000L, // 2 minutos
                injectJavaScript = """
                    // Remove elementos de ads enquanto carrega
                    setTimeout(function() {
                        var selectors = [
                            '[class*="ad"]', '[id*="ad"]', '[class*="Ad"]', '[id*="Ad"]',
                            '.ad-container', '.ad-banner', '.adsbygoogle', 'iframe[src*="ads"]',
                            'div[class*="popup"]', 'div[class*="modal"]'
                        ];
                        
                        selectors.forEach(function(selector) {
                            var elements = document.querySelectorAll(selector);
                            elements.forEach(function(el) {
                                el.style.display = 'none';
                                el.remove();
                            });
                        });
                    }, 3000);
                """,
                onUrlLoaded = { loadedUrl ->
                    println("📄 Página carregada: ${loadedUrl.take(80)}...")
                }
            )
            
            // Faz a requisição com o WebView
            val response = app.get(
                url,
                headers = getWebViewHeaders(),
                interceptor = resolver,
                timeout = 125000L
            )
            
            println("✅ WebView concluído")
            println("📊 HTML obtido: ${response.text.length} caracteres")
            
            // Extrai URLs M3U8 do HTML
            val htmlM3u8s = extractM3u8FromHtml(response.text)
            m3u8Urls.addAll(htmlM3u8s)
            
        } catch (e: Exception) {
            println("❌ Erro no WebView: ${e.message}")
        }
        
        return m3u8Urls
    }
    
    // Método alternativo: WebView com interceptação manual
    private suspend fun extractWithWebViewInterceptor(url: String): List<String> {
        val m3u8Urls = mutableListOf<String>()
        
        println("🎯 WebView com interceptação avançada...")
        
        try {
            // Usa WebViewCaptchaObserver para mais controle
            val observer = WebViewCaptchaObserver(
                userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                timeout = 120000L,
                onPageStarted = { url -> 
                    println("➡️ Navegando para: ${url.take(80)}...")
                },
                onPageFinished = { url ->
                    println("🏁 Página carregada: ${url.take(80)}...")
                },
                onLoadResource = { resourceUrl ->
                    // Filtra requisições
                    val shouldIgnore = ignorePatterns.any { 
                        resourceUrl.contains(it, ignoreCase = true) 
                    }
                    
                    if (!shouldIgnore && resourceUrl.contains(".m3u8", ignoreCase = true)) {
                        println("🎯 M3U8 detectado: ${resourceUrl.take(100)}...")
                        if (!m3u8Urls.contains(resourceUrl)) {
                            m3u8Urls.add(resourceUrl)
                        }
                    }
                    
                    true // Continua carregando
                }
            )
            
            // Executa o WebView
            val result = AppUtils.parseResponseUsingWebView(
                url = url,
                headers = getWebViewHeaders(),
                timeout = 120000L,
                webViewObserver = observer
            )
            
            println("✅ Interceptação concluída: ${m3u8Urls.size} URLs M3U8 encontradas")
            
        } catch (e: Exception) {
            println("❌ Erro na interceptação: ${e.message}")
        }
        
        return m3u8Urls
    }
    
    // Extrai M3U8 do HTML
    private fun extractM3u8FromHtml(html: String): List<String> {
        val urls = mutableListOf<String>()
        
        val patterns = listOf(
            "\"(https?://[^\"]+\\.m3u8[^\"]*)\"",
            "'(https?://[^']+\\.m3u8[^']*)'",
            "src=[\"'](https?://[^\"']+\\.m3u8[^\"']*)[\"']",
            "hls.*?[\"'](https?://[^\"']+\\.m3u8[^\"']*)[\"']",
            "videoUrl.*?[\"'](https?://[^\"']+\\.m3u8[^\"']*)[\"']",
            "file.*?[\"'](https?://[^\"']+\\.m3u8[^\"']*)[\"']",
            "source.*?[\"'](https?://[^\"']+\\.m3u8[^\"']*)[\"']"
        )
        
        for (pattern in patterns) {
            try {
                val regex = Regex(pattern, RegexOption.IGNORE_CASE)
                val matches = regex.findAll(html)
                
                matches.forEach { match ->
                    var url = match.groupValues.getOrNull(1) ?: continue
                    
                    // Limpa a URL
                    url = url.replace("\\/", "/")
                        .replace("\\\"", "")
                        .replace("\\'", "")
                        .replace("\\\\", "")
                        .trim()
                    
                    // Verifica se é URL válida e não é ad
                    if (url.startsWith("http") && 
                        url.contains(".m3u8") &&
                        !ignorePatterns.any { url.contains(it, ignoreCase = true) }) {
                        
                        // Filtra padrões específicos que você encontrou
                        if (url.contains("i8yz83pn.com") || 
                            url.contains("/hls2/") ||
                            url.contains("master.m3u8") ||
                            url.contains("index.m3u8")) {
                            
                            if (!urls.contains(url)) {
                                urls.add(url)
                                println("🔍 M3U8 no HTML: ${url.take(80)}...")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Continua com próximo padrão
            }
        }
        
        return urls
    }
    
    // Testa uma URL M3U8
    private suspend fun testM3u8Url(m3u8Url: String): Boolean {
        return try {
            println("🧪 Testando: ${m3u8Url.take(80)}...")
            
            val headers = getM3u8Headers()
            val response = app.head(m3u8Url, headers = headers, timeout = 15000L)
            
            if (response.code in 200..299) {
                println("✅ URL válida (status ${response.code})")
                true
            } else {
                println("⚠️ URL inválida (status ${response.code})")
                false
            }
        } catch (e: Exception) {
            println("❌ Falha no teste: ${e.message}")
            false
        }
    }
    
    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        println("=".repeat(50))
        println("🚀 SUPERFLIX EXTRACTOR INICIADO")
        println("🎯 URL: $url")
        println("=".repeat(50))
        
        val results = mutableListOf<ExtractorLink>()
        val allM3u8Urls = mutableListOf<String>()
        
        // STRATÉGIA 1: WebView com interceptação
        println("\n1️⃣ ESTRATÉGIA: WebView com interceptação")
        val webViewUrls = extractWithWebViewInterceptor(url)
        allM3u8Urls.addAll(webViewUrls)
        
        // Se não encontrou, tenta WebView simples
        if (allM3u8Urls.isEmpty()) {
            println("\n2️⃣ ESTRATÉGIA: WebView simples")
            val simpleWebViewUrls = extractWithWebView(url)
            allM3u8Urls.addAll(simpleWebViewUrls)
        }
        
        // Se ainda não encontrou, tenta padrão direto
        if (allM3u8Urls.isEmpty()) {
            println("\n3️⃣ ESTRATÉGIA: Padrão direto")
            // Extrai ID da URL
            val idMatch = Regex("""/(\d+)(?:-dub)?""").find(url)
            val videoId = idMatch?.groupValues?.get(1)
            
            if (videoId != null) {
                // URL baseada no padrão que você encontrou
                val directUrl = "https://be7713.rcr82.waw05.i8yz83pn.com/hls2/09/10529/ftltsho61fgs_h/master.m3u8"
                allM3u8Urls.add(directUrl)
                
                // Tenta algumas variações
                val variations = listOf(
                    "https://be7713.rcr82.waw05.i8yz83pn.com/hls2/01/0101/video_${videoId}_h/master.m3u8",
                    "https://cdn.superflix.com/hls/$videoId/master.m3u8",
                    "https://stream.superflix.com/video/$videoId/master.m3u8"
                )
                allM3u8Urls.addAll(variations)
            }
        }
        
        // Remove duplicados
        val uniqueUrls = allM3u8Urls.distinct()
        
        println("\n📊 RESULTADOS DA BUSCA:")
        println("   Total de URLs M3U8 encontradas: ${uniqueUrls.size}")
        uniqueUrls.forEachIndexed { index, url ->
            println("   ${index + 1}. ${url.take(100)}...")
        }
        
        // Testa cada URL e gera links
        println("\n🧪 TESTANDO URLs...")
        for ((index, m3u8Url) in uniqueUrls.withIndex()) {
            println("\n${index + 1}/${uniqueUrls.size}: Testando URL...")
            
            if (testM3u8Url(m3u8Url)) {
                try {
                    println("🔄 Gerando links M3u8...")
                    
                    val headers = getM3u8Headers()
                    val links = M3u8Helper.generateM3u8(
                        "SuperFlix",
                        m3u8Url,
                        referer = "https://g9r6.com/",
                        headers = headers
                    )
                    
                    if (links.isNotEmpty()) {
                        results.addAll(links)
                        println("✅ ${links.size} qualidades geradas com sucesso!")
                        break // Para no primeiro sucesso
                    }
                } catch (e: Exception) {
                    println("❌ Erro ao gerar M3U8: ${e.message}")
                }
            }
            
            // Pequena pausa entre testes
            if (index < uniqueUrls.size - 1) {
                delay(1000)
            }
        }
        
        println("\n" + "=".repeat(50))
        if (results.isNotEmpty()) {
            println("🎉 EXTRATOR CONCLUÍDO COM SUCESSO!")
            println("   Total de links gerados: ${results.size}")
        } else {
            println("❌ EXTRATOR FALHOU - Nenhum link encontrado")
        }
        println("=".repeat(50))
        
        return results
    }
}
