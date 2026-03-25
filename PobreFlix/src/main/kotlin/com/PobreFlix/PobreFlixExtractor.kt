package com.PobreFlix

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

object PobreFlixExtractor {
    
    private const val BASE_URL = "https://superflixapi.rest"
    private const val CDN_BASE = "https://llanfairpwllgwyngy.com"
    
    private var sessionCookies: String = ""
    private var csrfToken: String = ""
    private var pageToken: String = ""
    
    private val HEADERS = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "pt-BR",
        "Referer" to "https://lospobreflix.site/",
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
    
    private fun updateCookies(response: okhttp3.Response) {
        val setCookie = response.header("set-cookie")
        if (setCookie != null) {
            sessionCookies = setCookie
        }
    }
    
    private fun getCookieHeader(): Map<String, String> {
        return if (sessionCookies.isNotEmpty()) mapOf("Cookie" to sessionCookies) else emptyMap()
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
    
    private fun extractTokenFromUrl(url: String): String? {
        val match = Regex("token=([a-zA-Z0-9_\\-]+)").find(url)
        return match?.groupValues?.get(1)
    }
    
    private fun extractVideoUrlsFromResponse(response: String): List<String> {
        val videos = mutableListOf<String>()
        
        // Remover o prefixo )]}'\n
        var data = response.replace(Regex("^\\]\\)\\]\\}'\\n"), "")
        
        // Extrair URLs do Google Video
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
    
    private suspend fun extractBloggerVideo(bloggerUrl: String, referer: String, serverType: String, callback: (ExtractorLink) -> Unit): Boolean {
        println("[Blogger] Extraindo vídeo de: $bloggerUrl")
        
        val token = extractTokenFromUrl(bloggerUrl) ?: return false
        println("[Blogger] Token encontrado: ${token.take(20)}...")
        
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
            "user-agent" to HEADERS["User-Agent"]!!,
            "x-same-domain" to "1"
        )
        
        val urlWithParams = "$apiUrl?rpcids=WcwnYd&source-path=%2Fvideo.g&f.sid=$fSid&bl=$bl&hl=pt-BR&_reqid=$reqid&rt=c"
        val body = "f.req=%5B%5B%5B%22WcwnYd%22%2C%22%5B%5C%22$token%5C%22%2C%5C%22%5C%22%2C0%5D%22%2Cnull%2C%22generic%22%5D%5D%5D"
        
        try {
            val response = app.post(urlWithParams, headers = headers, data = body)
            val responseText = response.text
            
            val videoUrls = extractVideoUrlsFromResponse(responseText)
            
            if (videoUrls.isEmpty()) {
                println("[Blogger] Nenhuma URL de vídeo encontrada")
                return false
            }
            
            println("[Blogger] Encontradas ${videoUrls.size} URLs")
            
            for (videoUrl in videoUrls) {
                callback.invoke(
                    newExtractorLink(
                        source = "SuperFlix",
                        name = "SuperFlix $serverType HD",
                        url = videoUrl,
                        type = ExtractorLinkType.M3U8,
                        quality = Qualities.Unknown.value
                    ) {
                        this.referer = "https://youtube.googleapis.com/"
                        this.headers = mapOf(
                            "Referer" to "https://youtube.googleapis.com/",
                            "User-Agent" to HEADERS["User-Agent"]!!,
                            "Accept" to "*/*",
                            "Accept-Language" to "pt-BR",
                            "Range" to "bytes=0-"
                        )
                    }
                )
                println("[Blogger] ✅ Link adicionado: ${videoUrl.take(80)}...")
            }
            
            return true
            
        } catch (e: Exception) {
            println("[Blogger] Erro: ${e.message}")
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
        
        println("\n${"=".repeat(60)}")
        println("[SuperFlix] Buscando: $mediaType $tmdbId S${targetSeason}E${targetEpisode}")
        println("${"=".repeat(60)}")
        
        try {
            // 1. Página inicial
            val pageUrl = if (mediaType == "movie") {
                "$BASE_URL/filme/$tmdbId"
            } else {
                "$BASE_URL/serie/$tmdbId/$targetSeason/$targetEpisode"
            }
            
            println("[1] Página: $pageUrl")
            
            val pageResponse = app.get(pageUrl, headers = HEADERS + getCookieHeader())
            println("[1] Status: ${pageResponse.code}")
            
            if (!pageResponse.isSuccessful) return emptyList()
            updateCookies(pageResponse)
            
            var html = pageResponse.text
            println("[1] HTML: ${html.length} caracteres")
            
            // 2. Extrair tokens
            val csrfMatch = Regex("var CSRF_TOKEN\\s*=\\s*[\"']([^\"']+)[\"']").find(html)
            if (csrfMatch == null) {
                println("[2] CSRF_TOKEN não encontrado")
                return emptyList()
            }
            csrfToken = csrfMatch.groupValues[1]
            println("[2] CSRF_TOKEN: ${csrfToken.take(30)}...")
            
            val pageMatch = Regex("var PAGE_TOKEN\\s*=\\s*[\"']([^\"']+)[\"']").find(html)
            if (pageMatch == null) {
                println("[2] PAGE_TOKEN não encontrado")
                return emptyList()
            }
            pageToken = pageMatch.groupValues[1]
            println("[2] PAGE_TOKEN: ${pageToken.take(30)}...")
            
            // 3. Extrair contentId
            var contentId: String? = null
            
            if (mediaType == "movie") {
                val initialContentMatch = Regex("INITIAL_CONTENT_ID\\s*=\\s*(\\d+)").find(html)
                if (initialContentMatch != null) {
                    contentId = initialContentMatch.groupValues[1]
                    println("[3] CONTENT_ID (filme): $contentId")
                } else {
                    val dataContentMatch = Regex("data-contentid=[\"'](\\d+)[\"']").find(html)
                    contentId = dataContentMatch?.groupValues?.get(1)
                    println("[3] CONTENT_ID (fallback): $contentId")
                }
            } else {
                val epMatch = Regex("var ALL_EPISODES\\s*=\\s*(\\{.*?\\});", RegexOption.DOT_MATCHES_ALL).find(html)
                if (epMatch != null) {
                    try {
                        val jsonString = epMatch.groupValues[1]
                        val jsonObject = app.parseJson<Map<String, List<Map<String, Any>>>>(jsonString)
                        
                        val seasonData = jsonObject[targetSeason.toString()]
                        if (seasonData != null) {
                            for (ep in seasonData) {
                                val epNum = (ep["epi_num"] as? Number)?.toInt()
                                if (epNum == targetEpisode) {
                                    contentId = (ep["ID"] ?: ep["id"])?.toString()
                                    println("[3] CONTENT_ID (série): $contentId (episódio $targetEpisode)")
                                    break
                                }
                            }
                        }
                    } catch (e: Exception) {
                        println("[3] ERRO ao parsear episódios: ${e.message}")
                    }
                }
            }
            
            if (contentId == null) {
                println("[3] CONTENT_ID não encontrado")
                return emptyList()
            }
            
            // 4. Options
            val optionsParams = mapOf(
                "contentid" to contentId,
                "type" to if (mediaType == "movie") "filme" else "serie",
                "_token" to csrfToken,
                "page_token" to pageToken,
                "pageToken" to pageToken
            )
            
            println("[4] Options: contentId=$contentId, type=${if (mediaType == "movie") "filme" else "serie"}")
            
            val optionsResponse = app.post(
                "$BASE_URL/player/options",
                headers = API_HEADERS + getCookieHeader() + mapOf("X-Page-Token" to pageToken, "Referer" to pageUrl),
                data = optionsParams.toFormData()
            )
            
            println("[4] Options status: ${optionsResponse.code}")
            
            if (!optionsResponse.isSuccessful) return emptyList()
            
            val optionsData = app.parseJson<Map<String, Any>>(optionsResponse.text)
            val optionsArray = (optionsData["data"] as? Map<*, *>)?.get("options") as? List<*> ?: emptyList<Any>()
            println("[4] Options count: ${optionsArray.size}")
            
            // 5. Processar CADA servidor
            for ((index, option) in optionsArray.withIndex()) {
                val optionMap = option as Map<*, *>
                val videoId = optionMap["ID"]?.toString() ?: continue
                val type = (optionMap["type"] as? Number)?.toInt() ?: 0
                val serverType = when (type) {
                    1 -> "Dublado"
                    2 -> "Legendado"
                    else -> "Tipo $type"
                }
                
                println("\n[5.$index] Processando $serverType (ID: $videoId)")
                
                // Source
                val sourceParams = mapOf(
                    "video_id" to videoId,
                    "page_token" to pageToken,
                    "_token" to csrfToken
                )
                
                println("[5.$index] Source: video_id=$videoId")
                
                val sourceResponse = app.post(
                    "$BASE_URL/player/source",
                    headers = API_HEADERS + getCookieHeader() + mapOf("Referer" to pageUrl),
                    data = sourceParams.toFormData()
                )
                
                println("[5.$index] Source status: ${sourceResponse.code}")
                
                if (!sourceResponse.isSuccessful) {
                    println("[5.$index] Source error: ${sourceResponse.text.take(200)}")
                    continue
                }
                
                val sourceData = app.parseJson<Map<String, Any>>(sourceResponse.text)
                val redirectUrl = (sourceData["data"] as? Map<*, *>)?.get("video_url")?.toString()
                println("[5.$index] Redirect URL: ${redirectUrl?.take(100)}...")
                
                if (redirectUrl == null) continue
                
                // Seguir redirect
                println("[5.$index] Seguindo redirect...")
                
                val redirectResponse = app.get(redirectUrl, headers = HEADERS + getCookieHeader())
                println("[5.$index] Redirect status: ${redirectResponse.code}")
                
                val finalUrl = redirectResponse.headers["location"] ?: redirectResponse.request.url.toString()
                println("[5.$index] URL final: $finalUrl")
                
                if (!redirectResponse.isSuccessful && redirectResponse.headers["location"] == null) {
                    println("[5.$index] ERRO: Redirect falhou")
                    continue
                }
                
                // Verificar se é URL do Blogger
                if (finalUrl.contains("blogger.com/video.g") || finalUrl.contains("blogger.com")) {
                    println("[5.$index] Detectado Blogger - processando via extrator")
                    
                    val success = extractBloggerVideo(finalUrl, pageUrl, serverType) { stream ->
                        results.add(stream)
                    }
                    
                    if (success) {
                        println("[5.$index] ✅ Blogger processado com sucesso!")
                    } else {
                        println("[5.$index] ❌ Blogger falhou")
                    }
                    continue
                }
                
                // Processamento normal (HLS)
                val playerHash = finalUrl.split("/").lastOrNull() ?: continue
                println("[5.$index] Player hash: $playerHash")
                
                println("[5.$index] Obtendo vídeo final...")
                
                val videoParams = mapOf(
                    "hash" to playerHash,
                    "r" to ""
                )
                
                val videoResponse = app.post(
                    "$CDN_BASE/player/index.php?data=$playerHash&do=getVideo",
                    headers = mapOf(
                        "Accept" to "*/*",
                        "Accept-Language" to "pt-BR",
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                        "Origin" to CDN_BASE,
                        "Referer" to "$CDN_BASE/",
                        "X-Requested-With" to "XMLHttpRequest",
                        "User-Agent" to HEADERS["User-Agent"]!!
                    ),
                    data = videoParams.toFormData()
                )
                
                println("[5.$index] Video status: ${videoResponse.code}")
                
                if (!videoResponse.isSuccessful) continue
                
                val videoData = app.parseJson<Map<String, Any>>(videoResponse.text)
                val videoUrl = (videoData["securedLink"] ?: videoData["videoSource"])?.toString()
                println("[5.$index] Video URL: ${videoUrl?.take(100)}...")
                
                if (videoUrl == null) continue
                
                println("[5.$index] ✅ SUCESSO! $serverType HD")
                
                results.add(
                    newExtractorLink(
                        source = "SuperFlix",
                        name = "SuperFlix $serverType HD",
                        url = videoUrl,
                        type = ExtractorLinkType.M3U8,
                        quality = Qualities.Unknown.value
                    ) {
                        this.referer = "$CDN_BASE/"
                        this.headers = mapOf(
                            "Referer" to "$CDN_BASE/",
                            "User-Agent" to HEADERS["User-Agent"]!!
                        )
                    }
                )
            }
            
            println("\n${"=".repeat(60)}")
            println("[SuperFlix] Total streams encontrados: ${results.size}")
            println("${"=".repeat(60)}")
            
            return results
            
        } catch (e: Exception) {
            println("\n[SuperFlix] ERRO: ${e.message}")
            e.printStackTrace()
            return emptyList()
        }
    }
    
    private fun Map<String, String>.toFormData(): String {
        return this.entries.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, "UTF-8")}"
        }
    }
}
