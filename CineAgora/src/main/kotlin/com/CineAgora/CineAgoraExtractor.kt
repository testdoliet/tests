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
    
    // Adicionar constantes para tags de log
    private const val TAG = "CineAgoraExtractor"
    private fun logInfo(message: String) = println("ℹ️ [$TAG] $message")
    private fun logSuccess(message: String) = println("✅ [$TAG] $message")
    private fun logWarning(message: String) = println("⚠️ [$TAG] $message")
    private fun logError(message: String, e: Exception? = null) {
        println("❌ [$TAG] $message")
        e?.printStackTrace()
    }
    private fun logDebug(message: String) = println("🔍 [$TAG] $message")

    suspend fun extractVideoLinks(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        logInfo("Iniciando extração de links para: $name")
        logDebug("URL recebida: $url")
        
        return when {
            url.contains("watch.brplayer.cc") -> {
                logDebug("URL é do player brplayer, extraindo diretamente")
                extractHlsFromWatchPage(url, name, callback)
            }
            url.contains("cineagora.net") -> {
                logDebug("URL é do CineAgora, extraindo da página")
                extractFromCineAgoraPage(url, name, callback)
            }
            else -> {
                logWarning("URL não reconhecida, tentando extração direta")
                extractHlsFromWatchPage(url, name, callback)
            }
        }
    }

    private suspend fun extractFromCineAgoraPage(
        cineAgoraUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        logInfo("Extraindo da página do CineAgora: $cineAgoraUrl")
        
        try {
            logDebug("Fazendo requisição para a página")
            val html = app.get(cineAgoraUrl, referer = REFERER_CINEAGORA).text
            logDebug("Página carregada, tamanho: ${html.length} caracteres")

            val iframePatterns = listOf(
                """<iframe[^>]*src=["'](https://watch\.brplayer\.cc/watch\?v=[^"']+)["']""",
                """<iframe[^>]*src=["'](https://watch\.brplayer\.cc/watch/[^"']+)["']""",
                """src=["'](https://watch\.brplayer\.cc/watch[^"']+)["'][^>]*allowfullscreen""",
                """<iframe[^>]*allowfullscreen[^>]*src=["'](https://watch\.brplayer\.cc/[^"']+)["']""",
                """data-src=["'][^"']*["'][^>]*src=["'](https://watch\.brplayer\.cc/[^"']+)["']"""
            )

            logDebug("Procurando padrões de iframe (${iframePatterns.size} padrões)")
            
            for ((index, pattern) in iframePatterns.withIndex()) {
                logDebug("Testando padrão ${index + 1}")
                val match = Regex(pattern).find(html)
                if (match != null) {
                    var playerUrl = match.groupValues[1]
                    logSuccess("Iframe encontrado com padrão ${index + 1}: $playerUrl")
                    
                    if (!playerUrl.startsWith("http")) {
                        playerUrl = BASE_PLAYER + (if (playerUrl.startsWith("/")) "" else "/") + playerUrl
                        logDebug("URL normalizada: $playerUrl")
                    }
                    
                    return extractHlsFromWatchPage(playerUrl, name, callback)
                }
            }

            logDebug("Nenhum iframe encontrado nos padrões principais, buscando URLs alternativas")
            val fallbackPattern = """https://watch\.brplayer\.cc/[^"'\s<>]+"""
            val allMatches = Regex(fallbackPattern).findAll(html).toList()
            logDebug("Encontradas ${allMatches.size} URLs alternativas")

            for ((index, match) in allMatches.withIndex()) {
                val playerUrl = match.value
                logDebug("Verificando URL alternativa $index: $playerUrl")
                if (playerUrl.contains("/watch")) {
                    logSuccess("URL alternativa válida encontrada: $playerUrl")
                    return extractHlsFromWatchPage(playerUrl, name, callback)
                }
            }

            logWarning("Nenhum player encontrado na página do CineAgora")
            return false
        } catch (e: Exception) {
            logError("Erro ao extrair da página do CineAgora", e)
            return false
        }
    }

    private suspend fun extractHlsFromWatchPage(
        watchUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        logInfo("Extraindo HLS da página do player: $watchUrl")
        
        try {
            logDebug("Fazendo requisição para a página do player")
            val html = app.get(watchUrl, referer = REFERER_CINEAGORA).text
            logDebug("Página do player carregada, tamanho: ${html.length} caracteres")

            logDebug("Extraindo parâmetros do player")
            val uid = extractFromRegex(html, "\"uid\"\\s*:\\s*\"(\\d+)\"")
            val md5 = extractFromRegex(html, "\"md5\"\\s*:\\s*\"([a-f0-9]{32})\"")
            val videoId = extractFromRegex(html, "\"id\"\\s*:\\s*\"(\\d+)\"")
            val status = extractFromRegex(html, "\"status\"\\s*:\\s*\"([01])\"") ?: "1"

            logDebug("Parâmetros extraídos - UID: $uid, MD5: $md5, VideoID: $videoId, Status: $status")

            if (uid != null && md5 != null && videoId != null) {
                logSuccess("Parâmetros do player encontrados com sucesso")
                val masterUrl = "$BASE_PLAYER/m3u8/$uid/$md5/master.txt?s=1&id=$videoId&cache=$status"
                val altUrl = "$BASE_PLAYER/alternative_stream/$uid/$md5/master.m3u8"
                
                logDebug("URL principal gerada: $masterUrl")
                logDebug("URL alternativa gerada: $altUrl")

                val headers = mapOf(
                    "Referer" to watchUrl,
                    "Origin" to BASE_PLAYER,
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
                )

                logDebug("Tentando gerar links M3U8 através do M3u8Helper")
                val links = try {
                    val generatedLinks = M3u8Helper.generateM3u8(
                        source = "CineAgora",
                        streamUrl = masterUrl,
                        referer = watchUrl,
                        headers = headers
                    )
                    logSuccess("M3u8Helper gerou ${generatedLinks.size} links")
                    generatedLinks
                } catch (e: Exception) {
                    logWarning("M3u8Helper falhou: ${e.message}")
                    emptyList()
                }

                if (links.isNotEmpty()) {
                    logInfo("Adicionando ${links.size} links extraídos ao callback")
                    links.forEachIndexed { index, link ->
                        logDebug("Link $index: ${link.name} - ${link.url.take(50)}...")
                        callback(link)
                    }
                    return true
                }

                logDebug("Criando link principal fallback")
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
                logDebug("Link principal adicionado: ${fallbackLink.url.take(50)}...")

                logDebug("Criando link alternativo")
                val altLink = newExtractorLink(
                    source = "CineAgora (Alt)",
                    name = "$name (Alternativo)",
                    url = altUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = watchUrl
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                }
                callback(altLink)
                logDebug("Link alternativo adicionado: ${altLink.url.take(50)}...")

                logSuccess("Extração via parâmetros concluída com sucesso")
                return true
            } else {
                logWarning("Parâmetros do player não encontrados ou incompletos")
            }

            logDebug("Tentando extração direta da URL mestre")
            val masterUrlDirect = extractMasterUrlDirect(html)
            if (masterUrlDirect != null) {
                logSuccess("URL mestre encontrada diretamente: ${masterUrlDirect.take(80)}...")
                
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
                logDebug("Link direto adicionado")
                return true
            }

            logDebug("Procurando todas as URLs M3U8 na página")
            val m3u8Urls = extractAllM3u8Urls(html)
            logDebug("Encontradas ${m3u8Urls.size} URLs M3U8")
            
            if (m3u8Urls.isNotEmpty()) {
                logInfo("Adicionando ${m3u8Urls.size} URLs M3U8 encontradas")
                m3u8Urls.forEachIndexed { index, m3u8Url ->
                    logDebug("M3U8 URL $index: ${m3u8Url.take(80)}...")
                    val m3u8Link = newExtractorLink(
                        source = "CineAgora",
                        name = name,
                        url = m3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = watchUrl
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf("Referer" to watchUrl)
                    }
                    callback(m3u8Link)
                }
                logSuccess("Extração via URLs M3U8 diretas concluída")
                return true
            }

            logWarning("Nenhum método de extração funcionou para esta URL")
            return false
        } catch (e: Exception) {
            logError("Erro fatal ao extrair da página do player", e)
            return false
        }
    }

    private fun extractFromRegex(text: String, pattern: String): String? {
        val regex = Regex(pattern)
        val match = regex.find(text)
        val result = match?.groupValues?.get(1)
        logDebug("Regex '$pattern' -> $result")
        return result
    }

    private fun extractMasterUrlDirect(html: String): String? {
        logDebug("Iniciando extração direta de URL mestre")
        
        val patterns = listOf(
            """file\s*:\s*['"](/m3u8/\d+/[a-f0-9]+/master\.txt[^'"]*)['"]""",
            """["']sources["']\s*:\s*\[.*?file["']\s*:\s*["']([^"']+master\.txt[^"']*)["']""",
            """master\.txt[?&]s=1&id=\d+""",
            """["']file["']\s*:\s*["']([^"']+\.txt)["']""",
            """src\s*:\s*['"]([^'"]+\.m3u8[^'"]*)['"]"""
        )

        logDebug("Testando ${patterns.size} padrões de extração direta")
        
        for ((index, pattern) in patterns.withIndex()) {
            val regex = Regex(pattern, RegexOption.DOT_MATCHES_ALL)
            val match = regex.find(html)
            if (match != null) {
                var url = match.groupValues.getOrNull(1) ?: match.value
                logDebug("Padrão $index encontrado: $url")

                if (url.startsWith("/")) {
                    url = BASE_PLAYER + url
                    logDebug("URL normalizada (adicionado BASE_PLAYER): $url")
                } else if (!url.startsWith("http")) {
                    url = "$BASE_PLAYER/$url"
                    logDebug("URL normalizada (relativa): $url")
                }

                logSuccess("URL mestre extraída com sucesso via padrão $index")
                return url
            }
        }

        logDebug("Nenhum padrão de extração direta encontrou resultados")
        return null
    }

    private fun extractAllM3u8Urls(html: String): List<String> {
        logDebug("Extraindo todas as URLs M3U8")
        
        val urls = mutableListOf<String>()

        val patterns = listOf(
            """["'](https?://[^"']+\.m3u8[^"']*)["']""",
            """["'](/[^"']+\.m3u8[^"']*)["']""",
            """(https?://[^\s<>"']+\.m3u8)""",
            """(/\S+\.m3u8\S*)"""
        )

        logDebug("Buscando com ${patterns.size} padrões M3U8")
        
        for ((patternIndex, pattern) in patterns.withIndex()) {
            val regex = Regex(pattern)
            val matches = regex.findAll(html)
            val matchCount = matches.count()

            if (matchCount > 0) {
                logDebug("Padrão $patternIndex encontrou $matchCount correspondências")
            }

            matches.forEachIndexed { matchIndex, match ->
                var url = match.value
                logDebug("Padrão $patternIndex, correspondência $matchIndex: $url")
                
                if (url.startsWith("\"") || url.startsWith("'")) {
                    url = url.substring(1, url.length - 1)
                    logDebug("Removidas aspas: $url")
                }

                if (url.startsWith("/") && !url.startsWith("//")) {
                    url = BASE_PLAYER + url
                    logDebug("URL convertida para absoluta: $url")
                }

                if (url.startsWith("http") && !urls.contains(url)) {
                    urls.add(url)
                    logDebug("URL adicionada à lista: ${url.take(80)}...")
                }
            }
        }

        logDebug("Total de URLs M3U8 encontradas: ${urls.size}")
        return urls
    }
}
