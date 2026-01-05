package com.BetterFlix

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.SubtitleFile

class BetterFlixExtractor : ExtractorApi() {
    override val name = "BetterFlix"
    override val mainUrl = "https://betterflix.vercel.app"
    
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // Extrair informações da URL
            val tmdbId = extractTmdbId(url) ?: return false
            val isSeries = url.contains("type=tv")
            
            // Tentar ambas as fontes
            val success = trySource1(tmdbId, isSeries, subtitleCallback, callback) ||
                         trySource2(tmdbId, isSeries, subtitleCallback, callback)
            
            // Se ambas falharem, tentar URL direta
            if (!success) {
                tryDirectUrl(url, subtitleCallback, callback)
            } else {
                true
            }
        } catch (e: Exception) {
            false
        }
    }
    
    private fun extractTmdbId(url: String): String? {
        return Regex("[?&]id=(\\d+)").find(url)?.groupValues?.get(1)
    }
    
    private fun extractSeasonEpisode(url: String): Pair<Int, Int> {
        val seasonMatch = Regex("[?&]season=(\\d+)").find(url)
        val episodeMatch = Regex("[?&]episode=(\\d+)").find(url)
        
        val season = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val episode = episodeMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
        
        return season to episode
    }
    
    private suspend fun trySource1(
        tmdbId: String,
        isSeries: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val (season, episode) = if (isSeries) extractSeasonEpisodeFromCache(tmdbId) else (1 to 1)
            
            val playerUrl = if (isSeries) {
                "https://superflixapi.asia/serie/$tmdbId/$season/$episode"
            } else {
                "https://superflixapi.asia/filme/$tmdbId"
            }
            
            extractFromSuperflixApi(playerUrl, subtitleCallback, callback)
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun trySource2(
        tmdbId: String,
        isSeries: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val (season, episode) = if (isSeries) extractSeasonEpisodeFromCache(tmdbId) else (1 to 1)
            
            val playerUrl = if (isSeries) {
                "https://megaembed.com/embed/series?tmdb=$tmdbId&sea=$season&epi=$episode"
            } else {
                "https://megaembed.com/embed/movie?tmdb=$tmdbId"
            }
            
            extractFromMegaembed(playerUrl, subtitleCallback, callback)
        } catch (e: Exception) {
            false
        }
    }
    
    private fun extractSeasonEpisodeFromCache(tmdbId: String): Pair<Int, Int> {
        // Aqui você poderia implementar um cache de temporada/episódio
        // Por enquanto retorna padrão
        return 1 to 1
    }
    
    private suspend fun extractFromSuperflixApi(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                "Accept" to "*/*",
                "Accept-Language" to "pt-BR,pt;q=0.9",
                "Referer" to "https://betterflix.vercel.app/",
                "Origin" to "https://betterflix.vercel.app"
            )
            
            val response = app.get(url, headers = headers, timeout = 15_000)
            
            if (response.code != 200) return false
            
            val html = response.text
            
            // Padrões para encontrar m3u8
            val m3u8Patterns = listOf(
                Regex("""file["']?\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                Regex("""src=["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                Regex("""hls["']?\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                Regex("""(https?://[^"'\s]+\.m3u8[^\s"']*)""")
            )
            
            for (pattern in m3u8Patterns) {
                val matches = pattern.findAll(html).toList()
                for (match in matches) {
                    val m3u8Url = match.groupValues[1]
                    if (m3u8Url.contains(".m3u8")) {
                        if (createM3u8Link(m3u8Url, subtitleCallback, callback)) {
                            return true
                        }
                    }
                }
            }
            
            // Procurar por iframe
            val iframePattern = Regex("""<iframe[^>]+src=["']([^"']+)["']""")
            val iframeMatch = iframePattern.find(html)
            
            if (iframeMatch != null) {
                val iframeUrl = iframeMatch.groupValues[1]
                return extractFromIframe(iframeUrl, subtitleCallback, callback)
            }
            
            false
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun extractFromMegaembed(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "pt-BR,pt;q=0.9",
                "Referer" to "https://betterflix.vercel.app/"
            )
            
            val response = app.get(url, headers = headers, timeout = 15_000)
            
            if (response.code != 200) return false
            
            val html = response.text
            
            // Megaembed geralmente usa iframe
            val iframePattern = Regex("""<iframe[^>]+src=["']([^"']+)["']""")
            val iframeMatch = iframePattern.find(html)
            
            if (iframeMatch != null) {
                var iframeUrl = iframeMatch.groupValues[1]
                
                // Corrigir URL relativa
                if (iframeUrl.startsWith("//")) {
                    iframeUrl = "https:${iframeUrl}"
                } else if (iframeUrl.startsWith("/")) {
                    iframeUrl = "https://megaembed.com${iframeUrl}"
                }
                
                return extractFromIframe(iframeUrl, subtitleCallback, callback)
            }
            
            // Procurar por m3u8 direto
            val m3u8Pattern = Regex("""(https?://[^"'\s]+\.m3u8[^\s"']*)""")
            val m3u8Match = m3u8Pattern.find(html)
            
            if (m3u8Match != null) {
                val m3u8Url = m3u8Match.groupValues[1]
                return createM3u8Link(m3u8Url, subtitleCallback, callback)
            }
            
            false
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun extractFromIframe(
        iframeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "pt-BR,pt;q=0.9",
                "Referer" to "https://betterflix.vercel.app/"
            )
            
            val response = app.get(iframeUrl, headers = headers, timeout = 15_000)
            
            if (response.code != 200) return false
            
            val html = response.text
            
            // Procurar por m3u8 em vários padrões
            val patterns = listOf(
                Regex("""sources\s*:\s*\[\s*\{\s*[^}]*file\s*:\s*["']([^"']+\.m3u8[^"']*)["']"""),
                Regex("""file["']?\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                Regex("""hls["']?\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                Regex("""(https?://[^"'\s]+\.m3u8[^\s"']*)"""),
                Regex("""src\s*=\s*["'](https?://[^"']+\.m3u8[^"']*)["']""")
            )
            
            for (pattern in patterns) {
                val matches = pattern.findAll(html).toList()
                for (match in matches) {
                    val m3u8Url = match.groupValues[1]
                    if (m3u8Url.contains(".m3u8")) {
                        if (createM3u8Link(m3u8Url, subtitleCallback, callback)) {
                            return true
                        }
                    }
                }
            }
            
            false
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun tryDirectUrl(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // Tentar interpretar a URL como player direto
            val tmdbId = extractTmdbId(data)
            
            if (tmdbId != null) {
                // Construir URL do player
                val isSeries = data.contains("type=tv")
                val (season, episode) = if (isSeries) extractSeasonEpisode(data) else (1 to 1)
                
                val directUrl = if (isSeries) {
                    "https://vidplay.online/e/$tmdbId?c=$season-$episode"
                } else {
                    "https://vidplay.online/v/$tmdbId"
                }
                
                // Tentar extrair do VidPlay
                return extractFromVidPlay(directUrl, subtitleCallback, callback)
            }
            
            false
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun extractFromVidPlay(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                "Accept" to "*/*",
                "Accept-Language" to "pt-BR,pt;q=0.9",
                "Referer" to "https://betterflix.vercel.app/"
            )
            
            val response = app.get(url, headers = headers, timeout = 15_000)
            
            if (response.code != 200) return false
            
            val html = response.text
            
            // Padrões comuns do VidPlay
            val patterns = listOf(
                Regex("""sources:\s*\[\s*\{\s*file:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                Regex("""file["']?\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                Regex("""(https?://[^"'\s]+\.m3u8[^\s"']*)""")
            )
            
            for (pattern in patterns) {
                val match = pattern.find(html)
                if (match != null) {
                    val m3u8Url = match.groupValues[1]
                    return createM3u8Link(m3u8Url, subtitleCallback, callback)
                }
            }
            
            false
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun createM3u8Link(
        m3u8Url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                "Accept" to "*/*",
                "Accept-Language" to "pt-BR",
                "Referer" to "https://betterflix.vercel.app/",
                "Origin" to "https://betterflix.vercel.app"
            )
            
            // Usar M3u8Helper para gerar múltiplas qualidades
            val links = M3u8Helper.generateM3u8(
                source = name,
                streamUrl = m3u8Url,
                referer = "https://betterflix.vercel.app/",
                headers = headers
            )
            
            if (links.isNotEmpty()) {
                links.forEach { callback(it) }
                true
            } else {
                // Se M3u8Helper falhar, criar link direto
                val fallbackLink = newExtractorLink(
                    source = name,
                    name = "Video",
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "https://betterflix.vercel.app/"
                    this.quality = 720
                    this.headers = headers
                }
                callback(fallbackLink)
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}
