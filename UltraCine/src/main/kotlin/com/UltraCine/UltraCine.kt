package com.UltraCine

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.WebViewResolver
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
    override val chromecastSupport = true

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

    // User-Agent customizado para simular browser real
    private fun getUserAgent(): String = 
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

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

        val iframeUrl = document.selectFirst("iframe[src*='assistirseriesonline']")?.attr("src")
            ?.takeIf { it.isNotBlank() } ?: document.selectFirst("iframe[src*='assistirseriesonline']")?.attr("data-src")

        val isSerie = url.contains("/serie/")

        return if (isSerie) {
            val episodes = if (iframeUrl != null) {
                try {
                    val iframeDoc = app.get(iframeUrl).document
                    parseSeriesEpisodes(iframeDoc)
                } catch (e: Exception) {
                    emptyList()
                }
            } else emptyList()

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

    // FUNÇÃO SIMPLIFICADA PARA EXTRAIR EPISÓDIOS
    private fun parseSeriesEpisodes(doc: org.jsoup.nodes.Document): List<Episode> {
        val episodes = mutableListOf<Episode>()

        println("=== ANALISANDO EPISÓDIOS ===")

        // Procura por links de episódios
        doc.select("a[href*='/episodio/']").forEach { link ->
            val href = link.attr("href")
            val title = link.text().trim()
            
            if (title.isNotBlank() && href.isNotBlank()) {
                println("🎬 Encontrado: $title -> $href")
                
                // Tenta extrair temporada e episódio do título
                var season = 1
                var episode = 1
                
                val seasonMatch = Regex("""T(\d+)""", RegexOption.IGNORE_CASE).find(title)
                val episodeMatch = Regex("""E(\d+)""", RegexOption.IGNORE_CASE).find(title)
                
                season = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                episode = episodeMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                
                episodes.add(
                    Episode(
                        data = href,
                        name = title,
                        season = season,
                        episode = episode,
                        posterUrl = null
                    )
                )
            }
        }

        println("\n✅ Total de episódios encontrados: ${episodes.size}")
        return episodes
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

    // SOLUÇÃO DEFINITIVA PARA 2025 - JW Player + Anúncios Interativos
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
            // Constrói a URL final
            val finalUrl = when {
                data.startsWith("https://") || data.startsWith("http://") -> data
                data.matches(Regex("\\d+")) -> "https://assistirseriesonline.icu/episodio/$data"
                else -> "https://assistirseriesonline.icu/$data"
            }
            
            println("🔗 URL final: $finalUrl")
            
            // ESTRATÉGIA 1: Extração manual rápida (sem WebView)
            if (tryManualExtraction(finalUrl, subtitleCallback, callback)) {
                println("✅ Extração manual bem-sucedida!")
                return true
            }
            
            // ESTRATÉGIA 2: WebViewResolver (para sites com JW Player + ads)
            println("🔄 Usando WebViewResolver para lidar com JW Player e anúncios...")
            return useWebViewResolver(finalUrl, callback)
            
        } catch (e: Exception) {
            println("💥 ERRO CRÍTICO no loadLinks: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    // Tenta extração manual primeiro (mais rápido)
    private suspend fun tryManualExtraction(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val headers = mapOf(
                "User-Agent" to getUserAgent(),
                "Referer" to mainUrl,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8"
            )
            
            val res = app.get(url, headers = headers, timeout = 30)
            val doc = res.document
            
            // Procura JW Player específico
            val jwPlayer = doc.selectFirst("div.jwplayer, div.jw-wrapper, [class*='jw-']")
            if (jwPlayer != null) {
                println("🎯 JW Player detectado na página!")
                
                // Tenta extrair do JW Player via atributos data
                val possibleSources = listOf(
                    jwPlayer.attr("data-src"),
                    jwPlayer.attr("data-file"),
                    jwPlayer.attr("data-video-src"),
                    jwPlayer.selectFirst("video")?.attr("src"),
                    jwPlayer.selectFirst("source")?.attr("src"),
                    jwPlayer.selectFirst("iframe")?.attr("src")
                )
                
                for (source in possibleSources) {
                    if (!source.isNullOrBlank() && 
                        (source.contains(".m3u8") || source.contains(".mp4") || source.contains("googlevideo"))) {
                        println("🎬 Vídeo encontrado no JW Player: $source")
                        
                        if (loadExtractor(fixUrl(source), url, subtitleCallback, callback)) {
                            return true
                        }
                    }
                }
            }
            
            // Procura botões de play/skip
            doc.select("button.skip-button, .skip-ad, .jw-skip, [class*='skip']").forEach { btn ->
                val skipUrl = btn.attr("data-src") ?: btn.attr("data-url") ?: btn.attr("href")
                if (!skipUrl.isNullOrBlank()) {
                    println("⏭️ Botão skip encontrado: $skipUrl")
                    
                    if (loadExtractor(fixUrl(skipUrl), url, subtitleCallback, callback)) {
                        return true
                    }
                }
            }
            
            // Procura iframes de vídeo
            doc.select("iframe[src*='player'], iframe[src*='video']").forEach { iframe ->
                val src = iframe.attr("src")
                if (!src.isNullOrBlank()) {
                    println("🖼️ Iframe de vídeo: $src")
                    
                    if (loadExtractor(fixUrl(src), url, subtitleCallback, callback)) {
                        return true
                    }
                }
            }
            
        } catch (e: Exception) {
            println("❌ Extração manual falhou: ${e.message}")
        }
        
        return false
    }
    
    // Usa WebViewResolver para sites complexos com JavaScript
    private suspend fun useWebViewResolver(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            println("🌐 Iniciando WebViewResolver...")
            
            // Configurações do WebView
            val webViewResult = WebViewResolver(
                url = url,
                timeout = 45000, // 45 segundos para carregar e interagir
                customHeaders = mapOf(
                    "User-Agent" to getUserAgent(),
                    "Referer" to mainUrl,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                    "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8"
                ),
                // Scripts JavaScript para interagir com a página
                jsCode = """
                    // Aguarda a página carregar
                    setTimeout(function() {
                        console.log('🔍 Procurando JW Player e anúncios...');
                        
                        // 1. Tenta encontrar e pular anúncios
                        var skipButtons = document.querySelectorAll('button.skip-button, .skip-ad, .jw-skip, [class*="skip"]');
                        skipButtons.forEach(function(btn) {
                            console.log('🎯 Clicando em botão skip:', btn);
                            btn.click();
                        });
                        
                        // 2. Tenta encontrar e clicar no play do JW Player
                        var playButtons = document.querySelectorAll('.jw-play, .jw-icon-playback, button[aria-label*="play"], .play-btn');
                        playButtons.forEach(function(btn) {
                            console.log('▶️ Clicando em play:', btn);
                            btn.click();
                        });
                        
                        // 3. Aguarda um pouco para vídeo carregar
                        setTimeout(function() {
                            // Coleta todos os links de vídeo possíveis
                            var videoLinks = [];
                            
                            // JW Player sources
                            var jwPlayers = document.querySelectorAll('[class*="jw-"]');
                            jwPlayers.forEach(function(player) {
                                var sources = [
                                    player.getAttribute('data-src'),
                                    player.getAttribute('data-file'),
                                    player.getAttribute('data-video-src'),
                                    player.querySelector('video')?.src,
                                    player.querySelector('source')?.src,
                                    player.querySelector('iframe')?.src
                                ];
                                videoLinks = videoLinks.concat(sources.filter(s => s));
                            });
                            
                            // Vídeos HTML5
                            var videos = document.querySelectorAll('video');
                            videos.forEach(function(video) {
                                videoLinks.push(video.src);
                                if (video.currentSrc) videoLinks.push(video.currentSrc);
                            });
                            
                            // Iframes
                            var iframes = document.querySelectorAll('iframe');
                            iframes.forEach(function(iframe) {
                                videoLinks.push(iframe.src);
                            });
                            
                            // Scripts com URLs de vídeo
                            var scripts = document.querySelectorAll('script');
                            scripts.forEach(function(script) {
                                var text = script.textContent || script.innerHTML;
                                var matches = text.match(/(https?:[^"'\s]+\.(?:m3u8|mp4|mkv)[^"'\s]*)/gi);
                                if (matches) {
                                    videoLinks = videoLinks.concat(matches);
                                }
                            });
                            
                            console.log('📹 Links encontrados:', videoLinks.filter(l => l).length);
                            
                            // Envia os links de volta
                            videoLinks.filter(function(link) {
                                return link && (link.includes('.m3u8') || 
                                               link.includes('.mp4') || 
                                               link.includes('.mkv') || 
                                               link.includes('googlevideo'));
                            }).forEach(function(link) {
                                console.log('📤 Enviando link:', link);
                                Android.sendLink(link);
                            });
                            
                        }, 5000); // Aguarda 5 segundos após interação
                        
                    }, 3000); // Aguarda 3 segundos inicial
                """.trimIndent()
            ).resolve()
            
            // Processa os links encontrados pelo WebView
            var foundLinks = false
            webViewResult.forEach { link ->
                if (link.isNotBlank() && 
                    (link.contains(".m3u8") || link.contains(".mp4") || 
                     link.contains("googlevideo") || link.contains("blob:"))) {
                    
                    println("🎬 WebView encontrou vídeo: $link")
                    
                    // Converte blob: URLs se necessário
                    val finalUrl = if (link.startsWith("blob:")) {
                        println("⚠️ Convertendo blob URL...")
                        link
                    } else {
                        fixUrl(link)
                    }
                    
                    // Cria o ExtractorLink
                    callback(
                        ExtractorLink(
                            source = name,
                            name = "${name} (Auto-Extracted)",
                            url = finalUrl,
                            referer = url,
                            quality = extractQualityFromUrl(finalUrl),
                            isM3u8 = finalUrl.contains(".m3u8"),
                            headers = mapOf(
                                "Referer" to url,
                                "User-Agent" to getUserAgent()
                            )
                        )
                    )
                    
                    foundLinks = true
                }
            }
            
            if (!foundLinks) {
                println("⚠️ WebView não encontrou links de vídeo válidos")
                // Tenta fallback para extractors padrão
                return loadExtractor(url, url, {}, 