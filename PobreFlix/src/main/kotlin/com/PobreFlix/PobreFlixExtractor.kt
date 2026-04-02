package com.PobreFlix.extractor

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

object PobreFlixExtractor {

    private const val BASE_URL = "https://superflixapi.rest"
    private const val CDN_BASE = "https://llanfairpwllgwyngy.com"

    private var csrfToken: String = ""
    private var pageToken: String = ""
    private var sessionCookies: String = ""

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

    private fun extractVideoUrlsFromResponse(response: String): List<Pair<String, Int>> {
        val videos = mutableListOf<Pair<String, Int>>()
        
        // Remover o prefixo )]}'\n
        var data = response.replace(Regex("^\\]\\]\\)\\]\\}'\\n"), "")
        
        // Extrair URLs com itag
        val urlPattern = Regex("\"((?:https?:\\\\?/\\\\?/)?[^\"]+?googlevideo[^\"]+?)\",\\[(\\d+)\\]")
        val matches = urlPattern.findAll(data)
        
        for (match in matches) {
            var url = match.groupValues[1]
            val itag = match.groupValues[2].toIntOrNull() ?: 18
            url = decodeUrl(url)
            if (!url.startsWith("http")) url = "https://$url"
            videos.add(Pair(url, itag))
        }
        
        // Se não encontrou, tentar padrão mais simples
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
        
        // Ordenar por qualidade (1080p > 720p > 480p > 360p)
        val qualityOrder = listOf(37, 22, 18, 59)
        videos.sortBy { (_, itag) -> qualityOrder.indexOf(itag) }
        
        // Remover duplicados
        return videos.distinctBy { "${it.first}_${it.second}" }
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
        
        val tokenMatch = Regex("token=([a-zA-Z0-9_\\-]+)").find(bloggerUrl)
        val token = tokenMatch?.groupValues?.get(1) ?: return false

        val apiUrl = "https://www.blogger.com/_/BloggerVideoPlayerUi/data/batchexecute"
        val reqid = (10000..100000).random()
        val fS
