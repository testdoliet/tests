package com.AniTube

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.math.min

object AniTubeExtractor {
    
    private val chromeUserAgents = listOf(
        "Mozilla/5.0 (Linux; Android 13; SM-S901B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Linux; Android 10; SM-G973F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/120.0.0.0 Mobile/15E148 Safari/604.1"
    )
    
    private fun getRandomUserAgent(): String {
        return chromeUserAgents.random()
    }
    
    private fun createVideoHeaders(referer: String): Map<String, String> {
        return mapOf(
            "Accept" to "*/*",
            "Accept-Encoding" to "identity;q=1, *;q=0",
            "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
            "Connection" to "keep-alive",
            "DNT" to "1",
            "Host" to "rr1---sn-bg0e6n7k.googlevideo.com",
            "Origin" to "https://www.anitube.news",
            "Range" to "bytes=0-",
            "Referer" to referer,
            "Sec-Fetch-Dest" to "video",
            "Sec-Fetch-Mode" to "no-cors",
            "Sec-Fetch-Site" to "cross-site",
            "User-Agent" to getRandomUserAgent(),
            "sec-ch-ua" to "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"",
            "sec-ch-ua-mobile" to "?1",
            "sec-ch-ua-platform" to "\"Android\""
        )
    }
    
    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            var linksFound = false
            
            // ESTRATÉGIA 1: WebView com interceptação específica para googlevideo
            val videoResolver = WebViewResolver(
                interceptUrl = Regex("""(googlevideo\.com/videoplayback|\.m3u8|index\.m3u8|master\.m3u8)"""),
                additionalUrls = listOf(
                    Regex("""googlevideo\.com/videoplayback\?.*"""),
                    Regex("""\.m3u8(\?.*)?""")
                ),
                useOkhttp = false,
                timeout = 30_000L, // Tempo maior para carregar player completo
                headless = true,
                enableJavascript = true,
                enableDomStorage = true
            )
            
            println("DEBUG: Iniciando WebView para URL: $url")
            
            val webViewResult = app.get(url, interceptor = videoResolver)
            val interceptedUrls = videoResolver.interceptedUrls
            
            println("DEBUG: Interceptou ${interceptedUrls.size} URLs")
            
            // Processar URLs interceptadas
            for ((index, intercepted) in interceptedUrls.withIndex()) {
                println("DEBUG: URL interceptada $index: ${intercepted.take(100)}...")
                
                when {
                    intercepted.contains("googlevideo.com/videoplayback") -> {
                        // TRATAR LINK GOOGLEVIDEO COM ESTRATÉGIA ESPECIAL
                        try {
                            // Tentar com headers personalizados
                            val headers = createVideoHeaders(url)
                            
                            // Verificar se link ainda está vivo com HEAD request
                            val headResponse = app.head(intercepted, headers = headers)
                            if (headResponse.code in 200..299) {
                                callback(ExtractorLink(
                                    source = name,
                                    name = "AniTube GoogleVideo",
                                    url = intercepted,
                                    referer = url,
                                    quality = Qualities.Unknown.value,
                                    isM3u8 = false,
                                    headers = headers
                                ))
                                println("DEBUG: Link GoogleVideo adicionado com sucesso!")
                                linksFound = true
                            } else {
                                println("DEBUG: HEAD request falhou com código: ${headResponse.code}")
                            }
                        } catch (e: Exception) {
                            println("DEBUG: Erro ao processar GoogleVideo: ${e.message}")
                        }
                    }
                    
                    intercepted.contains(".m3u8") -> {
                        // TRATAR M3U8
                        try {
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
                                "AniTube - $name",
                                intercepted,
                                mainUrl,
                                headers = headers
                            ).forEach { m3u8Link ->
                                callback(m3u8Link)
                                linksFound = true
                                println("DEBUG: M3U8 link adicionado: $intercepted")
                            }
                        } catch (e: Exception) {
                            println("DEBUG: Erro ao processar M3U8: ${e.message}")
                        }
                    }
                    
                    intercepted.contains(".mp4") || intercepted.contains(".mkv") || intercepted.contains(".webm") -> {
                        // TRATAR VÍDEOS DIRETOS
                        try {
                            callback(ExtractorLink(
                                source = name,
                                name = "AniTube Direct",
                                url = intercepted,
                                referer = url,
                                quality = Qualities.Unknown.value,
                                isM3u8 = false
                            ))
                            linksFound = true
                            println("DEBUG: Vídeo direto adicionado: $intercepted")
                        } catch (e: Exception) {
                            println("DEBUG: Erro ao processar vídeo direto: ${e.message}")
                        }
                    }
                }
            }
            
            // ESTRATÉGIA 2: Analisar HTML para encontrar players alternativos
            if (!linksFound) {
                println("DEBUG: Tentando estratégia 2 - HTML analysis")
                val document = app.get(url, headers = mapOf(
                    "User-Agent" to getRandomUserAgent(),
                    "Referer" to mainUrl
                )).document
                
                // Procurar por players que não sejam Google Video
                val videoSources = document.select("""
                    source[src*=".m3u8"], 
                    source[src*=".mp4"], 
                    video[src*=".m3u8"], 
                    video[src*=".mp4"],
                    iframe[src*="streamtape"],
                    iframe[src*="dood"],
                    iframe[src*="mystream"],
                    iframe[src*="mp4upload"],
                    iframe[src*="vidstream"],
                    iframe[src*="kwik"],
                    iframe[src*="videobin"],
                    [data-src*=".m3u8"],
                    [data-src*=".mp4"]
                """.trimIndent())
                
                for (element in videoSources) {
                    val src = element.attr("src").ifEmpty { element.attr("data-src") }
                    if (src.isNotBlank() && !src.contains("googlevideo")) {
                        val fullUrl = if (src.startsWith("http")) src else mainUrl + src
                        
                        // Testar se o link está acessível
                        try {
                            val testResponse = app.head(fullUrl, timeout = 5000)
                            if (testResponse.code in 200..399) {
                                val isM3u8 = fullUrl.contains(".m3u8")
                                callback(ExtractorLink(
                                    source = name,
                                    name = "AniTube Alt Player",
                                    url = fullUrl,
                                    referer = url,
                                    quality = Qualities.Unknown.value,
                                    isM3u8 = isM3u8
                                ))
                                linksFound = true
                                println("DEBUG: Player alternativo encontrado: $fullUrl")
                                break
                            }
                        } catch (e: Exception) {
                            // Ignorar e continuar
                        }
                    }
                }
            }
            
            // ESTRATÉGIA 3: Extrair de scripts JavaScript
            if (!linksFound) {
                println("DEBUG: Tentando estratégia 3 - JS extraction")
                val document = app.get(url).document
                val scripts = document.select("script")
                
                val videoPatterns = listOf(
                    """(https?://[^"'\s]+\.(?:m3u8|mp4|mkv|webm)[^"'\s]*)""".toRegex(),
                    """["']?(?:file|src|url|source)["']?\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""".toRegex(),
                    """(https?://[^"'\s]+/videoplayback[^"'\s]*)""".toRegex(),
                    """hls\.loadSource\(["']([^"']+)["']\)""".toRegex(),
                    """player\.src\(["']([^"']+)["']\)""".toRegex()
                )
                
                for (script in scripts) {
                    val scriptText = script.html()
                    if (scriptText.length < 50000) { // Limitar scripts grandes
                        for (pattern in videoPatterns) {
                            val matches = pattern.findAll(scriptText).toList()
                            for (match in matches.take(5)) { // Limitar a 5 matches por script
                                val videoUrl = match.groupValues[1]
                                if (videoUrl.isNotBlank() && videoUrl.contains("//")) {
                                    val fullUrl = if (videoUrl.startsWith("http")) videoUrl else "https:$videoUrl"
                                    
                                    // Verificar se é m3u8 ou vídeo direto
                                    val isM3u8 = fullUrl.contains(".m3u8")
                                    val isGoogleVideo = fullUrl.contains("googlevideo.com")
                                    
                                    if (!isGoogleVideo || isM3u8) { // Evitar GoogleVideo MP4 direto
                                        try {
                                            val testResponse = app.head(fullUrl, timeout = 3000)
                                            if (testResponse.code in 200..399) {
                                                callback(ExtractorLink(
                                                    source = name,
                                                    name = "AniTube JS Extract",
                                                    url = fullUrl,
                                                    referer = url,
                                                    quality = Qualities.Unknown.value,
                                                    isM3u8 = isM3u8
                                                ))
                                                linksFound = true
                                                println("DEBUG: Link extraído do JS: $fullUrl")
                                                return@forEach // Sair do loop se encontrar um link
                                            }
                                        } catch (e: Exception) {
                                            // Ignorar e continuar
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            println("DEBUG: Extração finalizada. Links encontrados: $linksFound")
            linksFound
            
        } catch (e: Exception) {
            println("DEBUG: Erro no extractor: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    private fun extractM3u8FromUrl(url: String): String? {
        return try {
            if (url.contains("d=")) {
                URLDecoder.decode(url.substringAfter("d=").substringBefore("&"), "UTF-8")
            } else if (url.contains("url=")) {
                URLDecoder.decode(url.substringAfter("url=").substringBefore("&"), "UTF-8")
            } else {
                url
            }
        } catch (e: Exception) {
            url
        }
    }
}
