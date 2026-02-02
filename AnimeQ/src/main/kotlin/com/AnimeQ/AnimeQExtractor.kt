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

    suspend fun extractVideoLinks(
        url: String,
        name: String = "Episódio",
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[AnimeQDebug] 🚀 Iniciando extração de vídeo")
        println("[AnimeQDebug] 📌 URL: $url")
        println("[AnimeQDebug] 📝 Nome: $name")
        
        return try {
            println("[AnimeQDebug] 🔄 1️⃣ Fazendo requisição para a página...")
            val pageResponse = app.get(url)
            println("[AnimeQDebug] 📊 Status code: ${pageResponse.code}")
            println("[AnimeQDebug] 📏 Tamanho da resposta: ${pageResponse.text.length} caracteres")
            
            val doc = org.jsoup.Jsoup.parse(pageResponse.text)
            println("[AnimeQDebug] ✅ Página parseada com sucesso")

            // Procurar por iframe do Blogger/YouTube
            println("[AnimeQDebug] 🔄 2️⃣ Procurando por iframes...")
            val iframe = doc.selectFirst("iframe[src*='blogger.com'], iframe[src*='youtube.com/embed'], iframe[src*='youtube.googleapis.com']")
            
            if (iframe != null) {
                val iframeUrl = iframe.attr("src")
                println("[AnimeQDebug] 🎯 IFRAME ENCONTRADO!")
                println("[AnimeQDebug] 🔗 URL do iframe: $iframeUrl")
                
                println("[AnimeQDebug] 📊 Estatísticas de iframes:")
                println("[AnimeQDebug]   📍 blogger.com: ${doc.select("iframe[src*='blogger.com']").size}")
                println("[AnimeQDebug]   📍 youtube.com/embed: ${doc.select("iframe[src*='youtube.com/embed']").size}")
                println("[AnimeQDebug]   📍 youtube.googleapis.com: ${doc.select("iframe[src*='youtube.googleapis.com']").size}")
                
                return extractFromBloggerIframe(iframeUrl, url, name, callback)
            } else {
                println("[AnimeQDebug] ⚠️ NENHUM IFRAME ENCONTRADO na página principal")
                println("[AnimeQDebug] 📊 Total de iframes na página: ${doc.select("iframe").size}")
                if (doc.select("iframe").isNotEmpty()) {
                    doc.select("iframe").forEachIndexed { index, frame ->
                        println("[AnimeQDebug]   ${index + 1}. ${frame.attr("src")}")
                    }
                }
                
                println("[AnimeQDebug] 🔄 3️⃣ Tentando extração direta da página...")
                return extractDirectFromPage(doc, url, name, callback)
            }
        } catch (e: Exception) {
            println("[AnimeQDebug] ❌ Falha na extração principal: ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    private suspend fun extractFromBloggerIframe(
        iframeUrl: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[AnimeQDebug] 🔍 Iniciando extração do iframe")
        println("[AnimeQDebug] 🎯 URL do iframe: $iframeUrl")
        println("[AnimeQDebug] 🔙 Referer: $referer")
        
        return try {
            val headers = mapOf(
                "Referer" to referer,
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "pt-BR,pt;q=0.9"
            )

            println("[AnimeQDebug] 🔄 1️⃣ Acessando conteúdo do iframe...")
            val iframeResponse = app.get(iframeUrl, headers = headers)
            println("[AnimeQDebug] 📊 Status do iframe: ${iframeResponse.code}")
            println("[AnimeQDebug] 📏 Tamanho HTML do iframe: ${iframeResponse.text.length}")
            
            val iframeHtml = iframeResponse.text

            println("[AnimeQDebug] 🔄 2️⃣ Procurando URLs do Google Video...")
            val videoPattern = """https?://[^"'\s<>]+googlevideo\.com/videoplayback[^"'\s<>]+""".toRegex()
            val matches = videoPattern.findAll(iframeHtml).toList()
            
            println("[AnimeQDebug] 🎯 URLs de videoplayback encontradas: ${matches.size}")
            
            if (matches.isNotEmpty()) {
                println("[AnimeQDebug] ✅ VÍDEOS ENCONTRADOS no iframe!")
                var found = false
                for ((index, match) in matches.distinct().withIndex()) {
                    val videoUrl = match.value
                    println("[AnimeQDebug] 🎬 Vídeo ${index + 1}: ${videoUrl.take(100)}...")
                    
                    // Extrair qualidade
                    val itagPattern = """[?&]itag=(\d+)""".toRegex()
                    val itagMatch = itagPattern.find(videoUrl)
                    val itag = itagMatch?.groupValues?.get(1)?.toIntOrNull() ?: 18
                    val quality = itagQualityMap[itag] ?: 360
                    val qualityLabel = getQualityLabel(quality)
                    
                    println("[AnimeQDebug]   📊 Qualidade: $quality")
                    println("[AnimeQDebug]   🏷️  iTag: $itag")
                    println("[AnimeQDebug]   🏷️  Label: $qualityLabel")

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

                    println("[AnimeQDebug] ✅ Link ${index + 1} criado com sucesso!")
                    callback(extractorLink)
                    found = true
                }
                println("[AnimeQDebug] 🎉 ${matches.distinct().size} links extraídos do iframe!")
                return found
            }

            println("[AnimeQDebug] 🔄 3️⃣ Procurando por URLs de vídeo em JavaScript...")
            val jsPattern = """(?i)(?:src|url|file|video_url)\s*[:=]\s*["'](https?://[^"'\s]+\.(?:mp4|m3u8|m4v|mov|webm|flv|avi))["']""".toRegex()
            val jsMatches = jsPattern.findAll(iframeHtml).toList()
            
            println("[AnimeQDebug] 🔍 URLs JS encontradas: ${jsMatches.size}")
            
            for ((index, match) in jsMatches.withIndex()) {
                val videoUrl = match.groupValues[1]
                println("[AnimeQDebug] 🎬 Vídeo JS encontrado ${index + 1}: $videoUrl")
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

                println("[AnimeQDebug] ✅ Link JS criado!")
                callback(extractorLink)
                return true
            }

            println("[AnimeQDebug] ⚠️ Nenhum vídeo encontrado no iframe")
            println("[AnimeQDebug] 🔄 4️⃣ Analisando HTML do iframe...")
            println("[AnimeQDebug] 📊 Primeiros 500 caracteres do HTML:")
            println("[AnimeQDebug] ${iframeHtml.take(500)}")
            println("[AnimeQDebug] 🔍 Procurando por palavras-chave...")
            
            val lowerHtml = iframeHtml.lowercase()
            println("[AnimeQDebug]   'video' aparece: ${lowerHtml.split("video").size - 1} vezes")
            println("[AnimeQDebug]   'src' aparece: ${lowerHtml.split("src").size - 1} vezes")
            println("[AnimeQDebug]   'http' aparece: ${lowerHtml.split("http").size - 1} vezes")
            
            return false
        } catch (e: Exception) {
            println("[AnimeQDebug] ❌ Falha na extração do iframe: ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    private suspend fun extractDirectFromPage(
        doc: org.jsoup.nodes.Document,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[AnimeQDebug] 🔍 Iniciando extração direta da página")
        println("[AnimeQDebug] 🔙 Referer: $referer")
        
        println("[AnimeQDebug] 🔄 1️⃣ Procurando em scripts...")
        val scripts = doc.select("script")
        println("[AnimeQDebug] 📊 Total de scripts encontrados: ${scripts.size}")
        
        for ((scriptIndex, script) in scripts.withIndex()) {
            val scriptText = script.html()
            if (scriptText.isNotEmpty()) {
                println("[AnimeQDebug] 📜 Script ${scriptIndex + 1}: ${scriptText.length} caracteres")
                
                // Procurar URLs do Google Video
                val videoPattern = """https?://[^"'\s<>]+googlevideo\.com/videoplayback[^"'\s<>]+""".toRegex()
                val matches = videoPattern.findAll(scriptText).toList()
                
                if (matches.isNotEmpty()) {
                    println("[AnimeQDebug] ✅ VÍDEOS ENCONTRADOS no script ${scriptIndex + 1}!")
                    println("[AnimeQDebug] 🎯 URLs encontradas: ${matches.size}")
                    
                    for ((index, match) in matches.distinct().withIndex()) {
                        val videoUrl = match.value
                        println("[AnimeQDebug] 🎬 Vídeo ${index + 1}: ${videoUrl.take(100)}...")
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

                        println("[AnimeQDebug] ✅ Link criado do script!")
                        callback(extractorLink)
                        return true
                    }
                }
                
                // Verificar se tem dados de vídeo
                val lowerScript = scriptText.lowercase()
                if (lowerScript.contains("video") || 
                    lowerScript.contains("mp4") || 
                    lowerScript.contains("m3u8")) {
                    println("[AnimeQDebug] 📝 Script ${scriptIndex + 1} contém referências de vídeo")
                }
            }
        }
        
        println("[AnimeQDebug] 🔄 2️⃣ Procurando por elementos de vídeo...")
        val videoTags = doc.select("video")
        val sourceTags = doc.select("source[src]")
        val embedTags = doc.select("embed[src]")
        val objectTags = doc.select("object[data]")
        
        println("[AnimeQDebug] 📊 Elementos de vídeo encontrados:")
        println("[AnimeQDebug]   🎥 <video>: ${videoTags.size}")
        println("[AnimeQDebug]   📍 <source>: ${sourceTags.size}")
        println("[AnimeQDebug]   📎 <embed>: ${embedTags.size}")
        println("[AnimeQDebug]   📦 <object>: ${objectTags.size}")
        
        if (videoTags.isNotEmpty() || sourceTags.isNotEmpty()) {
            println("[AnimeQDebug] ✅ Elementos de vídeo HTML5 encontrados!")
            
            // Verificar tags <source>
            for ((index, source) in sourceTags.withIndex()) {
                val src = source.attr("src")
                if (src.isNotBlank() && src.startsWith("http")) {
                    println("[AnimeQDebug] 🎬 Fonte ${index + 1}: $src")
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

                    println("[AnimeQDebug] ✅ Link de fonte criado!")
                    callback(extractorLink)
                    return true
                }
            }
        }
        
        println("[AnimeQDebug] ⚠️ Nenhum vídeo encontrado na extração direta")
        println("[AnimeQDebug] 📊 Resumo da página:")
        println("[AnimeQDebug]   🔗 Links totais: ${doc.select("a[href]").size}")
        println("[AnimeQDebug]   🖼️  Imagens: ${doc.select("img[src]").size}")
        println("[AnimeQDebug]   📄 Iframes: ${doc.select("iframe").size}")
        
        println("[AnimeQDebug] 🔄 3️⃣ Mostrando primeiros links encontrados...")
        val allLinks = doc.select("a[href]").take(10)
        allLinks.forEachIndexed { index, link ->
            val href = link.attr("href")
            if (href.contains("video", true) || href.contains("mp4", true) || href.contains("m3u8", true)) {
                println("[AnimeQDebug] 🔗 Link ${index + 1} (vídeo): $href")
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
