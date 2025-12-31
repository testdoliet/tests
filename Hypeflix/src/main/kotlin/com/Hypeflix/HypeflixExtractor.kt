package com.Hypeflix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app

object HypeFlixExtractor {
    // Constantes para seletores CSS (ajuste conforme necessário)
    private const val PLAYER_FHD = "iframe[src*='m3u8'][src*='1080p']"
    private const val PLAYER_HD = "iframe[src*='m3u8'][src*='720p']"
    private const val PLAYER_SD = "iframe[src*='m3u8'][src*='480p'], iframe[src*='m3u8'][src*='360p']"
    private const val PLAYER_BACKUP = "iframe[src*='m3u8']:not([src*='1080p']):not([src*='720p']):not([src*='480p']):not([src*='360p'])"
    
    suspend fun extractVideoLinks(
        data: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val actualUrl = data.split("|poster=")[0]
        val document = app.get(actualUrl, referer = referer).document

        var linksFound = false

        // Player FHD (1080p)
        document.selectFirst(PLAYER_FHD)?.let { iframe ->
            val src = iframe.attr("src").takeIf { it.isNotBlank() } ?: return@let
            val m3u8Url = extractM3u8FromUrl(src) ?: src

            callback(
                newExtractorLink(
                    "HypeFlix",
                    "Player FHD",
                    m3u8Url,
                    ExtractorLinkType.M3U8
                ) {
                    this.referer = "$referer/"
                    quality = 1080
                }
            )
            linksFound = true
        }

        // Player HD (720p)
        document.selectFirst(PLAYER_HD)?.let { iframe ->
            val src = iframe.attr("src").takeIf { it.isNotBlank() } ?: return@let
            val m3u8Url = extractM3u8FromUrl(src) ?: src

            callback(
                newExtractorLink(
                    "HypeFlix",
                    "Player HD",
                    m3u8Url,
                    ExtractorLinkType.M3U8
                ) {
                    this.referer = "$referer/"
                    quality = 720
                }
            )
            linksFound = true
        }

        // Player SD (480p/360p)
        document.selectFirst(PLAYER_SD)?.let { iframe ->
            val src = iframe.attr("src").takeIf { it.isNotBlank() } ?: return@let
            val m3u8Url = extractM3u8FromUrl(src) ?: src
            val quality = if (src.contains("480p", true)) 480 else 360

            callback(
                newExtractorLink(
                    "HypeFlix",
                    "Player SD",
                    m3u8Url,
                    ExtractorLinkType.M3U8
                ) {
                    this.referer = "$referer/"
                    this.quality = quality
                }
            )
            linksFound = true
        }

        // Player Backup (qualidade desconhecida)
        document.selectFirst(PLAYER_BACKUP)?.let { iframe ->
            val src = iframe.attr("src").takeIf { it.isNotBlank() } ?: return@let
            val isM3u8 = src.contains("m3u8", true)
            val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

            callback(
                newExtractorLink(
                    "HypeFlix",
                    "Player Backup",
                    src,
                    linkType
                ) {
                    this.referer = "$referer/"
                    quality = 720
                }
            )
            linksFound = true
        }

        // Verificar todos os iframes restantes
        document.select("iframe").forEachIndexed { index, iframe ->
            val src = iframe.attr("src")
            if (src.contains("m3u8", true)) {
                // Verificar se já foi adicionado pelos seletores acima
                val alreadyAdded = listOf(
                    document.selectFirst(PLAYER_FHD)?.attr("src"),
                    document.selectFirst(PLAYER_HD)?.attr("src"),
                    document.selectFirst(PLAYER_SD)?.attr("src"),
                    document.selectFirst(PLAYER_BACKUP)?.attr("src")
                ).any { it == src }

                if (!alreadyAdded) {
                    val m3u8Url = extractM3u8FromUrl(src) ?: src

                    callback(
                        newExtractorLink(
                            "HypeFlix",
                            "Player ${index + 1}",
                            m3u8Url,
                            ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$referer/"
                            quality = 720
                        }
                    )
                    linksFound = true
                }
            }
        }

        // Se não encontrou iframes com m3u8, procurar em scripts
        if (!linksFound) {
            document.select("script").forEach { script ->
                val scriptContent = script.html()
                if (scriptContent.contains("m3u8")) {
                    // Extrair URL m3u8 do script
                    val m3u8Pattern = Regex("""(https?://[^\s"']*\.m3u8[^\s"']*)""")
                    val m3u8Matches = m3u8Pattern.findAll(scriptContent)
                    
                    m3u8Matches.forEach { match ->
                        val m3u8Url = match.value
                        if (m3u8Url.isNotBlank()) {
                            callback(
                                newExtractorLink(
                                    "HypeFlix",
                                    "Script Player",
                                    m3u8Url,
                                    ExtractorLinkType.M3U8
                                ) {
                                    this.referer = "$referer/"
                                    quality = 720
                                }
                            )
                            linksFound = true
                        }
                    }
                }
            }
        }

        return linksFound
    }

    private fun extractM3u8FromUrl(url: String): String? {
        return try {
            // Se a URL já é m3u8, retorna direto
            if (url.contains(".m3u8")) {
                return url
            }
            
            // Se for uma URL de player, tenta extrair o m3u8
            if (url.contains("player") || url.contains("embed")) {
                // Padrões comuns de extração
                val patterns = listOf(
                    Regex("""(?:file|src|source)\s*[:=]\s*['"]([^'"]+\.m3u8[^'"]*)['"]"""),
                    Regex("""(https?://[^"']+\.m3u8[^"']*)"""),
                    Regex("""m3u8.*?(https?://[^\s"']+)""")
                )
                
                // Fazer requisição para extrair
                val response = app.get(url).text
                
                for (pattern in patterns) {
                    val match = pattern.find(response)
                    if (match != null) {
                        val extractedUrl = match.groupValues[1]
                        if (extractedUrl.isNotBlank()) {
                            return extractedUrl
                        }
                    }
                }
            }
            
            null
        } catch (e: Exception) {
            null
        }
    }
}
