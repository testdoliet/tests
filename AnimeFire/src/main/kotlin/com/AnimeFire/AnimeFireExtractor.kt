package com.AnimeFire

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink

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
            
            // Procurar por iframes do lightspeedst.net
            val iframe = document.selectFirst("iframe[src*='lightspeedst.net'], iframe[src*='lightspeedts.net']")
            if (iframe != null) {
                val iframeSrc = iframe.attr("src")
                println("🌐 AnimeFireExtractor: Iframe encontrado: $iframeSrc")
                
                // Extrair múltiplas qualidades
                if (extractMultipleQualitiesFromUrl(iframeSrc, mainUrl, name, callback)) {
                    return true
                }
            }
            
            // Procurar por links MP4 no HTML
            val html = app.get(url).text
            val mp4Regex = Regex("""https?://[^"'\s]*lightspeed(st|ts)\.net[^"'\s]*\.mp4""")
            val mp4Matches = mp4Regex.findAll(html)
            
            var foundLinks = false
            mp4Matches.forEach { match ->
                val videoUrl = match.value
                println("✅ AnimeFireExtractor: Link MP4 encontrado: $videoUrl")
                
                if (extractMultipleQualitiesFromUrl(videoUrl, mainUrl, name, callback)) {
                    foundLinks = true
                }
            }
            
            if (foundLinks) {
                return true
            }
            
            // Fallback: Tentar encontrar qualquer link de vídeo
            println("⚠️ AnimeFireExtractor: Procurando qualquer link de vídeo...")
            val videoRegex = Regex("""(https?://[^"'\s]*\.(mp4|m3u8))""")
            val videoMatches = videoRegex.findAll(html)
            
            videoMatches.forEach { match ->
                val videoUrl = match.value
                if (videoUrl.contains(".mp4") || videoUrl.contains(".m3u8")) {
                    println("✅ AnimeFireExtractor: Link de vídeo encontrado: $videoUrl")
                    
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "Vídeo - AnimeFire",
                            videoUrl,
                        ) {
                            this.referer = mainUrl
                            this.quality = 720 // qualidade padrão
                            this.isM3u8 = videoUrl.contains(".m3u8")
                        }
                    )
                    return true
                }
            }
            
            println("❌ AnimeFireExtractor: Nenhum link de vídeo encontrado")
            false
        } catch (e: Exception) {
            println("💥 AnimeFireExtractor: Erro - ${e.message}")
            false
        }
    }
    
    private fun extractMultipleQualitiesFromUrl(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            // Padrão: https://lightspeedst.net/s2/mp4/titulo/hd/1.mp4
            val regex = Regex("""lightspeed(st|ts)\.net/s(\d+)/mp4/([^/]+)/([^/]+)/(\d+)\.mp4""")
            val match = regex.find(url)
            
            if (match != null) {
                val serverType = match.groupValues[1] // "st" ou "ts"
                val season = match.groupValues[2] // número do servidor
                val titlePath = match.groupValues[3] // caminho do título
                val currentQuality = match.groupValues[4] // qualidade atual
                val episode = match.groupValues[5] // número do episódio
                
                println("✨ AnimeFireExtractor: Padrão identificado - Server: $serverType, Title: $titlePath, Qual: $currentQuality, Ep: $episode")
                
                // Gerar links para todas as qualidades
                val qualities = listOf(
                    "fhd" to "Full HD",
                    "hd" to "HD", 
                    "sd" to "SD"
                )
                
                var foundAny = false
                qualities.forEach { (qual, qualName) ->
                    val videoUrl = "https://lightspeed${serverType}.net/s${season}/mp4/${titlePath}/${qual}/${episode}.mp4"
                    
                    // Usar newExtractorLink corretamente
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "$qualName - AnimeFire",
                            videoUrl,
                        ) {
                            this.referer = mainUrl
                            this.quality = when (qual) {
                                "fhd" -> 1080
                                "hd" -> 720
                                else -> 480
                            }
                            this.isM3u8 = false
                        }
                    )
                    
                    println("✅ AnimeFireExtractor: Qualidade $qual adicionada")
                    foundAny = true
                }
                
                return foundAny
            }
        } catch (e: Exception) {
            println("⚠️ AnimeFireExtractor: Erro ao extrair múltiplas qualidades - ${e.message}")
        }
        
        return false
    }
}
