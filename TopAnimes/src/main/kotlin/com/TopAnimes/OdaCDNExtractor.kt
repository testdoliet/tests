package com.TopAnimes

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.Jsoup

object OdaCDNExtractor {
    
    /**
     * Extrai links de vídeo do player OdaCDN (/antivirus2/)
     */
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
            val doc = episodeResponse.document
            println("✅ Página carregada (${episodeResponse.text.length} chars)")
            
            // 2. PROCURA IFRAME COM /antivirus2/
            println("🔎 Procurando iframe /antivirus2/...")
            
            var odaIframeSrc: String? = null
            
            // Procura todos os iframes
            val allIframes = doc.select("iframe")
            println("📊 Total de iframes na página: ${allIframes.size}")
            
            for ((index, iframe) in allIframes.withIndex()) {
                val src = iframe.attr("src")
                println("🔗 Iframe #${index + 1}: $src")
                
                if (src.contains("/antivirus2/")) {
                    println("🎯 ENCONTRADO IFRAME ODACDN! (/antivirus2/)")
                    odaIframeSrc = src
                    break
                }
            }
            
            // Se não encontrou diretamente, procura em source-box
            if (odaIframeSrc == null) {
                println("🔍 Procurando em source-box...")
                val sourceBoxes = doc.select(".source-box")
                
                for ((index, box) in sourceBoxes.withIndex()) {
                    val iframeInBox = box.selectFirst("iframe")
                    val src = iframeInBox?.attr("src") ?: continue
                    
                    println("📦 Source-box #${index + 1} iframe: $src")
                    
                    if (src.contains("/antivirus2/")) {
                        println("🎯 ENCONTRADO ODACDN NO SOURCE-BOX!")
                        odaIframeSrc = src
                        break
                    }
                }
            }
            
            if (odaIframeSrc == null) {
                println("❌ NENHUM IFRAME /antivirus2/ ENCONTRADO!")
                return false
            }
            
            println("✅ IFRAME ODACDN ENCONTRADO: $odaIframeSrc")
            
            // 3. MONTA URL DO PLAYER (já está completa)
            val playerUrl = when {
                odaIframeSrc.startsWith("http") -> odaIframeSrc
                odaIframeSrc.startsWith("//") -> "https:$odaIframeSrc"
                odaIframeSrc.startsWith("/") -> "https://topanimes.net$odaIframeSrc"
                else -> "https://topanimes.net/$odaIframeSrc"
            }
            
            println("🎮 URL do player: $playerUrl")
            
            // 4. FAZ REQUEST PRO PLAYER
            println("📤 Fazendo request para o player OdaCDN...")
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
            
            // 5. PROCURA LINK DO VÍDEO (M3U8 no JWPlayer)
            println("🔎 Procurando link M3U8 na resposta...")
            val videoLink = findM3U8LinkInResponse(playerResponse.text)
            
            if (videoLink == null) {
                println("❌ Nenhum link M3U8 encontrado")
                return false
            }
            
            println("🎬 LINK M3U8 ENCONTRADO: $videoLink")
            
            // 6. CRIA EXTRACTORLINK (M3U8)
            val quality = determineQuality(videoLink)
            val qualityLabel = getQualityLabel(quality)
            
            println("📏 Qualidade: $quality ($qualityLabel)")
            
            // Para M3U8
            val extractorLink = newExtractorLink(
                source = "OdaCDN",
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
            println("💥 ERRO NO ODACDN: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    /**
     * Procura link M3U8 na resposta do player
     */
    private fun findM3U8LinkInResponse(html: String): String? {
        println("🔬 Analisando resposta para M3U8...")
        
        // PADRÃO 1: JWPlayer com M3U8
        val jwPattern = """"file"\s*:\s*"([^"]+)"""".toRegex()
        val jwMatch = jwPattern.find(html)
        
        if (jwMatch != null) {
            var url = jwMatch.groupValues[1]
            println("🔗 URL encontrada no JWPlayer: $url")
            
            url = url.replace("\\/", "/")
            url = url.replace("&amp;", "&")
            
            if (url.contains(".m3u8")) {
                println("✅ É um link M3U8 válido!")
                return url
            }
        }
        
        // PADRÃO 2: sources: [{file: "URL"}]
        val sourcesPattern = """sources\s*:\s*\[([^\]]+)\]""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val sourcesMatch = sourcesPattern.find(html)
        
        if (sourcesMatch != null) {
            val sourcesContent = sourcesMatch.groupValues[1]
            println("📦 Conteúdo do sources: ${sourcesContent.take(200)}...")
            
            val filePattern = """"file"\s*:\s*"([^"]+)"""".toRegex()
            val fileMatch = filePattern.find(sourcesContent)
            
            if (fileMatch != null) {
                var url = fileMatch.groupValues[1]
                println("🔗 URL no sources: $url")
                
                url = url.replace("\\/", "/")
                url = url.replace("&amp;", "&")
                
                if (url.contains(".m3u8")) {
                    println("✅ M3U8 encontrado no sources!")
                    return url
                }
            }
        }
        
        // PADRÃO 3: Links .m3u8 diretos
        val m3u8Pattern = """https?://[^"\s]*\.m3u8[^"\s]*""".toRegex()
        val m3u8Matches = m3u8Pattern.findAll(html)
        
        for (match in m3u8Matches) {
            val url = match.value
            println("🔗 Link .m3u8 encontrado: $url")
            
            if (url.contains("token=") && url.contains("expires=")) {
                println("✅ M3U8 com token encontrado!")
                return url
            }
        }
        
        println("❌ Nenhum link M3U8 encontrado")
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
