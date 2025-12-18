package com.AnimeFire

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.delay

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

            val success = if (intercepted.isNotEmpty() && intercepted.contains("lightspeedst.net")) {
                // 2. Encontrar o padrão base do link - PADRÃO CORRIGIDO!
                // OLD: (https://lightspeedst\.net/s\d+/mp4/[^/]+)/[^/]+/\d+\.mp4
                // NEW: (https://lightspeedst\.net/s\d+/mp4_temp/[^/]+)/(\d+)/[^/]+\.mp4
                val baseLinkPattern = """(https://lightspeedst\.net/s\d+/mp4_temp/[^/]+)/(\d+)/([^/]+)\.mp4""".toRegex()
                val matchResult = baseLinkPattern.find(intercepted)

                if (matchResult != null) {
                    val basePath = matchResult.groupValues[1]
                    val episodeNumber = matchResult.groupValues[2]
                    val foundQuality = matchResult.groupValues[3]
                    
                    println("✅ AnimeFireExtractor: Padrão base encontrado: $basePath")
                    println("✅ AnimeFireExtractor: Episódio: $episodeNumber")
                    println("✅ AnimeFireExtractor: Qualidade encontrada: $foundQuality")

                    // 3. Gerar links para TODAS as qualidades SEM VERIFICAR
                    var foundAny = false

                    // Primeiro, adiciona a qualidade interceptada
                    val qualityNum = when {
                        foundQuality.contains("1080") -> 1080
                        foundQuality.contains("720") -> 720
                        foundQuality.contains("480") -> 480
                        foundQuality.contains("360") -> 360
                        else -> 480
                    }

                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name (${if (qualityNum == 1080) "1080p" else if (qualityNum == 720) "720p" else "480p"})",
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
                    println("✅ AnimeFireExtractor: Qualidade interceptada adicionada")

                    // 4. AGORA - Adicionar todas as outras qualidades SEM TESTAR
                    val qualitiesToAdd = listOf("1080p", "720p", "480p", "360p").filter { it != foundQuality }
                    
                    for (quality in qualitiesToAdd) {
                        val qualityUrl = "$basePath/$episodeNumber/$quality.mp4"
                        val testQualityNum = when {
                            quality.contains("1080") -> 1080
                            quality.contains("720") -> 720
                            quality.contains("480") -> 480
                            quality.contains("360") -> 360
                            else -> 480
                        }

                        println("➕ AnimeFireExtractor: Adicionando qualidade $quality: $qualityUrl")

                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "$name ($quality)",
                                url = qualityUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "$mainUrl/"
                                this.quality = testQualityNum
                                this.headers = mapOf(
                                    "Referer" to url,
                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                                )
                            }
                        )
                        foundAny = true
                        println("✅ AnimeFireExtractor: Qualidade $quality adicionada")
                        
                        // Pequena pausa entre adições
                        delay(100)
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
                    // Procurar por padrões de URL do lightspeedst.net - PADRÃO CORRIGIDO!
                    val pattern = """(https://lightspeedst\.net/s\d+/mp4_temp/[^/]+/\d+/[^/]+\.mp4)""".toRegex()
                    val matches = pattern.findAll(script)

                    matches.forEach { match ->
                        val foundUrl = match.value
                        println("✅ AnimeFireExtractor: Link encontrado no HTML: $foundUrl")

                        val quality = when {
                            foundUrl.contains("1080") -> 1080
                            foundUrl.contains("720") -> 720
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
