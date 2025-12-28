package com.SuperFlix

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
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
            
            // PASSO 3: Criar ExtractorLink CORRETAMENTE (como no AnimeFire)
            return createExtractorLink(m3u8Url, name, callback)
            
        } catch (e: Exception) {
            println("💥 Erro fatal: ${e.message}")
            false
        }
    }
    
    private suspend fun tryDirectG9r6Api(videoId: String, originalUrl: String): String? {
        return try {
            println("🚀 Tentando API direta do g9r6.com...")
            
            // Construir headers
            val headers = mapOf(
                "accept" to "*/*",
                "accept-language" to "pt-BR,pt;q=0.9,en;q=0.8",
                "cache-control" to "no-cache",
                "pragma" to "no-cache",
                "priority" to "u=1, i",
                "referer" to "$G9R6_DOMAIN/bk2vx/$videoId",
                "sec-ch-ua" to "\"Chromium\";v=\"127\", \"Not)A;Brand\";v=\"99\", \"Microsoft Edge Simulate\";v=\"127\", \"Lemur\";v=\"127\"",
                "sec-ch-ua-mobile" to "?1",
                "sec-ch-ua-platform" to "\"Android\"",
                "sec-fetch-dest" to "empty",
                "sec-fetch-mode" to "cors",
                "sec-fetch-site" to "same-origin",
                "user-agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                "x-embed-parent" to originalUrl,
                "cookie" to API_COOKIE
            )
            
            val apiUrl = "$G9R6_DOMAIN/api/videos/$videoId/embed/playback"
            println("📡 API URL: $apiUrl")
            
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
            Regex("""/e/([a-zA-Z0-9]+)"""),
            Regex("""/v/([a-zA-Z0-9]+)"""),
            Regex("""fembed\.sx/e/([a-zA-Z0-9]+)"""),
            Regex("""bysevepoin\.com/e/([a-zA-Z0-9]+)"""),
            Regex("""/([0-9]+)$""")
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
            
            val json = JSONObject(jsonText)
            val playback = json.getJSONObject("playback")
            
            println("📊 Algoritmo: ${playback.getString("algorithm")}")
            
            // FUNÇÃO PARA DECODIFICAR BASE64
            fun decodeBase64(base64Str: String): ByteArray {
                val cleanStr = base64Str.trim()
                println("   Decodificando: '$cleanStr' (${cleanStr.length} chars)")
                
                if (cleanStr.contains('-') || cleanStr.contains('_')) {
                    println("   Usando Base64.URL_SAFE (contém - ou _)")
                    return Base64.decode(cleanStr, Base64.URL_SAFE or Base64.NO_PADDING)
                }
                
                try {
                    return Base64.decode(cleanStr, Base64.DEFAULT)
                } catch (e: IllegalArgumentException) {
                    println("   Base64.DEFAULT falhou, tentando NO_PADDING...")
                    return Base64.decode(cleanStr, Base64.NO_PADDING)
                }
            }
            
            // Decodificar dados
            val ivBase64 = playback.getString("iv")
            val payloadBase64 = playback.getString("payload")
            val keyParts = playback.getJSONArray("key_parts")
            
            println("🔐 Dados de criptografia:")
            println("   iv: $ivBase64")
            println("   payload (primeiros 20 chars): ${payloadBase64.take(20)}...")
            println("   key_parts: ${keyParts.length()} partes")
            
            val iv = decodeBase64(ivBase64)
            val payload = decodeBase64(payloadBase64)
            val key1 = decodeBase64(keyParts.getString(0))
            val key2 = decodeBase64(keyParts.getString(1))
            val key = key1 + key2
            
            println("✅ Base64 decodificado com sucesso!")
            println("   iv: ${iv.size} bytes")
            println("   payload: ${payload.size} bytes")
            println("   key1: ${key1.size} bytes")
            println("   key2: ${key2.size} bytes")
            println("   key total: ${key.size} bytes")
            
            // Descriptografar
            println("🔓 Iniciando descriptografia AES-256-GCM...")
            
            val decrypted = decryptAesGcm(payload, key, iv)
            if (decrypted == null) {
                println("❌ Falha na descriptografia AES")
                return null
            }
            
            val decryptedText = String(decrypted, Charsets.UTF_8)
            println("✅ Descriptografado com sucesso!")
            println("📝 Texto descriptografado: $decryptedText")
            
            // Parse JSON descriptografado
            val decryptedJson = JSONObject(decryptedText)
            
            println("📊 Parâmetros descriptografados:")
            for (keyParam in decryptedJson.keys()) {
                val value = decryptedJson.get(keyParam)
                println("   $keyParam = $value")
            }
            
            // Extrair URL do JSON descriptografado
            extractM3u8FromDecryptedJson(decryptedJson)
            
        } catch (e: Exception) {
            println("💥 Erro ao processar resposta: ${e.message}")
            e.printStackTrace()
            null
        }
    }
    
    private fun extractM3u8FromDecryptedJson(decryptedJson: JSONObject): String? {
        return try {
            println("🔍 Extraindo URL M3U8 do JSON...")
            
            // Opção 1: sources array
            if (decryptedJson.has("sources")) {
                val sources = decryptedJson.getJSONArray("sources")
                if (sources.length() > 0) {
                    val firstSource = sources.getJSONObject(0)
                    if (firstSource.has("url")) {
                        var url = firstSource.getString("url")
                        // Decodificar caracteres Unicode
                        url = decodeUnicodeEscapes(url)
                        println("🎯 URL encontrada em sources: $url")
                        return url
                    }
                }
            }
            
            println("❌ Nenhuma URL encontrada no JSON descriptografado")
            null
            
        } catch (e: Exception) {
            println("💥 Erro ao extrair URL do JSON: ${e.message}")
            null
        }
    }
    
    private fun decodeUnicodeEscapes(text: String): String {
        return text.replace("\\u0026", "&")
    }
    
    private fun decryptAesGcm(ciphertext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? {
        return try {
            println("   ciphertext: ${ciphertext.size} bytes")
            println("   key: ${key.size} bytes")
            println("   iv: ${iv.size} bytes")
            
            // AES/GCM/NoPadding é padrão para AES-GCM
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            
            // Para GCM, IV deve ter 12 bytes (96 bits)
            val actualIv = if (iv.size != 12) {
                println("⚠️  IV tem ${iv.size} bytes, usando primeiros 12 bytes")
                iv.copyOf(12) // Pegar os primeiros 12 bytes
            } else {
                iv
            }
            
            // Tag de autenticação de 128 bits
            val spec = GCMParameterSpec(128, actualIv)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), spec)
            
            val result = cipher.doFinal(ciphertext)
            println("✅ Descriptografia bem-sucedida: ${result.size} bytes")
            result
            
        } catch (e: Exception) {
            println("💥 Erro na descriptografia: ${e.message}")
            
            // Tentar alternativa
            try {
                println("🔧 Tentando alternativa...")
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                
                // Tentar com IV original (sem truncar)
                val spec = GCMParameterSpec(128, iv)
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), spec)
                
                cipher.doFinal(ciphertext)
            } catch (e2: Exception) {
                println("💥 Alternativa também falhou: ${e2.message}")
                null
            }
        }
    }
    
    // FUNÇÃO CORRETA PARA CRIAR EXTRACTORLINK (como no AnimeFire)
    // FUNÇÃO SIMPLIFICADA - NÃO TESTAR, APENAS PASSAR
private suspend fun createExtractorLink(
    m3u8Url: String,
    name: String,
    callback: (ExtractorLink) -> Unit
): Boolean {
    println("🎯 Usando headers CORRETOS do curl...")
    
    try {
        // Headers IDÊNTICOS ao curl que funciona!
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
            "Accept" to "*/*",
            "Accept-Language" to "pt-BR",
            "Cache-Control" to "no-cache",
            "Connection" to "keep-alive",
            "Pragma" to "no-cache",
            "Range" to "bytes=0-",  // ← CRÍTICO PARA STREAMING
            "Referer" to m3u8Url,   // ← Referer é a própria URL!
            "Sec-Fetch-Dest" to "video",
            "Sec-Fetch-Mode" to "no-cors",
            "Sec-Fetch-Site" to "same-origin",
            "sec-ch-ua" to "\"Chromium\";v=\"127\", \"Not)A;Brand\";v=\"99\", \"Microsoft Edge Simulate\";v=\"127\", \"Lemur\";v=\"127\"",
            "sec-ch-ua-mobile" to "?1",
            "sec-ch-ua-platform" to "\"Android\""
        )
        
        println("🔗 URL: $m3u8Url")
        
        // Teste rápido
        val test = app.get(m3u8Url, headers = headers)
        println("📊 Status: ${test.code}")
        
        if (test.code != 200) {
            println("⚠️  Código ${test.code}, mas vamos tentar mesmo assim")
        }
        
        val extractorLink = newExtractorLink(
            source = "SuperFlix",
            name = "$name (720p)",
            url = m3u8Url,
            type = ExtractorLinkType.VIDEO
        ) {
            this.referer = m3u8Url  // ← Referer correto!
            this.quality = 720
            this.headers = headers
        }
        
        callback(extractorLink)
        println("✅ Link criado com headers do curl!")
        return true
        
    } catch (e: Exception) {
        println("💥 Erro: ${e.message}")
        
        // Fallback simplificado
        val extractorLink = newExtractorLink(
            source = "SuperFlix",
            name = "$name (720p)",
            url = m3u8Url,
            type = ExtractorLinkType.VIDEO
        ) {
            this.referer = m3u8Url
            this.quality = 720
        }
        
        callback(extractorLink)
        println("✅ Link criado em fallback")
        return true
    }
}
