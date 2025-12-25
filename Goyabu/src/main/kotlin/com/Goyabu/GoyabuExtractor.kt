package com.Goyabu

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.jsoup.Jsoup
import java.net.URLDecoder

object GoyabuExtractor {
    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🎬 GOYABU EXTRACTOR: Iniciando extração para: $url")
        
        return try {
            // ESTRATÉGIA 1: WebView simples com timeout maior
            println("🔧 Estratégia 1: WebView com timeout longo...")
            val webViewSuccess = trySimpleWebView(url, mainUrl, name, callback)
            
            if (webViewSuccess) {
                println("✅ GOYABU: WebView funcionou!")
                return true
            }
            
            // ESTRATÉGIA 2: Simulação manual de clique via requisições
            println("🔧 Estratégia 2: Simulação manual de ações...")
            val manualSuccess = tryManualActions(url, mainUrl, name, callback)
            
            if (manualSuccess) {
                println("✅ GOYABU: Simulação manual funcionou!")
                return true
            }
            
            println("❌ GOYABU: Nenhuma estratégia funcionou")
            false
            
        } catch (e: Exception) {
            println("❌ GOYABU EXTRACTOR: Erro: ${e.message}")
            false
        }
    }
    
    // ============ ESTRATÉGIA 1: WebView Simples ============
    private suspend fun trySimpleWebView(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val streamResolver = WebViewResolver(
                interceptUrl = Regex("""(anivideo\.net/videohls\.php|videohls\.php\?d=)"""),
                useOkhttp = false,
                timeout = 45_000L // 45 segundos
            )
            
            println("🌐 WebView iniciado (45s timeout)...")
            val response = app.get(url, interceptor = streamResolver)
            val interceptedUrl = response.url
            
            println("📡 URL interceptada: $interceptedUrl")
            
            if (interceptedUrl.contains("anivideo.net") && interceptedUrl.contains("videohls.php")) {
                println("🎯 API interceptada!")
                return extractAndProcessM3u8FromApi(interceptedUrl, url, mainUrl, name, callback)
            }
            
            false
        } catch (e: Exception) {
            println("⚠️ WebView falhou: ${e.message}")
            false
        }
    }
    
    // ============ ESTRATÉGIA 2: Ações Manuais ============
    private suspend fun tryManualActions(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("👆 Tentando simular ações do usuário...")
            
            // 1. Primeira requisição para obter a página
            val initialResponse = app.get(url, headers = getRealBrowserHeaders())
            val initialHtml = initialResponse.text
            val doc = Jsoup.parse(initialHtml)
            
            // 2. Procurar por tokens/IDs que possam ser usados para carregar o player
            println("🔍 Analisando página para encontrar triggers...")
            
            // Padrões comuns para encontrar dados do player
            val patterns = listOf(
                // Procura por data-player-id, data-video-id, etc.
                """data-(?:player|video)-?id\s*=\s*["']([^"']+)["']""".toRegex(),
                """id\s*=\s*["'](player[^"']*)["']""".toRegex(),
                """["']player_id["']\s*:\s*["']([^"']+)["']""".toRegex(),
                """["']episode_id["']\s*:\s*["']([^"']+)["']""".toRegex(),
                """["']token["']\s*:\s*["']([^"']+)["']""".toRegex()
            )
            
            val foundIds = mutableListOf<String>()
            
            for (pattern in patterns) {
                val matches = pattern.findAll(initialHtml)
                matches.forEach { match ->
                    val id = match.groupValues[1]
                    if (id.isNotBlank() && !foundIds.contains(id)) {
                        foundIds.add(id)
                        println("🔑 ID encontrado: $id")
                    }
                }
            }
            
            // 3. Tentar URLs comuns de API com os IDs encontrados
            for (id in foundIds) {
                val apiUrls = listOf(
                    "https://api.anivideo.net/load.php?id=$id",
                    "https://api.anivideo.net/player.php?id=$id",
                    "https://api.anivideo.net/video.php?id=$id",
                    "https://api.anivideo.net/embed.php?id=$id",
                    "$url?player_id=$id",
                    "$url&player_id=$id",
                    "$url?load_player=$id",
                    "$url&load_player=$id"
                )
                
                for (apiUrl in apiUrls) {
                    try {
                        println("📡 Tentando API: $apiUrl")
                        val apiResponse = app.get(apiUrl, headers = mapOf(
                            "Referer" to url,
                            "X-Requested-With" to "XMLHttpRequest",
                            "User-Agent" to "Mozilla/5.0"
                        ))
                        
                        val apiText = apiResponse.text
                        
                        // Procurar por URL da API anivideo na resposta
                        val anivideoPattern = Regex("""https?://api\.anivideo\.net/videohls\.php\?d=[^"'\s]+""")
                        val match = anivideoPattern.find(apiText)
                        
                        if (match != null) {
                            val foundApiUrl = match.value
                            println("🎯 API encontrada na resposta!")
                            return extractAndProcessM3u8FromApi(foundApiUrl, url, mainUrl, name, callback)
                        }
                        
                        // Procurar por iframe na resposta
                        val iframePattern = Regex("""<iframe[^>]+src=["']([^"']*anivideo\.net[^"']*)["']""")
                        val iframeMatch = iframePattern.find(apiText)
                        
                        if (iframeMatch != null) {
                            val iframeUrl = iframeMatch.groupValues[1]
                            println("🎯 Iframe encontrado na resposta!")
                            return extractAndProcessM3u8FromApi(iframeUrl, url, mainUrl, name, callback)
                        }
                    } catch (e: Exception) {
                        // Continuar tentando outras URLs
                        continue
                    }
                }
            }
            
            // 4. Se não encontrou IDs, tentar requisições comuns de player
            println("🔍 Tentando endpoints comuns de player...")
            
            val commonEndpoints = listOf(
                "$url?action=get_player",
                "$url&action=get_player",
                "$url?ajax=get_player",
                "$url&ajax=get_player",
                "$url?load=player",
                "$url&load=player",
                "${url.removeSuffix("/")}/ajax/player",
                "${url.removeSuffix("/")}/ajax/get_player"
            )
            
            for (endpoint in commonEndpoints) {
                try {
                    println("📡 Tentando endpoint: $endpoint")
                    val endpointResponse = app.get(endpoint, headers = mapOf(
                        "Referer" to url,
                        "X-Requested-With" to "XMLHttpRequest",
                        "User-Agent" to "Mozilla/5.0"
                    ))
                    
                    val endpointText = endpointResponse.text
                    
                    // Procurar URL da API
                    val apiPattern = Regex("""https?://api\.anivideo\.net/videohls\.php\?d=[^"'\s]+""")
                    val apiMatch = apiPattern.find(endpointText)
                    
                    if (apiMatch != null) {
                        val apiUrl = apiMatch.value
                        println("🎯 API encontrada no endpoint!")
                        return extractAndProcessM3u8FromApi(apiUrl, url, mainUrl, name, callback)
                    }
                } catch (e: Exception) {
                    continue
                }
            }
            
            false
        } catch (e: Exception) {
            println("❌ Erro nas ações manuais: ${e.message}")
            false
        }
    }
    
    // ============ FUNÇÃO DE EXTRAÇÃO DO M3U8 ============
    private suspend fun extractAndProcessM3u8FromApi(
        apiUrl: String,
        referer: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔗 Extraindo M3U8 da API: ${apiUrl.take(100)}...")
        
        return try {
            // Extrair parâmetro d=
            val dParamRegex = Regex("""[?&]d=([^&]+)""")
            val match = dParamRegex.find(apiUrl)
            
            if (match != null) {
                val encodedM3u8 = match.groupValues[1]
                val m3u8Url = URLDecoder.decode(encodedM3u8, "UTF-8")
                
                println("✅ M3U8 decodificado: $m3u8Url")
                
                if (m3u8Url.startsWith("http") && m3u8Url.contains(".m3u8")) {
                    return processM3u8Stream(m3u8Url, referer, mainUrl, name, callback)
                }
            }
            
            // Fallback: requisição direta
            println("🔄 Fazendo requisição direta à API...")
            val apiResponse = app.get(apiUrl, headers = mapOf(
                "Referer" to referer,
                "User-Agent" to "Mozilla/5.0"
            ))
            
            val apiContent = apiResponse.text
            val m3u8Pattern = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""", RegexOption.IGNORE_CASE)
            val m3u8Match = m3u8Pattern.find(apiContent)
            
            if (m3u8Match != null) {
                val m3u8Url = m3u8Match.groupValues[1]
                println("✅ M3U8 encontrado na resposta: $m3u8Url")
                return processM3u8Stream(m3u8Url, apiUrl, mainUrl, name, callback)
            }
            
            println("❌ Não encontrou M3U8 na API")
            false
            
        } catch (e: Exception) {
            println("❌ Erro ao processar API: ${e.message}")
            false
        }
    }
    
    // ============ PROCESSAR STREAM M3U8 ============
    private suspend fun processM3u8Stream(
        m3u8Url: String,
        referer: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔗 Processando stream M3U8: $m3u8Url")
        
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
            
            println("✅ Stream M3U8 processado com sucesso!")
            true
            
        } catch (e: Exception) {
            println("❌ Erro ao processar stream: ${e.message}")
            false
        }
    }
    
    // ============ HEADERS DE NAVEGADOR REAL ============
    private fun getRealBrowserHeaders(): Map<String, String> {
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
            "DNT" to "1",
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1"
        )
    }
}
