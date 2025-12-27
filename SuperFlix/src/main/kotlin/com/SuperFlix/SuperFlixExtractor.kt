package com.SuperFlix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.jsoup.Jsoup

object SuperFlixExtractor {
    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔍 SuperFlixExtractor processando: $url")
        
        return try {
            // PRIMEIRA TENTATIVA: Método rápido SEM WebView
            val fastSuccess = tryFastExtraction(url, name, callback)
            if (fastSuccess) {
                println("✅ Método rápido funcionou!")
                return true
            }
            
            println("⚠️  Método rápido falhou, tentando WebView...")
            
            // SEGUNDA TENTATIVA: WebView com timeout maior
            val streamResolver = WebViewResolver(
                interceptUrl = Regex("""\.m3u8"""),
                useOkhttp = false,
                timeout = 45_000L  // 45 segundos para contornar os ads
            )

            val response = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to getUserAgent(),
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
                    "Referer" to mainUrl
                ),
                interceptor = streamResolver
            )
            
            val intercepted = response.url

            if (intercepted.isNotEmpty() && intercepted.contains(".m3u8")) {
                println("✅ M3U8 encontrado após ads: $intercepted")
                
                val headers = mapOf(
                    "Referer" to url,
                    "Origin" to mainUrl,
                    "User-Agent" to getUserAgent(),
                    "Accept" to "*/*",
                    "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8"
                )

                M3u8Helper.generateM3u8(
                    name,
                    intercepted,
                    mainUrl,
                    headers = headers
                ).forEach(callback)

                true
            } else {
                println("❌ Nenhum M3U8 encontrado")
                false
            }
        } catch (e: Exception) {
            println("❌ Erro no extrator: ${e.message}")
            false
        }
    }
    
    /**
     * Tenta extração RÁPIDA sem WebView
     */
    private suspend fun tryFastExtraction(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // 1. Tenta encontrar ID do Fembed
            val fembedId = extractFembedId(url)
            if (fembedId == null) {
                println("⚠️  Não é URL do Fembed")
                return false
            }
            
            println("🔍 ID Fembed encontrado: $fembedId")
            
            // 2. Tenta diferentes APIs do Fembed
            val apiUrls = listOf(
                "https://fembed.sx/api/source/$fembedId",
                "https://www.fembed.com/api/source/$fembedId",
                "https://fembed.com/api/source/$fembedId",
                "https://www.fembed.sx/api/source/$fembedId"
            )
            
            for (apiUrl in apiUrls) {
                try {
                    println("📡 Tentando API: $apiUrl")
                    
                    val response = app.post(
                        apiUrl,
                        headers = mapOf(
                            "Referer" to url,
                            "User-Agent" to getUserAgent(),
                            "X-Requested-With" to "XMLHttpRequest",
                            "Accept" to "application/json, text/javascript, */*; q=0.01"
                        ),
                        data = mapOf("r" to "")
                    )
                    
                    val jsonString = response.text
                    println("📄 Resposta da API: ${jsonString.take(200)}...")
                    
                    // Parse manual do JSON (mais confiável)
                    val m3u8Urls = extractM3u8UrlsFromJson(jsonString)
                    
                    if (m3u8Urls.isNotEmpty()) {
                        println("✅ ${m3u8Urls.size} links M3U8 encontrados")
                        
                        m3u8Urls.forEachIndexed { index, m3u8Url ->
                            println("   ${index + 1}. $m3u8Url")
                            
                            M3u8Helper.generateM3u8(
                                "$name (${getQualityName(index)})",
                                m3u8Url,
                                "https://fembed.sx",
                                headers = mapOf(
                                    "Referer" to "https://fembed.sx/",
                                    "User-Agent" to getUserAgent()
                                )
                            ).forEach(callback)
                        }
                        
                        return true
                    }
                } catch (e: Exception) {
                    println("⚠️  API falhou ($apiUrl): ${e.message}")
                }
            }
            
            false
        } catch (e: Exception) {
            println("❌ Erro no método rápido: ${e.message}")
            false
        }
    }
    
    /**
     * Extrai URLs M3U8 do JSON da API do Fembed
     */
    private fun extractM3u8UrlsFromJson(jsonString: String): List<String> {
        val urls = mutableListOf<String>()
        
        try {
            // Método 1: Regex simples
            val regex = Regex(""""file"\s*:\s*"([^"]+\.m3u8[^"]*)"""")
            val matches = regex.findAll(jsonString)
            
            matches.forEach { match ->
                val url = match.groupValues[1]
                if (url.isNotEmpty() && !urls.contains(url)) {
                    urls.add(url)
                }
            }
            
            // Método 2: Procura por m3u8 em qualquer parte do JSON
            if (urls.isEmpty()) {
                val m3u8Regex = Regex("""https?://[^"\s]+\.m3u8[^"\s]*""")
                val allMatches = m3u8Regex.findAll(jsonString)
                
                allMatches.forEach { match ->
                    val url = match.value
                    if (url.isNotEmpty() && !urls.contains(url)) {
                        urls.add(url)
                    }
                }
            }
        } catch (e: Exception) {
            println("⚠️  Erro ao extrair URLs do JSON: ${e.message}")
        }
        
        return urls
    }
    
    /**
     * Extrai ID do Fembed de várias formas de URL
     */
    private fun extractFembedId(url: String): String? {
        val patterns = listOf(
            """fembed\.(?:com|sx|net|org)/[evf]/([^/?]+)""",
            """fembed\.(?:com|sx|net|org)/v/([^/?]+)""",
            """fembed\.(?:com|sx|net|org)/f/([^/?]+)""",
            """fembed\.(?:com|sx|net|org)/e/([^/?]+)"""
        )
        
        for (pattern in patterns) {
            try {
                val regex = pattern.toRegex(RegexOption.IGNORE_CASE)
                val match = regex.find(url)
                if (match != null) {
                    return match.groupValues[1]
                }
            } catch (e: Exception) {
                // Continua para o próximo padrão
            }
        }
        
        // Tenta método simples
        return url.substringAfterLast("/").substringBefore("?").takeIf { it.isNotEmpty() && it != url }
    }
    
    /**
     * Nome da qualidade baseado no índice
     */
    private fun getQualityName(index: Int): String {
        return when (index) {
            0 -> "1080p"
            1 -> "720p" 
            2 -> "480p"
            3 -> "360p"
            4 -> "240p"
            else -> "SD"
        }
    }
    
    private fun getUserAgent(): String {
        // User-Agent mobile pode carregar menos ads
        return "Mozilla/5.0 (Linux; Android 13; SM-G991B) " +
               "AppleWebKit/537.36 (KHTML, like Gecko) " +
               "Chrome/120.0.6099.144 Mobile Safari/537.36"
    }
}
