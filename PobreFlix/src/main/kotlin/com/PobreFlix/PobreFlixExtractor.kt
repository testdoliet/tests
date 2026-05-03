package com.PobreFlix.extractor

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

object PobreFlixExtractor {

    private const val BASE_URL = "https://superflixapi.online"
    private const val CDN_BASE = "https://llanfairpwllgwyngy.com"

    private val HEADERS = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "pt-BR",
        "Accept-Encoding" to "identity",
        "Referer" to "https://lospobreflix.site/",
        "Sec-Fetch-Dest" to "iframe",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "cross-site",
        "Upgrade-Insecure-Requests" to "1",
        "Connection" to "keep-alive"
    )

    private val API_HEADERS = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "pt-BR",
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "X-Requested-With" to "XMLHttpRequest",
        "Origin" to BASE_URL,
        "Connection" to "keep-alive"
    )

    private var sessionCookies: String = ""

    private fun getCookieHeader(): Map<String, String> {
        return if (sessionCookies.isNotEmpty()) mapOf("Cookie" to sessionCookies) else emptyMap()
    }

    private fun extractContentIdFromTruncatedJson(html: String, targetSeason: Int, targetEpisode: Int): String? {
        println("🔍 [PobreFlix] Procurando content ID para temporada $targetSeason, episódio $targetEpisode...")
        
        val pattern1 = Regex("\"season\":$targetSeason,\"epi_num\":$targetEpisode[^}]*?\"ID\":(\\d+)")
        val match1 = pattern1.find(html)
        if (match1 != null) {
            println("✅ [PobreFlix] Content ID encontrado (padrão 1): ${match1.groupValues[1]}")
            return match1.groupValues[1]
        }
        
        val pattern2 = Regex("\"ID\":(\\d+)[^}]*?\"epi_num\":$targetEpisode[^}]*?\"season\":$targetSeason")
        val match2 = pattern2.find(html)
        if (match2 != null) {
            println("✅ [PobreFlix] Content ID encontrado (padrão 2): ${match2.groupValues[1]}")
            return match2.groupValues[1]
        }
        
        val seasonPattern = Regex("\"$targetSeason\":\\s*\\[([\\s\\S]*?)(?=\\s*,\\s*\"\\d+\"\\s*:|\\s*\\})")
        val seasonMatch = seasonPattern.find(html)
        if (seasonMatch != null) {
            val seasonContent = seasonMatch.groupValues[1]
            val episodePattern = Regex("\"epi_num\":$targetEpisode[^}]*?\"ID\":(\\d+)")
            val episodeMatch = episodePattern.find(seasonContent)
            if (episodeMatch != null) {
                println("✅ [PobreFlix] Content ID encontrado (padrão de temporada): ${episodeMatch.groupValues[1]}")
                return episodeMatch.groupValues[1]
            }
        }
        
        println("❌ [PobreFlix] Nenhum content ID encontrado!")
        return null
    }

    suspend fun getStreams(
        tmdbId: Int,
        mediaType: String,
        season: Int,
        episode: Int
    ): List<ExtractorLink> {
        println("\n🎬 [PobreFlix] Iniciando extração de streams...")
        println("📌 [PobreFlix] TMDB ID: $tmdbId | Tipo: $mediaType | Temporada: $season | Episódio: $episode")
        
        val results = mutableListOf<ExtractorLink>()
        val targetSeason = if (mediaType == "filme") 1 else season
        val targetEpisode = if (mediaType == "filme") 1 else episode
        
        println("🎯 [PobreFlix] Target - Temporada: $targetSeason, Episódio: $targetEpisode")

        try {
            val pageUrl = if (mediaType == "filme") {
                "$BASE_URL/filme/$tmdbId"
            } else {
                "$BASE_URL/serie/$tmdbId/$targetSeason/$targetEpisode"
            }
            
            println("🌐 [PobreFlix] Acessando URL: $pageUrl")
            
            val response = app.get(pageUrl, headers = HEADERS + getCookieHeader())
            if (!response.isSuccessful) {
                println("❌ [PobreFlix] Erro na requisição: ${response.code}")
                return emptyList()
            }
            
            println("✅ [PobreFlix] Requisição bem sucedida! Status: ${response.code}")
            
            val setCookie = response.headers["set-cookie"]
            if (setCookie != null && setCookie.isNotEmpty()) {
                sessionCookies = setCookie
                println("🍪 [PobreFlix] Cookie de sessão capturado: $setCookie")
            }
            
            val html = response.text
            println("📄 [PobreFlix] HTML recebido (${html.length} caracteres)")
            
            val csrfMatch = Regex("CSRF_TOKEN\\s*=\\s*[\"']([^\"']+)[\"']").find(html)
            if (csrfMatch == null) {
                println("❌ [PobreFlix] CSRF_TOKEN não encontrado!")
                return emptyList()
            }
            val csrfToken = csrfMatch.groupValues[1]
            println("🔑 [PobreFlix] CSRF Token: $csrfToken")
            
            val pageMatch = Regex("PAGE_TOKEN\\s*=\\s*[\"']([^\"']+)[\"']").find(html)
            if (pageMatch == null) {
                println("❌ [PobreFlix] PAGE_TOKEN não encontrado!")
                return emptyList()
            }
            val pageToken = pageMatch.groupValues[1]
            println("🔑 [PobreFlix] Page Token: $pageToken")
            
            var contentId: String? = null
            
            if (mediaType == "filme") {
                val initialContentMatch = Regex("INITIAL_CONTENT_ID\\s*=\\s*(\\d+)").find(html)
                if (initialContentMatch != null) {
                    contentId = initialContentMatch.groupValues[1]
                    println("🎬 [PobreFlix] Content ID do filme: $contentId")
                }
            } else {
                contentId = extractContentIdFromTruncatedJson(html, targetSeason, targetEpisode)
            }
            
            if (contentId == null) {
                println("❌ [PobreFlix] Content ID não encontrado!")
                return emptyList()
            }
            
            println("📦 [PobreFlix] Content ID: $contentId")
            
            val optionsParams = mutableMapOf<String, String>()
            optionsParams["contentid"] = contentId
            optionsParams["type"] = if (mediaType == "filme") "filme" else "serie"
            optionsParams["_token"] = csrfToken
            optionsParams["page_token"] = pageToken
            optionsParams["pageToken"] = pageToken
            
            println("🚀 [PobreFlix] Enviando requisição para /player/options...")
            println("📤 [PobreFlix] Parâmetros: $optionsParams")
            
            val optionsResponse = app.post(
                url = "$BASE_URL/player/options",
                headers = API_HEADERS + getCookieHeader() + mapOf("X-Page-Token" to pageToken),
                data = optionsParams
            )
            
            if (!optionsResponse.isSuccessful) {
                println("❌ [PobreFlix] Erro em /player/options: ${optionsResponse.code}")
                return emptyList()
            }
            
            println("✅ [PobreFlix] /player/options respondido com sucesso!")
            
            val optionsData = JSONObject(optionsResponse.text)
            val dataObj = optionsData.optJSONObject("data")
            if (dataObj == null) {
                println("❌ [PobreFlix] Objeto 'data' não encontrado na resposta!")
                return emptyList()
            }
            
            val optionsArray = dataObj.optJSONArray("options")
            if (optionsArray == null) {
                println("❌ [PobreFlix] Array 'options' não encontrado!")
                return emptyList()
            }
            
            println("📊 [PobreFlix] Total de opções encontradas: ${optionsArray.length()}")
            
            for (i in 0 until optionsArray.length()) {
                println("\n--- [PobreFlix] Processando opção ${i + 1}/${optionsArray.length()} ---")
                
                val option = optionsArray.getJSONObject(i)
                val videoId = option.optString("ID")
                val type = option.optInt("type")
                val serverType = when (type) {
                    1 -> "Dublado"
                    2 -> "Legendado"
                    else -> "Tipo $type"
                }
                
                println("🎥 [PobreFlix] Video ID: $videoId | Tipo: $serverType")
                
                if (videoId.isEmpty()) {
                    println("⚠️ [PobreFlix] Video ID vazio, pulando...")
                    continue
                }
                
                val sourceParams = mutableMapOf<String, String>()
                sourceParams["video_id"] = videoId
                sourceParams["page_token"] = pageToken
                sourceParams["_token"] = csrfToken
                
                println("🔄 [PobreFlix] Buscando source para video_id: $videoId")
                
                val sourceResponse = app.post(
                    url = "$BASE_URL/player/source",
                    headers = API_HEADERS + getCookieHeader(),
                    data = sourceParams
                )
                
                if (!sourceResponse.isSuccessful) {
                    println("⚠️ [PobreFlix] Erro ao buscar source: ${sourceResponse.code}")
                    continue
                }
                
                val sourceData = JSONObject(sourceResponse.text)
                val redirectUrl = sourceData.optJSONObject("data")?.optString("video_url")
                if (redirectUrl.isNullOrEmpty()) {
                    println("⚠️ [PobreFlix] Redirect URL vazio ou nulo!")
                    continue
                }
                
                println("🔄 [PobreFlix] URL de redirecionamento: $redirectUrl")
                
                val redirectResponse = app.get(redirectUrl, headers = HEADERS + getCookieHeader())
                val finalUrl = redirectResponse.url
                
                println("🔗 [PobreFlix] URL final: $finalUrl")
                
                if (finalUrl.contains("blogger.com/video.g") || finalUrl.contains("blogger.com")) {
                    println("⚠️ [PobreFlix] URL do Blogger detectada, pulando...")
                    continue
                }
                
                val playerHash = finalUrl.split("/").lastOrNull()
                if (playerHash == null) {
                    println("⚠️ [PobreFlix] Player hash não encontrado!")
                    continue
                }
                
                println("🔑 [PobreFlix] Player hash: $playerHash")
                
                val videoParams = mutableMapOf<String, String>()
                videoParams["hash"] = playerHash
                videoParams["r"] = ""
                
                println("🎬 [PobreFlix] Solicitando vídeo ao CDN...")
                
                val videoResponse = app.post(
                    url = "$CDN_BASE/player/index.php?data=$playerHash&do=getVideo",
                    headers = mapOf(
                        "Accept" to "*/*",
                        "Accept-Language" to "pt-BR",
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                        "Origin" to CDN_BASE,
                        "Referer" to "$CDN_BASE/",
                        "X-Requested-With" to "XMLHttpRequest",
                        "User-Agent" to HEADERS["User-Agent"]!!
                    ),
                    data = videoParams
                )
                
                if (!videoResponse.isSuccessful) {
                    println("⚠️ [PobreFlix] Erro ao buscar vídeo: ${videoResponse.code}")
                    continue
                }
                
                val videoData = JSONObject(videoResponse.text)
                val videoUrl = videoData.optString("securedLink").takeIf { it.isNotEmpty() } 
                    ?: videoData.optString("videoSource")
                
                if (videoUrl.isNullOrEmpty()) {
                    println("⚠️ [PobreFlix] URL do vídeo vazio!")
                    continue
                }
                
                println("✅ [PobreFlix] URL do vídeo obtida com sucesso!")
                println("📺 [PobreFlix] Video URL: $videoUrl")
                
                val title = if (mediaType == "filme") {
                    "Filme $tmdbId"
                } else {
                    "S${targetSeason.toString().padStart(2, '0')}E${targetEpisode.toString().padStart(2, '0')}"
                }
                
                val extractorLink = newExtractorLink(
                    source = "SuperFlixAPI",
                    name = "SuperFlixAPI $serverType",
                    url = videoUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$CDN_BASE/"
                    this.quality = 720
                    this.headers = mapOf(
                        "Referer" to "$CDN_BASE/",
                        "User-Agent" to HEADERS["User-Agent"]!!
                    )
                }
                
                results.add(extractorLink)
                println("➕ [PobreFlix] Link adicionado aos resultados: $title - $serverType")
            }
            
            println("\n✅ [PobreFlix] Extração concluída! Total de streams encontrados: ${results.size}")
            return results
            
        } catch (e: Exception) {
            println("💥 [PobreFlix] Erro durante a extração: ${e.message}")
            e.printStackTrace()
            return emptyList()
        }
    }
}
