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
    
    // Novo: Adicionar padrão de URL base para os links de série/filme
    private const val BASE_TV_PATH = "/tv/"
    private const val BASE_MOVIE_PATH = "/movie/"

    suspend fun extractVideoLinks(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[CineAgora] Iniciando extração para: $name - URL: $url")
        
        return when {
            url.contains("watch.brplayer.cc") -> {
                println("[CineAgora] URL direta do player")
                extractHlsFromWatchPage(url, name, callback)
            }
            url.contains("cineagora.net") -> {
                println("[CineAgora] URL do CineAgora, extraindo da página")
                extractFromCineAgoraPage(url, name, callback)
            }
            else -> {
                println("[CineAgora] URL não reconhecida, tentando extração direta")
                extractHlsFromWatchPage(url, name, callback)
            }
        }
    }

    private suspend fun extractFromCineAgoraPage(
        cineAgoraUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[CineAgora] Extraindo da página: $cineAgoraUrl")
        
        try {
            val html = app.get(cineAgoraUrl, referer = REFERER_CINEAGORA).text
            println("[CineAgora] Página carregada: ${html.length} caracteres")

            // **NOVO MÉTODO 1: Procurar por padrões específicos de série/filme (/tv/slug ou /movie/slug)**
            println("[CineAgora] Método 1: Procurando padrões /tv/ e /movie/...")
            val mediaSlugs = extractMediaSlugs(html)
            println("[CineAgora] Encontrados ${mediaSlugs.size} slugs de mídia")
            
            for ((index, (slug, type)) in mediaSlugs.withIndex()) {
                println("[CineAgora] Processando slug $index: $slug ($type)")
                
                // Construir URL do player baseado no tipo (tv ou movie)
                val playerUrl = if (type == "tv") {
                    "$BASE_PLAYER${BASE_TV_PATH}$slug"
                } else {
                    "$BASE_PLAYER${BASE_MOVIE_PATH}$slug"
                }
                
                println("[CineAgora] URL do player gerada: $playerUrl")
                
                if (extractHlsFromWatchPage(playerUrl, name, callback)) {
                    println("[CineAgora] Sucesso com slug $slug ($type)")
                    return true
                }
            }
            
            // Método 2: Extrair todas as URLs que contenham watch.brplayer.cc
            println("[CineAgora] Método 2: Procurando todas as URLs do player...")
            val allUrls = extractAllUrlsFromHtml(html)
            
            println("[CineAgora] Encontradas ${allUrls.size} URLs no total")
            
            // Priorizar URLs com /watch
            val watchUrls = allUrls.filter { it.contains("/watch") }
            println("[CineAgora] URLs com /watch: ${watchUrls.size}")
            
            for ((index, playerUrl) in watchUrls.withIndex()) {
                println("[CineAgora] Tentando URL watch $index: $playerUrl")
                if (extractHlsFromWatchPage(playerUrl, name, callback)) {
                    println("[CineAgora] Sucesso com URL watch $index")
                    return true
                }
            }
            
            // Se não encontrou com /watch, tentar outras URLs do brplayer
            val brplayerUrls = allUrls.filter { it.contains("brplayer") }
            println("[CineAgora] Outras URLs brplayer: ${brplayerUrls.size}")
            
            for ((index, playerUrl) in brplayerUrls.withIndex()) {
                println("[CineAgora] Tentando URL brplayer $index: $playerUrl")
                if (extractHlsFromWatchPage(playerUrl, name, callback)) {
                    println("[CineAgora] Sucesso com URL brplayer $index")
                    return true
                }
            }
            
            // Método 3: Procurar por iframes genéricos
            println("[CineAgora] Método 3: Procurando iframes genéricos...")
            val iframeUrls = extractIframeUrls(html)
            println("[CineAgora] Iframes encontrados: ${iframeUrls.size}")
            
            for ((index, iframeUrl) in iframeUrls.withIndex()) {
                println("[CineAgora] Tentando iframe $index: $iframeUrl")
                if (extractHlsFromWatchPage(iframeUrl, name, callback)) {
                    println("[CineAgora] Sucesso com iframe $index")
                    return true
                }
            }
            
            // Método 4: Procurar por URLs em atributos data-src, src, href, etc.
            println("[CineAgora] Método 4: Procurando URLs em atributos...")
            val attributeUrls = extractUrlsFromAttributes(html)
            println("[CineAgora] URLs em atributos: ${attributeUrls.size}")
            
            for ((index, attrUrl) in attributeUrls.withIndex()) {
                println("[CineAgora] Tentando atributo URL $index: $attrUrl")
                if (extractHlsFromWatchPage(attrUrl, name, callback)) {
                    println("[CineAgora] Sucesso com atributo URL $index")
                    return true
                }
            }
            
            // Método 5: Procurar por padrões específicos de streaming
            println("[CineAgora] Método 5: Procurando padrões de streaming...")
            val streamingUrls = extractStreamingUrls(html)
            println("[CineAgora] URLs de streaming: ${streamingUrls.size}")
            
            for ((index, streamUrl) in streamingUrls.withIndex()) {
                println("[CineAgora] Tentando streaming URL $index: $streamUrl")
                if (extractHlsFromWatchPage(streamUrl, name, callback)) {
                    println("[CineAgora] Sucesso com streaming URL $index")
                    return true
                }
            }

            println("[CineAgora] Nenhum player encontrado após todos os métodos")
            return false
        } catch (e: Exception) {
            println("[CineAgora] ERRO na extração: ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    // **NOVO MÉTODO: Extrair slugs de mídia (/tv/slug ou /movie/slug)**
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
        
        for (pattern in patterns) {
            val regex = Regex(pattern, RegexOption.IGNORE_CASE)
            val matches = regex.findAll(html)
            
            matches.forEach { match ->
                val fullUrl = match.groupValues[1]
                val slug = match.groupValues[2]
                
                if (slug.isNotBlank()) {
                    // Determinar tipo (tv ou movie)
                    val type = if (fullUrl.contains("/tv/")) "tv" else "movie"
                    
                    val pair = Pair(slug, type)
                    if (!mediaSlugs.contains(pair)) {
                        println("[CineAgora] Slug encontrado: $slug ($type) - URL: ${fullUrl.take(50)}...")
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
        
        for (pattern in jsPatterns) {
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
                mediaSlugs.add(Pair(slug, "tv"))
                // Também tentar como filme (/movie/)
                mediaSlugs.add(Pair(slug, "movie"))
            }
        }
        
        return mediaSlugs.distinct()
    }

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

    // O restante do código permanece o mesmo...
    private suspend fun extractHlsFromWatchPage(
        watchUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[CineAgora] Extraindo HLS: $watchUrl")
        
        try {
            val html = app.get(watchUrl, referer = REFERER_CINEAGORA).text
            println("[CineAgora] Página player: ${html.length} caracteres")

            val uid = extractFromRegex(html, "\"uid\"\\s*:\\s*\"(\\d+)\"")
            val md5 = extractFromRegex(html, "\"md5\"\\s*:\\s*\"([a-f0-9]{32})\"")
            val videoId = extractFromRegex(html, "\"id\"\\s*:\\s*\"(\\d+)\"")
            val status = extractFromRegex(html, "\"status\"\\s*:\\s*\"([01])\"") ?: "1"

            println("[CineAgora] Parâmetros - UID: $uid, MD5: $md5, VideoID: $videoId, Status: $status")

            if (uid != null && md5 != null && videoId != null) {
                println("[CineAgora] Parâmetros OK")
                val masterUrl = "$BASE_PLAYER/m3u8/$uid/$md5/master.txt?s=1&id=$videoId&cache=$status"
                val altUrl = "$BASE_PLAYER/alternative_stream/$uid/$md5/master.m3u8"
                
                println("[CineAgora] URL principal: $masterUrl")
                println("[CineAgora] URL alternativa: $altUrl")

                val headers = mapOf(
                    "Referer" to watchUrl,
                    "Origin" to BASE_PLAYER,
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
                )

                println("[CineAgora] Gerando links M3U8...")
                val links = try {
                    val generatedLinks = M3u8Helper.generateM3u8(
                        source = "CineAgora",
                        streamUrl = masterUrl,
                        referer = watchUrl,
                        headers = headers
                    )
                    println("[CineAgora] M3u8Helper gerou ${generatedLinks.size} links")
                    generatedLinks
                } catch (e: Exception) {
                    println("[CineAgora] M3u8Helper falhou: ${e.message}")
                    emptyList()
                }

                if (links.isNotEmpty()) {
                    println("[CineAgora] Adicionando ${links.size} links")
                    links.forEachIndexed { index, link ->
                        println("[CineAgora] Link $index: ${link.name}")
                        callback(link)
                    }
                    return true
                }

                println("[CineAgora] Criando link fallback")
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
                println("[CineAgora] Link principal adicionado")

                println("[CineAgora] Criando link alternativo")
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
                println("[CineAgora] Link alternativo adicionado")

                println("[CineAgora] Extração concluída com sucesso")
                return true
            } else {
                println("[CineAgora] Parâmetros incompletos")
            }

            println("[CineAgora] Tentando extração direta...")
            val masterUrlDirect = extractMasterUrlDirect(html)
            if (masterUrlDirect != null) {
                println("[CineAgora] URL mestre encontrada: ${masterUrlDirect.take(100)}...")
                
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
                println("[CineAgora] Link direto adicionado")
                return true
            }

            println("[CineAgora] Procurando URLs M3U8...")
            val m3u8Urls = extractAllM3u8Urls(html)
            println("[CineAgora] Encontradas ${m3u8Urls.size} URLs M3U8")
            
            if (m3u8Urls.isNotEmpty()) {
                println("[CineAgora] Adicionando ${m3u8Urls.size} URLs M3U8")
                m3u8Urls.forEachIndexed { index, m3u8Url ->
                    println("[CineAgora] M3U8 $index: ${m3u8Url.take(100)}...")
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
                println("[CineAgora] Extração via M3U8 diretas concluída")
                return true
            }

            println("[CineAgora] Nenhum método funcionou para esta URL")
            return false
        } catch (e: Exception) {
            println("[CineAgora] ERRO fatal: ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    private fun extractFromRegex(text: String, pattern: String): String? {
        val regex = Regex(pattern)
        val match = regex.find(text)
        val result = match?.groupValues?.get(1)
        return result
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
