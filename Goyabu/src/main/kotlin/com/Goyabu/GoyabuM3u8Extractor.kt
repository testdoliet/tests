package com.Goyabu

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

object GoyabuM3u8Extractor {
    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔍 GOYABU M3U8 EXTRACTOR: Analisando URL: $url")
        
        return try {
            // Primeiro, fazer requisição para a página principal
            val response = app.get(url)
            val html = response.text
            
            // Procurar especificamente pelo padrão index.m3u8
            val m3u8Patterns = listOf(
                """(https?://[^"'\s]+\.mp4/index\.m3u8)""".toRegex(),
                """["'](https?://[^"']+\.mp4/index\.m3u8)["']""".toRegex(),
                """videoUrl\s*[:=]\s*["'](https?://[^"']+)["']""".toRegex(),
                """(https?://[^"'\s]+/index\.m3u8)""".toRegex()
            )
            
            var m3u8Url: String? = null
            
            for (pattern in m3u8Patterns) {
                val match = pattern.find(html)
                if (match != null) {
                    m3u8Url = match.groupValues[1]
                    println("🎯 Encontrado M3U8: $m3u8Url")
                    break
                }
            }
            
            // Se não encontrou no HTML, tentar o WebViewResolver
            if (m3u8Url == null) {
                println("⚠️ Não encontrou M3U8 no HTML, tentando WebView...")
                
                val streamResolver = WebViewResolver(
                    interceptUrl = Regex("""\.m3u8"""),
                    useOkhttp = false,
                    timeout = 20_000L
                )

                val webViewResponse = app.get(url, interceptor = streamResolver)
                val intercepted = webViewResponse.url
                
                if (intercepted.contains(".m3u8")) {
                    m3u8Url = intercepted
                    println("🎯 WebView interceptou M3U8: $m3u8Url")
                }
            }
            
            // Se encontrou o M3U8, processá-lo
            if (m3u8Url != null && m3u8Url.contains(".m3u8")) {
                println("🚀 Processando M3U8: $m3u8Url")
                
                val headers = mapOf(
                    "Referer" to url,
                    "Origin" to mainUrl,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                )
                
                // Tentar baixar o M3U8 master
                val m3u8Response = app.get(m3u8Url, headers = headers)
                val m3u8Content = m3u8Response.text
                
                if (m3u8Content.contains("#EXTM3U")) {
                    // Usar M3u8Helper para gerar os links
                    M3u8Helper.generateM3u8(
                        name,
                        m3u8Url,
                        mainUrl,
                        headers = headers
                    ).forEach(callback)
                    
                    println("✅ GOYABU M3U8 EXTRACTOR: Links extraídos com sucesso!")
                    return true
                } else {
                    // Se não for um M3U8 válido, criar link manualmente
                    println("⚠️ M3U8 não contém #EXTM3U, criando link manualmente")
                    
                    val extractorLink = newExtractorLink(
                        source = name,
                        name = "Vídeo M3U8",
                        url = m3u8Url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = url
                        this.quality = 720
                        this.headers = headers
                    }
                    
                    callback(extractorLink)
                    println("✅ GOYABU M3U8 EXTRACTOR: Link manual criado")
                    return true
                }
            }
            
            println("❌ GOYABU M3U8 EXTRACTOR: Nenhum M3U8 encontrado")
            false
            
        } catch (e: Exception) {
            println("❌ GOYABU M3U8 EXTRACTOR: Erro: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    // Função auxiliar para criar ExtractorLink
    private fun newExtractorLink(
        source: String,
        name: String,
        url: String,
        type: ExtractorLinkType,
        init: ExtractorLink.() -> Unit = {}
    ): ExtractorLink {
        return newExtractorLink(
            source = source,
            name = name,
            url = url,
            type = type
        ).apply(init)
    }
}
