package com.Hypeflix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.runBlocking

object HypeflixExtractor {
    // Constantes para seletores CSS
    private const val PLAYER_FHD = "iframe[src*='m3u8'][src*='1080p']"
    private const val PLAYER_HD = "iframe[src*='m3u8'][src*='720p']"
    private const val PLAYER_SD = "iframe[src*='m3u8'][src*='480p'], iframe[src*='m3u8'][src*='360p']"
    private const val PLAYER_BACKUP = "iframe[src*='m3u8']:not([src*='1080p']):not([src*='720p']):not([src*='480p']):not([src*='360p'])"
    
    suspend fun extractVideoLinks(
        data: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[HypeflixExtractor] === INICIANDO EXTRACTION ===")
        println("[HypeflixExtractor] Data recebida: $data")
        println("[HypeflixExtractor] Referer: $referer")
        
        // Separar URL e poster
        val parts = data.split("|poster=")
        val actualUrl = parts[0]
        val poster = parts.getOrNull(1)
        
        println("[HypeflixExtractor] URL extraída: $actualUrl")
        println("[HypeflixExtractor] Poster extraído: ${poster ?: "Nenhum"}")
        
        // Verificar se a URL é válida
        if (actualUrl.isBlank() || !actualUrl.startsWith("http")) {
            println("[HypeflixExtractor] ERRO: URL inválida ou vazia")
            return false
        }
        
        try {
            println("[HypeflixExtractor] Acessando página: $actualUrl")
            println("[HypeflixExtractor] Headers sendo enviados: referer=$referer")
            
            val response = app.get(actualUrl, referer = referer)
            val document = response.document
            val statusCode = response.code
            
            println("[HypeflixExtractor] Status code: $statusCode")
            println("[HypeflixExtractor] Título da página: ${document.title()}")
            
            // Verificar conteúdo da página
            val pageContentPreview = document.html().take(500)
            println("[HypeflixExtractor] Primeiros 500 chars da página: $pageContentPreview")
            
            // Contar iframes
            val iframes = document.select("iframe")
            println("[HypeflixExtractor] Total de iframes encontrados: ${iframes.size}")
            
            iframes.forEachIndexed { index, iframe ->
                val src = iframe.attr("src")
                println("[HypeflixExtractor] Iframe $index - src: $src")
                println("[HypeflixExtractor] Iframe $index - atributos: ${iframe.attributes()}")
            }
            
            // Contar scripts
            val scripts = document.select("script")
            println("[HypeflixExtractor] Total de scripts: ${scripts.size}")
            
            var linksFound = false

            // DEBUG: Verificar seletores
            println("[HypeflixExtractor] Verificando seletores CSS...")
            
            // Player FHD (1080p)
            val playerFhd = document.selectFirst(PLAYER_FHD)
            println("[HypeflixExtractor] Player FHD encontrado: ${playerFhd != null}")
            playerFhd?.let { iframe ->
                val src = iframe.attr("src").takeIf { it.isNotBlank() } ?: return@let
                println("[HypeflixExtractor] Player FHD src: $src")
                
                val m3u8Url = extractM3u8FromUrl(src) ?: src
                println("[HypeflixExtractor] Player FHD m3u8 final: $m3u8Url")

                callback(
                    newExtractorLink(
                        "Hypeflix",
                        "Player FHD",
                        m3u8Url,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$referer/"
                        quality = 1080
                    }
                )
                println("[HypeflixExtractor] Link FHD adicionado com sucesso!")
                linksFound = true
            }

            // Player HD (720p)
            val playerHd = document.selectFirst(PLAYER_HD)
            println("[HypeflixExtractor] Player HD encontrado: ${playerHd != null}")
            playerHd?.let { iframe ->
                val src = iframe.attr("src").takeIf { it.isNotBlank() } ?: return@let
                println("[HypeflixExtractor] Player HD src: $src")
                
                val m3u8Url = extractM3u8FromUrl(src) ?: src
                println("[HypeflixExtractor] Player HD m3u8 final: $m3u8Url")

                callback(
                    newExtractorLink(
                        "Hypeflix",
                        "Player HD",
                        m3u8Url,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$referer/"
                        quality = 720
                    }
                )
                println("[HypeflixExtractor] Link HD adicionado com sucesso!")
                linksFound = true
            }

            // Player SD (480p/360p)
            val playerSd = document.selectFirst(PLAYER_SD)
            println("[HypeflixExtractor] Player SD encontrado: ${playerSd != null}")
            playerSd?.let { iframe ->
                val src = iframe.attr("src").takeIf { it.isNotBlank() } ?: return@let
                println("[HypeflixExtractor] Player SD src: $src")
                
                val m3u8Url = extractM3u8FromUrl(src) ?: src
                val quality = if (src.contains("480p", true)) 480 else 360
                println("[HypeflixExtractor] Player SD m3u8 final: $m3u8Url (qualidade: $quality)")

                callback(
                    newExtractorLink(
                        "Hypeflix",
                        "Player SD",
                        m3u8Url,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$referer/"
                        this.quality = quality
                    }
                )
                println("[HypeflixExtractor] Link SD adicionado com sucesso!")
                linksFound = true
            }

            // Player Backup (qualidade desconhecida)
            val playerBackup = document.selectFirst(PLAYER_BACKUP)
            println("[HypeflixExtractor] Player Backup encontrado: ${playerBackup != null}")
            playerBackup?.let { iframe ->
                val src = iframe.attr("src").takeIf { it.isNotBlank() } ?: return@let
                println("[HypeflixExtractor] Player Backup src: $src")
                
                val isM3u8 = src.contains("m3u8", true)
                val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                println("[HypeflixExtractor] Player Backup type: $linkType")

                callback(
                    newExtractorLink(
                        "Hypeflix",
                        "Player Backup",
                        src,
                        linkType
                    ) {
                        this.referer = "$referer/"
                        quality = 720
                    }
                )
                println("[HypeflixExtractor] Link Backup adicionado com sucesso!")
                linksFound = true
            }

            // Verificar todos os iframes restantes
            println("[HypeflixExtractor] Verificando iframes restantes...")
            val alreadyChecked = listOf(
                playerFhd?.attr("src"),
                playerHd?.attr("src"),
                playerSd?.attr("src"),
                playerBackup?.attr("src")
            )
            
            document.select("iframe").forEachIndexed { index, iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank() && src.contains("m3u8", true)) {
                    val alreadyAdded = alreadyChecked.any { it == src }
                    
                    if (!alreadyAdded) {
                        println("[HypeflixExtractor] Iframe $index não processado anteriormente - src: $src")
                        val m3u8Url = extractM3u8FromUrl(src) ?: src

                        callback(
                            newExtractorLink(
                                "Hypeflix",
                                "Player ${index + 1}",
                                m3u8Url,
                                ExtractorLinkType.M3U8
                            ) {
                                this.referer = "$referer/"
                                quality = 720
                            }
                        )
                        println("[HypeflixExtractor] Link do iframe $index adicionado!")
                        linksFound = true
                    }
                }
            }

            // Se não encontrou iframes, procurar em scripts
            if (!linksFound) {
                println("[HypeflixExtractor] Nenhum iframe encontrado, procurando em scripts...")
                document.select("script").forEachIndexed { index, script ->
                    val scriptContent = script.html()
                    if (scriptContent.contains("m3u8")) {
                        println("[HypeflixExtractor] Script $index contém 'm3u8'")
                        val m3u8Pattern = Regex("""(https?://[^\s"']*\.m3u8[^\s"']*)""")
                        val m3u8Matches = m3u8Pattern.findAll(scriptContent)
                        
                        println("[HypeflixExtractor] Encontrados ${m3u8Matches.count()} matches de m3u8")
                        m3u8Matches.forEach { match ->
                            val m3u8Url = match.value
                            if (m3u8Url.isNotBlank()) {
                                println("[HypeflixExtractor] M3U8 encontrado em script: $m3u8Url")
                                callback(
                                    newExtractorLink(
                                        "Hypeflix",
                                        "Script Player",
                                        m3u8Url,
                                        ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = "$referer/"
                                        quality = 720
                                    }
                                )
                                println("[HypeflixExtractor] Link do script adicionado!")
                                linksFound = true
                            }
                        }
                    }
                }
            }

            // Tentar extrair de players alternativos
            if (!linksFound) {
                println("[HypeflixExtractor] Procurando em players alternativos...")
                document.select("div.player-container script").forEachIndexed { index, script ->
                    println("[HypeflixExtractor] Player container script $index encontrado")
                    val content = script.html()
                    val result = extractAllVideoLinks(content, referer, callback)
                    if (result) {
                        println("[HypeflixExtractor] Links encontrados em player container!")
                        linksFound = true
                    }
                }
            }

            // Última tentativa: buscar em todo o HTML
            if (!linksFound) {
                println("[HypeflixExtractor] Última tentativa: buscar em todo HTML...")
                val html = document.html()
                val allM3u8Pattern = Regex("""https?://[^\s"']*\.m3u8[^\s"']*""")
                val allMatches = allM3u8Pattern.findAll(html).toList()
                
                println("[HypeflixExtractor] Total de matches m3u8 no HTML: ${allMatches.size}")
                allMatches.forEachIndexed { index, match ->
                    val url = match.value
                    println("[HypeflixExtractor] M3U8 encontrado #$index: $url")
                    
                    // Verificar se é uma URL válida
                    if (url.isNotBlank() && (url.contains("1080p") || url.contains("720p") || url.contains("480p") || url.contains("360p"))) {
                        val quality = getQualityFromUrl(url)
                        callback(
                            newExtractorLink(
                                "Hypeflix",
                                "Auto-detected $quality",
                                url,
                                ExtractorLinkType.M3U8
                            ) {
                                this.referer = referer
                                this.quality = quality
                            }
                        )
                        println("[HypeflixExtractor] Link auto-detectado adicionado!")
                        linksFound = true
                    }
                }
            }

            println("[HypeflixExtractor] === FINALIZANDO EXTRACTION ===")
            println("[HypeflixExtractor] Links encontrados: $linksFound")
            
            return linksFound
            
        } catch (e: Exception) {
            println("[HypeflixExtractor] ERRO durante extração: ${e.message}")
            println("[HypeflixExtractor] Stack trace:")
            e.printStackTrace()
            return false
        }
    }

    private suspend fun extractM3u8FromUrl(url: String): String? {
        println("[HypeflixExtractor] extractM3u8FromUrl chamado para: $url")
        
        try {
            // Se a URL já é m3u8, retorna direto
            if (url.contains(".m3u8")) {
                println("[HypeflixExtractor] URL já é m3u8, retornando direto")
                return url
            }
            
            // Se for uma URL de player, tenta extrair o m3u8
            if (url.contains("player") || url.contains("embed") || url.contains("video")) {
                println("[HypeflixExtractor] URL parece ser de player, tentando extrair m3u8...")
                
                val response = app.get(url)
                println("[HypeflixExtractor] Response status: ${response.code}")
                
                val responseText = response.text
                println("[HypeflixExtractor] Response preview (500 chars): ${responseText.take(500)}")
                
                val patterns = listOf(
                    Regex("""(?:file|src|source|url)\s*[:=]\s*['"]([^'"]+\.m3u8[^'"]*)['"]"""),
                    Regex("""(https?://[^"']+\.m3u8[^"']*)"""),
                    Regex("""m3u8.*?(https?://[^\s"']+)"""),
                    Regex(""""url"\s*:\s*"([^"]+\.m3u8[^"]*)"""")
                )
                
                for ((index, pattern) in patterns.withIndex()) {
                    val match = pattern.find(responseText)
                    if (match != null) {
                        val extractedUrl = match.groupValues[1]
                        println("[HypeflixExtractor] Pattern $index encontrou: $extractedUrl")
                        
                        if (extractedUrl.isNotBlank()) {
                            val fixedUrl = fixUrl(extractedUrl)
                            println("[HypeflixExtractor] URL fixada: $fixedUrl")
                            return fixedUrl
                        }
                    }
                }
                
                println("[HypeflixExtractor] Nenhum m3u8 encontrado nos patterns")
            } else {
                println("[HypeflixExtractor] URL não parece ser de player, ignorando")
            }
            
            return null
        } catch (e: Exception) {
            println("[HypeflixExtractor] ERRO em extractM3u8FromUrl: ${e.message}")
            return null
        }
    }

    private suspend fun extractAllVideoLinks(
        content: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[HypeflixExtractor] extractAllVideoLinks chamado")
        println("[HypeflixExtractor] Content preview: ${content.take(200)}")
        
        var found = false
        
        // Buscar m3u8
        val m3u8Pattern = Regex("""(https?://[^\s"']*\.m3u8[^\s"']*)""")
        val m3u8Matches = m3u8Pattern.findAll(content).toList()
        println("[HypeflixExtractor] M3U8 matches encontrados: ${m3u8Matches.size}")
        
        m3u8Matches.forEachIndexed { index, match ->
            val url = match.value
            println("[HypeflixExtractor] M3U8 match $index: $url")
            
            if (url.isNotBlank()) {
                val fixedUrl = fixUrl(url)
                val quality = getQualityFromUrl(url)
                println("[HypeflixExtractor] Adicionando m3u8 - URL: $fixedUrl, Quality: $quality")
                
                callback(
                    newExtractorLink(
                        "Hypeflix",
                        "Extracted M3U8",
                        fixedUrl,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = referer
                        this.quality = quality
                    }
                )
                found = true
            }
        }
        
        // Buscar mp4
        val mp4Pattern = Regex("""(https?://[^\s"']*\.mp4[^\s"']*)""")
        val mp4Matches = mp4Pattern.findAll(content).toList()
        println("[HypeflixExtractor] MP4 matches encontrados: ${mp4Matches.size}")
        
        mp4Matches.forEachIndexed { index, match ->
            val url = match.value
            println("[HypeflixExtractor] MP4 match $index: $url")
            
            if (url.isNotBlank()) {
                val fixedUrl = fixUrl(url)
                val quality = getQualityFromUrl(url)
                println("[HypeflixExtractor] Adicionando mp4 - URL: $fixedUrl, Quality: $quality")
                
                callback(
                    newExtractorLink(
                        "Hypeflix",
                        "Extracted MP4",
                        fixedUrl,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.referer = referer
                        this.quality = quality
                    }
                )
                found = true
            }
        }
        
        println("[HypeflixExtractor] extractAllVideoLinks resultado: $found")
        return found
    }

    private fun getQualityFromUrl(url: String): Int {
        val quality = when {
            url.contains("360p", ignoreCase = true) -> {
                println("[HypeflixExtractor] Qualidade detectada: 360p")
                Qualities.P360.value
            }
            url.contains("480p", ignoreCase = true) -> {
                println("[HypeflixExtractor] Qualidade detectada: 480p")
                Qualities.P480.value
            }
            url.contains("720p", ignoreCase = true) -> {
                println("[HypeflixExtractor] Qualidade detectada: 720p")
                Qualities.P720.value
            }
            url.contains("1080p", ignoreCase = true) -> {
                println("[HypeflixExtractor] Qualidade detectada: 1080p")
                Qualities.P1080.value
            }
            url.contains("2160p", ignoreCase = true) || url.contains("4k", ignoreCase = true) -> {
                println("[HypeflixExtractor] Qualidade detectada: 4K")
                Qualities.P2160.value
            }
            else -> {
                println("[HypeflixExtractor] Qualidade desconhecida, usando 720p")
                Qualities.Unknown.value
            }
        }
        return quality
    }

    private fun fixUrl(url: String): String {
        val fixed = when {
            url.startsWith("//") -> "https:$url"
            !url.startsWith("http") -> "https://$url"
            else -> url
        }
        println("[HypeflixExtractor] fixUrl: $url -> $fixed")
        return fixed
    }
}
