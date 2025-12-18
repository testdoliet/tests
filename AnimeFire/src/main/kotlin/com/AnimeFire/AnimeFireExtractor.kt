package com.AnimeFire

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
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
                // 2. Encontrar o padrão base do link
                val baseLinkPattern = """(https://lightspeedst\.net/s\d+/mp4/[^/]+)/[^/]+/(\d+)\.mp4""".toRegex()
                val matchResult = baseLinkPattern.find(intercepted)

                if (matchResult != null) {
                    val basePath = matchResult.groupValues[1]
                    val episodeNumber = matchResult.groupValues[2]
                    
                    println("✅ AnimeFireExtractor: Padrão base encontrado: $basePath")
                    println("✅ AnimeFireExtractor: Episódio: $episodeNumber")

                    // 3. Lista de qualidades na ordem de preferência (da maior para a menor)
                    val qualities = listOf(
                        Triple("fhd", 1080),
                        Triple("hd", 720),
                        Triple("sd", 480)
                    )

                    var foundAny = false

                    // 4. Testar TODAS as qualidades, começando pela interceptada
                    // Primeiro, adiciona a qualidade interceptada
                    for ((qualityName, qualityValue) in qualities) {
                        if (intercepted.contains("/$qualityName/")) {
                            addQualityLink(
                                intercepted, 
                                name, 
                                mainUrl, 
                                url, 
                                qualityName, 
                                qualityValue, 
                                callback
                            )
                            foundAny = true
                            println("✅ AnimeFireExtractor: Qualidade interceptada $qualityName adicionada")
                            break
                        }
                    }

                    // 5. AGORA - Testar as outras qualidades mesmo se já encontrou uma
                    for ((qualityName, qualityValue) in qualities) {
                        // Pular se já adicionamos esta qualidade
                        if (intercepted.contains("/$qualityName/")) continue

                        val qualityUrl = "$basePath/$qualityName/$episodeNumber.mp4"
                        
                        println("🔄 AnimeFireExtractor: Testando qualidade $qualityName: $qualityUrl")

                        // Verificar se o link existe ANTES de adicionar
                        try {
                            val testResponse = app.head(qualityUrl, timeout = 5000L)
                            if (testResponse.code == 200) {
                                addQualityLink(
                                    qualityUrl, 
                                    name, 
                                    mainUrl, 
                                    url, 
                                    qualityName, 
                                    qualityValue, 
                                    callback
                                )
                                foundAny = true
                                println("✅ AnimeFireExtractor: Qualidade $qualityName adicionada")
                            } else {
                                println("❌ AnimeFireExtractor: Qualidade $qualityName não disponível (HTTP ${testResponse.code})")
                            }
                        } catch (e: Exception) {
                            println("⚠️ AnimeFireExtractor: Qualidade $qualityName não acessível: ${e.message}")
                        }
                        
                        // Pequena pausa entre requisições
                        delay(300)
                    }

                    foundAny
                } else {
                    // Fallback: se não encontrou o padrão, usa o link interceptado como está
                    println("⚠️ AnimeFireExtractor: Padrão não encontrado, usando link direto")
                    addSingleQualityLink(intercepted, name, mainUrl, url, callback)
                    true
                }
            } else {
                println("❌ AnimeFireExtractor: Nenhum link lightspeedst.net encontrado")
                false
            }

            // 6. Se ainda não encontrou nada, tentar um método alternativo
            if (!success) {
                println("🔄 AnimeFireExtractor: Tentando método alternativo...")
                return tryAlternativeMethod(url, mainUrl, name, callback)
            }

            success

        } catch (e: Exception) {
            println("💥 AnimeFireExtractor: Erro - ${e.message}")
            false
        }
    }

    private suspend fun addQualityLink(
        videoUrl: String,
        name: String,
        mainUrl: String,
        referer: String,
        qualityName: String,
        qualityValue: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val qualityDisplay = when (qualityValue) {
                1080 -> "1080p"
                720 -> "720p"
                else -> "480p"
            }
            
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name ($qualityDisplay)",
                    url = videoUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = qualityValue
                    this.headers = mapOf(
                        "Referer" to referer,
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                        "Accept" to "video/mp4,video/*;q=0.9,*/*;q=0.8"
                    )
                }
            )
        } catch (e: Exception) {
            println("⚠️ AnimeFireExtractor: Erro ao adicionar qualidade $qualityName: ${e.message}")
        }
    }

    private suspend fun addSingleQualityLink(
        videoUrl: String,
        name: String,
        mainUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val quality = when {
                videoUrl.contains("/fhd/") || videoUrl.contains("1080") -> 1080
                videoUrl.contains("/hd/") || videoUrl.contains("720") -> 720
                else -> 480
            }
            
            val qualityDisplay = when (quality) {
                1080 -> "1080p"
                720 -> "720p"
                else -> "480p"
            }
            
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name ($qualityDisplay)",
                    url = videoUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = quality
                    this.headers = mapOf(
                        "Referer" to referer,
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                    )
                }
            )
            println("✅ AnimeFireExtractor: Única qualidade $qualityDisplay adicionada")
        } catch (e: Exception) {
            println("❌ AnimeFireExtractor: Erro ao adicionar qualidade única: ${e.message}")
        }
    }

    private suspend fun tryAlternativeMethod(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🔍 AnimeFireExtractor: Método alternativo - analisando HTML...")
            val doc = app.get(url).document
            
            // Buscar todos os scripts
            val scripts = doc.select("script")
            var foundAny = false
            
            val pattern = """(https://lightspeedst\.net/s\d+/mp4/[^/]+/[^/]+/\d+\.mp4)""".toRegex()
            
            for (script in scripts) {
                val scriptContent = script.html()
                if (scriptContent.contains("lightspeedst.net")) {
                    val matches = pattern.findAll(scriptContent)

                    matches.forEach { match ->
                        val foundUrl = match.value
                        println("✅ AnimeFireExtractor: Link encontrado no HTML: $foundUrl")

                        val quality = when {
                            foundUrl.contains("/fhd/") -> 1080
                            foundUrl.contains("/hd/") -> 720
                            else -> 480
                        }
                        
                        val qualityDisplay = when (quality) {
                            1080 -> "1080p"
                            720 -> "720p"
                            else -> "480p"
                        }

                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "$name ($qualityDisplay)",
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
                        foundAny = true
                    }
                }
            }

            foundAny
        } catch (e: Exception) {
            println("❌ AnimeFireExtractor: Método alternativo falhou: ${e.message}")
            false
        }
    }
}
