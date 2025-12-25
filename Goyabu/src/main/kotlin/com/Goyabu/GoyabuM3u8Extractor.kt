package com.Goyabu

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import kotlinx.coroutines.delay

object GoyabuPlayerExtractor {
    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🎬 GOYABU PLAYER EXTRACTOR: Analisando URL: $url")
        
        return try {
            // ESTRATÉGIA 1: WebView com comportamento mais realista
            println("🔧 Estratégia 1: WebView com interceptação inteligente...")
            val success = tryWebViewExtraction(url, mainUrl, name, callback)
            
            if (success) return true
            
            // ESTRATÉGIA 2: Simular requisições AJAX que o site faz
            println("🔧 Estratégia 2: Simulando requisições AJAX...")
            return simulateAjaxRequests(url, mainUrl, name, callback)
            
        } catch (e: Exception) {
            println("❌ GOYABU PLAYER EXTRACTOR: Erro: ${e.message}")
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
            // WebView com regex mais específico para capturar a API anivideo
            val streamResolver = WebViewResolver(
                interceptUrl = Regex("""(anivideo\.net|\.mp4/index\.m3u8|videohls\.php)"""),
                useOkhttp = false,
                timeout = 25_000L // 25 segundos
            )
            
            println("🌐 WebView iniciado (25s timeout)...")
            val response = app.get(url, interceptor = streamResolver)
            val interceptedUrl = response.url
            
            println("📡 URL interceptada: $interceptedUrl")
            
            if (interceptedUrl.contains("anivideo.net")) {
                println("✅ API anivideo interceptada!")
                return processAnivideoUrl(interceptedUrl, url, mainUrl, name, callback)
            } else if (interceptedUrl.contains(".m3u8")) {
                println("✅ M3U8 interceptado diretamente!")
                return processM3u8Url(interceptedUrl, url, mainUrl, name, callback)
            }
            
            false
        } catch (e: Exception) {
            println("⚠️ WebView falhou: ${e.message}")
            false
        }
    }
    
    private suspend fun simulateAjaxRequests(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // 1. Buscar a página principal
            println("📄 Buscando página HTML...")
            val response = app.get(url)
            val html = response.text
            
            // 2. Procurar por scripts que podem fazer requisições AJAX
            val scripts = extractScriptUrls(html)
            
            // 3. Procurar por endpoints de API no HTML
            val apiEndpoints = findApiEndpoints(html)
            
            // 4. Combinar todas as URLs suspeitas
            val allUrls = scripts + apiEndpoints + listOf(
                "$url?player=true",
                "$url&player=true",
                "${url.removeSuffix("/")}/player",
                "${url.removeSuffix("/")}/ajax"
            )
            
            // 5. Testar cada URL para encontrar o player
            for (testUrl in allUrls.distinct()) {
                println("🔍 Testando URL: $testUrl")
                
                try {
                    val testResponse = app.get(testUrl, headers = mapOf(
                        "Referer" to url,
                        "X-Requested-With" to "XMLHttpRequest",
                        "User-Agent" to "Mozilla/5.0"
                    ))
                    
                    val testHtml = testResponse.text
                    
                    // Procurar por iframe player na resposta
                    val iframePatterns = listOf(
                        """<iframe[^>]+src=["']([^"']+anivideo\.net[^"']+)["']""".toRegex(),
                        """["']player_url["']\s*:\s*["']([^"']+)["']""".toRegex(),
                        """["']iframe["']\s*:\s*["']([^"']+)["']""".toRegex(),
                        """(https?://api\.anivideo\.net/[^"'\s]+)""".toRegex()
                    )
                    
                    for (pattern in iframePatterns) {
                        val match = pattern.find(testHtml)
                        if (match != null) {
                            val foundUrl = match.groupValues[1]
                            println("🎯 URL do player encontrada: $foundUrl")
                            
                            if (foundUrl.contains("anivideo.net")) {
                                return processAnivideoUrl(foundUrl, url, mainUrl, name, callback)
                            }
                        }
                    }
                    
                    // Pequeno delay para não sobrecarregar
                    delay(100)
                    
                } catch (e: Exception) {
                    // Ignorar erros e continuar testando
                    continue
                }
            }
            
            // 6. Última tentativa: procurar por dados JSON no HTML
            println("🔍 Buscando dados JSON no HTML...")
            val jsonPattern = """\{[^{}]*["']player["'][^{}]*\}""".toRegex()
            val jsonMatches = jsonPattern.findAll(html).toList()
            
            for (match in jsonMatches) {
                val jsonStr = match.value
                if (jsonStr.contains("anivideo") || jsonStr.contains("m3u8")) {
                    println("📦 JSON encontrado: ${jsonStr.take(100)}...")
                    
                    // Extrair URL do JSON
                    val urlPattern = """["'](https?://[^"']+)["']""".toRegex()
                    val urlMatch = urlPattern.find(jsonStr)
                    
                    if (urlMatch != null) {
                        val jsonUrl = urlMatch.groupValues[1]
                        if (jsonUrl.contains("anivideo.net")) {
                            return processAnivideoUrl(jsonUrl, url, mainUrl, name, callback)
                        }
                    }
                }
            }
            
            println("❌ Nenhuma API encontrada nas simulações")
            false
            
        } catch (e: Exception) {
            println("❌ Erro na simulação AJAX: ${e.message}")
            false
        }
    }
    
    private fun extractScriptUrls(html: String): List<String> {
        val scripts = mutableListOf<String>()
        val scriptPattern = """<script[^>]+src=["']([^"']+)["'][^>]*>""".toRegex()
        
        val matches = scriptPattern.findAll(html).toList()
        for (match in matches) {
            val scriptUrl = match.groupValues[1]
            if (scriptUrl.contains("player") || scriptUrl.contains("video") || 
                scriptUrl.contains("ajax") || scriptUrl.endsWith(".js")) {
                scripts.add(scriptUrl)
            }
        }
        
        return scripts
    }
    
    private fun findApiEndpoints(html: String): List<String> {
        val endpoints = mutableListOf<String>()
        val patterns = listOf(
            """["'](https?://[^"']+/api/[^"']+)["']""".toRegex(),
            """["'](https?://[^"']+/ajax/[^"']+)["']""".toRegex(),
            """["'](https?://[^"']+/player/[^"']+)["']""".toRegex(),
            """["'](https?://[^"']+/load/[^"']+)["']""".toRegex()
        )
        
        for (pattern in patterns) {
            val matches = pattern.findAll(html).toList()
            for (match in matches) {
                endpoints.add(match.groupValues[1])
            }
        }
        
        return endpoints
    }
    
    private suspend fun processAnivideoUrl(
        apiUrl: String,
        referer: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔗 Processando URL da API: $apiUrl")
        
        try {
            // Extrair parâmetro d= da URL
            val m3u8Url = extractM3u8FromApiUrl(apiUrl)
            
            if (m3u8Url.isNotEmpty()) {
                println("✅ M3U8 extraído: $m3u8Url")
                return processM3u8Url(m3u8Url, referer, mainUrl, name, callback)
            } else {
                // Se não tem parâmetro d=, fazer requisição à API
                println("🔄 Fazendo requisição à API...")
                val apiResponse = app.get(apiUrl, headers = mapOf(
                    "Referer" to referer,
                    "User-Agent" to "Mozilla/5.0"
                ))
                
                val apiContent = apiResponse.text
                
                // Procurar M3U8 na resposta
                val m3u8Pattern = """(https?://[^"'\s]+\.m3u8[^"'\s]*)""".toRegex()
                val match = m3u8Pattern.find(apiContent)
                
                if (match != null) {
                    val foundM3u8 = match.groupValues[1]
                    println("✅ M3U8 encontrado na API: $foundM3u8")
                    return processM3u8Url(foundM3u8, apiUrl, mainUrl, name, callback)
                }
            }
        } catch (e: Exception) {
            println("❌ Erro ao processar API: ${e.message}")
        }
        
        return false
    }
    
    private suspend fun processM3u8Url(
        m3u8Url: String,
        referer: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
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
            
            println("✅ Vídeo M3U8 processado com sucesso!")
            return true
        } catch (e: Exception) {
            println("❌ Erro ao processar M3U8: ${e.message}")
            return false
        }
    }
    
    private fun extractM3u8FromApiUrl(apiUrl: String): String {
        return try {
            val dParamPattern = """[?&]d=([^&]+)""".toRegex()
            val match = dParamPattern.find(apiUrl)
            
            if (match != null) {
                val encodedUrl = match.groupValues[1]
                java.net.URLDecoder.decode(encodedUrl, "UTF-8")
            } else {
                // Verificar se já é um M3U8
                if (apiUrl.contains(".m3u8")) apiUrl else ""
            }
        } catch (e: Exception) {
            ""
        }
    }
}
