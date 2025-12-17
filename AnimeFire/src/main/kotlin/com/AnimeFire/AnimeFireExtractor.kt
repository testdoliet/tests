package com.AnimeFire

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.WebViewResolver
import com.lagradost.cloudstream3.utils.newExtractorLink
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
            
            // PASSO 1: INTERCEPTAR COM WEBVIEW
            println("🌐 Iniciando WebViewResolver...")
            
            // Configurar o WebViewResolver CORRETAMENTE
            val resolver = WebViewResolver(
                regex = Regex("""lightspeedst\.net.*\.mp4"""),
                useOkhttp = false,
                timeout = 15000L
            )
            
            // Fazer a requisição com interceptor
            val response = app.get(url, interceptor = resolver)
            val interceptedUrl = response.url
            
            println("✅ URL interceptada: $interceptedUrl")
            
            if (interceptedUrl.isNotEmpty() && interceptedUrl.contains("lightspeedst.net") && interceptedUrl.contains(".mp4")) {
                // PASSO 2: EXTRAIR INFORMAÇÕES DA URL
                println("🔍 Analisando URL interceptada...")
                
                // Tentar extrair base e episódio
                val baseInfo = extractBaseInfo(interceptedUrl)
                
                if (baseInfo != null) {
                    val (basePath, episodeNum) = baseInfo
                    println("📁 Base encontrada: $basePath")
                    println("🎬 Episódio: $episodeNum")
                    
                    // PASSO 3: GERAR TODAS AS QUALIDADES
                    // Ordem de preferência: fhd > hd > sd
                    val qualities = listOf(
                        "fhd" to 1080,
                        "hd" to 720, 
                        "sd" to 480
                    )
                    
                    var foundAny = false
                    
                    for ((qualityName, qualityValue) in qualities) {
                        // Construir URL para esta qualidade
                        val videoUrl = "$basePath/$qualityName/$episodeNum.mp4"
                        println("🔄 Gerando: $qualityName ($videoUrl)")
                        
                        // Verificar se existe (sem usar HEAD instável)
                        if (checkUrlExists(videoUrl)) {
                            println("✅ $qualityName DISPONÍVEL!")
                            
                            // Criar ExtractorLink
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
                                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                                        "Accept" to "video/mp4,video/*;q=0.9,*/*;q=0.8"
                                    )
                                )
                            )
                            foundAny = true
                        } else {
                            println("⚠️ $qualityName não encontrada")
                        }
                    }
                    
                    if (foundAny) {
                        println("🎉 Sucesso! Qualidades extraídas via WebView")
                        return true
                    }
                } else {
                    println("⚠️ Não conseguiu extrair informações da URL")
                }
            } else {
                println("❌ WebView não interceptou link válido")
            }
            
            // PASSO 4: FALLBACK - Usar URL interceptada diretamente
            if (interceptedUrl.isNotEmpty() && interceptedUrl.contains(".mp4")) {
                println("🔄 Fallback: usando URL interceptada direta")
                
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
            
            // PASSO 5: ÚLTIMO RECURSO - Buscar no HTML
            println("🔄 Tentando extração via HTML...")
            return extractFromHtmlFallback(url, mainUrl, name, callback)
            
        } catch (e: Exception) {
            println("💥 AnimeFireExtractor Erro: ${e.message}")
            e.printStackTrace()
            
            // Fallback para HTML em caso de erro
            extractFromHtmlFallback(url, mainUrl, name, callback)
        }
    }
    
    // ============ FUNÇÕES AUXILIARES ============
    
    private fun extractBaseInfo(url: String): Pair<String, String>? {
        try {
            println("🔧 Extraindo base info de: ${url.take(80)}...")
            
            // Padrão 1: https://lightspeedst.net/s4/mp4/haikyuu-dublado/sd/1.mp4
            val pattern1 = Regex("""(https://lightspeedst\.net/s\d+/mp4/[^/]+)/(sd|hd|fhd)/(\d+)\.mp4""")
            val match1 = pattern1.find(url)
            
            if (match1 != null) {
                val basePath = match1.groupValues[1]
                val episodeNum = match1.groupValues[3]
                println("✅ Padrão 1 encontrado: base=$basePath, ep=$episodeNum")
                return Pair(basePath, episodeNum)
            }
            
            // Padrão 2: https://lightspeedst.net/s4/mp4/haikyuu-dublado/1.mp4 (sem qualidade)
            val pattern2 = Regex("""(https://lightspeedst\.net/s\d+/mp4/[^/]+)/(\d+)\.mp4""")
            val match2 = pattern2.find(url)
            
            if (match2 != null) {
                val fullPath = match2.groupValues[1]
                val episodeNum = match2.groupValues[2]
                
                // Remover qualquer qualidade que possa estar no path
                val basePath = fullPath.replace(Regex("/(sd|hd|fhd)$"), "")
                println("✅ Padrão 2 encontrado: base=$basePath, ep=$episodeNum")
                return Pair(basePath, episodeNum)
            }
            
            // Padrão 3: Mais genérico
            val pattern3 = Regex("""(https://lightspeedst\.net/s\d+/mp4/[^/]+/[^/]+)/(\d+)\.mp4""")
            val match3 = pattern3.find(url)
            
            if (match3 != null) {
                val basePath = match3.groupValues[1].substringBeforeLast("/")
                val episodeNum = match3.groupValues[2]
                println("✅ Padrão 3 encontrado: base=$basePath, ep=$episodeNum")
                return Pair(basePath, episodeNum)
            }
            
            println("❌ Nenhum padrão encontrado")
            return null
            
        } catch (e: Exception) {
            println("⚠️ Erro extractBaseInfo: ${e.message}")
            return null
        }
    }
    
    private suspend fun checkUrlExists(url: String): Boolean {
        return try {
            // Usar GET com range request (mais seguro que HEAD)
            val headers = mapOf(
                "Range" to "bytes=0-1",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            )
            
            val response = app.get(url, headers = headers, timeout = 3000, allowRedirects = false)
            
            // Código 206 (Partial Content) ou 200 (OK) são válidos
            response.code == 206 || response.code == 200
            
        } catch (e: Exception) {
            println("⚠️ checkUrlExists falhou para $url: ${e.message}")
            false
        }
    }
    
    private fun guessQuality(url: String): Pair<String, Int> {
        return when {
            url.contains("/fhd/") || url.contains("1080") -> Pair("fhd", 1080)
            url.contains("/hd/") || url.contains("720") -> Pair("hd", 720)
            url.contains("/sd/") || url.contains("480") -> Pair("sd", 480)
            else -> Pair("SD", 480)
        }
    }
    
    private suspend fun extractFromHtmlFallback(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("📄 Extraindo do HTML como fallback...")
            
            val doc = app.get(url).document
            val html = doc.html()
            
            // Buscar links no HTML
            val pattern = Regex("""https://lightspeedst\.net/s\d+/mp4/[^"'\s]+\.mp4""")
            val matches = pattern.findAll(html)
            
            var foundAny = false
            
            matches.forEach { match ->
                val foundUrl = match.value
                if (foundUrl.contains(".mp4")) {
                    println("🔍 Encontrado no HTML: ${foundUrl.take(80)}...")
                    
                    val quality = guessQuality(foundUrl)
                    
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = "$name (${quality.first})",
                            url = foundUrl,
                            referer = "$mainUrl/",
                            quality = quality.second,
                            type = ExtractorLinkType.VIDEO,
                            headers = mapOf(
                                "Referer" to url,
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                            )
                        )
                    )
                    foundAny = true
                }
            }
            
            foundAny
            
        } catch (e: Exception) {
            println("❌ HTML fallback falhou: ${e.message}")
            false
        }
    }
}
