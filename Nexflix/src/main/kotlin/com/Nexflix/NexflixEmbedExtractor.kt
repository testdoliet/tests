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
import java.net.URI

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
        println("[NexFlix] === URL Recebida: $url")

        // 1. Se a URL for do NexFlix (player.php), precisamos abrir para achar o link real
        if (url.contains("nexflix.vip") || url.contains("/player.php")) {
            println("[NexFlix] Detectado link interno. Baixando HTML para achar o iframe real...")
            try {
                val response = app.get(url, headers = mapOf("Referer" to "https://nexflix.vip/")).text
                
                // Busca o link do player real (comprarebom ou nexembed)
                val realIframe = Regex("""(?:src|data-src)=["'](https?://(?:comprarebom|nexembed)[^"']+)["']""").find(response)?.groupValues?.get(1)
                
                if (realIframe != null) {
                    println("[NexFlix] Iframe real encontrado: $realIframe")
                    // Agora sim chamamos a API com o link do servidor de vídeo
                    extractFromApi(realIframe, "https://nexflix.vip/", callback)
                } else {
                    println("[NexFlix] ERRO: Nenhum iframe encontrado no HTML.")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return
        }

        // 2. Se a URL já for do servidor de vídeo, vai direto
        extractFromApi(url, "https://nexflix.vip/", callback)
    }

    private suspend fun extractFromApi(playerUrl: String, originalReferer: String, callback: (ExtractorLink) -> Unit) {
        try {
            println("[NexFlix] Iniciando extração via API para: $playerUrl")

            // Extrai o ID real do vídeo (hash ou tt...)
            val videoId = if (playerUrl.contains("id=")) {
                playerUrl.substringAfter("id=").substringBefore("&")
            } else {
                playerUrl.substringAfter("/e/").substringBefore("?")
            }

            // Pega o domínio correto (ex: comprarebom.xyz)
            val uri = URI(playerUrl)
            val domainUrl = "https://${uri.host}" 
            val apiUrl = "$domainUrl/player/index.php?data=$videoId&do=getVideo"

            println("[NexFlix] ID: $videoId | API: $apiUrl")

            // Headers simulando o player
            val apiHeaders = mapOf(
                "Host" to uri.host,
                "Referer" to "$domainUrl/e/$videoId",
                "Origin" to domainUrl,
                "X-Requested-With" to "XMLHttpRequest",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )

            // Headers para o vídeo (M3U8)
            val videoHeaders = mapOf(
                "Referer" to "$domainUrl/",
                "Origin" to domainUrl,
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )

            val body = mapOf(
                "hash" to videoId,
                "r" to originalReferer
            )

            // POST na API correta
            val jsonResponse = app.post(apiUrl, headers = apiHeaders, data = body).text
            
            // Debug da resposta (para ver se veio JSON mesmo)
            if (jsonResponse.trim().startsWith("<")) {
                println("[NexFlix] ERRO: A API retornou HTML em vez de JSON. Provavelmente o ID ou Domínio está errado.")
                return
            }

            val json = mapper.readTree(jsonResponse)
            val videoLink = json["securedLink"]?.asText()?.takeIf { it.isNotBlank() } 
                         ?: json["videoSource"]?.asText()

            if (!videoLink.isNullOrBlank()) {
                println("[NexFlix] Link encontrado: $videoLink")
                
                val m3u8Links = M3u8Helper.generateM3u8(
                    source = "NexFlix",
                    streamUrl = videoLink,
                    referer = "$domainUrl/",
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
                        this.referer = "$domainUrl/"
                        this.quality = Qualities.Unknown.value
                        this.headers = videoHeaders
                    }
                    callback(fallbackLink)
                }
            } else {
                println("[NexFlix] ERRO: JSON válido, mas sem link de vídeo.")
            }

        } catch (e: Exception) {
            println("[NexFlix] Exceção: ${e.message}")
            e.printStackTrace()
        }
    }
}
