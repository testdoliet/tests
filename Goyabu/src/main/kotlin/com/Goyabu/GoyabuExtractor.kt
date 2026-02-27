package com.Goyabu

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.Jsoup
import kotlinx.coroutines.delay
import java.security.MessageDigest
import java.util.*
import kotlin.collections.HashMap

object GoyabuExtractor {
    private val itagQualityMap = mapOf(
        18 to 360, 22 to 720, 37 to 1080, 59 to 480,
        133 to 240, 134 to 360, 135 to 480, 136 to 720,
        137 to 1080, 160 to 144, 242 to 240, 243 to 360,
        244 to 480, 247 to 720, 248 to 1080
    )

    // Cache para dados da sessão
    private var cachedWizData: WizData? = null
    private var cachedNonce: String? = null
    private var lastSessionRefresh = 0L

    data class WizData(
        val fSid: String,
        val bl: String,
        val cfb2h: String,
        val UUFaWc: String,
        val hsFLT: String,
        val combinedSignature: String,
        val cssRowKey: String
    )

    suspend fun extractVideoLinks(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🚀 INICIANDO EXTRAÇÃO PARA: $url")
        
        return try {
            // PASSO 1: Pegar HTML da página do Goyabu
            val response = app.get(
                url,
                headers = mobileHeaders()
            )

            val html = response.text
            val doc = Jsoup.parse(html)
            
            println("📄 HTML do Goyabu carregado, tamanho: ${html.length} bytes")
            
            // PASSO 2: Extrair token do iframe do Blogger
            val token = extractBloggerToken(doc)
            
            if (token == null) {
                println("❌ Token do Blogger não encontrado!")
                return false
            }
            
            println("✅ Token encontrado: ${token.take(50)}...")
            
            // PASSO 3: Acessar Blogger e extrair dados da sessão
            val wizData = extractSessionData(token)
            
            // PASSO 4: Chamar API batch execute
            val videos = callBloggerBatchApi(token, wizData)
            
            if (videos.isEmpty()) {
                println("❌ Nenhum vídeo encontrado na resposta da API")
                return false
            }
            
            println("✅ Encontradas ${videos.size} URLs de vídeo!")
            
            // PASSO 5: Gerar timestamp único para esta sessão
            val timestamp = System.currentTimeMillis()
            
            // PASSO 6: Processar cada URL encontrada e adicionar parâmetros anti-bot
            videos.forEach { (videoUrl, itag) ->
                val quality = itagQualityMap[itag] ?: 360
                val qualityLabel = when(quality) {
                    1080 -> "FHD"
                    720 -> "HD"
                    480 -> "SD"
                    else -> "SD"
                }
                
                // Extrair videoId da URL
                val videoId = extractVideoId(videoUrl)
                
                // Gerar cpn para esta URL
                val cpn = generateCpn(
                    wizData = wizData,
                    videoId = videoId,
                    timestamp = timestamp,
                    nonce = cachedNonce ?: generateRandomString(32)
                )
                
                // Decodificar a URL base primeiro
                val urlBase = decodeUrl(videoUrl)
                
                // CORREÇÃO CRÍTICA: Remover qualquer \ antes de &
                val urlLimpa = urlBase.replace("\\&", "&")
                
                // Adicionar parâmetros anti-bot à URL
                val finalUrl = buildString {
                    append(urlLimpa)
                    if (urlLimpa.contains("?")) {
                        append("&cpn=$cpn&c=WEB_EMBEDDED_PLAYER&cver=1.20260224.08.00")
                    } else {
                        append("?cpn=$cpn&c=WEB_EMBEDDED_PLAYER&cver=1.20260224.08.00")
                    }
                }
                
                callback(
                    newExtractorLink(
                        source = "Goyabu",
                        name = "$name ($qualityLabel)",
                        url = finalUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://youtube.googleapis.com/"
                        this.quality = quality
                        this.headers = videoHeaders()
                    }
                )
                println("✅ Link adicionado: $qualityLabel (${quality}p) com cpn=$cpn")
            }
            
            true
            
        } catch (e: Exception) {
            println("❌ ERRO: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    private suspend fun extractSessionData(token: String): WizData {
        // Se já temos sessão recente (menos de 5 minutos), reutilizar
        if (cachedWizData != null && System.currentTimeMillis() - lastSessionRefresh < 300000) {
            println("📋 Usando dados de sessão em cache")
            return cachedWizData!!
        }
        
        println("📡 Acessando Blogger para obter dados da sessão...")
        
        // Requisição para o Blogger
        val bloggerResponse = app.get(
            "https://www.blogger.com/video.g?token=$token",
            headers = mobileHeaders()
        )
        
        val bloggerHtml = bloggerResponse.text
        println("📄 HTML do Blogger recebido, tamanho: ${bloggerHtml.length} bytes")
        
        // Extrair WIZ_global_data
        val wizData = extractWizData(bloggerHtml)
        
        // Extrair nonce da página
        cachedNonce = extractNonce(bloggerHtml)
        
        // Extrair assinatura combinada
        val combinedSignature = extractCombinedSignature(bloggerHtml)
        
        // Extrair CSS Row Key
        val cssRowKey = extractCssRowKey(bloggerHtml)
        
        val result = WizData(
            fSid = wizData["FdrFJe"] ?: "-7535563745894756252",
            bl = wizData["cfb2h"] ?: "boq_bloggeruiserver_20260223.02_p0",
            cfb2h = wizData["cfb2h"] ?: "",
            UUFaWc = wizData["UUFaWc"] ?: "%.@.null,1000,2]",
            hsFLT = wizData["hsFLT"] ?: "%.@.null,1000,2]",
            combinedSignature = combinedSignature,
            cssRowKey = cssRowKey
        )
        
        cachedWizData = result
        lastSessionRefresh = System.currentTimeMillis()
        
        println("📋 Dados da sessão extraídos:")
        println("   f.sid: ${result.fSid}")
        println("   bl: ${result.bl}")
        println("   nonce: ${cachedNonce?.take(20)}...")
        
        return result
    }
    
    private fun extractWizData(html: String): Map<String, String> {
        val wizData = HashMap<String, String>()
        
        val pattern = """window\.WIZ_global_data\s*=\s*\{([^}]+)\}""".toRegex()
        val match = pattern.find(html)
        
        if (match != null) {
            val wizStr = match.groupValues[1]
            
            // Extrair campos importantes
            extractField(wizStr, "FdrFJe")?.let { wizData["FdrFJe"] = it }
            extractField(wizStr, "cfb2h")?.let { wizData["cfb2h"] = it }
            extractField(wizStr, "UUFaWc")?.let { wizData["UUFaWc"] = it }
            extractField(wizStr, "hsFLT")?.let { wizData["hsFLT"] = it }
        }
        
        return wizData
    }
    
    private fun extractField(data: String, field: String): String? {
        val pattern = """"$field":"([^"]+)"""".toRegex()
        return pattern.find(data)?.groupValues?.get(1)
    }
    
    private fun extractNonce(html: String): String? {
        val pattern = """script[^>]*nonce="([^"]+)"""".toRegex()
        return pattern.find(html)?.groupValues?.get(1)
    }
    
    private fun extractCombinedSignature(html: String): String {
        val pattern = """_F_combinedSignature\s*=\s*'([^']*)'""".toRegex()
        return pattern.find(html)?.groupValues?.get(1) ?: "AEy-KP0QB8hC39tFP1M5MbDoKPIrbaS1kQ"
    }
    
    private fun extractCssRowKey(html: String): String {
        val pattern = """_F_cssRowKey\s*=\s*'([^']*)'""".toRegex()
        return pattern.find(html)?.groupValues?.get(1) ?: "boq-blogger.BloggerVideoPlayerUi.XKA1asAesHc.L.B1.O"
    }
    
    private fun generateCpn(
        wizData: WizData,
        videoId: String,
        timestamp: Long,
        nonce: String
    ): String {
        return try {
            // Construir a seed
            val seed = buildString {
                append(wizData.cfb2h)
                append(wizData.UUFaWc)
                append(wizData.hsFLT)
                append(videoId)
                append(timestamp.toString())
                append(nonce)
            }
            
            // Calcular SHA-256
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(seed.toByteArray())
            
            // Converter para Base64 e pegar primeiros 16 caracteres
            Base64.getEncoder().encodeToString(hash)
                .substring(0, 16)
                .replace("+", "")
                .replace("/", "")
                .replace("=", "")
            
        } catch (e: Exception) {
            println("⚠️ Erro ao gerar cpn: ${e.message}")
            // Fallback: cpn aleatório
            generateRandomString(16)
        }
    }
    
    private fun generateRandomString(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length).map { chars.random() }.joinToString("")
    }
    
    private fun extractVideoId(url: String): String {
        val pattern = """id=([a-f0-9]+)""".toRegex()
        return pattern.find(url)?.groupValues?.get(1) ?: "picasacid"
    }
    
    private fun mobileHeaders(): Map<String, String> {
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
            "Accept" to "*/*",
            "Accept-Language" to "pt-BR",
            "sec-ch-ua" to "\"Chromium\";v=\"127\", \"Not)A;Brand\";v=\"99\", \"Microsoft Edge Simulate\";v=\"127\", \"Lemur\";v=\"127\"",
            "sec-ch-ua-mobile" to "?1",
            "sec-ch-ua-platform" to "\"Android\"",
            "x-client-data" to "COjuygE="
        )
    }
    
    private fun videoHeaders(): Map<String, String> {
        return mapOf(
            "Referer" to "https://youtube.googleapis.com/",
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
            "Accept" to "*/*",
            "Accept-Language" to "pt-BR",
            "Priority" to "i",
            "Range" to "bytes=0-",
            "sec-ch-ua" to "\"Chromium\";v=\"127\", \"Not)A;Brand\";v=\"99\", \"Microsoft Edge Simulate\";v=\"127\", \"Lemur\";v=\"127\"",
            "sec-ch-ua-mobile" to "?1",
            "sec-ch-ua-platform" to "\"Android\"",
            "sec-fetch-dest" to "video",
            "sec-fetch-mode" to "no-cors",
            "sec-fetch-site" to "cross-site",
            "x-client-data" to "COjuygE=",
            "Connection" to "keep-alive"
        )
    }
    
    private fun extractBloggerToken(doc: org.jsoup.nodes.Document): String? {
        // Procurar no iframe primeiro
        val iframe = doc.selectFirst("iframe[src*='blogger.com/video.g']")
        if (iframe != null) {
            val src = iframe.attr("src")
            val pattern = """token=([a-zA-Z0-9_\-]+)""".toRegex()
            val match = pattern.find(src)
            if (match != null) {
                println("✅ Token extraído do iframe")
                return match.groupValues[1]
            }
        }
        
        // Fallback: procurar em scripts
        val pattern = """video\.g\?token=([a-zA-Z0-9_\-]+)""".toRegex()
        doc.select("script").forEach { script ->
            pattern.find(script.html())?.let {
                println("✅ Token extraído do script")
                return it.groupValues[1]
            }
        }
        
        return null
    }
    
    private suspend fun callBloggerBatchApi(
        token: String,
        wizData: WizData
    ): List<Pair<String, Int>> {
        
        val apiUrl = "https://www.blogger.com/_/BloggerVideoPlayerUi/data/batchexecute"
        val reqid = (10000..99999).random()
        
        val headers = mapOf(
            "authority" to "www.blogger.com",
            "accept" to "*/*",
            "content-type" to "application/x-www-form-urlencoded;charset=UTF-8",
            "origin" to "https://www.blogger.com",
            "referer" to "https://www.blogger.com/",
            "user-agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
            "sec-ch-ua" to "\"Chromium\";v=\"127\", \"Not)A;Brand\";v=\"99\", \"Microsoft Edge Simulate\";v=\"127\", \"Lemur\";v=\"127\"",
            "sec-ch-ua-mobile" to "?1",
            "sec-ch-ua-platform" to "\"Android\"",
            "x-client-data" to "COjuygE=",
            "x-same-domain" to "1"
        )
        
        val urlWithParams = "$apiUrl?rpcids=WcwnYd&source-path=%2Fvideo.g&f.sid=${wizData.fSid}&bl=${wizData.bl}&hl=pt-BR&_reqid=$reqid&rt=c"
        
        // Body no formato correto
        val body = mapOf(
            "f.req" to "%5B%5B%5B%22WcwnYd%22%2C%22%5B%5C%22$token%5C%22%2C%5C%22%5C%22%2C0%5D%22%2Cnull%2C%22generic%22%5D%5D%5D"
        )
        
        println("📡 Chamando API batch execute...")
        
        val response = app.post(
            url = urlWithParams,
            headers = headers,
            data = body
        )
        
        println("✅ Resposta da API recebida, status: ${response.code}")
        println("📄 Tamanho da resposta: ${response.text.length} bytes")
        
        return extractVideoUrlsFromResponse(response.text)
    }
    
    private fun extractVideoUrlsFromResponse(response: String): List<Pair<String, Int>> {
        val videos = mutableListOf<Pair<String, Int>>()
        
        println("\n📄 Resposta da API (primeiros 500 chars):")
        println(response.take(500))
        
        // Primeiro, tentar extrair o JSON real do formato do Google
        val jsonData = extractGoogleJson(response)
        
        if (jsonData != response) {
            println("✅ JSON extraído do wrapper, tamanho: ${jsonData.length} bytes")
            println("📄 JSON extraído (primeiros 300 chars):")
            println(jsonData.take(300))
        }
        
        // Padrões para encontrar URLs
        val patterns = listOf(
            """https?:\\/\\/[^"\\]+?\.googlevideo\.com\\/[^"\\]+?videoplayback[^"\\]*""".toRegex(),
            """https?:\\?/\\?/[^"\\]+?\.googlevideo\.com\\?/[^"\\]+?videoplayback[^"\\]*""".toRegex(),
            """https?://[^"'\s]+?\.googlevideo\.com/[^"'\s]+?videoplayback[^"'\s]*""".toRegex(),
            """googlevideo\.com/[^"'\s]+?videoplayback[^"'\s]*""".toRegex()
        )
        
        // Procurar em ambos: resposta original e JSON extraído
        val sources = listOf(response, jsonData).distinct()
        
        for (sourceIndex in sources.indices) {
            val source = sources[sourceIndex]
            val sourceName = if (sourceIndex == 0) "Resposta original" else "JSON extraído"
            
            println("\n🔍 Buscando em: $sourceName")
            
            for (patternIndex in patterns.indices) {
                val pattern = patterns[patternIndex]
                val matches = pattern.findAll(source).toList()
                
                if (matches.isNotEmpty()) {
                    println("   ✅ Padrão ${patternIndex + 1} encontrou ${matches.size} URLs")
                    
                    matches.forEach { match ->
                        var url = match.value
                        println("   📹 URL bruta: ${url.take(100)}...")
                        
                        if (!url.startsWith("http")) {
                            url = "https://$url"
                        }
                        
                        url = decodeUrl(url)
                        val itag = extractItagFromUrl(url)
                        
                        if (!videos.any { it.first == url }) {
                            videos.add(Pair(url, itag))
                            println("   ✅ URL adicionada: itag=$itag")
                        }
                    }
                }
            }
        }
        
        // Se ainda não encontrou, tentar busca manual na string
        if (videos.isEmpty()) {
            println("\n⚠️ Nenhuma URL encontrada com padrões, tentando busca manual...")
            
            val googleVideoIndices = response.indicesOf("googlevideo")
            println("   Encontradas ${googleVideoIndices.size} ocorrências de 'googlevideo'")
            
            // Procurar manualmente por URLs
            val urlStartPattern = """https?://""".toRegex()
            urlStartPattern.findAll(response).forEach { match ->
                val start = match.range.first
                val end = response.indexOf('"', start)
                if (end > start) {
                    val url = response.substring(start, end)
                    if ("googlevideo" in url && "videoplayback" in url) {
                        println("   📹 URL manual: ${url.take(100)}...")
                        val decodedUrl = decodeUrl(url)
                        val itag = extractItagFromUrl(decodedUrl)
                        if (!videos.any { it.first == decodedUrl }) {
                            videos.add(Pair(decodedUrl, itag))
                        }
                    }
                }
            }
        }
        
        // Ordenar por qualidade (melhor primeiro)
        val qualityOrder = listOf(37, 22, 18, 59)
        val result = videos
            .distinctBy { it.second }
            .sortedBy { qualityOrder.indexOf(it.second) }
        
        println("\n📊 Total de URLs encontradas: ${result.size}")
        result.forEach { (url, itag) ->
            val quality = itagQualityMap[itag] ?: 0
            println("   📹 itag=$itag (${quality}p) -> ${url.take(100)}...")
        }
        
        return result
    }
    
    private fun extractGoogleJson(response: String): String {
        try {
            var data = response.replace(Regex("""^\)\]\}'\s*\n?"""), "")
            
            println("📄 Após remover prefixo: ${data.take(100)}...")
            
            val pattern = """\[\s*\[\s*"wrb\.fr"\s*,\s*"[^"]*"\s*,\s*"(.+?)"\s*\]""".toRegex(RegexOption.DOT_MATCHES_ALL)
            val match = pattern.find(data)
            
            if (match != null) {
                println("✅ Encontrado padrão wrb.fr")
                var jsonStr = match.groupValues[1]
                println("📄 JSON string bruta: ${jsonStr.take(200)}...")
                
                jsonStr = jsonStr.replace("\\\"", "\"")
                jsonStr = jsonStr.replace("\\\\", "\\")
                jsonStr = decodeUnicodeEscapes(jsonStr)
                
                return jsonStr
            } else {
                println("⚠️ Padrão wrb.fr não encontrado")
            }
        } catch (e: Exception) {
            println("⚠️ Erro ao extrair JSON: ${e.message}")
        }
        
        return response
    }
    
    private fun decodeUrl(url: String): String {
        var decoded = url
        decoded = decodeUnicodeEscapes(decoded)
        decoded = decoded.replace("\\/", "/")
        decoded = decoded.replace("\\\\", "\\")
        decoded = decoded.replace("\\=", "=")
        decoded = decoded.replace("\\&", "&")  // ← CORREÇÃO CRÍTICA!
        
        // Remove barra invertida no final (caso ainda exista)
        if (decoded.endsWith("\\")) {
            decoded = decoded.dropLast(1)
        }
        
        decoded = decoded.trim('"')
        return decoded
    }
    
    private fun decodeUnicodeEscapes(text: String): String {
        var result = text
        val pattern = """\\u([0-9a-fA-F]{4})""".toRegex()
        
        result = pattern.replace(result) { matchResult ->
            val hexCode = matchResult.groupValues[1]
            try {
                hexCode.toInt(16).toChar().toString()
            } catch (e: Exception) {
                "?"
            }
        }
        
        return result
    }
    
    private fun extractItagFromUrl(url: String): Int {
        val patterns = listOf(
            """itag[=?&](\d+)""".toRegex(),
            """itag%3D(\d+)""".toRegex(),
            """itag\\u003d(\d+)""".toRegex()
        )
        
        for (pattern in patterns) {
            pattern.find(url)?.let {
                return it.groupValues[1].toIntOrNull() ?: 18
            }
        }
        
        return when {
            "itag=22" in url || "itag%3D22" in url || "itag\\u003d22" in url -> 22
            "itag=18" in url || "itag%3D18" in url || "itag\\u003d18" in url -> 18
            "itag=37" in url || "itag%3D37" in url || "itag\\u003d37" in url -> 37
            "itag=59" in url || "itag%3D59" in url || "itag\\u003d59" in url -> 59
            else -> 18
        }
    }
    
    // Função de extensão para encontrar índices de substring
    private fun String.indicesOf(substr: String, ignoreCase: Boolean = true): List<Int> {
        val indices = mutableListOf<Int>()
        var index = 0
        while (index < length) {
            index = indexOf(substr, index, ignoreCase)
            if (index < 0) break
            indices.add(index)
            index += substr.length
        }
        return indices
    }
}
