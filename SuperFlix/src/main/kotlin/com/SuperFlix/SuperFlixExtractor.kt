package com.SuperFlix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities

object SuperFlixExtractor {

    private const val TIMEOUT_MS = 20_000L

    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("SFX Extractor: Iniciando extração via WebViewResolver para URL: $url")

        return try {
            // Configura o WebViewResolver para interceptar links de stream
            val streamResolver = WebViewResolver(
                interceptUrl = Regex("""\.(m3u8|mp4|mkv)"""),
                useOkhttp = false,
                timeout = TIMEOUT_MS
            )

            // Navega até a URL do player
            val response = app.get(url, interceptor = streamResolver, timeout = TIMEOUT_MS)
            val intercepted = response.url

            println("SFX Extractor: URL Interceptada: $intercepted")

            if (intercepted.isNotEmpty() && intercepted.startsWith("http")) {
                // Verifica se a interceptação foi bem-sucedida
                if (!intercepted.contains("m3u8") && !intercepted.contains("mp4") && !intercepted.contains("mkv")) {
                    println("SFX Extractor DEBUG: Interceptação ocorreu, mas URL não é de mídia.")
                    return false 
                }

                // Headers de referência
                val headers = mapOf(
                    "Referer" to url,
                    "Origin" to mainUrl,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )

                // Processamento da Mídia
                if (intercepted.contains(".m3u8")) {
                    println("SFX Extractor DEBUG: URL é M3U8. Gerando links.")
                    // Se for M3U8, usa o M3u8Helper
                    M3u8Helper.generateM3u8(
                        source = name,
                        url = intercepted,
                        referer = url,
                        headers = headers
                    ).forEach(callback)
                } else {
                    // Se for MP4/MKV direto
                    println("SFX Extractor DEBUG: URL é MP4/MKV direto. Retornando link.")
                    val quality = if (intercepted.contains("1080p", true)) {
                        Qualities.FullHDP.value
                    } else if (intercepted.contains("720p", true)) {
                        Qualities.HDP.value
                    } else {
                        Qualities.Unknown.value
                    }

                    // Corrigido: usando o construtor correto do ExtractorLink
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = "SuperFlix",
                            url = com.lagradost.cloudstream3.utils.fixUrl(intercepted),
                            referer = url,
                            quality = quality,
                            isM3u8 = false
                        )
                    )
                }

                return true
            } else {
                println("SFX Extractor DEBUG: WebViewResolver falhou em interceptar uma URL válida.")
                false
            }
        } catch (e: Exception) {
            println("SFX Extractor FALHA CRÍTICA: Erro durante a extração: ${e.message}")
            e.printStackTrace()
            false
        }
    }
}