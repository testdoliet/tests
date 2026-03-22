package com.PobreFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class PobreFlix : MainAPI() {
    override var mainUrl = "https://lospobreflix.site/"
    override var name = "PobreFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Documentary, TvType.TvShow)
    override val usesWebView = true

    companion object {
        private const val SEARCH_PATH = "/pesquisar"

        // Categorias do menu principal
        private val FIXED_CATEGORIES = listOf(
            "/filmes" to "Filmes",
            "/series" to "Séries", 
            "/animes" to "Animes",
            "/doramas" to "Doramas",
            "/calendario" to "Calendário",
            "" to "Em Alta"  // Página principal
        )
    }

    override val mainPage = mainPageOf(
        *FIXED_CATEGORIES.map { (path, name) ->
            if (path.isEmpty()) mainUrl to name
            else "$mainUrl$path" to name
        }.toTypedArray()
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1 && request.data != mainUrl) {
            if (request.data.contains("?")) "${request.data}&page=$page"
            else "${request.data}?page=$page"
        } else {
            request.data
        }

        val document = app.get(url).document

        // Seletores baseados no HTML:
        // - Cards dos sliders: .swiper-slide article.relative.group/item
        // - Cards do top 10: .top10-rank-wrap + a
        // - Cards em geral: article.relative.group/item, a.card
        val home = document.select("article.relative.group/item, .swiper-slide article.relative.group/item, .grid article.relative.group/item, a.card")
            .mapNotNull { element ->
                element.toSearchResult()
            }

        // Paginação: verificar se existe botão "Próxima"
        val hasNextPage = document.select("a:contains(Próxima), .pagination a:contains(Próxima), .page-numbers a:contains(Próxima)").isNotEmpty()

        return newHomePageResponse(request.name, home.distinctBy { it.url }, hasNextPage)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Título: pode estar em h3, .font-bold, ou alt da imagem
        val titleElement = selectFirst("h3, .font-bold, .text-sm.md\\:text-base.font-bold")
        val title = titleElement?.text() ?: selectFirst("img")?.attr("alt") ?: return null
        
        // URL: link do card
        val link = selectFirst("a[href]") ?: return null
        val href = link.attr("href") ?: return null
        val cleanUrl = fixUrl(href)
        
        // Pôster: imagem do card
        val poster = selectFirst("img")?.attr("src")?.let { fixUrl(it) }
        
        // Ano: span com ano (formato: 2026)
        val yearElement = selectFirst(".text-xs span:first-child, .text-xs span:matches(\\d{4}), .text-white\\/70.text-xs")
        val year = yearElement?.text()?.trim()?.toIntOrNull()
        
        // Tipo baseado na URL e badges
        val badge = selectFirst(".border-slate-300, .border-white\\/10, .px-1\\.5.py-0\\.5")?.text()?.lowercase() ?: ""
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
        
        val isAnime = href.contains("/anime/") || badge.contains("anime")
        val isSerie = href.contains("/serie/") || badge.contains("série") || badge.contains("serie")
        val isDorama = href.contains("/dorama/") || badge.contains("dorama")
        
        return when {
            isAnime -> newAnimeSearchResponse(cleanTitle, cleanUrl, TvType.Anime) {
                this.posterUrl = poster
                this.year = year
            }
            isSerie || isDorama -> newTvSeriesSearchResponse(cleanTitle, cleanUrl, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
            else -> newMovieSearchResponse(cleanTitle, cleanUrl, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl$SEARCH_PATH?s=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document

        return document.select(".grid .card, a.card, article.relative.group/item").mapNotNull { card ->
            try {
                val title = card.attr("title") ?: card.selectFirst("img")?.attr("alt") ?: return@mapNotNull null
                val href = card.attr("href") ?: return@mapNotNull null
                val cleanUrl = fixUrl(href)
                
                val poster = card.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                val year = Regex("\\b(\\d{4})\\b").find(title)?.groupValues?.get(1)?.toIntOrNull()
                val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
                
                val isAnime = href.contains("/anime/") || title.contains("(Anime)", ignoreCase = true)
                val isSerie = href.contains("/serie/") || href.contains("/tv/")
                
                when {
                    isAnime -> newAnimeSearchResponse(cleanTitle, cleanUrl, TvType.Anime) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    isSerie -> newTvSeriesSearchResponse(cleanTitle, cleanUrl, TvType.TvSeries) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    else -> newMovieSearchResponse(cleanTitle, cleanUrl, TvType.Movie) {
                        this.posterUrl = poster
                        this.year = year
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        // ===== TÍTULO =====
        val title = document.selectFirst("h1, .text-3xl.text-lead.font-bold, .text-3xl.md\\:text-4xl.font-bold")?.text() ?: return null
        
        // ===== ANO =====
        val year = document.selectFirst(".inline-flex.items-center.rounded-full.px-3.py-1 span:contains(\\d{4}), .text-xs span:contains(\\d{4})")?.text()?.toIntOrNull()
        
        // ===== SINOPSE =====
        val plot = document.selectFirst(".text-slate-700.dark\\:text-slate-200.md\\:text-lg, .text-slate-900\\/90.dark\\:text-slate-100\\/90, p:contains(Cinco ex-espiões)")?.text()
        
        // ===== GÊNEROS =====
        val tags = document.select(".flex.flex-wrap.gap-2 a, .px-3.py-1.rounded-full.text-xs.bg-slate-200")
            .map { it.text() }
            .takeIf { it.isNotEmpty() }
        
        // ===== AVALIAÇÃO (converter % para 1-10) =====
        val ratingPercent = document.selectFirst("text[x='18'][y='21']")?.text()?.replace("%", "")?.toFloatOrNull()
        val rating = ratingPercent?.let { (it / 10) } // Ex: 90% -> 9.0
        
        // ===== POSTER e BACKGROUND =====
        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }
        
        // Background: imagem de fundo do hero
        val background = document.selectFirst(".absolute.left-1\\/2 img, .blur-\\[4px\\] img")?.attr("src")?.let { fixUrl(it) }
        
        // ===== DURAÇÃO (apenas para filmes) =====
        val durationText = document.selectFirst(".bg-slate-200.dark\\:bg-slate-700.rounded-lg.p-3:contains(min) .font-medium, .inline-flex.items-center.rounded-full.px-3.py-1:contains(min)")?.text()
        val duration = durationText?.let { 
            Regex("(\\d+)\\s*min").find(it)?.groupValues?.get(1)?.toIntOrNull()
        }
        
        // ===== QUALIDADE =====
        val quality = document.selectFirst(".inline-flex.items-center.rounded-full.px-3.py-1:contains(HD), .inline-flex.items-center.rounded-full.px-3.py-1:contains(4K)")?.text()
        
        // ===== ELENCO =====
        val cast = document.select("#cast-section .swiper-slide .text-sm.font-bold, .swiper-slide .text-sm.font-bold")
            .map { it.text() }
            .takeIf { it.isNotEmpty() }
            ?.map { Actor(it) }
        
        // ===== TRAILER =====
        val trailerKey = document.selectFirst("script:containsData(window.__trailerKeys)")?.data()?.let { script ->
            Regex("window\\.__trailerKeys\\s*=\\s*\\[\"([^\"]+)\"\\]").find(script)?.groupValues?.get(1)
        }
        val trailerUrl = trailerKey?.let { "https://www.youtube.com/watch?v=$it" }
        
        // ===== RECOMENDAÇÕES =====
        val recommendations = document.select("#relatedSection .swiper-slide a, .related-swiper .swiper-slide a")
            .mapNotNull { element ->
                try {
                    val href = element.attr("href") ?: return@mapNotNull null
                    val titleEl = element.selectFirst("h3, .text-white.font-bold")
                    val title = titleEl?.text() ?: return@mapNotNull null
                    val posterRec = element.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                    val yearRec = element.selectFirst(".text-white\\/70.text-xs")?.text()?.toIntOrNull()
                    
                    val isAnimeRec = href.contains("/anime/")
                    val isSerieRec = href.contains("/serie/") || href.contains("/dorama/")
                    
                    when {
                        isAnimeRec -> newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
                            this.posterUrl = posterRec
                            this.year = yearRec
                        }
                        isSerieRec -> newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                            this.posterUrl = posterRec
                            this.year = yearRec
                        }
                        else -> newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                            this.posterUrl = posterRec
                            this.year = yearRec
                        }
                    }
                } catch (e: Exception) { null }
            }
        
        // ===== DETERMINAR SE É SÉRIE =====
        val isSerie = url.contains("/serie/") || url.contains("/dorama/") || 
                      document.select("#episodes-list, .season-dropdown, .episode-card").isNotEmpty()
        
        return if (isSerie) {
            val episodes = extractEpisodes(document, url)
            
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.year = year
                this.plot = plot
                this.tags = tags
                this.rating = rating?.toInt()
                this.quality = quality
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
                this.trailerUrl = trailerUrl
                
                cast?.let { addActors(it) }
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.year = year
                this.plot = plot
                this.tags = tags
                this.rating = rating?.toInt()
                this.quality = quality
                this.duration = duration
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
                this.trailerUrl = trailerUrl
                
                cast?.let { addActors(it) }
            }
        }
    }

    private suspend fun extractEpisodes(document: org.jsoup.nodes.Document, url: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        // Extrair dados dos episódios do JavaScript (window.allEpisodes)
        val scriptData = document.selectFirst("script:containsData(window.allEpisodes)")?.data()
        if (scriptData != null) {
            try {
                // Parse do JSON de episódios
                val jsonMatch = Regex("window\\.allEpisodes\\s*=\\s*(\\{[^;]+\\})").find(scriptData)
                val jsonString = jsonMatch?.groupValues?.get(1) ?: return extractEpisodesFromHtml(document)
                
                // Usar parsing simples com regex para extrair episódios
                val seasonPattern = Regex("\"(\\d+)\":\\s*\\[([^\\]]+)\\]")
                val seasonMatches = seasonPattern.findAll(jsonString)
                
                for (seasonMatch in seasonMatches) {
                    val seasonNum = seasonMatch.groupValues[1].toIntOrNull() ?: 1
                    val episodesJson = seasonMatch.groupValues[2]
                    
                    val episodePattern = Regex("\\{[^}]*\"epi_num\"\\s*:\\s*(\\d+)[^}]*\"title\"\\s*:\\s*\"([^\"]*)\"[^}]*\"thumb_url\"\\s*:\\s*\"([^\"]*)\"[^}]*\"duration\"\\s*:\\s*(\\d+)[^}]*\"air_date\"\\s*:\\s*\"([^\"]*)\"[^}]*\"has_dub\"\\s*:\\s*(true|false)[^}]*\"has_leg\"\\s*:\\s*(true|false)[^}]*\\}")
                    val episodeMatches = episodePattern.findAll(episodesJson)
                    
                    for (epMatch in episodeMatches) {
                        val epNum = epMatch.groupValues[1].toIntOrNull() ?: continue
                        val epTitle = epMatch.groupValues[2].ifEmpty { "Episódio $epNum" }
                        val thumbUrl = epMatch.groupValues[3].takeIf { it.isNotEmpty() }?.let { fixUrl(it) }
                        val duration = epMatch.groupValues[4].toIntOrNull()
                        val airDate = epMatch.groupValues[5].takeIf { it.isNotEmpty() }
                        val hasDub = epMatch.groupValues[6] == "true"
                        val hasLeg = epMatch.groupValues[7] == "true"
                        
                        val episodeUrl = "$url/$seasonNum/$epNum"
                        
                        episodes.add(newEpisode(fixUrl(episodeUrl)) {
                            this.name = epTitle
                            this.season = seasonNum
                            this.episode = epNum
                            this.posterUrl = thumbUrl
                            this.description = buildString {
                                if (duration != null && duration > 0) append("Duração: ${duration}min\n")
                                if (hasDub) append("Dublado\n")
                                if (hasLeg) append("Legendado\n")
                                if (airDate != null) append("Data: $airDate")
                            }.trim()
                        })
                    }
                }
                
                if (episodes.isNotEmpty()) return episodes
            } catch (e: Exception) {
                // Fallback para extração por HTML
            }
        }
        
        // Fallback: extrair episódios do HTML
        return extractEpisodesFromHtml(document)
    }
    
    private fun extractEpisodesFromHtml(document: org.jsoup.nodes.Document): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        val episodeElements = document.select("#episodes-list article, .episode-card, .episode-item")
        
        episodeElements.forEach { element ->
            try {
                val link = element.selectFirst("a[href]") ?: return@forEach
                val episodeUrl = link.attr("href")
                if (episodeUrl.isBlank()) return@forEach
                
                val seasonText = element.selectFirst(".text-lead.shrink-0")?.text() ?: "T1:E1"
                val seasonMatch = Regex("T(\\d+):E(\\d+)").find(seasonText)
                val season = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val episodeNum = seasonMatch?.groupValues?.get(2)?.toIntOrNull() ?: 1
                
                val title = element.selectFirst("h2, .truncate")?.text() ?: "Episódio $episodeNum"
                val thumb = element.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                val description = element.selectFirst(".line-clamp-2.text-xs")?.text()
                
                episodes.add(newEpisode(fixUrl(episodeUrl)) {
                    this.name = title
                    this.season = season
                    this.episode = episodeNum
                    this.posterUrl = thumb
                    this.description = description
                })
            } catch (e: Exception) { }
        }
        
        return episodes
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // O PobreFlix usa players como warezcdn.site e superflixapi.rest
        // Vamos extrair o iframe ou usar os extractors padrão
        
        return try {
            val document = app.get(data).document
            
            // Procurar o iframe do player no HTML
            val iframe = document.selectFirst("iframe[src*='player'], iframe[src*='embed'], iframe[src*='fembed'], iframe[src*='filemoon']")
            if (iframe != null) {
                val playerUrl = iframe.attr("src")
                // Usar os extractors padrão do CloudStream
                loadExtractor(playerUrl, subtitleCallback, callback)
            } else {
                // Procurar vídeo direto (m3u8, mp4)
                val videoUrl = document.selectFirst("video source, source[src]")?.attr("src")
                if (videoUrl != null) {
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = name,
                            url = videoUrl,
                            quality = Qualities.Unknown.value,
                            isM3u8 = videoUrl.contains(".m3u8"),
                            headers = mapOf("Referer" to mainUrl)
                        )
                    )
                    true
                } else false
            }
        } catch (e: Exception) {
            false
        }
    }
}
