package com.TopAnimes

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64
import java.util.*

object FileMoonExtractor {
    
    suspend fun extractVideoLinks(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔍 FILEMOON EXTRACTOR INICIADO")
        println("📄 URL do episódio: $url")
        
        return try {
            // 1. CARREGA PÁGINA DO EPISÓDIO
            println("📥 Baixando página do episódio...")
            val episodeResponse = app.get(url)
            val html = episodeResponse.text
            println("✅ Página carregada (${html.length} chars)")
            
            // 2. PROCURA URL DO FILEMOON NO HTML
            println("🔎 Procurando URL filemoon.sx/e/...")
            
            var filemoonUrl: String? = null
            
            // Padrão para filemoon
            val filemoonPattern = """https?://[^"\s']*filemoon\.sx/e/[^"\s']*""".toRegex()
            val matches = filemoonPattern.findAll(html)
            
            matches.forEach { match ->
                val foundUrl = match.value
                if (foundUrl.contains("filemoon.sx/e/")) {
                    println("🎯 URL FILEMOON ENCONTRADA: $foundUrl")
                    filemoonUrl = foundUrl
                }
            }
            
            if (filemoonUrl == null) {
                println("❌ Nenhum link filemoon.sx encontrado")
                return false
            }
            
            // 3. EXTRAI O ID DO VÍDEO
            val videoId = extractVideoId(filemoonUrl!!)
            if (videoId.isEmpty()) {
                println("❌ Não consegui extrair ID do vídeo")
                return false
            }
            
            println("🆔 ID do vídeo: $videoId")
            
            // 4. FAZ REQUISIÇÃO PARA A API
            println("📡 Fazendo requisição para API 9n8o.com...")
            
            val apiUrl = "https://9n8o.com/api/videos/$videoId/embed/playback"
            
            // Headers necessários
            val headers = mapOf(
                "accept" to "*/*",
                "accept-language" to "pt-BR",
                "content-type" to "application/json",
                "origin" to "https://9n8o.com",
                "referer" to filemoonUrl!!,
                "sec-ch-ua" to "\"Chromium\";v=\"127\"",
                "sec-ch-ua-mobile" to "?1",
                "sec-ch-ua-platform" to "\"Android\"",
                "sec-fetch-dest" to "empty",
                "sec-fetch-mode" to "cors",
                "sec-fetch-site" to "same-origin",
                "user-agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                "x-embed-parent" to filemoonUrl!!
            )
            
            // Corpo da requisição (fingerprint)
            val fingerprint = generateFingerprint()
            val requestBody = """
            {
                "fingerprint": {
                    "token": "${fingerprint.first}",
                    "viewer_id": "${fingerprint.second}",
                    "device_id": "${fingerprint.third}",
                    "confidence": 0.91
                }
            }
            """.trimIndent()
            
            // CORREÇÃO: Converte string para map
            val apiResponse = app.post(
                apiUrl, 
                headers = headers, 
                data = mapOf("body" to requestBody), // CONVERTE PARA MAP
                timeout = 30
            )
            
            if (apiResponse.code != 200) {
                println("❌ API respondeu com status ${apiResponse.code}")
                return false
            }
            
            val responseText = apiResponse.text
            println("✅ API respondeu (${responseText.length} chars)")
            
            // 5. PROCESSAR RESPOSTA (descriptografar)
            val m3u8Url = processFileMoonResponse(responseText)
            if (m3u8Url == null) {
                println("❌ Falha ao processar resposta da API")
                return false
            }
            
            println("🎬 LINK M3U8 ENCONTRADO: ${m3u8Url.take(100)}...")
            
            // 6. CRIAR EXTRACTORLINK
            createExtractorLink(m3u8Url, name, filemoonUrl!!, callback)
            true
            
        } catch (e: Exception) {
            println("💥 ERRO NO FILEMOON: ${e.message}")
            false
        }
    }
    
    private fun extractVideoId(url: String): String {
        // Exemplo: https://filemoon.sx/e/0l77x64hzznz
        val pattern = """filemoon\.sx/e/([a-zA-Z0-9]+)""".toRegex()
        val match = pattern.find(url)
        return match?.groupValues?.get(1) ?: ""
    }
    
    private fun generateFingerprint(): Triple<String, String, String> {
        // Gera dados aleatórios (pode usar fixos também)
        val viewerId = UUID.randomUUID().toString().replace("-", "").take(32)
        val deviceId = UUID.randomUUID().toString().replace("-", "").take(32)
        
        // Token fictício (na prática, o site gera um JWT)
        val token = Base64.encodeToString(
            """{"viewer_id":"$viewerId","device_id":"$deviceId","confidence":0.91}""".toByteArray(),
            Base64.URL_SAFE or Base64.NO_PADDING
        )
        
        return Triple(token, viewerId, deviceId)
    }
    
    private fun processFileMoonResponse(jsonText: String): String? {
        return try {
            println("🔬 Processando resposta JSON...")
            
            val json = JSONObject(jsonText)
            val playback = json.getJSONObject("playback")
            
            // Descriptografa igual ao SuperFlix
            val iv = decodeBase64(playback.getString("iv"))
            val payload = decodeBase64(playback.getString("payload"))
            val keyParts = playback.getJSONArray("key_parts")
            val key1 = decodeBase64(keyParts.getString(0))
            val key2 = decodeBase64(keyParts.getString(1))
            val key = key1 + key2
            
            val decrypted = decryptAesGcm(payload, key, iv)
            if (decrypted == null) {
                println("❌ Falha ao descriptografar")
                return null
            }
            
            val decryptedText = String(decrypted, Charsets.UTF_8)
            println("🔓 Texto descriptografado (${decryptedText.length} chars)")
            
            val decryptedJson = JSONObject(decryptedText)
            
            // Extrai URL do vídeo
            if (decryptedJson.has("sources")) {
                val sources = decryptedJson.getJSONArray("sources")
                if (sources.length() > 0) {
                    val source = sources.getJSONObject(0)
                    return source.getString("url")
                }
            }
            
            null
            
        } catch (e: Exception) {
            println("💥 Erro processando resposta: ${e.message}")
            null
        }
    }
    
    private fun decodeBase64(base64Str: String): ByteArray {
        val cleanStr = base64Str.trim()
        if (cleanStr.contains('-') || cleanStr.contains('_')) {
            return Base64.decode(cleanStr, Base64.URL_SAFE or Base64.NO_PADDING)
        }
        try {
            return Base64.decode(cleanStr, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            return Base64.decode(cleanStr, Base64.NO_PADDING)
        }
    }
    
    private fun decryptAesGcm(ciphertext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val actualIv = if (iv.size != 12) iv.copyOf(12) else iv
            val spec = GCMParameterSpec(128, actualIv)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), spec)
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            println("💥 Erro descriptografando: ${e.message}")
            null
        }
    }
    
    private suspend fun createExtractorLink(
        m3u8Url: String,
        name: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
            "Referer" to referer,
            "Origin" to "https://filemoon.sx",
            "Accept" to "*/*"
        )
        
        try {
            val links = M3u8Helper.generateM3u8(
                source = "FileMoon",
                streamUrl = m3u8Url,
                referer = referer,
                headers = headers
            )
            
            if (links.isNotEmpty()) {
                println("✅ ${links.size} links M3U8 gerados")
                links.forEach { callback(it) }
                return
            }
        } catch (e: Exception) {
            println("⚠️ M3u8Helper falhou: ${e.message}")
        }
        
        val extractorLink = newExtractorLink(
            source = "FileMoon",
            name = "$name [HLS]",
            url = m3u8Url,
            type = ExtractorLinkType.M3U8
        ) {
            this.referer = referer
            this.quality = 720
            this.headers = headers
        }
        
        callback(extractorLink)
        println("✅ ExtractorLink criado")
    }
}
