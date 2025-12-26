package com.SuperFlix

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import org.json.JSONObject

class YouTubeTrailerExtractor : ExtractorApi() {
    override val name = "YouTube HD"
    override val mainUrl = "https://www.youtube.com"
    override val requiresReferer = false

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            println("🔍 YouTube Extractor FINAL: $url")

            // Extrair ID do vídeo
            val videoId = Regex("(?:youtube\\.com/(?:watch\\?v=|embed/)|youtu\\.be/)([A-Za-z0-9_-]{11})")
                .find(url)?.groupValues?.get(1) ?: return

            println("✅ Video ID: $videoId")

            // MÉTODO 1: yt-dlp via API Pública
            val ytdlpFound = tryYtDlpApi(videoId, callback)
            if (ytdlpFound) {
                println("✅ yt-dlp API funcionou!")
                return
            }

            println("⚠️ yt-dlp falhou, tentando método alternativo...")
            tryYouTubeApi(videoId, callback)

        } catch (e: Exception) {
            println("❌ Erro YouTube Extractor: ${e.message}")
        }
    }

    private suspend fun tryYtDlpApi(
        videoId: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val apiUrls = listOf(
                "https://yt.lemnoslife.com/videos?part=streamingDetails&id=$videoId",
                "https://inv.riverside.rocks/api/v1/videos/$videoId",
                "https://pipedapi.kavin.rocks/streams/$videoId"
            )

            for (apiUrl in apiUrls) {
                try {
                    println("🔄 Tentando API: $apiUrl")
                    val response = app.get(apiUrl, headers = mapOf(
                        "User-Agent" to userAgent
                    ), timeout = 10000)

                    if (response.isSuccessful) {
                        val json = JSONObject(response.text)
                        
                        val hlsUrl = extractHlsUrl(json)
                        if (hlsUrl != null) {
                            println("✅ HLS via API: $hlsUrl")
                            createHlsLink(hlsUrl, callback)
                            return true
                        }

                        val formats = extractFormats(json)
                        if (formats.isNotEmpty()) {
                            println("✅ ${formats.size} formatos encontrados via API")
                            for ((formatUrl, quality) in formats) {
                                createVideoLink(formatUrl, quality, callback)
                            }
                            return true
                        }
                    }
                } catch (e: Exception) {
                    println("⚠️ API falhou: ${e.message}")
                    continue
                }
            }
            false
        } catch (e: Exception) {
            println("❌ Erro yt-dlp API: ${e.message}")
            false
        }
    }

    private fun extractHlsUrl(json: JSONObject): String? {
        return try {
            listOf(
                { json.optJSONArray("items")?.optJSONObject(0)?.optJSONObject("streamingDetails")?.optString("hlsManifestUrl") },
                { json.optString("hls") },
                { json.optJSONObject("videoStreams")?.optString("hls") },
                { json.optJSONArray("videoStreams")?.optJSONObject(0)?.optString("hls") }
            ).forEach { path ->
                val url = path()
                if (!url.isNullOrBlank()) return url
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun extractFormats(json: JSONObject): List<Pair<String, Int>> {
        val formats = mutableListOf<Pair<String, Int>>()
        
        try {
            val items = json.optJSONArray("items")
            if (items != null && items.length() > 0) {
                val video = items.getJSONObject(0)
                val streamingDetails = video.optJSONObject("streamingDetails")
                val adaptiveFormats = streamingDetails?.optJSONArray("adaptiveFormats")
                
                adaptiveFormats?.let { formatsArray ->
                    for (i in 0 until formatsArray.length()) {
                        val format = formatsArray.getJSONObject(i)
                        val url = format.optString("url")
                        val qualityLabel = format.optString("qualityLabel", "")
                        
                        if (url.isNotBlank()) {
                            val quality = extractQualityFromLabel(qualityLabel)
                            formats.add(Pair(url, quality))
                        }
                    }
                }
            }
            
            val videoStreams = json.optJSONArray("videoStreams") ?: json.optJSONArray("formats")
            if (videoStreams != null) {
                for (i in 0 until videoStreams.length()) {
                    val stream = videoStreams.getJSONObject(i)
                    val url = stream.optString("url")
                    val quality = stream.optString("quality", "")
                    
                    if (url.isNotBlank()) {
                        val qualityNum = extractQualityFromLabel(quality)
                        formats.add(Pair(url, qualityNum))
                    }
                }
            }
            
        } catch (e: Exception) {
            println("⚠️ Erro extraindo formatos: ${e.message}")
        }
        
        return formats.sortedByDescending { it.second }
    }

    private fun extractQualityFromLabel(label: String): Int {
        return when {
            label.contains("2160") || label.contains("4K") -> 2160
            label.contains("1440") || label.contains("2K") -> 1440
            label.contains("1080") || label.contains("FHD") -> 1080
            label.contains("720") || label.contains("HD") -> 720
            label.contains("480") || label.contains("SD") -> 480
            label.contains("360") -> 360
            label.contains("240") -> 240
            label.contains("144") -> 144
            else -> 720
        }
    }

    private suspend fun tryYouTubeApi(
        videoId: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val pageResponse = app.get("https://www.youtube.com/watch?v=$videoId", headers = mapOf(
                "User-Agent" to userAgent
            ))
            val html = pageResponse.text

            val playerResponseMatch = Regex("""ytInitialPlayerResponse\s*=\s*(\{.*?\});""")
                .find(html, 0)
            
            if (playerResponseMatch != null) {
                val playerJson = JSONObject(playerResponseMatch.groupValues[1])
                val streamingData = playerJson.optJSONObject("streamingData")
                
                val hlsUrl = streamingData?.optString("hlsManifestUrl")
                if (!hlsUrl.isNullOrBlank()) {
                    println("✅ HLS direto do YouTube: $hlsUrl")
                    createHlsLink(hlsUrl, callback)
                    return
                }
                
                val formats = streamingData?.optJSONArray("adaptiveFormats")
                if (formats != null && formats.length() > 0) {
                    for (i in 0 until formats.length()) {
                        val format = formats.getJSONObject(i)
                        val url = format.optString("url")
                        val qualityLabel = format.optString("qualityLabel", "")
                        
                        if (url.isNotBlank()) {
                            val quality = extractQualityFromLabel(qualityLabel)
                            createVideoLink(url, quality, callback)
                        }
                    }
                }
            }
            
            // Fallback final
            println("⚠️ Usando fallback genérico")
            val fallbackUrl = "https://www.youtube.com/embed/$videoId?autoplay=1"
            createVideoLink(fallbackUrl, 720, callback)
            
        } catch (e: Exception) {
            println("❌ Erro YouTube API: ${e.message}")
        }
    }

    private suspend fun createHlsLink(
        hlsUrl: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val extractorLink = newExtractorLink(
            source = name,
            name = "$name (HLS - 1080p)",
            url = hlsUrl,
            type = ExtractorLinkType.VIDEO
        ) {
            // CORREÇÃO: Não use 'val' aqui
            referer = "https://www.youtube.com/"
            quality = 1080
            headers = mapOf(
                "Referer" to "https://www.youtube.com/",
                "User-Agent" to userAgent,
                "Origin" to "https://www.youtube.com"
            )
            isM3u8 = true
        }
        callback(extractorLink)
    }

    private suspend fun createVideoLink(
        videoUrl: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        val qualityLabel = when {
            quality >= 2160 -> "4K"
            quality >= 1440 -> "2K"
            quality >= 1080 -> "FHD"
            quality >= 720 -> "HD"
            quality >= 480 -> "SD"
            else -> "SD"
        }
        
        val isM3u8 = videoUrl.contains(".m3u8")
        
        val extractorLink = newExtractorLink(
            source = name,
            name = "$name ($qualityLabel)",
            url = videoUrl,
            type = ExtractorLinkType.VIDEO
        ) {
            // CORREÇÃO: Não use 'val' aqui
            referer = "https://www.youtube.com/"
            quality = quality
            headers = mapOf(
                "Referer" to "https://www.youtube.com/",
                "User-Agent" to userAgent,
                "Origin" to "https://www.youtube.com"
            )
            isM3u8 = isM3u8
        }
        callback(extractorLink)
    }
}
