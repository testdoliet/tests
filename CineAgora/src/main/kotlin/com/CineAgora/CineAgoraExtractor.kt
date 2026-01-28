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
            url.contains("watch.brstream.cc") -> {
                println("[CineAgoraExtractor] 🔗 URL direta do player")
                extractHlsFromWatchPage(url, name, callback)
            }
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

            // **MÉTODO 1: Procurar por padrões específicos de série/filme (/tv/slug ou /movie/slug)**
            println("[CineAgoraExtractor] 🔗 Método 1: Procurando padrões /tv/ e /movie/...")
            val mediaSlugs = extractMediaSlugs(html)
            println("[CineAgoraExtractor] 🔗 Encontrados ${mediaSlugs.size} slugs de mídia")
            
            mediaSlugs.forEachIndexed { index, (slug, type) ->
                println("[CineAgoraExtractor] 🔗 Processando slug $index: $slug ($type)")
                
                // Construir URL do player baseado no tipo (tv ou movie)
                val playerUrl = if (type == "tv") {
                    "$BASE_PLAYER/tv/$slug"
                } else {
                    "$BASE_PLAYER/movie/$slug"
                }
                
                println("[CineAgoraExtractor] 🔗 URL do player gerada: $playerUrl")
                
                // Extrair video_slug da página da série
                val videoSlug = extractVideoSlugFromSeriesPage(playerUrl)
                if (videoSlug != null) {
                    println("[CineAgoraExtractor] 🔗 Video slug encontrado: $videoSlug")
                    
                    // Agora fazemos a requisição para o endpoint /watch/{video_slug}
                    val watchUrl = "$BASE_PLAYER/watch/$videoSlug?ref=&d=null"
                    println("[CineAgoraExtractor] 🔗 Watch URL: $watchUrl")
                    
                    if (extractHlsFromWatchPage(watchUrl, name, callback)) {
                        println("[CineAgoraExtractor] 🔗 Sucesso com video slug $videoSlug")
                        return true
                    }
                } else {
                    // Se não encontrou video_slug, tenta extrair direto da página /tv/slug
                    if (extractHlsFromWatchPage(playerUrl, name, callback)) {
                        println("[CineAgoraExtractor] 🔗 Sucesso com slug $slug ($type)")
                        return true
                    }
                }
            }
            
            // **MÉTODO 2: Procurar por iframes específicos**
            println("[CineAgoraExtractor] 🔗 Método 2: Procurando iframes específicos...")
            val iframePatterns = listOf(
                """<iframe[^>]*src=["'](https://watch\.brstream\.cc/watch[^"']+)["']""",
                """<iframe[^>]*src=["'](https://watch\.brstream\.cc/tv/[^"']+)["']""",
                """src=["'](https://watch\.brstream\.cc/watch[^"']+)["'][^>]*allowfullscreen""",
                """data-link=["'](https://watch\.brstream\.cc/[^"']+)["']"""
            )

            iframePatterns.forEachIndexed { patternIndex, pattern ->
                val match = Regex(pattern).find(html)
                if (match != null) {
                    var playerUrl = match.groupValues[1]
                    if (!playerUrl.startsWith("http")) {
                        playerUrl = BASE_PLAYER + (if (playerUrl.startsWith("/")) "" else "/") + playerUrl
                    }
                    println("[CineAgoraExtractor] 🔗 Iframe encontrado (padrão $patternIndex): $playerUrl")
                    if (extractHlsFromWatchPage(playerUrl, name, callback)) {
                        return true
                    }
                }
            }
            
            // **MÉTODO 3: Procurar todas as URLs do brstream no HTML**
            println("[CineAgoraExtractor] 🔗 Método 3: Procurando todas as URLs do player...")
            val fallbackPattern = """https://watch\.brstream\.cc/[^"'\s<>]+"""
            val allMatches = Regex(fallbackPattern).findAll(html).toList()
            
            if (allMatches.isNotEmpty()) {
                println("[CineAgoraExtractor] 🔗 Encontradas ${allMatches.size} URLs no total")
                
                // Priorizar URLs com /watch
                val watchUrls = allMatches.map { it.value }.filter { it.contains("/watch") }
                println("[CineAgoraExtractor] 🔗 URLs com /watch: ${watchUrls.size}")
                
                watchUrls.forEachIndexed { index, playerUrl ->
                    println("[CineAgoraExtractor] 🔗 Tentando URL watch $index: $playerUrl")
                    if (extractHlsFromWatchPage(playerUrl, name, callback)) {
                        println("[CineAgoraExtractor] 🔗 Sucesso com URL watch $index")
                        return true
                    }
                }
                
                // Se não encontrou com /watch, tentar outras URLs do brstream
                val brstreamUrls = allMatches.map { it.value }.filter { it.contains("brstream") }
                println("[CineAgoraExtractor] 🔗 Outras URLs brstream: ${brstreamUrls.size}")
                
                brstreamUrls.forEachIndexed { index, playerUrl ->
                    println("[CineAgoraExtractor] 🔗 Tentando URL brstream $index: $playerUrl")
                    if (extractHlsFromWatchPage(playerUrl, name, callback)) {
                        println("[CineAgoraExtractor] 🔗 Sucesso com URL brstream $index")
                        return true
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

    // **NOVO MÉTODO: Extrair video_slug da página da série (/tv/slug)**
    private suspend fun extractVideoSlugFromSeriesPage(seriesUrl: String): String? {
        println("[CineAgoraExtractor] 🔗 Extraindo video slug da página da série: $seriesUrl")
        
        try {
            val html = app.get(seriesUrl, referer = REFERER_CINEAGORA).text
            println("[CineAgoraExtractor] 🔗 Página da série carregada: ${html.length} caracteres")
            
            // Padrão 1: Buscar por variável JavaScript com video_slug
            val patterns = listOf(
                """video_slug["']\s*:\s*["']([^"']+)["']""",
                """["']slug["']\s*:\s*["']([^"']+)["']""",
                """/watch/([^"'\s<>/]+)""",
                """data-link=["']([^"']+)["'].*?video_slug"""
            )
            
            patterns.forEach { pattern ->
                val match = Regex(pattern).find(html)
                if (match != null) {
                    val slug = match.groupValues[1]
                    if (slug.isNotBlank() && slug.matches(Regex("^[A-Z0-9]+$"))) {
                        println("[CineAgoraExtractor] 🔗 Video slug encontrado (padrão '$pattern'): $slug")
                        return slug
                    }
                }
            }
            
            // Padrão 2: Buscar por JSON com informações da série
            val jsonPattern = """\{.*?"seasons".*?\}"""
            val jsonMatch = Regex(jsonPattern, RegexOption.DOT_MATCHES_ALL).find(html)
            if (jsonMatch != null) {
                val jsonText = jsonMatch.value
                println("[CineAgoraExtractor] 🔗 JSON encontrado: ${jsonText.take(200)}...")
                
                // Tentar extrair o primeiro video_slug do JSON
                val slugMatch = Regex(""""video_slug"\s*:\s*"([^"]+)"""").find(jsonText)
                if (slugMatch != null) {
                    val slug = slugMatch.groupValues[1]
                    println("[CineAgoraExtractor] 🔗 Video slug extraído do JSON: $slug")
                    return slug
                }
            }
            
            println("[CineAgoraExtractor] 🔗 Nenhum video slug encontrado na página da série")
            return null
        } catch (e: Exception) {
            println("[CineAgoraExtractor] 🔗 ❌ Erro ao extrair video slug: ${e.message}")
            return null
        }
    }

    // **MÉTODO: Extrair slugs de mídia (/tv/slug ou /movie/slug)**
    private fun extractMediaSlugs(html: String): List<Pair<String, String>> {
        val mediaSlugs = mutableListOf<Pair<String, String>>()
        
        val patterns = listOf(
            """data-link=["']([^"']*/(?:tv|movie)/([^"']+?))["']""",
            """["'](https?://[^"']+/(?:tv|movie)/([^"']+?))["']""",
            """["'](/(?:tv|movie)/([^"']+?))["']""",
            """href=["']([^"']*/(?:tv|movie)/([^"']+?))["']""",
            """<iframe[^>]+src=["']([^"']*/(?:tv|movie)/([^"']+?))["'][^>]*>"""
        )
        
        patterns.forEach { pattern ->
            val regex = Regex(pattern, RegexOption.IGNORE_CASE)
            val matches = regex.findAll(html)
            
            matches.forEach { match ->
                val fullUrl = match.groupValues[1]
                val slug = match.groupValues[2]
                
                if (slug.isNotBlank()) {
                    val type = if (fullUrl.contains("/tv/")) "tv" else "movie"
                    val pair = slug to type
                    if (!mediaSlugs.contains(pair)) {
                        println("[CineAgoraExtractor] 🔗 Slug encontrado: $slug ($type) - URL: ${fullUrl.take(50)}...")
                        mediaSlugs.add(pair)
                    }
                }
            }
        }
        
        return mediaSlugs.distinct()
    }

    // **MÉTODO PRINCIPAL: Extrair HLS da página /watch/{video_slug}**
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

            // **EXTRAIR OS PARÂMETROS DO HTML QUE VOCÊ COMPARTILHOU**
            val videoParams = extractVideoParams(html)
            if (videoParams != null) {
                val (uid, md5, videoId, status) = videoParams
                println("[CineAgoraExtractor] 🔗 Dados extraídos - UID: $uid, MD5: $md5, VideoID: $videoId, Status: $status")
                
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
                        source = "CineAgora",
                        streamUrl = masterUrl,
                        referer = watchUrl,
                        headers = hlsHeaders
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
                    
                    // 1. Primeiro enviar os links da fonte principal
                    if (primaryLinks.isNotEmpty()) {
                        println("[CineAgoraExtractor] 🔗 Enviando ${primaryLinks.size} links da fonte principal ($PRIMARY_SOURCE)")
                        primaryLinks.forEach { callback(it) }
                    }
                    
                    // 2. Depois enviar os links secundários
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
                            this.headers = hlsHeaders
                        }
                        callback(fallbackLink)
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
                println("[CineAgoraExtractor] 🔗 URL M3U8 encontrada diretamente: $m3u8Url")
                
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
