package com.SuperFlix

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64
import java.net.URLEncoder

object SuperFlixExtractor {
    // Domínios principais
    private const val MAIN_DOMAIN = "https://g9r6.com"
    private const val PLAYER_DOMAIN = "https://bysevepoin.com"
    
    // Headers para simular navegador real
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
        "Referer" to "$PLAYER_DOMAIN/",
        "Origin" to PLAYER_DOMAIN,
        "DNT" to "1",
        "Connection" to "keep-alive",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "cross-site"
    )
    
    suspend fun extractVideoLinks(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🎯 SuperFlixExtractor: Iniciando extração via API...")
        
        try {
            // 1. Extrair ID do vídeo
            val videoId = extractVideoId(url)
            if (videoId == null) {
                println("❌ Não consegui extrair ID da URL: $url")
                return false
            }
            
            println("✅ ID extraído: $videoId")
            
            // 2. Obter dados de playback criptografados
            val playbackData = getPlaybackData(videoId)
            if (playbackData == null) {
                println("❌ Falha ao obter dados de playback")
                return false
            }
            
            println("✅ Dados de playback obtidos com sucesso")
            
            // 3. Descriptografar payload
            val decryptedParams = decryptPlaybackData(playbackData)
            if (decryptedParams == null) {
                println("❌ Falha ao descriptografar payload")
                return false
            }
            
            println("✅ Payload descriptografado: $decryptedParams")
            
            // 4. Construir URL do m3u8
            val m3u8Url = buildM3u8Url(videoId, decryptedParams)
            println("🎬 URL M3U8 gerada: $m3u8Url")
            
            // 5. Gerar links M3U8
            return generateM3u8Links(m3u8Url, name, callback)
            
        } catch (e: Exception) {
            println("💥 Erro na extração: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
    
    private fun extractVideoId(url: String): String? {
        val patterns = listOf(
            Regex("""/e/([a-zA-Z0-9]+)"""),
            Regex("""/v/([a-zA-Z0-9]+)"""),
            Regex("""/videos/([a-zA-Z0-9]+)"""),
            Regex("""\?id=([a-zA-Z0-9]+)"""),
            Regex("""&id=([a-zA-Z0-9]+)"""),
            Regex("""/([a-zA-Z0-9]{6,})""")
        )
        
        for (pattern in patterns) {
            pattern.find(url)?.let { match ->
                val id = match.groupValues[1]
                if (id.length >= 6) {
                    return id
                }
            }
        }
        return null
    }
    
    private suspend fun getPlaybackData(videoId: String): JSONObject? {
        return try {
            val apiUrl = "$MAIN_DOMAIN/api/videos/$videoId/embed/playback"
            println("📡 Buscando dados de playback: $apiUrl")
            
            val response = app.get(apiUrl, headers = headers)
            
            if (response.code != 200) {
                println("❌ Status code: ${response.code}")
                println("📄 Resposta: ${response.text.take(500)}")
                return null
            }
            
            val json = JSONObject(response.text)
            if (!json.has("playback")) {
                println("❌ JSON não contém 'playback': ${json.toString().take(500)}")
                return null
            }
            
            json.getJSONObject("playback")
            
        } catch (e: Exception) {
            println("💥 Erro ao obter playback data: ${e.message}")
            null
        }
    }
    
    private fun decryptPlaybackData(playback: JSONObject): Map<String, String>? {
        return try {
            // Extrair dados de criptografia
            val iv = Base64.decode(playback.getString("iv"), Base64.DEFAULT)
            val payload = Base64.decode(playback.getString("payload"), Base64.DEFAULT)
            val keyParts = playback.getJSONArray("key_parts")
            
            // Juntar partes da chave
            val key1 = Base64.decode(keyParts.getString(0), Base64.DEFAULT)
            val key2 = Base64.decode(keyParts.getString(1), Base64.DEFAULT)
            val key = key1 + key2
            
            println("🔐 Descriptografando: key=${key.size} bytes, iv=${iv.size} bytes, payload=${payload.size} bytes")
            
            // Descriptografar AES-256-GCM
            val decrypted = decryptAesGcm(payload, key, iv)
            if (decrypted == null) {
                println("❌ Falha na descriptografia")
                return null
            }
            
            val decryptedText = String(decrypted, Charsets.UTF_8)
            println("✅ Texto descriptografado: $decryptedText")
            
            // Parse dos parâmetros (pode ser JSON ou query string)
            return parseDecryptedData(decryptedText)
            
        } catch (e: Exception) {
            println("💥 Erro na descriptografia: ${e.message}")
            null
        }
    }
    
    private fun parseDecryptedData(decryptedText: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        
        try {
            // Tentar como JSON primeiro
            val json = JSONObject(decryptedText)
            for (key in json.keys()) {
                params[key] = json.getString(key)
            }
        } catch (e: Exception) {
            // Se não for JSON, tentar como query string
            println("⚠️  Não é JSON, tentando como query string")
            decryptedText.split("&").forEach { pair ->
                val parts = pair.split("=")
                if (parts.size == 2) {
                    params[parts[0]] = parts[1]
                }
            }
        }
        
        return params
    }
    
    private fun buildM3u8Url(videoId: String, params: Map<String, String>): String {
        // Padrão observado: https://[CDN]/hls2/05/10459/[videoId]_h/master.m3u8?t=token&s=timestamp...
        
        // Extrair parâmetros necessários
        val token = params["t"] ?: params["token"] ?: ""
        val timestamp = params["s"] ?: params["timestamp"] ?: System.currentTimeMillis().toString()
        val expire = params["e"] ?: params["expire"] ?: (System.currentTimeMillis() + 10800000).toString() // +3 horas
        val fileId = params["f"] ?: params["file_id"] ?: "1"
        val server = params["srv"] ?: params["server"] ?: "1070"
        val speed = params["sp"] ?: params["speed"] ?: "4000"
        val quality = params["p"] ?: params["quality"] ?: "0"
        
        // Domínio CDN (pode variar)
        val cdnDomain = params["cdn"] ?: "be6721.rcr72.waw04.i8yz83pn.com"
        
        return "https://$cdnDomain/hls2/05/10459/${videoId}_h/master.m3u8" +
               "?t=$token" +
               "&s=$timestamp" +
               "&e=$expire" +
               "&f=$fileId" +
               "&srv=$server" +
               "&sp=$speed" +
               "&p=$quality"
    }
    
    private suspend fun generateM3u8Links(
        m3u8Url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🔄 Gerando links M3U8...")
            
            val links = M3u8Helper.generateM3u8(
                name,
                m3u8Url,
                referer = PLAYER_DOMAIN,
                headers = headers
            )
            
            if (links.isNotEmpty()) {
                links.forEach(callback)
                println("🎉 SUCESSO! ${links.size} links M3U8 gerados!")
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
    
    private fun decryptAesGcm(ciphertext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv) // 128-bit auth tag
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), spec)
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            println("🔒 Erro na descriptografia AES-GCM: ${e.message}")
            null
        }
    }
}
