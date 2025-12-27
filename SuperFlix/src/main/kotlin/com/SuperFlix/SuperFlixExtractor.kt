package com.SuperFlix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

object SuperFlixExtractor {
    // Criação do cliente OkHttp IDÊNTICO ao WebVideoCaster
    private val okHttpClient = OkHttpClient.Builder()
        .followRedirects(true) // IMPORTANTE: segue redirecionamentos
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS) // Timeout maior para carregar tudo
        .writeTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            // Headers de navegador real (igual WebVideoCaster)
            val originalRequest = chain.request()
            val requestBuilder = originalRequest.newBuilder()
            
            // Headers que o WebVideoCaster usa
            requestBuilder.header("User-Agent", 
                "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36")
            requestBuilder.header("Accept", 
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            requestBuilder.header("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
            requestBuilder.header("Accept-Encoding", "gzip, deflate")
            requestBuilder.header("Connection", "keep-alive")
            requestBuilder.header("Upgrade-Insecure-Requests", "1")
            requestBuilder.header("Cache-Control", "max-age=0")
            
            chain.proceed(requestBuilder.build())
        }
        .build()

    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🕵️‍♂️ WebVideoCaster MODE: OkHttp scanning...")
            
            // ETAPA 1: Fetch da página com OkHttp (como WebVideoCaster)
            val (finalHtml, finalUrl) = fetchPageWithOkHttp(url)
            
            println("📍 Final URL: $finalUrl")
            println("📄 HTML size: ${finalHtml.length} chars")
            
            // ETAPA 2: Análise completa do HTML
            val videoUrls = analyzeHtmlForVideoUrls(finalHtml, finalUrl)
            
            println("🎯 Found ${videoUrls.size} potential video URLs")
            
            // ETAPA 3: Testar cada URL encontrada
            for ((index, videoUrl) in videoUrls.withIndex()) {
                println("\n🧪 Testing URL ${index + 1}/${videoUrls.size}:")
                println("   ${videoUrl.take(80)}...")
                
                if (testAndExtractVideo(videoUrl, name, callback)) {
                    println("✅ SUCCESS! Video extracted")
                    return true
                }
            }
            
            println("❌ No working video URLs found")
            false
        } catch (e: Exception) {
            println("💥 Error in WebVideoCaster mode: ${e.message}")
            false
        }
    }
    
    private suspend fun fetchPageWithOkHttp(startUrl: String): Pair<String, String> {
        return try {
            println("🌐 Fetching page with OkHttp...")
            
            val request = Request.Builder()
                .url(startUrl)
                .get()
                .build()
            
            val response = okHttpClient.newCall(request).execute()
            
            if (response.isSuccessful) {
                val html = response.body?.string() ?: ""
                val finalUrl = response.request.url.toString()
                response.close()
                
                println("✅ Page fetched successfully")
                Pair(html, finalUrl)
            } else {
                response.close()
                throw Exception("HTTP ${response.code}")
            }
        } catch (e: Exception) {
            throw Exception("Failed to fetch page: ${e.message}")
        }
    }
    
    private fun analyzeHtmlForVideoUrls(html: String, baseUrl: String): List<String> {
        val videoUrls = mutableListOf<String>()
        
        println("🔍 Analyzing HTML for video URLs...")
        
        // 1. Primeiro: Procurar URLs de vídeo óbvias
        val directPatterns = listOf(
            // Tags HTML com src
            Regex("""<video[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<source[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<embed[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<object[^>]+data=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            
            // Atributos data-*
            Regex("""data-url=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""data-src=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""data-file=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""data-video=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""data-hls=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            
            // URLs em JavaScript
            Regex("""["'](https?://[^"']+\.(m3u8|mp4|mkv|webm|avi|mov)[^"']*)["']"""),
            Regex("""(https?://[^"'\s]+\.(m3u8|mp4|mkv|webm|avi|mov)[^"'\s]*)""")
        )
        
        for (pattern in directPatterns) {
            val matches = pattern.findAll(html)
            matches.forEach { match ->
                var url = match.groupValues[1]
                
                // Se for URL relativa, converte para absoluta
                if (url.startsWith("//")) {
                    url = "https:$url"
                } else if (url.startsWith("/")) {
                    url = getBaseUrl(baseUrl) + url
                }
                
                if (isValidVideoUrl(url)) {
                    videoUrls.add(url)
                    println("   Found: ${url.take(60)}...")
                }
            }
        }
        
        // 2. Extrair e analisar scripts JavaScript
        val scriptPattern = Regex("""<script[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
        val scripts = scriptPattern.findAll(html).map { it.groupValues[1] }.toList()
        
        println("📜 Found ${scripts.size} scripts to analyze")
        
        for (script in scripts) {
            // Procurar configurações de players comuns
            val playerPatterns = listOf(
                // JWPlayer patterns
                Regex("""["']file["']\s*:\s*["']([^"']+)["']"""),
                Regex("""["']sources["']\s*:\s*\[[^\]]*["']([^"']+)["']"""),
                Regex("""setup\s*\(\s*\{[^}]*["']file["']\s*:\s*["']([^"']+)["']"""),
                
                // VideoJS patterns
                Regex("""["']src["']\s*:\s*["']([^"']+)["']"""),
                Regex("""src\s*\(\s*["']([^"']+)["']\s*\)"""),
                
                // HLS.js patterns
                Regex("""loadSource\s*\(\s*["']([^"']+)["']\s*\)"""),
                Regex("""["']url["']\s*:\s*["']([^"']+)["']"""),
                
                // Generic video URL patterns in JS
                Regex("""(https?://[^"'\s]+/.*?\.m3u8[^"'\s]*)"""),
                Regex("""(https?://[^"'\s]+/.*?\.mp4[^"'\s]*)"""),
                Regex("""video\s*\.\s*src\s*=\s*["']([^"']+)["']""")
            )
            
            for (pattern in playerPatterns) {
                val matches = pattern.findAll(script)
                matches.forEach { match ->
                    var url = match.groupValues[1]
                    
                    if (isValidVideoUrl(url)) {
                        videoUrls.add(url)
                        println("   Found in JS: ${url.take(60)}...")
                    }
                }
            }
        }
        
        return videoUrls.distinct()
    }
    
    private fun getBaseUrl(url: String): String {
        return try {
            val urlObj = java.net.URL(url)
            "${urlObj.protocol}://${urlObj.host}"
        } catch (e: Exception) {
            "https://superflix21.lol"
        }
    }
    
    private fun isValidVideoUrl(url: String): Boolean {
        if (!url.startsWith("http")) return false
        
        // Verifica extensões de vídeo
        val videoExtensions = listOf(
            ".m3u8", ".mp4", ".mkv", ".webm", ".avi", ".mov", ".flv", ".wmv", ".m4v"
        )
        
        if (videoExtensions.any { url.contains(it, ignoreCase = true) }) {
            return true
        }
        
        // Verifica domínios de CDN conhecidos
        val cdnDomains = listOf(
            "g9r6.com", "filemoon.sx", "fcdn.stream", "sxcdn.stream",
            "bysevepoin.com", "fembed.sx", "superflix21.lol"
        )
        
        if (cdnDomains.any { url.contains(it) }) {
            return true
        }
        
        return false
    }
    
    private suspend fun testAndExtractVideo(
        videoUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("   Testing: ${videoUrl.take(60)}...")
            
            // Se for M3U8
            if (videoUrl.contains(".m3u8")) {
                testM3u8Url(videoUrl, name, callback)
            } 
            // Se for MP4 ou vídeo direto
            else if (videoUrl.contains(".mp4") || videoUrl.contains(".mkv") || 
                     videoUrl.contains(".webm")) {
                testDirectVideoUrl(videoUrl, name, callback)
            }
            // Se for URL do player (iframe)
            else if (videoUrl.contains("bysevepoin.com") || videoUrl.contains("fembed.sx")) {
                // Tenta extrair do player recursivamente
                extractVideoLinks(videoUrl, videoUrl, name, callback)
            }
            else {
                false
            }
        } catch (e: Exception) {
            println("   ❌ Test failed: ${e.message}")
            false
        }
    }
    
    private suspend fun testM3u8Url(
        m3u8Url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Testa diferentes referers (WebVideoCaster tenta vários)
        val referers = listOf(
            "https://g9r6.com/",
            "https://superflix21.lol/",
            "https://bysevepoin.com/",
            "https://fembed.sx/",
            getBaseUrl(m3u8Url)
        )
        
        for (referer in referers) {
            try {
                println("   Trying referer: $referer")
                
                val headers = mutableMapOf(
                    "Referer" to referer,
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                    "Accept" to "*/*",
                    "Accept-Language" to "pt-BR"
                )
                
                // Adiciona Origin se possível
                if (referer.startsWith("https://")) {
                    headers["Origin"] = referer.removeSuffix("/")
                }
                
                val links = M3u8Helper.generateM3u8(
                    name,
                    m3u8Url,
                    referer,
                    headers = headers
                )
                
                if (links.isNotEmpty()) {
                    links.forEach(callback)
                    println("   ✅ M3U8 worked with referer: $referer")
                    return true
                }
            } catch (e: Exception) {
                // Silently continue to next referer
            }
        }
        
        return false
    }
    
    private suspend fun testDirectVideoUrl(
        videoUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // Testa se o vídeo é acessível
            val request = Request.Builder()
                .url(videoUrl)
                .head() // Usa HEAD para verificar sem baixar o arquivo inteiro
                .header("Range", "bytes=0-1") // Pega apenas os primeiros bytes
                .build()
            
            val response = okHttpClient.newCall(request).execute()
            val success = response.isSuccessful
            response.close()
            
            if (success) {
                callback(
                    ExtractorLink(
                        name = "SuperFlix",
                        url = videoUrl,
                        referer = getBaseUrl(videoUrl),
                        quality = Qualities.Unknown.value,
                        type = INFER_TYPE
                    )
                )
                println("   ✅ Direct video URL works!")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
}
