package com.Superflix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.Jsoup
import com.fasterxml.jackson.annotation.JsonProperty

class SuperflixExtractor : ExtractorApi() {
    override val name = "Superflix"
    override val mainUrl = "https://superflix1.cloud"
    override val requiresReferer = true
    
    companion object {
        private const val TAG = "SuperflixExtractor"
        private const val PLAYER_DOMAIN = "https://assistirseriesonline.icu"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) { // REMOVE O Boolean, retorna Unit
        println("[$TAG] ========== INICIANDO EXTRACTION ==========")
        println("[$TAG] URL recebida: $url")
        println("[$TAG] Referer: ${referer ?: "null"}")
        println("[$TAG] Main URL: $mainUrl")

        try {
            // CORREÇÃO: Headers como Map<String, String>
            val headers = mapOf<String, String>(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                "Referer" to referer ?: mainUrl,
                "Origin" to mainUrl
            )

            // ESTRATÉGIA 1: Tentar extrair episodeId da URL
            println("[$TAG] === ESTRATÉGIA 1: Extraindo IDs ===")
            val episodeId = extractEpisodeId(url)
            println("[$TAG] Episode ID extraído: $episodeId")
            
            if (episodeId != null) {
                // Tentar API do player
                val videoUrl = tryGetVideoFromEpisode(episodeId, referer)
                if (videoUrl != null) {
                    println("[$TAG] URL do vídeo encontrada: $videoUrl")
                    
                    callback(newExtractorLink(
                        source = name,
                        name = "Superflix Player",
                        url = videoUrl,
                        type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = referer ?: mainUrl
                        this.quality = Qualities.P720.value
                        this.headers = headers
                    })
                    
                    println("[$TAG] ========== EXTRACTION SUCESSO ==========")
                    return
                }
            }
            
            // ESTRATÉGIA 2: Analisar a página
            println("[$TAG] === ESTRATÉGIA 2: Analisando página ===")
            
            val res = app.get(url, headers = headers, referer = referer)
            println("[$TAG] Status: ${res.code}, Tamanho: ${res.text.length}")
            
            val foundUrls = mutableSetOf<String>()
            
            // Procurar URLs de vídeo
            val patterns = listOf(
                """(https?://[^\s"'<>]+\.(?:m3u8|mp4|mkv)[^\s"'<>]*)""",
                """["'](https?://storage\.googleapis\.com[^"']+)["']""",
                """file["']?\s*:\s*["']([^"']+)["']""",
                """source["']?\s*:\s*["']([^"']+)["']""",
                """src=["']([^"']+)["']""",
                """url["']?\s*:\s*["']([^"']+)["']"""
            )
            
            patterns.forEachIndexed { i, pattern ->
                Regex(pattern).findAll(res.text).forEach { match ->
                    val foundUrl = match.groupValues[1]
                    if (foundUrl.isNotBlank() && !foundUrls.contains(foundUrl)) {
                        println("[$TAG] Padrão $i encontrou: $foundUrl")
                        foundUrls.add(foundUrl)
                    }
                }
            }
            
            // Processar URLs encontradas
            foundUrls.forEach { videoUrl ->
                val isM3u8 = videoUrl.contains(".m3u8", ignoreCase = true)
                
                callback(newExtractorLink(
                    source = name,
                    name = "Superflix ${if (isM3u8) "HLS" else "MP4"}",
                    url = videoUrl,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer ?: mainUrl
                    this.quality = if (isM3u8) Qualities.P720.value else Qualities.P1080.value
                    this.headers = headers
                })
            }
            
            if (foundUrls.isNotEmpty()) {
                println("[$TAG] ========== EXTRACTION SUCESSO (${foundUrls.size} links) ==========")
                return
            }
            
            println("[$TAG] ========== EXTRACTION FALHOU ==========")
            
        } catch (e: Exception) {
            println("[$TAG] ERRO: ${e.message}")
            e.printStackTrace()
        }
    }
    
    private fun extractEpisodeId(url: String): String? {
        // Padrões comuns para IDs de episódio
        val patterns = listOf(
            """episodio/(\d+)""",
            """embed/(\d+)""",
            """#(\d+_\d+)""",
            """(\d{6,})""",
            """data-episode-id=["'](\d+)["']"""
        )
        
        patterns.forEach { pattern ->
            val match = Regex(pattern).find(url)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        
        return null
    }
    
    private suspend fun tryGetVideoFromEpisode(episodeId: String, referer: String?): String? {
        println("[$TAG] Tentando obter vídeo para episódio $episodeId")
        
        try {
            // Tentar acessar a API do episódio
            val episodeUrl = "$PLAYER_DOMAIN/episodio/$episodeId"
            println("[$TAG] Acessando: $episodeUrl")
            
            // CORREÇÃO: Headers como Map<String, String>
            val headers = mapOf<String, String>(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                "Referer" to referer ?: mainUrl,
                "Origin" to mainUrl
            )
            
            val res = app.get(episodeUrl, headers = headers, referer = referer)
            
            // Procurar URLs de storage.googleapis.com (que você encontrou)
            val storagePattern = """(https?://storage\.googleapis\.com/[^\s"'<>]+\.mp4)"""
            val match = Regex(storagePattern).find(res.text)
            
            if (match != null) {
                val videoUrl = match.groupValues[1]
                println("[$TAG] Encontrado storage URL: $videoUrl")
                return videoUrl
            }
            
            // Procurar por variáveis JavaScript
            val jsPatterns = listOf(
                """source["']?\s*:\s*["']([^"']+)["']""",
                """file["']?\s*:\s*["']([^"']+)["']""",
                """var\s+player\s*=\s*\{[^}]+source["']?\s*:\s*["']([^"']+)["']"""
            )
            
            jsPatterns.forEach { pattern ->
                val jsMatch = Regex(pattern, RegexOption.DOT_MATCHES_ALL).find(res.text)
                if (jsMatch != null) {
                    val url = jsMatch.groupValues[1]
                    if (url.isNotBlank() && (url.contains("http") || url.contains(".mp4") || url.contains(".m3u8"))) {
                        println("[$TAG] Encontrado via JS: $url")
                        return if (url.startsWith("http")) url else "$PLAYER_DOMAIN/$url"
                    }
                }
            }
            
        } catch (e: Exception) {
            println("[$TAG] Erro ao acessar episódio: ${e.message}")
        }
        
        return null
    }
}
