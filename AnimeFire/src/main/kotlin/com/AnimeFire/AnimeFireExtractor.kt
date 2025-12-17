package com.AnimeFire

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.WebViewResolver
import kotlinx.coroutines.delay
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink

object AnimeFireExtractor {
    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🔗 AnimeFireExtractor: Extraindo de $url")
            
            // Passo 1: Tentar interceptar com WebView
            var interceptedUrl: String? = null
            try {
                val resolver = WebViewResolver(
                    regex = Regex("""lightspeedst\.net.*\.mp4"""),
                    useOkhttp = false
                )
                
                val response = app.get(url, interceptor = resolver)
                interceptedUrl = response.url
                println("🌐 Interceptado via WebView: $interceptedUrl")
            } catch (e: Exception) {
                println("⚠️ WebView falhou: ${e.message}")
            }
            
            // Passo 2: Se não interceptou, buscar no HTML
            if (interceptedUrl == null || !interceptedUrl.contains("lightspeedst.net")) {
                println("🔄 Buscando no HTML...")
                val htmlUrl = extractFromHtml(url)
                if (htmlUrl != null) {
                    interceptedUrl = htmlUrl
                    println("🌐 Encontrado no HTML: $interceptedUrl")
                }
            }
            
            // Passo 3: Se temos uma URL, construir as qualidades
            if (interceptedUrl != null && interceptedUrl.contains("lightspeedst.net")) {
                val baseInfo = extractBaseInfo(interceptedUrl)
                if (baseInfo != null) {
                    val (basePath, episodeNum) = baseInfo
                    println("📁 Base: $basePath, Ep: $episodeNum")
                    
                    // Qualidades na ordem de preferência
                    val qualities = listOf(
                        Pair("fhd", 1080),
                        Pair("hd", 720),
                        Pair("sd", 480)
                    )
                    
                    var foundAny = false
                    
                    for ((qualityName, qualityValue) in qualities) {
                        val videoUrl = "$basePath/$qualityName/$episodeNum.mp4"
                        println("🔄 Testando: $qualityName -> $videoUrl")
                        
                        if (checkUrlExists(videoUrl)) {
                            println("✅ $qualityName disponível!")
                            
                            callback.invoke(
                                ExtractorLink(
                                    source = name,
                                    name = "$name ($qualityName)",
                                    url = videoUrl,
                                    referer = "$mainUrl/",
                                    quality = qualityValue,
                                    type = ExtractorLinkType.VIDEO,
                                    headers = mapOf(
                                        "Referer" to url,
                                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                                    )
                                )
                            )
                            foundAny = true
                        } else {
                            println("❌ $qualityName não disponível")
                        }
                    }
                    
                    // Se encontrou pelo menos uma qualidade
                    if (foundAny) {
                        println("🎉 Extração bem-sucedida!")
                        return true
                    }
                }
            }
            
            // Passo 4: Fallback - usar URL original se não conseguiu construir
            if (interceptedUrl != null && interceptedUrl.contains(".mp4")) {
                println("🔄 Usando URL original como fallback")
                
                val quality = guessQuality(interceptedUrl)
                
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = "$name (${quality.first})",
                        url = interceptedUrl,
                        referer = "$mainUrl/",
                        quality = quality.second,
                        type = ExtractorLinkType.VIDEO,
                        headers = mapOf(
                            "Referer" to url,
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                        )
                    )
                )
                return true
            }
            
            println("❌ Nenhum link encontrado")
            false
            
        } catch (e: Exception) {
            println("💥 AnimeFireExtractor: Erro - ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    // ============ FUNÇÕES AUXILIARES ============
    
    private fun extractFromHtml(url: String): String? {
        return try {
            val doc = app.get(url).document
            val html = doc.html()
            
            // Buscar padrão lightspeedst.net
            val regex = Regex("""https://lightspeedst\.net/s\d+/mp4/[^"'\s]+\.mp4""")
            val match = regex.find(html)
            
            match?.value
        } catch (e: Exception) {
            null
        }
    }
    
    private fun extractBaseInfo(url: String): Pair<String, String>? {
        return try {
            // Padrão: https://lightspeedst.net/s4/mp4/haikyuu-dublado/sd/1.mp4
            val pattern = Regex("""(https://lightspeedst\.net/s\d+/mp4/[^/]+)/(sd|hd|fhd)/(\d+)\.mp4""")
            val match = pattern.find(url)
            
            if (match != null) {
                val basePath = match.groupValues[1]  // https://lightspeedst.net/s4/mp4/haikyuu-dublado
                val episodeNum = match.groupValues[3] // 1
                return Pair(basePath, episodeNum)
            }
            
            // Tentar outro padrão
            val altPattern = Regex("""(https://lightspeedst\.net/s\d+/mp4/.+?)/(\d+)\.mp4""")
            val altMatch = altPattern.find(url)
            
            if (altMatch != null) {
                val basePath = altMatch.groupValues[1].substringBeforeLast("/")
                val episodeNum = altMatch.groupValues[2]
                return Pair(basePath, episodeNum)
            }
            
            null
        } catch (e: Exception) {
            println("⚠️ Erro ao extrair base info: ${e.message}")
            null
        }
    }
    
    private suspend fun checkUrlExists(url: String): Boolean {
        return try {
            val response = app.head(url, timeout = 3000)
            response.code == 200
        } catch (e: Exception) {
            false
        }
    }
    
    private fun guessQuality(url: String): Pair<String, Int> {
        return when {
            url.contains("/fhd/") -> Pair("fhd", 1080)
            url.contains("/hd/") -> Pair("hd", 720)
            url.contains("/sd/") -> Pair("sd", 480)
            url.contains("1080") -> Pair("1080p", 1080)
            url.contains("720") -> Pair("720p", 720)
            url.contains("480") -> Pair("480p", 480)
            else -> Pair("SD", 480)
        }
    }
}
