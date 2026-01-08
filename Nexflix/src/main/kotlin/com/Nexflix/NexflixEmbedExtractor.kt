package com.Nexflix

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.json.JSONObject

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
            
            // 1. Extrair ID da URL do player
            val videoId = extractIdFromPlayerUrl(url)
            if (videoId.isEmpty()) {
                println("❌ Não foi possível extrair ID da URL")
                return false
            }
            
            println("✅ ID extraído: $videoId")
            
            // 2. Obter hash do player usando o ID
            val videoHash = getHashFromPlayer(videoId, url)
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

    private fun extractIdFromPlayerUrl(playerUrl: String): String {
        val idPattern = Regex("""[?&]id=([^&]+)""")
        val match = idPattern.find(playerUrl)
        
        return if (match != null) {
            val id = match.groupValues[1]
            println("✅ ID extraído da URL: $id")
            id
        } else {
            println("❌ Não foi possível extrair ID da URL")
            ""
        }
    }

    private suspend fun getHashFromPlayer(videoId: String, refererUrl: String): String {
        return try {
            val playerUrl = "$API_DOMAIN/e/$videoId"
            println("🎬 Acessando player: $playerUrl")
            
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                "Accept-Language" to "pt-BR",
                "Referer" to PLAYER_DOMAIN,
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
            
            println("📄 Tamanho do HTML: ${html.length} chars")
            
            // DEBUG: Mostrar parte do HTML onde deve estar o hash
            debugHtmlForHash(html)
            
            // Extrair hash MD5 do JavaScript
            extractHashFromHtml(html)
            
        } catch (e: Exception) {
            println("❌ Erro ao acessar player: ${e.message}")
            ""
        }
    }

    /**
     * DEBUG: Mostrar partes do HTML onde o hash pode estar
     */
    private fun debugHtmlForHash(html: String) {
        println("\n=== DEBUG HTML PARA HASH ===")
        
        // 1. Procurar por "FirePlayer" no HTML
        val firePlayerIndex = html.indexOf("FirePlayer")
        if (firePlayerIndex > 0) {
            val start = maxOf(0, firePlayerIndex - 100)
            val end = minOf(html.length, firePlayerIndex + 300)
            val context = html.substring(start, end)
            println("\n🔍 Contexto ao redor de 'FirePlayer':")
            println("...${context}...")
        } else {
            println("❌ 'FirePlayer' não encontrado no HTML")
        }
        
        // 2. Procurar por "eval(function" (código ofuscado)
        val evalIndex = html.indexOf("eval(function")
        if (evalIndex > 0) {
            val start = maxOf(0, evalIndex)
            val end = minOf(html.length, evalIndex + 800)
            val context = html.substring(start, end)
            println("\n🔍 Contexto ao redor de 'eval(function':")
            println("${context}...")
        } else {
            println("❌ 'eval(function' não encontrado no HTML")
        }
        
        // 3. Procurar por "skin|" (padrão visto)
        val skinIndex = html.indexOf("skin|")
        if (skinIndex > 0) {
            val start = maxOf(0, skinIndex - 50)
            val end = minOf(html.length, skinIndex + 200)
            val context = html.substring(start, end)
            println("\n🔍 Contexto ao redor de 'skin|':")
            println("...${context}...")
        }
        
        // 4. Procurar todos os hashes MD5 no HTML
        val md5Pattern = Regex("""[a-fA-F0-9]{32}""")
        val allHashes = md5Pattern.findAll(html).toList()
        
        println("\n🔍 Todos os hashes MD5 encontrados no HTML:")
        if (allHashes.isEmpty()) {
            println("❌ Nenhum hash MD5 encontrado")
        } else {
            allHashes.forEachIndexed { index, match ->
                val hash = match.value
                // Pegar contexto ao redor do hash
                val start = maxOf(0, match.range.first - 30)
                val end = minOf(html.length, match.range.last + 50)
                val context = html.substring(start, end)
                
                println("\nHash #${index + 1}: $hash")
                println("Contexto: ...${context}...")
                
                // Verificar se parece ser um hash de vídeo
                when {
                    context.contains("FirePlayer") -> println("✅ PRÓXIMO A FirePlayer!")
                    context.contains("skin") -> println("✅ PRÓXIMO A skin!")
                    context.contains("|") -> println("✅ TEM | (pipe) próximo")
                    else -> println("⚠️  Sem indicadores claros")
                }
            }
        }
        
        // 5. Procurar por scripts JavaScript
        val scriptPattern = Regex("""<script[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
        val scripts = scriptPattern.findAll(html).toList()
        
        println("\n🔍 Scripts encontrados: ${scripts.size}")
        scripts.forEachIndexed { index, script ->
            val content = script.groups[1]?.value ?: ""
            if (content.contains("FirePlayer") || content.contains("eval(")) {
                println("\nScript #${index + 1} (${content.length} chars) - CONTÉM FirePlayer/eval")
                // Mostrar início do script
                val preview = if (content.length > 500) {
                    content.substring(0, 500) + "..."
                } else {
                    content
                }
                println("Preview: $preview")
            }
        }
        
        println("=== FIM DEBUG ===\n")
    }

    private fun extractHashFromHtml(html: String): String {
        // Padrão mais específico baseado nos logs: skin|HASH|FirePlayer
        val pattern1 = Regex("""skin\|([a-fA-F0-9]{32})\|FirePlayer""")
        val match1 = pattern1.find(html)
        if (match1 != null) {
            val hash = match1.groupValues[1].lowercase()
            println("✅ Hash encontrado (skin|HASH|FirePlayer): $hash")
            return hash
        }
        
        // Padrão alternativo: 'HASH'|'FirePlayer'
        val pattern2 = Regex("""['"]([a-fA-F0-9]{32})['"][\s]*[|,][\s]*['"]FirePlayer['"]""")
        val match2 = pattern2.find(html)
        if (match2 != null) {
            val hash = match2.groupValues[1].lowercase()
            println("✅ Hash encontrado ('HASH'|'FirePlayer'): $hash")
            return hash
        }
        
        // Padrão: FirePlayer('HASH',
        val pattern3 = Regex("""FirePlayer\s*\(\s*['"]([a-fA-F0-9]{32})['"]""")
        val match3 = pattern3.find(html)
        if (match3 != null) {
            val hash = match3.groupValues[1].lowercase()
            println("✅ Hash encontrado (FirePlayer('HASH')): $hash")
            return hash
        }
        
        // Padrão em eval(function
        val pattern4 = Regex("""eval\(function.*?split\('\|'\).*?'([a-fA-F0-9]{32})'""", RegexOption.DOT_MATCHES_ALL)
        val match4 = pattern4.find(html)
        if (match4 != null) {
            val hash = match4.groupValues[1].lowercase()
            println("✅ Hash encontrado (eval/split): $hash")
            return hash
        }
        
        // Buscar qualquer hash MD5 que não seja do CloudFlare
        val md5Pattern = Regex("""[a-fA-F0-9]{32}""")
        val allHashes = md5Pattern.findAll(html).toList()
        
        // Filtrar hashes inválidos
        val invalidHashes = listOf(
            "cd15cbe7772f49c399c6a5babf22c124", // CloudFlare
            "00000000000000000000000000000000"
        )
        
        for (match in allHashes) {
            val hash = match.value.lowercase()
            
            // Ignorar hashes inválidos
            if (invalidHashes.contains(hash) || hash.matches(Regex("""\d+"""))) {
                continue
            }
            
            // Pegar contexto para verificar
            val start = maxOf(0, match.range.first - 20)
            val end = minOf(html.length, match.range.last + 20)
            val context = html.substring(start, end)
            
            // Verificar se tem indicadores de ser hash de vídeo
            if (context.contains("|") || context.contains("FirePlayer") || 
                context.contains("skin") || context.contains(",")) {
                println("✅ Hash potencial encontrado: $hash (contexto: ...$context...)")
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
            
            val response = app.post(apiUrl, headers = API_HEADERS, data = postData)
            
            println("📥 Status: ${response.code}")
            
            if (response.code != 200) {
                println("❌ Status inválido: ${response.code}")
                return null
            }
            
            val responseText = response.text.trim()
            println("📄 Resposta (${responseText.length} chars): ${responseText.take(200)}...")
            
            // DEBUG: Mostrar resposta completa se for pequena
            if (responseText.length < 1000) {
                println("📄 Resposta completa: $responseText")
            }
            
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
            
            println("❌ Nenhum link encontrado no JSON")
            println("📄 JSON: $jsonText")
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
