package com.Superflix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.Jsoup

class SuperflixExtractor : ExtractorApi() {
    override val name = "Superflix"
    override val mainUrl = "https://superflix1.cloud"
    override val requiresReferer = true
    
    companion object {
        private const val TAG = "SuperflixExtractor"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("[$TAG] ========== INICIANDO EXTRACTION ==========")
        println("[$TAG] URL recebida: $url")
        println("[$TAG] Referer: ${referer ?: "null"}")

        try {
            val refererHeader = referer ?: mainUrl
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                "Referer" to refererHeader,
                "Origin" to mainUrl,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8"
            )

            // ESTRATÉGIA 1: Extrair owner_id/player_id da URL
            println("[$TAG] === ESTRATÉGIA 1: Extraindo IDs ===")
            
            val ids = extractAllIds(url)
            println("[$TAG] IDs encontrados: $ids")
            
            // Primeiro, tentar usar os IDs para acessar a API
            val videoUrlFromApi = tryApiWithIds(ids, refererHeader)
            if (videoUrlFromApi != null) {
                println("[$TAG] URL obtida da API: $videoUrlFromApi")
                createExtractorLink(videoUrlFromApi, refererHeader, callback)
                println("[$TAG] ========== EXTRACTION SUCESSO (API) ==========")
                return
            }
            
            // ESTRATÉGIA 2: Acessar a página e analisar
            println("[$TAG] === ESTRATÉGIA 2: Analisando página ===")
            
            val res = app.get(url, headers = headers)
            println("[$TAG] Status: ${res.code}, Tamanho: ${res.text.length}")
            
            // Salvar página para debug (opcional)
            // println("[$TAG] Primeiros 1000 chars: ${res.text.take(1000)}")
            
            // Buscar URLs do service worker/player
            val playerUrls = extractPlayerUrls(res.text)
            println("[$TAG] Player URLs encontradas: ${playerUrls.size}")
            
            for ((index, playerUrl) in playerUrls.withIndex()) {
                println("[$TAG] Player $index: $playerUrl")
                
                // Tentar extrair vídeo do player
                val videoUrl = extractFromPlayer(playerUrl, refererHeader)
                if (videoUrl != null) {
                    println("[$TAG] Vídeo encontrado no player: $videoUrl")
                    createExtractorLink(videoUrl, refererHeader, callback)
                    println("[$TAG] ========== EXTRACTION SUCESSO (Player) ==========")
                    return
                }
            }
            
            // ESTRATÉGIA 3: Procurar URLs diretas de vídeo
            println("[$TAG] === ESTRATÉGIA 3: Buscando URLs diretas ===")
            
            val directUrls = extractDirectVideoUrls(res.text)
            println("[$TAG] URLs diretas encontradas: ${directUrls.size}")
            
            if (directUrls.isNotEmpty()) {
                // Usar a primeira URL de vídeo encontrada
                val videoUrl = directUrls.first()
                println("[$TAG] Usando URL direta: $videoUrl")
                createExtractorLink(videoUrl, refererHeader, callback)
                println("[$TAG] ========== EXTRACTION SUCESSO (Direto) ==========")
                return
            }
            
            // ESTRATÉGIA 4: Tentar requisição AJAX/API
            println("[$TAG] === ESTRATÉGIA 4: Tentando API direta ===")
            
            // Tentar com diferentes endpoints da API
            val apiEndpoints = listOf(
                "$mainUrl/api/video/${ids["owner_id"] ?: ids["id"] ?: "0"}",
                "$mainUrl/api/player/${ids["player_id"] ?: ids["id"] ?: "0"}",
                "$mainUrl/api/stream/${ids["stream_id"] ?: ids["id"] ?: "0"}"
            )
            
            for (apiUrl in apiEndpoints) {
                try {
                    println("[$TAG] Tentando API: $apiUrl")
                    val apiResponse = app.get(apiUrl, headers = headers)
                    
                    if (apiResponse.code == 200) {
                        val videoData = extractVideoFromApiResponse(apiResponse.text)
                        if (videoData != null) {
                            println("[$TAG] Vídeo encontrado via API: $videoData")
                            createExtractorLink(videoData, refererHeader, callback)
                            println("[$TAG] ========== EXTRACTION SUCESSO (API) ==========")
                            return
                        }
                    }
                } catch (e: Exception) {
                    println("[$TAG] API falhou: ${e.message}")
                }
            }
            
            println("[$TAG] ========== EXTRACTION FALHOU ==========")
            
        } catch (e: Exception) {
            println("[$TAG] ERRO: ${e.message}")
            e.printStackTrace()
        }
    }
    
    private fun extractAllIds(url: String): Map<String, String> {
        val ids = mutableMapOf<String, String>()
        
        // Padrões comuns para IDs
        val patterns = mapOf(
            "owner_id" to listOf(
                """owner_id["']?\s*:\s*["']?(\d+)["']?""",
                """["']owner_id["']\s*:\s*(\d+)""",
                """/api/video/(\d+)""",
                """ownerId["']?\s*:\s*["']?(\d+)["']?"""
            ),
            "id" to listOf(
                """["']id["']\s*:\s*["']?(\d+)["']?""",
                """data-id=["'](\d+)["']""",
                """episodio/(\d+)""",
                """video/(\d+)""",
                """\b(\d{6,})\b"""
            ),
            "player_id" to listOf(
                """player_id["']?\s*:\s*["']?(\d+)["']?""",
                """["']player["']\s*:\s*\{[^}]+["']id["']\s*:\s*["']?(\d+)["']?"""
            ),
            "media_id" to listOf(
                """media_id["']?\s*:\s*["']?(\d+)["']?""",
                """["']mediaId["']\s*:\s*["']?(\d+)["']?"""
            )
        )
        
        patterns.forEach { (key, patternList) ->
            patternList.forEach { pattern ->
                val match = Regex(pattern).find(url)
                if (match != null && ids[key] == null) {
                    ids[key] = match.groupValues[1]
                }
            }
        }
        
        return ids
    }
    
    private suspend fun tryApiWithIds(ids: Map<String, String>, referer: String): String? {
        val ownerId = ids["owner_id"] ?: ids["id"] ?: return null
        
        try {
            val apiUrl = "$mainUrl/api/video/$ownerId"
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                "Referer" to referer,
                "Origin" to mainUrl,
                "Accept" to "application/json, text/plain, */*"
            )
            
            val response = app.get(apiUrl, headers = headers)
            
            if (response.code == 200) {
                // Tentar parsear como JSON
                try {
                    val json = tryParseJson<ApiResponse>(response.text)
                    if (json?.source != null) {
                        return json.source
                    }
                } catch (e: Exception) {
                    println("[$TAG] Não é JSON válido, tentando extrair texto")
                }
                
                // Tentar extrair URL do texto da resposta
                val urlPatterns = listOf(
                    """source["']?\s*:\s*["']([^"']+)["']""",
                    """url["']?\s*:\s*["']([^"']+)["']""",
                    """(https?://[^\s"']+\.(?:mp4|m3u8|mkv))""",
                    """(https?://playembedapi\.site[^\s"']+)""",
                    """(https?://storage\.googleapis\.com[^\s"']+)"""
                )
                
                urlPatterns.forEach { pattern ->
                    val match = Regex(pattern).find(response.text)
                    if (match != null) {
                        return match.groupValues[1]
                    }
                }
            }
        } catch (e: Exception) {
            println("[$TAG] Erro na API: ${e.message}")
        }
        
        return null
    }
    
    private fun extractPlayerUrls(html: String): List<String> {
        val urls = mutableListOf<String>()
        
        // Padrões para encontrar URLs de player
        val patterns = listOf(
            """(https?://playembedapi\.site/[^\s"']+)""",
            """source["']?\s*:\s*["'](https?://playembedapi\.site[^"']+)["']""",
            """play\([^)]+source["']?\s*:\s*["'](https?://[^"']+)["']""",
            """<iframe[^>]+src=["'](https?://[^"']+)["']""",
            """player\.src\s*=\s*["'](https?://[^"']+)["']"""
        )
        
        patterns.forEach { pattern ->
            Regex(pattern, RegexOption.DOT_MATCHES_ALL).findAll(html).forEach { match ->
                val url = match.groupValues[1]
                if (url.isNotBlank() && !urls.contains(url)) {
                    urls.add(url)
                }
            }
        }
        
        return urls.distinct()
    }
    
    private suspend fun extractFromPlayer(playerUrl: String, referer: String): String? {
        println("[$TAG] Acessando player: $playerUrl")
        
        try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                "Referer" to referer,
                "Origin" to mainUrl
            )
            
            val response = app.get(playerUrl, headers = headers)
            
            // Procurar URLs do Google Storage
            val storageUrls = extractGoogleStorageUrls(response.text)
            if (storageUrls.isNotEmpty()) {
                return storageUrls.first()
            }
            
            // Procurar URLs do SORA
            val soraUrls = extractSoraUrls(response.text)
            if (soraUrls.isNotEmpty()) {
                return soraUrls.first()
            }
            
            // Procurar URLs MP4/M3U8 diretas
            val directUrls = extractDirectVideoUrls(response.text)
            if (directUrls.isNotEmpty()) {
                return directUrls.first()
            }
            
        } catch (e: Exception) {
            println("[$TAG] Erro no player: ${e.message}")
        }
        
        return null
    }
    
    private fun extractGoogleStorageUrls(html: String): List<String> {
        return Regex("""https?://storage\.googleapis\.com/mediastorage/[^\s"']+\.mp4""")
            .findAll(html)
            .map { it.value }
            .toList()
            .distinct()
    }
    
    private fun extractSoraUrls(html: String): List<String> {
        return Regex("""https?://[^\s"']+\.sssrr\.org/sora/[^\s"']+""")
            .findAll(html)
            .map { it.value }
            .toList()
            .distinct()
    }
    
    private fun extractDirectVideoUrls(html: String): List<String> {
        val urls = mutableListOf<String>()
        
        val patterns = listOf(
            """(https?://[^\s"']+\.(?:mp4|m3u8|mkv)[^\s"']*)""",
            """file["']?\s*:\s*["']([^"']+)["']""",
            """url["']?\s*:\s*["']([^"']+)["']""",
            """src=["']([^"']+)["']"""
        )
        
        patterns.forEach { pattern ->
            Regex(pattern).findAll(html).forEach { match ->
                val url = match.groupValues.getOrNull(1) ?: match.value
                if (url.isNotBlank() && 
                    (url.contains(".mp4") || url.contains(".m3u8") || url.contains(".mkv")) &&
                    !url.contains("google-analytics") &&
                    !urls.contains(url)) {
                    urls.add(url)
                }
            }
        }
        
        return urls.distinct()
    }
    
    private fun extractVideoFromApiResponse(text: String): String? {
        val patterns = listOf(
            """source["']?\s*:\s*["']([^"']+)["']""",
            """url["']?\s*:\s*["']([^"']+)["']""",
            """video_url["']?\s*:\s*["']([^"']+)["']""",
            """(https?://[^\s"']+\.(?:mp4|m3u8))"""
        )
        
        patterns.forEach { pattern ->
            val match = Regex(pattern).find(text)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        
        return null
    }
    
    private fun createExtractorLink(
        videoUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val isM3u8 = videoUrl.contains(".m3u8", ignoreCase = true)
        val isGoogleStorage = videoUrl.contains("storage.googleapis.com")
        
        val quality = when {
            videoUrl.contains("1080") -> Qualities.P1080.value
            videoUrl.contains("720") -> Qualities.P720.value
            videoUrl.contains("480") -> Qualities.P480.value
            videoUrl.contains("360") -> Qualities.P360.value
            else -> Qualities.P720.value
        }
        
        val headers = if (isGoogleStorage) {
            mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                "Referer" to "https://playembedapi.site/",
                "Origin" to "https://playembedapi.site",
                "Range" to "bytes=0-"
            )
        } else {
            mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                "Referer" to referer,
                "Origin" to mainUrl
            )
        }
        
        callback(newExtractorLink(
            source = name,
            name = "Superflix ${if (isM3u8) "HLS" else if (isGoogleStorage) "Google Storage" else "Video"}",
            url = videoUrl,
            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        ) {
            this.referer = referer
            this.quality = quality
            this.headers = headers
        })
    }
    
    // Classes para parsear JSON
    data class ApiResponse(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("source") val source: String? = null,
        @JsonProperty("subtitles") val subtitles: String? = null,
        @JsonProperty("player") val player: String? = null,
        @JsonProperty("audio_type") val audio_type: String? = null,
        @JsonProperty("owner_type") val owner_type: String? = null,
        @JsonProperty("owner_id") val owner_id: Int? = null,
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("type") val type: String? = null
    )
}
