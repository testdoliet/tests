package com.Goyabu

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.Jsoup

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
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                        )
                    }
                )
                println("✅ Link adicionado: $qualityLabel")
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
        
        // Body no formato correto (URL encoded)
        val body = mapOf(
            "f.req" to "%5B%5B%5B%22WcwnYd%22%2C%22%5B%5C%22$token%5C%22%2C%5C%22%5C%22%2C0%5D%22%2Cnull%2C%22generic%22%5D%5D%5D"
        )
        
        println("📡 Chamando API batch execute...")
        
        val response = app.post(
            urlWithParams,
            headers = headers,
            data = body
        )
        
        println("✅ Resposta da API recebida, status: ${response.code}")
        
        return extractVideoUrlsFromResponse(response.text)
    }
    
    private fun extractVideoUrlsFromResponse(response: String): List<Pair<String, Int>> {
        val videos = mutableListOf<Pair<String, Int>>()
        
        // Padrão para encontrar URLs do Google Video (com escape)
        val pattern = """(https?:\\/\\/[^"\\]+\.googlevideo\.com\\/[^"\\]+videoplayback[^"\\]*)""".toRegex()
        
        pattern.findAll(response).forEach { match ->
            var url = match.value
                .replace("\\u003d", "=")
                .replace("\\/", "/")
                .replace("\\", "")
                .trim()
            
            // Extrair itag da URL
            val itagPattern = """itag[=?&](\d+)""".toRegex()
            val itag = itagPattern.find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 18
            
            videos.add(Pair(url, itag))
        }
        
        // Se não encontrou com escape, tentar sem escape
        if (videos.isEmpty()) {
            val pattern2 = """(https?://[^"'\s]+\.googlevideo\.com/[^"'\s]+videoplayback[^"'\s]*)""".toRegex()
            pattern2.findAll(response).forEach { match ->
                val url = match.value.trim()
                val itagPattern = """itag[=?&](\d+)""".toRegex()
                val itag = itagPattern.find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 18
                videos.add(Pair(url, itag))
            }
        }
        
        return videos.distinctBy { it.second }
    }
}
