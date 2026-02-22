package com.AnimeQ

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.json.JSONObject
import java.net.URLDecoder

object AnimeQVideoExtractor {
    // 1️⃣ ADICIONAR CLOUDFLAREKILLER
    private val cfKiller = CloudflareKiller()
    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

    suspend fun extractVideoLinks(
        url: String,
        name: String = "Episódio",
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[AnimeQ] 🚀 Iniciando extração para: $url")
        
        return try {
            // 2️⃣ USAR CLOUDFLAREKILLER NA REQUISIÇÃO
            println("[AnimeQ] 📄 Obtendo página com CloudflareKiller...")
            val pageResponse = app.get(
                url = url,
                interceptor = cfKiller,
                headers = mapOf("User-Agent" to USER_AGENT)
            )
            val html = pageResponse.text

            // Extrair ID do post
            val postId = extractPostId(html)
            println("[AnimeQ] 🔍 ID do post encontrado: $postId")
            
            if (postId == null) {
                println("[AnimeQ] ❌ Não foi possível encontrar o ID do post")
                return false
            }

            // 3️⃣ PRIORIDADE: Player 4 (FHD) primeiro, depois Player 2 (HD)
            println("[AnimeQ] 🔍 Tentando players por prioridade: 4 (FHD) → 2 (HD) → 1 (Mobile ignorado)")
            var foundAny = false

            // Player 4 (FHD) - PRIORIDADE MÁXIMA
            println("[AnimeQ] 🎯 [PRIORIDADE 1] Tentando player option 4 (FHD)...")
            val success4 = tryPlayerApi(postId, 4, url, name) { extractorLink ->
                println("[AnimeQ] ✅ Adicionando link do player 4 (FHD)")
                callback(extractorLink)
            }
            if (success4) {
                foundAny = true
                println("[AnimeQ] ✅ Player 4 (FHD) encontrou links")
            } else {
                println("[AnimeQ] ❌ Player 4 (FHD) não encontrou links")
            }

            // Player 2 (FullHD/HLS) - SEGUNDA PRIORIDADE
            if (!success4) { // Só tenta player 2 se player 4 falhou
                println("[AnimeQ] 🎯 [PRIORIDADE 2] Tentando player option 2 (HD)...")
                val success2 = tryPlayerApi(postId, 2, url, name) { extractorLink ->
                    println("[AnimeQ] ✅ Adicionando link do player 2 (HD)")
                    callback(extractorLink)
                }
                if (success2) {
                    foundAny = true
                    println("[AnimeQ] ✅ Player 2 (HD) encontrou links")
                } else {
                    println("[AnimeQ] ❌ Player 2 (HD) não encontrou links")
                }
            }

            // Player 1 (Mobile) - IGNORADO (não tentamos)

            if (foundAny) {
                println("[AnimeQ] 🎉 Extração concluída! Links encontrados")
                return true
            } else {
                println("[AnimeQ] ❌ Nenhum player encontrou links")
                return false
            }
            
        } catch (e: Exception) {
            println("[AnimeQ] ❌ Erro na extração: ${e.message}")
            return false
        }
    }
    
    private fun extractPostId(html: String): String? {
        println("[AnimeQ] 🔍 Procurando ID do post no HTML...")
        
        val postIdPattern = """postid-(\d+)""".toRegex()
        val match = postIdPattern.find(html)
        
        if (match != null) {
            val id = match.groupValues[1]
            println("[AnimeQ] ✅ ID encontrado via 'postid-': $id")
            return id
        }
        
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
        val apiUrl = "https://animeq.net/wp-json/dooplayer/v2/$postId/tv/$playerOption"
        println("[AnimeQ] 🔗 API URL (Player $playerOption): $apiUrl")

        val headers = mapOf(
            "Referer" to referer,
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "X-Requested-With" to "XMLHttpRequest",
            "Accept-Language" to "pt-BR,pt;q=0.9",
            "Origin" to "https://animeq.net"
        )

        try {
            println("[AnimeQ] 🔄 Acessando API Dooplay...")
            // USAR CLOUDFLAREKILLER NA API TAMBÉM
            val response = app.get(apiUrl, interceptor = cfKiller, headers = headers)
            println("[AnimeQ] 📊 Status da API: ${response.code}")

            if (response.code == 200) {
                val jsonText = response.text
                println("[AnimeQ] 📄 Resposta da API: $jsonText")

                val json = JSONObject(jsonText)
                val embedUrl = json.optString("embed_url", "")
                println("[AnimeQ] 🔍 Embed URL: $embedUrl")

                return when {
                    // Source direto (MP4/M3U8)
                    embedUrl.contains("source=") && (embedUrl.contains(".mp4") || embedUrl.contains(".m3u8")) -> {
                        handleDirectSource(embedUrl, playerOption, referer, name, callback)
                    }
                    
                    // Blogger
                    embedUrl.contains("blogger.com") -> {
                        handleBlogger(embedUrl, referer, name, callback)
                    }
                    
                    else -> {
                        println("[AnimeQ] ❌ Tipo de resposta não suportado")
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
    
    private suspend fun handleDirectSource(
        embedUrl: String,
        playerOption: Int,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[AnimeQ] 🎬 Processando source direto...")
        
        try {
            val sourcePattern = """[?&]source=([^&]+)""".toRegex()
            val match = sourcePattern.find(embedUrl)

            if (match != null) {
                val encodedSource = match.groupValues[1]
                val videoUrl = URLDecoder.decode(encodedSource, "UTF-8")
                
                // 4️⃣ QUALIDADE BASEADA NO PLAYER (simplificado)
                val qualityLabel = when (playerOption) {
                    4 -> "FHD 🔥"
                    2 -> "HD ⭐"
                    else -> "SD 📺"
                }
                
                println("[AnimeQ] ✅ URL de vídeo extraída: $videoUrl")
                println("[AnimeQ] 📊 Qualidade: $qualityLabel")
                
                val extractorLink = newExtractorLink(
                    source = "AnimeQ",
                    name = "$name ($qualityLabel)",
                    url = videoUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = when (playerOption) {
                        4 -> 1080
                        2 -> 720
                        else -> 480
                    }
                    this.headers = mapOf(
                        "Referer" to referer,
                        "User-Agent" to USER_AGENT
                    )
                }
                
                callback(extractorLink)
                return true
            }
            return false
        } catch (e: Exception) {
            println("[AnimeQ] ❌ Erro ao processar source: ${e.message}")
            return false
        }
    }
    
    private suspend fun handleBlogger(
        bloggerUrl: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[AnimeQ] 🎬 Processando Blogger...")
        println("[AnimeQ] 🔗 URL: $bloggerUrl")

        return try {
            val response = app.get(
                url = bloggerUrl,
                interceptor = cfKiller,
                headers = mapOf(
                    "Referer" to referer,
                    "User-Agent" to USER_AGENT
                )
            )
            
            val videoPattern = """https?://[^\s"']*googlevideo\.com/videoplayback[^\s"']*""".toRegex()
            val matches = videoPattern.findAll(response.text).toList()
            
            if (matches.isNotEmpty()) {
                println("[AnimeQ] ✅ ${matches.size} vídeos encontrados no Blogger!")

                var found = false
                val distinctUrls = matches.map { it.value }.distinct()

                for (videoUrl in distinctUrls) {
                    println("[AnimeQ] 🎬 Vídeo encontrado: ${videoUrl.take(80)}...")
                    
                    val extractorLink = newExtractorLink(
                        source = "AnimeQ",
                        name = "$name (SD 📺)",
                        url = videoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = bloggerUrl
                        this.quality = 480
                        this.headers = mapOf(
                            "Referer" to bloggerUrl,
                            "User-Agent" to USER_AGENT
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
            println("[AnimeQ] ❌ Erro no Blogger: ${e.message}")
            return false
        }
    }
}
