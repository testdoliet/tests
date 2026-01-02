package com.CineAgora

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

object CineAgoraExtractor {
    private const val TAG = "CineAgoraExtractor"
    private const val BASE_PLAYER = "https://watch.brplayer.cc"
    private const val REFERER = "https://cineagora.net/"

    suspend fun extractVideoLinks(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val html = app.get(url, referer = REFERER).text

            // 1. Fallback direto: master.txt na página
            val directMaster = Regex("""['"](https?://[^'"]*master\.txt[^'"]*)['"]""").find(html)
            if (directMaster != null) {
                val masterUrl = directMaster.groupValues[1]
                M3u8Helper.generateM3u8(
                    source = "CineAgora",
                    streamUrl = masterUrl,
                    referer = url
                ).forEach { callback(it) }
                return true
            }

            // 2. Método clássico uid/md5
            val uid = extract(html, "\"uid\"\\s*:\\s*\"(\\d+)\"")
            val md5 = extract(html, "\"md5\"\\s*:\\s*\"([a-f0-9]{32})\"")
            val videoId = extract(html, "\"id\"\\s*:\\s*\"(\\d+)\"")
            val status = extract(html, "\"status\"\\s*:\\s*\"([01])\"") ?: "1"

            if (uid != null && md5 != null && videoId != null) {
                val masterUrl = "$BASE_PLAYER/m3u8/$uid/$md5/master.txt?s=1&id=$videoId&cache=$status"
                val altUrl = "$BASE_PLAYER/alternative_stream/$uid/$md5/master.m3u8"

                val headers = mapOf("Referer" to url, "Origin" to BASE_PLAYER)

                val links = try {
                    M3u8Helper.generateM3u8(
                        source = "CineAgora",
                        streamUrl = masterUrl,
                        referer = url,
                        headers = headers
                    )
                } catch (e: Exception) {
                    emptyList()
                }

                if (links.isNotEmpty()) {
                    links.forEach { callback(it) }
                    return true
                }

                // Fallback manual
                newExtractorLink(
                    source = "CineAgora",
                    name = name,
                    url = masterUrl,
                    referer = url
                ) {
                    this.quality = Qualities.Unknown.value
                    this.isM3u8 = true
                    this.headers = headers
                }.let(callback)

                newExtractorLink(
                    source = "CineAgora (Alt)",
                    name = "$name (Alt)",
                    url = altUrl,
                    referer = url
                ) {
                    this.quality = Qualities.Unknown.value
                    this.isM3u8 = true
                    this.headers = headers
                }.let(callback)

                return true
            }

            return false
        } catch (e: Exception) {
            return false
        }
    }

    private fun extract(text: String, pattern: String): String? {
        return Regex(pattern).find(text)?.groupValues?.get(1)
    }
}
