package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app

class SuperFlixExtractor : Extractor() {
    override val name = "SuperFlix"
    override val mainUrl = "https://superflix21.lol"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🔍 SuperFlixExtractor processando URL: $url")
            
            val response = app.get(url, referer = referer)
            val document = response.document
            
            // Método 1: Procurar por iframes
            val iframeLinks = extractIframeLinks(document, referer ?: mainUrl, callback)
            if (iframeLinks) {
                println("✅ SuperFlixExtractor: Links encontrados via iframe")
                return true
            }
            
            // Método 2: Procurar por players embutidos
            val embedLinks = extractEmbedLinks(document, referer ?: mainUrl, callback)
            if (embedLinks) {
                println("✅ SuperFlixExtractor: Links encontrados via embed")
                return true
            }
            
            // Método 3: Procurar por scripts com URLs de vídeo
            val scriptLinks = extractScriptLinks(document, referer ?: mainUrl, callback)
            if (scriptLinks) {
                println("✅ SuperFlixExtractor: Links encontrados via scripts")
                return true
            }
            
            // Método 4: Procurar por links diretos
            val directLinks = extractDirectLinks(document, referer ?: mainUrl, callback)
            if (directLinks) {
                println("✅ SuperFlixExtractor: Links encontrados via links diretos")
                return true
            }
            
            println("❌ SuperFlixExtractor: Nenhum link encontrado")
            false
            
        } catch (e: Exception) {
            println("❌ SuperFlixExtractor erro: ${e.message}")
            false
        }
    }

    private suspend fun extractIframeLinks(
        document: org.jsoup.nodes.Document,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        
        val iframes = document.select("iframe[src]")
        for (iframe in iframes) {
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                val fixedSrc = fixUrl(src)
                println("📺 Encontrado iframe: $fixedSrc")
                
                // Verificar se é um player conhecido
                when {
                    src.contains("fembed") -> {
                        val extractorLink = newExtractorLink(
                            source = "Fembed",
                            name = "Fembed Player",
                            url = fixedSrc,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = referer
                            this.quality = Qualities.P720.value
                        }
                        callback(extractorLink)
                        found = true
                    }
                    src.contains("filemoon") -> {
                        val extractorLink = newExtractorLink(
                            source = "Filemoon",
                            name = "Filemoon Player",
                            url = fixedSrc,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = referer
                            this.quality = Qualities.P720.value
                        }
                        callback(extractorLink)
                        found = true
                    }
                    src.contains("player") || src.contains("embed") -> {
                        val extractorLink = newExtractorLink(
                            source = "Embed Player",
                            name = "Player",
                            url = fixedSrc,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = referer
                            this.quality = Qualities.P720.value
                        }
                        callback(extractorLink)
                        found = true
                    }
                }
            }
        }
        
        return found
    }

    private suspend fun extractEmbedLinks(
        document: org.jsoup.nodes.Document,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        
        // Procurar por players embutidos
        val videoPlayers = document.select("""
            video[src],
            video source[src],
            .video-player[data-url],
            .player[data-src],
            [class*='player'][data-url],
            [class*='video'][data-src]
        """.trimIndent())
        
        for (player in videoPlayers) {
            val videoUrl = player.attr("src") ?: 
                          player.attr("data-url") ?: 
                          player.attr("data-src")
            
            if (videoUrl.isNotBlank()) {
                val fixedUrl = fixUrl(videoUrl)
                println("🎥 Encontrado vídeo: $fixedUrl")
                
                // Extrair qualidade se disponível
                val quality = extractQualityFromElement(player)
                
                val extractorLink = newExtractorLink(
                    source = "SuperFlix Direct",
                    name = "Vídeo (${quality}p)",
                    url = fixedUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = when (quality) {
                        1080 -> Qualities.P1080.value
                        720 -> Qualities.P720.value
                        480 -> Qualities.P480.value
                        360 -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    }
                }
                callback(extractorLink)
                found = true
            }
        }
        
        return found
    }

    private suspend fun extractScriptLinks(
        document: org.jsoup.nodes.Document,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        
        val scripts = document.select("script")
        val videoPatterns = listOf(
            Regex("""src\s*[:=]\s*['"]([^'"]*\.m3u8[^'"]*)['"]"""),
            Regex("""file\s*[:=]\s*['"]([^'"]*\.mp4[^'"]*)['"]"""),
            Regex("""url\s*[:=]\s*['"]([^'"]*\.m3u8[^'"]*)['"]"""),
            Regex("""(https?://[^'"]*\.m3u8[^'"]*)"""),
            Regex("""(https?://[^'"]*\.mp4[^'"]*)""")
        )
        
        for (script in scripts) {
            val scriptContent = script.html()
            
            for (pattern in videoPatterns) {
                val matches = pattern.findAll(scriptContent)
                matches.forEach { match ->
                    val videoUrl = match.groupValues[1]
                    if (videoUrl.isNotBlank() && !videoUrl.contains("google") && !videoUrl.contains("analytics")) {
                        val fixedUrl = fixUrl(videoUrl)
                        println("📜 Encontrado vídeo em script: $fixedUrl")
                        
                        // Determinar qualidade pela URL
                        val quality = when {
                            fixedUrl.contains("1080") -> 1080
                            fixedUrl.contains("720") -> 720
                            fixedUrl.contains("480") -> 480
                            fixedUrl.contains("360") -> 360
                            else -> 720
                        }
                        
                        val isM3u8 = fixedUrl.contains(".m3u8")
                        
                        val extractorLink = newExtractorLink(
                            source = "SuperFlix Script",
                            name = "Vídeo (${quality}p)${if (isM3u8) " [HLS]" else ""}",
                            url = fixedUrl,
                            type = if (isM3u8) ExtractorLinkType.HLS else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = referer
                            this.quality = when (quality) {
                                1080 -> Qualities.P1080.value
                                720 -> Qualities.P720.value
                                480 -> Qualities.P480.value
                                360 -> Qualities.P360.value
                                else -> Qualities.Unknown.value
                            }
                        }
                        callback(extractorLink)
                        found = true
                    }
                }
            }
        }
        
        return found
    }

    private suspend fun extractDirectLinks(
        document: org.jsoup.nodes.Document,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        
        // Procurar por links diretos
        val directLinks = document.select("""
            a[href*='.m3u8'],
            a[href*='.mp4'],
            a[href*='videoplayback'],
            [data-video-src],
            [data-file]
        """.trimIndent())
        
        for (link in directLinks) {
            val videoUrl = link.attr("href") ?: 
                          link.attr("data-video-src") ?: 
                          link.attr("data-file")
            
            if (videoUrl.isNotBlank()) {
                val fixedUrl = fixUrl(videoUrl)
                println("🔗 Encontrado link direto: $fixedUrl")
                
                // Determinar qualidade
                val quality = when {
                    fixedUrl.contains("1080") -> 1080
                    fixedUrl.contains("720") -> 720
                    fixedUrl.contains("480") -> 480
                    fixedUrl.contains("360") -> 360
                    else -> 720
                }
                
                val isM3u8 = fixedUrl.contains(".m3u8")
                
                val extractorLink = newExtractorLink(
                    source = "SuperFlix Direct Link",
                    name = "Vídeo (${quality}p)${if (isM3u8) " [HLS]" else ""}",
                    url = fixedUrl,
                    type = if (isM3u8) ExtractorLinkType.HLS else ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = when (quality) {
                        1080 -> Qualities.P1080.value
                        720 -> Qualities.P720.value
                        480 -> Qualities.P480.value
                        360 -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    }
                }
                callback(extractorLink)
                found = true
            }
        }
        
        return found
    }

    private fun extractQualityFromElement(element: org.jsoup.nodes.Element): Int {
        // Tentar extrair qualidade do elemento
        val qualityText = element.attr("data-quality") ?: 
                         element.attr("quality") ?: 
                         element.attr("res") ?: 
                         element.attr("data-res")
        
        if (qualityText.isNotBlank()) {
            val qualityMatch = Regex("""(\d+)""").find(qualityText)
            qualityMatch?.let {
                return it.groupValues[1].toIntOrNull() ?: 720
            }
        }
        
        // Tentar extrair do texto do elemento
        val textQuality = Regex("""(\d+)p""", RegexOption.IGNORE_CASE).find(element.text())
        textQuality?.let {
            return it.groupValues[1].toIntOrNull() ?: 720
        }
        
        return 720
    }

    private fun fixUrl(url: String): String {
        return if (url.startsWith("http")) {
            url
        } else if (url.startsWith("//")) {
            "https:$url"
        } else if (url.startsWith("/")) {
            "https://superflix21.lol$url"
        } else {
            "https://superflix21.lol/$url"
        }
    }
    
    // Função companion para compatibilidade com código antigo
    companion object {
        suspend fun extractVideoLinks(
            url: String,
            mainUrl: String,
            sourceName: String,
            callback: (ExtractorLink) -> Unit
        ): Boolean {
            return SuperFlixExtractor().getUrl(url, mainUrl, {}, callback)
        }
    }
}
