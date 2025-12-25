package com.Goyabu

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.Jsoup

object GoyabuExtractor {
    // Mapa de itag para qualidade (APENAS para Blogger/Google Video)
    private val itagQualityMap = mapOf(
        18 to 360,   // 360p MP4
        22 to 720,   // 720p MP4
        37 to 1080,  // 1080p MP4
        59 to 480,   // 480p MP4
        133 to 240,  // 240p MP4
        134 to 360,  // 360p MP4
        135 to 480,  // 480p MP4
        136 to 720,  // 720p MP4
        137 to 1080, // 1080p MP4
        160 to 144,  // 144p MP4
        242 to 240,  // 240p WebM
        243 to 360,  // 360p WebM
        244 to 480,  // 480p WebM
        247 to 720,  // 720p WebM
        248 to 1080, // 1080p WebM
    )
    
    suspend fun extractVideoLinks(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🎬 GOYABU EXTRACTOR: Buscando vídeos em: $url")
        
        return try {
            val pageResponse = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Referer" to "https://goyabu.io/",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
                ),
                timeout = 30
            )
            
            val html = pageResponse.text
            val doc = Jsoup.parse(html)
            
            // 🔍 PRIMEIRO: Procurar M3U8 (STCode Player) - mais comum agora
            println("1️⃣ Buscando M3U8 (STCode Player)...")
            if (extractM3U8Urls(doc, url, name, callback)) {
                println("✅ M3U8 encontrado!")
                return true
            }
            
            // 🔍 SEGUNDO: Procurar Blogger/Google Video
            println("2️⃣ Buscando Blogger/Google Video...")
            if (extractBloggerUrls(doc, url, name, callback)) {
                println("✅ Blogger encontrado!")
                return true
            }
            
            println("❌ Nenhum vídeo encontrado")
            false
            
        } catch (e: Exception) {
            println("❌ Erro na extração: ${e.message}")
            false
        }
    }
    
    // ==================== MÉTODO 1: M3U8 (STCode Player) ====================
    private suspend fun extractM3U8Urls(
        doc: org.jsoup.nodes.Document,
        originalUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Buscar em scripts JavaScript
        val scripts = doc.select("script")
        var found = false
        
        scripts.forEach { script ->
            val scriptContent = script.html()
            
            // Padrões específicos para STCode Player/anivideo
            val patterns = listOf(
                """api\.anivideo\.net/videohls\.php[^"'\s]*""".toRegex(),
                """https?://[^"'\s]*\.m3u8[^"'\s]*""".toRegex(),
                """"(?:file|video|src|url)"\s*:\s*"([^"']*api\.anivideo[^"']+)"""".toRegex()
            )
            
            for (pattern in patterns) {
                val matches = pattern.findAll(scriptContent)
                matches.forEach { match ->
                    var videoUrl = match.value
                    
                    // Extrair de JSON se necessário
                    if (pattern.pattern.contains("""["']\s*:\s*["']""")) {
                        val group = match.groupValues.getOrNull(1)
                        if (group != null) videoUrl = group
                    }
                    
                    if (isM3U8Url(videoUrl)) {
                        println("📦 M3U8 encontrado em script")
                        if (processM3U8Url(videoUrl, originalUrl, name, callback)) {
                            found = true
                        }
                    }
                }
            }
        }
        
        // Buscar em elementos HTML
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
                println("📦 M3U8 encontrado em elemento HTML")
                if (processM3U8Url(m3u8Url, originalUrl, name, callback)) {
                    found = true
                }
            }
        }
        
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
            println("🔧 URL limpa: ${url.take(80)}...")
            
            // Extrair M3U8 real do STCode Player
            if (url.contains("api.anivideo.net/videohls.php")) {
                val dParamRegex = """[?&]d=([^&]+)""".toRegex()
                val match = dParamRegex.find(url)
                
                match?.let {
                    val encodedUrl = it.groupValues[1]
                    try {
                        url = java.net.URLDecoder.decode(encodedUrl, "UTF-8")
                        println("✅ M3U8 extraído do parâmetro 'd'")
                    } catch (e: Exception) {
                        println("⚠️ Não foi possível decodificar URL")
                    }
                }
            }
            
            if (!url.contains(".m3u8") && !url.contains("m3u8")) {
                return false
            }
            
            val quality = determineM3U8Quality(url)
            val qualityLabel = getQualityLabel(quality)
            
            println("✅ Criando link M3U8: $qualityLabel")
            
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
            println("❌ Erro ao processar M3U8: ${e.message}")
            return false
        }
    }
    
    // ==================== MÉTODO 2: Blogger/Google Video ====================
    private suspend fun extractBloggerUrls(
        doc: org.jsoup.nodes.Document,
        originalUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        
        // 1. Buscar iframes do Blogger
        val iframes = doc.select("iframe[src*='blogger.com'], iframe[src*='video.g']")
        iframes.forEach { iframe ->
            val src = iframe.attr("src").trim()
            if (src.isNotBlank() && (src.contains("blogger.com") || src.contains("video.g"))) {
                println("📺 Iframe do Blogger encontrado")
                if (processBloggerIframe(src, originalUrl, name, callback)) {
                    found = true
                }
            }
        }
        
        // 2. Buscar em scripts JavaScript
        val scripts = doc.select("script")
        scripts.forEach { script ->
            val scriptContent = script.html()
            
            val patterns = listOf(
                """https?://[^"'\s]*blogger\.com/video\.g\?[^"'\s]*""".toRegex(),
                """video\.g\?[^"'\s]*token=[^"'\s]*""".toRegex(),
                """https?://[^"'\s]*\.googlevideo\.com/[^"'\s]*videoplayback[^"'\s]*""".toRegex(),
                """https?://[^"'\s]*\.googlevideo\.com/[^"'\s]*itag=\d+[^"'\s]*""".toRegex()
            )
            
            for (pattern in patterns) {
                val matches = pattern.findAll(scriptContent)
                matches.forEach { match ->
                    var videoUrl = match.value
                    
                    // Converter URL relativa para absoluta
                    if (videoUrl.startsWith("//")) {
                        videoUrl = "https:$videoUrl"
                    } else if (videoUrl.startsWith("/") || videoUrl.startsWith("./")) {
                        videoUrl = "https://www.blogger.com$videoUrl"
                    } else if (videoUrl.startsWith("video.g")) {
                        videoUrl = "https://www.blogger.com/$videoUrl"
                    }
                    
                    if (isBloggerUrl(videoUrl)) {
                        println("📺 URL do Blogger encontrada em script")
                        if (processBloggerVideoUrl(videoUrl, originalUrl, name, callback)) {
                            found = true
                        }
                    }
                }
            }
        }
        
        return found
    }
    
    private suspend fun processBloggerIframe(
        iframeUrl: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔍 Acessando iframe do Blogger: ${iframeUrl.take(80)}...")
        
        return try {
            val response = app.get(
                iframeUrl,
                headers = mapOf(
                    "Referer" to referer,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                )
            )
            
            val html = response.text
            
            // Procurar URLs do Google Video no iframe
            val videoPattern = """https?://[^"'\s]*\.googlevideo\.com/[^"'\s]*""".toRegex()
            val matches = videoPattern.findAll(html)
            
            var found = false
            matches.forEach { match ->
                val videoUrl = match.value
                if (isBloggerUrl(videoUrl)) {
                    val itag = extractItagFromUrl(videoUrl)
                    val quality = itagQualityMap[itag] ?: 360
                    val qualityLabel = getQualityLabel(quality)
                    
                    println("📹 Vídeo do Blogger: $qualityLabel (itag: $itag)")
                    
                    val extractorLink = newExtractorLink(
                        source = "Goyabu Blogger",
                        name = "$name ($qualityLabel)",
                        url = videoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = iframeUrl
                        this.quality = quality
                        this.headers = mapOf(
                            "Referer" to iframeUrl,
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                            "Origin" to "https://www.blogger.com"
                        )
                    }
                    
                    callback(extractorLink)
                    found = true
                }
            }
            
            found
            
        } catch (e: Exception) {
            println("❌ Erro ao acessar iframe: ${e.message}")
            false
        }
    }
    
    private suspend fun processBloggerVideoUrl(
        videoUrl: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            var url = videoUrl
            
            // Se for iframe do Blogger, processar
            if (url.contains("blogger.com/video.g")) {
                return processBloggerIframe(url, referer, name, callback)
            }
            
            // Se for link direto do Google Video
            if (url.contains("googlevideo.com")) {
                val itag = extractItagFromUrl(url)
                val quality = itagQualityMap[itag] ?: 360
                val qualityLabel = getQualityLabel(quality)
                
                println("📹 Vídeo Google direto: $qualityLabel (itag: $itag)")
                
                val extractorLink = newExtractorLink(
                    source = "Goyabu Blogger",
                    name = "$name ($qualityLabel)",
                    url = url,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = quality
                    this.headers = mapOf(
                        "Referer" to referer,
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                        "Origin" to "https://www.blogger.com"
                    )
                }
                
                callback(extractorLink)
                return true
            }
            
            false
            
        } catch (e: Exception) {
            println("❌ Erro ao processar Blogger: ${e.message}")
            false
        }
    }
// ==================== MÉTODO 3: JW Player (Direct MP4/Google Video) ====================
private fun extractJwPlayerUrls(
    doc: org.jsoup.nodes.Document,
    referer: String,
    name: String,
    callback: (ExtractorLink) -> Unit
): Boolean {
    var found = false

    // Buscar o elemento específico do JW Player que você identificou
    val jwVideoElement = doc.selectFirst(".jw-video.jw-reset[src]")
    jwVideoElement?.attr("src")?.let { videoUrl ->
        if (isDirectVideoUrl(videoUrl)) {
            println("📦 JW Player (Direct MP4) encontrado!")
            if (processDirectVideoUrl(videoUrl, referer, name, callback)) {
                found = true
            }
        }
    }

    // Busca alternativa: qualquer tag <video> com src contendo "googlevideo.com"
    if (!found) {
        doc.select("video[src*='googlevideo.com']").forEach { element ->
            val src = element.attr("src")
            if (src.isNotBlank() && isDirectVideoUrl(src)) {
                println("📦 <video> tag com link direto encontrado")
                if (processDirectVideoUrl(src, referer, name, callback)) {
                    found = true
                }
            }
        }
    }

    // Busca em scripts para URLs do Google Video (fallback)
    if (!found) {
        val scripts = doc.select("script")
        val directVideoPattern = Regex("""https?://[^"'\s]*\.googlevideo\.com/[^"'\s]*videoplayback[^"'\s]*""")
        
        scripts.forEach { script ->
            val matches = directVideoPattern.findAll(script.html())
            matches.forEach { match ->
                val url = match.value
                if (isDirectVideoUrl(url) && !url.contains("m3u8")) { // Garante que não é HLS
                    println("📦 Link direto (googlevideo.com) encontrado em script")
                    if (processDirectVideoUrl(url, referer, name, callback)) {
                        found = true
                    }
                }
            }
        }
    }

    return found
}

private fun isDirectVideoUrl(url: String): Boolean {
    return (url.contains("googlevideo.com") && 
            url.contains("videoplayback") && 
            !url.contains("m3u8"))
}

private fun processDirectVideoUrl(
    videoUrl: String,
    referer: String,
    name: String,
    callback: (ExtractorLink) -> Unit
): Boolean {
    return try {
        // Extrair qualidade do parâmetro 'itag' na URL
        val itag = extractItagFromUrl(videoUrl)
        // Usar o mesmo mapa de qualidade do método Blogger
        val quality = itagQualityMap[itag] ?: 720 
        val qualityLabel = getQualityLabel(quality)

        println("✅ Criando link direto MP4: $qualityLabel (itag: $itag)")

        val extractorLink = newExtractorLink(
            source = "Goyabu JWPlayer",
            name = "$name ($qualityLabel) [MP4]",
            url = videoUrl,
            type = ExtractorLinkType.VIDEO // Tipo VIDEO para MP4 direto
        ) {
            this.referer = referer
            this.quality = quality
            this.headers = mapOf(
                "Referer" to referer,
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Origin" to URI(referer).host?.let { "https://$it" } ?: "https://goyabu.io"
            )
        }

        callback(extractorLink)
        true
    } catch (e: Exception) {
        println("❌ Erro ao processar link direto: ${e.message}")
        false
    }
}
    
    // ==================== FUNÇÕES AUXILIARES ====================
    private fun cleanUrl(url: String): String {
        var cleaned = url.trim()
        
        // Remover aspas e JSON extras
        if (cleaned.startsWith("\"url\":\"")) {
            cleaned = cleaned.removePrefix("\"url\":\"")
        }
        if (cleaned.startsWith("\"")) {
            cleaned = cleaned.removePrefix("\"")
        }
        if (cleaned.endsWith("\"")) {
            cleaned = cleaned.removeSuffix("\"")
        }
        
        // Remover barras invertidas
        cleaned = cleaned.replace("\\/", "/")
        
        return cleaned
    }
    
    private fun determineM3U8Quality(url: String): Int {
        val urlLower = url.lowercase()
        
        // Detecção realista para M3U8
        return when {
            urlLower.contains("1080") || urlLower.contains("fhd") -> 1080
            urlLower.contains("720") || urlLower.contains("hd") -> 720
            urlLower.contains("480") -> 480
            urlLower.contains("360") -> 360
            else -> 720 // Padrão para M3U8
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
    
    private fun isBloggerUrl(url: String): Boolean {
        return url.contains("googlevideo.com") ||
               url.contains("blogger.com") ||
               url.contains("video.g") ||
               url.contains("videoplayback")
    }
    
    // Função auxiliar para uso simples
    suspend fun extractVideoLinks(
    url: String,
    name: String,
    callback: (ExtractorLink) -> Unit
): Boolean {
    println("🎬 GOYABU EXTRACTOR: Buscando vídeos em: $url")
    
    return try {
        val pageResponse = app.get(
            url,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Referer" to "https://goyabu.io/",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
            ),
            timeout = 30
        )
        
        val html = pageResponse.text
        val doc = Jsoup.parse(html)
        
        // 🔍 ORDEM DE BUSCA OTIMIZADA:
        // 1. PRIMEIRO: JW Player com links MP4 diretos (qualidade FHD)
        println("1️⃣ Buscando JW Player (MP4 Direto)...")
        if (extractJwPlayerUrls(doc, url, name, callback)) {
            println("✅ JW Player (MP4) encontrado!")
            return true
        }
        
        // 2. SEGUNDO: M3U8 (STCode Player/HLS)
        println("2️⃣ Buscando M3U8 (STCode Player)...")
        if (extractM3U8Urls(doc, url, name, callback)) {
            println("✅ M3U8 encontrado!")
            return true
        }
        
        // 3. TERCEIRO: Blogger/Google Video (fallback)
        println("3️⃣ Buscando Blogger/Google Video...")
        if (extractBloggerUrls(doc, url, name, callback)) {
            println("✅ Blogger encontrado!")
            return true
        }
        
        println("❌ Nenhum vídeo encontrado")
        false
        
    } catch (e: Exception) {
        println("❌ Erro na extração: ${e.message}")
        false
    }
}
