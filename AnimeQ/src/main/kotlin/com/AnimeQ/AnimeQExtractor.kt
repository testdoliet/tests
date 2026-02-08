package com.AnimeQ

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.json.JSONObject

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
            val html = pageResponse.text
            
            // DEBUG: Mostrar parte do HTML para ver o que foi retornado
            println("[AnimeQ] 📊 Tamanho do HTML: ${html.length} caracteres")
            println("[AnimeQ] 🔍 Mostrando primeiros 1000 caracteres do HTML:")
            println("=" * 80)
            println(html.take(30000))
            println("=" * 80)
            
            // DEBUG: Procurar por palavras-chave específicas
            println("[AnimeQ] 🔍 Procurando palavras-chave no HTML:")
            val keywords = listOf("blogger", "iframe", "video.g", "dooplay_player", "playcontainer", "metaframe", "pframe")
            keywords.forEach { keyword ->
                val count = html.count { char -> 
                    keyword.lowercase().toCharArray().contains(char.lowercaseChar())
                }
                println("[AnimeQ]   '$keyword': encontrado $count vezes")
            }
            
            // Verificar se há iframe no HTML
            val hasIframe = html.contains("<iframe", ignoreCase = true)
            println("[AnimeQ] 📊 Tem tag <iframe>? $hasIframe")
            
            if (hasIframe) {
                // Extrair todas as tags iframe para debug
                val iframePattern = """<iframe[^>]*>""".toRegex()
                val iframes = iframePattern.findAll(html).toList()
                println("[AnimeQ] 🔍 Tags iframe encontradas: ${iframes.size}")
                iframes.forEachIndexed { index, match ->
                    println("[AnimeQ]   Iframe $index: ${match.value.take(150)}...")
                }
            }
            
            // 2. Busca ABRANGENTE em TODO o HTML - MELHORADO
            println("[AnimeQ] 🔍 Procurando URLs do Blogger em TODO o HTML...")
            
            // Padrões de busca mais abrangentes e simples
            val bloggerPatterns = listOf(
                // Padrão mais simples para blogger.com
                """https?://[^\s"']*blogger\.com[^\s"']*""".toRegex(),
                // Padrão para src="..."
                """src\s*=\s*['"]([^'"]*blogger\.com[^'"]*)['"]""".toRegex(),
                // Padrão para data-src="..."
                """data-src\s*=\s*['"]([^'"]*blogger\.com[^'"]*)['"]""".toRegex(),
                // Padrão específico para video.g
                """https?://[^\s"']*blogger\.com/video\.g[^\s"']*""".toRegex(),
                // Padrão mais geral para qualquer URL com blogger
                """(https?://[^\s"']*blogger[^\s"']*)""".toRegex(),
            )
            
            val bloggerUrls = mutableSetOf<String>()
            
            // Buscar em todos os padrões
            for ((index, pattern) in bloggerPatterns.withIndex()) {
                println("[AnimeQ] 🔍 Procurando com padrão $index...")
                val matches = pattern.findAll(html)
                val matchList = matches.toList()
                println("[AnimeQ]   Encontrou ${matchList.size} correspondências")
                
                matchList.forEach { match ->
                    var foundUrl = if (match.groupValues.size > 1) {
                        match.groupValues[1].trim()
                    } else {
                        match.value.trim()
                    }
                    
                    if (foundUrl.isNotEmpty()) {
                        // Limpar a URL se necessário
                        foundUrl = foundUrl.removeSurrounding("'", "'")
                        foundUrl = foundUrl.removeSurrounding("\"", "\"")
                        
                        if (foundUrl.contains("blogger.com")) {
                            println("[AnimeQ]   ✅ URL encontrada: ${foundUrl.take(80)}...")
                            bloggerUrls.add(foundUrl)
                        }
                    }
                }
            }
            
            println("[AnimeQ] 📊 URLs do Blogger encontradas: ${bloggerUrls.size}")
            bloggerUrls.forEachIndexed { index, urlFound ->
                println("[AnimeQ]   $index: ${urlFound}")
            }
            
            // Filtrar apenas URLs que contêm video.g
            val videoBloggerUrls = bloggerUrls.filter { it.contains("video.g") }
            
            if (videoBloggerUrls.isNotEmpty()) {
                println("[AnimeQ] ✅ URLs com video.g encontradas: ${videoBloggerUrls.size}")
                // Usar a primeira URL encontrada
                val bloggerUrl = videoBloggerUrls.first()
                println("[AnimeQ] 🎯 Usando URL: $bloggerUrl")
                return extractFromBloggerUrl(bloggerUrl, url, name, callback)
            } else if (bloggerUrls.isNotEmpty()) {
                // Se encontrou URLs do Blogger mas não com video.g, tentar a primeira
                println("[AnimeQ] ⚠️ URLs do Blogger encontradas mas sem video.g. Tentando primeira URL...")
                val bloggerUrl = bloggerUrls.first()
                println("[AnimeQ] 🎯 Tentando URL: $bloggerUrl")
                return extractFromBloggerUrl(bloggerUrl, url, name, callback)
            }
            
            // 3. Método de fallback: Buscar iframes de forma mais simples com Jsoup
            println("[AnimeQ] 🔍 Fallback: procurando iframes com Jsoup...")
            val doc = org.jsoup.Jsoup.parse(html)
            val allIframes = doc.select("iframe[src]")
            println("[AnimeQ] 📊 Iframes encontrados com Jsoup: ${allIframes.size}")
            
            for ((index, iframe) in allIframes.withIndex()) {
                val src = iframe.attr("src")
                val classes = iframe.attr("class")
                val id = iframe.attr("id")
                println("[AnimeQ]   Iframe $index:")
                println("[AnimeQ]     src: $src")
                println("[AnimeQ]     class: $classes")
                println("[AnimeQ]     id: $id")
                
                if (src.contains("blogger.com")) {
                    println("[AnimeQ] ✅ IFRAME encontrado com blogger.com!")
                    return extractFromBloggerUrl(src, url, name, callback)
                }
            }
            
            // Procurar também em divs que possam conter iframes
            println("[AnimeQ] 🔍 Procurando em divs com classes específicas...")
            val playerDivs = doc.select("div[id*='player'], div[class*='player'], #dooplay_player_response, .pframe, .playcontainer")
            println("[AnimeQ] 📊 Divs de player encontradas: ${playerDivs.size}")
            
            playerDivs.forEach { div ->
                val divHtml = div.html()
                if (divHtml.contains("blogger.com")) {
                    println("[AnimeQ] ✅ Encontrou 'blogger.com' em div de player!")
                    // Extrair URL do blogger do HTML da div
                    val bloggerPattern = """https?://[^\s"']*blogger\.com[^\s"']*""".toRegex()
                    val match = bloggerPattern.find(divHtml)
                    if (match != null) {
                        val bloggerUrl = match.value
                        println("[AnimeQ] 🎯 URL extraída: $bloggerUrl")
                        return extractFromBloggerUrl(bloggerUrl, url, name, callback)
                    }
                }
            }
            
            // 4. Último recurso: Procurar URLs do Google Video diretamente no HTML
            println("[AnimeQ] 🔍 Último recurso: procurando URLs do Google Video...")
            val googleVideoPattern = """https?://[^\s"']*googlevideo\.com/videoplayback[^\s"']*""".toRegex()
            val videoMatches = googleVideoPattern.findAll(html).toList()
            println("[AnimeQ] 📊 URLs do Google Video encontradas: ${videoMatches.size}")
            
            if (videoMatches.isNotEmpty()) {
                println("[AnimeQ] 🎉 URLs do Google Video encontradas diretamente no HTML!")
                return extractGoogleVideoUrls(videoMatches, url, name, callback)
            }
            
            println("[AnimeQ] ❌ Nenhuma URL encontrada")
            println("[AnimeQ] 📊 Estatísticas da página:")
            println("[AnimeQ]   🔗 Iframes totais: ${doc.select("iframe").size}")
            println("[AnimeQ]   📜 Scripts: ${doc.select("script").size}")
            println("[AnimeQ]   🖼️ Imagens: ${doc.select("img").size}")
            println("[AnimeQ]   🔗 Links: ${doc.select("a[href]").size}")
            
            // DEBUG: Mostrar algumas linhas do HTML que contenham "video"
            println("[AnimeQ] 🔍 Linhas do HTML contendo 'video':")
            html.lines().filter { it.contains("video", ignoreCase = true) }
                .take(10)
                .forEachIndexed { index, line ->
                    println("[AnimeQ]   $index: ${line.trim().take(200)}...")
                }
            
            return false
        } catch (e: Exception) {
            println("[AnimeQ] ❌ Erro na extração: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
    
    // Função auxiliar para repetir string
    private operator fun String.times(n: Int): String {
        return repeat(n)
    }
    
    private suspend fun extractGoogleVideoUrls(
        matches: List<kotlin.text.MatchResult>,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val distinctUrls = matches.map { it.value }.distinct()
        
        println("[AnimeQ] 🎬 Extraindo ${distinctUrls.size} vídeos únicos...")
        
        for ((index, videoUrl) in distinctUrls.withIndex()) {
            println("[AnimeQ] 🎬 Vídeo ${index + 1}: ${videoUrl.take(80)}...")
            
            // Extrair qualidade
            val itagPattern = """[?&]itag=(\d+)""".toRegex()
            val itagMatch = itagPattern.find(videoUrl)
            val itag = itagMatch?.groupValues?.get(1)?.toIntOrNull() ?: 18
            val quality = itagQualityMap[itag] ?: 360
            val qualityLabel = getQualityLabel(quality)
            
            println("[AnimeQ]   🏷️ iTag: $itag")
            println("[AnimeQ]   📊 Qualidade: $quality")
            println("[AnimeQ]   🏷️ Label: $qualityLabel")
            
            // Criar link
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
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                    "Origin" to "https://www.blogger.com"
                )
            }
            
            callback(extractorLink)
            found = true
        }
        
        return found
    }

    private suspend fun extractFromBloggerUrl(
        bloggerUrl: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[AnimeQ] 🎬 Extraindo da URL do Blogger")
        println("[AnimeQ] 🔗 URL: $bloggerUrl")

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
            
            // DEBUG: Mostrar parte da resposta
            println("[AnimeQ] 🔍 Mostrando primeiros 500 caracteres da resposta:")
            println("-" * 50)
            println(response.text.take(500))
            println("-" * 50)

            // Tentar extrair primeiro usando VIDEO_CONFIG (formato mais limpo)
            val videoConfigPattern = """var\s+VIDEO_CONFIG\s*=\s*(\{.*?\})\s*;""".toRegex(RegexOption.DOT_MATCHES_ALL)
            val configMatch = videoConfigPattern.find(response.text)
            
            if (configMatch != null) {
                println("[AnimeQ] ✅ Encontrou VIDEO_CONFIG!")
                return try {
                    val config = JSONObject(configMatch.groupValues[1])
                    val streams = config.getJSONArray("streams")
                    
                    var found = false
                    for (i in 0 until streams.length()) {
                        val stream = streams.getJSONObject(i)
                        val videoUrl = stream.getString("play_url")
                        val itag = stream.getInt("format_id")
                        val quality = itagQualityMap[itag] ?: 360
                        val qualityLabel = getQualityLabel(quality)
                        
                        println("[AnimeQ] 🎬 Stream $i: itag=$itag, quality=$quality")
                        
                        val extractorLink = newExtractorLink(
                            source = "AnimeQ",
                            name = "$name ($qualityLabel)",
                            url = videoUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = bloggerUrl
                            this.quality = quality
                            this.headers = headers
                        }
                        
                        callback(extractorLink)
                        found = true
                    }
                    
                    found
                } catch (e: Exception) {
                    println("[AnimeQ] ⚠️ Erro ao parsear VIDEO_CONFIG: ${e.message}")
                    false
                }
            }
            
            // Fallback: procurar URLs do Google Video diretamente
            val videoPattern = """https?://[^\s"']*googlevideo\.com/videoplayback[^\s"']*""".toRegex()
            val matches = videoPattern.findAll(response.text).toList()
            println("[AnimeQ] 📊 URLs do Google Video na resposta: ${matches.size}")
            
            if (matches.isNotEmpty()) {
                println("[AnimeQ] ✅ ${matches.size} vídeos encontrados!")
                return extractGoogleVideoUrls(matches, bloggerUrl, name, callback)
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
