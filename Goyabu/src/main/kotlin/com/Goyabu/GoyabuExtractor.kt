package com.Goyabu

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.Jsoup
import kotlinx.coroutines.delay

object GoyabuExtractor {
    private val itagQualityMap = mapOf(
        18 to 360, 22 to 720, 37 to 1080, 59 to 480,
        133 to 240, 134 to 360, 135 to 480, 136 to 720,
        137 to 1080, 160 to 144, 242 to 240, 243 to 360,
        244 to 480, 247 to 720, 248 to 1080
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
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Referer" to "https://goyabu.io/"
                )
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
            
            // PASSO 3: Construir URL do Blogger
            val bloggerUrl = "https://www.blogger.com/video.g?token=$token"
            println("📡 Acessando Blogger URL")
            
            // PASSO 4: Fazer requisição para o Blogger
            val bloggerResponse = app.get(
                bloggerUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Referer" to "https://goyabu.io/"
                )
            )
            
            val bloggerHtml = bloggerResponse.text
            println("📄 HTML do Blogger recebido, tamanho: ${bloggerHtml.length} bytes")
            
            // PASSO 5: Extrair parâmetros do HTML do Blogger
            val (f_sid, bl) = extractBloggerParams(bloggerHtml)
            
            println("📋 Parâmetros encontrados:")
            println("   f.sid: $f_sid")
            println("   bl: $bl")
            
            // Pequena pausa para evitar rate limiting
            delay(500)
            
            // PASSO 6: Chamar API batch execute
            val videos = callBloggerBatchApi(token, f_sid, bl)
            
            if (videos.isEmpty()) {
                println("❌ Nenhum vídeo encontrado na resposta da API")
                return false
            }
            
            println("✅ Encontradas ${videos.size} URLs de vídeo!")
            
            // PASSO 7: Processar cada URL encontrada
            videos.forEach { (videoUrl, itag) ->
                val quality = itagQualityMap[itag] ?: 360
                val qualityLabel = when(quality) {
                    1080 -> "FHD"
                    720 -> "HD"
                    480 -> "SD"
                    else -> "SD"
                }
                
                callback(
                    newExtractorLink(
                        source = "Goyabu",
                        name = "$name ($qualityLabel)",
                        url = videoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://www.blogger.com/"
                        this.quality = quality
                        this.headers = mapOf(
                            "Referer" to "https://www.blogger.com/",
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                            "Accept" to "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5",
                            "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
                            "Origin" to "https://www.blogger.com",
                            "Connection" to "keep-alive",
                            "Sec-Fetch-Dest" to "video",
                            "Sec-Fetch-Mode" to "no-cors",
                            "Sec-Fetch-Site" to "cross-site",
                            "Range" to "bytes=0-"
                        )
                    }
                )
                println("✅ Link adicionado: $qualityLabel (${quality}p)")
            }
            
            true
            
        } catch (e: Exception) {
            println("❌ ERRO: ${e.message}")
            e.printStackTrace()
            false
        }
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
    
    private fun extractBloggerParams(html: String): Pair<String, String> {
        // Valores padrão (fallback)
        var f_sid = "-7535563745894756252"
        var bl = "boq_bloggeruiserver_20260223.02_p0"
        
        // Procurar por WIZ_global_data no HTML
        val wizPattern = """window\.WIZ_global_data\s*=\s*\{([^}]+)\}""".toRegex()
        val wizMatch = wizPattern.find(html)
        
        if (wizMatch != null) {
            val wizData = wizMatch.groupValues[1]
            
            // Extrair FdrFJe (f.sid)
            val sidPattern = """"FdrFJe":"([^"]+)"""".toRegex()
            sidPattern.find(wizData)?.let {
                f_sid = it.groupValues[1]
            }
            
            // Extrair cfb2h (bl)
            val blPattern = """"cfb2h":"([^"]+)"""".toRegex()
            blPattern.find(wizData)?.let {
                bl = it.groupValues[1]
            }
        }
        
        return Pair(f_sid, bl)
    }
    
    private suspend fun callBloggerBatchApi(
        token: String,
        f_sid: String,
        bl: String
    ): List<Pair<String, Int>> {
        
        val apiUrl = "https://www.blogger.com/_/BloggerVideoPlayerUi/data/batchexecute"
        val reqid = (10000..99999).random()
        
        val headers = mapOf(
            "authority" to "www.blogger.com",
            "accept" to "*/*",
            "content-type" to "application/x-www-form-urlencoded;charset=UTF-8",
            "origin" to "https://www.blogger.com",
            "referer" to "https://www.blogger.com/",
            "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "x-same-domain" to "1"
        )
        
        val urlWithParams = "$apiUrl?rpcids=WcwnYd&source-path=%2Fvideo.g&f.sid=$f_sid&bl=$bl&hl=pt-BR&_reqid=$reqid&rt=c"
        
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
        decoded = decoded.replace("\\&", "&")
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
