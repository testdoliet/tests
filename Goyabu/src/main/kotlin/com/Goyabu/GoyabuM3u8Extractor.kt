package com.Goyabu

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

object GoyabuM3u8Extractor {
    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔍 GOYABU M3U8 EXTRACTOR: Analisando URL: $url")
        
        return try {
            // PRIMEIRA TENTATIVA: WebView para interceptar o iframe
            println("🔄 Iniciando WebView para carregar JavaScript e interceptar iframe...")
            
            val streamResolver = WebViewResolver(
                interceptUrl = Regex("""(\.m3u8|anivideo\.net|cdn-s01\.mywallpaper)"""),
                useOkhttp = false,
                timeout = 15_000L // 15 segundos deve ser suficiente
            )

            val response = app.get(url, interceptor = streamResolver)
            val interceptedUrl = response.url
            
            println("📡 URL interceptada: $interceptedUrl")
            
            // Se interceptou um M3U8 diretamente
            if (interceptedUrl.contains(".m3u8")) {
                println("🎯 M3U8 interceptado: $interceptedUrl")
                
                val headers = mapOf(
                    "Referer" to url,
                    "Origin" to mainUrl,
                    "User-Agent" to "Mozilla/5.0"
                )
                
                M3u8Helper.generateM3u8(
                    name,
                    interceptedUrl,
                    mainUrl,
                    headers = headers
                ).forEach(callback)
                
                return true
            }
            
            // SEGUNDA TENTATIVA: Analisar o HTML após o JavaScript carregar
            println("⚠️ Tentando analisar HTML após JS...")
            val htmlResponse = app.get(url)
            val html = htmlResponse.text
            
            // Padrões específicos para encontrar o iframe com id="player"
            val playerPatterns = listOf(
                // Procura exatamente pelo iframe com id="player"
                """<iframe[^>]+id=["']player["'][^>]+src=["']([^"']+)["'][^>]*>""".toRegex(),
                // Procura por iframe dentro de div com id="player-content"
                """<div[^>]+id=["']player-content["'][^>]*>.*?<iframe[^>]+src=["']([^"']+)["'][^>]*>""".toRegex(RegexOption.DOT_MATCHES_ALL),
                // Procura por qualquer iframe com anivideo.net
                """<iframe[^>]+src=["'](https?://[^"']*anivideo\.net[^"']*)["'][^>]*>""".toRegex(),
                // Procura pelo padrão da API
                """src=["'](https?://api\.anivideo\.net/[^"']+)["']""".toRegex()
            )
            
            for (pattern in playerPatterns) {
                val match = pattern.find(html)
                if (match != null) {
                    val iframeSrc = match.groupValues[1]
                    println("🎯 Iframe encontrado: $iframeSrc")
                    
                    // Processar a URL do iframe
                    return processIframeUrl(iframeSrc, url, mainUrl, name, callback)
                }
            }
            
            // TERCEIRA TENTATIVA: Buscar em scripts JavaScript
            println("⚠️ Buscando em scripts JavaScript...")
            val scriptPattern = """(https?://api\.anivideo\.net/[^"'\s]+)""".toRegex()
            val scriptMatches = scriptPattern.findAll(html).toList()
            
            for (scriptMatch in scriptMatches) {
                val apiUrl = scriptMatch.groupValues[1]
                if (apiUrl.contains("anivideo.net") && apiUrl.contains("m3u8")) {
                    println("🎯 API URL encontrada no JS: $apiUrl")
                    return processIframeUrl(apiUrl, url, mainUrl, name, callback)
                }
            }
            
            println("❌ GOYABU M3U8 EXTRACTOR: Não encontrou iframe do player")
            false
            
        } catch (e: Exception) {
            println("❌ GOYABU M3U8 EXTRACTOR: Erro: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    private suspend fun processIframeUrl(
        iframeUrl: String,
        referer: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔗 Processando URL do iframe: $iframeUrl")
        
        return try {
            // Se a URL já contém .m3u8 (pode ser direta ou via API)
            if (iframeUrl.contains(".m3u8")) {
                // Extrair o M3U8 real da URL da API
                val m3u8Url = extractM3u8FromApiUrl(iframeUrl)
                
                println("🎯 M3U8 extraído: $m3u8Url")
                
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
                
                true
            } else {
                // Se não tem .m3u8, pode ser a URL da API que precisa ser resolvida
                println("🔄 Fazendo requisição para API: $iframeUrl")
                val apiResponse = app.get(iframeUrl, referer = referer)
                val apiHtml = apiResponse.text
                
                // Procurar por M3U8 na resposta da API
                val m3u8Pattern = """(https?://[^"'\s]+\.m3u8[^"'\s]*)""".toRegex()
                val m3u8Match = m3u8Pattern.find(apiHtml)
                
                if (m3u8Match != null) {
                    val m3u8Url = m3u8Match.groupValues[1]
                    println("🎯 M3U8 encontrado na API: $m3u8Url")
                    
                    val headers = mapOf(
                        "Referer" to iframeUrl,
                        "Origin" to mainUrl,
                        "User-Agent" to "Mozilla/5.0"
                    )
                    
                    M3u8Helper.generateM3u8(
                        name,
                        m3u8Url,
                        mainUrl,
                        headers = headers
                    ).forEach(callback)
                    
                    true
                } else {
                    println("❌ Não encontrou M3U8 na resposta da API")
                    false
                }
            }
        } catch (e: Exception) {
            println("❌ Erro ao processar iframe: ${e.message}")
            false
        }
    }
    
    private fun extractM3u8FromApiUrl(apiUrl: String): String {
        // Exemplo: https://api.anivideo.net/videohls.php?d=https://cdn-s01.mywallpaper...net/stream/1/invencivel-3-dublado/01.mp4/index.m3u8&nocache1740152558
        // Extrair o parâmetro d= que contém o M3U8 real
        
        val m3u8Pattern = """[?&]d=([^&]+)""".toRegex()
        val match = m3u8Pattern.find(apiUrl)
        
        return if (match != null) {
            val encodedUrl = match.groupValues[1]
            java.net.URLDecoder.decode(encodedUrl, "UTF-8")
        } else {
            // Se não encontrar o parâmetro d=, retorna a URL como está
            apiUrl
        }
    }
}
