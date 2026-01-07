package com.Nexflix

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile // <--- IMPORT CRÍTICO ADICIONADO
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType

class NexEmbedExtractor : ExtractorApi() {
    override val name = "NexEmbed"
    override val mainUrl = "https://nexembed.xyz"
    override val requiresReferer = true

    // A assinatura agora vai bater porque importamos SubtitleFile corretamente
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf("Referer" to "https://nexflix.vip/")
        
        try {
            val response = app.get(url, headers = headers).text
            
            val linkRedirect = Regex("""src=["'](https?://(?:comprarebom|nexembed)[^"']+)["']""").find(response)?.groupValues?.get(1)
                ?: Regex("""data-src=["'](https?://(?:comprarebom|nexembed)[^"']+)["']""").find(response)?.groupValues?.get(1)

            if (linkRedirect != null) {
                extractFromRedirect(linkRedirect, url, callback)
            } else {
                extractDirect(response, url, callback)
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun extractFromRedirect(url: String, originalReferer: String, callback: (ExtractorLink) -> Unit) {
        try {
            val headers = mapOf(
                "Referer" to originalReferer,
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Origin" to "https://comprarebom.xyz"
            )

            val response = app.get(url, headers = headers).text

            val linkVideo = Regex("""file:\s*["']([^"']+)["']""").find(response)?.groupValues?.get(1)
                ?: Regex("""["']([^"']+\.txt)["']""").find(response)?.groupValues?.get(1)
                ?: Regex("""["']([^"']+\.m3u8)["']""").find(response)?.groupValues?.get(1)

            if (linkVideo != null) {
                // 1. Tenta gerar todas as qualidades via M3u8Helper
                val m3u8Links = M3u8Helper.generateM3u8(
                    source = "NexFlix",
                    streamUrl = linkVideo,
                    referer = "https://comprarebom.xyz/",
                    headers = headers
                )

                if (m3u8Links.isNotEmpty()) {
                    m3u8Links.forEach { callback(it) }
                } else {
                    // 2. Fallback usando newExtractorLink (Agora funciona pois está dentro de suspend)
                    val fallbackLink = newExtractorLink(
                        source = "NexFlix",
                        name = "NexFlix (Original)",
                        url = linkVideo,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://comprarebom.xyz/"
                        this.quality = Qualities.Unknown.value
                        this.headers = headers
                    }
                    
                    callback(fallbackLink)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ADICIONADO 'suspend' AQUI PARA CORRIGIR O ERRO
    private suspend fun extractDirect(html: String, referer: String, callback: (ExtractorLink) -> Unit) {
        val link = Regex("""file:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
        if (link != null) {
            // newExtractorLink é suspend, então esta função também precisa ser suspend
            val directLink = newExtractorLink(
                source = "NexFlix",
                name = "NexFlix",
                url = link,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
            }
            callback(directLink)
        }
    }
}
