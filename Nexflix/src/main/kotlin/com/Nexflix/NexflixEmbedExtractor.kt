package com.Nexflix

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.json.JSONObject
import java.util.regex.Pattern

object NexflixEmbedExtractor {
    private const val API_DOMAIN = "https://comprarebom.xyz"
    
    private val API_HEADERS = mapOf(
        "accept" to "*/*",
        "accept-language" to "pt-BR,pt;q=0.9,en;q=0.8",
        "cache-control" to "no-cache",
        "content-type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "origin" to API_DOMAIN,
        "pragma" to "no-cache",
        "priority" to "u=1, i",
        "sec-ch-ua" to "\"Chromium\";v=\"127\", \"Not)A;Brand\";v=\"99\"",
        "sec-ch-ua-mobile" to "?1",
        "sec-ch-ua-platform" to "\"Android\"",
        "sec-fetch-dest" to "empty",
        "sec-fetch-mode" to "cors",
        "sec-fetch-site" to "same-origin",
        "user-agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
        "x-requested-with" to "XMLHttpRequest"
    )

    suspend fun extractVideoLinks(
        url: String,  // URL do iframe tipo: https://comprarebom.xyz/e/tt27543632
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🔍 Iniciando extração para: $url")
            
            // 1. Buscar página do player para extrair hash
            val videoHash = extractHashFromPlayerPage(url)
            if (videoHash.isEmpty()) {
                println("❌ Não foi possível extrair hash da página")
                return false
            }
            
            println("✅ Hash extraído: $videoHash")
            
            // 2. Fazer POST para API com o hash
            val m3u8Url = getVideoFromApi(videoHash, url)
            if (m3u8Url == null) {
                println("❌ Não foi possível obter link M3U8")
                return false
            }
            
            println("✅ Link M3U8: ${m3u8Url.take(80)}...")
            
            // 3. Criar e enviar link
            createVideoLink(m3u8Url, name, callback)
            
        } catch (e: Exception) {
            println("❌ Erro na extração: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * Passo 1: Extrair hash MD5 da página do player
     */
    private suspend fun extractHashFromPlayerPage(playerUrl: String): String {
        return try {
            println("📄 Buscando página do player: $playerUrl")
            
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                "Accept-Language" to "pt-BR",
                "Cache-Control" to "no-cache",
                "Pragma" to "no-cache",
                "Referer" to "https://nexembed.xyz/",
                "Sec-Fetch-Dest" to "iframe",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "cross-site",
                "Upgrade-Insecure-Requests" to "1"
            )
            
            val response = app.get(playerUrl, headers = headers)
            val html = response.text
            
            if (html.isEmpty()) {
                println("❌ HTML vazio")
                return ""
            }
            
            // Buscar hash no JavaScript ofuscado
            val hash = extractHashFromObfuscatedJs(html)
            if (hash.isNotEmpty()) {
                println("✅ Hash encontrado no JS: $hash")
                return hash
            }
            
            // Tentar métodos alternativos
            extractHashAlternativeMethods(html)
            
        } catch (e: Exception) {
            println("❌ Erro ao buscar página: ${e.message}")
            ""
        }
    }

    /**
     * Extrair hash do JavaScript ofuscado
     */
    
     private fun extractHashFromObfuscatedJs(html: String): String {
    // Padrão mais específico: hash seguido de "FirePlayer" e parâmetros comuns
    val patterns = listOf(
        // Padrão 1: '89fcd07f20b6785b92134bd6c1d0fa42|FirePlayer|x59|netflix'
        Regex("""([a-fA-F0-9]{32})\|FirePlayer\|[^'"]+"""),
        
        // Padrão 2: Dentro de eval(function
        Regex("""eval\(function.*?'([a-fA-F0-9]{32})'.*?split\('\|'\)""", RegexOption.DOT_MATCHES_ALL),
        
        // Padrão 3: Em arrays JavaScript
        Regex("""['"]([a-fA-F0-9]{32})['"]\s*[,\|]"""),
        
        // Padrão 4: Próximo a "skin" (visto no exemplo)
        Regex("""skin\|([a-fA-F0-9]{32})\|""")
    )
    
    for (pattern in patterns) {
        val match = pattern.find(html)
        if (match != null) {
            val hash = match.groupValues[1]
            if (hash.length == 32 && hash.matches(Regex("[a-fA-F0-9]{32}"))) {
                println("✅ Hash encontrado com padrão: ${hash.lowercase()}")
                return hash.lowercase()
            }
        }
    }
    
    return ""
     }
  
    private fun extractHashAlternativeMethods(html: String): String {
        // Método 1: Buscar todas as ocorrências de 32 caracteres hex
        val all32hex = Regex("""[a-fA-F0-9]{32}""").findAll(html).toList()
        for (match in all32hex) {
            val potentialHash = match.value
            // Verificar se não é algo comum (like CSS colors, etc)
            if (!potentialHash.matches(Regex("""\d+""")) && // Não é só números
                potentialHash != "00000000000000000000000000000000" &&
                !html.contains("$potentialHash.css") && // Não é nome de arquivo CSS
                !html.contains("$potentialHash.js")) {  // Não é nome de arquivo JS
                println("⚠️  Hash potencial encontrado: $potentialHash")
                return potentialHash.lowercase()
            }
        }
        
        // Método 2: Buscar em scripts específicos
        val scriptPattern = Regex("""<script[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
        val scripts = scriptPattern.findAll(html).toList()
        
        for (script in scripts) {
            val content = script.groups[1]?.value ?: continue
            if (content.contains("FirePlayer")) {
                // Buscar primeiro hash MD5 após FirePlayer
                val firePlayerPattern = Regex("""FirePlayer.*?['"]([a-fA-F0-9]{32})['"]""")
                val fpMatch = firePlayerPattern.find(content)
                if (fpMatch != null) {
                    return fpMatch.groupValues[1].lowercase()
                }
            }
        }
        
        return ""
    }

    /**
     * Passo 2: Fazer POST para API com o hash
     */
    private suspend fun getVideoFromApi(videoHash: String, refererUrl: String): String? {
    return try {
        val apiUrl = "$API_DOMAIN/player/index.php?data=$videoHash&do=getVideo"
        
        val postData = mapOf(
            "hash" to videoHash,
            "r" to refererUrl
        )
        
        println("📤 POST para: $apiUrl")
        
        // Tentar com headers diferentes
        val finalHeaders = mapOf(
            "accept" to "application/json, text/javascript, */*; q=0.01",
            "accept-language" to "pt-BR,pt;q=0.9,en;q=0.8",
            "content-type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "origin" to API_DOMAIN,
            "referer" to "$API_DOMAIN/",
            "sec-ch-ua" to "\"Chromium\";v=\"127\", \"Not)A;Brand\";v=\"99\"",
            "sec-ch-ua-mobile" to "?1",
            "sec-ch-ua-platform" to "\"Android\"",
            "sec-fetch-dest" to "empty",
            "sec-fetch-mode" to "cors",
            "sec-fetch-site" to "same-origin",
            "user-agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
            "x-requested-with" to "XMLHttpRequest"
        )
        
        val response = app.post(apiUrl, headers = finalHeaders, data = postData)
        
        println("📥 Status: ${response.code}")
        println("📄 Resposta (primeiros 500 chars): ${response.text.take(500)}")
        
        if (response.code != 200) {
            println("❌ Status inválido: ${response.code}")
            return null
        }
        
        val responseText = response.text
        
        // Verificar se é HTML de erro
        if (responseText.contains("<!DOCTYPE html") || responseText.contains("<html")) {
            println("❌ API retornou HTML em vez de JSON")
            println("📄 HTML: ${responseText.take(200)}...")
            return null
        }
        
        parseApiResponse(responseText)
        
    } catch (e: Exception) {
        println("❌ Erro na API: ${e.message}")
        null
    }
    }
    /**
     * Parse da resposta JSON da API
     */
    private fun parseApiResponse(jsonText: String): String? {
        return try {
            val json = JSONObject(jsonText)
            
            // Prioridade 1: securedLink (URL M3U8 direta)
            if (json.has("securedLink")) {
                val securedLink = json.getString("securedLink")
                if (securedLink.isNotBlank() && (securedLink.contains(".m3u8") || securedLink.contains(".txt"))) {
                    println("✅ Usando securedLink")
                    // Converter .txt para .m3u8 se necessário
                    return if (securedLink.contains(".txt")) {
                        securedLink.replace(".txt", ".m3u8")
                    } else {
                        securedLink
                    }
                }
            }
            
            // Prioridade 2: videoSource (pode ser .txt que vira .m3u8)
            if (json.has("videoSource")) {
                val videoSource = json.getString("videoSource")
                if (videoSource.isNotBlank()) {
                    println("⚠️  Usando videoSource (fallback)")
                    if (videoSource.contains(".txt")) {
                        return videoSource.replace(".txt", ".m3u8")
                    }
                    return videoSource
                }
            }
            
            // Prioridade 3: hls com caminho específico
            if (json.has("hls") && json.getBoolean("hls")) {
                // Tentar construir URL baseada no padrão comum
                val m3u8Url = "$API_DOMAIN/cdn/hls/VIDEO_ID/master.m3u8"
                println("⚠️  Tentando padrão HLS comum")
                return m3u8Url
            }
            
            println("❌ Nenhum link válido encontrado no JSON")
            println("📄 JSON completo: $jsonText")
            null
            
        } catch (e: Exception) {
            println("❌ Erro ao parsear JSON: ${e.message}")
            null
        }
    }

    /**
     * Passo 3: Criar link do vídeo
     */
    private suspend fun createVideoLink(
        m3u8Url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🎬 Preparando vídeo: $name")
            
            // Headers para o player
            val playerHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                "Accept" to "*/*",
                "Accept-Language" to "pt-BR",
                "Referer" to "$API_DOMAIN/",
                "Origin" to API_DOMAIN
            )
            
            // Se for M3U8, usar M3u8Helper
            if (m3u8Url.contains(".m3u8")) {
                println("📦 Processando stream M3U8...")
                try {
                    val links = M3u8Helper.generateM3u8(
                        source = "Nexflix",
                        streamUrl = m3u8Url,
                        referer = "$API_DOMAIN/",
                        headers = playerHeaders
                    )
                    
                    if (links.isNotEmpty()) {
                        println("✅ ${links.size} qualidades geradas")
                        links.forEach { callback(it) }
                        return true
                    }
                } catch (e: Exception) {
                    println("⚠️  Erro no M3u8Helper: ${e.message}")
                }
            }
            
            // Fallback: link direto simples
            println("⚠️  Usando link direto...")
            
            val quality = when {
                name.contains("4k", true) || name.contains("2160") -> 2160
                name.contains("1080") -> 1080
                name.contains("720") -> 720
                name.contains("hd", true) -> 1080
                name.contains("sd", true) -> 480
                else -> 720
            }
            
            val link = newExtractorLink(
                source = "Nexflix",
                name = name,
                url = m3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "$API_DOMAIN/"
                this.quality = quality
                this.headers = playerHeaders
            }
            
            callback(link)
            true
            
        } catch (e: Exception) {
            println("❌ Erro ao criar link: ${e.message}")
            false
        }
    }
}
