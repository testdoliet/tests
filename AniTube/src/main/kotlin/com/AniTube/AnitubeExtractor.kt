package com.AniTube

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.Jsoup
import java.security.MessageDigest
import java.util.*

object AniTubeVideoExtractor {
    
    private var cachedBloggerData: BloggerData? = null
    private var lastBloggerRefresh = 0L

    data class BloggerData(
        val fSid: String,
        val bl: String,
        val cfb2h: String,
        val UUFaWc: String,
        val hsFLT: String,
        val nonce: String,
        val token: String
    )
    
    private val itagQualityMap = mapOf(
        18 to 360,
        22 to 720,
        37 to 1080,
        59 to 480
    )
    
    private fun getRandomUserAgent(): String {
        return "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"
    }
    
    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔍 AniTubeExtractor: Iniciando extração para: $url")
        
        return try {
            // 1. Pegar página do episódio
            val pageResponse = app.get(url, headers = mapOf(
                "User-Agent" to getRandomUserAgent(),
                "Referer" to mainUrl
            ))
            
            val doc = Jsoup.parse(pageResponse.text)
            
            // 2. Procurar pelo iframe com classe metaframe
            val iframe = doc.selectFirst("iframe.metaframe")
            
            if (iframe != null) {
                val proxyUrl = iframe.attr("src")
                println("✅ Encontrou iframe proxy: $proxyUrl")
                
                // 3. Seguir o proxy (requisição para api.anivideo.net)
                return extractFromProxy(proxyUrl, url, name, callback)
            }
            
            println("❌ Nenhum iframe encontrado")
            false
            
        } catch (e: Exception) {
            println("❌ Erro: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    private suspend fun extractFromProxy(
        proxyUrl: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Construir URL completa do proxy
        val fullProxyUrl = if (proxyUrl.startsWith("http")) {
            proxyUrl
        } else {
            "https://www.anitube.news$proxyUrl"
        }
        
        println("🔍 Acessando proxy: $fullProxyUrl")
        
        // Fazer requisição para o proxy (api.anivideo.net)
        val proxyResponse = app.get(
            fullProxyUrl,
            headers = mapOf(
                "User-Agent" to getRandomUserAgent(),
                "Referer" to "https://www.anitube.news/",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "pt-BR",
                "sec-ch-ua" to "\"Chromium\";v=\"127\", \"Not)A;Brand\";v=\"99\", \"Microsoft Edge Simulate\";v=\"127\", \"Lemur\";v=\"127\"",
                "sec-ch-ua-mobile" to "?1",
                "sec-ch-ua-platform" to "\"Android\"",
                "sec-fetch-dest" to "iframe",
                "sec-fetch-mode" to "navigate",
                "sec-fetch-site" to "cross-site",
                "upgrade-insecure-requests" to "1"
            )
        )
        
        val proxyHtml = proxyResponse.text
        val proxyDoc = Jsoup.parse(proxyHtml)
        
        // 4. Extrair iframe do Blogger da resposta do proxy
        val bloggerIframe = proxyDoc.selectFirst("iframe[src*='blogger.com/video.g']")
        
        if (bloggerIframe == null) {
            println("❌ Iframe do Blogger não encontrado na resposta do proxy")
            return false
        }
        
        val bloggerUrl = bloggerIframe.attr("src")
        println("✅ URL do Blogger encontrada: $bloggerUrl")
        
        // 5. Extrair token da URL do Blogger
        val token = extractTokenFromUrl(bloggerUrl)
        if (token == null) {
            println("❌ Token não encontrado na URL")
            return false
        }
        
        println("✅ Token extraído: ${token.take(20)}...")
        
        // 6. Obter dados da sessão do Blogger
        val bloggerData = getBloggerSessionData(token, bloggerUrl)
        
        // 7. Chamar API batch execute
        val videos = callBloggerBatchApi(bloggerData)
        
        if (videos.isEmpty()) {
            println("❌ Nenhum vídeo encontrado na API")
            return false
        }
        
        println("✅ Encontradas ${videos.size} URLs de vídeo!")
        
        // 8. Timestamp para cpn
        val timestamp = System.currentTimeMillis()
        
        // 9. Processar cada URL
        videos.forEach { (videoUrl, itag) ->
            val quality = itagQualityMap[itag] ?: 480
            
            val videoId = extractVideoId(videoUrl)
            val cpn = generateCpn(bloggerData, videoId, timestamp)
            
            val urlBase = decodeUrl(videoUrl)
            val urlLimpa = urlBase.replace("\\&", "&")
            
            val urlFinal = buildString {
                append(urlLimpa)
                if (urlLimpa.contains("?")) {
                    append("&cpn=$cpn&c=WEB_EMBEDDED_PLAYER&cver=1.20260224.08.00")
                } else {
                    append("?cpn=$cpn&c=WEB_EMBEDDED_PLAYER&cver=1.20260224.08.00")
                }
            }
            
            val qualityLabel = when(quality) {
                1080 -> "FHD"
                720 -> "HD"
                480 -> "SD"
                360 -> "SD"
                else -> "SD"
            }
            
            callback(
                newExtractorLink(
                    source = "AniTube",
                    name = "$name ($qualityLabel)",
                    url = urlFinal,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "https://youtube.googleapis.com/"
                    this.quality = quality
                    this.headers = videoHeaders()
                }
            )
            println("✅ Link adicionado: $qualityLabel com cpn=$cpn")
        }
        
        return true
    }

    private suspend fun getBloggerSessionData(token: String, referer: String): BloggerData {
        if (cachedBloggerData != null && 
            cachedBloggerData?.token == token && 
            System.currentTimeMillis() - lastBloggerRefresh < 300000) {
            return cachedBloggerData!!
        }
        
        val bloggerResponse = app.get(
            "https://www.blogger.com/video.g?token=$token",
            headers = mapOf(
                "Referer" to referer,
                "User-Agent" to getRandomUserAgent(),
                "sec-ch-ua" to "\"Chromium\";v=\"127\", \"Not)A;Brand\";v=\"99\", \"Microsoft Edge Simulate\";v=\"127\", \"Lemur\";v=\"127\"",
                "sec-ch-ua-mobile" to "?1",
                "sec-ch-ua-platform" to "\"Android\""
            )
        )
        
        val html = bloggerResponse.text
        val wizData = extractWizData(html)
        val nonce = extractNonce(html) ?: generateRandomString(32)
        
        val data = BloggerData(
            fSid = wizData["FdrFJe"] ?: "-7535563745894756252",
            bl = wizData["cfb2h"] ?: "boq_bloggeruiserver_20260223.02_p0",
            cfb2h = wizData["cfb2h"] ?: "",
            UUFaWc = wizData["UUFaWc"] ?: "%.@.null,1000,2]",
            hsFLT = wizData["hsFLT"] ?: "%.@.null,1000,2]",
            nonce = nonce,
            token = token
        )
        
        cachedBloggerData = data
        lastBloggerRefresh = System.currentTimeMillis()
        
        return data
    }

    private fun extractWizData(html: String): Map<String, String> {
        val wizData = HashMap<String, String>()
        val pattern = """window\.WIZ_global_data\s*=\s*\{([^}]+)\}""".toRegex()
        val match = pattern.find(html)
        
        if (match != null) {
            val wizStr = match.groupValues[1]
            extractField(wizStr, "FdrFJe")?.let { wizData["FdrFJe"] = it }
            extractField(wizStr, "cfb2h")?.let { wizData["cfb2h"] = it }
            extractField(wizStr, "UUFaWc")?.let { wizData["UUFaWc"] = it }
            extractField(wizStr, "hsFLT")?.let { wizData["hsFLT"] = it }
        }
        return wizData
    }

    private fun extractField(data: String, field: String): String? {
        val pattern = """"$field":"([^"]+)"""".toRegex()
        return pattern.find(data)?.groupValues?.get(1)
    }

    private fun extractNonce(html: String): String? {
        val pattern = """script[^>]*nonce="([^"]+)"""".toRegex()
        return pattern.find(html)?.groupValues?.get(1)
    }

    private fun extractTokenFromUrl(url: String): String? {
        val pattern = """token=([a-zA-Z0-9_\-]+)""".toRegex()
        return pattern.find(url)?.groupValues?.get(1)
    }

    private suspend fun callBloggerBatchApi(data: BloggerData): List<Pair<String, Int>> {
        val apiUrl = "https://www.blogger.com/_/BloggerVideoPlayerUi/data/batchexecute"
        val reqid = (10000..99999).random()
        
        val headers = mapOf(
            "authority" to "www.blogger.com",
            "accept" to "*/*",
            "content-type" to "application/x-www-form-urlencoded;charset=UTF-8",
            "origin" to "https://www.blogger.com",
            "referer" to "https://www.blogger.com/",
            "user-agent" to getRandomUserAgent(),
            "sec-ch-ua" to "\"Chromium\";v=\"127\", \"Not)A;Brand\";v=\"99\", \"Microsoft Edge Simulate\";v=\"127\", \"Lemur\";v=\"127\"",
            "sec-ch-ua-mobile" to "?1",
            "sec-ch-ua-platform" to "\"Android\"",
            "x-client-data" to "COjuygE=",
            "x-same-domain" to "1"
        )
        
        val urlWithParams = "$apiUrl?rpcids=WcwnYd&source-path=%2Fvideo.g&f.sid=${data.fSid}&bl=${data.bl}&hl=pt-BR&_reqid=$reqid&rt=c"
        
        val body = mapOf(
            "f.req" to "%5B%5B%5B%22WcwnYd%22%2C%22%5B%5C%22${data.token}%5C%22%2C%5C%22%5C%22%2C0%5D%22%2Cnull%2C%22generic%22%5D%5D%5D"
        )
        
        val response = app.post(
            url = urlWithParams,
            headers = headers,
            data = body
        )
        
        return extractVideoUrlsFromResponse(response.text)
    }

    private fun extractVideoUrlsFromResponse(response: String): List<Pair<String, Int>> {
        val videos = mutableListOf<Pair<String, Int>>()
        val jsonData = extractGoogleJson(response)
        
        val urlPattern = """\"((?:https?:\\/\\/)?[^"]+?googlevideo[^"]+?)\",\[(\d+)\]""".toRegex()
        urlPattern.findAll(jsonData).forEach { match ->
            var url = match.groupValues[1]
            val itag = match.groupValues[2].toIntOrNull() ?: 18
            url = decodeUrl(url)
            videos.add(Pair(url, itag))
        }
        
        val qualityOrder = listOf(37, 22, 18, 59)
        return videos.distinctBy { it.second }.sortedBy { qualityOrder.indexOf(it.second) }
    }

    private fun extractGoogleJson(response: String): String {
        try {
            var data = response.replace(Regex("""^\)\]\}'\s*\n?"""), "")
            val pattern = """\[\s*\[\s*"wrb\.fr"\s*,\s*"[^"]*"\s*,\s*"(.+?)"\s*\]""".toRegex(RegexOption.DOT_MATCHES_ALL)
            val match = pattern.find(data)
            
            if (match != null) {
                var jsonStr = match.groupValues[1]
                jsonStr = jsonStr.replace("\\\"", "\"")
                jsonStr = jsonStr.replace("\\\\", "\\")
                jsonStr = decodeUnicodeEscapes(jsonStr)
                return jsonStr
            }
        } catch (e: Exception) {}
        return response
    }

    private fun decodeUnicodeEscapes(text: String): String {
        var result = text
        val pattern = """\\u([0-9a-fA-F]{4})""".toRegex()
        result = pattern.replace(result) { matchResult ->
            val hexCode = matchResult.groupValues[1]
            try { hexCode.toInt(16).toChar().toString() } catch (e: Exception) { "?" }
        }
        return result
    }

    private fun decodeUrl(url: String): String {
        var decoded = url
        decoded = decodeUnicodeEscapes(decoded)
        decoded = decoded.replace("\\/", "/")
        decoded = decoded.replace("\\\\", "\\")
        decoded = decoded.replace("\\=", "=")
        decoded = decoded.replace("\\&", "&")
        
        if (decoded.endsWith("\\")) {
            decoded = decoded.dropLast(1)
        }
        
        return decoded.trim('"')
    }

    private fun extractVideoId(url: String): String {
        val pattern = """id=([a-f0-9]+)""".toRegex()
        return pattern.find(url)?.groupValues?.get(1) ?: "picasacid"
    }

    private fun generateCpn(data: BloggerData, videoId: String, timestamp: Long): String {
        return try {
            val seed = buildString {
                append(data.cfb2h)
                append(data.UUFaWc)
                append(data.hsFLT)
                append(videoId)
                append(timestamp.toString())
                append(data.nonce)
            }
            
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

    private fun generateRandomString(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length).map { chars.random() }.joinToString("")
    }

    private fun videoHeaders(): Map<String, String> {
        return mapOf(
            "Referer" to "https://youtube.googleapis.com/",
            "User-Agent" to getRandomUserAgent(),
            "Accept" to "*/*",
            "Accept-Language" to "pt-BR",
            "Range" to "bytes=0-",
            "sec-ch-ua" to "\"Chromium\";v=\"127\", \"Not)A;Brand\";v=\"99\", \"Microsoft Edge Simulate\";v=\"127\", \"Lemur\";v=\"127\"",
            "sec-ch-ua-mobile" to "?1",
            "sec-ch-ua-platform" to "\"Android\"",
            "sec-fetch-dest" to "video",
            "sec-fetch-mode" to "no-cors",
            "sec-fetch-site" to "cross-site",
            "x-client-data" to "COjuygE="
        )
    }
}
