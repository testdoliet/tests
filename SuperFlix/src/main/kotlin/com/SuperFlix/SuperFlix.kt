package com.SuperFlix

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.metaproviders.TmdbLink
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder


class SuperFlix : TmdbProvider() {
    override var name = "SuperFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val instantLinkLoading = true
    override val useMetaLoadResponse = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
    )

    companion object {
        const val HOST = "https://superflix21.lol"
        
        // Configuração de idioma para TMDB
        private const val TMDB_LANG = "pt-BR"
        private const val TMDB_REGION = "BR"
    }
    
    // Sobrescreve a configuração de idioma do TMDB
    override val mainPage = mainPageOf(
        "" to "Filmes e Séries"
    )
    
    // Função para corrigir URLs
    private fun fixUrl(url: String): String {
        return when {
            url.isEmpty() -> url
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$HOST$url"
            else -> "$HOST/$url"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val mediaData = AppUtils.parseJson<TmdbLink>(data).toLinkData()
            
            println("🎬 [SuperFlix] Buscando: ${mediaData.title ?: "Unknown"}")
            println("🎬 [SuperFlix] TMDB ID: ${mediaData.tmdbId}")
            
            // 1. Busca no site SuperFlix
            val searchQuery = mediaData.title ?: return false
            val searchUrl = "$HOST/buscar?q=${URLEncoder.encode(searchQuery, "UTF-8")}"
            
            println("🔍 [SuperFlix] Buscando em: $searchUrl")
            val document = app.get(searchUrl).document
            
            // 2. Encontra primeiro resultado
            val firstResult = document.selectFirst(".grid .card, a.card, .movie-item a, .rec-card")
            if (firstResult == null) {
                println("❌ [SuperFlix] Nenhum resultado encontrado")
                return false
            }
            
            val detailUrl = firstResult.attr("href")?.let { fixUrl(it) } ?: return false
            println("🔗 [SuperFlix] Página de detalhes: $detailUrl")
            
            // 3. Carrega página de detalhes
            val detailDoc = app.get(detailUrl).document
            
            // 4. Encontra player usando múltiplos métodos
            val playerUrl = findPlayerUrl(detailDoc)
            if (playerUrl == null) {
                println("❌ [SuperFlix] Player não encontrado")
                return false
            }
            
            println("🎥 [SuperFlix] Player URL: $playerUrl")
            
            // 5. Usa seu extractor personalizado
            val success = SuperFlixExtractor.extractVideoLinks(playerUrl, HOST, name, callback)
            
            if (!success) {
                println("⚠️ [SuperFlix] Extractor falhou, tentando método alternativo...")
                // Fallback para método tipo Tamilian
                extractVideoFallback(playerUrl, callback)
            }
            
            success
        } catch (e: Exception) {
            println("💥 [SuperFlix] Erro: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    private fun findPlayerUrl(document: org.jsoup.nodes.Document): String? {
        // Método 1: Botão com data-url
        val playButton = document.selectFirst("button.bd-play[data-url], button[data-url*='watch'], .play-btn[data-url]")
        if (playButton != null) {
            val url = playButton.attr("data-url")
            println("🔘 [SuperFlix] Player encontrado no botão: $url")
            return fixUrl(url)
        }
        
        // Método 2: Iframe
        val iframe = document.selectFirst("iframe[src*='player'], iframe[src*='embed'], iframe[src*='watch']")
        if (iframe != null) {
            val url = iframe.attr("src")
            println("📺 [SuperFlix] Player encontrado no iframe: $url")
            return fixUrl(url)
        }
        
        // Método 3: Link direto
        val videoLink = document.selectFirst("a[href*='.m3u8'], a[href*='assistir'], a[href*='watch']")
        if (videoLink != null) {
            val url = videoLink.attr("href")
            println("🔗 [SuperFlix] Player encontrado no link: $url")
            return fixUrl(url)
        }
        
        // Método 4: Script com URL
        val scripts = document.select("script")
        for (script in scripts) {
            val scriptText = script.data() + script.html()
            val m3u8Match = Regex("(https?:[^\"']+\\.m3u8[^\"' ]*)").find(scriptText)
            if (m3u8Match != null) {
                val url = m3u8Match.value
                println("📜 [SuperFlix] Player encontrado no script: $url")
                return url
            }
        }
        
        return null
    }
    
    private suspend fun extractVideoFallback(playerUrl: String, callback: (ExtractorLink) -> Unit) {
        try {
            val playerDoc = app.get(playerUrl).document
            
            // Tenta método tipo Tamilian (FirePlayer)
            val script = playerDoc.selectFirst("script:containsData(function(p,a,c,k,e,d))")
                ?.data()?.let { getAndUnpack(it) }
            
            if (script != null) {
                println("🔓 [SuperFlix] Script FirePlayer encontrado")
                val token = script.substringAfter("FirePlayer(\"").substringBefore("\",")
                val videoApiUrl = "$HOST/player/index.php?data=$token&do=getVideo"
                
                println("🔄 [SuperFlix] Chamando API: $videoApiUrl")
                val videoData = app.post(videoApiUrl, headers = mapOf("X-Requested-With" to "XMLHttpRequest"))
                    .parsedSafe<VideoData>()
                
                videoData?.videoSource?.let { videoUrl ->
                    println("✅ [SuperFlix] Vídeo encontrado: $videoUrl")
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "SuperFlix Stream",
                            url = videoUrl,
                            ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$HOST/"
                            this.quality = Qualities.P1080.value
                            this.headers = mapOf(
                                "Origin" to HOST,
                                "Referer" to playerUrl,
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                            )
                        }
                    )
                }
            }
        } catch (e: Exception) {
            println("⚠️ [SuperFlix] Fallback falhou: ${e.message}")
        }
    }

    private fun TmdbLink.toLinkData(): LinkData {
        return LinkData(
            imdbId = imdbID,
            tmdbId = tmdbID,
            title = movieName,
            season = season,
            episode = episode
        )
    }

    data class LinkData(
        @JsonProperty("imdbId") val imdbId: String? = null,
        @JsonProperty("tmdbId") val tmdbId: Int? = null,
        @JsonProperty("tvdbId") val tvdbId: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("episode") val episode: Int? = null,
        @JsonProperty("year") val year: Int? = null,
    )

    data class VideoData(
        @JsonProperty("hls") val hls: Boolean,
        @JsonProperty("videoImage") val videoImage: String,
        @JsonProperty("videoSource") val videoSource: String,
        @JsonProperty("securedLink") val securedLink: String,
        @JsonProperty("downloadLinks") val downloadLinks: List<Any?>,
        @JsonProperty("attachmentLinks") val attachmentLinks: List<Any?>,
        @JsonProperty("ck") val ck: String,
    )
}
