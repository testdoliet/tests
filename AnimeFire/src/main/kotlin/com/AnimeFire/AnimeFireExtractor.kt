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
        18 to 360,   // 360p MP4
        22 to 720,   // 720p MP4
        37 to 1080,  // 1080p MP4
        59 to 480,   // 480p MP4
        43 to 360,   // 360p WebM
        44 to 480,   // 480p WebM
        45 to 720,   // 720p WebM
        46 to 1080,  // 1080p WebM
    )
    
    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔗 AnimeFireExtractor: Processando $url")
        
        // Decidir qual método usar baseado na página
        val pageResponse = app.get(url)
        val pageHtml = pageResponse.text
        val doc = Jsoup.parse(pageHtml)
        
        // Verificar qual sistema está sendo usado
        val hasBloggerIframe = doc.selectFirst("iframe[src*='blogger.com/video.g']") != null
        
        return if (hasBloggerIframe) {
            println("📹 Sistema Blogger detectado")
            extractBloggerVideo(doc, url, name, callback)
        } else {
            println("⚡ Sistema Lightspeed/XHR detectado")
            extractLightspeedVideo(url, name, callback)
        }
    }
    
    private suspend fun extractBloggerVideo(
        doc: org.jsoup.nodes.Document,
        originalUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val iframe = doc.selectFirst("iframe[src*='blogger.com/video.g']")
                ?: return false.also { println("⚠️ Iframe não encontrado") }
            
            val iframeUrl = iframe.attr("src")
            println("🔗 Iframe URL: $iframeUrl")
            
            // Acessar o iframe do Blogger
            val iframeResponse = app.get(iframeUrl, headers = mapOf(
                "Referer" to originalUrl,
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            ))
            
            val iframeHtml = iframeResponse.text
            println("📄 Iframe HTML obtido (${iframeHtml.length} chars)")
            
            // Extrair VIDEO_CONFIG do HTML
            extractFromBloggerHtml(iframeHtml, iframeUrl, name, callback)
            
        } catch (e: Exception) {
            println("💥 Erro no extrator Blogger: ${e.message}")
            false
        }
    }
    
    private suspend fun extractFromBloggerHtml(
        html: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Método 1: Buscar VIDEO_CONFIG
        val configPattern = """var\s+VIDEO_CONFIG\s*=\s*(\{.*?\})\s*;""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val configMatch = configPattern.find(html)
        
        if (configMatch != null) {
            println("🎯 VIDEO_CONFIG encontrado")
            return try {
                val configJson = configMatch.groupValues[1]
                val config = JSONObject(configJson)
                val streams = config.getJSONArray("streams")
                
                var found = false
                for (i in 0 until streams.length()) {
                    val stream = streams.getJSONObject(i)
                    val videoUrl = stream.getString("play_url")
                    val itag = stream.getInt("format_id")
                    val quality = itagQualityMap[itag] ?: 360
                    
                    val qualityName = getQualityName(quality)
                    
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
                    println("✅ Blogger: $qualityName (itag $itag) - ${videoUrl.take(80)}...")
                    found = true
                }
                
                found
            } catch (e: Exception) {
                println("💥 Erro ao processar VIDEO_CONFIG: ${e.message}")
                false
            }
        }
        
        // Método 2: Buscar URLs diretamente no HTML
        println("🔍 Buscando URLs de vídeo no HTML...")
        val videoPattern = """https?://[^"'\s<>]+googlevideo\.com/videoplayback[^"'\s<>]+""".toRegex()
        val matches = videoPattern.findAll(html).toList()
        
        if (matches.isNotEmpty()) {
            println("🎯 ${matches.size} URLs de vídeo encontradas")
            
            var found = false
            for (match in matches) {
                val videoUrl = match.value
                
                // Extrair itag da URL
                val itagPattern = """[?&]itag=(\d+)""".toRegex()
                val itagMatch = itagPattern.find(videoUrl)
                val itag = itagMatch?.groupValues?.get(1)?.toIntOrNull() ?: 18
                val quality = itagQualityMap[itag] ?: 360
                val qualityName = getQualityName(quality)
                
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
                println("✅ Blogger (direto): $qualityName - ${videoUrl.take(80)}...")
                found = true
            }
            
            return found
        }
        
        println("⚠️ Nenhum vídeo encontrado no HTML do Blogger")
        return false
    }
    
    private suspend fun extractLightspeedVideo(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val pathParts = url.removePrefix("https://animefire.io/animes/").split("/")
            if (pathParts.size < 2) {
                println("⚠️ URL inválida para XHR")
                return false
            }
            
            val slug = pathParts[0]
            val ep = pathParts[1].toIntOrNull() ?: 1
            
            val xhrUrl = "https://animefire.io/video/$slug/$ep?tempsubs=0&${System.currentTimeMillis()/1000}"
            println("🌐 XHR URL: $xhrUrl")
            
            val response = app.get(xhrUrl, headers = mapOf(
                "Referer" to url,
                "X-Requested-With" to "XMLHttpRequest",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            ))
            
            val responseText = response.text
            println("📄 Resposta XHR (${responseText.length} chars)")
            
            if (responseText.isEmpty() || responseText.length < 10) {
                println("⚠️ Resposta XHR vazia ou muito curta")
                return false
            }
            
            val json = JSONObject(responseText)
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
                    label.contains("240") -> 240
                    videoUrl.contains("lightspeedst") -> 720 // Lightspeed geralmente é 720p
                    else -> 480
                }
                
                val qualityName = getQualityName(quality)
                
                val extractorLink = newExtractorLink(
                    source = "AnimeFire",
                    name = "$name ($qualityName)",
                    url = videoUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = url
                    this.quality = quality
                    this.headers = mapOf(
                        "Referer" to url,
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                    )
                }
                
                callback(extractorLink)
                println("✅ XHR/Lightspeed: $qualityName - $videoUrl")
                found = true
            }
            
            found
            
        } catch (e: Exception) {
            println("💥 Erro no sistema XHR/Lightspeed: ${e.message}")
            false
        }
    }
    
    private fun getQualityName(quality: Int): String {
        return when (quality) {
            in 1080..Int.MAX_VALUE -> "1080p"
            720 -> "720p"
            480 -> "480p"
            360 -> "360p"
            240 -> "240p"
            else -> "SD"
        }
    }
}
