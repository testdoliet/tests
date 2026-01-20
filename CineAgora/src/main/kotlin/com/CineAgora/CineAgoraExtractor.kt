package com.CineAgora

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

object CineAgoraExtractor {
    private const val BASE_PLAYER = "https://watch.brplayer.cc"
    private const val REFERER_CINEAGORA = "https://cineagora.net/"
    
    // Fonte principal que queremos priorizar
    private const val PRIMARY_SOURCE = "CineAgora"

    suspend fun extractVideoLinks(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[CineAgoraExtractor] 🔗 Iniciando extração para: $url")
        
        val result = when {
            url.contains("watch.brplayer.cc") -> {
                extractHlsFromWatchPage(url, name, callback)
            }
            url.contains("cineagora.net") -> {
                extractFromCineAgoraPage(url, name, callback)
            }
            else -> {
                extractHlsFromWatchPage(url, name, callback)
            }
        }
        
        println("[CineAgoraExtractor] 🔗 Extração concluída: $result")
        return result
    }

    private suspend fun extractFromCineAgoraPage(
        cineAgoraUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[CineAgoraExtractor] 🔗 Extraindo da página CineAgora: $cineAgoraUrl")
        
        try {
            val html = app.get(cineAgoraUrl, referer = REFERER_CINEAGORA).text
            println("[CineAgoraExtractor] 🔗 HTML obtido (${html.length} caracteres)")

            val iframePatterns = listOf(
                """<iframe[^>]*src=["'](https://watch\.brplayer\.cc/watch\?v=[^"']+)["']""",
                """<iframe[^>]*src=["'](https://watch\.brplayer\.cc/watch/[^"']+)["']""",
                """src=["'](https://watch\.brplayer\.cc/watch[^"']+)["'][^>]*allowfullscreen""",
                """<iframe[^>]*allowfullscreen[^>]*src=["'](https://watch\.brplayer\.cc/[^"']+)["']""",
                """data-src=["'][^"']*["'][^>]*src=["'](https://watch\.brplayer\.cc/[^"']+)["']"""
            )

            for (pattern in iframePatterns) {
                val match = Regex(pattern).find(html)
                if (match != null) {
                    var playerUrl = match.groupValues[1]
                    if (!playerUrl.startsWith("http")) {
                        playerUrl = BASE_PLAYER + (if (playerUrl.startsWith("/")) "" else "/") + playerUrl
                    }
                    println("[CineAgoraExtractor] 🔗 Iframe encontrado: $playerUrl")
                    return extractHlsFromWatchPage(playerUrl, name, callback)
                }
            }

            val fallbackPattern = """https://watch\.brplayer\.cc/[^"'\s<>]+"""
            val allMatches = Regex(fallbackPattern).findAll(html).toList()

            for (match in allMatches) {
                val playerUrl = match.value
                if (playerUrl.contains("/watch")) {
                    println("[CineAgoraExtractor] 🔗 URL encontrada no HTML: $playerUrl")
                    return extractHlsFromWatchPage(playerUrl, name, callback)
                }
            }

            println("[CineAgoraExtractor] 🔗 ❌ Nenhuma URL do player encontrada")
            return false
        } catch (e: Exception) {
            println("[CineAgoraExtractor] 🔗 ❌ Erro ao extrair da página CineAgora: ${e.message}")
            return false
        }
    }

    private suspend fun extractHlsFromWatchPage(
        watchUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[CineAgoraExtractor] 🔗 Extraindo do watch page: $watchUrl")
        
        try {
            val html = app.get(watchUrl, referer = REFERER_CINEAGORA).text
            println("[CineAgoraExtractor] 🔗 Watch page HTML obtido (${html.length} caracteres)")

            val uid = extractFromRegex(html, "\"uid\"\\s*:\\s*\"(\\d+)\"")
            val md5 = extractFromRegex(html, "\"md5\"\\s*:\\s*\"([a-f0-9]{32})\"")
            val videoId = extractFromRegex(html, "\"id\"\\s*:\\s*\"(\\d+)\"")
            val status = extractFromRegex(html, "\"status\"\\s*:\\s*\"([01])\"") ?: "1"

            println("[CineAgoraExtractor] 🔗 Dados extraídos - UID: $uid, MD5: $md5, VideoID: $videoId, Status: $status")

            if (uid != null && md5 != null && videoId != null) {
                // URLs principais
                val masterUrl = "$BASE_PLAYER/m3u8/$uid/$md5/master.txt?s=1&id=$videoId&cache=$status"
                println("[CineAgoraExtractor] 🔗 Master URL: $masterUrl")
                
                val headers = mapOf(
                    "Referer" to watchUrl,
                    "Origin" to BASE_PLAYER,
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
                )

                try {
                    // Gerar TODOS os links M3U8 (incluindo todas as qualidades)
                    val allLinks = M3u8Helper.generateM3u8(
                        source = "CineAgora",
                        streamUrl = masterUrl,
                        referer = watchUrl,
                        headers = headers
                    )
                    
                    println("[CineAgoraExtractor] 🔗 ${allLinks.size} links M3U8 gerados")
                    
                    // Separar links por fonte
                    val primaryLinks = mutableListOf<ExtractorLink>()
                    val secondaryLinks = mutableListOf<ExtractorLink>()
                    
                    allLinks.forEach { link ->
                        if (link.source == PRIMARY_SOURCE) {
                            primaryLinks.add(link)
                        } else {
                            secondaryLinks.add(link)
                        }
                    }
                    
                    // 1. Primeiro enviar os links da fonte principal (CineAgora)
                    // Eles serão selecionados por padrão
                    if (primaryLinks.isNotEmpty()) {
                        println("[CineAgoraExtractor] 🔗 Enviando ${primaryLinks.size} links da fonte principal ($PRIMARY_SOURCE)")
                        primaryLinks.forEach { callback(it) }
                    }
                    
                    // 2. Depois enviar os links secundários (CineAgora240p, CineAgora480p, etc)
                    // Eles estarão disponíveis como alternativas
                    if (secondaryLinks.isNotEmpty()) {
                        println("[CineAgoraExtractor] 🔗 Enviando ${secondaryLinks.size} links secundários")
                        secondaryLinks.forEach { callback(it) }
                    }
                    
                    // Se não gerou links via M3u8Helper, criar um link direto
                    if (primaryLinks.isEmpty() && secondaryLinks.isEmpty()) {
                        val fallbackLink = newExtractorLink(
                            source = PRIMARY_SOURCE,
                            name = name,
                            url = masterUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = watchUrl
                            this.quality = Qualities.Unknown.value
                            this.headers = headers
                        }
                        callback(fallbackLink)
                    }
                    
                    return true
                    
                } catch (e: Exception) {
                    println("[CineAgoraExtractor] 🔗 ❌ Erro ao gerar M3U8: ${e.message}")
                    // Fallback: criar link direto da fonte principal
                    val fallbackLink = newExtractorLink(
                        source = PRIMARY_SOURCE,
                        name = name,
                        url = masterUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = watchUrl
                        this.quality = Qualities.Unknown.value
                        this.headers = headers
                    }
                    callback(fallbackLink)
                    
                    // Também criar links alternativos como fallback
                    val altUrls = listOf(
                        "$BASE_PLAYER/m3u8/$uid/$md5/240p.txt?s=1&id=$videoId&cache=$status",
                        "$BASE_PLAYER/m3u8/$uid/$md5/480p.txt?s=1&id=$videoId&cache=$status",
                        "$BASE_PLAYER/m3u8/$uid/$md5/720p.txt?s=1&id=$videoId&cache=$status"
                    )
                    
                    val altSources = listOf("CineAgora240p", "CineAgora480p", "CineAgora720p")
                    
                    altUrls.forEachIndexed { index, altUrl ->
                        val quality = altSources.getOrNull(index) ?: "CineAgora"
                        val altLink = newExtractorLink(
                            source = quality,
                            name = "$name ($quality)",
                            url = altUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = watchUrl
                            this.quality = when (index) {
                                0 -> Qualities.P240.value
                                1 -> Qualities.P480.value
                                2 -> Qualities.P720.value
                                else -> Qualities.Unknown.value
                            }
                            this.headers = headers
                        }
                        callback(altLink)
                    }
                    
                    return true
                }
            }

            // Métodos alternativos
            val masterUrlDirect = extractMasterUrlDirect(html)
            if (masterUrlDirect != null) {
                println("[CineAgoraExtractor] 🔗 Master URL direta encontrada: $masterUrlDirect")
                
                val headers = mapOf(
                    "Referer" to watchUrl,
                    "Origin" to BASE_PLAYER
                )

                val directLink = newExtractorLink(
                    source = PRIMARY_SOURCE,
                    name = name,
                    url = masterUrlDirect,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = watchUrl
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                }
                callback(directLink)
                return true
            }

            val m3u8Urls = extractAllM3u8Urls(html)
            if (m3u8Urls.isNotEmpty()) {
                println("[CineAgoraExtractor] 🔗 ${m3u8Urls.size} URLs M3U8 encontradas")
                
                // Criar links para todas as URLs encontradas
                m3u8Urls.forEachIndexed { index, m3u8Url ->
                    val sourceName = if (index == 0) PRIMARY_SOURCE else "CineAgoraAlt${index}"
                    val link = newExtractorLink(
                        source = sourceName,
                        name = if (index == 0) name else "$name (Alt ${index})",
                        url = m3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = watchUrl
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf("Referer" to watchUrl)
                    }
                    callback(link)
                }
                return true
            }

            println("[CineAgoraExtractor] 🔗 ❌ Nenhuma URL de vídeo encontrada")
            return false
            
        } catch (e: Exception) {
            println("[CineAgoraExtractor] 🔗 ❌ Erro ao extrair do watch page: ${e.message}")
            return false
        }
    }

    private fun extractFromRegex(text: String, pattern: String): String? {
        val regex = Regex(pattern)
        val match = regex.find(text)
        return match?.groupValues?.get(1)
    }

    private fun extractMasterUrlDirect(html: String): String? {
        val patterns = listOf(
            """file\s*:\s*['"](/m3u8/\d+/[a-f0-9]+/master\.txt[^'"]*)['"]""",
            """["']sources["']\s*:\s*\[.*?file["']\s*:\s*["']([^"']+master\.txt[^"']*)["']""",
            """master\.txt[?&]s=1&id=\d+""",
            """["']file["']\s*:\s*["']([^"']+\.txt)["']""",
            """src\s*:\s*['"]([^'"]+\.m3u8[^'"]*)['"]"""
        )

        for (pattern in patterns) {
            val regex = Regex(pattern, RegexOption.DOT_MATCHES_ALL)
            val match = regex.find(html)
            if (match != null) {
                var url = match.groupValues.getOrNull(1) ?: match.value

                if (url.startsWith("/")) {
                    url = BASE_PLAYER + url
                } else if (!url.startsWith("http")) {
                    url = "$BASE_PLAYER/$url"
                }

                return url
            }
        }

        return null
    }

    private fun extractAllM3u8Urls(html: String): List<String> {
        val urls = mutableListOf<String>()

        val patterns = listOf(
            """["'](https?://[^"']+\.m3u8[^"']*)["']""",
            """["'](/[^"']+\.m3u8[^"']*)["']""",
            """(https?://[^\s<>"']+\.m3u8)""",
            """(/\S+\.m3u8\S*)"""
        )

        for (pattern in patterns) {
            val regex = Regex(pattern)
            val matches = regex.findAll(html)

            matches.forEach { match ->
                var url = match.value
                if (url.startsWith("\"") || url.startsWith("'")) {
                    url = url.substring(1, url.length - 1)
                }

                if (url.startsWith("/") && !url.startsWith("//")) {
                    url = BASE_PLAYER + url
                }

                if (url.startsWith("http") && !urls.contains(url)) {
                    urls.add(url)
                }
            }
        }

        return urls
    }
}
