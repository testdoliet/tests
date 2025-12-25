package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

// Data classes para a API
private data class YouTubeStreamFormat(
    @JsonProperty("url") val url: String?,
    @JsonProperty("quality") val quality: String?,
    @JsonProperty("qualityLabel") val qualityLabel: String?,
    @JsonProperty("type") val type: String?,
    @JsonProperty("audio") val audio: Boolean? = false,
    @JsonProperty("video") val video: Boolean? = false,
    @JsonProperty("itag") val itag: Int?
)

private data class YouTubeApiResponse(
    @JsonProperty("formatStreams") val formatStreams: List<YouTubeStreamFormat>?,
    @JsonProperty("adaptiveFormats") val adaptiveFormats: List<YouTubeStreamFormat>?,
    @JsonProperty("title") val title: String?
)

object SuperFlixYoutubeExtractor {
    val name = "SuperFlixYouTube"
    
    init {
        println("✅ SuperFlixYoutubeExtractor carregado!")
    }
    
    suspend fun getUrl(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🎬 [SuperFlix] Processando trailer: $url")
            
            val videoId = extractYouTubeId(url) ?: return false
            println("📹 Video ID: $videoId")
            
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
        val instances = listOf(
            "https://inv.riverside.rocks",
            "https://vid.puffyan.us",
            "https://yewtu.be"
        )
        
        for (instance in instances) {
            try {
                val apiUrl = "$instance/api/v1/videos/$videoId"
                println("🔗 Tentando: $instance")
                
                val response = app.get(apiUrl, timeout = 15000)
                if (response.code != 200) continue
                
                val data: YouTubeApiResponse = mapper.readValue(response.text)
                var foundStreams = false
                
                // Streams combinados (áudio+video)
                data.formatStreams?.forEach { format ->
                    if (format.url != null && format.video == true && format.audio == true) {
                        val quality = mapQuality(format)
                        println("✅ Stream completo: ${format.qualityLabel} (${quality}p)")
                        
                        val extractorLink = ExtractorLink(
                            source = name,
                            name = "YouTube (${format.qualityLabel ?: "${quality}p"})",
                            url = format.url,
                            referer = referer,
                            quality = quality,
                            isM3u8 = false,
                            headers = mapOf(
                                "Referer" to "https://www.youtube.com",
                                "User-Agent" to "Mozilla/5.0"
                            )
                        )
                        
                        callback(extractorLink)
                        foundStreams = true
                    }
                }
                
                // Streams adaptativos
                data.adaptiveFormats?.forEach { format ->
                    if (format.url != null && format.video == true && format.audio != true) {
                        val quality = mapQuality(format)
                        
                        if (quality >= 720) {
                            println("🎥 Stream vídeo: ${format.qualityLabel} (${quality}p)")
                            
                            val extractorLink = ExtractorLink(
                                source = name,
                                name = "YouTube Vídeo (${format.qualityLabel ?: "${quality}p"})",
                                url = format.url,
                                referer = referer,
                                quality = quality,
                                isM3u8 = false,
                                headers = mapOf(
                                    "Referer" to "https://www.youtube.com",
                                    "User-Agent" to "Mozilla/5.0"
                                )
                            )
                            
                            callback(extractorLink)
                            foundStreams = true
                        }
                    }
                }
                
                // Legendas
                try {
                    extractSubtitles(videoId, subtitleCallback)
                } catch (e: Exception) {
                    // Ignorar
                }
                
                if (foundStreams) {
                    println("✨ Qualidades encontradas!")
                    return true
                }
                
            } catch (e: Exception) {
                println("⚠️ $instance falhou: ${e.message}")
                continue
            }
        }
        
        return fallbackToEmbed(videoId, referer, callback)
    }
    
    private fun mapQuality(format: YouTubeStreamFormat): Int {
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
        
        format.itag?.let { itag ->
            return when (itag) {
                18 -> 360   // MP4 360p
                22 -> 720   // MP4 720p
                37 -> 1080  // MP4 1080p
                43 -> 360   // WebM 360p
                44 -> 480   // WebM 480p
                45 -> 720   // WebM 720p
                46 -> 1080  // WebM 1080p
                137 -> 1080 // MP4 1080p
                248 -> 1080 // WebM 1080p
                313 -> 2160 // WebM 4K
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
                val subPattern = Regex(""""label":"([^"]+)","url":"([^"]+)"""")
                
                subPattern.findAll(json).forEach { match ->
                    val label = match.groupValues[1]
                    val url = match.groupValues[2].replace("\\/", "/")
                    
                    if (url.endsWith(".vtt") || url.endsWith(".srt")) {
                        println("📝 Legenda: $label")
                        subtitleCallback(SubtitleFile(label, url))
                    }
                }
            }
        } catch (e: Exception) {
            // Ignorar
        }
    }
    
    private suspend fun fallbackToEmbed(
        videoId: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val embedUrl = "https://www.youtube-nocookie.com/embed/$videoId"
            println("🔗 Fallback para embed")
            
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
            return false
        }
    }
}
