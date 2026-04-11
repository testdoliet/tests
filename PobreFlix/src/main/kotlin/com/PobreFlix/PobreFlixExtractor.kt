package com.PobreFlix.extractor

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

object PobreFlixExtractor {

    private const val BASE_URL = "https://superflixapi.rest"
    private const val CDN_BASE = "https://llanfairpwllgwyngy.com"

    private val HEADERS = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "pt-BR",
        "Accept-Encoding" to "identity",
        "Referer" to "https://lospobreflix.site/",
        "Sec-Fetch-Dest" to "iframe",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "cross-site",
        "Upgrade-Insecure-Requests" to "1",
        "Connection" to "keep-alive"
    )

    private val API_HEADERS = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "pt-BR",
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "X-Requested-With" to "XMLHttpRequest",
        "Origin" to BASE_URL,
        "Connection" to "keep-alive"
    )

    private var sessionCookies: String = ""

    private fun getCookieHeader(): Map<String, String> {
        return if (sessionCookies.isNotEmpty()) mapOf("Cookie" to sessionCookies) else emptyMap()
    }

    private fun extractContentIdFromTruncatedJson(html: String, targetSeason: Int, targetEpisode: Int): String? {
        println("[PobreFlix] 🔍 Buscando episódio S${targetSeason}E${targetEpisode}")
        
        // ===== DEBUG: Mostrar parte do HTML para entender o formato =====
        val sample = html.substring(0, minOf(3000, html.length))
        println("[PobreFlix] 📄 SAMPLE DO HTML (primeiros 3000 chars):")
        println("[PobreFlix] $sample")
        
        // ===== DEBUG: Verificar se existem padrões conhecidos =====
        val hasSeasonPattern = html.contains("\"season\":$targetSeason")
        val hasEpiNumPattern = html.contains("\"epi_num\":$targetEpisode")
        val hasIdPattern = html.contains("\"ID\"")
        
        println("[PobreFlix] 🔍 Contém '\"season\":$targetSeason': $hasSeasonPattern")
        println("[PobreFlix] 🔍 Contém '\"epi_num\":$targetEpisode': $hasEpiNumPattern")
        println("[PobreFlix] 🔍 Contém '\"ID\"': $hasIdPattern")
        
        // Também procurar por ALL_EPISODES
        val hasAllEpisodes = html.contains("allEpisodes")
        println("[PobreFlix] 🔍 Contém 'allEpisodes': $hasAllEpisodes")
        
        // ===== FIM DOS DEBUGS =====
        
        // CÓDIGO ORIGINAL - Método 1
        val pattern = Regex("\"season\":$targetSeason,\"epi_num\":$targetEpisode[^}]*?\"ID\":(\\d+)")
        val match = pattern.find(html)
        
        if (match != null) {
            val contentId = match.groupValues[1]
            println("[PobreFlix] ✅ Content ID via season+epi_num: $contentId")
            return contentId
        }
        
        // CÓDIGO ORIGINAL - Método 2
        val seasonPattern = Regex("\"$targetSeason\":\\s*\\[([\\s\\S]*?)\\]")
        val seasonMatch = seasonPattern.find(html)
        
        if (seasonMatch != null) {
            val seasonContent = seasonMatch.groupValues[1]
            val episodePattern = Regex("\"epi_num\":$targetEpisode[^}]*?\"ID\":(\\d+)")
            val episodeMatch = episodePattern.find(seasonContent)
            
            if (episodeMatch != null) {
                val contentId = episodeMatch.groupValues[1]
                println("[PobreFlix] ✅ Content ID via temporada: $contentId")
                return contentId
            }
        }
        
        // CÓDIGO ORIGINAL - Método 3
        val simplePattern = Regex("\"epi_num\":$targetEpisode[,\\s]*?\"ID\":(\\d+)")
        val simpleMatch = simplePattern.find(html)
        
        if (simpleMatch != null) {
            val contentId = simpleMatch.groupValues[1]
            println("[PobreFlix] ✅ Content ID via padrão simples: $contentId")
            return contentId
        }
        
        println("[PobreFlix] ❌ Content ID NÃO encontrado para S${targetSeason}E${targetEpisode}")
        return null
    }

    suspend fun getStreams(
        tmdbId: Int,
        mediaType: String,
        season: Int,
        episode: Int
    ): List<ExtractorLink> {
        val results = mutableListOf<ExtractorLink>()
        val targetSeason = if (mediaType == "filme") 1 else season
        val targetEpisode = if (mediaType == "filme") 1 else episode
        
        println("[PobreFlix] ═══════════════════════════════════════")
        println("[PobreFlix] 🎬 getStreams INICIADO")
        println("[PobreFlix] 📺 tmdbId: $tmdbId")
        println("[PobreFlix] 📺 mediaType: $mediaType")
        println("[PobreFlix] 📺 season: $targetSeason")
        println("[PobreFlix] 📺 episode: $targetEpisode")
        println("[PobreFlix] ═══════════════════════════════════════")

        try {
            val pageUrl = if (mediaType == "filme") {
                "$BASE_URL/filme/$tmdbId"
            } else {
                "$BASE_URL/serie/$tmdbId/$targetSeason/$targetEpisode"
            }
            
            println("[PobreFlix] 🌐 URL da página: $pageUrl")
            
            val response = app.get(pageUrl, headers = HEADERS + getCookieHeader())
            if (!response.isSuccessful) {
                println("[PobreFlix] ❌ Falha na requisição: ${response.code}")
                return emptyList()
            }
            
            val setCookie = response.headers["set-cookie"]
            if (setCookie != null && setCookie.isNotEmpty()) {
                sessionCookies = setCookie
                println("[PobreFlix] 🍪 Cookie atualizado")
            }
            
            val html = response.text
            println("[PobreFlix] 📄 HTML carregado, tamanho: ${html.length} bytes")
            
            // Extrair tokens
            val csrfMatch = Regex("CSRF_TOKEN\\s*=\\s*[\"']([^\"']+)[\"']").find(html)
            if (csrfMatch == null) {
                println("[PobreFlix] ❌ CSRF_TOKEN não encontrado")
                return emptyList()
            }
            val csrfToken = csrfMatch.groupValues[1]
            println("[PobreFlix] 🔑 CSRF_TOKEN obtido: ${csrfToken.take(20)}...")
            
            val pageMatch = Regex("PAGE_TOKEN\\s*=\\s*[\"']([^\"']+)[\"']").find(html)
            if (pageMatch == null) {
                println("[PobreFlix] ❌ PAGE_TOKEN não encontrado")
                return emptyList()
            }
            val pageToken = pageMatch.groupValues[1]
            println("[PobreFlix] 🔑 PAGE_TOKEN obtido: ${pageToken.take(20)}...")
            
            // Extrair contentId
            var contentId: String? = null
            
            if (mediaType == "filme") {
                val initialContentMatch = Regex("INITIAL_CONTENT_ID\\s*=\\s*(\\d+)").find(html)
                if (initialContentMatch != null) {
                    contentId = initialContentMatch.groupValues[1]
                    println("[PobreFlix] 🎯 CONTENT_ID (filme): $contentId")
                }
            } else {
                contentId = extractContentIdFromTruncatedJson(html, targetSeason, targetEpisode)
            }
            
            if (contentId == null) {
                println("[PobreFlix] ❌ Content ID não encontrado para S${targetSeason}E${targetEpisode}")
                return emptyList()
            }
            
            println("[PobreFlix] ✅ Content ID final: $contentId")
            
            // Chamar options
            val optionsParams = mutableMapOf<String, String>()
            optionsParams["contentid"] = contentId
            optionsParams["type"] = if (mediaType == "filme") "filme" else "serie"
            optionsParams["_token"] = csrfToken
            optionsParams["page_token"] = pageToken
            optionsParams["pageToken"] = pageToken
            
            println("[PobreFlix] 📡 Chamando API /player/options...")
            
            val optionsResponse = app.post(
                url = "$BASE_URL/player/options",
                headers = API_HEADERS + getCookieHeader() + mapOf("X-Page-Token" to pageToken),
                data = optionsParams
            )
            
            if (!optionsResponse.isSuccessful) {
                println("[PobreFlix] ❌ Options falhou: ${optionsResponse.code}")
                return emptyList()
            }
            
            val optionsData = JSONObject(optionsResponse.text)
            val dataObj = optionsData.optJSONObject("data")
            if (dataObj == null) {
                println("[PobreFlix] ❌ data object não encontrado")
                return emptyList()
            }
            
            val optionsArray = dataObj.optJSONArray("options")
            if (optionsArray == null) {
                println("[PobreFlix] ❌ options array não encontrado")
                return emptyList()
            }
            
            println("[PobreFlix] 📡 Servidores encontrados: ${optionsArray.length()}")
            
            // Processar cada servidor
            for (i in 0 until optionsArray.length()) {
                val option = optionsArray.getJSONObject(i)
                val videoId = option.optString("ID")
                val type = option.optInt("type")
                val serverType = when (type) {
                    1 -> "Dublado"
                    2 -> "Legendado"
                    else -> "Tipo $type"
                }
                
                println("[PobreFlix] ───────────────────────────────────")
                println("[PobreFlix] 🖥️ Servidor ${i+1}/${optionsArray.length()}")
                println("[PobreFlix] 🖥️ videoId: $videoId")
                println("[PobreFlix] 🖥️ type: $serverType")
                
                if (videoId.isEmpty()) {
                    println("[PobreFlix] ⏭️ videoId vazio, pulando...")
                    continue
                }
                
                val sourceParams = mutableMapOf<String, String>()
                sourceParams["video_id"] = videoId
                sourceParams["page_token"] = pageToken
                sourceParams["_token"] = csrfToken
                
                val sourceResponse = app.post(
                    url = "$BASE_URL/player/source",
                    headers = API_HEADERS + getCookieHeader(),
                    data = sourceParams
                )
                
                if (!sourceResponse.isSuccessful) {
                    println("[PobreFlix] ❌ Source falhou: ${sourceResponse.code}")
                    continue
                }
                
                val sourceData = JSONObject(sourceResponse.text)
                val redirectUrl = sourceData.optJSONObject("data")?.optString("video_url")
                if (redirectUrl.isNullOrEmpty()) {
                    println("[PobreFlix] ❌ redirectUrl vazio")
                    continue
                }
                
                println("[PobreFlix] 🔗 redirectUrl: ${redirectUrl.take(100)}...")
                
                val redirectResponse = app.get(redirectUrl, headers = HEADERS + getCookieHeader())
                val finalUrl = redirectResponse.url
                println("[PobreFlix] 🔗 finalUrl: $finalUrl")
                
                val playerHash = finalUrl.split("/").lastOrNull()
                if (playerHash == null) {
                    println("[PobreFlix] ❌ playerHash null")
                    continue
                }
                
                val videoParams = mutableMapOf<String, String>()
                videoParams["hash"] = playerHash
                videoParams["r"] = ""
                
                val videoResponse = app.post(
                    url = "$CDN_BASE/player/index.php?data=$playerHash&do=getVideo",
                    headers = mapOf(
                        "Accept" to "*/*",
                        "Accept-Language" to "pt-BR",
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                        "Origin" to CDN_BASE,
                        "Referer" to "$CDN_BASE/",
                        "X-Requested-With" to "XMLHttpRequest",
                        "User-Agent" to HEADERS["User-Agent"]!!
                    ),
                    data = videoParams
                )
                
                if (!videoResponse.isSuccessful) {
                    println("[PobreFlix] ❌ Video falhou: ${videoResponse.code}")
                    continue
                }
                
                val videoData = JSONObject(videoResponse.text)
                val videoUrl = videoData.optString("securedLink").takeIf { it.isNotEmpty() } 
                    ?: videoData.optString("videoSource")
                
                if (videoUrl.isNullOrEmpty()) {
                    println("[PobreFlix] ❌ videoUrl vazio")
                    continue
                }
                
                var quality = 720
                when {
                    videoUrl.contains("2160") || videoUrl.contains("4k") -> quality = 2160
                    videoUrl.contains("1440") -> quality = 1440
                    videoUrl.contains("1080") -> quality = 1080
                    videoUrl.contains("720") -> quality = 720
                    videoUrl.contains("480") -> quality = 480
                }
                
                val title = if (mediaType == "filme") {
                    "Filme $tmdbId"
                } else {
                    "S${targetSeason.toString().padStart(2, '0')}E${targetEpisode.toString().padStart(2, '0')}"
                }
                
                println("[PobreFlix] ✅✅✅ SUCESSO! ✅✅✅")
                println("[PobreFlix] 🎬 $serverType ${quality}p")
                println("[PobreFlix] 🎬 URL: ${videoUrl.take(150)}...")
                
                results.add(
                    newExtractorLink(
                        source = "SuperFlix",
                        name = "SuperFlix $serverType ${quality}p",
                        url = videoUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$CDN_BASE/"
                        this.quality = quality
                        this.headers = mapOf(
                            "Referer" to "$CDN_BASE/",
                            "User-Agent" to HEADERS["User-Agent"]!!
                        )
                    }
                )
            }
            
            println("[PobreFlix] ═══════════════════════════════════════")
            println("[PobreFlix] 📊 Total de links encontrados: ${results.size}")
            println("[PobreFlix] ═══════════════════════════════════════")
            
            return results
            
        } catch (e: Exception) {
            println("[PobreFlix] ❌❌❌ ERRO GERAL ❌❌❌")
            println("[PobreFlix] Erro: ${e.message}")
            e.printStackTrace()
            return emptyList()
        }
    }
}
