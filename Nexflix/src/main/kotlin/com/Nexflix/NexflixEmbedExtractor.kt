package com.Nexflix

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.json.JSONObject
import org.jsoup.Jsoup

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
        url: String, // URL da página do filme: https://nexflix.vip/filme/sob-fogo-2025
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🔍 Iniciando extração para: $url")
            
            // 1. Extrair URL do player nexembed e IMDb ID
            val (playerUrl, imdbId) = extractNexembedUrlAndId(url)
            if (playerUrl.isEmpty() || imdbId.isEmpty()) {
                println("❌ Não foi possível extrair URL do player ou ID")
                return false
            }
            
            println("✅ URL do player: $playerUrl")
            println("✅ IMDb ID: $imdbId")
            
            // 2. Obter hash do player usando IMDb ID
            val videoHash = getHashFromPlayer(imdbId, playerUrl)
            if (videoHash.isEmpty()) {
                println("❌ Não foi possível obter hash do player")
                return false
            }
            
            println("✅ Hash obtido: $videoHash")
            
            // 3. Obter link M3U8 usando o hash
            val m3u8Url = getVideoFromApi(videoHash, playerUrl)
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
     * Passo 1: Extrair URL do player nexembed e IMDb ID
     */
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
            
            // Usar Jsoup para parsear HTML
            val doc = Jsoup.parse(html)
            
            // Procurar por iframes do nexembed
            val iframes = doc.select("iframe[src*='nexembed']")
            println("🔍 Iframes nexembed encontrados: ${iframes.size}")
            
            for (iframe in iframes) {
                val src = iframe.attr("src")
                println("🔍 Iframe encontrado: $src")
                
                if (src.contains("nexembed") && src.contains("player.php")) {
                    // Extrair ID do iframe URL
                    val idPattern = Regex("""[?&]id=([^&]+)""")
                    val match = idPattern.find(src)
                    
                    if (match != null) {
                        val imdbId = match.groupValues[1]
                        println("✅ IMDb ID extraído do iframe: $imdbId")
                        return Pair(src, imdbId)
                    }
                }
            }
            
            // Se não encontrar iframe, procurar em scripts ou links
            println("⚠️  Nenhum iframe encontrado, procurando em scripts...")
            
            // Procurar em scripts JavaScript
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

    /**
     * Passo 2: Obter hash MD5 do player usando IMDb ID
     */

private suspend fun getHashFromPlayer(imdbId: String, refererUrl: String): String {
    return try {
        // DETECTAR SE É FILME OU SÉRIE
        val isMovie = imdbId.startsWith("tt") // IMDb IDs começam com "tt"
        
        val playerUrl = if (isMovie) {
            // URL para filmes: https://comprarebom.xyz/e/tt123456
            "$API_DOMAIN/e/$imdbId"
        } else {
            // URL para séries: https://comprarebom.xyz/e/246386/1/1
            // Precisamos extrair temporada e episódio da URL do player
            val seasonEpisode = extractSeasonEpisode(refererUrl)
            if (seasonEpisode != null) {
                "$API_DOMAIN/e/$imdbId/${seasonEpisode.first}/${seasonEpisode.second}"
            } else {
                // Fallback para temporada 1 episódio 1
                "$API_DOMAIN/e/$imdbId/1/1"
            }
        }
        
        println("🎬 Acessando player para hash: $playerUrl")
        println("🔗 Referer: $refererUrl")
        println("📌 Tipo: ${if (isMovie) "FILME" else "SÉRIE"}")
        
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
 * Extrai temporada e episódio da URL do player
 * Exemplo: https://nexembed.xyz/player.php?type=serie&id=246386&season=1&episode=1
 * Retorna: Pair(1, 1)
 */
private fun extractSeasonEpisode(playerUrl: String): Pair<Int, Int>? {
    return try {
        val seasonPattern = Regex("""[?&]season=(\d+)""")
        val episodePattern = Regex("""[?&]episode=(\d+)""")
        
        val seasonMatch = seasonPattern.find(playerUrl)
        val episodeMatch = episodePattern.find(playerUrl)
        
        if (seasonMatch != null && episodeMatch != null) {
            val season = seasonMatch.groupValues[1].toInt()
            val episode = episodeMatch.groupValues[1].toInt()
            println("✅ Temporada/Episódio extraídos: S${season}E${episode}")
            return Pair(season, episode)
        }
        
        println("⚠️  Não foi possível extrair temporada/episódio da URL")
        null
    } catch (e: Exception) {
        println("❌ Erro ao extrair temporada/episódio: ${e.message}")
        null
    }
}
private fun debugHtmlForHash(html: String) {
    println("\n=== DEBUG HTML PARA HASH ===")
    
    // 1. Procurar por "FirePlayer" no HTML
    val firePlayerIndex = html.indexOf("FirePlayer")
    if (firePlayerIndex > 0) {
        val start = maxOf(0, firePlayerIndex - 150)
        val end = minOf(html.length, firePlayerIndex + 150)
        val context = html.substring(start, end)
        println("\n🔍 Contexto ao redor de 'FirePlayer' (300 chars):")
        println("...$context...")
    } else {
        println("❌ 'FirePlayer' não encontrado no HTML")
    }
    
    // 2. Procurar por "|skin" (padrão visto: HASH|skin)
    val pipeSkinIndex = html.indexOf("|skin")
    if (pipeSkinIndex > 0) {
        val start = maxOf(0, pipeSkinIndex - 50)
        val end = minOf(html.length, pipeSkinIndex + 50)
        val context = html.substring(start, end)
        println("\n🔍 Contexto ao redor de '|skin':")
        println("...$context...")
        
        // Procurar hash antes do |skin
        val beforePipe = html.substring(maxOf(0, pipeSkinIndex - 40), pipeSkinIndex)
        println("🔍 40 chars antes de '|skin': $beforePipe")
        
        // Procurar por hash MD5 nessa região
        val md5Pattern = Regex("""[a-fA-F0-9]{32}""")
        val hashMatch = md5Pattern.find(beforePipe)
        if (hashMatch != null) {
            println("✅ HASH encontrado antes de |skin: ${hashMatch.value}")
        }
    }
    
    // 3. Procurar todos os hashes MD5 no HTML
    val md5Pattern = Regex("""[a-fA-F0-9]{32}""")
    val allHashes = md5Pattern.findAll(html).toList()
    
    println("\n🔍 Todos os hashes MD5 encontrados no HTML (${allHashes.size}):")
    
    val invalidHashes = listOf(
        "cd15cbe7772f49c399c6a5babf22c124", // CloudFlare
        "00000000000000000000000000000000"
    )
    
    allHashes.forEachIndexed { index, match ->
        val hash = match.value
        val hashLower = hash.lowercase()
        
        // Ignorar hashes inválidos
        if (invalidHashes.contains(hashLower) || hashLower.matches(Regex("""\d+"""))) {
            return@forEachIndexed
        }
        
        // Pegar contexto ao redor do hash
        val start = maxOf(0, match.range.first - 30)
        val end = minOf(html.length, match.range.last + 30)
        val context = html.substring(start, end)
        
        println("\nHash #${index + 1}: $hash")
        println("Contexto: ...$context...")
        
        // Verificar se parece ser um hash de vídeo
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
        // Padrão específico: HASH|skin (onde HASH está antes de |skin)
        // Procurar por qualquer coisa seguida de |skin e verificar se é MD5
        val pattern1 = Regex("""([a-fA-F0-9]{32})\|skin""")
        val match1 = pattern1.find(html)
        if (match1 != null) {
            val hash = match1.groupValues[1].lowercase()
            println("✅ Hash encontrado (HASH|skin): $hash")
            return hash
        }
        
        // Padrão: skin|HASH|FirePlayer
        val pattern2 = Regex("""skin\|([a-fA-F0-9]{32})\|FirePlayer""")
        val match2 = pattern2.find(html)
        if (match2 != null) {
            val hash = match2.groupValues[1].lowercase()
            println("✅ Hash encontrado (skin|HASH|FirePlayer): $hash")
            return hash
        }
        
        // Padrão: FirePlayer|HASH|skin (novo padrão que você mencionou)
        val pattern3 = Regex("""FirePlayer\|([a-fA-F0-9]{32})\|skin""")
        val match3 = pattern3.find(html)
        if (match3 != null) {
            val hash = match3.groupValues[1].lowercase()
            println("✅ Hash encontrado (FirePlayer|HASH|skin): $hash")
            return hash
        }
        
        // Padrão: 'HASH'|'FirePlayer'
        val pattern4 = Regex("""['"]([a-fA-F0-9]{32})['"][\s]*[|,][\s]*['"]FirePlayer['"]""")
        val match4 = pattern4.find(html)
        if (match4 != null) {
            val hash = match4.groupValues[1].lowercase()
            println("✅ Hash encontrado ('HASH'|'FirePlayer'): $hash")
            return hash
        }
        
        // Padrão em eval(function
        val pattern5 = Regex("""eval\(function.*?split\('\|'\).*?'([a-fA-F0-9]{32})'""", RegexOption.DOT_MATCHES_ALL)
        val match5 = pattern5.find(html)
        if (match5 != null) {
            val hash = match5.groupValues[1].lowercase()
            println("✅ Hash encontrado (eval/split): $hash")
            return hash
        }
        
        // Buscar qualquer hash MD5 que tenha pipe(|) próximo
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
            
            // Pegar contexto para verificar
            val start = maxOf(0, match.range.first - 10)
            val end = minOf(html.length, match.range.last + 10)
            val context = html.substring(start, end)
            
            // Verificar se tem pipe(|) antes ou depois
            if (context.contains("|")) {
                println("✅ Hash com pipe(|) próximo: $hash (contexto: ...$context...)")
                return hash
            }
        }
        
        println("❌ Nenhum hash válido encontrado no HTML")
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
            
            println("❌ Nenhum link encontrado no JSON")
            println("📄 JSON completo: $jsonText")
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
        
        if (m3u8Url.contains(".m3u8")) {
            println("📦 Processando M3U8...")
            try {
                val allLinks = M3u8Helper.generateM3u8(
                    source = "Nexflix",
                    streamUrl = m3u8Url,
                    referer = "$API_DOMAIN/",
                    headers = playerHeaders
                )
                
                if (allLinks.isNotEmpty()) {
                    println("✅ ${allLinks.size} qualidades geradas no total")
                    
                    // FILTRAR: Manter apenas "Nexflix" (sem qualidade especificada)
                    val filteredLinks = allLinks.filter { link ->
                        // Verificar se é exatamente "Nexflix" ou variações
                        val linkName = link.name.lowercase().trim()
                        val keep = linkName == "nexflix" || 
                                   linkName.contains("nexflix") && !linkName.matches(Regex(".*\\d+p.*"))
                        
                        if (!keep) {
                            println("🔇 Removendo qualidade sem áudio: '${link.name}'")
                        } else {
                            println("🔊 Mantendo qualidade com áudio: '${link.name}' (${link.quality}p)")
                        }
                        
                        keep
                    }
                    
                    // Se não encontrou nenhum "Nexflix", usar o primeiro link
                    val finalLinks = if (filteredLinks.isEmpty()) {
                        println("⚠️  Nenhum link 'Nexflix' encontrado, usando a primeira qualidade")
                        listOf(allLinks.first())
                    } else {
                        filteredLinks
                    }
                    
                    println("🎯 ${finalLinks.size} qualidades com áudio disponíveis:")
                    finalLinks.forEach { link ->
                        println("   → '${link.name}' (${link.quality}p)")
                    }
                    
                    finalLinks.forEach { callback(it) }
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
