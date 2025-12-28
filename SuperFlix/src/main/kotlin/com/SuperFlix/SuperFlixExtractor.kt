package com.SuperFlix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.json.JSONObject
import java.net.URLEncoder

object SuperFlixExtractor {
    // Cookie atualizado para evitar bloqueios
    private const val API_COOKIE = "SITE_TOTAL_ID=aTYqe6GU65PNmeCXpelwJwAAAMi; __dtsu=104017651574995957BEB724C6373F9E; __cc_id=a44d1e52993b9c2Oaaf40eba24989a06; __cc_cc=ACZ4nGNQSDQXsTFMNTWyTDROskw2MkhMTDDMXSE1KNDKxtLBMNDBjAIJMC4fgVe%2B%2F%2BDngAHemT8XsDBKrv%2FF3%2F2F2%2F%2FF0ZGhFP15u.VnW-1Y0o8o6/84-1.2.1.1-4_OXh2hYevsbO8hINijDKB8O_SPowh.pNojloHEbwX_qZorbmW8u8zqV9B7UsV6bbRmCWx_dD17mA7vJJklpOD9WBh9DA0wMV2a1QSKuR2J3FN9.TRzOUM4AhnTGFd8dJH8bHfqQdY7uYuUg7Ny1TVQDF9kXqyEPtnmkZ9rFkqQ2KS6u0t2hhFdQvRBY7dqyGfdjmyjDqwc7ZOovHB0eqep.FPHrh8T9iz1LuucA; cf_clearance=rfIEldahI7B..Y4PpZhGgwi.QOJBqIRGdFP150.VnW-1766868784-1.1-"
    
    suspend fun extractVideoLinks(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🎯 SuperFlixExtractor: Iniciando extração...")
            
            // 1. Extrair ID do vídeo
            val videoId = extractVideoId(url)
            if (videoId == null) {
                println("❌ Não consegui extrair ID da URL: $url")
                return false
            }
            
            println("✅ ID extraído: $videoId")
            
            // 2. Obter detalhes do vídeo
            val details = getVideoDetails(videoId)
            if (details == null) {
                println("❌ Não consegui obter detalhes do vídeo")
                return false
            }
            
            println("📊 Título: ${details.title}")
            println("🔗 Embed URL: ${details.embedFrameUrl}")
            
            // 3. Obter URL do m3u8
            val m3u8Url = getM3u8Url(videoId, details.fileId)
            if (m3u8Url == null) {
                println("❌ Não consegui obter URL do m3u8")
                return false
            }
            
            println("🎬 URL do M3U8: ${m3u8Url.take(100)}...")
            
            // 4. Gerar links M3U8
            return generateM3u8Links(m3u8Url, details.embedFrameUrl, name, callback)
            
        } catch (e: Exception) {
            println("💥 Erro na extração: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    private fun extractVideoId(url: String): String? {
        val patterns = listOf(
            Regex("""/e/([a-zA-Z0-9]+)"""),
            Regex("""/videos/([a-zA-Z0-9]+)"""),
            Regex("""/embed/([a-zA-Z0-9]+)"""),
            Regex("""/([a-zA-Z0-9]{8,})"""), // IDs têm pelo menos 8 caracteres
            Regex("""([a-zA-Z0-9]{11,})""")  // IDs parecem ter 11+ caracteres
        )
        
        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        
        return null
    }
    
    private suspend fun getVideoDetails(videoId: String): VideoDetails? {
        return try {
            val apiUrl = "https://byseepoin.com/api/videos/$videoId/embed/details"
            println("📡 Buscando detalhes: $apiUrl")
            
            val headers = mapOf(
                "Accept" to "application/json, text/plain, */*",
                "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
                "Cache-Control" to "no-cache",
                "Pragma" to "no-cache",
                "Referer" to "https://byseepoin.com/",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                "Origin" to "https://byseepoin.com",
                "Cookie" to API_COOKIE
            )
            
            val response = app.get(apiUrl, headers = headers)
            
            if (response.statusCode == 200) {
                val jsonText = response.text
                println("📥 Resposta detalhes (${response.statusCode}): ${jsonText.take(200)}...")
                
                try {
                    val json = JSONObject(jsonText)
                    val fileId = json.getInt("id") // 52296122
                    val code = json.getString("code") // ouu59ray1kvp
                    val title = json.getString("title") // 1457-s01e01-dub
                    val embedFrameUrl = json.getString("embed_frame_url") // https://g9r6.com/2ur/ouu59ray1kvp
                    val posterUrl = if (json.has("poster_url")) json.getString("poster_url") else null
                    
                    VideoDetails(
                        videoId = code,
                        fileId = fileId,
                        title = title,
                        embedFrameUrl = embedFrameUrl,
                        posterUrl = posterUrl
                    )
                } catch (e: Exception) {
                    println("❌ Erro ao parsear JSON: ${e.message}")
                    // Fallback: extrair com regex
                    val title = extractFromJson(jsonText, "title")
                    val embedFrameUrl = extractFromJson(jsonText, "embed_frame_url")
                    val fileIdStr = extractFromJson(jsonText, "id") ?: "0"
                    
                    if (embedFrameUrl != null) {
                        VideoDetails(
                            videoId = videoId,
                            fileId = fileIdStr.toIntOrNull() ?: 0,
                            title = title ?: "Video $videoId",
                            embedFrameUrl = embedFrameUrl,
                            posterUrl = extractFromJson(jsonText, "poster_url")
                        )
                    } else {
                        null
                    }
                }
            } else {
                println("❌ Status code detalhes: ${response.statusCode}")
                null
            }
        } catch (e: Exception) {
            println("💥 Erro ao buscar detalhes: ${e.message}")
            null
        }
    }
    
    private suspend fun getM3u8Url(videoId: String, fileId: Int): String? {
        return try {
            // Primeiro, precisamos obter os dados de playback
            val playbackData = getPlaybackData(videoId)
            if (playbackData == null) {
                println("❌ Não consegui obter dados de playback")
                return null
            }
            
            println("🔐 Playback data obtido")
            println("⚙️  Algoritmo: ${playbackData.algorithm}")
            println("🔢 IV: ${playbackData.iv.take(20)}...")
            println("📦 Payload: ${playbackData.payload.take(30)}...")
            println("🔑 Key parts: ${playbackData.keyParts.size}")
            
            // Tentar construir a URL baseado no padrão que vimos
            buildM3u8Url(videoId, fileId, playbackData)
            
        } catch (e: Exception) {
            println("💥 Erro ao obter m3u8: ${e.message}")
            null
        }
    }
    
    private suspend fun getPlaybackData(videoId: String): PlaybackData? {
        return try {
            val apiUrl = "https://g9r6.com/api/videos/$videoId/embed/playback"
            println("📡 Buscando playback: $apiUrl")
            
            val headers = mapOf(
                "Accept" to "application/json, text/plain, */*",
                "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
                "Cache-Control" to "no-cache",
                "Pragma" to "no-cache",
                "Referer" to "https://g9r6.com/",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                "Origin" to "https://g9r6.com",
                "Cookie" to API_COOKIE
            )
            
            val response = app.get(apiUrl, headers = headers)
            
            if (response.statusCode == 200) {
                val jsonText = response.text
                println("📥 Resposta playback (${response.statusCode}): ${jsonText.take(300)}...")
                
                try {
                    val json = JSONObject(jsonText)
                    val playback = json.getJSONObject("playback")
                    
                    val algorithm = playback.getString("algorith") // Nota: está escrito "algorith" (errado)
                    val iv = playback.getString("iv")
                    val payload = playback.getString("payload")
                    val expiresAt = playback.getString("expires_at")
                    
                    val keyPartsArray = playback.getJSONArray("key_parts")
                    val keyParts = mutableListOf<String>()
                    for (i in 0 until keyPartsArray.length()) {
                        keyParts.add(keyPartsArray.getString(i))
                    }
                    
                    // Extrair decrypt_keys se existirem
                    val decryptKeys = mutableMapOf<String, String>()
                    if (playback.has("decrypt_keys")) {
                        val decryptKeysObj = playback.getJSONObject("decrypt_keys")
                        val keys = decryptKeysObj.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            decryptKeys[key] = decryptKeysObj.getString(key)
                        }
                    }
                    
                    PlaybackData(
                        algorithm = algorithm,
                        iv = iv,
                        payload = payload,
                        keyParts = keyParts,
                        expiresAt = expiresAt,
                        decryptKeys = decryptKeys
                    )
                } catch (e: Exception) {
                    println("❌ Erro ao parsear playback JSON: ${e.message}")
                    null
                }
            } else {
                println("❌ Status code playback: ${response.statusCode}")
                null
            }
        } catch (e: Exception) {
            println("💥 Erro ao buscar playback: ${e.message}")
            null
        }
    }
    
    private fun buildM3u8Url(videoId: String, fileId: Int, playbackData: PlaybackData): String? {
        return try {
            // Baseado na URL que vimos nas imagens
            // Padrão: https://[server]/hls2/05/10459/{videoId}_h/master.m3u8?t=TOKEN&s=TIMESTAMP&e=EXPIRATION&f=FILE_ID&srv=SERVER_ID&sp=SPEED&p=PART
            
            // O server parece ser dinâmico: be2719.rcr22.ams01.i8yz83pn.com
            // Vamos tentar diferentes patterns de server
            
            val servers = listOf(
                "be2719.rcr22.ams01.i8yz83pn.com",
                "rcr22.ams01.i8yz83pn.com",
                "ams01.i8yz83pn.com",
                "i8yz83pn.com"
            )
            
            // Tentar gerar token baseado nos dados (simplificação)
            // Na prática, o token parece ser derivado da descriptografia do payload
            val timestamp = System.currentTimeMillis() / 1000
            val expiration = 10800 // 3 horas
            val serverId = 1070
            val speed = 4000
            val part = 0
            
            // Se temos decrypt_keys, tentar usá-las
            var token = playbackData.decryptKeys["legacy_fallback"] ?: 
                       playbackData.decryptKeys["edge_1"] ?: 
                       playbackData.decryptKeys["edge_2"] ?: 
                       generateFallbackToken(playbackData)
            
            // Limpar token (remover quebras de linha etc)
            token = token.replace("\n", "").replace("\r", "").trim()
            
            for (server in servers) {
                val m3u8Url = "https://$server/hls2/05/10459/${videoId}_h/master.m3u8" +
                    "?t=${URLEncoder.encode(token, "UTF-8")}" +
                    "&s=$timestamp" +
                    "&e=$expiration" +
                    "&f=$fileId" +
                    "&srv=$serverId" +
                    "&sp=$speed" +
                    "&p=$part"
                
                println("🔗 Tentando URL: ${m3u8Url.take(80)}...")
                
                // Testar se a URL é válida
                if (testUrl(m3u8Url)) {
                    return m3u8Url
                }
            }
            
            null
            
        } catch (e: Exception) {
            println("💥 Erro ao construir URL: ${e.message}")
            null
        }
    }
    
    private fun generateFallbackToken(playbackData: PlaybackData): String {
        // Fallback: criar um token simples baseado nos dados disponíveis
        val combined = playbackData.keyParts.joinToString("") + playbackData.iv
        return Base64.getEncoder().encodeToString(combined.toByteArray())
    }
    
    private suspend fun testUrl(url: String): Boolean {
        return try {
            val response = app.get(url, timeout = 10000)
            response.statusCode == 200 && response.text.contains("#EXTM3U")
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun generateM3u8Links(
        m3u8Url: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🔄 Gerando links M3U8...")
            println("🎯 URL: $m3u8Url")
            println("🔗 Referer: $referer")
            
            val links = M3u8Helper.generateM3u8(
                name,
                m3u8Url,
                referer,
                headers = mapOf(
                    "Referer" to referer,
                    "Origin" to referer.removeSuffix("/"),
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                    "Accept" to "*/*",
                    "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
                    "Accept-Encoding" to "gzip, deflate, br"
                )
            )
            
            if (links.isNotEmpty()) {
                links.forEach(callback)
                println("🎉 ${links.size} links M3U8 gerados com sucesso!")
                true
            } else {
                println("❌ Nenhum link M3U8 gerado")
                false
            }
        } catch (e: Exception) {
            println("💥 Erro ao gerar links M3U8: ${e.message}")
            false
        }
    }
    
    private fun extractFromJson(json: String, key: String): String? {
        val pattern = Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"")
        val match = pattern.find(json)
        return match?.groupValues?.get(1)
    }
    
    // Data classes
    data class VideoDetails(
        val videoId: String,
        val fileId: Int,
        val title: String,
        val embedFrameUrl: String,
        val posterUrl: String?
    )
    
    data class PlaybackData(
        val algorithm: String,
        val iv: String,
        val payload: String,
        val keyParts: List<String>,
        val expiresAt: String,
        val decryptKeys: Map<String, String> = emptyMap()
    )
}
