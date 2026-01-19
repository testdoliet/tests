package com.AniTube

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.Qualities
import java.net.URLDecoder

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
            "Range" to "bytes=0-",
            "Referer" to referer,
            "Origin" to "https://www.anitube.news",
            "Sec-Fetch-Dest" to "video",
            "Sec-Fetch-Mode" to "no-cors",
            "Sec-Fetch-Site" to "cross-site",
            "User-Agent" to getRandomUserAgent()
        )
    }
    
    private fun getQualityLabel(quality: Int): String {
        return when {
            quality >= 1080 -> "FHD"
            quality >= 720 -> "HD"
            quality >= 480 -> "SD"
            quality >= 360 -> "SD"
            else -> "SD"
        }
    }
    
    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            var linksFound = false
            
            // ESTRATÉGIA 1: Tentar extrair do HTML principal
            linksFound = extractFromHtml(url, mainUrl, name, callback)
            
            // ESTRATÉGIA 2: Se não encontrou, tentar com WebView
            if (!linksFound) {
                linksFound = extractWithWebView(url, mainUrl, name, callback)
            }
            
            // ESTRATÉGIA 3: Extrair de iframes
            if (!linksFound) {
                linksFound = extractFromIframes(url, mainUrl, name, callback)
            }
            
            linksFound
            
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun extractFromHtml(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val pageResponse = app.get(url, headers = mapOf(
                "User-Agent" to getRandomUserAgent(),
                "Referer" to mainUrl
            ))
            
            val html = pageResponse.text
            
            // Procurar por iframes com players
            val iframePattern = """<iframe[^>]*src=["']([^"']+)["'][^>]*>""".toRegex()
            val iframeMatches = iframePattern.findAll(html).toList()
            
            var found = false
            for (match in iframeMatches) {
                val iframeUrl = match.groupValues[1]
                if (iframeUrl.isNotBlank()) {
                    val fullIframeUrl = if (iframeUrl.startsWith("http")) iframeUrl else mainUrl + iframeUrl
                    
                    // Extrair do iframe
                    found = found || extractFromIframePage(fullIframeUrl, url, name, callback)
                }
            }
            
            // Procurar por links de vídeo diretos no HTML
            val videoPatterns = listOf(
                """https?://[^"'\s]+\.(?:m3u8|mp4|mkv|webm)[^"'\s]*""".toRegex(),
                """["']?(?:file|src|url)["']?\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""".toRegex(),
                """(https?://[^"'\s]+googlevideo\.com/videoplayback[^"'\s]*)""".toRegex()
            )
            
            for (pattern in videoPatterns) {
                val matches = pattern.findAll(html).toList()
                for (match in matches.take(10)) {
                    val videoUrl = if (pattern == videoPatterns[1]) match.groupValues[1] else match.value
                    
                    if (videoUrl.isNotBlank() && videoUrl.contains("//")) {
                        val fullUrl = if (videoUrl.startsWith("http")) videoUrl else "https:$videoUrl"
                        
                        // Verificar tipo de vídeo
                        val isM3u8 = fullUrl.contains(".m3u8")
                        val isGoogleVideo = fullUrl.contains("googlevideo.com/videoplayback")
                        val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        
                        val quality = if (isGoogleVideo) {
                            val itagPattern = """[?&]itag=(\d+)""".toRegex()
                            val itagMatch = itagPattern.find(fullUrl)
                            val itag = itagMatch?.groupValues?.get(1)?.toIntOrNull() ?: 18
                            when (itag) {
                                18, 43 -> 360
                                22, 45 -> 720
                                37, 46 -> 1080
                                59, 44 -> 480
                                else -> 360
                            }
                        } else if (fullUrl.contains("720") || fullUrl.contains("HD")) {
                            720
                        } else if (fullUrl.contains("1080") || fullUrl.contains("FHD")) {
                            1080
                        } else {
                            480
                        }
                        
                        val qualityLabel = getQualityLabel(quality)
                        
                        val headers = if (isGoogleVideo) {
                            createVideoHeaders(url)
                        } else {
                            mapOf(
                                "Referer" to url,
                                "User-Agent" to getRandomUserAgent(),
                                "Origin" to mainUrl
                            )
                        }
                        
                        val extractorLink = newExtractorLink(
                            source = "AniTube",
                            name = "$name ($qualityLabel)",
                            url = fullUrl,
                            type = linkType
                        ) {
                            this.referer = url
                            this.quality = quality
                            this.headers = headers
                        }
                        
                        callback(extractorLink)
                        found = true
                    }
                }
            }
            
            found
            
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun extractFromIframePage(
        iframeUrl: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val response = app.get(iframeUrl, headers = mapOf(
                "User-Agent" to getRandomUserAgent(),
                "Referer" to referer
            ))
            
            val html = response.text
            var found = false
            
            // Procurar por m3u8
            val m3u8Pattern = """(https?://[^"'\s]+\.m3u8[^"'\s]*)""".toRegex()
            val m3u8Matches = m3u8Pattern.findAll(html).toList()
            
            for (match in m3u8Matches.take(5)) {
                val m3u8Url = match.value
                val extractorLink = newExtractorLink(
                    source = "AniTube",
                    name = "$name (HLS)",
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
                found = true
            }
            
            // Procurar por vídeos diretos
            val videoPattern = """(https?://[^"'\s]+\.(?:mp4|mkv|webm)[^"'\s]*)""".toRegex()
            val videoMatches = videoPattern.findAll(html).toList()
            
            for (match in videoMatches.take(5)) {
                val videoUrl = match.value
                val extractorLink = newExtractorLink(
                    source = "AniTube",
                    name = "$name (Direct)",
                    url = videoUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = iframeUrl
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "Referer" to iframeUrl,
                        "User-Agent" to getRandomUserAgent()
                    )
                }
                
                callback(extractorLink)
                found = true
            }
            
            found
            
        } catch (e: Exception) {
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
            val m3u8Resolver = WebViewResolver(
                interceptUrl = Regex("""\.m3u8"""),
                additionalUrls = listOf(Regex("""\.m3u8""")),
                useOkhttp = false,
                timeout = 20000L
            )
            
            val intercepted = app.get(url, interceptor = m3u8Resolver).url
            
            if (intercepted.isNotEmpty() && intercepted.contains(".m3u8")) {
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
                    val extractorLink = newExtractorLink(
                        source = "AniTube",
                        name = "$name (HLS)",
                        url = m3u8Link.url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = url
                        this.quality = m3u8Link.quality
                        this.headers = headers
                    }
                    
                    callback(extractorLink)
                }
                
                return true
            }
            
            false
            
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun extractFromIframes(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val document = app.get(url).document
            val iframes = document.select("iframe[src]")
            
            var found = false
            for (iframe in iframes) {
                val src = iframe.attr("src")
                if (src.isNotBlank()) {
                    val fullUrl = if (src.startsWith("http")) src else mainUrl + src
                    
                    // Pular iframes de anúncios
                    if (fullUrl.contains("ads") || fullUrl.contains("banner")) continue
                    
                    try {
                        val iframeDoc = app.get(fullUrl, headers = mapOf(
                            "User-Agent" to getRandomUserAgent(),
                            "Referer" to url
                        )).document
                        
                        // Procurar por vídeos no iframe
                        val videoSources = iframeDoc.select("source[src], video[src]")
                        for (source in videoSources) {
                            val videoSrc = source.attr("src")
                            if (videoSrc.isNotBlank()) {
                                val videoUrl = if (videoSrc.startsWith("http")) videoSrc else fullUrl + videoSrc
                                
                                val isM3u8 = videoUrl.contains(".m3u8")
                                val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                
                                val quality = when {
                                    videoUrl.contains("1080") || videoUrl.contains("FHD") -> 1080
                                    videoUrl.contains("720") || videoUrl.contains("HD") -> 720
                                    else -> 480
                                }
                                
                                val qualityLabel = getQualityLabel(quality)
                                
                                val extractorLink = newExtractorLink(
                                    source = "AniTube",
                                    name = "$name ($qualityLabel)",
                                    url = videoUrl,
                                    type = linkType
                                ) {
                                    this.referer = fullUrl
                                    this.quality = quality
                                    this.headers = mapOf(
                                        "Referer" to fullUrl,
                                        "User-Agent" to getRandomUserAgent()
                                    )
                                }
                                
                                callback(extractorLink)
                                found = true
                            }
                        }
                        
                    } catch (e: Exception) {
                        // Ignorar iframes que não podem ser acessados
                    }
                }
            }
            
            found
            
        } catch (e: Exception) {
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
