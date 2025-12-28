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
    private const val API_COOKIE = "SITE_TOTAL_ID=aTYqe6GU65PNmeCXpelwJwAAAMi; __dtsu=104017651574995957BEB724C6373F9E; __cc_id=a44d1e52993b9c2Oaaf40eba24989a06; __cc_cc=ACZ4nGNQSDQXsTFMNTWyTDROskw2MkhMTDDMXSE1KNDKxtLBMNDBjAIJMC4fgVe%2B%2F%2BDngAHemT8XsDBKrv%2FF3%2F2F2%2F%2FF0ZGhFP15u.VnW-1Y0o8o6/84-1.2.1.1-4_OXh2hYevsbO8hINijDKB8O_SPowh.pNojloHEbwX_qZorbmW8u8zqV9B7UsV6bbRmCWx_dD17mA7vJJklpOD9WBh9DA0wMV2a1QSKuR2J3FN9.TRzOUM4AhnTGFd8dJH8bHfqQdY7uYuUg7Ny1TVQDF9kXqyEPtnmkZ9rFkqQ2KS6u0t2hhFdQvRBY7dqyGfdjmyjDqwc7ZOovHB0eqep.FPHrh8T9iz1LuucA; cf_clearance=rfIEldahI7B..Y4PpZhGgwi.QOJBqIRGdFP150.VnW-1766868784-1.1-"
    
    // Domínios
    private const val G9R6_DOMAIN = "https://g9r6.com"
    private const val BYSEVEPOIN_DOMAIN = "https://bysevepoin.com"
    private const val FEMBED_DOMAIN = "https://fembed.sx"
    
    suspend fun extractVideoLinks(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🎯 SuperFlixExtractor: Iniciando extração...")
        println("🔗 URL original: $url")
        
        return try {
            // PASSO 1: Extrair ID do vídeo
            val videoId = extractVideoId(url)
            if (videoId == null) {
                println("❌ Não consegui extrair ID")
                return false
            }
            
            println("✅ ID extraído: $videoId")
            
            // PASSO 2: Verificar tipo de ID
            val m3u8Url = if (videoId.length > 10 && videoId.matches(Regex("[a-z0-9]+"))) {
                // ID longo (ex: ouu59ray1kvp) - API direta
                tryDirectG9r6Api(videoId, url)
            } else {
                // ID curto (ex: 1497017) - Fluxo completo
                tryFullFlow(videoId, url)
            }
            
            if (m3u8Url == null) {
                println("❌ Não consegui obter URL M3U8")
                return false
            }
            
            println("🎬 URL M3U8 final: $m3u8Url")
            
            // PASSO 3: Gerar links M3U8
            generateM3u8Links(m3u8Url, name, callback)
            
        } catch (e: Exception) {
            println("💥 Erro fatal: ${e.message}")
            false
        }
    }
    
    private suspend fun tryDirectG9r6Api(videoId: String, originalUrl: String): String? {
        return try {
            println("🚀 Tentando API direta do g9r6.com...")
            
            // Construir headers EXATAMENTE como no curl
            val headers = mapOf(
                "accept" to "*/*",
                "accept-language" to "pt-BR,pt;q=0.9,en;q=0.8",
                "cache-control" to "no-cache",
                "pragma" to "no-cache",
                "priority" to "u=1, i",
                // REFERER CRÍTICO: https://g9r6.com/bk2vx/{videoId}
                "referer" to "$G9R6_DOMAIN/bk2vx/$videoId",
                "sec-ch-ua" to "\"Chromium\";v=\"127\", \"Not)A;Brand\";v=\"99\", \"Microsoft Edge Simulate\";v=\"127\", \"Lemur\";v=\"127\"",
                "sec-ch-ua-mobile" to "?1",
                "sec-ch-ua-platform" to "\"Android\"",
                "sec-fetch-dest" to "empty",
                "sec-fetch-mode" to "cors",
                "sec-fetch-site" to "same-origin",
                "user-agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                // HEADER MAIS IMPORTANTE: X-Embed-Parent
                "x-embed-parent" to originalUrl, // URL original do bysevepoin
                "cookie" to API_COOKIE
            )
            
            val apiUrl = "$G9R6_DOMAIN/api/videos/$videoId/embed/playback"
            println("📡 API URL: $apiUrl")
            println("🔑 Referer: ${headers["referer"]}")
            println("🔑 X-Embed-Parent: ${headers["x-embed-parent"]}")
            
            val response = app.get(apiUrl, headers = headers)
            
            if (response.code != 200) {
                println("❌ API retornou ${response.code}: ${response.text.take(200)}")
                return null
            }
            
            println("✅ API respondeu com sucesso!")
            
            // Processar resposta criptografada
            processEncryptedResponse(response.text, videoId)
            
        } catch (e: Exception) {
            println("⚠️  Falha na API direta: ${e.message}")
            null
        }
    }
    
    private suspend fun tryFullFlow(shortVideoId: String, originalUrl: String): String? {
        return try {
            println("🔄 Iniciando fluxo completo...")
            
            // PASSO 1: Obter iframe do Fembed
            val iframeUrl = getFembedIframe(shortVideoId)
            if (iframeUrl == null) {
                println("❌ Falha ao obter iframe do Fembed")
                return null
            }
            
            println("✅ Iframe obtido: $iframeUrl")
            
            // PASSO 2: Acessar iframe para obter URL do Bysevepoin
            val bysevepoinUrl = getBysevepoinFromIframe(iframeUrl, shortVideoId)
            if (bysevepoinUrl == null) {
                println("❌ Falha ao obter URL do Bysevepoin")
                return null
            }
            
            println("✅ URL do Bysevepoin: $bysevepoinUrl")
            
            // PASSO 3: Extrair ID real do Bysevepoin
            val realVideoId = extractRealVideoId(bysevepoinUrl)
            if (realVideoId == null) {
                println("❌ Não consegui extrair ID real")
                return null
            }
            
            println("🎯 ID real encontrado: $realVideoId")
            
            // PASSO 4: Acessar API do g9r6.com com ID real
            tryDirectG9r6Api(realVideoId, bysevepoinUrl)
            
        } catch (e: Exception) {
            println("💥 Erro no fluxo completo: ${e.message}")
            null
        }
    }
    
    private suspend fun getFembedIframe(videoId: String): String? {
        return try {
            val apiUrl = "$FEMBED_DOMAIN/api.php?s=$videoId&c="
            println("📡 POST para Fembed: $apiUrl")
            
            val headers = mapOf(
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to "$FEMBED_DOMAIN/e/$videoId",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                "Cookie" to API_COOKIE
            )
            
            val postData = mapOf(
                "action" to "getPlayer",
                "lang" to "DUB",
                "key" to "MA=="
            )
            
            val response = app.post(apiUrl, headers = headers, data = postData)
            val text = response.text
            
            // Extrair URL do iframe
            val iframeRegex = Regex("""<iframe[^>]+src=["']([^"']+)["']""")
            val match = iframeRegex.find(text)
            
            if (match != null) {
                var url = match.groupValues[1]
                if (url.startsWith("/")) {
                    url = "$FEMBED_DOMAIN$url"
                }
                url
            } else {
                null
            }
            
        } catch (e: Exception) {
            println("⚠️  Erro no Fembed: ${e.message}")
            null
        }
    }
    
    private suspend fun getBysevepoinFromIframe(iframeUrl: String, videoId: String): String? {
        return try {
            println("🔍 Acessando iframe: $iframeUrl")
            
            val headers = mapOf(
                "Referer" to "$FEMBED_DOMAIN/e/$videoId",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                "Cookie" to API_COOKIE
            )
            
            val response = app.get(iframeUrl, headers = headers)
            val html = response.text
            
            // Procurar URL do Bysevepoin
            val pattern = Regex("""<iframe[^>]+src=["'](https?://bysevepoin\.com/[^"']+)["']""")
            val match = pattern.find(html)
            
            match?.groupValues?.get(1)
            
        } catch (e: Exception) {
            println("⚠️  Erro no iframe: ${e.message}")
            null
        }
    }
    
    private fun extractRealVideoId(bysevepoinUrl: String): String? {
        // Formato: /e/yziqjcntix6v/1497017-dub
        val pattern = Regex("""/e/([a-zA-Z0-9]+)(?:/|$)""")
        val match = pattern.find(bysevepoinUrl)
        return match?.groupValues?.get(1)
    }
    
    private fun extractVideoId(url: String): String? {
        val patterns = listOf(
            Regex("""/e/([a-zA-Z0-9]+)"""),              // /e/ouu59ray1kvp
            Regex("""/v/([a-zA-Z0-9]+)"""),              // /v/283689
            Regex("""fembed\.sx/e/([a-zA-Z0-9]+)"""),    // fembed.sx/e/1497017
            Regex("""bysevepoin\.com/e/([a-zA-Z0-9]+)"""), // bysevepoin.com/e/yziqjcntix6v
            Regex("""/([0-9]+)$""")                      // /1497017
        )
        
        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        
        return null
    }
    
    private fun processEncryptedResponse(jsonText: String, videoId: String): String? {
    return try {
        println("🔧 Processando resposta da API...")
        println("📄 JSON recebido: ${jsonText.take(500)}...")
        
        val json = JSONObject(jsonText)
        
        // Verificar se tem erro
        if (json.has("error")) {
            println("❌ Erro no JSON: ${json.getString("error")}")
            return null
        }
        
        if (!json.has("playback")) {
            println("❌ JSON não tem campo 'playback'")
            println("📊 JSON completo: ${json.toString().take(500)}")
            return null
        }
        
        val playback = json.getJSONObject("playback")
        
        // LOG DETALHADO para debug
        println("📊 Campos do playback:")
        for (key in playback.keys()) {
            val value = playback.get(key)
            println("   $key = ${if (value.toString().length > 100) "${value.toString().take(100)}..." else value}")
        }
        
        // Verificar campos obrigatórios
        if (!playback.has("iv") || !playback.has("payload") || !playback.has("key_parts")) {
            println("❌ Campos de criptografia ausentes")
            return null
        }
        
        // EXTRAIR BASE64 CORRETAMENTE
        val ivBase64 = playback.getString("iv")
        val payloadBase64 = playback.getString("payload")
        val keyParts = playback.getJSONArray("key_parts")
        
        println("🔐 Dados de criptografia:")
        println("   iv (Base64): ${ivBase64.take(50)}... (${ivBase64.length} chars)")
        println("   payload (Base64): ${payloadBase64.take(50)}... (${payloadBase64.length} chars)")
        println("   key_parts: ${keyParts.length()} partes")
        
        for (i in 0 until keyParts.length()) {
            val part = keyParts.getString(i)
            println("     Parte $i: ${part.take(50)}... (${part.length} chars)")
        }
        
        // DECODIFICAR BASE64 CORRETAMENTE
        try {
            val iv = Base64.decode(ivBase64, Base64.DEFAULT)
            val payload = Base64.decode(payloadBase64, Base64.DEFAULT)
            
            println("✅ Base64 decodificado:")
            println("   iv: ${iv.size} bytes")
            println("   payload: ${payload.size} bytes")
            
            // Juntar partes da chave
            val key1 = Base64.decode(keyParts.getString(0), Base64.DEFAULT)
            val key2 = Base64.decode(keyParts.getString(1), Base64.DEFAULT)
            val key = key1 + key2
            
            println("   key1: ${key1.size} bytes")
            println("   key2: ${key2.size} bytes")
            println("   key total: ${key.size} bytes (esperado: 32 para AES-256)")
            
            // Verificar tamanhos
            if (key.size != 32) {
                println("⚠️  Atenção: chave tem ${key.size} bytes, esperado 32")
            }
            
            // Descriptografar
            val decrypted = decryptAesGcm(payload, key, iv)
            if (decrypted == null) {
                println("❌ Falha na descriptografia AES")
                return null
            }
            
            val decryptedText = String(decrypted, Charsets.UTF_8)
            println("✅ Descriptografado com sucesso!")
            println("📝 Texto descriptografado: ${decryptedText.take(500)}...")
            
            // Parse JSON descriptografado
            val decryptedJson = JSONObject(decryptedText)
            
            // LOG dos parâmetros
            println("📊 Parâmetros descriptografados:")
            for (keyParam in decryptedJson.keys()) {
                val value = decryptedJson.get(keyParam)
                println("   $keyParam = $value")
            }
            
            // Construir URL M3U8
            return buildM3u8Url(videoId, decryptedJson)
            
        } catch (e: Exception) {
            println("💥 Erro ao decodificar Base64: ${e.message}")
            println("🔍 Tentando Base64 com FLAGS diferentes...")
            
            // Tentar diferentes modos de Base64
            val base64Variants = listOf(
                Pair("DEFAULT", Base64.DEFAULT),
                Pair("NO_WRAP", Base64.NO_WRAP),
                Pair("NO_PADDING", Base64.NO_PADDING),
                Pair("URL_SAFE", Base64.URL_SAFE),
                Pair("NO_CLOSE", Base64.NO_CLOSE)
            )
            
            for ((name, flags) in base64Variants) {
                try {
                    println("🔧 Tentando Base64.$name...")
                    val iv = Base64.decode(ivBase64, flags)
                    val payload = Base64.decode(payloadBase64, flags)
                    println("✅ Base64.$name funcionou!")
                    
                    // Continuar com esta decodificação...
                    val key1 = Base64.decode(keyParts.getString(0), flags)
                    val key2 = Base64.decode(keyParts.getString(1), flags)
                    val key = key1 + key2
                    
                    val decrypted = decryptAesGcm(payload, key, iv)
                    if (decrypted != null) {
                        val decryptedText = String(decrypted, Charsets.UTF_8)
                        println("✅ Descriptografado com Base64.$name")
                        val decryptedJson = JSONObject(decryptedText)
                        return buildM3u8Url(videoId, decryptedJson)
                    }
                } catch (e2: Exception) {
                    println("❌ Base64.$name falhou: ${e2.message}")
                }
            }
            
            return null
        }
        
    } catch (e: Exception) {
        println("💥 Erro ao processar resposta: ${e.message}")
        e.printStackTrace()
        null
    }
                    }
    
    private fun buildM3u8Url(videoId: String, params: JSONObject): String {
        // Extrair parâmetros
        val token = params.optString("t", params.optString("token", ""))
        val timestamp = params.optString("s", params.optString("timestamp", ""))
        val expire = params.optString("e", params.optString("expire", ""))
        val fileId = params.optString("f", params.optString("file_id", ""))
        val server = params.optString("srv", params.optString("server", "1070"))
        val speed = params.optString("sp", params.optString("speed", "4000"))
        val quality = params.optString("p", params.optString("quality", "0"))
        
        // Verificar se tem domínio CDN no JSON
        val cdnDomain = params.optString("cdn", "be6721.rcr72.waw04.i8yz83pn.com")
        
        // Construir URL
        val url = "https://$cdnDomain/hls2/05/10459/${videoId}_h/master.m3u8" +
                 "?t=$token" +
                 "&s=$timestamp" +
                 "&e=$expire" +
                 "&f=$fileId" +
                 "&srv=$server" +
                 "&sp=$speed" +
                 "&p=$quality"
        
        println("🔗 URL construída: $url")
        return url
    }
    
    private fun decryptAesGcm(ciphertext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv) // 128-bit auth tag
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), spec)
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            println("🔒 Erro AES-GCM: ${e.message}")
            null
        }
    }
    
    private suspend fun generateM3u8Links(
        m3u8Url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🔄 Gerando links M3U8...")
            
            // Testar diferentes referers para o M3U8Helper
            val referers = listOf(
                "$G9R6_DOMAIN/bk2vx/",
                BYSEVEPOIN_DOMAIN,
                FEMBED_DOMAIN
            )
            
            for (referer in referers) {
                try {
                    println("🔧 Testando referer: $referer")
                    
                    val headers = mapOf(
                        "Referer" to referer,
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                        "Origin" to referer.removeSuffix("/")
                    )
                    
                    val links = M3u8Helper.generateM3u8(
                        name,
                        m3u8Url,
                        referer = referer,
                        headers = headers
                    )
                    
                    if (links.isNotEmpty()) {
                        links.forEach(callback)
                        println("🎉 SUCESSO! ${links.size} links gerados!")
                        return true
                    }
                } catch (e: Exception) {
                    println("⚠️  Falha com referer $referer: ${e.message}")
                }
            }
            
            println("❌ Nenhum link gerado")
            false
            
        } catch (e: Exception) {
            println("💥 Erro ao gerar links: ${e.message}")
            false
        }
    }
}
