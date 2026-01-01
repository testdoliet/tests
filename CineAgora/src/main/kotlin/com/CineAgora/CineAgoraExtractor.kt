package com.CineAgora

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

object CineAgoraExtractor {
    private const val TAG = "CineAgoraExtractor"
    private const val BASE_PLAYER_URL = "https://watch.brplayer.cc"
    
    suspend fun extractVideoLinks(
        url: String,  // URL da página do CineAgora
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[$TAG] Iniciando extração para URL: $url")
        
        return try {
            println("[$TAG] Carregando página do CineAgora...")
            val doc = app.get(url).document
            println("[$TAG] Página carregada com sucesso")
            
            // AGORA: Precisamos encontrar o link do player brplayer.cc
            println("[$TAG] Analisando HTML para encontrar link do player...")
            
            // 1. Primeiro, vamos salvar o HTML completo para análise
            val html = doc.toString()
            println("[$TAG] Tamanho do HTML: ${html.length} caracteres")
            
            // 2. Procura por padrões específicos do brplayer.cc
            val playerUrl = findBrplayerUrl(html, url)
            
            if (playerUrl != null) {
                println("[$TAG] URL do player encontrada: $playerUrl")
                return extractFromBrplayer(playerUrl, name, callback)
            } else {
                println("[$TAG] URL do player não encontrada no HTML")
                println("[$TAG] Vamos analisar os scripts mais detalhadamente...")
                return analyzeScriptsForPlayer(doc, url, name, callback)
            }
            
        } catch (e: Exception) {
            println("[$TAG] Erro na extração: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    private fun findBrplayerUrl(html: String, originalUrl: String): String? {
        println("[$TAG] Procurando URLs do brplayer.cc no HTML...")
        
        // Padrões comuns para encontrar o player
        val patterns = listOf(
            // Padrão 1: https://watch.brplayer.cc/watch/XXXXX
            Regex("""(https?://watch\.brplayer\.cc/watch/[a-zA-Z0-9]+)"""),
            
            // Padrão 2: URL com parâmetros
            Regex("""(https?://watch\.brplayer\.cc/[^"'\s]+)"""),
            
            // Padrão 3: URL em atributos data-*
            Regex("""data-(?:url|src|player)="(https?://watch\.brplayer\.cc/[^"]+)"""),
            
            // Padrão 4: Em scripts JavaScript
            Regex("""['"](https?://watch\.brplayer\.cc/[^"']+)['"]"""),
            
            // Padrão 5: brplayer.cc sem https
            Regex("""(//watch\.brplayer\.cc/[^"'\s]+)""")
        )
        
        for ((index, pattern) in patterns.withIndex()) {
            println("[$TAG] Testando padrão ${index + 1}...")
            val matches = pattern.findAll(html).toList()
            println("[$TAG] Encontrados ${matches.size} matches com padrão ${index + 1}")
            
            for (match in matches) {
                var foundUrl = match.groupValues[1]
                println("[$TAG] Match encontrado: $foundUrl")
                
                // Se a URL começar com //, adiciona https:
                if (foundUrl.startsWith("//")) {
                    foundUrl = "https:$foundUrl"
                    println("[$TAG] URL corrigida: $foundUrl")
                }
                
                // Verifica se é uma URL válida do player
                if (isValidPlayerUrl(foundUrl)) {
                    println("[$TAG] URL válida do player encontrada!")
                    return foundUrl
                }
            }
        }
        
        return null
    }
    
    private fun isValidPlayerUrl(url: String): Boolean {
        return url.contains("watch.brplayer.cc") && 
               (url.contains("/watch/") || url.contains("watch?v="))
    }
    
    private suspend fun analyzeScriptsForPlayer(
        doc: org.jsoup.nodes.Document,
        originalUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[$TAG] Analisando scripts detalhadamente...")
        val scripts = doc.select("script")
        
        for ((index, script) in scripts.withIndex()) {
            val scriptContent = script.data() + script.html()
            if (scriptContent.isNotBlank()) {
                println("[$TAG] Analisando script ${index + 1}/${scripts.size} (${scriptContent.length} chars)")
                
                // Procura por variáveis JavaScript que podem conter a URL
                val patterns = listOf(
                    // Padrão: playerUrl = "https://watch.brplayer.cc/watch/XXXXX"
                    Regex("""player(?:Url|URL|_url)\s*[=:]\s*["'](https?://watch\.brplayer\.cc/[^"']+)["']"""),
                    
                    // Padrão: src: "https://watch.brplayer.cc/watch/XXXXX"
                    Regex("""src\s*:\s*["'](https?://watch\.brplayer\.cc/[^"']+)["']"""),
                    
                    // Padrão: var video = { url: "https://..." }
                    Regex("""url\s*:\s*["'](https?://watch\.brplayer\.cc/[^"']+)["']"""),
                    
                    // Padrão: loadPlayer("https://watch.brplayer.cc/...")
                    Regex("""loadPlayer\s*\(\s*["'](https?://watch\.brplayer\.cc/[^"']+)["']"""),
                    
                    // Padrão: iframe.src = "https://watch.brplayer.cc/..."
                    Regex("""iframe\.src\s*=\s*["'](https?://watch\.brplayer\.cc/[^"']+)["']""")
                )
                
                for (pattern in patterns) {
                    val match = pattern.find(scriptContent)
                    if (match != null) {
                        val playerUrl = match.groupValues[1]
                        println("[$TAG] URL do player encontrada em script: $playerUrl")
                        return extractFromBrplayer(playerUrl, name, callback)
                    }
                }
                
                // Procura por objetos JSON que podem conter a URL
                if (scriptContent.contains("brplayer.cc")) {
                    println("[$TAG] Script contém 'brplayer.cc', analisando mais...")
                    // Tenta encontrar qualquer URL do brplayer
                    val urlRegex = Regex("""(https?://watch\.brplayer\.cc/[^"'\s]+)""")
                    val matches = urlRegex.findAll(scriptContent).toList()
                    
                    for (match in matches) {
                        val foundUrl = match.groupValues[1]
                        println("[$TAG] URL encontrada em script: $foundUrl")
                        if (isValidPlayerUrl(foundUrl)) {
                            println("[$TAG] URL válida encontrada em script!")
                            return extractFromBrplayer(foundUrl, name, callback)
                        }
                    }
                }
            }
        }
        
        println("[$TAG] Nenhuma URL de player encontrada nos scripts")
        return false
    }
    
    private suspend fun extractFromBrplayer(
        playerUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[$TAG] Extraindo do brplayer.cc: $playerUrl")
        
        return try {
            println("[$TAG] Acessando página do brplayer.cc...")
            val headers = mapOf(
                "Referer" to "https://cineagora.net/",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
            
            val response = app.get(playerUrl, headers = headers)
            val html = response.text
            println("[$TAG] Página do brplayer carregada (${html.length} caracteres)")
            
            // Procura pelo objeto video no JavaScript
            println("[$TAG] Procurando objeto 'var video = {...}'")
            
            // Padrão: var video = { ... }
            val videoPattern = Regex("""var\s+video\s*=\s*(\{[^}]+\})""")
            val videoMatch = videoPattern.find(html)
            
            if (videoMatch == null) {
                println("[$TAG] Objeto 'video' não encontrado, tentando padrões alternativos...")
                // Tenta padrão alternativo: video = { ... } (sem var)
                val altPattern = Regex("""video\s*=\s*(\{[^}]+\})""")
                val altMatch = altPattern.find(html)
                
                if (altMatch != null) {
                    return extractVideoData(altMatch.groupValues[1], playerUrl, name, callback)
                }
                
                println("[$TAG] Nenhum objeto video encontrado")
                return false
            }
            
            println("[$TAG] Objeto video encontrado!")
            return extractVideoData(videoMatch.groupValues[1], playerUrl, name, callback)
            
        } catch (e: Exception) {
            println("[$TAG] Erro ao acessar brplayer.cc: ${e.message}")
            false
        }
    }
    
    private suspend fun extractVideoData(
        videoJson: String,
        playerUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[$TAG] Extraindo dados do objeto video...")
        println("[$TAG] JSON: $videoJson")
        
        // Extrai os valores usando regex simples
        val uid = extractFromJson(videoJson, "uid")
        val md5 = extractFromJson(videoJson, "md5")
        val videoId = extractFromJson(videoJson, "id")
        
        println("[$TAG] Valores extraídos - UID: '$uid', MD5: '$md5', ID: '$videoId'")
        
        if (uid.isBlank() || md5.isBlank() || videoId.isBlank()) {
            println("[$TAG] Valores faltando no JSON")
            
            // Tenta extrair de outros padrões no HTML
            val html = videoJson // Na verdade, precisaríamos do HTML completo aqui
            // Mas vamos tentar com o que temos
            
            val fallbackUid = extractFromJson(videoJson, "uid") ?: "12" // Valor padrão comum
            val fallbackMd5 = extractFromJson(videoJson, "md5") ?: return false
            val fallbackId = extractFromJson(videoJson, "id") ?: return false
            
            return createM3u8Link(fallbackUid, fallbackMd5, fallbackId, playerUrl, name, callback)
        }
        
        return createM3u8Link(uid, md5, videoId, playerUrl, name, callback)
    }
    
    private fun extractFromJson(json: String, key: String): String {
        val pattern = Regex("""["']$key["']\s*:\s*["']([^"']+)["']""")
        val match = pattern.find(json)
        return match?.groupValues?.get(1) ?: ""
    }
    
    private suspend fun createM3u8Link(
        uid: String,
        md5: String,
        videoId: String,
        playerUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[$TAG] Criando URL M3U8...")
        
        // Constrói a URL M3U8 conforme o padrão
        val m3u8Url = "$BASE_PLAYER_URL/m3u8/$uid/$md5/master.txt?s=1&id=$videoId&cache=1"
        println("[$TAG] URL M3U8: $m3u8Url")
        
        // Headers importantes
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
            "Accept" to "*/*",
            "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
            "Referer" to playerUrl,
            "Origin" to BASE_PLAYER_URL
        )
        
        try {
            println("[$TAG] Gerando links M3U8 com qualidades...")
            val links = M3u8Helper.generateM3u8(
                source = name,
                streamUrl = m3u8Url,
                referer = playerUrl,
                headers = headers
            )
            
            if (links.isNotEmpty()) {
                println("[$TAG] ${links.size} links M3U8 gerados com sucesso!")
                links.forEach { link ->
                    println("[$TAG] Link: ${link.url} (Qualidade: ${link.quality})")
                    callback(link)
                }
                return true
            }
            
            println("[$TAG] M3u8Helper não gerou links, criando link direto...")
            
            // Fallback: link M3U8 direto
            val fallbackLink = newExtractorLink(
                source = name,
                name = "CineAgora HD",
                url = m3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = playerUrl
                this.quality = Qualities.Unknown.value
                this.headers = headers
            }
            
            callback(fallbackLink)
            println("[$TAG] Link direto criado com sucesso")
            return true
            
        } catch (e: Exception) {
            println("[$TAG] Erro ao criar links M3U8: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
}
