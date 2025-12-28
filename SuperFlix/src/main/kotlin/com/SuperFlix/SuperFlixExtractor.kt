package com.SuperFlix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.json.JSONObject
import java.net.URLEncoder

object SuperFlixExtractor {
    // Cookie atualizado
    private const val API_COOKIE = "SITE_TOTAL_ID=aTYqe6GU65PNmeCXpelwJwAAAMi; __dtsu=104017651574995957BEB724C6373F9E; __cc_id=a44d1e52993b9c2Oaaf40eba24989a06; __cc_cc=ACZ4nGNQSDQXsTFMNTWyTDROskw2MkhMTDDMXSE1KNDKxtLBMNDBjAIJMC4fgVe%2B%2F%2BDngAHemT8XsDBKrv%2FF3%2F2F2%2F%2FF0ZGhFP15u.VnW-1Y0o8o6/84-1.2.1.1-4_OXh2hYevsbO8hINijDKB8O_SPowh.pNojloHEbwX_qZorbmW8u8zqV9B7UsV6bbRmCWx_dD17mA7vJJklpOD9WBh9DA0wMV2a1QSKuR2J3FN9.TRzOUM4AhnTGFd8dJH8bHfqQdY7uYuUg7Ny1TVQDF9kXqyEPtnmkZ9rFkqQ2KS6u0t2hhFdQvRBY7dqyGfdjmyjDqwc7ZOovHB0eqep.FPHrh8T9iz1LuucA; cf_clearance=rfIEldahI7B..Y4PpZhGgwi.QOJBqIRGdFP150.VnW-1766868784-1.1-"
    
    // Contador de requisições
    private var requestCount = 0
    
    suspend fun extractVideoLinks(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🎯 SuperFlixExtractor: Iniciando extração...")
            println("🔗 URL recebida: $url")
            
            // Resetar contador
            requestCount = 0
            
            // 1. Extrair ID do vídeo
            val videoId = extractVideoId(url)
            if (videoId == null) {
                println("❌ Não consegui extrair ID da URL")
                return false
            }
            
            println("✅ ID extraído: $videoId")
            
            // 2. Construir URL do bysevepoin
            val bysevepoinUrl = buildBysevepoinUrl(videoId, url)
            println("🎬 URL do Bysevepoin: $bysevepoinUrl")
            
            // 3. Usar WebViewResolver para interceptar requisições
            return interceptRequestsWithCounter(bysevepoinUrl, name, callback)
            
        } catch (e: Exception) {
            println("💥 Erro na extração: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    private fun extractVideoId(url: String): String? {
        incrementCounter("extractVideoId")
        val patterns = listOf(
            Regex("""/e/([a-zA-Z0-9]+)"""),
            Regex("""/v/([a-zA-Z0-9]+)"""),
            Regex("""/videos/([a-zA-Z0-9]+)"""),
            Regex("""\?id=([a-zA-Z0-9]+)"""),
            Regex("""&id=([a-zA-Z0-9]+)"""),
            Regex("""/([a-zA-Z0-9]{6,})""")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null) {
                val id = match.groupValues[1]
                if (id.length >= 6 && !id.contains("/") && !id.contains("?")) {
                    return id
                }
            }
        }
        
        return null
    }
    
    private fun buildBysevepoinUrl(videoId: String, originalUrl: String): String {
        incrementCounter("buildBysevepoinUrl")
        return when {
            originalUrl.contains("bysevepoin") -> originalUrl
            originalUrl.contains("byseepoin") -> {
                // Converter byseepoin para bysevepoin
                originalUrl.replace("byseepoin.com", "bysevepoin.com")
            }
            originalUrl.contains("fembed") -> {
                // Converter fembed para bysevepoin
                "https://bysevepoin.com/e/$videoId"
            }
            else -> "https://bysevepoin.com/e/$videoId"
        }
    }
    
    private suspend fun interceptRequestsWithCounter(
        playerUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🌐 Iniciando WebView com contador...")
            println("🎯 URL: $playerUrl")
            
            // Configurar WebViewResolver
            val streamResolver = WebViewResolver(
                interceptUrl = Regex(""".*\.m3u8.*"""),
                useOkhttp = false,
                timeout = 90_000L
            )
            
            // Headers
            val headers = mapOf(
                "Cookie" to API_COOKIE,
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
                "Referer" to "https://superflix21.lol/"
            )
            
            println("📡 Fazendo requisição #${incrementCounter("interceptRequestsWithCounter")} para WebView...")
            
            // Fazer requisição com WebView
            val response = app.get(playerUrl, headers = headers, interceptor = streamResolver)
            
            println("📊 ESTATÍSTICAS DE REQUISIÇÃO:")
            println("   Total de requisições feitas: $requestCount")
            println("   Status Code: ${response.code}")
            println("   URL final: ${response.url}")
            println("   Tamanho da resposta: ${response.text.length} caracteres")
            
            val interceptedUrl = response.url
            
            // Verificar se interceptamos m3u8
            if (interceptedUrl.isNotEmpty() && interceptedUrl.contains(".m3u8")) {
                println("✅ URL m3u8 interceptada!")
                println("🎬 M3U8: $interceptedUrl")
                
                // Gerar links M3U8
                return generateM3u8LinksWithCounter(interceptedUrl, playerUrl, name, callback)
            } else {
                println("⚠️  Nenhuma URL m3u8 interceptada diretamente")
                println("📄 Conteúdo (primeiros 500 chars): ${response.text.take(500)}...")
                
                // Procurar m3u8 no HTML
                val m3u8Url = findM3u8InHtml(response.text)
                if (m3u8Url != null) {
                    println("🔍 URL m3u8 encontrada no HTML: $m3u8Url")
                    return generateM3u8LinksWithCounter(m3u8Url, playerUrl, name, callback)
                }
                
                println("❌ Não consegui encontrar URL m3u8")
                return false
            }
            
        } catch (e: Exception) {
            println("💥 Erro no WebViewResolver: ${e.message}")
            false
        }
    }
    
    private fun findM3u8InHtml(html: String): String? {
        incrementCounter("findM3u8InHtml")
        val patterns = listOf(
            Regex("""(https?://[^"\s]+\.m3u8[^"\s]*)"""),
            Regex("""["'](https?://[^"']+\.m3u8)["']"""),
            Regex("""file["']?\s*:\s*["']([^"']+\.m3u8)["']"""),
            Regex("""src["']?\s*:\s*["']([^"']+\.m3u8)["']"""),
            Regex("""hls["']?\s*:\s*["']([^"']+\.m3u8)["']"""),
            Regex("""(https?://[^"\s]+/hls/[^"\s]+\.m3u8)"""),
            Regex("""(https?://[^"\s]+/hls2/[^"\s]+\.m3u8)""")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(html)
            if (match != null) {
                var url = match.groupValues[1]
                url = url.replace("\\/", "/")
                return url
            }
        }
        
        return null
    }
    
    private suspend fun generateM3u8LinksWithCounter(
        m3u8Url: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🔄 Gerando links M3U8...")
            println("🎯 URL M3U8: $m3u8Url")
            println("🔗 Referer: $referer")
            
            // Headers para testar
            val headerSets = listOf(
                mapOf(
                    "Referer" to referer,
                    "Origin" to referer.removeSuffix("/"),
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                    "Accept" to "*/*",
                    "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
                    "Cookie" to API_COOKIE
                ),
                mapOf(
                    "Referer" to referer,
                    "User-Agent" to "Mozilla/5.0",
                    "Accept" to "*/*"
                ),
                mapOf(
                    "Referer" to "https://g9r6.com/",
                    "Origin" to "https://g9r6.com",
                    "User-Agent" to "Mozilla/5.0",
                    "Cookie" to API_COOKIE
                )
            )
            
            var testCount = 0
            for (headers in headerSets) {
                testCount++
                println("🔧 Testando configuração #$testCount...")
                incrementCounter("generateM3u8Links")
                
                try {
                    val links = M3u8Helper.generateM3u8(
                        name,
                        m3u8Url,
                        referer,
                        headers = headers
                    )
                    
                    if (links.isNotEmpty()) {
                        links.forEach(callback)
                        println("🎉 SUCESSO! ${links.size} links M3U8 gerados!")
                        println("📊 TOTAL DE REQUISIÇÕES: $requestCount")
                        return true
                    }
                } catch (e: Exception) {
                    println("⚠️  Falha no teste #$testCount: ${e.message}")
                }
            }
            
            println("❌ Nenhum link M3U8 gerado após $testCount tentativas")
            println("📊 TOTAL DE REQUISIÇÕES: $requestCount")
            false
            
        } catch (e: Exception) {
            println("💥 Erro ao gerar links M3U8: ${e.message}")
            println("📊 TOTAL DE REQUISIÇÕES: $requestCount")
            false
        }
    }
    
    // Função para incrementar e retornar o contador
    private fun incrementCounter(context: String): Int {
        requestCount++
        println("🔢 [$context] Requisição #$requestCount")
        return requestCount
    }
}
