package com.AnimeFire

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType

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
                
                // Determinar qualidade
                val quality = when {
                    intercepted.contains("1080") -> 1080
                    intercepted.contains("720") -> 720
                    intercepted.contains("480") -> 480
                    intercepted.contains("360") -> 360
                    intercepted.contains("240") -> 240
                    else -> 0
                }
                
                // Corrigindo o newExtractorLink
                callback.invoke(
                    newExtractorLink(
                        source = name,  // ← source, não sourceName
                        name = name,
                        url = intercepted,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = quality
                        this.headers = mapOf(
                            "Referer" to url,
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                        )
                    }
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
