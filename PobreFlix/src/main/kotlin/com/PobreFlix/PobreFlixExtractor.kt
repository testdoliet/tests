package com.PobreFlix.extractor

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URLEncoder

object PobreFlixExtractor {

    private const val BASE_URL = "https://superflixapi.rest"
    private const val CDN_BASE = "https://llanfairpwllgwyngy.com"

    private var csrfToken: String = ""
    private var pageToken: String = ""

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
        "Referer" to "https://lospobreflix.site/"
    )

    private fun log(message: String) {
        println("[PobreFlixExtractor] $message")
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

    private fun extractVideoUrlsFromResponse(response: String): List<String> {
        val videos = mutableListOf<String>()
        var data = response.replace(Regex("^\\]\\)\\]\\}'\\n"), "")
        val urlPattern = Regex("\"((?:https?:\\\\?/\\\\?/)?[^\"]+?googlevideo[^\"]+?)\"")
        val matches = urlPattern.findAll(data)
        
        for (match in matches) {
            var url = match.groupValues[1]
            url = decodeUrl(url)
            if (!url.startsWith("http")) url = "https://$url"
            videos.add(url)
        }
        return videos.distinct()
    }

    private suspend fun extractBloggerVideo(bloggerUrl: String, serverType: String, callback: (ExtractorLink) -> Unit): Boolean {
        log("extractBloggerVideo: $bloggerUrl")
        val token = extractTokenFromUrl(bloggerUrl) ?: return false

        val apiUrl = "https://www.blogger.com/_/BloggerVideoPlayerUi/data/batchexecute"
        val reqid = (10000..100000).random()
        val fSid = "-7535563745894756252"
        val bl = "boq_bloggeruiserver_20260223.02_p0"
        val urlWithParams = "$apiUrl?rpcids=WcwnYd&source-path=%2Fvideo.g&f.sid=$fSid&bl=$bl&hl=pt-BR&_reqid=$reqid&rt=c"
        
        val body = mapOf(
            "f.req" to "%5B%5B%5B%22WcwnYd%22%2C%22%5B%5C%22$token%5C%22%2C%5C%22%5C%22%2C0%5D%22%2Cnull%2C%22generic%22%5D%5D%5D"
        )

        try {
            val response = app.post(
                url = urlWithParams,
                data = body
            )
            val responseText = response.text
            log("Blogger response size: ${responseText.length}")
            
            val videoUrls = extractVideoUrlsFromResponse(responseText)
            log("Video URLs encontradas: ${videoUrls.size}")
            
            if (videoUrls.isEmpty()) return false
            
            for (videoUrl in videoUrls) {
                log("Processando URL: ${videoUrl.take(100)}")
                val links = M3u8Helper.generateM3u8(
                    source = "SuperFlix",
                    streamUrl = videoUrl,
                    referer = "https://youtube.googleapis.com/"
                )
                
                if (links.isNotEmpty()) {
                    log("Links M3U8 gerados: ${links.size}")
                    links.forEach { callback(it) }
                } else {
                    log("Criando link direto")
                    callback.invoke(
                        newExtractorLink(
                            source = "SuperFlix",
                            name = "SuperFlix $serverType HD",
                            url = videoUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "https://youtube.googleapis.com/"
                            this.quality = 720
                        }
                    )
                }
            }
            return true
        } catch (e: Exception) {
            log("Erro no extractBloggerVideo: ${e.message}")
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

        log("=== getStreams INICIADO ===")
        log("tmdbId: $tmdbId, mediaType: $mediaType, season: $season, episode: $episode")

        try {
            // 1. Página inicial
            val pageUrl = if (mediaType == "movie") {
                "$BASE_URL/filme/$tmdbId"
            } else {
                "$BASE_URL/serie/$tmdbId/$targetSeason/$targetEpisode"
            }
            
            log("URL da página: $pageUrl")
            
            val pageResponse = app.get(
                url = pageUrl,
                headers = defaultHeaders
            )
            if (!pageResponse.isSuccessful) {
                log("Página não carregou: ${pageResponse.statusCode}")
                return emptyList()
            }
            val html = pageResponse.text
            log("HTML carregado, tamanho: ${html.length}")
            
            // 2. Extrair tokens
            val csrfMatch = Regex("var CSRF_TOKEN\\s*=\\s*[\"']([^\"']+)[\"']").find(html)
            if (csrfMatch == null) {
                log("CSRF_TOKEN não encontrado")
                return emptyList()
            }
            csrfToken = csrfMatch.groupValues[1]
            log("CSRF_TOKEN: $csrfToken")
            
            val pageMatch = Regex("var PAGE_TOKEN\\s*=\\s*[\"']([^\"']+)[\"']").find(html)
            if (pageMatch == null) {
                log("PAGE_TOKEN não encontrado")
                return emptyList()
            }
            pageToken = pageMatch.groupValues[1]
            log("PAGE_TOKEN: $pageToken")
            
            // 3. Extrair contentId
            var contentId: String? = null
            
            if (mediaType == "movie") {
                val initialContentMatch = Regex("INITIAL_CONTENT_ID\\s*=\\s*(\\d+)").find(html)
                if (initialContentMatch != null) {
                    contentId = initialContentMatch.groupValues[1]
                } else {
                    val dataContentMatch = Regex("data-contentid=[\"'](\\d+)[\"']").find(html)
                    contentId = dataContentMatch?.groupValues?.get(1)
                }
            } else {
                val epMatch = Regex("var ALL_EPISODES\\s*=\\s*(\\{.*?\\});", RegexOption.DOT_MATCHES_ALL).find(html)
                if (epMatch != null) {
                    try {
                        val jsonString = epMatch.groupValues[1]
                        val jsonObject = JSONObject(jsonString)
                        val seasonData = jsonObject.optJSONArray(targetSeason.toString())
                        
                        if (seasonData != null) {
                            for (i in 0 until seasonData.length()) {
                                val ep = seasonData.getJSONObject(i)
                                val epNum = ep.optInt("epi_num")
                                if (epNum == targetEpisode) {
                                    contentId = ep.optString("ID")
                                    if (contentId.isNullOrEmpty()) contentId = ep.optString("id")
                                    break
                                }
                            }
                        }
                    } catch (e: Exception) {
                        log("Erro ao parsear JSON: ${e.message}")
                    }
                }
            }
            
            if (contentId == null) {
                log("contentId não encontrado")
                return emptyList()
            }
            log("contentId: $contentId")
            
            // 4. Options
            val optionsParams = mutableMapOf<String, String>()
            optionsParams["contentid"] = URLEncoder.encode(contentId, "UTF-8")
            optionsParams["type"] = URLEncoder.encode(if (mediaType == "movie") "filme" else "serie", "UTF-8")
            optionsParams["_token"] = URLEncoder.encode(csrfToken, "UTF-8")
            optionsParams["page_token"] = URLEncoder.encode(pageToken, "UTF-8")
            optionsParams["pageToken"] = URLEncoder.encode(pageToken, "UTF-8")
            
            val apiHeaders = mapOf(
                "Content-Type" to "application/x-www-form-urlencoded",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to pageUrl,
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            )
            
            log("Chamando options...")
            val optionsResponse = app.post(
                url = "$BASE_URL/player/options",
                headers = apiHeaders,
                data = optionsParams
            )
            
            if (!optionsResponse.isSuccessful) {
                log("Options falhou: ${optionsResponse.statusCode}")
                log("Resposta: ${optionsResponse.text}")
                return emptyList()
            }
            
            val optionsText = optionsResponse.text
            log("Options resposta: ${optionsText.take(200)}")
            
            val optionsData = JSONObject(optionsText)
            val dataObj = optionsData.optJSONObject("data")
            if (dataObj == null) {
                log("data object não encontrado no options")
                return emptyList()
            }
            
            val optionsArray = dataObj.optJSONArray("options")
            if (optionsArray == null) {
                log("options array não encontrado")
                return emptyList()
            }
            
            log("Options encontradas: ${optionsArray.length()}")
            
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
                
                log("Processando server $i: videoId=$videoId, type=$serverType")
                
                if (videoId.isEmpty()) continue
                
                // Source
                val sourceParams = mutableMapOf<String, String>()
                sourceParams["video_id"] = URLEncoder.encode(videoId, "UTF-8")
                sourceParams["page_token"] = URLEncoder.encode(pageToken, "UTF-8")
                sourceParams["_token"] = URLEncoder.encode(csrfToken, "UTF-8")
                
                log("Chamando source...")
                val sourceResponse = app.post(
                    url = "$BASE_URL/player/source",
                    headers = apiHeaders,
                    data = sourceParams
                )
                
                if (!sourceResponse.isSuccessful) {
                    log("Source falhou: ${sourceResponse.statusCode}")
                    continue
                }
                
                val sourceText = sourceResponse.text
                log("Source resposta: ${sourceText.take(200)}")
                
                val sourceData = JSONObject(sourceText)
                val redirectUrl = sourceData.optJSONObject("data")?.optString("video_url")
                if (redirectUrl.isNullOrEmpty()) {
                    log("redirectUrl vazio")
                    continue
                }
                
                log("redirectUrl: $redirectUrl")
                
                // Seguir redirect
                val redirectResponse = app.get(
                    url = redirectUrl,
                    headers = defaultHeaders
                )
                if (!redirectResponse.isSuccessful) {
                    log("Redirect falhou: ${redirectResponse.statusCode}")
                    continue
                }
                
                val finalUrl = try {
                    redirectResponse.url
                } catch (e: Exception) {
                    redirectUrl
                }
                
                log("finalUrl: $finalUrl")
                
                // Verificar se é URL do Blogger
                if (finalUrl.contains("blogger.com/video.g") || finalUrl.contains("blogger.com")) {
                    log("É URL do Blogger, chamando extractBloggerVideo")
                    extractBloggerVideo(finalUrl, serverType) { stream ->
                        results.add(stream)
                    }
                    continue
                }
                
                // Processamento normal (HLS)
                val playerHash = finalUrl.split("/").lastOrNull() ?: continue
                log("playerHash: $playerHash")
                
                val videoParams = mutableMapOf<String, String>()
                videoParams["hash"] = URLEncoder.encode(playerHash, "UTF-8")
                videoParams["r"] = ""
                
                val videoHeaders = mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to "$CDN_BASE/",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                )
                
                log("Chamando video...")
                val videoResponse = app.post(
                    url = "$CDN_BASE/player/index.php?data=$playerHash&do=getVideo",
                    headers = videoHeaders,
                    data = videoParams
                )
                
                if (!videoResponse.isSuccessful) {
                    log("Video falhou: ${videoResponse.statusCode}")
                    continue
                }
                
                val videoText = videoResponse.text
                log("Video resposta: ${videoText.take(200)}")
                
                val videoData = JSONObject(videoText)
                val videoUrl = videoData.optString("securedLink").takeIf { it.isNotEmpty() } 
                    ?: videoData.optString("videoSource")
                
                if (videoUrl.isNullOrEmpty()) {
                    log("videoUrl vazio")
                    continue
                }
                
                log("videoUrl encontrado: ${videoUrl.take(100)}")
                
                val links = M3u8Helper.generateM3u8(
                    source = "SuperFlix",
                    streamUrl = videoUrl,
                    referer = "$CDN_BASE/"
                )
                
                if (links.isNotEmpty()) {
                    log("Links M3U8 gerados: ${links.size}")
                    results.addAll(links)
                } else {
                    log("Criando link direto")
                    results.add(
                        newExtractorLink(
                            source = "SuperFlix",
                            name = "SuperFlix $serverType HD",
                            url = videoUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$CDN_BASE/"
                            this.quality = 720
                        }
                    )
                }
            }
            
            log("Total de links encontrados: ${results.size}")
            return results
            
        } catch (e: Exception) {
            log("Erro geral: ${e.message}")
            e.printStackTrace()
            return emptyList()
        }
    }
}
