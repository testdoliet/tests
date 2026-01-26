package com.TopAnimes

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

object ChPlayExtractor {

    suspend fun extractVideoLinks(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔍 CHPLAY EXTRACTOR - VERSÃO COM CLICK")
        
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
            
            // 6. USA WEBVIEWRESOLVER COM JAVASCRIPT PARA CLICAR NO BOTÃO
            val m3u8Resolver = WebViewResolver(
                interceptUrl = Regex("""cf-master\.\d+\.txt|\.m3u8|master\.m3u8|\.mp4"""),
                additionalUrls = listOf(
                    Regex("""cf-master"""),
                    Regex("""\.m3u8"""),
                    Regex("""\.mp4""")
                ),
                useOkhttp = false,
                timeout = 20_000L, // Aumenta o timeout para dar tempo do vídeo carregar
                onPageLoaded = { webView ->
                    // Aguarda um pouco para a página carregar completamente
                    Thread.sleep(2000)
                    
                    // Executa JavaScript para clicar no botão de play
                    val clickScript = """
                        // Tenta encontrar e clicar no botão de play
                        function clickPlayButton() {
                            console.log("Procurando botão de play...");
                            
                            // Tenta pelo ID
                            var button = document.getElementById('player-button');
                            if (button) {
                                console.log("Botão encontrado pelo ID");
                                button.click();
                                return true;
                            }
                            
                            // Tenta pelo container
                            var container = document.getElementById('player-button-container');
                            if (container) {
                                console.log("Container encontrado, clicando...");
                                container.click();
                                return true;
                            }
                            
                            // Tenta por qualquer elemento que pareça botão de play
                            var divs = document.getElementsByTagName('div');
                            for (var i = 0; i < divs.length; i++) {
                                var div = divs[i];
                                var style = window.getComputedStyle(div);
                                if (style.display === 'flex' && style.justifyContent === 'center' && 
                                    style.alignItems === 'center' && style.cursor === 'pointer') {
                                    console.log("Botão por estilo encontrado");
                                    div.click();
                                    return true;
                                }
                            }
                            
                            console.log("Nenhum botão encontrado");
                            return false;
                        }
                        
                        // Tenta clicar
                        var clicked = clickPlayButton();
                        
                        // Se não encontrou, tenta encontrar elementos de vídeo
                        if (!clicked) {
                            console.log("Procurando elementos de vídeo...");
                            var videos = document.getElementsByTagName('video');
                            if (videos.length > 0) {
                                console.log("Vídeo encontrado, tentando play...");
                                videos[0].play();
                                return true;
                            }
                            
                            // Procura por JWPlayer
                            if (typeof jwplayer !== 'undefined') {
                                console.log("JWPlayer encontrado, iniciando...");
                                jwplayer().play();
                                return true;
                            }
                        }
                        
                        return clicked;
                    """
                    
                    webView.evaluateJavascript(clickScript) { result ->
                        println("📱 JavaScript executado: $result")
                    }
                    
                    // Aguarda mais um pouco para o vídeo começar a carregar
                    Thread.sleep(3000)
                }
            )
            
            println("🔄 Acessando com WebViewResolver + JavaScript...")
            val intercepted = app.get(finalUrl, interceptor = m3u8Resolver).url
            
            println("🔗 Interceptado: $intercepted")
            
            if (intercepted.isNotEmpty() && (intercepted.contains(".m3u8") || intercepted.contains(".mp4") || intercepted.contains("cf-master"))) {
                println("✅ Link de vídeo encontrado!")
                
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
                
                // Se for m3u8, usa M3u8Helper
                if (intercepted.contains(".m3u8") || intercepted.contains("cf-master")) {
                    M3u8Helper.generateM3u8(
                        "$name Player",
                        intercepted,
                        "https://topanimes.net",
                        headers = headers
                    ).forEach(callback)
                } else if (intercepted.contains(".mp4")) {
                    // Se for MP4 direto, cria link simples
                    callback(
                        ExtractorLink(
                            source = name,
                            name = "$name Player",
                            url = intercepted,
                            referer = finalUrl,
                            quality = 720,
                            headers = headers
                        )
                    )
                }
                
                return true
            }
            
            println("❌ Nenhum link de vídeo interceptado após clique")
            false
            
        } catch (e: Exception) {
            println("💥 Erro: ${e.message}")
            e.printStackTrace()
            false
        }
    }
}
