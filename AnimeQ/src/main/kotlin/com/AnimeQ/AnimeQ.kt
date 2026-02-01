package com.AnimeQ

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType

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
        38 to 3072,
        266 to 2160,
        138 to 2160,
        313 to 2160,
    )

    suspend fun extractVideoLinks(
        url: String,
        name: String = "Episódio",
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val pageResponse = app.get(url)
            val doc = org.jsoup.Jsoup.parse(pageResponse.text)

            // Procurar por iframe do Blogger/YouTube
            val iframe = doc.selectFirst("iframe[src*='blogger.com'], iframe[src*='youtube.com/embed'], iframe[src*='youtube.googleapis.com']")
            
            if (iframe != null) {
                val iframeUrl = iframe.attr("src")
                extractFromBloggerIframe(iframeUrl, url, name, callback)
            } else {
                // Tentar encontrar diretamente na página
                extractDirectFromPage(doc, url, name, callback)
            }
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun extractFromBloggerIframe(
        iframeUrl: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val headers = mapOf(
                "Referer" to referer,
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "pt-BR,pt;q=0.9"
            )

            val iframeResponse = app.get(iframeUrl, headers = headers)
            val iframeHtml = iframeResponse.text

            // Padrão 1: URLs de videoplayback do Google
            val videoPattern = """https?://[^"'\s<>]+googlevideo\.com/videoplayback[^"'\s<>]+""".toRegex()
            val matches = videoPattern.findAll(iframeHtml).toList()

            if (matches.isNotEmpty()) {
                var found = false
                for (match in matches.distinct()) {
                    val videoUrl = match.value
                    
                    // Extrair qualidade
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
                            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                            "Origin" to "https://www.blogger.com"
                        )
                    }

                    callback(extractorLink)
                    found = true
                }
                return found
            }

            // Padrão 2: Procurar em variáveis JavaScript
            val jsPattern = """(?i)(?:src|url|file|video_url)\s*[:=]\s*["'](https?://[^"'\s]+\.(?:mp4|m3u8|m4v|mov|webm|flv|avi))["']""".toRegex()
            val jsMatches = jsPattern.findAll(iframeHtml).toList()

            for (match in jsMatches) {
                val videoUrl = match.groupValues[1]
                val quality = 720 // Default
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
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
                    )
                }

                callback(extractorLink)
                return true
            }

            false
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun extractDirectFromPage(
        doc: org.jsoup.nodes.Document,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Procurar em scripts
        val scripts = doc.select("script")
        
        for (script in scripts) {
            val scriptText = script.html()
            
            // Procurar URLs do Google Video
            val videoPattern = """https?://[^"'\s<>]+googlevideo\.com/videoplayback[^"'\s<>]+""".toRegex()
            val matches = videoPattern.findAll(scriptText).toList()
            
            if (matches.isNotEmpty()) {
                for (match in matches.distinct()) {
                    val videoUrl = match.value
                    val itag = 18 // Default
                    val quality = itagQualityMap[itag] ?: 360
                    val qualityLabel = getQualityLabel(quality)

                    val extractorLink = newExtractorLink(
                        source = "AnimeQ",
                        name = "$name ($qualityLabel)",
                        url = videoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = referer
                        this.quality = quality
                        this.headers = mapOf(
                            "Referer" to referer,
                            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
                        )
                    }

                    callback(extractorLink)
                    return true
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
            else -> "SD"
        }
    }
}
