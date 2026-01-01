
package com.CineAgora

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.json.JSONObject

object CineAgoraExtractor {
    private const val TAG = "CineAgoraExtractor"
    
    suspend fun extractVideoLinks(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[$TAG] Iniciando extração para URL: $url")
        
        return try {
            println("[$TAG] Carregando página do CineAgora...")
            val doc = app.get(url).document
            println("[$TAG] Página carregada com sucesso")
            
            // Tenta encontrar o iframe do brplayer.cc
            println("[$TAG] Procurando iframe do player...")
            val iframeSrc = findPlayerIframe(doc)
            
            if (iframeSrc != null) {
                println("[$TAG] Iframe encontrado: $iframeSrc")
                return extractFromIframe(iframeSrc, name, callback)
            } else {
                println("[$TAG] Iframe não encontrado, tentando extração direta...")
                return tryDirectExtraction(doc, url, name, callback)
            }
            
        } catch (e: Exception) {
            println("[$TAG] Erro na extração: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    private suspend fun extractFromIframe(
        iframeSrc: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("[$TAG] Acessando página do player: $iframeSrc")
            val playerPage = app.get(iframeSrc).text
            println("[$TAG] Página do player carregada (${playerPage.length} caracteres)")
            
            // Extrai as variáveis do JavaScript
            println("[$TAG] Extraindo variáveis JavaScript...")
            val (uid, md5, videoId) = extractJsVariables(playerPage)
            
            println("[$TAG] Variáveis extraídas - UID: '$uid', MD5: '$md5', VideoID: '$videoId'")
            
            if (uid.isBlank() || md5.isBlank() || videoId.isBlank()) {
                println("[$TAG] Variáveis incompletas, tentando método alternativo...")
                return tryAlternativeExtraction(playerPage, name, iframeSrc, callback)
            }
            
            // Constrói a URL HLS
            val hlsUrl = "https://watch.brplayer.cc/m3u8/$uid/$md5/master.txt?s=1&id=$videoId&cache=1"
            println("[$TAG] URL HLS construída: $hlsUrl")
            
            // Cria o link do extractor
            return createExtractorLink(hlsUrl, name, iframeSrc, callback)
            
        } catch (e: Exception) {
            println("[$TAG] Erro ao extrair do iframe: ${e.message}")
            false
        }
    }
    
    private fun findPlayerIframe(document: org.jsoup.nodes.Document): String? {
        // 1. Procura por iframes do brplayer.cc
        println("[$TAG] Procurando iframes...")
        val iframes = document.select("iframe[src]")
        println("[$TAG] Encontrados ${iframes.size} iframes")
        
        for (iframe in iframes) {
            val src = iframe.attr("src")
            println("[$TAG] Iframe encontrado: $src")
            
            if (src.contains("watch.brplayer.cc/watch?v=")) {
                println("[$TAG] Iframe do brplayer.cc encontrado!")
                return src
            }
            if (src.contains("brplayer.cc")) {
                println("[$TAG] Iframe com brplayer.cc encontrado!")
                return src
            }
            if (src.contains("player.")) {
                println("[$TAG] Iframe de player genérico encontrado")
                return src
            }
        }
        
        // 2. Procura por divs com data-url
        println("[$TAG] Procurando elementos com data-url...")
        val dataUrlElements = document.select("[data-url]")
        println("[$TAG] Encontrados ${dataUrlElements.size} elementos com data-url")
        
        for (element in dataUrlElements) {
            val dataUrl = element.attr("data-url")
            println("[$TAG] data-url encontrado: $dataUrl")
            
            if (dataUrl.contains("brplayer.cc") || dataUrl.contains("watch?v=")) {
                println("[$TAG] data-url do player encontrado!")
                return dataUrl
            }
        }
        
        // 3. Procura por scripts
        println("[$TAG] Procurando em scripts...")
        val scripts = document.select("script")
        println("[$TAG] Encontrados ${scripts.size} scripts")
        
        for (script in scripts) {
            val scriptContent = script.data() + script.html()
            if (scriptContent.contains("brplayer.cc")) {
                println("[$TAG] Script contém brplayer.cc")
                val regex = Regex("""(https?://[^'"]*brplayer\.cc[^'"]*)""")
                val match = regex.find(scriptContent)
                if (match != null) {
                    val url = match.groupValues[1]
                    println("[$TAG] URL extraída do script: $url")
                    return url
                }
            }
        }
        
        println("[$TAG] Nenhum iframe ou player encontrado")
        return null
    }
    
    private fun extractJsVariables(pageContent: String): Triple<String, String, String> {
        var uid = ""
        var md5 = ""
        var videoId = ""
        
        println("[$TAG] Procurando variáveis JavaScript...")
        
        // Padrão 1: var video = { ... }
        val videoObjPattern = Regex("""var\s+video\s*=\s*\{[^}]+\}""")
        val videoObjMatch = videoObjPattern.find(pageContent)
        
        if (videoObjMatch != null) {
            println("[$TAG] Objeto video encontrado")
            val videoObj = videoObjMatch.value
            
            uid = extractFromJsonLike(videoObj, "uid") ?: ""
            md5 = extractFromJsonLike(videoObj, "md5") ?: ""
            videoId = extractFromJsonLike(videoObj, "id") ?: ""
            
            println("[$TAG] Extraído do objeto video: uid='$uid', md5='$md5', id='$videoId'")
        }
        
        // Se não encontrou no objeto video, procura diretamente
        if (uid.isBlank()) {
            uid = extractVariable(pageContent, "uid")
            println("[$TAG] uid extraído diretamente: '$uid'")
        }
        
        if (md5.isBlank()) {
            md5 = extractVariable(pageContent, "md5")
            println("[$TAG] md5 extraído diretamente: '$md5'")
        }
        
        if (videoId.isBlank()) {
            videoId = extractVariable(pageContent, "id")
            if (videoId.isBlank()) {
                videoId = extractVariable(pageContent, "videoId")
            }
            println("[$TAG] videoId extraído: '$videoId'")
        }
        
        // Fallback: tenta extrair do slug/URL
        if (videoId.isBlank()) {
            val slug = extractVariable(pageContent, "slug")
            if (slug.isNotBlank()) {
                videoId = slug
                println("[$TAG] Usando slug como videoId: '$slug'")
            }
        }
        
        return Triple(uid, md5, videoId)
    }
    
    private fun extractFromJsonLike(jsonLike: String, key: String): String? {
        val pattern = Regex("""["']$key["']\s*:\s*["']([^"']+)["']""")
        val match = pattern.find(jsonLike)
        return match?.groupValues?.get(1)
    }
    
    private fun extractVariable(content: String, varName: String): String {
        val patterns = listOf(
            Regex("""["']$varName["']\s*:\s*["']([^"']+)["']"""),
            Regex("""var\s+$varName\s*=\s*["']([^"']+)["']"""),
            Regex("""let\s+$varName\s*=\s*["']([^"']+)["']"""),
            Regex("""const\s+$varName\s*=\s*["']([^"']+)["']"""),
            Regex("""$varName\s*=\s*["']([^"']+)["']""")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(content)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        
        return ""
    }
    
    private suspend fun tryAlternativeExtraction(
        playerPage: String,
        name: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[$TAG] Tentando extração alternativa...")
        
        // Tenta encontrar URL m3u8 diretamente
        val m3u8Pattern = Regex("""(https?://[^"' ]*\.(?:m3u8|mp4)[^"' ]*)""")
        val m3u8Matches = m3u8Pattern.findAll(playerPage).toList()
        
        println("[$TAG] Encontrados ${m3u8Matches.size} possíveis URLs m3u8/mp4")
        
        for (match in m3u8Matches) {
            val url = match.groupValues[1]
            println("[$TAG] URL encontrada: $url")
            
            if (url.contains("master.m3u8") || url.contains("master.txt") || url.contains("playlist.m3u8")) {
                println("[$TAG] URL m3u8 master encontrada!")
                return createExtractorLink(url, name, referer, callback)
            }
        }
        
        // Tenta encontrar em scripts JSON
        val jsonPattern = Regex("""\{[^{}]*["']sources["'][^{}]*\}""")
        val jsonMatches = jsonPattern.findAll(playerPage).toList()
        
        println("[$TAG] Encontrados ${jsonMatches.size} objetos JSON possíveis")
        
        for (match in jsonMatches) {
            try {
                val jsonStr = match.value
                val json = tryParseJson<Map<String, Any>>(jsonStr)
                
                if (json != null) {
                    val sources = json["sources"] as? List<Map<String, Any>>
                    if (sources != null && sources.isNotEmpty()) {
                        val firstSource = sources[0]
                        val url = firstSource["url"] as? String
                        if (url != null && (url.contains(".m3u8") || url.contains(".mp4"))) {
                            println("[$TAG] URL encontrada em JSON: $url")
                            return createExtractorLink(url, name, referer, callback)
                        }
                    }
                }
            } catch (e: Exception) {
                println("[$TAG] Erro ao processar JSON: ${e.message}")
            }
        }
        
        println("[$TAG] Extração alternativa falhou")
        return false
    }
    
    private suspend fun tryDirectExtraction(
        doc: org.jsoup.nodes.Document,
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[$TAG] Tentando extração direta da página...")
        
        // Procura por scripts com informações de vídeo
        val scripts = doc.select("script")
        println("[$TAG] Analisando ${scripts.size} scripts para extração direta")
        
        for (script in scripts) {
            val content = script.data() + script.html()
            
            // Procura por objetos de vídeo
            if (content.contains("video") || content.contains("player") || content.contains("stream")) {
                println("[$TAG] Script pode conter informações de vídeo")
                
                // Tenta extrair URLs m3u8
                val m3u8Pattern = Regex("""(https?://[^"' ]*\.m3u8[^"' ]*)""")
                val matches = m3u8Pattern.findAll(content).toList()
                
                for (match in matches) {
                    val m3u8Url = match.groupValues[1]
                    println("[$TAG] URL m3u8 encontrada em script: $m3u8Url")
                    
                    if (createExtractorLink(m3u8Url, name, url, callback)) {
                        return true
                    }
                }
            }
        }
        
        println("[$TAG] Extração direta falhou")
        return false
    }
    
    private suspend fun createExtractorLink(
        hlsUrl: String,
        name: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("[$TAG] Criando ExtractorLink para: $hlsUrl")
            println("[$TAG] Referer: $referer")
            
            // Tenta gerar links M3U8 com qualidades
            println("[$TAG] Gerando links M3U8...")
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                "Accept" to "*/*",
                "Accept-Language" to "pt-BR,pt;q=0.9",
                "Referer" to referer,
                "Origin" to "https://cineagora.net"
            )
            
            val links = M3u8Helper.generateM3u8(
                source = name,
                streamUrl = hlsUrl,
                referer = referer,
                headers = headers
            )
            
            if (links.isNotEmpty()) {
                println("[$TAG] ${links.size} links M3U8 gerados com sucesso!")
                links.forEach { link ->
                    println("[$TAG] Link gerado: ${link.url} (Qualidade: ${link.quality})")
                    callback(link)
                }
                return true
            }
            
            println("[$TAG] Fallback: criando link simples M3U8")
            // Fallback: link M3U8 simples
            val fallbackLink = newExtractorLink(
                source = name,
                name = name,
                url = hlsUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
                this.headers = headers
            }
            
            callback(fallbackLink)
            println("[$TAG] Link fallback criado com sucesso")
            true
            
        } catch (e: Exception) {
            println("[$TAG] Erro ao criar ExtractorLink: ${e.message}")
            e.printStackTrace()
            false
        }
    }
}
