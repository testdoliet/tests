package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

class SuperFlixYoutubeExtractor : ExtractorApi() {
    override val name = "SuperFlixYouTube"
    override val mainUrl = "https://www.youtube.com"
    override val requiresReferer = false

    // MÉTODO 1: Com callback (SUSPEND)
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
            
            // Método 1: YouTube embed (sempre funciona)
            if (createYouTubeEmbed(videoId, callback)) {
                return
            }
            
            // Método 2: API simples
            extractWithSimpleApi(videoId, callback)
            
        } catch (e: Exception) {
            println("❌ YouTubeExtractor erro: ${e.message}")
        }
    }

    // MÉTODO 2: Retorna lista (SUSPEND)
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

    // MÉTODO SUSPEND: YouTube Embed
    private suspend fun createYouTubeEmbed(
        videoId: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // URL do embed do YouTube
            val embedUrl = "https://www.youtube-nocookie.com/embed/$videoId?autoplay=1&rel=0"
            println("🔗 Criando YouTube embed: $embedUrl")
            
            // USANDO newExtractorLink (dentro de função suspend)
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
            true
            
        } catch (e: Exception) {
            println("❌ Erro criando embed: ${e.message}")
            false
        }
    }

    // MÉTODO SUSPEND: API simples
    private suspend fun extractWithSimpleApi(
        videoId: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // Tentar API do ytdl
            val apiUrl = "https://yt.lemnoslife.com/noKey/videos?part=streamingDetails&id=$videoId"
            println("🔗 Tentando API: $apiUrl")
            
            val response = app.get(apiUrl, timeout = 10000)
            if (response.code == 200) {
                val jsonText = response.text
                
                // Procurar URLs de vídeo
                val videoPattern = """https?://[^"\s]+googlevideo\.com/videoplayback[^"\s]*""".toRegex()
                val matches = videoPattern.findAll(jsonText).toList()
                
                if (matches.isNotEmpty()) {
                    matches.take(3).forEach { match ->
                        val videoUrl = match.value
                        println("✅ URL encontrada: ${videoUrl.take(60)}...")
                        
                        // USANDO newExtractorLink (dentro de função suspend)
                        val extractorLink = newExtractorLink(
                            source = name,
                            name = "Trailer YouTube (Qualidade Máxima)",
                            url = videoUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "https://www.youtube.com"
                            this.quality = Qualities.P1080.value
                            this.headers = mapOf(
                                "Referer" to "https://www.youtube.com",
                                "User-Agent" to "Mozilla/5.0",
                                "Origin" to "https://www.youtube.com"
                            )
                        }
                        
                        callback(extractorLink)
                    }
                    return true
                }
            }
            
            // Se API falhar, criar fallback simples
            return createSimpleFallback(videoId, callback)
            
        } catch (e: Exception) {
            println("❌ API falhou: ${e.message}")
            return createSimpleFallback(videoId, callback)
        }
    }

    // MÉTODO SUSPEND: Fallback simples
    private suspend fun createSimpleFallback(
        videoId: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // URL direta do vídeo
            val directUrl = "https://www.youtube.com/watch?v=$videoId"
            
            // USANDO newExtractorLink (dentro de função suspend)
            val extractorLink = newExtractorLink(
                source = name,
                name = "Trailer YouTube (WebView)",
                url = directUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = "https://www.youtube.com"
                this.quality = Qualities.P720.value
                this.headers = mapOf(
                    "Referer" to "https://www.youtube.com",
                    "User-Agent" to "Mozilla/5.0 Chrome/91.0.4472.124 Safari/537.36",
                    "Origin" to "https://www.youtube.com"
                )
            }
            
            callback(extractorLink)
            println("✅ Fallback criado")
            true
            
        } catch (e: Exception) {
            println("❌ Fallback falhou: ${e.message}")
            false
        }
    }
    
    // Função auxiliar para múltiplas qualidades
    private suspend fun createQualityLink(
        videoUrl: String,
        quality: Int,
        qualityName: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // USANDO newExtractorLink (dentro de função suspend)
            val extractorLink = newExtractorLink(
                source = name,
                name = "Trailer YouTube ($qualityName)",
                url = videoUrl,
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
            println("✅ Link $qualityName criado")
            
        } catch (e: Exception) {
            println("⚠️ Erro criando link $qualityName: ${e.message}")
        }
    }
}
