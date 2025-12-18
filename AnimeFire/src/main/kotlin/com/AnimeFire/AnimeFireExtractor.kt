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

            // LISTA para armazenar TODAS as URLs interceptadas
            val interceptedUrls = mutableListOf<String>()
            
            // 1. Interceptar MÚLTIPLAS URLs - não parar na primeira
            println("🌐 AnimeFireExtractor: Iniciando interceptação múltipla...")
            
            // Vamos usar um WebViewResolver personalizado
            // Primeiro interceptamos o link principal
            val streamResolver = object : WebViewResolver(
                interceptUrl = Regex("""lightspeedst\.net.*\.mp4(?:\?|$)"""),
                useOkhttp = false,
                timeout = 20_000L
            ) {
                // Sobrescrevemos o método para capturar múltiplas URLs
                override suspend fun resolve(url: String): String? {
                    println("🔍 AnimeFireExtractor: Resolvendo URL: $url")
                    val result = super.resolve(url)
                    if (result != null && result.contains("lightspeedst.net")) {
                        interceptedUrls.add(result)
                        println("📡 AnimeFireExtractor: URL capturada: ${result.take(100)}...")
                    }
                    return result
                }
            }

            // 2. Fazer a requisição com timeout
            println("🔄 AnimeFireExtractor: Fazendo requisição principal...")
            val response = withTimeoutOrNull(25_000L) {
                app.get(url, interceptor = streamResolver, timeout = 25_000L)
            }
            
            if (response != null) {
                println("✅ AnimeFireExtractor: Requisição principal concluída")
            } else {
                println("⚠️ AnimeFireExtractor: Timeout na requisição principal")
            }

            // 3. Aguardar um pouco mais para capturar possíveis requisições adicionais
            println("⏳ AnimeFireExtractor: Aguardando possíveis requisições adicionais...")
            delay(3000)

            println("✅ AnimeFireExtractor: Total de URLs interceptadas: ${interceptedUrls.size}")
            interceptedUrls.forEachIndexed { index, url -> 
                println("   ${index + 1}. ${url.take(80)}...")
            }

            // 4. Se não encontrou URLs, tentar método alternativo
            if (interceptedUrls.isEmpty()) {
                println("🔄 AnimeFireExtractor: Nenhuma URL interceptada, tentando método alternativo...")
                return tryAlternativeMethod(url, mainUrl, name, callback)
            }

            // 5. Processar todas as URLs encontradas
            val processedUrls = mutableSetOf<String>() // Para evitar duplicatas
            var successCount = 0

            // Ordenar por qualidade (fhd -> hd -> sd)
            val qualityOrder = listOf("fhd", "hd", "sd")
            
            val sortedUrls = interceptedUrls.sortedByDescending { url ->
                qualityOrder.indexOfFirst { url.contains("/$it/") }.let { 
                    if (it == -1) 999 else it 
                }
            }

            for ((index, videoUrl) in sortedUrls.withIndex()) {
                try {
                    // Extrair a "assinatura" da URL (tudo exceto a qualidade)
                    val urlSignature = extractUrlSignature(videoUrl)
                    if (processedUrls.contains(urlSignature)) {
                        println("⏭️ AnimeFireExtractor: URL similar já processada, pulando...")
                        continue
                    }
                    
                    processedUrls.add(urlSignature)
                    
                    // Determinar qualidade
                    val (qualityName, qualityValue) = when {
                        videoUrl.contains("/fhd/") -> Pair("1080p", 1080)
                        videoUrl.contains("/hd/") -> Pair("720p", 720)
                        else -> Pair("480p", 480)
                    }
                    
                    println("🔍 AnimeFireExtractor: Testando qualidade $qualityName...")
                    
                    // Testar se a URL é acessível
                    try {
                        val testResponse = app.head(videoUrl, timeout = 5000L)
                        if (testResponse.code == 200) {
                            println("✅ AnimeFireExtractor: Qualidade $qualityName funciona!")
                            
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
                                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                                        "Accept" to "video/mp4,video/*;q=0.9,*/*;q=0.8"
                                    )
                                }
                            )
                            successCount++
                        } else {
                            println("❌ AnimeFireExtractor: Qualidade $qualityName não acessível (HTTP ${testResponse.code})")
                        }
                    } catch (e: Exception) {
                        println("⚠️ AnimeFireExtractor: Erro ao testar URL: ${e.message}")
                    }
                    
                } catch (e: Exception) {
                    println("⚠️ AnimeFireExtractor: Erro ao processar URL ${index + 1}: ${e.message}")
                }
                
                // Pequena pausa entre requisições
                if (index < sortedUrls.size - 1) {
                    delay(500)
                }
            }

            // 6. Se encontrou menos de 3 qualidades, tentar gerar as outras
            if (successCount < 3 && interceptedUrls.isNotEmpty()) {
                println("🔄 AnimeFireExtractor: Tentando gerar qualidades faltantes...")
                tryGenerateMissingQualities(interceptedUrls.first(), mainUrl, name, url, callback)
            }

            println("🎉 AnimeFireExtractor: Concluído! $successCount qualidades adicionadas")
            return successCount > 0

        } catch (e: Exception) {
            println("💥 AnimeFireExtractor: Erro geral - ${e.message}")
            e.printStackTrace()
            false
        }
    }

    private fun extractUrlSignature(videoUrl: String): String {
        // Extrai a parte da URL que identifica o vídeo (sem qualidade)
        return try {
            val pattern = """(https://lightspeedst\.net/s\d+/mp4/[^/]+)/[^/]+/\d+\.mp4""".toRegex()
            val match = pattern.find(videoUrl)
            match?.groupValues?.get(1) ?: videoUrl
        } catch (e: Exception) {
            videoUrl
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
            val videoUrls = mutableListOf<String>()
            
            val patterns = listOf(
                """(https://lightspeedst\.net/s\d+/mp4/[^/]+/[^/]+/\d+\.mp4)""".toRegex(),
                """['"](https://lightspeedst\.net[^'"]+\.mp4)['"]""".toRegex(),
                """src:\s*['"](https://lightspeedst\.net[^'"]+\.mp4)['"]""".toRegex()
            )
            
            for (script in scripts) {
                val scriptContent = script.html()
                if (scriptContent.contains("lightspeedst.net")) {
                    for (pattern in patterns) {
                        val matches = pattern.findAll(scriptContent)
                        matches.forEach { match ->
                            val foundUrl = match.groupValues[1]
                            if (foundUrl.contains(".mp4") && !videoUrls.contains(foundUrl)) {
                                println("✅ AnimeFireExtractor: Encontrado no HTML: ${foundUrl.take(80)}...")
                                videoUrls.add(foundUrl)
                            }
                        }
                    }
                }
            }
            
            // Adicionar URLs encontradas
            for (videoUrl in videoUrls.distinct()) {
                val quality = when {
                    videoUrl.contains("/fhd/") -> 1080
                    videoUrl.contains("/hd/") -> 720
                    else -> 480
                }
                
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name (${if (quality == 1080) "1080p" else if (quality == 720) "720p" else "480p"})",
                        url = videoUrl,
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
            
            videoUrls.isNotEmpty()
        } catch (e: Exception) {
            println("❌ AnimeFireExtractor: Método alternativo falhou: ${e.message}")
            false
        }
    }

    private suspend fun tryGenerateMissingQualities(
        baseUrl: String,
        mainUrl: String,
        name: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            println("🛠️ AnimeFireExtractor: Gerando qualidades faltantes...")
            
            // Extrair padrão da URL
            val pattern = """(https://lightspeedst\.net/s\d+/mp4/[^/]+)/([^/]+)/(\d+)\.mp4""".toRegex()
            val match = pattern.find(baseUrl) ?: return
            
            val basePath = match.groupValues[1]
            val currentQuality = match.groupValues[2]
            val episodeNumber = match.groupValues[3]
            
            // Lista de qualidades para tentar
            val qualitiesToTry = listOf("fhd", "hd", "sd").filter { it != currentQuality }
            
            for (quality in qualitiesToTry) {
                val generatedUrl = "$basePath/$quality/$episodeNumber.mp4"
                println("🔧 AnimeFireExtractor: Testando qualidade gerada: $quality...")
                
                try {
                    val testResponse = app.head(generatedUrl, timeout = 3000L)
                    if (testResponse.code == 200) {
                        val qualityValue = when (quality) {
                            "fhd" -> 1080
                            "hd" -> 720
                            else -> 480
                        }
                        
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "$name (${if (qualityValue == 1080) "1080p" else if (qualityValue == 720) "720p" else "480p"})",
                                url = generatedUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "$mainUrl/"
                                this.quality = qualityValue
                                this.headers = mapOf(
                                    "Referer" to referer,
                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                                )
                            }
                        )
                        println("✅ AnimeFireExtractor: Qualidade $quality adicionada com sucesso!")
                    } else {
                        println("❌ AnimeFireExtractor: Qualidade $quality não disponível (HTTP ${testResponse.code})")
                    }
                } catch (e: Exception) {
                    println("⚠️ AnimeFireExtractor: Qualidade $quality não acessível: ${e.message}")
                }
                
                delay(300) // Pequena pausa
            }
        } catch (e: Exception) {
            println("❌ AnimeFireExtractor: Erro ao gerar qualidades: ${e.message}")
        }
    }

    private fun addSingleQualityLink(
        videoUrl: String,
        name: String,
        mainUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val quality = when {
            videoUrl.contains("1080") || videoUrl.contains("/fhd/") -> 1080
            videoUrl.contains("720") || videoUrl.contains("/hd/") -> 720
            else -> 480
        }
        
        callback.invoke(
            newExtractorLink(
                source = name,
                name = "$name (${if (quality == 1080) "1080p" else if (quality == 720) "720p" else "480p"})",
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
        println("✅ AnimeFireExtractor: Única qualidade adicionada")
    }
}
