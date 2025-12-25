package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

// Data classes para a API do Invidious
data class YouTubeStreamFormat(
    @JsonProperty("url") val url: String?,
    @JsonProperty("quality") val quality: String?,
    @JsonProperty("qualityLabel") val qualityLabel: String?,
    @JsonProperty("type") val type: String?,
    @JsonProperty("audio") val audio: Boolean? = false,
    @JsonProperty("video") val video: Boolean? = false,
    @JsonProperty("itag") val itag: Int?
)

data class YouTubeApiResponse(
    @JsonProperty("formatStreams") val formatStreams: List<YouTubeStreamFormat>?,
    @JsonProperty("adaptiveFormats") val adaptiveFormats: List<YouTubeStreamFormat>?,
    @JsonProperty("title") val title: String?
)

object SuperFlixYoutubeExtractor {
    val name = "SuperFlixYouTube"
    
    init {
        println("✅ SuperFlixYouTubeExtractor inicializado!")
    }
    
    suspend fun getUrl(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🎬 [SuperFlix] YouTubeExtractor processando: $url")
            
            val videoId = extractYouTubeId(url) ?: return false
            println("📹 Video ID: $videoId")
            
            // Método principal
            extractAllQualities(videoId, referer, subtitleCallback, callback)
            
        } catch (e: Exception) {
            println("❌ YouTubeExtractor erro: ${e.message}")
            false
        }
    }
    
    private fun extractYouTubeId(url: String): String? {
        return Regex("""(?:youtube\.com/watch\?v=|youtu\.be/|youtube\.com/embed/|v=)([a-zA-Z0-9_-]{11})""")
            .find(url)?.groupValues?.get(1)
    }
    
    private suspend fun extractAllQualities(
        videoId: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val mapper = jacksonObjectMapper()
        
        // Múltiplas APIs do Invidious
        val apiUrls = listOf(
            "https://inv.riverside.rocks/api/v1/videos/$videoId",
            "https://yewtu.be/api/v1/videos/$videoId",
            "https://vid.puffyan.us/api/v1/videos/$videoId",
            "https://invidious.snopyta.org/api/v1/videos/$videoId"
        )
        
        for (apiUrl in apiUrls) {
            try {
                println("🔗 Tentando API: $apiUrl")
                
                val response = app.get(apiUrl, timeout = 15000)
                if (response.code != 200) continue
                
                val data: YouTubeApiResponse = mapper.readValue(response.text)
                var foundAny = false
                
                // 1. Buscar streams combinados (áudio+video)
                println("📊 Procurando streams combinados...")
                data.formatStreams?.forEach { format ->
                    if (format.url != null && format.video == true && format.audio == true) {
                        val quality = mapQualityFromFormat(format)
                        println("✅ Stream combinado: ${format.qualityLabel} (${quality}p)")
                        
                        val extractorLink = ExtractorLink(
                            source = name,
                            name = "YouTube (${format.qualityLabel ?: "${quality}p"})",
                            url = format.url,
                            referer = referer,
                            quality = quality,
                            isM3u8 = false,
                            headers = mapOf(
                                "Referer" to "https://www.youtube.com",
                                "User-Agent" to "Mozilla/5.0",
                                "Origin" to "https://www.youtube.com"
                            )
                        )
                        
                        callback(extractorLink)
                        foundAny = true
                    }
                }
                
                // 2. Buscar streams adaptativos (vídeo apenas)
                println("📊 Procurando streams adaptativos...")
                data.adaptiveFormats?.forEach { format ->
                    if (format.url != null && format.video == true && format.audio != true) {
                        val quality = mapQualityFromFormat(format)
                        
                        // Só pega qualidades 720p+
                        if (quality >= 720) {
                            println("🎥 Stream adaptativo (vídeo): ${format.qualityLabel} (${quality}p)")
                            
                            val extractorLink = ExtractorLink(
                                source = name,
                                name = "YouTube Vídeo (${format.qualityLabel ?: "${quality}p"}) [SOMENTE VÍDEO]",
                                url = format.url,
                                referer = referer,
                                quality = quality,
                                isM3u8 = false,
                                headers = mapOf(
                                    "Referer" to "https://www.youtube.com",
                                    "User-Agent" to "Mozilla/5.0",
                                    "Origin" to "https://www.youtube.com"
                                )
                            )
                            
                            callback(extractorLink)
                            foundAny = true
                        }
                    }
                }
                
                // 3. Extrair legendas
                try {
                    extractSubtitles(videoId, subtitleCallback)
                } catch (e: Exception) {
                    // Ignorar erros de legenda
                }
                
                if (foundAny) {
                    println("✨ Encontradas múltiplas qualidades!")
                    return true
                }
                
            } catch (e: Exception) {
                println("⚠️ API falhou: ${e.message}")
                continue
            }
        }
        
        // Fallback
        println("🔄 Nenhuma qualidade encontrada, tentando fallback...")
        return fallbackToEmbed(videoId, referer, callback)
    }
    
    private fun mapQualityFromFormat(format: YouTubeStreamFormat): Int {
        // Baseado no qualityLabel
        format.qualityLabel?.let { label ->
            return when {
                label.contains("144p") -> 144
                label.contains("240p") -> 240
                label.contains("360p") -> 360
                label.contains("480p") -> 480
                label.contains("720p") -> 720
                label.contains("1080p") -> 1080
                label.contains("1440p") -> 1440
                label.contains("2160p") || label.contains("4K") -> 2160
                else -> 720
            }
        }
        
        // Baseado no itag
        format.itag?.let { itag ->
            return when (itag) {
                17, 160 -> 144    // 144p
                18, 133 -> 360    // 360p
                22, 136 -> 720    // 720p
                37, 137 -> 1080   // 1080p
                313, 315 -> 2160  // 4K
                else -> 720
            }
        }
        
        return 720
    }
    
    private suspend fun extractSubtitles(
        videoId: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        try {
            val captionUrl = "https://inv.riverside.rocks/api/v1/captions/$videoId"
            val response = app.get(captionUrl, timeout = 10000)
            
            if (response.code == 200) {
                val json = response.text
                val subPattern = Regex(""""label":"([^"]+)","languageCode":"([^"]+)","url":"([^"]+)"""")
                
                subPattern.findAll(json).forEach { match ->
                    val label = match.groupValues[1]
                    val langCode = match.groupValues[2]
                    val url = match.groupValues[3].replace("\\/", "/")
                    
                    if (url.endsWith(".vtt") || url.endsWith(".srt")) {
                        println("📝 Legenda encontrada: $label ($langCode)")
                        subtitleCallback(SubtitleFile(label, url))
                    }
                }
            }
        } catch (e: Exception) {
            // Ignorar erros
        }
    }
    
    private suspend fun fallbackToEmbed(
        videoId: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val embedUrl = "https://www.youtube-nocookie.com/embed/$videoId"
            println("🔗 Fallback para embed: $embedUrl")
            
            val extractorLink = ExtractorLink(
                source = name,
                name = "YouTube (360p)",
                url = embedUrl,
                referer = referer,
                quality = 360,
                isM3u8 = false
            )
            
            callback(extractorLink)
            return true
            
        } catch (e: Exception) {
            println("❌ Fallback falhou: ${e.message}")
            return false
        }
    }
}
