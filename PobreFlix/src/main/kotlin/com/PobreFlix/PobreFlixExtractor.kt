package com.PobreFlix.extractor

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.json.JSONException

object PobreFlixExtractor {

    private const val BASE_URL = "https://superflixapi.rest"
    private const val CDN_BASE = "https://llanfairpwllgwyngy.com"

    private var csrfToken: String = ""
    private var pageToken: String = ""
    private var sessionCookies: String = ""

    private val HEADERS = mapOf(
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
    "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
    "Referer" to "https://lospobreflix.site/",
    "Sec-Fetch-Dest" to "document",  // Mudar de 'iframe' para 'document'
    "Sec-Fetch-Mode" to "navigate",
    "Sec-Fetch-Site" to "cross-site",
    "Upgrade-Insecure-Requests" to "1",
    "Connection" to "keep-alive",
    "Cache-Control" to "no-cache",   // Adicionar
    "Pragma" to "no-cache"            // Adicionar
)

    private val API_HEADERS = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "X-Requested-With" to "XMLHttpRequest",
        "Origin" to BASE_URL,
        "Connection" to "keep-alive",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache"
    )

    private fun getCookieHeader(): Map<String, String> {
        return if (sessionCookies.isNotEmpty()) mapOf("Cookie" to sessionCookies) else emptyMap()
    }

    private fun extractTokenFromUrl(url: String): String? {
        val match = Regex("token=([a-zA-Z0-9_\\-]+)").find(url)
        return match?.groupValues?.get(1)
    }

    private fun decodeUrl(url: String): String {
        var decoded = url
        decoded = decoded.replace("\\/", "/")
        decoded = decoded.replace("\\\\", "\\")
        decoded = decoded.replace("\\=", "=")
        decoded = decoded.replace("\\&", "&")
        decoded = decoded.replace("\\\"", "\"")
        if (decoded.endsWith("\\")) decoded = decoded.dropLast(1)
        return decoded.trim()
    }

    private fun extractVideoUrlsFromResponse(response: String): List<Pair<String, Int>> {
        val videos = mutableListOf<Pair<String, Int>>()
        
        var data = response.replace(Regex("^\\]\\]\\)\\]\\}'\\n"), "")
        
        val urlPattern = Regex("\"((?:https?:\\\\?/\\\\?/)?[^\"]+?googlevideo[^\"]+?)\",\\[(\\d+)\\]")
        val matches = urlPattern.findAll(data)
        
        for (match in matches) {
            var url = match.groupValues[1]
            val itag = match.groupValues[2].toIntOrNull() ?: 18
            url = decodeUrl(url)
            if (!url.startsWith("http")) url = "https://$url"
            videos.add(Pair(url, itag))
        }
        
        if (videos.isEmpty()) {
            val simplePattern = Regex("https?:\\\\?/\\\\?/[^\"'\\s]+?googlevideo[^\"'\\s]+")
            val simpleMatches = simplePattern.findAll(data)
            for (match in simpleMatches) {
                var url = match.value
                if (!url.startsWith("http")) url = "https://$url"
                url = decodeUrl(url)
                val itag = extractItagFromUrl(url)
                videos.add(Pair(url, itag))
            }
        }
        
        val qualityOrder = listOf(37, 22, 18, 59)
        videos.sortBy { (_, itag) -> qualityOrder.indexOf(itag) }
        
        return videos.distinctBy { "${it.first}_${it.second}" }
    }

    private fun extractItagFromUrl(url: String): Int {
        val patterns = listOf(
            Regex("itag[=?&](\\d+)"),
            Regex("itag%3D(\\d+)"),
            Regex("itag\\\\u003d(\\d+)")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null) {
                val itag = match.groupValues[1].toIntOrNull()
                if (itag != null) return itag
            }
        }
        
        return when {
            url.contains("itag=22") -> 22
            url.contains("itag=18") -> 18
            url.contains("itag=37") -> 37
            url.contains("itag=59") -> 59
            else -> 18
        }
    }

    private val itagQualityMap = mapOf(
        18 to 360,
        22 to 720,
        37 to 1080,
        59 to 480
    )

    private suspend fun extractBloggerVideo(
        bloggerUrl: String, 
        serverType: String, 
        title: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[PobreFlix] extractBloggerVideo: $bloggerUrl")
        val token = extractTokenFromUrl(bloggerUrl) ?: return false

        val apiUrl = "https://www.blogger.com/_/BloggerVideoPlayerUi/data/batchexecute"
        val reqid = (10000..100000).random()
        val fSid = "-7535563745894756252"
        val bl = "boq_bloggeruiserver_20260223.02_p0"
        val urlWithParams = "$apiUrl?rpcids=WcwnYd&source-path=%2Fvideo.g&f.sid=$fSid&bl=$bl&hl=pt-BR&_reqid=$reqid&rt=c"
        
        val bloggerHeaders = mapOf(
            "authority" to "www.blogger.com",
            "accept" to "*/*",
            "content-type" to "application/x-www-form-urlencoded;charset=UTF-8",
            "origin" to "https://www.blogger.com",
            "referer" to "https://www.blogger.com/",
            "user-agent" to HEADERS["user-agent"]!!,
            "sec-ch-ua" to "\"Chromium\";v=\"127\", \"Not)A;Brand\";v=\"99\", \"Microsoft Edge Simulate\";v=\"127\", \"Lemur\";v=\"127\"",
            "sec-ch-ua-mobile" to "?1",
            "sec-ch-ua-platform" to "\"Android\"",
            "x-same-domain" to "1"
        )
        
        val body = "f.req=%5B%5B%5B%22WcwnYd%22%2C%22%5B%5C%22$token%5C%22%2C%5C%22%5C%22%2C0%5D%22%2Cnull%2C%22generic%22%5D%5D%5D"

        try {
            val response = app.post(
                url = urlWithParams,
                headers = bloggerHeaders,
                data = mapOf("f.req" to "%5B%5B%5B%22WcwnYd%22%2C%22%5B%5C%22$token%5C%22%2C%5C%22%5C%22%2C0%5D%22%2Cnull%2C%22generic%22%5D%5D%5D")
            )
            
            val responseText = response.text
            println("[PobreFlix] Blogger response size: ${responseText.length}")
            
            val videoUrls = extractVideoUrlsFromResponse(responseText)
            println("[PobreFlix] Video URLs encontradas: ${videoUrls.size}")
            
            if (videoUrls.isEmpty()) return false
            
            for ((videoUrl, itag) in videoUrls) {
                val videoQuality = itagQualityMap[itag] ?: 480
                println("[PobreFlix] Processando URL: ${videoUrl.take(100)} (${videoQuality}p)")
                
                callback.invoke(
                    newExtractorLink(
                        source = "SuperFlix",
                        name = "SuperFlix $serverType ${videoQuality}p",
                        url = videoUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://youtube.googleapis.com/"
                        this.quality = videoQuality
                        this.headers = mapOf(
                            "Referer" to "https://youtube.googleapis.com/",
                            "User-Agent" to HEADERS["user-agent"]!!,
                            "Accept" to "*/*",
                            "Accept-Language" to "pt-BR",
                            "Range" to "bytes=0-"
                        )
                    }
                )
            }
            return true
        } catch (e: Exception) {
            println("[PobreFlix] Erro no extractBloggerVideo: ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    suspend fun getStreams(
        tmdbId: Int,
        mediaType: String,
        season: Int = 1,
        episode: Int = 1
    ): List<ExtractorLink> {
        val results = mutableListOf<ExtractorLink>()
        val targetSeason = if (mediaType == "movie") 1 else season
        val targetEpisode = if (mediaType == "movie") 1 else episode

        println("[PobreFlix] === getStreams INICIADO ===")
        println("[PobreFlix] tmdbId: $tmdbId, mediaType: $mediaType, season: $season, episode: $episode")

        try {
            val pageUrl = if (mediaType == "movie") {
                "$BASE_URL/filme/$tmdbId"
            } else {
                "$BASE_URL/serie/$tmdbId/$targetSeason/$targetEpisode"
            }
            
            println("[PobreFlix] URL da página: $pageUrl")
            
            // Primeira tentativa com headers desktop
            var pageResponse = app.get(
                url = pageUrl,
                headers = HEADERS + getCookieHeader()
            )
            
            println("[PobreFlix] Status: ${pageResponse.code}")
            
            if (!pageResponse.isSuccessful) {
                println("[PobreFlix] Primeira tentativa falhou")
                return emptyList()
            }
            
            var cookies = pageResponse.headers["set-cookie"]
            if (cookies != null && cookies.isNotEmpty()) {
                sessionCookies = cookies
                println("[PobreFlix] Cookie obtido: ${sessionCookies.take(50)}...")
            }
            
            var html = pageResponse.text
            println("[PobreFlix] HTML carregado, tamanho: ${html.length}")
            
            // Verificar se o HTML está completo (contém episódios com IDs reais)
            val hasRealEpisodes = html.contains("\"ID\":10") // IDs reais começam com 10
            val hasAllEpisodes = html.contains("ALL_EPISODES") && html.contains("\"epi_num\":2")
            
            if (!hasRealEpisodes || !hasAllEpisodes) {
                println("[PobreFlix] HTML incompleto detectado! Tentando novamente com headers diferentes...")
                
                // Tentar com headers de navegador desktop completo
                val desktopHeaders = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
                    "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
                    "Accept-Encoding" to "gzip, deflate, br",
                    "Connection" to "keep-alive",
                    "Upgrade-Insecure-Requests" to "1",
                    "Sec-Fetch-Dest" to "document",
                    "Sec-Fetch-Mode" to "navigate",
                    "Sec-Fetch-Site" to "none",
                    "Sec-Fetch-User" to "?1",
                    "Cache-Control" to "max-age=0",
                    "Referer" to "https://lospobreflix.site/"
                )
                
                val retryResponse = app.get(
                    url = pageUrl,
                    headers = desktopHeaders + getCookieHeader()
                )
                
                if (retryResponse.isSuccessful) {
                    val newCookies = retryResponse.headers["set-cookie"]
                    if (newCookies != null && newCookies.isNotEmpty()) {
                        sessionCookies = newCookies
                    }
                    html = retryResponse.text
                    println("[PobreFlix] HTML alternativo carregado, tamanho: ${html.length}")
                    
                    // Verificar se agora está completo
                    if (html.contains("\"ID\":10")) {
                        println("[PobreFlix] ✅ HTML completo obtido!")
                    } else {
                        println("[PobreFlix] ⚠️ HTML ainda parece incompleto")
                    }
                }
            }
            
            // 2. Extrair tokens
            val csrfMatch = Regex("var CSRF_TOKEN\\s*=\\s*[\"']([^\"']+)[\"']").find(html)
            if (csrfMatch == null) {
                println("[PobreFlix] CSRF_TOKEN não encontrado")
                return emptyList()
            }
            csrfToken = csrfMatch.groupValues[1]
            println("[PobreFlix] CSRF_TOKEN: ${csrfToken.take(30)}...")
            
            val pageMatch = Regex("var PAGE_TOKEN\\s*=\\s*[\"']([^\"']+)[\"']").find(html)
            if (pageMatch == null) {
                println("[PobreFlix] PAGE_TOKEN não encontrado")
                return emptyList()
            }
            pageToken = pageMatch.groupValues[1]
            println("[PobreFlix] PAGE_TOKEN: ${pageToken.take(30)}...")
            
            // 3. Extrair contentId
            var contentId: String? = null
            
            if (mediaType == "movie") {
                val initialContentMatch = Regex("INITIAL_CONTENT_ID\\s*=\\s*(\\d+)").find(html)
                if (initialContentMatch != null) {
                    contentId = initialContentMatch.groupValues[1]
                    println("[PobreFlix] CONTENT_ID (filme): $contentId")
                } else {
                    val dataContentMatch = Regex("data-contentid=[\"'](\\d+)[\"']").find(html)
                    contentId = dataContentMatch?.groupValues?.get(1)
                    println("[PobreFlix] CONTENT_ID (fallback): $contentId")
                }
            } else {
                val epMatch = Regex("var ALL_EPISODES\\s*=\\s*(\\{.*?\\});", RegexOption.DOT_MATCHES_ALL).find(html)
                if (epMatch != null) {
                    try {
                        val jsonString = epMatch.groupValues[1]
                        println("[PobreFlix] JSON de episódios encontrado, tamanho: ${jsonString.length}")
                        
                        val jsonObject = JSONObject(jsonString)
                        val seasonKey = targetSeason.toString()
                        
                        if (jsonObject.has(seasonKey)) {
                            val seasonValue = jsonObject.get(seasonKey)
                            
                            when (seasonValue) {
                                is org.json.JSONArray -> {
                                    println("[PobreFlix] Temporada $seasonKey é um ARRAY com ${seasonValue.length()} episódios")
                                    
                                    for (i in 0 until seasonValue.length()) {
                                        val ep = seasonValue.getJSONObject(i)
                                        val epNum = ep.optInt("epi_num")
                                        println("[PobreFlix] Episódio $i: epi_num=$epNum, ID=${ep.optInt("ID")}")
                                        
                                        if (epNum == targetEpisode) {
                                            contentId = ep.optString("ID")
                                            if (contentId.isNullOrEmpty()) contentId = ep.optString("id")
                                            println("[PobreFlix] ✅ CONTENT_ID encontrado: $contentId")
                                            break
                                        }
                                    }
                                }
                                is JSONObject -> {
                                    println("[PobreFlix] Temporada $seasonKey é um OBJETO")
                                    val keys = seasonValue.keys()
                                    while (keys.hasNext()) {
                                        val key = keys.next()
                                        val ep = seasonValue.getJSONObject(key)
                                        val epNum = ep.optInt("epi_num")
                                        if (epNum == targetEpisode) {
                                            contentId = ep.optString("ID")
                                            println("[PobreFlix] ✅ CONTENT_ID encontrado: $contentId")
                                            break
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        println("[PobreFlix] Erro ao processar JSON: ${e.message}")
                    }
                }
            }
            
            if (contentId == null || contentId == "0") {
                println("[PobreFlix] contentId inválido (null ou 0)")
                return emptyList()
            }
            
            // 4. Options
            val optionsParams = mutableMapOf<String, String>()
            optionsParams["contentid"] = contentId
            optionsParams["type"] = if (mediaType == "movie") "filme" else "serie"
            optionsParams["_token"] = csrfToken
            optionsParams["page_token"] = pageToken
            optionsParams["pageToken"] = pageToken
            
            println("[PobreFlix] Chamando options...")
            println("[PobreFlix] contentId: $contentId, type: ${if (mediaType == "movie") "filme" else "serie"}")
            
            val optionsResponse = app.post(
                url = "$BASE_URL/player/options",
                headers = API_HEADERS + getCookieHeader() + mapOf(
                    "X-Page-Token" to pageToken,
                    "Referer" to pageUrl
                ),
                data = optionsParams
            )
            
            println("[PobreFlix] Options status: ${optionsResponse.code}")
            
            if (!optionsResponse.isSuccessful) {
                println("[PobreFlix] Options falhou: ${optionsResponse.text.take(200)}")
                return emptyList()
            }
            
            val optionsText = optionsResponse.text
            println("[PobreFlix] Options resposta: ${optionsText.take(200)}")
            
            val optionsData = JSONObject(optionsText)
            val dataObj = optionsData.optJSONObject("data")
            if (dataObj == null) {
                println("[PobreFlix] data object não encontrado no options")
                return emptyList()
            }
            
            val optionsArray = dataObj.optJSONArray("options")
            if (optionsArray == null) {
                println("[PobreFlix] options array não encontrado")
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
                
                println("[PobreFlix] Processando server $i: videoId=$videoId, type=$serverType")
                
                if (videoId.isEmpty()) continue
                
                val sourceParams = mutableMapOf<String, String>()
                sourceParams["video_id"] = videoId
                sourceParams["page_token"] = pageToken
                sourceParams["_token"] = csrfToken
                
                println("[PobreFlix] Chamando source...")
                
                val sourceResponse = app.post(
                    url = "$BASE_URL/player/source",
                    headers = API_HEADERS + getCookieHeader() + mapOf("Referer" to pageUrl),
                    data = sourceParams
                )
                
                println("[PobreFlix] Source status: ${sourceResponse.code}")
                
                if (!sourceResponse.isSuccessful) {
                    println("[PobreFlix] Source falhou: ${sourceResponse.text.take(200)}")
                    continue
                }
                
                val sourceText = sourceResponse.text
                println("[PobreFlix] Source resposta: ${sourceText.take(200)}")
                
                val sourceData = JSONObject(sourceText)
                val redirectUrl = sourceData.optJSONObject("data")?.optString("video_url")
                if (redirectUrl.isNullOrEmpty()) {
                    println("[PobreFlix] redirectUrl vazio")
                    continue
                }
                
                println("[PobreFlix] redirectUrl: ${redirectUrl.take(100)}...")
                
                val redirectResponse = app.get(
                    url = redirectUrl,
                    headers = HEADERS + getCookieHeader()
                )
                
                println("[PobreFlix] Redirect status: ${redirectResponse.code}")
                
                val finalUrl = redirectResponse.url
                println("[PobreFlix] finalUrl: $finalUrl")
                
                if (finalUrl.contains("blogger.com/video.g") || finalUrl.contains("blogger.com")) {
                    println("[PobreFlix] É URL do Blogger, chamando extractBloggerVideo")
                    
                    val title = if (mediaType == "movie") {
                        "Filme $tmdbId"
                    } else {
                        "S${targetSeason.toString().padStart(2, '0')}E${targetEpisode.toString().padStart(2, '0')}"
                    }
                    
                    extractBloggerVideo(finalUrl, serverType, title) { stream ->
                        results.add(stream)
                    }
                    continue
                }
                
                val playerHash = finalUrl.split("/").lastOrNull()
                if (playerHash == null) {
                    println("[PobreFlix] playerHash null")
                    continue
                }
                
                println("[PobreFlix] playerHash: $playerHash")
                
                val videoParams = mutableMapOf<String, String>()
                videoParams["hash"] = playerHash
                videoParams["r"] = ""
                
                println("[PobreFlix] Chamando video...")
                
                val videoResponse = app.post(
                    url = "$CDN_BASE/player/index.php?data=$playerHash&do=getVideo",
                    headers = mapOf(
                        "Accept" to "*/*",
                        "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                        "Origin" to CDN_BASE,
                        "Referer" to "$CDN_BASE/",
                        "X-Requested-With" to "XMLHttpRequest",
                        "User-Agent" to HEADERS["user-agent"]!!
                    ),
                    data = videoParams
                )
                
                println("[PobreFlix] Video status: ${videoResponse.code}")
                
                if (!videoResponse.isSuccessful) {
                    println("[PobreFlix] Video falhou")
                    continue
                }
                
                val videoText = videoResponse.text
                println("[PobreFlix] Video resposta: ${videoText.take(200)}")
                
                val videoData = JSONObject(videoText)
                val videoUrl = videoData.optString("securedLink").takeIf { it.isNotEmpty() } 
                    ?: videoData.optString("videoSource")
                
                if (videoUrl.isNullOrEmpty()) {
                    println("[PobreFlix] videoUrl vazio")
                    continue
                }
                
                println("[PobreFlix] videoUrl encontrado: ${videoUrl.take(100)}...")
                
                var quality = 720
                when {
                    videoUrl.contains("2160") || videoUrl.contains("4k") -> quality = 2160
                    videoUrl.contains("1440") -> quality = 1440
                    videoUrl.contains("1080") -> quality = 1080
                    videoUrl.contains("720") -> quality = 720
                    videoUrl.contains("480") -> quality = 480
                }
                
                val title = if (mediaType == "movie") {
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
                            "User-Agent" to HEADERS["user-agent"]!!
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
