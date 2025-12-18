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
            
            // ESTRATÉGIA: Capturar TODAS as requisições que contenham os links de vídeo
            val allUrls = mutableListOf<String>()
            
            // 1. Primeiro, usar WebView para interceptar TUDO que for lightspeedst.net
            println("🌐 Iniciando interceptação completa...")
            
            val resolver = WebViewResolver(
                interceptUrl = Regex("""lightspeedst\.net.*"""), // Captura TUDO do domínio
                useOkhttp = false,
                timeout = 20_000L
            )
            
            // 2. Fazer a requisição principal
            val response = app.get(url, interceptor = resolver)
            val intercepted = response.url
            
            println("📡 URL final após interceptação: $intercepted")
            
            // 3. AGORA: Fazer uma segunda requisição para SIMULAR O PLAY
            // Esta é a chave - precisamos disparar a requisição que gera os links
            println("🎬 Simulando clique no play...")
            
            // Primeiro, obter o HTML da página
            val doc = app.get(url).document
            
            // Procurar o botão de play REAL
            val playButton = findRealPlayButton(doc)
            
            if (playButton != null) {
                println("✅ Botão de play encontrado: $playButton")
                
                // Extrair a URL de ativação do vídeo
                val videoActivationUrl = extractVideoActivationUrl(doc, playButton)
                
                if (videoActivationUrl != null) {
                    println("🔗 URL de ativação do vídeo: $videoActivationUrl")
                    
                    // Fazer a requisição de ativação (simula o clique)
                    val activationResponse = app.get(videoActivationUrl, timeout = 10000)
                    
                    // Analisar a resposta para encontrar TODOS os links
                    extractAllLinksFromResponse(activationResponse.text, allUrls)
                }
            }
            
            // 4. Também procurar diretamente no HTML por padrões
            println("🔍 Buscando padrões no HTML...")
            
            val html = doc.html()
            val patterns = listOf(
                // Padrão para URLs de vídeo
                Regex("""https://lightspeedst\.net/s\d+/[^"'\s]+\.mp4"""),
                // Padrão para playlists ou manifestos
                Regex(""""url"\s*:\s*"([^"]+\.mp4)"""),
                // Padrão em scripts JavaScript
                Regex("""(https://lightspeedst\.net[^"']+\.mp4)""")
            )
            
            for (pattern in patterns) {
                val matches = pattern.findAll(html)
                matches.forEach { match ->
                    val foundUrl = match.value
                    if (foundUrl.contains("lightspeedst.net") && foundUrl.contains(".mp4")) {
                        println("🎯 Encontrado no HTML: ${foundUrl.take(80)}...")
                        allUrls.add(foundUrl)
                    }
                }
            }
            
            // 5. Se não encontrou nada, tentar buscar em scripts específicos
            if (allUrls.isEmpty()) {
                println("🔄 Buscando em scripts JavaScript...")
                
                val scripts = doc.select("script")
                for (script in scripts) {
                    val scriptContent = script.html()
                    if (scriptContent.contains("lightspeedst") && scriptContent.contains("mp4")) {
                        println("📜 Script com links encontrado")
                        
                        // Procurar arrays ou objetos JSON com URLs
                        val jsonPattern = Regex("""\[\s*"([^"]+\.mp4)"\s*(?:,\s*"([^"]+\.mp4)"\s*)*\]""")
                        val jsonMatches = jsonPattern.findAll(scriptContent)
                        
                        jsonMatches.forEach { jsonMatch ->
                            jsonMatch.groupValues.forEach { group ->
                                if (group.contains(".mp4")) {
                                    println("📦 URL em JSON: $group")
                                    allUrls.add(group)
                                }
                            }
                        }
                    }
                }
            }
            
            // 6. Processar e adicionar TODAS as URLs encontradas
            println("📊 Total de URLs encontradas: ${allUrls.size}")
            
            if (allUrls.isNotEmpty()) {
                // Remover duplicatas e classificar por qualidade
                val uniqueUrls = allUrls.distinct()
                println("✨ URLs únicas: ${uniqueUrls.size}")
                
                // Adicionar cada URL como uma qualidade separada
                for (videoUrl in uniqueUrls) {
                    val quality = extractQualityFromUrl(videoUrl)
                    val qualityName = getQualityDisplayName(quality)
                    
                    println("➕ Adicionando: $qualityName - ${videoUrl.take(80)}...")
                    
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name ($qualityName)",
                            url = videoUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "$mainUrl/"
                            this.quality = quality
                            this.headers = mapOf(
                                "Referer" to url,
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                            )
                        }
                    )
                }
                
                println("🎉 ${uniqueUrls.size} qualidades adicionadas com sucesso!")
                return true
            }
            
            println("❌ Nenhum link encontrado")
            false
            
        } catch (e: Exception) {
            println("💥 Erro: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    // ============ FUNÇÕES AUXILIARES ============
    
    private fun findRealPlayButton(doc: org.jsoup.nodes.Document): org.jsoup.nodes.Element? {
        // Procurar por botões de play comuns
        val selectors = listOf(
            "button[onclick*='play'], button[onclick*='Play']",
            "button:contains(Assistir), button:contains(Play)",
            "a[onclick*='play'], a[onclick*='Play']",
            "div[onclick*='play'], div[onclick*='Play']",
            "[data-action='play'], [data-url*='lightspeedst']",
            ".play-button, .btn-play, .video-play"
        )
        
        for (selector in selectors) {
            val elements = doc.select(selector)
            if (elements.isNotEmpty()) {
                return elements.first()
            }
        }
        
        return null
    }
    
    private fun extractVideoActivationUrl(doc: org.jsoup.nodes.Document, playButton: org.jsoup.nodes.Element): String? {
        // Extrair URL do onclick ou data attributes
        val onclick = playButton.attr("onclick")
        if (onclick.isNotEmpty()) {
            println("🔍 Analisando onclick: ${onclick.take(100)}...")
            
            // Padrões comuns em onclick
            val patterns = listOf(
                Regex("""['"](https://[^'"]+)['"]"""),
                Regex("""location\.href\s*=\s*['"]([^'"]+)['"]"""),
                Regex("""loadVideo\(['"]([^'"]+)['"]\)"""),
                Regex("""play\(['"]([^'"]+)['"]\)""")
            )
            
            for (pattern in patterns) {
                val match = pattern.find(onclick)
                if (match != null) {
                    val url = match.groupValues[1]
                    if (url.contains("lightspeedst")) {
                        return url
                    }
                }
            }
        }
        
        // Verificar data attributes
        val dataUrl = playButton.attr("data-url")
        if (dataUrl.isNotEmpty() && dataUrl.contains("lightspeedst")) {
            return dataUrl
        }
        
        // Verificar href
        val href = playButton.attr("href")
        if (href.isNotEmpty() && href.contains("lightspeedst")) {
            return href
        }
        
        return null
    }
    
    private fun extractAllLinksFromResponse(responseText: String, urlList: MutableList<String>) {
        // Procurar por múltiplos links na resposta
        val linkPatterns = listOf(
            Regex("""https://lightspeedst\.net/s\d+/[^"'\s]+\.mp4"""),
            Regex(""""url"\s*:\s*"([^"]+\.mp4)"""),
            Regex(""""src"\s*:\s*"([^"]+\.mp4)"""),
            Regex(""""file"\s*:\s*"([^"]+\.mp4)"""),
            Regex("""\["([^"]+\.mp4)"(?:,"([^"]+\.mp4)")*\]""")
        )
        
        for (pattern in linkPatterns) {
            val matches = pattern.findAll(responseText)
            matches.forEach { match ->
                // Adicionar todos os grupos (pode ter múltiplas URLs)
                match.groupValues.forEach { group ->
                    if (group.contains(".mp4") && group.contains("lightspeedst")) {
                        println("🔗 Extraído da resposta: ${group.take(80)}...")
                        urlList.add(group)
                    }
                }
            }
        }
    }
    
    private fun extractQualityFromUrl(url: String): Int {
        return when {
            url.contains("1080p") || url.contains("1080") -> 1080
            url.contains("720p") || url.contains("720") || url.contains("/hd/") -> 720
            url.contains("480p") || url.contains("480") || url.contains("/sd/") -> 480
            url.contains("360p") || url.contains("360") -> 360
            url.contains("240p") || url.contains("240") -> 240
            else -> 480 // Default
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
