package com.Nexflix

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.json.JSONObject
import java.net.URLEncoder

object NexflixExtractor {
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
     * Padrões esperados:
     * - /filme/avatar-fogo-e-cinzas-2025
     * - /serie/nome-da-serie/temporada-1/episodio-1
     */
    private suspend fun extractVideoIdFromUrl(url: String): String {
        return try {
            // Primeiro, tentamos encontrar o ID na URL diretamente
            val cleanUrl = url.replace(Regex("""^https?://[^/]+"""), "")
            
            // Padrões para extrair ID
            val patterns = listOf(
                // Filmes: /filme/nome-2025 -> ID pode ser hash na página
                Regex("""/filme/([^/]+)(?:/|$)"""),
                // Séries: /serie/nome/temporada-X/episodio-Y
                Regex("""/serie/([^/]+)(?:/|$)"""),
                // Fallback: pegar última parte da URL
                Regex("""/([^/]+?)(?:/|\?|$)""")
            )
            
            var extractedId = ""
            
            for (pattern in patterns) {
                val match = pattern.find(cleanUrl)
                if (match != null) {
                    val potentialId = match.groupValues[1]
                    // Verificar se parece um ID (contém números ou letras sem acentos)
                    if (potentialId.matches(Regex("[a-zA-Z0-9-]+")) && potentialId.length > 3) {
                        extractedId = potentialId
                        break
                    }
                }
            }
            
            // Se não encontramos na URL, precisamos buscar na página HTML
            if (extractedId.isEmpty() || extractedId.contains("-")) {
                extractedId = extractIdFromHtmlPage(url)
            }
            
            extractedId
            
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
            val iframeMatch = iframePattern.findAll(html).toList()
            
            for (match in iframeMatch) {
                val iframeUrl = match.groupValues[1]
                println("🔍 Iframe encontrado: $iframeUrl")
                
                // Verificar se é um player nexembed
                if (iframeUrl.contains("nexembed") || iframeUrl.contains("player.php")) {
                    // Extrair ID do parâmetro
                    val idPattern = Regex("""[?&]id=([^&'"]+)""")
                    val idMatch = idPattern.find(iframeUrl)
                    if (idMatch != null) {
                        val foundId = idMatch.groupValues[1]
                        println("✅ ID encontrado no iframe: $foundId")
                        return foundId
                    }
                }
            }
            
            // Procurar por scripts com ID
            val scriptPattern = Regex("""<script[^>]*>.*?(tt[0-9]+).*?</script>""", RegexOption.DOT_MALL)
            val scriptMatch = scriptPattern.find(html)
            if (scriptMatch != null) {
                val foundId = scriptMatch.groupValues[1]
                println("✅ ID encontrado em script: $foundId")
                return foundId
            }
            
            // Procurar por meta tags
            val metaPattern = Regex("""<meta[^>]+content=["']([^"']*tt[0-9]+[^"']*)["'][^>]*>""")
            val metaMatch = metaPattern.find(html)
            if (metaMatch != null) {
                val content = metaMatch.groupValues[1]
                val idInContent = Regex("""(tt[0-9]+)""").find(content)
                if (idInContent != null) {
                    val foundId = idInContent.groupValues[1]
                    println("✅ ID encontrado em meta tag: $foundId")
                    return foundId
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
     * Este é o "pulo do gato" - ignorar o domínio do iframe e bater direto na API
     */
    private suspend fun getM3u8FromApi(videoId: String, originalUrl: String): String? {
        return try {
            println("🚀 Chamando API para ID: $videoId")
            
            // URL da API baseada no seu relatório
            val apiUrl = "$API_DOMAIN/player/index.php?data=$videoId&do=getVideo"
            
            // Headers específicos para a API
            val apiHeaders = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to "$API_DOMAIN/e/$videoId",
                "Origin" to API_DOMAIN,
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "User-Agent" to DEFAULT_HEADERS["User-Agent"]!!,
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
                "Cache-Control" to "no-cache",
                "Pragma" to "no-cache"
            )
            
            // Body da requisição (como no seu relatório)
            val postData = mapOf(
                "hash" to videoId,
                "r" to originalUrl
            )
            
            println("📤 Enviando POST para: $apiUrl")
            println("📦 Dados: $postData")
            
            val response = app.post(apiUrl, headers = apiHeaders, data = postData)
            
            println("📥 Resposta: ${response.code}")
            println("📄 Conteúdo: ${response.text.take(500)}...")
            
            if (response.code != 200) {
                println("❌ Status code inválido: ${response.code}")
                return null
            }
            
            val jsonText = response.text
            if (jsonText.isBlank()) {
                println("❌ Resposta vazia")
                return null
            }
            
            // Parse do JSON
            val json = try {
                JSONObject(jsonText)
            } catch (e: Exception) {
                // Tentar encontrar JSON mesmo se houver lixo antes/depois
                val jsonMatch = Regex("""(\{.*\})""", RegexOption.DOTALL).find(jsonText)
                if (jsonMatch != null) {
                    JSONObject(jsonMatch.groupValues[1])
                } else {
                    throw e
                }
            }
            
            // Extrair o link M3U8 (prioridade: securedLink > videoSource)
            var m3u8Url: String? = null
            
            if (json.has("securedLink")) {
                m3u8Url = json.getString("securedLink")
                println("✅ Usando securedLink")
            } else if (json.has("videoSource")) {
                m3u8Url = json.getString("videoSource")
                println("⚠️  Usando videoSource (fallback)")
            } else if (json.has("url")) {
                m3u8Url = json.getString("url")
                println("⚠️  Usando url (fallback)")
            } else if (json.has("link")) {
                m3u8Url = json.getString("link")
                println("⚠️  Usando link (fallback)")
            }
            
            // Se encontrou um link, garantir que seja HTTPS e válido
            if (m3u8Url != null) {
                // Corrigir URL se necessário
                if (m3u8Url.startsWith("//")) {
                    m3u8Url = "https:$m3u8Url"
                }
                
                // Verificar se é um link M3U8
                if (!m3u8Url.contains(".m3u8") && !m3u8Url.contains(".txt")) {
                    // Tentar extrair de sub-objeto ou array
                    m3u8Url = extractM3u8FromComplexJson(json)
                }
            }
            
            m3u8Url
            
        } catch (e: Exception) {
            println("❌ Erro na API: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * Extrai link M3U8 de estruturas JSON mais complexas
     */
    private fun extractM3u8FromComplexJson(json: JSONObject): String? {
        return try {
            // Tentar diferentes estruturas
            if (json.has("sources")) {
                val sources = json.getJSONArray("sources")
                if (sources.length() > 0) {
                    val firstSource = sources.getJSONObject(0)
                    if (firstSource.has("file")) {
                        return firstSource.getString("file")
                    }
                    if (firstSource.has("url")) {
                        return firstSource.getString("url")
                    }
                }
            }
            
            if (json.has("data")) {
                val data = json.getJSONObject("data")
                if (data.has("url")) {
                    return data.getString("url")
                }
            }
            
            // Procurar por qualquer campo que contenha ".m3u8"
            val jsonString = json.toString()
            val m3u8Pattern = Regex("""https?://[^"\s]+\.m3u8[^"\s]*""")
            val match = m3u8Pattern.find(jsonString)
            match?.value
            
        } catch (e: Exception) {
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
            
            // Headers para o player (BYPASS de segurança)
            val playerHeaders = mapOf(
                "User-Agent" to DEFAULT_HEADERS["User-Agent"]!!,
                "Accept" to "*/*",
                "Accept-Language" to "pt-BR",
                "Accept-Encoding" to "identity",
                "Referer" to "$API_DOMAIN/",
                "Origin" to API_DOMAIN,
                "Sec-Fetch-Mode" to "no-cors",
                "Sec-Fetch-Site" to "cross-site"
            )
            
            // Verificar se é um arquivo M3U8 direto
            if (m3u8Url.contains(".m3u8")) {
                println("📦 É M3U8 direto, gerando links...")
                
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
     * Tenta determinar a qualidade baseada no nome
     */
    private fun getQualityFromName(name: String): Int {
        val lowerName = name.lowercase()
        return when {
            lowerName.contains("4k") || lowerName.contains("2160") -> 2160
            lowerName.contains("1080") -> 1080
            lowerName.contains("720") -> 720
            lowerName.contains("hd") -> 1080
            lowerName.contains("sd") -> 480
            else -> 720 // Default
        }
    }

    /**
     * Função auxiliar para debug
     */
    private suspend fun debugPage(url: String) {
        try {
            println("🐛 DEBUG PAGE: $url")
            val response = app.get(url, headers = DEFAULT_HEADERS)
            println("📄 Tamanho HTML: ${response.text.length}")
            
            // Procurar por iframes
            val iframePattern = Regex("""<iframe[^>]+src=["']([^"']+)["']""")
            val iframes = iframePattern.findAll(response.text).toList()
            println("🔍 ${iframes.size} iframes encontrados:")
            iframes.forEachIndexed { index, match ->
                println("  $index: ${match.groupValues[1]}")
            }
            
            // Procurar por scripts
            val scriptPattern = Regex("""<script[^>]*>.*?(tt[0-9]+|hash.*?:.*?["'][^"']+["']).*?</script>""", RegexOption.DOT_MALL)
            val scripts = scriptPattern.findAll(response.text).toList()
            println("🔍 ${scripts.size} scripts relevantes encontrados")
            
        } catch (e: Exception) {
            println("❌ Debug error: ${e.message}")
        }
    }
}
