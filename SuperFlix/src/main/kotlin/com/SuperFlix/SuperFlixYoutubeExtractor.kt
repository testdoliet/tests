package com.SuperFlix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object SuperFlixExtractor {
    private val okHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
                .header("Referer", "https://superflix21.lol/")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "cross-site")
                .build()
            chain.proceed(request)
        }
        .build()

    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🚀 Usando OkHttp para extração direta...")
            
            // PASSO 1: Seguir TODOS os redirecionamentos com OkHttp
            val finalUrl = followRedirectsWithOkHttp(url)
            println("🔗 URL final após redirecionamentos: $finalUrl")
            
            // PASSO 2: Se chegou no player bysevepoin, extrair m3u8
            if (finalUrl.contains("bysevepoin.com/e/")) {
                extractM3u8FromBysevepoin(finalUrl, name, callback)
            } else {
                println("❌ Não chegou no player bysevepoin.com")
                false
            }
        } catch (e: Exception) {
            println("💥 Erro no OkHttp: ${e.message}")
            false
        }
    }
    
    private suspend fun followRedirectsWithOkHttp(startUrl: String): String {
        return try {
            val request = Request.Builder()
                .url(startUrl)
                .get()
                .build()
            
            val response = okHttpClient.newCall(request).execute()
            // Retorna a URL FINAL após TODOS os redirecionamentos
            val finalUrl = response.request.url.toString()
            response.close()
            finalUrl
        } catch (e: Exception) {
            startUrl
        }
    }
    
    private suspend fun extractM3u8FromBysevepoin(
        playerUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🎬 Extraindo m3u8 do player: $playerUrl")
            
            // Faz request ao player bysevepoin
            val request = Request.Builder()
                .url(playerUrl)
                .header("Referer", "https://superflix21.lol/")
                .header("Origin", "https://superflix21.lol")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()
            
            val response = okHttpClient.newCall(request).execute()
            
            if (response.isSuccessful) {
                val html = response.body?.string() ?: ""
                response.close()
                println("📄 HTML do player obtido (${html.length} chars)")
                
                // Procura m3u8 no HTML
                val m3u8Url = findM3u8InHtml(html)
                
                if (m3u8Url != null) {
                    println("✅ M3U8 encontrado no HTML: $m3u8Url")
                    
                    // Gerar links M3U8 com headers CORRETOS
                    M3u8Helper.generateM3u8(
                        name,
                        m3u8Url,
                        "https://g9r6.com/",
                        headers = mapOf(
                            "Referer" to "https://g9r6.com/",
                            "Origin" to "https://g9r6.com",
                            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
                        )
                    ).forEach(callback)
                    
                    true
                } else {
                    println("❌ Nenhum m3u8 encontrado no HTML do player")
                    response.close()
                    false
                }
            } else {
                println("❌ Falha ao acessar player: ${response.code}")
                response.close()
                false
            }
        } catch (e: Exception) {
            println("💥 Erro ao extrair do player: ${e.message}")
            false
        }
    }
    
    private fun findM3u8InHtml(html: String): String? {
        // Padrões para encontrar m3u8 no HTML
        val patterns = listOf(
            Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']"""),
            Regex("""src=["'](https?://[^"']+\.m3u8[^"']*)["']"""),
            Regex("""source.*src=["'](https?://[^"']+\.m3u8[^"']*)["']"""),
            Regex("""file:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
            Regex("""hls\.loadSource\(["'](https?://[^"']+\.m3u8[^"']*)["']"""),
            Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(html)
            if (match != null) {
                val url = match.groupValues[1]
                // Filtra apenas URLs do CDN correto
                if (url.contains("g9r6.com") || url.contains("filemoon") || 
                    url.contains("sxcdn") || url.contains("fcdn") ||
                    url.contains(".m3u8")) {
                    return url
                }
            }
        }
        
        return null
    }
}
