package com.AnimeQ

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.extractors.VidStack
import org.json.JSONObject
import java.net.URLDecoder

// 1️⃣ ADICIONAR SÓ O ANIMESTRP (igual AnimesCloud)
class AnimesSTRP : VidStack() {
    override var name = "Animes STRP"
    override var mainUrl = "https://animes.strp2p.com"
    override var requiresReferer = true
}

object AnimeQVideoExtractor {
    // 2️⃣ ADICIONAR CLOUDFLAREKILLER
    private val cfKiller = CloudflareKiller()
    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    
    private val itagQualityMap = mapOf(
        18 to 360, 22 to 720, 37 to 1080, 59 to 480,
        43 to 360, 44 to 480, 45 to 720, 46 to 1080,
        38 to 3072, 266 to 2160, 138 to 2160, 313 to 2160,
    )

    suspend fun extractVideoLinks(
        url: String,
        name: String = "Episódio",
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[AnimeQ] 🚀 Iniciando extração para: $url")
        
        return try {
            // 3️⃣ USAR CLOUDFLAREKILLER (igual classe principal)
            println("[AnimeQ] 📄 Obtendo página com CloudflareKiller...")
            val pageResponse = app.get(
                url = url,
                interceptor = cfKiller,
                headers = mapOf("User-Agent" to USER_AGENT)
            )
            val html = pageResponse.text

            // 4️⃣ EXTRAIR ID DO POST (SEU CÓDIGO)
            val postId = extractPostId(html)
            println("[AnimeQ] 🔍 ID do post encontrado: $postId")
            
            if (postId == null) {
                println("[AnimeQ] ❌ Não foi possível encontrar o ID do post")
                return false
            }

            // 5️⃣ TENTAR PLAYERS 1 E 2 (SEU CÓDIGO)
            println("[AnimeQ] 🔍 Tentando players 1 e 2...")
            var foundAny = false

            // Player 1 
            println("[AnimeQ] 🎯 Tentando player option 1...")
            val success1 = tryPlayerApi(postId, 1, url, name) { extractorLink ->
                println("[AnimeQ] ✅ Adicionando link do player 1")
                callback(extractorLink)
            }
            if (success1) {
                foundAny = true
                println("[AnimeQ] ✅ Player 1 encontrou links")
            } else {
                println("[AnimeQ] ❌ Player 1 não encontrou links")
            }

            // Player 2
            println("[AnimeQ] 🎯 Tentando player option 2...")
            val success2 = tryPlayerApi(postId, 2, url, name) { extractorLink ->
                println("[AnimeQ] ✅ Adicionando link do player 2")
                callback(extractorLink)
            }
            if (success2) {
                foundAny = true
                println("[AnimeQ] ✅ Player 2 encontrou links")
            } else {
                println("[AnimeQ] ❌ Player 2 não encontrou links")
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
            // 6️⃣ USAR CLOUDFLAREKILLER NA API TAMBÉM
            val response = app.get(apiUrl, interceptor = cfKiller, headers = headers)
            println("[AnimeQ] 📊 Status da API: ${response.code}")

            if (response.code == 200) {
                val jsonText = response.text
                println("[AnimeQ] 📄 Resposta da API: $jsonText")

                val json = JSONObject(jsonText)
                val embedUrl = json.optString("embed_url", "")
                println("[AnimeQ] 🔍 Embed URL: $embedUrl")

                // 7️⃣ TRATAR OS MESMOS CASOS DE SEMPRE + ANIMESTRP
                return when {
                    // CASO 1: Source direto (SEU CÓDIGO)
                    embedUrl.contains("source=") && (embedUrl.contains(".mp4") || embedUrl.contains(".m3u8")) -> {
                        handleDirectSource(embedUrl, playerOption, referer, name, callback)
                    }
                    
                    // CASO 2: Blogger (SEU CÓDIGO)
                    embedUrl.contains("blogger.com") -> {
                        handleBlogger(embedUrl, referer, name, callback)
                    }
                    
                    // 8️⃣ CASO 3: ANIMESTRP (NOVO - igual AnimesCloud)
                    embedUrl.contains("animes.strp2p.com") -> {
                        println("[AnimeQ] 🎬 Usando extractor AnimesSTRP")
                        val extractor = AnimesSTRP()
                        extractor.name = "AnimeQ STR"
                        extractor.getUrl(embedUrl, referer, { }, callback)
                        true
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
    
    // 9️⃣ handleDirectSource AGORA É SUSPEND
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
                
                val quality = determineQualityFromUrl(videoUrl, playerOption)
                val qualityLabel = getQualityLabel(quality)
                
                val link = newExtractorLink(
                    source = "AnimeQ",
                    name = "$name ($qualityLabel)",
                    url = videoUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = quality
                    this.headers = mapOf("Referer" to referer, "User-Agent" to USER_AGENT)
                }
                callback(link)
                return true
            }
            return false
        } catch (e: Exception) {
            println("[AnimeQ] ❌ Erro ao processar source: ${e.message}")
            return false
        }
    }
    
    // 🔟 handleBlogger AGORA É SUSPEND
    private suspend fun handleBlogger(
        bloggerUrl: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[AnimeQ] 🎬 Processando Blogger...")
        
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
                val distinctUrls = matches.map { it.value }.distinct()
                var found = false

                for (videoUrl in distinctUrls) {
                    val itagPattern = """[?&]itag=(\d+)""".toRegex()
                    val itag = itagPattern.find(videoUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 18
                    val quality = itagQualityMap[itag] ?: 360
                    
                    val link = newExtractorLink(
                        source = "AnimeQ",
                        name = "$name (${getQualityLabel(quality)})",
                        url = videoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = bloggerUrl
                        this.quality = quality
                        this.headers = mapOf("Referer" to bloggerUrl, "User-Agent" to USER_AGENT)
                    }
                    callback(link)
                    found = true
                }
                return found
            }
            return false
        } catch (e: Exception) {
            println("[AnimeQ] ❌ Erro no Blogger: ${e.message}")
            return false
        }
    }
    
    private fun determineQualityFromUrl(url: String, playerOption: Int): Int {
        return when {
            url.contains("hd.mp4", ignoreCase = true) -> 720
            url.contains("fhd", ignoreCase = true) -> 1080
            url.contains("1080", ignoreCase = true) -> 1080
            url.contains("720", ignoreCase = true) -> 720
            url.contains("480", ignoreCase = true) -> 480
            url.contains("360", ignoreCase = true) -> 360
            else -> when (playerOption) {
                1 -> 360
                2 -> 720
                else -> 720
            }
        }
    }
    
    private fun getQualityLabel(quality: Int): String {
        return when {
            quality >= 1080 -> "FHD 🔥"
            quality >= 720 -> "HD ⭐"
            else -> "SD 📺"
        }
    }
}
