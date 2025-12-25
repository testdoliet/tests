package com.Goyabu

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.jsoup.Jsoup
import java.net.URLDecoder

object GoyabuExtractor {
    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🎬 GOYABU EXTRACTOR: Iniciando extração para: $url")
        
        return try {
            // ESTRATÉGIA 1: WebView que SIMULA CLIQUE
            println("🔧 Estratégia 1: WebView com simulação de clique...")
            val webViewSuccess = tryWebViewWithClick(url, mainUrl, name, callback)
            
            if (webViewSuccess) {
                println("✅ GOYABU: WebView com clique funcionou!")
                return true
            }
            
            // ESTRATÉGIA 2: Requisição POST que simula clique
            println("🔧 Estratégia 2: Requisição POST simulando clique...")
            val postSuccess = tryPostRequest(url, mainUrl, name, callback)
            
            if (postSuccess) {
                println("✅ GOYABU: POST simulando clique funcionou!")
                return true
            }
            
            println("❌ GOYABU: Nenhuma estratégia funcionou")
            false
            
        } catch (e: Exception) {
            println("❌ GOYABU EXTRACTOR: Erro: ${e.message}")
            false
        }
    }
    
    // ============ ESTRATÉGIA 1: WebView com JavaScript para clicar ============
    private suspend fun tryWebViewWithClick(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // WebView que executa JavaScript para clicar no player
            val streamResolver = WebViewResolver(
                interceptUrl = Regex("""(anivideo\.net/videohls\.php|videohls\.php\?d=)"""),
                useOkhttp = false,
                timeout = 30_000L,
                onPageLoaded = { webView ->
                    // DEPOIS que a página carregar, executar JavaScript para clicar
                    println("🖱️ Executando JavaScript para simular clique...")
                    
                    // Script que tenta clicar em vários elementos possíveis
                    val clickScript = """
                        // Tentar clicar em vários elementos possíveis
                        function simulateClick() {
                            // 1. Tentar elemento com id="player"
                            var player = document.getElementById('player');
                            if (player) {
                                player.click();
                                console.log('✅ Clicado no elemento #player');
                                return true;
                            }
                            
                            // 2. Tentar elemento com classe contendo "player"
                            var playerElements = document.querySelectorAll('[class*="player"], [class*="Player"]');
                            for (var i = 0; i < playerElements.length; i++) {
                                if (playerElements[i].offsetWidth > 0 && playerElements[i].offsetHeight > 0) {
                                    playerElements[i].click();
                                    console.log('✅ Clicado em elemento player: ' + playerElements[i].className);
                                    return true;
                                }
                            }
                            
                            // 3. Tentar botão de play
                            var playButtons = document.querySelectorAll('[class*="play"], [class*="Play"], button, [onclick*="play"]');
                            for (var i = 0; i < playButtons.length; i++) {
                                if (playButtons[i].offsetWidth > 0 && playButtons[i].offsetHeight > 0) {
                                    playButtons[i].click();
                                    console.log('✅ Clicado em botão play');
                                    return true;
                                }
                            }
                            
                            // 4. Tentar clicar na div principal do player
                            var mainDivs = document.querySelectorAll('div');
                            for (var i = 0; i < mainDivs.length; i++) {
                                var style = window.getComputedStyle(mainDivs[i]);
                                if (style.display !== 'none' && 
                                    mainDivs[i].offsetWidth > 300 && 
                                    mainDivs[i].offsetHeight > 200) {
                                    mainDivs[i].click();
                                    console.log('✅ Clicado em div grande');
                                    return true;
                                }
                            }
                            
                            console.log('❌ Nenhum elemento encontrado para clicar');
                            return false;
                        }
                        
                        // Executar depois de um delay
                        setTimeout(simulateClick, 2000);
                        
                        // Executar novamente depois de mais tempo
                        setTimeout(simulateClick, 5000);
                    """.trimIndent()
                    
                    webView.evaluateJavascript(clickScript, null)
                }
            )
            
            println("🌐 WebView iniciado com simulação de clique...")
            val response = app.get(url, interceptor = streamResolver)
            val interceptedUrl = response.url
            
            println("📡 URL interceptada após clique: $interceptedUrl")
            
            if (interceptedUrl.contains("anivideo.net") && interceptedUrl.contains("videohls.php")) {
                println("🎯 API interceptada APÓS clique!")
                return extractAndProcessM3u8FromApi(interceptedUrl, url, mainUrl, name, callback)
            }
            
            false
        } catch (e: Exception) {
            println("⚠️ WebView com clique falhou: ${e.message}")
            false
        }
    }
    
    // ============ ESTRATÉGIA 2: Requisição POST que simula ação do usuário ============
    private suspend fun tryPostRequest(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("📨 Enviando requisições para simular ação do usuário...")
            
            // PRIMEIRO: Obter a página inicial
            val initialResponse = app.get(url, headers = getRealBrowserHeaders())
            val initialHtml = initialResponse.text
            val doc = Jsoup.parse(initialHtml)
            
            // Procurar por elementos que podem acionar o player
            val possibleTriggers = listOf(
                // Botões/links que podem carregar o player
                "button[data-player]", "a[data-player]", "[data-action='play']",
                "[onclick*='player']", "[onclick*='loadPlayer']", "[onclick*='video']",
                "#load-player", ".load-player", ".player-trigger"
            )
            
            var found = false
            
            for (selector in possibleTriggers) {
                val elements = doc.select(selector)
                for (element in elements) {
                    val dataUrl = element.attr("data-url")
                    val onclick = element.attr("onclick")
                    val href = element.attr("href")
                    
                    println("🔍 Elemento encontrado: $selector")
                    
                    // Tentar extrair URL do onclick
                    if (onclick.isNotBlank()) {
                        val urlPattern = Regex("""['"](https?://[^'"]+)['"]""")
                        val match = urlPattern.find(onclick)
                        if (match != null) {
                            val extractedUrl = match.groupValues[1]
                            println("🎯 URL extraída do onclick: $extractedUrl")
                            if (extractedUrl.contains("anivideo.net")) {
                                return extractAndProcessM3u8FromApi(extractedUrl, url, mainUrl, name, callback)
                            }
                        }
                    }
                    
                    // Se tem data-url
                    if (dataUrl.isNotBlank() && dataUrl.contains("anivideo.net")) {
                        println("🎯 data-url encontrada: $dataUrl")
                        return extractAndProcessM3u8FromApi(dataUrl, url, mainUrl, name, callback)
                    }
                }
            }
            
            // Se não encontrou elementos específicos, tentar requisição AJAX comum
            println("🔍 Tentando requisições AJAX comuns...")
            
            val ajaxUrls = listOf(
                "$url?ajax=true",
                "$url&ajax=true",
                "$url?action=load_player",
                "$url&action=load_player",
                "$url?player=load",
                "$url&player=load"
            )
            
            for (ajaxUrl in ajaxUrls) {
                try {
                    println("📡 Tentando AJAX: $ajaxUrl")
                    val ajaxResponse = app.get(ajaxUrl, headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to url,
                        "User-Agent" to "Mozilla/5.0"
                    ))
                    
                    val ajaxText = ajaxResponse.text
                    
                    // Procurar URL da API na resposta AJAX
                    val apiPattern = Regex("""https?://api\.anivideo\.net/videohls\.php\?d=[^"'\s]+""")
                    val match = apiPattern.find(ajaxText)
                    
                    if (match != null) {
                        val apiUrl = match.value
                        println("🎯 API encontrada na resposta AJAX!")
                        return extractAndProcessM3u8FromApi(apiUrl, url, mainUrl, name, callback)
                    }
                } catch (e: Exception) {
                    // Continuar tentando outras URLs
                    continue
                }
            }
            
            false
        } catch (e: Exception) {
            println("❌ Erro nas requisições POST: ${e.message}")
            false
        }
    }
    
    // ============ FUNÇÃO DE EXTRAÇÃO DO M3U8 (mantida igual) ============
    private suspend fun extractAndProcessM3u8FromApi(
        apiUrl: String,
        referer: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔗 Extraindo M3U8 da API: ${apiUrl.take(100)}...")
        
        return try {
            // Extrair parâmetro d=
            val dParamRegex = Regex("""[?&]d=([^&]+)""")
            val match = dParamRegex.find(apiUrl)
            
            if (match != null) {
                val encodedM3u8 = match.groupValues[1]
                val m3u8Url = URLDecoder.decode(encodedM3u8, "UTF-8")
                
                println("✅ M3U8 decodificado: $m3u8Url")
                
                if (m3u8Url.startsWith("http") && m3u8Url.contains(".m3u8")) {
                    return processM3u8Stream(m3u8Url, referer, mainUrl, name, callback)
                }
            }
            
            // Fallback: requisição direta
            println("🔄 Fazendo requisição direta à API...")
            val apiResponse = app.get(apiUrl, headers = mapOf(
                "Referer" to referer,
                "User-Agent" to "Mozilla/5.0"
            ))
            
            val apiContent = apiResponse.text
            val m3u8Pattern = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""", RegexOption.IGNORE_CASE)
            val m3u8Match = m3u8Pattern.find(apiContent)
            
            if (m3u8Match != null) {
                val m3u8Url = m3u8Match.groupValues[1]
                println("✅ M3U8 encontrado na resposta: $m3u8Url")
                return processM3u8Stream(m3u8Url, apiUrl, mainUrl, name, callback)
            }
            
            println("❌ Não encontrou M3U8 na API")
            false
            
        } catch (e: Exception) {
            println("❌ Erro ao processar API: ${e.message}")
            false
        }
    }
    
    // ============ PROCESSAR STREAM M3U8 ============
    private suspend fun processM3u8Stream(
        m3u8Url: String,
        referer: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔗 Processando stream M3U8: $m3u8Url")
        
        return try {
            val headers = mapOf(
                "Referer" to referer,
                "Origin" to mainUrl,
                "User-Agent" to "Mozilla/5.0"
            )
            
            M3u8Helper.generateM3u8(
                name,
                m3u8Url,
                mainUrl,
                headers = headers
            ).forEach(callback)
            
            println("✅ Stream M3U8 processado com sucesso!")
            true
            
        } catch (e: Exception) {
            println("❌ Erro ao processar stream: ${e.message}")
            false
        }
    }
    
    // ============ HEADERS DE NAVEGADOR REAL ============
    private fun getRealBrowserHeaders(): Map<String, String> {
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
            "DNT" to "1",
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1"
        )
    }
}
