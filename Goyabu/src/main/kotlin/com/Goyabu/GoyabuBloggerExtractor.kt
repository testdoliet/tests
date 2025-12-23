package com.Goyabu

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getAndUnpack
import org.jsoup.Jsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

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
            // PRIMEIRO: Tentar métodos do Blogger
            val pageResponse = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                    "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
                    "Accept-Encoding" to "gzip, deflate, br",
                    "DNT" to "1",
                    "Connection" to "keep-alive",
                    "Upgrade-Insecure-Requests" to "1"
                ),
                timeout = 30
            )
            
            val html = pageResponse.text
            val doc = Jsoup.parse(html)
            
            // MÉTODO 1: Procurar vídeos do Blogger diretamente
            if (extractBloggerVideo(doc, url, name, callback)) {
                println("✅ GOYABU EXTRACTOR: Vídeo do Blogger encontrado")
                return true
            }
            
            // MÉTODO 2: Procurar por iframes do Blogger
            if (extractBloggerIframes(doc, url, name, callback)) {
                println("✅ GOYABU EXTRACTOR: Vídeo via iframe encontrado")
                return true
            }
            
            // MÉTODO 3: Procurar player embed customizado
            if (extractEmbeddedVideo(doc, url, name, callback)) {
                println("✅ GOYABU EXTRACTOR: Vídeo embed encontrado")
                return true
            }
            
            // MÉTODO 4: Procurar por player.goyabu
            if (extractGoyabuPlayer(doc, url, name, callback)) {
                println("✅ GOYABU EXTRACTOR: Vídeo do player.goyabu encontrado")
                return true
            }
            
            println("❌ GOYABU EXTRACTOR: Nenhum vídeo encontrado")
            false
            
        } catch (e: Exception) {
            println("❌ GOYABU EXTRACTOR: Erro na extração: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    // MÉTODO 1: Extrair vídeo do Blogger via iframe
    private suspend fun extractBloggerIframes(
        doc: org.jsoup.nodes.Document,
        originalUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔍 GOYABU EXTRACTOR: Procurando iframes do Blogger...")
        
        val iframes = doc.select("iframe")
        println("📊 ${iframes.size} iframes encontrados")
        
        for ((index, iframe) in iframes.withIndex()) {
            val src = iframe.attr("src")
            if (src.contains("blogger.com/video.g")) {
                println("✅ Iframe do Blogger encontrado (#${index + 1}): $src")
                
                return try {
                    val iframeResponse = app.get(
                        src,
                        headers = mapOf(
                            "Referer" to originalUrl,
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
                        )
                    )
                    
                    extractFromBloggerHtml(iframeResponse.text, src, originalUrl, name, callback)
                    
                } catch (e: Exception) {
                    println("❌ Erro ao acessar iframe: ${e.message}")
                    false
                }
            }
        }
        
        return false
    }
    
    // MÉTODO 2: Extrair vídeo do Blogger diretamente do HTML
    private suspend fun extractBloggerVideo(
        doc: org.jsoup.nodes.Document,
        originalUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔍 GOYABU EXTRACTOR: Procurando configurações do Blogger...")
        
        // Buscar por var VIDEO_CONFIG
        val scriptTags = doc.select("script")
        for ((index, script) in scriptTags.withIndex()) {
            val scriptContent = script.html()
            
            // Padrão 1: var VIDEO_CONFIG = {...}
            val configPattern = """var\s+VIDEO_CONFIG\s*=\s*(\{.*?\})\s*;""".toRegex(RegexOption.DOT_MATCHES_ALL)
            val configMatch = configPattern.find(scriptContent)
            
            if (configMatch != null) {
                println("✅ VIDEO_CONFIG encontrado no script #$index")
                return extractFromBloggerJson(configMatch.groupValues[1], originalUrl, name, callback)
            }
            
            // Padrão 2: VIDEO_CONFIG = {...}
            val configPattern2 = """VIDEO_CONFIG\s*=\s*(\{.*?\})\s*;""".toRegex(RegexOption.DOT_MATCHES_ALL)
            val configMatch2 = configPattern2.find(scriptContent)
            
            if (configMatch2 != null) {
                println("✅ VIDEO_CONFIG encontrado (padrão 2) no script #$index")
                return extractFromBloggerJson(configMatch2.groupValues[1], originalUrl, name, callback)
            }
            
            // Padrão 3: Procura direta por URLs do Blogger
            if (extractDirectVideoUrls(scriptContent, originalUrl, name, callback)) {
                return true
            }
        }
        
        return false
    }
    
    // MÉTODO 3: Extrair vídeos embedded customizados
    private suspend fun extractEmbeddedVideo(
        doc: org.jsoup.nodes.Document,
        originalUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔍 GOYABU EXTRACTOR: Procurando players customizados...")
        
        // Procurar por divs de vídeo
        val videoDivs = doc.select("div[class*='video'], div[class*='player'], #player, #video-player")
        
        for (div in videoDivs) {
            val html = div.html()
            
            // Procurar por URL googlevideo
            val videoPattern = """https?://[^"'\s<>]*\.googlevideo\.com/[^"'\s<>]+""".toRegex()
            val matches = videoPattern.findAll(html).toList()
            
            if (matches.isNotEmpty()) {
                println("✅ URL do Google Video encontrada")
                return processGooglevideoUrls(matches, originalUrl, name, callback)
            }
        }
        
        return false
    }
    
    // MÉTODO 4: Extrair do player.goyabu (se houver)
    private suspend fun extractGoyabuPlayer(
        doc: org.jsoup.nodes.Document,
        originalUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔍 GOYABU EXTRACTOR: Procurando player.goyabu...")
        
        // Procurar por scripts que possam conter dados do player
        val scripts = doc.select("script")
        
        for (script in scripts) {
            val content = script.html()
            
            // Procurar por padrões comuns de players de vídeo
            if (content.contains("player.goyabu") || content.contains("playerConfig") || content.contains("videoSources")) {
                println("🔍 Padrão de player encontrado, analisando...")
                
                // Tentar extrair URLs de diferentes padrões
                val patterns = listOf(
                    """(https?://[^"'\s<>]*\.(?:mp4|m3u8|mkv|webm|avi)[^"'\s<>]*)""".toRegex(),
                    """src\s*:\s*['"]([^"']+)['"]""".toRegex(),
                    """url\s*:\s*['"]([^"']+)['"]""".toRegex(),
                    """file\s*:\s*['"]([^"']+)['"]""".toRegex()
                )
                
                for (pattern in patterns) {
                    val matches = pattern.findAll(content).toList()
                    if (matches.isNotEmpty()) {
                        println("✅ URLs encontradas com padrão: ${pattern.pattern}")
                        
                        for (match in matches) {
                            val videoUrl = match.groupValues[1]
                            if (videoUrl.contains("http")) {
                                val extractorLink = newExtractorLink(
                                    source = "Goyabu",
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
                    }
                }
            }
        }
        
        return false
    }
    
    // Função auxiliar: Extrair de JSON do Blogger
    private suspend fun extractFromBloggerJson(
        jsonString: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val json = Json.parseToJsonElement(jsonString).jsonObject
            
            // Tentar diferentes estruturas de dados do Blogger
            val streams = when {
                json["streams"] != null -> json["streams"]?.jsonObject?.entries
                json["play_url"] != null -> listOf(json)
                else -> null
            }
            
            var found = false
            streams?.forEach { (_, value) ->
                try {
                    val streamObj = value.jsonObject
                    val videoUrl = streamObj["play_url"]?.jsonPrimitive?.content
                        ?: streamObj["url"]?.jsonPrimitive?.content
                        ?: return@forEach
                    
                    val itag = streamObj["format_id"]?.jsonPrimitive?.content?.toIntOrNull()
                        ?: streamObj["itag"]?.jsonPrimitive?.content?.toIntOrNull()
                        ?: 18
                    
                    val quality = itagQualityMap[itag] ?: 360
                    val qualityLabel = getQualityLabel(quality)
                    
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
                            "Origin" to "https://www.blogger.com"
                        )
                    }
                    
                    callback(extractorLink)
                    found = true
                    println("✅ Stream extraído: $qualityLabel (itag: $itag)")
                    
                } catch (e: Exception) {
                    println("⚠️ Erro ao processar stream: ${e.message}")
                }
            }
            
            found
            
        } catch (e: Exception) {
            println("❌ Erro ao parsear JSON: ${e.message}")
            false
        }
    }
    
    // Função auxiliar: Extrair de HTML do Blogger
    private suspend fun extractFromBloggerHtml(
        html: String,
        iframeUrl: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔍 Analisando HTML do iframe...")
        
        // Tentar extrair do padrão JSON
        val jsonPattern = """\{.*?"streams".*?\}""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val jsonMatch = jsonPattern.find(html)
        
        if (jsonMatch != null) {
            println("✅ JSON encontrado no iframe")
            return extractFromBloggerJson(jsonMatch.value, referer, name, callback)
        }
        
        // Se não encontrar JSON, procurar URLs diretamente
        return extractDirectVideoUrls(html, referer, name, callback)
    }
    
    // Função auxiliar: Extrair URLs diretas de vídeo
    private fun extractDirectVideoUrls(
        content: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Procurar URLs do googlevideo
        val videoPattern = """https?://[^"'\s<>]*\.googlevideo\.com/[^"'\s<>]+""".toRegex()
        val matches = videoPattern.findAll(content).toList()
        
        if (matches.isNotEmpty()) {
            println("✅ ${matches.size} URLs do Google Video encontradas")
            return processGooglevideoUrls(matches, referer, name, callback)
        }
        
        return false
    }
    
    // Função auxiliar: Processar URLs do Google Video
    private fun processGooglevideoUrls(
        matches: List<Regex.MatchResult>,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        
        for (match in matches) {
            var videoUrl = match.value
            
            // Decodificar URL se necessário
            if (videoUrl.contains("&amp;")) {
                videoUrl = videoUrl.replace("&amp;", "&")
            }
            
            // Extrair itag da URL
            val itagPattern = """[?&]itag=(\d+)""".toRegex()
            val itagMatch = itagPattern.find(videoUrl)
            val itag = itagMatch?.groupValues?.get(1)?.toIntOrNull() ?: 18
            
            val quality = itagQualityMap[itag] ?: 360
            val qualityLabel = getQualityLabel(quality)
            
            println("📹 Processando vídeo: $qualityLabel (itag: $itag)")
            
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
                    "Origin" to "https://www.blogger.com"
                )
            }
            
            callback(extractorLink)
            found = true
        }
        
        return found
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
