package com.Hypeflix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app

object HypeFlixExtractor {
    suspend fun extractVideoLinks(
        data: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val document = app.get(data, referer = referer).document
            
            // Tentar 1: Procurar por iframe primeiro
            val iframe = document.selectFirst("iframe[src]")
            if (iframe != null) {
                val iframeSrc = iframe.attr("src")
                if (iframeSrc.isNotBlank()) {
                    return processIframeUrl(iframeSrc, data, callback)
                }
            }
            
            // Tentar 2: Procurar em scripts
            val scripts = document.select("script")
            for (script in scripts) {
                val content = script.html()
                
                // Buscar links m3u8
                val m3u8Links = extractLinks(content, ".m3u8")
                if (m3u8Links.isNotEmpty()) {
                    m3u8Links.forEach { url ->
                        callback(
                            newExtractorLink(
                                "HypeFlix",
                                "HLS",
                                url,
                                referer = data,
                                Qualities.Unknown.value,
                                isM3u8 = true
                            )
                        )
                    }
                    return true
                }
                
                // Buscar links mp4
                val mp4Links = extractLinks(content, ".mp4")
                if (mp4Links.isNotEmpty()) {
                    mp4Links.forEach { url ->
                        callback(
                            newExtractorLink(
                                "HypeFlix",
                                "MP4",
                                url,
                                referer = data,
                                Qualities.Unknown.value,
                                isM3u8 = false
                            )
                        )
                    }
                    return true
                }
            }
            
            // Tentar 3: Procurar em meta tags
            val metaTags = document.select("meta[content]")
            for (meta in metaTags) {
                val content = meta.attr("content")
                if (content.contains(".m3u8")) {
                    callback(
                        newExtractorLink(
                            "HypeFlix",
                            "HLS",
                            content,
                            referer = data,
                            getQualityFromUrl(content),
                            isM3u8 = true
                        )
                    )
                    return true
                } else if (content.contains(".mp4")) {
                    callback(
                        newExtractorLink(
                            "HypeFlix",
                            "MP4",
                            content,
                            referer = data,
                            getQualityFromUrl(content),
                            isM3u8 = false
                        )
                    )
                    return true
                }
            }
            
            // Tentar 4: PlayerFlixAPI
            if (data.contains("playerflixapi.com")) {
                return extractFromPlayerFlixApi(data, callback)
            }
            
            false
            
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    private suspend fun processIframeUrl(
        iframeUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // Se já for link direto
            if (iframeUrl.contains(".m3u8")) {
                callback(
                    newExtractorLink(
                        "HypeFlix",
                        "HLS",
                        iframeUrl,
                        referer = referer,
                        getQualityFromUrl(iframeUrl),
                        isM3u8 = true
                    )
                )
                return true
            } else if (iframeUrl.contains(".mp4")) {
                callback(
                    newExtractorLink(
                        "HypeFlix",
                        "MP4",
                        iframeUrl,
                        referer = referer,
                        getQualityFromUrl(iframeUrl),
                        isM3u8 = false
                    )
                )
                return true
            }
            
            // Fazer requisição ao iframe
            val iframeDoc = app.get(iframeUrl, referer = referer).document
            
            // Procurar vídeos dentro do iframe
            val iframeScripts = iframeDoc.select("script")
            for (script in iframeScripts) {
                val content = script.html()
                
                val m3u8Links = extractLinks(content, ".m3u8")
                val mp4Links = extractLinks(content, ".mp4")
                
                m3u8Links.forEach { url ->
                    callback(
                        newExtractorLink(
                            "HypeFlix",
                            "HLS",
                            url,
                            referer = iframeUrl,
                            getQualityFromUrl(url),
                            isM3u8 = true
                        )
                    )
                }
                
                mp4Links.forEach { url ->
                    callback(
                        newExtractorLink(
                            "HypeFlix",
                            "MP4",
                            url,
                            referer = iframeUrl,
                            getQualityFromUrl(url),
                            isM3u8 = false
                        )
                    )
                }
                
                if (m3u8Links.isNotEmpty() || mp4Links.isNotEmpty()) {
                    return true
                }
            }
            
            false
        } catch (e: Exception) {
            false
        }
    }
    
    private fun extractLinks(content: String, extension: String): List<String> {
        val patterns = listOf(
            Regex("""['"]((?:https?:)?//[^'"]*?$extension(?:\?[^'"]*)?)['"]"""),
            Regex("""[""]((?:https?:)?//[^""]*?$extension(?:\?[^""]*)?)[""]"""),
            Regex("""(https?://[^\s<>"']*?$extension[^\s<>"']*?)"""),
            Regex("""(?:file|src|source|url)\s*[:=]\s*['"]((?:https?:)?//[^'"]*?$extension[^'"]*?)['"]""")
        )
        
        return patterns.flatMap { pattern ->
            pattern.findAll(content).mapNotNull { match ->
                match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
            }.toList()
        }.distinct()
    }
    
    private fun extractFromPlayerFlixApi(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        val patterns = listOf(
            Regex("""playerflixapi\.com/serie/(\d+)/(\d+)/(\d+)"""),
            Regex("""playerflixapi\.com/filme/(\d+)""")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null) {
                val embedUrl = when (match.groupValues.size) {
                    4 -> "https://playerflixapi.com/embed/serie/${match.groupValues[1]}/${match.groupValues[2]}/${match.groupValues[3]}"
                    2 -> "https://playerflixapi.com/embed/movie/${match.groupValues[1]}"
                    else -> null
                }
                
                embedUrl?.let {
                    callback(
                        newExtractorLink(
                            "HypeFlix",
                            "Embed",
                            it,
                            referer = url,
                            Qualities.Unknown.value,
                            isM3u8 = false
                        )
                    )
                    return true
                }
            }
        }
        
        return false
    }
    
    private fun getQualityFromUrl(url: String): Int {
        return when {
            url.contains("360p", ignoreCase = true) -> Qualities.P360.value
            url.contains("480p", ignoreCase = true) -> Qualities.P480.value
            url.contains("720p", ignoreCase = true) -> Qualities.P720.value
            url.contains("1080p", ignoreCase = true) -> Qualities.P1080.value
            url.contains("2160p", ignoreCase = true) || url.contains("4k", ignoreCase = true) -> Qualities.P2160.value
            else -> Qualities.Unknown.value
        }
    }
}
