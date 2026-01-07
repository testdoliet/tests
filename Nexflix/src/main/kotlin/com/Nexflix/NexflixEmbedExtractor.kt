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
        println("[NexFlix] === Iniciando Extractor ===")
        println("[NexFlix] URL recebida: $url")

        // Headers para acessar a página inicial do player/iframe
        val initialHeaders = mapOf("Referer" to "https://nexflix.vip/")
        
        try {
            // Se a URL já for direta com ID (do player.php), pulamos o scrape do HTML
            if (url.contains("id=") || url.contains("/e/")) {
                println("[NexFlix] URL já contém ID. Indo direto para API.")
                extractFromApi(url, "https://nexflix.vip/", callback)
                return
            }

            println("[NexFlix] Baixando HTML para procurar iframe...")
            // Caso contrário, baixa o HTML para achar o iframe (fallback)
            val response = app.get(url, headers = initialHeaders).text
            
            val linkRedirect = Regex("""(?:src|data-src)=["'](https?://(?:comprarebom|nexembed)[^"']+)["']""").find(response)?.groupValues?.get(1)

            if (linkRedirect != null) {
                println("[NexFlix] Redirecionamento encontrado no HTML: $linkRedirect")
                extractFromApi(linkRedirect, url, callback)
            } else {
                println("[NexFlix] ERRO: Nenhum link nexembed/comprarebom encontrado no HTML.")
            }

        } catch (e: Exception) {
            println("[NexFlix] ERRO CRÍTICO no getUrl: ${e.message}")
            e.printStackTrace()
        }
    }

    private suspend fun extractFromApi(playerUrl: String, originalReferer: String, callback: (ExtractorLink) -> Unit) {
        try {
            println("[NexFlix] Processando URL da API: $playerUrl")

            // 1. Extração do ID e Domínio
            val videoId = if (playerUrl.contains("id=")) {
                playerUrl.substringAfter("id=").substringBefore("&")
            } else {
                playerUrl.substringAfter("/e/").substringBefore("?")
            }
            
            println("[NexFlix] ID Extraído: $videoId")

            if (videoId.isBlank()) {
                println("[NexFlix] ERRO: ID do vídeo vazio!")
                return
            }
            
            // Define domínio base (comprarebom.xyz)
            val uri = URI(playerUrl)
            val domainUrl = "https://${uri.host}" 
            val apiUrl = "$domainUrl/player/index.php?data=$videoId&do=getVideo"
            
            println("[NexFlix] URL da API montada: $apiUrl")

            // 2. Headers para o POST
            val apiHeaders = mapOf(
                "Host" to uri.host,
                "Referer" to "$domainUrl/e/$videoId",
                "Origin" to domainUrl,
                "X-Requested-With" to "XMLHttpRequest",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )

            // 3. Headers para o VÍDEO
            val videoHeaders = mapOf(
                "Referer" to "$domainUrl/", 
                "Origin" to domainUrl,
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )

            // 4. Corpo do POST
            val body = mapOf(
                "hash" to videoId,
                "r" to originalReferer
            )

            println("[NexFlix] Fazendo POST na API...")
            
            // 5. Faz o POST
            val jsonResponse = app.post(apiUrl, headers = apiHeaders, data = body).text
            
            println("[NexFlix] Resposta da API (Raw): $jsonResponse")

            val json = mapper.readTree(jsonResponse)

            // 6. Pega o Link Seguro
            val securedLink = json["securedLink"]?.asText()
            val videoSource = json["videoSource"]?.asText()
            
            println("[NexFlix] securedLink encontrado: $securedLink")
            println("[NexFlix] videoSource encontrado: $videoSource")

            val videoLink = securedLink?.takeIf { it.isNotBlank() } ?: videoSource
            
            if (!videoLink.isNullOrBlank()) {
                println("[NexFlix] Usando link final: $videoLink")
                println("[NexFlix] Gerando M3u8Helper...")

                // Tenta gerar qualidades
                val m3u8Links = M3u8Helper.generateM3u8(
                    source = "NexFlix",
                    streamUrl = videoLink,
                    referer = "$domainUrl/",
                    headers = videoHeaders
                )

                if (m3u8Links.isNotEmpty()) {
                    println("[NexFlix] Sucesso! ${m3u8Links.size} links gerados automaticamente.")
                    m3u8Links.forEach { callback(it) }
                } else {
                    println("[NexFlix] M3u8Helper não gerou links. Usando Fallback manual.")
                    // Fallback
                    val fallbackLink = newExtractorLink(
                        source = "NexFlix",
                        name = "NexFlix (Original)",
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
                println("[NexFlix] ERRO: Nenhum link de vídeo encontrado no JSON.")
            }

        } catch (e: Exception) {
            println("[NexFlix] ERRO CRÍTICO no extractFromApi: ${e.message}")
            e.printStackTrace()
        }
    }
}
