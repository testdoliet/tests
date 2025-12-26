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
import java.util.regex.Pattern

class SuperFlixYoutubeExtractor : ExtractorApi() {
    override val name = "SuperFlixYouTube"
    override val mainUrl = "https://www.youtube.com"
    override val requiresReferer = false
    
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private val mapper = jacksonObjectMapper()
    
    // Headers para requests
    private val headers = mapOf(
        "User-Agent" to userAgent,
        "Accept-Language" to "en-US,en;q=0.9",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Encoding" to "gzip, deflate, br"
    )

    // Mapa completo de itags - PRIORIDADE: formatos COM ÁUDIO primeiro
    private val priorityItags = mapOf(
        // Formatos COM ÁUDIO (mais importantes para trailers)
        22 to Qualities.P720.value,   // mp4 720p COM ÁUDIO (ESSENCIAL!)
        37 to Qualities.P1080.value,  // mp4 1080p COM ÁUDIO
        46 to Qualities.P1080.value,  // WebM 1080p COM ÁUDIO
        18 to Qualities.P360.value,   // mp4 360p COM ÁUDIO (fallback)
        
        // Formatos de vídeo only (podem ser combinados com áudio)
        313 to Qualities.P2160.value, // vp9 2160p
        315 to Qualities.P2160.value, // vp9 2160p HDR
        266 to Qualities.P2160.value, // avc1 2160p
        137 to Qualities.P1080.value, // avc1 1080p
        299 to Qualities.P1080.value, // avc1 1080p60
        248 to Qualities.P1080.value, // vp9 1080p
        247 to Qualities.P720.value,  // vp9 720p
        244 to Qualities.P480.value,  // vp9 480p
        243 to Qualities.P360.value,  // vp9 360p
        
        // Áudio only
        140 to Qualities.P720.value,  // mp4 áudio 128kbps
        251 to Qualities.P720.value,  // WebM áudio 160kbps
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("🎬 [SuperFlix] YouTubeExtractor - BUSCANDO QUALIDADES ALTAS")
        
        try {
            val videoId = extractYouTubeId(url) ?: run {
                println("❌ Video ID não encontrado")
                return
            }
            
            println("📹 Video ID: $videoId")
            
            // Método 1: Tentar via API interna (HLS ou adaptiveFormats)
            if (!extractViaAPI(videoId, callback)) {
                // Método 2: Fallback para métodos alternativos
                println("🔄 API falhou, tentando métodos alternativos...")
                extractViaAlternativeMethods(videoId, callback)
            }
            
        } catch (e: Exception) {
            println("❌ Erro geral no extrator: ${e.message}")
            e.printStackTrace()
        }
    }

    // 🔥 **MÉTODO PRINCIPAL: Usar API interna do YouTube**
    private suspend fun extractViaAPI(videoId: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            println("🔗 Tentando API interna do YouTube...")
            
            // 1. Pegar configuração
            val config = getYouTubeConfig(videoId) ?: run {
                println("❌ Não consegui pegar configuração da página")
                return false
            }
            
            val apiKey = config["INNERTUBE_API_KEY"] ?: run {
                println("❌ API Key não encontrada")
                return false
            }
            
            val clientVersion = config["INNERTUBE_CLIENT_VERSION"] ?: "2.20241220.00.00"
            val visitorData = config["VISITOR_DATA"] ?: ""
            
            println("✅ Config obtida: API Key=${apiKey.take(10)}..., Version=$clientVersion")
            
            // 2. Chamar API do YouTube
            val apiUrl = "https://www.youtube.com/youtubei/v1/player?key=$apiKey"
            println("📡 Chamando API: $apiUrl")
            
            val requestBody = """
            {
                "context": {
                    "client": {
                        "hl": "en",
                        "gl": "US",
                        "clientName": "WEB",
                        "clientVersion": "$clientVersion",
                        "visitorData": "$visitorData",
                        "platform": "DESKTOP",
                        "userAgent": "$userAgent"
                    }
                },
                "videoId": "$videoId",
                "playbackContext": {
                    "contentPlaybackContext": {
                        "html5Preference": "HTML5_PREF_WANTS"
                    }
                }
            }
            """.trimIndent()
            
            val jsonMediaType = "application/json; charset=utf-8".toMediaType()
            
            val response = app.post(
                apiUrl,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Accept-Language" to "en-US,en;q=0.5",
                    "Content-Type" to "application/json",
                    "Origin" to "https://www.youtube.com",
                    "Referer" to "https://www.youtube.com"
                ),
                requestBody = requestBody.toRequestBody(jsonMediaType),
                timeout = 20000
            )
            
            if (response.code != 200) {
                println("❌ API retornou status ${response.code}")
                return false
            }
            
            val jsonText = response.text
            println("✅ Resposta API recebida (${jsonText.length} chars)")
            
            // 3. Parsear resposta JSON
            val rootNode = mapper.readTree(jsonText)
            val streamingData = rootNode.path("streamingData")
            
            if (streamingData.isMissingNode) {
                println("❌ streamingData não encontrado na resposta")
                return false
            }
            
            // 4. Tentar HLS primeiro
            val hlsUrl = streamingData.path("hlsManifestUrl").asText(null)
            if (hlsUrl != null) {
                println("🎯 URL HLS encontrada: ${hlsUrl.take(80)}...")
                extractQualitiesFromHLSManual(hlsUrl, callback)
                return true
            }
            
            // 5. Se não tiver HLS, usar adaptiveFormats
            println("⚠️ HLS não disponível, usando adaptiveFormats...")
            val success = extractAdaptiveFormats(streamingData, callback)
            
            if (!success) {
                // 6. Última tentativa: usar formatos com áudio combinado
                println("🔄 Tentando extrair formatos com áudio...")
                extractCombinedFormats(rootNode, callback)
            }
            
            success
            
        } catch (e: Exception) {
            println("❌ Erro na API: ${e.message}")
            false
        }
    }
    
    // 🔧 **Extrair adaptiveFormats (método principal)**
    private suspend fun extractAdaptiveFormats(streamingData: JsonNode, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val adaptiveFormats = streamingData.path("adaptiveFormats")
            if (!adaptiveFormats.isArray || adaptiveFormats.size() == 0) {
                println("❌ Nenhum adaptiveFormat encontrado")
                return false
            }
            
            println("📊 Total de formatos encontrados: ${adaptiveFormats.size()}")
            
            // Separar vídeo e áudio
            val videoFormats = mutableListOf<JsonNode>()
            val audioFormats = mutableListOf<JsonNode>()
            
            for (i in 0 until adaptiveFormats.size()) {
                val format = adaptiveFormats[i]
                val mimeType = format.path("mimeType").asText("").lowercase()
                
                if (mimeType.contains("video/")) {
                    videoFormats.add(format)
                } else if (mimeType.contains("audio/")) {
                    audioFormats.add(format)
                }
            }
            
            println("🎬 Formatos de vídeo: ${videoFormats.size()}")
            println("🎵 Formatos de áudio: ${audioFormats.size()}")
            
            // DEBUG: Mostrar alguns formatos
            if (videoFormats.size() > 0) {
                println("📋 Primeiros formatos de vídeo:")
                for (i in 0 until minOf(5, videoFormats.size())) {
                    val format = videoFormats[i]
                    val itag = format.path("itag").asInt(-1)
                    val qualityLabel = format.path("qualityLabel").asText("Sem label")
                    val mimeType = format.path("mimeType").asText("")
                    val hasAudio = !format.path("audioQuality").isMissingNode
                    
                    println("   itag=$itag | $qualityLabel | $mimeType | audio=$hasAudio")
                }
            }
            
            var foundCount = 0
            
            // PRIMEIRO: Buscar formatos COM ÁUDIO INCLUSO (para trailers)
            for (format in videoFormats) {
                val itag = format.path("itag").asInt(-1)
                val url = format.path("url").asText(null)
                val qualityLabel = format.path("qualityLabel").asText("")
                val mimeType = format.path("mimeType").asText("")
                
                // Verificar se é um formato COM ÁUDIO (ideal para trailers)
                val hasAudio = !format.path("audioQuality").isMissingNode || 
                              mimeType.contains("mp4a") ||
                              itag in listOf(22, 37, 46, 18, 43, 45)
                
                if (url != null && hasAudio) {
                    println("✅ Formato COM ÁUDIO encontrado: itag=$itag ($qualityLabel)")
                    
                    val quality = when {
                        qualityLabel.contains("2160") || qualityLabel.contains("4K") -> Qualities.P2160.value
                        qualityLabel.contains("1440") -> Qualities.P1440.value
                        qualityLabel.contains("1080") || itag in listOf(37, 46, 137) -> Qualities.P1080.value
                        qualityLabel.contains("720") || itag == 22 -> Qualities.P720.value  // 720p COM ÁUDIO!
                        qualityLabel.contains("480") || itag == 59 -> Qualities.P480.value
                        else -> Qualities.P360.value
                    }
                    
                    val qualityName = when (quality) {
                        Qualities.P2160.value -> "4K"
                        Qualities.P1440.value -> "1440p"
                        Qualities.P1080.value -> "1080p"
                        Qualities.P720.value -> "720p"
                        Qualities.P480.value -> "480p"
                        else -> "360p"
                    }
                    
                    createExtractorLink(url, quality, qualityName, callback)
                    foundCount++
                    
                    // Priorizar 720p com áudio para trailers
                    if (itag == 22) {
                        println("🏆 Encontrado 720p COM ÁUDIO (PERFEITO PARA TRAILERS!)")
                    }
                    
                    if (foundCount >= 3) break
                }
            }
            
            // SEGUNDO: Se não encontrou formatos com áudio, buscar qualquer vídeo
            if (foundCount == 0) {
                println("🔍 Nenhum formato com áudio encontrado, buscando qualquer vídeo...")
                
                for (format in videoFormats) {
                    val itag = format.path("itag").asInt(-1)
                    val url = format.path("url").asText(null)
                    val qualityLabel = format.path("qualityLabel").asText("")
                    
                    if (url != null) {
                        println("✅ Vídeo encontrado: itag=$itag ($qualityLabel)")
                        
                        val quality = when {
                            qualityLabel.contains("2160") -> Qualities.P2160.value
                            qualityLabel.contains("1440") -> Qualities.P1440.value
                            qualityLabel.contains("1080") -> Qualities.P1080.value
                            qualityLabel.contains("720") -> Qualities.P720.value
                            qualityLabel.contains("480") -> Qualities.P480.value
                            else -> Qualities.P360.value
                        }
                        
                        val qualityName = when (quality) {
                            Qualities.P2160.value -> "4K"
                            Qualities.P1440.value -> "1440p"
                            Qualities.P1080.value -> "1080p"
                            Qualities.P720.value -> "720p"
                            Qualities.P480.value -> "480p"
                            else -> "360p"
                        }
                        
                        createExtractorLink(url, quality, qualityName, callback)
                        foundCount++
                        
                        if (foundCount >= 2) break
                    }
                }
            }
            
            println("✨ Extraídos $foundCount qualidades via adaptiveFormats")
            foundCount > 0
            
        } catch (e: Exception) {
            println("❌ Erro em extractAdaptiveFormats: ${e.message}")
            false
        }
    }
    
    // 🔧 **Extrair formatos combinados (áudio + vídeo)**
    private suspend fun extractCombinedFormats(rootNode: JsonNode, callback: (ExtractorLink) -> Unit) {
        try {
            // Tentar pegar streamingData de outros lugares
            val streamingData = rootNode.path("streamingData")
            
            // Procurar por "formats" (formats simples com áudio)
            val formats = rootNode.path("streamingData").path("formats")
            if (formats.isArray && formats.size() > 0) {
                println("📦 Encontrados ${formats.size()} formatos combinados")
                
                for (i in 0 until formats.size()) {
                    val format = formats[i]
                    val itag = format.path("itag").asInt(-1)
                    val url = format.path("url").asText(null)
                    val qualityLabel = format.path("qualityLabel").asText("")
                    
                    if (url != null && (itag == 22 || itag == 18 || itag == 37)) {
                        println("✅ Formato combinado: itag=$itag ($qualityLabel)")
                        
                        val quality = when (itag) {
                            37 -> Qualities.P1080.value
                            22 -> Qualities.P720.value
                            else -> Qualities.P360.value
                        }
                        
                        val qualityName = when (quality) {
                            Qualities.P1080.value -> "1080p"
                            Qualities.P720.value -> "720p"
                            else -> "360p"
                        }
                        
                        createExtractorLink(url, quality, qualityName, callback)
                    }
                }
            }
            
        } catch (e: Exception) {
            println("❌ Erro em extractCombinedFormats: ${e.message}")
        }
    }
    
    // 🔧 **Criar ExtractorLink (SUSPEND function)**
    private suspend fun createExtractorLink(
        videoUrl: String,
        quality: Int,
        qualityText: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            println("🔗 Criando link: $qualityText")
            
            val extractorLink = newExtractorLink(
                source = name,
                name = "YouTube ($qualityText)",
                url = videoUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = "https://www.youtube.com"
                this.quality = quality
                this.headers = mapOf(
                    "Referer" to "https://www.youtube.com",
                    "User-Agent" to userAgent,
                    "Origin" to "https://www.youtube.com"
                )
            }
            
            callback(extractorLink)
            println("✨ Link $qualityText criado com sucesso!")
            
        } catch (e: Exception) {
            println("❌ Erro criando link: ${e.message}")
        }
    }
    
    // 🎬 **Extrair qualidades do HLS manualmente**
    private suspend fun extractQualitiesFromHLSManual(hlsUrl: String, callback: (ExtractorLink) -> Unit) {
        println("🎬 Parseando HLS manualmente...")
        
        try {
            val response = app.get(hlsUrl, headers = headers, timeout = 15000)
            if (response.code != 200) {
                println("❌ Falha ao baixar HLS: ${response.code}")
                return
            }
            
            val m3u8Content = response.text
            val lines = m3u8Content.lines()
            
            var currentQuality = ""
            var currentBandwidth = 0
            
            for (line in lines) {
                when {
                    line.startsWith("#EXT-X-STREAM-INF:") -> {
                        currentQuality = extractQualityFromM3u8Line(line)
                        currentBandwidth = extractBandwidthFromM3u8Line(line)
                    }
                    !line.startsWith("#") && line.isNotBlank() && currentQuality.isNotBlank() -> {
                        val streamUrl = if (line.startsWith("http")) line else resolveRelativeUrl(hlsUrl, line)
                        
                        val qualityValue = when {
                            currentQuality.contains("2160") || currentBandwidth > 8000000 -> Qualities.P2160.value
                            currentQuality.contains("1440") || currentBandwidth > 5000000 -> Qualities.P1440.value
                            currentQuality.contains("1080") || currentBandwidth > 2500000 -> Qualities.P1080.value
                            currentQuality.contains("720") || currentBandwidth > 1000000 -> Qualities.P720.value
                            currentQuality.contains("480") || currentBandwidth > 500000 -> Qualities.P480.value
                            else -> Qualities.P360.value
                        }
                        
                        val qualityName = when (qualityValue) {
                            Qualities.P2160.value -> "4K"
                            Qualities.P1440.value -> "1440p"
                            Qualities.P1080.value -> "1080p"
                            Qualities.P720.value -> "720p"
                            Qualities.P480.value -> "480p"
                            else -> "360p"
                        }
                        
                        println("✅ Qualidade HLS: $qualityName ($currentQuality, ${currentBandwidth/1000}Kbps)")
                        
                        createExtractorLink(streamUrl, qualityValue, qualityName, callback)
                        currentQuality = ""
                    }
                }
            }
            
            println("✨ Qualidades HLS extraídas com sucesso!")
            
        } catch (e: Exception) {
            println("❌ Erro parseando HLS: ${e.message}")
        }
    }
    
    // 🔄 **Métodos alternativos se API falhar**
    private suspend fun extractViaAlternativeMethods(videoId: String, callback: (ExtractorLink) -> Unit) {
        println("🔄 Tentando métodos alternativos...")
        
        // Método 1: Invidious (yewtu.be)
        try {
            val invidiousUrl = "https://yewtu.be/latest_version?id=$videoId"
            println("🌐 Tentando Invidious: $invidiousUrl")
            
            val response = app.get(invidiousUrl, timeout = 10000)
            if (response.code == 200) {
                val html = response.text
                extractLinksFromHTML(html, callback)
            }
        } catch (e: Exception) {
            println("❌ Invidious falhou: ${e.message}")
        }
        
        // Método 2: Piped (alternativa)
        try {
            val pipedUrl = "https://pipedapi.kavin.rocks/streams/$videoId"
            println("🌐 Tentando Piped API: $pipedUrl")
            
            val response = app.get(pipedUrl, timeout = 10000)
            if (response.code == 200) {
                val json = mapper.readTree(response.text)
                val videoUrl = json.path("videoStreams")
                    .find { it.path("quality").asText("").contains("720") }
                    ?.path("url")?.asText(null)
                
                if (videoUrl != null) {
                    println("✅ Piped encontrou vídeo 720p")
                    createExtractorLink(videoUrl, Qualities.P720.value, "720p (Piped)", callback)
                }
            }
        } catch (e: Exception) {
            println("❌ Piped falhou: ${e.message}")
        }
    }
    
    // 🔍 **Extrair links do HTML (método antigo como fallback)**
    private suspend fun extractLinksFromHTML(html: String, callback: (ExtractorLink) -> Unit) {
        // Padrões para encontrar URLs
        val patterns = listOf(
            """hlsManifestUrl["']?\s*:\s*["']([^"']+)["']""",
            """url_encoded_fmt_stream_map["']?\s*:\s*["']([^"']+)["']""",
            """adaptive_fmts["']?\s*:\s*["']([^"']+)["']""",
            """(https?://[^"\s]+googlevideo\.com/videoplayback[^"\s]*itag=22[^"\s]*)"""
        )
        
        for (pattern in patterns) {
            try {
                val regex = pattern.toRegex()
                val matches = regex.findAll(html)
                
                matches.forEach { match ->
                    val found = match.groupValues[1]
                    if (found.isNotBlank()) {
                        println("🔗 Encontrado no HTML: ${found.take(80)}...")
                        
                        // Verificar se é uma URL direta
                        if (found.startsWith("http")) {
                            val quality = if (found.contains("itag=22")) Qualities.P720.value else Qualities.P360.value
                            val qualityName = if (quality == Qualities.P720.value) "720p" else "360p"
                            
                            createExtractorLink(found, quality, qualityName, callback)
                        }
                    }
                }
            } catch (e: Exception) {
                // Continuar
            }
        }
    }
    
    // 🔑 **Obter configuração do YouTube (ytcfg)**
    private suspend fun getYouTubeConfig(videoId: String): Map<String, String>? {
        return withContext(Dispatchers.IO) {
            try {
                val url = "https://www.youtube.com/watch?v=$videoId"
                println("📄 Baixando página do YouTube...")
                
                val response = app.get(url, headers = headers, timeout = 15000)
                if (response.code != 200) {
                    println("❌ Página retornou status ${response.code}")
                    return@withContext null
                }
                
                val html = response.text
                
                // Extrair ytcfg usando regex (igual ao plugin original)
                val pattern = """ytcfg\.set\(\s*(\{.*?\})\s*\)\s*;""".toRegex(RegexOption.DOT_MATCHES_ALL)
                val match = pattern.find(html)
                
                if (match != null) {
                    val jsonStr = match.groupValues[1]
                    println("✅ ytcfg encontrado (${jsonStr.length} chars)")
                    
                    val ytCfg = mapper.readTree(jsonStr)
                    
                    val apiKey = ytCfg.path("INNERTUBE_API_KEY").asText(null)
                    val clientVersion = ytCfg.path("INNERTUBE_CLIENT_VERSION").asText("2.20241220.00.00")
                    val visitorData = ytCfg.path("VISITOR_DATA").asText("")
                    
                    if (apiKey != null && apiKey.isNotBlank()) {
                        println("🔑 Config extraída: API Key=${apiKey.take(10)}..., Version=$clientVersion")
                        return@withContext mapOf(
                            "INNERTUBE_API_KEY" to apiKey,
                            "INNERTUBE_CLIENT_VERSION" to clientVersion,
                            "VISITOR_DATA" to visitorData
                        )
                    }
                }
                
                println("❌ ytcfg não encontrado ou API Key inválida")
                null
                
            } catch (e: Exception) {
                println("❌ Erro em getYouTubeConfig: ${e.message}")
                null
            }
        }
    }
    
    // 🔧 **Funções auxiliares para HLS**
    private fun extractQualityFromM3u8Line(line: String): String {
        val pattern = Pattern.compile("RESOLUTION=(\\d+x\\d+)")
        val matcher = pattern.matcher(line)
        return if (matcher.find()) matcher.group(1) else "unknown"
    }
    
    private fun extractBandwidthFromM3u8Line(line: String): Int {
        val pattern = Pattern.compile("BANDWIDTH=(\\d+)")
        val matcher = pattern.matcher(line)
        return if (matcher.find()) matcher.group(1).toIntOrNull() ?: 0 else 0
    }
    
    private fun resolveRelativeUrl(baseUrl: String, relativePath: String): String {
        return if (relativePath.startsWith("http")) {
            relativePath
        } else {
            val base = baseUrl.substringBeforeLast("/")
            "$base/$relativePath"
        }
    }
    
    // 🔧 **Extrair videoId de várias formas de URL**
    private fun extractYouTubeId(url: String): String? {
        return when {
            url.contains("youtube.com/watch?v=") -> {
                url.substringAfter("v=").substringBefore("&").takeIf { it.length == 11 }
            }
            url.contains("youtu.be/") -> {
                url.substringAfter("youtu.be/").substringBefore("?").takeIf { it.length == 11 }
            }
            url.contains("youtube.com/embed/") -> {
                url.substringAfter("embed/").substringBefore("?").takeIf { it.length == 11 }
            }
            url.contains("/v/") -> {
                url.substringAfter("/v/").substringBefore("?").takeIf { it.length == 11 }
            }
            else -> {
                // Tentar extrair qualquer coisa que pareça um videoId
                val regex = """[a-zA-Z0-9_-]{11}""".toRegex()
                regex.find(url)?.value
            }
        }
    }
    
    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val links = mutableListOf<ExtractorLink>()
        
        getUrl(url, referer, {}, { link ->
            links.add(link)
        })
        
        return if (links.isNotEmpty()) links else null
    }
}
