package com.AnimeFire

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType

object AnimeFireExtractor {
    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🔗 AnimeFireExtractor: Extraindo de $url")

            // 1. Interceptar com WebView
            val streamResolver = WebViewResolver(
                interceptUrl = Regex("""lightspeedst\.net.*\.mp4"""),
                useOkhttp = false,
                timeout = 15_000L
            )

            val response = app.get(url, interceptor = streamResolver)
            val intercepted = response.url

            println("🌐 URL interceptada: $intercepted")

            if (intercepted.isNotEmpty() && intercepted.contains("lightspeedst.net") && intercepted.contains(".mp4")) {
                println("✅ Link válido interceptado")
                
                // 2. EXTRAIR INFORMAÇÕES CORRETAMENTE
                // Padrão: https://lightspeedst.net/s4/mp4/haikyuu-dublado/sd/1.mp4
                // Queremos: base = https://lightspeedst.net/s4/mp4/haikyuu-dublado
                //           episódio = 1
                
                // SOLUÇÃO SIMPLES: Usar replace para obter a base
                val basePath = if (intercepted.contains("/sd/")) {
                    intercepted.replace("/sd/", "/")
                } else if (intercepted.contains("/hd/")) {
                    intercepted.replace("/hd/", "/")
                } else if (intercepted.contains("/fhd/")) {
                    intercepted.replace("/fhd/", "/")
                } else {
                    // Tentar extrair com regex
                    val pattern = """(https://lightspeedst\.net/s\d+/mp4/[^/]+)/[^/]+""".toRegex()
                    val match = pattern.find(intercepted)
                    match?.groupValues?.get(1) ?: intercepted.substringBeforeLast("/")
                }
                
                // Extrair número do episódio
                val episodeNum = intercepted.substringAfterLast("/").replace(".mp4", "")
                
                println("📁 Base extraída: $basePath")
                println("🎬 Episódio: $episodeNum")
                
                // 3. GERAR AS 3 QUALIDADES DIRETAMENTE
                val qualities = listOf(
                    "fhd" to 1080,
                    "hd" to 720,
                    "sd" to 480
                )
                
                var addedCount = 0
                
                for ((qualityCode, qualityValue) in qualities) {
                    // Construir URL para esta qualidade
                    val videoUrl = "$basePath/$qualityCode/$episodeNum.mp4"
                    val displayName = when (qualityCode) {
                        "fhd" -> "1080p"
                        "hd" -> "720p"
                        else -> "480p"
                    }
                    
                    println("➕ Adicionando: $displayName - $videoUrl")
                    
                    // Usar newExtractorLink com lambda configuration
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name ($displayName)",
                            url = videoUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "$mainUrl/"
                            this.quality = qualityValue
                            this.headers = mapOf(
                                "Referer" to url,
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                            )
                        }
                    )
                    
                    addedCount++
                }
                
                println("🎉 $addedCount qualidades adicionadas!")
                return addedCount > 0
            }
            
            // Fallback: buscar no HTML
            println("🔄 Fallback: buscando no HTML...")
            
            val doc = app.get(url).document
            val html = doc.html()
            
            // Procurar link no HTML
            val pattern = Regex("""https://lightspeedst\.net/s\d+/mp4/[^"'\s]+\.mp4""")
            val match = pattern.find(html)
            
            if (match != null) {
                val foundUrl = match.value
                println("✅ Link encontrado no HTML: $foundUrl")
                
                // Tentar gerar qualidades do link do HTML também
                val htmlBase = if (foundUrl.contains("/sd/")) {
                    foundUrl.replace("/sd/", "/")
                } else if (foundUrl.contains("/hd/")) {
                    foundUrl.replace("/hd/", "/")
                } else if (foundUrl.contains("/fhd/")) {
                    foundUrl.replace("/fhd/", "/")
                } else {
                    foundUrl.substringBeforeLast("/")
                }
                
                val htmlEpisode = foundUrl.substringAfterLast("/").replace(".mp4", "")
                
                val htmlQualities = listOf(
                    "fhd" to 1080,
                    "hd" to 720,
                    "sd" to 480
                )
                
                for ((qualityCode, qualityValue) in htmlQualities) {
                    val videoUrl = "$htmlBase/$qualityCode/$htmlEpisode.mp4"
                    val displayName = when (qualityCode) {
                        "fhd" -> "1080p"
                        "hd" -> "720p"
                        else -> "480p"
                    }
                    
                    println("➕ HTML: Adicionando $displayName")
                    
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name ($displayName)",
                            url = videoUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "$mainUrl/"
                            this.quality = qualityValue
                        }
                    )
                }
                
                return true
            }
            
            println("❌ Nenhum link encontrado")
            false
            
        } catch (e: Exception) {
            println("💥 Erro: ${e.message}")
            false
        }
    }
}
