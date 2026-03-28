package com.PobreFlix.extractor

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest
import java.util.Base64

object PobreFlixExtractor {

    private const val BASE_URL = "https://superflixapi.rest"
    private const val CDN_BASE = "https://llanfairpwllgwyngy.com"

    // SESSION_DATA como no JavaScript
    private data class SessionData(
        var cookies: String = "",
        var csrfToken: String = "",
        var pageToken: String = ""
    )

    private val session = SessionData()

    // Headers para páginas HTML (igual ao JavaScript)
    private val HEADERS = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "pt-BR",
        "Referer" to "https://lospobreflix.site/",
        "Sec-Fetch-Dest" to "iframe",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "cross-site",
        "Upgrade-Insecure-Requests" to "1",
        "Connection" to "keep-alive"
    )

    // Headers para API (igual ao JavaScript)
    private val API_HEADERS = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "pt-BR",
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "X-Requested-With" to "XMLHttpRequest",
        "Origin" to BASE_URL,
        "Connection" to "keep-alive"
    )

    // Mapa de qualidade ITAG (igual ao JavaScript)
    private val ITAG_QUALITY_MAP = mapOf(
        18 to 360,
        22 to 720,
        37 to 1080,
        59 to 480
    )

    private fun updateCookies(responseHeaders: Map<String, String>) {
        val setCookie = responseHeaders["set-cookie"]
        if (setCookie != null) {
            session.cookies = setCookie
        }
    }

    private fun getCookieHeader(): Map<String, String> {
        return if (session.cookies.isNotEmpty()) mapOf("Cookie" to session.cookies) else emptyMap()
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

    private fun extractVideoId(url: String): String {
        val match = Regex("id=([a-f0-9]+)").find(url)
        return match?.groupValues?.get(1) ?: "picasacid"
    }

    private fun extractTokenFromUrl(url: String): String? {
        val match = Regex("token=([a-zA-Z0-9_\\-]+)").find(url)
        return match?.groupValues?.get(1)
    }

    private fun generateRandomString(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length).map { chars.random() }.joinToString("")
    }

    private suspend fun generateCpn(token: String, videoId: String, timestamp: Long): String {
        return try {
            val seed = "boq_bloggeruiserver_20260223.02_p0$videoId$timestamp$token"
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(seed.toByteArray())
            Base64.getEncoder().encodeToString(hash)
                .substring(0, 16)
                .replace("+", "")
                .replace("/", "")
                .replace("=", "")
        } catch (e: Exception) {
            generateRandomString(16)
        }
    }

    private fun decodeUnicodeEscapes(text: String): String {
        return text.replace(Regex("\\\\u([0-9a-fA-F]{4})")) { match ->
            val hex = match.groupValues[1]
            try {
                hex.toInt(16).toChar().toString()
            } catch (e: Exception) {
                "?"
            }
        }
    }

    private suspend fun extractBloggerVideo(
        bloggerUrl: String,
        referer: String,
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

        val headers = mapOf(
            "authority" to "www.blogger.com",
            "accept" to "*/*",
            "content-type" to "application/x-www-form-urlencoded;charset=UTF-8",
            "origin" to "https://www.blogger.com",
            "referer" to "https://www.blogger.com/",
            "user-agent" to HEADERS["User-Agent"]!!,
            "sec-ch-ua" to "\"Chromium\";v=\"127\", \"Not)A;Brand\";v=\"99\", \"Microsoft Edge Simulate\";v=\"127\", \"Lemur\";v=\"127\"",
            "sec-ch-ua-mobile" to "?1",
            "sec-ch-ua-platform" to "\"Android\"",
            "x-client-data" to "COjuygE=",
            "x-same-domain" to "1"
        )

        val body = "f.req=%5B%5B%5B%22WcwnYd%22%2C%22%5B%5C%22$token%5C%22%2C%5C%22%5C%22%2C0%5D%22%2Cnull%2C%22generic%22%5D%5D%5D"

        try {
            val response = app.post(urlWithParams, headers = headers, data = body)
            if (!response.isSuccessful) return false

            val responseText = response.text
            val videoUrls = extractVideoUrlsFromResponse(responseText)

            if (videoUrls.isEmpty()) return false

            val timestamp = System.currentTimeMillis()

            for ((videoUrl, itag) in videoUrls) {
                val videoQuality = ITAG_QUALITY_MAP[itag] ?: 480
                val videoId = extractVideoId(videoUrl)
                val cpn = generateCpn(token, videoId, timestamp)

                var urlBase = decodeUrl(videoUrl)
                val urlFinal = if (urlBase.contains("?")) {
                    "$urlBase&cpn=$cpn&c=WEB_EMBEDDED_PLAYER&cver=1.20260224.08.00"
                } else {
                    "$urlBase?cpn=$cpn&c=WEB_EMBEDDED_PLAYER&cver=1.20260224.08.00"
                }

                callback(
                    newExtractorLink(
                        source = "SuperFlix",
                        name = "SuperFlix $serverType ${videoQuality}p",
                        url = urlFinal,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://youtube.googleapis.com/"
                        this.quality = videoQuality
                        this.headers = mapOf(
                            "Referer" to "https://youtube.googleapis.com/",
                            "User-Agent" to HEADERS["User-Agent"]!!,
                            "Accept" to "*/*",
                            "Accept-Language" to "pt-BR",
                            "Range" to "bytes=0-",
                            "sec-ch-ua" to "\"Chromium\";v=\"127\", \"Not)A;Brand\";v=\"99\", \"Microsoft Edge Simulate\";v=\"127\", \"Lemur\";v=\"127\"",
                            "sec-ch-ua-mobile" to "?1",
                            "sec-ch-ua-platform" to "\"Android\"",
                            "sec-fetch-dest" to "video",
                            "sec-fetch-mode" to "no-cors",
                            "sec-fetch-site" to "cross-site"
                        )
                    }
                )
            }

            return true

        } catch (e: Exception) {
            println("[PobreFlix] Erro no extractBloggerVideo: ${e.message}")
            return false
        }
    }

    private fun extractVideoUrlsFromResponse(response: String): List<Pair<String, Int>> {
        val videos = mutableListOf<Pair<String, Int>>()

        var data = response.replace(Regex("^\\]\\)\\]\\}'\\n"), "")

        val jsonPattern = Regex("""\[\s*\[\s*"wrb\.fr"\s*,\s*"[^"]*"\s*,\s*"(.+?)"\s*\]""", RegexOption.DOT_MATCHES_ALL)
        val match = jsonPattern.find(data)

        if (match != null) {
            var jsonStr = match.groupValues[1]
            jsonStr = jsonStr.replace("\\\"", "\"")
            jsonStr = jsonStr.replace("\\\\", "\\")
            jsonStr = decodeUnicodeEscapes(jsonStr)

            val urlPattern = Regex("\"((?:https?:\\\\/\\\\/)?[^\"]+?googlevideo[^\"]+?)\",\\[(\\d+)\\]")
            val urlMatches = urlPattern.findAll(jsonStr)

            for (urlMatch in urlMatches) {
                var url = urlMatch.groupValues[1]
                val itag = urlMatch.groupValues[2].toIntOrNull() ?: 18
                url = decodeUrl(url)
                videos.add(Pair(url, itag))
            }

            if (videos.isEmpty()) {
                val simplePattern = Regex("https?:\\\\?/\\\\?/[^\"'\\s]+?googlevideo[^\"'\\s]+")
                val simpleMatches = simplePattern.findAll(jsonStr)

                for (simpleMatch in simpleMatches) {
                    var url = simpleMatch.value
                    if (!url.startsWith("http")) url = "https://$url"
                    url = decodeUrl(url)
                    val itag = extractItagFromUrl(url)
                    videos.add(Pair(url, itag))
                }
            }
        }

        val qualityOrder = listOf(37, 22, 18, 59)
        return videos
            .distinctBy { it.first + it.second.toString() }
            .sortedBy { qualityOrder.indexOf(it.second) }
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
            // 1. Página inicial
            val pageUrl = if (mediaType == "movie") {
                "$BASE_URL/filme/$tmdbId"
            } else {
                "$BASE_URL/serie/$tmdbId/$targetSeason/$targetEpisode"
            }

            println("[PobreFlix] URL da página: $pageUrl")

            var pageResponse = app.get(
                url = pageUrl,
                headers = HEADERS + getCookieHeader()
            )

            if (!pageResponse.isSuccessful) return emptyList()

            // Atualizar cookies
            try {
                val responseHeaders = pageResponse.headers
                updateCookies(responseHeaders)
            } catch (e: Exception) {}

            var html = pageResponse.text
            println("[PobreFlix] HTML carregado, tamanho: ${html.length}")

            var finalHtml = html
            if (!html.contains("var CSRF_TOKEN") && !html.contains("<!DOCTYPE")) {
                println("[PobreFlix] HTML comprimido, tentando sem brotli...")
                val altResponse = app.get(
                    url = pageUrl,
                    headers = HEADERS + getCookieHeader() + mapOf("Accept-Encoding" to "gzip, deflate")
                )
                if (altResponse.isSuccessful) {
                    try {
                        val responseHeaders = altResponse.headers
                        updateCookies(responseHeaders)
                    } catch (e: Exception) {}
                    finalHtml = altResponse.text
                    println("[PobreFlix] HTML alternativo: ${finalHtml.length} caracteres")
                }
            }

            // 2. Extrair tokens
            val csrfMatch = Regex("var CSRF_TOKEN\\s*=\\s*[\"']([^\"']+)[\"']").find(finalHtml)
            if (csrfMatch == null) return emptyList()
            session.csrfToken = csrfMatch.groupValues[1]
            println("[PobreFlix] CSRF_TOKEN: ${session.csrfToken.take(30)}...")

            val pageMatch = Regex("var PAGE_TOKEN\\s*=\\s*[\"']([^\"']+)[\"']").find(finalHtml)
            if (pageMatch == null) return emptyList()
            session.pageToken = pageMatch.groupValues[1]
            println("[PobreFlix] PAGE_TOKEN: ${session.pageToken.take(30)}...")

            // 3. Extrair contentId
            var contentId: String? = null

            if (mediaType == "movie") {
                val initialContentMatch = Regex("INITIAL_CONTENT_ID\\s*=\\s*(\\d+)").find(finalHtml)
                if (initialContentMatch != null) {
                    contentId = initialContentMatch.groupValues[1]
                    println("[PobreFlix] CONTENT_ID (filme): $contentId")
                } else {
                    val dataContentMatch = Regex("data-contentid=[\"'](\\d+)[\"']").find(finalHtml)
                    contentId = dataContentMatch?.groupValues?.get(1)
                    println("[PobreFlix] CONTENT_ID (fallback): $contentId")
                }
            } else {
                val epMatch = Regex("var ALL_EPISODES\\s*=\\s*(\\{.*?\\});", RegexOption.DOT_MATCHES_ALL).find(finalHtml)
                if (epMatch != null) {
                    try {
                        val episodesJson = JSONObject(epMatch.groupValues[1])
                        val seasonData = episodesJson.optJSONArray(targetSeason.toString())
                        if (seasonData != null) {
                            for (i in 0 until seasonData.length()) {
                                val ep = seasonData.getJSONObject(i)
                                val epNum = ep.optInt("epi_num")
                                if (epNum == targetEpisode) {
                                    contentId = ep.optString("ID").takeIf { it.isNotEmpty() }
                                    println("[PobreFlix] CONTENT_ID (série): $contentId (episódio $targetEpisode)")
                                    break
                                }
                            }
                        }
                    } catch (e: Exception) {
                        println("[PobreFlix] ERRO ao parsear episódios: ${e.message}")
                    }
                }
            }

            if (contentId == null) return emptyList()

            // 4. Options
            val optionsParams = mutableMapOf<String, String>()
            optionsParams["contentid"] = contentId
            optionsParams["type"] = if (mediaType == "movie") "filme" else "serie"
            optionsParams["_token"] = session.csrfToken
            optionsParams["page_token"] = session.pageToken
            optionsParams["pageToken"] = session.pageToken

            println("[PobreFlix] Options: contentId=$contentId, type=${if (mediaType == "movie") "filme" else "serie"}")

            val optionsResponse = app.post(
                url = "$BASE_URL/player/options",
                headers = API_HEADERS + getCookieHeader() + mapOf(
                    "X-Page-Token" to session.pageToken,
                    "Referer" to pageUrl
                ),
                data = optionsParams
            )

            if (!optionsResponse.isSuccessful) return emptyList()

            val optionsData = JSONObject(optionsResponse.text)
            val optionsArray = optionsData.optJSONObject("data")?.optJSONArray("options") ?: return emptyList()
            println("[PobreFlix] Options count: ${optionsArray.length()}")

            // 5. Processar CADA servidor
            for (i in 0 until optionsArray.length()) {
                val option = optionsArray.getJSONObject(i)
                val videoId = option.optString("ID")
                val type = option.optInt("type")
                val serverType = when (type) {
                    1 -> "Dublado"
                    2 -> "Legendado"
                    else -> "Tipo $type"
                }

                println("[PobreFlix] Processando $serverType (ID: $videoId)")

                if (videoId.isEmpty()) continue

                // Source
                val sourceParams = mutableMapOf<String, String>()
                sourceParams["video_id"] = videoId
                sourceParams["page_token"] = session.pageToken
                sourceParams["_token"] = session.csrfToken

                val sourceResponse = app.post(
                    url = "$BASE_URL/player/source",
                    headers = API_HEADERS + getCookieHeader() + mapOf("Referer" to pageUrl),
                    data = sourceParams
                )

                if (!sourceResponse.isSuccessful) continue

                val sourceData = JSONObject(sourceResponse.text)
                val redirectUrl = sourceData.optJSONObject("data")?.optString("video_url")
                if (redirectUrl.isNullOrEmpty()) continue

                println("[PobreFlix] Redirect URL: ${redirectUrl.take(100)}...")

                // Seguir redirect
                val redirectResponse = app.get(
                    url = redirectUrl,
                    headers = HEADERS + getCookieHeader()
                )

                val finalUrl = try {
                    redirectResponse.url
                } catch (e: Exception) {
                    redirectUrl
                }

                println("[PobreFlix] URL final: $finalUrl")

                // Verificar se é URL do Blogger
                if (finalUrl.contains("blogger.com/video.g") || finalUrl.contains("blogger.com")) {
                    println("[PobreFlix] Detectado Blogger - processando via extrator")
                    val title = if (mediaType == "movie") "Filme $tmdbId" else "S${targetSeason.toString().padStart(2, '0')}E${targetEpisode.toString().padStart(2, '0')}"
                    extractBloggerVideo(finalUrl, pageUrl, serverType, title) { stream ->
                        results.add(stream)
                    }
                    continue
                }

                // Processamento normal (HLS)
                val playerHash = finalUrl.split("/").lastOrNull() ?: continue
                println("[PobreFlix] Player hash: $playerHash")

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

                println("[PobreFlix] Video URL: ${videoUrl.take(100)}...")

                var quality = 720
                when {
                    videoUrl.contains("2160") || videoUrl.contains("4k") -> quality = 2160
                    videoUrl.contains("1440") -> quality = 1440
                    videoUrl.contains("1080") -> quality = 1080
                    videoUrl.contains("720") -> quality = 720
                    videoUrl.contains("480") -> quality = 480
                }

                val title = if (mediaType == "movie") "Filme $tmdbId" else "S${targetSeason.toString().padStart(2, '0')}E${targetEpisode.toString().padStart(2, '0')}"

                println("[PobreFlix] ✅ SUCESSO! $serverType ${quality}p")

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
            }

            println("[PobreFlix] Total streams encontrados: ${results.size}")
            return results

        } catch (e: Exception) {
            println("[PobreFlix] ERRO: ${e.message}")
            e.printStackTrace()
            return emptyList()
        }
    }
}
