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
            
            // FUNÇÃO PARA DECODIFICAR BASE64 SEM PADDING
            fun decodeBase64NoPadding(base64Str: String): ByteArray {
                // Adicionar padding se necessário
                var padded = base64Str
                when (base64Str.length % 4) {
                    2 -> padded += "=="
                    3 -> padded += "="
                }
                return Base64.decode(padded, Base64.DEFAULT)
            }
            
            // Decodificar dados
            val ivBase64 = playback.getString("iv")
            val payloadBase64 = playback.getString("payload")
            val keyParts = playback.getJSONArray("key_parts")
            
            val iv = decodeBase64NoPadding(ivBase64)
            val payload = decodeBase64NoPadding(payloadBase64)
            val key1 = decodeBase64NoPadding(keyParts.getString(0))
            val key2 = decodeBase64NoPadding(keyParts.getString(1))
            val key = key1 + key2
            
            println("🔐 Dados de criptografia:")
            println("   iv: $ivBase64 -> ${iv.size} bytes")
            println("   payload: ${payloadBase64.take(20)}... -> ${payload.size} bytes")
            println("   key1: ${keyParts.getString(0)} -> ${key1.size} bytes")
            println("   key2: ${keyParts.getString(1)} -> ${key2.size} bytes")
            println("   key total: ${key.size} bytes")
            
            // Verificar tamanho da chave
            when (key.size) {
                16 -> println("🔑 AES-128 detectado")
                24 -> println("🔑 AES-192 detectado")
                32 -> println("🔑 AES-256 detectado")
                else -> println("⚠️  Tamanho de chave inválido: ${key.size} bytes")
            }
            
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
                println("   $keyParam = ${decryptedJson.get(keyParam)}")
            }
            
            // Construir URL M3U8
            buildM3u8Url(videoId, decryptedJson)
            
        } catch (e: Exception) {
            println("💥 Erro ao processar resposta: ${e.message}")
            e.printStackTrace()
            null
        }
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
    
    private fun buildM3u8Url(videoId: String, params: JSONObject): String {
        println("🔧 Construindo URL M3U8...")
        
        // Log de todos os campos
        for (key in params.keys()) {
            println("   Campo '$key' = ${params.get(key)}")
        }
        
        // Extrair parâmetros comuns
        val token = when {
            params.has("t") -> params.getString("t")
            params.has("token") -> params.getString("token")
            else -> {
                println("⚠️  Token não encontrado, usando valor padrão")
                "default"
            }
        }
        
        val timestamp = params.optString("s", System.currentTimeMillis().toString())
        val expire = params.optString("e", (System.currentTimeMillis() + 10800000).toString())
        val fileId = params.optString("f", "1")
        val server = params.optString("srv", "1070")
        val speed = params.optString("sp", "4000")
        val quality = params.optString("p", "0")
        
        // Domínio CDN (pode variar)
        val cdnBase = "be6721.rcr72.waw04.i8yz83pn.com"
        
        // Construir URL
        val url = "https://$cdnBase/hls2/05/10459/${videoId}_h/master.m3u8" +
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
    
    private suspend fun generateM3u8Links(
        m3u8Url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🔄 Gerando links M3U8...")
            
            // Testar diferentes referers
            val referers = listOf(
                "$G9R6_DOMAIN/bk2vx/",
                BYSEVEPOIN_DOMAIN,
                FEMBED_DOMAIN,
                "https://superflix21.lol/"
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
