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
            // ESTRATÉGIA 1: WebView com foco TOTAL no padrão específico
            println("🔧 Estratégia 1: WebView focado no padrão anivideo...")
            val webViewSuccess = tryWebViewStrategy(url, mainUrl, name, callback)
            
            if (webViewSuccess) {
                println("✅ GOYABU: WebView encontrou o iframe!")
                return true
            }
            
            // ESTRATÉGIA 2: Simular comportamento do navegador
            println("🔧 Estratégia 2: Simulação de navegador...")
            val simulationSuccess = tryBrowserSimulation(url, mainUrl, name, callback)
            
            if (simulationSuccess) {
                println("✅ GOYABU: Simulação encontrou o iframe!")
                return true
            }
            
            println("❌ GOYABU: Nenhuma estratégia funcionou")
            false
            
        } catch (e: Exception) {
            println("❌ GOYABU EXTRACTOR: Erro: ${e.message}")
            false
        }
    }
    
    // ============ ESTRATÉGIA 1: WebView Focado ============
    private suspend fun tryWebViewStrategy(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // WebView com timeout MAIOR e foco EXCLUSIVO no padrão específico
            val streamResolver = WebViewResolver(
                interceptUrl = Regex("""(anivideo\.net/videohls\.php|videohls\.php\?d=)"""),
                useOkhttp = false,
                timeout = 45_000L // 45 segundos - tempo suficiente pro JS rodar
            )
            
            println("🌐 WebView iniciado (45s timeout, foco no padrão)...")
            val response = app.get(url, interceptor = streamResolver)
            val interceptedUrl = response.url
            
            println("📡 URL interceptada pelo WebView: $interceptedUrl")
            
            // Se interceptou a URL da API, processar
            if (interceptedUrl.contains("anivideo.net") && interceptedUrl.contains("videohls.php")) {
                println("🎯 EXATO! URL da API interceptada: $interceptedUrl")
                return extractAndProcessM3u8FromApi(interceptedUrl, url, mainUrl, name, callback)
            }
            
            false
        } catch (e: Exception) {
            println("⚠️ WebView falhou: ${e.message}")
            false
        }
    }
    
    // ============ ESTRATÉGIA 2: Simulação de Navegador ============
    private suspend fun tryBrowserSimulation(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // PRIMEIRA ETAPA: Carregar página com headers de navegador real
            println("🖥️ Simulando navegador real...")
            val response = app.get(url, headers = getRealBrowserHeaders())
            val html = response.text
            
            // SEGUNDA ETAPA: Analisar o HTML após o JS ter rodado (se houver)
            val doc = Jsoup.parse(html)
            
            // BUSCA DIRETA: Procurar exatamente o iframe com o padrão específico
            println("🔍 Buscando iframe com padrão exato...")
            
            // Padrão 1: iframe com src contendo anivideo.net/videohls.php
            val iframes = doc.select("iframe[src]")
            println("📊 ${iframes.size} iframes encontrados na página")
            
            for (iframe in iframes) {
                val src = iframe.attr("src")
                println("🔗 Iframe src: ${src.take(100)}...")
                
                if (src.contains("anivideo.net/videohls.php") && src.contains("?d=")) {
                    println("🎯 IFrame encontrado! Processando...")
                    return extractAndProcessM3u8FromApi(src, url, mainUrl, name, callback)
                }
            }
            
            // Padrão 2: Procurar em scripts que podem ter injetado o iframe
            println("🔍 Procurando em scripts JS...")
            val scripts = doc.select("script:not([src])")
            
            for (script in scripts) {
                val scriptContent = script.html()
                
                // Procurar pela URL exata no JS
                val apiPattern = Regex("""https?://api\.anivideo\.net/videohls\.php\?d=[^"'\s]+""")
                val matches = apiPattern.findAll(scriptContent).toList()
                
                for (match in matches) {
                    val apiUrl = match.value
                    println("🎯 URL encontrada no JS: $apiUrl")
                    return extractAndProcessM3u8FromApi(apiUrl, url, mainUrl, name, callback)
                }
                
                // Procurar por partes da URL
                if (scriptContent.contains("anivideo.net") && scriptContent.contains("videohls.php")) {
                    println("⚠️ Padrão encontrado no JS, tentando extrair...")
                    
                    // Tentar extrair URL mais complexa
                    val complexPattern = Regex("""["'](https?://[^"']*anivideo\.net[^"']*)["']""")
                    val complexMatches = complexPattern.findAll(scriptContent).toList()
                    
                    for (complexMatch in complexMatches) {
                        val possibleUrl = complexMatch.groupValues[1]
                        if (possibleUrl.contains("videohls.php") && possibleUrl.contains("?d=")) {
                            println("🎯 URL extraída do JS: $possibleUrl")
                            return extractAndProcessM3u8FromApi(possibleUrl, url, mainUrl, name, callback)
                        }
                    }
                }
            }
            
            false
        } catch (e: Exception) {
            println("❌ Erro na simulação: ${e.message}")
            false
        }
    }
    
    // ============ FUNÇÃO PRINCIPAL DE EXTRAÇÃO ============
    private suspend fun extractAndProcessM3u8FromApi(
        apiUrl: String,
        referer: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔗 Extraindo M3U8 da API: ${apiUrl.take(100)}...")
        
        return try {
            // ETAPA 1: Extrair parâmetro d= da URL
            val dParamRegex = Regex("""[?&]d=([^&]+)""")
            val match = dParamRegex.find(apiUrl)
            
            if (match != null) {
                val encodedM3u8 = match.groupValues[1]
                val m3u8Url = URLDecoder.decode(encodedM3u8, "UTF-8")
                
                println("✅ M3U8 decodificado: $m3u8Url")
                println("📊 Comprimento do URL: ${m3u8Url.length} caracteres")
                
                // Verificar se é um URL válido
                if (m3u8Url.startsWith("http") && m3u8Url.contains(".m3u8")) {
                    return processM3u8Stream(m3u8Url, referer, mainUrl, name, callback)
                } else {
                    println("⚠️ URL decodificado não parece ser M3U8 válido")
                }
            } else {
                println("⚠️ Não encontrou parâmetro d= na URL da API")
            }
            
            // ETAPA 2: Se não conseguiu extrair do parâmetro, fazer requisição à API
            println("🔄 Fazendo requisição direta à API...")
            val apiResponse = app.get(apiUrl, headers = mapOf(
                "Referer" to referer,
                "User-Agent" to "Mozilla/5.0"
            ))
            
            val apiContent = apiResponse.text
            println("📄 Conteúdo da API: ${apiContent.take(500)}...")
            
            // Procurar M3U8 na resposta
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
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            )
            
            println("📡 Gerando links M3U8...")
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
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
            "Accept-Encoding" to "gzip, deflate, br",
            "DNT" to "1",
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "none",
            "Sec-Fetch-User" to "?1",
            "Cache-Control" to "max-age=0"
        )
    }
}
