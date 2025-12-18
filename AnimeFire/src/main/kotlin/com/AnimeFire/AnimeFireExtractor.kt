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
                
                // 2. ANALISAR A ESTRUTURA REAL DA URL:
                // Exemplo: https://lightspeedst.net/s5/mp4_temp/let-s-play-quest-darake-no-my-life/1/480p.mp4
                // Padrão: https://lightspeedst.net/sX/mp4_temp/nome-anime/episodio/QUALIDADE.mp4
                
                val pattern = """https://lightspeedst\.net/s\d+/mp4_temp/([^/]+)/(\d+)/(\d+p)\.mp4""".toRegex()
                val match = pattern.find(intercepted)
                
                if (match != null) {
                    val animeName = match.groupValues[1] // let-s-play-quest-darake-no-my-life
                    val episodeNum = match.groupValues[2] // 1
                    val interceptedQuality = match.groupValues[3] // 480p
                    
                    println("📊 Estrutura detectada:")
                    println("   Anime: $animeName")
                    println("   Episódio: $episodeNum")
                    println("   Qualidade interceptada: $interceptedQuality")
                    
                    // 3. CONSTRUIR BASE CORRETA:
                    // Base: https://lightspeedst.net/s5/mp4_temp/let-s-play-quest-darake-no-my-life/1
                    val baseUrl = "https://lightspeedst.net/s${match.value.substringAfter("s").take(1)}/mp4_temp/$animeName/$episodeNum"
                    println("📁 Base correta: $baseUrl")
                    
                    // 4. GERAR AS 3 QUALIDADES NA ESTRUTURA CORRETA:
                    // Formato: https://lightspeedst.net/s5/mp4_temp/nome-anime/1/480p.mp4
                    //           https://lightspeedst.net/s5/mp4_temp/nome-anime/1/720p.mp4  
                    //           https://lightspeedst.net/s5/mp4_temp/nome-anime/1/1080p.mp4
                    
                    val qualities = listOf(
                        "1080p" to 1080,
                        "720p" to 720,
                        "480p" to 480
                    )
                    
                    var addedCount = 0
                    
                    for ((qualityName, qualityValue) in qualities) {
                        // URL correta para esta qualidade
                        val videoUrl = "$baseUrl/$qualityName.mp4"
                        
                        println("➕ Gerando: $qualityName")
                        println("   URL: $videoUrl")
                        
                        // Adicionar ao callback
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "$name ($qualityName)",
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
                        println("✅ Qualidade $qualityName adicionada")
                    }
                    
                    println("🎉 $addedCount qualidades geradas!")
                    return addedCount > 0
                    
                } else {
                    // Tentar outro padrão alternativo
                    println("🔄 Tentando padrão alternativo...")
                    
                    // Padrão alternativo: talvez tenha estrutura diferente
                    val altPattern = """https://lightspeedst\.net/s\d+/([^/]+)/([^/]+)/(\d+)/([^/]+)\.mp4""".toRegex()
                    val altMatch = altPattern.find(intercepted)
                    
                    if (altMatch != null) {
                        val folder = altMatch.groupValues[1] // mp4_temp
                        val animeName = altMatch.groupValues[2] // let-s-play-quest-darake-no-my-life
                        val episodeNum = altMatch.groupValues[3] // 1
                        val quality = altMatch.groupValues[4] // 480p
                        
                        println("✅ Padrão alternativo encontrado:")
                        println("   Pasta: $folder")
                        println("   Anime: $animeName")
                        println("   Episódio: $episodeNum")
                        println("   Qualidade: $quality")
                        
                        // Construir base
                        val serverNum = intercepted.substringAfter("s").take(1)
                        val baseUrl = "https://lightspeedst.net/s$serverNum/$folder/$animeName/$episodeNum"
                        
                        // Gerar qualidades
                        val qualityOptions = listOf("1080p", "720p", "480p")
                        
                        for (qualityOption in qualityOptions) {
                            val videoUrl = "$baseUrl/$qualityOption.mp4"
                            
                            val qualityValue = when (qualityOption) {
                                "1080p" -> 1080
                                "720p" -> 720
                                else -> 480
                            }
                            
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = "$name ($qualityOption)",
                                    url = videoUrl,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = "$mainUrl/"
                                    this.quality = qualityValue
                                }
                            )
                        }
                        
                        println("✅ 3 qualidades geradas (padrão alternativo)")
                        return true
                    }
                    
                    println("❌ Nenhum padrão reconhecido")
                    return false
                }
            }
            
            // Fallback para HTML
            println("🔄 Fallback: buscando no HTML...")
            return extractFromHtmlFallback(url, mainUrl, name, callback)
            
        } catch (e: Exception) {
            println("💥 Erro: ${e.message}")
            false
        }
    }
    
    private suspend fun extractFromHtmlFallback(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val doc = app.get(url).document
            val html = doc.html()
            
            // Procurar no HTML
            val pattern = Regex("""https://lightspeedst\.net/s\d+/[^"'\s]+\.mp4""")
            val match = pattern.find(html)
            
            if (match != null) {
                val foundUrl = match.value
                println("✅ Link encontrado no HTML: $foundUrl")
                
                // Tentar analisar
                val urlPattern = """https://lightspeedst\.net/s\d+/([^/]+)/([^/]+)/(\d+)/(\d+p)\.mp4""".toRegex()
                val urlMatch = urlPattern.find(foundUrl)
                
                if (urlMatch != null) {
                    val folder = urlMatch.groupValues[1]
                    val animeName = urlMatch.groupValues[2]
                    val episodeNum = urlMatch.groupValues[3]
                    
                    val serverNum = foundUrl.substringAfter("s").take(1)
                    val baseUrl = "https://lightspeedst.net/s$serverNum/$folder/$animeName/$episodeNum"
                    
                    // Gerar 3 qualidades
                    listOf("1080p", "720p", "480p").forEach { qualityName ->
                        val videoUrl = "$baseUrl/$qualityName.mp4"
                        val qualityValue = when (qualityName) {
                            "1080p" -> 1080
                            "720p" -> 720
                            else -> 480
                        }
                        
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "$name ($qualityName)",
                                url = videoUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "$mainUrl/"
                                this.quality = qualityValue
                            }
                        )
                    }
                    
                    println("✅ 3 qualidades geradas do HTML")
                    return true
                }
            }
            
            println("❌ Nada encontrado no HTML")
            false
            
        } catch (e: Exception) {
            println("⚠️ Erro HTML: ${e.message}")
            false
        }
    }
}
