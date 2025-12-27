package com.SuperFlix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

object SuperFlixExtractor {
    // COOKIE que o GoFlix usa (CRUCIAL!)
    private const val API_COOKIE = "SITE_TOTAL_ID=aNMeQg3ajIMkDqsskT-8twAAAMg; cf_clearance=1cz1gt_lNTNk3FBQfipe2ZMywqRJuCT98Irqbmy3dCk-1758666307-1.2.1.1-8iqgHQO5yglQC.QdLgffecdiDEoQueXo3bMTtXYg3b3k2V3zHUvF_RTUB9m5VGmPjkJmhWXufohjocVGUJix0YlTLOiywrHzz.yPhI.Epn05b1acy9t_iDQY34TbcpwVynI0c7qMS4HiKbfinTzPS.z0SREH9aFBkay.AfmYN6eFFkkonzbO5gBpEgzGZ_a6zjYgTVD_WmkOdM91YFvlR4p_6eGEa0Lq_J2fgHbPC2o"
    
    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🎯 Usando método GoFlix (que funcionava)...")
            
            // ETAPA 1: Extrair ID do fembed
            val fembedId = extractFembedId(url)
            if (fembedId == null) {
                println("❌ Não consegui extrair ID do fembed")
                return false
            }
            
            println("✅ ID do fembed: $fembedId")
            
            // ETAPA 2: Chamar API do fembed EXATAMENTE como o GoFlix
            val m3u8Url = callFembedApiLikeGoFlix(fembedId)
            
            if (m3u8Url != null) {
                println("✅ M3U8 obtido da API: $m3u8Url")
                
                // ETAPA 3: Usar M3u8Helper com referer correto
                M3u8Helper.generateM3u8(
                    name,
                    m3u8Url,
                    "https://g9r6.com/", // Referer que FUNCIONA
                    headers = mapOf(
                        "Referer" to "https://g9r6.com/",
                        "Origin" to "https://g9r6.com",
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
                    )
                ).forEach(callback)
                
                true
            } else {
                println("❌ Falha ao obter M3U8 da API")
                false
            }
        } catch (e: Exception) {
            println("💥 Erro no método GoFlix: ${e.message}")
            false
        }
    }
    
    private fun extractFembedId(url: String): String? {
        // Tenta extrair de diferentes padrões
        val patterns = listOf(
            Regex("""fembed\.sx/e/([a-zA-Z0-9]+)"""),
            Regex("""/e/([a-zA-Z0-9]+)"""),
            Regex("""bysevepoin\.com/e/([a-zA-Z0-9]+)""")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        
        // Se não encontrou na URL, tenta extrair do HTML da página
        return null
    }
    
    private suspend fun callFembedApiLikeGoFlix(fembedId: String): String? {
        return try {
            // URL da API EXATAMENTE como no GoFlix
            val apiUrl = "https://fembed.sx/api/source/$fembedId"
            println("📡 Chamando API: $apiUrl")
            
            // Headers EXATAMENTE como no GoFlix
            val headers = mapOf(
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "X-Requested-With" to "XMLHttpRequest",
                "Accept" to "*/*",
                "Referer" to "https://fembed.sx/e/$fembedId",
                "Origin" to "https://fembed.sx",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Cookie" to API_COOKIE, // ← COOKIE É ESSENCIAL!
                "Cache-Control" to "no-cache",
                "Pragma" to "no-cache"
            )
            
            // Dados EXATAMENTE como no GoFlix
            val postData = mapOf(
                "action" to "getPlayer",  // Ação: getPlayer
                "lang" to "DUB",          // Idioma: DUB (dublado)
                "key" to "MA=="           // Key: "0" em Base64
            )
            
            println("📦 Enviando dados: $postData")
            
            // Faz o POST
            val response = app.post(apiUrl, headers = headers, data = postData)
            val responseText = response.text
            println("📥 Resposta da API (${responseText.length} chars): ${responseText.take(200)}...")
            
            // Procura iframe na resposta (como o GoFlix faz)
            val iframePattern = Regex("""src=["'](https?://[^"']+)["']""")
            val iframeMatch = iframePattern.find(responseText)
            
            if (iframeMatch != null) {
                val iframeUrl = iframeMatch.groupValues[1]
                println("🎬 Iframe encontrado: $iframeUrl")
                
                // Acessa o iframe para extrair m3u8
                return extractM3u8FromIframe(iframeUrl)
            } else {
                // Tenta encontrar m3u8 diretamente na resposta
                val m3u8Pattern = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""")
                val m3u8Match = m3u8Pattern.find(responseText)
                
                m3u8Match?.groupValues?.get(1)
            }
        } catch (e: Exception) {
            println("💥 Erro na API: ${e.message}")
            null
        }
    }
    
    private suspend fun extractM3u8FromIframe(iframeUrl: String): String? {
        return try {
            println("🔍 Acessando iframe: $iframeUrl")
            
            val headers = mapOf(
                "Referer" to "https://fembed.sx/",
                "User-Agent" to "Mozilla/5.0",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
            
            val response = app.get(iframeUrl, headers = headers)
            val html = response.text
            
            // Procura m3u8 no iframe
            val patterns = listOf(
                Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                Regex("""source.*src=["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                Regex("""file:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""")
            )
            
            for (pattern in patterns) {
                val match = pattern.find(html)
                if (match != null) {
                    val m3u8Url = match.groupValues[1]
                    println("✅ M3U8 encontrado no iframe: $m3u8Url")
                    return m3u8Url
                }
            }
            
            null
        } catch (e: Exception) {
            println("💥 Erro no iframe: ${e.message}")
            null
        }
    }
}
