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
        println("🎭 SuperFlixExtractor - Enganando o site")
        
        // Teste 1: Com User-Agent customizado
        println("🧪 Teste 1: User-Agent customizado")
        val test1 = tryWebViewWithCustomUA(url, name, callback)
        if (test1) return true
        
        // Teste 2: Com headers completos
        println("🧪 Teste 2: Headers completos")
        val test2 = tryWebViewWithFullHeaders(url, name, callback)
        if (test2) return true
        
        // Teste 3: Com timeout menor e regex diferente
        println("🧪 Teste 3: Regex mais amplo")
        return tryWebViewWithWideRegex(url, name, callback)
    }
    
    /**
     * Teste 1: User-Agent que parece Chrome desktop real
     */
    private suspend fun tryWebViewWithCustomUA(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val streamResolver = WebViewResolver(
                interceptUrl = Regex("""\.m3u8"""),
                useOkhttp = false,
                timeout = 30_000L  // 30 segundos apenas
            )
            
            val response = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                    "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
                    "Sec-Fetch-Dest" to "document",
                    "Sec-Fetch-Mode" to "navigate",
                    "Sec-Fetch-Site" to "none",
                    "Sec-Fetch-User" to "?1",
                    "Upgrade-Insecure-Requests" to "1",
                    "Cache-Control" to "max-age=0"
                ),
                interceptor = streamResolver
            )
            
            println("📍 URL final: ${response.url}")
            println("📄 Tamanho resposta: ${response.text.length}")
            
            // Procura M3U8 manualmente no HTML
            val html = response.text
            val m3u8Regex = Regex("""https?://[^"\s]+\.m3u8[^"\s]*""")
            val matches = m3u8Regex.findAll(html)
            
            val m3u8Urls = matches.map { it.value }.toList()
            
            if (m3u8Urls.isNotEmpty()) {
                println("✅ Encontrados ${m3u8Urls.size} M3U8 no HTML")
                m3u8Urls.forEach { println("   - $it") }
                
                // Usa o primeiro
                M3u8Helper.generateM3u8(
                    name,
                    m3u8Urls.first(),
                    "https://bysevepoin.com",
                    headers = mapOf(
                        "Referer" to "https://bysevepoin.com/",
                        "User-Agent" to "Mozilla/5.0"
                    )
                ).forEach(callback)
                
                return true
            }
            
            false
            
        } catch (e: Exception) {
            println("❌ Teste 1 falhou: ${e.message}")
            false
        }
    }
    
    /**
     * Teste 2: Headers COMPLETOS como um navegador real
     */
    private suspend fun tryWebViewWithFullHeaders(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // Primeiro, faz uma request "normal" para pegar cookies
            println("🍪 Obtendo cookies iniciais...")
            val initialResponse = app.get(
                "https://fembed.sx/",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
            )
            
            // Depois usa WebView com os cookies
            val streamResolver = WebViewResolver(
                interceptUrl = Regex("""(m3u8|master\.m3u8|iframes)"""),
                useOkhttp = false,
                timeout = 45_000L
            )
            
            val response = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                    "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
                    "Accept-Encoding" to "gzip, deflate, br",
                    "Connection" to "keep-alive",
                    "Upgrade-Insecure-Requests" to "1",
                    "Sec-Fetch-Dest" to "document",
                    "Sec-Fetch-Mode" to "navigate",
                    "Sec-Fetch-Site" to "cross-site",
                    "Pragma" to "no-cache",
                    "Cache-Control" to "no-cache"
                ),
                interceptor = streamResolver
            )
            
            val intercepted = response.url
            println("🎯 URL interceptada: $intercepted")
            
            if (intercepted.contains("m3u8")) {
                println("✅ M3U8 interceptado!")
                M3u8Helper.generateM3u8(
                    name,
                    intercepted,
                    "https://bysevepoin.com",
                    headers = mapOf(
                        "Referer" to "https://bysevepoin.com/",
                        "Origin" to "https://bysevepoin.com"
                    )
                ).forEach(callback)
                
                return true
            }
            
            false
            
        } catch (e: Exception) {
            println("❌ Teste 2 falhou: ${e.message}")
            false
        }
    }
    
    /**
     * Teste 3: Regex mais amplo para interceptar QUALQUER coisa
     */
    private suspend fun tryWebViewWithWideRegex(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🎯 Interceptando TUDO...")
            
            // Lista para armazenar TODAS as URLs que passarem
            val allUrls = mutableListOf<String>()
            
            val streamResolver = WebViewResolver(
                // Regex que pega QUALQUER URL que possa ser de vídeo
                interceptUrl = Regex("""(m3u8|mp4|mkv|video|stream|master|iframes|hls)"""),
                useOkhttp = false,
                timeout = 60_000L
            )
            
            val response = app.get(
                url,
                headers = getStealthHeaders(),
                interceptor = streamResolver
            )
            
            println("📊 WebView concluído")
            println("📍 URL final: ${response.url}")
            
            // Procura M3U8 no HTML também
            val html = response.text
            findM3u8InHtml(html).forEach { m3u8Url ->
                println("🔍 M3U8 encontrado no HTML: $m3u8Url")
                allUrls.add(m3u8Url)
            }
            
            // Tenta as URLs encontradas
            for (m3u8Url in allUrls.distinct().take(5)) {
                println("🧪 Tentando: $m3u8Url")
                try {
                    M3u8Helper.generateM3u8(
                        name,
                        m3u8Url,
                        "https://bysevepoin.com",
                        headers = mapOf("Referer" to "https://bysevepoin.com/")
                    ).forEach(callback)
                    
                    println("✅ Funcionou!")
                    return true
                } catch (e: Exception) {
                    println("❌ Falhou: ${e.message}")
                }
            }
            
            false
            
        } catch (e: Exception) {
            println("❌ Teste 3 falhou: ${e.message}")
            false
        }
    }
    
    /**
     * Headers para parecer um navegador REAL (não WebView)
     */
    private fun getStealthHeaders(): Map<String, String> {
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
            "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
            "Accept-Encoding" to "gzip, deflate, br",
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "none",
            "Sec-Fetch-User" to "?1",
            "Cache-Control" to "max-age=0",
            "DNT" to "1",
            "Pragma" to "no-cache"
        )
    }
    
    /**
     * Procura M3U8 no HTML usando múltiplos métodos
     */
    private fun findM3u8InHtml(html: String): List<String> {
        val urls = mutableListOf<String>()
        
        // Método 1: Regex simples
        val simpleRegex = Regex("""https?://[^"\s]+\.m3u8[^"\s]*""")
        urls.addAll(simpleRegex.findAll(html).map { it.value })
        
        // Método 2: Procura por padrões específicos
        val patterns = listOf(
            """master\.m3u8""",
            """iframes[^"\s]*\.m3u8""",
            """hls/[^"\s]+\.m3u8""",
            """stream[^"\s]+\.m3u8"""
        )
        
        patterns.forEach { pattern ->
            val regex = Regex("""https?://[^"\s]*$pattern[^"\s]*""")
            urls.addAll(regex.findAll(html).map { it.value })
        }
        
        // Método 3: Procura em JSON/JavaScript
        if (html.contains("m3u8")) {
            val contextRegex = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""")
            urls.addAll(contextRegex.findAll(html).map { it.groupValues[1] })
        }
        
        return urls.distinct().filterNot { it.contains("ads") || it.contains("google") }
    }
}
