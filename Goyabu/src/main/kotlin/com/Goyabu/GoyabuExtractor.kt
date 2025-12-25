package com.Goyabu

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import java.net.URLDecoder

object GoyabuExtractor {
    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🎬 GOYABU EXTRACTOR: Iniciando extração para: $url")
        
        return try {
            // PRIMEIRO: Obter a página com headers completos
            println("🌐 Carregando página inicial...")
            val pageResponse = app.get(url, headers = getFullHeaders(url))
            val html = pageResponse.text
            
            // SEGUNDO: Extrair dados necessários da página
            println("🔍 Extraindo dados da página...")
            val pageData = extractPageData(html, url)
            
            // TERCEIRO: Tentar construir a URL da API anivideo
            println("🔗 Construindo URL da API...")
            val apiUrl = buildAnivideoApiUrl(pageData, url)
            
            if (apiUrl != null) {
                println("✅ URL da API construída: $apiUrl")
                return extractM3u8FromApi(apiUrl, url, mainUrl, name, callback)
            }
            
            // QUARTO: Se não conseguiu construir, tentar encontrar no HTML
            println("🔍 Procurando URL da API no HTML...")
            val foundApiUrl = findApiUrlInHtml(html)
            
            if (foundApiUrl != null) {
                println("✅ URL da API encontrada no HTML: $foundApiUrl")
                return extractM3u8FromApi(foundApiUrl, url, mainUrl, name, callback)
            }
            
            // QUINTO: Tentar endpoint direto comum
            println("🔍 Tentando endpoint direto...")
            val directUrl = tryDirectEndpoint(url)
            
            if (directUrl != null) {
                println("✅ Endpoint direto encontrado: $directUrl")
                return processM3u8Stream(directUrl, url, mainUrl, name, callback)
            }
            
            println("❌ Nenhum método funcionou")
            false
            
        } catch (e: Exception) {
            println("❌ GOYABU EXTRACTOR: Erro: ${e.message}")
            false
        }
    }
    
    // ============ EXTRAIR DADOS DA PÁGINA ============
    private fun extractPageData(html: String, url: String): Map<String, String> {
        val data = mutableMapOf<String, String>()
        
        try {
            // 1. Extrair ID do episódio da URL
            val episodeId = extractEpisodeId(url)
            if (episodeId.isNotBlank()) {
                data["episode_id"] = episodeId
                println("📌 ID do episódio: $episodeId")
            }
            
            // 2. Procurar por player ID no HTML
            val playerIdPattern = """data-player-id=["']([^"']+)["']""".toRegex()
            val playerIdMatch = playerIdPattern.find(html)
            if (playerIdMatch != null) {
                val playerId = playerIdMatch.groupValues[1]
                data["player_id"] = playerId
                println("🎮 Player ID: $playerId")
            }
            
            // 3. Procurar por token/security token
            val tokenPattern = """["']token["']\s*:\s*["']([^"']+)["']""".toRegex()
            val tokenMatch = tokenPattern.find(html)
            if (tokenMatch != null) {
                val token = tokenMatch.groupValues[1]
                data["token"] = token
                println("🔐 Token: $token")
            }
            
            // 4. Procurar por timestamp/nocache
            val timestamp = System.currentTimeMillis().toString()
            data["timestamp"] = timestamp
            println("⏰ Timestamp: $timestamp")
            
        } catch (e: Exception) {
            println("⚠️ Erro ao extrair dados da página: ${e.message}")
        }
        
        return data
    }
    
    // ============ CONSTRUIR URL DA API ============
    private fun buildAnivideoApiUrl(data: Map<String, String>, url: String): String? {
        try {
            // Padrão 1: URL direta com ID do episódio
            val episodeId = data["episode_id"]
            if (episodeId != null) {
                val timestamp = data["timestamp"] ?: System.currentTimeMillis().toString()
                return "https://api.anivideo.net/videohls.php?id=$episodeId&nocache=$timestamp"
            }
            
            // Padrão 2: URL com player ID
            val playerId = data["player_id"]
            if (playerId != null) {
                val timestamp = data["timestamp"] ?: System.currentTimeMillis().toString()
                return "https://api.anivideo.net/player.php?id=$playerId&t=$timestamp"
            }
            
            // Padrão 3: URL com token
            val token = data["token"]
            if (token != null) {
                return "https://api.anivideo.net/video.php?token=$token"
            }
            
        } catch (e: Exception) {
            println("⚠️ Erro ao construir URL da API: ${e.message}")
        }
        
        return null
    }
    
    // ============ ENCONTRAR URL DA API NO HTML ============
    private fun findApiUrlInHtml(html: String): String? {
        try {
            // Procurar por padrão exato da API anivideo
            val patterns = listOf(
                """https?://api\.anivideo\.net/videohls\.php\?[^"'\s]+""".toRegex(),
                """["'](https?://api\.anivideo\.net/[^"']+)["']""".toRegex(),
                """src\s*=\s*["'](https?://api\.anivideo\.net/[^"']+)["']""".toRegex(),
                """iframe.*?src=["'](https?://api\.anivideo\.net/[^"']+)["']""".toRegex(RegexOption.DOT_MATCHES_ALL)
            )
            
            for (pattern in patterns) {
                val match = pattern.find(html)
                if (match != null) {
                    var foundUrl = match.value
                    
                    // Se foi capturado grupo, usar o grupo
                    if (match.groupValues.size > 1) {
                        foundUrl = match.groupValues[1]
                    }
                    
                    if (foundUrl.contains("anivideo.net") && foundUrl.contains("videohls.php")) {
                        println("🎯 Padrão encontrado: $foundUrl")
                        return foundUrl
                    }
                }
            }
            
        } catch (e: Exception) {
            println("⚠️ Erro ao buscar URL no HTML: ${e.message}")
        }
        
        return null
    }
    
    // ============ TENTAR ENDPOINT DIRETO ============
    private suspend fun tryDirectEndpoint(url: String): String? {
        return try {
            // Tentar endpoint comum de M3U8
            val directPattern = """(https?://[^"'\s]+\.mp4/index\.m3u8)""".toRegex()
            
            // Fazer requisição para a página
            val response = app.get(url, headers = getFullHeaders(url))
            val html = response.text
            
            // Procurar M3U8 direto no HTML
            val match = directPattern.find(html)
            if (match != null) {
                val m3u8Url = match.groupValues[1]
                println("🎯 M3U8 direto encontrado: $m3u8Url")
                return m3u8Url
            }
            
            null
        } catch (e: Exception) {
            println("⚠️ Erro ao tentar endpoint direto: ${e.message}")
            null
        }
    }
    
    // ============ EXTRAIR M3U8 DA API ============
    private suspend fun extractM3u8FromApi(
        apiUrl: String,
        referer: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔗 Acessando API: ${apiUrl.take(100)}...")
        
        return try {
            // 1. Tentar extrair do parâmetro d=
            if (apiUrl.contains("?d=")) {
                val dParamRegex = Regex("""[?&]d=([^&]+)""")
                val match = dParamRegex.find(apiUrl)
                
                if (match != null) {
                    val encodedM3u8 = match.groupValues[1]
                    val m3u8Url = URLDecoder.decode(encodedM3u8, "UTF-8")
                    
                    if (m3u8Url.startsWith("http") && m3u8Url.contains(".m3u8")) {
                        println("✅ M3U8 extraído do parâmetro d=: $m3u8Url")
                        return processM3u8Stream(m3u8Url, apiUrl, mainUrl, name, callback)
                    }
                }
            }
            
            // 2. Fazer requisição à API
            println("📨 Fazendo requisição à API...")
            val apiResponse = app.get(apiUrl, headers = getApiHeaders(referer))
            val apiContent = apiResponse.text
            
            println("📄 Resposta da API (${apiContent.length} chars)")
            
            // 3. Procurar M3U8 na resposta
            val m3u8Pattern = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""", RegexOption.IGNORE_CASE)
            val m3u8Match = m3u8Pattern.find(apiContent)
            
            if (m3u8Match != null) {
                val m3u8Url = m3u8Match.groupValues[1]
                println("✅ M3U8 encontrado na resposta da API: $m3u8Url")
                return processM3u8Stream(m3u8Url, apiUrl, mainUrl, name, callback)
            }
            
            // 4. Se a resposta já for um M3U8
            if (apiContent.contains("#EXTM3U")) {
                println("✅ Resposta da API já é um M3U8")
                return processM3u8Stream(apiUrl, referer, mainUrl, name, callback)
            }
            
            // 5. Procurar por iframe na resposta
            val iframePattern = Regex("""<iframe[^>]+src=["']([^"']+)["']""")
            val iframeMatch = iframePattern.find(apiContent)
            
            if (iframeMatch != null) {
                val iframeUrl = iframeMatch.groupValues[1]
                println("🎯 Iframe encontrado na API: $iframeUrl")
                
                if (iframeUrl.contains("anivideo.net")) {
                    return extractM3u8FromApi(iframeUrl, apiUrl, mainUrl, name, callback)
                }
            }
            
            println("❌ Não encontrou M3U8 na API")
            false
            
        } catch (e: Exception) {
            println("❌ Erro ao extrair da API: ${e.message}")
            false
        }
    }
    
    // ============ PROCESSAR STREAM M3U8 ============
    private suspend fun processM3u8Stream(
        m3u8Url: String,
        referer: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔗 Processando stream M3U8: $m3u8Url")
        
        return try {
            val headers = mapOf(
                "Referer" to referer,
                "Origin" to "https://goyabu.io",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            )
            
            M3u8Helper.generateM3u8(
                name,
                m3u8Url,
                mainUrl,
                headers = headers
            ).forEach(callback)
            
            println("✅ Stream M3U8 processado com sucesso!")
            true
            
        } catch (e: Exception) {
            println("❌ Erro ao processar stream: ${e.message}")
            false
        }
    }
    
    // ============ FUNÇÕES AUXILIARES ============
    private fun extractEpisodeId(url: String): String {
        // Extrair ID numérico da URL (ex: https://goyabu.io/51971 → 51971)
        val pattern = Regex("""/(\d+)/?$""")
        val match = pattern.find(url)
        return match?.groupValues?.get(1) ?: ""
    }
    
    private fun getFullHeaders(referer: String): Map<String, String> {
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
            "Accept-Encoding" to "gzip, deflate, br",
            "Referer" to "https://goyabu.io/",
            "DNT" to "1",
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "same-origin",
            "Sec-Fetch-User" to "?1",
            "Cache-Control" to "max-age=0"
        )
    }
    
    private fun getApiHeaders(referer: String): Map<String, String> {
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Accept" to "*/*",
            "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
            "Referer" to referer,
            "Origin" to "https://goyabu.io",
            "Connection" to "keep-alive",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site"
        )
    }
}
