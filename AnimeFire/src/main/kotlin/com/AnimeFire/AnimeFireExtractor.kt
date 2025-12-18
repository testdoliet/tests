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

            // 1. Interceptar a URL do vídeo
            val streamResolver = WebViewResolver(
                interceptUrl = Regex("""lightspeedst\.net.*\.mp4(?:\?|$)"""),
                useOkhttp = false,
                timeout = 15_000L
            )

            val response = app.get(url, interceptor = streamResolver)
            val intercepted = response.url

            println("🌐 AnimeFireExtractor: URL interceptada: $intercepted")

            val success = if (intercepted.isNotEmpty() && intercepted.contains("lightspeedst.net")) {
                // 2. Encontrar o padrão base do link CORRETO
                val baseLinkPattern = """(https://lightspeedst\.net/s\d+/mp4_temp/[^/]+)/(\d+)/([^/]+)\.mp4""".toRegex()
                val matchResult = baseLinkPattern.find(intercepted)

                if (matchResult != null) {
                    val basePath = matchResult.groupValues[1]
                    val episodeNumber = matchResult.groupValues[2]
                    val foundQuality = matchResult.groupValues[3]
                    
                    println("✅ AnimeFireExtractor: Padrão base encontrado")
                    println("✅ AnimeFireExtractor: Episódio: $episodeNumber")
                    println("✅ AnimeFireExtractor: Qualidade encontrada: $foundQuality")

                    var foundAny = false

                    // 3. Adicionar a qualidade interceptada (sempre funciona)
                    val qualityNum = when {
                        foundQuality.contains("1080") -> 1080
                        foundQuality.contains("720") -> 720
                        else -> 480
                    }

                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = name,
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
                    println("✅ AnimeFireExtractor: Qualidade $foundQuality adicionada")

                    // 4. Gerar e TESTAR outras qualidades
                    val qualitiesToTest = listOf("1080p", "720p").filter { it != foundQuality }
                    
                    for (quality in qualitiesToTest) {
                        val qualityUrl = "$basePath/$episodeNumber/$quality.mp4"
                        val testQualityNum = when {
                            quality.contains("1080") -> 1080
                            quality.contains("720") -> 720
                            else -> 480
                        }

                        println("🔍 AnimeFireExtractor: Testando qualidade $quality...")
                        
                        // VERIFICAÇÃO SIMPLES: tentar fazer uma requisição HEAD
                        val works = try {
                            // Timeout baixo para não demorar
                            val testResponse = app.head(qualityUrl, timeout = 3000L)
                            testResponse.code == 200
                        } catch (e: Exception) {
                            false
                        }
                        
                        if (works) {
                            println("✅ AnimeFireExtractor: Qualidade $quality funciona, adicionando...")
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = name,
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
                        } else {
                            println("❌ AnimeFireExtractor: Qualidade $quality não disponível")
                        }
                        
                        // Pequena pausa entre testes
                        delay(500)
                    }

                    foundAny
                } else {
                    // Fallback: se não encontrou o padrão, usa só o link interceptado
                    println("⚠️ AnimeFireExtractor: Padrão não encontrado, usando link direto")
                    val quality = when {
                        intercepted.contains("1080") -> 1080
                        intercepted.contains("720") -> 720
                        else -> 480
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

            success

        } catch (e: Exception) {
            println("💥 AnimeFireExtractor: Erro - ${e.message}")
            false
        }
    }
}
