package com.AnimeFire

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.Jsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object AnimeFireExtractor {
    @Serializable
    data class VideoSource(
        val file: String? = null,
        val label: String? = null,
        val type: String? = null
    )
    
    @Serializable
    data class VideoPlayerConfig(
        val sources: List<VideoSource>? = null,
        val tracks: List<Any>? = null
    )

    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🔗 AnimeFireExtractor: Extraindo de $url")
            
            // ESTRATÉGIA REVISADA:
            // 1. Primeiro, acessar a página do episódio
            // 2. Encontrar o script que contém TODAS as qualidades
            // 3. Extrair as URLs diretamente do JavaScript
            
            println("📄 Acessando página do episódio...")
            val response = app.get(url)
            val doc = response.document
            
            // Salvar HTML para debug
            val html = doc.html()
            println("📝 HTML obtido (primeiros 1000 chars):")
            println(html.take(1000))
            
            // Buscar TODAS as URLs possíveis do lightspeedst.net
            val allUrls = mutableListOf<String>()
            
            // Buscar por padrões de URLs no HTML
            println("🔍 Buscando padrões de URLs no HTML...")
            
            // Padrões mais específicos para o lightspeedst.net
            val urlPatterns = listOf(
                Regex("""https?://lightspeedst\.net/s\d+/[^"'\s<>]+\.mp4"""),
                Regex(""""url"\s*:\s*"([^"]+\.mp4)"""),
                Regex(""""src"\s*:\s*"([^"]+\.mp4)"""),
                Regex(""""file"\s*:\s*"([^"]+\.mp4)"""),
                Regex(""""((?:https?:)?//lightspeedst\.net[^"'\s<>]+\.mp4)"""),
                Regex("""\['((?:https?:)?//lightspeedst\.net[^']+\.mp4)'"""),
                Regex("""\["((?:https?:)?//lightspeedst\.net[^"]+\.mp4)"""")
            )
            
            for (pattern in urlPatterns) {
                val matches = pattern.findAll(html)
                matches.forEach { match ->
                    var foundUrl = if (match.groupValues.size > 1) match.groupValues[1] else match.value
                    // Garantir que tenha protocolo
                    if (foundUrl.startsWith("//")) {
                        foundUrl = "https:$foundUrl"
                    }
                    if (foundUrl.contains("lightspeedst.net") && foundUrl.contains(".mp4")) {
                        println("🎯 Encontrado com regex: $foundUrl")
                        allUrls.add(foundUrl)
                    }
                }
            }
            
            // Buscar em scripts específicos
            println("🔍 Buscando em scripts JavaScript...")
            val scripts = doc.select("script")
            
            for (script in scripts) {
                val scriptContent = script.html() ?: continue
                
                // Procurar por objetos JavaScript com URLs
                if (scriptContent.contains("lightspeedst") || scriptContent.contains("mp4")) {
                    println("📜 Script potencial encontrado")
                    
                    // Tentar encontrar arrays de vídeos
                    val arrayPattern = Regex("""\[([^\]]*lightspeedst[^\]]*\.mp4[^\]]*)\]""")
                    val arrayMatches = arrayPattern.findAll(scriptContent)
                    
                    arrayMatches.forEach { arrayMatch ->
                        val arrayContent = arrayMatch.groupValues[1]
                        println("📦 Array encontrado: $arrayContent")
                        
                        // Extrair URLs do array
                        val urlsInArray = Regex(""""((?:https?:)?//lightspeedst\.net[^"]+\.mp4)"""")
                            .findAll(arrayContent)
                            .map { it.groupValues[1] }
                            .toList()
                        
                        urlsInArray.forEach { urlInArray ->
                            var finalUrl = urlInArray
                            if (finalUrl.startsWith("//")) {
                                finalUrl = "https:$finalUrl"
                            }
                            println("➕ URL do array: $finalUrl")
                            allUrls.add(finalUrl)
                        }
                    }
                    
                    // Procurar por objetos JSON de configuração do player
                    if (scriptContent.contains("sources") && scriptContent.contains("file")) {
                        println("🎬 Possível configuração do player encontrada")
                        
                        // Tentar extrair JSON do script
                        val jsonPattern = Regex("""\{[^{}]*sources\s*:\s*\[[^]]+\][^{}]*\}""")
                        val jsonMatches = jsonPattern.findAll(scriptContent)
                        
                        jsonMatches.forEach { jsonMatch ->
                            val jsonStr = jsonMatch.value
                            println("📋 JSON encontrado: $jsonStr")
                            
                            try {
                                // Tentar analisar como JSON
                                val json = Json { ignoreUnknownKeys = true }
                                val config = json.decodeFromString<VideoPlayerConfig>(jsonStr)
                                
                                config.sources?.forEach { source ->
                                    source.file?.let { fileUrl ->
                                        if (fileUrl.contains("lightspeedst") && fileUrl.contains(".mp4")) {
                                            var finalUrl = fileUrl
                                            if (finalUrl.startsWith("//")) {
                                                finalUrl = "https:$finalUrl"
                                            }
                                            println("🎯 URL do JSON: $finalUrl")
                                            allUrls.add(finalUrl)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                println("⚠️ Não consegui analisar JSON: ${e.message}")
                                
                                // Fallback: extrair URLs do texto
                                val urlMatches = Regex(""""((?:https?:)?//lightspeedst\.net[^"]+\.mp4)"""")
                                    .findAll(jsonStr)
                                    .map { it.groupValues[1] }
                                    .toList()
                                
                                urlMatches.forEach { urlMatch ->
                                    var finalUrl = urlMatch
                                    if (finalUrl.startsWith("//")) {
                                        finalUrl = "https:$finalUrl"
                                    }
                                    println("📥 URL do texto: $finalUrl")
                                    allUrls.add(finalUrl)
                                }
                            }
                        }
                    }
                }
            }
            
            // Se ainda não encontrou, tentar uma abordagem mais agressiva
            if (allUrls.isEmpty()) {
                println("🔄 Tentando abordagem mais agressiva...")
                
                // Procurar por TODAS as ocorrências de lightspeedst.net
                val allLinks = Regex("""(https?://lightspeedst\.net/[^"'\s<>]+)""")
                    .findAll(html)
                    .map { it.value }
                    .toList()
                
                println("🔗 Todos os links lightspeedst encontrados: ${allLinks.size}")
                allLinks.forEachIndexed { index, link ->
                    println("  $index: $link")
                    if (link.contains(".mp4")) {
                        allUrls.add(link)
                    }
                }
            }
            
            // Processar e adicionar URLs encontradas
            println("📊 Total de URLs encontradas: ${allUrls.size}")
            
            if (allUrls.isNotEmpty()) {
                // Remover duplicatas
                val uniqueUrls = allUrls.distinct()
                println("✨ URLs únicas: ${uniqueUrls.size}")
                
                // Classificar por qualidade (maior para menor)
                val sortedUrls = uniqueUrls.sortedByDescending { url ->
                    when {
                        url.contains("1080p") || url.contains("/1080/") -> 1080
                        url.contains("720p") || url.contains("/720/") || url.contains("/hd/") -> 720
                        url.contains("480p") || url.contains("/480/") || url.contains("/sd/") -> 480
                        url.contains("360p") || url.contains("/360/") -> 360
                        url.contains("240p") || url.contains("/240/") -> 240
                        else -> 0
                    }
                }
                
                // Adicionar cada URL como uma qualidade separada
                sortedUrls.forEach { videoUrl ->
                    val quality = extractQualityFromUrl(videoUrl)
                    val qualityName = getQualityDisplayName(quality)
                    
                    println("➕ Adicionando: $qualityName - ${videoUrl.take(80)}...")
                    
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name ($qualityName)",
                            url = videoUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "$mainUrl/"
                            this.quality = quality
                            this.headers = mapOf(
                                "Referer" to url,
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                                "Accept" to "*/*",
                                "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
                                "Origin" to "https://animefire.io"
                            )
                        }
                    )
                }
                
                println("🎉 ${sortedUrls.size} qualidades adicionadas com sucesso!")
                return true
            }
            
            // Último recurso: tentar com WebView
            println("🚨 Nenhuma URL encontrada. Tentando com WebView...")
            return tryWebViewExtraction(url, mainUrl, name, callback)
            
        } catch (e: Exception) {
            println("💥 Erro no extrator principal: ${e.message}")
            e.printStackTrace()
            
            // Fallback para WebView
            return tryWebViewExtraction(url, mainUrl, name, callback)
        }
    }
    
    private suspend fun tryWebViewExtraction(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🌐 Iniciando WebView...")
            
            val allUrls = mutableListOf<String>()
            
            // Usar WebView com regex mais específico
            val resolver = WebViewResolver(
                interceptUrl = Regex("""lightspeedst\.net.*\.mp4"""),
                useOkhttp = false,
                timeout = 15_000L
            )
            
            val response = app.get(url, interceptor = resolver)
            println("📡 Resposta do WebView: ${response.url}")
            
            // Tentar capturar todas as requisições do Network tab
            println("🔍 Analisando resposta do WebView...")
            
            // Padrão para múltiplas qualidades
            val qualities = listOf("1080p", "720p", "480p", "360p", "240p")
            
            for (quality in qualities) {
                val qualityUrl = response.url.toString()
                    .replace(Regex("/[^/]+\\.mp4$"), "/$quality.mp4")
                
                println("🔗 Tentando URL de qualidade: $qualityUrl")
                
                // Verificar se a URL existe
                try {
                    val testResponse = app.head(qualityUrl, timeout = 5000)
                    if (testResponse.code in 200..299) {
                        println("✅ Qualidade $quality encontrada!")
                        allUrls.add(qualityUrl)
                    }
                } catch (e: Exception) {
                    println("❌ Qualidade $quality não disponível")
                }
            }
            
            if (allUrls.isNotEmpty()) {
                allUrls.forEach { videoUrl ->
                    val quality = extractQualityFromUrl(videoUrl)
                    val qualityName = getQualityDisplayName(quality)
                    
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name ($qualityName)",
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
                println("🎉 ${allUrls.size} qualidades adicionadas via WebView!")
                return true
            }
            
            println("❌ WebView também não encontrou links")
            false
            
        } catch (e: Exception) {
            println("💥 Erro no WebView: ${e.message}")
            false
        }
    }
    
    private fun extractQualityFromUrl(url: String): Int {
        return when {
            url.contains("1080p") || url.contains("1080") -> 1080
            url.contains("720p") || url.contains("720") || url.contains("/hd/") -> 720
            url.contains("480p") || url.contains("480") || url.contains("/sd/") -> 480
            url.contains("360p") || url.contains("360") -> 360
            url.contains("240p") || url.contains("240") -> 240
            else -> 480 // Default
        }
    }
    
    private fun getQualityDisplayName(quality: Int): String {
        return when (quality) {
            1080 -> "1080p"
            720 -> "720p"
            480 -> "480p"
            360 -> "360p"
            240 -> "240p"
            else -> "SD"
        }
    }
    
    // Função de debug para scripts
    private fun debugScripts(doc: org.jsoup.nodes.Document) {
        val scripts = doc.select("script")
        println("📊 Total de scripts: ${scripts.size}")
        
        scripts.forEachIndexed { index, script ->
            val content = script.html()
            if (content.contains("lightspeedst") || content.contains("mp4") || content.contains("sources")) {
                println("\n=== SCRIPT $index ===")
                println("Tamanho: ${content.length} chars")
                println("Primeiros 500 chars:")
                println(content.take(500))
                
                // Procurar padrões específicos
                val patterns = listOf(
                    """sources\s*:\s*\[""",
                    """lightspeedst\.net""",
                    """\.mp4"""
                )
                
                patterns.forEach { pattern ->
                    if (content.contains(pattern.toRegex())) {
                        println("✅ Contém: $pattern")
                    }
                }
            }
        }
    }
                                                       }
