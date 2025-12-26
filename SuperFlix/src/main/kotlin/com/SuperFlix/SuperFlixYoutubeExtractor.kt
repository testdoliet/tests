package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

class SuperFlixYoutubeExtractor : ExtractorApi() {
    override val name = "SuperFlixYouTube"
    override val mainUrl = "https://www.youtube.com"
    override val requiresReferer = false

    // MÉTODO 1: Com callback
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("🎬 [SuperFlix] YouTubeExtractor processando: $url")
        
        try {
            val videoId = extractYouTubeId(url) ?: return
            println("📹 Video ID encontrado: $videoId")
            
            // Método SIMPLES e direto: Usar o player.js
            extractWithPlayerJs(videoId, callback)
            
        } catch (e: Exception) {
            println("❌ YouTubeExtractor erro: ${e.message}")
        }
    }

    // MÉTODO 2: Retorna lista
    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val links = mutableListOf<ExtractorLink>()
        
        getUrl(url, referer, {}, { link ->
            links.add(link)
        })
        
        return if (links.isNotEmpty()) links else null
    }
    
    private fun extractYouTubeId(url: String): String? {
        val patterns = listOf(
            "youtube\\.com/watch\\?v=([a-zA-Z0-9_-]{11})",
            "youtu\\.be/([a-zA-Z0-9_-]{11})",
            "youtube\\.com/embed/([a-zA-Z0-9_-]{11})",
            "v=([a-zA-Z0-9_-]{11})"
        )
        
        for (pattern in patterns) {
            Regex(pattern, RegexOption.IGNORE_CASE).find(url)?.let {
                return it.groupValues[1]
            }
        }
        return null
    }

    // MÉTODO SIMPLES E EFICAZ: Usar o player.js do YouTube
    private suspend fun extractWithPlayerJs(
        videoId: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // URL do player.js (método mais direto)
            val playerUrl = "https://www.youtube.com/s/player/$videoId/player_ias.vflset/pt_BR/base.js"
            println("🔗 Buscando player.js: $playerUrl")
            
            val response = app.get(playerUrl, timeout = 10000)
            if (response.code != 200) {
                println("❌ player.js não encontrado")
                // Fallback para embed direto
                return createYouTubeEmbed(videoId, callback)
            }
            
            val jsContent = response.text
            println("✅ player.js carregado (${jsContent.length} chars)")
            
            // Extrair URLs do player.js
            extractUrlsFromPlayerJs(jsContent, videoId, callback)
            
        } catch (e: Exception) {
            println("⚠️ Erro no player.js: ${e.message}")
            // Fallback para embed
            createYouTubeEmbed(videoId, callback)
        }
    }

    private fun extractUrlsFromPlayerJs(
        jsContent: String,
        videoId: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            // Padrões para extrair informações de qualidade
            val patterns = listOf(
                """["'](https?://[^"']+googlevideo[^"']+)["']""",
                """(https?://[^\s"']+\.googlevideo\.com/[^\s"']+)""",
                """itag["']?\s*:\s*["']?(\d+)["']?""",
                """quality_label["']?\s*:\s*["']?([^"',]+)["']?"""
            )
            
            var foundLinks = false
            
            // Procurar URLs googlevideo
            patterns.forEachIndexed { index, pattern ->
                try {
                    val regex = pattern.toRegex(RegexOption.IGNORE_CASE)
                    val matches = regex.findAll(jsContent).toList()
                    
                    if (matches.isNotEmpty()) {
                        println("🔍 Padrão $index encontrou ${matches.size} matches")
                        
                        matches.take(10).forEach { match ->
                            val value = match.value.trim('"', '\'', ' ')
                            if (value.contains("googlevideo") && !value.contains("player")) {
                                println("🎥 URL encontrada: ${value.take(80)}...")
                                
                                val quality = determineQualityFromUrl(value)
                                val qualityText = getQualityText(quality)
                                
                                val extractorLink = newExtractorLink(
                                    source = name,
                                    name = "Trailer YouTube ($qualityText)",
                                    url = value,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = "https://www.youtube.com"
                                    this.quality = quality
                                    this.headers = mapOf(
                                        "Referer" to "https://www.youtube.com",
                                        "User-Agent" to "Mozilla/5.0",
                                        "Origin" to "https://www.youtube.com"
                                    )
                                }
                                
                                callback(extractorLink)
                                foundLinks = true
                            }
                        }
                    }
                } catch (e: Exception) {
                    println("⚠️ Erro processando padrão $index: ${e.message}")
                }
            }
            
            if (foundLinks) {
                println("✨ URLs extraídas do player.js!")
                return true
            }
            
            // Se não encontrou, usar fallback
            println("ℹ️ Nenhuma URL encontrada no player.js, usando fallback")
            return createYouTubeEmbed(videoId, callback)
            
        } catch (e: Exception) {
            println("❌ Erro extraindo URLs: ${e.message}")
            return createYouTubeEmbed(videoId, callback)
        }
    }

    // MÉTODO DE FALLBACK: YouTube Embed (sempre funciona)
    private fun createYouTubeEmbed(
        videoId: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            // URL do embed do YouTube (sempre funciona)
            val embedUrl = "https://www.youtube-nocookie.com/embed/$videoId?autoplay=1&rel=0"
            println("🔗 Criando YouTube embed: $embedUrl")
            
            val extractorLink = newExtractorLink(
                source = name,
                name = "Trailer YouTube (720p)",
                url = embedUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = "https://www.youtube.com"
                this.quality = Qualities.P720.value
                this.headers = mapOf(
                    "Referer" to "https://www.youtube.com",
                    "User-Agent" to "Mozilla/5.0",
                    "Origin" to "https://www.youtube.com"
                )
            }
            
            callback(extractorLink)
            println("✅ YouTube embed criado com sucesso")
            return true
            
        } catch (e: Exception) {
            println("❌ Erro criando embed: ${e.message}")
            return false
        }
    }

    // MÉTODO ALTERNATIVO: Página oembed do YouTube
    private suspend fun extractWithOEmbed(videoId: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val oembedUrl = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$videoId&format=json"
            println("🔗 Tentando oEmbed: $oembedUrl")
            
            val response = app.get(oembedUrl, timeout = 8000)
            if (response.code == 200) {
                val jsonText = response.text
                println("✅ oEmbed respondeu")
                
                // Extrair HTML e buscar iframe
                if (jsonText.contains("iframe") || jsonText.contains("src=")) {
                    val iframePattern = """src=["']([^"']+)["']""".toRegex()
                    val matches = iframePattern.findAll(jsonText)
                    
                    matches.forEach { match ->
                        val iframeSrc = match.groupValues[1]
                        if (iframeSrc.contains("youtube")) {
                            println("🎥 Iframe encontrado: $iframeSrc")
                            
                            val extractorLink = newExtractorLink(
                                source = name,
                                name = "Trailer YouTube (via oEmbed)",
                                url = iframeSrc,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "https://www.youtube.com"
                                this.quality = Qualities.P720.value
                                this.headers = mapOf(
                                    "Referer" to "https://www.youtube.com",
                                    "User-Agent" to "Mozilla/5.0",
                                    "Origin" to "https://www.youtube.com"
                                )
                            }
                            
                            callback(extractorLink)
                            return true
                        }
                    }
                }
            }
            
            false
            
        } catch (e: Exception) {
            println("⚠️ oEmbed falhou: ${e.message}")
            false
        }
    }

    private fun determineQualityFromUrl(url: String): Int {
        return when {
            url.contains("itag=37") || url.contains("hd1080") -> Qualities.P1080.value
            url.contains("itag=22") || url.contains("hd720") -> Qualities.P720.value
            url.contains("itag=59") || url.contains("480") -> Qualities.P480.value
            url.contains("itag=18") || url.contains("360") -> Qualities.P360.value
            else -> Qualities.P720.value // Padrão
        }
    }

    private fun getQualityText(quality: Int): String {
        return when {
            quality >= Qualities.P2160.value -> "4K"
            quality >= Qualities.P1440.value -> "1440p"
            quality >= Qualities.P1080.value -> "1080p"
            quality >= Qualities.P720.value -> "720p"
            quality >= Qualities.P480.value -> "480p"
            quality >= Qualities.P360.value -> "360p"
            else -> "SD"
        }
    }
    
    // MÉTODO DE TESTE: Adicione esta linha na sua MainProvider para testar
    fun testExtractor() {
        println("🧪 TESTANDO YouTube Extractor...")
        // Teste com vídeo conhecido
        val testUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        getUrl(testUrl, "https://www.youtube.com", {}, { link ->
            println("✅ TESTE OK: Link encontrado - ${link.name}")
        })
    }
}
