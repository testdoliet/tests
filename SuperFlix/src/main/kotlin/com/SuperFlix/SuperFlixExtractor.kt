package com.SuperFlix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

object SuperFlixExtractor {
    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // PRIMEIRO: Detecta para onde a página redireciona
            val finalUrl = detectRedirectUrl(url)
            
            // SEGUNDO: Extrai do domínio correto (g9r6.com ou similar)
            extractFromCorrectDomain(finalUrl, mainUrl, name, callback)
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun detectRedirectUrl(initialUrl: String): String {
        return try {
            // Faz uma requisição HEAD para ver os redirecionamentos
            val response = app.get(initialUrl, followRedirects = true)
            response.url // Retorna a URL final após redirecionamentos
        } catch (e: Exception) {
            initialUrl // Fallback
        }
    }
    
    private suspend fun extractFromCorrectDomain(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // Verifica se é o domínio g9r6.com (ou similar)
            val isG9r6Domain = url.contains("g9r6.com") || 
                               url.contains("i8yz83pn.com") || 
                               url.contains("rcr82.waw05")
            
            // Se for, usa estratégia específica
            if (isG9r6Domain) {
                extractFromG9r6CDN(url, mainUrl, name, callback)
            } else {
                // Tenta extração genérica
                extractGeneric(url, mainUrl, name, callback)
            }
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun extractFromG9r6CDN(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // Headers EXATOS como você encontrou
            val headers = mapOf(
                "Referer" to "https://g9r6.com/",
                "Origin" to "https://g9r6.com/",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                "Accept" to "*/*",
                "Accept-Language" to "pt-BR",
                "Accept-Encoding" to "gzip, deflate, br",
                "Cache-Control" to "no-cache",
                "Pragma" to "no-cache",
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "cross-site",
                "sec-ch-ua" to "\"Chromium\";v=\"127\", \"Not)A;Brand\";v=\"99\"",
                "sec-ch-ua-mobile" to "?1",
                "sec-ch-ua-platform" to "\"Android\""
            )
            
            // Tenta encontrar o m3u8 no HTML
            val response = app.get(url, headers = headers)
            val html = response.text
            
            // Procura por padrões de m3u8 específicos deste CDN
            val patterns = listOf(
                // Padrão 1: URLs do CDN específico
                Regex("""https?://[a-z0-9]+\.rcr82\.waw05\.i8yz83pn\.com/[^"\s]+\.m3u8[^"\s]*"""),
                // Padrão 2: URLs com parâmetros t, s, e, f
                Regex("""https?://[^"\s]+\.m3u8\?t=[^&\s]+&s=[^&\s]+&e=[^&\s]+&f=[^&\s]+[^"\s]*"""),
                // Padrão 3: Em scripts JSON
                Regex(""""file"\s*:\s*"(https?://[^"]+\.m3u8[^"]*)""""),
                // Padrão 4: Em atributos data
                Regex("""data-(?:src|file)=["'](https?://[^"']+\.m3u8[^"']*)["']""")
            )
            
            for (pattern in patterns) {
                val matches = pattern.findAll(html)
                for (match in matches) {
                    val m3u8Url = match.value
                    if (m3u8Url.isNotBlank()) {
                        // Usa os MESMOS headers para o m3u8
                        M3u8Helper.generateM3u8(
                            name,
                            m3u8Url,
                            "https://g9r6.com/", // Referer correto
                            headers = headers
                        ).forEach(callback)
                        return true
                    }
                }
            }
            
            // Se não encontrou no HTML, tenta WebView com bloqueio de ads
            tryWebViewWithAdBlock(url, mainUrl, name, callback)
            
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun tryWebViewWithAdBlock(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // WebView com bloqueio AG-RESSIVO de ads
            val streamResolver = WebViewResolver(
                interceptUrl = { interceptedUrl ->
                    // SÓ intercepta URLs que são claramente do CDN de vídeo
                    val isVideoCDN = interceptedUrl.contains("rcr82.waw05.i8yz83pn.com") ||
                                     interceptedUrl.contains(".m3u8?t=") ||
                                     (interceptedUrl.contains(".m3u8") && 
                                      interceptedUrl.contains("&s=") && 
                                      interceptedUrl.contains("&e="))
                    
                    // BLOQUEIA TUDO que não for vídeo
                    if (!isVideoCDN) {
                        // Bloqueia ads, scripts, etc
                        val shouldBlock = interceptedUrl.contains("ad") ||
                                         interceptedUrl.contains("track") ||
                                         interceptedUrl.contains("analytics") ||
                                         interceptedUrl.endsWith(".js") ||
                                         interceptedUrl.endsWith(".css") ||
                                         interceptedUrl.contains("adsco") ||
                                         interceptedUrl.contains("tynt")
                        
                        if (shouldBlock) {
                            return@WebViewResolver false // BLOQUEADO
                        }
                    }
                    
                    isVideoCDN // Só intercepta se for do CDN de vídeo
                },
                useOkhttp = false,
                timeout = 10_000L
            )
            
            // Headers para fazer o WebView parecer um player legítimo
            val headers = mapOf(
                "Referer" to "https://g9r6.com/",
                "Origin" to "https://g9r6.com/",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
            )
            
            val response = app.get(url, headers = headers, interceptor = streamResolver)
            val intercepted = response.url
            
            if (intercepted.isNotEmpty() && intercepted.contains(".m3u8")) {
                val videoHeaders = mapOf(
                    "Referer" to "https://g9r6.com/",
                    "Origin" to "https://g9r6.com/",
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
                )
                
                M3u8Helper.generateM3u8(
                    name,
                    intercepted,
                    "https://g9r6.com/",
                    headers = videoHeaders
                ).forEach(callback)
                
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun extractGeneric(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // Método genérico para outros domínios
            val streamResolver = WebViewResolver(
                interceptUrl = Regex("""\.m3u8(\?.*)?$"""),
                useOkhttp = false,
                timeout = 8_000L
            )
            
            val response = app.get(url, interceptor = streamResolver)
            val intercepted = response.url
            
            if (intercepted.isNotEmpty() && intercepted.contains(".m3u8")) {
                val headers = mapOf(
                    "Referer" to url,
                    "Origin" to mainUrl
                )
                
                M3u8Helper.generateM3u8(
                    name,
                    intercepted,
                    mainUrl,
                    headers = headers
                ).forEach(callback)
                
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
}
