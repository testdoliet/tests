package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import java.net.URI

object SuperFlixYoutubeExtractor {
    suspend fun getUrl(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🎬 YouTubeExtractor processando: $url")
            
            // Extrair ID do vídeo
            val videoId = extractYouTubeId(url) ?: return false
            
            // Usar Invidious API que fornece TODAS as qualidades
            extractAllQualitiesFromInvidious(videoId, referer, subtitleCallback, callback) ||
            extractAllQualitiesFromPiped(videoId, referer, subtitleCallback, callback) ||
            extractWithYouTubeEmbed(videoId, referer, callback)
            
        } catch (e: Exception) {
            println("❌ YouTubeExtractor erro: ${e.message}")
            false
        }
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

    // NOVO MÉTODO: Extrair TODAS as qualidades do Invidious
    private suspend fun extractAllQualitiesFromInvidious(
        videoId: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // Usar API do Invidious que retorna JSON com todas as qualidades
            val apiUrl = "https://inv.riverside.rocks/api/v1/videos/$videoId"
            println("🔗 Usando Invidious API: $apiUrl")
            
            val response = app.get(apiUrl, timeout = 15000)
            if (response.code != 200) {
                println("❌ Invidious API falhou: ${response.code}")
                return false
            }
            
            val data = response.parsedSafe<JsonElement>() ?: return false
            println("✅ Invidious API resposta recebida")
            
            var foundAny = false
            
            // Extrair formatos adaptativos (todos separados)
            val adaptiveFormats = data.obj["adaptiveFormats"]?.array
            adaptiveFormats?.forEach { format ->
                try {
                    val urlElement = format.obj["url"]?.string ?: return@forEach
                    val bitrate = format.obj["bitrate"]?.int ?: 0
                    val type = format.obj["type"]?.string ?: ""
                    val qualityLabel = format.obj["qualityLabel"]?.string ?: ""
                    
                    // Determinar se é vídeo ou áudio
                    val isVideo = type.contains("video")
                    val isAudio = type.contains("audio")
                    
                    if (isVideo) {
                        val quality = extractQualityFromLabel(qualityLabel)
                        println("🎥 Vídeo encontrado: ${quality}p (${qualityLabel}) - Bitrate: ${bitrate/1000}kbps")
                        
                        // Agora precisamos encontrar o áudio correspondente
                        val audioFormat = findMatchingAudioFormat(adaptiveFormats, bitrate)
                        
                        if (audioFormat != null) {
                            val audioUrl = audioFormat.obj["url"]?.string
                            if (audioUrl != null) {
                                // Para YouTube, normalmente usamos HLS que já mescla
                                // Mas vamos retornar o link direto que o Cloudstream pode processar
                                val videoUrlWithAudio = urlElement // O link já deve ter áudio
                                
                                val extractorLink = createExtractorLink(
                                    source = "YouTube",
                                    name = "Trailer YouTube (${quality}p)",
                                    url = videoUrlWithAudio,
                                    quality = quality,
                                    referer = referer
                                )
                                
                                callback(extractorLink)
                                foundAny = true
                            }
                        }
                    }
                } catch (e: Exception) {
                    println("⚠️ Erro processando formato: ${e.message}")
                }
            }
            
            // Se não encontrou formatos adaptativos, tenta formatos normais
            if (!foundAny) {
                val formatStreams = data.obj["formatStreams"]?.array
                formatStreams?.forEach { stream ->
                    try {
                        val urlElement = stream.obj["url"]?.string ?: return@forEach
                        val qualityLabel = stream.obj["qualityLabel"]?.string ?: ""
                        val bitrate = stream.obj["bitrate"]?.int ?: 0
                        
                        val quality = extractQualityFromLabel(qualityLabel)
                        println("📹 Stream encontrado: ${quality}p (${qualityLabel})")
                        
                        val extractorLink = createExtractorLink(
                            source = "YouTube",
                            name = "Trailer YouTube (${quality}p)",
                            url = urlElement,
                            quality = quality,
                            referer = referer
                        )
                        
                        callback(extractorLink)
                        foundAny = true
                    } catch (e: Exception) {
                        println("⚠️ Erro processando stream: ${e.message}")
                    }
                }
            }
            
            // Extrair legendas
            val captions = data.obj["captions"]?.array
            captions?.forEach { caption ->
                try {
                    val label = caption.obj["label"]?.string ?: caption.obj["language"]?.string ?: "Unknown"
                    val url = caption.obj["url"]?.string
                    
                    if (url != null) {
                        subtitleCallback.invoke(
                            SubtitleFile(label, url)
                        )
                        println("📝 Legenda encontrada: $label")
                    }
                } catch (e: Exception) {
                    // Ignora erro em legendas
                }
            }
            
            if (foundAny) {
                println("✅ Invidious: ${foundAny} qualidades encontradas")
            }
            
            foundAny
        } catch (e: Exception) {
            println("❌ Invidious API erro: ${e.message}")
            false
        }
    }

    // Método para extrair de Piped (fallback)
    private suspend fun extractAllQualitiesFromPiped(
        videoId: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // Piped também tem API JSON
            val apiUrl = "https://pipedapi.kavin.rocks/streams/$videoId"
            println("🔗 Usando Piped API: $apiUrl")
            
            val response = app.get(apiUrl, timeout = 15000)
            if (response.code != 200) return false
            
            val data = response.parsedSafe<JsonElement>() ?: return false
            
            var foundAny = false
            
            // Vídeos com áudio incluído
            val videoStreams = data.obj["videoStreams"]?.array
            videoStreams?.forEach { stream ->
                try {
                    val url = stream.obj["url"]?.string ?: return@forEach
                    val quality = stream.obj["quality"]?.string ?: ""
                    val bitrate = stream.obj["bitrate"]?.int ?: 0
                    val format = stream.obj["format"]?.string ?: ""
                    
                    val qualityNum = extractQualityFromString(quality)
                    println("🎥 Piped Stream: ${qualityNum}p - $format - ${bitrate/1000}kbps")
                    
                    val extractorLink = createExtractorLink(
                        source = "YouTube via Piped",
                        name = "Trailer YouTube (${qualityNum}p)",
                        url = url,
                        quality = qualityNum,
                        referer = referer
                    )
                    
                    callback(extractorLink)
                    foundAny = true
                } catch (e: Exception) {
                    println("⚠️ Erro processando stream Piped: ${e.message}")
                }
            }
            
            foundAny
        } catch (e: Exception) {
            println("❌ Piped API erro: ${e.message}")
            false
        }
    }

    private fun findMatchingAudioFormat(formats: JsonElement.Array, videoBitrate: Int): JsonElement.Obj? {
        return formats.firstOrNull { format ->
            val type = format.obj["type"]?.string ?: ""
            val bitrate = format.obj["bitrate"]?.int ?: 0
            type.contains("audio") && bitrate > 64000 // Áudio de boa qualidade
        }
    }

    private fun extractQualityFromLabel(label: String): Int {
        return when {
            label.contains("144") -> 144
            label.contains("240") -> 240
            label.contains("360") -> 360
            label.contains("480") -> 480
            label.contains("720") -> 720
            label.contains("1080") -> 1080
            label.contains("1440") -> 1440
            label.contains("2160") || label.contains("4K") -> 2160
            else -> 720
        }
    }

    private fun extractQualityFromString(str: String): Int {
        return Regex("(\\d+)").find(str)?.groupValues?.get(1)?.toIntOrNull() ?: 720
    }

    private fun createExtractorLink(
        source: String,
        name: String,
        url: String,
        quality: Int,
        referer: String
    ): ExtractorLink {
        val host = try {
            URI(referer).host ?: "www.youtube.com"
        } catch (e: Exception) {
            "www.youtube.com"
        }
        
        val headers = mapOf(
            "Referer" to referer,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Origin" to "https://$host"
        )
        
        return newExtractorLink(
            source = source,
            name = name,
            url = url,
            type = ExtractorLinkType.VIDEO
        ) {
            this.referer = referer
            this.quality = mapQualityToValue(quality)
            this.headers = headers
        }
    }

    private suspend fun extractWithYouTubeEmbed(
        videoId: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Fallback: usar embed do YouTube
        val embedUrl = "https://www.youtube-nocookie.com/embed/$videoId?autoplay=1&rel=0"
        println("🔗 Fallback para YouTube Embed")
        
        val extractorLink = createExtractorLink(
            source = "YouTube Embed",
            name = "Trailer YouTube (720p)",
            url = embedUrl,
            quality = 720,
            referer = referer
        )
        
        callback(extractorLink)
        return true
    }

    private fun mapQualityToValue(quality: Int): Int {
        return when (quality) {
            144 -> Qualities.P144.value
            240 -> Qualities.P240.value
            360 -> Qualities.P360.value
            480 -> Qualities.P480.value
            720 -> Qualities.P720.value
            1080 -> Qualities.P1080.value
            1440 -> Qualities.P1440.value
            2160 -> Qualities.P2160.value
            else -> Qualities.Unknown.value
        }
    }
}
