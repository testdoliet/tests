package com.SuperFlix

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app

class YouTubeTrailerExtractor : ExtractorApi() {
    override val name = "YouTube HD"
    override val mainUrl = "https://www.youtube.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            println("🔍 YouTube Extractor: $url")
            
            // SIMPLES: Se a URL já for M3U8, usa direto
            if (url.contains(".m3u8")) {
                val headers = mapOf(
                    "Referer" to "https://www.youtube.com/",
                    "Origin" to "https://www.youtube.com"
                )
                
                M3u8Helper.generateM3u8(
                    name,
                    url,
                    mainUrl,
                    headers = headers
                ).forEach(callback)
            }
            
        } catch (e: Exception) {
            println("❌ Erro: ${e.message}")
        }
    }
}
