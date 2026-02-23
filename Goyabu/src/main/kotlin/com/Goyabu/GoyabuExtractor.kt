package com.Goyabu

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.Jsoup
import java.net.URI

object GoyabuExtractor {
    private val itagQualityMap = mapOf(
        18 to 360,   22 to 720,   37 to 1080,  59 to 480,
        133 to 240,  134 to 360,  135 to 480,  136 to 720,
        137 to 1080, 160 to 144,  242 to 240,  243 to 360,
        244 to 480,  247 to 720,  248 to 1080
    )

    suspend fun extractVideoLinks(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🚀 INICIANDO EXTRAÇÃO PARA: $url")
        println("📺 Nome do vídeo: $name")
        
        return try {
            println("📡 Fazendo requisição GET para: $url")
            
            val pageResponse = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Referer" to "https://goyabu.io/",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
                ),
                timeout = 30
            )

            println("✅ Resposta recebida - Status: ${pageResponse.code}, Tamanho: ${pageResponse.text.length} bytes")
            
            val html = pageResponse.text
            val doc = Jsoup.parse(html)
            
            println("📊 Estatísticas da página:")
            println("   - Scripts encontrados: ${doc.select("script").size}")

            // TENTAR PRIMEIRO O NOVO MÉTODO DO BLOGGER (BATCH EXECUTE)
            println("🔍 Tentando extrair URLs via Blogger Batch Execute...")
            if (extractBloggerUrls(doc, url, name, callback)) {
                println("✅ URLs do Blogger extraídas com sucesso!")
                return true
            }

            // FALLBACK PARA MÉTODOS ANTIGOS
            println("🔍 Tentando extrair URLs do JWPlayer...")
            if (extractJwPlayerUrls(doc, url, name, callback)) {
                println("✅ URLs do JWPlayer extraídas com sucesso!")
                return true
            }

            println("🔍 Tentando extrair URLs M3U8...")
            if (extractM3U8Urls(doc, url, name, callback)) {
                println("✅ URLs M3U8 extraídas com sucesso!")
                return true
            }

            println("❌ Nenhum vídeo encontrado na página!")
            false

        } catch (e: Exception) {
            println("❌ EXCEÇÃO DURANTE EXTRAÇÃO: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    // ==================== NOVO MÉTODO BLOGGER BATCH EXECUTE ====================
    
    private suspend fun extractBloggerUrls(
        doc: org.jsoup.nodes.Document,
        originalUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🎯 Iniciando extração Blogger (via batch execute)...")
        
        // PASSO 1: Extrair o token da página
        println("🔍 Procurando token do Blogger nos scripts...")
        val token = extractBloggerToken(doc)
        
        if (token == null) {
            println("❌ Token do Blogger não encontrado!")
            return false
        }
        
        println("✅ Token encontrado: $token")
        
        // PASSO 2: Parâmetros FIXOS do Blogger
        val f_sid = "-7535563745894756252"
        val bl = "boq_bloggeruiserver_20260218.01_p0"
        val reqid = (10000..99999).random()
        
        println("📋 Parâmetros da API:")
        println("   - f.sid: $f_sid")
        println("   - bl: $bl")
        println("   - _reqid: $reqid")
        
        // PASSO 3: Chamar API batch execute
        val videos = callBloggerBatchApi(token, f_sid, bl, reqid)
        
        if (videos.isEmpty()) {
            println("❌ Nenhum vídeo encontrado na resposta da API")
            return false
        }
        
        println("✅ Encontrados ${videos.size} links de vídeo!")
        
        // PASSO 4: Processar cada URL encontrada
        var found = false
        videos.forEach { (videoUrl, itag) ->
            val quality = itagQualityMap[itag] ?: 360
            val qualityLabel = getQualityLabel(quality)
            
            println("🎬 Processando vídeo - Qualidade: ${quality}p (itag: $itag)")
            
            val extractorLink = newExtractorLink(
                source = "Goyabu",
                name = "$name ($qualityLabel)",
                url = videoUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = "https://www.blogger.com/"
                this.quality = quality
                this.headers = mapOf(
                    "Referer" to "https://www.blogger.com/",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Origin" to "https://www.blogger.com"
                )
            }
            
            println("✅ Link adicionado: $qualityLabel")
            callback(extractorLink)
            found = true
        }
        
        return found
    }

    private fun extractBloggerToken(doc: org.jsoup.nodes.Document): String? {
        val pattern = """video\.g\?token=([a-zA-Z0-9_\-]+)""".toRegex()
        
        doc.select("script").forEach { script ->
            val match = pattern.find(script.html())
            if (match != null) {
                val token = match.groupValues[1]
                println("   Token extraído do script: $token")
                return token
            }
        }
        
        doc.select("iframe[src*='video.g']").forEach { iframe ->
            val src = iframe.attr("src")
            val match = pattern.find(src)
            if (match != null) {
                val token = match.groupValues[1]
                println("   Token extraído do iframe: $token")
                return token
            }
        }
        
        return null
    }

    private suspend fun callBloggerBatchApi(
        token: String,
        f_sid: String,
        bl: String,
        reqid: Int
    ): List<Pair<String, Int>> {
        
        val apiUrl = "https://www.blogger.com/_/BloggerVideoPlayerUi/data/batchexecute"
        
        val headers = mapOf(
            "authority" to "www.blogger.com",
            "accept" to "*/*",
            "accept-language" to "pt-BR",
            "content-type" to "application/x-www-form-urlencoded;charset=UTF-8",
            "origin" to "https://www.blogger.com",
            "referer" to "https://www.blogger.com/",
            "sec-ch-ua" to "\"Chromium\";v=\"127\", \"Not)A;Brand\";v=\"99\"",
            "sec-ch-ua-mobile" to "?1",
            "sec-ch-ua-platform" to "\"Android\"",
            "user-agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
            "x-same-domain" to "1"
        )
        
        val urlWithParams = "$apiUrl?rpcids=WcwnYd&source-path=%2Fvideo.g&f.sid=$f_sid&bl=$bl&hl=pt-BR&_reqid=$reqid&rt=c"
        
        // 🔥 CORREÇÃO AQUI: body como Map, não String
        val body = mapOf(
            "f.req" to "%5B%5B%5B%22WcwnYd%22%2C%22%5B%5C%22$token%5C%22%2C%5C%22%5C%22%2C0%5D%22%2Cnull%2C%22generic%22%5D%5D%5D"
        )
        
        println("📡 Chamando API batch execute...")
        println("   URL: $urlWithParams")
        
        val response = app.post(
            urlWithParams,
            headers = headers,
            data = body  // Agora é um Map, não String
        )
        
        println("✅ Resposta da API - Status: ${response.code}, Tamanho: ${response.text.length} bytes")
        
        if (!response.isSuccessful) {
            println("❌ API retornou erro: ${response.code}")
            return emptyList()
        }
        
        return extractVideoUrlsFromResponse(response.text)
    }

    private fun extractVideoUrlsFromResponse(response: String): List<Pair<String, Int>> {
        val videos = mutableListOf<Pair<String, Int>>()
        
        val urlPattern = """(https?:\\/\\/[^"\\]+\.googlevideo\.com\\/[^"\\]+videoplayback[^"\\]+)""".toRegex()
        val matches = urlPattern.findAll(response)
        
        var count = 0
        matches.forEach { match ->
            count++
            var url = match.value
                .replace("\\u003d", "=")
                .replace("\\/", "/")
                .replace("\\", "")
            
            val itagPattern = """itag[=?&](\d+)""".toRegex()
            val itag = itagPattern.find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 18
            
            url = url.trim()
            videos.add(Pair(url, itag))
        }
        
        println("📊 Total de URLs encontradas: $count")
        return videos.distinctBy { it.second }
    }

    // ==================== MÉTODOS EXISTENTES (NÃO MEXI) ====================

    private suspend fun extractJwPlayerUrls(
        doc: org.jsoup.nodes.Document,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🎯 Iniciando extração JWPlayer...")
        var found = false

        println("🔍 Procurando elemento .jw-video...")
        val jwVideoElement = doc.selectFirst(".jw-video.jw-reset[src]")
        jwVideoElement?.attr("src")?.let { videoUrl ->
            println("✅ Elemento .jw-video encontrado: ${videoUrl.take(100)}...")
            if (isDirectVideoUrl(videoUrl)) {
                if (processDirectVideoUrl(videoUrl, referer, name, callback)) {
                    println("✅ URL do JWVideo processada com sucesso")
                    found = true
                }
            }
        } ?: println("⚠ Nenhum elemento .jw-video encontrado")

        if (!found) {
            println("🔍 Procurando tags <video> com googlevideo...")
            val videoElements = doc.select("video[src*='googlevideo.com']")
            println("🎬 Encontradas ${videoElements.size} tags <video> com googlevideo")
            
            videoElements.forEachIndexed { index, element ->
                val src = element.attr("src")
                if (src.isNotBlank() && isDirectVideoUrl(src)) {
                    if (processDirectVideoUrl(src, referer, name, callback)) {
                        println("✅ Video $index processado com sucesso")
                        found = true
                    }
                }
            }
        }

        if (!found) {
            println("🔍 Procurando URLs de video em scripts...")
            val scripts = doc.select("script")
            println("📜 Analisando ${scripts.size} scripts...")
            
            val directVideoPattern = Regex("""https?://[^"'\s]*\.googlevideo\.com/[^"'\s]*videoplayback[^"'\s]*""")

            scripts.forEachIndexed { index, script ->
                val scriptContent = script.html()
                if (scriptContent.contains("googlevideo")) {
                    val matches = directVideoPattern.findAll(scriptContent)
                    matches.forEach { match ->
                        val url = match.value
                        if (isDirectVideoUrl(url) && !url.contains("m3u8")) {
                            if (processDirectVideoUrl(url, referer, name, callback)) {
                                println("✅ URL processada com sucesso")
                                found = true
                            }
                        }
                    }
                }
            }
        }

        println("🏁 Extração JWPlayer finalizada. Encontrou vídeos: $found")
        return found
    }

    private fun isDirectVideoUrl(url: String): Boolean {
        return url.contains("googlevideo.com") && 
               url.contains("videoplayback") && 
               !url.contains("m3u8")
    }

    private suspend fun processDirectVideoUrl(
        videoUrl: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val itag = extractItagFromUrl(videoUrl)
            val quality = itagQualityMap[itag] ?: 720 
            val qualityLabel = getQualityLabel(quality)

            val extractorLink = newExtractorLink(
                source = "Goyabu",
                name = "$name ($qualityLabel)",
                url = videoUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = quality
                this.headers = mapOf(
                    "Referer" to referer,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Origin" to (URI(referer).host?.let { "https://$it" } ?: "https://goyabu.io")
                )
            }

            callback(extractorLink)
            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun extractM3U8Urls(
        doc: org.jsoup.nodes.Document,
        originalUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🎯 Iniciando extração M3U8...")
        val scripts = doc.select("script")
        var found = false

        println("📜 Analisando ${scripts.size} scripts em busca de URLs M3U8...")

        scripts.forEachIndexed { index, script ->
            val scriptContent = script.html()
            
            if (scriptContent.contains("m3u8") || scriptContent.contains("anivideo")) {
                val patterns = listOf(
                    """api\.anivideo\.net/videohls\.php[^"'\s]*""".toRegex(),
                    """https?://[^"'\s]*\.m3u8[^"'\s]*""".toRegex(),
                    """"(?:file|video|src|url)"\s*:\s*"([^"']*api\.anivideo[^"']+)"""".toRegex()
                )

                patterns.forEach { pattern ->
                    val matches = pattern.findAll(scriptContent)
                    matches.forEach { match ->
                        var videoUrl = match.value

                        if (pattern.pattern.contains("""["']\s*:\s*["']""")) {
                            val group = match.groupValues.getOrNull(1)
                            if (group != null) videoUrl = group
                        }

                        if (isM3U8Url(videoUrl)) {
                            if (processM3U8Url(videoUrl, originalUrl, name, callback)) {
                                found = true
                            }
                        }
                    }
                }
            }
        }

        val elements = doc.select("""
            [src*=".m3u8"],
            [data-src*=".m3u8"],
            [href*=".m3u8"]
        """.trimIndent())

        elements.forEach { element ->
            val m3u8Url = element.attr("src") 
                ?: element.attr("data-src") 
                ?: element.attr("href")

            if (m3u8Url.isNotBlank() && isM3U8Url(m3u8Url)) {
                if (processM3U8Url(m3u8Url, originalUrl, name, callback)) {
                    found = true
                }
            }
        }

        println("🏁 Extração M3U8 finalizada. Encontrou vídeos: $found")
        return found
    }

    private suspend fun processM3U8Url(
        m3u8Url: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            var url = cleanUrl(m3u8Url)

            if (url.contains("api.anivideo.net/videohls.php")) {
                val dParamRegex = """[?&]d=([^&]+)""".toRegex()
                val match = dParamRegex.find(url)

                match?.let {
                    val encodedUrl = it.groupValues[1]
                    try {
                        url = java.net.URLDecoder.decode(encodedUrl, "UTF-8")
                    } catch (e: Exception) {
                        // Ignora erro
                    }
                }
            }

            if (!url.contains(".m3u8") && !url.contains("m3u8")) {
                return false
            }

            val quality = determineM3U8Quality(url)
            val qualityLabel = getQualityLabel(quality)

            val extractorLink = newExtractorLink(
                source = "Goyabu",
                name = "$name ($qualityLabel) [HLS]",
                url = url,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = referer
                this.quality = quality
                this.headers = mapOf(
                    "Referer" to referer,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                )
            }

            callback(extractorLink)
            return true

        } catch (e: Exception) {
            return false
        }
    }

    private fun cleanUrl(url: String): String {
        var cleaned = url.trim()

        if (cleaned.startsWith("\"url\":\"")) {
            cleaned = cleaned.removePrefix("\"url\":\"")
        }
        if (cleaned.startsWith("\"")) {
            cleaned = cleaned.removePrefix("\"")
        }
        if (cleaned.endsWith("\"")) {
            cleaned = cleaned.removeSuffix("\"")
        }

        return cleaned.replace("\\/", "/")
    }

    private fun determineM3U8Quality(url: String): Int {
        val urlLower = url.lowercase()
        return when {
            urlLower.contains("1080") || urlLower.contains("fhd") -> 1080
            urlLower.contains("720") || urlLower.contains("hd") -> 720
            urlLower.contains("480") -> 480
            urlLower.contains("360") -> 360
            else -> 720
        }
    }

    private fun getQualityLabel(quality: Int): String {
        return when {
            quality >= 2160 -> "4K"
            quality >= 1440 -> "QHD"
            quality >= 1080 -> "FHD"
            quality >= 720 -> "HD"
            quality >= 480 -> "SD"
            else -> "SD"
        }
    }

    private fun extractItagFromUrl(url: String): Int {
        val itagPattern = """[?&]itag=(\d+)""".toRegex()
        val match = itagPattern.find(url)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 18
    }

    private fun isM3U8Url(url: String): Boolean {
        return url.contains("m3u8") ||
               url.contains(".m3u8") ||
               url.contains("anivideo.net")
    }
}
