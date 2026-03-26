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
        val token = extractTokenFromUrl(bloggerUrl) ?: return false

        val apiUrl = "https://www.blogger.com/_/BloggerVideoPlayerUi/data/batchexecute"
        val reqid = (10000..100000).random()
        val fSid = "-7535563745894756252"
        val bl = "boq_bloggeruiserver_20260223.02_p0"
        val urlWithParams = "$apiUrl?rpcids=WcwnYd&source-path=%2Fvideo.g&f.sid=$fSid&bl=$bl&hl=pt-BR&_reqid=$reqid&rt=c"
        val body = "f.req=%5B%5B%5B%22WcwnYd%22%2C%22%5B%5C%22$token%5C%22%2C%5C%22%5C%22%2C0%5D%22%2Cnull%2C%22generic%22%5D%5D%5D"

        try {
            val response = app.post(urlWithParams, data = body)
            val responseText = response.text
            val videoUrls = extractVideoUrlsFromResponse(responseText)
            
            if (videoUrls.isEmpty()) return false
            
            for (videoUrl in videoUrls) {
                val links = M3u8Helper.generateM3u8(
                    source = "SuperFlix",
                    streamUrl = videoUrl,
                    referer = "https://youtube.googleapis.com/"
                )
                
                if (links.isNotEmpty()) {
                    links.forEach { callback(it) }
                } else {
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

        try {
            // 1. Página inicial
            val pageUrl = if (mediaType == "movie") {
                "$BASE_URL/filme/$tmdbId"
            } else {
                "$BASE_URL/serie/$tmdbId/$targetSeason/$targetEpisode"
            }
            
            val pageResponse = app.get(pageUrl)
            if (!pageResponse.isSuccessful) return emptyList()
            val html = pageResponse.text
            
            // 2. Extrair tokens
            val csrfMatch = Regex("var CSRF_TOKEN\\s*=\\s*[\"']([^\"']+)[\"']").find(html)
            if (csrfMatch == null) return emptyList()
            csrfToken = csrfMatch.groupValues[1]
            
            val pageMatch = Regex("var PAGE_TOKEN\\s*=\\s*[\"']([^\"']+)[\"']").find(html)
            if (pageMatch == null) return emptyList()
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
            
            if (contentId == null) return emptyList()
            
            // 4. Options - SEM HEADERS COMPLEXOS
            val optionsParams = mutableListOf<String>()
            optionsParams.add("contentid=${URLEncoder.encode(contentId, "UTF-8")}")
            optionsParams.add("type=${URLEncoder.encode(if (mediaType == "movie") "filme" else "serie", "UTF-8")}")
            optionsParams.add("_token=${URLEncoder.encode(csrfToken, "UTF-8")}")
            optionsParams.add("page_token=${URLEncoder.encode(pageToken, "UTF-8")}")
            optionsParams.add("pageToken=${URLEncoder.encode(pageToken, "UTF-8")}")
            val optionsBody = optionsParams.joinToString("&")
            
            val optionsResponse = app.post("$BASE_URL/player/options", data = optionsBody)
            if (!optionsResponse.isSuccessful) return emptyList()
            
            val optionsData = JSONObject(optionsResponse.text)
            val optionsArray = optionsData.optJSONObject("data")?.optJSONArray("options") ?: return emptyList()
            
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
                
                if (videoId.isEmpty()) continue
                
                // Source - SEM HEADERS COMPLEXOS
                val sourceParams = mutableListOf<String>()
                sourceParams.add("video_id=${URLEncoder.encode(videoId, "UTF-8")}")
                sourceParams.add("page_token=${URLEncoder.encode(pageToken, "UTF-8")}")
                sourceParams.add("_token=${URLEncoder.encode(csrfToken, "UTF-8")}")
                val sourceBody = sourceParams.joinToString("&")
                
                val sourceResponse = app.post("$BASE_URL/player/source", data = sourceBody)
                if (!sourceResponse.isSuccessful) continue
                
                val sourceData = JSONObject(sourceResponse.text)
                val redirectUrl = sourceData.optJSONObject("data")?.optString("video_url")
                if (redirectUrl.isNullOrEmpty()) continue
                
                // Seguir redirect
                val redirectResponse = app.get(redirectUrl)
                if (!redirectResponse.isSuccessful) continue
                
                val finalUrl = try {
                    redirectResponse.url
                } catch (e: Exception) {
                    redirectUrl
                }
                
                // Verificar se é URL do Blogger
                if (finalUrl.contains("blogger.com/video.g") || finalUrl.contains("blogger.com")) {
                    extractBloggerVideo(finalUrl, serverType) { stream ->
                        results.add(stream)
                    }
                    continue
                }
                
                // Processamento normal (HLS)
                val playerHash = finalUrl.split("/").lastOrNull() ?: continue
                
                val videoParams = mutableListOf<String>()
                videoParams.add("hash=${URLEncoder.encode(playerHash, "UTF-8")}")
                videoParams.add("r=")
                val videoBody = videoParams.joinToString("&")
                
                val videoResponse = app.post(
                    "$CDN_BASE/player/index.php?data=$playerHash&do=getVideo",
                    data = videoBody
                )
                
                if (!videoResponse.isSuccessful) continue
                
                val videoData = JSONObject(videoResponse.text)
                val videoUrl = videoData.optString("securedLink").takeIf { it.isNotEmpty() } 
                    ?: videoData.optString("videoSource")
                
                if (videoUrl.isNullOrEmpty()) continue
                
                val links = M3u8Helper.generateM3u8(
                    source = "SuperFlix",
                    streamUrl = videoUrl,
                    referer = "$CDN_BASE/"
                )
                
                if (links.isNotEmpty()) {
                    results.addAll(links)
                } else {
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
            
            return results
            
        } catch (e: Exception) {
            return emptyList()
        }
    }
}
