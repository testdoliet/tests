package com.SuperFlix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

object SuperFlixExtractor {
    // COOKIE ATUALIZADO
    private const val API_COOKIE = "SITE_TOTAL_ID=aTYqe6GU65PNmeCXpelwJwAAAMi; __dtsu=104017651574995957BEB724C6373F9E; __cc_id=a44d1e52993b9c2Oaaf40eba24989a06; __cc_cc=ACZ4nGNQSDQXsTFMNTWyTDROskw2MkhMTDDMXSE1KNDKxtLBMNDBjAIJMC4fgVe%2B%2F%2BDngAHemT8XsDBKrv%2FF3%2F2F2%2F%2FF0ZGhFP15u.VnW-1Y0o8o6/84-1.2.1.1-4_OXh2hYevsbO8hINijDKB8O_SPowh.pNojloHEbwX_qZorbmW8u8zqV9B7UsV6bbRmCWx_dD17mA7vJJklpOD9WBh9DA0wMV2a1QSKuR2J3FN9.TRzOUM4AhnTGFd8dJH8bHfqQdY7uYuUg7Ny1TVQDF9kXqyEPtnmkZ9rFkqQ2KS6u0t2hhFdQvRBY7dqyGfdjmyjDqwc7ZOovHB0eqep.FPHrh8T9iz1LuucA; cf_clearance=rfIEldahI7B..Y4PpZhGgwi.QOJBqIRGdFP150.VnW-1766868784-1.1-"
    
    suspend fun extractVideoLinks(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return tryFembedMethod(url, name, callback)
    }
    
    private suspend fun tryFembedMethod(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🎯 SuperFlixExtractor: Método Fembed...")
            
            // 1. Extrair ID
            val videoId = extractFembedId(url)
            if (videoId == null) {
                println("❌ Não consegui extrair ID")
                return false
            }
            
            println("✅ ID: $videoId")
            
            // 2. Fazer POST para obter iframe
            val iframeUrl = getIframeUrlFromFembed(videoId)
            if (iframeUrl == null) {
                println("❌ Não consegui obter iframe")
                return false
            }
            
            println("🎬 Iframe obtido: $iframeUrl")
            
            // 3. Acessar iframe (getAds)
            val bysevepoinUrl = getBysevepoinUrlFromIframe(iframeUrl, videoId)
            if (bysevepoinUrl == null) {
                println("❌ Não consegui obter URL do bysevepoin")
                return false
            }
            
            println("🔗 URL do bysevepoin: $bysevepoinUrl")
            
            // 4. Acessar bysevepoin e procurar m3u8
            val m3u8Url = extractM3u8FromBysevepoin(bysevepoinUrl, videoId)
            if (m3u8Url != null) {
                println("✅ M3U8 encontrado: $m3u8Url")
                return generateM3u8Links(m3u8Url, name, callback)
            }
            
            println("❌ Não consegui extrair m3u8")
            false
        } catch (e: Exception) {
            println("💥 Erro no método Fembed: ${e.message}")
            false
        }
    }
    
    private suspend fun getIframeUrlFromFembed(videoId: String): String? {
        return try {
            val apiUrl = "https://fembed.sx/api.php?s=$videoId&c="
            println("📡 POST para: $apiUrl")
            
            val headers = mapOf(
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "X-Requested-With" to "XMLHttpRequest",
                "Accept" to "*/*",
                "Accept-Language" to "pt-BR",
                "Cache-Control" to "no-cache",
                "Pragma" to "no-cache",
                "Referer" to "https://fembed.sx/e/$videoId",
                "Origin" to "https://fembed.sx",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                "Cookie" to API_COOKIE
            )
            
            val postData = mapOf(
                "action" to "getPlayer",
                "lang" to "DUB",
                "key" to "MA=="
            )
            
            val response = app.post(apiUrl, headers = headers, data = postData)
            val responseText = response.text
            
            println("📥 Resposta POST (${responseText.length} chars): ${responseText.take(200)}...")
            
            // Extrair URL do iframe
            extractIframeUrl(responseText)
        } catch (e: Exception) {
            println("💥 Erro ao obter iframe: ${e.message}")
            null
        }
    }
    
    private fun extractIframeUrl(html: String): String? {
        val pattern = Regex("""<iframe[^>]+src=["']([^"']+)["']""")
        val match = pattern.find(html)
        
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
    
    private suspend fun getBysevepoinUrlFromIframe(iframeUrl: String, videoId: String): String? {
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
            
            // Extrair URL do bysevepoin
            extractBysevepoinUrl(html)
        } catch (e: Exception) {
            println("💥 Erro ao acessar iframe: ${e.message}")
            null
        }
    }
    
    private fun extractBysevepoinUrl(html: String): String? {
        // Procurar src do iframe que aponta para bysevepoin
        val pattern = Regex("""<iframe[^>]+src=["'](https?://bysevepoin\.com/[^"']+)["']""")
        val match = pattern.find(html)
        
        if (match != null) {
            return match.groupValues[1]
        }
        
        // Tentar padrão alternativo
        val altPattern = Regex("""src=["'](https?://[^"']*bysevepoin[^"']*)["']""")
        val altMatch = altPattern.find(html)
        
        return altMatch?.groupValues?.get(1)
    }
    
    private suspend fun extractM3u8FromBysevepoin(
        bysevepoinUrl: String,
        videoId: String
    ): String? {
        return try {
            println("🌐 Acessando bysevepoin: $bysevepoinUrl")
            
            val headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "pt-BR",
                "Referer" to "https://fembed.sx/e/$videoId",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                "Cookie" to API_COOKIE
            )
            
            val response = app.get(bysevepoinUrl, headers = headers)
            val html = response.text
            
            println("📥 Resposta bysevepoin (${html.length} chars)")
            
            // Salvar HTML completo para debug (apenas se for pequeno)
            if (html.length < 5000) {
                println("🔍 HTML completo:\n$html")
            }
            
            // Procurar m3u8 de várias formas
            val m3u8Url = findM3u8InHtml(html)
            if (m3u8Url != null) {
                return m3u8Url
            }
            
            // Procurar por dados JSON que possam conter o m3u8
            val jsonData = findJsonData(html)
            if (jsonData != null) {
                println("📊 JSON encontrado: ${jsonData.take(200)}...")
                val m3u8FromJson = extractM3u8FromJson(jsonData)
                if (m3u8FromJson != null) {
                    return m3u8FromJson
                }
            }
            
            // Procurar por script inline
            val inlineScripts = findInlineScripts(html)
            for (script in inlineScripts) {
                println("📜 Script inline encontrado (${script.length} chars)")
                val m3u8FromScript = findM3u8InHtml(script)
                if (m3u8FromScript != null) {
                    return m3u8FromScript
                }
                
                // Procurar por base64
                val base64Data = extractBase64Data(script)
                if (base64Data != null) {
                    println("🔓 Base64 encontrado")
                    val decoded = decodeBase64(base64Data)
                    if (decoded != null && decoded.contains("m3u8")) {
                        val m3u8FromBase64 = findM3u8InHtml(decoded)
                        if (m3u8FromBase64 != null) {
                            return m3u8FromBase64
                        }
                    }
                }
            }
            
            null
        } catch (e: Exception) {
            println("💥 Erro ao acessar bysevepoin: ${e.message}")
            null
        }
    }
    
    private fun findM3u8InHtml(html: String): String? {
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
                val url = match.groupValues[1]
                println("✅ M3U8 encontrado com padrão: $url")
                return url
            }
        }
        
        return null
    }
    
    private fun findJsonData(html: String): String? {
        // Procurar por objetos JSON
        val patterns = listOf(
            Regex("""\{[^{}]*\.m3u8[^{}]*\}"""),
            Regex(""""file"\s*:\s*"[^"]+\.m3u8"""),
            Regex(""""sources"\s*:\s*\[[^\]]+\]"""),
            Regex("""data\s*=\s*(\{.*?\})""", RegexOption.DOT_MATCHES_ALL)
        )
        
        for (pattern in patterns) {
            val match = pattern.find(html)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        
        return null
    }
    
    private fun extractM3u8FromJson(jsonString: String): String? {
        // Tentar extrair m3u8 de string JSON
        val patterns = listOf(
            Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']"""),
            Regex(""""file"\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
            Regex(""""url"\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(jsonString)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        
        return null
    }
    
    private fun findInlineScripts(html: String): List<String> {
        val scripts = mutableListOf<String>()
        val pattern = Regex("""<script[^>]*>([^<]+)</script>""")
        val matches = pattern.findAll(html)
        
        for (match in matches) {
            scripts.add(match.groupValues[1])
        }
        
        return scripts
    }
    
    private fun extractBase64Data(script: String): String? {
        val patterns = listOf(
            Regex("""atob\(["']([^"']+)["']\)"""),
            Regex("""["']([A-Za-z0-9+/]+={0,2})["']""")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(script)
            if (match != null) {
                val data = match.groupValues[1]
                // Verificar se parece base64
                if (data.length > 20 && data.matches(Regex("""[A-Za-z0-9+/]+={0,2}"""))) {
                    return data
                }
            }
        }
        
        return null
    }
    
    private fun decodeBase64(base64: String): String? {
        return try {
            val decoded = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
            String(decoded, Charsets.UTF_8)
        } catch (e: Exception) {
            println("⚠️  Erro ao decodificar base64: ${e.message}")
            null
        }
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
