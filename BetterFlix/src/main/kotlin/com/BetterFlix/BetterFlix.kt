package com.BetterFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element

class BetterFlix : MainAPI() {
    override var mainUrl = "https://betterflix.vercel.app"
    override var name = "BetterFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Live)
    override val usesWebView = false

    // Agora temos apenas uma página principal com todas as seções
    override val mainPage = mainPageOf(
        "$mainUrl/" to "BetterFlix - Tudo"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homeLists = mutableListOf<HomePageList>()

        // 1. Tops da semana
        val tops = extractSection(document, "Tops da semana")
        if (tops.isNotEmpty()) {
            homeLists.add(HomePageList("Tops da semana", tops))
        }

        // 2. Filmes do momento  
        val filmes = extractSection(document, "Filmes do momento")
        if (filmes.isNotEmpty()) {
            homeLists.add(HomePageList("Filmes do momento", filmes))
        }

        // 3. Séries do momento
        val series = extractSection(document, "Séries do momento")
        if (series.isNotEmpty()) {
            homeLists.add(HomePageList("Séries do momento", series))
        }

        // 4. Canais de TV Ao Vivo
        val canais = extractTVChannels(document)
        if (canais.isNotEmpty()) {
            homeLists.add(HomePageList("Canais de TV Ao Vivo", canais))
        }

        return HomePageResponse(homeLists)
    }

    private fun extractSection(doc: org.jsoup.nodes.Document, sectionTitle: String): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        
        // Encontrar todas as seções com título
        doc.select("div.py-8.w-full.mx-auto").forEach { section ->
            val h2 = section.selectFirst("h2")?.text()?.trim()
            if (h2 == sectionTitle) {
                // Extrair links dentro desta seção
                section.select("a[href*='?id=']").forEach { element ->
                    try {
                        val href = element.attr("href") ?: return@forEach
                        if (href.startsWith("/canal")) return@forEach

                        val imgElement = element.selectFirst("img")
                        val title = imgElement?.attr("alt") ?: 
                                   element.selectFirst("p")?.text() ?:
                                   return@forEach

                        val poster = imgElement?.attr("src")?.let { fixUrl(it) }
                        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
                        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()

                        // Determinar tipo pelo URL
                        val isSeries = href.contains("type=tv") || sectionTitle.contains("Séries")
                        val isMovie = href.contains("type=movie") || sectionTitle.contains("Filmes")
                        val isAnime = title.contains("(Anime)", ignoreCase = true)

                        when {
                            isAnime -> {
                                newAnimeSearchResponse(cleanTitle, fixUrl(href), TvType.Anime) {
                                    this.posterUrl = poster
                                    this.year = year
                                }.also { items.add(it) }
                            }
                            isSeries -> {
                                newTvSeriesSearchResponse(cleanTitle, fixUrl(href), TvType.TvSeries) {
                                    this.posterUrl = poster
                                    this.year = year
                                }.also { items.add(it) }
                            }
                            else -> {
                                newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                                    this.posterUrl = poster
                                    this.year = year
                                }.also { items.add(it) }
                            }
                        }
                    } catch (e: Exception) {
                        // Ignorar erro
                    }
                }
            }
        }
        
        return items.distinctBy { it.url }
    }

    private fun extractTVChannels(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val channels = mutableListOf<SearchResponse>()

        // Extrair canais da grid
        document.select("a.canal-card, a[href^='/canal']").forEach { element ->
            try {
                val href = element.attr("href") ?: return@forEach
                if (!href.contains("canal")) return@forEach

                val nameElement = element.selectFirst("h3")
                val title = nameElement?.text() ?: return@forEach

                val imgElement = element.selectFirst("img")
                val poster = imgElement?.attr("src")?.let { fixUrl(it) }

                newTvSeriesSearchResponse(title, fixUrl(href), TvType.Live) {
                    this.posterUrl = poster
                }.also { channels.add(it) }
            } catch (e: Exception) {
                // Ignorar erro
            }
        }

        return channels.distinctBy { it.url }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?q=${query.encodeSearchQuery()}"
        val document = app.get(searchUrl).document

        return document.select("a[href*='?id=']").mapNotNull { element ->
            try {
                val href = element.attr("href") ?: return@mapNotNull null
                if (href.startsWith("/canal")) return@mapNotNull null

                val imgElement = element.selectFirst("img")
                val title = imgElement?.attr("alt") ?: 
                           element.selectFirst(".text-white")?.text() ?:
                           return@mapNotNull null

                val poster = imgElement?.attr("src")?.let { fixUrl(it) }
                val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
                val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()

                // Determinar tipo
                val isSeries = href.contains("type=tv")
                val isMovie = href.contains("type=movie")
                val isAnime = title.contains("(Anime)", ignoreCase = true)

                when {
                    isAnime -> newAnimeSearchResponse(cleanTitle, fixUrl(href), TvType.Anime) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    isSeries -> newTvSeriesSearchResponse(cleanTitle, fixUrl(href), TvType.TvSeries) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    isMovie -> newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        try {
            // Primeiro testar se a URL ainda é válida
            val response = app.get(url, timeout = 10_000)
            if (response.code >= 400) {
                // URL pode ter mudado, tentar formatos alternativos
                return tryAlternativeLoad(url)
            }
            
            val document = response.document

            // Extrair título
            val titleElement = document.selectFirst("h1, .title, header h1")
            val title = titleElement?.text() ?: return null
            
            // Extrair informações básicas
            val year = extractYear(document)
            val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
            
            // Determinar tipo
            val isSeries = url.contains("type=tv") || document.select(".episode-list, .season-list").isNotEmpty()
            val isMovie = url.contains("type=movie") || (!isSeries && document.select(".movie-player").isNotEmpty())
            val isAnime = cleanTitle.contains("(Anime)", ignoreCase = true)
            
            // Extrair sinopse
            val synopsis = document.selectFirst("p.text-gray-200, .synopsis, .description, .plot")?.text()
            
            // Extrair gêneros
            val genres = document.select("span.bg-purple-600\\/80, .genre, .tags, .category").map { it.text().trim() }
                .takeIf { it.isNotEmpty() }
            
            // Extrair poster
            val poster = extractPoster(document)
            
            if (isSeries || isAnime) {
                val type = if (isAnime) TvType.Anime else TvType.TvSeries
                
                // Para séries, tentar extrair episódios
                val episodes = tryExtractEpisodes(document, url)
                
                return newTvSeriesLoadResponse(cleanTitle, url, type, episodes) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = synopsis
                    this.tags = genres
                    this.backgroundPosterUrl = poster
                }
            } else {
                // Para filmes
                return newMovieLoadResponse(cleanTitle, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = synopsis
                    this.tags = genres
                    this.backgroundPosterUrl = poster
                }
            }
        } catch (e: Exception) {
            // Fallback: tentar formato alternativo
            return tryAlternativeLoad(url)
        }
    }

    private suspend fun tryAlternativeLoad(url: String): LoadResponse? {
        return try {
            // Tentar extrair informações da URL direto
            val tmdbMatch = Regex("[?&]id=(\\d+)").find(url)
            val tmdbId = tmdbMatch?.groupValues?.get(1)
            val type = if (url.contains("type=tv")) "tv" else "movie"
            
            if (tmdbId != null) {
                val title = "Conteúdo TMDB $tmdbId"
                if (type == "tv") {
                    newTvSeriesLoadResponse(title, url, TvType.TvSeries, emptyList()) {
                        this.posterUrl = "https://image.tmdb.org/t/p/w500"
                    }
                } else {
                    newMovieLoadResponse(title, url, TvType.Movie, url) {
                        this.posterUrl = "https://image.tmdb.org/t/p/w500"
                    }
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun tryExtractEpisodes(document: org.jsoup.nodes.Document, url: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        try {
            // Tentar extrair botões de episódio
            document.select("button[data-url], a[href*='episode'], .episode-item, .episode-link").forEachIndexed { index, element ->
                try {
                    val dataUrl = element.attr("data-url") ?: element.attr("href") ?: ""
                    if (dataUrl.isBlank()) return@forEachIndexed
                    
                    val epNumber = extractEpisodeNumber(element, index + 1)
                    val seasonNumber = element.attr("data-season").toIntOrNull() ?: 1
                    
                    val episode = newEpisode(fixUrl(dataUrl)) {
                        this.name = "Episódio $epNumber"
                        this.season = seasonNumber
                        this.episode = epNumber
                        
                        // Tentar extrair descrição
                        element.selectFirst(".ep-desc, .description")?.text()?.trim()?.let { desc ->
                            if (desc.isNotBlank()) {
                                this.description = desc
                            }
                        }
                    }
                    
                    episodes.add(episode)
                } catch (e: Exception) {
                    // Ignorar episódio com erro
                }
            }
        } catch (e: Exception) {
            // Falha silenciosa
        }
        
        return episodes
    }

    private fun extractYear(document: org.jsoup.nodes.Document): Int? {
        // Tenta extrair do grid de informações
        document.select("div.bg-gray-800\\/50, .info-grid, .metadata").forEach { div ->
            val label = div.selectFirst("p.text-gray-400, .label, .info-label")?.text()
            if (label?.contains("Ano") == true || label?.contains("Year") == true) {
                val yearText = div.selectFirst("p.text-white, .value, .info-value")?.text()
                return yearText?.toIntOrNull()
            }
        }
        
        // Tenta extrair do título
        val title = document.selectFirst("h1, .title")?.text() ?: ""
        return Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractPoster(document: org.jsoup.nodes.Document): String? {
        // Tenta meta tag primeiro
        val ogImage = document.selectFirst("meta[property='og:image']")?.attr("content")
        if (ogImage != null) return fixUrl(ogImage)
        
        // Tenta qualquer imagem grande
        return document.select("img[src*='tmdb.org'], img[src*='poster'], .poster img").firstOrNull()?.attr("src")?.let { fixUrl(it) }
    }

    private fun extractEpisodeNumber(element: Element, default: Int): Int {
        return element.attr("data-ep").toIntOrNull() ?:
               element.selectFirst(".ep-number, .number, .episode-number")?.text()?.toIntOrNull() ?:
               Regex("Ep\\.?\\s*(\\d+)").find(element.text())?.groupValues?.get(1)?.toIntOrNull() ?:
               Regex("Epis[oó]dio\\s*(\\d+)").find(element.text())?.groupValues?.get(1)?.toIntOrNull() ?:
               default
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // Tentar extrair links da página
            val document = app.get(data).document
            
            // Procurar por iframes de player
            val iframeSrc = document.selectFirst("iframe[src*='embed'], iframe[src*='player']")?.attr("src")
            if (iframeSrc != null) {
                return extractFromIframe(fixUrl(iframeSrc), callback)
            }
            
            // Procurar por scripts com m3u8
            val scripts = document.select("script")
            for (script in scripts) {
                val html = script.html()
                val m3u8Pattern = Regex("""(https?://[^"\s]+\.m3u8[^"\s]*)""")
                val match = m3u8Pattern.find(html)
                if (match != null) {
                    val m3u8Url = match.groupValues[1]
                    return createM3u8Link(m3u8Url, callback)
                }
            }
            
            // Procurar por data-url em botões
            val playButton = document.selectFirst("button[data-url], a[data-url]")
            val dataUrl = playButton?.attr("data-url")
            if (dataUrl != null) {
                return extractVideoLinks(dataUrl, subtitleCallback, callback)
            }
            
            false
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun extractFromIframe(
        iframeUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                "Referer" to "https://betterflix.vercel.app/"
            )
            
            val response = app.get(iframeUrl, headers = headers)
            val html = response.text
            
            // Procurar por m3u8
            val patterns = listOf(
                Regex("""file["']?\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                Regex("""src=["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                Regex("""(https?://[^"\s]+\.m3u8[^"\s]*)""")
            )
            
            for (pattern in patterns) {
                val match = pattern.find(html)
                if (match != null) {
                    val m3u8Url = match.groupValues[1]
                    createM3u8Link(m3u8Url, callback)
                    return true
                }
            }
            
            false
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun createM3u8Link(
        m3u8Url: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                "Referer" to "https://betterflix.vercel.app/",
                "Origin" to "https://betterflix.vercel.app"
            )
            
            // Gerar múltiplas qualidades
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
                // Link direto se M3u8Helper falhar
                val link = newExtractorLink(
                    source = name,
                    name = "Video",
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "https://betterflix.vercel.app/"
                    this.quality = 720
                    this.headers = headers
                }
                callback(link)
                true
            }
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun extractVideoLinks(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Tentar extrair do URL direto
        if (url.contains(".m3u8")) {
            return createM3u8Link(url, callback)
        }
        
        // Se não for m3u8, tentar seguir o link
        return try {
            val document = app.get(url).document
            val iframe = document.selectFirst("iframe[src]")
            if (iframe != null) {
                extractFromIframe(iframe.attr("src"), callback)
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
}

// Função de extensão para codificar query
private fun String.encodeSearchQuery(): String {
    return java.net.URLEncoder.encode(this, "UTF-8")
}
