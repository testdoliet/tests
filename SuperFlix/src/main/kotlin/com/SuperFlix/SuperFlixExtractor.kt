package com.SuperFlix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

object SuperFlixExtractor {
    // COOKIE ATUALIZADO (em uma linha só)
    private const val API_COOKIE = "SITE_TOTAL_ID=aTYqe6GU65PNmeCXpelwJwAAAMi; __dtsu=104017651574995957BEB724C6373F9E; __cc_id=a44d1e52993b9c2Oaaf40eba24989a06; __cc_cc=ACZ4nGNQSDQXsTFMNTWyTDROskw2MkhMTDDMXSE1KNDKxtLBMNDBjAIJMC4fgVe%2B%2F%2BDngAHemT8XsDBKrv%2FF3%2F2F2%2F%2FF0ZGhFP15u.VnW-1Y0o8o6/84-1.2.1.1-4_OXh2hYevsbO8hINijDKB8O_SPowh.pNojloHEbwX_qZorbmW8u8zqV9B7UsV6bbRmCWx_dD17mA7vJJklpOD9WBh9DA0wMV2a1QSKuR2J3FN9.TRzOUM4AhnTGFd8dJH8bHfqQdY7uYuUg7Ny1TVQDF9kXqyEPtnmkZ9rFkqQ2KS6u0t2hhFdQvRBY7dqyGfdjmyjDqwc7ZOovHB0eqep.FPHrh8T9iz1LuucA; cf_clearance=rfIEldahI7B..Y4PpZhGgwi.QOJBqIRGdFP150.VnW-1766868784-1.1-"
    
    suspend fun extractVideoLinks(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return tryFembedNewMethod(url, name, callback)
    }
    
    private suspend fun tryFembedNewMethod(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🎯 SuperFlixExtractor: Método NOVO Fembed...")
            
            // 1. Extrair ID
            val videoId = extractFembedId(url)
            if (videoId == null) {
                println("❌ Não consegui extrair ID")
                return false
            }
            
            println("✅ ID: $videoId")
            
            // 2. PRIMEIRO: Acessar a página para obter cookies frescos
            val pageUrl = "https://fembed.sx/e/$videoId"
            println("🌐 Acessando página: $pageUrl")
            
            val pageHeaders = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
                "Accept-Language" to "pt-BR",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                "Upgrade-Insecure-Requests" to "1",
                "Sec-Fetch-Dest" to "document",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "none",
                "Sec-Fetch-User" to "?1"
            )
            
            // Acessar página para obter cookies atualizados
            val pageResponse = app.get(pageUrl, headers = pageHeaders)
            println("📄 Página acessada (${pageResponse.code})")
            
            // 3. AGORA fazer POST EXATO como mostrado nas imagens
            val apiUrl = "https://fembed.sx/api.php?s=$videoId&c="
            println("📡 POST para: $apiUrl")
            
            val headers = mapOf(
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "X-Requested-With" to "XMLHttpRequest",
                "Accept" to "*/*",
                // REMOVER Accept-Encoding para evitar compressão!
                "Accept-Language" to "pt-BR",
                "Cache-Control" to "no-cache",
                "Pragma" to "no-cache",
                "Referer" to "https://fembed.sx/e/$videoId",
                "Origin" to "https://fembed.sx",
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "same-origin",
                "Priority" to "u=1, i",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                "Cookie" to API_COOKIE
            )
            
            // 4. Body EXATO das imagens
            val postData = mapOf(
                "action" to "getPlayer",
                "lang" to "DUB",
                "key" to "MA=="
            )
            
            println("📦 Body: $postData")
            
            val response = app.post(apiUrl, headers = headers, data = postData)
            val responseText = response.text
            
            println("📥 Resposta POST (${responseText.length} chars):")
            println(responseText)
            
            // 5. Verificar se a resposta está legível
            if (responseText.contains("<") || responseText.contains("{")) {
                // Resposta parece HTML/JSON
                
                // Extrair URL do iframe da resposta
                val iframeUrl = extractIframeUrlFromResponse(responseText)
                if (iframeUrl != null) {
                    println("🎬 Iframe encontrado: $iframeUrl")
                    
                    // Fazer requisição para o iframe
                    return processIframeUrl(iframeUrl, videoId, name, callback)
                }
                
                // Tentar extrair m3u8 direto
                val m3u8Url = extractM3u8Directly(responseText)
                if (m3u8Url != null) {
                    println("✅ M3U8 direto: $m3u8Url")
                    return generateM3u8Links(m3u8Url, name, callback)
                }
                
                // Mostrar resposta para debug
                println("🔍 Resposta completa: $responseText")
            } else {
                // Resposta parece binária/criptografada
                println("⚠️  Resposta parece binária/criptografada")
                
                // Tentar decodificar como string UTF-8
                try {
                    val decoded = String(response.body.toByteArray(), Charsets.UTF_8)
                    println("🔓 Tentativa de decodificação: ${decoded.take(200)}...")
                    
                    if (decoded.contains("<iframe") || decoded.contains("src=")) {
                        val iframeUrl = extractIframeUrlFromResponse(decoded)
                        if (iframeUrl != null) {
                            return processIframeUrl(iframeUrl, videoId, name, callback)
                        }
                    }
                } catch (e: Exception) {
                    println("💥 Erro ao decodificar: ${e.message}")
                }
            }
            
            println("❌ Nenhum iframe ou m3u8 encontrado")
            false
        } catch (e: Exception) {
            println("💥 Erro no método Fembed: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    private fun extractIframeUrlFromResponse(html: String): String? {
        // Procurar src do iframe
        val iframePattern = Regex("""<iframe[^>]+src=["']([^"']+)["']""")
        val match = iframePattern.find(html)
        
        if (match != null) {
            var url = match.groupValues[1]
            
            // Se URL é relativa, adicionar domínio
            if (url.startsWith("/")) {
                url = "https://fembed.sx$url"
            }
            
            return url
        }
        
        return null
    }
    
    private suspend fun processIframeUrl(
        iframeUrl: String,
        videoId: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🔍 Acessando iframe: $iframeUrl")
            
            val headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "pt-BR",
                "Referer" to "https://fembed.sx/e/$videoId",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                "Cookie" to API_COOKIE
            )
            
            val response = app.get(iframeUrl, headers = headers)
            val html = response.text
            
            println("📥 Resposta iframe (${html.length} chars): ${html.take(300)}...")
            
            // O iframe é provavelmente: /api.php?action=getAds&s=407451&c=&key=0&lang=DUB
            // Pode conter scripts que carregam o player real
            
            // Tentar extrair m3u8
            val m3u8Url = extractM3u8FromHtml(html)
            if (m3u8Url != null) {
                println("✅ M3U8 no iframe: $m3u8Url")
                return generateM3u8Links(m3u8Url, name, callback)
            }
            
            // Procurar por scripts que possam conter a URL do vídeo
            val scriptPattern = Regex("""<script[^>]*>([^<]+)</script>""")
            val scripts = scriptPattern.findAll(html).toList()
            
            for (scriptMatch in scripts) {
                val script = scriptMatch.groupValues[1]
                if (script.contains("m3u8") || script.contains("filemoon") || script.contains("g9r6")) {
                    println("📜 Script encontrado: ${script.take(100)}...")
                    
                    // Procurar URL no script
                    val urlPattern = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""")
                    val urlMatch = urlPattern.find(script)
                    if (urlMatch != null) {
                        val m3u8 = urlMatch.groupValues[1]
                        println("✅ M3U8 no script: $m3u8")
                        return generateM3u8Links(m3u8, name, callback)
                    }
                }
            }
            
            false
        } catch (e: Exception) {
            println("💥 Erro ao processar iframe: ${e.message}")
            false
        }
    }
    
    private fun extractM3u8FromHtml(html: String): String? {
        val patterns = listOf(
            Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']"""),
            Regex("""source.*?src=["'](https?://[^"']+\.m3u8[^"']*)["']""", RegexOption.DOT_MATCHES_ALL),
            Regex("""file:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
            Regex("""hlsManifestUrl["']?:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
            Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(html)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        
        return null
    }
    
    private fun extractM3u8Directly(html: String): String? {
        return extractM3u8FromHtml(html)
    }
    
    private fun extractFembedId(url: String): String? {
        val patterns = listOf(
            Regex("""fembed\.sx/e/([a-zA-Z0-9]+)"""),
            Regex("""bysevepoin\.com/e/([a-zA-Z0-9]+)"""),
            Regex("""/e/([a-zA-Z0-9]+)"""),
            Regex("""/([a-zA-Z0-9]+)$""")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        
        return null
    }
    
    private suspend fun generateM3u8Links(
        m3u8Url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // Testar diferentes referers
            val referers = listOf(
                "https://g9r6.com/",
                "https://fembed.sx/",
                "https://bysevepoin.com/",
                "https://superflix21.lol/",
                "https://filemoon.sx/"
            )
            
            for (referer in referers) {
                try {
                    println("🔄 Tentando referer: $referer")
                    
                    val links = M3u8Helper.generateM3u8(
                        name,
                        m3u8Url,
                        referer,
                        headers = mapOf(
                            "Referer" to referer,
                            "Origin" to referer.removeSuffix("/"),
                            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
                        )
                    )
                    
                    if (links.isNotEmpty()) {
                        links.forEach(callback)
                        println("🎉 ${links.size} links gerados!")
                        return true
                    }
                } catch (e: Exception) {
                    println("⚠️  Falha com referer $referer: ${e.message}")
                }
            }
            
            false
        } catch (e: Exception) {
            println("💥 Erro ao gerar links: ${e.message}")
            false
        }
    }
}
