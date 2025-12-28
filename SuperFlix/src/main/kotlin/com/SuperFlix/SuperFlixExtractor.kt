package com.SuperFlix

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

object SuperFlixExtractor {
    // Cookies atualizados
    private const val API_COOKIE = "__dtsu=104017651574995957BEB724C6373F9E; __cc_id=a44d1e52993b9c2Oaaf40eba24989a06; cf_clearance=rfIEldahI7B..Y4PpZhGgwi.QOJBqIRGdFP150.VnW-1766868784-1.1-"
    
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
            // PASSO 1: Identificar tipo de URL e extrair ID
            val (videoId, urlType) = extractVideoIdAndType(url)
            if (videoId == null) {
                println("❌ Não consegui extrair ID")
                return false
            }
            
            println("✅ ID extraído: $videoId (Tipo: $urlType)")
            
            // PASSO 2: Baseado no tipo, usar fluxo apropriado
            val m3u8Url = when (urlType) {
                UrlType.SHORT_ID -> tryFullFlow(videoId)      // IDs curtos (10529, 1456349)
                UrlType.LONG_ID -> tryDirectG9r6Api(videoId, url) // IDs longos (j7u05mwoi6xc, ouu59ray1kvp)
                UrlType.BYSEVEPOIN -> extractFromBysevepoinUrl(url) // URLs diretas do Bysevepoin
            }
            
            if (m3u8Url == null) {
                println("❌ Não consegui obter URL M3U8")
                return false
            }
            
            println("🎬 URL M3U8 final: $m3u8Url")
            
            // PASSO 3: Criar ExtractorLink
            createExtractorLink(m3u8Url, name, callback)
            
        } catch (e: Exception) {
            println("💥 Erro fatal: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    // ================ SISTEMA 1: IDs CURTOS (fluxo completo) ================
    private suspend fun tryFullFlow(shortVideoId: String): String? {
        println("🔄 Iniciando fluxo completo para ID curto: $shortVideoId")
        
        return try {
            // TENTATIVA 1: Sistema 1 (JSON direto com M3U8)
            val system1Result = trySystem1(shortVideoId)
            if (system1Result != null) {
                println("✅ Sistema 1 funcionou!")
                return system1Result
            }
            
            println("⚠️  Sistema 1 falhou, tentando Sistema 2...")
            
            // TENTATIVA 2: Sistema 2 (Iframe -> Bysevepoin)
            val system2Result = trySystem2(shortVideoId)
            if (system2Result != null) {
                println("✅ Sistema 2 funcionou!")
                return system2Result
            }
            
            println("❌ Ambos os sistemas falharam")
            null
            
        } catch (e: Exception) {
            println("💥 Erro no fluxo completo: ${e.message}")
            null
        }
    }
    
    private suspend fun trySystem1(shortVideoId: String): String? {
        println("🔧 Tentando Sistema 1 (JSON direto)...")
        
        val endpoint = "https://fembed.sx/api.php?s=$shortVideoId&c="
        println("📡 POST para: $endpoint")
        
        try {
            val response = app.post(
                endpoint,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                    "Accept" to "*/*",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to "https://fembed.sx/",
                    "Origin" to "https://fembed.sx"
                )
            )
            
            if (response.isSuccessful) {
                val text = response.text
                println("📊 Resposta (primeiros 500 chars): ${text.take(500)}...")
                
                // Verificar se é JSON
                if (text.trim().startsWith("{") && text.contains("\"file\"")) {
                    try {
                        val json = JSONObject(text)
                        if (json.has("file")) {
                            val m3u8Url = json.getString("file")
                            if (m3u8Url.contains(".m3u8")) {
                                println("✅ Sistema 1 - M3U8 direto encontrado: $m3u8Url")
                                return m3u8Url
                            }
                        }
                    } catch (e: Exception) {
                        println("⚠️  Não é JSON válido: ${e.message}")
                    }
                }
            }
            
            return null
            
        } catch (e: Exception) {
            println("⚠️  Erro no Sistema 1: ${e.message}")
            return null
        }
    }
    
    private suspend fun trySystem2(shortVideoId: String): String? {
        println("🔧 Tentando Sistema 2 (Bysevepoin)...")
        
        return try {
            // 1. Obter resposta do Fembed
            val endpoint = "https://fembed.sx/api.php?s=$shortVideoId&c="
            println("📡 POST para Fembed: $endpoint")
            
            val response = app.post(
                endpoint,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                    "Accept" to "*/*",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to "https://fembed.sx/",
                    "Origin" to "https://fembed.sx"
                )
            )
            
            if (!response.isSuccessful) {
                println("❌ Fembed API falhou: ${response.code}")
                return null
            }
            
            val content = response.text
            println("📄 Resposta do Fembed (${content.length} chars)")
            
            // 2. Extrair URL do iframe
            val iframeUrl = extractIframeUrl(content)
            if (iframeUrl == null) {
                println("❌ Não encontrei URL do iframe")
                return null
            }
            
            println("✅ URL do iframe: $iframeUrl")
            
            // 3. Acessar iframe para obter URL do Bysevepoin
            val bysevepoinUrl = extractBysevepoinUrl(iframeUrl)
            if (bysevepoinUrl == null) {
                println("❌ Não consegui extrair URL do Bysevepoin")
                return null
            }
            
            println("✅ URL do Bysevepoin: $bysevepoinUrl")
            
            // 4. Extrair ID real do Bysevepoin e usar API direta
            val realVideoId = extractRealVideoId(bysevepoinUrl)
            if (realVideoId == null) {
                println("❌ Não consegui extrair ID real do Bysevepoin")
                return null
            }
            
            println("🎯 ID real extraído: $realVideoId")
            
            // 5. Usar API direta com ID real
            tryDirectG9r6Api(realVideoId, bysevepoinUrl)
            
        } catch (e: Exception) {
            println("💥 Erro no Sistema 2: ${e.message}")
            null
        }
    }
    
    private fun extractIframeUrl(html: String): String? {
        // Padrões para encontrar URL do iframe
        val patterns = listOf(
            Regex("""src=["'](https?://[^"']+)["']"""),
            Regex("""<iframe[^>]+src=["']([^"']+)["']"""),
            Regex("""iframe=["']([^"']+)["']""")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(html)
            if (match != null) {
                var url = match.groupValues[1]
                // Garantir que seja URL completa
                if (url.startsWith("//")) {
                    url = "https:$url"
                } else if (url.startsWith("/")) {
                    url = "https://fembed.sx$url"
                }
                
                if (url.startsWith("http")) {
                    println("🔍 URL encontrada com padrão: $url")
                    return url
                }
            }
        }
        
        return null
    }
    
    private suspend fun extractBysevepoinUrl(iframeUrl: String): String? {
        println("🔍 Acessando iframe: $iframeUrl")
        
        try {
            val response = app.get(iframeUrl, headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                "Referer" to "https://fembed.sx/",
                "Origin" to "https://fembed.sx"
            ))
            
            if (!response.isSuccessful) {
                println("❌ Iframe não acessível: ${response.code}")
                return null
            }
            
            val html = response.text
            
            // Procurar URL do Bysevepoin
            val bysevepoinPatterns = listOf(
                Regex("""src=["'](https?://bysevepoin\.com/[^"']+)["']"""),
                Regex("""<iframe[^>]+src=["'](https?://bysevepoin\.com/[^"']+)["']"""),
                Regex("""bysevepoin\.com/e/([^"'\s]+)""")
            )
            
            for (pattern in bysevepoinPatterns) {
                val match = pattern.find(html)
                if (match != null) {
                    var url = match.groupValues[1]
                    // Construir URL completa se necessário
                    if (!url.startsWith("http") && url.startsWith("/e/")) {
                        url = "https://bysevepoin.com$url"
                    } else if (!url.startsWith("http")) {
                        url = "https://bysevepoin.com/e/$url"
                    }
                    
                    if (url.startsWith("http")) {
                        println("✅ URL do Bysevepoin encontrada: $url")
                        return url
                    }
                }
            }
            
            println("❌ Não encontrei URL do Bysevepoin no iframe")
            return null
            
        } catch (e: Exception) {
            println("⚠️  Erro ao acessar iframe: ${e.message}")
            return null
        }
    }
    
    // ================ SISTEMA 2: IDs LONGOS (API direta) ================
    private suspend fun tryDirectG9r6Api(videoId: String, refererUrl: String): String? {
        println("🚀 Usando API direta do g9r6.com: $videoId")
        
        return try {
            val apiUrl = "$G9R6_DOMAIN/api/videos/$videoId/embed/playback"
            println("📡 API URL: $apiUrl")
            
            val response = app.get(apiUrl, headers = createApiHeaders(refererUrl))
            
            if (response.code != 200) {
                println("❌ API retornou ${response.code}")
                return null
            }
            
            println("✅ API respondeu com sucesso")
            
            // Processar resposta criptografada
            processEncryptedResponse(response.text, videoId)
            
        } catch (e: Exception) {
            println("⚠️  Falha na API direta: ${e.message}")
            null
        }
    }
    
    // ================ SISTEMA 3: URLs DIRETAS DO BYSEVEPOIN ================
    private suspend fun extractFromBysevepoinUrl(url: String): String? {
        println("🔗 Processando URL direta do Bysevepoin: $url")
        
        val realVideoId = extractRealVideoId(url)
        if (realVideoId == null) {
            println("❌ Não consegui extrair ID da URL do Bysevepoin")
            return null
        }
        
        println("🎯 ID extraído do Bysevepoin: $realVideoId")
        return tryDirectG9r6Api(realVideoId, url)
    }
    
    // ================ FUNÇÕES AUXILIARES ================
    private fun createApiHeaders(refererUrl: String): Map<String, String> {
        return mapOf(
            "accept" to "*/*",
            "accept-language" to "pt-BR,pt;q=0.9",
            "cache-control" to "no-cache",
            "pragma" to "no-cache",
            "referer" to refererUrl,
            "sec-ch-ua" to "\"Chromium\";v=\"127\", \"Not)A;Brand\";v=\"99\"",
            "sec-ch-ua-mobile" to "?1",
            "sec-ch-ua-platform" to "\"Android\"",
            "user-agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
            "x-embed-parent" to refererUrl,
            "cookie" to API_COOKIE
        )
    }
    
    private fun extractRealVideoId(url: String): String? {
        // Extrair ID de URLs do Bysevepoin
        val patterns = listOf(
            Regex("""/e/([a-zA-Z0-9]+)(?:/|$)"""),  // /e/yziqjcntix6v/
            Regex("""/v/([a-zA-Z0-9]+)(?:/|$)"""),  // /v/abc123
            Regex("""bysevepoin\.com/e/([a-zA-Z0-9]+)""")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        
        return null
    }
    
    private fun extractVideoIdAndType(url: String): Pair<String?, UrlType> {
        // IDs curtos (10529, 1456349) - apenas números
        val shortIdMatch = Regex("""/(\d{4,})(?:/|$|-|\.)""").find(url)
        if (shortIdMatch != null) {
            return Pair(shortIdMatch.groupValues[1], UrlType.SHORT_ID)
        }
        
        // IDs longos alfanuméricos (yziqjcntix6v, j7u05mwoi6xc)
        val longIdMatch = Regex("""/([a-z0-9]{10,})(?:/|$|-|\.)""").find(url)
        if (longIdMatch != null) {
            return Pair(longIdMatch.groupValues[1], UrlType.LONG_ID)
        }
        
        // URLs do Bysevepoin
        if (url.contains("bysevepoin.com")) {
            val id = extractRealVideoId(url)
            return Pair(id, UrlType.BYSEVEPOIN)
        }
        
        return Pair(null, UrlType.UNKNOWN)
    }
    
    // ================ DECRIPTOGRAFIA ================
    private fun processEncryptedResponse(jsonText: String, videoId: String): String? {
        return try {
            println("🔧 Processando resposta criptografada...")
            
            val json = JSONObject(jsonText)
            val playback = json.getJSONObject("playback")
            
            val ivBase64 = playback.getString("iv")
            val payloadBase64 = playback.getString("payload")
            val keyParts = playback.getJSONArray("key_parts")
            
            // Decodificar Base64
            val iv = decodeBase64(ivBase64)
            val payload = decodeBase64(payloadBase64)
            val key1 = decodeBase64(keyParts.getString(0))
            val key2 = decodeBase64(keyParts.getString(1))
            val key = key1 + key2
            
            // Descriptografar
            val decrypted = decryptAesGcm(payload, key, iv)
            if (decrypted == null) {
                println("❌ Falha na descriptografia")
                return null
            }
            
            val decryptedText = String(decrypted, Charsets.UTF_8)
            println("✅ Descriptografado: ${decryptedText.take(200)}...")
            
            // Extrair URL do JSON descriptografado
            extractM3u8FromDecryptedJson(JSONObject(decryptedText))
            
        } catch (e: Exception) {
            println("💥 Erro ao processar resposta: ${e.message}")
            null
        }
    }
    
    private fun decodeBase64(base64Str: String): ByteArray {
        val cleanStr = base64Str.trim()
        return try {
            Base64.decode(cleanStr, Base64.NO_PADDING)
        } catch (e: Exception) {
            Base64.decode(cleanStr, Base64.DEFAULT)
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
            println("💥 Erro na descriptografia: ${e.message}")
            null
        }
    }
    
    private fun extractM3u8FromDecryptedJson(json: JSONObject): String? {
        return try {
            // Procurar em sources
            if (json.has("sources")) {
                val sources = json.getJSONArray("sources")
                for (i in 0 until sources.length()) {
                    val source = sources.getJSONObject(i)
                    if (source.has("url")) {
                        var url = source.getString("url")
                        url = decodeUnicodeEscapes(url)
                        if (url.contains(".m3u8")) {
                            println("🎯 M3U8 encontrado em sources: $url")
                            return url
                        }
                    }
                }
            }
            
            // Procurar em playlists
            if (json.has("playlists")) {
                val playlists = json.getJSONArray("playlists")
                for (i in 0 until playlists.length()) {
                    val playlist = playlists.getJSONObject(i)
                    if (playlist.has("url")) {
                        var url = playlist.getString("url")
                        url = decodeUnicodeEscapes(url)
                        if (url.contains(".m3u8")) {
                            println("🎯 M3U8 encontrado em playlists: $url")
                            return url
                        }
                    }
                }
            }
            
            println("❌ Nenhum M3U8 encontrado no JSON")
            null
            
        } catch (e: Exception) {
            println("💥 Erro ao extrair M3U8: ${e.message}")
            null
        }
    }
    
    private fun decodeUnicodeEscapes(text: String): String {
        return text.replace("\\u0026", "&")
                  .replace("\\u002F", "/")
                  .replace("\\u003D", "=")
    }
    
    // ================ CRIAÇÃO DO LINK ================
    private suspend fun createExtractorLink(
        m3u8Url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                "Accept" to "*/*",
                "Accept-Language" to "pt-BR",
                "Referer" to m3u8Url,
                "Range" to "bytes=0-"
            )
            
            println("🔗 Testando URL M3U8: $m3u8Url")
            val testResponse = app.get(m3u8Url, headers = headers)
            
            if (!testResponse.isSuccessful || !testResponse.text.contains("#EXTM3U")) {
                println("❌ M3U8 inválido ou inacessível")
                return false
            }
            
            println("✅ M3U8 válido! Gerando links...")
            
            val links = M3u8Helper.generateM3u8(
                source = "SuperFlix",
                streamUrl = m3u8Url,
                referer = m3u8Url,
                headers = headers
            )
            
            if (links.isNotEmpty()) {
                links.forEach { link ->
                    println("📺 ${link.name} - ${link.quality}p")
                    callback(link)
                }
                return true
            }
            
            // Fallback
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
            println("✅ Link fallback criado")
            return true
            
        } catch (e: Exception) {
            println("💥 ERRO criando link: ${e.message}")
            return false
        }
    }
    
    // ================ ENUMS ================
    private enum class UrlType {
        SHORT_ID,      // IDs curtos numéricos (10529, 1456349) - precisa fluxo completo
        LONG_ID,       // IDs longos alfanuméricos (yziqjcntix6v) - API direta
        BYSEVEPOIN,    // URLs diretas do Bysevepoin
        UNKNOWN
    }
  }
}
