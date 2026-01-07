package com.Nexflix

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.json.JSONObject
import java.net.URLEncoder

object NexflixEmbedExtractor {
    // Domínios principais identificados
    private const val MAIN_DOMAIN = "https://nexflix.vip"
    private const val PLAYER_DOMAIN = "https://nexembed.xyz"
    private const val API_DOMAIN = "https://comprarebom.xyz"
    
    // Headers padrão para simular navegador
    private val DEFAULT_HEADERS = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
        "Accept" to "*/*",
        "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
        "Accept-Encoding" to "gzip, deflate, br",
        "DNT" to "1",
        "Connection" to "keep-alive",
        "Upgrade-Insecure-Requests" to "1",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1",
        "Cache-Control" to "max-age=0"
    )

    suspend fun extractVideoLinks(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🔍 Iniciando extração para: $url")
            
            // 1. Obter o ID do vídeo
            val videoId = extractVideoIdFromUrl(url)
            if (videoId.isEmpty()) {
                println("❌ Não foi possível extrair o ID do vídeo")
                return false
            }
            
            println("✅ ID extraído: $videoId")
            
            // 2. Buscar o link M3U8 via API
            val m3u8Url = getM3u8FromApi(videoId, url)
            if (m3u8Url == null) {
                println("❌ Não foi possível obter link M3U8")
                return false
            }
            
            println("✅ Link M3U8 obtido: ${m3u8Url.take(100)}...")
            
            // 3. Criar e enviar o link para o callback
            createExtractorLink(m3u8Url, name, videoId, callback)
            
        } catch (e: Exception) {
            println("❌ Erro na extração: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * Extrai o ID do vídeo da URL
     */
    private suspend fun extractVideoIdFromUrl(url: String): String {
        return try {
            val cleanUrl = url.replace(Regex("""^https?://[^/]+"""), "")
            
            // Primeiro, tentar padrões específicos
            val specificPatterns = listOf(
                // Padrão: /filme/nome-do-filme
                Regex("""/filme/([^/?]+)"""),
                // Padrão: /serie/nome-da-serie
                Regex("""/serie/([^/?]+)"""),
                // Padrão: tt123456 (IMDb ID)
                Regex("""(tt\d+)""")
            )
            
            for (pattern in specificPatterns) {
                val match = pattern.find(url)
                if (match != null) {
                    val foundId = match.groupValues.getOrNull(1)
                    if (!foundId.isNullOrEmpty() && foundId.length > 3) {
                        return foundId
                    }
                }
            }
            
            // Se não encontrou padrão específico, buscar na página HTML
            extractIdFromHtmlPage(url)
            
        } catch (e: Exception) {
            println("❌ Erro ao extrair ID: ${e.message}")
            ""
        }
    }

    /**
     * Busca o ID do vídeo no HTML da página
     */
    private suspend fun extractIdFromHtmlPage(pageUrl: String): String {
        return try {
            println("📄 Buscando ID no HTML da página: $pageUrl")
            
            val response = app.get(pageUrl, headers = DEFAULT_HEADERS)
            val html = response.text
            
            // Procurar por iframe do player
            val iframePattern = Regex("""<iframe[^>]+src=["']([^"']+)["']""")
            val iframeMatches = iframePattern.findAll(html).toList()
            
            for (match in iframeMatches) {
                val iframeUrl = match.groups[1]?.value
                if (iframeUrl != null) {
                    println("🔍 Iframe encontrado: $iframeUrl")
                    
                    // Verificar se é um player nexembed
                    if (iframeUrl.contains("nexembed") || iframeUrl.contains("player.php")) {
                        // Extrair ID do parâmetro
                        val idPattern = Regex("""[?&]id=([^&'"]+)""")
                        val idMatch = idPattern.find(iframeUrl)
                        if (idMatch != null) {
                            val foundId = idMatch.groups[1]?.value
                            if (!foundId.isNullOrEmpty()) {
                                println("✅ ID encontrado no iframe: $foundId")
                                return foundId
                            }
                        }
                        
                        // Tentar extrair ID da URL
                        val urlIdPattern = Regex("""/([^/?&]+)(?:\.php|\?|$)""")
                        val urlIdMatch = urlIdPattern.find(iframeUrl)
                        if (urlIdMatch != null) {
                            val urlId = urlIdMatch.groups[1]?.value
                            if (!urlId.isNullOrEmpty() && urlId.length > 3) {
                                println("✅ ID extraído da URL: $urlId")
                                return urlId
                            }
                        }
                    }
                }
            }
            
            // Procurar por scripts com ID
            val scriptPattern = Regex("""<script[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            val scriptMatches = scriptPattern.findAll(html).toList()
            
            for (match in scriptMatches) {
                val scriptContent = match.groups[1]?.value ?: continue
                
                // Procurar por IMDb ID (tt123456)
                val imdbPattern = Regex("""(tt\d+)""")
                val imdbMatch = imdbPattern.find(scriptContent)
                if (imdbMatch != null) {
                    val foundId = imdbMatch.groups[1]?.value
                    if (!foundId.isNullOrEmpty()) {
                        println("✅ IMDb ID encontrado em script: $foundId")
                        return foundId
                    }
                }
                
                // Procurar por hash ou ID em variáveis JS
                val varPatterns = listOf(
                    Regex("""['"]hash['"]\s*:\s*['"]([^'"]+)['"]"""),
                    Regex("""['"]id['"]\s*:\s*['"]([^'"]+)['"]"""),
                    Regex("""videoId\s*=\s*['"]([^'"]+)['"]"""),
                    Regex("""data=['"]([^'"]+)['"]""")
                )
                
                for (pattern in varPatterns) {
                    val varMatch = pattern.find(scriptContent)
                    if (varMatch != null) {
                        val foundId = varMatch.groups[1]?.value
                        if (!foundId.isNullOrEmpty() && foundId.length > 3) {
                            println("✅ ID encontrado em variável JS: $foundId")
                            return foundId
                        }
                    }
                }
            }
            
            // Procurar por meta tags
            val metaPattern = Regex("""<meta[^>]+content=["']([^"']*)["'][^>]*>""")
            val metaMatches = metaPattern.findAll(html).toList()
            
            for (match in metaMatches) {
                val content = match.groups[1]?.value ?: continue
                val idInContent = Regex("""(tt\d+)""").find(content)
                if (idInContent != null) {
                    val foundId = idInContent.groups[1]?.value
                    if (!foundId.isNullOrEmpty()) {
                        println("✅ ID encontrado em meta tag: $foundId")
                        return foundId
                    }
                }
            }
            
            // Fallback: usar slug da URL como ID
            val slugPattern = Regex("""/([^/?]+)(?:\?|$|/)""")
            val slugMatch = slugPattern.find(pageUrl)
            if (slugMatch != null) {
                val slug = slugMatch.groups[1]?.value
                if (!slug.isNullOrEmpty() && slug.length > 3 && !slug.contains("filme") && !slug.contains("serie")) {
                    println("⚠️  Usando slug como ID: $slug")
                    return slug
                }
            }
            
            println("❌ Nenhum ID encontrado no HTML")
            ""
            
        } catch (e: Exception) {
            println("❌ Erro ao buscar ID no HTML: ${e.message}")
            ""
        }
    }

    /**
     * Obtém o link M3U8 da API CompraReBom
     */
    private suspend fun getM3u8FromApi(videoId: String, originalUrl: String): String? {
        return try {
            println("🚀 Chamando API para ID: $videoId")
            
            // Primeiro tentar com o ID direto
            var m3u8Url = tryApiCall(videoId, originalUrl)
            
            // Se falhar, tentar limpar o ID
            if (m3u8Url == null) {
                val cleanId = cleanVideoId(videoId)
                if (cleanId != videoId) {
                    println("🔄 Tentando com ID limpo: $cleanId")
                    m3u8Url = tryApiCall(cleanId, originalUrl)
                }
            }
            
            m3u8Url
            
        } catch (e: Exception) {
            println("❌ Erro na API: ${e.message}")
            null
        }
    }

    /**
     * Limpa o ID do vídeo (remove prefixos/sufixos)
     */
    private fun cleanVideoId(videoId: String): String {
        return videoId
            .replace("-", "")  // Remove hífens
            .replace("_", "")  // Remove underscores
            .replace(".", "")  // Remove pontos
            .trim()            // Remove espaços
    }

    /**
     * Tenta fazer a chamada à API
     */
    private suspend fun tryApiCall(videoId: String, originalUrl: String): String? {
        return try {
            // URL da API
            val apiUrl = "$API_DOMAIN/player/index.php?data=$videoId&do=getVideo"
            
            // Headers específicos para a API
            val apiHeaders = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to "$API_DOMAIN/e/$videoId",
                "Origin" to API_DOMAIN,
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "User-Agent" to DEFAULT_HEADERS["User-Agent"] ?: "Mozilla/5.0",
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
                "Cache-Control" to "no-cache",
                "Pragma" to "no-cache"
            )
            
            // Body da requisição
            val postData = mapOf(
                "hash" to videoId,
                "r" to originalUrl
            )
            
            println("📤 Enviando POST para: $apiUrl")
            
            val response = app.post(apiUrl, headers = apiHeaders, data = postData)
            
            println("📥 Status: ${response.code}")
            
            if (response.code != 200) {
                println("❌ Status code inválido: ${response.code}")
                return null
            }
            
            val jsonText = response.text
            if (jsonText.isBlank()) {
                println("❌ Resposta vazia")
                return null
            }
            
            println("📄 Resposta (primeiros 500 chars): ${jsonText.take(500)}...")
            
            // Parse do JSON
            parseApiResponse(jsonText)
            
        } catch (e: Exception) {
            println("❌ Erro na chamada da API: ${e.message}")
            null
        }
    }

    /**
     * Processa a resposta da API
     */
    private fun parseApiResponse(jsonText: String): String? {
        return try {
            // Tentar parsear como JSON
            val json = try {
                JSONObject(jsonText)
            } catch (e: Exception) {
                // Tentar extrair JSON mesmo com lixo
                val jsonMatch = Regex("""(\{.*\})""", RegexOption.DOT_MATCHES_ALL).find(jsonText)
                if (jsonMatch != null) {
                    JSONObject(jsonMatch.groups[1]?.value ?: jsonText)
                } else {
                    println("❌ Não é JSON válido")
                    return null
                }
            }
            
            // Procurar por link em diferentes caminhos
            val linkPaths = listOf(
                arrayOf("securedLink"),
                arrayOf("videoSource"),
                arrayOf("url"),
                arrayOf("link"),
                arrayOf("data", "url"),
                arrayOf("sources", "0", "file"),
                arrayOf("sources", "0", "url")
            )
            
            for (path in linkPaths) {
                var current: Any? = json
                for (key in path) {
                    current = when (current) {
                        is JSONObject -> if (current.has(key)) current.get(key) else null
                        else -> null
                    }
                    if (current == null) break
                }
                
                if (current is String && current.isNotBlank()) {
                    var url = current
                    
                    // Garantir que seja HTTPS
                    if (url.startsWith("//")) {
                        url = "https:$url"
                    } else if (url.startsWith("/")) {
                        url = "https://comprarebom.xyz$url"
                    }
                    
                    // Verificar se parece um link de vídeo
                    if (url.contains(".m3u8") || url.contains(".mp4") || url.contains(".txt")) {
                        println("✅ Link encontrado em ${path.joinToString(".")}: ${url.take(80)}...")
                        return url
                    }
                }
            }
            
            // Procurar por link em texto
            val linkPattern = Regex("""https?://[^"\s]+\.(?:m3u8|mp4|txt)[^"\s]*""")
            val linkMatch = linkPattern.find(jsonText)
            if (linkMatch != null) {
                val url = linkMatch.groups[0]?.value
                if (!url.isNullOrEmpty()) {
                    println("✅ Link encontrado via regex: ${url.take(80)}...")
                    return url
                }
            }
            
            println("❌ Nenhum link encontrado na resposta")
            null
            
        } catch (e: Exception) {
            println("❌ Erro ao processar resposta: ${e.message}")
            null
        }
    }

    /**
     * Cria e envia o ExtractorLink para o callback
     */
    private suspend fun createExtractorLink(
        m3u8Url: String,
        name: String,
        videoId: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🎬 Preparando player para: $name")
            
            // Headers para o player
            val playerHeaders = mapOf(
                "User-Agent" to DEFAULT_HEADERS["User-Agent"] ?: "Mozilla/5.0",
                "Accept" to "*/*",
                "Accept-Language" to "pt-BR",
                "Accept-Encoding" to "identity",
                "Referer" to "$API_DOMAIN/",
                "Origin" to API_DOMAIN,
                "Sec-Fetch-Mode" to "no-cors",
                "Sec-Fetch-Site" to "cross-site"
            )
            
            // Verificar se é M3U8
            if (m3u8Url.contains(".m3u8")) {
                println("📦 É M3U8, gerando links...")
                
                try {
                    val links = M3u8Helper.generateM3u8(
                        source = "Nexflix",
                        streamUrl = m3u8Url,
                        referer = "$API_DOMAIN/",
                        headers = playerHeaders
                    )
                    
                    if (links.isNotEmpty()) {
                        println("✅ ${links.size} links M3U8 gerados")
                        links.forEach { callback(it) }
                        return true
                    } else {
                        println("⚠️  M3u8Helper não gerou links, usando fallback")
                    }
                } catch (e: Exception) {
                    println("⚠️  Erro no M3u8Helper: ${e.message}, usando fallback")
                }
            }
            
            // Fallback: criar link simples
            println("⚠️  Criando link fallback...")
            
            val fallbackLink = newExtractorLink(
                source = "Nexflix",
                name = name,
                url = m3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "$API_DOMAIN/"
                this.quality = getQualityFromName(name)
                this.headers = playerHeaders
            }
            
            callback(fallbackLink)
            true
            
        } catch (e: Exception) {
            println("❌ Erro ao criar link: ${e.message}")
            false
        }
    }

    /**
     * Tenta determinar a qualidade
     */
    private fun getQualityFromName(name: String): Int {
        val lowerName = name.lowercase()
        return when {
            lowerName.contains("4k") || lowerName.contains("2160") -> 2160
            lowerName.contains("1080") -> 1080
            lowerName.contains("720") -> 720
            lowerName.contains("hd") -> 1080
            lowerName.contains("sd") -> 480
            else -> 720
        }
    }
}
