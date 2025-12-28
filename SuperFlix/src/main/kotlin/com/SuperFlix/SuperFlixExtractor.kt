package com.SuperFlix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.json.JSONObject
import java.net.URLEncoder
import java.util.*

object SuperFlixExtractor {
    // Cookie atualizado para evitar bloqueios
    private const val API_COOKIE = "SITE_TOTAL_ID=aTYqe6GU65PNmeCXpelwJwAAAMi; __dtsu=104017651574995957BEB724C6373F9E; __cc_id=a44d1e52993b9c2Oaaf40eba24989a06; __cc_cc=ACZ4nGNQSDQXsTFMNTWyTDROskw2MkhMTDDMXSE1KNDKxtLBMNDBjAIJMC4fgVe%2B%2F%2BDngAHemT8XsDBKrv%2FF3%2F2F2%2F%2FF0ZGhFP15u.VnW-1Y0o8o6/84-1.2.1.1-4_OXh2hYevsbO8hINijDKB8O_SPowh.pNojloHEbwX_qZorbmW8u8zqV9B7UsV6bbRmCWx_dD17mA7vJJklpOD9WBh9DA0wMV2a1QSKuR2J3FN9.TRzOUM4AhnTGFd8dJH8bHfqQdY7uYuUg7Ny1TVQDF9kXqyEPtnmkZ9rFkqQ2KS6u0t2hhFdQvRBY7dqyGfdjmyjDqwc7ZOovHB0eqep.FPHrh8T9iz1LuucA; cf_clearance=rfIEldahI7B..Y4PpZhGgwi.QOJBqIRGdFP150.VnW-1766868784-1.1-"
    
    suspend fun extractVideoLinks(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🎯 SuperFlixExtractor: Iniciando extração...")
            println("🔗 URL recebida: $url")
            
            // 1. Extrair ID do vídeo
            val videoId = extractVideoId(url)
            if (videoId == null) {
                println("❌ Não consegui extrair ID da URL: $url")
                return false
            }
            
            println("✅ ID extraído: $videoId")
            
            // 2. Primeiro tentar método direto (se for URL do fembed/bysevepoin)
            if (url.contains("fembed") || url.contains("bysevepoin")) {
                println("🔍 Tentando método direto...")
                val directResult = tryDirectMethod(url, videoId, name, callback)
                if (directResult) {
                    println("✅ Método direto funcionou!")
                    return true
                }
                println("❌ Método direto falhou, tentando API...")
            }
            
            // 3. Tentar obter detalhes via API
            val details = getVideoDetails(videoId)
            if (details == null) {
                println("❌ Não consegui obter detalhes do vídeo via API")
                // Tentar fallback: usar URL original como referência
                return tryFallbackMethod(url, videoId, name, callback)
            }
            
            println("📊 Título: ${details.title}")
            println("🔗 Embed URL: ${details.embedFrameUrl}")
            
            // 4. Gerar links usando a URL de embed
            return generateM3u8FromEmbed(details.embedFrameUrl, name, callback)
            
        } catch (e: Exception) {
            println("💥 Erro na extração: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    private suspend fun tryDirectMethod(
        url: String,
        videoId: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // Se for URL do fembed ou bysevepoin, tentar extrair diretamente
            val embedUrl = if (url.contains("fembed")) {
                "https://fembed.sx/e/$videoId"
            } else if (url.contains("bysevepoin")) {
                "https://bysevepoin.com/e/$videoId"
            } else {
                url
            }
            
            println("🎯 Tentando embed direto: $embedUrl")
            
            // Tentar fazer requisição para obter iframe
            val headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                "Referer" to "https://superflix21.lol/",
                "Cookie" to API_COOKIE
            )
            
            val response = app.get(embedUrl, headers = headers)
            
            if (response.code == 200) {
                val html = response.text
                println("📥 HTML obtido (${html.length} chars)")
                
                // Procurar por iframe ou script que contenha a URL do vídeo
                val videoUrl = extractVideoUrlFromHtml(html)
                if (videoUrl != null && videoUrl.contains(".m3u8")) {
                    println("🎬 URL M3U8 encontrada: $videoUrl")
                    return generateM3u8Links(videoUrl, embedUrl, name, callback)
                }
                
                // Procurar por iframe
                val iframeUrl = extractIframeUrl(html)
                if (iframeUrl != null) {
                    println("🎬 Iframe encontrado: $iframeUrl")
                    return generateM3u8FromEmbed(iframeUrl, name, callback)
                }
            }
            
            false
        } catch (e: Exception) {
            println("⚠️  Erro no método direto: ${e.message}")
            false
        }
    }
    
    private suspend fun tryFallbackMethod(
        url: String,
        videoId: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🔄 Tentando fallback...")
            
            // Tentar diferentes domínios
            val domains = listOf(
                "https://bysevepoin.com",
                "https://byseepoin.com",
                "https://fembed.sx",
                "https://g9r6.com"
            )
            
            for (domain in domains) {
                try {
                    val apiUrl = "$domain/api/videos/$videoId/embed/details"
                    println("📡 Testando domínio: $apiUrl")
                    
                    val headers = mapOf(
                        "Accept" to "application/json, text/plain, */*",
                        "User-Agent" to "Mozilla/5.0",
                        "Referer" to "https://superflix21.lol/",
                        "Cookie" to API_COOKIE
                    )
                    
                    val response = app.get(apiUrl, headers = headers, timeout = 10000)
                    
                    if (response.code == 200) {
                        val jsonText = response.text
                        println("✅ Domínio funcionou: $domain")
                        
                        // Extrair embed_frame_url
                        val embedUrl = extractFromJson(jsonText, "embed_frame_url")
                        if (embedUrl != null) {
                            return generateM3u8FromEmbed(embedUrl, name, callback)
                        }
                    }
                } catch (e: Exception) {
                    println("⚠️  Falha com domínio $domain: ${e.message}")
                }
            }
            
            false
        } catch (e: Exception) {
            println("💥 Erro no fallback: ${e.message}")
            false
        }
    }
    
    private suspend fun generateM3u8FromEmbed(
        embedUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🎯 Gerando M3U8 do embed: $embedUrl")
            
            // Extrair ID do embed URL
            val videoId = extractVideoId(embedUrl)
            if (videoId == null) {
                println("❌ Não consegui extrair ID do embed")
                return false
            }
            
            // Tentar diferentes padrões de URL de vídeo
            val patterns = listOf(
                "https://[server]/hls2/05/10459/${videoId}_h/master.m3u8",
                "https://[server]/hls2/01/10459/${videoId}/master.m3u8",
                "https://[server]/hls/${videoId}/master.m3u8",
                "https://[server]/v/${videoId}/master.m3u8"
            )
            
            // Servers conhecidos
            val servers = listOf(
                "be2719.rcr22.ams01.i8yz83pn.com",
                "rcr22.ams01.i8yz83pn.com",
                "ams01.i8yz83pn.com"
            )
            
            val timestamp = (System.currentTimeMillis() / 1000).toString()
            
            for (pattern in patterns) {
                for (server in servers) {
                    val m3u8Url = pattern.replace("[server]", server) +
                            "?t=temp_token&s=$timestamp&e=10800&f=0&srv=1070&sp=4000&p=0"
                    
                    println("🔗 Testando: ${m3u8Url.take(80)}...")
                    
                    try {
                        val testResponse = app.get(m3u8Url, timeout = 5000)
                        if (testResponse.code == 200 && testResponse.text.contains("#EXTM3U")) {
                            println("✅ URL M3U8 válida encontrada!")
                            return generateM3u8Links(m3u8Url, embedUrl, name, callback)
                        }
                    } catch (e: Exception) {
                        // Ignorar e continuar testando
                    }
                }
            }
            
            println("❌ Nenhuma URL M3U8 válida encontrada")
            false
            
        } catch (e: Exception) {
            println("💥 Erro ao gerar M3U8 do embed: ${e.message}")
            false
        }
    }
    
    private suspend fun getVideoDetails(videoId: String): VideoDetails? {
        return try {
            // Tentar diferentes domínios e endpoints
            val endpoints = listOf(
                "https://bysevepoin.com/api/videos/$videoId/embed/details",
                "https://g9r6.com/api/videos/$videoId/embed/details",
                "https://fembed.sx/api/videos/$videoId/embed/details"
            )
            
            for (apiUrl in endpoints) {
                try {
                    println("📡 Buscando detalhes: $apiUrl")
                    
                    val headers = mapOf(
                        "Accept" to "application/json, text/plain, */*",
                        "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
                        "Cache-Control" to "no-cache",
                        "Pragma" to "no-cache",
                        "Referer" to "https://superflix21.lol/",
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                        "Origin" to "https://superflix21.lol",
                        "Cookie" to API_COOKIE
                    )
                    
                    val response = app.get(apiUrl, headers = headers, timeout = 15000)
                    
                    if (response.code == 200) {
                        val jsonText = response.text
                        println("📥 Resposta detalhes (${response.code}): ${jsonText.take(200)}...")
                        
                        try {
                            val json = JSONObject(jsonText)
                            val fileId = if (json.has("id")) json.getInt("id") else 0
                            val code = if (json.has("code")) json.getString("code") else videoId
                            val title = if (json.has("title")) json.getString("title") else "Video $videoId"
                            val embedFrameUrl = if (json.has("embed_frame_url")) json.getString("embed_frame_url") else apiUrl
                            val posterUrl = if (json.has("poster_url")) json.getString("poster_url") else null
                            
                            return VideoDetails(
                                videoId = code,
                                fileId = fileId,
                                title = title,
                                embedFrameUrl = embedFrameUrl,
                                posterUrl = posterUrl
                            )
                        } catch (e: Exception) {
                            println("❌ Erro ao parsear JSON: ${e.message}")
                            // Continuar para próximo endpoint
                        }
                    } else {
                        println("⚠️  Status code: ${response.code}")
                    }
                } catch (e: Exception) {
                    println("⚠️  Erro no endpoint $apiUrl: ${e.message}")
                }
            }
            
            null
        } catch (e: Exception) {
            println("💥 Erro ao buscar detalhes: ${e.message}")
            null
        }
    }
    
    private fun extractVideoId(url: String): String? {
        val patterns = listOf(
            Regex("""/e/([a-zA-Z0-9]+)"""),
            Regex("""/v/([a-zA-Z0-9]+)"""),
            Regex("""/videos/([a-zA-Z0-9]+)"""),
            Regex("""/embed/([a-zA-Z0-9]+)"""),
            Regex("""/([a-zA-Z0-9]{8,})"""),
            Regex("""\?id=([a-zA-Z0-9]+)"""),
            Regex("""&id=([a-zA-Z0-9]+)""")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        
        return null
    }
    
    private fun extractVideoUrlFromHtml(html: String): String? {
        // Procurar por URLs .m3u8
        val m3u8Pattern = Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""")
        val m3u8Match = m3u8Pattern.find(html)
        if (m3u8Match != null) {
            return m3u8Match.groupValues[1]
        }
        
        // Procurar por sources de vídeo
        val sourcePattern = Regex("""<source[^>]+src=["']([^"']+)["']""")
        val sourceMatch = sourcePattern.find(html)
        if (sourceMatch != null) {
            return sourceMatch.groupValues[1]
        }
        
        return null
    }
    
    private fun extractIframeUrl(html: String): String? {
        val pattern = Regex("""<iframe[^>]+src=["']([^"']+)["']""")
        val match = pattern.find(html)
        return match?.groupValues?.get(1)
    }
    
    private fun extractFromJson(json: String, key: String): String? {
        val pattern = Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"")
        val match = pattern.find(json)
        return match?.groupValues?.get(1)
    }
    
    private suspend fun generateM3u8Links(
        m3u8Url: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🔄 Gerando links M3U8...")
            println("🎯 URL: $m3u8Url")
            println("🔗 Referer: $referer")
            
            val links = M3u8Helper.generateM3u8(
                name,
                m3u8Url,
                referer,
                headers = mapOf(
                    "Referer" to referer,
                    "Origin" to referer.removeSuffix("/"),
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                    "Accept" to "*/*",
                    "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8"
                )
            )
            
            if (links.isNotEmpty()) {
                links.forEach(callback)
                println("🎉 ${links.size} links M3U8 gerados com sucesso!")
                true
            } else {
                println("❌ Nenhum link M3U8 gerado")
                false
            }
        } catch (e: Exception) {
            println("💥 Erro ao gerar links M3U8: ${e.message}")
            false
        }
    }
    
    // Data class
    data class VideoDetails(
        val videoId: String,
        val fileId: Int,
        val title: String,
        val embedFrameUrl: String,
        val posterUrl: String?
    )
}
