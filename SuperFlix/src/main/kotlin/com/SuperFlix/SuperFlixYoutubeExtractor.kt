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

class SuperFlixYoutubeExtractor : ExtractorApi() {
    override val name = "SuperFlixYouTube"
    override val mainUrl = "https://www.youtube.com"
    override val requiresReferer = false
    
    // User-Agent moderno (igual ao plugin)
    private val userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15"
    
    // Headers fixos (importante!)
    private val headers = mapOf(
        "User-Agent" to userAgent,
        "Accept-Language" to "en-US,en;q=0.9",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1"
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("🎬 [SuperFlix] YouTubeExtractor - NOVO MÉTODO HLS")
        
        try {
            val videoId = extractYouTubeId(url) ?: run {
                println("❌ Não consegui extrair videoId da URL: $url")
                return
            }
            
            println("📹 Video ID: $videoId")
            
            // NOVO: Usar método HLS para pegar TODAS qualidades
            extractViaHLS(videoId, callback)
            
        } catch (e: Exception) {
            println("❌ Erro no novo método: ${e.message}")
            e.printStackTrace()
        }
    }

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val links = mutableListOf<ExtractorLink>()
        
        getUrl(url, referer, {}, { link ->
            links.add(link)
        })
        
        return if (links.isNotEmpty()) links else null
    }
    
    // 🔥 **MÉTODO PRINCIPAL ATUALIZADO** - Usa HLS como o plugin
    private suspend fun extractViaHLS(videoId: String, callback: (ExtractorLink) -> Unit) {
        println("🔗 Buscando qualidades via HLS...")
        
        try {
            // PASSO 1: Pegar configuração da página (ytcfg)
            val config = getPageConfig(videoId) ?: run {
                println("❌ Não consegui pegar configuração da página")
                return
            }
            
            val apiKey = config["apiKey"] ?: run {
                println("❌ API Key não encontrada")
                return
            }
            
            val clientVersion = config["clientVersion"] ?: "2.20241220.00.00"
            val visitorData = config["visitorData"] ?: ""
            
            println("✅ Configuração obtida: API Key=$apiKey, Client Version=$clientVersion")
            
            // PASSO 2: Fazer request para API interna do YouTube
            val apiUrl = "https://www.youtube.com/youtubei/v1/player?key=$apiKey"
            println("📡 Chamando API: $apiUrl")
            
            val requestBody = buildJsonBody(videoId, clientVersion, visitorData)
            val jsonMediaType = "application/json; charset=utf-8".toMediaType()
            
            val response = app.post(
                apiUrl,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Accept-Language" to "en-US,en;q=0.5",
                    "Content-Type" to "application/json",
                    "Origin" to "https://www.youtube.com",
                    "Referer" to "https://www.youtube.com/watch?v=$videoId"
                ),
                requestBody = requestBody.toRequestBody(jsonMediaType),
                timeout = 15000
            )
            
            if (response.code != 200) {
                println("❌ API retornou status ${response.code}")
                return
            }
            
            val jsonText = response.text
            println("✅ Resposta da API recebida (${jsonText.length} chars)")
            
            // PASSO 3: Extrair streamingData e HLS URL
            val mapper = jacksonObjectMapper()
            val rootNode = mapper.readTree(jsonText)
            
            val streamingData = rootNode.path("streamingData")
            if (streamingData.isMissingNode) {
                println("❌ Não encontrou streamingData na resposta")
                return
            }
            
            // Tentar pegar URL HLS (m3u8) - MÉTODO PRINCIPAL
            val hlsUrl = streamingData.path("hlsManifestUrl").asText(null)
            
            if (hlsUrl != null) {
                println("🎯 URL HLS encontrada! ($hlsUrl)")
                extractQualitiesFromHLS(hlsUrl, callback)
            } else {
                println("⚠️ hlsManifestUrl não encontrada, tentando adaptiveFormats...")
                // Fallback: tentar adaptiveFormats se HLS não estiver disponível
                extractAdaptiveFormats(streamingData, callback)
            }
            
        } catch (e: Exception) {
            println("❌ Erro no método HLS: ${e.message}")
            e.printStackTrace()
        }
    }
    
    // 🔧 **PASSO 1: Pegar configuração da página (ytcfg)**
    private suspend fun getPageConfig(videoId: String): Map<String, String>? {
        return withContext(Dispatchers.IO) {
            try {
                println("📄 Baixando página do vídeo...")
                val videoUrl = "https://www.youtube.com/watch?v=$videoId"
                
                val response = app.get(videoUrl, headers = headers, timeout = 10000)
                if (response.code != 200) {
                    println("❌ Página retornou status ${response.code}")
                    return@withContext null
                }
                
                val html = response.text
                println("✅ Página baixada (${html.length} chars)")
                
                // Extrair ytcfg do HTML (mesmo regex do plugin)
                val ytCfg = extractYtCfgFromHtml(html)
                if (ytCfg == null) {
                    println("❌ Não encontrou ytcfg no HTML")
                    return@withContext null
                }
                
                // Extrair valores importantes
                val apiKey = ytCfg.path("INNERTUBE_API_KEY").asText(null)
                val clientVersion = ytCfg.path("INNERTUBE_CLIENT_VERSION").asText("2.20241220.00.00")
                val visitorData = ytCfg.path("VISITOR_DATA").asText("")
                
                if (apiKey == null || apiKey.isEmpty()) {
                    println("❌ API Key vazia ou não encontrada")
                    return@withContext null
                }
                
                println("🔑 Config extraída: API Key=${apiKey.take(10)}..., Client Version=$clientVersion")
                
                mapOf(
                    "apiKey" to apiKey,
                    "clientVersion" to clientVersion,
                    "visitorData" to visitorData
                )
                
            } catch (e: Exception) {
                println("❌ Erro obtendo config: ${e.message}")
                null
            }
        }
    }
    
    // Extrair ytcfg do HTML (igual ao plugin)
    private fun extractYtCfgFromHtml(html: String): JsonNode? {
        try {
            val pattern = """ytcfg\.set\(\s*(\{.*?\})\s*\)\s*;""".toRegex(RegexOption.DOT_MATCHES_ALL)
            val match = pattern.find(html)
            
            if (match != null) {
                val jsonStr = match.groupValues[1]
                println("✅ ytcfg encontrado (${jsonStr.length} chars)")
                
                val mapper = jacksonObjectMapper()
                return mapper.readTree(jsonStr)
            }
        } catch (e: Exception) {
            println("❌ Erro parseando ytcfg: ${e.message}")
        }
        return null
    }
    
    // 🔧 **PASSO 2: Construir JSON para API**
    private fun buildJsonBody(videoId: String, clientVersion: String, visitorData: String): String {
        return """
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
    }
    
    // 🎯 **PASSO 3: Extrair qualidades do HLS (método principal)**
    private suspend fun extractQualitiesFromHLS(hlsUrl: String, callback: (ExtractorLink) -> Unit) {
        println("🎬 Extraindo qualidades do HLS: ${hlsUrl.take(80)}...")
        
        try {
            // Usar M3u8Helper2 do CloudStream (igual ao plugin)
            val hlsHelper = M3u8Helper2()
            
            // Gerar links a partir do HLS
            hlsHelper.generateM3u8(
                source = name,
                url = hlsUrl,
                referer = "https://www.youtube.com",
                quality = null,
                headers = mapOf(
                    "Referer" to "https://www.youtube.com",
                    "Origin" to "https://www.youtube.com",
                    "User-Agent" to userAgent
                ),
                subtitleCallback = null,
                callback = callback
            )
            
            println("✅ Todas qualidades extraídas do HLS!")
            
        } catch (e: Exception) {
            println("❌ Erro extraindo HLS: ${e.message}")
        }
    }
    
    // 🔄 **FALLBACK: Extrair adaptiveFormats se HLS não estiver disponível**
    private suspend fun extractAdaptiveFormats(streamingData: JsonNode, callback: (ExtractorLink) -> Unit) {
        try {
            println("🔄 Tentando extrair adaptiveFormats...")
            
            val adaptiveFormats = streamingData.path("adaptiveFormats")
            if (!adaptiveFormats.isArray || adaptiveFormats.size() == 0) {
                println("❌ Nenhum adaptiveFormat encontrado")
                return
            }
            
            println("📊 Encontrados ${adaptiveFormats.size()} formatos")
            
            val itagPriority = mapOf(
                // 4K
                313 to Qualities.P2160.value,
                315 to Qualities.P2160.value,
                271 to Qualities.P1440.value,
                38 to Qualities.P2160.value,
                
                // 1080p
                37 to Qualities.P1080.value,
                137 to Qualities.P1080.value,
                248 to Qualities.P1080.value,
                
                // 720p (IMPORTANTE para trailers)
                22 to Qualities.P720.value,
                45 to Qualities.P720.value,
                46 to Qualities.P1080.value, // WebM 1080p com áudio
                
                // 480p
                59 to Qualities.P480.value,
                18 to Qualities.P360.value, // Fallback mínimo
            )
            
            var foundCount = 0
            
            for (format in adaptiveFormats) {
                val itag = format.path("itag").asInt(-1)
                val url = format.path("url").asText(null)
                val qualityLabel = format.path("qualityLabel").asText("")
                
                if (url != null && itagPriority.containsKey(itag)) {
                    val quality = itagPriority[itag] ?: Qualities.P720.value
                    val qualityName = when (quality) {
                        Qualities.P2160.value -> "4K"
                        Qualities.P1440.value -> "1440p"
                        Qualities.P1080.value -> "1080p"
                        Qualities.P720.value -> "720p"
                        Qualities.P480.value -> "480p"
                        else -> "SD"
                    }
                    
                    println("✅ Encontrado: itag=$itag, qualidade=$qualityName")
                    
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
                    foundCount++
                    
                    // Limitar para não pegar muitos formatos
                    if (foundCount >= 5) break
                }
            }
            
            println("✨ Total de $foundCount qualidades extraídas via adaptiveFormats")
            
        } catch (e: Exception) {
            println("❌ Erro extraindo adaptiveFormats: ${e.message}")
        }
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
