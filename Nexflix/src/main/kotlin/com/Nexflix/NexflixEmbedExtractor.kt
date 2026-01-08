package com.Nexflix

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.json.JSONObject
import org.jsoup.Jsoup
import kotlin.math.max
import kotlin.math.min

object NexflixExtractor {
    private const val PLAYER_DOMAIN = "https://nexembed.xyz"
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
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🔍 Iniciando extração para: $url")
            
            val (playerUrl, imdbId) = extractNexembedUrlAndId(url)
            if (playerUrl.isEmpty() || imdbId.isEmpty()) {
                println("❌ Não foi possível extrair URL do player ou ID")
                return false
            }
            
            println("✅ URL do player: $playerUrl")
            println("✅ IMDb ID: $imdbId")
            
            val videoHash = getHashFromPlayer(imdbId, playerUrl)
            if (videoHash.isEmpty()) {
                println("❌ Não foi possível obter hash do player")
                return false
            }
            
            println("✅ Hash obtido: $videoHash")
            
            val m3u8Url = getVideoFromApi(videoHash, playerUrl)
            if (m3u8Url == null) {
                println("❌ Não foi possível obter link M3U8")
                return false
            }
            
            println("✅ Link M3U8: ${m3u8Url.take(80)}...")
            
            createVideoLink(m3u8Url, name, callback)
            
        } catch (e: Exception) {
            println("❌ Erro na extração: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    private suspend fun extractNexembedUrlAndId(pageUrl: String): Pair<String, String> {
        return try {
            println("📄 Buscando página: $pageUrl")
            
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                "Accept-Language" to "pt-BR",
                "Referer" to "https://nexflix.vip/",
                "Upgrade-Insecure-Requests" to "1"
            )
            
            val response = app.get(pageUrl, headers = headers)
            val html = response.text
            
            if (html.isEmpty()) {
                println("❌ HTML vazio")
                return Pair("", "")
            }
            
            val doc = Jsoup.parse(html)
            
            val iframes = doc.select("iframe[src*='nexembed']")
            println("🔍 Iframes nexembed encontrados: ${iframes.size}")
            
            for (iframe in iframes) {
                val src = iframe.attr("src")
                println("🔍 Iframe encontrado: $src")
                
                if (src.contains("nexembed") && src.contains("player.php")) {
                    val idPattern = Regex("""[?&]id=([^&]+)""")
                    val match = idPattern.find(src)
                    
                    if (match != null) {
                        val imdbId = match.groupValues[1]
                        println("✅ IMDb ID extraído do iframe: $imdbId")
                        return Pair(src, imdbId)
                    }
                }
            }
            
            println("⚠️  Nenhum iframe encontrado, procurando em scripts...")
            
            val scripts = doc.select("script")
            for (script in scripts) {
                val scriptContent = script.html()
                if (scriptContent.contains("nexembed") && scriptContent.contains("player.php")) {
                    val pattern = Regex("""(https?://[^"']*nexembed[^"']*player\.php[^"']*)""")
                    val match = pattern.find(scriptContent)
                    
                    if (match != null) {
                        val playerUrl = match.value
                        val idPattern = Regex("""[?&]id=([^&]+)""")
                        val idMatch = idPattern.find(playerUrl)
                        
                        if (idMatch != null) {
                            val imdbId = idMatch.groupValues[1]
                            println("✅ Player URL encontrado em script: $playerUrl")
                            println("✅ IMDb ID extraído: $imdbId")
                            return Pair(playerUrl, imdbId)
                        }
                    }
                }
            }
            
            println("❌ Nenhum player nexembed encontrado")
            Pair("", "")
            
        } catch (e: Exception) {
            println("❌ Erro ao buscar página: ${e.message}")
            Pair("", "")
        }
    }

    private suspend fun getHashFromPlayer(imdbId: String, refererUrl: String): String {
        return try {
            val playerUrl = "$API_DOMAIN/e/$imdbId"
            println("🎬 Acessando player para hash: $playerUrl")
            println("🔗 Referer: $refererUrl")
            
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
            
            val response = app.get(playerUrl, headers = headers, allowRedirects = true)
            
            // DEBUG DOS HEADERS E STATUS
            println("📡 Status: ${response.code}")
            println("📡 URL final: ${response.url}")
            println("📡 Headers da resposta:")
            response.headers.forEach { key, values ->
                values.forEach { value ->
                    println("   $key: $value")
                }
            }
            
            val html = response.text
            
            if (html.isEmpty()) {
                println("❌ HTML do player vazio")
                return ""
            }
            
            println("📄 Tamanho do HTML: ${html.length} chars")
            
            // DEBUG: MOSTRAR HTML COMPLETO
            println("\n" + "=".repeat(80))
            println("📄 HTML COMPLETO DA RESPOSTA (${html.length} chars):")
            println("=".repeat(80))
            
            if (html.length > 2000) {
                println("Primeiros 1000 chars:")
                println(html.substring(0, 1000))
                println("...")
                println("Últimos 1000 chars:")
                println(html.substring(html.length - 1000))
            } else {
                println(html)
            }
            println("=".repeat(80) + "\n")
            
            // TENTATIVA 1: Verificar se é JSON
            val trimmedHtml = html.trim()
            if (trimmedHtml.startsWith("{") && trimmedHtml.endsWith("}")) {
                println("🔍 A resposta parece ser JSON!")
                try {
                    val json = JSONObject(trimmedHtml)
                    
                    // Procurar hash em diferentes campos JSON
                    val possibleKeys = listOf("hash", "video_hash", "md5", "token", "id", "videoHash", "video_id")
                    
                    for (key in possibleKeys) {
                        if (json.has(key)) {
                            val value = json.getString(key)
                            if (value.matches(Regex("""[a-fA-F0-9]{32}"""))) {
                                println("✅ Hash encontrado no JSON (key=$key): $value")
                                return value
                            }
                        }
                    }
                    
                    // Mostrar todas as chaves do JSON para debug
                    println("🔍 Chaves do JSON:")
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = json.optString(key, "")
                        println("   $key: ${if (value.length > 100) value.take(100) + "..." else value}")
                    }
                    
                    // Se não encontrou hash, tentar usar o próprio token
                    if (json.has("token")) {
                        val token = json.getString("token")
                        println("⚠️  Usando token como hash: $token")
                        return token
                    }
                    
                } catch (e: Exception) {
                    println("⚠️  Erro ao parsear JSON: ${e.message}")
                }
            }
            
            // TENTATIVA 2: Buscar hash no HTML normal
            debugHtmlForHash(html)
            return extractHashFromHtml(html)
            
        } catch (e: Exception) {
            println("❌ Erro ao acessar player: ${e.message}")
            e.printStackTrace()
            ""
        }
    }

    private fun debugHtmlForHash(html: String) {
        println("\n=== DEBUG HTML PARA HASH ===")
        
        val firePlayerIndex = html.indexOf("FirePlayer")
        if (firePlayerIndex > 0) {
            val start = max(0, firePlayerIndex - 150)
            val end = min(html.length, firePlayerIndex + 150)
            val context = html.substring(start, end)
            println("\n🔍 Contexto ao redor de 'FirePlayer' (300 chars):")
            println("...$context...")
        } else {
            println("❌ 'FirePlayer' não encontrado no HTML")
        }
        
        val pipeSkinIndex = html.indexOf("|skin")
        if (pipeSkinIndex > 0) {
            val start = max(0, pipeSkinIndex - 50)
            val end = min(html.length, pipeSkinIndex + 50)
            val context = html.substring(start, end)
            println("\n🔍 Contexto ao redor de '|skin':")
            println("...$context...")
            
            val beforePipe = html.substring(max(0, pipeSkinIndex - 40), pipeSkinIndex)
            println("🔍 40 chars antes de '|skin': $beforePipe")
            
            val md5Pattern = Regex("""[a-fA-F0-9]{32}""")
            val hashMatch = md5Pattern.find(beforePipe)
            if (hashMatch != null) {
                println("✅ HASH encontrado antes de |skin: ${hashMatch.value}")
            }
        }
        
        val md5Pattern = Regex("""[a-fA-F0-9]{32}""")
        val allHashes = md5Pattern.findAll(html).toList()
        
        println("\n🔍 Todos os hashes MD5 encontrados no HTML (${allHashes.size}):")
        
        val invalidHashes = listOf(
            "cd15cbe7772f49c399c6a5babf22c124",
            "00000000000000000000000000000000"
        )
        
        allHashes.forEachIndexed { index, match ->
            val hash = match.value
            val hashLower = hash.lowercase()
            
            if (invalidHashes.contains(hashLower) || hashLower.matches(Regex("""\d+"""))) {
                return@forEachIndexed
            }
            
            val start = max(0, match.range.first - 30)
            val end = min(html.length, match.range.last + 30)
            val context = html.substring(start, end)
            
            println("\nHash #${index + 1}: $hash")
            println("Contexto: ...$context...")
            
            val indicators = mutableListOf<String>()
            if (context.contains("FirePlayer")) indicators.add("FirePlayer")
            if (context.contains("skin")) indicators.add("skin")
            if (context.contains("|")) indicators.add("pipe(|)")
            if (context.contains(",")) indicators.add("comma(,)")
            
            if (indicators.isNotEmpty()) {
                println("✅ Indicadores: ${indicators.joinToString(", ")}")
            } else {
                println("⚠️  Sem indicadores claros")
            }
        }
        
        println("=== FIM DEBUG ===\n")
    }

    private fun extractHashFromHtml(html: String): String {
        val pattern1 = Regex("""([a-fA-F0-9]{32})\|skin""")
        val match1 = pattern1.find(html)
        if (match1 != null) {
            val hash = match1.groupValues[1].lowercase()
            println("✅ Hash encontrado (HASH|skin): $hash")
            return hash
        }
        
        val pattern2 = Regex("""skin\|([a-fA-F0-9]{32})\|FirePlayer""")
        val match2 = pattern2.find(html)
        if (match2 != null) {
            val hash = match2.groupValues[1].lowercase()
            println("✅ Hash encontrado (skin|HASH|FirePlayer): $hash")
            return hash
        }
        
        val pattern3 = Regex("""FirePlayer\|([a-fA-F0-9]{32})\|skin""")
        val match3 = pattern3.find(html)
        if (match3 != null) {
            val hash = match3.groupValues[1].lowercase()
            println("✅ Hash encontrado (FirePlayer|HASH|skin): $hash")
            return hash
        }
        
        val pattern4 = Regex("""['"]([a-fA-F0-9]{32})['"][\s]*[|,][\s]*['"]FirePlayer['"]""")
        val match4 = pattern4.find(html)
        if (match4 != null) {
            val hash = match4.groupValues[1].lowercase()
            println("✅ Hash encontrado ('HASH'|'FirePlayer'): $hash")
            return hash
        }
        
        val pattern5 = Regex("""eval\(function.*?split\('\|'\).*?'([a-fA-F0-9]{32})'""", RegexOption.DOT_MATCHES_ALL)
        val match5 = pattern5.find(html)
        if (match5 != null) {
            val hash = match5.groupValues[1].lowercase()
            println("✅ Hash encontrado (eval/split): $hash")
            return hash
        }
        
        // TENTATIVA NOVA: Buscar em atributos data-*
        val dataPattern = Regex("""data-hash=["']([a-fA-F0-9]{32})["']""")
        val dataMatch = dataPattern.find(html)
        if (dataMatch != null) {
            val hash = dataMatch.groupValues[1].lowercase()
            println("✅ Hash encontrado (data-hash): $hash")
            return hash
        }
        
        // TENTATIVA NOVA: Buscar em var hash =
        val varPattern = Regex("""var\s+hash\s*=\s*['"]([a-fA-F0-9]{32})['"]""")
        val varMatch = varPattern.find(html)
        if (varMatch != null) {
            val hash = varMatch.groupValues[1].lowercase()
            println("✅ Hash encontrado (var hash): $hash")
            return hash
        }
        
        // TENTATIVA NOVA: Buscar em JSON dentro do HTML
        val jsonPattern = Regex("""["']hash["']\s*:\s*["']([a-fA-F0-9]{32})["']""")
        val jsonMatch = jsonPattern.find(html)
        if (jsonMatch != null) {
            val hash = jsonMatch.groupValues[1].lowercase()
            println("✅ Hash encontrado (JSON hash): $hash")
            return hash
        }
        
        val md5Pattern = Regex("""[a-fA-F0-9]{32}""")
        val allHashes = md5Pattern.findAll(html).toList()
        
        val invalidHashes = listOf(
            "cd15cbe7772f49c399c6a5babf22c124",
            "00000000000000000000000000000000"
        )
        
        for (match in allHashes) {
            val hash = match.value.lowercase()
            
            if (invalidHashes.contains(hash) || hash.matches(Regex("""\d+"""))) {
                continue
            }
            
            val start = max(0, match.range.first - 10)
            val end = min(html.length, match.range.last + 10)
            val context = html.substring(start, end)
            
            if (context.contains("|")) {
                println("✅ Hash com pipe(|) próximo: $hash (contexto: ...$context...)")
                return hash
            }
        }
        
        println("❌ Nenhum hash válido encontrado no HTML")
        return ""
    }

    private suspend fun getVideoFromApi(videoHash: String, refererUrl: String): String? {
        return try {
            val apiUrl = "$API_DOMAIN/player/index.php?data=$videoHash&do=getVideo"
            
            val postData = mapOf(
                "hash" to videoHash,
                "r" to refererUrl
            )
            
            println("📤 POST para API: $apiUrl")
            println("📦 Dados: hash=$videoHash, r=$refererUrl")
            
            val response = app.post(apiUrl, headers = API_HEADERS, data = postData)
            
            println("📥 Status: ${response.code}")
            
            if (response.code != 200) {
                println("❌ Status inválido: ${response.code}")
                return null
            }
            
            val responseText = response.text.trim()
            println("📄 Resposta (${responseText.length} chars): ${responseText.take(300)}...")
            
            if (responseText.startsWith("{") && responseText.endsWith("}")) {
                parseApiResponse(responseText)
            } else {
                println("❌ Resposta não é JSON")
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
            
            if (json.has("securedLink")) {
                val securedLink = json.getString("securedLink")
                if (securedLink.isNotBlank()) {
                    println("✅ securedLink: ${securedLink.take(80)}...")
                    return securedLink
                }
            }
            
            if (json.has("videoSource")) {
                val videoSource = json.getString("videoSource")
                if (videoSource.isNotBlank()) {
                    println("✅ videoSource: ${videoSource.take(80)}...")
                    return if (videoSource.contains(".txt")) {
                        videoSource.replace(".txt", ".m3u8")
                    } else {
                        videoSource
                    }
                }
            }
            
            // NOVO: Procurar por outros campos possíveis
            val possibleFields = listOf("url", "link", "m3u8", "stream", "source")
            for (field in possibleFields) {
                if (json.has(field)) {
                    val value = json.getString(field)
                    if (value.isNotBlank() && (value.contains("m3u8") || value.contains("mp4"))) {
                        println("✅ Link encontrado no campo '$field': ${value.take(80)}...")
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
