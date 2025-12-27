package com.SuperFlix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

object SuperFlixExtractor {
    // JavaScript para BLOQUEAR anúncios e ACELERAR
    private val adBlockScript = """
        // ============================================
        // BLOQUEADOR DE ANÚNCIOS ULTRA-RÁPIDO
        // ============================================
        
        // 1. BLOQUEIA POPUPS IMEDIATAMENTE
        (function() {
            window.alert = function() { console.log('Popup bloqueado'); };
            window.confirm = function() { return true; };
            window.prompt = function() { return ""; };
            window.open = function() { 
                console.log('Window.open bloqueado');
                return window; 
            };
            
            // Bloqueia antes mesmo de carregar
            Object.defineProperty(window, 'open', {
                value: function() { return window; },
                writable: false
            });
        })();
        
        // 2. REMOVE ELEMENTOS DE ADS DO DOM
        function removeAds() {
            try {
                // Lista de seletores de anúncios
                var adSelectors = [
                    'div[class*="ad"]', 'div[id*="ad"]',
                    'iframe[src*="ad"]', 'iframe[src*="ads"]',
                    'ins.adsbygoogle', '.adsbygoogle',
                    '[class*="banner"]', '[id*="banner"]',
                    '[class*="Ad"]', '[id*="Ad"]',
                    '.ad-container', '.ad-wrapper',
                    '.ad-unit', '.advertisement',
                    'script[src*="ads"]', 'script[src*="adserver"]'
                ];
                
                adSelectors.forEach(function(selector) {
                    var elements = document.querySelectorAll(selector);
                    elements.forEach(function(el) {
                        el.parentNode?.removeChild(el);
                    });
                });
                
                // Remove qualquer elemento com altura/width pequena (provavelmente ad)
                var allElements = document.querySelectorAll('div, iframe, img, ins');
                allElements.forEach(function(el) {
                    var rect = el.getBoundingClientRect();
                    if ((rect.width < 200 && rect.height < 100) || 
                        (rect.width > 0 && rect.height === 0) ||
                        (el.innerHTML.includes('ad') || el.innerHTML.includes('Ad'))) {
                        el.parentNode?.removeChild(el);
                    }
                });
                
                console.log('Anúncios removidos do DOM');
            } catch(e) {}
        }
        
        // 3. BLOQUEIA REQUESTS DE ADS
        (function() {
            // Bloqueia fetch
            var originalFetch = window.fetch;
            if (originalFetch) {
                window.fetch = function() {
                    var url = arguments[0];
                    if (typeof url === 'string' && isAdUrl(url)) {
                        console.log('Fetch bloqueado:', url);
                        return Promise.reject(new Error('AdBlock'));
                    }
                    return originalFetch.apply(this, arguments);
                };
            }
            
            // Bloqueia XMLHttpRequest
            var originalOpen = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function() {
                this._url = arguments[1];
                return originalOpen.apply(this, arguments);
            };
            
            var originalSend = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.send = function() {
                if (this._url && isAdUrl(this._url)) {
                    console.log('XHR bloqueado:', this._url);
                    return;
                }
                return originalSend.apply(this, arguments);
            };
            
            function isAdUrl(url) {
                var adKeywords = [
                    'adsco', 'tynt', 'dtscout', 'dtscdn',
                    'cdn4ads', 'doubleclick', 'googleads',
                    'cloudflareinsights', 'amung.us',
                    'onaudience', 'mrktmtrcs', 'adserver',
                    'adsystem', 'googlesyndication',
                    'facebook.com/tr', 'track', 'analytics',
                    'banner', 'popup', 'promo'
                ];
                
                var lowerUrl = url.toLowerCase();
                return adKeywords.some(function(keyword) {
                    return lowerUrl.includes(keyword);
                });
            }
        })();
        
        // 4. PROCURA ATIVAMENTE POR M3U8
        function searchForM3U8() {
            try {
                // Verifica no DOM
                var elements = document.querySelectorAll('a, iframe, video, source, link');
                for (var i = 0; i < elements.length; i++) {
                    var el = elements[i];
                    var src = el.src || el.href || el.data || '';
                    if (src.includes('.m3u8')) {
                        console.log('🎯 M3U8 encontrado no DOM:', src);
                        // Força redirecionamento para interceptar
                        window.location.href = src;
                        return;
                    }
                }
                
                // Verifica em scripts
                var scripts = document.querySelectorAll('script');
                for (var j = 0; j < scripts.length; j++) {
                    var script = scripts[j];
                    if (script.textContent) {
                        var m3u8Match = script.textContent.match(/(https?:\/\/[^"'\s]+\.m3u8[^"'\s]*)/);
                        if (m3u8Match) {
                            console.log('🎯 M3U8 encontrado em script:', m3u8Match[0]);
                            window.location.href = m3u8Match[0];
                            return;
                        }
                    }
                }
                
                // Verifica no body inteiro
                var bodyText = document.body.innerHTML;
                var m3u8Matches = bodyText.match(/(https?:\/\/[^"'\s]+\.m3u8[^"'\s]*)/g);
                if (m3u8Matches) {
                    console.log('🎯 M3U8 encontrado no body:', m3u8Matches[0]);
                    window.location.href = m3u8Matches[0];
                    return;
                }
            } catch(e) {}
        }
        
        // 5. EXECUTA PERIODICAMENTE
        // Executa imediatamente
        setTimeout(removeAds, 100);
        setTimeout(searchForM3U8, 200);
        
        // Executa a cada 500ms
        setInterval(removeAds, 500);
        setInterval(searchForM3U8, 1000);
        
        // 6. ACELERA A PÁGINA
        // Desativa animações
        document.body.style.animation = 'none';
        document.body.style.transition = 'none';
        
        console.log('✅ Bloqueador de ads ativado - Procurando M3U8...');
    """.trimIndent()

    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🚀 SuperFlixExtractor com bloqueador de ads")
            
            // PRIMEIRO: Tenta método DIRETO (sem WebView)
            val directSuccess = tryDirectExtraction(url, mainUrl, name, callback)
            if (directSuccess) {
                println("✅ Método direto funcionou!")
                return true
            }
            
            println("⚠️  Método direto falhou, tentando WebView...")
            
            // SEGUNDO: WebView com bloqueador
            val streamResolver = WebViewResolver(
                interceptUrl = Regex("""\.m3u8"""),
                useOkhttp = false,
                timeout = 25_000L, // 25 segundos (com ads bloqueados é rápido)
                additionalSettings = { webView ->
                    // CONFIGURAÇÕES PARA BLOQUEAR ADS
                    webView.settings.apply {
                        // Desativa recursos que carregam ads
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        // Otimizações para velocidade
                        loadWithOverviewMode = true
                        useWideViewPort = true
                        builtInZoomControls = false
                        displayZoomControls = false
                        setSupportZoom(false)
                        setGeolocationEnabled(false)
                        // Desativa coisas desnecessárias
                        mediaPlaybackRequiresUserGesture = false
                    }
                    
                    // INJETA O BLOQUEADOR DE ADS
                    webView.evaluateJavascript(adBlockScript, null)
                    
                    // WebViewClient customizado para bloquear URLs
                    webView.webViewClient = object : android.webkit.WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: android.webkit.WebView?,
                            request: android.webkit.WebResourceRequest?
                        ): Boolean {
                            val requestUrl = request?.url?.toString() ?: ""
                            
                            // BLOQUEIA URLs DE ANÚNCIOS
                            if (isAdUrl(requestUrl)) {
                                println("🚫 Bloqueando URL de ad: $requestUrl")
                                return true // Bloqueia o carregamento
                            }
                            
                            // PERMITE URLs NORMAIS
                            return super.shouldOverrideUrlLoading(view, request)
                        }
                        
                        override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            // Re-injeta o script após a página carregar
                            webView.evaluateJavascript(adBlockScript, null)
                            println("✅ Página carregada, bloqueador reinjetado")
                        }
                    }
                }
            )

            // Adiciona headers para parecer um navegador normal
            val headers = mapOf(
                "User-Agent" to getUserAgent(),
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
                "Referer" to mainUrl
            )
            
            val response = app.get(url, headers = headers, interceptor = streamResolver)
            val intercepted = response.url

            if (intercepted.isNotEmpty() && intercepted.contains(".m3u8")) {
                println("✅ M3U8 obtido em ${System.currentTimeMillis()}ms")
                
                val m3u8Headers = mapOf(
                    "Referer" to "https://fembed.sx/",
                    "Origin" to "https://fembed.sx",
                    "User-Agent" to getUserAgent(),
                    "Accept" to "*/*"
                )

                M3u8Helper.generateM3u8(
                    name,
                    intercepted,
                    "https://fembed.sx",
                    headers = m3u8Headers
                ).forEach(callback)

                return true
            }
            
            println("❌ Nenhum M3U8 encontrado via WebView")
            false
            
        } catch (e: Exception) {
            println("❌ Erro no extrator: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    // Método direto SEM WebView (mais rápido)
    private suspend fun tryDirectExtraction(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // Tenta extrair ID do Fembed
            val fembedId = extractFembedId(url)
            if (fembedId == null) {
                println("⚠️  Não é URL do Fembed, tentando métodos alternativos...")
                return false
            }
            
            println("🔍 Tentando API do Fembed com ID: $fembedId")
            
            // Tenta diferentes APIs do Fembed
            val apiUrls = listOf(
                "https://fembed.com/api/source/$fembedId",
                "https://www.fembed.com/api/source/$fembedId",
                "https://fembed.sx/api/source/$fembedId",
                "https://www.fembed.sx/api/source/$fembedId"
            )
            
            for (apiUrl in apiUrls) {
                try {
                    println("📡 Tentando API: $apiUrl")
                    
                    val response = app.post(
                        apiUrl,
                        headers = mapOf(
                            "Referer" to url,
                            "User-Agent" to getUserAgent(),
                            "X-Requested-With" to "XMLHttpRequest"
                        ),
                        data = mapOf("r" to "")
                    )
                    
                    val json = response.parsedSafe<Map<String, Any>>()
                    val data = json?.get("data") as? List<Map<String, Any>>
                    
                    if (data != null && data.isNotEmpty()) {
                        // Pega todas as qualidades
                        data.forEach { item ->
                            val file = item["file"] as? String
                            val label = item["label"] as? String
                            
                            if (file != null && file.contains("m3u8")) {
                                println("✅ M3U8 via API: ${label ?: "Desconhecido"} - $file")
                                
                                M3u8Helper.generateM3u8(
                                    "${name} (${label ?: "SD"})",
                                    file,
                                    "https://fembed.com",
                                    headers = mapOf(
                                        "Referer" to "https://fembed.com/",
                                        "User-Agent" to getUserAgent()
                                    )
                                ).forEach(callback)
                            }
                        }
                        
                        return true
                    }
                } catch (e: Exception) {
                    println("⚠️  API falhou: ${e.message}")
                    // Continua para próxima API
                }
            }
            
            false
        } catch (e: Exception) {
            false
        }
    }
    
    private fun extractFembedId(url: String): String? {
        return try {
            // Extrai ID de várias formas de URL do Fembed
            val patterns = listOf(
                """fembed\.(?:com|sx)/[ev]/([^/?]+)""",
                """fembed\.(?:com|sx)/f/([^/?]+)""",
                """fembed\.(?:com|sx)/v/([^/?]+)"""
            )
            
            patterns.forEach { pattern ->
                val regex = pattern.toRegex(RegexOption.IGNORE_CASE)
                val match = regex.find(url)
                if (match != null) {
                    return match.groupValues[1]
                }
            }
            
            null
        } catch (e: Exception) {
            null
        }
    }
    
    private fun isAdUrl(url: String): Boolean {
        val adDomains = listOf(
            "adsco.re", "tynt.com", "dtscout", "dtscdn",
            "cdn4ads", "doubleclick", "googleads",
            "cloudflareinsights", "amung.us",
            "onaudience", "mrktmtrcs", "adserver",
            "googlesyndication", "facebook.com/tr",
            "analytics", "tracking", "banner",
            "promo", "popup", "advert"
        )
        
        val lowerUrl = url.lowercase()
        return adDomains.any { lowerUrl.contains(it) }
    }
    
    private fun getUserAgent(): String {
        return "Mozilla/5.0 (Linux; Android 13; SM-G991B) " +
               "AppleWebKit/537.36 (KHTML, like Gecko) " +
               "Chrome/120.0.6099.144 Mobile Safari/537.36"
    }
}
