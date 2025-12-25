package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import java.net.URI
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType

class YouTubeTrailerExtractor : Extractor() {
    override val name = "YouTube Trailer"
    override val mainUrl = "https://www.youtube.com"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // Extrair ID do vídeo
            val videoId = extractYouTubeId(url) ?: return false
            
            // Usar serviço Piped que já fornece vídeo com áudio
            val pipedUrl = "https://piped.video/watch?v=$videoId"
            
            // Tentar extrair usando múltiplos métodos
            extractWithPiped(pipedUrl, referer ?: mainUrl, subtitleCallback, callback) ||
            extractWithInvidious(videoId, referer ?: mainUrl, subtitleCallback, callback) ||
            extractWithYouTubeEmbed(videoId, referer ?: mainUrl, callback)
            
        } catch (e: Exception) {
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

    private suspend fun extractWithPiped(
        pipedUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val document = app.get(pipedUrl).document
            
            // Extrair links de vídeo do Piped
            val videoElements = document.select("video[src], source[type^='video/']")
            var found = false
            
            for (element in videoElements) {
                val videoUrl = element.attr("src")
                if (videoUrl.isNotBlank() && isDirectVideoUrl(videoUrl)) {
                    val quality = extractQualityFromUrl(videoUrl)
                    
                    callback.invoke(
                        newExtractorLink(
                            source = "YouTube via Piped",
                            name = "Trailer YouTube (${quality}p)",
                            url = videoUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = referer
                            this.quality = mapQualityToValue(quality)
                            this.headers = mapOf(
                                "Referer" to referer,
                                "User-Agent" to "Mozilla/5.0",
                                "Origin" to URI(referer).host?.let { "https://$it" } ?: "https://piped.video"
                            )
                        }
                    )
                    found = true
                }
            }
            
            // Extrair legendas se disponíveis
            document.select("track[kind='subtitles']").forEach { track ->
                val label = track.attr("label").ifBlank { track.attr("srclang") }
                val src = track.attr("src")
                
                if (src.isNotBlank()) {
                    subtitleCallback.invoke(
                        SubtitleFile(
                            label.ifBlank { "Unknown" },
                            src
                        )
                    )
                }
            }
            
            found
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun extractWithInvidious(
        videoId: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val invidiousUrl = "https://inv.riverside.rocks/watch?v=$videoId"
            val document = app.get(invidiousUrl).document
            
            // Procurar por links de vídeo no Invidious
            val videoSources = document.select("source[src*='googlevideo.com']")
            var found = false
            
            for (source in videoSources) {
                val videoUrl = source.attr("src")
                if (videoUrl.isNotBlank() && videoUrl.contains("videoplayback")) {
                    val quality = extractQualityFromUrl(videoUrl)
                    
                    callback.invoke(
                        newExtractorLink(
                            source = "YouTube via Invidious",
                            name = "Trailer YouTube (${quality}p)",
                            url = videoUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = referer
                            this.quality = mapQualityToValue(quality)
                            this.headers = mapOf(
                                "Referer" to referer,
                                "User-Agent" to "Mozilla/5.0",
                                "Origin" to URI(referer).host?.let { "https://$it" } ?: "https://inv.riverside.rocks"
                            )
                        }
                    )
                    found = true
                }
            }
            
            found
        } catch (e: Exception) {
            false
        }
    }

    private fun extractWithYouTubeEmbed(
        videoId: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Fallback: usar embed do YouTube
        val embedUrl = "https://www.youtube-nocookie.com/embed/$videoId?autoplay=1&rel=0"
        
        callback.invoke(
            newExtractorLink(
                source = "YouTube Embed",
                name = "Trailer YouTube (Embed)",
                url = embedUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = Qualities.P720.value
                this.headers = mapOf(
                    "Referer" to referer,
                    "User-Agent" to "Mozilla/5.0"
                )
            }
        )
        
        return true
    }

    private fun isDirectVideoUrl(url: String): Boolean {
        return url.endsWith(".mp4") || 
               url.contains("googlevideo.com/videoplayback") ||
               url.contains("video/mp4") ||
               url.contains(".m3u8")
    }

    private fun extractQualityFromUrl(url: String): Int {
        val qualityPatterns = listOf(
            Regex("/(\\d+)p/"),
            Regex("itag=(\\d+)"),
            Regex("quality=(\\d+)"),
            Regex("/(\\d+)/index\\.m3u8")
        )
        
        for (pattern in qualityPatterns) {
            pattern.find(url)?.let {
                val qualityStr = it.groupValues[1]
                return qualityStr.toIntOrNull() ?: 720
            }
        }
        
        // Tenta deduzir pela URL
        return when {
            url.contains("1080") -> 1080
            url.contains("720") -> 720
            url.contains("480") -> 480
            url.contains("360") -> 360
            url.contains("240") -> 240
            url.contains("144") -> 144
            else -> 720
        }
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
