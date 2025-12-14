package com.AnimeFire

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app

object AnimeFireExtractor {
    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🔗 AnimeFireExtractor: Extraindo de $url")

            // Fazer requisição à página do episódio
            val document = app.get(url).document
            
            // Procurar por iframes
            val iframe = document.selectFirst("iframe[src*='lightspeedst.net'], iframe[src*='lightspeedts.net']")
            
            if (iframe != null) {
                val iframeSrc = iframe.attr("src")
                return extractFromLightspeedUrl(iframeSrc, mainUrl, name, callback)
            }
            
            // Procurar scripts com URLs
            val html = app.get(url).text
            val mp4Regex = Regex("""["'](https?://[^"']*lightspeed(st|ts)\.net[^"']*\.mp4)["']""")
            val mp4Matches = mp4Regex.findAll(html)
            
            var found = false
            mp4Matches.forEach { match ->
                val videoUrl = match.groupValues[1]
                if (extractFromLightspeedUrl(videoUrl, mainUrl, name, callback)) {
                    found = true
                }
            }
            
            println("✅ AnimeFireExtractor: Links encontrados: $found")
            found
        } catch (e: Exception) {
            println("💥 AnimeFireExtractor: Erro - ${e.message}")
            false
        }
    }
    
    private suspend fun extractFromLightspeedUrl(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val regex = Regex("""lightspeed(st|ts)\.net/s(\d+)/mp4/([^/]+)/([^/]+)/(\d+)\.mp4""")
            val match = regex.find(url)
            
            if (match != null) {
                val serverType = match.groupValues[1]
                val season = match.groupValues[2]
                val titlePath = match.groupValues[3]
                val quality = match.groupValues[4]
                val episode = match.groupValues[5]
                
                val qualities = listOf("fhd", "hd", "sd")
                
                qualities.forEach { qual ->
                    val videoUrl = "https://lightspeed${serverType}.net/s${season}/mp4/${titlePath}/${qual}/${episode}.mp4"
                    
                    // Testar se o link está acessível
                    try {
                        val response = app.head(videoUrl, headers = mapOf(
                            "Referer" to mainUrl,
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                        ))
                        
                        if (response.code in 200..299) {
                            // Usar newExtractorLink
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = "${qual.uppercase()} - AnimeFire",
                                    url = videoUrl,
                                    referer = "$mainUrl/",
                                    quality = getQualityFromString(qual),
                                    isM3u8 = false
                                )
                            )
                            println("✅ AnimeFireExtractor: Link válido encontrado - $qual")
                        }
                    } catch (e: Exception) {
                        println("⚠️ AnimeFireExtractor: Link não acessível - $qual")
                    }
                }
                
                return true
            }
        } catch (e: Exception) {
            println("💥 AnimeFireExtractor: Erro ao processar URL - ${e.message}")
        }
        
        return false
    }
    
    private fun getQualityFromString(quality: String): Int {
        return when (quality.lowercase()) {
            "fhd", "1080p", "fullhd" -> Qualities.Q1080P.value
            "hd", "720p" -> Qualities.Q720P.value
            "sd", "480p" -> Qualities.Q480P.value
            "360p" -> Qualities.Q360P.value
            "240p" -> Qualities.Q240P.value
            else -> Qualities.Q720P.value // Default
        }
    }
}
