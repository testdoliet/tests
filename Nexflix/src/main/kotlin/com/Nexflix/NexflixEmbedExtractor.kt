package com.Nexflix

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

class NexEmbedExtractor : ExtractorApi() {
    override val name = "NexEmbed"
    override val mainUrl = "https://nexembed.xyz"
    override val requiresReferer = true

    private val mapper = jacksonObjectMapper()

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Headers iniciais
        val headers = mapOf("Referer" to "https://nexflix.vip/")
        
        try {
            var videoId: String? = null

            // 1. Extração do ID
            // Se já vier com ID na URL (do loadLinks)
            if (url.contains("id=") || url.contains("/e/")) {
                videoId = extractIdFromUrl(url)
            } 
            
            // Se não, baixamos o HTML do NexFlix para achar o link do player
            if (videoId == null) {
                val response = app.get(url, headers = headers).text
                // Procura links como nexembed.xyz/player.php?id=... ou comprarebom.xyz/e/...
                val playerUrl = Regex("""(?:src|data-src)=["']([^"']+)["']""").find(response)?.groupValues?.get(1)
                
                if (playerUrl != null) {
                    videoId = extractIdFromUrl(playerUrl)
                }
            }

            if (videoId.isNullOrBlank()) return

            // 2. Chamada para a API (SEMPRE no comprarebom.xyz)
            // Ignoramos se o iframe era nexembed, o backend é comprarebom.
            val targetDomain = "https://comprarebom.xyz"
            val apiUrl = "$targetDomain/player/index.php?data=$videoId&do=getVideo"

            val apiHeaders = mapOf(
                "Host" to "comprarebom.xyz",
                "Referer" to "$targetDomain/e/$videoId", // Referer fingindo ser o player
                "Origin" to targetDomain,
                "X-Requested-With" to "XMLHttpRequest",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )

            val body = mapOf(
                "hash" to videoId,
                "r" to "https://nexflix.vip/"
            )

            // 3. Faz o POST
            val jsonResponse = app.post(apiUrl, headers = apiHeaders, data = body).text
            
            // Verifica erro de HTML
            if (jsonResponse.trim().startsWith("<")) return 

            val json = mapper.readTree(jsonResponse)
            val videoLink = json["securedLink"]?.asText()?.takeIf { it.isNotBlank() } 
                         ?: json["videoSource"]?.asText()

            if (!videoLink.isNullOrBlank()) {
                
                // Headers para rodar o vídeo (m3u8)
                val videoHeaders = mapOf(
                    "Referer" to "$targetDomain/",
                    "Origin" to targetDomain,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )

                val m3u8Links = M3u8Helper.generateM3u8(
                    source = "NexFlix",
                    streamUrl = videoLink,
                    referer = "$targetDomain/",
                    headers = videoHeaders
                )

                if (m3u8Links.isNotEmpty()) {
                    m3u8Links.forEach { callback(it) }
                } else {
                    val fallbackLink = newExtractorLink(
                        source = "NexFlix",
                        name = "NexFlix (Auto)",
                        url = videoLink,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$targetDomain/"
                        this.quality = Qualities.Unknown.value
                        this.headers = videoHeaders
                    }
                    callback(fallbackLink)
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun extractIdFromUrl(url: String): String? {
        return if (url.contains("id=")) {
            // Caso: player.php?type=filme&id=tt27543632&...
            url.substringAfter("id=").substringBefore("&")
        } else if (url.contains("/e/")) {
            // Caso: .../e/tt27543632
            url.substringAfter("/e/").substringBefore("?")
        } else {
            null
        }
    }
}
