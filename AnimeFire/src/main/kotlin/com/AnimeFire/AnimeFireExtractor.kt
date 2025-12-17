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
                val baseLinkPattern = """(https://lightspeedst\.net/s\d+/mp4/[^/]+)/[^/]+/(\d+)\.mp4""".toRegex()
                val matchResult = baseLinkPattern.find(intercepted)

                if (matchResult != null) {
                    val basePath = matchResult.groupValues[1]
                    val episodeNumber = matchResult.groupValues[2]
                    
                    println("✅ AnimeFireExtractor: Padrão base encontrado: $basePath")
                    println("🎬 Episódio: $episodeNumber")

                    // 3. ADICIONAR TODAS AS 3 QUALIDADES DIRETAMENTE (SEM VERIFICAR)
                    // Fazer assim: fhd (1080p) → hd (720p) → sd (480p)
                    var foundAny = false
                    
                    // Para CADA qualidade na lista
                    for (quality in qualities) {
                        val qualityUrl = "$basePath/$quality/$episodeNumber.mp4"
                        val qualityNum = when (quality) {
                            "fhd" -> 1080
                            "hd" -> 720
                            else -> 480 // sd
                        }
                        
                        val displayName = when (quality) {
                            "fhd" -> "1080p"
                            "hd" -> "720p"
                            else -> "480p"
                        }

                        println("🔄 AnimeFireExtractor: Adicionando qualidade $displayName: $qualityUrl")

                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "$name ($displayName)",
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
                        println("✅ AnimeFireExtractor: Qualidade $displayName adicionada")
                    }

                    foundAny
                } else {
                    // Fallback: se não encontrou o padrão, usa o link interceptado como está
                    println("⚠️ AnimeFireExtractor: Padrão não encontrado, usando link direto")
                    
                    // Tentar adivinhar qualidades mesmo sem padrão
                    val basePattern2 = """(https://lightspeedst\.net/s\d+/mp4/.+?)/(\d+)\.mp4""".toRegex()
                    val match2 = basePattern2.find(intercepted)
                    
                    if (match2 != null) {
                        val basePath = match2.groupValues[1]
                        val episodeNum = match2.groupValues[2]
                        
                        println("🔍 Padrão alternativo encontrado, gerando qualidades...")
                        
                        // Gerar as 3 qualidades
                        for (quality in qualities) {
                            val qualityUrl = "$basePath/$quality/$episodeNum.mp4"
                            val qualityNum = when (quality) {
                                "fhd" -> 1080
                                "hd" -> 720
                                else -> 480
                            }
                            
                            val displayName = when (quality) {
                                "fhd" -> "1080p"
                                "hd" -> "720p"
                                else -> "480p"
                            }
                            
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = "$name ($displayName)",
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
                        }
                        true
                    } else {
                        // Último fallback: link único
                        val quality = if (intercepted.contains("1080") || intercepted.contains("fhd")) {
                            1080
                        } else if (intercepted.contains("720") || intercepted.contains("hd")) {
                            720
                        } else {
                            480
                        }
                        
                        val displayName = when (quality) {
                            1080 -> "1080p"
                            720 -> "720p"
                            else -> "480p"
                        }

                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "$name ($displayName)",
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
                
                var foundInScripts = false

                for (script in scripts) {
                    // Procurar por padrões de URL do lightspeedst.net
                    val pattern = """(https://lightspeedst\.net/s\d+/mp4/[^/]+/[^/]+/(\d+)\.mp4)""".toRegex()
                    val matches = pattern.findAll(script)

                    matches.forEach { match ->
                        val foundUrl = match.value
                        println("✅ AnimeFireExtractor: Link encontrado no HTML: $foundUrl")
                        
                        // Tentar extrair base para gerar qualidades
                        val basePattern = """(https://lightspeedst\.net/s\d+/mp4/[^/]+)/[^/]+/(\d+)\.mp4""".toRegex()
                        val baseMatch = basePattern.find(foundUrl)
                        
                        if (baseMatch != null) {
                            val basePath = baseMatch.groupValues[1]
                            val episodeNum = baseMatch.groupValues[2]
                            
                            // GERAR AS 3 QUALIDADES
                            for (quality in qualities) {
                                val qualityUrl = "$basePath/$quality/$episodeNum.mp4"
                                val qualityNum = when (quality) {
                                    "fhd" -> 1080
                                    "hd" -> 720
                                    else -> 480
                                }
                                
                                val displayName = when (quality) {
                                    "fhd" -> "1080p"
                                    "hd" -> "720p"
                                    else -> "480p"
                                }
                                
                                callback.invoke(
                                    newExtractorLink(
                                        source = name,
                                        name = "$name ($displayName)",
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
                            }
                            foundInScripts = true
                            println("✅ AnimeFireExtractor: 3 qualidades geradas do HTML")
                        } else {
                            // Fallback: link único
                            val quality = when {
                                foundUrl.contains("/fhd/") -> 1080
                                foundUrl.contains("/hd/") -> 720
                                else -> 480
                            }
                            
                            val displayName = when (quality) {
                                1080 -> "1080p"
                                720 -> "720p"
                                else -> "480p"
                            }

                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = "$name ($displayName)",
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
                            foundInScripts = true
                        }
                    }
                }

                foundInScripts
            } else {
                success
            }

        } catch (e: Exception) {
            println("💥 AnimeFireExtractor: Erro - ${e.message}")
            false
        }
    }
}
