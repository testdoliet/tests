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
        println("🔍 AniTubeExtractor: INICIANDO extração para: $url")
        println("🔍 AniTubeExtractor: mainUrl: $mainUrl")
        println("🔍 AniTubeExtractor: name: $name")
        
        return try {
            val response = app.get(url, headers = mapOf(
                "User-Agent" to getRandomUserAgent(),
                "Referer" to mainUrl
            ))
            
            println("📥 Resposta recebida: status=${response.code}, tamanho=${response.text.length}")
            
            val doc = Jsoup.parse(response.text)
            
            // Verificar todas as abas
            val abas = doc.select("div.pagEpiAbasItem")
            println("📋 Total de abas encontradas: ${abas.size}")
            
            if (abas.isEmpty()) {
                println("❌ Nenhuma aba encontrada!")
                // Tentar encontrar iframe diretamente
                val directIframe = doc.selectFirst("iframe.metaframe")
                if (directIframe != null) {
                    println("✅ Iframe direto encontrado: ${directIframe.attr("src")}")
                }
            }
            
            var anySuccess = false
            
            for ((index, aba) in abas.withIndex()) {
                val tabName = aba.text().trim()
                val tabTarget = aba.attr("aba-target")
                val isActive = aba.hasClass("active") && aba.hasClass("current")
                
                println("\n--- ABA ${index + 1} ---")
                println("   Nome: $tabName")
                println("   Target: $tabTarget")
                println("   Ativa: $isActive")
                
                // Encontrar o container correspondente
                val container = doc.selectFirst("div#$tabTarget")
                if (container == null) {
                    println("   ❌ Container não encontrado: div#$tabTarget")
                    continue
                }
                
                val containerStyle = container.attr("style")
                println("   Container style: $containerStyle")
                
                val iframe = container.selectFirst("iframe.metaframe")
                if (iframe == null) {
                    println("   ❌ Iframe não encontrado no container")
                    continue
                }
                
                val iframeSrc = iframe.attr("src")
                println("   📦 Iframe src: $iframeSrc")
                
                // Processar TODAS as abas, não apenas a ativa
                println("   🔄 Processando aba...")
                
                val success = when {
                    // Player FHD/HD - HLS direto
                    iframeSrc.contains("videohls.php") || iframeSrc.contains(".m3u8") -> {
                        println("   ✅ Detectado player HLS")
                        extractHlsVideo(iframeSrc, tabName, url, name, callback)
                    }
                    
                    // Player via proxy (URL ofuscada) - Blogger
                    iframeSrc.contains("anitube.news/") && !iframeSrc.contains("videohls.php") -> {
                        println("   ✅ Detectado player via proxy (Blogger)")
                        extractFromProxy(iframeSrc, tabName, url, name, callback)
                    }
                    
                    // Player Blogger direto
                    iframeSrc.contains("blogger.com") -> {
                        println("   ✅ Detectado player Blogger direto")
                        extractBloggerDirect(iframeSrc, tabName, url, name, callback)
                    }
                    
                    else -> {
                        println("   ❌ Tipo de player desconhecido")
                        false
                    }
                }
                
                if (success) {
                    println("   ✅ Extração bem-sucedida para $tabName")
                    anySuccess = true
                } else {
                    println("   ❌ Falha na extração para $tabName")
                }
            }
            
            if (!anySuccess) {
                println("❌ NENHUMA aba teve sucesso na extração")
                
                // Fallback: procurar qualquer iframe
                println("🔍 Buscando qualquer iframe como fallback...")
                val anyIframe = doc.selectFirst("iframe")
                if (anyIframe != null) {
                    println("   Iframe encontrado: ${anyIframe.attr("src")}")
                } else {
                    println("   Nenhum iframe encontrado")
                }
            }
            
            anySuccess
            
        } catch (e: Exception) {
            println("❌ ERRO: ${e.message}")
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
        println("   📹 extractHlsVideo iniciado")
        println("      iframeSrc: $iframeSrc")
        println("      tabName: $tabName")
        
        return try {
            val hlsUrl = if (iframeSrc.contains("videohls.php")) {
                println("      Formato videohls.php detectado")
                val match = Regex("""[?&]d=([^&]+)""").find(iframeSrc)
                if (match != null) {
                    val encodedUrl = match.groupValues[1]
                    val decoded = java.net.URLDecoder.decode(encodedUrl, "UTF-8")
                    println("      URL decodificada: $decoded")
                    decoded
                } else {
                    println("      Nenhum parâmetro d= encontrado")
                    iframeSrc
                }
            } else {
                println("      Formato direto")
                iframeSrc
            }
            
            println("      URL HLS final: $hlsUrl")
            
            val quality = when {
                tabName.contains("FHD", ignoreCase = true) -> 1080
                tabName.contains("HD", ignoreCase = true) -> 720
                tabName.contains("SD", ignoreCase = true) -> 480
                else -> 720
            }
            
            println("      Qualidade determinada: ${quality}p")
            
            val qualityLabel = when(quality) {
                1080 -> "FHD"
                720 -> "HD"
                480 -> "SD"
                else -> "SD"
            }
            
            println("      Gerando links M3U8...")
            
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
            
            println("      Links M3U8 gerados: ${m3u8Links.size}")
            
            m3u8Links.forEachIndexed { i, m3u8Link ->
                println("      Link ${i+1}: ${m3u8Link.url.take(100)}...")
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
            }
            
            println("      ✅ HLS extraído com sucesso")
            true
            
        } catch (e: Exception) {
            println("      ❌ Erro no HLS: ${e.message}")
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
        println("   📹 extractFromProxy iniciado")
        println("      proxyUrl: $proxyUrl")
        
        val fullProxyUrl = if (proxyUrl.startsWith("http")) {
            proxyUrl
        } else {
            "https://www.anitube.news$proxyUrl"
        }
        
        println("      fullProxyUrl: $fullProxyUrl")
        
        val proxyResponse = app.get(
            fullProxyUrl,
            headers = mapOf(
                "User-Agent" to getRandomUserAgent(),
                "Referer" to referer,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "pt-BR"
            )
        )
        
        println("      Proxy response: status=${proxyResponse.code}, tamanho=${proxyResponse.text.length}")
        
        val proxyHtml = proxyResponse.text
        val proxyDoc = Jsoup.parse(proxyHtml)
        
        val bloggerIframe = proxyDoc.selectFirst("iframe[src*='blogger.com/video.g']")
        
        if (bloggerIframe == null) {
            println("      ❌ Iframe do Blogger não encontrado no proxy")
            return false
        }
        
        val bloggerUrl = bloggerIframe.attr("src")
        println("      ✅ URL do Blogger encontrada: $bloggerUrl")
        
        return extractBloggerDirect(bloggerUrl, tabName, referer, name, callback)
    }
    
    private suspend fun extractBloggerDirect(
        bloggerUrl: String,
        tabName: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("   📹 extractBloggerDirect iniciado")
        println("      bloggerUrl: $bloggerUrl")
        
        val token = extractTokenFromUrl(bloggerUrl)
        if (token == null) {
            println("      ❌ Token não encontrado na URL")
            return false
        }
        
        println("      ✅ Token extraído: ${token.take(20)}...")
        
        val apiUrl = "https://www.blogger.com/_/BloggerVideoPlayerUi/data/batchexecute"
        val reqid = (10000..99999).random()
        val fSid = "-7535563745894756252"
        val bl = "boq_bloggeruiserver_20260223.02_p0"
        
        println("      Chamando API batch execute...")
        println("      reqid: $reqid")
        println("      f.sid: $fSid")
        println("      bl: $bl")
        
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
        
        val urlWithParams = "$apiUrl?rpcids=WcwnYd&source-path=%2Fvideo.g&f.sid=$fSid&bl=$bl&hl=pt-BR&_reqid=$reqid&rt=c"
        
        val body = mapOf(
            "f.req" to "%5B%5B%5B%22WcwnYd%22%2C%22%5B%5C%22$token%5C%22%2C%5C%22%5C%22%2C0%5D%22%2Cnull%2C%22generic%22%5D%5D%5D"
        )
        
        val response = app.post(
            url = urlWithParams,
            headers = headers,
            data = body
        )
        
        println("      API response: status=${response.code}, tamanho=${response.text.length}")
        
        if (response.text.length < 100) {
            println("      ⚠️ Resposta muito curta: ${response.text}")
        }
        
        val videos = extractVideoUrlsFromResponse(response.text)
        
        if (videos.isEmpty()) {
            println("      ❌ Nenhum vídeo encontrado na resposta da API")
            return false
        }
        
        println("      ✅ ${videos.size} URLs de vídeo encontradas")
        
        val timestamp = System.currentTimeMillis()
        println("      Timestamp para cpn: $timestamp")
        
        videos.forEachIndexed { i, (videoUrl, itag) ->
            println("\n      --- Vídeo ${i+1} ---")
            println("      itag: $itag")
            println("      URL bruta: ${videoUrl.take(100)}...")
            
            val quality = itagQualityMap[itag] ?: 480
            val videoId = extractVideoId(videoUrl)
            println("      videoId: $videoId")
            println("      qualidade: ${quality}p")
            
            val cpn = generateCpn(token, videoId, timestamp)
            println("      cpn gerado: $cpn")
            
            val urlBase = decodeUrl(videoUrl)
            println("      URL decodificada: ${urlBase.take(100)}...")
            
            val urlLimpa = urlBase.replace("\\&", "&")
            println("      URL limpa: ${urlLimpa.take(100)}...")
            
            val urlFinal = buildString {
                append(urlLimpa)
                if (urlLimpa.contains("?")) {
                    append("&cpn=$cpn&c=WEB_EMBEDDED_PLAYER&cver=1.20260224.08.00")
                } else {
                    append("?cpn=$cpn&c=WEB_EMBEDDED_PLAYER&cver=1.20260224.08.00")
                }
            }
            
            println("      URL final: ${urlFinal.take(100)}...")
            
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
                    this.headers = mapOf(
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
            )
            println("      ✅ Link adicionado")
        }
        
        return true
    }

    private fun extractVideoUrlsFromResponse(response: String): List<Pair<String, Int>> {
        println("      🔍 extractVideoUrlsFromResponse")
        println("      Resposta (primeiros 200 chars): ${response.take(200)}")
        
        val videos = mutableListOf<Pair<String, Int>>()
        val jsonData = extractGoogleJson(response)
        
        if (jsonData != response) {
            println("      ✅ JSON extraído do wrapper, tamanho: ${jsonData.length}")
        }
        
        val urlPattern = """\"((?:https?:\\/\\/)?[^"]+?googlevideo[^"]+?)\",\[(\d+)\]""".toRegex()
        val urlMatches = urlPattern.findAll(jsonData)
        
        var count = 0
        for (match in urlMatches) {
            count++
            var url = match.groupValues[1]
            val itag = match.groupValues[2].toIntOrNull() ?: 18
            println("      URL ${count}: itag=$itag")
            url = decodeUrl(url)
            videos.add(Pair(url, itag))
        }
        
        if (count == 0) {
            println("      ⚠️ Nenhuma URL encontrada no formato padrão")
            
            val urlPattern2 = """https?:\\?/\\?/[^"'\s]+?googlevideo[^"'\s]+""".toRegex()
            val rawMatches = urlPattern2.findAll(jsonData)
            
            var rawCount = 0
            for (match in rawMatches) {
                rawCount++
                var url = match.value
                if (!url.startsWith("http")) url = "https://$url"
                url = decodeUrl(url)
                val itag = extractItagFromUrl(url)
                println("      URL bruta ${rawCount}: itag=$itag")
                videos.add(Pair(url, itag))
            }
            
            if (rawCount == 0) {
                println("      ❌ Nenhuma URL encontrada")
            }
        }
        
        val qualityOrder = listOf(37, 22, 18, 59)
        val result = videos.distinctBy { it.second }.sortedBy { qualityOrder.indexOf(it.second) }
        println("      Total de URLs únicas: ${result.size}")
        
        return result
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
            println("      ⚠️ Erro ao extrair JSON: ${e.message}")
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
        decoded = decoded.replace("\\&", "&")
        if (decoded.endsWith("\\")) decoded = decoded.dropLast(1)
        return decoded.trim('"')
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

    private fun extractTokenFromUrl(url: String): String? {
        val pattern = """token=([a-zA-Z0-9_\-]+)""".toRegex()
        return pattern.find(url)?.groupValues?.get(1)
    }

    private fun generateCpn(token: String, videoId: String, timestamp: Long): String {
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

    private fun generateRandomString(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length).map { chars.random() }.joinToString("")
    }
}
