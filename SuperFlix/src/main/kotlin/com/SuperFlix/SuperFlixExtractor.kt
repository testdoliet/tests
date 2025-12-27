
package com.SuperFlix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit


object SuperFlixExtractor {
    private val okHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🎯 Extração inteligente com OkHttp...")
            
            // Estratégia: Analisar o fluxo completo
            val analysis = analyzeUrlFlow(url)
            println("📊 Análise do fluxo:")
            println("   - URL inicial: ${analysis.initialUrl}")
            println("   - Redirecionamentos: ${analysis.redirects.size}")
            analysis.redirects.forEachIndexed { i, redirect ->
                println("     ${i+1}. $redirect")
            }
            println("   - URL final: ${analysis.finalUrl}")
            
            // Se chegou no bysevepoin, extrair
            if (analysis.finalUrl.contains("bysevepoin.com")) {
                println("✅ Chegou no player bysevepoin.com!")
                extractFromFinalPlayer(analysis.finalUrl, name, callback)
            } else {
                println("❌ Não chegou no player final")
                false
            }
        } catch (e: Exception) {
            println("💥 Erro: ${e.message}")
            false
        }
    }
    
    private suspend fun analyzeUrlFlow(startUrl: String): UrlAnalysis {
        val redirects = mutableListOf<String>()
        var currentUrl = startUrl
        var finalUrl = startUrl
        val maxRedirects = 10
        
        println("🔍 Analisando fluxo de redirecionamentos...")
        
        for (i in 0 until maxRedirects) {
            try {
                println("   Visitando: $currentUrl")
                
                val request = Request.Builder()
                    .url(currentUrl)
                    .header("User-Agent", "Mozilla/5.0")
                    .get()
                    .build()
                
                okHttpClient.newCall(request).execute().use { response ->
                    // Adiciona à lista de redirecionamentos
                    redirects.add("${response.code} - $currentUrl")
                    
                    // Verifica se houve redirecionamento
                    val location = response.header("Location")
                    
                    if (location != null && response.isRedirect) {
                        currentUrl = location
                        finalUrl = location
                        println("   ↪️  Redirecionado para: $location")
                    } else {
                        // Sem mais redirecionamentos
                        finalUrl = currentUrl
                        println("   ✅ Fim dos redirecionamentos")
                        break
                    }
                }
            } catch (e: Exception) {
                println("   ⚠️  Erro: ${e.message}")
                break
            }
        }
        
        return UrlAnalysis(startUrl, redirects, finalUrl)
    }
    
    private suspend fun extractFromFinalPlayer(
        playerUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🎬 Extraindo do player final...")
            
            // 1. Primeiro, pega o HTML do player
            val html = fetchHtml(playerUrl)
            
            // 2. Procura por scripts JavaScript
            val scripts = extractScripts(html)
            println("📜 ${scripts.size} scripts encontrados no player")
            
            // 3. Procura por variáveis JavaScript que contenham URLs de vídeo
            val videoUrls = findVideoUrlsInScripts(scripts)
            println("🔍 ${videoUrls.size} URLs de vídeo encontradas nos scripts")
            
            // 4. Filtra para m3u8
            val m3u8Urls = videoUrls.filter { it.contains(".m3u8") }
            
            if (m3u8Urls.isNotEmpty()) {
                println("✅ ${m3u8Urls.size} M3U8(s) encontrado(s):")
                m3u8Urls.forEach { println("   - $it") }
                
                // Pega o primeiro m3u8 encontrado
                val m3u8Url = m3u8Urls.first()
                
                // Tenta gerar os links
                val success = M3u8Helper.generateM3u8(
                    name,
                    m3u8Url,
                    "https://g9r6.com/",
                    headers = getCdnHeaders()
                ).any { link ->
                    callback(link)
                    true
                }
                
                success
            } else {
                println("❌ Nenhum M3U8 encontrado nos scripts")
                
                // Fallback: Procura m3u8 direto no HTML
                val m3u8FromHtml = findM3u8InHtml(html)
                if (m3u8FromHtml != null) {
                    println("✅ M3U8 encontrado no HTML: $m3u8FromHtml")
                    
                    M3u8Helper.generateM3u8(
                        name,
                        m3u8FromHtml,
                        "https://g9r6.com/",
                        headers = getCdnHeaders()
                    ).forEach(callback)
                    
                    true
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            println("💥 Erro ao extrair do player: ${e.message}")
            false
        }
    }
    
    private suspend fun fetchHtml(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0")
            .header("Referer", "https://superflix21.lol/")
            .get()
            .build()
        
        return okHttpClient.newCall(request).execute().use { response ->
            response.body?.string() ?: ""
        }
    }
    
    private fun extractScripts(html: String): List<String> {
        val scriptPattern = Regex("""<script[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
        return scriptPattern.findAll(html).map { it.groupValues[1] }.toList()
    }
    
    private fun findVideoUrlsInScripts(scripts: List<String>): List<String> {
        val urls = mutableListOf<String>()
        val urlPatterns = listOf(
            Regex("""["'](https?://[^"']+\.(m3u8|mp4|mkv)[^"']*)["']"""),
            Regex("""(https?://[^"'\s]+\.(m3u8|mp4|mkv)[^"'\s]*)"""),
            Regex("""file:\s*["'](https?://[^"']+)["']"""),
            Regex("""src:\s*["'](https?://[^"']+)["']"""),
            Regex("""url:\s*["'](https?://[^"']+)["']""")
        )
        
        for (script in scripts) {
            for (pattern in urlPatterns) {
                val matches = pattern.findAll(script)
                matches.forEach { match ->
                    val url = match.groupValues[1]
                    if (url.contains("g9r6.com") || url.contains("filemoon") || 
                        url.contains("sxcdn") || url.contains("fcdn") ||
                        url.contains(".m3u8")) {
                        urls.add(url)
                    }
                }
            }
        }
        
        return urls.distinct()
    }
    
    private fun getCdnHeaders(): Map<String, String> {
        return mapOf(
            "Referer" to "https://g9r6.com/",
            "Origin" to "https://g9r6.com",
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
            "Accept" to "*/*",
            "Accept-Language" to "pt-BR",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site"
        )
    }
    
    data class UrlAnalysis(
        val initialUrl: String,
        val redirects: List<String>,
        val finalUrl: String
    )
}
