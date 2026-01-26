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
            
            // 2. PROCURA IFRAME DO ZUPLAY
            println("🔎 Procurando iframe do ZUPLAY...")
            val iframes = doc.select("iframe")
            println("📊 Total de iframes encontrados: ${iframes.size}")
            
            var zuplayIframe: org.jsoup.nodes.Element? = null
            var zuplaySrc: String? = null
            
            for (iframe in iframes) {
                val src = iframe.attr("src")
                println("  Iframe src: $src")
                
                if (src.contains("/antivirus3/")) {
                    zuplayIframe = iframe
                    zuplaySrc = src
                    println("🎯 Iframe ZUPLAY encontrado!")
                    break
                }
            }
            
            if (zuplaySrc == null) {
                println("❌ Nenhum iframe /antivirus3/ encontrado")
                return false
            }
            
            println("🔗 Iframe src original: $zuplaySrc")
            
            // 3. MONTA URL DO PLAYER
            val playerUrl = when {
                zuplaySrc.startsWith("http") -> zuplaySrc
                zuplaySrc.startsWith("//") -> "https:$zuplaySrc"
                zuplaySrc.startsWith("/") -> "https://topanimes.net$zuplaySrc"
                else -> "https://topanimes.net/$zuplaySrc"
            }
            
            println("🎮 URL do player montada: $playerUrl")
            
            // 4. FAZ REQUEST PRO PLAYER
            println("📤 Fazendo request para o player...")
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Referer" to url,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8"
            )
            
            println("📋 Headers da request:")
            headers.forEach { (key, value) ->
                println("  $key: $value")
            }
            
            val playerResponse = app.get(playerUrl, headers = headers, timeout = 30)
            println("✅ Resposta do player recebida")
            println("📊 Status: ${playerResponse.code}")
            println("📄 Tamanho: ${playerResponse.text.length} chars")
            
            // Salva para debug
            val responseText = playerResponse.text
            if (responseText.length < 1000) {
                println("📝 Resposta (primeiros 1000 chars):")
                println(responseText.take(1000))
            }
            
            // 5. PROCURA LINK DO VÍDEO
            println("🔎 Procurando link do vídeo na resposta...")
            val videoLink = findVideoLinkInResponse(responseText)
            
            if (videoLink == null) {
                println("❌ Nenhum link de vídeo encontrado na resposta")
                
                // Debug: mostra partes da resposta
                println("🔍 Analisando resposta para debug...")
                analyzeResponseForDebug(responseText)
                return false
            }
            
            println("🎬 LINK DO VÍDEO ENCONTRADO: $videoLink")
            
            // 6. DETERMINA QUALIDADE
            val quality = determineQuality(videoLink)
            val qualityLabel = getQualityLabel(quality)
            println("📏 Qualidade detectada: $quality ($qualityLabel)")
            
            // 7. CRIA EXTRACTORLINK
            println("🏗️ Criando ExtractorLink...")
            val extractorLink = newExtractorLink(
                source = "ZUPLAY",
                name = "$name ($qualityLabel) [MP4]",
                url = videoLink,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = playerUrl
                this.quality = quality
                this.headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Referer" to playerUrl,
                    "Accept" to "video/webm,video/mp4,*/*"
                )
            }
            
            println("✅ ExtractorLink criado com sucesso!")
            println("📤 Enviando callback...")
            
            callback(extractorLink)
            true
            
        } catch (e: Exception) {
            println("💥 ERRO NO ZUPLAY EXTRACTOR: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    private fun findVideoLinkInResponse(html: String): String? {
        println("🔬 Iniciando análise da resposta HTML...")
        
        // PADRÃO 1: JWPlayer - "file": "URL"
        println("📝 Padrão 1: Procurando 'file': 'URL' no JWPlayer...")
        val jwPlayerPattern = """"file"\s*:\s*"([^"]+)"""".toRegex()
        val jwMatch = jwPlayerPattern.find(html)
        
        if (jwMatch != null) {
            var url = jwMatch.groupValues[1]
            println("🎯 Padrão 1 encontrado! URL bruta: $url")
            
            url = url.replace("\\/", "/")
            println("🔧 URL após replace: $url")
            
            if (isValidVideoUrl(url)) {
                println("✅ URL válida encontrada pelo padrão 1")
                return url
            } else {
                println("❌ URL do padrão 1 não é válida")
            }
        } else {
            println("❌ Padrão 1 não encontrado")
        }
        
        // PADRÃO 2: sources: [{file: "URL"}]
        println("📝 Padrão 2: Procurando sources: [{file: 'URL'}]...")
        val sourcesPattern = """sources\s*:\s*\[([^\]]+)\]""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val sourcesMatch = sourcesPattern.find(html)
        
        if (sourcesMatch != null) {
            println("🎯 Padrão 2 encontrado!")
            val sourcesContent = sourcesMatch.groupValues[1]
            println("📄 Conteúdo do sources: ${sourcesContent.take(200)}...")
            
            val filePattern = """"file"\s*:\s*"([^"]+)"""".toRegex()
            val fileMatch = filePattern.find(sourcesContent)
            
            if (fileMatch != null) {
                var url = fileMatch.groupValues[1]
                println("🔗 URL do file: $url")
                
                url = url.replace("\\/", "/")
                println("🔧 URL após replace: $url")
                
                if (isValidVideoUrl(url)) {
                    println("✅ URL válida encontrada pelo padrão 2")
                    return url
                } else {
                    println("❌ URL do padrão 2 não é válida")
                }
            } else {
                println("❌ 'file' não encontrado dentro do sources")
            }
        } else {
            println("❌ Padrão 2 não encontrado")
        }
        
        // PADRÃO 3: Links .mp4 diretos
        println("📝 Padrão 3: Procurando links .mp4 diretos...")
        val mp4Pattern = """https?://[^"\s<>]*\.mp4[^"\s<>]*""".toRegex()
        val mp4Matches = mp4Pattern.findAll(html)
        var mp4Count = 0
        
        for (match in mp4Matches) {
            mp4Count++
            val url = match.value
            println("🔗 MP4 encontrado #$mp4Count: $url")
            
            if (isValidVideoUrl(url)) {
                println("✅ MP4 válido encontrado!")
                return url
            }
        }
        
        println("📊 Total de links .mp4 encontrados: $mp4Count")
        
        // PADRÃO 4: googlevideo.com
        println("📝 Padrão 4: Procurando googlevideo.com...")
        val googlePattern = """https?://[^"\s<>]*googlevideo\.com[^"\s<>]*""".toRegex()
        val googleMatches = googlePattern.findAll(html)
        var googleCount = 0
        
        for (match in googleMatches) {
            googleCount++
            val url = match.value
            println("🔗 Google Video encontrado #$googleCount: ${url.take(100)}...")
            
            if (url.contains("videoplayback") && !url.contains("m3u8")) {
                println("✅ Google Video válido encontrado!")
                return url
            }
        }
        
        println("📊 Total de links googlevideo.com encontrados: $googleCount")
        
        // PADRÃO 5: discordapp.net
        println("📝 Padrão 5: Procurando discordapp.net...")
        val discordPattern = """https?://[^"\s<>]*discordapp\.net[^"\s<>]*""".toRegex()
        val discordMatches = discordPattern.findAll(html)
        var discordCount = 0
        
        for (match in discordMatches) {
            discordCount++
            val url = match.value
            println("🔗 Discord encontrado #$discordCount: $url")
            
            if (url.contains(".mp4") || url.contains("attachments")) {
                println("✅ Discord válido encontrado!")
                return url
            }
        }
        
        println("📊 Total de links discordapp.net encontrados: $discordCount")
        
        println("❌ Nenhum link de vídeo encontrado em nenhum padrão")
        return null
    }
    
    private fun analyzeResponseForDebug(html: String) {
        println("🔍 DEBUG - Análise detalhada da resposta:")
        
        // Verifica se tem JWPlayer
        val hasJWPlayer = html.contains("jwplayer", ignoreCase = true)
        println("🎮 Contém 'jwplayer': $hasJWPlayer")
        
        // Verifica se tem 'file'
        val hasFile = html.contains("\"file\"", ignoreCase = true)
        println("📁 Contém 'file': $hasFile")
        
        // Verifica se tem 'sources'
        val hasSources = html.contains("sources", ignoreCase = true)
        println("📦 Contém 'sources': $hasSources")
        
        // Mostra trecho ao redor de 'file' se existir
        if (hasFile) {
            val fileIndex = html.indexOf("\"file\"")
            val start = maxOf(0, fileIndex - 100)
            val end = minOf(html.length, fileIndex + 200)
            println("📄 Trecho ao redor de 'file':")
            println(html.substring(start, end))
        }
        
        // Mostra trecho ao redor de 'sources' se existir
        if (hasSources) {
            val sourcesIndex = html.indexOf("sources")
            val start = maxOf(0, sourcesIndex - 100)
            val end = minOf(html.length, sourcesIndex + 200)
            println("📄 Trecho ao redor de 'sources':")
            println(html.substring(start, end))
        }
    }
    
    private fun isValidVideoUrl(url: String): Boolean {
        val isValid = url.contains(".mp4") || 
                     url.contains("googlevideo.com") || 
                     url.contains("discordapp.net") ||
                     url.contains("secvideo")
        
        println("🔍 Validando URL '$url': $isValid")
        return isValid
    }
    
    private fun determineQuality(url: String): Int {
        val quality = when {
            url.contains("1080") || url.contains("1080p") -> 1080
            url.contains("720") || url.contains("720p") -> 720
            url.contains("480") || url.contains("480p") -> 480
            url.contains("360") || url.contains("360p") -> 360
            else -> {
                println("📏 Qualidade não detectada na URL, usando padrão 720")
                720
            }
        }
        
        println("📏 Qualidade determinada: $quality")
        return quality
    }
    
    private fun getQualityLabel(quality: Int): String {
        return when {
            quality >= 1080 -> "FHD"
            quality >= 720 -> "HD"
            quality >= 480 -> "SD"
            else -> "SD"
        }
    }
}
