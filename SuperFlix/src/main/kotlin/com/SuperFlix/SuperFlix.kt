package com.SuperFlix

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.metaproviders.TmdbLink
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder
import java.text.SimpleDateFormat

class SuperFlix : TmdbProvider() {
    override var name = "SuperFlix"
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
    }
    
    override val mainUrl = HOST
    
    // Página principal
    override val mainPage = mainPageOf(
        "$HOST/lancamentos" to "Lançamentos",
        "$HOST/filmes" to "Últimos Filmes",
        "$HOST/series" to "Últimas Séries",
        "$HOST/animes" to "Últimas Animes"
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

    // Função para mapear tipos do site para tipos TMDB
    private fun Element.toTmdbType(): String? {
        val href = attr("href") ?: return null
        val badge = selectFirst(".badge-kind")?.text()?.lowercase() ?: ""
        
        return when {
            badge.contains("anime") || href.contains("/anime/") -> "tv"
            badge.contains("série") || badge.contains("serie") || href.contains("/serie/") || href.contains("/tv/") -> "tv"
            else -> "movie"
        }
    }

    // Buscar no site (para quick search e main page)
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$HOST/buscar?q=${URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document

        return document.select(".grid .card, a.card").mapNotNull { card ->
            try {
                val title = card.attr("title") ?: card.selectFirst("img")?.attr("alt") ?: return@mapNotNull null
                val href = card.attr("href") ?: return@mapNotNull null

                val poster = card.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
                val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()

                val tmdbType = card.toTmdbType()
                
                // Usar TMDB para metadados
                val tmdbId = when (tmdbType) {
                    "tv" -> tmdb.searchTv(cleanTitle, 1, year).firstOrNull()?.id
                    else -> tmdb.search(cleanTitle, 1, year, "movie").firstOrNull()?.id
                }

                if (tmdbId != null) {
                    // Se encontrou no TMDB, usar resposta TMDB
                    when (tmdbType) {
                        "tv" -> {
                            val details = tmdb.getTvDetails(tmdbId, lang)
                            newTvSeriesSearchResponse(details?.name ?: cleanTitle, fixUrl(href), TvType.TvSeries) {
                                this.posterUrl = details?.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" } ?: poster
                                this.year = details?.first_air_date?.substring(0, 4)?.toIntOrNull() ?: year
                                this.id = tmdbId
                            }
                        }
                        else -> {
                            val details = tmdb.getMovieDetails(tmdbId, lang)
                            newMovieSearchResponse(details?.title ?: cleanTitle, fixUrl(href), TvType.Movie) {
                                this.posterUrl = details?.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" } ?: poster
                                this.year = details?.release_date?.substring(0, 4)?.toIntOrNull() ?: year
                                this.id = tmdbId
                            }
                        }
                    }
                } else {
                    // Se não encontrou no TMDB, usar dados do site
                    when (tmdbType) {
                        "tv" -> newTvSeriesSearchResponse(cleanTitle, fixUrl(href), TvType.TvSeries) {
                            this.posterUrl = poster
                            this.year = year
                        }
                        else -> newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                            this.posterUrl = poster
                            this.year = year
                        }
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    // Carregar página principal
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + if (page > 1) "?page=$page" else ""
        val document = app.get(url).document

        val home = document.select("a.card, div.recs-grid a.rec-card").mapNotNull { element ->
            try {
                val title = element.attr("title") ?: element.selectFirst("img")?.attr("alt") ?: return@mapNotNull null
                val href = element.attr("href") ?: return@mapNotNull null

                val poster = element.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
                val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()

                val tmdbType = element.toTmdbType()
                
                when (tmdbType) {
                    "tv" -> newTvSeriesSearchResponse(cleanTitle, fixUrl(href), TvType.TvSeries) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    else -> newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                        this.posterUrl = poster
                        this.year = year
                    }
                }
            } catch (e: Exception) {
                null
            }
        }

        return newHomePageResponse(request.name, home.distinctBy { it.url })
    }

    // ESSA É A PARTE IMPORTANTE: Usar TMDB para metadados completos
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
            
            // 4. Encontra player
            val playerUrl = findPlayerUrl(detailDoc)
            if (playerUrl == null) {
                println("❌ [SuperFlix] Player não encontrado")
                return false
            }
            
            println("🎥 [SuperFlix] Player URL: $playerUrl")
            
            // 5. Extrai links de vídeo
            extractVideoLinks(playerUrl, callback)
            true
        } catch (e: Exception) {
            println("💥 [SuperFlix] Erro: ${e.message}")
            false
        }
    }
    
    private suspend fun extractVideoLinks(playerUrl: String, callback: (ExtractorLink) -> Unit) {
        try {
            // Método 1: Tentar como Tamilian (FirePlayer)
            val playerDoc = app.get(playerUrl).document
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
            } else {
                // Método 2: Tentar extrair m3u8 diretamente
                val m3u8Match = Regex("(https?:[^\"']+\\.m3u8[^\"' ]*)").find(playerDoc.text())
                if (m3u8Match != null) {
                    val videoUrl = m3u8Match.value
                    println("✅ [SuperFlix] Vídeo encontrado diretamente: $videoUrl")
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "SuperFlix Stream",
                            url = videoUrl,
                            ExtractorLinkType.M3U8
                        ) {
                            this.referer = playerUrl
                            this.quality = Qualities.P1080.value
                        }
                    )
                } else {
                    println("❌ [SuperFlix] Nenhum vídeo encontrado")
                }
            }
        } catch (e: Exception) {
            println("⚠️ [SuperFlix] Erro ao extrair vídeo: ${e.message}")
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
        
        return null
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
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("episode") val episode: Int? = null,
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
