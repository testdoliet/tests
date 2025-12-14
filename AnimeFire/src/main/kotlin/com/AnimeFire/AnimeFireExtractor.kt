package com.AnimeFire

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink

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
                    
                    // Usar ExtractorLink diretamente pois newExtractorLink é suspend
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = "Vídeo - AnimeFire",
                            url = videoUrl,
                            referer = mainUrl,
                            quality = 720,
                            isM3u8 = videoUrl.contains(".m3u8")
                        )
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
    
    private suspend fun extractMultipleQualitiesFromUrl(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
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
                for ((qual, qualName) in qualities) {
                    val videoUrl = "https://lightspeed${serverType}.net/s${season}/mp4/${titlePath}/${qual}/${episode}.mp4"
                    
                    // Testar se o link existe
                    try {
                        val response = app.head(videoUrl, timeout = 3000)
                        
                        if (response.code == 200) {
                            // Usar newExtractorLink corretamente (função suspend)
                            val extractorLink = newExtractorLink(
                                source = name,
                                name = "$qualName - AnimeFire",
                                url = videoUrl,
                            ) {
                                // Dentro do bloco suspend podemos acessar as propriedades
                                // mas não podemos reassignar val, então configuramos no construtor
                            }
                            
                            // Configurar propriedades adicionais
                            val finalLink = ExtractorLink(
                                source = extractorLink.source,
                                name = extractorLink.name,
                                url = extractorLink.url,
                                referer = mainUrl,
                                quality = when (qual) {
                                    "fhd" -> 1080
                                    "hd" -> 720
                                    else -> 480
                                },
                                isM3u8 = false
                            )
                            
                            callback.invoke(finalLink)
                            println("✅ AnimeFireExtractor: Qualidade $qual adicionada")
                            foundAny = true
                        } else {
                            println("⚠️ AnimeFireExtractor: Qualidade $qual não disponível (HTTP ${response.code})")
                        }
                    } catch (e: Exception) {
                        println("⚠️ AnimeFireExtractor: Erro ao testar qualidade $qual - ${e.message}")
                    }
                }
                
                foundAny
            } else {
                false
            }
        } catch (e: Exception) {
            println("⚠️ AnimeFireExtractor: Erro ao extrair múltiplas qualidades - ${e.message}")
            false
        }
    }
}
