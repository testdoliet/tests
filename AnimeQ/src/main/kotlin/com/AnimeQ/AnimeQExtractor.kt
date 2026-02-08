package com.AnimeQ

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.json.JSONObject
import java.net.URLDecoder

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
            // 1. Buscar a página para extrair o ID do post
            println("[AnimeQ] 📄 Obtendo página...")
            val pageResponse = app.get(url)
            val html = pageResponse.text
            
            // 2. Extrair ID do post do HTML
            val postId = extractPostId(html)
            println("[AnimeQ] 🔍 ID do post encontrado: $postId")
            
            if (postId == null) {
                println("[AnimeQ] ❌ Não foi possível encontrar o ID do post")
                return false
            }
            
            // 3. Tentar todas as opções de player (1-4)
            println("[AnimeQ] 🔍 Tentando todas as opções de player...")
            
            for (playerOption in 1..4) {
                println("[AnimeQ] 🎯 Tentando player option $playerOption...")
                
                val success = tryPlayerApi(postId, playerOption, url, name, callback)
                if (success) {
                    println("[AnimeQ] ✅ Sucesso com player option $playerOption")
                    return true
                }
            }
            
            println("[AnimeQ] ❌ Nenhuma opção de player funcionou")
            return false
            
        } catch (e: Exception) {
            println("[AnimeQ] ❌ Erro na extração: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
    
    private fun extractPostId(html: String): String? {
        println("[AnimeQ] 🔍 Procurando ID do post no HTML...")
        
        // Método 1: Procurar por "postid-"
        val postIdPattern = """postid-(\d+)""".toRegex()
        val match = postIdPattern.find(html)
        
        if (match != null) {
            val id = match.groupValues[1]
            println("[AnimeQ] ✅ ID encontrado via 'postid-': $id")
            return id
        }
        
        // Método 2: Procurar por data-postid
        val dataPostIdPattern = """data-postid=['"](\d+)['"]""".toRegex()
        val dataMatch = dataPostIdPattern.find(html)
        
        if (dataMatch != null) {
            val id = dataMatch.groupValues[1]
            println("[AnimeQ] ✅ ID encontrado via 'data-postid': $id")
            return id
        }
        
        println("[AnimeQ] ❌ Não foi possível extrair o ID do post")
        return null
    }
    
    private suspend fun tryPlayerApi(
        postId: String,
        playerOption: Int,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Montar URL da API do Dooplay
        val apiUrl = "https://animeq.net/wp-json/dooplayer/v2/$postId/tv/$playerOption"
        println("[AnimeQ] 🔗 API URL (Player $playerOption): $apiUrl")
        
        // Headers necessários
        val headers = mapOf(
            "Referer" to referer,
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "X-Requested-With" to "XMLHttpRequest",
            "Accept-Language" to "pt-BR,pt;q=0.9",
            "Origin" to "https://animeq.net"
        )
        
        try {
            println("[AnimeQ] 🔄 Acessando API Dooplay...")
            val response = app.get(apiUrl, headers = headers)
            println("[AnimeQ] 📊 Status da API: ${response.code}")
            
            if (response.code == 200) {
                val jsonText = response.text
                println("[AnimeQ] 📄 Resposta da API: $jsonText")
                
                // Parsear resposta JSON
                val json = JSONObject(jsonText)
                
                // Verificar o tipo de resposta
                val responseType = json.optString("type", "")
                val embedUrl = json.optString("embed_url", "")
                
                println("[AnimeQ] 🔍 Tipo de resposta: $responseType")
                println("[AnimeQ] 🔍 Embed URL: $embedUrl")
                
                return when (responseType) {
                    "mp4" -> {
                        // Player 2 retorna MP4 direto via JWPlayer
                        handleMp4Response(embedUrl, playerOption, referer, name, callback)
                    }
                    "iframe" -> {
                        // Player 1, 3, 4 retornam iframes
                        handleIframeResponse(embedUrl, playerOption, referer, name, callback)
                    }
                    else -> {
                        println("[AnimeQ] ❌ Tipo de resposta desconhecido: $responseType")
                        false
                    }
                }
            } else {
                println("[AnimeQ] ❌ Falha na requisição da API: ${response.code}")
                return false
            }
            
        } catch (e: Exception) {
            println("[AnimeQ] ❌ Erro na API Dooplay (Player $playerOption): ${e.message}")
            return false
        }
    }
    
    private suspend fun handleMp4Response(
        embedUrl: String,
        playerOption: Int,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[AnimeQ] 🎬 Processando resposta MP4...")
        
        try {
            // A embed_url é uma URL do JWPlayer com parâmetro source
            // Exemplo: https://animeq.net/jwplayer/?source=URL_ENCODED&id=59948&type=mp4
            
            // Extrair o parâmetro source da URL
            val sourcePattern = """[?&]source=([^&]+)""".toRegex()
            val match = sourcePattern.find(embedUrl)
            
            if (match != null) {
                val encodedSource = match.groupValues[1]
                val videoUrl = URLDecoder.decode(encodedSource, "UTF-8")
                
                println("[AnimeQ] ✅ URL de vídeo extraída: $videoUrl")
                
                // Determinar qualidade baseada na URL e player option
                val quality = determineQualityFromUrl(videoUrl, playerOption)
                val qualityLabel = getQualityLabel(quality)
                
                println("[AnimeQ] 📊 Qualidade determinada: $quality ($qualityLabel)")
                
                // Criar link de vídeo - CORRIGIDO: Chamada suspensa
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
                
                callback(extractorLink)
                return true
            } else {
                println("[AnimeQ] ❌ Não foi possível extrair source da URL: $embedUrl")
                return false
            }
            
        } catch (e: Exception) {
            println("[AnimeQ] ❌ Erro ao processar MP4: ${e.message}")
            return false
        }
    }
    
    private suspend fun handleIframeResponse(
        embedUrl: String,
        playerOption: Int,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[AnimeQ] 🎬 Processando resposta iframe...")
        
        return when {
            embedUrl.contains("blogger.com") -> {
                // Player 1: iframe do Blogger
                extractFromBloggerUrl(embedUrl, referer, name, callback)
            }
            embedUrl.contains("animeshd.cloud") -> {
                // Player 3: iframe do AnimesHD
                extractFromAnimesHD(embedUrl, playerOption, referer, name, callback)
            }
            else -> {
                // Outros iframes que possam aparecer
                println("[AnimeQ] ⚠️ Iframe desconhecido: $embedUrl")
                extractFromGenericIframe(embedUrl, playerOption, referer, name, callback)
            }
        }
    }
    
    private suspend fun extractFromAnimesHD(
        url: String,
        playerOption: Int,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[AnimeQ] 🎬 Extraindo do AnimesHD: $url")
        
        try {
            // Headers para acessar o AnimesHD
            val headers = mapOf(
                "Referer" to referer,
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "pt-BR,pt;q=0.9"
            )
            
            // Acessar a URL do AnimesHD
            println("[AnimeQ] 🔄 Acessando AnimesHD...")
            val response = app.get(url, headers = headers)
            println("[AnimeQ] 📊 Status do AnimesHD: ${response.code}")
            
            // Vamos analisar o HTML para ver o que tem
            val html = response.text
            println("[AnimeQ] 📄 Primeiros 1000 chars do HTML: ${html.take(1000)}")
            
            // Procurar por URLs de vídeo comuns
            val videoPatterns = listOf(
                """https?://[^\s"']*\.mp4[^\s"']*""".toRegex(),
                """https?://[^\s"']*\.m3u8[^\s"']*""".toRegex(),
                """https?://[^\s"']*googlevideo\.com[^\s"']*""".toRegex(),
                """src=['"]([^'"]*\.mp4[^'"]*)['"]""".toRegex(),
                """src=['"]([^'"]*\.m3u8[^'"]*)['"]""".toRegex()
            )
            
            for (pattern in videoPatterns) {
                val matches = pattern.findAll(html).toList()
                if (matches.isNotEmpty()) {
                    println("[AnimeQ] ✅ ${matches.size} vídeos encontrados com padrão!")
                    
                    for ((index, match) in matches.withIndex()) {
                        val videoUrl = if (match.groupValues.size > 1) match.groupValues[1] else match.value
                        println("[AnimeQ] 🎬 Vídeo ${index + 1}: ${videoUrl.take(80)}...")
                        
                        // Determinar qualidade
                        val quality = determineQualityFromUrl(videoUrl, playerOption)
                        val qualityLabel = getQualityLabel(quality)
                        
                        // Criar link de vídeo - CORRIGIDO: Chamada suspensa
                        val extractorLink = newExtractorLink(
                            source = "AnimeQ",
                            name = "$name ($qualityLabel)",
                            url = videoUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = url
                            this.quality = quality
                            this.headers = headers
                        }
                        
                        callback(extractorLink)
                    }
                    return true
                }
            }
            
            println("[AnimeQ] ⚠️ Nenhum vídeo encontrado no AnimesHD")
            return false
            
        } catch (e: Exception) {
            println("[AnimeQ] ❌ Erro ao extrair do AnimesHD: ${e.message}")
            return false
        }
    }
    
    private suspend fun extractFromGenericIframe(
        url: String,
        playerOption: Int,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[AnimeQ] 🎬 Extraindo de iframe genérico: $url")
        
        try {
            // Headers básicos
            val headers = mapOf(
                "Referer" to referer,
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
            
            // Acessar a URL
            println("[AnimeQ] 🔄 Acessando iframe...")
            val response = app.get(url, headers = headers)
            
            // Procurar por vídeos
            val html = response.text
            
            // Padrões de busca
            val patterns = listOf(
                """https?://[^\s"']*\.mp4[^\s"']*""".toRegex(),
                """https?://[^\s"']*\.m3u8[^\s"']*""".toRegex(),
                """src=['"]([^'"]*\.mp4[^'"]*)['"]""".toRegex(),
                """src=['"]([^'"]*\.m3u8[^'"]*)['"]""".toRegex(),
                """file:['"]([^'"]*)['"]""".toRegex(),
                """source:['"]([^'"]*)['"]""".toRegex()
            )
            
            for (pattern in patterns) {
                val matches = pattern.findAll(html).toList()
                if (matches.isNotEmpty()) {
                    println("[AnimeQ] ✅ ${matches.size} vídeos encontrados!")
                    
                    for ((index, match) in matches.withIndex()) {
                        val videoUrl = if (match.groupValues.size > 1) match.groupValues[1] else match.value
                        println("[AnimeQ] 🎬 Vídeo ${index + 1}: ${videoUrl.take(80)}...")
                        
                        // Determinar qualidade
                        val quality = determineQualityFromUrl(videoUrl, playerOption)
                        val qualityLabel = getQualityLabel(quality)
                        
                        // Criar link - CORRIGIDO: Chamada suspensa
                        val extractorLink = newExtractorLink(
                            source = "AnimeQ",
                            name = "$name ($qualityLabel)",
                            url = videoUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = url
                            this.quality = quality
                            this.headers = headers
                        }
                        
                        callback(extractorLink)
                    }
                    return true
                }
            }
            
            println("[AnimeQ] ⚠️ Nenhum vídeo encontrado no iframe genérico")
            return false
            
        } catch (e: Exception) {
            println("[AnimeQ] ❌ Erro ao extrair do iframe genérico: ${e.message}")
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
        println("[AnimeQ] 🔗 URL: $bloggerUrl")
        
        return try {
            // Headers necessários para acessar o Blogger
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
            println("[AnimeQ] 📊 Status do Blogger: ${response.code}")
            
            // Procurar URLs do Google Video
            val videoPattern = """https?://[^\s"']*googlevideo\.com/videoplayback[^\s"']*""".toRegex()
            val matches = videoPattern.findAll(response.text).toList()
            
            if (matches.isNotEmpty()) {
                println("[AnimeQ] ✅ ${matches.size} vídeos encontrados no Blogger!")
                
                var found = false
                val distinctUrls = matches.map { it.value }.distinct()
                
                for ((index, videoUrl) in distinctUrls.withIndex()) {
                    println("[AnimeQ] 🎬 Vídeo ${index + 1}: ${videoUrl.take(80)}...")
                    
                    // Extrair qualidade do itag
                    val itagPattern = """[?&]itag=(\d+)""".toRegex()
                    val itagMatch = itagPattern.find(videoUrl)
                    val itag = itagMatch?.groupValues?.get(1)?.toIntOrNull() ?: 18
                    val quality = itagQualityMap[itag] ?: 360
                    val qualityLabel = getQualityLabel(quality)
                    
                    println("[AnimeQ]   🏷️ iTag: $itag")
                    println("[AnimeQ]   📊 Qualidade: $quality")
                    println("[AnimeQ]   🏷️ Label: $qualityLabel")
                    
                    // Criar link - CORRIGIDO: Chamada suspensa
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
            
            println("[AnimeQ] ⚠️ Nenhum vídeo encontrado no Blogger")
            return false
            
        } catch (e: Exception) {
            println("[AnimeQ] ❌ Erro ao extrair do Blogger: ${e.message}")
            return false
        }
    }
    
    private fun determineQualityFromUrl(url: String, playerOption: Int): Int {
        // Primeiro, verificar pela URL
        return when {
            url.contains("hd.mp4", ignoreCase = true) -> 720
            url.contains("fhd", ignoreCase = true) -> 1080
            url.contains("1080", ignoreCase = true) -> 1080
            url.contains("720", ignoreCase = true) -> 720
            url.contains("480", ignoreCase = true) -> 480
            url.contains("360", ignoreCase = true) -> 360
            url.contains(".m3u8", ignoreCase = true) -> 720 // HLS geralmente é 720p+
            else -> when (playerOption) {
                1 -> 360  // Mobile
                2 -> 720  // FullHD/HLS
                3 -> 1080 // FHD
                4 -> 1080 // FHD
                else -> 720
            }
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
