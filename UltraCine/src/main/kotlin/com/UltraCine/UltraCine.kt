package com.UltraCine

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import kotlinx.coroutines.delay
import org.jsoup.nodes.Element

class UltraCine : MainAPI() {
    override var mainUrl = "https://ultracine.org"
    override var name = "UltraCine"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasQuickSearch = true
    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "$mainUrl/category/lancamentos/" to "Lançamentos",
        "$mainUrl/category/acao/" to "Ação",
        "$mainUrl/category/animacao/" to "Animação",
        "$mainUrl/category/comedia/" to "Comédia",
        "$mainUrl/category/crime/" to "Crime",
        "$mainUrl/category/documentario/" to "Documentário",
        "$mainUrl/category/drama/" to "Drama",
        "$mainUrl/category/familia/" to "Família",
        "$mainUrl/category/fantasia/" to "Fantasia",
        "$mainUrl/category/ficcao-cientifica/" to "Ficção Científica",
        "$mainUrl/category/guerra/" to "Guerra",
        "$mainUrl/category/kids/" to "Kids",
        "$mainUrl/category/misterio/" to "Misterio",
        "$mainUrl/category/romance/" to "Romance",
        "$mainUrl/category/terror/" to "Terror",
        "$mainUrl/category/thriller/" to "Thriller"
    )

    private fun getUserAgent(): String = 
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + if (page > 1) "page/$page/" else ""
        val document = app.get(url).document
        val home = document.select("div.aa-cn div#movies-a ul.post-lst li").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("header.entry-header h2.entry-title")?.text() ?: return null
        val href = selectFirst("a.lnk-blk")?.attr("href") ?: return null

        val posterUrl = selectFirst("div.post-thumbnail figure img")?.attr("src")
            ?.takeIf { it.isNotBlank() } 
            ?.let { fixUrl(it).replace("/w500/", "/original/") }
            ?: selectFirst("div.post-thumbnail figure img")?.attr("data-src")
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrl(it).replace("/w500/", "/original/") }

        val year = selectFirst("span.year")?.text()?.toIntOrNull()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.year = year
            this.quality = getQualityFromString(selectFirst("span.post-ql")?.text())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.aa-cn div#movies-a ul.post-lst li").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("aside.fg1 header.entry-header h1.entry-title")?.text() ?: return null

        val poster = document.selectFirst("div.bghd img.TPostBg")?.attr("src")
            ?.takeIf { it.isNotBlank() } 
            ?.let { fixUrl(it).replace("/w1280/", "/original/") }
            ?: document.selectFirst("div.bghd img.TPostBg")?.attr("data-src")
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrl(it).replace("/w1280/", "/original/") }

        val yearText = document.selectFirst("aside.fg1 header.entry-header div.entry-meta span.year")?.ownText()
        val year = yearText?.toIntOrNull()
        val durationText = document.selectFirst("aside.fg1 header.entry-header div.entry-meta span.duration")?.ownText()
        val plot = document.selectFirst("aside.fg1 div.description p")?.text()
        val tags = document.select("aside.fg1 header.entry-header div.entry-meta span.genres a").map { it.text() }
        val actors = document.select("aside.fg1 ul.cast-lst p a").map {
            Actor(it.text(), it.attr("href"))
        }
        val trailer = document.selectFirst("div.mdl-cn div.video iframe")?.attr("src")
            ?.takeIf { it.isNotBlank() } ?: document.selectFirst("div.mdl-cn div.video iframe")?.attr("data-src")

        val isSerie = url.contains("/serie/")

        return if (isSerie) {
            // Extrair informações específicas para séries
            val episodes = parseSeriesEpisodes(document)
            
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = null
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            // Filmes - buscar iframe do player
            val iframeUrl = document.selectFirst("iframe[src*='assistirseriesonline']")?.attr("src")
                ?.takeIf { it.isNotBlank() } ?: document.selectFirst("iframe[src*='assistirseriesonline']")?.attr("data-src")

            newMovieLoadResponse(title, url, TvType.Movie, iframeUrl ?: "") {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.duration = parseDuration(durationText)
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    private fun parseSeriesEpisodes(doc: org.jsoup.nodes.Document): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        println("=== ANALISANDO EPISÓDIOS DA SÉRIE ===")
        
        // ESTRATÉGIA 1: Procurar elementos com data-episode-id
        doc.select("[data-episode-id]").forEach { element ->
            val episodeId = element.attr("data-episode-id")
            val title = element.selectFirst("span.episode-title")?.text() 
                ?: element.text().trim()
            
            if (episodeId.isNotBlank() && title.isNotBlank()) {
                println("🎬 Encontrado (data-episode-id): $title -> ID: $episodeId")
                
                val (season, episode) = extractSeasonEpisode(title)
                
                episodes.add(
                    newEpisode(episodeId) {
                        this.name = title
                        this.season = season
                        this.episode = episode
                    }
                )
            }
        }
        
        // ESTRATÉGIA 2: Procurar em divs de episódios
        doc.select("div.episode-item, li.episode-item").forEach { item ->
            val onclick = item.attr("onclick")
            val dataId = item.attr("data-id")
            val title = item.selectFirst(".episode-title, .title")?.text() 
                ?: item.text().trim()
            
            val episodeId = when {
                dataId.isNotBlank() -> dataId
                onclick.contains("loadEpisode") -> {
                    val match = Regex("""loadEpisode\s*\(\s*['"]?(\d+)['"]?\s*\)""").find(onclick)
                    match?.groupValues?.get(1)
                }
                onclick.contains("playEpisode") -> {
                    val match = Regex("""playEpisode\s*\(\s*['"]?(\d+)['"]?\s*\)""").find(onclick)
                    match?.groupValues?.get(1)
                }
                else -> null
            }
            
            if (episodeId != null && title.isNotBlank()) {
                println("🎬 Encontrado (onclick): $title -> ID: $episodeId")
                
                val (season, episode) = extractSeasonEpisode(title)
                
                episodes.add(
                    newEpisode(episodeId) {
                        this.name = title
                        this.season = season
                        this.episode = episode
                    }
                )
            }
        }
        
        // ESTRATÉGIA 3: Procurar por links de episódios
        doc.select("a[href*='/episodio/']").forEach { link ->
            val href = link.attr("href")
            val title = link.text().trim()
            
            if (title.isNotBlank() && href.isNotBlank()) {
                // Extrair ID do episódio da URL
                val episodeId = Regex("""/episodio/(\d+)/?""").find(href)?.groupValues?.get(1)
                    ?: Regex("""e=(\d+)""").find(href)?.groupValues?.get(1)
                
                if (episodeId != null) {
                    println("🎬 Encontrado (link): $title -> ID: $episodeId")
                    
                    val (season, episode) = extractSeasonEpisode(title)
                    
                    episodes.add(
                        newEpisode(episodeId) {
                            this.name = title
                            this.season = season
                            this.episode = episode
                        }
                    )
                }
            }
        }
        
        println("\n✅ Total de episódios encontrados: ${episodes.size}")
        return episodes.distinctBy { it.episode }
    }

    private fun extractSeasonEpisode(text: String): Pair<Int, Int> {
        var season = 1
        var episode = 1
        
        // Tentar padrões como "T1 E1", "S01E01", "1x01", etc.
        val patterns = listOf(
            Regex("""T(\d+)\s*E(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""S(\d+)\s*E(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""(\d+)x(\d+)"""),
            Regex("""Episódio\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""E(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""(\d+)\s*-\s*(\d+)""")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                season = match.groupValues.getOrNull(1)?.toIntOrNull() ?: 1
                episode = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 
                         match.groupValues.getOrNull(1)?.toIntOrNull() ?: 1
                break
            }
        }
        
        return Pair(season, episode)
    }

    private fun parseDuration(duration: String?): Int? {
        if (duration.isNullOrBlank()) return null
        val regex = Regex("""(\d+)h.*?(\d+)m""")
        val match = regex.find(duration)
        return if (match != null) {
            val h = match.groupValues[1].toIntOrNull() ?: 0
            val m = match.groupValues[2].toIntOrNull() ?: 0
            h * 60 + m
        } else {
            Regex("""(\d+)m""").find(duration)?.groupValues?.get(1)?.toIntOrNull()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🎬 ULTRA CINE loadLinks CHAMADO!")
        println("📦 Data recebido: $data")
        
        if (data.isBlank()) return false

        return try {
            // PARA SÉRIES: data contém o ID do episódio
            if (data.matches(Regex("\\d+"))) {
                return loadEpisodeLinks(data, subtitleCallback, callback)
            }
            
            // PARA FILMES: data contém URL do iframe
            return loadMovieLinks(data, subtitleCallback, callback)
            
        } catch (e: Exception) {
            println("💥 ERRO em loadLinks: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    private suspend fun loadMovieLinks(
        iframeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🎬 Carregando links de FILME...")
        println("🔗 Iframe URL: $iframeUrl")
        
        val headers = mapOf(
            "User-Agent" to getUserAgent(),
            "Referer" to mainUrl,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8"
        )
        
        return try {
            // PRIMEIRO: Tentar acessar o iframe
            val doc = app.get(fixUrl(iframeUrl), headers = headers, timeout = 30).document
            
            // ESTRATÉGIA 1: Procurar scripts com player config
            val scripts = doc.select("script:not([src])")
            for (script in scripts) {
                val scriptText = script.html()
                
                // Procurar por iframes dentro de scripts
                val iframePattern = Regex("""src\s*[:=]\s*['"](https?://[^"']+)['"]""")
                val iframeMatches = iframePattern.findAll(scriptText).toList()
                
                for (match in iframeMatches) {
                    val src = match.groupValues[1]
                    if (src.contains("player") || src.contains("embed") || src.contains("video")) {
                        println("🎯 Iframe em script: $src")
                        
                        if (loadExtractor(fixUrl(src), iframeUrl, subtitleCallback, callback)) {
                            return true
                        }
                    }
                }
                
                // Procurar por URLs de vídeo
                val videoPatterns = listOf(
                    Regex("""['"]file['"]\s*:\s*['"](https?://[^"']+)['"]"""),
                    Regex("""['"]url['"]\s*:\s*['"](https?://[^"']+)['"]"""),
                    Regex("""['"]src['"]\s*:\s*['"](https?://[^"']+)['"]"""),
                    Regex("""videoUrl\s*[:=]\s*['"](https?://[^"']+)['"]""")
                )
                
                for (pattern in videoPatterns) {
                    val matches = pattern.findAll(scriptText).toList()
                    for (match in matches) {
                        val videoUrl = match.groupValues[1]
                        if (videoUrl.contains(".m3u8") || videoUrl.contains(".mp4") || 
                            videoUrl.contains("googlevideo")) {
                            println("🎬 URL de vídeo em script: $videoUrl")
                            
                            callback(
                                ExtractorLink(
                                    name,
                                    name,
                                    videoUrl,
                                    referer = iframeUrl,
                                    quality = Qualities.Unknown.value,
                                    isM3u8 = videoUrl.contains(".m3u8")
                                )
                            )
                            return true
                        }
                    }
                }
            }
            
            // ESTRATÉGIA 2: Procurar iframes aninhados
            val nestedIframes = doc.select("iframe[src]")
            for (iframe in nestedIframes) {
                val src = iframe.attr("src")
                if (src.isNotBlank()) {
                    println("🖼️ Iframe aninhado: $src")
                    
                    if (loadExtractor(fixUrl(src), iframeUrl, subtitleCallback, callback)) {
                        return true
                    }
                }
            }
            
            // ESTRATÉGIA 3: Tentar com WebView (último recurso)
            println("🔄 Tentando com WebView...")
            val webViewResult = loadWebViewLinks(iframeUrl, iframeUrl, subtitleCallback, callback)
            if (webViewResult) {
                println("✅ WebView funcionou!")
                return true
            }
            
            false
        } catch (e: Exception) {
            println("💥 ERRO em loadMovieLinks: ${e.message}")
            false
        }
    }

    private suspend fun loadEpisodeLinks(
        episodeId: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🎬 Carregando links de EPISÓDIO...")
        println("🆔 Episode ID: $episodeId")
        
        // URLs de API que o site pode usar
        val apiUrls = listOf(
            "https://assistirseriesonline.icu/wp-json/player/video/$episodeId",
            "https://assistirseriesonline.icu/ajax/load_episode.php",
            "https://assistirseriesonline.icu/wp-admin/admin-ajax.php"
        )
        
        val headers = mapOf(
            "User-Agent" to getUserAgent(),
            "Referer" to mainUrl,
            "X-Requested-With" to "XMLHttpRequest",
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
        )
        
        // ESTRATÉGIA 1: Tentar API endpoints
        for (apiUrl in apiUrls) {
            try {
                println("🔄 Tentando API: $apiUrl")
                
                val payload = when {
                    apiUrl.contains("admin-ajax.php") -> mapOf(
                        "action" to "load_episode",
                        "episode_id" to episodeId
                    )
                    apiUrl.contains("load_episode.php") -> mapOf(
                        "id" to episodeId
                    )
                    else -> null
                }
                
                val response = if (payload != null) {
                    app.post(apiUrl, headers = headers, data = payload)
                } else {
                    app.get(apiUrl, headers = headers)
                }
                
                val responseText = response.text
                println("📄 Resposta da API: ${responseText.take(500)}...")
                
                // Tentar extrair URL do vídeo da resposta
                val videoPatterns = listOf(
                    Regex("""['"]url['"]\s*:\s*['"](https?://[^"']+)['"]"""),
                    Regex("""['"]src['"]\s*:\s*['"](https?://[^"']+)['"]"""),
                    Regex("""<iframe[^>]+src=['"](https?://[^"']+)['"]"""),
                    Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)"""),
                    Regex("""(https?://[^"'\s]+\.mp4[^"'\s]*)""")
                )
                
                for (pattern in videoPatterns) {
                    val matches = pattern.findAll(responseText).toList()
                    for (match in matches) {
                        val videoUrl = match.groupValues[1]
                        if (videoUrl.isNotBlank() && 
                            (videoUrl.contains(".m3u8") || videoUrl.contains(".mp4") || 
                             videoUrl.contains("player") || videoUrl.contains("embed"))) {
                            println("🎬 URL encontrada na API: $videoUrl")
                            
                            if (loadExtractor(fixUrl(videoUrl), mainUrl, subtitleCallback, callback)) {
                                return true
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                println("⚠️ API falhou: ${e.message}")
            }
        }
        
        // ESTRATÉGIA 2: Construir URL direta do episódio
        val episodeUrl = "https://assistirseriesonline.icu/episodio/$episodeId/"
        println("🔗 Tentando URL direta: $episodeUrl")
        
        try {
            val doc = app.get(episodeUrl, headers = mapOf(
                "User-Agent" to getUserAgent(),
                "Referer" to mainUrl
            )).document
            
            // Procurar player na página do episódio
            val playerIframe = doc.selectFirst("iframe[src*='player'], iframe[src*='embed']")
            val iframeSrc = playerIframe?.attr("src")
            
            if (iframeSrc != null) {
                println("🖼️ Iframe encontrado: $iframeSrc")
                return loadMovieLinks(iframeSrc, subtitleCallback, callback)
            }
            
            // Procurar scripts com dados do player
            val scripts = doc.select("script:not([src])")
            for (script in scripts) {
                val scriptText = script.html()
                if (scriptText.contains("jwplayer") || scriptText.contains("videojs")) {
                    println("🎬 Player JavaScript encontrado")
                    
                    val videoUrlPattern = Regex("""['"](https?://[^"']+\.(?:m3u8|mp4)[^"']*)['"]""")
                    val matches = videoUrlPattern.findAll(scriptText).toList()
                    
                    for (match in matches) {
                        val videoUrl = match.groupValues[1]
                        println("🎬 URL do player: $videoUrl")
                        
                        callback(
                            ExtractorLink(
                                name,
                                name,
                                videoUrl,
                                referer = episodeUrl,
                                quality = Qualities.Unknown.value,
                                isM3u8 = videoUrl.contains(".m3u8")
                            )
                        )
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            println("💥 ERRO ao acessar página do episódio: ${e.message}")
        }
        
        // ESTRATÉGIA 3: WebView como último recurso
        println("🔄 Último recurso: WebView")
        return loadWebViewLinks(episodeUrl, episodeUrl, subtitleCallback, callback)
    }

    private suspend fun loadWebViewLinks(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val webView = WebViewUtil()
            val result = webView.extractLinks(
                url = url,
                regex = listOf(
                    Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)"""),
                    Regex("""(https?://[^"'\s]+\.mp4[^"'\s]*)"""),
                    Regex("""['"]file['"]\s*:\s*['"](https?://[^"']+)['"]"""),
                    Regex("""<source[^>]+src=['"](https?://[^"']+)['"]""")
                ),
                timeout = 15000,
                headers = mapOf(
                    "User-Agent" to getUserAgent(),
                    "Referer" to referer
                )
            )
            
            if (result.isNotEmpty()) {
                println("✅ WebView encontrou ${result.size} links")
                result.forEach { videoUrl ->
                    if (videoUrl.contains(".m3u8") || videoUrl.contains(".mp4")) {
                        callback(
                            ExtractorLink(
                                name,
                                name,
                                videoUrl,
                                referer = referer,
                                quality = Qualities.Unknown.value,
                                isM3u8 = videoUrl.contains(".m3u8")
                            )
                        )
                    }
                }
                return true
            }
            false
        } catch (e: Exception) {
            println("💥 ERRO no WebView: ${e.message}")
            false
        }
    }
}