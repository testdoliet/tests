package com.AnimeFire

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.WebViewResolver
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
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
            
            // Tentar com WebView primeiro
            val resolver = WebViewResolver(
                regex = Regex("""lightspeedst\.net.*\.mp4"""),
                useOkhttp = false
            )
            
            val response = app.get(url, interceptor = resolver)
            val interceptedUrl = response.url
            
            if (interceptedUrl.isNotEmpty() && interceptedUrl.contains("lightspeedst.net")) {
                println("✅ Link interceptado: $interceptedUrl")
                
                // Extrair informações do link
                val basePattern = """(https://lightspeedst\.net/s\d+/mp4/[^/]+)/[^/]+/(\d+)\.mp4""".toRegex()
                val match = basePattern.find(interceptedUrl)
                
                if (match != null) {
                    val basePath = match.groupValues[1]
                    val episodeNum = match.groupValues[2]
                    
                    println("📁 Base: $basePath")
                    println("🎬 Episódio: $episodeNum")
                    
                    // Qualidades disponíveis
                    val qualities = listOf(
                        Triple("fhd", 1080),
                        Triple("hd", 720), 
                        Triple("sd", 480)
                    )
                    
                    var foundAny = false
                    
                    for ((qualityName, qualityValue) in qualities) {
                        val videoUrl = "$basePath/$qualityName/$episodeNum.mp4"
                        
                        println("🔄 Testando: $qualityName ($videoUrl)")
                        
                        // Verificar se o link existe
                        try {
                            val test = app.head(videoUrl, timeout = 3000)
                            if (test.code == 200) {
                                println("✅ Qualidade $qualityName disponível")
                                
                                callback.invoke(
                                    ExtractorLink(
                                        source = name,
                                        name = "$name ($qualityName)",
                                        url = videoUrl,
                                        referer = "$mainUrl/",
                                        quality = qualityValue,
                                        headers = mapOf(
                                            "Referer" to url,
                                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                                        ),
                                        type = ExtractorLinkType.VIDEO
                                    )
                                )
                                foundAny = true
                            }
                        } catch (e: Exception) {
                            println("❌ $qualityName não disponível")
                        }
                    }
                    
                    return foundAny
                } else {
                    // Se não encontrou o padrão, usar o link direto
                    val quality = when {
                        interceptedUrl.contains("1080") || interceptedUrl.contains("fhd") -> 1080
                        interceptedUrl.contains("720") || interceptedUrl.contains("hd") -> 720
                        else -> 480
                    }
                    
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = name,
                            url = interceptedUrl,
                            referer = "$mainUrl/",
                            quality = quality,
                            headers = mapOf(
                                "Referer" to url,
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                            ),
                            type = ExtractorLinkType.VIDEO
                        )
                    )
                    return true
                }
            }
            
            // Se WebView não funcionou, tentar buscar no HTML
            println("🔄 WebView não funcionou, buscando no HTML...")
            
            val doc = app.get(url).document
            val html = doc.html()
            
            // Buscar links no HTML
            val regex = Regex("""https://lightspeedst\.net/s\d+/mp4/[^"'\s]+""")
            val matches = regex.findAll(html)
            
            var foundInHtml = false
            
            matches.forEach { match ->
                val videoUrl = match.value
                if (videoUrl.contains(".mp4")) {
                    println("✅ Encontrado no HTML: $videoUrl")
                    
                    val quality = when {
                        videoUrl.contains("/fhd/") -> 1080
                        videoUrl.contains("/hd/") -> 720
                        else -> 480
                    }
                    
                    val qualityName = when (quality) {
                        1080 -> "fhd"
                        720 -> "hd"
                        else -> "sd"
                    }
                    
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = "$name ($qualityName)",
                            url = videoUrl,
                            referer = "$mainUrl/",
                            quality = quality,
                            headers = mapOf(
                                "Referer" to url,
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                            ),
                            type = ExtractorLinkType.VIDEO
                        )
                    )
                    
                    foundInHtml = true
                }
            }
            
            // Último recurso: tentar extrair de scripts JavaScript
            if (!foundInHtml) {
                println("🔄 Buscando em scripts JS...")
                
                val scripts = doc.select("script")
                for (script in scripts) {
                    val scriptContent = script.html()
                    if (scriptContent.contains("lightspeedst")) {
                        val jsRegex = Regex("""["'](https://lightspeedst\.net[^"']+)["']""")
                        val jsMatches = jsRegex.findAll(scriptContent)
                        
                        jsMatches.forEach { jsMatch ->
                            val jsUrl = jsMatch.groupValues[1]
                            if (jsUrl.contains(".mp4")) {
                                println("✅ Encontrado em JS: $jsUrl")
                                
                                callback.invoke(
                                    ExtractorLink(
                                        source = name,
                                        name = name,
                                        url = jsUrl,
                                        referer = "$mainUrl/",
                                        quality = 480,
                                        type = ExtractorLinkType.VIDEO
                                    )
                                )
                                return true
                            }
                        }
                    }
                }
            }
            
            foundInHtml
            
        } catch (e: Exception) {
            println("💥 AnimeFireExtractor: Erro - ${e.message}")
            e.printStackTrace()
            false
        }
    }
}
