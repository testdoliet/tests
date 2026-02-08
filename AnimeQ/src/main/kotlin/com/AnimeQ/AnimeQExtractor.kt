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
            
            // 3. AGORA VAMOS TENTAR O PLAYER 2 (FullHD/HLS)
            println("[AnimeQ] 🎯 Tentando Player Option 2 (FullHD/HLS)...")
            return tryPlayer2Api(postId, url, name, callback)
            
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
    
    private suspend fun tryPlayer2Api(
        postId: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Montar URL da API do Dooplay para Player Option 2
        val apiUrl = "https://animeq.net/wp-json/dooplayer/v2/$postId/tv/2"
        println("[AnimeQ] 🔗 API URL (Player 2): $apiUrl")
        
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
            println("[AnimeQ] 🔄 Acessando API Dooplay (Player 2)...")
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
                        handleMp4Response(embedUrl, referer, name, callback)
                    }
                    "iframe" -> {
                        // Player 1 retorna iframe do Blogger (fallback)
                        println("[AnimeQ] ⚠️ Player 2 retornou iframe (inesperado), usando método Blogger...")
                        extractFromBloggerUrl(embedUrl, referer, name, callback)
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
            println("[AnimeQ] ❌ Erro na API Dooplay (Player 2): ${e.message}")
            return false
        }
    }
    
    private fun handleMp4Response(
        embedUrl: String,
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
                
                // Determinar qualidade baseada na URL
                val quality = determineQualityFromUrl(videoUrl)
                val qualityLabel = getQualityLabel(quality)
                
                println("[AnimeQ] 📊 Qualidade determinada: $quality ($qualityLabel)")
                
                // Criar link de vídeo
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
    
    private fun determineQualityFromUrl(url: String): Int {
        // Analisar a URL para determinar qualidade
        return when {
            url.contains("hd.mp4", ignoreCase = true) -> 720
            url.contains("fhd", ignoreCase = true) -> 1080
            url.contains("1080", ignoreCase = true) -> 1080
            url.contains("720", ignoreCase = true) -> 720
            url.contains("480", ignoreCase = true) -> 480
            url.contains("360", ignoreCase = true) -> 360
            else -> 720 // Padrão para HLS/FullHD
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
            
            println("[AnimeQ] ⚠️ Nenhum vídeo encontrado no Blogger")
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
