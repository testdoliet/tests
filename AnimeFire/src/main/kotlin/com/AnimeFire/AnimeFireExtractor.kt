package com.AnimeFire

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
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
            
            // 1. Primeiro, tenta o método tradicional com WebView (para capturar o link base)
            val streamResolver = WebViewResolver(
                interceptUrl = Regex("""lightspeedst\.net.*\.mp4(?:\?|$)"""),
                useOkhttp = false,
                timeout = 15_000L
            )
            
            val response = app.get(url, interceptor = streamResolver)
            val intercepted = response.url
            
            println("🌐 AnimeFireExtractor: URL interceptada: $intercepted")
            
            // Lista de qualidades na ordem de preferência (da maior para a menor)
            val qualities = listOf("fhd", "hd", "sd")
            
            val success = if (intercepted.isNotEmpty() && intercepted.contains("lightspeedst.net")) {
                // 2. Encontrar o padrão base do link
                val baseLinkPattern = """(https://lightspeedst\.net/s\d+/mp4/[^/]+)/[^/]+/\d+\.mp4""".toRegex()
                val matchResult = baseLinkPattern.find(intercepted)
                
                if (matchResult != null) {
                    val basePath = matchResult.groupValues[1]
                    println("✅ AnimeFireExtractor: Padrão base encontrado: $basePath")
                    
                    // 3. Gerar links para todas as qualidades
                    var foundAny = false
                    
                    // Primeiro, adiciona a qualidade interceptada (se for uma das nossas)
                    for (quality in qualities) {
                        if (intercepted.contains("/$quality/")) {
                            val qualityNum = when (quality) {
                                "fhd" -> 1080
                                "hd" -> 720
                                "sd" -> 480
                                else -> 480
                            }
                            
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = "$name ($quality)",
                                    url = intercepted,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = "$mainUrl/"
                                    this.quality = qualityNum
                                    this.headers = mapOf(
                                        "Referer" to url,
                                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                                    )
                                }
                            )
                            foundAny = true
                            println("✅ AnimeFireExtractor: Qualidade $quality adicionada")
                            break
                        }
                    }
                    
                    // 4. Gerar as outras qualidades dinamicamente
                    // Encontrar o número do episódio
                    val episodePattern = """/(\d+)\.mp4$""".toRegex()
                    val episodeMatch = episodePattern.find(intercepted)
                    val episodeNumber = episodeMatch?.groupValues?.get(1) ?: "1"
                    
                    for (quality in qualities) {
                        // Pular se já adicionamos esta qualidade
                        if (intercepted.contains("/$quality/")) continue
                        
                        val qualityUrl = "$basePath/$quality/$episodeNumber.mp4"
                        val qualityNum = when (quality) {
                            "fhd" -> 1080
                            "hd" -> 720
                            "sd" -> 480
                            else -> 480
                        }
                        
                        println("🔄 AnimeFireExtractor: Testando qualidade $quality: $qualityUrl")
                        
                        // Verificar se o link existe (opcional)
                        try {
                            val testResponse = app.head(qualityUrl, timeout = 3000L)
                            if (testResponse.code == 200) {
                                callback.invoke(
                                    newExtractorLink(
                                        source = name,
                                        name = "$name ($quality)",
                                        url = qualityUrl,
                                        type = ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = "$mainUrl/"
                                        this.quality = qualityNum
                                        this.headers = mapOf(
                                            "Referer" to url,
                                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                                        )
                                    }
                                )
                                foundAny = true
                                println("✅ AnimeFireExtractor: Qualidade $quality adicionada")
                            }
                        } catch (e: Exception) {
                            println("⚠️ AnimeFireExtractor: Qualidade $quality não disponível")
                        }
                    }
                    
                    foundAny
                } else {
                    // Fallback: se não encontrou o padrão, usa o link interceptado como está
                    println("⚠️ AnimeFireExtractor: Padrão não encontrado, usando link direto")
                    val quality = if (intercepted.contains("1080") || intercepted.contains("fhd")) {
                        1080
                    } else if (intercepted.contains("720") || intercepted.contains("hd")) {
                        720
                    } else {
                        480
                    }
                    
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = intercepted,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "$mainUrl/"
                            this.quality = quality
                            this.headers = mapOf(
                                "Referer" to url,
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                            )
                        }
                    )
                    true
                }
            } else {
                println("❌ AnimeFireExtractor: Nenhum link lightspeedst.net encontrado")
                false
            }
            
            // 5. Se ainda não encontrou nada, tentar um método alternativo
            if (!success) {
                println("🔄 AnimeFireExtractor: Tentando método alternativo...")
                // Tentar buscar no HTML da página
                val doc = app.get(url).document
                val scripts = doc.select("script").map { it.html() }
                
                for (script in scripts) {
                    // Procurar por padrões de URL do lightspeedst.net
                    val pattern = """(https://lightspeedst\.net/s\d+/mp4/[^/]+/[^/]+/\d+\.mp4)""".toRegex()
                    val matches = pattern.findAll(script)
                    
                    matches.forEach { match ->
                        val foundUrl = match.value
                        println("✅ AnimeFireExtractor: Link encontrado no HTML: $foundUrl")
                        
                        val quality = when {
                            foundUrl.contains("/fhd/") -> 1080
                            foundUrl.contains("/hd/") -> 720
                            else -> 480
                        }
                        
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "$name (HTML)",
                                url = foundUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "$mainUrl/"
                                this.quality = quality
                                this.headers = mapOf(
                                    "Referer" to url,
                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                                )
                            }
                        )
                    }
                }
                
                !scripts.isEmpty()
            } else {
                success
            }
            
        } catch (e: Exception) {
            println("💥 AnimeFireExtractor: Erro - ${e.message}")
            false
        }
    }
}
