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
        println("🔍 CHPLAY EXTRACTOR - BUSCANDO CF-MASTER COM CLIQUE")
        
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
            
            // 6. TENTATIVA 1: Interceptação com tempo maior
            println("🔄 Tentativa 1: Interceptação normal (15 segundos)")
            
            try {
                val resolver1 = WebViewResolver(
                    interceptUrl = cfMasterPattern,
                    additionalUrls = listOf(cfMasterPattern),
                    useOkhttp = false,
                    timeout = 15_000L  // 15 segundos
                )
                
                val result1 = app.get(finalUrl, interceptor = resolver1)
                interceptedUrl = result1.url
                
                if (interceptedUrl.isNotEmpty() && interceptedUrl != finalUrl && interceptedUrl.contains("cf-master")) {
                    println("✅ URL COM CF-MASTER ENCONTRADA!")
                    println("🔗 URL: ${interceptedUrl.take(150)}...")
                    
                    // Processa o vídeo
                    return processCfMaster(interceptedUrl, finalUrl, name, callback)
                } else {
                    println("⚠️ Nenhum cf-master interceptado (tentativa 1)")
                }
                
            } catch (e: Exception) {
                println("❌ Erro tentativa 1: ${e.message}")
            }
            
            // 7. TENTATIVA 2: JavaScript injection para simular cliques
            println("\n🔄 Tentativa 2: JavaScript para cliques (20 segundos)")
            
            try {
                // Primeiro carrega a página
                val pageResponse = app.get(finalUrl)
                println("📄 Página carregada: ${pageResponse.text.length} caracteres")
                
                // Cria um resolver com JavaScript injection
                val jsInjection = """
                    <script>
                    // Função para simular múltiplos cliques
                    function simulateClicks() {
                        console.log('Iniciando simulação de cliques...');
                        
                        // Tenta 3 cliques como descrito
                        for (let i = 1; i <= 3; i++) {
                            setTimeout(function() {
                                console.log('Clique ' + i + ' de 3');
                                
                                // Cria evento de clique
                                const clickEvent = new MouseEvent('click', {
                                    view: window,
                                    bubbles: true,
                                    cancelable: true,
                                    clientX: window.innerWidth / 2,
                                    clientY: window.innerHeight / 2
                                });
                                
                                // Dispara em vários elementos
                                const elements = [
                                    document.querySelector('video'),
                                    document.querySelector('iframe'),
                                    document.querySelector('.jwplayer'),
                                    document.querySelector('.player'),
                                    document.querySelector('body')
                                ];
                                
                                elements.forEach(el => {
                                    if (el) {
                                        el.dispatchEvent(clickEvent);
                                        console.log('Clique disparado em:', el.tagName);
                                    }
                                });
                                
                                // Tenta iniciar JWPlayer
                                if (typeof jwplayer !== 'undefined') {
                                    try {
                                        const players = jwplayer();
                                        if (players && players.length > 0) {
                                            players[0].play();
                                            console.log('JWPlayer iniciado');
                                        }
                                    } catch (e) {
                                        console.log('Erro JWPlayer:', e);
                                    }
                                }
                                
                            }, i * 3000); // 3 segundos entre cliques
                        }
                        
                        // Aguarda mais tempo após cliques
                        setTimeout(function() {
                            console.log('Cliques completados. Aguardando vídeo...');
                        }, 12000);
                    }
                    
                    // Executa quando a página carrega
                    if (document.readyState === 'loading') {
                        document.addEventListener('DOMContentLoaded', simulateClicks);
                    } else {
                        simulateClicks();
                    }
                    </script>
                """.trimIndent()
                
                // Combina o HTML original com o JavaScript
                val injectedHtml = pageResponse.text.replace("</body>", "$jsInjection</body>")
                    .replace("</head>", "$jsInjection</head>")
                
                // Cria um data URL com o HTML injetado
                val dataUrl = "data:text/html;charset=utf-8,${java.net.URLEncoder.encode(injectedHtml, "UTF-8")}"
                
                val resolver2 = WebViewResolver(
                    interceptUrl = cfMasterPattern,
                    additionalUrls = listOf(cfMasterPattern),
                    useOkhttp = false,
                    timeout = 20_000L  // 20 segundos para cliques
                )
                
                val result2 = app.get(dataUrl, interceptor = resolver2)
                interceptedUrl = result2.url
                
                if (interceptedUrl.isNotEmpty() && interceptedUrl != dataUrl && interceptedUrl.contains("cf-master")) {
                    println("✅ CF-MASTER ENCONTRADO APÓS CLIQUE VIA JS!")
                    println("🔗 URL: ${interceptedUrl.take(150)}...")
                    
                    return processCfMaster(interceptedUrl, finalUrl, name, callback)
                } else {
                    println("⚠️ Nenhum cf-master após JS injection")
                }
                
            } catch (e: Exception) {
                println("❌ Erro tentativa 2: ${e.message}")
            }
            
            // 8. TENTATIVA 3: Usar o endpoint de API que vimos no log
            println("\n🔄 Tentativa 3: Acessando API diretamente")
            
            // Extrai o ID da URL (ex: wdlhc de https://png.strp2p.com/#wdlhc&poster=...)
            val idMatch = Regex("""#(\w+)[&#]""").find(finalUrl)
            val videoId = idMatch?.groupValues?.get(1) ?: ""
            
            if (videoId.isNotEmpty()) {
                println("🔑 ID do vídeo encontrado: $videoId")
                
                val apiUrl = "https://png.strp2p.com/api/v1/info?id=$videoId"
                println("📡 API URL: $apiUrl")
                
                try {
                    val apiResponse = app.get(apiUrl, headers = mapOf(
                        "Referer" to finalUrl,
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                        "Accept" to "application/json, text/plain, */*"
                    ))
                    
                    println("📊 API Response status: ${apiResponse.code}")
                    val apiText = apiResponse.text
                    println("📄 API Response (${apiText.length} chars): ${apiText.take(500)}...")
                    
                    // Procura por cf-master na resposta da API
                    val cfMasterRegex = Regex("""["'](https?://[^"']*cf-master[^"']*)["']""")
                    val matches = cfMasterRegex.findAll(apiText)
                    
                    for (match in matches) {
                        val possibleUrl = match.groupValues[1]
                        println("🔍 Possível cf-master na API: ${possibleUrl.take(100)}...")
                        
                        if (possibleUrl.contains("cf-master")) {
                            interceptedUrl = possibleUrl
                            println("🎯 CF-MASTER ENCONTRADO NA API!")
                            
                            return processCfMaster(interceptedUrl, finalUrl, name, callback)
                        }
                    }
                    
                    // Se não encontrou cf-master direto, procura por outras chaves
                    val urlPatterns = listOf(
                        Regex(""""file":\s*"([^"]+)""""),
                        Regex(""""url":\s*"([^"]+)""""),
                        Regex(""""source":\s*"([^"]+)""""),
                        Regex(""""playback":\s*\{[^}]*"url":\s*"([^"]+)"""),
                        Regex(""","sources":\s*\[[^\]]*"file":\s*"([^"]+)""")
                    )
                    
                    for (pattern in urlPatterns) {
                        val urlMatch = pattern.find(apiText)
                        if (urlMatch != null) {
                            val foundUrl = urlMatch.groupValues[1]
                            println("🔗 URL encontrada na API: ${foundUrl.take(100)}...")
                            
                            // Testa esta URL
                            try {
                                val testResponse = app.get(foundUrl, headers = mapOf(
                                    "Referer" to finalUrl,
                                    "User-Agent" to "Mozilla/5.0"
                                ))
                                
                                val testText = testResponse.text
                                if (testText.contains("cf-master") || testText.contains(".m3u8")) {
                                    println("✅ URL válida encontrada via API!")
                                    return processCfMaster(foundUrl, finalUrl, name, callback)
                                }
                            } catch (e: Exception) {
                                // Continua procurando
                            }
                        }
                    }
                    
                } catch (e: Exception) {
                    println("❌ Erro na API: ${e.message}")
                }
            } else {
                println("⚠️ Não foi possível extrair ID do vídeo da URL")
            }
            
            // 9. TENTATIVA FINAL: Analisar todas as requisições possíveis
            println("\n🔄 Tentativa Final: Capturar todas as requisições")
            
            try {
                // Usa um padrão mais amplo para ver TUDO
                val catchAllPattern = Regex(""".*""")
                val finalResolver = WebViewResolver(
                    interceptUrl = catchAllPattern,
                    additionalUrls = listOf(catchAllPattern),
                    useOkhttp = false,
                    timeout = 15_000L
                )
                
                val allRequests = mutableListOf<String>()
                
                // Não podemos usar requestInterceptor, então apenas capturamos a URL final
                val finalResult = app.get(finalUrl, interceptor = finalResolver)
                interceptedUrl = finalResult.url
                
                println("📊 URL final da captura: ${interceptedUrl.take(200)}...")
                
                // Também analisa o HTML final
                val finalHtml = finalResult.text
                println("📄 HTML final: ${finalHtml.length} caracteres")
                
                // Procura por cf-master no HTML
                val htmlCfMaster = Regex("""(https?://[^\s"'<>]*cf-master[^\s"'<>]*)""").find(finalHtml)
                if (htmlCfMaster != null) {
                    interceptedUrl = htmlCfMaster.value
                    println("✅ CF-MASTER ENCONTRADO NO HTML FINAL!")
                    return processCfMaster(interceptedUrl, finalUrl, name, callback)
                }
                
                // Procura por qualquer URL de vídeo
                val videoUrls = listOf(
                    Regex("""(https?://[^\s"'<>]*\.m3u8[^\s"'<>]*)"""),
                    Regex("""(https?://[^\s"'<>]*\.mp4[^\s"'<>]*)"""),
                    Regex("""(https?://[^\s"'<>]*\.ts[^\s"'<>]*)"""),
                    Regex("""(https?://[^\s"'<>]*\/9a\/[^\s"'<>]*)"""),
                    Regex("""(https?://[^\s"'<>]*\/v\/[^\s"'<>]*)""")
                )
                
                for (pattern in videoUrls) {
                    val matches = pattern.findAll(finalHtml)
                    for (match in matches) {
                        val url = match.value
                        println("🔍 Possível URL de vídeo: ${url.take(100)}...")
                        
                        if (url.contains("cf-master")) {
                            println("🎯 CF-MASTER EM URL DE VÍDEO!")
                            return processCfMaster(url, finalUrl, name, callback)
                        }
                    }
                }
                
            } catch (e: Exception) {
                println("❌ Erro tentativa final: ${e.message}")
            }
            
            println("\n❌ NENHUM CF-MASTER ENCONTRADO APÓS TODAS AS TENTATIVAS")
            println("💡 Dicas:")
            println("   - O site pode bloquear WebView automatizado")
            println("   - Talvez precise de autenticação/cookies")
            println("   - Pode ser necessário usar um browser real")
            println("   - URL testada: $finalUrl")
            
            false
            
        } catch (e: Exception) {
            println("💥 ERRO GERAL: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    private suspend fun processCfMaster(
        cfMasterUrl: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("\n🎬 PROCESSANDO URL CF-MASTER...")
        println("🔗 URL: ${cfMasterUrl.take(200)}...")
        
        return try {
            val headers = mapOf(
                "Accept" to "*/*",
                "Connection" to "keep-alive",
                "Referer" to referer,
                "Origin" to "https://png.strp2p.com",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            )
            
            // Processa como M3U8
            M3u8Helper.generateM3u8(
                source = "ChPlay",
                streamUrl = cfMasterUrl,
                referer = "https://topanimes.net",
                headers = headers
            ).forEach(callback)
            
            println("✅ VÍDEO CF-MASTER PROCESSADO COM SUCESSO!")
            true
            
        } catch (e: Exception) {
            println("❌ Erro ao processar cf-master: ${e.message}")
            
            // Tenta como link direto
            try {
                val extractorLink = newExtractorLink(
                    source = "ChPlay",
                    name = "$name [cf-master]",
                    url = cfMasterUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "https://topanimes.net"
                    this.quality = 1080
                }
                
                callback(extractorLink)
                println("✅ Link cf-master enviado como M3U8 direto")
                true
            } catch (e2: Exception) {
                println("❌ Também falhou como link direto: ${e2.message}")
                false
            }
        }
    }
}
