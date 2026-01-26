package com.TopAnimes

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType

object ChPlayExtractor {

    suspend fun extractVideoLinks(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔍 CHPLAY EXTRACTOR")
        
        return try {
            // 1. PEGA O HTML DA PÁGINA DO EPISÓDIO
            val html = app.get(url).text
            
            // 2. PROCURA O IFRAME DO PLAYER 1 (CHPLAY)
            val iframePattern = Regex("""id=["']source-player-1["'][^>]*>.*?<iframe[^>]*src=["']([^"']*)["']""", RegexOption.DOT_MATCHES_ALL)
            val iframeMatch = iframePattern.find(html)
            
            if (iframeMatch == null) {
                println("❌ Iframe do player 1 não encontrado")
                return false
            }
            
            var iframeUrl = iframeMatch.groupValues[1]
            println("✅ Iframe encontrado: ${iframeUrl.take(100)}...")
            
            // 3. SE FOR AVISO, PEGA O URL PARÂMETRO
            if (iframeUrl.contains("/aviso/?url=")) {
                val urlParamPattern = Regex("""url=([^&]*)""")
                val urlParamMatch = urlParamPattern.find(iframeUrl)
                
                if (urlParamMatch != null) {
                    val encodedUrl = urlParamMatch.groupValues[1]
                    iframeUrl = java.net.URLDecoder.decode(encodedUrl, "UTF-8")
                    println("🔗 URL decodificada: ${iframeUrl.take(100)}...")
                }
            }
            
            // 4. VERIFICA SE É A URL DO PNG
            if (!iframeUrl.contains("strp2p.com")) {
                println("❌ Não é URL do PNG: $iframeUrl")
                return false
            }
            
            // 5. CORRIGE A URL SE NECESSÁRIO
            val finalUrl = when {
                iframeUrl.startsWith("//") -> "https:$iframeUrl"
                iframeUrl.startsWith("/") -> "https://topanimes.net$iframeUrl"
                iframeUrl.startsWith("http") -> iframeUrl
                else -> "https://$iframeUrl"
            }
            
            println("🎯 URL final: ${finalUrl.take(100)}...")
            
            // 6. USA WEBVIEWRESOLVER COM TIMEOUT MAIOR
            val m3u8Resolver = WebViewResolver(
                interceptUrl = Regex("""cf-master\.\d+\.txt|\.m3u8|master\.m3u8|\.mp4|video|stream"""),
                additionalUrls = listOf(
                    Regex("""cf-master"""),
                    Regex("""\.m3u8"""),
                    Regex("""\.mp4"""),
                    Regex("""video"""),
                    Regex("""stream""")
                ),
                useOkhttp = false,
                timeout = 30_000L // 30 segundos para carregar
            )
            
            println("🔄 Acessando com WebViewResolver (30s timeout)...")
            val interceptedResponse = app.get(finalUrl, interceptor = m3u8Resolver)
            val interceptedUrl = interceptedResponse.url
            
            println("🔗 URL interceptada: $interceptedUrl")
            
            // 7. TAMBÉM VERIFICA O CORPO DA RESPOSTA POSSÍVEL M3U8
            val responseText = interceptedResponse.text
            var foundVideoUrl: String? = null
            
            if (interceptedUrl.isNotEmpty() && (interceptedUrl.contains("cf-master") || interceptedUrl.contains(".m3u8") || interceptedUrl.contains(".mp4"))) {
                foundVideoUrl = interceptedUrl
                println("✅ Link encontrado na URL interceptada")
            } else if (responseText.contains(".m3u8") || responseText.contains(".mp4")) {
                // Tenta extrair do texto da resposta
                val videoPatterns = listOf(
                    Regex("""https?://[^"\s]*\.m3u8[^"\s]*"""),
                    Regex("""https?://[^"\s]*\.mp4[^"\s]*"""),
                    Regex("""["'](https?://[^"']*\.m3u8[^"']*)["']"""),
                    Regex("""["'](https?://[^"']*\.mp4[^"']*)["']""")
                )
                
                for (pattern in videoPatterns) {
                    val match = pattern.find(responseText)
                    if (match != null) {
                        foundVideoUrl = match.groupValues.getOrNull(1) ?: match.value
                        println("✅ Link encontrado no corpo da resposta")
                        break
                    }
                }
            }
            
            if (foundVideoUrl == null) {
                println("❌ Nenhum link de vídeo encontrado")
                println("📄 Primeiros 500 chars da resposta: ${responseText.take(500)}...")
                return false
            }
            
            println("🎬 URL do vídeo: ${foundVideoUrl.take(100)}...")
            
            // 8. HEADERS
            val headers = mapOf(
                "Accept" to "*/*",
                "Connection" to "keep-alive",
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "cross-site",
                "Referer" to finalUrl,
                "Origin" to "https://png.strp2p.com",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )
            
            // 9. PROCESSAR O LINK
            if (foundVideoUrl.contains(".m3u8") || foundVideoUrl.contains("cf-master")) {
                // M3U8
                M3u8Helper.generateM3u8(
                    name,
                    foundVideoUrl,
                    "https://topanimes.net",
                    headers = headers
                ).forEach(callback)
                println("✅ M3U8 processado")
                return true
            } else if (foundVideoUrl.contains(".mp4")) {
                // MP4 direto - AGORA É SUSPEND!
                createMp4Link(foundVideoUrl, name, finalUrl, callback, headers)
                println("✅ MP4 processado")
                return true
            }
            
            false
            
        } catch (e: Exception) {
            println("💥 Erro: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    /**
     * Cria link MP4 - AGORA É SUSPEND!
     */
    private suspend fun createMp4Link(
        videoUrl: String,
        name: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        headers: Map<String, String>
    ) {
        val quality = determineQuality(videoUrl)
        val qualityLabel = getQualityLabel(quality)
        
        // newExtractorLink É SUSPEND!
        val extractorLink = newExtractorLink(
            source = "CHPLAY",
            name = "$name ($qualityLabel) [MP4]",
            url = videoUrl,
            type = ExtractorLinkType.VIDEO
        ) {
            this.referer = referer
            this.quality = quality
            this.headers = headers
        }
        
        callback(extractorLink)
    }
    
    /**
     * Determina a qualidade baseada na URL
     */
    private fun determineQuality(url: String): Int {
        return when {
            url.contains("1080") || url.contains("fullhd") || url.contains("fhd") -> 1080
            url.contains("720") || url.contains("hd") -> 720
            url.contains("480") || url.contains("sd") -> 480
            url.contains("360") -> 360
            url.contains("240") -> 240
            else -> 720 // Qualidade padrão
        }
    }
    
    /**
     * Retorna o label da qualidade
     */
    private fun getQualityLabel(quality: Int): String {
        return when (quality) {
            1080 -> "1080p"
            720 -> "720p"
            480 -> "480p"
            360 -> "360p"
            240 -> "240p"
            else -> "SD"
        }
    }
}
