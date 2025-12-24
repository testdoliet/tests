package com.Goyabu

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.Jsoup

object GoyabuBloggerExtractor {
    // Mapa de itag para qualidade
    private val itagQualityMap = mapOf(
        5 to 240,    // 240p FLV
        18 to 360,   // 360p MP4
        22 to 720,   // 720p MP4
        37 to 1080,  // 1080p MP4
        59 to 480,   // 480p MP4
        43 to 360,   // 360p WebM
        44 to 480,   // 480p WebM
        45 to 720,   // 720p WebM
        46 to 1080,  // 1080p WebM
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
        271 to 1440, // 1440p WebM
        313 to 2160, // 4K WebM
    )
    
    suspend fun extractVideoLinks(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🎬 GOYABU EXTRACTOR: Iniciando extração para: $url")
        
        return try {
            // Carregar a página do episódio com headers completos
            val pageResponse = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                    "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
                    "Referer" to "https://goyabu.io/",
                    "DNT" to "1",
                    "Connection" to "keep-alive",
                    "Upgrade-Insecure-Requests" to "1",
                    "Sec-Fetch-Dest" to "document",
                    "Sec-Fetch-Mode" to "navigate",
                    "Sec-Fetch-Site" to "same-origin"
                ),
                timeout = 30
            )
            
            val html = pageResponse.text
            val doc = Jsoup.parse(html)
            
            // MÉTODO 1: Procurar por iframes dinâmicos no JavaScript
            if (extractFromJavaScript(doc, url, name, callback)) {
                println("✅ GOYABU EXTRACTOR: Vídeo via JavaScript encontrado")
                return true
            }
            
            // MÉTODO 2: Procurar por URLs de vídeo diretas no HTML
            if (extractDirectVideoUrls(doc, url, name, callback)) {
                println("✅ GOYABU EXTRACTOR: Vídeo direto encontrado")
                return true
            }
            
            // MÉTODO 3: Procurar por iframes estáticos
            if (extractStaticIframes(doc, url, name, callback)) {
                println("✅ GOYABU EXTRACTOR: Iframe estático encontrado")
                return true
            }
            
            // MÉTODO 4: Procurar por dados embed em divs
            if (extractEmbeddedData(doc, url, name, callback)) {
                println("✅ GOYABU EXTRACTOR: Dados embed encontrados")
                return true
            }
            
            println("❌ GOYABU EXTRACTOR: Nenhum vídeo encontrado")
            false
            
        } catch (e: Exception) {
            println("❌ GOYABU EXTRACTOR: Erro na extração: ${e.message}")
            false
        }
    }
    
    // MÉTODO 1: Extrair de JavaScript (onde o iframe é carregado dinamicamente)
    private suspend fun extractFromJavaScript(
        doc: org.jsoup.nodes.Document,
        originalUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔍 GOYABU EXTRACTOR: Procurando vídeos em JavaScript...")
        
        val scripts = doc.select("script")
        var found = false
        
        scripts.forEachIndexed { index, script ->
            val scriptContent = script.html()
            
            // Padrões para encontrar URLs do Blogger/Google Video
            val patterns = listOf(
                // Padrão 1: blogspot.com/video.g?token=...
                """https?://[^"'\s]*blogger\.com/video\.g\?[^"'\s]*""".toRegex(),
                // Padrão 2: video.g?token=...
                """video\.g\?[^"'\s]*token=[^"'\s]*""".toRegex(),
                // Padrão 3: googlevideo.com/videoplayback
                """https?://[^"'\s]*\.googlevideo\.com/[^"'\s]*videoplayback[^"'\s]*""".toRegex(),
                // Padrão 4: URLs do Google Video com itag
                """https?://[^"'\s]*\.googlevideo\.com/[^"'\s]*itag=\d+[^"'\s]*""".toRegex(),
                // Padrão 5: Data do iframe (data-src, data-url, etc)
                """(?:src|data-src|data-url|url)\s*[:=]\s*['"](https?://[^"']+)['"]""".toRegex(),
                // Padrão 6: URLs em configurações JSON
                """"(?:play_url|url|src|source)"\s*:\s*"([^"]+)"""".toRegex()
            )
            
            for (pattern in patterns) {
                val matches = pattern.findAll(scriptContent)
                
                matches.forEach { match ->
                    var videoUrl = match.value
                    
                    // Se for um padrão de chave-valor JSON, extrair o valor
                    if (pattern.pattern.contains("""["']\s*:\s*["']""")) {
                        val group = match.groupValues.getOrNull(1)
                        if (group != null) {
                            videoUrl = group
                        }
                    }
                    
                    // Se for um caminho relativo, transformar em URL completa
                    if (videoUrl.startsWith("//")) {
                        videoUrl = "https:$videoUrl"
                    } else if (videoUrl.startsWith("/") || videoUrl.startsWith("./")) {
                        videoUrl = "https://www.blogger.com$videoUrl"
                    } else if (videoUrl.startsWith("video.g")) {
                        videoUrl = "https://www.blogger.com/$videoUrl"
                    }
                    
                    if (isValidVideoUrl(videoUrl)) {
                        println("✅ URL encontrada no script #$index: ${videoUrl.take(80)}...")
                        
                        if (processVideoUrl(videoUrl, originalUrl, name, callback)) {
                            found = true
                        }
                    }
                }
            }
        }
        
        return found
    }
    
    // MÉTODO 2: Extrair URLs diretas de vídeo
    private suspend fun extractDirectVideoUrls(
        doc: org.jsoup.nodes.Document,
        originalUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔍 GOYABU EXTRACTOR: Procurando URLs diretas...")
        
        var found = false
        
        // Procurar por elementos com URLs de vídeo
        val elements = doc.select("""
            [src*="googlevideo.com"],
            [data-src*="googlevideo.com"],
            [data-url*="googlevideo.com"],
            [href*="googlevideo.com"],
            video source,
            [data-video],
            [data-player],
            [data-embed]
        """.trimIndent())
        
        elements.forEach { element ->
            val videoUrl = element.attr("src")
                ?: element.attr("data-src")
                ?: element.attr("data-url")
                ?: element.attr("href")
                ?: element.attr("data-video")
                ?: element.attr("data-player")
                ?: element.attr("data-embed")
            
            if (videoUrl.isNotBlank() && isValidVideoUrl(videoUrl)) {
                println("✅ URL direta encontrada: ${videoUrl.take(80)}...")
                
                if (processVideoUrl(videoUrl, originalUrl, name, callback)) {
                    found = true
                }
            }
        }
        
        // Procurar por texto que contenha URLs de vídeo
        val bodyText = doc.body()?.text() ?: ""
        val videoPattern = """https?://[^"'\s]*\.googlevideo\.com/[^"'\s]*""".toRegex()
        val matches = videoPattern.findAll(bodyText)
        
        matches.forEach { match ->
            val videoUrl = match.value
            if (isValidVideoUrl(videoUrl)) {
                println("✅ URL encontrada no texto: ${videoUrl.take(80)}...")
                
                if (processVideoUrl(videoUrl, originalUrl, name, callback)) {
                    found = true
                }
            }
        }
        
        return found
    }
    
    // MÉTODO 3: Extrair iframes estáticos
    private suspend fun extractStaticIframes(
        doc: org.jsoup.nodes.Document,
        originalUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔍 GOYABU EXTRACTOR: Procurando iframes estáticos...")
        
        val iframes = doc.select("iframe")
        var found = false
        
        iframes.forEachIndexed { index, iframe ->
            val src = iframe.attr("src").trim()
            if (src.isNotBlank()) {
                println("🔍 Iframe encontrado (#${index + 1}): ${src.take(80)}...")
                
                // Se for um iframe do Blogger
                if (src.contains("blogger.com") || src.contains("video.g")) {
                    println("✅ Iframe do Blogger encontrado")
                    
                    // Acessar o iframe e extrair vídeos
                    val iframeResponse = try {
                        app.get(
                            src,
                            headers = mapOf(
                                "Referer" to originalUrl,
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                            )
                        )
                    } catch (e: Exception) {
                        println("❌ Erro ao acessar iframe: ${e.message}")
                        return@forEachIndexed
                    }
                    
                    val iframeHtml = iframeResponse.text
                    val iframeDoc = Jsoup.parse(iframeHtml)
                    
                    // Extrair URLs de vídeo do iframe
                    if (extractDirectVideoUrls(iframeDoc, src, name, callback)) {
                        found = true
                    }
                    
                    // Procurar no JavaScript do iframe
                    if (extractFromJavaScript(iframeDoc, src, name, callback)) {
                        found = true
                    }
                }
            }
        }
        
        return found
    }
    
    // MÉTODO 4: Extrair dados embed de divs
    private suspend fun extractEmbeddedData(
        doc: org.jsoup.nodes.Document,
        originalUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔍 GOYABU EXTRACTOR: Procurando dados embed...")
        
        // Procurar por divs que possam conter dados de vídeo
        val embedDivs = doc.select("""
            div[data-player],
            div[data-video],
            div[data-embed],
            div[id*="player"],
            div[class*="player"],
            div[id*="video"],
            div[class*="video"],
            #player,
            #video-player,
            .video-player,
            .player-container
        """.trimIndent())
        
        var found = false
        
        embedDivs.forEach { div ->
            // Extrair dados do atributo data-*
            val dataVideo = div.attr("data-video")
            val dataPlayer = div.attr("data-player")
            val dataEmbed = div.attr("data-embed")
            
            val videoUrl = dataVideo.takeIf { it.isNotBlank() }
                ?: dataPlayer.takeIf { it.isNotBlank() }
                ?: dataEmbed.takeIf { it.isNotBlank() }
            
            if (videoUrl != null && isValidVideoUrl(videoUrl)) {
                println("✅ Dados embed encontrados: ${videoUrl.take(80)}...")
                
                if (processVideoUrl(videoUrl, originalUrl, name, callback)) {
                    found = true
                }
            }
            
            // Verificar também o conteúdo HTML da div
            val html = div.html()
            val videoPattern = """https?://[^"'\s]*\.googlevideo\.com/[^"'\s]*""".toRegex()
            val matches = videoPattern.findAll(html)
            
            matches.forEach { match ->
                val url = match.value
                if (isValidVideoUrl(url)) {
                    println("✅ URL encontrada em div embed: ${url.take(80)}...")
                    
                    if (processVideoUrl(url, originalUrl, name, callback)) {
                        found = true
                    }
                }
            }
        }
        
        return found
    }
    
    // Processar uma URL de vídeo
    private suspend fun processVideoUrl(
        videoUrl: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            var url = videoUrl
            
            // Limpar URL
            if (url.contains("&amp;")) {
                url = url.replace("&amp;", "&")
            }
            
            // Se for um iframe do Blogger, precisamos acessá-lo
            if (url.contains("blogger.com/video.g")) {
                return processBloggerIframe(url, referer, name, callback)
            }
            
            // Extrair qualidade
            val itag = extractItagFromUrl(url)
            val quality = itagQualityMap[itag] ?: 360
            val qualityLabel = getQualityLabel(quality)
            
            println("📹 Processando vídeo: $qualityLabel (itag: $itag)")
            
            // Criar ExtractorLink
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
                    "Origin" to "https://www.blogger.com",
                    "Accept" to "*/*",
                    "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8"
                )
            }
            
            callback(extractorLink)
            true
            
        } catch (e: Exception) {
            println("❌ Erro ao processar URL: ${e.message}")
            false
        }
    }
    
    // Processar iframe do Blogger
    private suspend fun processBloggerIframe(
        iframeUrl: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔍 Processando iframe do Blogger: ${iframeUrl.take(80)}...")
        
        return try {
            val response = app.get(
                iframeUrl,
                headers = mapOf(
                    "Referer" to referer,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                )
            )
            
            val html = response.text
            
            // Procurar por URLs do Google Video no iframe
            val videoPattern = """https?://[^"'\s]*\.googlevideo\.com/[^"'\s]*""".toRegex()
            val matches = videoPattern.findAll(html)
            
            var found = false
            matches.forEach { match ->
                val videoUrl = match.value
                if (isValidVideoUrl(videoUrl)) {
                    val itag = extractItagFromUrl(videoUrl)
                    val quality = itagQualityMap[itag] ?: 360
                    val qualityLabel = getQualityLabel(quality)
                    
                    println("📹 Vídeo do iframe: $qualityLabel")
                    
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
            println("❌ Erro ao processar iframe: ${e.message}")
            false
        }
    }
    
    // Verificar se é uma URL de vídeo válida
    private fun isValidVideoUrl(url: String): Boolean {
        return url.contains("googlevideo.com") || 
               url.contains("blogger.com/video.g") || 
               url.contains("videoplayback")
    }
    
    // Extrair itag da URL
    private fun extractItagFromUrl(url: String): Int {
        val itagPattern = """[?&]itag=(\d+)""".toRegex()
        val match = itagPattern.find(url)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 18
    }
    
    private fun getQualityLabel(quality: Int): String {
        return when {
            quality >= 2160 -> "4K"
            quality >= 1440 -> "QHD"
            quality >= 1080 -> "FHD"
            quality >= 720 -> "HD"
            quality >= 480 -> "SD"
            quality >= 360 -> "SD"
            else -> "SD"
        }
    }
}
