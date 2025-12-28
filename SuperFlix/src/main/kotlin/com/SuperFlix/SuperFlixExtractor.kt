package com.SuperFlix

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
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
    
    // FUNÇÃO NOVA: Extrair URL M3U8 do JSON descriptografado
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
            
            // Opção 2: data.sources
            if (decryptedJson.has("data")) {
                val data = decryptedJson.getJSONObject("data")
                if (data.has("sources")) {
                    val sources = data.getJSONArray("sources")
                    if (sources.length() > 0) {
                        val firstSource = sources.getJSONObject(0)
                        if (firstSource.has("url")) {
                            var url = firstSource.getString("url")
                            url = decodeUnicodeEscapes(url)
                            println("🎯 URL encontrada em data.sources: $url")
                            return url
                        }
                    }
                }
            }
            
            // Opção 3: url direta
            if (decryptedJson.has("url")) {
                var url = decryptedJson.getString("url")
                url = decodeUnicodeEscapes(url)
                println("🎯 URL encontrada diretamente: $url")
                return url
            }
            
            // Opção 4: outros campos comuns
            val possibleFields = listOf("m3u8", "m3u8_url", "playback_url", "stream_url")
            for (field in possibleFields) {
                if (decryptedJson.has(field)) {
                    var url = decryptedJson.getString(field)
                    url = decodeUnicodeEscapes(url)
                    println("🎯 URL encontrada em $field: $url")
                    return url
                }
            }
            
            println("❌ Nenhuma URL encontrada no JSON descriptografado")
            
            // Se não encontrou URL, tenta usar os parâmetros para construir
            println("🛠️  Tentando construir URL a partir de parâmetros...")
            buildM3u8UrlFromParams(decryptedJson)
            
        } catch (e: Exception) {
            println("💥 Erro ao extrair URL do JSON: ${e.message}")
            null
        }
    }
    
    // Função para decodificar caracteres Unicode escapados
    private fun decodeUnicodeEscapes(text: String): String {
        return text
            .replace("\\u0026", "&")
            .replace("\\u002F", "/")
            .replace("\\u003D", "=")
            .replace("\\u002B", "+")
            .replace("\\u003A", ":")
            .replace("\\u003F", "?")
    }
    
    // Função auxiliar para construir URL se necessário
    private fun buildM3u8UrlFromParams(params: JSONObject): String? {
        try {
            // Verificar se temos o ID necessário
            if (!params.has("video_id") && !params.has("id")) {
                println("❌ Sem ID para construir URL")
                return null
            }
            
            val videoId = params.optString("video_id", params.optString("id", ""))
            if (videoId.isEmpty()) {
                println("❌ ID vazio")
                return null
            }
            
            // Tentar extrair domínio dos dados disponíveis
            val cdnBase = params.optString("cdn", "be2719.rcr22.ams01.i8yz83pn.com")
            val token = params.optString("t", "default")
            val timestamp = params.optString("s", System.currentTimeMillis().toString())
            val expire = params.optString("e", (System.currentTimeMillis() + 10800000).toString())
            
            // Construir URL padrão
            val url = "https://$cdnBase/hls2/02/10529/${videoId}_h/master.m3u8" +
                     "?t=$token" +
                     "&s=$timestamp" +
                     "&e=$expire" +
                     "&f=52646943" +
                     "&srv=1060" +
                     "&sp=4000" +
                     "&p=0"
            
            println("🔗 URL construída a partir de parâmetros: $url")
            return url
            
        } catch (e: Exception) {
            println("💥 Erro ao construir URL: ${e.message}")
            return null
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
private suspend fun testM3u8Url(m3u8Url: String): Boolean {
    return try {
        println("🧪 Testando URL M3U8: $m3u8Url")
        
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
            "Accept" to "*/*",
            "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
            "Origin" to "https://g9r6.com",
            "Referer" to "https://g9r6.com/",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site"
        )
        
        val response = app.get(m3u8Url, headers = headers)
        
        println("📊 Status: ${response.code}")
        println("📄 Conteúdo (primeiros 200 chars): ${response.text.take(200)}")
        
        // Verificar se é um M3U8 válido
        val content = response.text
        val isM3u8 = content.contains("#EXTM3U") || 
                     content.contains("#EXT-X-VERSION") || 
                     content.contains("#EXT-X-STREAM-INF") ||
                     content.contains("#EXTINF")
        
        if (isM3u8) {
            println("✅ É um M3U8 válido!")
            // Verificar tipo
            if (content.contains("#EXT-X-STREAM-INF")) {
                println("📋 Tipo: Master Playlist")
            } else if (content.contains("#EXTINF")) {
                println("📋 Tipo: Media Playlist")
            } else {
                println("📋 Tipo: M3U8 básico")
            }
            true
        } else {
            println("❌ Não parece ser um M3U8 válido")
            // Talvez seja um redirecionamento ou JSON
            println("🔍 Analisando conteúdo...")
            if (content.contains("http") || content.contains("m3u8")) {
                println("⚠️  Pode conter URL interna")
                // Tentar extrair URL interna
                val urlPattern = Regex("""https?://[^\s"']+\.m3u8[^\s"']*""")
                val match = urlPattern.find(content)
                if (match != null) {
                    val innerUrl = match.value
                    println("🔗 URL interna encontrada: $innerUrl")
                    return testM3u8Url(innerUrl)
                }
            }
            false
        }
        
    } catch (e: Exception) {
        println("💥 Erro ao testar M3U8: ${e.message}")
        false
    }
}

private suspend fun generateM3u8Links(
    m3u8Url: String,
    name: String,
    callback: (ExtractorLink) -> Unit
): Boolean {
    return try {
        println("🔄 Gerando links M3U8...")
        
        // Primeiro, testar se a URL é válida
        if (!testM3u8Url(m3u8Url)) {
            println("❌ URL M3U8 inválida")
            return false
        }
        
        // Agora tentar diferentes abordagens
        
        // ABORDAGEM 1: Usar M3u8Helper com headers mais completos
        println("🔧 Tentando M3u8Helper com headers aprimorados...")
        
        val enhancedHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
            "Accept" to "*/*",
            "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
            "Accept-Encoding" to "gzip, deflate, br",
            "Origin" to "https://g9r6.com",
            "Referer" to "https://g9r6.com/",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "Connection" to "keep-alive",
            "Cache-Control" to "no-cache",
            "Pragma" to "no-cache"
        )
        
        try {
            val links = M3u8Helper.generateM3u8(
                name,
                m3u8Url,
                referer = "https://g9r6.com/",
                headers = enhancedHeaders,
                quality = null
            )
            
            if (links.isNotEmpty()) {
                links.forEach(callback)
                println("🎉 SUCESSO com M3u8Helper! ${links.size} links gerados!")
                return true
            }
        } catch (e: Exception) {
            println("⚠️  M3u8Helper falhou: ${e.message}")
        }
        
        // ABORDAGEM 2: Criar ExtractorLink manualmente
        println("🔧 Tentando criar ExtractorLink manual...")
        
        try {
            // Primeiro verificar se podemos acessar diretamente
            val testResponse = app.get(m3u8Url, headers = enhancedHeaders)
            if (testResponse.code == 200) {
                val extractorLink = ExtractorLink(
                    source = "SuperFlix",
                    name = name,
                    url = m3u8Url,
                    referer = "https://g9r6.com/",
                    quality = 720,
                    isM3u8 = true,
                    headers = enhancedHeaders
                )
                
                callback(extractorLink)
                println("🎉 SUCESSO com ExtractorLink manual!")
                return true
            }
        } catch (e: Exception) {
            println("⚠️  ExtractorLink manual falhou: ${e.message}")
        }
        
        // ABORDAGEM 3: Tentar extrair URL de segmentos manualmente
        println("🔧 Tentando extrair segmentos manualmente...")
        
        try {
            val response = app.get(m3u8Url, headers = enhancedHeaders)
            val content = response.text
            
            // Se for uma Master Playlist, extrair a melhor qualidade
            if (content.contains("#EXT-X-STREAM-INF")) {
                val lines = content.lines()
                var bestQualityUrl = ""
                var bestBandwidth = 0
                
                for (i in lines.indices) {
                    if (lines[i].contains("#EXT-X-STREAM-INF")) {
                        val bandwidthMatch = Regex("""BANDWIDTH=(\d+)""").find(lines[i])
                        val bandwidth = bandwidthMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        
                        if (i + 1 < lines.size && !lines[i + 1].startsWith("#")) {
                            val segmentUrl = lines[i + 1].trim()
                            if (bandwidth > bestBandwidth) {
                                bestBandwidth = bandwidth
                                bestQualityUrl = segmentUrl
                            }
                        }
                    }
                }
                
                if (bestQualityUrl.isNotEmpty()) {
                    // Tornar URL absoluta se for relativa
                    val absoluteUrl = if (bestQualityUrl.startsWith("http")) {
                        bestQualityUrl
                    } else {
                        val baseUrl = m3u8Url.substringBeforeLast("/") + "/"
                        baseUrl + bestQualityUrl
                    }
                    
                    println("🎯 Melhor qualidade encontrada: $absoluteUrl ($bestBandwidth bps)")
                    
                    val extractorLink = newExtractorLink(
                        source = "SuperFlix",
                        name = "$name (720p)",
                        url = absoluteUrl,
                        referer = "https://g9r6.com/",
                        quality = 720,
                        isM3u8 = true,
                        headers = enhancedHeaders
                    )
                    
                    callback(extractorLink)
                    println("🎉 SUCESSO com segmentos manuais!")
                    return true
                }
            }
        } catch (e: Exception) {
            println("⚠️  Extração manual falhou: ${e.message}")
        }
        
        println("❌ Nenhum link gerado")
        false
        
    } catch (e: Exception) {
        println("💥 Erro ao gerar links: ${e.message}")
        false
    }
  }
}
