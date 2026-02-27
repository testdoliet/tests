package com.AniTube

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.Jsoup
import java.security.MessageDigest
import java.util.*

object AniTubeVideoExtractor {
    
    // Cache para dados da sessão do Blogger
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
        return "Mozilla/5.0 (Linux; Android 13; SM-S901B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
    
    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔍 AniTubeExtractor: Iniciando extração para: $url")
        
        return try {
            // Primeiro, verificar se é Blogger
            val pageResponse = app.get(url, headers = mapOf(
                "User-Agent" to getRandomUserAgent(),
                "Referer" to mainUrl
            ))
            
            val doc = Jsoup.parse(pageResponse.text)
            
            // Procurar por iframe do Blogger
            val bloggerIframe = doc.selectFirst("iframe[src*='blogger.com/video.g']")
            
            if (bloggerIframe != null) {
                println("✅ AniTubeExtractor: Detectado iframe do Blogger")
                return extractBloggerVideo(bloggerIframe.attr("src"), url, name, callback)
            }
            
            // Se não for Blogger, tentar outras estratégias
            println("⚠️ AniTubeExtractor: Não é Blogger, tentando outras estratégias...")
            
            val webViewResult = extractWithWebView(url, mainUrl, name, callback)
            if (webViewResult) return true
            
            val directResult = extractWithDirectHtml(url, mainUrl, name, callback)
            if (directResult) return true
            
            false
            
        } catch (e: Exception) {
            println("❌ AniTubeExtractor: Erro: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    private suspend fun extractBloggerVideo(
        iframeUrl: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // Extrair token da URL do iframe
            val token = extractTokenFromUrl(iframeUrl) ?: return false
            
            // Obter dados da sessão do Blogger
            val bloggerData = getBloggerSessionData(token, referer)
            
            // Chamar API batch execute
            val videos = callBloggerBatchApi(bloggerData)
            
            if (videos.isEmpty()) return false
            
            // Timestamp para cpn
            val timestamp = System.currentTimeMillis()
            
            // Processar cada URL
            videos.forEach { (videoUrl, itag) ->
                val quality = itagQualityMap[itag] ?: 480
                
                // Extrair videoId
                val videoId = extractVideoId(videoUrl)
                
                // Gerar cpn
                val cpn = generateCpn(bloggerData, videoId, timestamp)
                
                // Decodificar URL e remover caracteres problemáticos
                val urlBase = decodeUrl(videoUrl)
                val urlLimpa = urlBase.replace("\\&", "&")
                
                // Adicionar parâmetros anti-bot
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
            }
            
            true

        } catch (e: Exception) {
            println("❌ AniTubeExtractor: Erro no Blogger: ${e.message}")
            false
        }
    }

    private suspend fun getBloggerSessionData(token: String, referer: String): BloggerData {
        // Usar cache se disponível (5 minutos)
        if (cachedBloggerData != null && 
            cachedBloggerData?.token == token && 
            System.currentTimeMillis() - lastBloggerRefresh < 300000) {
            return cachedBloggerData!!
        }
        
        // Acessar página do Blogger
        val bloggerResponse = app.get(
            "https://www.blogger.com/video.g?token=$token",
            headers = mapOf(
                "Referer" to referer,
                "User-Agent" to getRandomUserAgent(),
                "sec-ch-ua" to "\"Chromium\";v=\"120\", \"Not)A;Brand\";v=\"99\"",
                "sec-ch-ua-mobile" to "?1",
                "sec-ch-ua-platform" to "\"Android\""
            )
        )
        
        val html = bloggerResponse.text
        
        // Extrair WIZ_global_data
        val wizData = extractWizData(html)
        
        // Extrair nonce
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
            "sec-ch-ua" to "\"Chromium\";v=\"120\", \"Not)A;Brand\";v=\"99\"",
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
        
        // Extrair JSON real do formato do Google
        val jsonData = extractGoogleJson(response)
        
        // Padrão específico para URLs com itag
        val urlPattern = """\"((?:https?:\\/\\/)?[^"]+?googlevideo[^"]+?)\",\[(\d+)\]""".toRegex()
        val urlMatches = urlPattern.findAll(jsonData)
        
        for (match in urlMatches) {
            var url = match.groupValues[1]
            val itag = match.groupValues[2].toIntOrNull() ?: 18
            
            url = decodeUrl(url)
            
            if (!videos.any { it.first == url }) {
                videos.add(Pair(url, itag))
            }
        }
        
        // Fallback: busca por URLs brutas
        if (videos.isEmpty()) {
            val urlPattern2 = """https?:\\?/\\?/[^"'\s]+?googlevideo[^"'\s]+""".toRegex()
            val rawMatches = urlPattern2.findAll(jsonData)
            
            for (match in rawMatches) {
                var url = match.value
                if (!url.startsWith("http")) url = "https://$url"
                
                url = decodeUrl(url)
                val itag = extractItagFromUrl(url)
                
                if (!videos.any { it.first == url }) {
                    videos.add(Pair(url, itag))
                }
            }
        }
        
        // Ordenar por qualidade (melhor primeiro)
        val qualityOrder = listOf(37, 22, 18, 59)
        return videos
            .distinctBy { it.second }
            .sortedBy { qualityOrder.indexOf(it.second) }
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
        } catch (e: Exception) {
        }
        return response
    }

    private fun decodeUnicodeEscapes(text: String): String {
        var result = text
        val pattern = """\\u([0-9a-fA-F]{4})""".toRegex()
        result = pattern.replace(result) { matchResult ->
            val hexCode = matchResult.groupValues[1]
            try {
                hexCode.toInt(16).toChar().toString()
            } catch (e: Exception) {
                "?"
            }
        }
        return result
    }

    private fun decodeUrl(url: String): String {
        var decoded = url
        decoded = decodeUnicodeEscapes(decoded)
        decoded = decoded.replace("\\/", "/")
        decoded = decoded.replace("\\\\", "\\")
        decoded = decoded.replace("\\=", "=")
        decoded = decoded.replace("\\&", "&")  // CORREÇÃO CRÍTICA
        
        if (decoded.endsWith("\\")) {
            decoded = decoded.dropLast(1)
        }
        
        decoded = decoded.trim('"')
        return decoded
    }

    private fun extractItagFromUrl(url: String): Int {
        val patterns = listOf(
            """itag[=?&](\d+)""".toRegex(),
            """itag%3D(\d+)""".toRegex(),
            """itag\\u003d(\d+)""".toRegex()
        )
        
        for (pattern in patterns) {
            pattern.find(url)?.let {
                return it.groupValues[1].toIntOrNull() ?: 18
            }
        }
        
        return when {
            "itag=22" in url -> 22
            "itag=18" in url -> 18
            "itag=37" in url -> 37
            "itag=59" in url -> 59
            else -> 18
        }
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
            "sec-ch-ua" to "\"Chromium\";v=\"120\", \"Not)A;Brand\";v=\"99\"",
            "sec-ch-ua-mobile" to "?1",
            "sec-ch-ua-platform" to "\"Android\"",
            "sec-fetch-dest" to "video",
            "sec-fetch-mode" to "no-cors",
            "sec-fetch-site" to "cross-site",
            "x-client-data" to "COjuygE="
        )
    }

    private suspend fun extractWithWebView(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Seu código existente do WebView
        // ... (mantido igual)
        return false
    }
    
    private suspend fun extractWithDirectHtml(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Seu código existente de fallback
        // ... (mantido igual)
        return false
    }
}
