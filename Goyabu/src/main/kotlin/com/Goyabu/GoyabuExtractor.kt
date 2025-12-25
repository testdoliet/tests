package com.Goyabu

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.jsoup.Jsoup

object GoyabuJwPlayerExtractor {
    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🎬 GOYABU JWPLAYER EXTRACTOR: Iniciando extração para: $url")
        
        return try {
            // Estratégia 1: Extrair configuração do JWPlayer
            println("🔍 Estratégia 1: Buscando configuração do JWPlayer...")
            val jwConfig = extractJwPlayerConfig(url)
            if (jwConfig != null) {
                println("✅ Configuração JWPlayer encontrada")
                return processJwPlayerConfig(jwConfig, url, mainUrl, name, callback)
            }
            
            // Estratégia 2: Buscar iframe do JWPlayer
            println("🔍 Estratégia 2: Buscando iframe JWPlayer...")
            val jwIframe = findJwPlayerIframe(url)
            if (jwIframe != null) {
                println("✅ Iframe JWPlayer encontrado: $jwIframe")
                return extractFromJwIframe(jwIframe, url, mainUrl, name, callback)
            }
            
            // Estratégia 3: Buscar em scripts JWPlayer
            println("🔍 Estratégia 3: Analisando scripts JWPlayer...")
            val jwScriptUrl = findJwPlayerInScripts(url)
            if (jwScriptUrl != null) {
                println("✅ URL JWPlayer em script: $jwScriptUrl")
                return processJwUrl(jwScriptUrl, url, mainUrl, name, callback)
            }
            
            // Estratégia 4: Buscar setupplayer
            println("🔍 Estratégia 4: Buscando player.setup()...")
            val setupUrl = findPlayerSetup(url)
            if (setupUrl != null) {
                println("✅ player.setup() encontrado: $setupUrl")
                return processJwUrl(setupUrl, url, mainUrl, name, callback)
            }
            
            println("❌ JWPlayer não encontrado")
            false
            
        } catch (e: Exception) {
            println("❌ GOYABU JWPLAYER EXTRACTOR: Erro: ${e.message}")
            false
        }
    }
    
    // ============ EXTRAIR CONFIGURAÇÃO JWPLAYER ============
    private suspend fun extractJwPlayerConfig(pageUrl: String): String? {
        return try {
            val response = app.get(pageUrl, headers = getHeaders())
            val html = response.text
            
            // Padrões para configuração do JWPlayer
            val patterns = listOf(
                // player.setup({ ... })
                """player\.setup\s*\(\s*(\{.*?"file".*?\})\s*\)""".toRegex(RegexOption.DOT_MATCHES_ALL),
                // jwplayer().setup({ ... })
                """jwplayer\([^)]*\)\.setup\s*\(\s*(\{.*?"file".*?\})\s*\)""".toRegex(RegexOption.DOT_MATCHES_ALL),
                // var player = jwplayer({ ... })
                """var\s+\w+\s*=\s*jwplayer\([^)]*\)\.setup\s*\(\s*(\{.*?"file".*?\})\s*\)""".toRegex(RegexOption.DOT_MATCHES_ALL),
                // Configuração em objeto
                """var\s+\w+\s*=\s*(\{.*?"file".*?\})""".toRegex(RegexOption.DOT_MATCHES_ALL),
                // Em scripts específicos
                """<script[^>]*>.*?jwplayer.*?setup.*?(\{.*?"file".*?\}).*?</script>""".toRegex(RegexOption.DOT_MATCHES_ALL)
            )
            
            for (pattern in patterns) {
                val match = pattern.find(html)
                if (match != null && match.groupValues.size > 1) {
                    val config = match.groupValues[1]
                    println("🎯 Configuração JWPlayer encontrada: ${config.take(200)}...")
                    return config
                }
            }
            
            null
        } catch (e: Exception) {
            println("⚠️ Erro ao buscar JWPlayer config: ${e.message}")
            null
        }
    }
    
    // ============ PROCESSAR CONFIGURAÇÃO JWPLAYER ============
    private fun processJwPlayerConfig(
        config: String,
        referer: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔧 Processando configuração JWPlayer...")
        
        try {
            // Extrair URL do arquivo
            val filePatterns = listOf(
                """"file"\s*:\s*"([^"]+)"""".toRegex(),
                """"file"\s*:\s*'([^']+)'""".toRegex(),
                """"file"\s*:\s*\["([^"]+)"""".toRegex(),
                """sources\s*:\s*\[\s*\{[^}]+"file"\s*:\s*"([^"]+)"""".toRegex()
            )
            
            for (pattern in filePatterns) {
                val match = pattern.find(config)
                if (match != null && match.groupValues.size > 1) {
                    val fileUrl = match.groupValues[1]
                    println("🎯 URL do arquivo encontrada: $fileUrl")
                    
                    // Verificar se é M3U8
                    if (fileUrl.contains(".m3u8") || fileUrl.contains(".mp4")) {
                        return processVideoUrl(fileUrl, referer, mainUrl, name, callback)
                    }
                }
            }
            
            // Extrair múltiplas fontes
            val sourcesPattern = """"sources"\s*:\s*\[(.*?)\]""".toRegex(RegexOption.DOT_MATCHES_ALL)
            val sourcesMatch = sourcesPattern.find(config)
            
            if (sourcesMatch != null && sourcesMatch.groupValues.size > 1) {
                val sourcesContent = sourcesMatch.groupValues[1]
                println("🎯 Fontes encontradas: $sourcesContent")
                
                // Extrair URLs das fontes
                val urlPattern = """"file"\s*:\s*"([^"]+)"""".toRegex()
                val urlMatches = urlPattern.findAll(sourcesContent)
                
                for (urlMatch in urlMatches) {
                    if (urlMatch.groupValues.size > 1) {
                        val videoUrl = urlMatch.groupValues[1]
                        println("🎯 URL de fonte: $videoUrl")
                        
                        if (videoUrl.contains(".m3u8") || videoUrl.contains(".mp4")) {
                            return processVideoUrl(videoUrl, referer, mainUrl, name, callback)
                        }
                    }
                }
            }
            
            // Extrair tracks (legendas)
            val tracksPattern = """"tracks"\s*:\s*\[(.*?)\]""".toRegex(RegexOption.DOT_MATCHES_ALL)
            val tracksMatch = tracksPattern.find(config)
            
            if (tracksMatch != null) {
                println("📝 Legendas encontradas na configuração")
            }
            
            return false
            
        } catch (e: Exception) {
            println("❌ Erro ao processar configuração: ${e.message}")
            return false
        }
    }
    
    // ============ ENCONTRAR IFRAME JWPLAYER ============
    private suspend fun findJwPlayerIframe(pageUrl: String): String? {
        return try {
            val response = app.get(pageUrl, headers = getHeaders())
            val doc = Jsoup.parse(response.text)
            
            // Procurar iframes do JWPlayer
            val iframes = doc.select("iframe[src]")
            for (iframe in iframes) {
                val src = iframe.attr("src")
                if (src.contains("jwplayer") || src.contains("jwplatform") || 
                    src.contains("anivideo") || src.contains("stream")) {
                    println("🎯 Iframe JWPlayer encontrado: $src")
                    return src
                }
            }
            
            null
        } catch (e: Exception) {
            println("⚠️ Erro ao buscar iframe: ${e.message}")
            null
        }
    }
    
    // ============ EXTRAIR DE IFRAME JWPLAYER ============
    private suspend fun extractFromJwIframe(
        iframeUrl: String,
        referer: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔗 Processando iframe JWPlayer: $iframeUrl")
        
        return try {
            val response = app.get(iframeUrl, headers = getHeaders(referer))
            val html = response.text
            
            // Procurar configuração do JWPlayer no iframe
            val config = extractJwPlayerConfigFromHtml(html)
            if (config != null) {
                println("✅ Configuração encontrada no iframe")
                return processJwPlayerConfig(config, iframeUrl, mainUrl, name, callback)
            }
            
            // Procurar URL direta
            val directUrl = findDirectVideoUrl(html)
            if (directUrl != null) {
                println("✅ URL direta no iframe: $directUrl")
                return processVideoUrl(directUrl, iframeUrl, mainUrl, name, callback)
            }
            
            false
            
        } catch (e: Exception) {
            println("❌ Erro no iframe: ${e.message}")
            false
        }
    }
    
    // ============ EXTRAIR CONFIGURAÇÃO DE HTML ============
    private fun extractJwPlayerConfigFromHtml(html: String): String? {
        // Padrão para configuração completa
        val pattern = """jwplayer\([^)]*\)\.setup\s*\(\s*(\{.*?\})\s*\)""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val match = pattern.find(html)
        
        if (match != null && match.groupValues.size > 1) {
            return match.groupValues[1]
        }
        
        return null
    }
    
    // ============ ENCONTRAR URL DIRETA ============
    private fun findDirectVideoUrl(html: String): String? {
        val patterns = listOf(
            """"file"\s*:\s*"([^"]+\.m3u8)"""".toRegex(),
            """"file"\s*:\s*"([^"]+\.mp4)"""".toRegex(),
            """src\s*=\s*["']([^"']+\.m3u8)["']""".toRegex(),
            """<source[^>]+src=["']([^"']+\.m3u8)["']""".toRegex()
        )
        
        for (pattern in patterns) {
            val match = pattern.find(html)
            if (match != null && match.groupValues.size > 1) {
                return match.groupValues[1]
            }
        }
        
        return null
    }
    
    // ============ BUSCAR JWPLAYER EM SCRIPTS ============
    private suspend fun findJwPlayerInScripts(pageUrl: String): String? {
        return try {
            val response = app.get(pageUrl, headers = getHeaders())
            val html = response.text
            
            // Procurar por URLs do JWPlayer em scripts
            val patterns = listOf(
                // player.setup({ file: "URL" })
                """player\.setup\s*\(\s*\{[^}]*"file"\s*:\s*"([^"]+)"""".toRegex(RegexOption.DOT_MATCHES_ALL),
                // jwplayer().setup({ file: "URL" })
                """jwplayer\([^)]*\)\.setup\s*\(\s*\{[^}]*"file"\s*:\s*"([^"]+)"""".toRegex(RegexOption.DOT_MATCHES_ALL),
                // file: "URL"
                """"file"\s*:\s*"([^"]+\.m3u8)"""".toRegex(),
                // URL em variável
                """var\s+\w+\s*=\s*["']([^"']+\.m3u8)["']""".toRegex()
            )
            
            for (pattern in patterns) {
                val match = pattern.find(html)
                if (match != null && match.groupValues.size > 1) {
                    val url = match.groupValues[1]
                    println("🎯 URL JWPlayer em script: $url")
                    return url
                }
            }
            
            null
        } catch (e: Exception) {
            println("⚠️ Erro em scripts: ${e.message}")
            null
        }
    }
    
    // ============ ENCONTRAR PLAYER.SETUP ============
    private suspend fun findPlayerSetup(pageUrl: String): String? {
        return try {
            val response = app.get(pageUrl, headers = getHeaders())
            val html = response.text
            
            // Padrão mais específico para player.setup
            val pattern = """player\.setup\s*\(\s*\{[^}]*"file"\s*:\s*"([^"]+)"""".toRegex(RegexOption.DOT_MATCHES_ALL)
            val match = pattern.find(html)
            
            if (match != null && match.groupValues.size > 1) {
                return match.groupValues[1]
            }
            
            null
        } catch (e: Exception) {
            println("⚠️ Erro ao buscar player.setup: ${e.message}")
            null
        }
    }
    
    // ============ PROCESSAR URL JWPLAYER ============
    private fun processJwUrl(
        jwUrl: String,
        referer: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔗 Processando URL JWPlayer: $jwUrl")
        
        // Se a URL já é um M3U8 ou MP4, processar diretamente
        if (jwUrl.contains(".m3u8") || jwUrl.contains(".mp4")) {
            return processVideoUrl(jwUrl, referer, mainUrl, name, callback)
        }
        
        // Se for uma configuração JSON
        if (jwUrl.startsWith("{") || jwUrl.contains("file")) {
            return processJwPlayerConfig(jwUrl, referer, mainUrl, name, callback)
        }
        
        return false
    }
    
    // ============ PROCESSAR URL DE VÍDEO ============
    private fun processVideoUrl(
        videoUrl: String,
        referer: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔗 Processando URL de vídeo: ${videoUrl.take(150)}...")
        
        return try {
            // Se for M3U8
            if (videoUrl.contains(".m3u8")) {
                processM3u8(videoUrl, referer, mainUrl, name, callback)
            } 
            // Se for MP4 direto
            else if (videoUrl.contains(".mp4")) {
                println("✅ MP4 direto encontrado: $videoUrl")
                callback.invoke(
                    ExtractorLink(
                        "Goyabu",
                        "Goyabu MP4",
                        videoUrl,
                        referer,
                        Qualities.Unknown.value,
                        false
                    )
                )
                true
            } else {
                false
            }
        } catch (e: Exception) {
            println("❌ Erro ao processar URL: ${e.message}")
            false
        }
    }
    
    // ============ PROCESSAR M3U8 ============
    private fun processM3u8(
        m3u8Url: String,
        referer: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔗 Processando M3U8: ${m3u8Url.take(150)}...")
        
        try {
            val headers = getM3u8Headers(referer)
            
            M3u8Helper.generateM3u8(
                name,
                m3u8Url,
                mainUrl,
                headers = headers
            ).forEach(callback)
            
            println("✅ M3U8 processado com sucesso!")
            return true
            
        } catch (e: Exception) {
            println("❌ Erro ao processar M3U8: ${e.message}")
            return false
        }
    }
    
    // ============ HEADERS ============
    private fun getHeaders(referer: String? = null): Map<String, String> {
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to (referer ?: "https://goyabu.io/"),
            "DNT" to "1"
        )
    }
    
    private fun getM3u8Headers(referer: String): Map<String, String> {
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Accept" to "*/*",
            "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
            "Referer" to referer,
            "Origin" to "https://goyabu.io"
        )
    }
}
