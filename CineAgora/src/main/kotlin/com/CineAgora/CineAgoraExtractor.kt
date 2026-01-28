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
    
    // Novo: Adicionar padrão de URL base para os links de série/filme
    private const val BASE_TV_PATH = "/tv/"
    private const val BASE_MOVIE_PATH = "/movie/"

    suspend fun extractVideoLinks(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[CineAgoraExtractor] 🔗 Iniciando extração para: $name - URL: $url")
        
        return when {
            url.contains("watch.brplayer.cc") -> {
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
                    "$BASE_PLAYER${BASE_TV_PATH}$slug"
                } else {
                    "$BASE_PLAYER${BASE_MOVIE_PATH}$slug"
                }
                
                println("[CineAgoraExtractor] 🔗 URL do player gerada: $playerUrl")
                
                if (extractHlsFromWatchPage(playerUrl, name, callback)) {
                    println("[CineAgoraExtractor] 🔗 Sucesso com slug $slug ($type)")
                    return true
                }
            }
            
            // **MÉTODO 2: Procurar por iframes específicos do watch.brplayer.cc/watch**
            println("[CineAgoraExtractor] 🔗 Método 2: Procurando iframes específicos...")
            val iframePatterns = listOf(
                """<iframe[^>]*src=["'](https://watch\.brplayer\.cc/watch\?v=[^"']+)["']""",
                """<iframe[^>]*src=["'](https://watch\.brplayer\.cc/watch/[^"']+)["']""",
                """src=["'](https://watch\.brplayer\.cc/watch[^"']+)["'][^>]*allowfullscreen""",
                """<iframe[^>]*allowfullscreen[^>]*src=["'](https://watch\.brplayer\.cc/[^"']+)["']""",
                """data-src=["'][^"']*["'][^>]*src=["'](https://watch\.brplayer\.cc/[^"']+)["']"""
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
            
            // **MÉTODO 3: Procurar todas as URLs do brplayer no HTML**
            println("[CineAgoraExtractor] 🔗 Método 3: Procurando todas as URLs do player...")
            val fallbackPattern = """https://watch\.brplayer\.cc/[^"'\s<>]+"""
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
                
                // Se não encontrou com /watch, tentar outras URLs do brplayer
                val brplayerUrls = allMatches.map { it.value }.filter { it.contains("brplayer") }
                println("[CineAgoraExtractor] 🔗 Outras URLs brplayer: ${brplayerUrls.size}")
                
                brplayerUrls.forEachIndexed { index, playerUrl ->
                    println("[CineAgoraExtractor] 🔗 Tentando URL brplayer $index: $playerUrl")
                    if (extractHlsFromWatchPage(playerUrl, name, callback)) {
                        println("[CineAgoraExtractor] 🔗 Sucesso com URL brplayer $index")
                        return true
                    }
                }
            }
            
            // **MÉTODO 4: Procurar por iframes genéricos**
            println("[CineAgoraExtractor] 🔗 Método 4: Procurando iframes genéricos...")
            val iframeUrls = extractIframeUrls(html)
            println("[CineAgoraExtractor] 🔗 Iframes encontrados: ${iframeUrls.size}")
            
            iframeUrls.forEachIndexed { index, iframeUrl ->
                println("[CineAgoraExtractor] 🔗 Tentando iframe $index: $iframeUrl")
                if (extractHlsFromWatchPage(iframeUrl, name, callback)) {
                    println("[CineAgoraExtractor] 🔗 Sucesso com iframe $index")
                    return true
                }
            }
            
            // **MÉTODO 5: Procurar por URLs em atributos data-src, src, href, etc.**
            println("[CineAgoraExtractor] 🔗 Método 5: Procurando URLs em atributos...")
            val attributeUrls = extractUrlsFromAttributes(html)
            println("[CineAgoraExtractor] 🔗 URLs em atributos: ${attributeUrls.size}")
            
            attributeUrls.forEachIndexed { index, attrUrl ->
                println("[CineAgoraExtractor] 🔗 Tentando atributo URL $index: $attrUrl")
                if (extractHlsFromWatchPage(attrUrl, name, callback)) {
                    println("[CineAgoraExtractor] 🔗 Sucesso com atributo URL $index")
                    return true
                }
            }
            
            // **MÉTODO 6: Procurar por padrões específicos de streaming**
            println("[CineAgoraExtractor] 🔗 Método 6: Procurando padrões de streaming...")
            val streamingUrls = extractStreamingUrls(html)
            println("[CineAgoraExtractor] 🔗 URLs de streaming: ${streamingUrls.size}")
            
            streamingUrls.forEachIndexed { index, streamUrl ->
                println("[CineAgoraExtractor] 🔗 Tentando streaming URL $index: $streamUrl")
                if (extractHlsFromWatchPage(streamUrl, name, callback)) {
                    println("[CineAgoraExtractor] 🔗 Sucesso com streaming URL $index")
                    return true
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

    // **MÉTODO: Extrair slugs de mídia (/tv/slug ou /movie/slug)**
    private fun extractMediaSlugs(html: String): List<Pair<String, String>> {
        val mediaSlugs = mutableListOf<Pair<String, String>>()
        
        // Padrões para capturar URLs do tipo /tv/slug ou /movie/slug
        val patterns = listOf(
            // Em atributos data-link (que vimos no exemplo: data-link=".../tv/ironheart")
            """data-link=["']([^"']*/(?:tv|movie)/([^"']+?))["']""",
            
            // Em URLs completas
            """["'](https?://[^"']+/(?:tv|movie)/([^"']+?))["']""",
            
            // Em URLs relativas
            """["'](/(?:tv|movie)/([^"']+?))["']""",
            
            // Em atributos src
            """src=["']([^"']*/(?:tv|movie)/([^"']+?))["']""",
            
            // Em atributos href
            """href=["']([^"']*/(?:tv|movie)/([^"']+?))["']""",
            
            // Em iframes
            """<iframe[^>]+src=["']([^"']*/(?:tv|movie)/([^"']+?))["'][^>]*>""",
            
            // Em elementos de player
            """["']player["'][^}]+["']url["']\s*:\s*["']([^"']*/(?:tv|movie)/([^"']+?))["']""",
            
            // Em scripts JSON
            """["'](?:url|src|link)["']\s*:\s*["']([^"']*/(?:tv|movie)/([^"']+?))["']"""
        )
        
        patterns.forEach { pattern ->
            val regex = Regex(pattern, RegexOption.IGNORE_CASE)
            val matches = regex.findAll(html)
            
            matches.forEach { match ->
                val fullUrl = match.groupValues[1]
                val slug = match.groupValues[2]
                
                if (slug.isNotBlank()) {
                    // Determinar tipo (tv ou movie)
                    val type = if (fullUrl.contains("/tv/")) "tv" else "movie"
                    
                    val pair = slug to type
                    if (!mediaSlugs.contains(pair)) {
                        println("[CineAgoraExtractor] 🔗 Slug encontrado: $slug ($type) - URL: ${fullUrl.take(50)}...")
                        mediaSlugs.add(pair)
                    }
                }
            }
        }
        
        // **EXTRA: Procurar por slugs em texto JavaScript/JSON**
        // Muitas vezes o slug aparece em variáveis JavaScript
        val jsPatterns = listOf(
            """["']slug["']\s*:\s*["']([^"']+?)["']""",
            """["']name["']\s*:\s*["']([^"']+?)["']""",
            """["']id["']\s*:\s*["']([^"']+?)["']""",
            """["']title["']\s*:\s*["']([^"']+?)["']""",
            """/(?:tv|movie)/([^/"']+?)["']"""
        )
        
        // Extrair slugs que pareçam ser de mídia (sem espaços, geralmente letras minúsculas com hífens)
        val potentialSlugs = mutableSetOf<String>()
        
        jsPatterns.forEach { pattern ->
            val regex = Regex(pattern, RegexOption.IGNORE_CASE)
            val matches = regex.findAll(html)
            
            matches.forEach { match ->
                val value = match.groupValues[1]
                // Filtrar valores que pareçam ser slugs (ex: ironheart, coracao-de-ferro, etc.)
                if (value.matches(Regex("^[a-z0-9\\-]+$")) && value.length in 2..50) {
                    potentialSlugs.add(value)
                }
            }
        }
        
        // Adicionar slugs potenciais (tentaremos tanto /tv/ quanto /movie/)
        potentialSlugs.forEach { slug ->
            if (!mediaSlugs.any { it.first == slug }) {
                // Tentar primeiro como série (/tv/)
                mediaSlugs.add(slug to "tv")
                // Também tentar como filme (/movie/)
                mediaSlugs.add(slug to "movie")
            }
        }
        
        return mediaSlugs.distinct()
    }

    // **INTEGRAÇÃO COMPLETA: Método de extração HLS do segundo código**
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

    // Métodos auxiliares
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

    // Métodos auxiliares do primeiro código para extração de URLs
    private fun extractAllUrlsFromHtml(html: String): List<String> {
        val urls = mutableListOf<String>()
        
        // Regex para capturar URLs em qualquer contexto
        val urlPatterns = listOf(
            """https?://[^\s"'<>]+""",  // URLs genéricas
            """//[^\s"'<>]+""",         // URLs protocolo relativo
            """"([^"]+\.(?:m3u8|mp4|mkv|avi|mov|wmv|flv)[^"]*)"""", // URLs de vídeo
            """'([^']+\.(?:m3u8|mp4|mkv|avi|mov|wmv|flv)[^']*)'"""  // URLs de vídeo com aspas simples
        )
        
        for (pattern in urlPatterns) {
            val regex = Regex(pattern)
            val matches = regex.findAll(html)
            
            matches.forEach { match ->
                var url = match.value.trim()
                
                // Remover aspas se houver
                if ((url.startsWith("\"") && url.endsWith("\"")) || 
                    (url.startsWith("'") && url.endsWith("'"))) {
                    url = url.substring(1, url.length - 1)
                }
                
                // Converter URL relativa para absoluta se necessário
                if (url.startsWith("//")) {
                    url = "https:" + url
                } else if (url.startsWith("/") && !url.startsWith("//")) {
                    if (url.contains("brplayer")) {
                        url = BASE_PLAYER + (if (url.startsWith("/")) "" else "/") + url
                    }
                }
                
                // Filtrar URLs do brplayer
                if (url.contains("brplayer") && url.startsWith("http") && !urls.contains(url)) {
                    urls.add(url)
                }
            }
        }
        
        return urls.distinct()
    }

    private fun extractIframeUrls(html: String): List<String> {
        val urls = mutableListOf<String>()
        
        // Extrair src de iframes
        val iframePattern = """<iframe[^>]*src=["']([^"']+)["'][^>]*>"""
        val regex = Regex(iframePattern, RegexOption.IGNORE_CASE)
        val matches = regex.findAll(html)
        
        matches.forEach { match ->
            var url = match.groupValues[1].trim()
            
            // Converter URL relativa para absoluta se necessário
            if (url.startsWith("//")) {
                url = "https:" + url
            } else if (url.startsWith("/") && !url.startsWith("//")) {
                url = "https://cineagora.net" + url
            } else if (!url.startsWith("http")) {
                url = "https://cineagora.net/$url"
            }
            
            if (url.startsWith("http") && !urls.contains(url)) {
                urls.add(url)
            }
        }
        
        return urls.distinct()
    }

    private fun extractUrlsFromAttributes(html: String): List<String> {
        val urls = mutableListOf<String>()
        
        // Procurar URLs em vários atributos
        val attributePatterns = listOf(
            """(?:src|href|data-src|data-url|data-href)\s*=\s*["']([^"']+)["']""",
            """(?:src|href|data-src|data-url|data-href)\s*:\s*["']([^"']+)["']""",
            """url\s*\(["']?([^"')]+)["']?\)""",
            """["']([^"']+\.(?:php|html|aspx)[^"']*\?[^"']*v=[^"']+)["']"""
        )
        
        for (pattern in attributePatterns) {
            val regex = Regex(pattern, RegexOption.IGNORE_CASE)
            val matches = regex.findAll(html)
            
            matches.forEach { match ->
                var url = match.groupValues[1].trim()
                
                // Converter URL relativa para absoluta se necessário
                if (url.startsWith("//")) {
                    url = "https:" + url
                } else if (url.startsWith("/") && !url.startsWith("//")) {
                    url = "https://cineagora.net" + url
                } else if (!url.startsWith("http")) {
                    url = "https://cineagora.net/$url"
                }
                
                // Filtrar URLs que podem conter player de vídeo
                if (url.startsWith("http") && 
                    (url.contains("player") || url.contains("watch") || url.contains("video") || 
                     url.contains("embed") || url.contains("stream")) && 
                    !urls.contains(url)) {
                    urls.add(url)
                }
            }
        }
        
        return urls.distinct()
    }

    private fun extractStreamingUrls(html: String): List<String> {
        val urls = mutableListOf<String>()
        
        // Procurar por padrões comuns de streaming
        val streamingPatterns = listOf(
            """(?:file|source|stream|video|src)\s*:\s*["']([^"']+)["']""",
            """["'](?:file|source|stream)["']\s*:\s*["']([^"']+)["']""",
            """m3u8["']?\s*[=:]\s*["']([^"']+)["']""",
            """master\.(?:txt|m3u8)["']?\s*[=:]\s*["']([^"']+)["']""",
            """["'](https?://[^"']+\.m3u8[^"']*)["']""",
            """["'](/[^"']+\.m3u8[^"']*)["']"""
        )
        
        for (pattern in streamingPatterns) {
            val regex = Regex(pattern, RegexOption.IGNORE_CASE)
            val matches = regex.findAll(html)
            
            matches.forEach { match ->
                var url = match.groupValues[1].trim()
                
                // Converter URL relativa para absoluta se necessário
                if (url.startsWith("//")) {
                    url = "https:" + url
                } else if (url.startsWith("/") && !url.startsWith("//")) {
                    if (url.contains("brplayer") || url.contains("m3u8") || url.contains("master")) {
                        url = BASE_PLAYER + url
                    } else {
                        url = "https://cineagora.net" + url
                    }
                }
                
                if (url.startsWith("http") && !urls.contains(url)) {
                    urls.add(url)
                }
            }
        }
        
        return urls.distinct()
    }
}
