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
        println("[AnimeQ] 🚀 Iniciando extração para: $url")
        
        return try {
            // 1. Buscar a página do episódio
            println("[AnimeQ] 📄 Obtendo página...")
            val pageResponse = app.get(url)
            val doc = org.jsoup.Jsoup.parse(pageResponse.text)
            
            // 2. Procurar EXATAMENTE ESSE IFRAME com src do Blogger
            println("[AnimeQ] 🔍 Procurando iframe do Blogger...")
            
            // Padrão específico do AnimeQ
            val iframeSelectors = listOf(
                "iframe.metaframe.rptss[src*='blogger.com/video.g']",
                "iframe[src*='blogger.com/video.g']",
                "iframe[src*='blogger.com']",
                "#dooplay_player_response iframe",
                ".pframe iframe",
                ".play.isnd iframe"
            )
            
            var bloggerUrl: String? = null
            
            for (selector in iframeSelectors) {
                val iframe = doc.selectFirst(selector)
                if (iframe != null) {
                    val src = iframe.attr("src")
                    if (src.contains("blogger.com/video.g")) {
                        bloggerUrl = src
                        println("[AnimeQ] ✅ IFRAME ENCONTRADO com seletor: $selector")
                        println("[AnimeQ] 🔗 URL do Blogger: ${src.take(100)}...")
                        break
                    }
                }
            }
            
            if (bloggerUrl == null) {
                // Tentar método alternativo: procurar em todos os iframes
                println("[AnimeQ] 🔍 Procurando em todos os iframes...")
                val allIframes = doc.select("iframe[src]")
                println("[AnimeQ] 📊 Total de iframes encontrados: ${allIframes.size}")
                
                allIframes.forEachIndexed { index, iframe ->
                    val src = iframe.attr("src")
                    if (src.contains("blogger.com/video.g")) {
                        bloggerUrl = src
                        println("[AnimeQ] ✅ IFRAME encontrado no índice $index")
                        println("[AnimeQ] 🔗 URL: ${src.take(100)}...")
                    }
                }
            }
            
            if (bloggerUrl != null) {
                // 3. Extrair vídeos da URL do Blogger (igual ao AnimeFire)
                return extractFromBloggerUrl(bloggerUrl, url, name, callback)
            }
            
            // 4. Se não encontrou, verificar se há elementos com data-src ou outros atributos
            println("[AnimeQ] 🔍 Procurando em data-src e outros atributos...")
            val dataElements = doc.select("[data-src*='blogger.com'], [data-url*='blogger.com']")
            dataElements.forEach { element ->
                val dataSrc = element.attr("data-src")
                val dataUrl = element.attr("data-url")
                
                if (dataSrc.contains("blogger.com/video.g")) {
                    bloggerUrl = dataSrc
                    println("[AnimeQ] ✅ URL encontrada em data-src")
                } else if (dataUrl.contains("blogger.com/video.g")) {
                    bloggerUrl = dataUrl
                    println("[AnimeQ] ✅ URL encontrada em data-url")
                }
            }
            
            if (bloggerUrl != null) {
                return extractFromBloggerUrl(bloggerUrl, url, name, callback)
            }
            
            // 5. Último recurso: procurar por URLs do Blogger em scripts
            println("[AnimeQ] 🔍 Procurando em scripts...")
            val scripts = doc.select("script")
            for (script in scripts) {
                val scriptText = script.html()
                val bloggerPattern = """(https?://[^"'\s]+blogger\.com/video\.g[^"'\s]+)""".toRegex()
                val match = bloggerPattern.find(scriptText)
                
                if (match != null) {
                    bloggerUrl = match.groupValues[1]
                    println("[AnimeQ] ✅ URL encontrada em script")
                    println("[AnimeQ] 🔗 ${bloggerUrl?.take(100)}...")
                    break
                }
            }
            
            if (bloggerUrl != null) {
                return extractFromBloggerUrl(bloggerUrl, url, name, callback)
            }
            
            println("[AnimeQ] ❌ Nenhuma URL do Blogger encontrada")
            println("[AnimeQ] 📊 Estatísticas da página:")
            println("[AnimeQ]   🔗 Iframes totais: ${doc.select("iframe").size}")
            println("[AnimeQ]   📜 Scripts: ${scripts.size}")
            println("[AnimeQ]   🖼️  Imagens: ${doc.select("img").size}")
            
            return false
        } catch (e: Exception) {
            println("[AnimeQ] ❌ Erro na extração: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
    
    private suspend fun extractFromBloggerUrl(
        bloggerUrl: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[AnimeQ] 🎬 Extraindo da URL do Blogger")
        println("[AnimeQ] 🔗 URL: ${bloggerUrl.take(80)}...")
        
        return try {
            // Headers necessários
            val headers = mapOf(
                "Referer" to referer,
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "pt-BR,pt;q=0.9",
                "Origin" to "https://www.blogger.com"
            )
            
            // Acessar a URL do Blogger
            println("[AnimeQ] 🔄 Acessando Blogger...")
            val response = app.get(bloggerUrl, headers = headers)
            println("[AnimeQ] 📊 Status: ${response.code}")
            
            // Procurar URLs do Google Video
            val videoPattern = """https?://[^"'\s<>]+googlevideo\.com/videoplayback[^"'\s<>]+""".toRegex()
            val matches = videoPattern.findAll(response.text).toList()
            
            if (matches.isNotEmpty()) {
                println("[AnimeQ] ✅ ${matches.size} vídeos encontrados!")
                
                var found = false
                for ((index, match) in matches.distinct().withIndex()) {
                    val videoUrl = match.value
                    println("[AnimeQ] 🎬 Vídeo ${index + 1}: ${videoUrl.take(80)}...")
                    
                    // Extrair qualidade
                    val itagPattern = """[?&]itag=(\d+)""".toRegex()
                    val itagMatch = itagPattern.find(videoUrl)
                    val itag = itagMatch?.groupValues?.get(1)?.toIntOrNull() ?: 18
                    val quality = itagQualityMap[itag] ?: 360
                    val qualityLabel = getQualityLabel(quality)
                    
                    println("[AnimeQ]   🏷️  iTag: $itag")
                    println("[AnimeQ]   📊 Qualidade: $quality")
                    println("[AnimeQ]   🏷️  Label: $qualityLabel")
                    
                    // Criar link
                    val extractorLink = newExtractorLink(
                        source = "AnimeQ",
                        name = "$name ($qualityLabel)",
                        url = videoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = bloggerUrl
                        this.quality = quality
                        this.headers = mapOf(
                            "Referer" to bloggerUrl,
                            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                            "Origin" to "https://www.blogger.com"
                        )
                    }
                    
                    callback(extractorLink)
                    found = true
                }
                
                return found
            }
            
            println("[AnimeQ] ⚠️ Nenhum vídeo encontrado na resposta do Blogger")
            return false
        } catch (e: Exception) {
            println("[AnimeQ] ❌ Erro ao extrair do Blogger: ${e.message}")
            return false
        }
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
