package com.PobreFlix.extractor

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.util.Base64

object PobreFlixExtractor {

    private const val BASE_URL = "https://superflixapi.rest"
    private const val CDN_BASE = "https://llanfairpwllgwyngy.com"
    private const val POBREFLIX_URL = "https://lospobreflix.site"

    private var csrfToken: String = ""
    private var pageToken: String = ""
    private var sessionCookies: String = ""

    private val HEADERS = mapOf(
    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
    "Accept-Language" to "pt-BR",
    "Accept-Encoding" to "identity",  // ← MUDAR de "gzip, deflate" para "identity"
    "Referer" to "$POBREFLIX_URL/",
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

    /**
     * Extrai o Content ID do episódio a partir do HTML da página da série
     */
    private fun extractEpisodeContentId(html: String, season: Int, episode: Int): String? {
        println("[PobreFlix] Procurando Content ID para S${season}E${episode}")
        
        // Verificar se o HTML contém links de episódio
        val hasEpisodeLinks = html.contains("/episodio/")
        println("[PobreFlix] HTML contém links de episódio: $hasEpisodeLinks")
        
        // Verificar se contém allEpisodes JSON
        val hasAllEpisodes = html.contains("allEpisodes")
        println("[PobreFlix] HTML contém allEpisodes: $hasAllEpisodes")
        
        // Procurar por episódios na lista
        val episodeListPattern = Regex("data-episode-id=[\"'](\\d+)[\"'].*?data-episode=[\"']$episode[\"']")
        val episodeListMatch = episodeListPattern.find(html)
        if (episodeListMatch != null) {
            val contentId = episodeListMatch.groupValues[1]
            if (contentId != "0") {
                println("[PobreFlix] ✅ Content ID encontrado via lista de episódios: $contentId")
                return contentId
            }
        }
        
        // Procura pelo link do episódio com o data-episode-id
        val pattern = Regex("href=[\"']/episodio/[^\"']*-${season}x$episode[\"'][^>]*data-episode-id=[\"'](\\d+)[\"']")
        val match = pattern.find(html)
        if (match != null) {
            val contentId = match.groupValues[1]
            if (contentId != "0") {
                println("[PobreFlix] ✅ Content ID encontrado via link: $contentId")
                return contentId
            }
        }
        
        // Fallback: procura no allEpisodes JSON
        val allEpisodesMatch = Regex("window\\.allEpisodes\\s*=\\s*(\\{[^;]+\\});", RegexOption.DOT_MATCHES_ALL).find(html)
        if (allEpisodesMatch != null) {
            println("[PobreFlix] allEpisodes JSON encontrado")
            try {
                val jsonString = allEpisodesMatch.groupValues[1]
                println("[PobreFlix] JSON tamanho: ${jsonString.length}")
                println("[PobreFlix] JSON (primeiros 500 chars): ${jsonString.take(500)}")
                
                val jsonObject = JSONObject(jsonString)
                val seasonKey = season.toString()
                println("[PobreFlix] Keys disponíveis: ${jsonObject.keys().asSequence().toList()}")
                
                if (jsonObject.has(seasonKey)) {
                    val seasonArray = jsonObject.getJSONArray(seasonKey)
                    println("[PobreFlix] Temporada $seasonKey tem ${seasonArray.length()} episódios")
                    
                    for (i in 0 until seasonArray.length()) {
                        val ep = seasonArray.getJSONObject(i)
                        val epNum = ep.optInt("epi_num")
                        val epId = ep.optInt("id")
                        val epTitle = ep.optString("title")
                        println("[PobreFlix] Episódio $i: epi_num=$epNum, id=$epId, title=$epTitle")
                        
                        if (epNum == episode) {
                            val contentId = epId.toString()
                            if (contentId != "0") {
                                println("[PobreFlix] ✅ Content ID via allEpisodes: $contentId")
                                return contentId
                            }
                        }
                    }
                } else {
                    println("[PobreFlix] Temporada $seasonKey NÃO encontrada no JSON")
                }
            } catch (e: Exception) {
                println("[PobreFlix] Erro ao parsear allEpisodes: ${e.message}")
                e.printStackTrace()
            }
        } else {
            println("[PobreFlix] allEpisodes JSON NÃO encontrado no HTML")
        }
        
        // Se não encontrou, mostrar parte do HTML para debug
        println("[PobreFlix] HTML (primeiros 2000 chars) para debug:")
        println(html.take(2000))
        
        println("[PobreFlix] ❌ Content ID não encontrado para S${season}E$episode")
        return null
    }

    /**
     * Extrai tokens CSRF_TOKEN e PAGE_TOKEN do HTML
     */
    private fun extractTokens(html: String): Boolean {
        val csrfMatch = Regex("CSRF_TOKEN\\s*=\\s*[\"']([^\"']+)[\"']").find(html)
        if (csrfMatch == null) {
            println("[PobreFlix] CSRF_TOKEN não encontrado")
            return false
        }
        csrfToken = csrfMatch.groupValues[1]
        println("[PobreFlix] CSRF_TOKEN obtido: ${csrfToken.take(30)}...")
        
        val pageMatch = Regex("PAGE_TOKEN\\s*=\\s*[\"']([^\"']+)[\"']").find(html)
        if (pageMatch == null) {
            println("[PobreFlix] PAGE_TOKEN não encontrado")
            return false
        }
        pageToken = pageMatch.groupValues[1]
        println("[PobreFlix] PAGE_TOKEN obtido: ${pageToken.take(30)}...")
        
        return true
    }

    /**
     * Método principal para obter streams
     * 
     * @param seriesUrl URL da série que o app já carregou (ex: https://lospobreflix.site/anime/rooster-fighter)
     * @param season Temporada
     * @param episode Episódio
     */
    suspend fun getStreams(
        seriesUrl: String,
        season: Int,
        episode: Int
    ): List<ExtractorLink> {
        val results = mutableListOf<ExtractorLink>()
        
        println("[PobreFlix] === getStreams PARA SÉRIES INICIADO ===")
        println("[PobreFlix] seriesUrl: $seriesUrl, season: $season, episode: $episode")

        try {
            // 1. Fazer requisição para a página da série (que o app já tem)
            val response = app.get(seriesUrl, headers = HEADERS + getCookieHeader())
            if (!response.isSuccessful) {
                println("[PobreFlix] Falha ao acessar página: ${response.code}")
                return emptyList()
            }
            
            // Atualizar cookies
            val cookies = response.headers["set-cookie"]
            if (cookies != null && cookies.isNotEmpty()) {
                sessionCookies = cookies
                println("[PobreFlix] Cookie atualizado")
            }
            
            val html = response.text
            println("[PobreFlix] HTML carregado, tamanho: ${html.length}")
            
            // 2. Extrair Content ID do episódio
            val contentId = extractEpisodeContentId(html, season, episode)
            if (contentId == null || contentId == "0") {
                println("[PobreFlix] ❌ Content ID não encontrado")
                return emptyList()
            }
            println("[PobreFlix] Content ID: $contentId")
            
            // 3. Extrair tokens
            if (!extractTokens(html)) {
                println("[PobreFlix] ❌ Falha ao extrair tokens")
                return emptyList()
            }
            
            // 4. Chamar options API
            val optionsParams = mutableMapOf<String, String>()
            optionsParams["contentid"] = contentId
            optionsParams["type"] = "serie"
            optionsParams["_token"] = csrfToken
            optionsParams["page_token"] = pageToken
            optionsParams["pageToken"] = pageToken
            
            println("[PobreFlix] Chamando options para contentId: $contentId")
            
            val optionsResponse = app.post(
                url = "$BASE_URL/player/options",
                headers = API_HEADERS + getCookieHeader() + mapOf(
                    "X-Page-Token" to pageToken,
                    "Referer" to seriesUrl
                ),
                data = optionsParams
            )
            
            if (!optionsResponse.isSuccessful) {
                println("[PobreFlix] Options falhou: ${optionsResponse.code}")
                return emptyList()
            }
            
            val optionsText = optionsResponse.text
            println("[PobreFlix] Options resposta: ${optionsText.take(200)}")
            
            val optionsData = JSONObject(optionsText)
            val dataObj = optionsData.optJSONObject("data")
            if (dataObj == null) {
                println("[PobreFlix] data object não encontrado")
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
                
                val sourceResponse = app.post(
                    url = "$BASE_URL/player/source",
                    headers = API_HEADERS + getCookieHeader(),
                    data = sourceParams
                )
                
                if (!sourceResponse.isSuccessful) {
                    println("[PobreFlix] Source falhou: ${sourceResponse.code}")
                    continue
                }
                
                val sourceText = sourceResponse.text
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
                
                val finalUrl = redirectResponse.url
                println("[PobreFlix] finalUrl: $finalUrl")
                
                // Blogger handler
                if (finalUrl.contains("blogger.com/video.g") || finalUrl.contains("blogger.com")) {
                    val title = "S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}"
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
                        "User-Agent" to HEADERS["user-agent"]!!
                    ),
                    data = videoParams
                )
                
                if (!videoResponse.isSuccessful) {
                    println("[PobreFlix] Video falhou: ${videoResponse.code}")
                    continue
                }
                
                val videoText = videoResponse.text
                val videoData = JSONObject(videoText)
                val videoUrl = videoData.optString("securedLink").takeIf { it.isNotEmpty() } 
                    ?: videoData.optString("videoSource")
                
                if (videoUrl.isNullOrEmpty()) {
                    println("[PobreFlix] videoUrl vazio")
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
                
                val title = "S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}"
                
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
