package com.Goyabu

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.Jsoup
import java.net.URLDecoder

object GoyabuDirectExtractor {
    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🎬 GOYABU DIRECT EXTRACTOR: Iniciando extração para: $url")
        
        return try {
            // Estratégia 1: Buscar iframe direto
            println("🔍 Estratégia 1: Buscando iframe direto...")
            val iframeUrl = findIframeUrl(url)
            if (iframeUrl != null) {
                println("✅ Iframe encontrado: $iframeUrl")
                if (extractFromIframe(iframeUrl, url, mainUrl, name, callback)) {
                    return true
                }
            }
            
            // Estratégia 2: Buscar M3U8 na página
            println("🔍 Estratégia 2: Buscando M3U8 na página...")
            val pageM3u8 = findM3u8InPage(url)
            if (pageM3u8 != null) {
                println("✅ M3U8 encontrado na página: $pageM3u8")
                return processM3u8(pageM3u8, url, mainUrl, name, callback)
            }
            
            // Estratégia 3: Buscar via API direta
            println("🔍 Estratégia 3: Tentando API direta...")
            if (tryDirectApi(url, mainUrl, name, callback)) {
                return true
            }
            
            // Estratégia 4: Buscar em scripts
            println("🔍 Estratégia 4: Analisando scripts da página...")
            val scriptUrl = findVideoInScripts(url)
            if (scriptUrl != null) {
                println("✅ URL encontrada em script: $scriptUrl")
                return processM3u8(scriptUrl, url, mainUrl, name, callback)
            }
            
            println("❌ Nenhuma estratégia funcionou")
            false
            
        } catch (e: Exception) {
            println("❌ GOYABU DIRECT EXTRACTOR: Erro: ${e.message}")
            false
        }
    }
    
    // ============ ENCONTRAR IFRAME ============
    private suspend fun findIframeUrl(pageUrl: String): String? {
        return try {
            val response = app.get(pageUrl, headers = getHeaders())
            val doc = Jsoup.parse(response.text)
            
            // Procurar iframes
            val iframes = doc.select("iframe[src]")
            for (iframe in iframes) {
                val src = iframe.attr("src")
                if (src.isNotEmpty() && (src.contains("anivideo") || src.contains("cdn") || src.contains("stream"))) {
                    println("🎯 Iframe encontrado: $src")
                    return src
                }
            }
            
            null
        } catch (e: Exception) {
            println("⚠️ Erro ao buscar iframe: ${e.message}")
            null
        }
    }
    
    // ============ EXTRAIR DE IFRAME ============
    private suspend fun extractFromIframe(
        iframeUrl: String,
        referer: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔗 Processando iframe: $iframeUrl")
        
        return try {
            // Se for URL da anivideo, extrair parâmetros
            if (iframeUrl.contains("anivideo")) {
                // Extrair parâmetro d
                val dParam = extractDParam(iframeUrl)
                if (dParam != null) {
                    println("🔑 Parâmetro d encontrado: ${dParam.take(100)}...")
                    val decoded = URLDecoder.decode(dParam, "UTF-8")
                    println("🔓 Decodificado: ${decoded.take(100)}...")
                    
                    if (decoded.contains(".m3u8")) {
                        return processM3u8(decoded, iframeUrl, mainUrl, name, callback)
                    }
                }
                
                // Fazer requisição ao iframe
                val iframeResponse = app.get(iframeUrl, headers = getHeaders(referer))
                val iframeContent = iframeResponse.text
                
                // Procurar M3U8 no iframe
                val patterns = listOf(
                    """src:\s*["'](https?://[^"']+\.m3u8)["']""".toRegex(),
                    """file:\s*["'](https?://[^"']+\.m3u8)["']""".toRegex(),
                    """["'](https?://[^"']+\.m3u8)["']""".toRegex(),
                    """(https?://[^\s"']+\.m3u8)""".toRegex()
                )
                
                for (pattern in patterns) {
                    val match = pattern.find(iframeContent)
                    if (match != null) {
                        val m3u8Url = match.groupValues.getOrNull(1) ?: match.value
                        println("🎯 M3U8 no iframe: $m3u8Url")
                        return processM3u8(m3u8Url, iframeUrl, mainUrl, name, callback)
                    }
                }
            }
            
            false
        } catch (e: Exception) {
            println("❌ Erro no iframe: ${e.message}")
            false
        }
    }
    
    // ============ EXTRAIR PARÂMETRO D ============
    private fun extractDParam(url: String): String? {
        val patterns = listOf(
            """[?&]d=([^&]+)""".toRegex(),
            """["']d["']\s*:\s*["']([^"']+)["']""".toRegex(),
            """data-d\s*=\s*["']([^"']+)["']""".toRegex()
        )
        
        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        
        return null
    }
    
    // ============ BUSCAR M3U8 NA PÁGINA ============
    private suspend fun findM3u8InPage(pageUrl: String): String? {
        return try {
            val response = app.get(pageUrl, headers = getHeaders())
            val html = response.text
            
            // Padrões para M3U8
            val patterns = listOf(
                // Padrão específico do CDN
                """(https?://cdn-s01[^"'\s]+\.m3u8)""".toRegex(),
                // Padrão em scripts
                """["']file["']\s*:\s*["'](https?://[^"']+\.m3u8)["']""".toRegex(),
                """["']src["']\s*:\s*["'](https?://[^"']+\.m3u8)["']""".toRegex(),
                // Padrão geral
                """(https?://[^"'\s]+\.mp4/index\.m3u8)""".toRegex(),
                """(https?://[^"'\s]+\.m3u8[^"'\s]*)""".toRegex()
            )
            
            for (pattern in patterns) {
                val matches = pattern.findAll(html)
                for (match in matches) {
                    var url = match.value
                    if (match.groupValues.size > 1) {
                        url = match.groupValues[1]
                    }
                    
                    if (url.isNotEmpty()) {
                        println("🎯 M3U8 encontrado com padrão: ${url.take(100)}...")
                        return url
                    }
                }
            }
            
            null
        } catch (e: Exception) {
            println("⚠️ Erro ao buscar M3U8: ${e.message}")
            null
        }
    }
    
    // ============ TENTAR API DIRETA ============
    private suspend fun tryDirectApi(
        pageUrl: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // Extrair ID da URL
            val idPattern = Regex("""/(\d+)/?$""")
            val idMatch = idPattern.find(pageUrl)
            
            if (idMatch != null) {
                val episodeId = idMatch.groupValues[1]
                println("📌 ID do episódio: $episodeId")
                
                // Tentar diferentes APIs
                val apiUrls = listOf(
                    "https://api.anivideo.net/videohls.php?id=$episodeId&nocache=${System.currentTimeMillis()}",
                    "https://api.anivideo.net/videohls.php?id=$episodeId",
                    "https://api.anivideo.net/video.php?id=$episodeId",
                    "https://api.anivideo.net/play.php?id=$episodeId"
                )
                
                for (apiUrl in apiUrls) {
                    println("🔗 Testando API: $apiUrl")
                    
                    try {
                        val apiResponse = app.get(apiUrl, headers = getApiHeaders(pageUrl))
                        val content = apiResponse.text
                        
                        // Verificar se é M3U8
                        if (content.contains("#EXTM3U")) {
                            println("✅ API retornou M3U8")
                            return processM3u8(apiUrl, pageUrl, mainUrl, name, callback)
                        }
                        
                        // Procurar M3U8 na resposta
                        val m3u8Pattern = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""")
                        val m3u8Match = m3u8Pattern.find(content)
                        
                        if (m3u8Match != null) {
                            val m3u8Url = m3u8Match.value
                            println("✅ M3U8 na resposta: $m3u8Url")
                            return processM3u8(m3u8Url, apiUrl, mainUrl, name, callback)
                        }
                        
                        // Verificar se tem URL codificada
                        if (content.contains("d=")) {
                            val encodedPattern = Regex("""d=([^&\s]+)""")
                            val encodedMatch = encodedPattern.find(content)
                            
                            if (encodedMatch != null) {
                                val encoded = encodedMatch.groupValues[1]
                                val decoded = URLDecoder.decode(encoded, "UTF-8")
                                println("🔓 URL decodificada: ${decoded.take(100)}...")
                                
                                if (decoded.contains(".m3u8")) {
                                    return processM3u8(decoded, apiUrl, mainUrl, name, callback)
                                }
                            }
                        }
                        
                    } catch (e: Exception) {
                        println("⚠️ API falhou: ${e.message}")
                        continue
                    }
                }
            }
            
            false
        } catch (e: Exception) {
            println("❌ Erro na API direta: ${e.message}")
            false
        }
    }
    
    // ============ BUSCAR EM SCRIPTS ============
    private suspend fun findVideoInScripts(pageUrl: String): String? {
        return try {
            val response = app.get(pageUrl, headers = getHeaders())
            val html = response.text
            
            // Extrair scripts
            val scriptPattern = Regex("""<script[^>]*>(.*?)</script>""", RegexOption.DOT_MATTERS_ALL)
            val scripts = scriptPattern.findAll(html)
            
            for (script in scripts) {
                val scriptContent = script.groupValues[1]
                
                // Procurar URLs de vídeo em scripts
                val videoPatterns = listOf(
                    """["']file["']\s*:\s*["'](https?://[^"']+)["']""".toRegex(),
                    """["']src["']\s*:\s*["'](https?://[^"']+)["']""".toRegex(),
                    """["']url["']\s*:\s*["'](https?://[^"']+)["']""".toRegex(),
                    """(https?://[^"'\s]+\.m3u8[^"'\s]*)""".toRegex(),
                    """player\.setup\([^)]*["'](https?://[^"']+)["']""".toRegex()
                )
                
                for (pattern in videoPatterns) {
                    val match = pattern.find(scriptContent)
                    if (match != null) {
                        var url = match.value
                        if (match.groupValues.size > 1) {
                            url = match.groupValues[1]
                        }
                        
                        if (url.contains("m3u8")) {
                            println("🎯 URL em script: ${url.take(100)}...")
                            return url
                        }
                    }
                }
            }
            
            null
        } catch (e: Exception) {
            println("⚠️ Erro em scripts: ${e.message}")
            null
        }
    }
    
    // ============ PROCESSAR M3U8 ============
    private suspend fun processM3u8(
        m3u8Url: String,
        referer: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔗 Processando M3U8: ${m3u8Url.take(150)}...")
        
        return try {
            // Verificar se a URL é válida
            if (!m3u8Url.startsWith("http")) {
                println("❌ URL inválida: $m3u8Url")
                return false
            }
            
            // Fazer requisição para verificar
            val headers = getM3u8Headers(referer)
            val response = app.get(m3u8Url, headers = headers)
            val content = response.text
            
            if (!content.contains("#EXTM3U")) {
                println("❌ Não é M3U8 válido")
                
                // Tentar extrair M3U8 da resposta
                val extractedM3u8 = extractM3u8FromContent(content)
                if (extractedM3u8 != null) {
                    println("🔄 Extraindo M3U8 da resposta: ${extractedM3u8.take(100)}...")
                    return processM3u8(extractedM3u8, m3u8Url, mainUrl, name, callback)
                }
                
                return false
            }
            
            println("✅ M3U8 válido encontrado!")
            println("📄 Cabeçalhos: ${response.headers}")
            println("📄 Primeiros 500 chars: ${content.take(500)}...")
            
            // Gerar links com M3u8Helper
            M3u8Helper.generateM3u8(
                name,
                m3u8Url,
                mainUrl,
                headers = headers
            ).forEach(callback)
            
            println("✅ M3U8 processado com sucesso!")
            true
            
        } catch (e: Exception) {
            println("❌ Erro ao processar M3U8: ${e.message}")
            false
        }
    }
    
    // ============ EXTRAIR M3U8 DO CONTEÚDO ============
    private fun extractM3u8FromContent(content: String): String? {
        val patterns = listOf(
            """(https?://[^\s"']+\.m3u8[^\s"']*)""".toRegex(),
            """["'](https?://[^"']+\.m3u8)["']""".toRegex(),
            """file:\s*["'](https?://[^"']+\.m3u8)["']""".toRegex(),
            """src:\s*["'](https?://[^"']+\.m3u8)["']""".toRegex()
        )
        
        for (pattern in patterns) {
            val match = pattern.find(content)
            if (match != null) {
                var url = match.value
                if (match.groupValues.size > 1) {
                    url = match.groupValues[1]
                }
                return url
            }
        }
        
        return null
    }
    
    // ============ HEADERS ============
    private fun getHeaders(referer: String? = null): Map<String, String> {
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to (referer ?: "https://goyabu.io/"),
            "DNT" to "1",
            "Connection" to "keep-alive"
        )
    }
    
    private fun getApiHeaders(referer: String): Map<String, String> {
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Accept" to "*/*",
            "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
            "Referer" to referer,
            "Origin" to "https://goyabu.io",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors"
        )
    }
    
    private fun getM3u8Headers(referer: String): Map<String, String> {
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Accept" to "*/*",
            "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
            "Referer" to referer,
            "Origin" to "https://goyabu.io",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors"
        )
    }
}
