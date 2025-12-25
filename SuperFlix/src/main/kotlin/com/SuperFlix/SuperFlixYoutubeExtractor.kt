package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

data class YouTubeStreamFormat(
    @JsonProperty("url") val url: String?,
    @JsonProperty("quality") val quality: String?,
    @JsonProperty("qualityLabel") val qualityLabel: String?,
    @JsonProperty("bitrate") val bitrate: Int?,
    @JsonProperty("width") val width: Int?,
    @JsonProperty("height") val height: Int?,
    @JsonProperty("itag") val itag: Int?,
    @JsonProperty("hasAudio") val hasAudio: Boolean = false,
    @JsonProperty("hasVideo") val hasVideo: Boolean = false,
    @JsonProperty("mimeType") val mimeType: String?
)

data class YouTubeApiResponse(
    @JsonProperty("title") val title: String?,
    @JsonProperty("videoThumbnails") val thumbnails: List<Map<String, Any>>?,
    @JsonProperty("formatStreams") val formatStreams: List<YouTubeStreamFormat>?,
    @JsonProperty("adaptiveFormats") val adaptiveFormats: List<YouTubeStreamFormat>?
)

object SuperFlixYoutubeExtractor {
    val name = "SuperFlixYouTube"
    
    suspend fun getUrl(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🎬 [SuperFlix] Processando YouTube: $url")
            
            val videoId = extractYouTubeId(url) ?: return false
            println("📹 Video ID encontrado: $videoId")
            
            // Método principal usando Invidious API
            if (extractAllQualities(videoId, referer, subtitleCallback, callback)) {
                return true
            }
            
            // Fallback simples
            return fallbackToEmbed(videoId, referer, callback)
            
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
        try {
            val mapper = jacksonObjectMapper()
            
            // Tentar múltiplas APIs do Invidious
            val apiUrls = listOf(
                "https://inv.riverside.rocks/api/v1/videos/$videoId",
                "https://vid.puffyan.us/api/v1/videos/$videoId",
                "https://invidious.snopyta.org/api/v1/videos/$videoId",
                "https://yewtu.be/api/v1/videos/$videoId"
            )
            
            for (apiUrl in apiUrls) {
                println("🔗 Tentando API: $apiUrl")
                
                try {
                    val response = app.get(apiUrl, timeout = 15000)
                    if (response.code == 200) {
                        val apiResponse: YouTubeApiResponse = mapper.readValue(response.text)
                        var foundAny = false
                        
                        // 1. Primeiro: Fluxos COMBINADOS (áudio + vídeo juntos)
                        println("📊 Buscando fluxos combinados...")
                        apiResponse.formatStreams?.forEach { format ->
                            if (format.url != null && format.hasVideo) {
                                val quality = mapQuality(format)
                                println("✅ Combinado: ${format.qualityLabel} (${quality}p)")
                                
                                val headers = mapOf(
                                    "Referer" to referer,
                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                                    "Origin" to "https://www.youtube.com"
                                )
                                
                                callback.invoke(ExtractorLink(
                                    source = name,
                                    name = "YouTube (${format.qualityLabel ?: "${quality}p"})",
                                    url = format.url,
                                    referer = referer,
                                    quality = quality,
                                    isM3u8 = false,
                                    headers = headers
                                ))
                                foundAny = true
                            }
                        }
                        
                        // 2. Segundo: Fluxos ADAPTATIVOS (vídeo apenas - alta qualidade)
                        println("📊 Buscando fluxos adaptativos (vídeo apenas)...")
                        apiResponse.adaptiveFormats?.forEach { format ->
                            if (format.url != null && format.hasVideo && !format.hasAudio) {
                                val quality = mapQuality(format)
                                if (quality >= 720) { // Só pega qualidades 720p+
                                    println("🎥 Adaptativo (vídeo): ${format.qualityLabel} (${quality}p)")
                                    
                                    // Nota: Este é só vídeo, sem áudio!
                                    // Para funcionar, precisaria do stream de áudio também
                                    // Mas vamos incluir como opção
                                    val headers = mapOf(
                                        "Referer" to referer,
                                        "User-Agent" to "Mozilla/5.0",
                                        "Origin" to "https://www.youtube.com"
                                    )
                                    
                                    callback.invoke(ExtractorLink(
                                        source = name,
                                        name = "YouTube Vídeo (${format.qualityLabel ?: "${quality}p"}) [SOMENTE VÍDEO]",
                                        url = format.url,
                                        referer = referer,
                                        quality = quality,
                                        isM3u8 = false,
                                        headers = headers
                                    ))
                                    foundAny = true
                                }
                            }
                        }
                        
                        // 3. Extrair legendas
                        extractSubtitles(videoId, subtitleCallback)
                        
                        if (foundAny) {
                            println("✨ Encontradas qualidades múltiplas!")
                            return true
                        }
                    }
                } catch (e: Exception) {
                    println("⚠️ API falhou: $apiUrl - ${e.message}")
                    continue
                }
            }
            
            return false
        } catch (e: Exception) {
            println("❌ Erro ao extrair qualidades: ${e.message}")
            return false
        }
    }

    private fun mapQuality(format: YouTubeStreamFormat): Int {
        // Mapear qualidade baseado na qualidadeLabel
        return when {
            format.qualityLabel?.contains("144p") == true -> Qualities.P144.value
            format.qualityLabel?.contains("240p") == true -> Qualities.P240.value
            format.qualityLabel?.contains("360p") == true -> Qualities.P360.value
            format.qualityLabel?.contains("480p") == true -> Qualities.P480.value
            format.qualityLabel?.contains("720p") == true -> Qualities.P720.value
            format.qualityLabel?.contains("1080p") == true -> Qualities.P1080.value
            format.qualityLabel?.contains("1440p") == true -> Qualities.P1440.value
            format.qualityLabel?.contains("2160p") == true -> Qualities.P2160.value
            format.qualityLabel?.contains("4K") == true -> Qualities.P2160.value
            
            // Baseado em height/width
            format.height ?: 0 >= 2160 -> Qualities.P2160.value
            format.height ?: 0 >= 1440 -> Qualities.P1440.value
            format.height ?: 0 >= 1080 -> Qualities.P1080.value
            format.height ?: 0 >= 720 -> Qualities.P720.value
            format.height ?: 0 >= 480 -> Qualities.P480.value
            format.height ?: 0 >= 360 -> Qualities.P360.value
            
            else -> Qualities.P360.value // Padrão
        }
    }

    private suspend fun extractSubtitles(
        videoId: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        try {
            val subUrl = "https://inv.riverside.rocks/api/v1/videos/$videoId?fields=subtitles"
            val response = app.get(subUrl, timeout = 10000)
            
            if (response.code == 200) {
                val json = response.text
                val subPattern = Regex(""""url":"(https?://[^"]+\\.vtt)","lang":"([^"]+)""")
                
                subPattern.findAll(json).forEach { match ->
                    val url = match.groupValues[1]
                    val lang = match.groupValues[2]
                    
                    if (url.contains("timedtext")) {
                        println("📝 Legenda: $lang")
                        subtitleCallback.invoke(SubtitleFile(lang, url))
                    }
                }
            }
        } catch (e: Exception) {
            // Ignorar erros de legendas
        }
    }

    private suspend fun fallbackToEmbed(
        videoId: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            // Embed simples como último recurso
            val embedUrl = "https://www.youtube.com/embed/$videoId"
            println("🔄 Fallback para embed simples")
            
            callback.invoke(ExtractorLink(
                source = name,
                name = "YouTube (360p)",
                url = embedUrl,
                referer = referer,
                quality = Qualities.P360.value,
                isM3u8 = false
            ))
            
            return true
        } catch (e: Exception) {
            return false
        }
    }
}
