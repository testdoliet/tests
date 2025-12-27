package com.SuperFlix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.network.WebViewResolver

object SuperFlixExtractor {
    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // Vamos usar JavaScript para dar play no vídeo automaticamente
            useWebViewWithAutoPlay(url, name, callback)
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun useWebViewWithAutoPlay(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // WebView que injeta JavaScript para dar play
            val streamResolver = object : WebViewResolver(
                interceptUrl = Regex("""\.m3u8(\?.*)?"""),
                useOkhttp = false,
                timeout = 15_000L
            ) {
                override fun onPageFinished(webView: android.webkit.WebView, url: String) {
                    super.onPageFinished(webView, url)
                    
                    // Quando a página carregar, tenta dar play no vídeo
                    // Espera 1 segundo para garantir que o player carregou
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        val playScript = """
                            // Tenta encontrar e dar play no vídeo
                            function tryToPlay() {
                                // Procura por elementos de vídeo
                                var videos = document.querySelectorAll('video');
                                var iframes = document.querySelectorAll('iframe');
                                
                                // Tenta dar play em vídeos
                                for (var i = 0; i < videos.length; i++) {
                                    try {
                                        videos[i].play();
                                        console.log('🎬 Play dado no vídeo ' + i);
                                        return true;
                                    } catch(e) {}
                                }
                                
                                // Tenta clicar em botões de play
                                var playButtons = document.querySelectorAll(
                                    'button[class*="play"], ' +
                                    'div[class*="play"], ' +
                                    '.play-button, ' +
                                    '[onclick*="play"], ' +
                                    '#play, ' +
                                    '.vjs-big-play-button'
                                );
                                
                                for (var i = 0; i < playButtons.length; i++) {
                                    try {
                                        playButtons[i].click();
                                        console.log('🟢 Clicado no botão play ' + i);
                                        return true;
                                    } catch(e) {}
                                }
                                
                                return false;
                            }
                            
                            // Tenta múltiplas vezes
                            var attempts = 0;
                            var maxAttempts = 3;
                            
                            function attemptPlay() {
                                if (attempts >= maxAttempts) return;
                                
                                attempts++;
                                console.log('🔄 Tentativa ' + attempts + ' de dar play');
                                
                                if (tryToPlay()) {
                                    console.log('✅ Play bem sucedido!');
                                } else {
                                    // Tenta novamente depois de 1 segundo
                                    setTimeout(attemptPlay, 1000);
                                }
                            }
                            
                            // Começa as tentativas
                            attemptPlay();
                        """.trimIndent()
                        
                        webView.evaluateJavascript(playScript, null)
                    }, 1000)
                }
            }
            
            // Headers para parecer um usuário real
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; SM-G973F) AppleWebKit/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
                "Referer" to "https://superflix21.lol/"
            )
            
            val response = app.get(url, headers = headers, interceptor = streamResolver)
            val intercepted = response.url
            
            if (intercepted.contains(".m3u8")) {
                println("✅ M3U8 interceptado: $intercepted")
                
                // Headers para o CDN
                val cdnHeaders = mapOf(
                    "Referer" to "https://g9r6.com/",
                    "Origin" to "https://g9r6.com/",
                    "User-Agent" to "Mozilla/5.0"
                )
                
                M3u8Helper.generateM3u8(
                    name,
                    intercepted,
                    "https://g9r6.com/",
                    headers = cdnHeaders
                ).forEach(callback)
                
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
}
