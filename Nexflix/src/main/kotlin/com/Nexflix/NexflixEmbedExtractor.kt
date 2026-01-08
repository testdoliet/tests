package com.Nexflix

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.json.JSONObject

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
        "sec-ch-ua" to "\"Chromium\";v=\"127\"",
        "sec-ch-ua-mobile" to "?1",
        "sec-ch-ua-platform" to "\"Android\"",
        "sec-fetch-dest" to "empty",
        "sec-fetch-mode" to "cors",
        "sec-fetch-site" to "same-origin",
        "user-agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
        "x-requested-with" to "XMLHttpRequest"
    )

    suspend fun extractVideoLinks(
        url: String, // URL da página do filme: https://nexflix.vip/filme/sob-fogo-2025
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🔍 Iniciando extração para: $url")
            
            // 1. Extrair IMDb ID (tt32212611) da página do filme
            val imdbId = extractImdbIdFromPage(url)
            if (imdbId.isEmpty()) {
                println("❌ Não foi possível extrair IMDb ID")
                return false
            }
            
            println("✅ IMDb ID encontrado: $imdbId")
            
            // 2. Obter hash do player usando IMDb ID
            val videoHash = getHashFromPlayer(imdbId, url)
            if (videoHash.isEmpty()) {
                println("❌ Não foi possível obter hash do player")
                return false
            }
            
            println("✅ Hash obtido: $videoHash")
            
            // 3. Obter link M3U8 usando o hash
            val m3u8Url = getVideoFromApi(videoHash, url)
            if (m3u8Url == null) {
                println("❌ Não foi possível obter link M3U8")
                return false
            }
            
            println("✅ Link M3U8: ${m3u8Url.take(80)}...")
            
            // 4. Criar e enviar link
            createVideoLink(m3u8Url, name, callback)
            
        } catch (e: Exception) {
            println("❌ Erro na extração: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * Passo 1: Extrair IMDb ID (tt32212611) da página do filme
     */
    private suspend fun extractImdbIdFromPage(movieUrl: String): String {
        return try {
            println("📄 Buscando página do filme: $movieUrl")
            
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                "Accept-Language" to "pt-BR",
                "Referer" to "https://nexflix.vip/",
                "Upgrade-Insecure-Requests" to "1"
            )
            
            val response = app.get(movieUrl, headers = headers)
            val html = response.text
            
            if (html.isEmpty()) {
                println("❌ HTML vazio")
                return ""
            }
            
            // Procurar IMDb ID em iframes ou links
            extractImdbId(html)
            
        } catch (e: Exception) {
            println("❌ Erro ao buscar página: ${e.message}")
            ""
        }
    }

    private fun extractImdbId(html: String): String {
        // Padrão 1: player.php?type=filme&id=tt32212611
        val pattern1 = Regex("""player\.php\?type=filme&id=(tt\d+)""")
        val match1 = pattern1.find(html)
        if (match1 != null) {
            val imdbId = match1.groupValues[1]
            println("✅ IMDb ID encontrado no player.php: $imdbId")
            return imdbId
        }
        
        // Padrão 2: /e/tt32212611
        val pattern2 = Regex("""/e/(tt\d+)""")
        val match2 = pattern2.find(html)
        if (match2 != null) {
            val imdbId = match2.groupValues[1]
            println("✅ IMDb ID encontrado em /e/: $imdbId")
            return imdbId
        }
        
        // Padrão 3: nexembed.xyz/player.php?type=filme&id=tt32212611
        val pattern3 = Regex("""nexembed\.xyz/player\.php\?[^"']*id=(tt\d+)""")
        val match3 = pattern3.find(html)
        if (match3 != null) {
            val imdbId = match3.groupValues[1]
            println("✅ IMDb ID encontrado em nexembed: $imdbId")
            return imdbId
        }
        
        // Padrão 4: data-id="tt32212611"
        val pattern4 = Regex("""data-id=["'](tt\d+)["']""")
        val match4 = pattern4.find(html)
        if (match4 != null) {
            val imdbId = match4.groupValues[1]
            println("✅ IMDb ID encontrado em data-id: $imdbId")
            return imdbId
        }
        
        // Padrão 5: Qualquer tt\d+ na página
        val pattern5 = Regex("""(tt\d{7,})""")
        val matches = pattern5.findAll(html).toList()
        for (match in matches) {
            val imdbId = match.value
            // Verificar se não é parte de outro texto
            if (imdbId.length in 9..12) { // tt + 7-10 dígitos
                println("⚠️  IMDb ID potencial encontrado: $imdbId")
                return imdbId
            }
        }
        
        println("❌ Nenhum IMDb ID encontrado")
        return ""
    }

    /**
     * Passo 2: Obter hash MD5 do player usando IMDb ID
     */
    private suspend fun getHashFromPlayer(imdbId: String, refererUrl: String): String {
        return try {
            val playerUrl = "$API_DOMAIN/e/$imdbId"
            println("🎬 Acessando player: $playerUrl")
            
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                "Accept-Language" to "pt-BR",
                "Referer" to refererUrl,
                "Sec-Fetch-Dest" to "iframe",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "cross-site",
                "Upgrade-Insecure-Requests" to "1"
            )
            
            val response = app.get(playerUrl, headers = headers)
            val html = response.text
            
            if (html.isEmpty()) {
                println("❌ HTML do player vazio")
                return ""
            }
            
            // Extrair hash MD5 do JavaScript ofuscado
            extractHashFromPlayerHtml(html)
            
        } catch (e: Exception) {
            println("❌ Erro ao acessar player: ${e.message}")
            ""
        }
    }

    private fun extractHashFromPlayerHtml(html: String): String {
        // Padrão específico visto nos logs: skin|HASH|FirePlayer
        val patterns = listOf(
            Regex("""skin\|([a-fA-F0-9]{32})\|FirePlayer"""),
            Regex("""['"]([a-fA-F0-9]{32})['"][\s]*[|,][\s]*['"]FirePlayer['"]"""),
            Regex("""FirePlayer.*?['"]([a-fA-F0-9]{32})['"]"""),
            Regex("""\.split\('\|'\).*?'([a-fA-F0-9]{32})'""", RegexOption.DOT_MATCHES_ALL)
        )
        
        for (pattern in patterns) {
            val match = pattern.find(html)
            if (match != null) {
                val hash = match.groupValues[1].lowercase()
                if (hash.length == 32) {
                    println("✅ Hash encontrado: $hash")
                    return hash
                }
            }
        }
        
        // Fallback: buscar qualquer hash MD5 válido
        val md5Pattern = Regex("""[a-fA-F0-9]{32}""")
        val allHashes = md5Pattern.findAll(html).toList()
        
        for (match in allHashes) {
            val hash = match.value.lowercase()
            // Filtrar hashes comuns/inválidos
            if (hash != "cd15cbe7772f49c399c6a5babf22c124" && // CloudFlare
                !hash.matches(Regex("""\d+"""))) { // Não é só números
                println("⚠️  Hash potencial: $hash")
                return hash
            }
        }
        
        println("❌ Nenhum hash encontrado no player")
        return ""
    }

    /**
     * Passo 3: Obter link M3U8 da API usando o hash
     */
    private suspend fun getVideoFromApi(videoHash: String, refererUrl: String): String? {
        return try {
            val apiUrl = "$API_DOMAIN/player/index.php?data=$videoHash&do=getVideo"
            
            val postData = mapOf(
                "hash" to videoHash,
                "r" to refererUrl
            )
            
            println("📤 POST para API: $apiUrl")
            
            val response = app.post(apiUrl, headers = API_HEADERS, data = postData)
            
            println("📥 Status: ${response.code}")
            
            if (response.code != 200) {
                println("❌ Status inválido: ${response.code}")
                return null
            }
            
            val responseText = response.text.trim()
            println("📄 Resposta (${responseText.length} chars): ${responseText.take(200)}...")
            
            // Verificar se é JSON válido
            if (responseText.startsWith("{") && responseText.endsWith("}")) {
                parseApiResponse(responseText)
            } else {
                println("❌ Resposta não é JSON: ${responseText.take(100)}")
                null
            }
            
        } catch (e: Exception) {
            println("❌ Erro na API: ${e.message}")
            null
        }
    }

    private fun parseApiResponse(jsonText: String): String? {
        return try {
            val json = JSONObject(jsonText)
            
            // Prioridade 1: securedLink
            if (json.has("securedLink")) {
                val securedLink = json.getString("securedLink")
                if (securedLink.isNotBlank()) {
                    println("✅ securedLink encontrado")
                    return securedLink
                }
            }
            
            // Prioridade 2: videoSource (pode ser .txt)
            if (json.has("videoSource")) {
                val videoSource = json.getString("videoSource")
                if (videoSource.isNotBlank()) {
                    println("✅ videoSource encontrado")
                    // Converter .txt para .m3u8 se necessário
                    return if (videoSource.contains(".txt")) {
                        videoSource.replace(".txt", ".m3u8")
                    } else {
                        videoSource
                    }
                }
            }
            
            // Prioridade 3: outros campos possíveis
            val possibleFields = listOf("url", "link", "source", "file", "stream")
            for (field in possibleFields) {
                if (json.has(field)) {
                    val value = json.getString(field)
                    if (value.isNotBlank() && (value.contains("m3u8") || value.contains("mp4"))) {
                        println("✅ Campo '$field' encontrado: ${value.take(80)}...")
                        return value
                    }
                }
            }
            
            println("❌ Nenhum link encontrado no JSON")
            println("📄 JSON: $jsonText")
            null
            
        } catch (e: Exception) {
            println("❌ Erro ao parsear JSON: ${e.message}")
            null
        }
    }

    /**
     * Passo 4: Criar link do vídeo
     */
    private suspend fun createVideoLink(
        m3u8Url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🎬 Criando link para: $name")
            
            val playerHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                "Accept" to "*/*",
                "Accept-Language" to "pt-BR",
                "Referer" to "$API_DOMAIN/",
                "Origin" to API_DOMAIN
            )
            
            // Se for M3U8, usar M3u8Helper
            if (m3u8Url.contains(".m3u8")) {
                println("📦 Processando M3U8...")
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
            
            // Fallback: link direto
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
