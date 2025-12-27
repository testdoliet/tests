package com.SuperFlix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

object SuperFlixExtractor {
    // JavaScript para BLOQUEAR anúncios
    private val adBlockScript = """
        // Bloqueia popups
        window.alert = function() {};
        window.confirm = function() { return true; };
        window.prompt = function() { return ""; };
        window.open = function() { return window; };
        
        // Remove elementos de anúncios comuns
        setTimeout(function() {
            // Remove divs de anúncios
            var adSelectors = [
                '[id*="ad"]', '[class*="ad"]', 
                '[id*="Ad"]', '[class*="Ad"]',
                '[id*="banner"]', '[class*="banner"]',
                'iframe[src*="ads"]', 'iframe[src*="ad"]',
                'div[data-ad]', 'ins.adsbygoogle',
                '.ad-container', '.ad-wrapper',
                '.adsense', '.ad-unit'
            ];
            
            adSelectors.forEach(function(selector) {
                var elements = document.querySelectorAll(selector);
                elements.forEach(function(el) {
                    el.remove();
                });
            });
            
            // Bloqueia requests de anúncios
            var originalFetch = window.fetch;
            window.fetch = function() {
                var url = arguments[0];
                if (typeof url === 'string') {
                    if (url.includes('ads') || 
                        url.includes('adserver') || 
                        url.includes('doubleclick') ||
                        url.includes('googleads') ||
                        url.includes('adsco') ||
                        url.includes('tynt') ||
                        url.includes('dtscout')) {
                        console.log('Bloqueando fetch de anúncio:', url);
                        return Promise.reject(new Error('Ad blocked'));
                    }
                }
                return originalFetch.apply(this, arguments);
            };
            
            // Bloqueia XMLHttpRequest de anúncios
            var originalXHROpen = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function() {
                var url = arguments[1];
                if (url && (
                    url.includes('ads') || 
                    url.includes('adserver') || 
                    url.includes('doubleclick') ||
                    url.includes('googleads') ||
                    url.includes('adsco') ||
                    url.includes('tynt') ||
                    url.includes('dtscout'))) {
                    console.log('Bloqueando XHR de anúncio:', url);
                    this._shouldBlock = true;
                    return;
                }
                originalXHROpen.apply(this, arguments);
            };
            
            XMLHttpRequest.prototype.send = function() {
                if (this._shouldBlock) {
                    console.log('Request de anúncio bloqueado');
                    return;
                }
                return XMLHttpRequest.prototype.send.apply(this, arguments);
            };
            
        }, 1000);
        
        // Redireciona imediatamente se encontrar M3U8
        var checkForM3U8 = function() {
            var links = document.querySelectorAll('a, iframe, source, video');
            links.forEach(function(link) {
                var src = link.src || link.href || '';
                if (src.includes('.m3u8')) {
                    console.log('M3U8 encontrado no DOM:', src);
                    // Envia para o interceptor
                    window.location.href = src;
                }
            });
            
            // Verifica scripts também
            var scripts = document.querySelectorAll('script');
            scripts.forEach(function(script) {
                if (script.textContent.includes('.m3u8')) {
                    var match = script.textContent.match(/https?:\/\/[^"'\s]+\.m3u8[^"'\s]*/);
                    if (match) {
                        console.log('M3U8 encontrado em script:', match[0]);
                        window.location.href = match[0];
                    }
                }
            });
        };
        
        // Executa a verificação periodicamente
        setInterval(checkForM3U8, 500);
        checkForM3U8();
    """.trimIndent()

    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🔍 SuperFlixExtractor com bloqueador de ads")
            
            val streamResolver = WebViewResolver(
                interceptUrl = Regex("""\.m3u8"""),
                useOkhttp = false,
                timeout = 20_000L, // Só 20 segundos porque sem ads é rápido!
                
                // INJETA JavaScript ANTES de carregar a página
                onPageStarted = { webView ->
                    // Injeta nosso script bloqueador
                    webView.evaluateJavascript(adBlockScript) {
                        println("✅ Script bloqueador injetado")
                    }
                },
                
                // Filtra URLs de anúncios
                urlFilter = { interceptedUrl ->
                    val lowerUrl = interceptedUrl.lowercase()
                    val isAd = lowerUrl.contains("adsco") || 
                              lowerUrl.contains("tynt") ||
                              lowerUrl.contains("dtscout") ||
                              lowerUrl.contains("dtscdn") ||
                              lowerUrl.contains("cdn4ads") ||
                              lowerUrl.contains("doubleclick") ||
                              lowerUrl.contains("googleads") ||
                              lowerUrl.contains("cloudflareinsights") ||
                              lowerUrl.contains("amung.us") ||
                              lowerUrl.contains("onaudience") ||
                              lowerUrl.contains("mrktmtrcs")
                    
                    if (isAd) {
                        println("🚫 Bloqueando: $interceptedUrl")
                        false
                    } else if (interceptedUrl.contains("m3u8")) {
                        println("🎯 M3U8 interceptado: $interceptedUrl")
                        true
                    } else {
                        true
                    }
                }
            )

            val response = app.get(url, interceptor = streamResolver)
            val intercepted = response.url

            if (intercepted.isNotEmpty() && intercepted.contains(".m3u8")) {
                println("✅ Link final obtido em ${System.currentTimeMillis()}ms")
                
                val headers = mapOf(
                    "Referer" to "https://fembed.sx/",
                    "Origin" to "https://fembed.sx",
                    "User-Agent" to getUserAgent(),
                    "Accept" to "application/x-mpegURL,application/vnd.apple.mpegurl,*/*",
                    "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8"
                )

                M3u8Helper.generateM3u8(
                    "$name (Sem Ads)",
                    intercepted,
                    "https://fembed.sx",
                    headers = headers
                ).forEach(callback)

                true
            } else {
                println("❌ Timeout ou nenhum M3U8 encontrado")
                false
            }
        } catch (e: Exception) {
            println("❌ Erro: ${e.message}")
            false
        }
    }
    
    private fun getUserAgent(): String {
        // User-Agent mobile pode carregar menos ads
        return "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.144 Mobile Safari/537.36"
    }
}
