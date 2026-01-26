package com.TopAnimes

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.Jsoup

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
                println("🔍 Vamos ver todo o HTML para debug...")
                
                // DEBUG: Mostra partes do HTML
                val html = episodeResponse.text
                val sample = html.take(5000)
                println("📄 Primeiras 5000 chars do HTML:")
                println(sample)
                
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
            
            // 8. PROCURA LINK DO VÍDEO
            println("🔎 Procurando link do vídeo na resposta...")
            val videoLink = findVideoLinkInResponse(playerResponse.text)
            
            if (videoLink == null) {
                println("❌ Nenhum link de vídeo encontrado")
                return false
            }
            
            println("🎬 LINK DO VÍDEO ENCONTRADO: $videoLink")
            
            // 9. CRIA EXTRACTORLINK
            val quality = determineQuality(videoLink)
            val qualityLabel = getQualityLabel(quality)
            
            val extractorLink = newExtractorLink(
                source = "ZUPLAY",
                name = "$name ($qualityLabel)",
                url = videoLink,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = playerUrl
                this.quality = quality
                this.headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Referer" to playerUrl
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
    
    private fun findVideoLinkInResponse(html: String): String? {
        // PADRÃO 1: JWPlayer - "file": "URL"
        val jwPattern = """"file"\s*:\s*"([^"]+)"""".toRegex()
        val jwMatch = jwPattern.find(html)
        
        if (jwMatch != null) {
            val url = jwMatch.groupValues[1].replace("\\/", "/")
            println("🔗 Link JWPlayer encontrado: $url")
            if (isValidVideoUrl(url)) return url
        }
        
        // PADRÃO 2: sources: [{file: "URL"}]
        val sourcesPattern = """sources\s*:\s*\[([^\]]+)\]""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val sourcesMatch = sourcesPattern.find(html)
        
        if (sourcesMatch != null) {
            val sourcesContent = sourcesMatch.groupValues[1]
            val filePattern = """"file"\s*:\s*"([^"]+)"""".toRegex()
            val fileMatch = filePattern.find(sourcesContent)
            
            if (fileMatch != null) {
                val url = fileMatch.groupValues[1].replace("\\/", "/")
                println("🔗 Link em sources encontrado: $url")
                if (isValidVideoUrl(url)) return url
            }
        }
        
        // PADRÃO 3: Links .mp4
        val mp4Pattern = """https?://[^"\s]*\.mp4[^"\s]*""".toRegex()
        val mp4Matches = mp4Pattern.findAll(html)
        
        for (match in mp4Matches) {
            val url = match.value
            println("🔗 Link .mp4 encontrado: $url")
            if (isValidVideoUrl(url)) return url
        }
        
        return null
    }
    
    private fun isValidVideoUrl(url: String): Boolean {
        return url.contains(".mp4") || 
               url.contains("googlevideo.com") || 
               url.contains("discordapp.net") ||
               url.contains("secvideo")
    }
    
    private fun determineQuality(url: String): Int {
        return when {
            url.contains("1080") -> 1080
            url.contains("720") -> 720
            url.contains("480") -> 480
            url.contains("360") -> 360
            else -> 720
        }
    }
    
    private fun getQualityLabel(quality: Int): String {
        return when {
            quality >= 1080 -> "FHD"
            quality >= 720 -> "HD"
            else -> "SD"
        }
    }
}
