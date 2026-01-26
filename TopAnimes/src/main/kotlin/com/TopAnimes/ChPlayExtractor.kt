package com.TopAnimes

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

object ChPlayExtractor {

    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔍 CHPLAY EXTRACTOR INICIADO")
        println("📄 URL do episódio: $url")
        
        return try {
            // 1. PROCURA A URL DO PNG NO HTML DA PÁGINA
            println("📥 Baixando página do episódio para encontrar URL PNG...")
            val episodeResponse = app.get(url)
            val html = episodeResponse.text
            println("✅ Página carregada (${html.length} chars)")
            
            // 2. PROCURA URL EXATA DO PNG (https://png.strp2p.com/#wdlhc...)
            println("🔎 Procurando URL do PNG (strp2p.com)...")
            
            var pngUrl: String? = null
            
            // Padrões para encontrar a URL do player PNG
            val pngPatterns = listOf(
                // Procura pela URL exata no src do iframe
                """src=["'](https?://png\.strp2p\.com/[^"']*)["']""".toRegex(),
                // Procura no iframe do player CHPLAY
                """id=["']source-player-1["'][^>]*>.*?<iframe[^>]*src=["'](https?://[^"']*)["']""".toRegex(RegexOption.DOT_MATCHES_ALL),
                // Procura por qualquer iframe que contenha strp2p
                """<iframe[^>]*src=["']([^"']*strp2p[^"']*)["']""".toRegex(),
                // Procura por URL com #wdlhc
                """src=["'](https?://[^"']*#wdlhc[^"']*)["']""".toRegex()
            )
            
            for (pattern in pngPatterns) {
                val matches = pattern.findAll(html)
                for (match in matches) {
                    val foundUrl = match.groupValues.getOrNull(1)
                    if (foundUrl != null && foundUrl.contains("strp2p.com")) {
                        println("🎯 URL PNG ENCONTRADA: ${foundUrl.take(100)}...")
                        pngUrl = foundUrl
                        break
                    }
                }
                if (pngUrl != null) break
            }
            
            if (pngUrl == null) {
                println("❌ Nenhuma URL strp2p.com encontrada")
                return false
            }
            
            // 3. USA WEBVIEWRESOLVER PARA INTERCEPTAR REQUISIÇÕES M3U8
            println("🎮 Usando WebViewResolver para interceptar requisições...")
            
            val m3u8Resolver = WebViewResolver(
                interceptUrl = Regex("""cf-master\.\d+\.txt|master\.m3u8|\.m3u8"""),
                additionalUrls = listOf(
                    Regex("""cf-master\.\d+\.txt"""),
                    Regex("""master\.m3u8"""),
                    Regex(""".*\.m3u8.*""")
                ),
                useOkhttp = false,
                timeout = 15_000L
            )
            
            // 4. ACESSA A URL DO PNG COM O RESOLVER PARA INTERCEPTAR M3U8
            println("🔄 Acessando URL PNG com WebViewResolver: ${pngUrl.take(100)}...")
            
            val interceptedResponse = app.get(pngUrl, interceptor = m3u8Resolver)
            val interceptedUrl = interceptedResponse.url
            
            println("🔗 URL interceptada: $interceptedUrl")
            
            // 5. VERIFICA SE INTERCEPTOU UM M3U8
            if (interceptedUrl.isNotEmpty() && (interceptedUrl.contains("cf-master") || interceptedUrl.contains(".m3u8"))) {
                println("✅ M3U8 INTERCEPTADO!")
                
                val m3u8Url = if (interceptedUrl.contains("cf-master")) {
                    // Se for o cf-master, usa diretamente
                    interceptedUrl
                } else if (interceptedUrl.contains(".m3u8")) {
                    // Se já for m3u8, usa diretamente
                    interceptedUrl
                } else {
                    // Tenta extrair de parâmetros
                    val m3u8Match = Regex("""(https?://[^&\s]+\.m3u8[^\s]*)""").find(interceptedUrl)
                    m3u8Match?.groupValues?.get(1) ?: interceptedUrl
                }
                
                println("🎬 URL M3U8 final: ${m3u8Url.take(100)}...")
                
                // 6. HEADERS PARA O M3U8 (baseado no código exemplo)
                val headers = mapOf(
                    "Accept" to "*/*",
                    "Connection" to "keep-alive",
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "cross-site",
                    "Referer" to pngUrl,
                    "Origin" to "https://png.strp2p.com",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
                
                // 7. GERA OS LINKS M3U8
                println("🔄 Gerando links M3U8...")
                
                M3u8Helper.generateM3u8(
                    name,
                    m3u8Url,
                    mainUrl,
                    headers = headers
                ).forEach(callback)
                
                println("✅ CHPLAY EXTRACTOR FINALIZADO COM SUCESSO!")
                return true
                
            } else {
                println("❌ Nenhum M3U8 foi interceptado")
                println("📄 Conteúdo da resposta: ${interceptedResponse.text.take(500)}...")
                return false
            }
            
        } catch (e: Exception) {
            println("💥 ERRO NO CHPLAY EXTRACTOR: ${e.message}")
            e.printStackTrace()
            false
        }
    }
}
