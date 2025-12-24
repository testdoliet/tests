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
            // Carregar a página do episódio
            val pageResponse = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                    "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
                    "Referer" to "https://goyabu.io/",
                    "DNT" to "1",
                    "Connection" to "keep-alive",
                    "Upgrade-Insecure-Requests" to "1"
                ),
                timeout = 30
            )
            
            val html = pageResponse.text
            val doc = Jsoup.parse(html)
            
            // MÉTODO 1: Procurar por iframes do Blogger
            if (extractBloggerIframes(doc, url, name, callback)) {
                println("✅ GOYABU EXTRACTOR: Vídeo via iframe encontrado")
                return true
            }
            
            // MÉTODO 2: Procurar por JavaScript que contém vídeos
            if (extractFromJavaScript(doc, url, name, callback)) {
                println("✅ GOYABU EXTRACTOR: Vídeo via JavaScript encontrado")
                return true
            }
            
            // MÉTODO 3: Procurar por URLs diretas
            if (extractDirectVideoUrls(doc, url, name, callback)) {
                println("✅ GOYABU EXTRACTOR: Vídeo direto encontrado")
                return true
            }
            
            println("❌ GOYABU EXTRACTOR: Nenhum vídeo encontrado")
            false
            
        } catch (e: Exception) {
            println("❌ GOYABU EXTRACTOR: Erro na extração: ${e.message}")
            false
        }
    }
    
    // MÉTODO PRINCIPAL: Extrair vídeo do Blogger via iframe
    private suspend fun extractBloggerIframes(
        doc: org.jsoup.nodes.Document,
        originalUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔍 GOYABU EXTRACTOR: Procurando iframes do Blogger...")
        
        // Padrões de iframe do Blogger
        val iframePatterns = listOf(
            "iframe[src*='blogger.com/video.g']",
            "iframe[src*='blogger.com']",
            "iframe[src*='video.g']",
            "#playerFrame iframe",
            ".playerContainer iframe",
            ".playerWrapper iframe",
            "#player-content iframe",
            "#canvasContainer iframe"
        )
        
        for (pattern in iframePatterns) {
            val iframes = doc.select(pattern)
            println("📊 Procurando padrão '$pattern': ${iframes.size} encontrados")
            
            for ((index, iframe) in iframes.withIndex()) {
                val src = iframe.attr("src").trim()
                if (src.isNotBlank()) {
                    println("✅ Iframe encontrado (#${index + 1}): ${src.take(80)}...")
                    
                    if (extractFromBloggerIframe(src, originalUrl, name, callback)) {
                        return true
                    }
                }
            }
        }
        
        return false
    }
    
    // Extrair vídeo de um iframe específico do Blogger
    private suspend fun extractFromBloggerIframe(
        iframeSrc: String,
        originalUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔍 Analisando iframe: ${iframeSrc.take(80)}...")
        
        return try {
            val iframeResponse = app.get(
                iframeSrc,
                headers = mapOf(
                    "Referer" to originalUrl,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                    "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8"
                )
            )
            
            val iframeHtml = iframeResponse.text
            extractFromBloggerHtml(iframeHtml, iframeSrc, originalUrl, name, callback)
            
        } catch (e: Exception) {
            println("❌ Erro ao acessar iframe: ${e.message}")
            false
        }
    }
    
    // Extrair vídeo do HTML do iframe do Blogger
    private suspend fun extractFromBloggerHtml(
        html: String,
        iframeUrl: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔍 Analisando HTML do iframe do Blogger...")
        
        // MÉTODO 1: Procurar por VIDEO_CONFIG no JavaScript
        val configPattern = """var\s+VIDEO_CONFIG\s*=\s*(\{.*?\})\s*;""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val configMatch = configPattern.find(html)
        
        if (configMatch != null) {
            println("✅ VIDEO_CONFIG encontrado no iframe")
            return extractFromBloggerConfig(configMatch.groupValues[1], referer, name, callback)
        }
        
        // MÉTODO 2: Procurar por playerConfig
        val playerConfigPattern = """playerConfig\s*=\s*(\{.*?\})\s*;""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val playerConfigMatch = playerConfigPattern.find(html)
        
        if (playerConfigMatch != null) {
            println("✅ playerConfig encontrado no iframe")
            return extractFromBloggerConfig(playerConfigMatch.groupValues[1], referer, name, callback)
        }
        
        // MÉTODO 3: Procurar por URLs do Google Video diretamente
        return extractGooglevideoUrlsFromHtml(html, referer, name, callback)
    }
    
    // Extrair vídeo da configuração JSON do Blogger
    private suspend fun extractFromBloggerConfig(
        jsonString: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔍 Analisando configuração JSON do Blogger...")
        
        return try {
            val json = Json.parseToJsonElement(jsonString).jsonObject
            
            var found = false
            
            // Extrair URLs de vídeo
            val videoUrls = mutableListOf<String>()
            
            // Procura por streams
            json["streams"]?.jsonObject?.let { streams ->
                for ((itagStr, streamObj) in streams) {
                    try {
                        val videoUrl = streamObj.jsonObject["play_url"]?.jsonPrimitive?.content
                        if (videoUrl != null) {
                            videoUrls.add("$itagStr:$videoUrl")
                        }
                    } catch (e: Exception) {
                        // Ignorar erro
                    }
                }
            }
            
            // Procura por play_url direto
            json["play_url"]?.jsonPrimitive?.content?.let { videoUrl ->
                val itag = json["format_id"]?.jsonPrimitive?.content ?: "18"
                videoUrls.add("$itag:$videoUrl")
            }
            
            // Procura por sources
            json["sources"]?.jsonPrimitive?.content?.let { sources ->
                extractVideoUrlsFromString(sources, videoUrls)
            }
            
            // Processar URLs encontradas
            for (videoInfo in videoUrls) {
                val parts = videoInfo.split(":", limit = 2)
                if (parts.size == 2) {
                    val itag = parts[0].toIntOrNull() ?: 18
                    val videoUrl = parts[1]
                    
                    if (createBloggerExtractorLink(itag, videoUrl, referer, name, callback)) {
                        found = true
                    }
                }
            }
            
            found
            
        } catch (e: Exception) {
            println("❌ Erro ao parsear JSON: ${e.message}")
            false
        }
    }
    
    // Extrair URLs de vídeo de uma string
    private fun extractVideoUrlsFromString(content: String, videoUrls: MutableList<String>) {
        // Procurar por padrões de URL
        val urlPatterns = listOf(
            """https?://[^"'\s]*\.googlevideo\.com/[^"'\s]*""".toRegex(),
            """https?://[^"'\s]*\.googleusercontent\.com/[^"'\s]*""".toRegex(),
            """https?://[^"'\s]*\.blogspot\.com/[^"'\s]*""".toRegex(),
            """https?://[^"'\s]*\.blogger\.com/[^"'\s]*""".toRegex()
        )
        
        for (pattern in urlPatterns) {
            val matches = pattern.findAll(content)
            for (match in matches) {
                val url = match.value
                if (url.contains("videoplayback") || url.contains("video.g")) {
                    // Extrair itag da URL
                    val itagPattern = """[?&]itag=(\d+)""".toRegex()
                    val itagMatch = itagPattern.find(url)
                    val itag = itagMatch?.groupValues?.get(1) ?: "18"
                    videoUrls.add("$itag:$url")
                }
            }
        }
    }
    
    // Extrair URLs do Google Video diretamente do HTML
    private suspend fun extractGooglevideoUrlsFromHtml(
        html: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔍 Procurando URLs do Google Video no HTML...")
        
        val videoPattern = """https?://[^"'\s]*\.googlevideo\.com/[^"'\s]*""".toRegex()
        val matches = videoPattern.findAll(html).toList()
        
        if (matches.isNotEmpty()) {
            println("✅ ${matches.size} URLs do Google Video encontradas")
            
            var found = false
            for (match in matches) {
                var videoUrl = match.value
                
                // Limpar URL
                if (videoUrl.contains("&amp;")) {
                    videoUrl = videoUrl.replace("&amp;", "&")
                }
                
                // Extrair itag
                val itagPattern = """[?&]itag=(\d+)""".toRegex()
                val itagMatch = itagPattern.find(videoUrl)
                val itag = itagMatch?.groupValues?.get(1)?.toIntOrNull() ?: 18
                
                if (createBloggerExtractorLink(itag, videoUrl, referer, name, callback)) {
                    found = true
                }
            }
            
            return found
        }
        
        return false
    }
    
    // Criar ExtractorLink para vídeo do Blogger
    private suspend fun createBloggerExtractorLink(
        itag: Int,
        videoUrl: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val quality = itagQualityMap[itag] ?: 360
        val qualityLabel = getQualityLabel(quality)
        
        println("📹 Criando link: $qualityLabel (itag: $itag)")
        
        val extractorLink = newExtractorLink(
            source = "Goyabu Blogger",
            name = "$name ($qualityLabel)",
            url = videoUrl,
            type = ExtractorLinkType.VIDEO
        ) {
            this.referer = referer
            this.quality = quality
            this.headers = mapOf(
                "Referer" to referer,
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Origin" to "https://www.blogger.com",
                "Accept" to "*/*",
                "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
                "Accept-Encoding" to "gzip, deflate, br",
                "Connection" to "keep-alive",
                "Sec-Fetch-Dest" to "video",
                "Sec-Fetch-Mode" to "no-cors",
                "Sec-Fetch-Site" to "cross-site"
            )
        }
        
        callback(extractorLink)
        return true
    }
    
    // Extrair vídeos de JavaScript
    private suspend fun extractFromJavaScript(
        doc: org.jsoup.nodes.Document,
        originalUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔍 Procurando vídeos em JavaScript...")
        
        val scripts = doc.select("script")
        for ((index, script) in scripts.withIndex()) {
            val scriptContent = script.html()
            
            // Procurar por URLs de vídeo
            val videoPattern = """https?://[^"'\s]*\.(?:googlevideo\.com|googleusercontent\.com|blogger\.com|blogspot\.com)/[^"'\s]*""".toRegex()
            val matches = videoPattern.findAll(scriptContent).toList()
            
            if (matches.isNotEmpty()) {
                println("✅ ${matches.size} URLs encontradas no script #$index")
                
                var found = false
                for (match in matches) {
                    val url = match.value
                    if (url.contains("videoplayback") || url.contains("video.g")) {
                        val itagPattern = """[?&]itag=(\d+)""".toRegex()
                        val itagMatch = itagPattern.find(url)
                        val itag = itagMatch?.groupValues?.get(1)?.toIntOrNull() ?: 18
                        
                        if (createBloggerExtractorLink(itag, url, originalUrl, name, callback)) {
                            found = true
                        }
                    }
                }
                
                if (found) return true
            }
        }
        
        return false
    }
    
    // Extrair URLs diretas
    private suspend fun extractDirectVideoUrls(
        doc: org.jsoup.nodes.Document,
        originalUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔍 Procurando URLs diretas...")
        
        // Procurar em elementos com data-video ou similares
        val videoElements = doc.select("[data-video], [data-src*='video'], video source, [href*='.mp4'], [href*='.m3u8']")
        
        for (element in videoElements) {
            val videoUrl = element.attr("data-video") 
                ?: element.attr("data-src") 
                ?: element.attr("src") 
                ?: element.attr("href")
            
            if (videoUrl != null && videoUrl.contains("http")) {
                println("✅ URL direta encontrada: ${videoUrl.take(80)}...")
                
                // Criar link direto
                val extractorLink = newExtractorLink(
                    source = "Goyabu Direct",
                    name = "$name (Direct)",
                    url = videoUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = originalUrl
                    this.quality = 720
                    this.headers = mapOf(
                        "Referer" to originalUrl,
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                    )
                }
                
                callback(extractorLink)
                return true
            }
        }
        
        return false
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
