package com.Superflix // Ajuste para seu pacote

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.WebViewExtractor
import okhttp3.OkHttpClient

class PlayEmbedExtractor(client: OkHttpClient) : WebViewExtractor(client) {
    override val name = "PlayEmbed"
    // URL base do player (usada pelo WebView)
    override val mainUrl = "https://playembedapi.site"
    
    // Regex para detectar quando o vídeo final (MP4 ou M3U8) aparece na rede
    override val regex = """(https?://.*\.mp4|https?://.*\.m3u8)""".toRegex()

    override suspend fun getExtractorUrl(url: String): List<ExtractorLink>? {
        var playerUrl = url

        // PASSO 1: Se a URL recebida não for do player (for a do episódio),
        // fazemos uma requisição GET para extrair o link real do HTML.
        if (!url.contains("playembedapi.site")) {
            try {
                // Baixa o HTML da página do episódio
                val document = app.get(url).text
                
                // Procura: "source":"https://playembedapi.site/?v=..."
                val extractedUrl = """["']source["']\s*:\s*["']([^"']+)["']""".toRegex()
                    .find(document)?.groupValues?.get(1)?.replace("\\/", "/")
                
                if (extractedUrl != null) {
                    playerUrl = extractedUrl
                } else {
                    // Se não achou, retorna nulo (falha)
                    return null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                return null
            }
        }

        // PASSO 2: Agora que temos a URL do player (https://playembedapi...),
        // chamamos o WebView (super) para rodar o Service Worker e descriptografar.
        return super.getExtractorUrl(playerUrl)?.map { link ->
            
            // Tratamento especial para o link do Google Storage (MP4)
            if (link.url.contains("storage.googleapis.com")) {
                link.copy(
                    name = "Superflix (Original)",
                    quality = Qualities.Unknown, // Geralmente a melhor qualidade
                    isM3u8 = false,
                    // IMPORTANTE: Adiciona o Referer para evitar o erro "Path not found"
                    headers = mapOf(
                        "Referer" to "https://playembedapi.site/",
                        "Origin" to "https://playembedapi.site",
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"
                    )
                )
            } else {
                link
            }
        }
    }
}
