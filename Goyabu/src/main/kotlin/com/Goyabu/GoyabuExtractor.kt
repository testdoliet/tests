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

    // Cores ANSI para console
    private const val RESET = "\u001B[0m"
    private const val RED = "\u001B[31m"
    private const val GREEN = "\u001B[32m"
    private const val YELLOW = "\u001B[33m"
    private const val BLUE = "\u001B[34m"
    private const val PURPLE = "\u001B[35m"
    private const val CYAN = "\u001B[36m"
    private const val BOLD = "\u001B[1m"

    private fun debugInfo(message: String) {
        println("$CYAN$BOLD[INFO]${RESET} $message")
    }

    private fun debugSuccess(message: String) {
        println("$GREEN$BOLD[✓ SUCESSO]${RESET} $message")
    }

    private fun debugWarning(message: String) {
        println("$YELLOW$BOLD[⚠ AVISO]${RESET} $message")
    }

    private fun debugError(message: String) {
        println("$RED$BOLD[✗ ERRO]${RESET} $message")
    }

    private fun debugExtractor(message: String) {
        println("$PURPLE$BOLD[🔌 EXTRACTOR]${RESET} $message")
    }

    private fun debugNetwork(message: String) {
        println("$BLUE$BOLD[🌐 NETWORK]${RESET} $message")
    }

    suspend fun extractVideoLinks(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        debugExtractor("${BOLD}🚀 INICIANDO EXTRAÇÃO PARA: $url${RESET}")
        debugInfo("📺 Nome do vídeo: $name")
        
        return try {
            debugNetwork("📡 Fazendo requisição GET para: $url")
            debugNetwork("🔧 Headers: User-Agent=Mozilla/5.0, Referer=https://goyabu.io/")
            
            val pageResponse = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Referer" to "https://goyabu.io/",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
                ),
                timeout = 30
            )

            debugNetwork("✅ Resposta recebida - Status: ${pageResponse.code}, Tamanho: ${pageResponse.text.length} bytes")
            
            if (pageResponse.isSuccessful) {
                debugSuccess("Requisição bem sucedida! Código: ${pageResponse.code}")
            } else {
                debugError("Requisição falhou! Código: ${pageResponse.code}")
            }

            val html = pageResponse.text
            val doc = Jsoup.parse(html)
            
            debugInfo("📄 HTML parseado com sucesso. Tamanho do documento: ${doc.text().length} caracteres")
            
            // Contar elementos relevantes
            val scriptCount = doc.select("script").size
            val videoCount = doc.select("video").size
            val iframeCount = doc.select("iframe").size
            
            debugInfo("📊 Estatísticas da página:")
            debugInfo("   - Scripts encontrados: $scriptCount")
            debugInfo("   - Tags <video>: $videoCount")
            debugInfo("   - Iframes: $iframeCount")

            debugExtractor("🔍 Tentando extrair URLs do JWPlayer...")
            if (extractJwPlayerUrls(doc, url, name, callback)) {
                debugSuccess("✅ URLs do JWPlayer extraídas com sucesso!")
                return true
            } else {
                debugWarning("⚠ Nenhuma URL do JWPlayer encontrada")
            }

            debugExtractor("🔍 Tentando extrair URLs M3U8...")
            if (extractM3U8Urls(doc, url, name, callback)) {
                debugSuccess("✅ URLs M3U8 extraídas com sucesso!")
                return true
            } else {
                debugWarning("⚠ Nenhuma URL M3U8 encontrada")
            }

            debugExtractor("🔍 Tentando extrair URLs do Blogger...")
            if (extractBloggerUrls(doc, url, name, callback)) {
                debugSuccess("✅ URLs do Blogger extraídas com sucesso!")
                return true
            } else {
                debugWarning("⚠ Nenhuma URL do Blogger encontrada")
            }

            debugError("❌ Nenhum vídeo encontrado na página!")
            false

        } catch (e: Exception) {
            debugError("❌ EXCEÇÃO DURANTE EXTRAÇÃO: ${e.message}")
            debugError("📋 Stack trace:")
            e.stackTrace.forEach { 
                debugError("   at $it")
            }
            false
        }
    }

    private suspend fun extractJwPlayerUrls(
        doc: org.jsoup.nodes.Document,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        debugInfo("🎯 Iniciando extração JWPlayer...")
        var found = false

        debugInfo("🔍 Procurando elemento .jw-video...")
        val jwVideoElement = doc.selectFirst(".jw-video.jw-reset[src]")
        jwVideoElement?.attr("src")?.let { videoUrl ->
            debugInfo("✅ Elemento .jw-video encontrado: ${videoUrl.take(100)}...")
            if (isDirectVideoUrl(videoUrl)) {
                debugInfo("🔄 URL direta detectada, processando...")
                if (processDirectVideoUrl(videoUrl, referer, name, callback)) {
                    debugSuccess("URL do JWVideo processada com sucesso")
                    found = true
                } else {
                    debugError("Falha ao processar URL do JWVideo")
                }
            } else {
                debugWarning("URL do JWVideo não é uma URL direta válida: ${videoUrl.take(100)}")
            }
        } ?: debugWarning("Nenhum elemento .jw-video encontrado")

        if (!found) {
            debugInfo("🔍 Procurando tags <video> com googlevideo...")
            val videoElements = doc.select("video[src*='googlevideo.com']")
            debugInfo("🎬 Encontradas ${videoElements.size} tags <video> com googlevideo")
            
            videoElements.forEachIndexed { index, element ->
                val src = element.attr("src")
                debugInfo("   Video $index: $src")
                if (src.isNotBlank() && isDirectVideoUrl(src)) {
                    debugInfo("🔄 Processando video $index...")
                    if (processDirectVideoUrl(src, referer, name, callback)) {
                        debugSuccess("Video $index processado com sucesso")
                        found = true
                    }
                }
            }
        }

        if (!found) {
            debugInfo("🔍 Procurando URLs de video em scripts...")
            val scripts = doc.select("script")
            debugInfo("📜 Analisando ${scripts.size} scripts...")
            
            val directVideoPattern = Regex("""https?://[^"'\s]*\.googlevideo\.com/[^"'\s]*videoplayback[^"'\s]*""")

            scripts.forEachIndexed { index, script ->
                val scriptContent = script.html()
                if (scriptContent.contains("googlevideo")) {
                    debugInfo("   Script $index contém 'googlevideo'")
                    val matches = directVideoPattern.findAll(scriptContent)
                    val matchCount = matches.count()
                    
                    if (matchCount > 0) {
                        debugInfo("   Encontradas $matchCount correspondências no script $index")
                        matches.forEach { match ->
                            val url = match.value
                            debugInfo("      URL encontrada: ${url.take(100)}...")
                            if (isDirectVideoUrl(url) && !url.contains("m3u8")) {
                                debugInfo("      ✅ URL direta válida")
                                if (processDirectVideoUrl(url, referer, name, callback)) {
                                    debugSuccess("URL processada com sucesso")
                                    found = true
                                } else {
                                    debugError("Falha ao processar URL")
                                }
                            } else {
                                debugWarning("      URL não é direta ou contém m3u8")
                            }
                        }
                    }
                }
            }
        }

        debugInfo("🏁 Extração JWPlayer finalizada. Encontrou vídeos: $found")
        return found
    }

    private fun isDirectVideoUrl(url: String): Boolean {
        val result = (url.contains("googlevideo.com") && 
                url.contains("videoplayback") && 
                !url.contains("m3u8"))
        debugInfo("🔍 Verificando URL direta: $result - ${url.take(50)}...")
        return result
    }

    private suspend fun processDirectVideoUrl(
        videoUrl: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        debugInfo("🔄 Processando URL direta: ${videoUrl.take(100)}...")
        return try {
            val itag = extractItagFromUrl(videoUrl)
            debugInfo("   Itag extraído: $itag")
            
            val quality = itagQualityMap[itag] ?: 720 
            debugInfo("   Qualidade determinada: ${quality}p")
            
            val qualityLabel = getQualityLabel(quality)
            debugInfo("   Label de qualidade: $qualityLabel")

            val extractorLink = newExtractorLink(
                source = "Goyabu JWPlayer",
                name = "$name ($qualityLabel) [MP4]",
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

            debugSuccess("✅ Link extraído com sucesso: ${extractorLink.name}")
            callback(extractorLink)
            true
        } catch (e: Exception) {
            debugError("❌ Erro ao processar URL direta: ${e.message}")
            false
        }
    }

    private suspend fun extractM3U8Urls(
        doc: org.jsoup.nodes.Document,
        originalUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        debugInfo("🎯 Iniciando extração M3U8...")
        val scripts = doc.select("script")
        var found = false

        debugInfo("📜 Analisando ${scripts.size} scripts em busca de URLs M3U8...")

        scripts.forEachIndexed { index, script ->
            val scriptContent = script.html()
            
            if (scriptContent.contains("m3u8") || scriptContent.contains("anivideo")) {
                debugInfo("   Script $index contém termos relacionados a M3U8")

                val patterns = listOf(
                    """api\.anivideo\.net/videohls\.php[^"'\s]*""".toRegex(),
                    """https?://[^"'\s]*\.m3u8[^"'\s]*""".toRegex(),
                    """"(?:file|video|src|url)"\s*:\s*"([^"']*api\.anivideo[^"']+)"""".toRegex()
                )

                patterns.forEachIndexed { pIndex, pattern ->
                    val matches = pattern.findAll(scriptContent)
                    val matchCount = matches.count()
                    
                    if (matchCount > 0) {
                        debugInfo("      Pattern $pIndex encontrou $matchCount correspondências")
                        matches.forEach { match ->
                            var videoUrl = match.value

                            if (pattern.pattern.contains("""["']\s*:\s*["']""")) {
                                val group = match.groupValues.getOrNull(1)
                                if (group != null) {
                                    debugInfo("         URL extraída do grupo: ${group.take(50)}...")
                                    videoUrl = group
                                }
                            }

                            if (isM3U8Url(videoUrl)) {
                                debugInfo("         ✅ URL M3U8 válida detectada")
                                if (processM3U8Url(videoUrl, originalUrl, name, callback)) {
                                    debugSuccess("URL M3U8 processada com sucesso")
                                    found = true
                                } else {
                                    debugError("Falha ao processar URL M3U8")
                                }
                            } else {
                                debugWarning("         URL não é M3U8 válida")
                            }
                        }
                    }
                }
            }
        }

        debugInfo("🔍 Procurando elementos com atributos M3U8...")
        val elements = doc.select("""
            [src*=".m3u8"],
            [data-src*=".m3u8"],
            [href*=".m3u8"]
        """.trimIndent())

        debugInfo("   Encontrados ${elements.size} elementos com atributos M3U8")
        
        elements.forEachIndexed { index, element ->
            val m3u8Url = element.attr("src") 
                ?: element.attr("data-src") 
                ?: element.attr("href")

            debugInfo("   Elemento $index: URL=${m3u8Url.take(100)}...")
            
            if (m3u8Url.isNotBlank() && isM3U8Url(m3u8Url)) {
                debugInfo("      ✅ URL M3U8 válida detectada no elemento")
                if (processM3U8Url(m3u8Url, originalUrl, name, callback)) {
                    debugSuccess("URL M3U8 do elemento processada com sucesso")
                    found = true
                } else {
                    debugError("Falha ao processar URL M3U8 do elemento")
                }
            } else {
                debugWarning("      URL não é M3U8 válida ou está em branco")
            }
        }

        debugInfo("🏁 Extração M3U8 finalizada. Encontrou vídeos: $found")
        return found
    }

    private suspend fun processM3U8Url(
        m3u8Url: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        debugInfo("🔄 Processando URL M3U8: ${m3u8Url.take(100)}...")
        try {
            var url = cleanUrl(m3u8Url)
            debugInfo("   URL limpa: ${url.take(100)}...")

            if (url.contains("api.anivideo.net/videohls.php")) {
                debugInfo("   URL da AniVideo detectada, decodificando parâmetro 'd'...")
                val dParamRegex = """[?&]d=([^&]+)""".toRegex()
                val match = dParamRegex.find(url)

                match?.let {
                    val encodedUrl = it.groupValues[1]
                    debugInfo("   Parâmetro 'd' encontrado: ${encodedUrl.take(50)}...")
                    try {
                        url = java.net.URLDecoder.decode(encodedUrl, "UTF-8")
                        debugInfo("   URL decodificada: ${url.take(100)}...")
                    } catch (e: Exception) {
                        debugError("   Erro ao decodificar URL: ${e.message}")
                    }
                } ?: debugWarning("   Parâmetro 'd' não encontrado na URL")
            }

            if (!url.contains(".m3u8") && !url.contains("m3u8")) {
                debugWarning("   URL final não contém .m3u8: ${url.take(100)}")
                return false
            }

            val quality = determineM3U8Quality(url)
            debugInfo("   Qualidade determinada: ${quality}p")
            
            val qualityLabel = getQualityLabel(quality)
            debugInfo("   Label de qualidade: $qualityLabel")

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

            debugSuccess("✅ Link M3U8 extraído com sucesso: ${extractorLink.name}")
            callback(extractorLink)
            return true

        } catch (e: Exception) {
            debugError("❌ Erro ao processar URL M3U8: ${e.message}")
            return false
        }
    }

    
    private fun cleanUrl(url: String): String {
        var cleaned = url.trim()
        val original = cleaned

        if (cleaned.startsWith("\"url\":\"")) {
            cleaned = cleaned.removePrefix("\"url\":\"")
            debugInfo("   Removido prefixo \"url\":\"")
        }
        if (cleaned.startsWith("\"")) {
            cleaned = cleaned.removePrefix("\"")
            debugInfo("   Removido prefixo de aspas")
        }
        if (cleaned.endsWith("\"")) {
            cleaned = cleaned.removeSuffix("\"")
            debugInfo("   Removido sufixo de aspas")
        }

        cleaned = cleaned.replace("\\/", "/")
        
        if (original != cleaned) {
            debugInfo("   URL limpa: $cleaned")
        }
        
        return cleaned
    }

    private fun determineM3U8Quality(url: String): Int {
        val urlLower = url.lowercase()
        
        val quality = when {
            urlLower.contains("1080") || urlLower.contains("fhd") -> 1080
            urlLower.contains("720") || urlLower.contains("hd") -> 720
            urlLower.contains("480") -> 480
            urlLower.contains("360") -> 360
            else -> 720
        }
        
        debugInfo("   Qualidade M3U8 determinada: ${quality}p baseado na URL: ${url.take(50)}...")
        return quality
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
        val itag = match?.groupValues?.get(1)?.toIntOrNull() ?: 18
        debugInfo("   Extraindo itag da URL: $itag")
        return itag
    }

    private fun isM3U8Url(url: String): Boolean {
        val result = url.contains("m3u8") ||
               url.contains(".m3u8") ||
               url.contains("anivideo.net")
        if (result) {
            debugInfo("   ✅ URL detectada como M3U8")
        }
        return result
    }

    private fun isBloggerUrl(url: String): Boolean {
        val result = url.contains("googlevideo.com") ||
               url.contains("blogger.com") ||
               url.contains("video.g") ||
               url.contains("videoplayback")
        if (result) {
            debugInfo("   ✅ URL detectada como Blogger")
        }
        return result
    }
}

private suspend fun extractBloggerUrls(
    doc: org.jsoup.nodes.Document,
    originalUrl: String,
    name: String,
    callback: (ExtractorLink) -> Unit
): Boolean {
    debugInfo("🎯 Iniciando extração Blogger (via batch execute)...")
    var found = false

    // PASSO 1: Extrair o token da página
    debugInfo("🔍 Procurando token do Blogger nos scripts...")
    val token = extractBloggerToken(doc)
    
    if (token == null) {
        debugError("❌ Token do Blogger não encontrado!")
        return false
    }
    
    debugSuccess("✅ Token encontrado: $token")
    
    // PASSO 2: Parâmetros FIXOS do Blogger (do HTML que você forneceu)
    val f_sid = "-7535563745894756252"  // FdrFJe
    val bl = "boq_bloggeruiserver_20260218.01_p0"  // cfb2h
    val reqid = (10000..99999).random()  // Gerar request ID aleatório
    
    debugInfo("📋 Parâmetros da API:")
    debugInfo("   - f.sid: $f_sid")
    debugInfo("   - bl: $bl")
    debugInfo("   - _reqid: $reqid")
    
    // PASSO 3: Chamar API batch execute
    val videos = callBloggerBatchApi(token, f_sid, bl, reqid)
    
    if (videos.isEmpty()) {
        debugError("❌ Nenhum vídeo encontrado na resposta da API")
        return false
    }
    
    debugSuccess("✅ Encontrados ${videos.size} links de vídeo!")
    
    // PASSO 4: Processar cada URL encontrada
    videos.forEach { (videoUrl, itag) ->
        val quality = itagQualityMap[itag] ?: 360
        val qualityLabel = getQualityLabel(quality)
        
        debugInfo("🎬 Processando vídeo - Qualidade: ${quality}p (itag: $itag)")
        
        val extractorLink = newExtractorLink(
            source = "Goyabu Blogger",
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
        
        debugSuccess("✅ Link adicionado: $qualityLabel")
        callback(extractorLink)
        found = true
    }
    
    return found
}

// NOVAS FUNÇÕES AUXILIARES:

private fun extractBloggerToken(doc: org.jsoup.nodes.Document): String? {
    val pattern = """video\.g\?token=([a-zA-Z0-9_\-]+)""".toRegex()
    
    doc.select("script").forEach { script ->
        val match = pattern.find(script.html())
        if (match != null) {
            val token = match.groupValues[1]
            debugInfo("   Token extraído: $token")
            return token
        }
    }
    
    // Tentar também em elementos iframe
    doc.select("iframe[src*='video.g']").forEach { iframe ->
        val src = iframe.attr("src")
        val match = pattern.find(src)
        if (match != null) {
            val token = match.groupValues[1]
            debugInfo("   Token extraído do iframe: $token")
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
    
    // Headers baseados na sua requisição curl
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
    
    // Construir URL com parâmetros
    val urlWithParams = "$apiUrl?rpcids=WcwnYd&source-path=%2Fvideo.g&f.sid=$f_sid&bl=$bl&hl=pt-BR&_reqid=$reqid&rt=c"
    
    // Body no formato exato que você usou
    val body = "f.req=%5B%5B%5B%22WcwnYd%22%2C%22%5B%5C%22$token%5C%22%2C%5C%22%5C%22%2C0%5D%22%2Cnull%2C%22generic%22%5D%5D%5D"
    
    debugNetwork("📡 Chamando API batch execute...")
    debugNetwork("   URL: $urlWithParams")
    debugNetwork("   Body: $body")
    
    val response = app.post(
        urlWithParams,
        headers = headers,
        data = body
    )
    
    debugNetwork("✅ Resposta da API - Status: ${response.code}, Tamanho: ${response.text.length} bytes")
    
    if (!response.isSuccessful) {
        debugError("❌ API retornou erro: ${response.code}")
        return emptyList()
    }
    
    val responseText = response.text
    debugInfo("📄 Resposta (primeiros 500 chars): ${responseText.take(500)}")
    
    // Extrair URLs da resposta
    return extractVideoUrlsFromResponse(responseText)
}

private fun extractVideoUrlsFromResponse(response: String): List<Pair<String, Int>> {
    val videos = mutableListOf<Pair<String, Int>>()
    
    // Padrão para encontrar URLs do Google Video
    // Ex: "https:\\/\\/rr2---sn-45mucg-jo4e.googlevideo.com\\/videoplayback?expire=..."
    val urlPattern = """(https?:\\/\\/[^"\\]+\.googlevideo\.com\\/[^"\\]+videoplayback[^"\\]+)""".toRegex()
    
    val matches = urlPattern.findAll(response)
    var matchCount = 0
    
    matches.forEach { match ->
        matchCount++
        var url = match.value
            .replace("\\u003d", "=")
            .replace("\\/", "/")
            .replace("\\", "")
        
        debugInfo("   URL encontrada: ${url.take(100)}...")
        
        // Extrair itag da URL
        val itagPattern = """itag[=?&](\d+)""".toRegex()
        val itag = itagPattern.find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 18
        
        debugInfo("      itag: $itag")
        
        // Limpar URL (remover caracteres estranhos)
        url = url.trim()
        
        videos.add(Pair(url, itag))
    }
    
    debugInfo("📊 Total de URLs encontradas: $matchCount")
    debugInfo("📊 URLs únicas por qualidade: ${videos.distinctBy { it.second }.size}")
    
    // Retornar URLs únicas por qualidade (itag)
    return videos.distinctBy { it.second }
}
,}
