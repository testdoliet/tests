package com.AnimeQ

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.json.JSONObject
import java.net.URLDecoder

object AnimeQVideoExtractor {
    private val cfKiller = CloudflareKiller()
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
            // 1. Buscar a página com CloudflareKiller (igual AnimesCloud)
            println("[AnimeQ] 📄 Obtendo página com CloudflareKiller...")
            val pageResponse = app.get(url, interceptor = cfKiller, headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            ))
            val html = pageResponse.text
            
            // 2. Extrair ID do post do HTML
            val postId = extractPostId(html)
            println("[AnimeQ] 🔍 ID do post encontrado: $postId")
            
            if (postId == null) {
                println("[AnimeQ] ❌ Não foi possível encontrar o ID do post")
                return false
            }
            
            // 3. Pegar TODOS os players disponíveis (igual AnimesCloud)
            val players = extractAllPlayers(html)
            println("[AnimeQ] 🔍 Encontrados ${players.size} players")
            
            if (players.isEmpty()) {
                println("[AnimeQ] ❌ Nenhum player encontrado")
                return false
            }
            
            var foundAny = false
            val priorityLinks = mutableListOf<suspend () -> Unit>()
            val lateLinks = mutableListOf<suspend () -> Unit>()

            // 4. Processar cada player (igual AnimesCloud)
            for ((playerNum, playerName) in players) {
                println("[AnimeQ] 🎯 Processando player $playerNum: $playerName")
                
                val success = tryPlayerApi(postId, playerNum, url, name, playerName) { extractorLink ->
                    callback(extractorLink)
                }
                
                if (success) {
                    foundAny = true
                    println("[AnimeQ] ✅ Player $playerNum encontrou links")
                }
            }
            
            if (foundAny) {
                println("[AnimeQ] 🎉 Extração concluída!")
                return true
            } else {
                println("[AnimeQ] ❌ Nenhum player encontrou links")
                return false
            }
            
        } catch (e: Exception) {
            println("[AnimeQ] ❌ Erro na extração: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
    
    private fun extractPostId(html: String): String? {
        // Regex para encontrar o post_id nos scripts
        val postIdPattern = """post_id["']?\s*[:=]\s*["']?(\d+)["']?""".toRegex()
        val match = postIdPattern.find(html)
        
        return match?.groupValues?.get(1)
    }
    
    private fun extractAllPlayers(html: String): List<Pair<String, String>> {
        val players = mutableListOf<Pair<String, String>>()
        
        // Procurar por opções de player no HTML
        val playerPattern = """<li[^>]*data-nume=["']([^"']+)["'][^>]*>.*?<span[^>]*class=["']title["']>(.*?)</span>""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val matches = playerPattern.findAll(html)
        
        matches.forEach { match ->
            val nume = match.groupValues[1]
            val title = match.groupValues[2].trim()
            players.add(nume to title)
        }
        
        return players.distinctBy { it.first }
    }
    
    private suspend fun tryPlayerApi(
        postId: String,
        playerOption: String,
        referer: String,
        name: String,
        playerName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Montar URL da API do Dooplay
        val apiUrl = "https://animeq.net/wp-json/dooplayer/v2/$postId/tv/$playerOption"
        println("[AnimeQ] 🔗 API URL (Player $playerOption - $playerName): $apiUrl")
        
        // Headers necessários (igual AnimesCloud)
        val headers = mapOf(
            "Referer" to referer,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "X-Requested-With" to "XMLHttpRequest",
            "Accept-Language" to "pt-BR,pt;q=0.9",
            "Origin" to "https://animeq.net"
        )

        try {
            println("[AnimeQ] 🔄 Acessando API Dooplay...")
            val response = app.get(apiUrl, interceptor = cfKiller, headers = headers)
            println("[AnimeQ] 📊 Status da API: ${response.code}")
            
            if (response.code == 200) {
                val jsonText = response.text
                println("[AnimeQ] 📄 Resposta da API: $jsonText")
                
                val json = JSONObject(jsonText)
                val embedUrl = json.optString("embed_url", "")
                
                println("[AnimeQ] 🔍 Embed URL: $embedUrl")
                
                // Processar a URL (igual AnimesCloud)
                return processEmbedUrl(embedUrl, playerName, referer, name, callback)
            } else {
                println("[AnimeQ] ❌ Falha na requisição da API: ${response.code}")
                return false
            }
            
        } catch (e: Exception) {
            println("[AnimeQ] ❌ Erro na API Dooplay (Player $playerOption): ${e.message}")
            return false
        }
    }
    
    private suspend fun processEmbedUrl(
        embedUrl: String,
        playerName: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return when {
            // Caso 1: URL com source direto (MP4)
            embedUrl.contains("source=") && (embedUrl.contains(".mp4") || embedUrl.contains(".m3u8")) -> {
                println("[AnimeQ] 🎬 URL com source direto encontrada")
                Regex("source=([^&]+)").find(embedUrl)?.groupValues?.get(1)?.let {
                    val directUrl = URLDecoder.decode(it, "UTF-8")
                    val quality = determineQualityFromUrl(directUrl, playerName)
                    
                    callback(newExtractorLink(
                        source = "AnimeQ",
                        name = "$playerName - ${getQualityLabel(quality)}",
                        url = directUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = referer
                        this.quality = quality
                        this.headers = mapOf(
                            "Referer" to referer,
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                        )
                    })
                    true
                } ?: false
            }
            
            // Caso 2: URL do Blogger (Google Video)
            embedUrl.contains("blogger.com") -> {
                println("[AnimeQ] 🎬 URL do Blogger encontrada")
                extractFromBloggerUrl(embedUrl, playerName, referer, name, callback)
            }
            
            // Caso 3: Outros casos (pode adicionar mais)
            else -> {
                println("[AnimeQ] ⚠️ Tipo de URL não reconhecido: $embedUrl")
                false
            }
        }
    }
    
    private suspend fun extractFromBloggerUrl(
        bloggerUrl: String,
        playerName: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[AnimeQ] 🎬 Extraindo do Blogger")
        
        return try {
            val headers = mapOf(
                "Referer" to referer,
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            )
            
            val response = app.get(bloggerUrl, headers = headers)
            
            // Procurar URLs do Google Video
            val videoPattern = """https?://[^\s"']*googlevideo\.com/videoplayback[^\s"']*""".toRegex()
            val matches = videoPattern.findAll(response.text).toList()
            
            if (matches.isNotEmpty()) {
                println("[AnimeQ] ✅ ${matches.size} vídeos encontrados no Blogger")
                
                var found = false
                val distinctUrls = matches.map { it.value }.distinct()

                for (videoUrl in distinctUrls) {
                    val itagPattern = """[?&]itag=(\d+)""".toRegex()
                    val itagMatch = itagPattern.find(videoUrl)
                    val itag = itagMatch?.groupValues?.get(1)?.toIntOrNull() ?: 18
                    val quality = itagQualityMap[itag] ?: 360
                    
                    callback(newExtractorLink(
                        source = "AnimeQ",
                        name = "$playerName - ${getQualityLabel(quality)}",
                        url = videoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = bloggerUrl
                        this.quality = quality
                        this.headers = headers
                    })
                    found = true
                }
                
                return found
            }
            
            println("[AnimeQ] ⚠️ Nenhum vídeo encontrado no Blogger")
            false
            
        } catch (e: Exception) {
            println("[AnimeQ] ❌ Erro ao extrair do Blogger: ${e.message}")
            false
        }
    }
    
    private fun determineQualityFromUrl(url: String, playerName: String): Int {
        return when {
            playerName.contains("FullHD", true) || playerName.contains("FHD", true) -> 1080
            playerName.contains("HD", true) -> 720
            playerName.contains("Mobile", true) -> 360
            url.contains("hd.mp4", ignoreCase = true) -> 720
            url.contains("fhd", ignoreCase = true) -> 1080
            url.contains("1080", ignoreCase = true) -> 1080
            url.contains("720", ignoreCase = true) -> 720
            url.contains("480", ignoreCase = true) -> 480
            url.contains("360", ignoreCase = true) -> 360
            else -> 720
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
