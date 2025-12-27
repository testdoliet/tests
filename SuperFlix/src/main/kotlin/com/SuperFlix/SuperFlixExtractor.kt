// SuperFlixExtractor.kt - ÚNICO arquivo com este nome
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
        return try {
            println("🚀 SuperFlixExtractor: Iniciando extração para $url")
            
            // Estratégia 1: Método GoFlix original (que funcionava)
            if (tryGoFlixMethod(url, name, callback)) {
                return true
            }
            
            println("⚠️  Método GoFlix falhou, tentando métodos alternativos...")
            
            // Estratégia 2: Extrair direto do HTML
            if (tryDirectHtmlExtraction(url, name, callback)) {
                return true
            }
            
            // Estratégia 3: Tentar APIs alternativas
            if (tryAlternativeAPIs(url, name, callback)) {
                return true
            }
            
            println("❌ Todos os métodos falharam para: $url")
            false
        } catch (e: Exception) {
            println("💥 Erro geral no extrator: ${e.message}")
            false
        }
    }
    
    private suspend fun tryGoFlixMethod(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🎯 Tentando método GoFlix (Fembed API)...")
            
            // 1. Extrair ID do vídeo
            val videoId = extractVideoId(url)
            if (videoId == null) {
                println("❌ Não consegui extrair ID do vídeo")
                return false
            }
            
            println("✅ ID extraído: $videoId")
            
            // 2. Chamar API do Fembed EXATAMENTE como o GoFlix fazia
            val apiUrl = "https://fembed.sx/api/source/$videoId"
            println("📡 Chamando API: $apiUrl")
            
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
            
            val postData = mapOf(
                "action" to "getPlayer",
                "lang" to "DUB",  // Pode alternar entre DUB (dublado) e LEG (legendado)
                "key" to "MA=="   // Base64 de "0"
            )
            
            // 3. Fazer POST para API
            val response = app.post(apiUrl, headers = headers, data = postData)
            val responseText = response.text
            
            println("📥 Resposta API (${responseText.length} chars): ${responseText.take(200)}...")
            
            // 4. Procurar iframe na resposta
            val iframePattern = Regex("""src=["'](https?://[^"']+)["']""")
            val iframeMatch = iframePattern.find(responseText)
            
            if (iframeMatch != null) {
                val iframeUrl = iframeMatch.groupValues[1]
                println("🎬 Iframe encontrado: $iframeUrl")
                
                // 5. Extrair m3u8 do iframe
                val m3u8Url = extractM3u8FromIframe(iframeUrl)
                if (m3u8Url != null) {
                    println("✅ M3U8 extraído: $m3u8Url")
                    return generateM3u8Links(m3u8Url, name, callback)
                }
            }
            
            // 6. Se não achou iframe, procura m3u8 direto
            val m3u8Pattern = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""")
            val m3u8Match = m3u8Pattern.find(responseText)
            
            if (m3u8Match != null) {
                val m3u8Url = m3u8Match.groupValues[1]
                println("✅ M3U8 encontrado direto na API: $m3u8Url")
                return generateM3u8Links(m3u8Url, name, callback)
            }
            
            println("❌ Nenhum m3u8 encontrado na resposta da API")
            false
        } catch (e: Exception) {
            println("💥 Erro no método GoFlix: ${e.message}")
            false
        }
    }
    
    private fun extractVideoId(url: String): String? {
        val patterns = listOf(
            // Padrões comuns
            Regex("""fembed\.sx/e/([a-zA-Z0-9]+)"""),
            Regex("""bysevepoin\.com/e/([a-zA-Z0-9]+)"""),
            Regex("""/e/([a-zA-Z0-9]+)"""),
            Regex("""/([a-zA-Z0-9]+)$"""),
            Regex("""v=([a-zA-Z0-9]+)"""),
            Regex("""id=([a-zA-Z0-9]+)""")
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
            println("🔍 Analisando iframe: $iframeUrl")
            
            val headers = mapOf(
                "Referer" to "https://fembed.sx/",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
            
            val response = app.get(iframeUrl, headers = headers)
            val html = response.text
            
            // Procurar m3u8 no HTML do iframe
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
                    val m3u8Url = match.groupValues[1]
                    println("✅ M3U8 encontrado no iframe: $m3u8Url")
                    return m3u8Url
                }
            }
            
            println("❌ Nenhum m3u8 encontrado no iframe")
            null
        } catch (e: Exception) {
            println("💥 Erro ao acessar iframe: ${e.message}")
            null
        }
    }
    
    private suspend fun tryDirectHtmlExtraction(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🌐 Tentando extração direta do HTML...")
            
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Referer" to "https://superflix21.lol/"
            )
            
            val response = app.get(url, headers = headers)
            val html = response.text
            
            // Procurar m3u8 no HTML
            val m3u8Pattern = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""")
            val matches = m3u8Pattern.findAll(html).toList()
            
            for (match in matches) {
                val m3u8Url = match.groupValues[1]
                
                // Verificar se é uma URL válida (não de CDN comum)
                if (isValidM3u8Url(m3u8Url)) {
                    println("🔗 M3U8 encontrado no HTML: $m3u8Url")
                    
                    // Tentar com diferentes referers
                    val referers = listOf(
                        "https://g9r6.com/",
                        "https://fembed.sx/",
                        "https://superflix21.lol/",
                        "https://bysevepoin.com/"
                    )
                    
                    for (referer in referers) {
                        try {
                            println("🔄 Tentando com referer: $referer")
                            val links = M3u8Helper.generateM3u8(
                                name,
                                m3u8Url,
                                referer,
                                headers = mapOf("Referer" to referer)
                            )
                            
                            if (links.isNotEmpty()) {
                                links.forEach(callback)
                                println("🎉 Sucesso com extração direta!")
                                return true
                            }
                        } catch (e: Exception) {
                            // Continua para próximo referer
                        }
                    }
                }
            }
            
            false
        } catch (e: Exception) {
            println("⚠️  Falha na extração direta: ${e.message}")
            false
        }
    }
    
    private suspend fun tryAlternativeAPIs(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🔄 Tentando APIs alternativas...")
            
            val videoId = extractVideoId(url) ?: return false
            
            // Lista de APIs alternativas para tentar
            val apiEndpoints = listOf(
                "https://bysevepoin.com/api/source/$videoId",
                "https://fembed.sx/api.php?s=$videoId&c=",
                "https://www.fembed.com/api/source/$videoId",
                "https://fembed-hd.com/api/source/$videoId"
            )
            
            for (apiUrl in apiEndpoints) {
                println("🔗 Tentando API alternativa: $apiUrl")
                
                try {
                    val headers = mapOf(
                        "Content-Type" to "application/x-www-form-urlencoded",
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to "https://superflix21.lol/",
                        "Origin" to "https://superflix21.lol"
                    )
                    
                    // Tentar diferentes parâmetros
                    val dataOptions = listOf(
                        mapOf("action" to "getPlayer", "lang" to "DUB", "key" to "MA=="),
                        mapOf("action" to "getPlayer", "lang" to "LEG", "key" to "MA=="),
                        mapOf("r" to "", "d" to "bysevepoin.com")
                    )
                    
                    for (data in dataOptions) {
                        try {
                            val response = app.post(apiUrl, headers = headers, data = data)
                            val text = response.text
                            
                            // Procurar m3u8
                            val m3u8Match = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").find(text)
                            if (m3u8Match != null) {
                                val m3u8Url = m3u8Match.groupValues[1]
                                println("✅ API alternativa funcionou! M3U8: $m3u8Url")
                                return generateM3u8Links(m3u8Url, name, callback)
                            }
                        } catch (e: Exception) {
                            // Continua para próxima opção
                        }
                    }
                } catch (e: Exception) {
                    println("⚠️  Falha na API $apiUrl: ${e.message}")
                }
            }
            
            false
        } catch (e: Exception) {
            println("💥 Erro nas APIs alternativas: ${e.message}")
            false
        }
    }
    
    private fun generateM3u8Links(
        m3u8Url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // Lista de referers que funcionam
            val referers = listOf(
                "https://g9r6.com/",
                "https://fembed.sx/",
                "https://superflix21.lol/",
                "https://filemoon.sx/",
                "https://embtaku.pro/"
            )
            
            for (referer in referers) {
                try {
                    println("🔄 Gerando links com referer: $referer")
                    
                    val links = M3u8Helper.generateM3u8(
                        name,
                        m3u8Url,
                        referer,
                        headers = mapOf(
                            "Referer" to referer,
                            "Origin" to referer.removeSuffix("/"),
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                        )
                    )
                    
                    if (links.isNotEmpty()) {
                        links.forEach(callback)
                        println("✅ ${links.size} links gerados com sucesso!")
                        return true
                    }
                } catch (e: Exception) {
                    println("⚠️  Falha com referer $referer: ${e.message}")
                }
            }
            
            false
        } catch (e: Exception) {
            println("💥 Erro ao gerar links M3U8: ${e.message}")
            false
        }
    }
    
    private fun isValidM3u8Url(url: String): Boolean {
        // Verifica se a URL parece ser um m3u8 válido
        return url.endsWith(".m3u8") && 
               !url.contains("cdn") && 
               !url.contains("google") &&
               !url.contains("doubleclick") &&
               url.startsWith("http")
    }
}
