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
        val initialHeaders = mapOf("Referer" to "https://nexflix.vip/")
        
        try {
            // 1. Tenta extrair o ID direto da URL (se for player.php ou /e/)
            var videoId = extractVideoId(url)

            // 2. Se não achou ID, baixa o HTML para procurar o iframe
            if (videoId == null) {
                println("[NexFlix] ID não encontrado na URL. Baixando HTML...")
                val response = app.get(url, headers = initialHeaders).text
                
                // Procura o iframe (pode ser nexembed ou comprarebom)
                val iframeUrl = Regex("""(?:src|data-src)=["'](https?://[^"']+)["']""").find(response)?.groupValues?.get(1)
                
                if (iframeUrl != null) {
                    videoId = extractVideoId(iframeUrl)
                }
            }

            if (videoId.isNullOrBlank()) {
                println("[NexFlix] ERRO: ID do vídeo não encontrado.")
                return
            }

            // === O PULO DO GATO: DOMÍNIO FIXO ===
            // Não importa se o iframe é nexembed.xyz, a API é SEMPRE comprarebom.xyz
            val fixedDomain = "https://comprarebom.xyz"
            val apiUrl = "$fixedDomain/player/index.php?data=$videoId&do=getVideo"

            println("[NexFlix] ID: $videoId | API Forçada: $apiUrl")

            // Headers simulando estar no comprarebom.xyz
            val apiHeaders = mapOf(
                "Host" to "comprarebom.xyz",
                "Referer" to "$fixedDomain/e/$videoId",
                "Origin" to fixedDomain,
                "X-Requested-With" to "XMLHttpRequest",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )

            val body = mapOf(
                "hash" to videoId,
                "r" to "https://nexflix.vip/"
            )

            // Faz o POST
            val jsonResponse = app.post(apiUrl, headers = apiHeaders, data = body).text
            
            // Verifica se a resposta é HTML (erro)
            if (jsonResponse.trim().startsWith("<")) {
                println("[NexFlix] ERRO: API retornou HTML. Resposta inválida.")
                return
            }

            val json = mapper.readTree(jsonResponse)
            val videoLink = json["securedLink"]?.asText()?.takeIf { it.isNotBlank() } 
                         ?: json["videoSource"]?.asText()

            if (!videoLink.isNullOrBlank()) {
                println("[NexFlix] Link seguro encontrado: $videoLink")
                
                // Headers para o player (ExoPlayer)
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
                    val fallbackLink = newExtractorLink(
                        source = "NexFlix",
                        name = "NexFlix (Original)",
                        url = videoLink,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$fixedDomain/"
                        this.quality = Qualities.Unknown.value
                        this.headers = videoHeaders
                    }
                    callback(fallbackLink)
                }
            } else {
                println("[NexFlix] JSON válido mas sem 'securedLink' ou 'videoSource'.")
            }

        } catch (e: Exception) {
            println("[NexFlix] Erro Crítico: ${e.message}")
            e.printStackTrace()
        }
    }

    // Função auxiliar para limpar a extração de ID
    private fun extractVideoId(url: String): String? {
        return when {
            url.contains("id=") -> url.substringAfter("id=").substringBefore("&")
            url.contains("/e/") -> url.substringAfter("/e/").substringBefore("?")
            else -> null
        }
    }
}
