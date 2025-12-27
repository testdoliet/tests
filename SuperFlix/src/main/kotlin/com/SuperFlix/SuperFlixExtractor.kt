package com.SuperFlix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

object SuperFlixExtractor {
    private const val API_COOKIE = "SITE_TOTAL_ID=aNMeQg3ajIMkDqsskT-8twAAAMg; cf_clearance=1cz1gt_lNTNk3FBQfipe2ZMywqRJuCT98Irqbmy3dCk-1758666307-1.2.1.1-8iqgHQO5yglQC.QdLgffecdiDEoQueXo3bMTtXYg3b3k2V3zHUvF_RTUB9m5VGmPjkJmhWXufohjocVGUJix0YlTLOiywrHzz.yPhI.Epn05b1acy9t_iDQY34TbcpwVynI0c7qMS4HiKbfinTzPS.z0SREH9aFBkay.AfmYN6eFFkkonzbO5gBpEgzGZ_a6zjYgTVD_WmkOdM91YFvlR4p_6eGEa0Lq_J2fgHbPC2o"
    
    suspend fun extractVideoLinks(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return tryGoFlixMethod(url, name, callback)
    }
    
    private suspend fun tryGoFlixMethod(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🎯 SuperFlixExtractor: Iniciando método GoFlix para: $url")
            
            // 1. Extrair ID do fembed
            val videoId = extractFembedId(url)
            if (videoId == null) {
                println("❌ Não consegui extrair ID do fembed")
                return false
            }
            
            println("✅ ID fembed: $videoId")
            
            // 2. Chamar API do fembed
            val apiUrl = "https://fembed.sx/api/source/$videoId"
            println("📡 Chamando API: $apiUrl")
            
            // 3. Headers EXATAMENTE como o GoFlix usava
            val headers = mapOf(
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "X-Requested-With" to "XMLHttpRequest",
                "Accept" to "*/*",
                "Referer" to "https://fembed.sx/e/$videoId",
                "Origin" to "https://fembed.sx",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Cookie" to API_COOKIE,
                "Cache-Control" to "no-cache",
                "Pragma" to "no-cache"
            )
            
            // 4. Dados EXATAMENTE como o GoFlix enviava
            val postData = mapOf(
                "action" to "getPlayer",
                "lang" to "DUB",  // DUB = dublado, LEG = legendado
                "key" to "MA=="   // Base64 de "0"
            )
            
            println("📦 Enviando dados: $postData")
            
            // 5. Fazer POST para API
            val response = app.post(apiUrl, headers = headers, data = postData)
            val responseText = response.text
            
            println("📥 Resposta API (${responseText.length} chars): ${responseText.take(300)}...")
            
            // 6. Procurar iframe na resposta
            val iframePattern = Regex("""src=["'](https?://[^"']+)["']""")
            val iframeMatch = iframePattern.find(responseText)
            
            if (iframeMatch != null) {
                val iframeUrl = iframeMatch.groupValues[1]
                println("🎬 Iframe encontrado: $iframeUrl")
                
                // 7. Extrair m3u8 do iframe
                val m3u8Url = extractM3u8FromIframe(iframeUrl)
                if (m3u8Url != null) {
                    println("✅ M3U8 do iframe: $m3u8Url")
                    return generateM3u8Links(m3u8Url, name, callback)
                }
            }
            
            // 8. Se não achou iframe, procura m3u8 direto
            val m3u8Pattern = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""")
            val m3u8Match = m3u8Pattern.find(responseText)
            
            if (m3u8Match != null) {
                val m3u8Url = m3u8Match.groupValues[1]
                println("✅ M3U8 direto na API: $m3u8Url")
                return generateM3u8Links(m3u8Url, name, callback)
            }
            
            println("❌ Nenhum m3u8 encontrado")
            false
        } catch (e: Exception) {
            println("💥 Erro no método GoFlix: ${e.message}")
            e.printStackTrace()
            false
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
    
    private suspend fun extractM3u8FromIframe(iframeUrl: String): String? {
        return try {
            println("🔍 Acessando iframe: $iframeUrl")
            
            val headers = mapOf(
                "Referer" to "https://fembed.sx/",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
            
            val response = app.get(iframeUrl, headers = headers)
            val html = response.text
            
            val patterns = listOf(
                Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                Regex("""source.*?src=["'](https?://[^"']+\.m3u8[^"']*)["']""", RegexOption.DOT_MATCHES_ALL),
                Regex("""file:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""")
            )
            
            for (pattern in patterns) {
                val match = pattern.find(html)
                if (match != null) {
                    val m3u8Url = match.groupValues[1]
                    println("✅ M3U8 no iframe: $m3u8Url")
                    return m3u8Url
                }
            }
            
            null
        } catch (e: Exception) {
            println("💥 Erro no iframe: ${e.message}")
            null
        }
    }
    
    private suspend fun generateM3u8Links(
        m3u8Url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // Referer que funcionava no GoFlix
            val referer = "https://g9r6.com/"
            
            println("🔄 Gerando links M3U8 com referer: $referer")
            
            val links = M3u8Helper.generateM3u8(
                name,
                m3u8Url,
                referer,
                headers = mapOf(
                    "Referer" to referer,
                    "Origin" to "https://g9r6.com",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                )
            )
            
            if (links.isNotEmpty()) {
                links.forEach(callback)
                println("🎉 ${links.size} links gerados com sucesso!")
                true
            } else {
                println("❌ Nenhum link gerado")
                false
            }
        } catch (e: Exception) {
            println("💥 Erro ao gerar links: ${e.message}")
            false
        }
    }
}
