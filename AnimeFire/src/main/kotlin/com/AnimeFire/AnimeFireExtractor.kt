package com.AnimeFire

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.Jsoup

object AnimeFireExtractor {
    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🔗 AnimeFireExtractor: Extraindo de $url")
            
            // 1. PRIMEIRO: Acessar a página e encontrar os botões de clique
            println("📄 Acessando página inicial...")
            val initialResponse = app.get(url)
            val doc = initialResponse.document
            
            // 2. ENCONTRAR OS BOTÕES DE CLIQUE (geralmente há dois)
            println("🔍 Buscando botões de clique...")
            
            // Botão 1: "Clique aqui para carregar o player"
            val clickButton1 = findClickButton1(doc)
            println("🎯 Botão 1 encontrado: ${clickButton1 != null}")
            
            // Botão 2: "Assistir agora" ou "Play"
            val clickButton2 = findClickButton2(doc)
            println("🎯 Botão 2 encontrado: ${clickButton2 != null}")
            
            // 3. SIMULAR OS DOIS CLICKS SEQUENCIALMENTE
            var currentUrl = url
            
            // CLICK 1
            if (clickButton1 != null) {
                println("🖱️ Simulando CLIQUE 1...")
                currentUrl = simulateClick(doc, clickButton1, currentUrl) ?: currentUrl
                
                // Esperar um pouco após o primeiro clique
                kotlinx.coroutines.delay(1000)
            }
            
            // CLICK 2
            if (clickButton2 != null) {
                println("🖱️ Simulando CLIQUE 2...")
                currentUrl = simulateClick(doc, clickButton2, currentUrl) ?: currentUrl
                
                // Esperar um pouco após o segundo clique
                kotlinx.coroutines.delay(1000)
            }
            
            // 4. AGORA SIM: Interceptar com WebView APÓS os cliques
            println("🌐 Iniciando WebView APÓS cliques...")
            
            val streamResolver = WebViewResolver(
                interceptUrl = Regex("""lightspeedst\.net.*\.mp4"""),
                useOkhttp = false,
                timeout = 20_000L,
                onIntercept = { interceptedUrl ->
                    println("📡 Interceptado: $interceptedUrl")
                    true // Continue interceptando
                }
            )
            
            val response = app.get(currentUrl, interceptor = streamResolver)
            val intercepted = response.url
            
            println("🎯 URL final após cliques + interceptação: $intercepted")
            
            // 5. PROCESSAR URL INTERCEPTADA
            if (intercepted.isNotEmpty() && intercepted.contains("lightspeedst.net") && intercepted.contains(".mp4")) {
                println("✅ Link válido interceptado após cliques!")
                
                // Analisar estrutura: https://lightspeedst.net/s5/mp4_temp/nome-anime/1/480p.mp4
                val pattern = """https://lightspeedst\.net/s(\d+)/([^/]+)/([^/]+)/(\d+)/(\d+p)\.mp4""".toRegex()
                val match = pattern.find(intercepted)
                
                if (match != null) {
                    val serverNum = match.groupValues[1] // 5
                    val folder = match.groupValues[2] // mp4_temp
                    val animeName = match.groupValues[3] // nome-anime
                    val episodeNum = match.groupValues[4] // 1
                    val interceptedQuality = match.groupValues[5] // 480p
                    
                    println("📊 Estrutura detectada:")
                    println("   Servidor: s$serverNum")
                    println("   Pasta: $folder")
                    println("   Anime: $animeName")
                    println("   Episódio: $episodeNum")
                    println("   Qualidade interceptada: $interceptedQuality")
                    
                    // Construir base URL
                    val baseUrl = "https://lightspeedst.net/s$serverNum/$folder/$animeName/$episodeNum"
                    println("📁 Base correta: $baseUrl")
                    
                    // Gerar múltiplas qualidades
                    val qualities = listOf(
                        "1080p" to 1080,
                        "720p" to 720, 
                        "480p" to 480,
                        "360p" to 360
                    )
                    
                    var addedCount = 0
                    
                    for ((qualityName, qualityValue) in qualities) {
                        val videoUrl = "$baseUrl/$qualityName.mp4"
                        
                        println("➕ Tentando qualidade: $qualityName")
                        println("   URL: $videoUrl")
                        
                        // Verificar se a URL existe
                        val exists = checkUrlExists(videoUrl)
                        
                        if (exists) {
                            println("✅ Qualidade $qualityName disponível")
                            
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = "$name ($qualityName)",
                                    url = videoUrl,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = "$mainUrl/"
                                    this.quality = qualityValue
                                    this.headers = mapOf(
                                        "Referer" to url,
                                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                                        "Accept" to "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5",
                                        "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
                                        "Range" to "bytes=0-",
                                        "Sec-Fetch-Dest" to "video",
                                        "Sec-Fetch-Mode" to "no-cors",
                                        "Sec-Fetch-Site" to "cross-site"
                                    )
                                }
                            )
                            
                            addedCount++
                        } else {
                            println("❌ Qualidade $qualityName não disponível")
                        }
                    }
                    
                    if (addedCount > 0) {
                        println("🎉 $addedCount qualidades adicionadas com sucesso!")
                        return true
                    }
                }
            }
            
            // 6. FALLBACK: Tentar extrair do JavaScript diretamente
            println("🔄 Fallback: buscando links no JavaScript...")
            return extractFromJavaScript(doc, url, mainUrl, name, callback)
            
        } catch (e: Exception) {
            println("💥 Erro no processo: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    // ============ FUNÇÕES AUXILIARES ============
    
    private fun findClickButton1(doc: org.jsoup.nodes.Document): org.jsoup.nodes.Element? {
        // Primeiro botão: geralmente é um botão ou div com texto de ativação
        val selectors = listOf(
            "button:contains(Clique), button:contains(clique)",
            "a:contains(Clique), a:contains(clique)",
            "div:contains(Clique), div:contains(clique)",
            "#play-button, .play-btn, .btn-play",
            "[onclick*='play'], [onclick*='Play']",
            "[data-action='load-video'], [data-url*='video']",
            ".video-load, .load-player, .player-load"
        )
        
        for (selector in selectors) {
            val elements = doc.select(selector)
            if (elements.isNotEmpty()) {
                println("✅ Botão 1 encontrado com seletor: $selector")
                return elements.first()
            }
        }
        
        return null
    }
    
    private fun findClickButton2(doc: org.jsoup.nodes.Document): org.jsoup.nodes.Element? {
        // Segundo botão: "Assistir", "Play", etc.
        val selectors = listOf(
            "button:contains(Assistir), button:contains(assistir)",
            "button:contains(Play), button:contains(play)",
            "a:contains(Assistir), a:contains(assistir)",
            "a:contains(Play), a:contains(play)",
            ".watch-btn, .watch-button, .assistir-btn",
            "[id*='play'], [class*='play']",
            "video, .video-player, .player-container"
        )
        
        for (selector in selectors) {
            val elements = doc.select(selector)
            if (elements.isNotEmpty()) {
                println("✅ Botão 2 encontrado com seletor: $selector")
                return elements.first()
            }
        }
        
        return null
    }
    
    private fun simulateClick(
        doc: org.jsoup.nodes.Document, 
        button: org.jsoup.nodes.Element,
        currentUrl: String
    ): String? {
        return try {
            // Extrair URL do onclick ou data attributes
            val onclick = button.attr("onclick")
            val dataUrl = button.attr("data-url")
            val href = button.attr("href")
            
            println("🔍 Analisando botão:")
            println("   onclick: ${onclick.take(100)}...")
            println("   data-url: $dataUrl")
            println("   href: $href")
            
            // Verificar data-url primeiro
            if (dataUrl.isNotEmpty()) {
                var urlToCall = dataUrl
                if (urlToCall.startsWith("/")) {
                    urlToCall = "https://animefire.io$urlToCall"
                } else if (!urlToCall.startsWith("http")) {
                    urlToCall = "https://animefire.io/$urlToCall"
                }
                
                println("🖱️ Fazendo requisição para data-url: $urlToCall")
                val response = app.get(urlToCall, timeout = 10000)
                println("📥 Resposta: ${response.code}")
                
                return urlToCall
            }
            
            // Verificar href
            if (href.isNotEmpty() && href != "#" && href != "javascript:void(0)") {
                var urlToCall = href
                if (urlToCall.startsWith("/")) {
                    urlToCall = "https://animefire.io$urlToCall"
                } else if (!urlToCall.startsWith("http")) {
                    urlToCall = "https://animefire.io/$urlToCall"
                }
                
                println("🖱️ Fazendo requisição para href: $urlToCall")
                val response = app.get(urlToCall, timeout = 10000)
                println("📥 Resposta: ${response.code}")
                
                return urlToCall
            }
            
            // Analisar onclick para extrair URL
            if (onclick.isNotEmpty()) {
                // Padrões comuns em onclick
                val patterns = listOf(
                    Regex("""location\.href\s*=\s*['"]([^'"]+)['"]"""),
                    Regex("""window\.open\(['"]([^'"]+)['"]"""),
                    Regex("""loadVideo\(['"]([^'"]+)['"]"""),
                    Regex("""play\(['"]([^'"]+)['"]"""),
                    Regex("""['"](https?://[^'"]+)['"]""")
                )
                
                for (pattern in patterns) {
                    val match = pattern.find(onclick)
                    if (match != null) {
                        var urlToCall = match.groupValues[1]
                        
                        if (urlToCall.startsWith("/")) {
                            urlToCall = "https://animefire.io$urlToCall"
                        } else if (!urlToCall.startsWith("http")) {
                            urlToCall = "https://animefire.io/$urlToCall"
                        }
                        
                        println("🖱️ Fazendo requisição para onclick: $urlToCall")
                        val response = app.get(urlToCall, timeout = 10000)
                        println("📥 Resposta: ${response.code}")
                        
                        return urlToCall
                    }
                }
            }
            
            // Se não encontrou URL específica, retorna URL atual
            println("⚠️ Nenhuma URL específica encontrada no botão")
            currentUrl
            
        } catch (e: Exception) {
            println("❌ Erro ao simular clique: ${e.message}")
            currentUrl
        }
    }
    
    private suspend fun checkUrlExists(url: String): Boolean {
        return try {
            val response = app.head(url, timeout = 5000)
            response.code in 200..299
        } catch (e: Exception) {
            false
        }
    }
    
    private fun extractFromJavaScript(
        doc: org.jsoup.nodes.Document,
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🔍 Extraindo links do JavaScript...")
            
            val scripts = doc.select("script")
            var foundUrls = mutableListOf<String>()
            
            for (script in scripts) {
                val content = script.html()
                if (content.contains("lightspeedst") && content.contains("mp4")) {
                    println("📜 Script com links encontrado")
                    
                    // Padrões para múltiplas qualidades
                    val patterns = listOf(
                        Regex("""https://lightspeedst\.net/s\d+/[^"'\s]+\.mp4"""),
                        Regex("""['"](https://lightspeedst\.net[^"']+\.mp4)['"]"""),
                        Regex(""""file"\s*:\s*"([^"]+\.mp4)"""),
                        Regex(""""url"\s*:\s*"([^"]+\.mp4)"""),
                        Regex("""\["([^"]+\.mp4)"(?:,"([^"]+\.mp4)")*\]""")
                    )
                    
                    for (pattern in patterns) {
                        val matches = pattern.findAll(content)
                        matches.forEach { match ->
                            match.groupValues.forEach { group ->
                                if (group.contains("lightspeedst.net") && group.contains(".mp4")) {
                                    val cleanUrl = group.replace("\"", "").replace("'", "")
                                    println("🔗 Encontrado: $cleanUrl")
                                    foundUrls.add(cleanUrl)
                                }
                            }
                        }
                    }
                }
            }
            
            // Adicionar URLs encontradas
            val uniqueUrls = foundUrls.distinct()
            
            if (uniqueUrls.isNotEmpty()) {
                println("✅ ${uniqueUrls.size} URLs encontradas no JavaScript")
                
                uniqueUrls.forEach { videoUrl ->
                    val quality = extractQualityFromUrl(videoUrl)
                    val qualityName = getQualityDisplayName(quality)
                    
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name ($qualityName)",
                            url = videoUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "$mainUrl/"
                            this.quality = quality
                        }
                    )
                }
                
                return true
            }
            
            false
            
        } catch (e: Exception) {
            println("⚠️ Erro no JavaScript: ${e.message}")
            false
        }
    }
    
    private fun extractQualityFromUrl(url: String): Int {
        return when {
            url.contains("1080p") || url.contains("1080") -> 1080
            url.contains("720p") || url.contains("720") -> 720
            url.contains("480p") || url.contains("480") -> 480
            url.contains("360p") || url.contains("360") -> 360
            url.contains("240p") || url.contains("240") -> 240
            else -> 480
        }
    }
    
    private fun getQualityDisplayName(quality: Int): String {
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
