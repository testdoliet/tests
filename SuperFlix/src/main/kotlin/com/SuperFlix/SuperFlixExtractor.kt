package com.SuperFlix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities

object SuperFlixExtractor {
    // JavaScript para CLICAR automaticamente em tudo
    private val autoClickScript = """
        // Função para clicar em elementos automaticamente
        function autoClickElements() {
            console.log('🔍 Procurando elementos para clicar...');
            
            // Lista de seletores de botões comuns
            const buttonSelectors = [
                'button', 
                'div[role="button"]',
                'a[href*="play"]',
                'a[href*="video"]',
                '.play-btn',
                '.player-button',
                '[class*="play"]',
                '[id*="play"]',
                '.btn-play',
                '.play-button',
                'input[type="button"][value*="Play"]',
                'input[type="button"][value*="Assistir"]',
                '.jw-icon-play',
                '.vjs-big-play-button',
                '.plyr__control--play'
            ];
            
            let clicked = false;
            
            // Tenta cada seletor
            buttonSelectors.forEach(selector => {
                const elements = document.querySelectorAll(selector);
                elements.forEach(element => {
                    console.log('🎯 Encontrado elemento:', element);
                    
                    // Simula click
                    element.click();
                    console.log('✅ Clicado no elemento via click()');
                    
                    // Também dispara eventos
                    const mouseEvents = ['mousedown', 'mouseup', 'click'];
                    mouseEvents.forEach(eventType => {
                        const event = new MouseEvent(eventType, {
                            view: window,
                            bubbles: true,
                            cancelable: true
                        });
                        element.dispatchEvent(event);
                    });
                    
                    clicked = true;
                });
            });
            
            // Se não encontrou botões normais, procura por divs clicáveis
            if (!clicked) {
                const allDivs = document.querySelectorAll('div');
                allDivs.forEach(div => {
                    const style = window.getComputedStyle(div);
                    const hasCursorPointer = style.cursor === 'pointer';
                    const hasText = div.textContent && (
                        div.textContent.toLowerCase().includes('play') ||
                        div.textContent.toLowerCase().includes('assistir') ||
                        div.textContent.toLowerCase().includes('watch') ||
                        div.textContent.toLowerCase().includes('carregar')
                    );
                    
                    if (hasCursorPointer || hasText) {
                        console.log('🎯 Div clicável encontrada:', div);
                        div.click();
                        clicked = true;
                    }
                });
            }
            
            return clicked;
        }
        
        // Executa imediatamente
        setTimeout(autoClickElements, 1000);
        
        // Executa periodicamente (a página pode carregar conteúdo dinâmico)
        setInterval(autoClickElements, 3000);
        
        // Também observa mudanças no DOM
        const observer = new MutationObserver(function(mutations) {
            console.log('🔄 DOM mudou, tentando clicar novamente...');
            autoClickElements();
        });
        
        observer.observe(document.body, {
            childList: true,
            subtree: true
        });
        
        console.log('🤖 Auto-click script carregado!');
    """.trimIndent()

    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🎮 SuperFlixExtractor com Auto-Click")
        
        // Lista para armazenar URLs interceptadas
        val interceptedUrls = mutableListOf<String>()
        
        val streamResolver = WebViewResolver(
            interceptUrl = Regex("""\.m3u8"""),
            useOkhttp = false,
            timeout = 90_000L,  // 90 segundos (precisa clicar e carregar)
            
            // Configurações customizadas do WebView
            additionalSettings = { webView ->
                // Permite JavaScript
                webView.settings.javaScriptEnabled = true
                
                // Injeta o script de auto-click
                webView.evaluateJavascript(autoClickScript) {
                    println("✅ Script de auto-click injetado")
                }
                
                // Re-injeta o script quando a página carrega
                webView.webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        
                        // Injeta o script novamente
                        webView.evaluateJavascript(autoClickScript) {
                            println("🔄 Script re-injetado após carregamento da página")
                        }
                        
                        // Também tenta clicar via JavaScript
                        val clickScript = """
                            // Tenta clicar em qualquer coisa que pareça um botão de play
                            function clickPlayButtons() {
                                var clicked = false;
                                
                                // Procura por texto "Play", "Assistir", etc
                                var texts = ['play', 'assistir', 'watch', 'carregar', 'player'];
                                for(var i = 0; i < texts.length; i++) {
                                    var xpath = "//*[contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), '" + texts[i] + "')]";
                                    var elements = document.evaluate(xpath, document, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);
                                    
                                    for(var j = 0; j < elements.snapshotLength; j++) {
                                        var element = elements.snapshotItem(j);
                                        if(element) {
                                            element.click();
                                            console.log('✅ Clicado via XPath: ' + texts[i]);
                                            clicked = true;
                                        }
                                    }
                                }
                                
                                return clicked;
                            }
                            
                            clickPlayButtons();
                        """.trimIndent()
                        
                        webView.evaluateJavascript(clickScript, null)
                    }
                }
            },
            
            // Callback quando intercepta URL
            onUrlIntercept = { interceptedUrl ->
                println("🔄 Interceptado: $interceptedUrl")
                
                if (interceptedUrl.contains(".m3u8")) {
                    interceptedUrls.add(interceptedUrl)
                    println("✅ M3U8 salvo (total: ${interceptedUrls.size})")
                }
                
                true
            }
        )

        return try {
            println("🌐 Navegando com auto-click...")
            
            val response = app.get(
                url,
                headers = getBrowserHeaders(),
                interceptor = streamResolver
            )
            
            println("📊 Navegação concluída!")
            println("📈 M3U8 interceptados: ${interceptedUrls.size}")
            
            // Mostra todos os links encontrados
            interceptedUrls.forEachIndexed { index, m3u8Url ->
                println("${index + 1}. $m3u8Url")
            }
            
            // Processa o melhor link
            return processBestM3u8(interceptedUrls, name, callback)
            
        } catch (e: Exception) {
            println("❌ Erro: ${e.message}")
            
            // Tenta usar o que foi interceptado mesmo com erro
            if (interceptedUrls.isNotEmpty()) {
                return processBestM3u8(interceptedUrls, name, callback)
            }
            
            false
        }
    }
    
    /**
     * Processa o melhor M3U8 da lista
     */
    private fun processBestM3u8(
        urls: List<String>,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (urls.isEmpty()) return false
        
        // Filtra e ordena por qualidade
        val sortedUrls = urls.sortedByDescending { url ->
            when {
                url.contains("1080") -> 1080
                url.contains("720") -> 720
                url.contains("master.m3u8") -> 1000  // Master tem todas as qualidades
                url.contains("iframes") -> 500
                else -> 0
            }
        }
        
        val bestUrl = sortedUrls.first()
        println("🎯 Melhor URL: $bestUrl")
        
        // Determina qualidade
        val quality = when {
            bestUrl.contains("1080") -> Qualities.FullHd.value
            bestUrl.contains("720") -> Qualities.HD.value
            bestUrl.contains("480") -> Qualities.SDVD.value
            else -> Qualities.Unknown.value
        }
        
        M3u8Helper.generateM3u8(
            name,
            bestUrl,
            "https://bysevepoin.com",
            headers = getStreamHeaders(),
            quality = quality
        ).forEach(callback)
        
        return true
    }
    
    private fun getBrowserHeaders(): Map<String, String> {
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
        )
    }
    
    private fun getStreamHeaders(): Map<String, String> {
        return mapOf(
            "User-Agent" to "Mozilla/5.0",
            "Referer" to "https://bysevepoin.com/",
            "Origin" to "https://bysevepoin.com"
        )
    }
}
