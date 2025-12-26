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
            
            // Seguir a mesma abordagem do YoutubeExtractor original
            extractViaAPI(videoId, callback)
            
        } catch (e: Exception) {
            println("❌ Erro geral no extrator: ${e.message}")
            e.printStackTrace()
        }
    }

    // 🔥 **MÉTODO IDÊNTICO AO ORIGINAL: Usar HLS via API**
    private suspend fun extractViaAPI(videoId: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            println("🔗 Tentando API interna do YouTube...")
            
            // 1. Pegar configuração (igual ao original)
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
            
            // 2. Chamar API do YouTube (igual ao original)
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
            
            // 3. Parsear resposta JSON usando Jackson (mais simples)
            val rootNode = mapper.readTree(jsonText)
            val streamingData = rootNode.path("streamingData")
            
            if (streamingData.isMissingNode) {
                println("❌ streamingData não encontrado na resposta")
                return false
            }
            
            // 4. BUSCAR HLS PRIMEIRO (igual ao original)
            val hlsUrl = streamingData.path("hlsManifestUrl").asText(null)
            
            if (hlsUrl != null && hlsUrl.isNotBlank()) {
                println("🎯 URL HLS encontrada: ${hlsUrl.take(80)}...")
                
                // Extrair qualidades do HLS manualmente
                extractQualitiesFromHLSManual(hlsUrl, callback)
                return true
            }
            
            // 5. Se não tiver HLS, tentar formatos adaptativos COM MAIS DETALHES
            println("⚠️ HLS não disponível, analisando formatos adaptativos...")
            val success = extractAdaptiveFormatsDetailed(streamingData, callback)
            
            // 6. Se não encontrou formatos adaptativos, tentar formatos simples
            if (!success) {
                println("🔄 Nenhum formato adaptativo encontrado, tentando formatos simples...")
                extractSimpleFormats(streamingData, callback)
            }
            
            success
            
        } catch (e: Exception) {
            println("❌ Erro na API: ${e.message}")
            false
        }
    }
    
    // 🔍 **ANALISAR FORMATOS ADAPTATIVOS DETALHADAMENTE**
    private suspend fun extractAdaptiveFormatsDetailed(streamingData: JsonNode, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val adaptiveFormats = streamingData.path("adaptiveFormats")
            
            if (!adaptiveFormats.isArray || adaptiveFormats.size() == 0) {
                println("❌ Nenhum adaptiveFormat encontrado")
                return false
            }
            
            println("📊 Total de formatos adaptativos: ${adaptiveFormats.size()}")
            
            // Separar vídeo e áudio
            val videoFormats = mutableListOf<JsonNode>()
            val audioFormats = mutableListOf<JsonNode>()
            
            for (i in 0 until adaptiveFormats.size()) {
                val format = adaptiveFormats.get(i)
                val mimeType = format.path("mimeType").asText("").lowercase()
                
                if (mimeType.contains("video/")) {
                    videoFormats.add(format)
                } else if (mimeType.contains("audio/")) {
                    audioFormats.add(format)
                }
            }
            
            println("🎬 Formatos de vídeo: ${videoFormats.size}")
            println("🎵 Formatos de áudio: ${audioFormats.size}")
            
            // DEBUG: Mostrar todos os formatos de vídeo
            println("📋 TODOS os formatos de vídeo encontrados:")
            for (i in 0 until minOf(10, videoFormats.size)) {
                val format = videoFormats[i]
                val itag = format.path("itag").asInt(-1)
                val qualityLabel = format.path("qualityLabel").asText("Sem label")
                val mimeType = format.path("mimeType").asText("")
                val url = format.path("url").asText(null)
                val hasAudio = !format.path("audioQuality").isMissingNode
                
                println("   [$i] itag=$itag | $qualityLabel | $mimeType | audio=$hasAudio | url=${if (url != null) "SIM" else "NÃO"}")
            }
            
            var foundCount = 0
            
            // PRIORIDADE 1: Buscar formatos COM ÁUDIO INCLUSO
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
                        qualityLabel.contains("1080") -> Qualities.P1080.value
                        qualityLabel.contains("720") || itag == 22 -> Qualities.P720.value  // 720p COM ÁUDIO!
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
                    
                    createExtractorLink(url, quality, "$qualityName (áudio)", callback)
                    foundCount++
                    
                    if (foundCount >= 3) break
                }
            }
            
            // PRIORIDADE 2: Se não encontrou formatos com áudio, buscar QUALQUER vídeo com URL
            if (foundCount == 0) {
                println("🔍 Nenhum formato com áudio encontrado, buscando qualquer vídeo com URL...")
                
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
                        
                        // Verificar se precisa de áudio separado
                        val needsAudio = audioFormats.isNotEmpty()
                        
                        createExtractorLink(url, quality, "$qualityName${if (needsAudio) " (vídeo)" else ""}", callback)
                        foundCount++
                        
                        // Adicionar também um formato de áudio se disponível
                        if (needsAudio && audioFormats.size > 0) {
                            val audioFormat = audioFormats[0]
                            val audioUrl = audioFormat.path("url").asText(null)
                            if (audioUrl != null) {
                                println("🎵 Adicionando áudio separado")
                                createExtractorLink(audioUrl, quality, "Áudio", callback)
                            }
                        }
                        
                        if (foundCount >= 2) break
                    }
                }
            }
            
            println("✨ Extraídos $foundCount qualidades via adaptiveFormats")
            foundCount > 0
            
        } catch (e: Exception) {
            println("❌ Erro em extractAdaptiveFormatsDetailed: ${e.message}")
            false
        }
    }
    
    // 🎯 **Extrair formatos simples (fallback)**
    private suspend fun extractSimpleFormats(streamingData: JsonNode, callback: (ExtractorLink) -> Unit) {
        try {
            val formats = streamingData.path("formats")
            if (!formats.isArray || formats.size() == 0) {
                println("❌ Nenhum formato simples encontrado")
                return
            }
            
            println("📦 Formatos simples encontrados: ${formats.size()}")
            
            for (i in 0 until formats.size()) {
                val format = formats.get(i)
                val itag = format.path("itag").asInt(-1)
                val url = format.path("url").asText(null)
                val qualityLabel = format.path("qualityLabel").asText("")
                
                if (url != null) {
                    println("📄 Formato simples: itag=$itag ($qualityLabel)")
                    
                    // Priorizar itags com áudio
                    if (itag in listOf(22, 37, 18)) {
                        val quality = when (itag) {
                            37 -> Qualities.P1080.value
                            22 -> Qualities.P720.value
                            else -> Qualities.P360.value
                        }
                        
                        val qualityName = when (itag) {
                            37 -> "1080p"
                            22 -> "720p"
                            else -> "360p"
                        }
                        
                        println("✅ Formato simples COM ÁUDIO: $qualityName")
                        createExtractorLink(url, quality, qualityName, callback)
                    }
                }
            }
            
        } catch (e: Exception) {
            println("❌ Erro em extractSimpleFormats: ${e.message}")
        }
    }
    
    // 🔑 **Obter configuração do YouTube - VERSÃO CORRIGIDA**
    private suspend fun getYouTubeConfig(videoId: String): Map<String, String>? {
        return withContext(Dispatchers.IO) {
            try {
                val url = "https://www.youtube.com/watch?v=$videoId"
                println("📄 Baixando página do YouTube: $url")
                
                // Headers mais completos
                val requestHeaders = mapOf(
                    "User-Agent" to userAgent,
                    "Accept-Language" to "en-US,en;q=0.9",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                    "Accept-Encoding" to "gzip, deflate, br",
                    "DNT" to "1",
                    "Connection" to "keep-alive",
                    "Upgrade-Insecure-Requests" to "1",
                    "Sec-Fetch-Dest" to "document",
                    "Sec-Fetch-Mode" to "navigate",
                    "Sec-Fetch-Site" to "none",
                    "Sec-Fetch-User" to "?1"
                )
                
                val response = app.get(url, headers = requestHeaders, timeout = 20000)
                if (response.code != 200) {
                    println("❌ Página retornou status ${response.code}")
                    return@withContext null
                }
                
                val html = response.text
                println("✅ Página baixada (${html.length} chars)")
                
                // Salvar HTML para debug (opcional)
                // saveHtmlForDebug(html, videoId)
                
                // MÉTODO 1: Usar regex IDÊNTICO ao original
                val pattern = Regex("ytcfg\\.set\\(\\s*(\\{.*?\\})\\s*\\)\\s*;", RegexOption.DOT_MATCHES_ALL)
                val match = pattern.find(html)
                
                if (match != null) {
                    val jsonStr = match.groupValues[1]
                    println("✅ ytcfg encontrado (${jsonStr.length} chars)")
                    
                    try {
                        val jsonObject = org.json.JSONObject(jsonStr)
                        
                        val apiKey = jsonObject.optString("INNERTUBE_API_KEY", null)
                        val clientVersion = jsonObject.optString("INNERTUBE_CLIENT_VERSION", "2.20241220.00.00")
                        val visitorData = jsonObject.optString("VISITOR_DATA", "")
                        
                        if (apiKey != null && apiKey.isNotBlank()) {
                            println("🔑 Config extraída via ytcfg")
                            return@withContext mapOf(
                                "INNERTUBE_API_KEY" to apiKey,
                                "INNERTUBE_CLIENT_VERSION" to clientVersion,
                                "VISITOR_DATA" to visitorData
                            )
                        }
                    } catch (e: Exception) {
                        println("⚠️ Erro parseando ytcfg JSON: ${e.message}")
                    }
                } else {
                    println("⚠️ ytcfg.set() não encontrado, tentando alternativas...")
                }
                
                // MÉTODO 4: API keys fixas de fallback (que sabemos que funciona)
                println("🔄 Usando fallback keys...")
                
                val fallbackKeys = listOf(
                    "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8",
                    "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w",
                    "AIzaSyC-6qtuR3pKcDL6mK0vHhYHhpT9qRyd0cQ",
                    "AIzaSyBUPetSUoZL5F8GhO8zB2K5J_Hwr0kRQoc"
                )
                
                // Testar cada API key
                for (key in fallbackKeys) {
                    println("🔑 Testando fallback key: ${key.take(10)}...")
                    if (testApiKey(key, videoId)) {
                        println("✅ Fallback key funcionou!")
                        return@withContext mapOf(
                            "INNERTUBE_API_KEY" to key,
                            "INNERTUBE_CLIENT_VERSION" to "2.20241220.00.00",
                            "VISITOR_DATA" to ""
                        )
                    }
                }
                
                println("❌ Nenhuma API key válida encontrada")
                null
                
            } catch (e: Exception) {
                println("❌ Erro em getYouTubeConfig: ${e.message}")
                null
            }
        }
    }
    
    // 🔧 **Testar API key**
    private suspend fun testApiKey(apiKey: String, videoId: String): Boolean {
        return try {
            val testUrl = "https://www.youtube.com/youtubei/v1/player?key=$apiKey"
            val requestBody = """
            {
                "context": {
                    "client": {
                        "hl": "en",
                        "gl": "US",
                        "clientName": "WEB",
                        "clientVersion": "2.20241220.00.00",
                        "platform": "DESKTOP",
                        "userAgent": "$userAgent"
                    }
                },
                "videoId": "$videoId"
            }
            """.trimIndent()
            
            val jsonMediaType = "application/json; charset=utf-8".toMediaType()
            
            val response = app.post(
                testUrl,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Content-Type" to "application/json",
                    "Origin" to "https://www.youtube.com"
                ),
                requestBody = requestBody.toRequestBody(jsonMediaType),
                timeout = 10000
            )
            
            response.code == 200
        } catch (e: Exception) {
            false
        }
    }
    
    // 🔧 **Criar ExtractorLink COM HEADERS CORRETOS**
    private suspend fun createExtractorLink(
        videoUrl: String,
        quality: Int,
        qualityText: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            println("🔗 Criando link: $qualityText")
            
            // Headers específicos para YouTube
            val youtubeHeaders = mapOf(
                "User-Agent" to userAgent,
                "Accept" to "*/*",
                "Accept-Language" to "en-US,en;q=0.9",
                "Accept-Encoding" to "gzip, deflate, br",
                "Referer" to "https://www.youtube.com/",
                "Origin" to "https://www.youtube.com",
                "Connection" to "keep-alive",
                "Sec-Fetch-Dest" to "video",
                "Sec-Fetch-Mode" to "no-cors",
                "Sec-Fetch-Site" to "same-site"
            )
            
            val extractorLink = newExtractorLink(
                source = name,
                name = "YouTube ($qualityText)",
                url = videoUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = mainUrl
                this.quality = quality
                this.headers = youtubeHeaders
            }
            
            callback(extractorLink)
            println("✨ Link $qualityText criado com sucesso!")
            
        } catch (e: Exception) {
            println("❌ Erro criando link: ${e.message}")
        }
    }
    
    // 🔧 **Extrair videoId**
    private fun extractYouTubeId(url: String): String? {
        val patterns = listOf(
            """v=([a-zA-Z0-9_-]{11})""",
            """youtu\.be/([a-zA-Z0-9_-]{11})""",
            """/embed/([a-zA-Z0-9_-]{11})""",
            """/v/([a-zA-Z0-9_-]{11})"""
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
