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
    override val usesWebView = true

    companion object {
        private val MAIN_TABS = listOf(
            "/" to "Tops da semana",
            "/filmes" to "Filmes do momento",
            "/series" to "Séries do momento",
            "/tv" to "Canais de TV"
        )
        
        // Fontes de player
        private const val SOURCE_1_BASE = "https://superflixapi.asia"
        private const val SOURCE_2_BASE = "https://megaembed.com"
    }

    override val mainPage = mainPageOf(
        *MAIN_TABS.map { (path, name) -> 
            "$mainUrl$path" to name 
        }.toTypedArray()
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document

        val home = when {
            request.data.contains("/tv") -> extractTVChannels(document)
            else -> extractMediaContent(document)
        }

        return newHomePageResponse(request.name, home.distinctBy { it.url }, hasNextPage = false)
    }

    private fun extractMediaContent(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()

        // Carrossel principal
        document.select("a[href*='?id='][target='_blank']").forEach { element ->
            try {
                val href = element.attr("href") ?: return@forEach
                if (href.startsWith("/canal")) return@forEach

                val imgElement = element.selectFirst("img")
                val title = imgElement?.attr("alt") ?: 
                           element.selectFirst("p.text-white")?.text() ?:
                           element.selectFirst("p.text-xs")?.text() ?:
                           return@forEach

                val poster = imgElement?.attr("src")?.let { fixUrl(it) }
                val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
                val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()

                // Determinar tipo
                val isSeries = href.contains("type=tv")
                val isMovie = href.contains("type=movie")
                val isAnime = title.contains("(Anime)", ignoreCase = true) || 
                              href.contains("/anime")

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

        return items
    }

    private fun extractTVChannels(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val channels = mutableListOf<SearchResponse>()

        document.select("a[href^='/canal']").forEach { element ->
            try {
                val href = element.attr("href") ?: return@forEach
                val nameElement = element.selectFirst("h3.text-xs, h3.text-sm")
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

        return channels
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
        val document = app.get(url).document

        // Extrair título
        val titleElement = document.selectFirst("h1")
        val title = titleElement?.text() ?: return null
        
        // Extrair informações básicas
        val year = extractYear(document)
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
        
        // Determinar tipo
        val isSeries = url.contains("type=tv")
        val isMovie = url.contains("type=movie")
        val isAnime = cleanTitle.contains("(Anime)", ignoreCase = true)
        
        // Extrair sinopse
        val synopsis = document.selectFirst("p.text-gray-200")?.text()
        
        // Extrair gêneros
        val genres = document.select("span.bg-purple-600\\/80").map { it.text().trim() }
            .takeIf { it.isNotEmpty() }
        
        // Extrair poster
        val poster = extractPoster(document)
        
        // Extrair ID do TMDB
        val tmdbId = extractTmdbId(url)
        
        if (isSeries || isAnime) {
            val type = if (isAnime) TvType.Anime else TvType.TvSeries
            
            // Para séries, tentar extrair episódios
            val episodes = extractEpisodes(url, tmdbId)
            
            return newTvSeriesLoadResponse(cleanTitle, url, type, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = synopsis
                this.tags = genres
                
                // Adicionar temporadas se disponível
                if (episodes.isNotEmpty()) {
                    val seasons = episodes.groupBy { it.season }.keys.sorted()
                    this.recommendedBackgroundUrl = poster
                }
            }
        } else {
            // Para filmes
            return newMovieLoadResponse(cleanTitle, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = synopsis
                this.tags = genres
                this.recommendedBackgroundUrl = poster
            }
        }
    }

    private fun extractYear(document: org.jsoup.nodes.Document): Int? {
        // Tenta extrair do grid de informações
        document.select("div.bg-gray-800\\/50").forEach { div ->
            val label = div.selectFirst("p.text-gray-400")?.text()
            if (label?.contains("Ano") == true) {
                val yearText = div.selectFirst("p.text-white")?.text()
                return yearText?.toIntOrNull()
            }
        }
        
        // Tenta extrair do título
        val title = document.selectFirst("h1")?.text() ?: ""
        return Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractPoster(document: org.jsoup.nodes.Document): String? {
        // Tenta meta tag primeiro
        val ogImage = document.selectFirst("meta[property='og:image']")?.attr("content")
        if (ogImage != null) return fixUrl(ogImage)
        
        // Tenta qualquer imagem grande
        return document.select("img[src*='tmdb.org']").firstOrNull()?.attr("src")?.let { fixUrl(it) }
    }

    private fun extractTmdbId(url: String): String? {
        return Regex("[?&]id=(\\d+)").find(url)?.groupValues?.get(1)
    }

    private suspend fun extractEpisodes(url: String, tmdbId: String?): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        if (tmdbId == null) return episodes
        
        try {
            // Para séries, vamos tentar carregar múltiplas temporadas
            for (season in 1..5) { // Tentar até 5 temporadas
                val seasonEpisodes = tryLoadSeasonEpisodes(tmdbId, season)
                episodes.addAll(seasonEpisodes)
                
                // Se não encontrou episódios nesta temporada, para
                if (seasonEpisodes.isEmpty() && season > 1) {
                    break
                }
            }
        } catch (e: Exception) {
            // Falha silenciosa
        }
        
        return episodes
    }
    
    private suspend fun tryLoadSeasonEpisodes(tmdbId: String, season: Int): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        try {
            // Tentar carregar página da temporada
            val seasonUrl = "$mainUrl/serie?id=$tmdbId&type=tv&season=$season"
            val document = app.get(seasonUrl).document
            
            // Procurar por botões de episódio
            document.select("button[data-url], a[href*='episode']").forEachIndexed { index, element ->
                try {
                    val dataUrl = element.attr("data-url") ?: element.attr("href") ?: ""
                    if (dataUrl.isBlank()) return@forEachIndexed
                    
                    val epNumber = extractEpisodeNumber(element, index + 1)
                    
                    val episode = newEpisode(fixUrl(dataUrl)) {
                        this.name = "Episódio $epNumber"
                        this.season = season
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
            
            // Se não encontrou botões, criar episódios baseados no padrão
            if (episodes.isEmpty()) {
                // Tentar encontrar contador de episódios
                val episodeCount = document.select("div:contains(eps), span:contains(ep)").firstOrNull()
                    ?.text()?.let { Regex("(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                    ?: 12 // Padrão: 12 episódios por temporada
                
                for (ep in 1..episodeCount) {
                    val episodeUrl = "$mainUrl/episode?id=$tmdbId&type=tv&season=$season&episode=$ep"
                    episodes.add(
                        newEpisode(episodeUrl) {
                            this.name = "Episódio $ep"
                            this.season = season
                            this.episode = ep
                        }
                    )
                }
            }
        } catch (e: Exception) {
            // Falha ao carregar temporada
        }
        
        return episodes
    }

    private fun extractEpisodeNumber(element: Element, default: Int): Int {
        return element.attr("data-ep").toIntOrNull() ?:
               element.selectFirst(".ep-number, .number")?.text()?.toIntOrNull() ?:
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
        return BetterFlixExtractor.extractVideoLinks(data, name, subtitleCallback, callback)
    }
}

// Função de extensão para codificar query
private fun String.encodeSearchQuery(): String {
    return java.net.URLEncoder.encode(this, "UTF-8")
}
