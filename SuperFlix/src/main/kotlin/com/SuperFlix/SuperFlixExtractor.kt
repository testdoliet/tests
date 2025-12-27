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
            
            // 4. EXTRAIR DADOS DO BYSEVEPOIN (NOVA ESTRATÉGIA)
            // Extrair o identificador real da URL: /e/uvgxvuik7woo/407451-dub
            val m3u8Url = extractBysevepoinData(bysevepoinUrl, videoId)
            if (m3u8Url != null) {
                println("✅ M3U8 encontrado: $m3u8Url")
                return generateM3u8Links(m3u8Url, name, callback)
            }
            
            // 5. Tentar API diretamente
            val apiUrl = tryApiDirectMethods(videoId, bysevepoinUrl)
            if (apiUrl != null) {
                println("✅ URL da API: $apiUrl")
                return generateM3u8Links(apiUrl, name, callback)
            }
            
            println("❌ Não consegui extrair m3u8")
            false
        } catch (e: Exception) {
            println("💥 Erro no método Fembed: ${e.message}")
            false
        }
    }
    
    private suspend fun extractBysevepoinData(
        bysevepoinUrl: String,
        videoId: String
    ): String? {
        return try {
            println("🔍 Extraindo dados da URL do Bysevepoin")
            
            // Extrair o identificador da URL: /e/uvgxvuik7woo/407451-dub
            val pattern = Regex("""/e/([^/]+)/([^/]+)""")
            val match = pattern.find(bysevepoinUrl)
            
            if (match != null) {
                val realVideoId = match.groupValues[1] // uvgxvuik7woo
                println("🎯 ID real do vídeo: $realVideoId")
                
                // Tentar diferentes endpoints da API
                val endpoints = listOf(
                    "https://bysevepoin.com/api/v1/video/$realVideoId",
                    "https://bysevepoin.com/api/video/$realVideoId",
                    "https://bysevepoin.com/video/$realVideoId/data",
                    "https://bysevepoin.com/e/$realVideoId/data.json",
                    "https://bysevepoin.com/embed/$realVideoId",
                    "https://bysevepoin.com/api/player/$realVideoId",
                    "https://bysevepoin.com/api/stream/$realVideoId"
                )
                
                for (endpoint in endpoints) {
                    try {
                        println("📡 Testando endpoint: $endpoint")
                        val response = app.get(endpoint, headers = mapOf(
                            "Referer" to "https://bysevepoin.com/",
                            "Origin" to "https://bysevepoin.com",
                            "User-Agent" to "Mozilla/5.0",
                            "Accept" to "application/json, text/plain, */*",
                            "Cookie" to API_COOKIE
                        ))
                        
                        if (response.statusCode == 200) {
                            val json = response.text
                            println("📥 Resposta da API: ${json.take(200)}...")
                            
                            val m3u8Url = extractM3u8FromJson(json)
                            if (m3u8Url != null) {
                                return m3u8Url
                            }
                        }
                    } catch (e: Exception) {
                        println("⚠️  Falha no endpoint $endpoint: ${e.message}")
                    }
                }
            }
            
            null
        } catch (e: Exception) {
            println("💥 Erro ao extrair dados do Bysevepoin: ${e.message}")
            null
        }
    }
    
    private suspend fun tryApiDirectMethods(
        videoId: String,
        bysevepoinUrl: String
    ): String? {
        return try {
            println("🔧 Tentando APIs diretas")
            
            // Tentar diferentes APIs conhecidas
            val apis = listOf(
                // API do Fembed
                "https://fembed.sx/api/source/$videoId",
                "https://fembed.sx/f/$videoId",
                "https://fembed.sx/api/v1/video/$videoId",
                
                // API do Bysevepoin
                "https://bysevepoin.com/api/source/$videoId",
                "https://bysevepoin.com/api/v1/stream/$videoId",
                "https://bysevepoin.com/api/video/$videoId"
            )
            
            for (apiUrl in apis) {
                try {
                    println("📡 Testando API: $apiUrl")
                    val response = app.get(apiUrl, headers = mapOf(
                        "Referer" to bysevepoinUrl,
                        "Accept" to "application/json",
                        "User-Agent" to "Mozilla/5.0",
                        "Cookie" to API_COOKIE
                    ))
                    
                    if (response.statusCode == 200) {
                        val json = response.text
                        println("📥 Resposta API: ${json.take(200)}...")
                        
                        val m3u8Url = extractM3u8FromJson(json)
                        if (m3u8Url != null) {
                            println("✅ URL encontrada via API: $m3u8Url")
                            return m3u8Url
                        }
                    }
                } catch (e: Exception) {
                    println("⚠️  Falha na API $apiUrl: ${e.message}")
                }
            }
            
            null
        } catch (e: Exception) {
            println("💥 Erro em APIs diretas: ${e.message}")
            null
        }
    }
    
    private fun extractM3u8FromJson(json: String): String? {
        // Padrões para extrair URL m3u8 de JSON
        val patterns = listOf(
            Regex(""""url"\s*:\s*"([^"]+\.m3u8[^"]*)"""),
            Regex(""""file"\s*:\s*"([^"]+\.m3u8[^"]*)"""),
            Regex(""""source"\s*:\s*"([^"]+\.m3u8[^"]*)"""),
            Regex(""""m3u8_url"\s*:\s*"([^"]+)"""),
            Regex(""""video_url"\s*:\s*"([^"]+\.m3u8[^"]*)"""),
            Regex(""""stream_url"\s*:\s*"([^"]+\.m3u8[^"]*)"""),
            Regex(""""hls"\s*:\s*"([^"]+\.m3u8[^"]*)"""),
            Regex(""""([^"]+\.m3u8[^"]*)"""")  // Último recurso
        )
        
        for (pattern in patterns) {
            val match = pattern.find(json)
            if (match != null) {
                val url = match.groupValues[1]
                println("✅ URL m3u8 extraída do JSON: $url")
                
                // Validar URL
                if (isValidVideoUrl(url)) {
                    return url
                }
            }
        }
        
        return null
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
