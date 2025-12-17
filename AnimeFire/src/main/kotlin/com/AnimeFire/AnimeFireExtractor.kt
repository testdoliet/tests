package com.AnimeFire

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.Jsoup
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

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
                
                // ============ PARTE MODIFICADA ============
                // Analisar QUALIDADE atual
                val currentQuality = when {
                    interceptedUrl.contains("/fhd/") -> "fhd"
                    interceptedUrl.contains("/hd/") -> "hd"
                    interceptedUrl.contains("/sd/") -> "sd"
                    else -> "sd" // padrão
                }
                
                println("🎯 Qualidade atual detectada: $currentQuality")
                
                // Extrair informações do link
                val basePattern = """(https://lightspeedst\.net/s\d+/mp4/[^/]+)/(sd|hd|fhd)/(\d+)\.mp4""".toRegex()
                val match = basePattern.find(interceptedUrl)
                
                if (match != null) {
                    val basePath = match.groupValues[1]  // https://lightspeedst.net/s4/mp4/haikyuu-dublado
                    val episodeNum = match.groupValues[3] // 1
                    
                    println("📁 Base: $basePath")
                    println("🎬 Episódio: $episodeNum")
                    
                    // QUALIDADES NA ORDEM DE PREFERÊNCIA
                    val qualities = listOf(
                        Pair("fhd", 1080),  // 1ª preferência
                        Pair("hd", 720),    // 2ª preferência  
                        Pair("sd", 480)     // 3ª preferência
                    )
                    
                    var foundAny = false
                    
                    for (qualityPair in qualities) {
                        val qualityName = qualityPair.first
                        val qualityValue = qualityPair.second
                        
                        // Se já estamos nessa qualidade, pular (já foi interceptada)
                        if (qualityName == currentQuality) {
                            // Adicionar a qualidade interceptada
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = "$name ($qualityName)",
                                    url = interceptedUrl,  // Usa a URL interceptada
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
                            println("✅ Qualidade $qualityName (interceptada)")
                            continue
                        }
                        
                        // Construir URL para outras qualidades
                        val videoUrl = "$basePath/$qualityName/$episodeNum.mp4"
                        
                        println("🔄 Tentando qualidade: $qualityName ($videoUrl)")
                        
                        // Verificar se o link existe (sem usar app.head() instável)
                        try {
                            // Usar GET com range request em vez de HEAD
                            val headers = mapOf(
                                "Range" to "bytes=0-1",
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                            )
                            
                            val test = app.get(videoUrl, headers = headers, timeout = 3000)
                            
                            if (test.code == 206 || test.code == 200) { // 206 = Partial Content, 200 = OK
                                println("✅ Qualidade $qualityName disponível!")
                                
                                callback.invoke(
                                    newExtractorLink(
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
                            } else {
                                println("❌ $qualityName não disponível (código: ${test.code})")
                            }
                        } catch (e: Exception) {
                            println("⚠️ $qualityName falhou: ${e.message}")
                        }
                    }
                    
                    if (foundAny) {
                        println("🎉 Todas as qualidades processadas!")
                        return true
                    }
                    
                } else {
                    // Se não encontrou o padrão, usar o link direto (fallback)
                    println("⚠️ Padrão não encontrado, usando fallback")
                    val quality = when {
                        interceptedUrl.contains("1080") || interceptedUrl.contains("fhd") -> Pair("fhd", 1080)
                        interceptedUrl.contains("720") || interceptedUrl.contains("hd") -> Pair("hd", 720)
                        else -> Pair("sd", 480)
                    }
                    
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name (${quality.first})",
                            url = interceptedUrl,
                            referer = "$mainUrl/",
                            quality = quality.second,
                            headers = mapOf(
                                "Referer" to url,
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                            ),
                            type = ExtractorLinkType.VIDEO
                        )
                    )
                    return true
                }
                // ============ FIM DA PARTE MODIFICADA ============
            }
            
            // Resto do seu código original (HTML fallback)
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
                        videoUrl.contains("/fhd/") -> Pair("fhd", 1080)
                        videoUrl.contains("/hd/") -> Pair("hd", 720)
                        else -> Pair("sd", 480)
                    }
                    
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name (${quality.first})",
                            url = videoUrl,
                            referer = "$mainUrl/",
                            quality = quality.second,
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
                                    newExtractorLink(
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
