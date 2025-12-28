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

            // PASSO 3: Criar ExtractorLink
            return createExtractorLink(m3u8Url, name, callback)

        } catch (e: Exception) {
            println("💥 Erro fatal: ${e.message}")
            false
        }
    }

    private fun extractVideoIdAndType(url: String): Triple<String, Boolean, String> {
        println("🔍 Extraindo ID de: $url")
        
        // 1. Extrai o ID (números ou letras) depois de /e/ ou /v/
        val idPattern = Regex("""/(?:e|v)/([a-zA-Z0-9]+)""")
        val idMatch = idPattern.find(url)
        
        if (idMatch == null) {
            println("❌ Não encontrei ID na URL")
            return Triple("", false, "")
        }
        
        val videoId = idMatch.groupValues[1]
        println("✅ ID extraído: $videoId")
        
        // 2. Verifica se tem parâmetro c= (série)
        val cParamPattern = Regex("""[?&]c=(\d+-\d+)""")
        val cParamMatch = cParamPattern.find(url)
        
        if (cParamMatch != null) {
            val cParam = cParamMatch.groupValues[1]
            println("📺 É UMA SÉRIE: c=$cParam")
            return Triple(videoId, true, cParam)
        }
        
        // 3. É filme
        println("🎬 É UM FILME")
        return Triple(videoId, false, "")
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
            return processEncryptedResponse(response.text, videoId)

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

            // ⭐ PRIMEIRO: Tenta com DUB
            var iframeUrl = getFembedIframeWithLang(shortVideoId, isSeries, cParam, "DUB")
            if (iframeUrl == null) {
                println("❌ Falha ao obter iframe do Fembed com DUB")
                return null
            }

            println("✅ Iframe obtido (DUB): $iframeUrl")

            // PASSO 2: Acessar iframe para obter URL do Bysevepoin
            var bysevepoinUrl = getBysevepoinFromIframe(iframeUrl, shortVideoId, isSeries)
            
            // ⭐ SE NÃO ENCONTRAR BYSEVEPOIN COM DUB, TENTA LEG
            if (bysevepoinUrl == null) {
                println("⚠️  Não encontrou Bysevepoin com DUB, tentando LEG...")
                
                // Tenta com LEG
                iframeUrl = getFembedIframeWithLang(shortVideoId, isSeries, cParam, "LEG")
                if (iframeUrl == null) {
                    println("❌ Falha ao obter iframe com LEG também")
                    return null
                }
                
                println("✅ Iframe obtido (LEG): $iframeUrl")
                bysevepoinUrl = getBysevepoinFromIframe(iframeUrl, shortVideoId, isSeries)
            }

            if (bysevepoinUrl == null) {
                println("❌ Falha ao obter URL do Bysevepoin (nem DUB nem LEG funcionaram)")
                return null
            }

            println("✅ URL do Bysevepoin: $bysevepoinUrl")

            // Resto do código
            val realVideoId = extractRealVideoId(bysevepoinUrl)
            if (realVideoId == null) {
                println("❌ Não consegui extrair ID real")
                return null
            }

            println("🎯 ID real encontrado: $realVideoId")
            return tryDirectG9r6Api(realVideoId, bysevepoinUrl)

        } catch (e: Exception) {
            println("💥 Erro no fluxo completo: ${e.message}")
            null
        }
    }

    private suspend fun getFembedIframeWithLang(
        videoId: String, 
        isSeries: Boolean = false, 
        cParam: String = "",
        lang: String
    ): String? {
        return try {
            val effectiveCParam = if (isSeries && cParam.isNotEmpty()) cParam else ""
            val apiUrl = "$FEMBED_DOMAIN/api.php?s=$videoId&c=$effectiveCParam"
            
            println("📡 [POST1-$lang] ${if (isSeries) "SÉRIE" else "FILME"}: $apiUrl")
            
            val headers = mapOf(
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to "$FEMBED_DOMAIN/e/$videoId${if (isSeries && cParam.isNotEmpty()) "?c=$cParam" else ""}",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                "Cookie" to API_COOKIE
            )
            
            val postData = mapOf(
                "action" to "getPlayer",
                "lang" to lang,
                "key" to "MA=="
            )
            
            val response = app.post(apiUrl, headers = headers, data = postData)
            val html = response.text
            
            // ⭐ DEBUG reduzido
            println("🔍 [POST1-$lang] Resposta (primeiros 150 chars): ${html.take(150)}")
            
            val iframePattern = Regex("""<iframe[^>]+src=["']([^"']+)["']""")
            val match = iframePattern.find(html)
            
            if (match != null) {
                var url = match.groupValues[1]
                
                // Rejeita iframes vazios
                if (url.isBlank() || url == "\"\"" || url.contains("src=\"\"")) {
                    println("❌ [POST1-$lang] Iframe VAZIO!")
                    return null
                }
                
                println("✅ [POST1-$lang] SRC válido: $url")
                
                // Converte URL relativa
                if (url.startsWith("/")) {
                    url = "$FEMBED_DOMAIN$url"
                }
                
                // Para séries: garante c=1-1
                if (isSeries && !url.contains("c=") && cParam.isNotEmpty()) {
                    url = url.replace("&key=", "&c=$cParam&key=")
                }
                
                return url
            }
            
            println("❌ [POST1-$lang] Nenhum iframe encontrado")
            null
            
        } catch (e: Exception) {
            println("💥 [POST1-$lang] Erro: ${e.message}")
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
                "Cookie" to API_COOKIE
            )
            
            val response = app.get(iframeUrl, headers = headers)
            val html = response.text
            
            // ⭐ VERIFICAÇÃO RÁPIDA: Tem Bysevepoin?
            val hasBysevepoin = html.contains("bysevepoin") || 
                               html.contains("bysevepoin.com") ||
                               Regex("""https?://bysevepoin\.com""").containsMatchIn(html)
            
            if (!hasBysevepoin) {
                println("❌ [POST2] Resposta NÃO contém Bysevepoin")
                return null
            }
            
            // Procura o URL exato
            val bysevepoinPattern = Regex("""<iframe[^>]+src=["'](https?://bysevepoin\.com/[^"']+)["']""")
            val match = bysevepoinPattern.find(html)
            
            if (match != null) {
                val url = match.groupValues[1]
                println("✅ [POST2] Bysevepoin encontrado: $url")
                return url
            }
            
            println("❌ [POST2] Tem 'bysevepoin' no HTML mas não encontrou iframe")
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

            // Função local para decodificar Base64
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
