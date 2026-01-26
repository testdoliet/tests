package com.TopAnimes

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType

object OdaCDNExtractor {
    
    /**
     * Extrai links de vídeo do player OdaCDN (/antivirus2/)
     * AGORA com suporte a JavaScript
     */
    suspend fun extractVideoLinks(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔍 ODACDN EXTRACTOR INICIADO")
        println("📄 URL do episódio: $url")
        
        return try {
            // 1. CARREGA PÁGINA COM HEADERS DE NAVEGADOR
            println("📥 Baixando página...")
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
                "Referer" to "https://topanimes.net/",
                "Sec-Fetch-Dest" to "document",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "same-origin",
                "Upgrade-Insecure-Requests" to "1"
            )
            
            val episodeResponse = app.get(url, headers = headers, timeout = 30)
            val html = episodeResponse.text
            println("✅ Página carregada (${html.length} chars)")
            
            // 2. PROCURA URL DO PLAYER ODACDN NO HTML (incluindo JavaScript)
            println("🔎 Procurando URL do player OdaCDN...")
            
            var playerUrl: String? = null
            
            // Padrão 1: URL direta em JavaScript
            val jsPattern = """['"](https?://[^'"]*/antivirus2/[^'"]*)['"]""".toRegex()
            val jsMatches = jsPattern.findAll(html)
            
            jsMatches.forEach { match ->
                val foundUrl = match.groupValues[1]
                println("🔗 URL encontrada em JS: $foundUrl")
                if (foundUrl.contains("antivirus2")) {
                    playerUrl = foundUrl
                }
            }
            
            // Padrão 2: Atributo data-src
            if (playerUrl == null) {
                val dataSrcPattern = """data-src=['"]([^'"]*/antivirus2/[^'"]*)['"]""".toRegex()
                val dataSrcMatch = dataSrcPattern.find(html)
                if (dataSrcMatch != null) {
                    playerUrl = dataSrcMatch.groupValues[1]
                    println("🔗 URL encontrada em data-src: $playerUrl")
                }
            }
            
            // Padrão 3: Iframe src (mesmo que about:blank)
            if (playerUrl == null) {
                val iframePattern = """<iframe[^>]*src=['"]([^'"]*)['"][^>]*>""".toRegex(RegexOption.DOT_MATCHES_ALL)
                val iframeMatches = iframePattern.findAll(html)
                
                iframeMatches.forEach { match ->
                    val src = match.groupValues[1]
                    if (src.contains("antivirus2")) {
                        playerUrl = src
                        println("🔗 URL encontrada em iframe src: $playerUrl")
                    }
                }
            }
            
            if (playerUrl == null) {
                println("❌ Nenhuma URL do player OdaCDN encontrada")
                return false
            }
            
            // 3. CORRIGE URL SE NECESSÁRIO
            val finalPlayerUrl = when {
                playerUrl.startsWith("http") -> playerUrl
                playerUrl.startsWith("//") -> "https:$playerUrl"
                playerUrl.startsWith("/") -> "https://topanimes.net$playerUrl"
                else -> "https://topanimes.net/$playerUrl"
            }
            
            println("🎮 URL final do player: $finalPlayerUrl")
            
            // 4. ACESSA O PLAYER
            println("📤 Acessando player OdaCDN...")
            val playerHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Referer" to url,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
                "Sec-Fetch-Dest" to "iframe",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "same-origin"
            )
            
            val playerResponse = app.get(finalPlayerUrl, headers = playerHeaders, timeout = 30)
            val playerHtml = playerResponse.text
            println("✅ Player acessado (${playerHtml.length} chars)")
            
            // 5. PROCURA M3U8 NO PLAYER
            println("🔎 Procurando M3U8 no player...")
            val m3u8Link = extractM3U8FromPlayer(playerHtml)
            
            if (m3u8Link == null) {
                println("❌ Nenhum link M3U8 encontrado no player")
                return false
            }
            
            println("🎬 LINK M3U8 ENCONTRADO: ${m3u8Link.take(100)}...")
            
            // 6. CRIA EXTRACTORLINK
            val quality = determineQuality(m3u8Link)
            val qualityLabel = getQualityLabel(quality)
            
            println("📏 Qualidade: $quality ($qualityLabel)")
            
            val extractorLink = newExtractorLink(
                source = "OdaCDN",
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
            println("💥 ERRO NO ODACDN: ${e.message}")
            false
        }
    }
    
    /**
     * Extrai link M3U8 do HTML do player
     */
    private fun extractM3U8FromPlayer(html: String): String? {
        println("🔬 Analisando player para M3U8...")
        
        // Padrões comuns em players
        val patterns = listOf(
            // JWPlayer: "file": "URL"
            """"file"\s*:\s*"([^"]+)"""".toRegex(),
            
            // JWPlayer: sources: [{file: "URL"}]
            """sources\s*:\s*\[\{[^}]*"file"\s*:\s*"([^"]+)"""".toRegex(RegexOption.DOT_MATCHES_ALL),
            
            // URL direta .m3u8
            """https?://[^"\s]*\.m3u8[^"\s]*""".toRegex(),
            
            // data-file attribute
            """data-file=["']([^"']+)["']""".toRegex(),
            
            // player.setup({ file: "URL" })
            """player\.setup\([^}]*file\s*:\s*["']([^"']+)["']""".toRegex(RegexOption.DOT_MATCHES_ALL)
        )
        
        patterns.forEachIndexed { index, pattern ->
            val matches = pattern.findAll(html)
            matches.forEach { match ->
                var url = match.groupValues.getOrNull(1) ?: match.value
                
                if (url.contains(".m3u8")) {
                    // Limpa a URL
                    url = url.replace("\\/", "/")
                    url = url.replace("&amp;", "&")
                    url = url.replace("\\\\u002F", "/")
                    
                    println("✅ M3U8 encontrado (padrão $index): ${url.take(100)}...")
                    return url
                }
            }
        }
        
        println("❌ Nenhum padrão M3U8 encontrado")
        return null
    }
    
    /**
     * Determina qualidade
     */
    private fun determineQuality(url: String): Int {
        return when {
            url.contains("1080") || url.contains("fhd") -> 1080
            url.contains("720") || url.contains("hd") -> 720
            url.contains("480") -> 480
            url.contains("360") -> 360
            else -> 720 // padrão
        }
    }
    
    /**
     * Rótulo da qualidade
     */
    private fun getQualityLabel(quality: Int): String {
        return when {
            quality >= 1080 -> "FHD"
            quality >= 720 -> "HD"
            quality >= 480 -> "SD"
            else -> "SD"
        }
    }
}
