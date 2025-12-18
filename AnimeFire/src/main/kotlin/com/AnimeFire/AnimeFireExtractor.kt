package com.AnimeFire

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

object AnimeFireExtractor {
    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🔗 AnimeFireExtractor: Extraindo de $url")

            // Vamos tentar capturar QUALQUER link de vídeo primeiro
            val firstUrl = captureFirstVideoUrl(url)
            
            if (firstUrl.isEmpty()) {
                println("❌ AnimeFireExtractor: Não conseguiu capturar nenhum link")
                return false
            }
            
            println("✅ AnimeFireExtractor: Primeira URL capturada: ${firstUrl.take(80)}...")
            
            // Agora extrair o padrão base para gerar outras qualidades
            val (basePath, episodeNumber) = extractBasePattern(firstUrl)
            
            if (basePath.isEmpty()) {
                // Fallback: usar apenas o link que encontrou
                addSingleQualityLink(firstUrl, name, mainUrl, url, callback)
                return true
            }
            
            println("🎯 AnimeFireExtractor: Base Path: $basePath")
            println("🎯 AnimeFireExtractor: Episódio: $episodeNumber")
            
            // Lista de qualidades para tentar (da melhor para a pior)
            val qualities = listOf(
                Triple("fhd", 1080),
                Triple("hd", 720),
                Triple("sd", 480)
            )
            
            // Tentar TODAS as qualidades
            val successfulQualities = mutableListOf<Pair<String, Int>>()
            
            for ((qualityName, qualityValue) in qualities) {
                val videoUrl = "$basePath/$qualityName/$episodeNumber.mp4"
                
                println("🔍 AnimeFireExtractor: Testando $qualityName...")
                
                if (testVideoUrl(videoUrl)) {
                    println("✅ AnimeFireExtractor: $qualityName funciona!")
                    successfulQualities.add(Pair(qualityName, qualityValue))
                } else {
                    println("❌ AnimeFireExtractor: $qualityName não disponível")
                }
                
                delay(300) // Pequena pausa entre requisições
            }
            
            // Se não encontrou nenhuma, usar a primeira que capturamos
            if (successfulQualities.isEmpty()) {
                println("⚠️ AnimeFireExtractor: Nenhuma qualidade funcionou, usando primeira capturada")
                addSingleQualityLink(firstUrl, name, mainUrl, url, callback)
                return true
            }
            
            // Adicionar todas as qualidades que funcionaram
            for ((qualityName, qualityValue) in successfulQualities) {
                val videoUrl = "$basePath/$qualityName/$episodeNumber.mp4"
                
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
                            "Referer" to url,
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                            "Accept" to "video/mp4,video/*;q=0.9,*/*;q=0.8"
                        )
                    }
                )
                
                println("➕ AnimeFireExtractor: Qualidade $qualityDisplay adicionada")
            }
            
            println("🎉 AnimeFireExtractor: ${successfulQualities.size} qualidades adicionadas com sucesso!")
            return true
            
        } catch (e: Exception) {
            println("💥 AnimeFireExtractor: Erro geral - ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    private suspend fun captureFirstVideoUrl(url: String): String {
        return try {
            println("🌐 AnimeFireExtractor: Capturando primeiro link de vídeo...")
            
            // Tentar com WebViewResolver primeiro
            val resolver = WebViewResolver(
                interceptUrl = Regex("""lightspeedst\.net.*\.mp4(?:\?|$)"""),
                useOkhttp = false,
                timeout = 15_000L
            )
            
            val response = withTimeoutOrNull(20_000L) {
                app.get(url, interceptor = resolver, timeout = 20_000L)
            }
            
            val interceptedUrl = response?.url ?: ""
            
            if (interceptedUrl.isNotEmpty()) {
                println("📡 AnimeFireExtractor: URL interceptada: ${interceptedUrl.take(80)}...")
                return interceptedUrl
            }
            
            // Se não funcionou, tentar método alternativo
            println("🔄 AnimeFireExtractor: WebView não capturou, tentando análise de HTML...")
            captureFromHtml(url)
            
        } catch (e: Exception) {
            println("⚠️ AnimeFireExtractor: Erro ao capturar primeira URL: ${e.message}")
            ""
        }
    }
    
    private suspend fun captureFromHtml(url: String): String {
        return try {
            println("🔍 AnimeFireExtractor: Analisando HTML da página...")
            val doc = app.get(url).document
            
            // Buscar em scripts JavaScript
            val scripts = doc.select("script")
            
            for (script in scripts) {
                val scriptContent = script.html()
                if (scriptContent.contains("lightspeedst.net") && scriptContent.contains(".mp4")) {
                    // Padrão: https://lightspeedst.net/sXX/mp4/VIDEO_ID/QUALIDADE/EPISODE.mp4
                    val pattern = """(https://lightspeedst\.net/s\d+/mp4/[^/]+/[^/]+/\d+\.mp4)""".toRegex()
                    val match = pattern.find(scriptContent)
                    
                    if (match != null) {
                        val foundUrl = match.groupValues[1]
                        println("✅ AnimeFireExtractor: Encontrado no HTML: ${foundUrl.take(80)}...")
                        return foundUrl
                    }
                    
                    // Outros padrões
                    val altPatterns = listOf(
                        """['"](https://lightspeedst\.net[^'"]+\.mp4)['"]""".toRegex(),
                        """src\s*:\s*['"](https://lightspeedst\.net[^'"]+\.mp4)['"]""".toRegex(),
                        """file\s*:\s*['"](https://lightspeedst\.net[^'"]+\.mp4)['"]""".toRegex()
                    )
                    
                    for (altPattern in altPatterns) {
                        val altMatch = altPattern.find(scriptContent)
                        if (altMatch != null) {
                            val foundUrl = altMatch.groupValues[1]
                            if (foundUrl.contains(".mp4")) {
                                println("✅ AnimeFireExtractor: Encontrado com padrão alternativo: ${foundUrl.take(80)}...")
                                return foundUrl
                            }
                        }
                    }
                }
            }
            
            println("❌ AnimeFireExtractor: Nenhum link encontrado no HTML")
            ""
            
        } catch (e: Exception) {
            println("💥 AnimeFireExtractor: Erro ao analisar HTML: ${e.message}")
            ""
        }
    }
    
    private fun extractBasePattern(videoUrl: String): Pair<String, String> {
        return try {
            // Padrão: https://lightspeedst.net/sXX/mp4/VIDEO_ID/QUALIDADE/EPISODE.mp4
            val pattern = """(https://lightspeedst\.net/s\d+/mp4/[^/]+)/[^/]+/(\d+)\.mp4""".toRegex()
            val match = pattern.find(videoUrl)
            
            if (match != null) {
                val basePath = match.groupValues[1]
                val episodeNumber = match.groupValues[2]
                return Pair(basePath, episodeNumber)
            }
            
            println("⚠️ AnimeFireExtractor: Não conseguiu extrair padrão da URL")
            Pair("", "1")
            
        } catch (e: Exception) {
            println("⚠️ AnimeFireExtractor: Erro ao extrair padrão: ${e.message}")
            Pair("", "1")
        }
    }
    
    private suspend fun testVideoUrl(videoUrl: String): Boolean {
        return try {
            // Fazer uma requisição HEAD para verificar se o vídeo existe
            val response = app.head(videoUrl, timeout = 5000L)
            response.code == 200
        } catch (e: Exception) {
            false
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
            
            println("✅ AnimeFireExtractor: Qualidade única $qualityDisplay adicionada")
        } catch (e: Exception) {
            println("❌ AnimeFireExtractor: Erro ao adicionar qualidade única: ${e.message}")
        }
    }
}
