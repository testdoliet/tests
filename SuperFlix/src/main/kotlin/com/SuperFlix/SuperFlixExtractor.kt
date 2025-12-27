package com.SuperFlix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

object SuperFlixExtractor {
    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🎬 SuperFlixExtractor - Versão Simplificada")
        
        // Usa um Set para armazenar URLs únicas
        val interceptedM3u8 = mutableSetOf<String>()
        
        // Cria um listener simples para capturar M3U8
        val streamResolver = WebViewResolver(
            interceptUrl = Regex("""\.m3u8"""),  // Só intercepta M3U8
            useOkhttp = false,
            timeout = 120_000L  // 2 minutos - tempo suficiente
        )

        return try {
            println("🌐 Iniciando navegação...")
            
            // Acessa a URL e tenta interceptar
            val response = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
                ),
                interceptor = streamResolver
            )
            
            // O WebViewResolver já interceptou URLs durante a navegação
            // Vamos tentar pegar o M3U8 da URL final
            val finalUrl = response.url
            println("📍 URL final: $finalUrl")
            
            // Tenta encontrar M3U8 na resposta ou na URL final
            val m3u8Urls = findM3u8InResponse(response.text, finalUrl)
            
            if (m3u8Urls.isNotEmpty()) {
                println("✅ Encontrados ${m3u8Urls.size} links M3U8")
                
                // Escolhe o melhor M3U8
                val bestM3u8 = selectBestM3u8(m3u8Urls)
                println("🎯 Melhor M3U8: $bestM3u8")
                
                // Gera os links
                M3u8Helper.generateM3u8(
                    name,
                    bestM3u8,
                    "https://bysevepoin.com",
                    headers = mapOf(
                        "Referer" to "https://bysevepoin.com/",
                        "User-Agent" to "Mozilla/5.0"
                    )
                ).forEach(callback)
                
                return true
            }
            
            println("❌ Nenhum M3U8 encontrado")
            false
            
        } catch (e: Exception) {
            println("❌ Erro: ${e.message}")
            false
        }
    }
    
    /**
     * Encontra URLs M3U8 no HTML da resposta
     */
    private fun findM3u8InResponse(html: String, finalUrl: String): List<String> {
        val urls = mutableListOf<String>()
        
        // 1. Procura por M3U8 no HTML
        val m3u8Regex = Regex("""(https?://[^"\s]+\.m3u8[^"\s]*)""")
        val matches = m3u8Regex.findAll(html)
        
        matches.forEach { match ->
            val url = match.value
            if (isValidM3u8(url)) {
                urls.add(url)
                println("🔍 M3U8 encontrado no HTML: $url")
            }
        }
        
        // 2. Se a URL final for M3U8, adiciona
        if (finalUrl.contains(".m3u8") && isValidM3u8(finalUrl)) {
            urls.add(finalUrl)
            println("🔍 URL final é M3U8: $finalUrl")
        }
        
        // 3. Tenta construir URLs com base em padrões conhecidos
        if (urls.isEmpty() && finalUrl.contains("bysevepoin.com")) {
            val possibleUrls = generatePossibleM3u8Urls(finalUrl)
            urls.addAll(possibleUrls)
        }
        
        return urls.distinct()
    }
    
    /**
     * Verifica se é um M3U8 válido (não é ad)
     */
    private fun isValidM3u8(url: String): Boolean {
        return !(url.contains("ads") || 
                url.contains("doubleclick") || 
                url.contains("google") ||
                url.contains("analytics"))
    }
    
    /**
     * Gera URLs M3U8 possíveis baseadas na URL do bysevepoin
     */
    private fun generatePossibleM3u8Urls(bysevepoinUrl: String): List<String> {
        val urls = mutableListOf<String>()
        
        try {
            // Extrai o ID único da URL
            val pattern = """bysevepoin\.com/e/([^/]+)""".toRegex()
            val match = pattern.find(bysevepoinUrl)
            val videoId = match?.groupValues?.get(1) ?: return urls
            
            println("🔑 Video ID extraído: $videoId")
            
            // URLs possíveis baseadas nos padrões que vimos antes
            val possiblePatterns = listOf(
                "https://be2719.rcr22.ams01.i8yz83pn.com/hls2/05/10459/${videoId}_h/master.m3u8",
                "https://be2719.rcr22.ams01.i8yz83pn.com/hls2/05/10459/${videoId}_h/iframes-v1-a1.m3u8",
                "https://cdn.bysevepoin.com/videos/$videoId/master.m3u8",
                "https://stream.bysevepoin.com/$videoId/master.m3u8",
                "https://video.bysevepoin.com/$videoId/playlist.m3u8"
            )
            
            urls.addAll(possiblePatterns)
            println("🧪 Geradas ${urls.size} URLs possíveis")
            
        } catch (e: Exception) {
            println("⚠️ Erro ao gerar URLs: ${e.message}")
        }
        
        return urls
    }
    
    /**
     * Seleciona o melhor M3U8 da lista
     */
    private fun selectBestM3u8(urls: List<String>): String {
        if (urls.isEmpty()) return ""
        
        // Ordena por qualidade (1080p > 720p > master > iframes > outros)
        return urls.sortedByDescending { url ->
            when {
                url.contains("1080") -> 5
                url.contains("720") -> 4
                url.contains("master.m3u8") -> 3
                url.contains("iframes") -> 2
                else -> 1
            }
        }.first()
    }
}
