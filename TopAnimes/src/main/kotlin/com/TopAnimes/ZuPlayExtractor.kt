package com.TopAnimes

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType

object ZuPlayExtractor {
    
    /**
     * Extrai links de vídeo do player ZUPLAY (/antivirus3/)
     */
    suspend fun extractVideoLinks(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔍 ZUPLAY EXTRACTOR INICIADO")
        println("📄 URL do episódio: $url")
        
        return try {
            // 1. CARREGA PÁGINA
            println("📥 Baixando página...")
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                "Referer" to "https://topanimes.net/",
                "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8"
            )
            
            val episodeResponse = app.get(url, headers = headers, timeout = 30)
            val html = episodeResponse.text
            println("✅ Página carregada (${html.length} chars)")
            
            // 2. PROCURA URL DO PLAYER ZUPLAY
            println("🔎 Procurando URL do player ZUPLAY...")
            
            var playerUrl: String? = null
            
            // Padrão específico para ZUPLAY
            val patterns = listOf(
                """['"](https?://[^'"]*/antivirus3/[^'"]*)['"]""".toRegex(),
                """data-src=['"]([^'"]*/antivirus3/[^'"]*)['"]""".toRegex(),
                """data-player=['"]([^'"]*/antivirus3/[^'"]*)['"]""".toRegex()
            )
            
            patterns.forEach { pattern ->
                val matches = pattern.findAll(html)
                matches.forEach { match ->
                    val foundUrl = match.groupValues.getOrNull(1) ?: return@forEach
                    if (foundUrl.contains("antivirus3")) {
                        playerUrl = foundUrl
                        println("🔗 URL ZUPLAY encontrada: $playerUrl")
                    }
                }
            }
            
            if (playerUrl == null) {
                println("❌ Nenhuma URL do player ZUPLAY encontrada")
                return false
            }
            
            // 3. CORRIGE URL
            val finalPlayerUrl = when {
                playerUrl.startsWith("http") -> playerUrl
                playerUrl.startsWith("//") -> "https:$playerUrl"
                playerUrl.startsWith("/") -> "https://topanimes.net$playerUrl"
                else -> "https://topanimes.net/$playerUrl"
            }
            
            println("🎮 URL final do player: $finalPlayerUrl")
            
            // 4. ACESSA O PLAYER
            println("📤 Acessando player ZUPLAY...")
            val playerHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Referer" to url,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
            )
            
            val playerResponse = app.get(finalPlayerUrl, headers = playerHeaders, timeout = 30)
            val playerHtml = playerResponse.text
            println("✅ Player acessado (${playerHtml.length} chars)")
            
            // 5. PROCURA M3U8 (usa o mesmo método do OdaCDN)
            println("🔎 Procurando M3U8 no player...")
            val m3u8Link = OdaCDNExtractor.extractM3U8FromPlayer(playerHtml)
            
            if (m3u8Link == null) {
                println("❌ Nenhum link M3U8 encontrado no player ZUPLAY")
                return false
            }
            
            println("🎬 LINK M3U8 ENCONTRADO: ${m3u8Link.take(100)}...")
            
            // 6. CRIA EXTRACTORLINK
            val quality = OdaCDNExtractor.determineQuality(m3u8Link)
            val qualityLabel = OdaCDNExtractor.getQualityLabel(quality)
            
            println("📏 Qualidade: $quality ($qualityLabel)")
            
            val extractorLink = newExtractorLink(
                source = "ZUPLAY",
                name = "$name ($qualityLabel) [HLS]",
                url = m3u8Link,
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
            println("💥 ERRO NO ZUPLAY: ${e.message}")
            false
        }
    }
}
