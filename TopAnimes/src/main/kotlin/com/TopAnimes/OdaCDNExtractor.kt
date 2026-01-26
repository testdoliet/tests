package com.TopAnimes

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType

object OdaCDNExtractor {
    
    suspend fun extractVideoLinks(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔍 ODACDN EXTRACTOR INICIADO")
        println("📄 URL do episódio: $url")
        
        return try {
            // 1. CARREGA PÁGINA DO EPISÓDIO
            println("📥 Baixando página do episódio...")
            val episodeResponse = app.get(url)
            val html = episodeResponse.text
            println("✅ Página carregada (${html.length} chars)")
            
            // 2. PROCURA URL DO PLAYER ODACDN (/antivirus2/) EM TODO O HTML
            println("🔎 Procurando URL /antivirus2/ em todo o HTML...")
            
            var playerUrl: String? = null
            
            // Padrões para encontrar URLs ODACDN
            val odaPatterns = listOf(
                """https?://[^"\s']*/antivirus2/[^"\s']*""".toRegex(),
                """['"]https?://[^'"]*/antivirus2/[^'"]*['"]""".toRegex(),
                """src=['"][^'"]*/antivirus2/[^'"]*['"]""".toRegex(),
                """data-src=['"][^'"]*/antivirus2/[^'"]*['"]""".toRegex(),
                """href=['"][^'"]*/antivirus2/[^'"]*['"]""".toRegex()
            )
            
            for (pattern in odaPatterns) {
                val matches = pattern.findAll(html)
                for (match in matches) {
                    var foundUrl = match.value
                    
                    // Limpa aspas e outros caracteres
                    foundUrl = foundUrl.replace("'", "").replace("\"", "").replace("src=", "")
                        .replace("data-src=", "").replace("href=", "")
                    
                    if (foundUrl.contains("/antivirus2/")) {
                        println("🎯 URL ODACDN ENCONTRADA: $foundUrl")
                        playerUrl = foundUrl
                        break
                    }
                }
                if (playerUrl != null) break
            }
            
            if (playerUrl == null) {
                println("❌ Nenhuma URL /antivirus2/ encontrada")
                return false
            }
            
            // 3. CORRIGE A URL SE NECESSÁRIO
            val finalPlayerUrl = when {
                playerUrl.startsWith("http") -> playerUrl
                playerUrl.startsWith("//") -> "https:$playerUrl"
                playerUrl.startsWith("/") -> "https://topanimes.net$playerUrl"
                else -> {
                    // Se começa com antivirus2 diretamente
                    if (playerUrl.startsWith("antivirus2")) {
                        "https://topanimes.net/$playerUrl"
                    } else {
                        playerUrl
                    }
                }
            }
            
            println("🎮 URL final do player: $finalPlayerUrl")
            
            // 4. ACESSA O PLAYER
            println("📤 Acessando player OdaCDN...")
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Referer" to url,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8"
            )
            
            val playerResponse = app.get(finalPlayerUrl, headers = headers, timeout = 30)
            val playerHtml = playerResponse.text
            println("✅ Player acessado (${playerHtml.length} chars)")
            
            // 5. PROCURA LINK M3U8
            println("🔎 Procurando link M3U8 na resposta...")
            val videoLink = extractM3U8FromPlayer(playerHtml)
            
            if (videoLink == null) {
                println("❌ Nenhum link M3U8 encontrado")
                return false
            }
            
            println("🎬 LINK M3U8 ENCONTRADO: ${videoLink.take(100)}...")
            
            // 6. CRIA EXTRACTORLINK
            val quality = determineQuality(videoLink)
            val qualityLabel = getQualityLabel(quality)
            
            val extractorLink = newExtractorLink(
                source = "OdaCDN",
                name = "$name ($qualityLabel) [HLS]",
                url = videoLink,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = finalPlayerUrl
                this.quality = quality
                this.headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Referer" to finalPlayerUrl,
                    "Origin" to "https://topanimes.net"
                )
            }
            
            println("✅ ExtractorLink criado com sucesso!")
            callback(extractorLink)
            true
            
        } catch (e: Exception) {
            println("💥 ERRO NO ODACDN: ${e.message}")
            false
        }
    }
    
    // Mantém as funções públicas existentes...
    fun extractM3U8FromPlayer(html: String): String? {
        // ... mantém o mesmo código
    }
    
    fun determineQuality(url: String): Int {
        // ... mantém o mesmo código
    }
    
    fun getQualityLabel(quality: Int): String {
        // ... mantém o mesmo código
    }
}
