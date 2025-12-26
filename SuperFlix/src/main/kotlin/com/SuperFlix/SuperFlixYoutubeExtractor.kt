package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

class SuperFlixYoutubeExtractor : ExtractorApi() {
    override val name = "SuperFlixYouTube"
    override val mainUrl = "https://www.youtube.com"
    override val requiresReferer = false

    // Mapa de qualidade itag -> qualidade
    private val itagQualityMap = mapOf(
        // MP4 (áudio + vídeo)
        18 to Qualities.P360.value,   // MP4 360p
        22 to Qualities.P720.value,   // MP4 720p
        37 to Qualities.P1080.value,  // MP4 1080p
        38 to Qualities.P2160.value,  // MP4 4K
        
        // WebM (áudio + vídeo)
        43 to Qualities.P360.value,   // WebM 360p
        44 to Qualities.P480.value,   // WebM 480p
        45 to Qualities.P720.value,   // WebM 720p
        46 to Qualities.P1080.value,  // WebM 1080p
        
        // Vídeo apenas
        137 to Qualities.P1080.value, // MP4 1080p (video only)
        248 to Qualities.P1080.value, // WebM 1080p (video only)
        271 to Qualities.P1440.value, // WebM 1440p (video only)
        313 to Qualities.P2160.value, // WebM 4K (video only)
        315 to Qualities.P2160.value, // WebM 4K60 (video only)
    )

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
            
            // Método 1: Usar API pública do YouTube (funciona melhor)
            if (extractWithYouTubeApi(videoId, callback)) {
                return
            }
            
            // Método 2: Fallback simples
            extractWithSimpleMethod(videoId, callback)
            
        } catch (e: Exception) {
            println("❌ YouTubeExtractor erro: ${e.message}")
        }
    }

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

    // Método principal: Usar API do YouTube para extrair links diretos
    private suspend fun extractWithYouTubeApi(
        videoId: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // API que retorna informações do vídeo
            val apiUrl = "https://www.youtube.com/watch?v=$videoId&pbj=1"
            println("🔗 Buscando dados do vídeo: $apiUrl")
            
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Accept" to "*/*",
                "Accept-Language" to "en-US,en;q=0.9",
                "X-YouTube-Client-Name" to "1",
                "X-YouTube-Client-Version" to "2.20241220.00.00",
                "Origin" to "https://www.youtube.com",
                "Referer" to "https://www.youtube.com"
            )
            
            val response = app.get(apiUrl, headers = headers, timeout = 15000)
            
            if (response.code == 200) {
                val content = response.text
                println("✅ Dados recebidos (${content.length} chars)")
                
                // Extrair URLs de vídeo do conteúdo
                extractVideoUrlsFromContent(content, videoId, callback)
                return true
            } else {
                println("❌ API retornou status ${response.code}")
                return false
            }
            
        } catch (e: Exception) {
            println("❌ Erro na API: ${e.message}")
            return false
        }
    }

    private suspend fun extractVideoUrlsFromContent(
        content: String,
        videoId: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Padrões para encontrar URLs de vídeo
            val patterns = listOf(
                """(https?://[^"\s]+googlevideo\.com/videoplayback[^"\s]+)""",
                """(https?://[^"\s]+\.googlevideo\.com/videoplayback[^"\s]+)""",
                """(https?://[^"\s]+\.googlevideo\.com/v/videoplayback[^"\s]+)""",
                """url_encoded_fmt_stream_map["']?\s*:\s*["']([^"']+)["']""",
                """adaptive_fmts["']?\s*:\s*["']([^"']+)["']"""
            )
            
            var foundLinks = false
            
            for (pattern in patterns) {
                try {
                    val regex = pattern.toRegex(RegexOption.IGNORE_CASE)
                    val matches = regex.findAll(content)
                    
                    matches.forEach { match ->
                        val urlOrData = match.groupValues[1]
                        
                        // Se for uma URL direta
                        if (urlOrData.startsWith("http")) {
                            processVideoUrl(urlOrData, callback)
                            foundLinks = true
                        }
                        // Se for dados codificados (url_encoded_fmt_stream_map)
                        else if (urlOrData.contains("%") || urlOrData.contains("itag=")) {
                            extractFromEncodedData(urlOrData, callback)
                            foundLinks = true
                        }
                    }
                } catch (e: Exception) {
                    // Continuar com próximo padrão
                }
            }
            
            if (foundLinks) {
                println("✨ URLs extraídas com sucesso!")
            } else {
                println("⚠️ Nenhuma URL encontrada, usando fallback")
                // Fallback para link direto
                createDirectVideoLink(videoId, callback)
            }
            
        } catch (e: Exception) {
            println("❌ Erro extraindo URLs: ${e.message}")
            createDirectVideoLink(videoId, callback)
        }
    }

    private suspend fun extractFromEncodedData(
        encodedData: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Decodificar dados URL encoded
            val decoded = java.net.URLDecoder.decode(encodedData, "UTF-8")
            
            // Dividir em streams individuais
            val streams = decoded.split("&url=")
            
            streams.forEach { stream ->
                if (stream.contains("itag=") && stream.contains("googlevideo")) {
                    val urlStart = stream.indexOf("http")
                    if (urlStart >= 0) {
                        val url = stream.substring(urlStart).split("&")[0]
                        processVideoUrl(url, callback)
                    }
                }
            }
        } catch (e: Exception) {
            println("❌ Erro decodificando dados: ${e.message}")
        }
    }

    private suspend fun processVideoUrl(
        videoUrl: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Extrair itag da URL para determinar qualidade
            val itagPattern = """itag=(\d+)""".toRegex()
            val itagMatch = itagPattern.find(videoUrl)
            val itag = itagMatch?.groupValues?.get(1)?.toIntOrNull() ?: 18
            
            val quality = itagQualityMap[itag] ?: Qualities.P720.value
            val qualityText = getQualityText(quality)
            
            println("🎥 Processando URL: itag=$itag ($qualityText)")
            
            // Criar link usando newExtractorLink (dentro de suspend)
            val extractorLink = newExtractorLink(
                source = name,
                name = "Trailer YouTube ($qualityText)",
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
            
        } catch (e: Exception) {
            println("⚠️ Erro processando URL: ${e.message}")
        }
    }

    // Método de fallback: Link direto do YouTube
    private suspend fun createDirectVideoLink(
        videoId: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // URL direta para o vídeo (o ExoPlayer pode lidar com isso)
            val directUrl = "https://www.youtube.com/watch?v=$videoId"
            
            println("🔗 Criando link direto: $directUrl")
            
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
                    "User-Agent" to "Mozilla/5.0 Chrome/91.0.4472.124 Safari/537.36"
                )
            }
            
            callback(extractorLink)
            println("✅ Link direto criado")
            
        } catch (e: Exception) {
            println("❌ Erro criando link direto: ${e.message}")
        }
    }

    // Método alternativo mais simples
    private suspend fun extractWithSimpleMethod(
        videoId: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Usar serviço de proxy para YouTube
            val proxyUrl = "https://yewtu.be/latest_version?id=$videoId&itag=22"
            
            println("🔗 Usando proxy: $proxyUrl")
            
            val extractorLink = newExtractorLink(
                source = name,
                name = "Trailer YouTube (via Proxy)",
                url = proxyUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = "https://yewtu.be"
                this.quality = Qualities.P720.value
                this.headers = mapOf(
                    "Referer" to "https://yewtu.be",
                    "User-Agent" to "Mozilla/5.0"
                )
            }
            
            callback(extractorLink)
            
        } catch (e: Exception) {
            println("❌ Método simples falhou: ${e.message}")
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
}
