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
        println("🔍 CHPLAY EXTRACTOR - BUSCANDO CF-MASTER")
        
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
            
            // 4. CORRIGE A URL SE NECESSÁRIO
            val finalUrl = when {
                iframeUrl.startsWith("//") -> "https:$iframeUrl"
                iframeUrl.startsWith("/") -> "https://topanimes.net$iframeUrl"
                iframeUrl.startsWith("http") -> iframeUrl
                else -> "https://$iframeUrl"
            }
            
            println("🎯 URL final para análise: $finalUrl")
            
            // 5. PADRÃO ESPECÍFICO PARA CF-MASTER
            println("\n🎯 PROCURANDO APENAS CF-MASTER...")
            println("==================================================")
            
            val cfMasterPattern = Regex(""".*cf-master.*""")
            var interceptedUrl = ""
            
            try {
                // PRIMEIRA TENTATIVA: Interceptação normal
                println("🔍 Tentando interceptar cf-master...")
                
                val resolver = WebViewResolver(
                    interceptUrl = cfMasterPattern,
                    additionalUrls = listOf(cfMasterPattern),
                    useOkhttp = false,
                    timeout = 10_000L  // 10 segundos para clicar
                )
                
                val result = app.get(finalUrl, interceptor = resolver)
                interceptedUrl = result.url
                
                if (interceptedUrl.isNotEmpty() && interceptedUrl != finalUrl && interceptedUrl.contains("cf-master")) {
                    println("✅ URL COM CF-MASTER ENCONTRADA!")
                    println("🔗 URL: ${interceptedUrl.take(150)}...")
                } else {
                    println("⚠️ Nenhum cf-master interceptado (talvez precise clicar)")
                }
                
            } catch (e: Exception) {
                println("❌ Erro na interceptação: ${e.message}")
            }
            
            // 6. SE NÃO ENCONTROU CF-MASTER, TENTA CLICAR NO PLAYER
            if (!interceptedUrl.contains("cf-master")) {
                println("\n🖱️ TENTANDO SIMULAR CLIQUE NO PLAYER...")
                println("==================================================")
                
                // Primeiro, tenta clicar via JavaScript
                println("🔍 Executando JavaScript para clicar no player...")
                
                val clickResolver = WebViewResolver(
                    interceptUrl = cfMasterPattern,
                    additionalUrls = listOf(cfMasterPattern),
                    useOkhttp = false,
                    timeout = 15_000L, // 15 segundos para interação
                    onPageFinished = { webView ->
                        // Aguarda um pouco para a página carregar completamente
                        Thread.sleep(2000)
                        
                        // Tenta clicar no centro da página (onde geralmente está o player)
                        println("🖱️ Simulando clique no centro da página...")
                        try {
                            // Executa JavaScript para simular clique
                            webView.evaluateJavascript("""
                                // Cria e dispara evento de clique
                                var event = new MouseEvent('click', {
                                    view: window,
                                    bubbles: true,
                                    cancelable: true
                                });
                                
                                // Tenta clicar em vários elementos possíveis
                                var elements = [
                                    document.querySelector('video'),
                                    document.querySelector('iframe'),
                                    document.querySelector('.jwplayer'),
                                    document.querySelector('.player'),
                                    document.querySelector('body'),
                                    document.documentElement
                                ];
                                
                                for (var i = 0; i < elements.length; i++) {
                                    if (elements[i]) {
                                        elements[i].dispatchEvent(event);
                                        console.log('Clicou no elemento ' + i);
                                    }
                                }
                                
                                // Também tenta rodar o JWPlayer se existir
                                if (typeof jwplayer !== 'undefined') {
                                    var players = jwplayer();
                                    if (players && players.length > 0) {
                                        players[0].play();
                                        console.log('JWPlayer iniciado');
                                    }
                                }
                                
                                // Retorna sucesso
                                'clique-simulado';
                            """.trimIndent(), null)
                            
                            // Aguarda mais um pouco para o vídeo carregar
                            Thread.sleep(5000)
                            
                        } catch (e: Exception) {
                            println("⚠️ Erro ao simular clique: ${e.message}")
                        }
                    }
                )
                
                try {
                    val clickResult = app.get(finalUrl, interceptor = clickResolver)
                    interceptedUrl = clickResult.url
                    
                    if (interceptedUrl.isNotEmpty() && interceptedUrl != finalUrl && interceptedUrl.contains("cf-master")) {
                        println("🎉 CF-MASTER ENCONTRADO APÓS CLIQUE!")
                        println("🔗 URL: ${interceptedUrl.take(150)}...")
                    } else {
                        println("⚠️ Nenhum cf-master após clique")
                    }
                    
                } catch (e: Exception) {
                    println("❌ Erro na simulação de clique: ${e.message}")
                }
            }
            
            // 7. TENTA MÚLTIPLOS CLICKS (como você descreveu: 3x)
            if (!interceptedUrl.contains("cf-master")) {
                println("\n🔄 TENTANDO MÚLTIPLOS CLICKS (3x)...")
                println("==================================================")
                
                val multipleClickResolver = WebViewResolver(
                    interceptUrl = cfMasterPattern,
                    additionalUrls = listOf(cfMasterPattern),
                    useOkhttp = false,
                    timeout = 20_000L, // 20 segundos para múltiplos cliques
                    onPageFinished = { webView ->
                        println("🎬 Simulando fluxo de cliques (3 tentativas)...")
                        
                        try {
                            // Fluxo que você descreveu: 3 cliques com pausas
                            for (attempt in 1..3) {
                                println("   👆 Tentativa $attempt/3")
                                
                                Thread.sleep(3000) // Espera 3s entre cliques
                                
                                // Simula clique no centro da página
                                webView.evaluateJavascript("""
                                    // Clique simples no body
                                    var event = new MouseEvent('click', {
                                        view: window,
                                        bubbles: true,
                                        cancelable: true,
                                        clientX: window.innerWidth / 2,
                                        clientY: window.innerHeight / 2
                                    });
                                    
                                    document.body.dispatchEvent(event);
                                    'click-attempt-' + $attempt;
                                """.trimIndent(), null)
                                
                                // Aguarda após cada clique
                                Thread.sleep(2000)
                            }
                            
                            // Aguarda mais tempo após os cliques
                            println("   ⏳ Aguardando carregamento do vídeo...")
                            Thread.sleep(5000)
                            
                        } catch (e: Exception) {
                            println("⚠️ Erro nos múltiplos cliques: ${e.message}")
                        }
                    }
                )
                
                try {
                    val multiClickResult = app.get(finalUrl, interceptor = multipleClickResolver)
                    interceptedUrl = multiClickResult.url
                    
                    if (interceptedUrl.isNotEmpty() && interceptedUrl != finalUrl && interceptedUrl.contains("cf-master")) {
                        println("🎉 CF-MASTER ENCONTRADO APÓS MÚLTIPLOS CLICKS!")
                        println("🔗 URL: ${interceptedUrl.take(150)}...")
                    } else {
                        println("❌ Nenhum cf-master após múltiplos cliques")
                    }
                    
                } catch (e: Exception) {
                    println("❌ Erro nos múltiplos cliques: ${e.message}")
                }
            }
            
            // 8. PROCESSAR A URL CF-MASTER ENCONTRADA
            if (interceptedUrl.isNotEmpty() && interceptedUrl.contains("cf-master")) {
                println("\n🎬 PROCESSANDO URL CF-MASTER...")
                println("==================================================")
                
                try {
                    val headers = mapOf(
                        "Accept" to "*/*",
                        "Connection" to "keep-alive",
                        "Referer" to finalUrl,
                        "Origin" to "https://png.strp2p.com",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                    )
                    
                    println("🔗 URL final: ${interceptedUrl.take(200)}...")
                    
                    // Processa como M3U8
                    M3u8Helper.generateM3u8(
                        source = "ChPlay",
                        streamUrl = interceptedUrl,
                        referer = "https://topanimes.net",
                        headers = headers
                    ).forEach(callback)
                    
                    println("✅ VÍDEO CF-MASTER PROCESSADO COM SUCESSO!")
                    return true
                    
                } catch (e: Exception) {
                    println("❌ Erro ao processar cf-master: ${e.message}")
                    e.printStackTrace()
                }
            }
            
            // 9. ÚLTIMA TENTATIVA: ANALISAR O HTML APÓS INTERAÇÃO
            println("\n🔍 ANALISANDO HTML APÓS TODAS AS TENTATIVAS...")
            println("==================================================")
            
            try {
                // Faz uma requisição normal para ver o HTML final
                val finalResponse = app.get(finalUrl)
                val finalHtml = finalResponse.text
                
                // Procura por cf-master no HTML
                val cfMasterRegex = Regex("""["'](https?://[^"']*cf-master[^"']*)["']""")
                val matches = cfMasterRegex.findAll(finalHtml)
                
                var found = false
                for (match in matches) {
                    val possibleUrl = match.groupValues[1]
                    println("🔍 Encontrado no HTML: ${possibleUrl.take(100)}...")
                    
                    if (possibleUrl.contains("cf-master")) {
                        interceptedUrl = possibleUrl
                        found = true
                        break
                    }
                }
                
                if (found) {
                    println("✅ CF-MASTER ENCONTRADO NO HTML FINAL!")
                    
                    val headers = mapOf(
                        "Accept" to "*/*",
                        "Connection" to "keep-alive",
                        "Referer" to finalUrl,
                        "Origin" to "https://png.strp2p.com",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                    )
                    
                    M3u8Helper.generateM3u8(
                        source = "ChPlay",
                        streamUrl = interceptedUrl,
                        referer = "https://topanimes.net",
                        headers = headers
                    ).forEach(callback)
                    
                    println("🎉 VÍDEO ENCONTRADO NO HTML!")
                    return true
                }
                
            } catch (e: Exception) {
                println("⚠️ Erro ao analisar HTML final: ${e.message}")
            }
            
            println("\n❌ NENHUM CF-MASTER ENCONTRADO")
            println("📝 Possíveis problemas:")
            println("   - O site requer interação humana real")
            println("   - Pode ter proteção contra bots")
            println("   - O WebView não está executando JavaScript corretamente")
            println("   - Pode precisar de mais tempo ou cliques diferentes")
            
            false
            
        } catch (e: Exception) {
            println("💥 ERRO GERAL: ${e.message}")
            e.printStackTrace()
            false
        }
    }
}
