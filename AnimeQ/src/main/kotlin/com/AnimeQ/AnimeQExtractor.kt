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
            
            // 2. Busca ABRANGENTE em TODO o HTML
            println("[AnimeQ] 🔍 Procurando URLs do Blogger em TODO o HTML...")
            
            // Padrões de busca mais abrangentes
            val bloggerPatterns = listOf(
                // Padrões para iframes
                """(https?://[^"'\s]+blogger\.com/video\.g[^"'\s]*)""".toRegex(),
                """(https?://[^"'\s]+blogger\.com/[^"'\s]*video[^"'\s]*)""".toRegex(),
                // Padrões gerais de blogger
                """(https?://[^"'\s]*blogger\.com[^"'\s]*)""".toRegex(),
                // Padrões para data-src, data-url, etc
                """data-(?:src|url|link)=['"]([^"']*blogger\.com[^"']*)['"]""".toRegex(),
                // Padrões para variáveis JavaScript
                """['"](https?://[^"']*blogger\.com[^"']*)['"]""".toRegex(),
                // Padrões para URLs em atributos genéricos
                """src=['"]([^"']*blogger\.com[^"']*)['"]""".toRegex(),
                // Padrões para URLs em scripts (mais abrangente)
                """(https?://(?:www\.)?blogger\.com/[^"'\s<>]*)""".toRegex()
            )
            
            val bloggerUrls = mutableSetOf<String>()
            
            // Buscar em todos os padrões
            for (pattern in bloggerPatterns) {
                val matches = pattern.findAll(html)
                matches.forEach { match ->
                    var foundUrl = match.groupValues[1].trim()
                    if (foundUrl.isNotEmpty()) {
                        // Limpar a URL se necessário
                        if (foundUrl.endsWith("'") || foundUrl.endsWith("\"")) {
                            foundUrl = foundUrl.dropLast(1)
                        }
                        if (foundUrl.startsWith("'") || foundUrl.startsWith("\"")) {
                            foundUrl = foundUrl.drop(1)
                        }
                        if (foundUrl.contains("blogger.com")) {
                            bloggerUrls.add(foundUrl)
                        }
                    }
                }
            }
            
            println("[AnimeQ] 📊 URLs do Blogger encontradas: ${bloggerUrls.size}")
            bloggerUrls.forEachIndexed { index, urlFound ->
                println("[AnimeQ]   $index: ${urlFound.take(80)}...")
            }
            
            // Filtrar apenas URLs que contêm video.g
            val videoBloggerUrls = bloggerUrls.filter { it.contains("video.g") }
            
            if (videoBloggerUrls.isNotEmpty()) {
                println("[AnimeQ] ✅ URLs com video.g encontradas: ${videoBloggerUrls.size}")
                // Usar a primeira URL encontrada
                val bloggerUrl = videoBloggerUrls.first()
                println("[AnimeQ] 🎯 Usando URL: ${bloggerUrl.take(80)}...")
                return extractFromBloggerUrl(bloggerUrl, url, name, callback)
            } else if (bloggerUrls.isNotEmpty()) {
                // Se encontrou URLs do Blogger mas não com video.g, tentar a primeira
                println("[AnimeQ] ⚠️ URLs do Blogger encontradas mas sem video.g. Tentando primeira URL...")
                val bloggerUrl = bloggerUrls.first()
                println("[AnimeQ] 🎯 Tentando URL: ${bloggerUrl.take(80)}...")
                return extractFromBloggerUrl(bloggerUrl, url, name, callback)
            }
            
            // 3. Método de fallback: Buscar iframes de forma mais simples
            println("[AnimeQ] 🔍 Fallback: procurando iframes...")
            val doc = org.jsoup.Jsoup.parse(html)
            val allIframes = doc.select("iframe[src]")
            
            for (iframe in allIframes) {
                val src = iframe.attr("src")
                if (src.contains("blogger.com")) {
                    println("[AnimeQ] ✅ IFRAME encontrado: ${src.take(80)}...")
                    return extractFromBloggerUrl(src, url, name, callback)
                }
            }
            
            // 4. Método adicional: Procurar URLs do Google Video diretamente no HTML
            println("[AnimeQ] 🔍 Último recurso: procurando URLs do Google Video...")
            val googleVideoPattern = """https?://[^"'\s<>]+googlevideo\.com/videoplayback[^"'\s<>]+""".toRegex()
            val videoMatches = googleVideoPattern.findAll(html).toList()
            
            if (videoMatches.isNotEmpty()) {
                println("[AnimeQ] 🎉 URLs do Google Video encontradas diretamente no HTML: ${videoMatches.size}")
                return extractGoogleVideoUrls(videoMatches, url, name, callback)
            }
            
            println("[AnimeQ] ❌ Nenhuma URL encontrada")
            println("[AnimeQ] 📊 Estatísticas da página:")
            println("[AnimeQ]   🔗 Iframes totais: ${doc.select("iframe").size}")
            println("[AnimeQ]   📜 Scripts: ${doc.select("script").size}")
            println("[AnimeQ]   🖼️ Imagens: ${doc.select("img").size}")
            
            return false
        } catch (e: Exception) {
            println("[AnimeQ] ❌ Erro na extração: ${e.message}")
            e.printStackTrace()
            return false
        }
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
            val videoPattern = """https?://[^"'\s<>]+googlevideo\.com/videoplayback[^"'\s<>]+""".toRegex()
            val matches = videoPattern.findAll(response.text).toList()
            
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
