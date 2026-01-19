package com.AniTube

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.Jsoup

object AniTubeVideoExtractor {
    
    private fun getRandomUserAgent(): String {
        return "Mozilla/5.0 (Linux; Android 13; SM-S901B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
    
    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔍 AniTubeExtractor: Iniciando extração para: $url")
        
        return try {
            // ESTRATÉGIA 1: WebView OBRIGATÓRIO - interceptar TUDO
            println("🔍 AniTubeExtractor: Tentando WebView...")
            val webViewResult = extractWithWebView(url, mainUrl, name, callback)
            
            if (webViewResult) {
                println("✅ AniTubeExtractor: WebView encontrou links!")
                return true
            }
            
            println("⚠️ AniTubeExtractor: WebView não encontrou links, tentando fallback...")
            
            // ESTRATÉGIA 2: Fallback - analisar HTML diretamente
            val fallbackResult = extractWithDirectHtml(url, mainUrl, name, callback)
            
            if (fallbackResult) {
                println("✅ AniTubeExtractor: Fallback encontrou links!")
                return true
            }
            
            println("❌ AniTubeExtractor: Nenhuma estratégia funcionou")
            false
            
        } catch (e: Exception) {
            println("❌ AniTubeExtractor: Erro: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    private suspend fun extractWithWebView(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🔍 AniTubeExtractor: Configurando WebViewResolver...")
            
            // Configurar WebView para interceptar TUDO
            val resolver = WebViewResolver(
                interceptUrl = Regex("""(\.m3u8|\.mp4|videoplayback|googlevideo|stream|video)"""),
                additionalUrls = listOf(
                    Regex("""\.m3u8"""),
                    Regex("""\.mp4"""),
                    Regex("""googlevideo\.com.*"""),
                    Regex("""videoplayback.*""")
                ),
                useOkhttp = false,
                timeout = 30000L // 30 segundos
            )
            
            println("🔍 AniTubeExtractor: Executando WebView na URL: $url")
            
            // Executar WebView
            val result = app.get(url, interceptor = resolver)
            
            println("🔍 AniTubeExtractor: WebView finalizado. URL final: ${result.url}")
            println("🔍 AniTubeExtractor: Status: ${result.code}")
            
            // Verificar URLs interceptadas
            val intercepted = result.url
            println("🔍 AniTubeExtractor: URL interceptada: $intercepted")
            
            var linksFound = false
            
            // Processar URLs m3u8
            if (intercepted.contains(".m3u8")) {
                println("✅ AniTubeExtractor: Encontrou M3U8 via WebView: $intercepted")
                
                val headers = mapOf(
                    "Accept" to "*/*",
                    "Connection" to "keep-alive",
                    "Referer" to url,
                    "Origin" to mainUrl,
                    "User-Agent" to getRandomUserAgent(),
                    "Accept-Encoding" to "gzip, deflate, br",
                    "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8"
                )
                
                M3u8Helper.generateM3u8(
                    "AniTube WebView - $name",
                    intercepted,
                    mainUrl,
                    headers = headers
                ).forEach { m3u8Link ->
                    val extractorLink = newExtractorLink(
                        source = "AniTube",
                        name = "$name (WebView HLS)",
                        url = m3u8Link.url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = url
                        this.quality = m3u8Link.quality
                        this.headers = headers
                    }
                    
                    println("✅ AniTubeExtractor: Adicionando M3U8: ${m3u8Link.url}")
                    callback(extractorLink)
                    linksFound = true
                }
            }
            
            // Verificar se encontrou links do Google Video
            if (intercepted.contains("googlevideo.com") || intercepted.contains("videoplayback")) {
                println("✅ AniTubeExtractor: Encontrou Google Video via WebView: ${intercepted.take(100)}...")
                
                val headers = mapOf(
                    "Accept" to "*/*",
                    "Accept-Encoding" to "identity;q=1, *;q=0",
                    "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
                    "Connection" to "keep-alive",
                    "Range" to "bytes=0-",
                    "Referer" to url,
                    "Origin" to mainUrl,
                    "User-Agent" to getRandomUserAgent()
                )
                
                val extractorLink = newExtractorLink(
                    source = "AniTube",
                    name = "$name (WebView Direct)",
                    url = intercepted,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                }
                
                println("✅ AniTubeExtractor: Adicionando Google Video")
                callback(extractorLink)
                linksFound = true
            }
            
            linksFound
            
        } catch (e: Exception) {
            println("❌ AniTubeExtractor: Erro no WebView: ${e.message}")
            false
        }
    }
    
    private suspend fun extractWithDirectHtml(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🔍 AniTubeExtractor: Analisando HTML diretamente...")
            
            val response = app.get(url, headers = mapOf(
                "User-Agent" to getRandomUserAgent(),
                "Referer" to mainUrl
            ))
            
            val html = response.text
            val doc = Jsoup.parse(html)
            
            var linksFound = false
            
            // Procurar por iframes do player
            val iframes = doc.select("iframe[src]")
            println("🔍 AniTubeExtractor: Encontrou ${iframes.size} iframes")
            
            for (iframe in iframes) {
                val src = iframe.attr("src")
                if (src.isNotBlank()) {
                    println("🔍 AniTubeExtractor: Iframe encontrado: $src")
                    
                    // Verificar se é um player conhecido
                    if (src.contains("blogger.com") || src.contains("youtube.com") || 
                        src.contains("googlevideo.com") || src.contains("player")) {
                        
                        val fullUrl = if (src.startsWith("http")) src else mainUrl + src
                        println("🔍 AniTubeExtractor: Analisando iframe: $fullUrl")
                        
                        // Tentar extrair do iframe
                        val iframeResult = extractFromIframe(fullUrl, url, name, callback)
                        if (iframeResult) {
                            linksFound = true
                            break
                        }
                    }
                }
            }
            
            // Procurar por scripts com links de vídeo
            if (!linksFound) {
                val scripts = doc.select("script")
                for (script in scripts) {
                    val scriptText = script.html()
                    
                    // Padrões comuns
                    val patterns = listOf(
                        """(https?://[^"'\s]+\.m3u8[^"'\s]*)""".toRegex(),
                        """["']?(?:file|src|url)["']?\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""".toRegex(),
                        """(https?://[^"'\s]+googlevideo\.com/videoplayback[^"'\s]*)""".toRegex()
                    )
                    
                    for (pattern in patterns) {
                        val matches = pattern.findAll(scriptText).toList()
                        for (match in matches.take(5)) {
                            val videoUrl = if (pattern == patterns[1]) match.groupValues[1] else match.value
                            
                            if (videoUrl.isNotBlank() && videoUrl.contains("//")) {
                                val fullUrl = if (videoUrl.startsWith("http")) videoUrl else "https:$videoUrl"
                                
                                println("✅ AniTubeExtractor: Encontrou link no script: ${fullUrl.take(100)}...")
                                
                                val isM3u8 = fullUrl.contains(".m3u8")
                                val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                
                                val quality = if (fullUrl.contains("720") || fullUrl.contains("HD")) 720
                                else if (fullUrl.contains("1080") || fullUrl.contains("FHD")) 1080
                                else 480
                                
                                val extractorLink = newExtractorLink(
                                    source = "AniTube",
                                    name = "$name (Script)",
                                    url = fullUrl,
                                    type = linkType
                                ) {
                                    this.referer = url
                                    this.quality = quality
                                    this.headers = mapOf(
                                        "Referer" to url,
                                        "User-Agent" to getRandomUserAgent(),
                                        "Origin" to mainUrl
                                    )
                                }
                                
                                callback(extractorLink)
                                linksFound = true
                            }
                        }
                    }
                }
            }
            
            linksFound
            
        } catch (e: Exception) {
            println("❌ AniTubeExtractor: Erro no HTML parsing: ${e.message}")
            false
        }
    }
    
    private suspend fun extractFromIframe(
        iframeUrl: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🔍 AniTubeExtractor: Extraindo do iframe: $iframeUrl")
            
            val response = app.get(iframeUrl, headers = mapOf(
                "User-Agent" to getRandomUserAgent(),
                "Referer" to referer
            ))
            
            val html = response.text
            
            // Procurar por m3u8 no iframe
            val m3u8Pattern = """(https?://[^"'\s]+\.m3u8[^"'\s]*)""".toRegex()
            val m3u8Matches = m3u8Pattern.findAll(html).toList()
            
            for (match in m3u8Matches.take(3)) {
                val m3u8Url = match.value
                println("✅ AniTubeExtractor: M3U8 encontrado no iframe: $m3u8Url")
                
                val extractorLink = newExtractorLink(
                    source = "AniTube",
                    name = "$name (Iframe HLS)",
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = iframeUrl
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "Referer" to iframeUrl,
                        "User-Agent" to getRandomUserAgent(),
                        "Origin" to "https://www.anitube.news"
                    )
                }
                
                callback(extractorLink)
                return true
            }
            
            false
            
        } catch (e: Exception) {
            println("❌ AniTubeExtractor: Erro no iframe: ${e.message}")
            false
        }
    }
}
