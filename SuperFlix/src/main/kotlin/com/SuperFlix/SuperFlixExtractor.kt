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
            
            // 4. Acessar bysevepoin e procurar script principal
            val mainScriptUrl = extractMainScriptUrl(bysevepoinUrl, videoId)
            if (mainScriptUrl == null) {
                println("❌ Não consegui extrair script principal")
                return false
            }
            
            println("📜 Script principal: $mainScriptUrl")
            
            // 5. Baixar script e extrair m3u8
            val m3u8Url = extractM3u8FromScript(mainScriptUrl, videoId)
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
        
        return match?.groupValues?.get(1)
    }
    
    private suspend fun extractMainScriptUrl(
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
            
            // Procurar o script principal do React/Vite
            // Padrão: <script type="module" crossorigin src="/assets/index-XXXXXXX.js"></script>
            val scriptPattern = Regex("""<script[^>]+src=["'](/assets/index-[^"']+\.js)["']""")
            val match = scriptPattern.find(html)
            
            if (match != null) {
                val scriptPath = match.groupValues[1]
                // Converter para URL completa
                val baseUrl = "https://bysevepoin.com"
                return "$baseUrl$scriptPath"
            }
            
            null
        } catch (e: Exception) {
            println("💥 Erro ao acessar bysevepoin: ${e.message}")
            null
        }
    }
    
    private suspend fun extractM3u8FromScript(
        scriptUrl: String,
        videoId: String
    ): String? {
        return try {
            println("📥 Baixando script: $scriptUrl")
            
            val headers = mapOf(
                "Accept" to "*/*",
                "Referer" to "https://bysevepoin.com/",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
            )
            
            val response = app.get(scriptUrl, headers = headers)
            val scriptContent = response.text
            
            println("📜 Conteúdo do script (${scriptContent.length} chars)")
            // Salvar uma amostra do script para análise
val sampleSize = 5000
println("🔍 Primeiros $sampleSize caracteres do script:")
println(scriptContent.take(sampleSize))
println("\n\n🔍 Últimos $sampleSize caracteres do script:")
println(scriptContent.takeLast(sampleSize))
            // Procurar m3u8 no script JavaScript
            // Padrões comuns em SPAs React:
            // 1. URLs em strings: "https://...m3u8"
            // 2. Em objetos: file: "https://...m3u8"
            // 3. Em arrays: ["https://...m3u8"]
            
            val patterns = listOf(
                // Padrão mais comum: URL direta
                Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                
                // Padrão em objetos JavaScript
                Regex("""(?:file|src|url|source)\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                
                // Padrão em strings grandes (pode ser base64)
                Regex("""(https?://[^"';\s]+\.m3u8[^"';\s]*)"""),
                
                // Padrão para g9r6.com (que sabemos que é usado)
                Regex("""(https?://g9r6\.com/[^"']+\.m3u8[^"']*)"""),
                
                // Padrão para filemoon (outro CDN comum)
                Regex("""(https?://filemoon\.[^"']+\.m3u8[^"']*)""")
            )
            
            for (pattern in patterns) {
                val matches = pattern.findAll(scriptContent).toList()
                for (match in matches) {
                    val url = match.groupValues[1]
                    println("🔍 URL encontrada: $url")
                    
                    // Validar se parece uma URL de vídeo
                    if (isValidVideoUrl(url)) {
                        println("✅ URL válida encontrada: $url")
                        return url
                    }
                }
            }
            
            // Se não encontrou, procurar por base64
            val base64Pattern = Regex("""["']([A-Za-z0-9+/]{100,}={0,2})["']""")
            val base64Matches = base64Pattern.findAll(scriptContent).toList()
            
            for (match in base64Matches) {
                val base64 = match.groupValues[1]
                try {
                    val decoded = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                    val decodedStr = String(decoded, Charsets.UTF_8)
                    
                    if (decodedStr.contains(".m3u8")) {
                        println("🔓 Base64 decodificado contém m3u8")
                        val m3u8Url = Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").find(decodedStr)
                        if (m3u8Url != null) {
                            return m3u8Url.groupValues[1]
                        }
                    }
                } catch (e: Exception) {
                    // Não é base64 válido, continuar
                }
            }
            
            null
        } catch (e: Exception) {
            println("💥 Erro ao baixar script: ${e.message}")
            null
        }
    }
    
    private fun isValidVideoUrl(url: String): Boolean {
        return url.startsWith("http") && 
               url.contains(".m3u8") &&
               !url.contains("google") &&
               !url.contains("doubleclick") &&
               !url.contains("analytics")
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
