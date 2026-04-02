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
        "Accept-Encoding" to "gzip, deflate",
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

    private fun updateCookies(response: com.lagradost.cloudstream3.app.Response) {
        val setCookie = response.headers["set-cookie"]
        if (setCookie != null && setCookie.isNotEmpty()) {
            sessionCookies = setCookie
            println("[PobreFlix] Cookie atualizado")
        }
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
        
        println("[PobreFlix] === getStreams INICIADO ===")
        println("[PobreFlix] tmdbId: $tmdbId, mediaType: $mediaType, season: $targetSeason, episode: $targetEpisode")

        try {
            // 1. Fazer requisição para a página do player
            val pageUrl = if (mediaType == "filme") {
                "$BASE_URL/filme/$tmdbId"
            } else {
                "$BASE_URL/serie/$tmdbId/$targetSeason/$targetEpisode"
            }
            
            println("[PobreFlix] URL da página: $pageUrl")
            
            var response = app.get(pageUrl, headers = HEADERS + getCookieHeader())
            if (!response.isSuccessful) {
                println("[PobreFlix] Falha na requisição: ${response.code}")
                return emptyList()
            }
            
            updateCookies(response)
            
            var html = response.text
            println("[PobreFlix] HTML carregado, tamanho: ${html.length}")
            
            // Tentar com Accept-Encoding diferente se necessário
            if (!html.contains("CSRF_TOKEN") && !html.contains("<!DOCTYPE")) {
                println("[PobreFlix] HTML comprimido, tentando sem compressão...")
                val altResponse = app.get(pageUrl, headers = HEADERS + getCookieHeader() + mapOf("Accept-Encoding" to "identity"))
                if (altResponse.isSuccessful) {
                    updateCookies(altResponse)
                    html = altResponse.text
                    println("[PobreFlix] HTML alternativo, tamanho: ${html.length}")
                }
            }
            
            // 2. Extrair tokens
            val csrfMatch = Regex("CSRF_TOKEN\\s*=\\s*[\"']([^\"']+)[\"']").find(html)
            if (csrfMatch == null) {
                println("[PobreFlix] CSRF_TOKEN não encontrado")
                return emptyList()
            }
            val csrfToken = csrfMatch.groupValues[1]
            println("[PobreFlix] CSRF_TOKEN obtido")
            
            val pageMatch = Regex("PAGE_TOKEN\\s*=\\s*[\"']([^\"']+)[\"']").find(html)
            if (pageMatch == null) {
                println("[PobreFlix] PAGE_TOKEN não encontrado")
                return emptyList()
            }
            val pageToken = pageMatch.groupValues[1]
            println("[PobreFlix] PAGE_TOKEN obtido")
            
            // 3. Extrair contentId
            var contentId: String? = null
            
            if (mediaType == "filme") {
                val initialContentMatch = Regex("INITIAL_CONTENT_ID\\s*=\\s*(\\d+)").find(html)
                if (initialContentMatch != null) {
                    contentId = initialContentMatch.groupValues[1]
                    println("[PobreFlix] CONTENT_ID (filme): $contentId")
                }
            } else {
                val allEpisodesMatch = Regex("ALL_EPISODES\\s*=\\s*(\\{[^;]+\\})", RegexOption.DOT_MATCHES_ALL).find(html)
                if (allEpisodesMatch != null) {
                    try {
                        val jsonString = allEpisodesMatch.groupValues[1]
                        val jsonObject = JSONObject(jsonString)
                        val seasonKey = targetSeason.toString()
                        
                        if (jsonObject.has(seasonKey)) {
                            val seasonArray = jsonObject.getJSONArray(seasonKey)
                            for (i in 0 until seasonArray.length()) {
                                val ep = seasonArray.getJSONObject(i)
                                if (ep.optInt("epi_num") == targetEpisode) {
                                    contentId = ep.optInt("ID").toString()
                                    println("[PobreFlix] CONTENT_ID (série): $contentId")
                                    break
                                }
                            }
                        }
                    } catch (e: Exception) {
                        println("[PobreFlix] Erro ao parsear episódios: ${e.message}")
                    }
                }
            }
            
            if (contentId == null) {
                println("[PobreFlix] Content ID não encontrado")
                return emptyList()
            }
            
            // 4. Chamar options
            val optionsParams = mutableMapOf<String, String>()
            optionsParams["contentid"] = contentId
            optionsParams["type"] = if (mediaType == "filme") "filme" else "serie"
            optionsParams["_token"] = csrfToken
            optionsParams["page_token"] = pageToken
            optionsParams["pageToken"] = pageToken
            
            println("[PobreFlix] Chamando options para contentId: $contentId")
            
            val optionsResponse = app.post(
                url = "$BASE_URL/player/options",
                headers = API_HEADERS + getCookieHeader() + mapOf("X-Page-Token" to pageToken),
                data = optionsParams
            )
            
            if (!optionsResponse.isSuccessful) {
                println("[PobreFlix] Options falhou: ${optionsResponse.code}")
                return emptyList()
            }
            
            val optionsData = JSONObject(optionsResponse.text)
            val optionsArray = optionsData.optJSONObject("data")?.optJSONArray("options")
            if (optionsArray == null) {
                println("[PobreFlix] Options array não encontrado")
                return emptyList()
            }
            
            println("[PobreFlix] Options encontradas: ${optionsArray.length()}")
            
            // 5. Processar cada servidor
            for (i in 0 until optionsArray.length()) {
                val option = optionsArray.getJSONObject(i)
                val videoId = option.optString("ID")
                val type = option.optInt("type")
                val serverType = when (type) {
                    1 -> "Dublado"
                    2 -> "Legendado"
                    else -> "Tipo $type"
                }
                
                println("[PobreFlix] Processando server $i: videoId=$videoId")
                
                if (videoId.isEmpty()) continue
                
                val sourceParams = mutableMapOf<String, String>()
                sourceParams["video_id"] = videoId
                sourceParams["page_token"] = pageToken
                sourceParams["_token"] = csrfToken
                
                val sourceResponse = app.post(
                    url = "$BASE_URL/player/source",
                    headers = API_HEADERS + getCookieHeader(),
                    data = sourceParams
                )
                
                if (!sourceResponse.isSuccessful) continue
                
                val sourceData = JSONObject(sourceResponse.text)
                val redirectUrl = sourceData.optJSONObject("data")?.optString("video_url")
                if (redirectUrl.isNullOrEmpty()) continue
                
                println("[PobreFlix] redirectUrl: ${redirectUrl.take(100)}...")
                
                val redirectResponse = app.get(redirectUrl, headers = HEADERS + getCookieHeader())
                val finalUrl = redirectResponse.url
                println("[PobreFlix] finalUrl: $finalUrl")
                
                val playerHash = finalUrl.split("/").lastOrNull() ?: continue
                
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
                
                if (!videoResponse.isSuccessful) continue
                
                val videoData = JSONObject(videoResponse.text)
                val videoUrl = videoData.optString("securedLink").takeIf { it.isNotEmpty() } 
                    ?: videoData.optString("videoSource")
                
                if (videoUrl.isNullOrEmpty()) continue
                
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
                
                println("[PobreFlix] ✅ SUCESSO! $serverType ${quality}p")
                
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
            
            println("[PobreFlix] Total de links encontrados: ${results.size}")
            return results
            
        } catch (e: Exception) {
            println("[PobreFlix] Erro geral: ${e.message}")
            e.printStackTrace()
            return emptyList()
        }
    }
}
