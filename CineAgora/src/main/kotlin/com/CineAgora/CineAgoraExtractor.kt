package com.CineAgora

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

object CineAgoraExtractor {
    private const val BASE_PLAYER = "https://watch.brstream.cc"
    private const val REFERER_CINEAGORA = "https://cineagora.net/"
    private const val PRIMARY_SOURCE = "CineAgora"

    suspend fun extractVideoLinks(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[CineAgoraExtractor] 🔗 Iniciando extração para: $name - URL: $url")
        
        // Se já é uma URL de watch com video_slug, extrair direto
        return if (url.contains("/watch/") && url.contains("brstream.cc")) {
            println("[CineAgoraExtractor] 🔗 URL direta do watch page")
            extractHlsFromWatchPage(url, name, callback)
        } else {
            // Para outros tipos de URL, extrair da página
            extractFromCineAgoraPage(url, name, callback)
        }
    }

    private suspend fun extractFromCineAgoraPage(
        cineAgoraUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[CineAgoraExtractor] 🔗 Extraindo da página: $cineAgoraUrl")
        
        try {
            val html = app.get(cineAgoraUrl, referer = REFERER_CINEAGORA).text
            println("[CineAgoraExtractor] 🔗 Página carregada: ${html.length} caracteres")

            // **MÉTODO 1: Procurar por iframes do brstream**
            println("[CineAgoraExtractor] 🔗 Método 1: Procurando iframes...")
            val iframePatterns = listOf(
                """<iframe[^>]*src=["'](https://watch\.brstream\.cc/watch/([^"']+))["']""",
                """<iframe[^>]*src=["'](https://watch\.brstream\.cc/tv/([^"']+))["']""",
                """<iframe[^>]*src=["'](https://watch\.brstream\.cc/movie/([^"']+))["']""",
                """src=["'](https://watch\.brstream\.cc/watch/([^"']+))["'][^>]*allowfullscreen""",
                """data-link=["'](https://watch\.brstream\.cc/watch/([^"']+))["']""",
                """data-link=["'](https://watch\.brstream\.cc/tv/([^"']+))["']"""
            )

            for ((index, pattern) in iframePatterns.withIndex()) {
                val match = Regex(pattern).find(html)
                if (match != null) {
                    var playerUrl = match.groupValues[1]
                    val slug = match.groupValues[2]
                    
                    println("[CineAgoraExtractor] 🔗 Iframe encontrado (padrão $index): $playerUrl (slug: $slug)")
                    
                    // Se for /tv/, precisamos extrair o video_slug da página da série
                    if (playerUrl.contains("/tv/")) {
                        println("[CineAgoraExtractor] 🔗 É uma série, extraindo video_slug da página da série")
                        val videoSlug = extractVideoSlugFromSeriesPage(playerUrl)
                        if (videoSlug != null) {
                            val watchUrl = "$BASE_PLAYER/watch/$videoSlug?ref=&d=null"
                            println("[CineAgoraExtractor] 🔗 Watch URL gerada: $watchUrl")
                            if (extractHlsFromWatchPage(watchUrl, name, callback)) {
                                return true
                            }
                        }
                    } else if (playerUrl.contains("/watch/")) {
                        // Já é URL de watch direta
                        if (extractHlsFromWatchPage(playerUrl, name, callback)) {
                            return true
                        }
                    }
                }
            }
            
            // **MÉTODO 2: Procurar por todas as URLs do brstream**
            println("[CineAgoraExtractor] 🔗 Método 2: Procurando todas as URLs do player...")
            val fallbackPattern = """https://watch\.brstream\.cc/(watch|tv|movie)/([^"'\s<>?&]+)"""
            val allMatches = Regex(fallbackPattern).findAll(html).toList()
            
            if (allMatches.isNotEmpty()) {
                println("[CineAgoraExtractor] 🔗 Encontradas ${allMatches.size} URLs no total")
                
                allMatches.forEachIndexed { index, match ->
                    val pathType = match.groupValues[1] // watch, tv ou movie
                    val slug = match.groupValues[2]
                    val playerUrl = match.value
                    
                    println("[CineAgoraExtractor] 🔗 URL $index: $playerUrl (tipo: $pathType, slug: $slug)")
                    
                    when (pathType) {
                        "watch" -> {
                            // Já é URL de watch direta
                            if (extractHlsFromWatchPage(playerUrl, name, callback)) {
                                println("[CineAgoraExtractor] 🔗 ✅ Sucesso com URL watch")
                                return true
                            }
                        }
                        "tv" -> {
                            // É página da série, precisamos extrair video_slug
                            val videoSlug = extractVideoSlugFromSeriesPage(playerUrl)
                            if (videoSlug != null) {
                                val watchUrl = "$BASE_PLAYER/watch/$videoSlug?ref=&d=null"
                                if (extractHlsFromWatchPage(watchUrl, name, callback)) {
                                    println("[CineAgoraExtractor] 🔗 ✅ Sucesso com video_slug da série")
                                    return true
                                }
                            }
                        }
                        "movie" -> {
                            // É filme, talvez precise extrair de forma diferente
                            if (extractHlsFromWatchPage(playerUrl, name, callback)) {
                                println("[CineAgoraExtractor] 🔗 ✅ Sucesso com URL de filme")
                                return true
                            }
                        }
                    }
                }
            }

            println("[CineAgoraExtractor] 🔗 ❌ Nenhum player encontrado após todos os métodos")
            return false
        } catch (e: Exception) {
            println("[CineAgoraExtractor] 🔗 ❌ Erro na extração: ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    // **EXTRAIR VIDEO_SLUG DA PÁGINA DA SÉRIE**
    private suspend fun extractVideoSlugFromSeriesPage(seriesUrl: String): String? {
        println("[CineAgoraExtractor] 🔗 Extraindo video slug da página da série: $seriesUrl")
        
        try {
            val html = app.get(seriesUrl, referer = REFERER_CINEAGORA).text
            
            // Padrões para encontrar video_slug
            val patterns = listOf(
                """video_slug["']\s*:\s*["']([^"']+)["']""",
                """["']slug["']\s*:\s*["']([^"']+)["']""",
                """/watch/([^"'\s<>/]+)""",
                """data-link=["']([^"']+)["'].*?video_slug""",
                """var\s+video_slug\s*=\s*["']([^"']+)["']""",
                """video_slug\s*=\s*["']([^"']+)["']"""
            )
            
            for (pattern in patterns) {
                val match = Regex(pattern).find(html)
                if (match != null) {
                    val slug = match.groupValues[1]
                    if (slug.isNotBlank() && slug.matches(Regex("^[A-Z0-9]+$"))) {
                        println("[CineAgoraExtractor] 🔗 ✅ Video slug encontrado (padrão '$pattern'): $slug")
                        return slug
                    }
                }
            }
            
            println("[CineAgoraExtractor] 🔗 ❌ Nenhum video slug encontrado na página da série")
            return null
        } catch (e: Exception) {
            println("[CineAgoraExtractor] 🔗 ❌ Erro ao extrair video slug: ${e.message}")
            return null
        }
    }

    // **MÉTODO PRINCIPAL: EXTRAIR HLS DA PÁGINA /watch/{video_slug}**
    private suspend fun extractHlsFromWatchPage(
        watchUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[CineAgoraExtractor] 🔗 Extraindo do watch page: $watchUrl")

        try {
            // Headers baseados no curl que você compartilhou
            val headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                "Accept-Language" to "pt-BR",
                "Cache-Control" to "no-cache",
                "Pragma" to "no-cache",
                "Referer" to "https://watch.brstream.cc/tv/severance",
                "Sec-Fetch-Dest" to "iframe",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "same-origin",
                "Upgrade-Insecure-Requests" to "1",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"
            )
            
            val html = app.get(watchUrl, headers = headers).text
            println("[CineAgoraExtractor] 🔗 Watch page HTML obtido (${html.length} caracteres)")

            // **EXTRAIR OS PARÂMETROS DO HTML**
            val videoParams = extractVideoParams(html)
            if (videoParams != null) {
                val (uid, md5, videoId, status) = videoParams
                println("[CineAgoraExtractor] 🔗 ✅ Dados extraídos - UID: $uid, MD5: $md5, VideoID: $videoId, Status: $status")
                
                // Construir URL do HLS
                val masterUrl = "$BASE_PLAYER/m3u8/$uid/$md5/master.txt?s=1&id=$videoId&cache=$status"
                println("[CineAgoraExtractor] 🔗 Master URL: $masterUrl")
                
                // Headers para a requisição do HLS
                val hlsHeaders = mapOf(
                    "Referer" to watchUrl,
                    "Origin" to BASE_PLAYER,
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
                )

                try {
                    // Gerar links M3U8
                    val allLinks = M3u8Helper.generateM3u8(
                        source = PRIMARY_SOURCE,
                        streamUrl = masterUrl,
                        referer = watchUrl,
                        headers = hlsHeaders
                    )
                    
                    println("[CineAgoraExtractor] 🔗 ${allLinks.size} links M3U8 gerados")
                    
                    allLinks.forEach { link ->
                        callback(link)
                    }
                    
                    return true
                    
                } catch (e: Exception) {
                    println("[CineAgoraExtractor] 🔗 ❌ Erro ao gerar M3U8: ${e.message}")
                    // Fallback: criar link direto
                    val fallbackLink = newExtractorLink(
                        source = PRIMARY_SOURCE,
                        name = name,
                        url = masterUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = watchUrl
                        this.quality = Qualities.Unknown.value
                        this.headers = hlsHeaders
                    }
                    callback(fallbackLink)
                    
                    return true
                }
            }

            // Método alternativo: procurar URL m3u8 diretamente no HTML
            val m3u8Url = extractM3u8UrlDirect(html)
            if (m3u8Url != null) {
                println("[CineAgoraExtractor] 🔗 ✅ URL M3U8 encontrada diretamente: $m3u8Url")
                
                val hlsHeaders = mapOf(
                    "Referer" to watchUrl,
                    "Origin" to BASE_PLAYER
                )

                val directLink = newExtractorLink(
                    source = PRIMARY_SOURCE,
                    name = name,
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = watchUrl
                    this.quality = Qualities.Unknown.value
                    this.headers = hlsHeaders
                }
                callback(directLink)
                return true
            }

            println("[CineAgoraExtractor] 🔗 ❌ Nenhuma URL de vídeo encontrada")
            return false
            
        } catch (e: Exception) {
            println("[CineAgoraExtractor] 🔗 ❌ Erro ao extrair do watch page: ${e.message}")
            return false
        }
    }

    // **MÉTODO: Extrair parâmetros do vídeo do HTML**
    private fun extractVideoParams(html: String): Triple<String, String, String, String>? {
        // Padrão 1: Buscar por var video = { ... }
        val videoPattern = """var\s+video\s*=\s*\{[^}]+\}"""
        val videoMatch = Regex(videoPattern, RegexOption.DOT_MATCHES_ALL).find(html)
        
        if (videoMatch != null) {
            val videoJson = videoMatch.value
            println("[CineAgoraExtractor] 🔗 Video JSON encontrado: ${videoJson.take(100)}...")
            
            // Extrair os parâmetros
            val uid = extractFromJson(videoJson, "uid")
            val md5 = extractFromJson(videoJson, "md5")
            val id = extractFromJson(videoJson, "id")
            val status = extractFromJson(videoJson, "status") ?: "1"
            
            if (uid != null && md5 != null && id != null) {
                return Triple(uid, md5, id, status)
            }
        }
        
        // Padrão 2: Buscar parâmetros individualmente
        val uid = extractFromRegex(html, """"uid"\s*:\s*"([^"]+)"""")
        val md5 = extractFromRegex(html, """"md5"\s*:\s*"([^"]+)"""")
        val id = extractFromRegex(html, """"id"\s*:\s*"([^"]+)"""")
        val status = extractFromRegex(html, """"status"\s*:\s*"([^"]+)"""") ?: "1"
        
        if (uid != null && md5 != null && id != null) {
            return Triple(uid, md5, id, status)
        }
        
        return null
    }

    private fun extractFromJson(json: String, key: String): String? {
        val pattern = """"$key"\s*:\s*"([^"]+)""""
        return extractFromRegex(json, pattern)
    }

    private fun extractFromRegex(text: String, pattern: String): String? {
        val regex = Regex(pattern)
        val match = regex.find(text)
        return match?.groupValues?.getOrNull(1)
    }

    private fun extractM3u8UrlDirect(html: String): String? {
        val patterns = listOf(
            """"file"\s*:\s*["']([^"']+/m3u8/[^"']+\.txt[^"']*)["']""",
            """sources\s*:\s*\[\{.*?"file"\s*:\s*["']([^"']+\.txt[^"']*)["']""",
            """master\.txt[?&]s=1&id=\d+""",
            """["'](https?://[^"']+\.m3u8[^"']*)["']""",
            """["'](/m3u8/[^"']+\.txt[^"']*)["']"""
        )
        
        for (pattern in patterns) {
            val regex = Regex(pattern, RegexOption.DOT_MATCHES_ALL)
            val match = regex.find(html)
            if (match != null) {
                var url = match.groupValues.getOrNull(1) ?: match.value
                
                if (url.startsWith("/") && !url.startsWith("//")) {
                    url = BASE_PLAYER + url
                }
                
                println("[CineAgoraExtractor] 🔗 URL M3U8 encontrada (padrão '$pattern'): $url")
                return url
            }
        }
        
        return null
    }
}
