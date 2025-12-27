package com.SuperFlix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

object SuperFlixExtractor {
    // LISTA DE DOMÍNIOS DE ANÚNCIOS PARA BLOQUEAR
    private val adDomains = listOf(
        "adsco.re",
        "tynt.com",
        "dtscout.com",
        "dtscdn.com",
        "cdn4ads.com",
        "onaudience.com",
        "mrktmtrcs.net",
        "eyeota.net",
        "cloudflareinsights.com",
        "whos.amung.us",
        "waust.at",
        "t.dtscout.com",
        "c.adsco.re",
        "4.adsco.re",
        "6.adsco.re",
        "doubleclick.net",
        "google-analytics.com",
        "googlesyndication.com",
        "googleadservices.com",
        "facebook.com/tr",
        "amazon-adsystem.com",
        "taboola.com",
        "outbrain.com"
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🔍 SuperFlixExtractor processando: $url")
            
            val streamResolver = WebViewResolver(
                interceptUrl = Regex("""\.m3u8"""),
                useOkhttp = false,
                timeout = 30_000L, // 30 segundos (reduzido porque sem ads é mais rápido)
                
                // FILTRO PARA BLOQUEAR ANÚNCIOS
                urlFilter = { interceptedUrl ->
                    // PERMITE apenas URLs que não são anúncios
                    val isAd = adDomains.any { it.containsMatchIn(interceptedUrl) }
                    
                    if (isAd) {
                        println("🚫 Bloqueando anúncio: $interceptedUrl")
                        false // BLOQUEIA esta URL
                    } else if (interceptedUrl.contains("m3u8")) {
                        println("✅ Permitindo M3U8: $interceptedUrl")
                        true // PERMITE M3U8
                    } else {
                        // Para outras URLs (conteúdo normal), permite
                        true
                    }
                }
            )

            val response = app.get(url, interceptor = streamResolver)
            val intercepted = response.url

            if (intercepted.isNotEmpty() && intercepted.contains(".m3u8")) {
                println("✅ M3U8 encontrado: $intercepted")
                
                val headers = mapOf(
                    "Referer" to url,
                    "Origin" to mainUrl,
                    "User-Agent" to getUserAgent(),
                    "Accept" to "*/*",
                    "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8"
                )

                M3u8Helper.generateM3u8(
                    name,
                    intercepted,
                    mainUrl,
                    headers = headers
                ).forEach(callback)

                true
            } else {
                println("❌ Nenhum M3U8 encontrado")
                false
            }
        } catch (e: Exception) {
            println("❌ Erro no extrator: ${e.message}")
            false
        }
    }
    
    private fun getUserAgent(): String {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
