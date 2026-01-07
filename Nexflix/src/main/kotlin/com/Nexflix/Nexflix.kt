package com.Superflix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty

class NexFlix : MainAPI() {
    override var mainUrl = "https://nexflix.vip"
    override var name = "NexFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val usesWebView = false

    private val tmdbImageUrl = "https://image.tmdb.org/t/p"
    private val TMDB_API_KEY = BuildConfig.TMDB_API_KEY
    private val TMDB_ACCESS_TOKEN = BuildConfig.TMDB_ACCESS_TOKEN

    companion object {
        private const val SEARCH_PATH = "/search.php"
        // Tags personalizadas para nosso código saber o que extrair
        private const val TAG_DESTAQUE = "&filtro_plugin=destaque"
        private const val TAG_RECENTE = "&filtro_plugin=recente"
    }

    // --- AQUI CRIAMOS AS ABAS SEPARADAS ---
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Início",
        
        // Filmes
        "$mainUrl/filmes?tipo_lista=filmes$TAG_DESTAQUE" to "Filmes em Destaque",
        "$mainUrl/filmes?tipo_lista=filmes$TAG_RECENTE" to "Filmes Recentes",
        
        // Séries
        "$mainUrl/series?tipo_lista=series$TAG_DESTAQUE" to "Séries em Destaque",
        "$mainUrl/series?tipo_lista=series$TAG_RECENTE" to "Séries Recentes",
        
        // Animes
        "$mainUrl/animes?tipo_lista=animes$TAG_DESTAQUE" to "Animes em Destaque",
        "$mainUrl/animes?tipo_lista=animes$TAG_RECENTE" to "Animes Recentes",
        
        // Doramas
        "$mainUrl/doramas?tipo_lista=doramas$TAG_DESTAQUE" to "Doramas em Destaque",
        "$mainUrl/doramas?tipo_lista=doramas$TAG_RECENTE" to "Doramas Recentes",
        
        // Novelas
        "$mainUrl/novelas?tipo_lista=novelas$TAG_DESTAQUE" to "Novelas em Destaque",
        "$mainUrl/novelas?tipo_lista=novelas$TAG_RECENTE" to "Novelas Recentes"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // 1. Identifica qual aba o usuário clicou baseada na nossa TAG
        val isDestaque = request.data.contains(TAG_DESTAQUE)
        
        // 2. Limpa a URL para fazer a requisição real ao site
        // Remove a tag falsa e prepara a paginação
        val cleanUrl = request.data
            .replace(TAG_DESTAQUE, "")
            .replace(TAG_RECENTE, "")
            
        val url = if (page > 1) {
            if (cleanUrl.contains("?")) "$cleanUrl&page=$page" else "$cleanUrl?page=$page"
        } else {
            cleanUrl
        }

        val document = app.get(url).document
        val items = ArrayList<SearchResponse>()

        // -------------------------------------------------------------
        // LÓGICA CONDICIONAL: SEPARA O QUE VAI SER EXTRAÍDO
        // -------------------------------------------------------------
        
        if (isDestaque) {
            // === EXTRAI APENAS O SLIDER (DESTAQUES) ===
            // Como você disse que os destaques mudam na página 2, extraímos em todas as páginas.
            document.select(".vf-hero-slide a.vf-hero-card").forEach { element ->
                val title = element.selectFirst(".vf-hero-title")?.text()?.trim() ?: return@forEach
                val href = element.attr("href")
                
                // Imagem do background
                val style = element.attr("style")
                val imgRegex = """url\(['"]?(.*?)['"]?\)""".toRegex()
                val posterUrl = imgRegex.find(style)?.groupValues?.get(1)?.let { fixUrl(it) }
                
                val year = element.selectFirst(".vf-year")?.text()?.toIntOrNull()
                val type = getType(href)

                items.add(
                    if (type == TvType.Movie) {
                        newMovieSearchResponse(title, fixUrl(href), type) {
                            this.posterUrl = posterUrl
                            this.year = year
                        }
                    } else {
                        newTvSeriesSearchResponse(title, fixUrl(href), type) {
                            this.posterUrl = posterUrl
                            this.year = year
                        }
                    }
                )
            }
        } else {
            // === EXTRAI APENAS A GRADE (RECENTES) ===
            document.select("article.card").forEach { element ->
                val aTag = element.selectFirst("a") ?: return@forEach
                val title = element.selectFirst(".ci-title")?.text() 
                    ?: element.selectFirst(".name")?.text() 
                    ?: aTag.attr("title")
                val href = aTag.attr("href")
                val posterUrl = element.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                val year = element.selectFirst(".year")?.text()?.toIntOrNull()
                val type = getType(href)

                items.add(
                    if (type == TvType.Movie) {
                        newMovieSearchResponse(title, fixUrl(href), type) {
                            this.posterUrl = posterUrl
                            this.year = year
                        }
                    } else {
                        newTvSeriesSearchResponse(title, fixUrl(href), type) {
                            this.posterUrl = posterUrl
                            this.year = year
                        }
                    }
                )
            }
        }

        // Verifica paginação
        val hasNextPage = document.select(".gd-pagination a:contains(›), .gd-pagination a:contains(»), .page-link.next").isNotEmpty()

        return newHomePageResponse(request.name, items.distinctBy { it.url }, hasNextPage)
    }

    private fun getType(url: String): TvType {
        return when {
            url.contains("/anime") -> TvType.Anime
            url.contains("/serie") || url.contains("/novela") || url.contains("/dorama") -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    // --- (O RESTANTE DO CÓDIGO PERMANECE IGUAL: search, load, loadLinks, tmdb...) ---
    // Copie as funções search, load, loadLinks e os data classes do exemplo anterior.
    // Vou colocar aqui resumido para garantir que você tenha o arquivo completo se copiar tudo.

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl$SEARCH_PATH?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document
        return document.select("article.card").mapNotNull { element ->
            val aTag = element.selectFirst("a") ?: return@mapNotNull null
            val title = element.selectFirst(".ci-title")?.text() ?: aTag.attr("title") ?: return@mapNotNull null
            val href = aTag.attr("href")
            val posterUrl = element.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
            val type = getType(href)
            if (type == TvType.Movie) newMovieSearchResponse(title, fixUrl(href), type) { this.posterUrl = posterUrl }
            else newTvSeriesSearchResponse(title, fixUrl(href), type) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst(".title-body h1")?.text()?.replace("Assistir", "")?.replace("Online em HD", "")?.trim() ?: return null
        val year = document.selectFirst(".meta span")?.text()?.toIntOrNull()
        val poster = (document.selectFirst(".title-poster img")?.attr("src") ?: document.selectFirst("meta[property='og:image']")?.attr("content"))?.let { fixUrl(it) }
        val desc = document.selectFirst(".desc")?.text()?.trim()
        val type = getType(url)
        val isSerie = type == TvType.TvSeries || type == TvType.Anime
        val tmdbInfo = searchOnTMDB(title, year, isSerie)
        val recommendations = document.select(".rail-card").mapNotNull {
            val rt = it.selectFirst("img")?.attr("alt") ?: return@mapNotNull null
            val rh = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val rp = it.selectFirst("img")?.attr("src")
            newMovieSearchResponse(rt, fixUrl(rh), TvType.Movie) { this.posterUrl = rp }
        }
        return if (tmdbInfo != null) createLoadResponseWithTMDB(tmdbInfo, url, document, isSerie, recommendations)
        else createLoadResponseFromSite(document, url, title, year, desc, poster, isSerie, recommendations)
    }

    private suspend fun createLoadResponseFromSite(doc: org.jsoup.nodes.Document, url: String, title: String, year: Int?, plot: String?, poster: String?, isSerie: Boolean, recs: List<SearchResponse>): LoadResponse {
        val tags = doc.select(".genres .chip").map { it.text() }
        val playerUrl = doc.selectFirst("iframe.player-iframe")?.attr("src")?.let { fixUrl(it) }
        return if (isSerie) {
            val eps = extractEpisodesFromSite(doc)
            val ft = if(url.contains("/anime")) TvType.Anime else TvType.TvSeries
            newTvSeriesLoadResponse(title, url, ft, eps) { this.posterUrl = poster; this.year = year; this.plot = plot; this.tags = tags; this.recommendations = recs }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, playerUrl ?: url) { this.posterUrl = poster; this.year = year; this.plot = plot; this.tags = tags; this.recommendations = recs }
        }
    }

    private suspend fun createLoadResponseWithTMDB(info: TMDBInfo, url: String, doc: org.jsoup.nodes.Document, isSerie: Boolean, recs: List<SearchResponse>): LoadResponse {
        val playerUrl = doc.selectFirst("iframe.player-iframe")?.attr("src")?.let { fixUrl(it) }
        return if (isSerie) {
            val eps = extractEpisodesFromSite(doc)
            val ft = if(url.contains("/anime")) TvType.Anime else TvType.TvSeries
            newTvSeriesLoadResponse(info.title ?: "", url, ft, eps) { this.posterUrl = info.posterUrl; this.backgroundPosterUrl = info.backdropUrl; this.year = info.year; this.plot = info.overview; this.tags = info.genres; addActors(info.actors ?: emptyList()); info.youtubeTrailer?.let { addTrailer(it) }; this.recommendations = recs }
        } else {
            newMovieLoadResponse(info.title ?: "", url, TvType.Movie, playerUrl ?: url) { this.posterUrl = info.posterUrl; this.backgroundPosterUrl = info.backdropUrl; this.year = info.year; this.plot = info.overview; this.tags = info.genres; this.duration = info.duration; addActors(info.actors ?: emptyList()); info.youtubeTrailer?.let { addTrailer(it) }; this.recommendations = recs }
        }
    }

    private fun extractEpisodesFromSite(doc: org.jsoup.nodes.Document): List<Episode> {
        val episodes = mutableListOf<Episode>()
        doc.select(".ep-grid .ep-btn, .season-row a").forEach { 
            val href = it.attr("href")
            val name = it.text()
            val epNum = Regex("\\d+").find(name)?.value?.toIntOrNull()
            if (href.isNotBlank()) episodes.add(newEpisode(fixUrl(href)) { this.name = name; this.episode = epNum })
        }
        return episodes
    }

    private suspend fun searchOnTMDB(query: String, year: Int?, isTv: Boolean): TMDBInfo? {
        return try {
            val q = java.net.URLEncoder.encode(query, "UTF-8")
            val y = year?.let { "&year=$it" } ?: ""
            val t = if (isTv) "tv" else "movie"
            val res = app.get("https://api.themoviedb.org/3/search/$t?api_key=$TMDB_API_KEY&query=$q&language=pt-BR$y", headers = mapOf("Authorization" to "Bearer $TMDB_ACCESS_TOKEN")).parsedSafe<TMDBSearchResponse>()?.results?.firstOrNull() ?: return null
            getTMDBDetails(res.id, isTv, res)
        } catch (e: Exception) { null }
    }

    private suspend fun getTMDBDetails(id: Int, isTv: Boolean, r: TMDBResult): TMDBInfo? {
        return try {
            val t = if (isTv) "tv" else "movie"
            val d = app.get("https://api.themoviedb.org/3/$t/$id?api_key=$TMDB_API_KEY&language=pt-BR&append_to_response=credits,videos", headers = mapOf("Authorization" to "Bearer $TMDB_ACCESS_TOKEN")).parsedSafe<TMDBDetailsResponse>() ?: return null
            val a = d.credits?.cast?.take(15)?.mapNotNull { if (it.name.isNotBlank()) Actor(it.name, it.profile_path?.let { p -> "$tmdbImageUrl/w185$p" }) else null }
            val v = d.videos?.results?.find { it.site == "YouTube" && it.type == "Trailer" }?.key?.let { "https://www.youtube.com/watch?v=$it" }
            TMDBInfo(id, if (isTv) r.name else r.title, (if (isTv) r.first_air_date else r.release_date)?.take(4)?.toIntOrNull(), r.poster_path?.let { "$tmdbImageUrl/w500$it" }, d.backdrop_path?.let { "$tmdbImageUrl/original$it" }, d.overview, d.genres?.map { it.name }, a, v, if (!isTv) d.runtime else null)
        } catch (e: Exception) { null }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        if (data.contains("nexflix.vip")) {
            val iframeSrc = app.get(data).document.select("iframe.vip-iframe").attr("src")
            if (iframeSrc.isNotBlank()) return loadExtractor(fixUrl(iframeSrc).replace("&amp;", "&"), subtitleCallback, callback)
        } else return loadExtractor(data, subtitleCallback, callback)
        return false
    }

    private data class TMDBInfo(val id: Int, val title: String?, val year: Int?, val posterUrl: String?, val backdropUrl: String?, val overview: String?, val genres: List<String>?, val actors: List<Actor>?, val youtubeTrailer: String?, val duration: Int?)
    private data class TMDBSearchResponse(@JsonProperty("results") val results: List<TMDBResult>)
    private data class TMDBResult(@JsonProperty("id") val id: Int, @JsonProperty("title") val title: String?, @JsonProperty("name") val name: String?, @JsonProperty("release_date") val release_date: String?, @JsonProperty("first_air_date") val first_air_date: String?, @JsonProperty("poster_path") val poster_path: String?)
    private data class TMDBDetailsResponse(@JsonProperty("overview") val overview: String?, @JsonProperty("backdrop_path") val backdrop_path: String?, @JsonProperty("runtime") val runtime: Int?, @JsonProperty("genres") val genres: List<TMDBGenre>?, @JsonProperty("credits") val credits: TMDBCredits?, @JsonProperty("videos") val videos: TMDBVideos?)
    private data class TMDBGenre(@JsonProperty("name") val name: String)
    private data class TMDBCredits(@JsonProperty("cast") val cast: List<TMDBCast>)
    private data class TMDBCast(@JsonProperty("name") val name: String, @JsonProperty("profile_path") val profile_path: String?)
    private data class TMDBVideos(@JsonProperty("results") val results: List<TMDBVideo>)
    private data class TMDBVideo(@JsonProperty("key") val key: String, @JsonProperty("site") val site: String, @JsonProperty("type") val type: String)
}
