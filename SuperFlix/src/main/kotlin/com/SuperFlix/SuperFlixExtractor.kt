package com.SuperFlix

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

object SuperFlixExtractor {
    private const val MAIN_DOMAIN = "https://g9r6.com"
    private const val PLAYER_DOMAIN = "https://bysevepoin.com"
    
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
        "Referer" to "$PLAYER_DOMAIN/",
        "Origin" to PLAYER_DOMAIN,
        "Connection" to "keep-alive"
    )
    
    suspend fun extractVideoLinks(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🎯 SuperFlixExtractor: Iniciando...")
        println("🔗 URL: $url")
        
        return try {
            // 1. Extrair ID do vídeo (ex: ouu59ray1kvp)
            val videoId = extractVideoId(url)
            if (videoId.isNullOrEmpty()) {
                println("❌ Não consegui extrair ID da URL")
                return false
            }
            
            println("✅ ID extraído: $videoId")
            
            // 2. Fazer requisição para a API
            val apiUrl = "$MAIN_DOMAIN/api/videos/$videoId/embed/playback"
            println("📡 Acessando API: $apiUrl")
            
            val response = app.get(apiUrl, headers = headers)
            
            if (response.code != 200) {
                println("❌ Erro na API: Status ${response.code}")
                println("📄 Resposta: ${response.text}")
                return false
            }
            
            println("✅ API respondeu com sucesso")
            
            val json = JSONObject(response.text)
            
            // Verificar se tem erro
            if (json.has("error")) {
                println("❌ Erro no JSON: ${json.getString("error")}")
                return false
            }
            
            // 3. Extrair dados de playback
            val playback = json.getJSONObject("playback")
            
            // 4. Descriptografar payload
            val decryptedData = decryptPlayback(playback)
            if (decryptedData == null) {
                println("❌ Falha na descriptografia")
                return false
            }
            
            println("✅ Payload descriptografado!")
            println("📊 Dados: $decryptedData")
            
            // 5. Construir URL do m3u8
            val m3u8Url = buildM3u8Url(videoId, decryptedData)
            println("🎬 URL M3U8: $m3u8Url")
            
            // 6. Gerar links
            generateM3u8Links(m3u8Url, name, callback)
            
        } catch (e: Exception) {
            println("💥 Erro: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    private fun extractVideoId(url: String): String? {
        // Padrão: /e/{id}/{slug} ou /v/{id}/{slug}
        val patterns = listOf(
            Regex("""/(?:e|v)/([a-zA-Z0-9]+)(?:/|$)"""),
            Regex("""/videos/([a-zA-Z0-9]+)(?:/|$)"""),
            Regex("""id=([a-zA-Z0-9]+)""")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null) {
                val id = match.groupValues[1]
                if (id.length >= 8) { // IDs geralmente são longos
                    return id
                }
            }
        }
        
        return null
    }
    
    private fun decryptPlayback(playback: JSONObject): JSONObject? {
        return try {
            // Extrair dados de criptografia
            val iv = Base64.decode(playback.getString("iv"), Base64.DEFAULT)
            val payload = Base64.decode(playback.getString("payload"), Base64.DEFAULT)
            val keyParts = playback.getJSONArray("key_parts")
            
            // Juntar partes da chave
            val key1 = Base64.decode(keyParts.getString(0), Base64.DEFAULT)
            val key2 = Base64.decode(keyParts.getString(1), Base64.DEFAULT)
            val key = key1 + key2 // 32 bytes para AES-256
            
            println("🔐 Descriptografando: key=${key.size} bytes, iv=${iv.size} bytes")
            
            // Descriptografar AES-256-GCM
            val decrypted = decryptAesGcm(payload, key, iv)
            if (decrypted == null) {
                println("❌ Falha na descriptografia AES")
                return null
            }
            
            val decryptedText = String(decrypted, Charsets.UTF_8)
            println("✅ Texto descriptografado: ${decryptedText.take(200)}...")
            
            JSONObject(decryptedText)
            
        } catch (e: Exception) {
            println("💥 Erro na descriptografia: ${e.message}")
            null
        }
    }
    
    private fun buildM3u8Url(videoId: String, decryptedData: JSONObject): String {
        // Extrair parâmetros do JSON descriptografado
        // Estes são os nomes que vimos na análise anterior
        val token = decryptedData.optString("t", "")
        val timestamp = decryptedData.optString("s", "")
        val expire = decryptedData.optString("e", "")
        val fileId = decryptedData.optString("f", "")
        val server = decryptedData.optString("srv", "1070")
        val speed = decryptedData.optString("sp", "4000")
        val quality = decryptedData.optString("p", "0")
        
        // Domínio CDN (pode precisar ajustar)
        val cdnDomain = "be6721.rcr72.waw04.i8yz83pn.com"
        
        // Construir URL no formato observado
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
                println("🎉 SUCESSO! ${links.size} links gerados!")
                true
            } else {
                println("❌ Nenhum link gerado")
                false
            }
            
        } catch (e: Exception) {
            println("💥 Erro ao gerar links: ${e.message}")
            false
        }
    }
    
    private fun decryptAesGcm(ciphertext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), spec)
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            println("🔒 Erro AES-GCM: ${e.message}")
            null
        }
    }
}
