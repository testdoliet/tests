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
        
        return when {
            // Se já é uma URL de watch com ?v= (filmes)
            url.contains("watch.brstream.cc/watch?v=") -> {
                println("[CineAgoraExtractor] 🔗 URL de filme com ?v=")
                extractHlsFromWatchPage(url, name, callback)
            }
            // Se já é uma URL de watch com /watch/ (séries)
            url.contains("watch.brstream.cc/watch/") && !url.contains("?v=") -> {
                println("[CineAgoraExtractor] 🔗 URL de série com /watch/")
                extractHlsFromWatchPage(url, name, callback)
            }
            // URL do CineAgora, extrair da página
            url.contains("cineagora.net") -> {
                println("[CineAgoraExtractor] 🔗 URL do CineAgora, extraindo da página")
                extractFromCineAgoraPage(url, name, callback)
            }
            else -> {
                println("[CineAgoraExtractor] 🔗 URL não reconhecida, tentando extração direta")
                extractHlsFromWatchPage(url, name, callback)
            }
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

            // **MÉTODO 1: Procurar por URLs de watch (?v= para filmes, /watch/ para séries)**
            println("[CineAgoraExtractor] 🔗 Método 1: Procurando URLs de watch...")
            
            // Padrões para filmes (?v=video_slug)
            val moviePatterns = listOf(
                """https://watch\.brstream\.cc/watch\?v=([A-Z0-9]+)""",
                """<iframe[^>]*src=["'](https://watch\.brstream\.cc/watch\?v=[^"']+)["']""",
                """data-link=["'](https://watch\.brstream\.cc/watch\?v=[^"']+)["']""",
                """["'](/watch\?v=([A-Z0-9]+))["']"""
            )
            
            // Padrões para séries (/watch/video_slug)
            val seriesPatterns = listOf(
                """https://watch\.brstream\.cc/watch/([A-Z0-9]+)""",
                """<iframe[^>]*src=["'](https://watch\.brstream\.cc/watch/[^"']+)["']""",
                """data-link=["'](https://watch\.brstream\.cc/watch/[^"']+)["']""",
                """["'](/watch/([A-Z0-9]+))["']"""
            )
            
            // Padrões para /tv/ (séries - página da série)
            val tvPatterns = listOf(
                """https://watch\.brstream\.cc/tv/([^"'\s?&]+)""",
                """<iframe[^>]*src=["'](https://watch\.brstream\.cc/tv/[^"']+)["']""",
                """data-link=["'](https://watch\.brstream\.cc/tv/[^"']+)["']"""
            )

            // Primeiro tentar encontrar URLs de filmes (?v=)
            println("[CineAgoraExtractor] 🔗 Buscando URLs de filmes (?v=)...")
            for ((patternIndex, pattern) in moviePatterns.withIndex()) {
                val regex = Regex(pattern)
                val matches = regex.findAll(html).toList()
                
                if (matches.isNotEmpty()) {
                    println("[CineAgoraExtractor] 🔗 Encontrados ${matches.size} matches com padrão $patternIndex")
                    
                    for ((matchIndex, match) in matches.withIndex()) {
                        val fullUrl = match.groupValues.getOrNull(1) ?: match.value
                        var watchUrl = fullUrl
                        
                        // Se for URL relativa (/watch?v=...)
                        if (watchUrl.startsWith("/")) {
                            watchUrl = BASE_PLAYER + watchUrl
                        }
                        
                        println("[CineAgoraExtractor] 🔗 Tentando URL de filme $matchIndex: $watchUrl")
                        
                        if (extractHlsFromWatchPage(watchUrl, name, callback)) {
                            println("[CineAgoraExtractor] 🔗 ✅ Sucesso com URL de filme")
                            return true
                        }
                    }
                }
            }
            
            // Se não encontrou filmes, tentar séries (/watch/video_slug)
            println("[CineAgoraExtractor] 🔗 Buscando URLs de séries (/watch/...)")
            for ((patternIndex, pattern) in seriesPatterns.withIndex()) {
                val regex = Regex(pattern)
                val matches = regex.findAll(html).toList()
                
                if (matches.isNotEmpty()) {
                    println("[CineAgoraExtractor] 🔗 Encontrados ${matches.size} matches com padrão $patternIndex")
                    
                    for ((matchIndex, match) in matches.withIndex()) {
                        val fullUrl = match.groupValues.getOrNull(1) ?: match.value
                        var watchUrl = fullUrl
                        
                        // Se for URL relativa (/watch/...)
                        if (watchUrl.startsWith("/")) {
                            watchUrl = BASE_PLAYER + watchUrl
                        }
                        
                        // Adicionar parâmetros de referência
                        if (!watchUrl.contains("?")) {
                            watchUrl += "?ref=&d=null"
                        }
                        
                        println("[CineAgoraExtractor] 🔗 Tentando URL de série $matchIndex: $watchUrl")
                        
                        if (extractHlsFromWatchPage(watchUrl, name, callback)) {
                            println("[CineAgoraExtractor] 🔗 ✅ Sucesso com URL de série")
                            return true
                        }
                    }
                }
            }
            
            // Se não encontrou URLs diretas de watch, tentar páginas /tv/
            println("[CineAgoraExtractor] 🔗 Buscando páginas de séries (/tv/...)")
            for ((patternIndex, pattern) in tvPatterns.withIndex()) {
                val regex = Regex(pattern)
                val matches = regex.findAll(html).toList()
                
                if (matches.isNotEmpty()) {
                    println("[CineAgoraExtractor] 🔗 Encontrados ${matches.size} matches com padrão $patternIndex")
                    
                    for ((matchIndex, match) in matches.withIndex()) {
                        val fullUrl = match.groupValues.getOrNull(1) ?: match.value
                        var seriesUrl = fullUrl
                        
                        // Se for URL relativa (/tv/...)
                        if (seriesUrl.startsWith("/")) {
                            seriesUrl = BASE_PLAYER + seriesUrl
                        }
                        
                        println("[CineAgoraExtractor] 🔗 Tentando página de série $matchIndex: $seriesUrl")
                        
                        // Extrair video_slug da página da série
                        val videoSlug = extractVideoSlugFromSeriesPage(seriesUrl)
                        if (videoSlug != null) {
                            val watchUrl = "$BASE_PLAYER/watch/$videoSlug?ref=&d=null"
                            println("[CineAgoraExtractor] 🔗 Watch URL gerada: $watchUrl")
                            
                            if (extractHlsFromWatchPage(watchUrl, name, callback)) {
                                return true
                            }
                        }
                    }
                }
            }

            // **MÉTODO 2: Procurar por todas as URLs do brstream**
            println("[CineAgoraExtractor] 🔗 Método 2: Procurando todas as URLs do player...")
            val fallbackPattern = """https://watch\.brstream\.cc/(watch\?v=|watch/|tv/)([^"'\s<>?&]+)"""
            val allMatches = Regex(fallbackPattern).findAll(html).toList()
            
            if (allMatches.isNotEmpty()) {
                println("[CineAgoraExtractor] 🔗 Encontradas ${allMatches.size} URLs no total")
                
                allMatches.forEachIndexed { index, match ->
                    val pathType = match.groupValues[1] // watch?v=, watch/ ou tv/
                    val slug = match.groupValues[2]
                    val playerUrl = match.value
                    
                    println("[CineAgoraExtractor] 🔗 URL $index: $playerUrl (tipo: $pathType, slug: $slug)")
                    
                    when {
                        pathType.contains("watch?v=") -> {
                            // É filme com ?v=
                            if (extractHlsFromWatchPage(playerUrl, name, callback)) {
                                println("[CineAgoraExtractor] 🔗 ✅ Sucesso com URL de filme (?v=)")
                                return true
                            }
                        }
                        pathType.contains("watch/") && !pathType.contains("?v=") -> {
                            // É série com /watch/
                            val watchUrl = if (!playerUrl.contains("?")) {
                                "$playerUrl?ref=&d=null"
                            } else {
                                playerUrl
                            }
                            if (extractHlsFromWatchPage(watchUrl, name, callback)) {
                                println("[CineAgoraExtractor] 🔗 ✅ Sucesso com URL de série (/watch/)")
                                return true
                            }
                        }
                        pathType.contains("tv/") -> {
                            // É página da série
                            val videoSlug = extractVideoSlugFromSeriesPage(playerUrl)
                            if (videoSlug != null) {
                                val watchUrl = "$BASE_PLAYER/watch/$videoSlug?ref=&d=null"
                                if (extractHlsFromWatchPage(watchUrl, name, callback)) {
                                    println("[CineAgoraExtractor] 🔗 ✅ Sucesso com video_slug da série")
                                    return true
                                }
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

    // **MÉTODO PRINCIPAL: EXTRAIR HLS DA PÁGINA DE WATCH**
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
                "Referer" to if (watchUrl.contains("/tv/")) watchUrl else "https://watch.brstream.cc/tv/severance",
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
                // ACESSE AS PROPRIEDADES DIRETAMENTE
                println("[CineAgoraExtractor] 🔗 ✅ Dados extraídos - UID: ${videoParams.uid}, MD5: ${videoParams.md5}, VideoID: ${videoParams.id}, Status: ${videoParams.status}")
                
                // Construir URL do HLS
                val masterUrl = "$BASE_PLAYER/m3u8/${videoParams.uid}/${videoParams.md5}/master.txt?s=1&id=${videoParams.id}&cache=${videoParams.status}"
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
    private fun extractVideoParams(html: String): VideoParams? {
        println("[CineAgoraExtractor] 🔗 Extraindo parâmetros do vídeo do HTML...")
        
        // Padrão 1: Buscar por var video = { ... } (exato do HTML que você mostrou)
        val videoPattern = """var\s+video\s*=\s*\{[^}]+\}"""
        val videoMatch = Regex(videoPattern, RegexOption.DOT_MATCHES_ALL).find(html)
        
        if (videoMatch != null) {
            val videoJson = videoMatch.value
            println("[CineAgoraExtractor] 🔗 Video JSON encontrado: ${videoJson.take(150)}...")
            
            // Extrair os parâmetros exatamente como estão no HTML
            val uid = extractFromRegex(videoJson, """"uid"\s*:\s*"([^"]+)"""")
            val md5 = extractFromRegex(videoJson, """"md5"\s*:\s*"([^"]+)"""")
            val id = extractFromRegex(videoJson, """"id"\s*:\s*"([^"]+)"""")
            val status = extractFromRegex(videoJson, """"status"\s*:\s*"([^"]+)"""") ?: "1"
            
            println("[CineAgoraExtractor] 🔗 Extraído do JSON - UID: $uid, MD5: $md5, ID: $id, Status: $status")
            
            if (uid != null && md5 != null && id != null) {
                return VideoParams(uid, md5, id, status)
            }
        }
        
        // Padrão 2: Buscar parâmetros individualmente no HTML inteiro
        println("[CineAgoraExtractor] 🔗 Buscando parâmetros individualmente no HTML...")
        val uid = extractFromRegex(html, """"uid"\s*:\s*"([^"]+)"""")
        val md5 = extractFromRegex(html, """"md5"\s*:\s*"([^"]+)"""")
        val id = extractFromRegex(html, """"id"\s*:\s*"([^"]+)"""")
        val status = extractFromRegex(html, """"status"\s*:\s*"([^"]+)"""") ?: "1"
        
        println("[CineAgoraExtractor] 🔗 Extraído individual - UID: $uid, MD5: $md5, ID: $id, Status: $status")
        
        if (uid != null && md5 != null && id != null) {
            return VideoParams(uid, md5, id, status)
        }
        
        // Padrão 3: Buscar na configuração do JW Player (outro lugar comum)
        println("[CineAgoraExtractor] 🔗 Buscando na configuração do JW Player...")
        val configPattern = """jwplayer\('[^']+'\)\.setup\(([\s\S]*?)\);"""
        val configMatch = Regex(configPattern).find(html)
        
        if (configMatch != null) {
            val configText = configMatch.groupValues[1]
            println("[CineAgoraExtractor] 🔗 Config JW Player encontrada: ${configText.take(200)}...")
            
            // Tentar extrair do objeto video dentro da configuração
            val videoInConfig = extractFromRegex(configText, """video"\s*:\s*\{([^}]+)\}""")
            if (videoInConfig != null) {
                val uid2 = extractFromRegex(videoInConfig, """"uid"\s*:\s*"([^"]+)"""")
                val md5_2 = extractFromRegex(videoInConfig, """"md5"\s*:\s*"([^"]+)"""")
                val id2 = extractFromRegex(videoInConfig, """"id"\s*:\s*"([^"]+)"""")
                val status2 = extractFromRegex(videoInConfig, """"status"\s*:\s*"([^"]+)"""") ?: "1"
                
                if (uid2 != null && md5_2 != null && id2 != null) {
                    return VideoParams(uid2, md5_2, id2, status2)
                }
            }
        }
        
        println("[CineAgoraExtractor] 🔗 ❌ Não conseguiu extrair parâmetros do vídeo")
        return null
    }

    private fun extractFromRegex(text: String, pattern: String): String? {
        val regex = Regex(pattern)
        val match = regex.find(text)
        return match?.groupValues?.getOrNull(1)
    }

    private fun extractM3u8UrlDirect(html: String): String? {
        println("[CineAgoraExtractor] 🔗 Buscando URL M3U8 diretamente...")
        
        val patterns = listOf(
            // Padrão exato do HTML: file: `/m3u8/${video.uid}/${video.md5}/master.txt?s=1&id=${video.id}&cache=${video.status}`
            """file\s*:\s*["']/m3u8/([^/]+)/([^/]+)/master\.txt\?s=1&id=([^&]+)&cache=([^"']+)["']""",
            """"file"\s*:\s*["']([^"']+/m3u8/[^"']+\.txt[^"']*)["']""",
            """sources\s*:\s*\[\{.*?"file"\s*:\s*["']([^"']+\.txt[^"']*)["']""",
            """master\.txt\?s=1&id=\d+&cache=\d+""",
            """["'](https?://[^"']+\.m3u8[^"']*)["']""",
            """["'](/m3u8/[^"']+\.txt[^"']*)["']"""
        )
        
        for ((index, pattern) in patterns.withIndex()) {
            val regex = Regex(pattern, RegexOption.DOT_MATCHES_ALL)
            val match = regex.find(html)
            if (match != null) {
                var url = match.groupValues.getOrNull(1) ?: match.value
                
                // Se a URL começa com /m3u8/ mas não tem http://
                if (url.startsWith("/m3u8/") && !url.startsWith("//")) {
                    url = BASE_PLAYER + url
                }
                
                println("[CineAgoraExtractor] 🔗 ✅ URL M3U8 encontrada (padrão $index): $url")
                return url
            }
        }
        
        println("[CineAgoraExtractor] 🔗 ❌ Nenhuma URL M3U8 encontrada diretamente")
        return null
    }

    // **Data class para armazenar os parâmetros do vídeo**
    data class VideoParams(
        val uid: String,
        val md5: String,
        val id: String,
        val status: String
    )
}
