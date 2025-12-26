package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

class SuperFlixYoutubeExtractor : ExtractorApi() {
    override val name = "SuperFlixYouTube"
    override val mainUrl = "https://www.youtube.com"
    
    // MÉTODO OBRIGATÓRIO: Define se precisa de referer
    override val requiresReferer = false

    private val itagQualityMap = mapOf(
        // Vídeos completos (áudio + vídeo)
        18 to Qualities.P360.value,   // MP4 360p
        22 to Qualities.P720.value,   // MP4 720p
        37 to Qualities.P1080.value,  // MP4 1080p
        38 to Qualities.P2160.value,  // MP4 4K
        43 to Qualities.P360.value,   // WebM 360p
        44 to Qualities.P480.value,   // WebM 480p
        45 to Qualities.P720.value,   // WebM 720p
        46 to Qualities.P1080.value,  // WebM 1080p
        59 to Qualities.P480.value,   // MP4 480p
        78 to Qualities.P480.value,   // MP4 480p
        
        // Vídeo apenas (alta qualidade)
        137 to Qualities.P1080.value, // MP4 1080p
        248 to Qualities.P1080.value, // WebM 1080p
        271 to Qualities.P1440.value, // WebM 1440p
        313 to Qualities.P2160.value, // WebM 4K
        315 to Qualities.P2160.value, // WebM 4K60
    )

    // MÉTODO OBRIGATÓRIO 1: Esta assinatura
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            println("🎬 [SuperFlix] YouTubeExtractor processando: $url")
            
            val videoId = extractYouTubeId(url) ?: return
            println("📹 Video ID encontrado: $videoId")
            
            // Método 1: API do Invidious
            if (extractWithInvidious(videoId, referer ?: mainUrl, subtitleCallback, callback)) {
                return
            }
            
            // Método 2: API pública
            extractWithPublicAPI(videoId, referer ?: mainUrl, callback)
            
        } catch (e: Exception) {
            println("❌ YouTubeExtractor erro: ${e.message}")
        }
    }
    
    // MÉTODO OBRIGATÓRIO 2: Esta sobrecarga também
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
    
    private suspend fun extractWithInvidious(
        videoId: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val instances = listOf(
            "https://inv.riverside.rocks",
            "https://vid.puffyan.us",
            "https://yewtu.be"
        )
        
        for (instance in instances) {
            try {
                val apiUrl = "$instance/api/v1/videos/$videoId"
                println("🔗 Tentando Invidious: $instance")
                
                val response = app.get(apiUrl, timeout = 15000)
                if (response.code != 200) continue
                
                val json = response.text
                val jsonObj = JSONObject(json)
                
                var foundStreams = false
                
                // Extrair legendas primeiro
                extractSubtitlesFromInvidious(jsonObj, subtitleCallback)
                
                // formatStreams (streams completos)
                if (jsonObj.has("formatStreams")) {
                    val formatStreams = jsonObj.getJSONArray("formatStreams")
                    for (i in 0 until formatStreams.length()) {
                        val stream = formatStreams.getJSONObject(i)
                        if (stream.has("url") && stream.has("itag")) {
                            val videoUrl = stream.getString("url")
                            val itag = stream.getInt("itag")
                            val qualityLabel = stream.optString("qualityLabel", "")
                            val hasAudio = stream.optBoolean("audio", false)
                            val hasVideo = stream.optBoolean("video", false)
                            
                            // Só pega streams completos (áudio+video)
                            if (hasAudio && hasVideo) {
                                val quality = itagQualityMap[itag] ?: Qualities.P720.value
                                val qualityText = getQualityText(quality, qualityLabel)
                                
                                println("✅ Stream completo: $qualityText")
                                
                                val extractorLink = newExtractorLink(
                                    source = name,
                                    name = "YouTube ($qualityText)",
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
                                foundStreams = true
                            }
                        }
                    }
                }
                
                // adaptiveFormats (streams adaptativos)
                if (jsonObj.has("adaptiveFormats")) {
                    val adaptiveFormats = jsonObj.getJSONArray("adaptiveFormats")
                    for (i in 0 until adaptiveFormats.length()) {
                        val stream = adaptiveFormats.getJSONObject(i)
                        if (stream.has("url") && stream.has("itag")) {
                            val videoUrl = stream.getString("url")
                            val itag = stream.getInt("itag")
                            val qualityLabel = stream.optString("qualityLabel", "")
                            val hasVideo = stream.optBoolean("video", false)
                            
                            // Pega streams de vídeo (mesmo sem áudio)
                            if (hasVideo) {
                                val quality = itagQualityMap[itag] ?: Qualities.P720.value
                                
                                // Só pega qualidades 720p+
                                if (quality >= Qualities.P720.value) {
                                    val qualityText = getQualityText(quality, qualityLabel)
                                    val typeName = if (stream.optBoolean("audio", false)) "Completo" else "Vídeo"
                                    
                                    println("🎥 Stream adaptativo: $qualityText [$typeName]")
                                    
                                    val extractorLink = newExtractorLink(
                                        source = name,
                                        name = "YouTube ($qualityText) [$typeName]",
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
                                    foundStreams = true
                                }
                            }
                        }
                    }
                }
                
                if (foundStreams) {
                    println("✨ Qualidades encontradas via Invidious!")
                    return true
                }
                
            } catch (e: Exception) {
                println("⚠️ $instance falhou: ${e.message}")
                continue
            }
        }
        
        return false
    }
    
    private fun extractSubtitlesFromInvidious(
        jsonObj: JSONObject,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        try {
            if (jsonObj.has("captions")) {
                val captions = jsonObj.getJSONArray("captions")
                for (i in 0 until captions.length()) {
                    val caption = captions.getJSONObject(i)
                    val label = caption.optString("label", "")
                    val url = caption.optString("url", "")
                    
                    if (label.isNotBlank() && url.isNotBlank()) {
                        println("📝 Legenda: $label")
                        subtitleCallback(SubtitleFile(label, url))
                    }
                }
            }
        } catch (e: Exception) {
            // Ignorar erros de legendas
        }
    }
    
    private suspend fun extractWithPublicAPI(
        videoId: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            // API pública do yt-dlp
            val apiUrl = "https://yt.lemnoslife.com/noKey/videos?part=streamingDetails&id=$videoId"
            println("🔗 Tentando API pública")
            
            val response = app.get(apiUrl, timeout = 10000)
            if (response.code != 200) return false
            
            val json = response.text
            
            // Procurar URLs de vídeo
            val videoPattern = """https?://[^"\s]+googlevideo\.com/videoplayback[^"\s]*itag=\d+[^"\s]*""".toRegex()
            val matches = videoPattern.findAll(json).toList()
            
            if (matches.isNotEmpty()) {
                var found = false
                for (match in matches) {
                    val videoUrl = match.value
                    val itagPattern = """itag=(\d+)""".toRegex()
                    val itagMatch = itagPattern.find(videoUrl)
                    val itag = itagMatch?.groupValues?.get(1)?.toIntOrNull() ?: 18
                    val quality = itagQualityMap[itag] ?: Qualities.P720.value
                    val qualityText = getQualityText(quality, "")
                    
                    println("✅ Link direto: $qualityText")
                    
                    val extractorLink = newExtractorLink(
                        source = name,
                        name = "YouTube ($qualityText)",
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
                    found = true
                }
                
                if (found) {
                    println("✨ Links diretos encontrados!")
                    return true
                }
            }
            
            return false
            
        } catch (e: Exception) {
            println("❌ API pública falhou: ${e.message}")
            return false
        }
    }
    
    private fun getQualityText(quality: Int, qualityLabel: String): String {
        if (qualityLabel.isNotBlank()) {
            return qualityLabel
        }
        
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
