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
    
    // Lista de fontes que queremos filtrar (apenas "CineAgora")
    private val ALLOWED_SOURCES = listOf("CineAgora")

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
                // Apenas criar link para o CineAgora (que contém todas as qualidades)
                val masterUrl = "$BASE_PLAYER/m3u8/$uid/$md5/master.txt?s=1&id=$videoId&cache=$status"
                println("[CineAgoraExtractor] 🔗 Master URL: $masterUrl")
                
                val headers = mapOf(
                    "Referer" to watchUrl,
                    "Origin" to BASE_PLAYER,
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
                )

                // Gerar M3U8 com todas as qualidades
                try {
                    val links = M3u8Helper.generateM3u8(
                        source = "CineAgora",
                        streamUrl = masterUrl,
                        referer = watchUrl,
                        headers = headers
                    )
                    
                    // Filtrar apenas links da fonte "CineAgora"
                    val filteredLinks = links.filter { link ->
                        ALLOWED_SOURCES.any { allowed -> link.source.contains(allowed, ignoreCase = true) }
                    }
                    
                    println("[CineAgoraExtractor] 🔗 ${filteredLinks.size} links filtrados (de ${links.size} total)")
                    
                    if (filteredLinks.isNotEmpty()) {
                        filteredLinks.forEach { callback(it) }
                        return true
                    }
                    
                    // Se não encontrou links filtrados, criar um link genérico
                    val fallbackLink = newExtractorLink(
                        source = "CineAgora",
                        name = name,
                        url = masterUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = watchUrl
                        this.quality = Qualities.Unknown.value
                        this.headers = headers
                    }
                    callback(fallbackLink)
                    
                    return true
                    
                } catch (e: Exception) {
                    println("[CineAgoraExtractor] 🔗 ❌ Erro ao gerar M3U8: ${e.message}")
                    // Fallback para link direto
                    val fallbackLink = newExtractorLink(
                        source = "CineAgora",
                        name = name,
                        url = masterUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = watchUrl
                        this.quality = Qualities.Unknown.value
                        this.headers = headers
                    }
                    callback(fallbackLink)
                    return true
                }
            }

            // Tentar métodos alternativos se o primeiro falhar
            val masterUrlDirect = extractMasterUrlDirect(html)
            if (masterUrlDirect != null) {
                println("[CineAgoraExtractor] 🔗 Master URL direta encontrada: $masterUrlDirect")
                
                val headers = mapOf(
                    "Referer" to watchUrl,
                    "Origin" to BASE_PLAYER
                )

                val directLink = newExtractorLink(
                    source = "CineAgora",
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
                
                // Usar apenas a primeira URL encontrada (normalmente a melhor)
                val primaryUrl = m3u8Urls.firstOrNull()
                if (primaryUrl != null) {
                    val m3u8Link = newExtractorLink(
                        source = "CineAgora",
                        name = name,
                        url = primaryUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = watchUrl
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf("Referer" to watchUrl)
                    }
                    callback(m3u8Link)
                    return true
                }
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
