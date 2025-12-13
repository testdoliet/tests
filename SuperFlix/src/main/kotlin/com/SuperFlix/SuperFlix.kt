package com.SuperFlix

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.metaproviders.TmdbLink
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

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
    
    override var mainUrl = HOST
    
    // Página principal - aqui fazemos nossa própria busca no site
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

    // Buscar na página principal (nossa implementação)
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        println("🔍 [DEBUG] getMainPage chamado: page=$page, request=${request.name}, data=${request.data}")
        
        val url = request.data + if (page > 1) "?page=$page" else ""
        println("🔗 [DEBUG] Buscando URL: $url")
        
        val document = app.get(url).document
        println("📄 [DEBUG] Document carregado, selecionando elementos...")

        val home = document.select("a.card, div.recs-grid a.rec-card").mapNotNull { element ->
            try {
                println("🔍 [DEBUG] Processando elemento...")
                
                val title = element.attr("title") ?: element.selectFirst("img")?.attr("alt") ?: run {
                    println("⚠️ [DEBUG] Elemento sem título, pulando")
                    return@mapNotNull null
                }
                println("📝 [DEBUG] Título encontrado: $title")
                
                val href = element.attr("href") ?: run {
                    println("⚠️ [DEBUG] Elemento sem href, pulando")
                    return@mapNotNull null
                }
                println("🔗 [DEBUG] Href encontrado: $href")

                val poster = element.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                println("🖼️ [DEBUG] Poster: $poster")
                
                val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
                val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
                println("📅 [DEBUG] Ano: $year, Título limpo: $cleanTitle")

                // Determinar tipo
                val badge = element.selectFirst(".badge-kind")?.text()?.lowercase() ?: ""
                val isAnime = badge.contains("anime") || href.contains("/anime/")
                val isSerie = badge.contains("série") || badge.contains("serie") || href.contains("/serie/") || href.contains("/tv/")
                println("🎬 [DEBUG] Badge: '$badge', isAnime: $isAnime, isSerie: $isSerie")

                // Para o TMDB Provider funcionar, nós NÃO precisamos do ID aqui
                // O TMDB Provider vai buscar automaticamente quando o usuário clicar
                // usando o título que passamos
                val response = when {
                    isAnime -> {
                        println("🎌 [DEBUG] Criando Anime Search Response")
                        newAnimeSearchResponse(cleanTitle, href, TvType.Anime) {
                            this.posterUrl = poster
                            this.year = year
                        }
                    }
                    isSerie -> {
                        println("📺 [DEBUG] Criando TV Series Search Response")
                        newTvSeriesSearchResponse(cleanTitle, href, TvType.TvSeries) {
                            this.posterUrl = poster
                            this.year = year
                        }
                    }
                    else -> {
                        println("🎥 [DEBUG] Criando Movie Search Response")
                        newMovieSearchResponse(cleanTitle, href, TvType.Movie) {
                            this.posterUrl = poster
                            this.year = year
                        }
                    }
                }
                
                println("✅ [DEBUG] SearchResponse criado: ${response.name}, URL: ${response.url}")
                response
            } catch (e: Exception) {
                println("❌ [DEBUG] Erro ao processar elemento: ${e.message}")
                e.printStackTrace()
                null
            }
        }

        println("🏠 [DEBUG] Total de itens na home: ${home.size}")
        return newHomePageResponse(request.name, home.distinctBy { it.url })
    }

    // Quick Search - nossa implementação
    override suspend fun search(query: String): List<SearchResponse> {
        println("🔍 [DEBUG] search chamado: query='$query'")
        
        val searchUrl = "$HOST/buscar?q=${URLEncoder.encode(query, "UTF-8")}"
        println("🔗 [DEBUG] Buscando URL: $searchUrl")
        
        val document = app.get(searchUrl).document
        println("📄 [DEBUG] Document carregado")

        return document.select(".grid .card, a.card").mapNotNull { card ->
            try {
                println("🔍 [DEBUG] Processando card de busca...")
                
                val title = card.attr("title") ?: card.selectFirst("img")?.attr("alt") ?: run {
                    println("⚠️ [DEBUG] Card sem título, pulando")
                    return@mapNotNull null
                }
                println("📝 [DEBUG] Título encontrado: $title")
                
                val href = card.attr("href") ?: run {
                    println("⚠️ [DEBUG] Card sem href, pulando")
                    return@mapNotNull null
                }
                println("🔗 [DEBUG] Href encontrado: $href")

                val poster = card.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                println("🖼️ [DEBUG] Poster: $poster")
                
                val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
                val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
                println("📅 [DEBUG] Ano: $year, Título limpo: $cleanTitle")

                val badge = card.selectFirst(".badge-kind")?.text()?.lowercase() ?: ""
                val isAnime = badge.contains("anime") || href.contains("/anime/")
                val isSerie = badge.contains("série") || badge.contains("serie") || href.contains("/serie/") || href.contains("/tv/")
                println("🎬 [DEBUG] Badge: '$badge', isAnime: $isAnime, isSerie: $isSerie")

                val response = when {
                    isAnime -> {
                        println("🎌 [DEBUG] Criando Anime Search Response para busca")
                        newAnimeSearchResponse(cleanTitle, href, TvType.Anime) {
                            this.posterUrl = poster
                            this.year = year
                        }
                    }
                    isSerie -> {
                        println("📺 [DEBUG] Criando TV Series Search Response para busca")
                        newTvSeriesSearchResponse(cleanTitle, href, TvType.TvSeries) {
                            this.posterUrl = poster
                            this.year = year
                        }
                    }
                    else -> {
                        println("🎥 [DEBUG] Criando Movie Search Response para busca")
                        newMovieSearchResponse(cleanTitle, href, TvType.Movie) {
                            this.posterUrl = poster
                            this.year = year
                        }
                    }
                }
                
                println("✅ [DEBUG] SearchResponse para busca criado: ${response.name}")
                response
            } catch (e: Exception) {
                println("❌ [DEBUG] Erro ao processar card de busca: ${e.message}")
                null
            }
        }
    }

    // AQUI ESTÁ A MAGIA: TMDB cuida dos metadados, nós buscamos o vídeo
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🎬 [DEBUG] loadLinks chamado!")
        println("📦 [DEBUG] Data recebida: ${data.take(100)}...")
        
        return try {
            val mediaData = AppUtils.parseJson<TmdbLink>(data).toLinkData()
            
            println("🎬 [DEBUG] ============ LOAD LINKS ============")
            println("🎬 [DEBUG] Título: ${mediaData.title ?: "Unknown"}")
            println("🎬 [DEBUG] TMDB ID: ${mediaData.tmdbId}")
            println("🎬 [DEBUG] IMDb ID: ${mediaData.imdbId}")
            println("🎬 [DEBUG] Temporada: ${mediaData.season}")
            println("🎬 [DEBUG] Episódio: ${mediaData.episode}")
            println("🎬 [DEBUG] Tipo: ${if (mediaData.season != null) "Série" else "Filme"}")
            
            // 1. Busca no site SuperFlix pelo título
            val searchQuery = mediaData.title ?: run {
                println("❌ [DEBUG] Título vazio, retornando false")
                return false
            }
            
            val searchUrl = "$HOST/buscar?q=${URLEncoder.encode(searchQuery, "UTF-8")}"
            println("🔍 [DEBUG] Buscando no SuperFlix: $searchUrl")
            
            val document = app.get(searchUrl).document
            println("📄 [DEBUG] Página de busca carregada")
            
            // 2. Encontra primeiro resultado
            val firstResult = document.selectFirst(".grid .card, a.card, .movie-item a, .rec-card")
            if (firstResult == null) {
                println("❌ [DEBUG] Nenhum resultado encontrado no SuperFlix")
                println("🔍 [DEBUG] HTML da página de busca:")
                println(document.html().take(500))
                return false
            }
            
            println("✅ [DEBUG] Primeiro resultado encontrado")
            val detailUrl = firstResult.attr("href")?.let { fixUrl(it) }
            if (detailUrl == null) {
                println("❌ [DEBUG] URL de detalhes vazia")
                return false
            }
            
            println("🔗 [DEBUG] Página de detalhes: $detailUrl")
            
            // 3. Carrega página de detalhes
            val detailDoc = app.get(detailUrl).document
            println("📄 [DEBUG] Página de detalhes carregada")
            
            // 4. Para séries, encontrar episódio específico
            val finalPlayerUrl = if (mediaData.season != null && mediaData.episode != null) {
                println("📺 [DEBUG] Buscando episódio S${mediaData.season}E${mediaData.episode}")
                findEpisodeUrl(detailDoc, mediaData.season, mediaData.episode)
            } else {
                println("🎥 [DEBUG] Buscando filme ou primeiro episódio")
                findPlayerUrl(detailDoc)
            }
            
            if (finalPlayerUrl == null) {
                println("❌ [DEBUG] Player não encontrado")
                println("🔍 [DEBUG] HTML da página de detalhes:")
                println(detailDoc.html().take(500))
                return false
            }
            
            println("🎥 [DEBUG] Player URL encontrado: $finalPlayerUrl")
            
            // 5. Extrai links de vídeo
            println("🔗 [DEBUG] Extraindo links de vídeo...")
            extractVideoLinks(finalPlayerUrl, callback)
            true
        } catch (e: Exception) {
            println("💥 [DEBUG] ERRO em loadLinks: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    private fun findEpisodeUrl(document: org.jsoup.nodes.Document, season: Int, episode: Int): String? {
        println("🔍 [DEBUG] Procurando episódio S${season}E${episode}")
        
        // Procura pelo episódio específico
        val episodeElements = document.select("button.bd-play[data-url], .episode-item, .episode-link, [data-season], [data-ep]")
        println("🔍 [DEBUG] Elementos de episódio encontrados: ${episodeElements.size}")
        
        for ((index, element) in episodeElements.withIndex()) {
            val epSeason = element.attr("data-season").toIntOrNull() ?: 1
            val epNumber = element.attr("data-ep").toIntOrNull() ?: 
                          Regex("Ep\\.?\\s*(\\d+)").find(element.text())?.groupValues?.get(1)?.toIntOrNull() ?:
                          Regex("(\\d+)").find(element.text())?.groupValues?.get(1)?.toIntOrNull()
            
            println("🔍 [DEBUG] Elemento $index: data-season='${element.attr("data-season")}', data-ep='${element.attr("data-ep")}', texto='${element.text().take(50)}...'")
            println("🔍 [DEBUG] Elemento $index: epSeason=$epSeason, epNumber=$epNumber")
            
            if (epSeason == season && epNumber == episode) {
                val url = element.attr("data-url") ?: element.attr("href")
                if (url != null) {
                    println("✅ [DEBUG] Episódio S${season}E${episode} encontrado: $url")
                    return fixUrl(url)
                }
            }
        }
        
        // Se não encontrar específico, pega o primeiro player
        println("⚠️ [DEBUG] Episódio específico não encontrado, usando primeiro disponível")
        return findPlayerUrl(document)
    }
    
    private fun findPlayerUrl(document: org.jsoup.nodes.Document): String? {
        println("🔍 [DEBUG] Procurando player URL")
        
        // Método 1: Botão com data-url
        val playButton = document.selectFirst("button.bd-play[data-url], button[data-url*='watch'], .play-btn[data-url]")
        if (playButton != null) {
            val url = playButton.attr("data-url")
            println("🔘 [DEBUG] Player encontrado no botão: $url")
            return fixUrl(url)
        }
        
        // Método 2: Iframe
        val iframe = document.selectFirst("iframe[src*='player'], iframe[src*='embed'], iframe[src*='watch']")
        if (iframe != null) {
            val url = iframe.attr("src")
            println("📺 [DEBUG] Player encontrado no iframe: $url")
            return fixUrl(url)
        }
        
        // Método 3: Link direto
        val videoLink = document.selectFirst("a[href*='.m3u8'], a[href*='assistir'], a[href*='watch']")
        if (videoLink != null) {
            val url = videoLink.attr("href")
            println("🔗 [DEBUG] Player encontrado no link: $url")
            return fixUrl(url)
        }
        
        // Método 4: Procurar em scripts
        println("🔍 [DEBUG] Procurando em scripts...")
        val scripts = document.select("script")
        for ((index, script) in scripts.withIndex()) {
            val scriptText = script.html() + script.data()
            if (scriptText.contains("m3u8") || scriptText.contains("mp4") || scriptText.contains("video")) {
                println("📜 [DEBUG] Script $index contém referência de vídeo")
                val m3u8Match = Regex("(https?:[^\"']+\\.m3u8[^\"' ]*)").find(scriptText)
                if (m3u8Match != null) {
                    val url = m3u8Match.value
                    println("✅ [DEBUG] URL encontrada no script: $url")
                    return fixUrl(url)
                }
            }
        }
        
        println("❌ [DEBUG] Nenhum player URL encontrado")
        return null
    }
    
    private suspend fun extractVideoLinks(playerUrl: String, callback: (ExtractorLink) -> Unit) {
        println("🔗 [DEBUG] extractVideoLinks chamado: $playerUrl")
        
        try {
            // Método 1: Tentar como Tamilian (FirePlayer)
            println("🔓 [DEBUG] Tentando método FirePlayer...")
            val playerDoc = app.get(playerUrl).document
            val script = playerDoc.selectFirst("script:containsData(function(p,a,c,k,e,d))")
                ?.data()?.let { 
                    println("📜 [DEBUG] Script packed encontrado, desempacotando...")
                    getAndUnpack(it) 
                }
            
            if (script != null) {
                println("🔓 [DEBUG] Script FirePlayer desempacotado: ${script.take(100)}...")
                val token = script.substringAfter("FirePlayer(\"").substringBefore("\",")
                println("🔑 [DEBUG] Token extraído: $token")
                
                val videoApiUrl = "$HOST/player/index.php?data=$token&do=getVideo"
                println("🔄 [DEBUG] Chamando API: $videoApiUrl")
                
                val videoData = app.post(videoApiUrl, headers = mapOf("X-Requested-With" to "XMLHttpRequest"))
                    .parsedSafe<VideoData>()
                
                videoData?.videoSource?.let { videoUrl ->
                    println("✅ [DEBUG] Vídeo encontrado via API: $videoUrl")
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
                println("⚠️ [DEBUG] Script FirePlayer não encontrado, tentando métodos alternativos...")
                
                // Método 2: Tentar extrair m3u8 diretamente
                println("🔍 [DEBUG] Procurando m3u8 em scripts...")
                val scripts = playerDoc.select("script")
                for ((index, scriptElement) in scripts.withIndex()) {
                    val scriptText = scriptElement.html() + scriptElement.data()
                    if (scriptText.contains("m3u8") || scriptText.contains("http")) {
                        println("📜 [DEBUG] Analisando script $index (${scriptText.length} chars)...")
                        val m3u8Match = Regex("(https?:[^\"']+\\.m3u8[^\"' ]*)").find(scriptText)
                        if (m3u8Match != null) {
                            val videoUrl = m3u8Match.value
                            println("✅ [DEBUG] Vídeo encontrado no script $index: $videoUrl")
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
                            return
                        }
                    }
                }
                
                // Método 3: Procurar em iframes
                println("🔍 [DEBUG] Procurando iframes...")
                val iframe = playerDoc.selectFirst("iframe")
                if (iframe != null) {
                    val iframeSrc = iframe.attr("src")
                    println("📺 [DEBUG] Iframe encontrado: $iframeSrc")
                    if (iframeSrc.isNotBlank()) {
                        println("🔄 [DEBUG] Recursão: extraindo do iframe...")
                        extractVideoLinks(fixUrl(iframeSrc), callback)
                        return
                    }
                }
                
                println("❌ [DEBUG] Nenhum vídeo encontrado")
            }
        } catch (e: Exception) {
            println("⚠️ [DEBUG] Erro ao extrair vídeo: ${e.message}")
            e.printStackTrace()
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
