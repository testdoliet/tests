package com.PobreFlix.extractor

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URLEncoder

object PobreFlixExtractor {
    
    private const val BASE_API = "https://superflixapi.rest"
    private const val CDN_BASE = "https://llanfairpwllgwyngy.com"
    private const val REFERER = "https://lospobreflix.site/"
    private const val PRIMARY_SOURCE = "SuperFlix"
    
    private var sessionCookies: String = ""
    private var csrfToken: String = ""
    private var pageToken: String = ""
    
    private fun updateCookies(response: com.lagradost.cloudstream3.app.Response) {
        val setCookie = response.headers["set-cookie"]
        if (setCookie != null) {
            sessionCookies = setCookie
        }
    }
    
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
    
    private suspend fun extractBloggerVideo(
        bloggerUrl: String, 
        serverType: String, 
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val token = extractTokenFromUrl(bloggerUrl) ?: return false
        
        val apiUrl = "https://www.blogger.com/_/BloggerVideoPlayerUi/data/batchexecute"
        val reqid = (10000..100000).random()
        val fSid = "-7535563745894756252"
        val bl = "boq_bloggeruiserver_20260223.02_p0"
        
        val headers = mapOf(
            "authority" to "www.blogger.com",
            "accept" to "*/*",
            "content-type" to "application/x-www-form-urlencoded;charset=UTF-8",
            "origin" to "https://www.blogger.com",
            "referer" to "https://www.blogger.com/",
            "user-agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
            "x-same-domain" to "1"
        )
        
        val urlWithParams = "$apiUrl?rpcids=WcwnYd&source-path=%2Fvideo.g&f.sid=$fSid&bl=$bl&hl=pt-BR&_reqid=$reqid&rt=c"
        val body = "f.req=%5B%5B%5B%22WcwnYd%22%2C%22%5B%5C%22$token%5C%22%2C%5C%22%5C%22%2C0%5D%22%2Cnull%2C%22generic%22%5D%5D%5D"
        
        try {
            val response = app.post(urlWithParams, headers = headers, data = body)
            val responseText = response.text
            
            val videoUrls = extractVideoUrlsFromResponse(responseText)
            
            if (videoUrls.isEmpty()) return false
            
            for (videoUrl in videoUrls) {
                val links = M3u8Helper.generateM3u8(
                    source = PRIMARY_SOURCE,
                    streamUrl = videoUrl,
                    referer = "https://youtube.googleapis.com/",
                    headers = mapOf(
                        "Referer" to "https://youtube.googleapis.com/",
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
                    )
                )
                
                if (links.isNotEmpty()) {
                    links.forEach { callback(it) }
                } else {
                    callback.invoke(
                        newExtractorLink(
                            source = PRIMARY_SOURCE,
                            name = "$PRIMARY_SOURCE $serverType HD",
                            url = videoUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "https://youtube.googleapis.com/"
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf(
                                "Referer" to "https://youtube.googleapis.com/",
                                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
                            )
                        }
                    )
                }
            }
            
            return true
            
        } catch (e: Exception) {
            return false
        }
    }
    
    suspend fun extractStreams(
        tmdbId: Int,
        mediaType: String,
        season: Int = 1,
        episode: Int = 1,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val targetSeason = if (mediaType == "movie") 1 else season
        val targetEpisode = if (mediaType == "movie") 1 else episode
        
        try {
            // 1. Página inicial
            val pageUrl = if (mediaType == "movie") {
                "$BASE_API/filme/$tmdbId"
            } else {
                "$BASE_API/serie/$tmdbId/$targetSeason/$targetEpisode"
            }
            
            val pageResponse = app.get(pageUrl, headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9",
                "Accept-Language" to "pt-BR",
                "Referer" to REFERER
            ) + getCookieHeader())
            
            if (!pageResponse.isSuccessful) return false
            updateCookies(pageResponse)
            
            val html = pageResponse.text
            
            // 2. Extrair tokens
            val csrfMatch = Regex("var CSRF_TOKEN\\s*=\\s*[\"']([^\"']+)[\"']").find(html)
            if (csrfMatch == null) return false
            csrfToken = csrfMatch.groupValues[1]
            
            val pageMatch = Regex("var PAGE_TOKEN\\s*=\\s*[\"']([^\"']+)[\"']").find(html)
            if (pageMatch == null) return false
            pageToken = pageMatch.groupValues[1]
            
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
                    } catch (e: Exception) { }
                }
            }
            
            if (contentId == null) return false
            
            // 4. Options
            val optionsBody = buildString {
                append("contentid=${URLEncoder.encode(contentId, "UTF-8")}&")
                append("type=${URLEncoder.encode(if (mediaType == "movie") "filme" else "serie", "UTF-8")}&")
                append("_token=${URLEncoder.encode(csrfToken, "UTF-8")}&")
                append("page_token=${URLEncoder.encode(pageToken, "UTF-8")}&")
                append("pageToken=${URLEncoder.encode(pageToken, "UTF-8")}")
            }
            
            val optionsResponse = app.post(
                "$BASE_API/player/options",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                    "Accept" to "application/json, text/plain, */*",
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Origin" to BASE_API,
                    "X-Page-Token" to pageToken,
                    "Referer" to pageUrl
                ) + getCookieHeader(),
                data = optionsBody
            )
            
            if (!optionsResponse.isSuccessful) return false
            
            val optionsData = JSONObject(optionsResponse.text)
            val optionsArray = optionsData.optJSONObject("data")?.optJSONArray("options") ?: return false
            
            // 5. Processar cada servidor
            var found = false
            for (i in 0 until optionsArray.length()) {
                val option = optionsArray.getJSONObject(i)
                val videoId = option.optString("ID")
                val type = option.optInt("type")
                val serverType = when (type) {
                    1 -> "Dublado"
                    2 -> "Legendado"
                    else -> "Tipo $type"
                }
                
                if (videoId.isEmpty()) continue
                
                // Source
                val sourceBody = buildString {
                    append("video_id=${URLEncoder.encode(videoId, "UTF-8")}&")
                    append("page_token=${URLEncoder.encode(pageToken, "UTF-8")}&")
                    append("_token=${URLEncoder.encode(csrfToken, "UTF-8")}")
                }
                
                val sourceResponse = app.post(
                    "$BASE_API/player/source",
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                        "Accept" to "application/json, text/plain, */*",
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                        "X-Requested-With" to "XMLHttpRequest",
                        "Origin" to BASE_API,
                        "Referer" to pageUrl
                    ) + getCookieHeader(),
                    data = sourceBody
                )
                
                if (!sourceResponse.isSuccessful) continue
                
                val sourceData = JSONObject(sourceResponse.text)
                val redirectUrl = sourceData.optJSONObject("data")?.optString("video_url")
                if (redirectUrl.isNullOrEmpty()) continue
                
                // Seguir redirect
                val redirectResponse = app.get(redirectUrl, headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                    "Accept" to "text/html,application/xhtml+xml",
                    "Accept-Language" to "pt-BR",
                    "Referer" to pageUrl
                ) + getCookieHeader())
                
                val finalUrl = redirectResponse.headers["location"] ?: redirectUrl
                
                if (!redirectResponse.isSuccessful && redirectResponse.headers["location"] == null) continue
                
                // Verificar se é URL do Blogger
                if (finalUrl.contains("blogger.com/video.g") || finalUrl.contains("blogger.com")) {
                    extractBloggerVideo(finalUrl, serverType) { link ->
                        callback(link)
                        found = true
                    }
                    continue
                }
                
                // Processamento normal (HLS)
                val playerHash = finalUrl.split("/").lastOrNull() ?: continue
                
                val videoBody = buildString {
                    append("hash=${URLEncoder.encode(playerHash, "UTF-8")}&")
                    append("r=")
                }
                
                val videoResponse = app.post(
                    "$CDN_BASE/player/index.php?data=$playerHash&do=getVideo",
                    headers = mapOf(
                        "Accept" to "*/*",
                        "Accept-Language" to "pt-BR",
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                        "Origin" to CDN_BASE,
                        "Referer" to "$CDN_BASE/",
                        "X-Requested-With" to "XMLHttpRequest",
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
                    ),
                    data = videoBody
                )
                
                if (!videoResponse.isSuccessful) continue
                
                val videoData = JSONObject(videoResponse.text)
                val videoUrl = videoData.optString("securedLink").takeIf { it.isNotEmpty() } 
                    ?: videoData.optString("videoSource")
                
                if (videoUrl.isNullOrEmpty()) continue
                
                val links = M3u8Helper.generateM3u8(
                    source = PRIMARY_SOURCE,
                    streamUrl = videoUrl,
                    referer = "$CDN_BASE/",
                    headers = mapOf(
                        "Referer" to "$CDN_BASE/",
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
                    )
                )
                
                if (links.isNotEmpty()) {
                    links.forEach { callback(it) }
                    found = true
                } else {
                    callback.invoke(
                        newExtractorLink(
                            source = PRIMARY_SOURCE,
                            name = "$PRIMARY_SOURCE $serverType HD",
                            url = videoUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$CDN_BASE/"
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf(
                                "Referer" to "$CDN_BASE/",
                                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
                            )
                        }
                    )
                    found = true
                }
            }
            
            return found
            
        } catch (e: Exception) {
            return false
        }
    }
}
