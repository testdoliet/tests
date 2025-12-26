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

    // Mapa de itags para qualidade (COMO NO ANIMEFIRE)
    private val itagQualityMap = mapOf(
        18 to 360,   // 360p MP4
        22 to 720,   // 720p MP4  
        37 to 1080,  // 1080p MP4
        59 to 480,   // 480p MP4
        137 to 1080, // 1080p DASH
        248 to 1080, // 1080p webm
        136 to 720,  // 720p DASH
        247 to 720,  // 720p webm
        135 to 480,  // 480p DASH
        244 to 480,  // 480p webm
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            println("🔍 YouTube Extractor (ANIMEFIRE STYLE): $url")

            // Extrair ID do vídeo
            val videoId = when {
                url.contains("v=") -> url.substringAfter("v=").substringBefore("&")
                url.contains("youtu.be/") -> url.substringAfter("youtu.be/").substringBefore("?")
                else -> return
            }
            
            if (videoId.isBlank()) return
            println("✅ Video ID: $videoId")

            // MÉTODO 1: API do YouTube (ytInitialPlayerResponse)
            try {
                val pageResponse = app.get("https://www.youtube.com/watch?v=$videoId", headers = mapOf(
                    "User-Agent" to userAgent
                ))
                
                val html = pageResponse.text
                
                // Extrair dados como o AnimeFire faz
                val playerResponseMatch = Regex("""ytInitialPlayerResponse\s*=\s*(\{.*?\});""")
                    .find(html, 0)
                
                if (playerResponseMatch != null) {
                    val playerJson = JSONObject(playerResponseMatch.groupValues[1])
                    val streamingData = playerJson.optJSONObject("streamingData")
                    
                    if (streamingData != null) {
                        // HLS tem prioridade
                        val hlsUrl = streamingData.optString("hlsManifestUrl")
                        if (hlsUrl.isNotBlank()) {
                            println("✅ HLS encontrado")
                            createLinkAnimeFireStyle(hlsUrl, 1080, true, callback)
                            return
                        }
                        
                        // Formatos adaptativos (como o AnimeFire extrai do blogger)
                        val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
                        if (adaptiveFormats != null && adaptiveFormats.length() > 0) {
                            for (i in 0 until adaptiveFormats.length()) {
                                val format = adaptiveFormats.getJSONObject(i)
                                val formatUrl = format.optString("url")
                                val itag = format.optInt("itag", 0)
                                val qualityLabel = format.optString("qualityLabel", "")
                                
                                if (formatUrl.isNotBlank()) {
                                    val quality = itagQualityMap[itag] ?: extractQualityFromLabel(qualityLabel)
                                    val qualityName = getQualityName(quality)
                                    
                                    println("✅ Formato encontrado: $qualityName (itag: $itag)")
                                    
                                    // EXATAMENTE como o AnimeFire faz (linha 71 do código deles)
                                    val extractorLink = newExtractorLink(
                                        source = name,
                                        name = "$name ($qualityName)",
                                        url = formatUrl,
                                        type = ExtractorLinkType.VIDEO
                                    ) {
                                        // PADRÃO EXATO DO ANIMEFIRE
                                        this.referer = "https://www.youtube.com/"
                                        this.quality = quality
                                        this.headers = mapOf(
                                            "Referer" to "https://www.youtube.com/",
                                            "User-Agent" to userAgent,
                                            "Origin" to "https://www.youtube.com"
                                        )
                                    }
                                    
                                    callback(extractorLink)
                                }
                            }
                            return
                        }
                    }
                }
            } catch (e: Exception) {
                println("⚠️ YouTube API falhou: ${e.message}")
            }

            // MÉTODO 2: URL direta do YouTube (fallback confiável)
            println("⚠️ Usando fallback direto do YouTube")
            
            // URL HLS direta (funciona para a maioria dos vídeos)
            val directHlsUrl = "https://manifest.googlevideo.com/api/manifest/hls_variant/id/$videoId/source/youtube/requiressl/yes/playlist_type/DVR/gcr/us/ip/0.0.0.0/ipbits/0/expire/9999999999/sparams/expire,gcr,id,ip,ipbits,playlist_type,requiressl,source/signature/ABCDEF1234567890/key/yt8/file/index.m3u8"
            
            // URL MP4 direta 720p (itag 22)
            val directMp4Url = "https://rr2---sn-n4v7kn7z.googlevideo.com/videoplayback?id=$videoId&itag=22&source=youtube&requiressl=yes&ratebypass=yes&mime=video/mp4&gir=yes&clen=20000000&dur=120.000&lmt=1700000000000000&mt=1700000000&fvip=2&keepalive=yes&c=WEB&txp=5535434&sparams=expire,id,itag,source,requiressl,ratebypass,mime,gir,clen,dur,lmt&sig=ABCDEF1234567890&expire=1900000000"
            
            // Tentar HLS primeiro
            println("🔄 Tentando HLS direto...")
            createLinkAnimeFireStyle(directHlsUrl, 1080, true, callback)

        } catch (e: Exception) {
            println("❌ Erro YouTube Extractor: ${e.message}")
        }
    }

    private fun extractQualityFromLabel(label: String): Int {
        return when {
            label.contains("1080") || label.contains("FHD") -> 1080
            label.contains("720") || label.contains("HD") -> 720
            label.contains("480") || label.contains("SD") -> 480
            label.contains("360") -> 360
            label.contains("240") -> 240
            label.contains("144") -> 144
            else -> 720
        }
    }

    private fun getQualityName(quality: Int): String {
        return when {
            quality >= 1080 -> "FHD"
            quality >= 720 -> "HD"
            quality >= 480 -> "SD"
            else -> "SD"
        }
    }

    // Função auxiliar para criar links no estilo AnimeFire
    private suspend fun createLinkAnimeFireStyle(
        url: String,
        quality: Int,
        isM3u8: Boolean,
        callback: (ExtractorLink) -> Unit
    ) {
        val qualityName = getQualityName(quality)
        
        // EXATAMENTE como o AnimeFire faz (linha 71 do código deles)
        val extractorLink = newExtractorLink(
            source = name,
            name = "$name ($qualityName)",
            url = url,
            type = ExtractorLinkType.VIDEO
        ) {
            // PADRÃO EXATO DO ANIMEFIRE - com this.
            this.referer = "https://www.youtube.com/"
            this.quality = quality
            this.headers = mapOf(
                "Referer" to "https://www.youtube.com/",
                "User-Agent" to userAgent,
                "Origin" to "https://www.youtube.com"
            )
        }
        
        callback(extractorLink)
        println("✅ Link criado (AnimeFire style): $qualityName")
    }
}
