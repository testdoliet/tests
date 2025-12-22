package com.AnimeFire

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.SubtitleFile
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element

class AnimeFireExtractor {
    suspend fun handleLinks(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("🔗 AnimeFireExtractor: Processando URL: $url")
        
        try {
            // Carregar a página do episódio
            val document = app.get(url, timeout = 30).document
            
            // Buscar o player de vídeo
            val videoSection = document.selectFirst("video#my-video")
            
            if (videoSection != null) {
                // Player HTML5 nativo
                extractFromHtml5Player(videoSection, callback)
            } else {
                // Tentar encontrar iframes
                val iframes = document.select("iframe")
                if (iframes.isNotEmpty()) {
                    extractFromIframes(iframes, url, subtitleCallback, callback)
                } else {
                    // Tentar encontrar scripts com links de vídeo
                    extractFromScripts(document, url, callback)
                }
            }
            
            // Procurar legendas (se disponíveis)
            extractSubtitles(document, subtitleCallback)
            
        } catch (e: Exception) {
            println("❌ Erro no AnimeFireExtractor: ${e.message}")
            throw e
        }
    }
    
    private fun extractFromHtml5Player(
        videoElement: Element,
        callback: (ExtractorLink) -> Unit
    ) {
        println("🎥 Encontrado player HTML5")
        
        // Extrair source do vídeo
        val videoSources = videoElement.select("source")
        
        videoSources.forEach { source ->
            val src = source.attr("src")
            val type = source.attr("type")
            
            if (src.isNotBlank()) {
                println("📹 Source encontrado: $src (type: $type)")
                
                // Determinar qualidade
                val quality = when {
                    src.contains("1080") || type.contains("1080") -> Qualities.P1080.value
                    src.contains("720") || type.contains("720") -> Qualities.P720.value
                    src.contains("480") || type.contains("480") -> Qualities.P480.value
                    src.contains("360") || type.contains("360") -> Qualities.P360.value
                    else -> Qualities.Unknown.value
                }
                
                // Determinar se é HLS ou MP4
                val isM3u8 = src.contains(".m3u8") || type.contains("application/x-mpegURL")
                
                callback(
                    ExtractorLink(
                        "AnimeFire",
                        "AnimeFire ${if (isM3u8) "HLS" else "Direct"}",
                        src,
                        referer = "https://animefire.plus/",
                        quality = quality,
                        isM3u8 = isM3u8
                    )
                )
            }
        }
        
        // Verificar também data-src ou outros atributos
        val dataSrc = videoElement.attr("data-src")
        if (dataSrc.isNotBlank()) {
            println("📹 Data-src encontrado: $dataSrc")
            
            callback(
                ExtractorLink(
                    "AnimeFire",
                    "AnimeFire Direct",
                    dataSrc,
                    referer = "https://animefire.plus/",
                    quality = Qualities.Unknown.value,
                    isM3u8 = dataSrc.contains(".m3u8")
                )
            )
        }
    }
    
    private suspend fun extractFromIframes(
        iframes: List<Element>,
        originalUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("🔍 Encontrados ${iframes.size} iframes")
        
        for ((index, iframe) in iframes.withIndex()) {
            try {
                val src = iframe.attr("src")
                if (src.isBlank() || src.startsWith("javascript")) continue
                
                println("🔄 Processando iframe $index: $src")
                
                // Verificar se é um player conhecido
                when {
                    src.contains("vidstream") -> {
                        println("🎬 Vidstream iframe detectado")
                        // Você pode adicionar suporte a vidstream aqui
                    }
                    src.contains("mp4upload") -> {
                        println("🎬 MP4Upload iframe detectado")
                        // Você pode adicionar suporte a mp4upload aqui
                    }
                    else -> {
                        // Tentar carregar o iframe e buscar vídeo
                        try {
                            val iframeDoc = app.get(fixUrl(src), timeout = 25).document
                            val video = iframeDoc.selectFirst("video")
                            if (video != null) {
                                extractFromHtml5Player(video, callback)
                            }
                        } catch (e: Exception) {
                            println("⚠️ Não foi possível carregar iframe: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                println("❌ Erro no iframe $index: ${e.message}")
            }
        }
    }
    
    private fun extractFromScripts(
        document: org.jsoup.nodes.Document,
        url: String,
        callback: (ExtractorLink) -> Unit
    ) {
        println("📜 Procurando em scripts...")
        
        // Buscar scripts que possam conter links de vídeo
        val scripts = document.select("script")
        
        for (script in scripts) {
            val scriptText = script.html()
            
            // Padrões comuns para links de vídeo
            val videoPatterns = listOf(
                Regex("""["'](https?://[^"']*\.(?:mp4|m3u8|mkv|avi|mov)[^"']*)["']"""),
                Regex("""["']file["']\s*:\s*["']([^"']+)["']"""),
                Regex("""["']src["']\s*:\s*["']([^"']+)["']"""),
                Regex("""["']url["']\s*:\s*["']([^"']+)["']"""),
                Regex("""sources\s*:\s*\[\s*\{[^}]+["']src["']\s*:\s*["']([^"']+)["'][^}]*\}""")
            )
            
            for (pattern in videoPatterns) {
                val matches = pattern.findAll(scriptText)
                matches.forEach { match ->
                    val videoUrl = match.groupValues[1]
                    if (videoUrl.isNotBlank() && (videoUrl.contains("http") || videoUrl.contains(".mp4") || videoUrl.contains(".m3u8"))) {
                        println("🎬 Vídeo encontrado no script: ${videoUrl.take(100)}...")
                        
                        val fullUrl = fixUrl(videoUrl)
                        
                        val quality = when {
                            fullUrl.contains("1080") -> Qualities.P1080.value
                            fullUrl.contains("720") -> Qualities.P720.value
                            fullUrl.contains("480") -> Qualities.P480.value
                            fullUrl.contains("360") -> Qualities.P360.value
                            else -> Qualities.Unknown.value
                        }
                        
                        callback(
                            ExtractorLink(
                                "AnimeFire",
                                "AnimeFire Script",
                                fullUrl,
                                referer = url,
                                quality = quality,
                                isM3u8 = fullUrl.contains(".m3u8")
                            )
                        )
                    }
                }
            }
        }
    }
    
    private fun extractSubtitles(
        document: org.jsoup.nodes.Document,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        println("📝 Procurando legendas...")
        
        // Buscar track elements (legendas HTML5)
        val tracks = document.select("track")
        
        tracks.forEach { track ->
            val src = track.attr("src")
            val kind = track.attr("kind")
            val label = track.attr("label")
            val srclang = track.attr("srclang")
            
            if (src.isNotBlank() && kind.equals("subtitles", ignoreCase = true)) {
                println("🎯 Legenda encontrada: $label ($srclang)")
                
                subtitleCallback(
                    SubtitleFile(
                        label ?: "Legenda",
                        fixUrl(src)
                    )
                )
            }
        }
        
        // Também procurar em scripts
        val scripts = document.select("script")
        val subPatterns = listOf(
            Regex("""["']subtitle["']\s*:\s*["']([^"']+)["']"""),
            Regex("""["']captions["']\s*:\s*\[[^\]]+["']src["']\s*:\s*["']([^"']+)["'][^\]]*\]""")
        )
        
        for (script in scripts) {
            val scriptText = script.html()
            for (pattern in subPatterns) {
                val matches = pattern.findAll(scriptText)
                matches.forEach { match ->
                    val subUrl = match.groupValues[1]
                    if (subUrl.isNotBlank() && (subUrl.contains(".vtt") || subUrl.contains(".srt"))) {
                        println("🎯 Legenda encontrada no script: ${subUrl.take(100)}...")
                        
                        subtitleCallback(
                            SubtitleFile(
                                "Legenda",
                                fixUrl(subUrl)
                            )
                        )
                    }
                }
            }
        }
    }
    
    private fun fixUrl(url: String): String {
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "https://animefire.plus$url"
            !url.startsWith("http") -> "https://animefire.plus/$url"
            else -> url
        }
    }
}
