package com.AnimeFire

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.json.JSONObject
import org.jsoup.Jsoup

object AnimeFireExtractor {
    // Mapa de itag para qualidade
    private val itagQualityMap = mapOf(
        5 to 240,    // 240p FLV
        17 to 144,   // 144p 3GP
        18 to 360,   // 360p MP4
        22 to 720,   // 720p MP4
        34 to 360,   // 360p FLV
        35 to 480,   // 480p FLV
        36 to 240,   // 240p 3GP
        37 to 1080,  // 1080p MP4
        38 to 3072,  // 3072p MP4
        43 to 360,   // 360p WebM
        44 to 480,   // 480p WebM
        45 to 720,   // 720p WebM
        46 to 1080,  // 1080p WebM
        59 to 480,   // 480p MP4
        78 to 480,   // 480p WebM
        82 to 360,   // 360p 3D
        83 to 480,   // 480p 3D
        84 to 720,   // 720p 3D
        85 to 1080,  // 1080p 3D
        100 to 360,  // 360p 3D WebM
        101 to 480,  // 480p 3D WebM
        102 to 720,  // 720p 3D WebM
    )
    
    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔗 AnimeFireExtractor: Processando $url")
        
        // Tentar em ordem:
        // 1. Novo sistema (Blogger/Google)
        // 2. Sistema antigo (XHR/Lightspeed)
        
        val methods = listOf(
            ::tryBloggerSystem,
            ::tryXHRSystem
        )
        
        for (method in methods) {
            try {
                println("🔄 AnimeFireExtractor: Tentando método ${method.name}")
                val result = method(url, mainUrl, name, callback)
                if (result) {
                    println("✅ AnimeFireExtractor: Sucesso com ${method.name}")
                    return true
                }
            } catch (e: Exception) {
                println("⚠️ AnimeFireExtractor: Método ${method.name} falhou: ${e.message}")
            }
        }
        
        println("❌ AnimeFireExtractor: Todos os métodos falharam")
        return false
    }
    
    private suspend fun tryBloggerSystem(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🌐 AnimeFireExtractor: Buscando iframe do Blogger...")
            
            val page = app.get(url)
            val doc = Jsoup.parse(page.text)
            
            val iframe = doc.selectFirst("iframe[src*='blogger.com/video.g']")
                ?: return false.also { println("⚠️ Nenhum iframe do Blogger encontrado") }
            
            val iframeUrl = iframe.attr("src")
            println("🔗 Iframe encontrado: ${iframeUrl.take(80)}...")
            
            // Acessar iframe
            val iframeContent = app.get(iframeUrl, headers = mapOf(
                "Referer" to url,
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            )).text
            
            // Extrair VIDEO_CONFIG
            extractVideoConfig(iframeContent, iframeUrl, name, callback)
            
        } catch (e: Exception) {
            println("💥 Erro no sistema Blogger: ${e.message}")
            false
        }
    }
    
    private suspend fun extractVideoConfig(
        html: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val configRegex = """var\s+VIDEO_CONFIG\s*=\s*(\{.*?\})\s*;""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val match = configRegex.find(html) ?: return false
        
        return try {
            val config = JSONObject(match.groupValues[1])
            val streams = config.getJSONArray("streams")
            
            var found = false
            for (i in 0 until streams.length()) {
                val stream = streams.getJSONObject(i)
                val videoUrl = stream.getString("play_url")
                val itag = stream.getInt("format_id")
                val quality = itagQualityMap[itag] ?: 360
                
                val qualityName = when (quality) {
                    in 1080..Int.MAX_VALUE -> "1080p"
                    720 -> "720p"
                    480 -> "480p"
                    360 -> "360p"
                    240 -> "240p"
                    else -> "SD"
                }
                
                // Criar o ExtractorLink (agora é suspend)
                val extractorLink = newExtractorLink(
                    source = "AnimeFire",
                    name = "$name ($qualityName)",
                    url = videoUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = quality
                    this.headers = mapOf(
                        "Referer" to referer,
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                        "Origin" to "https://www.blogger.com"
                    )
                }
                
                callback(extractorLink)
                println("✅ Stream $qualityName (itag $itag) adicionado")
                found = true
            }
            
            found
        } catch (e: Exception) {
            println("💥 Erro ao parsear VIDEO_CONFIG: ${e.message}")
            false
        }
    }
    
    private suspend fun tryXHRSystem(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val pathParts = url.removePrefix("https://animefire.io/animes/").split("/")
            if (pathParts.size < 2) return false
            
            val slug = pathParts[0]
            val ep = pathParts[1].toIntOrNull() ?: 1
            
            val xhrUrl = "https://animefire.io/video/$slug/$ep?tempsubs=0&${System.currentTimeMillis()/1000}"
            println("🌐 XHR URL: $xhrUrl")
            
            val response = app.get(xhrUrl, headers = mapOf(
                "Referer" to url,
                "X-Requested-With" to "XMLHttpRequest"
            ))
            
            val json = JSONObject(response.text)
            val data = json.getJSONArray("data")
            
            var found = false
            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                val videoUrl = item.getString("src")
                val label = item.optString("label", "")
                
                val quality = when {
                    label.contains("1080") -> 1080
                    label.contains("720") -> 720
                    label.contains("480") -> 480
                    label.contains("360") -> 360
                    videoUrl.contains("lightspeedst") -> 720 // Lightspeed geralmente é 720p
                    else -> 480
                }
                
                val qualityName = when (quality) {
                    1080 -> "1080p"
                    720 -> "720p"
                    480 -> "480p"
                    360 -> "360p"
                    else -> "SD"
                }
                
                // Criar o ExtractorLink (agora é suspend)
                val extractorLink = newExtractorLink(
                    source = "AnimeFire",
                    name = "$name ($qualityName)",
                    url = videoUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = quality
                    this.headers = mapOf(
                        "Referer" to url,
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                    )
                }
                
                callback(extractorLink)
                println("✅ XHR: $qualityName adicionado")
                found = true
            }
            
            found
        } catch (e: Exception) {
            println("💥 Erro no sistema XHR: ${e.message}")
            false
        }
    }
}
