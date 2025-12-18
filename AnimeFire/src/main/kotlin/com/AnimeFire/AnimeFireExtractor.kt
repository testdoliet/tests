package com.AnimeFire

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import org.json.JSONObject
import org.jsoup.Jsoup

object AnimeFireExtractor {
    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🔗 AnimeFireExtractor: Extraindo de $url")

            // 1. Verificar se é YouTube/embed primeiro
            if (isYouTubeOrEmbed(url)) {
                println("🎬 AnimeFireExtractor: YouTube/Embed detectado, delegando...")
                return handleYouTubeOrEmbed(url, mainUrl, name, callback)
            }

            // 2. Extrair slug e episódio
            val (animeSlug, episodeNum) = extractAnimeInfo(url) ?: run {
                println("❌ AnimeFireExtractor: Não conseguiu extrair informações da URL")
                return false
            }

            println("✅ AnimeFireExtractor: Anime: $animeSlug, Episódio: $episodeNum")

            // 3. Tentar método XHR (player padrão)
            val xhrSuccess = tryXhrMethod(animeSlug, episodeNum, url, mainUrl, name, callback)
            if (xhrSuccess) {
                println("🎉 AnimeFireExtractor: Sucesso com método XHR")
                return true
            }

            // 4. Fallback: analisar página diretamente
            println("⚠️ AnimeFireExtractor: Método XHR falhou, tentando análise direta...")
            return analyzePageDirectly(url, mainUrl, name, callback)

        } catch (e: Exception) {
            println("💥 AnimeFireExtractor: Erro - ${e.message}")
            e.printStackTrace()
            false
        }
    }

    // ==================== MÉTODOS AUXILIARES ====================

    private fun isYouTubeOrEmbed(url: String): Boolean {
        return url.contains("youtube.com") || 
               url.contains("youtu.be") || 
               url.contains("youtube.googleapis.com") ||
               url.contains("youtubei.googleapis.com") ||
               url.contains("webembed") ||
               url.contains("/embed/") ||
               url.contains("googleapis.com/embed") ||
               url.contains("player")
    }

    private suspend fun handleYouTubeOrEmbed(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Tentar extrair URL do vídeo real
        val videoUrl = extractEmbeddedVideoUrl(url)
        
        if (videoUrl != null) {
            println("🔗 AnimeFireExtractor: URL extraída do embed: $videoUrl")
            // Usar extrator apropriado
            return loadExtractor(videoUrl, mainUrl, name, callback)
        }
        
        // Se não conseguir extrair, tentar acessar a página e analisar
        try {
            val response = app.get(url)
            val document = Jsoup.parse(response.text)
            
            // Procurar iframes
            val iframes = document.select("iframe[src]")
            for (iframe in iframes) {
                val src = iframe.attr("src")
                if (src.isNotEmpty()) {
                    println("🔗 AnimeFireExtractor: Iframe encontrado: $src")
                    val success = loadExtractor(src, mainUrl, name, callback)
                    if (success) return true
                }
            }
            
            // Procurar scripts com URLs de vídeo
            val scripts = document.select("script")
            for (script in scripts) {
                val scriptText = script.html()
                val videoPattern = """["'](https?://[^"']+\.(?:mp4|m3u8|mpd)[^"']*)["']""".toRegex()
                val matches = videoPattern.findAll(scriptText)
                
                for (match in matches) {
                    val foundUrl = match.groupValues[1]
                    if (foundUrl.contains("video") || foundUrl.contains("googlevideo")) {
                        println("🔗 AnimeFireExtractor: URL em script: $foundUrl")
                        
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = name,
                                url = foundUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "$mainUrl/"
                                this.quality = 720
                                this.headers = mapOf(
                                    "Referer" to url,
                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                                )
                            }
                        )
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            println("⚠️ AnimeFireExtractor: Erro ao analisar embed: ${e.message}")
        }
        
        return false
    }

    private fun extractEmbeddedVideoUrl(url: String): String? {
        // Extrair ID do YouTube
        val youtubePatterns = listOf(
            """youtube\.com/embed/([^/?&]+)""".toRegex(),
            """youtu\.be/([^/?&]+)""".toRegex(),
            """youtube\.com/watch\?v=([^&]+)""".toRegex(),
            """youtube\.com/v/([^/?&]+)""".toRegex()
        )
        
        for (pattern in youtubePatterns) {
            val match = pattern.find(url)
            if (match != null) {
                val videoId = match.groupValues[1]
                return "https://www.youtube.com/watch?v=$videoId"
            }
        }
        
        // Verificar se já é uma URL direta
        if (url.contains(".mp4") || url.contains(".m3u8") || url.contains(".mpd")) {
            return url
        }
        
        return null
    }

    private fun extractAnimeInfo(url: String): Pair<String, Int>? {
        return try {
            val pathParts = url.removePrefix("https://animefire.io/animes/").split("/")
            if (pathParts.size < 2) return null
            
            val animeSlug = pathParts[0]
            val episodeNum = pathParts[1].toIntOrNull() ?: 1
            
            Pair(animeSlug, episodeNum)
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun tryXhrMethod(
        animeSlug: String,
        episodeNum: Int,
        pageUrl: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val timestamp = System.currentTimeMillis() / 1000
            val xhrUrl = "https://animefire.io/video/$animeSlug/$episodeNum?tempsubs=0&$timestamp"
            
            println("🌐 AnimeFireExtractor: XHR para: $xhrUrl")
            
            val xhrResponse = app.get(
                xhrUrl,
                headers = mapOf(
                    "Referer" to pageUrl,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Accept" to "*/*",
                    "X-Requested-With" to "XMLHttpRequest"
                )
            )
            
            val xhrText = xhrResponse.text
            println("📄 AnimeFireExtractor: Resposta XHR (${xhrText.length} chars)")
            
            // Tentar parsear JSON
            val json = JSONObject(xhrText)
            val dataArray = json.getJSONArray("data")
            
            println("✅ AnimeFireExtractor: JSON parseado, ${dataArray.length()} itens")
            
            // Usar Set para evitar duplicados
            val uniqueLinks = mutableSetOf<String>()
            var foundAny = false
            
            for (i in 0 until dataArray.length()) {
                val item = dataArray.getJSONObject(i)
                val videoUrl = item.getString("src")
                val qualityLabel = item.optString("label", "")
                
                // Verificar se já processamos esta URL
                if (uniqueLinks.contains(videoUrl)) {
                    println("⚠️ AnimeFireExtractor: URL duplicada ignorada: $videoUrl")
                    continue
                }
                
                uniqueLinks.add(videoUrl)
                println("🔗 AnimeFireExtractor: $qualityLabel -> $videoUrl")
                
                val quality = extractQualityFromLabel(qualityLabel, videoUrl)
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
                            "Referer" to pageUrl,
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                        )
                    }
                )
                foundAny = true
            }
            
            foundAny
            
        } catch (e: Exception) {
            println("⚠️ AnimeFireExtractor: Falha no método XHR: ${e.message}")
            false
        }
    }

    private fun extractQualityFromLabel(label: String, url: String): Int {
        // Tentar extrair do label primeiro
        val labelQuality = when {
            label.contains("1080") -> 1080
            label.contains("720") -> 720
            label.contains("480") -> 480
            label.contains("360") -> 360
            label.contains("240") -> 240
            else -> {
                // Extrair número do label (ex: "360p")
                val pattern = """(\d+)p""".toRegex()
                val match = pattern.find(label)
                match?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }
        }
        
        // Se não encontrou no label, tentar na URL
        if (labelQuality == 0) {
            return when {
                url.contains("1080") -> 1080
                url.contains("720") -> 720
                url.contains("480") -> 480
                url.contains("360") -> 360
                url.contains("240") -> 240
                else -> 480 // Default
            }
        }
        
        return labelQuality
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

    private suspend fun analyzePageDirectly(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val response = app.get(url)
            val document = Jsoup.parse(response.text)
            var foundAny = false
            
            // Procurar por data-video-src
            val dataVideoSrc = document.selectFirst("[data-video-src]")?.attr("data-video-src")
            if (dataVideoSrc != null && dataVideoSrc.isNotEmpty()) {
                println("🔗 AnimeFireExtractor: data-video-src encontrado: $dataVideoSrc")
                
                // Construir URL completa
                val videoApiUrl = if (dataVideoSrc.startsWith("/")) {
                    "https://animefire.io$dataVideoSrc"
                } else if (dataVideoSrc.startsWith("http")) {
                    dataVideoSrc
                } else {
                    "https://animefire.io/$dataVideoSrc"
                }
                
                // Fazer nova requisição
                val apiResponse = app.get(videoApiUrl)
                val apiText = apiResponse.text
                
                // Procurar links MP4
                val mp4Pattern = """https?://[^"\s<>']+\.mp4(?:\?[^"\s<>']*)?""".toRegex(RegexOption.IGNORE_CASE)
                val allLinks = mp4Pattern.findAll(apiText).map { it.value }.toList().distinct()
                
                for (link in allLinks.filter { it.contains("lightspeedst.net") }) {
                    println("🔗 AnimeFireExtractor: Link via fallback: $link")
                    
                    val quality = extractQualityFromLabel("", link)
                    val qualityName = getQualityName(quality, "")
                    
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name ($qualityName)",
                            url = link,
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
                }
            }
            
            foundAny
            
        } catch (e: Exception) {
            println("⚠️ AnimeFireExtractor: Erro na análise direta: ${e.message}")
            false
        }
    }
}
