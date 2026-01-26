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
            val html = episodeResponse.text
            println("✅ Página carregada (${html.length} chars)")
            
            // 2. PROCURA URL DO PLAYER ZUPLAY (/antivirus3/) EM TODO O HTML
            println("🔎 Procurando URL /antivirus3/ em todo o HTML...")
            
            var playerUrl: String? = null
            
            // Padrões para encontrar URLs ZUPLAY
            val zuplayPatterns = listOf(
                """https?://[^"\s']*/antivirus3/[^"\s']*""".toRegex(),
                """['"]https?://[^'"]*/antivirus3/[^'"]*['"]""".toRegex(),
                """src=['"][^'"]*/antivirus3/[^'"]*['"]""".toRegex(),
                """data-src=['"][^'"]*/antivirus3/[^'"]*['"]""".toRegex()
            )
            
            for (pattern in zuplayPatterns) {
                val matches = pattern.findAll(html)
                for (match in matches) {
                    var foundUrl = match.value
                    
                    // Limpa aspas e outros caracteres
                    foundUrl = foundUrl.replace("'", "").replace("\"", "").replace("src=", "").replace("data-src=", "")
                    
                    if (foundUrl.contains("/antivirus3/")) {
                        println("🎯 URL ZUPLAY ENCONTRADA: $foundUrl")
                        playerUrl = foundUrl
                        break
                    }
                }
                if (playerUrl != null) break
            }
            
            if (playerUrl == null) {
                println("❌ Nenhuma URL /antivirus3/ encontrada")
                return false
            }
            
            // 3. CORRIGE A URL SE NECESSÁRIO
            val finalPlayerUrl = when {
                playerUrl.startsWith("http") -> playerUrl
                playerUrl.startsWith("//") -> "https:$playerUrl"
                playerUrl.startsWith("/") -> "https://topanimes.net$playerUrl"
                else -> {
                    // Se começa com antivirus3 diretamente
                    if (playerUrl.startsWith("antivirus3")) {
                        "https://topanimes.net/$playerUrl"
                    } else {
                        playerUrl
                    }
                }
            }
            
            println("🎮 URL final do player: $finalPlayerUrl")
            
            // 4. ACESSA O PLAYER
            println("📤 Acessando player ZUPLAY...")
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Referer" to url,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8"
            )
            
            val playerResponse = app.get(finalPlayerUrl, headers = headers, timeout = 30)
            val playerHtml = playerResponse.text
            println("✅ Player acessado (${playerHtml.length} chars)")
            
            // 5. PROCURA LINKS DE VÍDEO (MP4 OU M3U8)
            println("🔎 Procurando links de vídeo na resposta...")
            
            // Primeiro tenta M3U8 usando o método do OdaCDN
            val m3u8Link = OdaCDNExtractor.extractM3U8FromPlayer(playerHtml)
            if (m3u8Link != null) {
                println("🎬 LINK M3U8 ENCONTRADO: ${m3u8Link.take(100)}...")
                // AGORA É SUSPEND!
                createExtractorLink(m3u8Link, name, finalPlayerUrl, callback, true)
                return true
            }
            
            // Se não encontrou M3U8, procura MP4
            val mp4Link = extractMP4FromPlayer(playerHtml)
            if (mp4Link != null) {
                println("🎬 LINK MP4 ENCONTRADO: ${mp4Link.take(100)}...")
                // AGORA É SUSPEND!
                createExtractorLink(mp4Link, name, finalPlayerUrl, callback, false)
                return true
            }
            
            // Tenta procurar qualquer link de vídeo
            val anyVideoLink = extractAnyVideoLink(playerHtml)
            if (anyVideoLink != null) {
                println("🎬 LINK DE VÍDEO ENCONTRADO: ${anyVideoLink.take(100)}...")
                // AGORA É SUSPEND!
                createExtractorLink(anyVideoLink, name, finalPlayerUrl, callback, anyVideoLink.contains(".m3u8"))
                return true
            }
            
            println("❌ Nenhum link de vídeo encontrado no player")
            false
            
        } catch (e: Exception) {
            println("💥 ERRO NO ZUPLAY: ${e.message}")
            false
        }
    }
    
    /**
     * Extrai link MP4 do HTML do player
     */
    private fun extractMP4FromPlayer(html: String): String? {
        println("🔬 Analisando player para MP4...")
        
        // Padrões para MP4
        val patterns = listOf(
            // JWPlayer: "file": "URL"
            """"file"\s*:\s*"([^"]+)"""".toRegex(),
            
            // URL direta .mp4
            """https?://[^"\s]*\.mp4[^"\s]*""".toRegex(),
            
            // sources: [{file: "URL"}]
            """sources\s*:\s*\[\{[^}]*"file"\s*:\s*"([^"]+)"""".toRegex(RegexOption.DOT_MATCHES_ALL),
            
            // data-file attribute
            """data-file=["']([^"']+)["']""".toRegex(),
            
            // Video tag src
            """<source[^>]*src=["']([^"']+)["'][^>]*>""".toRegex(),
            
            // player.setup({ file: "URL" })
            """player\.setup\([^}]*file\s*:\s*["']([^"']+)["']""".toRegex(RegexOption.DOT_MATCHES_ALL)
        )
        
        patterns.forEachIndexed { index, pattern ->
            val matches = pattern.findAll(html)
            matches.forEach { match ->
                var url = match.groupValues.getOrNull(1) ?: match.value
                
                // Limpa a URL
                url = url.replace("\\/", "/")
                url = url.replace("&amp;", "&")
                url = url.replace("\\\\u002F", "/")
                
                if (url.contains(".mp4") || url.contains("googlevideo") || url.contains("video")) {
                    println("✅ Vídeo encontrado (padrão $index): ${url.take(100)}...")
                    return url
                }
            }
        }
        
        println("❌ Nenhum padrão MP4 encontrado")
        return null
    }
    
    /**
     * Extrai qualquer link de vídeo
     */
    private fun extractAnyVideoLink(html: String): String? {
        println("🔍 Procurando qualquer link de vídeo...")
        
        // Procura por URLs que parecem ser de vídeo
        val videoPattern = """https?://[^"\s]*(?:\.mp4|\.m3u8|video|stream)[^"\s]*""".toRegex(RegexOption.IGNORE_CASE)
        val matches = videoPattern.findAll(html)
        
        for (match in matches) {
            val url = match.value
            println("🔗 URL de vídeo possível: ${url.take(100)}...")
            
            // Verifica se parece ser um link de vídeo válido
            if (isValidVideoUrl(url)) {
                println("✅ Link de vídeo válido encontrado!")
                return url
            }
        }
        
        return null
    }
    
    /**
     * Verifica se é uma URL de vídeo válida
     */
    private fun isValidVideoUrl(url: String): Boolean {
        return url.contains(".mp4") || 
               url.contains(".m3u8") ||
               url.contains("googlevideo.com") ||
               url.contains("video") ||
               url.contains("stream") ||
               url.contains("secvideo")
    }
    
    /**
     * Cria o ExtractorLink - AGORA É SUSPEND!
     */
    private suspend fun createExtractorLink(
        videoUrl: String,
        name: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        isM3U8: Boolean
    ) {
        val quality = OdaCDNExtractor.determineQuality(videoUrl)
        val qualityLabel = OdaCDNExtractor.getQualityLabel(quality)
        val type = if (isM3U8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        
        // newExtractorLink É SUSPEND, então esta função também precisa ser suspend
        val extractorLink = newExtractorLink(
            source = "ZUPLAY",
            name = "$name ($qualityLabel) [${if (isM3U8) "HLS" else "MP4"}]",
            url = videoUrl,
            type = type
        ) {
            this.referer = referer
            this.quality = quality
            this.headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Referer" to referer,
                "Origin" to "https://topanimes.net"
            )
        }
        
        println("✅ ExtractorLink criado com sucesso!")
        callback(extractorLink)
    }
}
