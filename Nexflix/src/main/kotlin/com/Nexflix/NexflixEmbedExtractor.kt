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
        println("[NexFlix] === Iniciando Extração ===")
        
        try {
            var videoId: String? = null

            // PASSO 1: Obter o ID do vídeo
            // Se a URL já tiver o ID (ex: player.php?id=tt123), pegamos direto
            if (url.contains("id=") || url.contains("/e/")) {
                videoId = extractId(url)
            } 
            
            // Se não, baixamos a página para achar o link do player (data-src ou src)
            if (videoId == null) {
                val response = app.get(url, headers = mapOf("Referer" to "https://nexflix.vip/")).text
                val playerLink = Regex("""(?:src|data-src)=["']([^"']+)["']""").find(response)?.groupValues?.get(1)
                
                if (playerLink != null) {
                    videoId = extractId(playerLink)
                }
            }

            if (videoId.isNullOrBlank()) {
                println("[NexFlix] ERRO: ID do vídeo não encontrado.")
                return
            }

            // PASSO 2: Fazer o POST no CompraReBom (Domínio Fixo)
            // Não importa se o iframe era nexembed.xyz, a API JSON é comprarebom.xyz
            val fixedDomain = "https://comprarebom.xyz"
            val apiUrl = "$fixedDomain/player/index.php?data=$videoId&do=getVideo"

            println("[NexFlix] ID: $videoId -> API: $apiUrl")

            val apiHeaders = mapOf(
                "Host" to "comprarebom.xyz",
                "Referer" to "$fixedDomain/e/$videoId", // Referer simulando o player
                "Origin" to fixedDomain,
                "X-Requested-With" to "XMLHttpRequest",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )

            val body = mapOf(
                "hash" to videoId,
                "r" to "https://nexflix.vip/"
            )

            val jsonResponse = app.post(apiUrl, headers = apiHeaders, data = body).text
            
            // Verifica se a resposta é HTML (erro)
            if (jsonResponse.trim().startsWith("<")) {
                println("[NexFlix] ERRO: API retornou HTML. O servidor pode ter bloqueado ou o ID expirou.")
                return
            }

            val json = mapper.readTree(jsonResponse)
            val videoLink = json["securedLink"]?.asText()?.takeIf { it.isNotBlank() } 
                         ?: json["videoSource"]?.asText()

            if (!videoLink.isNullOrBlank()) {
                println("[NexFlix] Link Seguro Encontrado: $videoLink")
                
                // Headers cruciais para o Player tocar o m3u8
                val videoHeaders = mapOf(
                    "Referer" to "$fixedDomain/",
                    "Origin" to fixedDomain,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )

                val m3u8Links = M3u8Helper.generateM3u8(
                    source = "NexFlix",
                    streamUrl = videoLink,
                    referer = "$fixedDomain/",
                    headers = videoHeaders
                )

                if (m3u8Links.isNotEmpty()) {
                    m3u8Links.forEach { callback(it) }
                } else {
                    callback(
                        ExtractorLink(
                            source = "NexFlix",
                            name = "NexFlix (Original)",
                            url = videoLink,
                            referer = "$fixedDomain/",
                            quality = Qualities.Unknown.value,
                            isM3u8 = true,
                            headers = videoHeaders
                        )
                    )
                }
            } else {
                println("[NexFlix] ERRO: JSON não contém link de vídeo.")
            }

        } catch (e: Exception) {
            println("[NexFlix] Erro Crítico: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun extractId(url: String): String? {
        return when {
            url.contains("id=") -> url.substringAfter("id=").substringBefore("&")
            url.contains("/e/") -> url.substringAfter("/e/").substringBefore("?")
            else -> null
        }
    }
}
