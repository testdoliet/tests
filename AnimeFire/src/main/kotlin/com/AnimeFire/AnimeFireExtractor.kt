package com.AnimeFire

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.WebViewResolver
import org.jsoup.Jsoup
import com.lagradost.cloudstream3.utils.loadExtractor
com.lagradost.cloudstream3.utils.newExtractorLink

object AnimeFireExtractor {
    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🔗 AnimeFireExtractor: Extraindo de $url")
            
            // 1. Tentar com WebView primeiro
            val interceptedUrl = interceptWithWebView(url)
            println("🌐 URL interceptada: ${interceptedUrl ?: "Nenhuma"}")
            
            // 2. Tentar extrair do HTML
            val htmlUrls = extractFromHtml(url)
            println("📄 URLs do HTML: ${htmlUrls.size}")
            
            // 3. Juntar todas as URLs encontradas
            val allFoundUrls = mutableListOf<String>()
            
            interceptedUrl?.let { allFoundUrls.add(it) }
            allFoundUrls.addAll(htmlUrls)
            
            // 4. Se encontrou alguma URL, processar
            if (allFoundUrls.isNotEmpty()) {
                // Usar a primeira URL como base para construir as outras qualidades
                val baseUrl = allFoundUrls.first()
                println("🎯 URL base para construção: $baseUrl")
                
                // GERAR TODAS AS QUALIDADES DINAMICAMENTE
                val qualities = listOf(
                    Triple("fhd", 1080),
                    Triple("hd", 720),
                    Triple("sd", 480)
                )
                
                var foundAny = false
                
                // Tentar na ORDEM DE PREFERÊNCIA: fhd > hd > sd
                for ((qualityName, qualityValue) in qualities) {
                    try {
                        val videoUrl = constructQualityUrl(baseUrl, qualityName)
                        if (videoUrl != null) {
                            println("🔄 Construindo link $qualityName: $videoUrl")
                            
                            // Verificar se o link existe
                            if (checkUrlExists(videoUrl)) {
                                println("✅ Qualidade $qualityName DISPONÍVEL!")
                                
                                callback.invoke(
                                    ExtractorLink(
                                        source = name,
                                        name = "$name ($qualityName)",
                                        url = videoUrl,
                                        referer = "$mainUrl/",
                                        quality = qualityValue,
                                        headers = mapOf(
                                            "Referer" to url,
                                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                                            "Accept" to "video/mp4,video/*;q=0.9,*/*;q=0.8"
                                        ),
                                        type = ExtractorLinkType.VIDEO
                                    )
                                )
                                foundAny = true
                            } else {
                                println("❌ $qualityName não disponível (HTTP check falhou)")
                            }
                        }
                    } catch (e: Exception) {
                        println("⚠️ Erro ao processar $qualityName: ${e.message}")
                    }
                }
                
                // Se não encontrou nenhuma qualidade construída, usar a URL original
                if (!foundAny && interceptedUrl != null) {
                    println("🔄 Usando URL interceptada original")
                    val quality = guessQualityFromUrl(interceptedUrl)
                    
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = "$name (${quality.first})",
                            url = interceptedUrl,
                            referer = "$mainUrl/",
                            quality = quality.second,
                            headers = mapOf(
                                "Referer" to url,
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                            ),
                            type = ExtractorLinkType.VIDEO
                        )
                    )
                    foundAny = true
                }
                
                return foundAny
            }
            
            println("❌ Nenhum link encontrado")
            false
            
        } catch (e: Exception) {
            println("💥 AnimeFireExtractor: Erro - ${e.message}")
            false
        }
    }
    
    // ============ FUNÇÕES AUXILIARES ============
    
    private suspend fun interceptWithWebView(url: String): String? {
        return try {
            val resolver = WebViewResolver(
                regex = Regex("""lightspeedst\.net.*\.mp4"""),
                useOkhttp = false,
                timeout = 15000L
            )
            
            val response = app.get(url, interceptor = resolver)
            val intercepted = response.url
            
            if (intercepted.isNotEmpty() && intercepted.contains("lightspeedst.net")) {
                intercepted
            } else {
                null
            }
        } catch (e: Exception) {
            println("⚠️ WebView falhou: ${e.message}")
            null
        }
    }
    
    private suspend fun extractFromHtml(url: String): List<String> {
        val urls = mutableListOf<String>()
        
        try {
            val doc = app.get(url).document
            val html = doc.html()
            
            // Padrão 1: links diretos no HTML
            val directPattern = Regex("""https://lightspeedst\.net/s\d+/mp4/[^"'\s]+\.mp4""")
            directPattern.findAll(html).forEach { match ->
                urls.add(match.value)
            }
            
            // Padrão 2: em atributos src
            doc.select("[src*='lightspeedst'], [data-src*='lightspeedst']").forEach { element ->
                val src = element.attr("src") ?: element.attr("data-src")
                if (src.contains(".mp4")) {
                    urls.add(src)
                }
            }
            
            // Padrão 3: em scripts JavaScript
            val scripts = doc.select("script")
            for (script in scripts) {
                val content = script.html()
                if (content.contains("lightspeedst")) {
                    val jsPattern = Regex("""['"](https://lightspeedst\.net[^'"]+\.mp4)['"]""")
                    jsPattern.findAll(content).forEach { match ->
                        urls.add(match.groupValues[1])
                    }
                }
            }
            
        } catch (e: Exception) {
            println("⚠️ HTML extraction falhou: ${e.message}")
        }
        
        return urls.distinct()
    }
    
    private fun constructQualityUrl(baseUrl: String, targetQuality: String): String? {
        try {
            // Padrão: https://lightspeedst.net/s4/mp4/haikyuu-dublado/sd/1.mp4
            val pattern = Regex("""(https://lightspeedst\.net/s\d+/mp4/[^/]+)/(sd|hd|fhd)/(\d+)\.mp4""")
            val match = pattern.find(baseUrl)
            
            if (match != null) {
                val basePath = match.groupValues[1] // https://lightspeedst.net/s4/mp4/haikyuu-dublado
                val currentQuality = match.groupValues[2] // sd
                val episodeNum = match.groupValues[3] // 1
                
                // Construir nova URL com qualidade alvo
                return "$basePath/$targetQuality/$episodeNum.mp4"
            }
            
            // Tentar outro padrão se o primeiro não funcionar
            val altPattern = Regex("""(https://lightspeedst\.net/s\d+/mp4/.+?)(?:/sd|/hd|/fhd)?/(\d+)\.mp4""")
            val altMatch = altPattern.find(baseUrl)
            
            if (altMatch != null) {
                val basePath = altMatch.groupValues[1].removeSuffix("/")
                val episodeNum = altMatch.groupValues[2]
                return "$basePath/$targetQuality/$episodeNum.mp4"
            }
            
        } catch (e: Exception) {
            println("⚠️ Erro ao construir URL para $targetQuality: ${e.message}")
        }
        
        return null
    }
    
    private suspend fun checkUrlExists(url: String): Boolean {
        return try {
            val response = app.head(url, timeout = 5000)
            response.code == 200
        } catch (e: Exception) {
            false
        }
    }
    
    private fun guessQualityFromUrl(url: String): Pair<String, Int> {
        return when {
            url.contains("/fhd/") -> Pair("fhd", 1080)
            url.contains("/hd/") -> Pair("hd", 720)
            url.contains("/sd/") -> Pair("sd", 480)
            url.contains("1080") -> Pair("1080p", 1080)
            url.contains("720") -> Pair("720p", 720)
            url.contains("480") -> Pair("480p", 480)
            url.contains("360") -> Pair("360p", 360)
            else -> Pair("SD", 480)
        }
    }
}
