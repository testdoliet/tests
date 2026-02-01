package com.AnimeQ

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.json.JSONObject
import org.jsoup.Jsoup

object AnimeQVideoExtractor {
    private val itagQualityMap = mapOf(
        18 to 360,
        22 to 720,
        37 to 1080,
        59 to 480,
        43 to 360,
        44 to 480,
        45 to 720,
        46 to 1080,
        38 to 3072, // 3072p (4K)
        266 to 2160, // 4K
        138 to 2160, // 4K
        313 to 2160, // 4K
    )

    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageResponse = app.get(url)
        val doc = Jsoup.parse(pageResponse.text)

        return try {
            // Tentar extrair de iframe do YouTube/blogger
            val hasIframe = doc.select("iframe").isNotEmpty()
            if (hasIframe) {
                extractFromIframe(doc, url, name, callback)
            } else {
                // Tentar extrair de player direto
                extractDirectVideo(doc, url, name, callback)
            }
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun extractFromIframe(
        doc: org.jsoup.nodes.Document,
        originalUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val iframes = doc.select("iframe")
        
        for (iframe in iframes) {
            val iframeSrc = iframe.attr("src") ?: continue
            
            // Verificar se é um iframe do YouTube/blogger
            if (iframeSrc.contains("blogger.com") || 
                iframeSrc.contains("youtube.com/embed") ||
                iframeSrc.contains("youtube.googleapis.com")) {
                
                val result = extractYouTubeBloggerVideo(iframeSrc, originalUrl, name, callback)
                if (result) return true
            }
        }
        
        return false
    }

    private suspend fun extractYouTubeBloggerVideo(
        iframeUrl: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val headers = mapOf(
                "Referer" to referer,
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Accept-Language" to "pt-BR,pt;q=0.9",
                "Accept-Encoding" to "gzip, deflate, br",
                "Sec-Fetch-Dest" to "iframe",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "cross-site",
                "Upgrade-Insecure-Requests" to "1"
            )

            val iframeResponse = app.get(iframeUrl, headers = headers)
            val iframeHtml = iframeResponse.text

            // Tentar encontrar URLs de videoplayback
            val videoPattern = """https?://[^"'\s<>]+googlevideo\.com/videoplayback[^"'\s<>]+""".toRegex()
            val matches = videoPattern.findAll(iframeHtml).toList()

            if (matches.isNotEmpty()) {
                var found = false
                for (match in matches.distinct()) {
                    val videoUrl = match.value
                    
                    // Extrair qualidade do itag
                    val itagPattern = """[?&]itag=(\d+)""".toRegex()
                    val itagMatch = itagPattern.find(videoUrl)
                    val itag = itagMatch?.groupValues?.get(1)?.toIntOrNull() ?: 18
                    val quality = itagQualityMap[itag] ?: 360
                    val qualityLabel = getQualityLabel(quality)

                    val extractorLink = newExtractorLink(
                        source = "AnimeQ",
                        name = "$name ($qualityLabel)",
                        url = videoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = iframeUrl
                        this.quality = quality
                        this.headers = mapOf(
                            "Referer" to iframeUrl,
                            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                            "Origin" to "https://www.blogger.com",
                            "Accept" to "*/*",
                            "Accept-Language" to "pt-BR,pt;q=0.9",
                            "Sec-Fetch-Dest" to "video",
                            "Sec-Fetch-Mode" to "no-cors",
                            "Sec-Fetch-Site" to "cross-site"
                        )
                    }

                    callback(extractorLink)
                    found = true
                }

                return found
            }

            // Tentar encontrar em variáveis JavaScript
            val jsPattern = """["']?url["']?\s*[:=]\s*["'](https?://[^"'\s]+)["']""".toRegex()
            val jsMatches = jsPattern.findAll(iframeHtml).toList()

            for (match in jsMatches) {
                val possibleUrl = match.groupValues[1]
                if (possibleUrl.contains("googlevideo.com") || possibleUrl.contains("videoplayback")) {
                    val itag = 18
                    val quality = itagQualityMap[itag] ?: 360
                    val qualityLabel = getQualityLabel(quality)

                    val extractorLink = newExtractorLink(
                        source = "AnimeQ",
                        name = "$name ($qualityLabel)",
                        url = possibleUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = iframeUrl
                        this.quality = quality
                        this.headers = mapOf(
                            "Referer" to iframeUrl,
                            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"
                        )
                    }

                    callback(extractorLink)
                    return true
                }
            }

            false
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun extractDirectVideo(
        doc: org.jsoup.nodes.Document,
        originalUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Tentar encontrar scripts com URLs de vídeo
        val scripts = doc.select("script")
        
        for (script in scripts) {
            val scriptText = script.html()
            if (scriptText.contains("googlevideo.com") || scriptText.contains("videoplayback")) {
                val videoPattern = """https?://[^"'\s<>]+googlevideo\.com/videoplayback[^"'\s<>]+""".toRegex()
                val matches = videoPattern.findAll(scriptText).toList()
                
                if (matches.isNotEmpty()) {
                    var found = false
                    for (match in matches.distinct()) {
                        val videoUrl = match.value
                        
                        val itagPattern = """[?&]itag=(\d+)""".toRegex()
                        val itagMatch = itagPattern.find(videoUrl)
                        val itag = itagMatch?.groupValues?.get(1)?.toIntOrNull() ?: 18
                        val quality = itagQualityMap[itag] ?: 360
                        val qualityLabel = getQualityLabel(quality)

                        val extractorLink = newExtractorLink(
                            source = "AnimeQ",
                            name = "$name ($qualityLabel)",
                            url = videoUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = originalUrl
                            this.quality = quality
                            this.headers = mapOf(
                                "Referer" to originalUrl,
                                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"
                            )
                        }

                        callback(extractorLink)
                        found = true
                    }
                    
                    return found
                }
            }
        }
        
        return false
    }

    private fun getQualityLabel(quality: Int): String {
        return when {
            quality >= 2160 -> "4K"
            quality >= 1080 -> "FHD"
            quality >= 720 -> "HD"
            quality >= 480 -> "SD"
            quality >= 360 -> "SD"
            else -> "SD"
        }
    }
}
