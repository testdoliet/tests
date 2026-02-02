package com.AnimeQ

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType

object AnimeQVideoExtractor {
    private val itagQualityMap = mapOf(
        18 to 360,
        22 to 720,
        37 to 1080,
        59 to 480,
        43 to 360,
        44 to 480,
        45 to 720,
        46 to 1080,
        38 to 3072,
        266 to 2160,
        138 to 2160,
        313 to 2160,
    )

    // Configuração de debug
    private var debugEnabled = false
    
    fun enableDebug() {
        debugEnabled = true
        debugLog("🚀 DEBUG ATIVADO")
    }
    
    fun disableDebug() {
        debugEnabled = false
    }

    private fun debugLog(message: String) {
        if (debugEnabled) {
            println("[AnimeQDebug] $message")
        }
    }

    private fun debugError(message: String, e: Exception? = null) {
        if (debugEnabled) {
            println("[AnimeQDebug] ❌ ERRO: $message")
            e?.printStackTrace()
        }
    }

    private fun debugSuccess(message: String) {
        if (debugEnabled) {
            println("[AnimeQDebug] ✅ SUCESSO: $message")
        }
    }

    private fun debugWarning(message: String) {
        if (debugEnabled) {
            println("[AnimeQDebug] ⚠️ ALERTA: $message")
        }
    }

    private fun debugInfo(message: String) {
        if (debugEnabled) {
            println("[AnimeQDebug] ℹ️ INFO: $message")
        }
    }

    private fun debugStep(message: String) {
        if (debugEnabled) {
            println("[AnimeQDebug] 🔄 ETAPA: $message")
        }
    }

    suspend fun extractVideoLinks(
        url: String,
        name: String = "Episódio",
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        debugLog("🚀 Iniciando extração de vídeo")
        debugInfo("📌 URL: $url")
        debugInfo("📝 Nome: $name")
        
        return try {
            debugStep("1️⃣ Fazendo requisição para a página...")
            val pageResponse = app.get(url)
            debugInfo("📊 Status code: ${pageResponse.code}")
            debugInfo("📏 Tamanho da resposta: ${pageResponse.text.length} caracteres")
            
            val doc = org.jsoup.Jsoup.parse(pageResponse.text)
            debugSuccess("✅ Página parseada com sucesso")

            // Procurar por iframe do Blogger/YouTube
            debugStep("2️⃣ Procurando por iframes...")
            val iframe = doc.selectFirst("iframe[src*='blogger.com'], iframe[src*='youtube.com/embed'], iframe[src*='youtube.googleapis.com']")
            
            if (iframe != null) {
                val iframeUrl = iframe.attr("src")
                debugSuccess("🎯 IFRAME ENCONTRADO!")
                debugInfo("🔗 URL do iframe: $iframeUrl")
                
                debugInfo("📊 Estatísticas de iframes:")
                debugInfo("  📍 blogger.com: ${doc.select("iframe[src*='blogger.com']").size}")
                debugInfo("  📍 youtube.com/embed: ${doc.select("iframe[src*='youtube.com/embed']").size}")
                debugInfo("  📍 youtube.googleapis.com: ${doc.select("iframe[src*='youtube.googleapis.com']").size}")
                
                return extractFromBloggerIframe(iframeUrl, url, name, callback)
            } else {
                debugWarning("⚠️ NENHUM IFRAME ENCONTRADO na página principal")
                debugInfo("📊 Total de iframes na página: ${doc.select("iframe").size}")
                if (doc.select("iframe").isNotEmpty()) {
                    doc.select("iframe").forEachIndexed { index, frame ->
                        debugInfo("  ${index + 1}. ${frame.attr("src")}")
                    }
                }
                
                debugStep("3️⃣ Tentando extração direta da página...")
                return extractDirectFromPage(doc, url, name, callback)
            }
        } catch (e: Exception) {
            debugError("❌ Falha na extração principal", e)
            return false
        }
    }

    private suspend fun extractFromBloggerIframe(
        iframeUrl: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        debugLog("🔍 Iniciando extração do iframe")
        debugInfo("🎯 URL do iframe: $iframeUrl")
        debugInfo("🔙 Referer: $referer")
        
        return try {
            val headers = mapOf(
                "Referer" to referer,
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "pt-BR,pt;q=0.9"
            )

            debugStep("1️⃣ Acessando conteúdo do iframe...")
            val iframeResponse = app.get(iframeUrl, headers = headers)
            debugInfo("📊 Status do iframe: ${iframeResponse.code}")
            debugInfo("📏 Tamanho HTML do iframe: ${iframeResponse.text.length}")
            
            val iframeHtml = iframeResponse.text

            debugStep("2️⃣ Procurando URLs do Google Video...")
            val videoPattern = """https?://[^"'\s<>]+googlevideo\.com/videoplayback[^"'\s<>]+""".toRegex()
            val matches = videoPattern.findAll(iframeHtml).toList()
            
            debugInfo("🎯 URLs de videoplayback encontradas: ${matches.size}")
            
            if (matches.isNotEmpty()) {
                debugSuccess("✅ VÍDEOS ENCONTRADOS no iframe!")
                var found = false
                for ((index, match) in matches.distinct().withIndex()) {
                    val videoUrl = match.value
                    debugInfo("🎬 Vídeo ${index + 1}: ${videoUrl.take(100)}...")
                    
                    // Extrair qualidade
                    val itagPattern = """[?&]itag=(\d+)""".toRegex()
                    val itagMatch = itagPattern.find(videoUrl)
                    val itag = itagMatch?.groupValues?.get(1)?.toIntOrNull() ?: 18
                    val quality = itagQualityMap[itag] ?: 360
                    val qualityLabel = getQualityLabel(quality)
                    
                    debugInfo("  📊 Qualidade: $quality")
                    debugInfo("  🏷️  iTag: $itag")
                    debugInfo("  🏷️  Label: $qualityLabel")

                    val extractorLink = newExtractorLink(
                        source = "AnimeQ",
                        name = "$name ($qualityLabel)",
                        url = videoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = iframeUrl
                        this.quality = quality
                        this.headers = mapOf(
                            "Referer" to iframeUrl,
                            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                            "Origin" to "https://www.blogger.com"
                        )
                    }

                    debugSuccess("✅ Link ${index + 1} criado com sucesso!")
                    callback(extractorLink)
                    found = true
                }
                debugSuccess("🎉 ${matches.distinct().size} links extraídos do iframe!")
                return found
            }

            debugStep("3️⃣ Procurando por URLs de vídeo em JavaScript...")
            val jsPattern = """(?i)(?:src|url|file|video_url)\s*[:=]\s*["'](https?://[^"'\s]+\.(?:mp4|m3u8|m4v|mov|webm|flv|avi))["']""".toRegex()
            val jsMatches = jsPattern.findAll(iframeHtml).toList()
            
            debugInfo("🔍 URLs JS encontradas: ${jsMatches.size}")
            
            for ((index, match) in jsMatches.withIndex()) {
                val videoUrl = match.groupValues[1]
                debugSuccess("🎬 Vídeo JS encontrado ${index + 1}: $videoUrl")
                val quality = 720 // Default
                val qualityLabel = getQualityLabel(quality)

                val extractorLink = newExtractorLink(
                    source = "AnimeQ",
                    name = "$name ($qualityLabel)",
                    url = videoUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = iframeUrl
                    this.quality = quality
                    this.headers = mapOf(
                        "Referer" to iframeUrl,
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
                    )
                }

                debugSuccess("✅ Link JS criado!")
                callback(extractorLink)
                return true
            }

            debugWarning("⚠️ Nenhum vídeo encontrado no iframe")
            debugStep("4️⃣ Analisando HTML do iframe...")
            debugInfo("📊 Primeiros 500 caracteres do HTML:")
            debugInfo(iframeHtml.take(500))
            debugInfo("🔍 Procurando por palavras-chave...")
            
            // CORREÇÃO AQUI: usar count() com lambda em vez de comparar Char com String
            val lowerHtml = iframeHtml.lowercase()
            debugInfo("  'video' aparece: ${lowerHtml.count { it == 'v' } + lowerHtml.count { it == 'i' } + lowerHtml.count { it == 'd' } + lowerHtml.count { it == 'e' } + lowerHtml.count { it == 'o' }} vezes (aprox.)")
            debugInfo("  'src' aparece: ${lowerHtml.count { it == 's' } + lowerHtml.count { it == 'r' } + lowerHtml.count { it == 'c' }} vezes (aprox.)")
            debugInfo("  'http' aparece: ${lowerHtml.count { it == 'h' } + lowerHtml.count { it == 't' } + lowerHtml.count { it == 't' } + lowerHtml.count { it == 'p' }} vezes (aprox.)")
            
            // Contagem mais precisa usando split
            debugInfo("  String 'video' aparece: ${lowerHtml.split("video").size - 1} vezes")
            debugInfo("  String 'src' aparece: ${lowerHtml.split("src").size - 1} vezes")
            debugInfo("  String 'http' aparece: ${lowerHtml.split("http").size - 1} vezes")
            
            return false
        } catch (e: Exception) {
            debugError("❌ Falha na extração do iframe", e)
            return false
        }
    }

    private suspend fun extractDirectFromPage(
        doc: org.jsoup.nodes.Document,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        debugLog("🔍 Iniciando extração direta da página")
        debugInfo("🔙 Referer: $referer")
        
        debugStep("1️⃣ Procurando em scripts...")
        val scripts = doc.select("script")
        debugInfo("📊 Total de scripts encontrados: ${scripts.size}")
        
        for ((scriptIndex, script) in scripts.withIndex()) {
            val scriptText = script.html()
            if (scriptText.isNotEmpty()) {
                debugInfo("📜 Script ${scriptIndex + 1}: ${scriptText.length} caracteres")
                
                // Procurar URLs do Google Video
                val videoPattern = """https?://[^"'\s<>]+googlevideo\.com/videoplayback[^"'\s<>]+""".toRegex()
                val matches = videoPattern.findAll(scriptText).toList()
                
                if (matches.isNotEmpty()) {
                    debugSuccess("✅ VÍDEOS ENCONTRADOS no script ${scriptIndex + 1}!")
                    debugInfo("🎯 URLs encontradas: ${matches.size}")
                    
                    for ((index, match) in matches.distinct().withIndex()) {
                        val videoUrl = match.value
                        debugInfo("🎬 Vídeo ${index + 1}: ${videoUrl.take(100)}...")
                        val itag = 18 // Default
                        val quality = itagQualityMap[itag] ?: 360
                        val qualityLabel = getQualityLabel(quality)

                        val extractorLink = newExtractorLink(
                            source = "AnimeQ",
                            name = "$name ($qualityLabel)",
                            url = videoUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = referer
                            this.quality = quality
                            this.headers = mapOf(
                                "Referer" to referer,
                                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
                            )
                        }

                        debugSuccess("✅ Link criado do script!")
                        callback(extractorLink)
                        return true
                    }
                }
                
                // Verificar se tem dados de vídeo
                val lowerScript = scriptText.lowercase()
                if (lowerScript.contains("video") || 
                    lowerScript.contains("mp4") || 
                    lowerScript.contains("m3u8")) {
                    debugInfo("📝 Script ${scriptIndex + 1} contém referências de vídeo")
                }
            }
        }
        
        debugStep("2️⃣ Procurando por elementos de vídeo...")
        val videoTags = doc.select("video")
        val sourceTags = doc.select("source[src]")
        val embedTags = doc.select("embed[src]")
        val objectTags = doc.select("object[data]")
        
        debugInfo("📊 Elementos de vídeo encontrados:")
        debugInfo("  🎥 <video>: ${videoTags.size}")
        debugInfo("  📍 <source>: ${sourceTags.size}")
        debugInfo("  📎 <embed>: ${embedTags.size}")
        debugInfo("  📦 <object>: ${objectTags.size}")
        
        if (videoTags.isNotEmpty() || sourceTags.isNotEmpty()) {
            debugSuccess("✅ Elementos de vídeo HTML5 encontrados!")
            
            // Verificar tags <source>
            for ((index, source) in sourceTags.withIndex()) {
                val src = source.attr("src")
                if (src.isNotBlank() && src.startsWith("http")) {
                    debugSuccess("🎬 Fonte ${index + 1}: $src")
                    val quality = 720
                    val qualityLabel = getQualityLabel(quality)

                    val extractorLink = newExtractorLink(
                        source = "AnimeQ",
                        name = "$name ($qualityLabel)",
                        url = src,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = referer
                        this.quality = quality
                        this.headers = mapOf(
                            "Referer" to referer,
                            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
                        )
                    }

                    debugSuccess("✅ Link de fonte criado!")
                    callback(extractorLink)
                    return true
                }
            }
        }
        
        debugWarning("⚠️ Nenhum vídeo encontrado na extração direta")
        debugInfo("📊 Resumo da página:")
        debugInfo("  🔗 Links totais: ${doc.select("a[href]").size}")
        debugInfo("  🖼️  Imagens: ${doc.select("img[src]").size}")
        debugInfo("  📄 Iframes: ${doc.select("iframe").size}")
        
        debugStep("3️⃣ Mostrando primeiros links encontrados...")
        val allLinks = doc.select("a[href]").take(10)
        allLinks.forEachIndexed { index, link ->
            val href = link.attr("href")
            if (href.contains("video", true) || href.contains("mp4", true) || href.contains("m3u8", true)) {
                debugInfo("🔗 Link ${index + 1} (vídeo): $href")
            }
        }
        
        return false
    }

    private fun getQualityLabel(quality: Int): String {
        return when {
            quality >= 2160 -> "4K 🏆"
            quality >= 1080 -> "FHD 🔥"
            quality >= 720 -> "HD ⭐"
            quality >= 480 -> "SD 📺"
            else -> "SD 📺"
        }
    }
}
