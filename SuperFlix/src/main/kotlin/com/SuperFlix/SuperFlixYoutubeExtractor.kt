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
    
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    private val mapper = jacksonObjectMapper()
    
    // Headers para requests
    private val headers = mapOf(
        "User-Agent" to userAgent,
        "Accept-Language" to "en-US,en;q=0.9",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
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
            
            // Método 1: Tentar via API interna (HLS)
            if (!extractViaAPI(videoId, callback)) {
                // Método 2: Fallback para métodos alternativos
                println("🔄 HLS não disponível, tentando métodos alternativos...")
                extractViaAlternativeMethods(videoId, callback)
            }
            
        } catch (e: Exception) {
            println("❌ Erro geral: ${e.message}")
            e.printStackTrace()
        }
    }

    // 🔥 **MÉTODO 1: Usar API interna do YouTube (HLS)**
    private suspend fun extractViaAPI(videoId: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            println("🔗 Tentando API interna do YouTube...")
            
            // 1. Pegar configuração
            val config = getYouTubeConfig(videoId) ?: return false
            
            val apiKey = config["INNERTUBE_API_KEY"] ?: return false
            val clientVersion = config["INNERTUBE_CLIENT_VERSION"] ?: "2.20241220.00.00"
            val visitorData = config["VISITOR_DATA"] ?: ""
            
            println("✅ Config obtida: API Key=${apiKey.take(10)}..., Version=$clientVersion")
            
            // 2. Chamar API
            val apiUrl = "https://www.youtube.com/youtubei/v1/player?key=$apiKey"
            println("📡 Chamando: $apiUrl")
            
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
                timeout = 15000
            )
            
            if (response.code != 200) {
                println("❌ API retornou ${response.code}")
                return false
            }
            
            val jsonText = response.text
            println("✅ Resposta API: ${jsonText.length} chars")
            
            // 3. Extrair streamingData
            val rootNode = mapper.readTree(jsonText)
            val streamingData = rootNode.path("streamingData")
            
            if (streamingData.isMissingNode) {
                println("❌ streamingData não encontrado")
                return false
            }
            
            // 4. Tentar HLS primeiro
            val hlsUrl = streamingData.path("hlsManifestUrl").asText(null)
            if (hlsUrl != null) {
                println("🎯 URL HLS encontrada!")
                extractQualitiesFromHLSManual(hlsUrl, callback)
                return true
            }
            
            // 5. Se não tiver HLS, tentar adaptiveFormats
            println("⚠️ HLS não disponível, tentando adaptiveFormats...")
            extractAdaptiveFormats(streamingData, callback)
            return true
            
        } catch (e: Exception) {
            println("❌ Erro na API: ${e.message}")
            false
        }
    }
    
    // 🔧 **Extrair qualidades do HLS MANUALMENTE (sem M3u8Helper2)**
    private suspend fun extractQualitiesFromHLSManual(hlsUrl: String, callback: (ExtractorLink) -> Unit) {
        println("🎬 Parseando HLS manualmente: ${hlsUrl.take(80)}...")
        
        try {
            // Baixar playlist m3u8
            val response = app.get(hlsUrl, headers = headers, timeout = 10000)
            if (response.code != 200) {
                println("❌ Falha ao baixar HLS: ${response.code}")
                return
            }
            
            val m3u8Content = response.text
            println("📄 Playlist HLS: ${m3u8Content.length} chars")
            
            // Parsear linhas do m3u8
            val lines = m3u8Content.lines()
            var currentQuality: String? = null
            var currentBandwidth: Int = 0
            
            for (line in lines) {
                when {
                    line.startsWith("#EXT-X-STREAM-INF:") -> {
                        // Extrair qualidade da linha
                        currentQuality = extractQualityFromM3u8Line(line)
                        currentBandwidth = extractBandwidthFromM3u8Line(line)
                    }
                    !line.startsWith("#") && line.isNotBlank() && currentQuality != null -> {
                        // Esta é uma URL de stream
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
                        
                        println("✅ Qualidade encontrada: $qualityName (bandwidth: $currentBandwidth)")
                        
                        // Criar link
                        val extractorLink = newExtractorLink(
                            source = name,
                            name = "YouTube ($qualityName)",
                            url = streamUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "https://www.youtube.com"
                            this.quality = qualityValue
                            this.headers = mapOf(
                                "Referer" to "https://www.youtube.com",
                                "User-Agent" to userAgent,
                                "Origin" to "https://www.youtube.com"
                            )
                        }
                        
                        callback(extractorLink)
                        currentQuality = null
                    }
                }
            }
            
            println("✨ Qualidades HLS extraídas com sucesso!")
            
        } catch (e: Exception) {
            println("❌ Erro parseando HLS: ${e.message}")
        }
    }
    
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
        } else if (baseUrl.contains("/manifest/")) {
            // Para URLs do YouTube
            val base = baseUrl.substringBeforeLast("/")
            "$base/$relativePath"
        } else {
            val base = baseUrl.substringBeforeLast("/")
            "$base/$relativePath"
        }
    }
    
    // 🔧 **Extrair adaptiveFormats (fallback)**
    private suspend fun extractAdaptiveFormats(streamingData: JsonNode, callback: (ExtractorLink) -> Unit) {
        try {
            val adaptiveFormats = streamingData.path("adaptiveFormats")
            if (!adaptiveFormats.isArray || adaptiveFormats.size() == 0) {
                println("❌ Nenhum adaptiveFormat")
                return
            }
            
            println("📊 Encontrados ${adaptiveFormats.size()} formatos")
            
            val priorityItags = mapOf(
                313 to Qualities.P2160.value,
                315 to Qualities.P2160.value,
                271 to Qualities.P1440.value,
                38 to Qualities.P2160.value,
                37 to Qualities.P1080.value,
                137 to Qualities.P1080.value,
                248 to Qualities.P1080.value,
                22 to Qualities.P720.value,  // 720p COM ÁUDIO (IMPORTANTE!)
                45 to Qualities.P720.value,
                46 to Qualities.P1080.value,
                59 to Qualities.P480.value,
                18 to Qualities.P360.value
            )
            
            var found = 0
            
            for (format in adaptiveFormats) {
                val itag = format.path("itag").asInt(-1)
                val url = format.path("url").asText(null)
                val qualityLabel = format.path("qualityLabel").asText("")
                
                if (url != null && priorityItags.containsKey(itag)) {
                    val quality = priorityItags[itag] ?: Qualities.P720.value
                    val qualityName = when (quality) {
                        Qualities.P2160.value -> "4K"
                        Qualities.P1440.value -> "1440p"
                        Qualities.P1080.value -> "1080p"
                        Qualities.P720.value -> "720p"
                        Qualities.P480.value -> "480p"
                        else -> "360p"
                    }
                    
                    println("✅ itag $itag -> $qualityName ($qualityLabel)")
                    
                    val extractorLink = newExtractorLink(
                        source = name,
                        name = "YouTube ($qualityName)",
                        url = url,
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
                    found++
                    
                    if (found >= 3) break // Limitar a 3 qualidades
                }
            }
            
            println("✨ $found qualidades via adaptiveFormats")
            
        } catch (e: Exception) {
            println("❌ Erro adaptiveFormats: ${e.message}")
        }
    }
    
    // 🔄 **MÉTODO 2: Alternativas se API falhar**
    private suspend fun extractViaAlternativeMethods(videoId: String, callback: (ExtractorLink) -> Unit) {
        println("🔄 Tentando métodos alternativos...")
        
        // Método 1: yewtu.be (Invidious)
        try {
            val invidiousUrl = "https://yewtu.be/latest_version?id=$videoId"
            println("🌐 Tentando Invidious: $invidiousUrl")
            
            val response = app.get(invidiousUrl, timeout = 10000)
            if (response.code == 200) {
                // Procurar links no HTML
                val html = response.text
                extractLinksFromHTML(html, callback)
            }
        } catch (e: Exception) {
            println("❌ Invidious falhou: ${e.message}")
        }
        
        // Método 2: Buscar diretamente no HTML do YouTube
        try {
            val youtubeUrl = "https://www.youtube.com/watch?v=$videoId"
            println("🎬 Buscando no HTML direto: $youtubeUrl")
            
            val response = app.get(youtubeUrl, headers = headers, timeout = 10000)
            if (response.code == 200) {
                val html = response.text
                extractLinksFromHTML(html, callback)
            }
        } catch (e: Exception) {
            println("❌ HTML direto falhou: ${e.message}")
        }
    }
    
    private fun extractLinksFromHTML(html: String, callback: (ExtractorLink) -> Unit) {
        // Procurar padrões de URLs de vídeo
        val patterns = listOf(
            """https?://[^"\s]+googlevideo\.com/videoplayback[^"\s]*itag=(22|37|137|248)[^"\s]*""",
            """hlsManifestUrl["']?\s*:\s*["']([^"']+)["']""",
            """adaptive_fmts["']?\s*:\s*["']([^"']+)["']"""
        )
        
        for (pattern in patterns) {
            try {
                val regex = pattern.toRegex()
                val matches = regex.findAll(html)
                
                matches.forEach { match ->
                    val url = match.groupValues[1]
                    if (url.isNotBlank() && url.contains("googlevideo")) {
                        println("🔗 URL encontrada: ${url.take(80)}...")
                        
                        // Determinar qualidade pela URL
                        val quality = when {
                            url.contains("itag=37") || url.contains("itag=137") -> Qualities.P1080.value
                            url.contains("itag=22") -> Qualities.P720.value
                            url.contains("itag=248") -> Qualities.P1080.value
                            else -> Qualities.P720.value
                        }
                        
                        val qualityName = when (quality) {
                            Qualities.P1080.value -> "1080p"
                            Qualities.P720.value -> "720p"
                            else -> "SD"
                        }
                        
                        val extractorLink = newExtractorLink(
                            source = name,
                            name = "YouTube ($qualityName)",
                            url = url,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "https://www.youtube.com"
                            this.quality = quality
                            this.headers = headers
                        }
                        
                        callback(extractorLink)
                    }
                }
            } catch (e: Exception) {
                // Continuar com próximo padrão
            }
        }
    }
    
    // 🔑 **Obter configuração do YouTube (ytcfg)**
    private suspend fun getYouTubeConfig(videoId: String): Map<String, String>? {
        return withContext(Dispatchers.IO) {
            try {
                val url = "https://www.youtube.com/watch?v=$videoId"
                println("📄 Baixando página: $url")
                
                val response = app.get(url, headers = headers, timeout = 10000)
                if (response.code != 200) {
                    println("❌ Página: ${response.code}")
                    return@withContext null
                }
                
                val html = response.text
                
                // Extrair ytcfg
                val pattern = """ytcfg\.set\(\s*(\{.*?\})\s*\)\s*;""".toRegex(RegexOption.DOT_MATCHES_ALL)
                val match = pattern.find(html)
                
                if (match != null) {
                    val jsonStr = match.groupValues[1]
                    val ytCfg = mapper.readTree(jsonStr)
                    
                    val apiKey = ytCfg.path("INNERTUBE_API_KEY").asText(null)
                    val clientVersion = ytCfg.path("INNERTUBE_CLIENT_VERSION").asText("2.20241220.00.00")
                    val visitorData = ytCfg.path("VISITOR_DATA").asText("")
                    
                    if (apiKey != null) {
                        println("✅ ytcfg extraído: ${apiKey.take(10)}...")
                        return@withContext mapOf(
                            "INNERTUBE_API_KEY" to apiKey,
                            "INNERTUBE_CLIENT_VERSION" to clientVersion,
                            "VISITOR_DATA" to visitorData
                        )
                    }
                }
                
                null
            } catch (e: Exception) {
                println("❌ Erro getYouTubeConfig: ${e.message}")
                null
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
            else -> null
        }
    }
}
