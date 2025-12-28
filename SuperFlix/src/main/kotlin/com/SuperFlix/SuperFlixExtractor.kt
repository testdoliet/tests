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
import com.lagradost.cloudstream3.utils.M3u8Helper

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
            // PASSO 1: Extrair ID e detectar se é série
            val (videoId, isSeries, cParam) = extractVideoIdAndType(url)
            if (videoId.isEmpty()) {
                println("❌ Não consegui extrair ID")
                return false
            }

            println("✅ ID extraído: $videoId")
            if (isSeries) {
                println("📺 É UMA SÉRIE: c=$cParam")
            }

            // PASSO 2: Verificar tipo de ID
            val m3u8Url = if (videoId.length > 10 && videoId.matches(Regex("[a-z0-9]+"))) {
                // ID longo (ex: ouu59ray1kvp) - API direta
                tryDirectG9r6Api(videoId, url)
            } else {
                // ID curto (ex: 1497017) - Fluxo completo
                tryFullFlow(videoId, isSeries, cParam, url)
            }

            if (m3u8Url == null) {
                println("❌ Não consegui obter URL M3U8")
                return false
            }

            println("🎬 URL M3U8 final: $m3u8Url")

            // PASSO 3: Criar ExtractorLink CORRETAMENTE
            return createExtractorLink(m3u8Url, name, callback)

        } catch (e: Exception) {
            println("💥 Erro fatal: ${e.message}")
            false
        }
    }

    private fun extractVideoIdAndType(url: String): Triple<String, Boolean, String> {
        // Retorna: (ID, isSeries, cParam)
        
        println("🔍 Analisando URL: $url")
        
        // Padrão para séries: /e/ID?c=X-Y (ex: /e/85718?c=1-1)
        val seriesPattern = Regex("""/e/([a-zA-Z0-9]+)\?c=(\d+-\d+)""")
        val seriesMatch = seriesPattern.find(url)
        
        if (seriesMatch != null) {
            val id = seriesMatch.groupValues[1]
            val cParam = seriesMatch.groupValues[2]  // "1-1"
            println("✅ SÉRIE detectada: ID=$id, c=$cParam")
            return Triple(id, true, cParam)
        }
        
        // Padrão para séries sem parâmetro explícito (às vezes vem no final)
        val seriesPattern2 = Regex("""/(\d+)-(\d+)""")
        val seriesMatch2 = seriesPattern2.find(url)
        
        if (seriesMatch2 != null) {
            // Pode ser uma série com formato diferente
            val id = seriesMatch2.groupValues[1]
            println("⚠️  Possível série detectada (formato diferente): ID=$id")
            return Triple(id, true, "1-1") // Assume temporada 1 episódio 1
        }
        
        // Padrão para filmes: /e/ID ou /v/ID
        val filmPattern = Regex("""/(?:e|v)/([a-zA-Z0-9]+)""")
        val filmMatch = filmPattern.find(url)
        
        if (filmMatch != null) {
            val id = filmMatch.groupValues[1]
            println("✅ FILME detectado: ID=$id")
            return Triple(id, false, "")
        }
        
        // Último recurso: pegar qualquer número no final
        val fallbackPattern = Regex("""/(\d+)$""")
        val fallbackMatch = fallbackPattern.find(url)
        
        if (fallbackMatch != null) {
            val id = fallbackMatch.groupValues[1]
            println("⚠️  Fallback: ID=$id (assumindo filme)")
            return Triple(id, false, "")
        }
        
        println("❌ Padrão não reconhecido")
        return Triple("", false, "")
    }

    private suspend fun tryDirectG9r6Api(videoId: String, originalUrl: String): String? {
        return try {
            println("🚀 Tentando API direta do g9r6.com...")

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
            processEncryptedResponse(response.text, videoId)

        } catch (e: Exception) {
            println("⚠️  Falha na API direta: ${e.message}")
            null
        }
    }

    private suspend fun tryFullFlow(
        shortVideoId: String, 
        isSeries: Boolean, 
        cParam: String, 
        originalUrl: String
    ): String? {
        return try {
            println("🔄 Iniciando fluxo completo...")

            // PASSO 1: Obter iframe do Fembed (com parâmetro correto para séries)
            val iframeUrl = getFembedIframe(shortVideoId, isSeries, cParam)
            if (iframeUrl == null) {
                println("❌ Falha ao obter iframe do Fembed")
                return null
            }

            println("✅ Iframe obtido: $iframeUrl")

            // PASSO 2: Acessar iframe para obter URL do Bysevepoin
            val bysevepoinUrl = getBysevepoinFromIframe(iframeUrl, shortVideoId, isSeries)
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

    private suspend fun getFembedIframe(
    videoId: String, 
    isSeries: Boolean = false, 
    cParam: String = ""
): String? {
    return try {
        val effectiveCParam = if (isSeries && cParam.isNotEmpty()) cParam else ""
        val apiUrl = "$FEMBED_DOMAIN/api.php?s=$videoId&c=$effectiveCParam"
        
        println("📡 [POST1] ${if (isSeries) "SÉRIE" else "FILME"}: $apiUrl")
        
        val headers = mapOf(
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to "$FEMBED_DOMAIN/e/$videoId${if (isSeries && cParam.isNotEmpty()) "?c=$cParam" else ""}",
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
            "Cookie" to API_COOKIE
        )
        
        // ⭐ DUB PRIMEIRO, depois LEG
        val languages = listOf("DUB", "LEG")
        
        for (lang in languages) {
            println("🔄 Tentando idioma: $lang")
            
            val postData = mapOf(
                "action" to "getPlayer",
                "lang" to lang,
                "key" to "MA=="
            )
            
            try {
                val response = app.post(apiUrl, headers = headers, data = postData)
                val html = response.text
                
                // Pega o SRC do iframe
                val iframePattern = Regex("""<iframe[^>]+src=["']([^"']+)["']""")
                val match = iframePattern.find(html)
                
                if (match != null) {
                    var url = match.groupValues[1]
                    
                    // ⭐ VERIFICA: Não aceita iframes vazios
                    if (url.isBlank() || url.isEmpty() || url == "\"\"" || url.contains("src=\"\"")) {
                        println("❌ Idioma $lang retornou iframe VAZIO!")
                        continue // Tenta próximo idioma
                    }
                    
                    println("✅ Idioma $lang FUNCIONOU! SRC: $url")
                    
                    // Converte URL relativa
                    if (url.startsWith("/")) {
                        url = "$FEMBED_DOMAIN$url"
                    }
                    
                    // Para séries: garante que mantém c=1-1
                    if (isSeries && !url.contains("c=") && cParam.isNotEmpty()) {
                        url = url.replace("&key=", "&c=$cParam&key=")
                    }
                    
                    println("🎯 URL final: $url")
                    return url
                } else {
                    println("❌ Idioma $lang não retornou iframe")
                }
                
            } catch (e: Exception) {
                println("⚠️  Idioma $lang falhou: ${e.message}")
                // Continua para próximo idioma
            }
        }
        
        println("❌ Nenhum idioma funcionou (nem DUB nem LEG)")
        null
        
    } catch (e: Exception) {
        println("💥 Erro no POST1: ${e.message}")
        null
    }
    }
    

    private suspend fun getBysevepoinFromIframe(
        iframeUrl: String, 
        videoId: String,
        isSeries: Boolean = false
    ): String? {
        return try {
            println("🔍 [POST2] Acessando: $iframeUrl")
            
            val headers = mapOf(
                "Referer" to "$FEMBED_DOMAIN/e/$videoId",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                "Cookie" to API_COOKIE,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
            
            val response = app.get(iframeUrl, headers = headers)
            
            println("📊 [POST2] Status: ${response.code}")
            println("📊 [POST2] Tamanho: ${response.text.length} chars")
            
            // DEBUG reduzido para não poluir logs
            val html = response.text
            if (html.length < 500) {
                println("🔍 [POST2] Resposta completa: $html")
            } else {
                println("🔍 [POST2] Primeiros 500 chars: ${html.take(500)}")
            }
            
            // ⭐ Pega APENAS URL do Bysevepoin
            val bysevepoinPattern = Regex("""<iframe[^>]+src=["'](https?://bysevepoin\.com/[^"']+)["']""")
            val match = bysevepoinPattern.find(html)
            
            if (match != null) {
                val url = match.groupValues[1]
                println("✅ [POST2] Bysevepoin encontrado: $url")
                return url
            }
            
            // Fallback: qualquer iframe
            val fallbackPattern = Regex("""<iframe[^>]+src=["'](https?://[^"']+)["']""")
            val fallbackMatch = fallbackPattern.find(html)
            
            if (fallbackMatch != null) {
                val url = fallbackMatch.groupValues[1]
                println("⚠️  [POST2] Iframe encontrado (não é Bysevepoin): $url")
                return url
            }
            
            println("❌ [POST2] Nenhum iframe encontrado")
            null
            
        } catch (e: Exception) {
            println("💥 [POST2] Erro: ${e.message}")
            null
        }
    }

    private fun extractRealVideoId(bysevepoinUrl: String): String? {
        // Formatos:
        // /e/zlbeq8pmmefu/1512623-dub
        // /e/ftltsho61fgs/1583500-leg
        // /e/yziqjcntix6v/1497017-dub
        
        println("🔍 Extraindo ID real de: $bysevepoinUrl")
        
        val pattern = Regex("""/e/([a-zA-Z0-9]+)(?:/|$)""")
        val match = pattern.find(bysevepoinUrl)
        
        if (match != null) {
            val id = match.groupValues[1]
            println("✅ ID real extraído: $id")
            return id
        }
        
        println("❌ Não consegui extrair ID real")
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
                if (cleanStr.contains('-') || cleanStr.contains('_')) {
                    return Base64.decode(cleanStr, Base64.URL_SAFE or Base64.NO_PADDING)
                }
                try {
                    return Base64.decode(cleanStr, Base64.DEFAULT)
                } catch (e: IllegalArgumentException) {
                    return Base64.decode(cleanStr, Base64.NO_PADDING)
                }
            }

            // Decodificar dados
            val ivBase64 = playback.getString("iv")
            val payloadBase64 = playback.getString("payload")
            val keyParts = playback.getJSONArray("key_parts")

            val iv = decodeBase64(ivBase64)
            val payload = decodeBase64(payloadBase64)
            val key1 = decodeBase64(keyParts.getString(0))
            val key2 = decodeBase64(keyParts.getString(1))
            val key = key1 + key2

            println("✅ Base64 decodificado com sucesso!")

            // Descriptografar
            val decrypted = decryptAesGcm(payload, key, iv)
            if (decrypted == null) {
                println("❌ Falha na descriptografia AES")
                return null
            }

            val decryptedText = String(decrypted, Charsets.UTF_8)
            println("✅ Descriptografado com sucesso!")

            // Parse JSON descriptografado
            val decryptedJson = JSONObject(decryptedText)
            return extractM3u8FromDecryptedJson(decryptedJson)

        } catch (e: Exception) {
            println("💥 Erro ao processar resposta: ${e.message}")
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
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val actualIv = if (iv.size != 12) iv.copyOf(12) else iv
            val spec = GCMParameterSpec(128, actualIv)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), spec)
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            println("💥 Erro na descriptografia: ${e.message}")
            null
        }
    }

    private suspend fun createExtractorLink(
        m3u8Url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🎯 Criando ExtractorLink...")

        try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                "Accept" to "*/*",
                "Accept-Language" to "pt-BR",
                "Referer" to m3u8Url,
                "Range" to "bytes=0-"
            )

            println("🔗 URL: $m3u8Url")

            // Testa a URL
            val testResponse = app.get(m3u8Url, headers = headers)
            println("📊 Status: ${testResponse.code}")
            
            if (testResponse.text.contains("#EXTM3U")) {
                println("✅ É um M3U8 válido!")
            }

            // Usa M3u8Helper
            val links = M3u8Helper.generateM3u8(
                source = "SuperFlix",
                streamUrl = m3u8Url,
                referer = m3u8Url,
                headers = headers
            )

            if (links.isNotEmpty()) {
                println("✅ M3u8Helper gerou ${links.size} links!")
                links.forEach { callback(it) }
                return true
            }

            // Fallback
            println("⚠️  M3u8Helper não gerou links, usando fallback...")
            val fallbackLink = newExtractorLink(
                source = "SuperFlix",
                name = "$name (720p)",
                url = m3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = m3u8Url
                this.quality = 720
                this.headers = headers
            }
            
            callback(fallbackLink)
            return true

        } catch (e: Exception) {
            println("💥 ERRO: ${e.message}")
            return false
        }
    }
}
