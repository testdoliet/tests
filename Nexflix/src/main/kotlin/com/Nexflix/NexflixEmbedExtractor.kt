package com.Nexflix

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.json.JSONObject
import org.jsoup.Jsoup

object NexflixEmbedExtractor {
    private const val DEFAULT_DOMAIN = "https://comprarebom.xyz"
    private var currentDomain = DEFAULT_DOMAIN
    
    private fun getHeaders(referer: String? = null): Map<String, String> {
        val headers = mutableMapOf(
            "accept" to "application/json, text/javascript, */*; q=0.01",
            "accept-language" to "pt-BR,pt;q=0.9,en;q=0.8",
            "content-type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "origin" to currentDomain,
            "sec-ch-ua" to "\"Chromium\";v=\"127\", \"Not)A;Brand\";v=\"99\"",
            "sec-ch-ua-mobile" to "?1",
            "sec-ch-ua-platform" to "\"Android\"",
            "sec-fetch-dest" to "empty",
            "sec-fetch-mode" to "cors",
            "sec-fetch-site" to "same-origin",
            "user-agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
            "x-requested-with" to "XMLHttpRequest"
        )
        
        referer?.let { headers["referer"] = it }
        return headers
    }

    suspend fun extractVideoLinks(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🔍 Iniciando extração para: $url")
            
            // Primeiro tentar descobrir o domínio correto
            currentDomain = discoverCorrectDomain(url)
            println("🌐 Usando domínio: $currentDomain")
            
            // 1. Buscar página do player
            val (videoHash, playerHtml) = extractHashAndHtmlFromPlayerPage(url)
            if (videoHash.isEmpty()) {
                println("❌ Não foi possível extrair hash")
                // Tentar extrair de forma alternativa
                val altHash = extractHashFromHtml(playerHtml)
                if (altHash.isEmpty()) {
                    return false
                }
                return processWithHash(altHash, url, name, callback)
            }
            
            return processWithHash(videoHash, url, name, callback)
            
        } catch (e: Exception) {
            println("❌ Erro na extração: ${e.message}")
            false
        }
    }
    
    private suspend fun processWithHash(
        videoHash: String, 
        refererUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("✅ Hash a ser usado: $videoHash")
        
        // 2. Fazer POST para API
        val m3u8Url = getVideoFromApi(videoHash, refererUrl)
        if (m3u8Url == null) {
            println("❌ Não foi possível obter link M3U8")
            return false
        }
        
        println("✅ Link M3U8: ${m3u8Url.take(80)}...")
        
        // 3. Criar link
        return createVideoLink(m3u8Url, name, callback)
    }

    /**
     * Passo 1: Extrair hash e HTML da página
     */
    private suspend fun extractHashAndHtmlFromPlayerPage(playerUrl: String): Pair<String, String> {
        return try {
            println("📄 Buscando página do player: $playerUrl")
            
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                "Accept-Language" to "pt-BR",
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
                return Pair("", "")
            }
            
            // Salvar HTML para debug
            println("📄 Tamanho HTML: ${html.length} chars")
            
            // Extrair hash
            val hash = extractHashFromHtml(html)
            Pair(hash, html)
            
        } catch (e: Exception) {
            println("❌ Erro ao buscar página: ${e.message}")
            Pair("", "")
        }
    }
    
    private fun extractHashFromHtml(html: String): String {
        // Método 1: Buscar padrão específico visto no exemplo
        val specificPattern = Regex("""skin\|([a-fA-F0-9]{32})\|FirePlayer""")
        val specificMatch = specificPattern.find(html)
        if (specificMatch != null) {
            val hash = specificMatch.groupValues[1]
            if (hash.length == 32) {
                println("✅ Hash encontrado (padrão skin|hash|FirePlayer): $hash")
                return hash.lowercase()
            }
        }
        
        // Método 2: Buscar em scripts
        val doc = Jsoup.parse(html)
        val scripts = doc.select("script:not([src])")
        
        for (script in scripts) {
            val content = script.html()
            if (content.contains("FirePlayer") || content.contains("eval(function")) {
                // Padrão: 'hash'|'FirePlayer'|...
                val pattern = Regex("""['"]([a-fA-F0-9]{32})['"][\s]*[|,][\s]*['"]FirePlayer['"]""")
                val match = pattern.find(content)
                if (match != null) {
                    val hash = match.groupValues[1]
                    println("✅ Hash encontrado em script: $hash")
                    return hash.lowercase()
                }
            }
        }
        
        // Método 3: Buscar qualquer hash MD5 próximo a "FirePlayer"
        val firePlayerPattern = Regex("""FirePlayer.*?['"]([a-fA-F0-9]{32})['"]""")
        val fpMatch = firePlayerPattern.find(html)
        if (fpMatch != null) {
            val hash = fpMatch.groupValues[1]
            println("✅ Hash próximo a FirePlayer: $hash")
            return hash.lowercase()
        }
        
        // Método 4: Buscar o primeiro hash MD5 válido (não do CloudFlare)
        val allHashes = Regex("""[a-fA-F0-9]{32}""").findAll(html).toList()
        for (match in allHashes) {
            val hash = match.value
            // Filtrar hashes conhecidos do CloudFlare/outros
            if (hash != "cd15cbe7772f49c399c6a5babf22c124" && // CloudFlare do log
                hash != "00000000000000000000000000000000" &&
                !hash.matches(Regex("""\d+"""))) { // Não é só números
                println("⚠️  Hash potencial: $hash")
                return hash.lowercase()
            }
        }
        
        return ""
    }

    /**
     * Passo 2: Obter vídeo da API
     */
    private suspend fun getVideoFromApi(videoHash: String, refererUrl: String): String? {
        return try {
            val apiUrl = "$currentDomain/player/index.php?data=$videoHash&do=getVideo"
            
            val postData = mapOf(
                "hash" to videoHash,
                "r" to refererUrl
            )
            
            println("📤 POST para: $apiUrl")
            
            val response = app.post(apiUrl, headers = getHeaders("$currentDomain/"), data = postData)
            
            println("📥 Status: ${response.code}")
            
            if (response.code != 200) {
                println("❌ Status inválido: ${response.code}")
                return null
            }
            
            val responseText = response.text.trim()
            println("📄 Resposta (${responseText.length} chars): ${responseText.take(300)}")
            
            // Verificar se é JSON
            if (responseText.startsWith("{") && responseText.endsWith("}")) {
                return parseApiResponse(responseText)
            } else {
                println("❌ Resposta não é JSON: ${responseText.take(100)}")
                return null
            }
            
        } catch (e: Exception) {
            println("❌ Erro na API: ${e.message}")
            null
        }
    }

    /**
     * Parse da resposta JSON
     */
    private fun parseApiResponse(jsonText: String): String? {
        return try {
            val json = JSONObject(jsonText)
            
            // Prioridade 1: securedLink
            if (json.has("securedLink")) {
                val securedLink = json.getString("securedLink")
                if (securedLink.isNotBlank()) {
                    println("✅ securedLink: ${securedLink.take(80)}...")
                    return securedLink
                }
            }
            
            // Prioridade 2: videoSource
            if (json.has("videoSource")) {
                val videoSource = json.getString("videoSource")
                if (videoSource.isNotBlank()) {
                    println("✅ videoSource: ${videoSource.take(80)}...")
                    // Converter .txt para .m3u8 se necessário
                    return if (videoSource.contains(".txt")) {
                        videoSource.replace(".txt", ".m3u8")
                    } else {
                        videoSource
                    }
                }
            }
            
            // Prioridade 3: URL direta em outros campos
            val fields = listOf("url", "link", "source", "file", "stream")
            for (field in fields) {
                if (json.has(field)) {
                    val value = json.getString(field)
                    if (value.isNotBlank() && (value.contains("m3u8") || value.contains("mp4"))) {
                        println("✅ Campo '$field': ${value.take(80)}...")
                        return value
                    }
                }
            }
            
            println("❌ Nenhum link encontrado no JSON")
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
                "Referer" to "$currentDomain/",
                "Origin" to currentDomain
            )
            
            // Se for M3U8, usar M3u8Helper
            if (m3u8Url.contains(".m3u8")) {
                println("📦 Processando stream M3U8...")
                try {
                    val links = M3u8Helper.generateM3u8(
                        source = "Nexflix",
                        streamUrl = m3u8Url,
                        referer = "$currentDomain/",
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
                this.referer = "$currentDomain/"
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

    /**
     * Descobrir domínio correto
     */
    private suspend fun discoverCorrectDomain(playerUrl: String): String {
        // Extrair domínio da URL do player
        val domainPattern = Regex("""https?://([^/]+)""")
        val match = domainPattern.find(playerUrl)
        match?.let {
            val extractedDomain = it.value
            println("🌐 Domínio extraído da URL: $extractedDomain")
            return extractedDomain
        }
        
        return DEFAULT_DOMAIN
    }
}
