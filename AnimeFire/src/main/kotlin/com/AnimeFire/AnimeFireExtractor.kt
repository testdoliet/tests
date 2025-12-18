package com.AnimeFire

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import org.json.JSONObject
import org.jsoup.Jsoup
import kotlin.random.Random

object AnimeFireExtractor {
    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🔗 AnimeFireExtractor: Extraindo de $url")
            
            // 1. Método principal: Extrair do iframe do Blogger
            val bloggerResult = extractFromBloggerIframe(url, mainUrl, name, callback)
            if (bloggerResult) return true
            
            // 2. Fallback: Tentar XHR padrão
            println("⚠️ AnimeFireExtractor: Método Blogger falhou, tentando XHR...")
            return tryStandardXHRMethod(url, mainUrl, name, callback)
            
        } catch (e: Exception) {
            println("💥 AnimeFireExtractor: Erro - ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    private suspend fun extractFromBloggerIframe(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🌐 AnimeFireExtractor: Procurando iframe do Blogger...")
            
            val pageResponse = app.get(url)
            val document = Jsoup.parse(pageResponse.text)
            
            // Procurar iframe do Blogger
            val iframe = document.select("iframe[src*='blogger.com/video.g']").first()
            if (iframe == null) {
                println("⚠️ AnimeFireExtractor: Iframe do Blogger não encontrado")
                return false
            }
            
            val iframeSrc = iframe.attr("src")
            println("🔗 AnimeFireExtractor: Iframe encontrado: $iframeSrc")
            
            // Acessar o iframe para obter o VIDEO_CONFIG
            val iframeResponse = app.get(iframeSrc, headers = mapOf(
                "Referer" to url,
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
            ))
            
            val iframeHtml = iframeResponse.text
            println("📄 AnimeFireExtractor: HTML do iframe (${iframeHtml.length} chars)")
            
            // Extrair o VIDEO_CONFIG do JavaScript
            return extractVideoConfigFromHtml(iframeHtml, iframeSrc, url, mainUrl, name, callback)
            
        } catch (e: Exception) {
            println("⚠️ AnimeFireExtractor: Erro no método Blogger: ${e.message}")
            false
        }
    }
    
    private fun extractVideoConfigFromHtml(
        html: String,
        iframeUrl: String,
        originalUrl: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Procurar por VIDEO_CONFIG no HTML
        val configPattern = """var\s+VIDEO_CONFIG\s*=\s*(\{.*?\});""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val match = configPattern.find(html)
        
        if (match == null) {
            println("⚠️ AnimeFireExtractor: VIDEO_CONFIG não encontrado")
            
            // Tentar encontrar de outra forma
            return tryAlternativeConfigExtraction(html, iframeUrl, originalUrl, mainUrl, name, callback)
        }
        
        try {
            val configJson = match.groupValues[1]
            println("🔧 AnimeFireExtractor: VIDEO_CONFIG encontrado")
            
            val config = JSONObject(configJson)
            
            // Verificar se tem streams
            if (!config.has("streams")) {
                println("⚠️ AnimeFireExtractor: Nenhum stream encontrado no config")
                return false
            }
            
            val streamsArray = config.getJSONArray("streams")
            var foundAny = false
            
            // Processar cada stream
            for (i in 0 until streamsArray.length()) {
                val stream = streamsArray.getJSONObject(i)
                
                if (stream.has("play_url")) {
                    val playUrl = stream.getString("play_url")
                    val formatId = stream.getInt("format_id")
                    
                    println("🔗 AnimeFireExtractor: Stream encontrado - itag $formatId: ${playUrl.take(80)}...")
                    
                    // Mapear itag para qualidade
                    val quality = when (formatId) {
                        18 -> 360  // 360p
                        22 -> 720  // 720p
                        37 -> 1080 // 1080p
                        59 -> 480  // 480p
                        43 -> 360  // 360p webm
                        else -> {
                            // Tentar extrair qualidade do URL
                            when {
                                formatId > 100 -> 1080
                                formatId > 80 -> 720
                                formatId > 60 -> 480
                                formatId > 40 -> 360
                                else -> 240
                            }
                        }
                    }
                    
                    val qualityName = when (quality) {
                        1080 -> "1080p"
                        720 -> "720p"
                        480 -> "480p"
                        360 -> "360p"
                        240 -> "240p"
                        else -> "SD"
                    }
                    
                    // Adicionar o link
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name ($qualityName)",
                            url = playUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = iframeUrl
                            this.quality = quality
                            this.headers = mapOf(
                                "Referer" to iframeUrl,
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                                "Accept" to "*/*",
                                "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
                                "Origin" to "https://www.blogger.com",
                                "Sec-Fetch-Dest" to "video",
                                "Sec-Fetch-Mode" to "no-cors",
                                "Sec-Fetch-Site" to "cross-site"
                            )
                        }
                    )
                    
                    foundAny = true
                    println("✅ AnimeFireExtractor: Adicionado $qualityName (itag $formatId)")
                }
            }
            
            return foundAny
            
        } catch (e: Exception) {
            println("⚠️ AnimeFireExtractor: Erro ao processar VIDEO_CONFIG: ${e.message}")
            return false
        }
    }
    
    private fun tryAlternativeConfigExtraction(
        html: String,
        iframeUrl: String,
        originalUrl: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Método alternativo: procurar por URLs de videoplayback diretamente
        println("🔄 AnimeFireExtractor: Tentando extração alternativa...")
        
        val videoPattern = """https?://[^"'\s<>]+googlevideo\.com/videoplayback[^"'\s<>]+""".toRegex()
        val matches = videoPattern.findAll(html)
        
        var foundAny = false
        
        for (match in matches) {
            val videoUrl = match.value
            println("🔗 AnimeFireExtractor: URL de video encontrada: ${videoUrl.take(80)}...")
            
            // Extrair itag da URL
            val itagPattern = """[?&]itag=(\d+)""".toRegex()
            val itagMatch = itagPattern.find(videoUrl)
            val itag = itagMatch?.groupValues?.get(1)?.toIntOrNull() ?: 18
            
            val quality = when (itag) {
                18 -> 360
                22 -> 720
                37 -> 1080
                59 -> 480
                43 -> 360
                else -> 360
            }
            
            val qualityName = when (quality) {
                1080 -> "1080p"
                720 -> "720p"
                480 -> "480p"
                360 -> "360p"
                else -> "SD"
            }
            
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name ($qualityName)",
                    url = videoUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = iframeUrl
                    this.quality = quality
                    this.headers = mapOf(
                        "Referer" to iframeUrl,
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                        "Accept" to "*/*"
                    )
                }
            )
            
            foundAny = true
            println("✅ AnimeFireExtractor: Adicionado alternativa $qualityName")
        }
        
        return foundAny
    }
    
    private suspend fun tryStandardXHRMethod(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val pathParts = url.removePrefix("https://animefire.io/animes/").split("/")
            if (pathParts.size < 2) return false
            
            val animeSlug = pathParts[0]
            val episodeNum = pathParts[1].toIntOrNull() ?: 1
            
            val timestamp = System.currentTimeMillis() / 1000
            val xhrUrl = "https://animefire.io/video/$animeSlug/$episodeNum?tempsubs=0&$timestamp"
            
            println("🌐 AnimeFireExtractor: Tentando XHR: $xhrUrl")
            
            val xhrResponse = app.get(
                xhrUrl,
                headers = mapOf(
                    "Referer" to url,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Accept" to "*/*",
                    "X-Requested-With" to "XMLHttpRequest"
                )
            )
            
            val xhrText = xhrResponse.text
            if (xhrText.length < 10) return false
            
            val json = JSONObject(xhrText)
            val dataArray = json.getJSONArray("data")
            
            var foundAny = false
            
            for (i in 0 until dataArray.length()) {
                val item = dataArray.getJSONObject(i)
                val videoUrl = item.optString("src", "")
                
                if (videoUrl.isNotEmpty()) {
                    val qualityLabel = item.optString("label", "")
                    val quality = extractQuality(qualityLabel, videoUrl)
                    val qualityName = getQualityName(quality, qualityLabel)
                    
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name ($qualityName)",
                            url = videoUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "$mainUrl/"
                            this.quality = quality
                            this.headers = mapOf(
                                "Referer" to url,
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                            )
                        }
                    )
                    foundAny = true
                    println("✅ AnimeFireExtractor: XHR encontrou $qualityName")
                }
            }
            
            foundAny
            
        } catch (e: Exception) {
            println("⚠️ AnimeFireExtractor: XHR falhou: ${e.message}")
            false
        }
    }
    
    private fun extractQuality(label: String, url: String): Int {
        return when {
            label.contains("1080") || url.contains("1080") -> 1080
            label.contains("720") || url.contains("720") -> 720
            label.contains("480") || url.contains("480") -> 480
            label.contains("360") || url.contains("360") -> 360
            label.contains("240") || url.contains("240") -> 240
            else -> 480
        }
    }
    
    private fun getQualityName(quality: Int, label: String): String {
        return when (quality) {
            1080 -> "1080p"
            720 -> "720p"
            480 -> "480p"
            360 -> "360p"
            240 -> "240p"
            else -> if (label.isNotEmpty()) label else "SD"
        }
    }
}
