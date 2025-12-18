package com.AnimeFire

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
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

            // 1. Primeiro, pegar o HTML da página para analisar o player
            val pageResponse = app.get(url)
            val document = Jsoup.parse(pageResponse.text)
            
            // 2. Procurar o elemento de qualidade no HTML
            val qualityMenu = document.selectFirst("#quality-menu")
            val videoQualities = document.selectFirst("#video-qualities")
            
            var foundAny = false
            
            // 3. Se encontrou o menu de qualidades, extrair os links das qualidades
            if (videoQualities != null) {
                println("✅ AnimeFireExtractor: Menu de qualidades encontrado no HTML")
                
                // Encontrar todas as opções de qualidade disponíveis
                val qualityElements = videoQualities.select("div.video-ql")
                
                println("📊 AnimeFireExtractor: ${qualityElements.size} qualidades encontradas")
                
                // Primeiro, precisamos interceptar o link base do vídeo
                val streamResolver = WebViewResolver(
                    interceptUrl = Regex("""lightspeedst\.net.*\.mp4(?:\?|$)"""),
                    useOkhttp = false,
                    timeout = 15_000L
                )

                val response = app.get(url, interceptor = streamResolver)
                val intercepted = response.url

                if (intercepted.isNotEmpty() && intercepted.contains("lightspeedst.net")) {
                    println("🌐 AnimeFireExtractor: URL interceptada: $intercepted")
                    
                    // 4. Para cada qualidade encontrada, criar o link correspondente
                    for (qualityElement in qualityElements) {
                        val qualityText = qualityElement.text().trim()
                        val qualityId = qualityElement.attr("id")
                        
                        println("🔍 AnimeFireExtractor: Processando qualidade: $qualityText (ID: $qualityId)")
                        
                        // Determinar o número da qualidade
                        val qualityNum = when {
                            qualityText.contains("1080") -> 1080
                            qualityText.contains("720") -> 720
                            qualityText.contains("480") -> 480
                            qualityText.contains("360") -> 360
                            qualityText.contains("240") -> 240
                            else -> 480
                        }
                        
                        // 5. Gerar o link da qualidade baseado no padrão
                        val baseLinkPattern = """(https://lightspeedst\.net/s\d+/mp4_temp/[^/]+)/(\d+)/([^/]+)\.mp4""".toRegex()
                        val matchResult = baseLinkPattern.find(intercepted)
                        
                        if (matchResult != null) {
                            val basePath = matchResult.groupValues[1]
                            val episodeNumber = matchResult.groupValues[2]
                            val currentQuality = matchResult.groupValues[3]
                            
                            // Extrair a resolução do texto da qualidade (ex: "1080p" -> "1080")
                            val resolutionMatch = """(\d+)p""".toRegex().find(qualityText)
                            val targetQuality = resolutionMatch?.groupValues?.get(1) ?: "480"
                            
                            // Construir o URL da qualidade
                            val qualityUrl = "$basePath/$episodeNumber/${targetQuality}p.mp4"
                            
                            println("🔄 AnimeFireExtractor: Gerando link para qualidade $qualityText: $qualityUrl")
                            
                            // 6. Verificar se o link existe
                            val works = try {
                                val testResponse = app.head(qualityUrl, timeout = 3000L)
                                testResponse.code == 200
                            } catch (e: Exception) {
                                false
                            }
                            
                            if (works) {
                                println("✅ AnimeFireExtractor: Qualidade $qualityText funciona, adicionando...")
                                
                                callback.invoke(
                                    newExtractorLink(
                                        source = name,
                                        name = name,
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
                            } else {
                                println("❌ AnimeFireExtractor: Qualidade $qualityText não disponível")
                            }
                            
                            // Pequena pausa entre verificações
                            delay(500)
                        }
                    }
                    
                    // 7. Se não encontrou nenhuma qualidade, adiciona pelo menos o link interceptado
                    if (!foundAny && intercepted.isNotEmpty()) {
                        println("⚠️ AnimeFireExtractor: Nenhuma qualidade verificada, usando link interceptado")
                        
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
            } else {
                // Fallback: método antigo se não encontrar o menu de qualidade
                println("⚠️ AnimeFireExtractor: Menu de qualidade não encontrado, usando método antigo")
                
                val streamResolver = WebViewResolver(
                    interceptUrl = Regex("""lightspeedst\.net.*\.mp4(?:\?|$)"""),
                    useOkhttp = false,
                    timeout = 15_000L
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

            foundAny

        } catch (e: Exception) {
            println("💥 AnimeFireExtractor: Erro - ${e.message}")
            e.printStackTrace()
            false
        }
    }
}
