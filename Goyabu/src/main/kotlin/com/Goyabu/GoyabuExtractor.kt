package com.Goyabu

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import java.net.URLDecoder

object GoyabuExtractor {
    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🎬 GOYABU EXTRACTOR: Tentando extrair de $url")

        return try {
            // Estratégia principal: WebViewResolver
            val webViewSuccess = tryWebViewExtraction(url, mainUrl, name, callback)
            if (webViewSuccess) return true

            // Fallback: buscar no HTML
            println("🔧 Fallback: Buscando no HTML...")
            val response = app.get(url)
            val iframeMatch = Regex("""<iframe[^>]+src=["']([^"']*anivideo\.net[^"']*)["']""").find(response.text)
            if (iframeMatch != null) {
                val iframeUrl = iframeMatch.groupValues[1]
                println("🎯 Iframe encontrado no HTML: $iframeUrl")
                return processAnivideoUrl(iframeUrl, url, mainUrl, name, callback)
            }

            false
        } catch (e: Exception) {
            println("❌ Erro no extractor: ${e.message}")
            false
        }
    }

    private suspend fun tryWebViewExtraction(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val streamResolver = WebViewResolver(
                interceptUrl = Regex("""(anivideo\.net|videohls\.php|\.m3u8)"""),
                useOkhttp = false,
                timeout = 60_000L
            )

            println("🌐 Iniciando WebView (60s timeout)...")
            val response = app.get(url, interceptor = streamResolver)
            val interceptedUrl = response.url

            println("📡 URL interceptada: $interceptedUrl")

            when {
                interceptedUrl.contains("anivideo.net") || interceptedUrl.contains("videohls.php") -> {
                    println("✅ API anivideo interceptada!")
                    return processAnivideoUrl(interceptedUrl, url, mainUrl, name, callback)
                }
                interceptedUrl.contains(".m3u8") -> {
                    println("✅ M3U8 interceptado diretamente!")
                    return processM3u8Url(interceptedUrl, url, mainUrl, name, callback)
                }
            }

            false
        } catch (e: Exception) {
            println("⚠️ WebView falhou: ${e.message}")
            false
        }
    }

    private suspend fun processAnivideoUrl(
        apiUrl: String,
        referer: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔗 Processando API anivideo: $apiUrl")

        // Extrai o parâmetro d= (que é a URL do m3u8 encoded)
        val m3u8Encoded = Regex("""[?&]d=([^&]+)""").find(apiUrl)?.groupValues?.get(1)
        if (m3u8Encoded != null) {
            val m3u8Url = URLDecoder.decode(m3u8Encoded, "UTF-8")
            println("✅ M3U8 extraído do parâmetro d=: $m3u8Url")
            return processM3u8Url(m3u8Url, referer, mainUrl, name, callback)
        }

        // Se não tiver d=, faz get na API (raramente necessário hoje)
        val apiResponse = app.get(apiUrl, headers = mapOf("Referer" to referer))
        val m3u8Match = Regex("""(https?://[^\s"']+\.m3u8)""".toRegex(RegexOption.IGNORE_CASE))
            .find(apiResponse.text)
        if (m3u8Match != null) {
            val m3u8Url = m3u8Match.groupValues[1]
            println("✅ M3U8 encontrado na resposta da API: $m3u8Url")
            return processM3u8Url(m3u8Url, apiUrl, mainUrl, name, callback)
        }

        return false
    }

    private suspend fun processM3u8Url(
        m3u8Url: String,
        referer: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val headers = mapOf(
                "Referer" to referer,
                "Origin" to mainUrl,
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            )

            M3u8Helper.generateM3u8(
                name,  // source
                m3u8Url,  // url
                mainUrl,  // referer
                headers = headers
            ).forEach(callback)

            println("✅ Link M3U8 adicionado com sucesso!")
            true
        } catch (e: Exception) {
            println("❌ Erro no M3U8: ${e.message}")
            false
        }
    }
}
