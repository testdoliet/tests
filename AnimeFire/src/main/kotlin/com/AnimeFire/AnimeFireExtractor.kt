package com.AnimeFire

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

object AnimeFireExtractor {

    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🔗 AnimeFireExtractor: Extraindo de $url")

            val document = app.get(url).document
            
            // Procurar por iframes
            val iframe = document.selectFirst("iframe[src*='lightspeedst.net'], iframe[src*='lightspeedts.net']")
            if (iframe != null) {
                val iframeSrc = iframe.attr("src")
                println("🌐 AnimeFireExtractor: Iframe encontrado: $iframeSrc")
                return extractMultipleQualities(iframeSrc, mainUrl, name, callback)
            }
            
            // Procurar links no HTML
            val html = app.get(url).text
            val mp4Regex = Regex("""https?://[^"'\s]*lightspeed(st|ts)\.net[^"'\s]*\.mp4""")
            
            val matches = mp4Regex.findAll(html).toList()
            if (matches.isNotEmpty()) {
                var foundAny = false
                matches.forEach { match ->
                    val videoUrl = match.value
                    println("✅ AnimeFireExtractor: Link MP4 encontrado: $videoUrl")
                    if (extractMultipleQualities(videoUrl, mainUrl, name, callback)) {
                        foundAny = true
                    }
                }
                return foundAny
            }
            
            println("❌ AnimeFireExtractor: Nenhum link encontrado")
            false
        } catch (e: Exception) {
            println("💥 AnimeFireExtractor: Erro - ${e.message}")
            false
        }
    }
    
    private suspend fun extractMultipleQualities(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return coroutineScope {
            try {
                val regex = Regex("""lightspeed(st|ts)\.net/s(\d+)/mp4/([^/]+)/([^/]+)/(\d+)\.mp4""")
                val match = regex.find(url)
                
                if (match != null) {
                    val serverType = match.groupValues[1]
                    val season = match.groupValues[2]
                    val titlePath = match.groupValues[3]
                    val episode = match.groupValues[5]
                    
                    println("✨ AnimeFireExtractor: Extraindo qualidades para: $titlePath/$episode")
                    
                    val qualities = listOf(
                        Triple("fhd", 1080, "Full HD"),
                        Triple("hd", 720, "HD"),
                        Triple("sd", 480, "SD")
                    )
                    
                    // Testar todas as qualidades em paralelo
                    val qualityResults = qualities.map { (qual, qualityNum, qualName) ->
                        async {
                            val videoUrl = "https://lightspeed${serverType}.net/s${season}/mp4/${titlePath}/${qual}/${episode}.mp4"
                            
                            return@async try {
                                val response = app.head(videoUrl, timeout = 3000)
                                if (response.code == 200) {
                                    Pair(videoUrl, Triple(qualName, qualityNum, qual))
                                } else {
                                    null
                                }
                            } catch (e: Exception) {
                                null
                            }
                        }
                    }.awaitAll()
                    
                    var foundAny = false
                    qualityResults.filterNotNull().forEach { (videoUrl, (qualName, qualityNum, qual)) ->
                        // Usar newExtractorLink dentro de uma função suspend
                        val extractorLink = newExtractorLink(
                            source = name,
                            name = "$qualName - AnimeFire",
                            url = videoUrl,
                        ) {
                            // Configurar propriedades no bloco lambda
                            referer = mainUrl
                            quality = qualityNum
                            isM3u8 = false
                        }
                        
                        callback.invoke(extractorLink)
                        println("✅ AnimeFireExtractor: Qualidade $qual adicionada")
                        foundAny = true
                    }
                    
                    return@coroutineScope foundAny
                }
            } catch (e: Exception) {
                println("⚠️ AnimeFireExtractor: Erro ao extrair qualidades - ${e.message}")
            }
            
            return@coroutineScope false
        }
    }
}
