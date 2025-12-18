package com.AnimeFire

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup

object AnimeFireExtractor {
    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🔗 AnimeFireExtractor: Extraindo de $url")

            // 1. Primeiro, pegar o HTML da página
            val pageResponse = app.get(url)
            val document = Jsoup.parse(pageResponse.text)
            
            // 2. Encontrar o menu de qualidades
            val videoQualities = document.selectFirst("#video-qualities")
            
            var foundAny = false
            
            // 3. Se encontrou o menu de qualidades
            if (videoQualities != null) {
                println("✅ AnimeFireExtractor: Menu de qualidades encontrado!")
                
                // Encontrar todas as opções de qualidade
                val qualityElements = videoQualities.select("div.video-ql")
                
                println("📊 AnimeFireExtractor: ${qualityElements.size} qualidades disponíveis")
                
                // 4. Interceptar o link base do vídeo
                val streamResolver = WebViewResolver(
                    interceptUrl = Regex("""lightspeedst\.net.*\.mp4(?:\?|$)"""),
                    useOkhttp = false,
                    timeout = 10_000L
                )

                val response = app.get(url, interceptor = streamResolver)
                val intercepted = response.url

                if (intercepted.isNotEmpty() && intercepted.contains("lightspeedst.net")) {
                    println("🌐 AnimeFireExtractor: URL interceptada: $intercepted")
                    
                    // 5. Extrair o padrão base
                    val baseLinkPattern = """(https://lightspeedst\.net/s\d+/[^/]+)/(\d+)/([^/]+)\.mp4""".toRegex()
                    val matchResult = baseLinkPattern.find(intercepted)
                    
                    if (matchResult != null) {
                        val basePath = matchResult.groupValues[1]
                        val episodeNumber = matchResult.groupValues[2]
                        
                        println("✅ AnimeFireExtractor: Base path: $basePath")
                        println("✅ AnimeFireExtractor: Episódio: $episodeNumber")
                        
                        // 6. Para cada qualidade no HTML, criar o link
                        for (qualityElement in qualityElements) {
                            val qualityText = qualityElement.text().trim()
                            
                            // Extrair a resolução do texto (ex: "1080p" -> "1080")
                            val resolutionMatch = """(\d+)p""".toRegex().find(qualityText)
                            val resolution = resolutionMatch?.groupValues?.get(1) ?: "480"
                            
                            // Determinar qualidade numérica
                            val qualityNum = when (resolution) {
                                "1080" -> 1080
                                "720" -> 720
                                "480" -> 480
                                "360" -> 360
                                else -> resolution.toIntOrNull() ?: 480
                            }
                            
                            // Construir o URL da qualidade
                            val qualityUrl = "$basePath/$episodeNumber/${resolution}p.mp4"
                            
                            println("✅ AnimeFireExtractor: Adicionando $qualityText: $qualityUrl")
                            
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = "$name ($qualityText)",
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
                        }
                    } else {
                        // Fallback: se não encontrou o padrão, usa só o link interceptado
                        println("⚠️ AnimeFireExtractor: Padrão não encontrado, usando link interceptado")
                        
                        val quality = when {
                            intercepted.contains("1080") -> 1080
                            intercepted.contains("720") -> 720
                            intercepted.contains("480") -> 480
                            else -> 360
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
                        foundAny = true
                    }
                } else {
                    println("❌ AnimeFireExtractor: Nenhum link lightspeedst.net interceptado")
                }
            } else {
                // Fallback: método antigo se não encontrar o menu de qualidade
                println("⚠️ AnimeFireExtractor: Menu de qualidade não encontrado, usando método de interceptação")
                
                val streamResolver = WebViewResolver(
                    interceptUrl = Regex("""lightspeedst\.net.*\.mp4(?:\?|$)"""),
                    useOkhttp = false,
                    timeout = 10_000L
                )

                val response = app.get(url, interceptor = streamResolver)
                val intercepted = response.url

                if (intercepted.isNotEmpty() && intercepted.contains("lightspeedst.net")) {
                    val quality = when {
                        intercepted.contains("1080") -> 1080
                        intercepted.contains("720") -> 720
                        intercepted.contains("480") -> 480
                        else -> 360
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
                    foundAny = true
                }
            }

            if (foundAny) {
                println("🎉 AnimeFireExtractor: Extração concluída com sucesso!")
            } else {
                println("❌ AnimeFireExtractor: Nenhum link encontrado")
            }

            foundAny

        } catch (e: Exception) {
            println("💥 AnimeFireExtractor: Erro - ${e.message}")
            e.printStackTrace()
            false
        }
    }
}
