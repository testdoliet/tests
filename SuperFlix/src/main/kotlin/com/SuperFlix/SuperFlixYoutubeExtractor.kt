package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.regex.Pattern
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

class SuperFlixYoutubeDLPExtractor : ExtractorApi() {
    override val name = "SuperFlixYoutubeDLP"
    override val mainUrl = "https://www.youtube.com"
    override val requiresReferer = false
    
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    private val mapper = jacksonObjectMapper()
    
    // Cabeçalhos que simulam o yt-dlp
    private val youtubeHeaders = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5",
        "Accept-Encoding" to "gzip, deflate, br",
        "DNT" to "1",
        "Connection" to "keep-alive",
        "Upgrade-Insecure-Requests" to "1",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1"
    )
    
    // Mapeamento de itags para qualidade (baseado no yt-dlp)
    private val itagToQuality = mapOf(
        // Vídeo MP4
        18 to Qualities.P360.value,    // 360p
        22 to Qualities.P720.value,    // 720p
        37 to Qualities.P1080.value,   // 1080p
        38 to Qualities.P1440.value,   // 1440p
        399 to Qualities.P1080.value,  // 1080p HDR
        398 to Qualities.P720.value,   // 720p HDR
        397 to Qualities.P480.value,   // 480p HDR
        396 to Qualities.P360.value,   // 360p HDR
        395 to Qualities.P240.value,   // 240p HDR
        // WebM
        43 to Qualities.P360.value,    // 360p WebM
        44 to Qualities.P480.value,    // 480p WebM
        45 to Qualities.P720.value,    // 720p WebM
        46 to Qualities.P1080.value,   // 1080p WebM
        // Áudio
        140 to 0,  // Áudio AAC 128k
        141 to 0,  // Áudio AAC 256k
        251 to 0,  // Áudio Opus 160k
        250 to 0,  // Áudio Opus 70k
        249 to 0,  // Áudio Opus 50k
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("🎬 [SuperFlixYoutubeDLP] Iniciando extração como yt-dlp")
        
        try {
            val videoId = extractYouTubeId(url) ?: run {
                println("❌ ID do vídeo não encontrado")
                return
            }
            
            println("📹 Video ID: $videoId")
            
            // Primeiro tentar método direto como yt-dlp
            if (extractDirectUrls(videoId, callback)) {
                println("✅ Método direto funcionou!")
                return
            }
            
            // Fallback para método via player
            println("🔄 Tentando método via player...")
            extractViaPlayer(videoId, callback)
            
        } catch (e: Exception) {
            println("❌ Erro no extrator: ${e.message}")
            e.printStackTrace()
        }
    }
    
    // 🎯 MÉTODO DIRETO - Igual ao yt-dlp
    private suspend fun extractDirectUrls(videoId: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            println("🔍 Buscando informações diretas como yt-dlp...")
            
            // 1. Obter página inicial
            val watchUrl = "https://www.youtube.com/watch?v=$videoId"
            val html = app.get(watchUrl, headers = youtubeHeaders).text
            println("📄 Página inicial obtida (${html.length} chars)")
            
            // 2. Extrair player_response
            val playerResponse = extractPlayerResponse(html)
            if (playerResponse == null) {
                println("❌ player_response não encontrado")
                return false
            }
            
            println("✅ player_response extraído (${playerResponse.length} chars)")
            
            // 3. Parsear JSON
            val json = mapper.readTree(playerResponse)
            val streamingData = json.path("streamingData")
            
            if (streamingData.isMissingNode) {
                println("❌ streamingData não encontrado")
                return false
            }
            
            // 4. Extrair URLs de formato adaptativo (como yt-dlp faz)
            val success = extractAdaptiveFormats(streamingData, callback)
            
            if (!success) {
                // Tentar formatos regulares
                extractRegularFormats(streamingData, callback)
            }
            
            success
            
        } catch (e: Exception) {
            println("❌ Erro no método direto: ${e.message}")
            false
        }
    }
    
    // 🔧 Extrair player_response da página
    private fun extractPlayerResponse(html: String): String? {
        // Pattern 1: var ytInitialPlayerResponse = {...};
        val pattern1 = """var ytInitialPlayerResponse\s*=\s*(\{.*?\});"""
        val regex1 = Regex(pattern1, RegexOption.DOT_MATCHES_ALL)
        
        val match1 = regex1.find(html)
        if (match1 != null) {
            println("✅ player_response encontrado via pattern1")
            return match1.groupValues[1]
        }
        
        // Pattern 2: ytInitialPlayerResponse = {...}
        val pattern2 = """ytInitialPlayerResponse\s*=\s*(\{.*?\})"""
        val regex2 = Regex(pattern2, RegexOption.DOT_MATCHES_ALL)
        
        val match2 = regex2.find(html)
        if (match2 != null) {
            println("✅ player_response encontrado via pattern2")
            return match2.groupValues[1]
        }
        
        // Pattern 3: window["ytInitialPlayerResponse"] = {...}
        val pattern3 = """window\["ytInitialPlayerResponse"\]\s*=\s*(\{.*?\})"""
        val regex3 = Regex(pattern3, RegexOption.DOT_MATCHES_ALL)
        
        val match3 = regex3.find(html)
        if (match3 != null) {
            println("✅ player_response encontrado via pattern3")
            return match3.groupValues[1]
        }
        
        return null
    }
    
    // 📦 Extrair formatos adaptativos (como o yt-dlp faz)
    private suspend fun extractAdaptiveFormats(streamingData: JsonNode, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            val adaptiveFormats = streamingData.path("adaptiveFormats")
            
            if (!adaptiveFormats.isArray || adaptiveFormats.size() == 0) {
                println("❌ Nenhum formato adaptativo encontrado")
                return false
            }
            
            println("🔍 Analisando ${adaptiveFormats.size()} formatos adaptativos")
            
            var videoFound = false
            var audioFound = false
            
            for (i in 0 until adaptiveFormats.size()) {
                val format = adaptiveFormats.get(i)
                val itag = format.path("itag").asInt(-1)
                
                // Verificar se é um formato que queremos
                if (itag in setOf(399, 398, 397, 396, 395,  # Vídeo HDR
                                  22, 37, 38, 18,           # Vídeo normal
                                  251, 250, 249,            # Áudio Opus
                                  140, 141)) {              # Áudio AAC
                    
                    val url = format.path("url").asText(null)
                    val cipher = format.path("cipher").asText(null)
                    val signatureCipher = format.path("signatureCipher").asText(null)
                    val mimeType = format.path("mimeType").asText("")
                    val bitrate = format.path("bitrate").asLong(0)
                    
                    var finalUrl: String? = null
                    
                    when {
                        url != null -> {
                            finalUrl = url
                        }
                        cipher != null -> {
                            finalUrl = extractUrlFromCipher(cipher)
                        }
                        signatureCipher != null -> {
                            finalUrl = extractUrlFromCipher(signatureCipher)
                        }
                    }
                    
                    if (finalUrl != null) {
                        val quality = itagToQuality[itag] ?: Qualities.P360.value
                        val qualityName = getQualityName(itag, mimeType, bitrate)
                        
                        println("✅ Formato encontrado: itag=$itag $qualityName")
                        
                        // Determinar tipo de conteúdo
                        val isVideo = mimeType.contains("video/")
                        val isAudio = mimeType.contains("audio/")
                        
                        val linkType = if (isAudio) ExtractorLinkType.AUDIO else ExtractorLinkType.VIDEO
                        
                        val extractorLink = newExtractorLink(
                            source = name,
                            name = "YouTube $qualityName",
                            url = finalUrl,
                            type = linkType
                        ) {
                            this.referer = mainUrl
                            this.quality = quality
                            this.headers = youtubeHeaders
                        }
                        
                        callback(extractorLink)
                        
                        if (isVideo) videoFound = true
                        if (isAudio) audioFound = true
                        
                        // Parar após encontrar alguns formatos
                        if (videoFound && audioFound) break
                    }
                }
            }
            
            return videoFound || audioFound
            
        } catch (e: Exception) {
            println("❌ Erro em extractAdaptiveFormats: ${e.message}")
            return false
        }
    }
    
    // 🎵 Extrair formatos regulares
    private suspend fun extractRegularFormats(streamingData: JsonNode, callback: (ExtractorLink) -> Unit) {
        try {
            val formats = streamingData.path("formats")
            
            if (!formats.isArray || formats.size() == 0) {
                return
            }
            
            println("📦 Analisando ${formats.size()} formatos regulares")
            
            for (i in 0 until minOf(5, formats.size())) {
                val format = formats.get(i)
                val itag = format.path("itag").asInt(-1)
                val url = format.path("url").asText(null)
                val mimeType = format.path("mimeType").asText("")
                val qualityLabel = format.path("qualityLabel").asText("")
                
                if (url != null && itag > 0) {
                    val quality = when {
                        qualityLabel.contains("1080") -> Qualities.P1080.value
                        qualityLabel.contains("720") -> Qualities.P720.value
                        qualityLabel.contains("480") -> Qualities.P480.value
                        qualityLabel.contains("360") -> Qualities.P360.value
                        else -> itagToQuality[itag] ?: Qualities.P360.value
                    }
                    
                    val qualityName = if (qualityLabel.isNotBlank()) qualityLabel else getQualityName(itag, mimeType, 0)
                    
                    println("✅ Formato regular: itag=$itag $qualityName")
                    
                    val extractorLink = newExtractorLink(
                        source = name,
                        name = "YouTube $qualityName",
                        url = url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                        this.quality = quality
                        this.headers = youtubeHeaders
                    }
                    
                    callback(extractorLink)
                }
            }
            
        } catch (e: Exception) {
            println("⚠️ Erro em extractRegularFormats: ${e.message}")
        }
    }
    
    // 🔐 Extrair URL do cipher
    private fun extractUrlFromCipher(cipher: String): String? {
        return try {
            val decoded = URLDecoder.decode(cipher, "UTF-8")
            
            // Procurar parâmetro url
            val params = decoded.split("&")
            var url: String? = null
            var signature: String? = null
            var signatureParam: String? = null
            
            for (param in params) {
                when {
                    param.startsWith("url=") -> {
                        url = param.substring(4)
                    }
                    param.startsWith("s=") -> {
                        signature = param.substring(2)
                    }
                    param.startsWith("sp=") -> {
                        signatureParam = param.substring(3)
                    }
                }
            }
            
            if (url == null) return null
            
            var finalUrl = URLDecoder.decode(url, "UTF-8")
            
            // Adicionar signature se existir
            if (signature != null) {
                val decodedSignature = URLDecoder.decode(signature, "UTF-8")
                val paramName = signatureParam ?: "signature"
                finalUrl += "&$paramName=$decodedSignature"
            }
            
            finalUrl
            
        } catch (e: Exception) {
            println("❌ Erro decodificando cipher: ${e.message}")
            null
        }
    }
    
    // 🎮 Método alternativo via player (fallback)
    private suspend fun extractViaPlayer(videoId: String, callback: (ExtractorLink) -> Unit) {
        try {
            println("🎮 Usando método via player...")
            
            // URL do embed (às vezes funciona melhor)
            val embedUrl = "https://www.youtube.com/embed/$videoId"
            val embedHtml = app.get(embedUrl, headers = youtubeHeaders).text
            
            // Extrair configurações
            val config = extractConfigFromEmbed(embedHtml)
            
            if (config != null) {
                val playerUrl = config["PLAYER_JS_URL"] ?: return
                val sts = config["STS"] ?: ""
                
                println("🎯 Player JS URL: $playerUrl")
                
                // Tentar obter URLs do player
                extractFromPlayerJS(videoId, playerUrl, sts, callback)
            }
            
        } catch (e: Exception) {
            println("❌ Erro no método via player: ${e.message}")
        }
    }
    
    // 🔧 Extrair configuração do embed
    private fun extractConfigFromEmbed(html: String): Map<String, String>? {
        val patterns = listOf(
            """PLAYER_JS_URL"\s*:\s*"([^"]+)"""",
            """"sts"\s*:\s*(\d+)"""
        )
        
        var playerUrl: String? = null
        var sts: String? = null
        
        for (pattern in patterns) {
            val regex = Regex(pattern)
            val match = regex.find(html)
            
            if (match != null) {
                when {
                    pattern.contains("PLAYER_JS_URL") -> playerUrl = match.groupValues[1]
                    pattern.contains("sts") -> sts = match.groupValues[1]
                }
            }
        }
        
        return if (playerUrl != null) {
            mapOf("PLAYER_JS_URL" to playerUrl, "STS" to (sts ?: ""))
        } else {
            null
        }
    }
    
    // 🔧 Extrair do player JS
    private suspend fun extractFromPlayerJS(videoId: String, playerUrl: String, sts: String, callback: (ExtractorLink) -> Unit) {
        try {
            // Este é um método simplificado
            // Em um extrator real, você precisaria implementar a lógica completa de decodificação
            println("⚠️ Método player JS requer implementação completa")
            
        } catch (e: Exception) {
            println("❌ Erro no player JS: ${e.message}")
        }
    }
    
    // 🏷️ Obter nome da qualidade baseado no itag
    private fun getQualityName(itag: Int, mimeType: String, bitrate: Long): String {
        return when (itag) {
            // Vídeo HDR
            399 -> "1080p HDR"
            398 -> "720p HDR"
            397 -> "480p HDR"
            396 -> "360p HDR"
            395 -> "240p HDR"
            
            // Vídeo MP4
            18 -> "360p"
            22 -> "720p"
            37 -> "1080p"
            38 -> "1440p"
            
            // Vídeo WebM
            43 -> "360p WebM"
            44 -> "480p WebM"
            45 -> "720p WebM"
            46 -> "1080p WebM"
            
            // Áudio
            140 -> "AAC 128k"
            141 -> "AAC 256k"
            251 -> "Opus 160k"
            250 -> "Opus 70k"
            249 -> "Opus 50k"
            
            else -> {
                if (mimeType.contains("video/")) {
                    "Video ${bitrate / 1000}k"
                } else if (mimeType.contains("audio/")) {
                    "Audio ${bitrate / 1000}k"
                } else {
                    "Unknown"
                }
            }
        }
    }
    
    // 🔧 Extrair ID do YouTube
    private fun extractYouTubeId(url: String): String? {
        val patterns = listOf(
            """v=([a-zA-Z0-9_-]{11})""",
            """youtu\.be/([a-zA-Z0-9_-]{11})""",
            """/embed/([a-zA-Z0-9_-]{11})""",
            """/v/([a-zA-Z0-9_-]{11})""",
            """watch/([a-zA-Z0-9_-]{11})"""
        )
        
        for (pattern in patterns) {
            val regex = pattern.toRegex()
            val match = regex.find(url)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        
        return null
    }
    
    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val links = mutableListOf<ExtractorLink>()
        
        getUrl(url, referer, {}, { link ->
            links.add(link)
        })
        
        return if (links.isNotEmpty()) links else null
    }
}
