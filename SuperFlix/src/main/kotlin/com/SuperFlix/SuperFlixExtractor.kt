package com.SuperFlix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities

object SuperFlixExtractor {

    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // Configura o WebViewResolver para interceptar links de stream
            val streamResolver = WebViewResolver(
                interceptUrl = Regex("""\.(m3u8|mp4|mkv)"""),
                useOkhttp = false,
                timeout = 15_000L
            )

            val intercepted = app.get(url, interceptor = streamResolver).url

            if (intercepted.isNotEmpty()) {
                val headers = mapOf(
                    "Accept" to "*/*",
                    "Connection" to "keep-alive",
                    "Referer" to url,
                    "Origin" to mainUrl,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )

                if (intercepted.contains(".m3u8")) {
                    // Para M3U8, usa o M3u8Helper
                    M3u8Helper.generateM3u8(
                        source = name,
                        streamUrl = intercepted,
                        referer = mainUrl,
                        headers = headers
                    ).forEach(callback)
                } else if (intercepted.contains(".mp4") || intercepted.contains(".mkv")) {
                    // Para links diretos MP4/MKV
                    val quality = extractQualityFromUrl(intercepted)
                    
                    // Cria um ExtractorLink simples para o vídeo direto
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = "SuperFlix",
                            url = intercepted,
                            referer = url,
                            quality = quality,
                            isM3u8 = false
                        )
                    )
                }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun extractQualityFromUrl(url: String): Int {
        return when {
            url.contains("1080p", ignoreCase = true) -> Qualities.P1080.value
            url.contains("720p", ignoreCase = true) -> Qualities.P720.value
            url.contains("480p", ignoreCase = true) -> Qualities.P480.value
            url.contains("360p", ignoreCase = true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}