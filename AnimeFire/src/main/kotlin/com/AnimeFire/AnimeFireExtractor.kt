package com.AnimeFire

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

object AnimeFireExtractor {

    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🔗 AnimeFireExtractor: Extraindo de $url")

            val streamResolver = WebViewResolver(
                interceptUrl = Regex("""\.mp4(?:\?|$)"""), // Só intercepta .mp4
                useOkhttp = false,
                timeout = 15_000L
            )

            val response = app.get(url, interceptor = streamResolver)
            val intercepted = response.url

            println("🌐 AnimeFireExtractor: URL interceptada: $intercepted")

            if (intercepted.isNotEmpty() && intercepted.contains(".mp4")) {
                println("✅ AnimeFireExtractor: MP4 encontrado: $intercepted")
                
                // Determinar qualidade usando Qualities
                val quality = when {
                    intercepted.contains("1080") -> Qualities.FullHd.value
                    intercepted.contains("720") -> Qualities.HD.value
                    intercepted.contains("480") -> Qualities.SD.value
                    intercepted.contains("360") -> Qualities.P360.value
                    intercepted.contains("240") -> Qualities.P240.value
                    else -> Qualities.Unknown.value
                }
                
                // Usa o mesmo construtor que o SuperFlixExtractor
                callback(
                    ExtractorLink(
                        name,            // source
                        name,            // name  
                        intercepted,     // url
                        "$mainUrl/",     // referer
                        quality,         // quality
                        false,           // isM3u8 (MP4 é false!)
                        headers = mapOf(
                            "Referer" to url,
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                        )
                    )
                )
                true
            } else {
                println("❌ AnimeFireExtractor: Nenhum link MP4 encontrado")
                false
            }
        } catch (e: Exception) {
            println("💥 AnimeFireExtractor: Erro - ${e.message}")
            false
        }
    }
}
