package com.TopAnimes

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType

object ZuPlayExtractor {
    
    suspend fun extractVideoLinks(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔍 ZUPLAY EXTRACTOR INICIADO")
        println("📄 URL do episódio: $url")
        
        return try {
            // 1. CARREGA PÁGINA DO EPISÓDIO
            println("📥 Baixando página do episódio...")
            val episodeResponse = app.get(url)
            val doc = episodeResponse.document
            println("✅ Página carregada (${episodeResponse.text.length} chars)")
            
            // 2. PROCURA TODOS OS IFRAMES
            println("🔎 Procurando todos os iframes...")
            val allIframes = doc.select("iframe")
            println("📊 Total de iframes na página: ${allIframes.size}")
            
            // 3. PROCURA ESPECIFICAMENTE O IFRAME COM /antivirus3/
            var zuplayIframeSrc: String? = null
            
            for ((index, iframe) in allIframes.withIndex()) {
                val src = iframe.attr("src")
                println("🔗 Iframe #${index + 1}: $src")
                
                if (src.contains("/antivirus3/")) {
                    println("🎯 ENCONTRADO IFRAME ZUPLAY! (antivirus3)")
                    zuplayIframeSrc = src
                    break
                }
            }
            
            // 4. SE NÃO ENCONTROU DIRETO, PROCURA EM source-box
            if (zuplayIframeSrc == null) {
                println("🔍 Não encontrou iframe diretamente, procurando em source-box...")
                
                val sourceBoxes = doc.select(".source-box")
                println("📦 Total de source-box encontrados: ${sourceBoxes.size}")
                
                for ((index, box) in sourceBoxes.withIndex()) {
                    val iframeInBox = box.selectFirst("iframe")
                    val src = iframeInBox?.attr("src") ?: continue
                    
                    println("📦 Source-box #${index + 1} iframe src: $src")
                    
                    if (src.contains("/antivirus3/")) {
                        println("🎯 ENCONTRADO ZUPLAY NO SOURCE-BOX!")
                        zuplayIframeSrc = src
                        break
                    }
                }
            }
            
            // 5. SE AINDA NÃO ENCONTROU, PROCURA EM TODOS OS ELEMENTOS
            if (zuplayIframeSrc == null) {
                println("🔍 Procurando em todo o HTML por URLs com /antivirus3/...")
                
                val html = episodeResponse.text
                val antivirusPattern = """https?://[^"\s]*/antivirus3/[^"\s]*""".toRegex()
                val matches = antivirusPattern.findAll(html)
                
                matches.forEach { match ->
                    println("🔗 URL /antivirus3/ encontrada no HTML: ${match.value}")
                    if (match.value.contains("/antivirus3/")) {
                        zuplayIframeSrc = match.value
                    }
                }
            }
            
            if (zuplayIframeSrc == null) {
                println("❌ NENHUM IFRAME COM /antivirus3/ ENCONTRADO!")
                return false
            }
            
            println("✅ IFRAME ZUPLAY ENCONTRADO: $zuplayIframeSrc")
            
            // 6. MONTA URL DO PLAYER
            val playerUrl = when {
                zuplayIframeSrc.startsWith("http") -> zuplayIframeSrc
                zuplayIframeSrc.startsWith("//") -> "https:$zuplayIframeSrc"
                zuplayIframeSrc.startsWith("/") -> "https://topanimes.net$zuplayIframeSrc"
                else -> {
                    // Se não começa com nada, assume que é relativo
                    val baseUrl = url.substringBeforeLast("/")
                    "$baseUrl/$zuplayIframeSrc"
                }
            }
            
            println("🎮 URL do player final: $playerUrl")
            
            // 7. FAZ REQUEST PRO PLAYER
            println("📤 Fazendo request para o player...")
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Referer" to url,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
                "Sec-Fetch-Dest" to "iframe",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "same-origin"
            )
            
            val playerResponse = app.get(playerUrl, headers = headers, timeout = 30)
            println("✅ Resposta do player recebida (${playerResponse.text.length} chars)")
            
            // 8. PROCURA LINK M3U8 (usando a função pública do OdaCDNExtractor)
            println("🔎 Procurando link M3U8 na resposta...")
            val videoLink = OdaCDNExtractor.extractM3U8FromPlayer(playerResponse.text)
            
            if (videoLink == null) {
                println("❌ Nenhum link M3U8 encontrado")
                return false
            }
            
            println("🎬 LINK M3U8 ENCONTRADO: $videoLink")
            
            // 9. CRIA EXTRACTORLINK
            val quality = OdaCDNExtractor.determineQuality(videoLink)
            val qualityLabel = OdaCDNExtractor.getQualityLabel(quality)
            
            println("📏 Qualidade: $quality ($qualityLabel)")
            
            // Para M3U8
            val extractorLink = newExtractorLink(
                source = "ZUPLAY",
                name = "$name ($qualityLabel) [HLS]",
                url = videoLink,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = playerUrl
                this.quality = quality
                this.headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Referer" to playerUrl,
                    "Origin" to "https://topanimes.net"
                )
            }
            
            println("✅ ExtractorLink criado com sucesso!")
            callback(extractorLink)
            true
            
        } catch (e: Exception) {
            println("💥 ERRO NO ZUPLAY: ${e.message}")
            e.printStackTrace()
            false
        }
    }
}
