package com.AnimeFire

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver

object AnimeFireExtractor {
    
    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔗 AnimeFireExtractor: Extraindo MP4 de $url")
        
        return try {
            // Configura o WebViewResolver para interceptar MP4
            val streamResolver = WebViewResolver(
                interceptUrl = Regex("""\.mp4(?:\?|$)"""), // Só intercepta .mp4
                useOkhttp = false,
                timeout = 15_000L
            )
            
            // Faz a requisição
            val response = app.get(url, interceptor = streamResolver)
            val intercepted = response.url
            
            println("🌐 AnimeFireExtractor: URL interceptada: $intercepted")
            
            if (intercepted.contains(".mp4")) {
                println("✅ AnimeFireExtractor: MP4 encontrado!")
                
                // Determinar qualidade baseada na URL
                val quality = when {
                    intercepted.contains("1080") -> Qualities.FullHd.value
                    intercepted.contains("720") -> Qualities.HD.value
                    intercepted.contains("480") -> Qualities.SD.value
                    intercepted.contains("360") -> Qualities.P360.value
                    intercepted.contains("240") -> Qualities.P240.value
                    else -> Qualities.Unknown.value
                }
                
                // API NOVA - MP4 é ExtractorLinkType.VIDEO
                callback.invoke(
                    newExtractorLink(
                        sourceName = name,
                        name = name,
                        url = intercepted,
                        type = ExtractorLinkType.VIDEO  // ← IMPORTANTE: VIDEO para MP4
                    ) {
                        this.referer = mainUrl
                        this.quality = quality
                        // Headers para MP4 direto
                        this.headers = mapOf(
                            "Referer" to mainUrl,
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                        )
                    }
                )
                true
            } else {
                println("❌ AnimeFireExtractor: Nenhum MP4 encontrado")
                false
            }
            
        } catch (e: Exception) {
            println("💥 AnimeFireExtractor: Erro - ${e.message}")
            e.printStackTrace()
            false
        }
    }
}
