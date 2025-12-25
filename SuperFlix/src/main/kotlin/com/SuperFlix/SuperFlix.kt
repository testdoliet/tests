package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

object SuperFlixYoutubeExtractor {
    val name = "SuperFlixYouTube"
    
    suspend fun getUrl(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🎬 [SuperFlix] Processando trailer YouTube: $url")
            
            val videoId = extractYouTubeId(url) ?: return false
            println("📹 Video ID encontrado: $videoId")
            
            // Método principal que pega TODAS as qualidades
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
    
    // Data classes para a API do Invidious
    data class YouTubeFormat(
        @JsonProperty("url") val url: String?,
        @JsonProperty("quality") val quality: String?,
        @JsonProperty("qualityLabel") val qualityLabel: String?,
        @JsonProperty("type") val type: String?,
        @JsonProperty("audio") val audio: Boolean? = false,
        @JsonProperty("video") val video: Boolean? = false,
        @JsonProperty("itag") val itag: Int?
    )
    
    data class YouTubeResponse(
        @JsonProperty("formatStreams") val formatStreams: List<YouTubeFormat>?,
        @JsonProperty("adaptiveFormats") val adaptiveFormats: List<YouTubeFormat>?,
        @JsonProperty("title") val title: String?
    )
    
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
                
                val data: YouTubeResponse = mapper.readValue(response.text)
                var foundAny = false
                
                // 1. Primeiro: formatStreams (streams COMBINADOS - áudio+video juntos)
                println("📊 Procurando streams combinados...")
                data.formatStreams?.forEach { format ->
                    if (format.url != null && format.video == true && format.audio == true) {
                        val quality = mapQuality(format.qualityLabel, format.itag)
                        println("✅ Stream combinado: ${format.qualityLabel} (${quality}p)")
                        
                        // AQUI ESTÁ O CORRETO: usando newExtractorLink
                        val extractorLink = newExtractorLink(
                            source = name,
                            name = "YouTube (${format.qualityLabel ?: "${quality}p"})",
                            url = format.url,
                            type = LoadExtractorConfig.VIDEO
                        ) {
                            this.referer = referer
                            this.quality = quality
                            this.headers = mapOf(
                                "Referer" to "https://www.youtube.com",
                                "User-Agent" to "Mozilla/5.0",
                                "Origin" to "https://www.youtube.com"
                            )
                        }
                        
                        callback(extractorLink)
                        foundAny = true
                    }
                }
                
                // 2. Segundo: adaptiveFormats (streams separados)
                println("📊 Procurando streams adaptativos...")
                data.adaptiveFormats?.forEach { format ->
                    if (format.url != null && format.video == true) {
                        val quality = mapQuality(format.qualityLabel, format.itag)
                        
                        // Só pega qualidades 720p+ para vídeo-only
                        if (quality >= Qualities.P720.value) {
                            println("🎥 Stream adaptativo (vídeo): ${format.qualityLabel} (${quality}p)")
                            
                            // AQUI TAMBÉM: usando newExtractorLink
                            val extractorLink = newExtractorLink(
                                source = name,
                                name = "YouTube Vídeo (${format.qualityLabel ?: "${quality}p"}) [VÍDEO APENAS]",
                                url = format.url,
                                type = LoadExtractorConfig.VIDEO
                            ) {
                                this.referer = referer
                                this.quality = quality
                                this.headers = mapOf(
                                    "Referer" to "https://www.youtube.com",
                                    "User-Agent" to "Mozilla/5.0",
                                    "Origin" to "https://www.youtube.com"
                                )
                            }
                            
                            callback(extractorLink)
                            foundAny = true
                        }
                    }
                }
                
                // 3. Extrair legendas se houver
                try {
                    extractSubtitles(apiUrl.replace("/videos/", "/captions/"), subtitleCallback)
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
        
        // Fallback: se não encontrou nada, tenta um método alternativo
        println("🔄 Nenhuma qualidade encontrada, tentando fallback...")
        return fallbackToSimpleMethod(videoId, referer, callback)
    }
    
    private fun mapQuality(qualityLabel: String?, itag: Int?): Int {
        // Baseado no qualityLabel
        qualityLabel?.let {
            return when {
                it.contains("144p") -> Qualities.P144.value
                it.contains("240p") -> Qualities.P240.value
                it.contains("360p") -> Qualities.P360.value
                it.contains("480p") -> Qualities.P480.value
                it.contains("720p") -> Qualities.P720.value
                it.contains("1080p") -> Qualities.P1080.value
                it.contains("1440p") -> Qualities.P1440.value
                it.contains("2160p") || it.contains("4K") -> Qualities.P2160.value
                else -> Qualities.P720.value
            }
        }
        
        // Baseado no itag
        itag?.let {
            return when (it) {
                17, 160 -> Qualities.P144.value    // 144p
                18, 133 -> Qualities.P360.value    // 360p
                22, 136 -> Qualities.P720.value    // 720p
                37, 137 -> Qualities.P1080.value   // 1080p
                313, 315 -> Qualities.P2160.value  // 4K
                else -> Qualities.P720.value
            }
        }
        
        return Qualities.P720.value
    }
    
    private suspend fun extractSubtitles(
        captionUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        try {
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
    
    private suspend fun fallbackToSimpleMethod(
        videoId: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            // Método de fallback simples: usa embed do YouTube
            val embedUrl = "https://www.youtube-nocookie.com/embed/$videoId"
            println("🔗 Fallback para embed: $embedUrl")
            
            val extractorLink = newExtractorLink(
                source = name,
                name = "YouTube (360p)",
                url = embedUrl,
                type = LoadExtractorConfig.VIDEO
            ) {
                this.referer = referer
                this.quality = Qualities.P360.value
                this.headers = mapOf(
                    "Referer" to "https://www.youtube.com",
                    "User-Agent" to "Mozilla/5.0",
                    "Origin" to "https://www.youtube.com"
                )
            }
            
            callback(extractorLink)
            return true
            
        } catch (e: Exception) {
            println("❌ Fallback falhou: ${e.message}")
            return false
        }
    }
}
