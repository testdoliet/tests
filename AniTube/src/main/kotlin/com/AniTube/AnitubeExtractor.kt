package com.AniTube

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
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
            val response = app.get(url, headers = mapOf(
                "User-Agent" to getRandomUserAgent(),
                "Referer" to mainUrl
            ))
            
            val doc = Jsoup.parse(response.text)
            
            // PASSO 1: Identificar qual aba está ativa
            val activeTab = doc.selectFirst("div.pagEpiAbasItem.active.current")
            val activeTabName = activeTab?.text()?.trim() ?: "Player 1"
            val activeTabTarget = activeTab?.attr("aba-target") ?: "blog1"
            
            println("📋 Aba ativa: $activeTabName (target: $activeTabTarget)")
            
            // PASSO 2: Encontrar o container da aba ativa
            val activeContainer = doc.selectFirst("div#$activeTabTarget")
            
            if (activeContainer == null) {
                println("❌ Container da aba ativa não encontrado")
                return false
            }
            
            // PASSO 3: Extrair iframe do container ativo
            val iframe = activeContainer.selectFirst("iframe.metaframe")
            
            if (iframe == null) {
                println("❌ Iframe não encontrado no container ativo")
                return false
            }
            
            val iframeSrc = iframe.attr("src")
            println("📦 URL do iframe: $iframeSrc")
            
            // PASSO 4: Verificar tipo de player
            return when {
                // Player FHD/HD - HLS direto
                iframeSrc.contains("videohls.php") || iframeSrc.contains(".m3u8") -> {
                    println("🎬 Detectado player HLS ($activeTabName)")
                    extractHlsVideo(iframeSrc, activeTabName, url, name, callback)
                }
                
                // Player Blogger - precisa de proxy (URL ofuscada do anitube.news)
                iframeSrc.contains("anitube.news/") && !iframeSrc.contains("videohls.php") -> {
                    println("📹 Detectado player via proxy (Blogger) - $activeTabName")
                    extractFromProxy(iframeSrc, activeTabName, url, name, callback)
                }
                
                // Player Blogger direto
                iframeSrc.contains("blogger.com") -> {
                    println("📹 Detectado player Blogger direto - $activeTabName")
                    extractBloggerDirect(iframeSrc, activeTabName, url, name, callback)
                }
                
                else -> {
                    println("⚠️ Tipo de player desconhecido, tentando como HLS")
                    extractHlsVideo(iframeSrc, activeTabName, url, name, callback)
                }
            }
            
        } catch (e: Exception) {
            println("❌ Erro: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    private suspend fun extractHlsVideo(
        iframeSrc: String,
        tabName: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // Extrair URL do HLS do parâmetro d=
            val hlsUrl = if (iframeSrc.contains("videohls.php")) {
                // Formato: https://api.anivideo.net/videohls.php?d=URL_ENCONDADA
                val match = Regex("""[?&]d=([^&]+)""").find(iframeSrc)
                if (match != null) {
                    val encodedUrl = match.groupValues[1]
                    java.net.URLDecoder.decode(encodedUrl, "UTF-8")
                } else {
                    iframeSrc
                }
            } else {
                iframeSrc
            }
            
            println("✅ URL HLS extraída: ${hlsUrl.take(100)}...")
            
            // Determinar qualidade baseado no nome da aba
            val quality = when {
                tabName.contains("FHD", ignoreCase = true) -> 1080
                tabName.contains("HD", ignoreCase = true) -> 720
                tabName.contains("SD", ignoreCase = true) -> 480
                else -> 720
            }
            
            val qualityLabel = when(quality) {
                1080 -> "FHD"
                720 -> "HD"
                480 -> "SD"
                else -> "SD"
            }
            
            // CORREÇÃO: Usar a sintaxe correta do M3u8Helper
            val m3u8Links = M3u8Helper.generateM3u8(
                source = "AniTube - $tabName",
                streamUrl = hlsUrl,
                referer = referer,
                headers = mapOf(
                    "Referer" to referer,
                    "User-Agent" to getRandomUserAgent(),
                    "Accept" to "*/*"
                )
            )
            
            m3u8Links.forEach { m3u8Link ->
                callback(
                    newExtractorLink(
                        source = "AniTube",
                        name = "$name ($qualityLabel)",
                        url = m3u8Link.url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = referer
                        this.quality = quality
                        this.headers = mapOf(
                            "Referer" to referer,
                            "User-Agent" to getRandomUserAgent(),
                            "Accept" to "*/*"
                        )
                    }
                )
                println("✅ Link HLS adicionado: $qualityLabel")
            }
            
            true
            
        } catch (e: Exception) {
            println("❌ Erro ao extrair HLS: ${e.message}")
            false
        }
    }
    
    private suspend fun extractFromProxy(
        proxyUrl: String,
        tabName: String,
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
        
        // Fazer requisição para o proxy
        val proxyResponse = app.get(
            fullProxyUrl,
            headers = mapOf(
                "User-Agent" to getRandomUserAgent(),
                "Referer" to referer,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "pt-BR"
            )
        )
        
        val proxyHtml = proxyResponse.text
        val proxyDoc = Jsoup.parse(proxyHtml)
        
        // Extrair iframe do Blogger
        val bloggerIframe = proxyDoc.selectFirst("iframe[src*='blogger.com/video.g']")
        
        if (bloggerIframe == null) {
            println("❌ Iframe do Blogger não encontrado na resposta do proxy")
            return false
        }
        
        val bloggerUrl = bloggerIframe.attr("src")
        println("✅ URL do Blogger encontrada no proxy: $bloggerUrl")
        
        return extractBloggerDirect(bloggerUrl, tabName, referer, name, callback)
    }
    
    private suspend fun extractBloggerDirect(
        bloggerUrl: String,
        tabName: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Extrair token da URL
        val token = extractTokenFromUrl(bloggerUrl)
        if (token == null) {
            println("❌ Token não encontrado na URL")
            return false
        }
        
        println("✅ Token extraído: ${token.take(20)}...")
        
        // Obter dados da sessão do Blogger
        val bloggerData = getBloggerSessionData(token, bloggerUrl)
        
        // Chamar API batch execute
        val videos = callBloggerBatchApi(bloggerData)
        
        if (videos.isEmpty()) {
            println("❌ Nenhum vídeo encontrado na API")
            return false
        }
        
        println("✅ Encontradas ${videos.size} URLs de vídeo!")
        
        val timestamp = System.currentTimeMillis()
        
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
        // Usar cache se disponível (5 minutos)
        if (cachedBloggerData != null && 
            cachedBloggerData?.token == token && 
            System.currentTimeMillis() - lastBloggerRefresh < 300000) {
            println("📋 Usando dados de sessão em cache")
            return cachedBloggerData!!
        }
        
        println("📡 Acessando Blogger para obter dados da sessão...")
        
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
        
        println("📋 Dados da sessão extraídos:")
        println("   f.sid: ${data.fSid}")
        println("   bl: ${data.bl}")
        println("   nonce: ${nonce.take(20)}...")
        
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
        
        println("📡 Chamando API batch execute...")
        
        val response = app.post(
            url = urlWithParams,
            headers = headers,
            data = body
        )
        
        println("✅ Resposta da API recebida, status: ${response.code}")
        println("📄 Tamanho da resposta: ${response.text.length} bytes")
        
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
                println("   📹 URL encontrada: itag=$itag")
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
                    println("   📹 URL bruta encontrada: itag=$itag")
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

