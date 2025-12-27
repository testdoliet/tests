package com.SuperFlix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import java.util.concurrent.CopyOnWriteArrayList

object SuperFlixExtractor {
    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // Lista para armazenar TODAS as URLs interceptadas
            val interceptedUrls = CopyOnWriteArrayList<String>()
            
            val streamResolver = WebViewResolver(
                interceptUrl = { interceptedUrl ->
                    // Coleta TODAS as URLs que batem no regex
                    if (interceptedUrl.contains(".m3u8") || 
                        interceptedUrl.contains(".mp4") || 
                        interceptedUrl.contains(".mkv")) {
                        
                        interceptedUrls.add(interceptedUrl)
                        // println("[DEBUG] URL interceptada: $interceptedUrl") // Para debug
                    }
                    false // SEMPRE retorna false para continuar coletando
                },
                useOkhttp = false,
                timeout = 12_000L // Dá tempo para coletar várias URLs
            )

            val response = app.get(url, interceptor = streamResolver)
            
            // Agora analisa todas as URLs coletadas
            if (interceptedUrls.isNotEmpty()) {
                // Estratégia 1: Pega a ÚLTIMA URL (provavelmente o vídeo real)
                val lastUrl = interceptedUrls.lastOrNull()
                
                // Estratégia 2: Pega a maior URL (m3u8 geralmente é maior)
                val largestUrl = interceptedUrls.maxByOrNull { it.length }
                
                // Estratégia 3: Filtra URLs suspeitas de ads
                val filteredUrls = interceptedUrls.filterNot { url ->
                    url.contains("ad") || 
                    url.contains("banner") || 
                    url.contains("popup") ||
                    url.contains("track") ||
                    url.length < 50 // URLs muito curtas são suspeitas
                }
                
                // Tenta usar a última URL filtrada
                val finalUrl = filteredUrls.lastOrNull() ?: lastUrl ?: largestUrl
                
                if (finalUrl != null && finalUrl.contains(".m3u8")) {
                    val headers = mapOf(
                        "Referer" to url,
                        "Origin" to mainUrl,
                        "User-Agent" to "Mozilla/5.0"
                    )

                    M3u8Helper.generateM3u8(
                        name,
                        finalUrl,
                        mainUrl,
                        headers = headers
                    ).forEach(callback)
                    
                    // println("[DEBUG] Usando URL: $finalUrl") // Para debug
                    return true
                }
            }
            
            false
        } catch (e: Exception) {
            false
        }
    }
}
