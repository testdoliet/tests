package com.StreamFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.fasterxml.jackson.annotation.JsonProperty
import java.text.SimpleDateFormat
import java.util.Locale

class StreamFlix : MainAPI() {
    override var mainUrl = "https://streamflix.live/"
    override var name = "StreamFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val usesWebView = true

    private val apiUrl = "$mainUrl/api_proxy.php"
    private val tmdbImageUrl = "https://image.tmdb.org/t/p"

    companion object {
        // Categorias da API
        private val homeCategories = listOf(
            "trending" to "Trending da Semana",
            "popular_movies" to "Filmes Populares",
            "popular_series" to "Séries Populares",
            "top_movies" to "Top Filmes",
            "top_series" to "Top Séries"
        )
    }

    override val mainPage = mainPageOf(
        *homeCategories.map { (category, name) ->
            "$apiUrl?action=tmdb_$category" to name
        }.toTypedArray()
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) {
            "$request.data&page=$page"
        } else {
            request.data
        }

        val response = app.get(url)
        val json = response.text

        // Parseia a resposta baseado no tipo de categoria
        val items = when {
            request.data.contains("movies") || request.data.contains("top_movies") -> {
                parseMoviesJson(json)
            }
            request.data.contains("series") || request.data.contains("top_series") -> {
                parseSeriesJson(json)
            }
            request.data.contains("trending") -> {
                parseTrendingJson(json)
            }
            else -> emptyList()
        }

        return newHomePageResponse(request.name, items, false)
    }

    private fun parseMoviesJson(json: String): List<SearchResponse> {
        return try {
            val response = json.parsedSafe<MoviesResponse>() ?: return emptyList()
            response.movies.mapNotNull { movie ->
                movie.toSearchResponse()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseSeriesJson(json: String): List<SearchResponse> {
        return try {
            val response = json.parsedSafe<SeriesResponse>() ?: return emptyList()
            response.series.mapNotNull { series ->
                series.toSearchResponse()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseTrendingJson(json: String): List<SearchResponse> {
        return try {
            val response = json.parsedSafe<TrendingResponse>() ?: return emptyList()
            val items = mutableListOf<SearchResponse>()
            
            response.movies?.forEach { movie ->
                movie.toSearchResponse()?.let { items.add(it) }
            }
            
            response.series?.forEach { series ->
                series.toSearchResponse()?.let { items.add(it) }
            }
            
            items
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun TMDBMovie.toSearchResponse(): SearchResponse? {
        val title = title ?: return null
        val year = release_date?.substring(0, 4)?.toIntOrNull()
        val poster = poster_path?.let { "$tmdbImageUrl/w500$it" }
        val url = "$mainUrl/movie/$id"

        return newMovieSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = poster
            this.year = year
        }
    }

    private fun TMDBSeries.toSearchResponse(): SearchResponse? {
        val title = name ?: return null
        val year = first_air_date?.substring(0, 4)?.toIntOrNull()
        val poster = poster_path?.let { "$tmdbImageUrl/w500$it" }
        val url = "$mainUrl/series/$id"

        return newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
            this.posterUrl = poster
            this.year = year
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        
        // Busca filmes
        try {
            val movieSearchUrl = "$apiUrl?action=tmdb_search&query=${java.net.URLEncoder.encode(query, "UTF-8")}&type=movie"
            val movieResponse = app.get(movieSearchUrl)
            val movieJson = movieResponse.parsedSafe<TMDBMovieSearchResponse>()
            
            movieJson?.result?.let { movie ->
                movie.toSearchResponse()?.let { results.add(it) }
            }
        } catch (e: Exception) {
            // Ignora erro na busca de filmes
        }
        
        // Busca séries
        try {
            val seriesSearchUrl = "$apiUrl?action=tmdb_search&query=${java.net.URLEncoder.encode(query, "UTF-8")}&type=tv"
            val seriesResponse = app.get(seriesSearchUrl)
            val seriesJson = seriesResponse.parsedSafe<TMDBSeriesSearchResponse>()
            
            seriesJson?.result?.let { series ->
                series.toSearchResponse()?.let { results.add(it) }
            }
        } catch (e: Exception) {
            // Ignora erro na busca de séries
        }
        
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        // Extrai o ID da URL
        val id = when {
            url.contains("/movie/") -> url.substringAfter("/movie/").toIntOrNull()
            url.contains("/series/") -> url.substringAfter("/series/").toIntOrNull()
            else -> null
        } ?: return null
        
        val isSerie = url.contains("/series/")
        
        // Busca informações detalhadas da API
        val infoUrl = "$apiUrl?action=get_vod_info&vod_id=$id"
        val infoResponse = app.get(infoUrl)
        val infoJson = infoResponse.parsedSafe<VodInfoResponse>() ?: return null
        
        val info = infoJson.info ?: return null
        val movieData = infoJson.movie_data
        
        val title = info.name ?: info.o_name ?: return null
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
        
        // Processa posters
        val poster = info.cover_big ?: info.movie_image ?: 
                    info.backdrop_path?.firstOrNull()?.let { fixUrl(it) }
        
        val backdrop = info.backdrop_path?.firstOrNull()?.let { fixUrl(it) }
        
        val year = info.release_date?.substring(0, 4)?.toIntOrNull() ?: 
                  movieData?.year?.toIntOrNull()
        
        val plot = info.plot ?: info.description
        
        // Processa gêneros
        val tags = info.genre?.split(",")?.map { it.trim() }
        
        // Processa elenco
        val actors = info.actors?.split(",")?.mapNotNull { actorName ->
            if (actorName.isNotBlank()) {
                Actor(actorName.trim())
            }
        } ?: info.cast?.split(",")?.mapNotNull { actorName ->
            if (actorName.isNotBlank()) {
                Actor(actorName.trim())
            }
        }
        
        // Processa trailer
        val trailerUrl = info.youtube_trailer?.let { 
            if (it.isNotBlank() && it != "null") {
                "https://www.youtube.com/watch?v=$it"
            } else null
        }
        
        // Processa duração para filmes
        val duration = info.episode_run_time ?: 
                      (info.duration_secs?.div(60)) ?: 
                      parseDuration(info.duration)
        
        // Busca recomendações da página
        val document = app.get(url).document
        val recommendations = extractRecommendations(document)
        
        return if (isSerie) {
            // Para séries, extrai episódios do HTML
            val episodes = extractEpisodes(document, id)
            
            newTvSeriesLoadResponse(cleanTitle, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = plot
                this.tags = tags
                
                if (!actors.isNullOrEmpty()) {
                    addActors(actors)
                }
                
                trailerUrl?.let { addTrailer(it) }
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
                this.duration = duration
            }
        } else {
            // Para filmes
            newMovieLoadResponse(cleanTitle, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = plot
                this.tags = tags
                
                if (!actors.isNullOrEmpty()) {
                    addActors(actors)
                }
                
                trailerUrl?.let { addTrailer(it) }
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
                this.duration = duration
            }
        }
    }

    private fun parseDuration(durationStr: String?): Int? {
        if (durationStr.isNullOrBlank()) return null
        
        // Formato "01:47:38"
        val parts = durationStr.split(":").map { it.toIntOrNull() }
        if (parts.size == 3 && parts.all { it != null }) {
            return parts[0]!! * 60 + parts[1]!!
        }
        return null
    }

    private suspend fun extractEpisodes(document: org.jsoup.nodes.Document, seriesId: Int): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        // Tenta encontrar elementos de episódios
        val episodeElements = document.select("button.bd-play[data-url], a[href*='episode'], [data-episode], .episode-item")
        
        if (episodeElements.isNotEmpty()) {
            episodeElements.forEachIndexed { index, element ->
                val dataUrl = element.attr("data-url") ?: element.attr("href") ?: ""
                val episodeNumber = element.attr("data-episode").toIntOrNull() ?: (index + 1)
                val seasonNumber = element.attr("data-season").toIntOrNull() ?: 1
                
                if (dataUrl.isNotBlank()) {
                    val episode = newEpisode(fixUrl(dataUrl)) {
                        this.name = element.selectFirst(".ep-name, .title")?.text() ?: "Episódio $episodeNumber"
                        this.season = seasonNumber
                        this.episode = episodeNumber
                        
                        element.selectFirst("img")?.attr("src")?.let { img ->
                            this.posterUrl = fixUrl(img)
                        }
                    }
                    episodes.add(episode)
                }
            }
        }
        
        return episodes.sortedBy { it.episode }
    }

    private suspend fun extractRecommendations(document: org.jsoup.nodes.Document): List<SearchResponse> {
        return document.select("div.group\\/card").mapNotNull { element ->
            extractFromCardElement(element)
        }
    }

    private fun extractFromCardElement(element: Element): SearchResponse? {
        val onclick = element.selectFirst("[onclick]")?.attr("onclick") ?: return null
        val jsonData = extractJsonFromOnclick(onclick) ?: return null
        
        val title = extractJsonValue(jsonData, "name") ?: 
                   extractJsonValue(jsonData, "title") ?: return null
        val id = extractJsonValue(jsonData, "id") ?:
                extractJsonValue(jsonData, "stream_id") ?:
                extractJsonValue(jsonData, "series_id") ?: return null
        
        val streamType = extractJsonValue(jsonData, "stream_type")
        val isSerie = streamType == "series" || jsonData.contains("series_id")
        
        val poster = extractJsonValue(jsonData, "tmdb_poster") ?:
                    extractJsonValue(jsonData, "poster_path")?.let { 
                        if (it.startsWith("http")) it else "$tmdbImageUrl/w500$it"
                    } ?: extractJsonValue(jsonData, "cover") ?:
                    extractJsonValue(jsonData, "stream_icon")
        
        val year = extractJsonValue(jsonData, "year")?.toIntOrNull()
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
        
        val url = if (isSerie) {
            "$mainUrl/series/$id"
        } else {
            "$mainUrl/movie/$id"
        }

        return if (isSerie) {
            newTvSeriesSearchResponse(cleanTitle, url, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(cleanTitle, url, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    private fun extractJsonFromOnclick(onclick: String): String? {
        val regex = "openContent\\(JSON.parse\\(decodeURIComponent\\(['\"](%7B.*?%7D)['\"]\\)\\)\\)".toRegex()
        val matchResult = regex.find(onclick)
        
        return matchResult?.groupValues?.get(1)?.let { encodedJson ->
            java.net.URLDecoder.decode(encodedJson, "UTF-8")
        }
    }

    private fun extractJsonValue(json: String, key: String): String? {
        val regex = "\"$key\"\\s*:\\s*\"([^\"]*)\"".toRegex()
        return regex.find(json)?.groupValues?.get(1)?.replace("\\\"", "\"")
    }

    // loadLinks desativado por enquanto para teste
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return false
    }

    // Classes de dados para a API
    data class MoviesResponse(
        @JsonProperty("movies") val movies: List<TMDBMovie>
    )

    data class SeriesResponse(
        @JsonProperty("series") val series: List<TMDBSeries>
    )

    data class TrendingResponse(
        @JsonProperty("movies") val movies: List<TMDBMovie>?,
        @JsonProperty("series") val series: List<TMDBSeries>?
    )

    data class TMDBMovie(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: String?,
        @JsonProperty("original_title") val original_title: String?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("poster_path") val poster_path: String?,
        @JsonProperty("backdrop_path") val backdrop_path: String?,
        @JsonProperty("release_date") val release_date: String?,
        @JsonProperty("vote_average") val vote_average: Double?,
        @JsonProperty("genre_ids") val genre_ids: List<Int>?,
        @JsonProperty("popularity") val popularity: Double?
    )

    data class TMDBSeries(
        @JsonProperty("id") val id: Int,
        @JsonProperty("name") val name: String?,
        @JsonProperty("original_name") val original_name: String?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("poster_path") val poster_path: String?,
        @JsonProperty("backdrop_path") val backdrop_path: String?,
        @JsonProperty("first_air_date") val first_air_date: String?,
        @JsonProperty("vote_average") val vote_average: Double?,
        @JsonProperty("genre_ids") val genre_ids: List<Int>?,
        @JsonProperty("origin_country") val origin_country: List<String>?,
        @JsonProperty("popularity") val popularity: Double?
    )

    data class TMDBMovieSearchResponse(
        @JsonProperty("result") val result: TMDBMovie?
    )

    data class TMDBSeriesSearchResponse(
        @JsonProperty("result") val result: TMDBSeries?
    )

    data class VodInfoResponse(
        @JsonProperty("info") val info: VodInfo?,
        @JsonProperty("movie_data") val movie_data: MovieData?
    )

    data class VodInfo(
        @JsonProperty("kinopoisk_url") val kinopoisk_url: String?,
        @JsonProperty("tmdb_id") val tmdb_id: Int?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("o_name") val o_name: String?,
        @JsonProperty("cover_big") val cover_big: String?,
        @JsonProperty("movie_image") val movie_image: String?,
        @JsonProperty("release_date") val release_date: String?,
        @JsonProperty("episode_run_time") val episode_run_time: Int?,
        @JsonProperty("youtube_trailer") val youtube_trailer: String?,
        @JsonProperty("director") val director: String?,
        @JsonProperty("actors") val actors: String?,
        @JsonProperty("cast") val cast: String?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("plot") val plot: String?,
        @JsonProperty("age") val age: String?,
        @JsonProperty("mpaa_rating") val mpaa_rating: String?,
        @JsonProperty("rating_count_kinopoisk") val rating_count_kinopoisk: Int?,
        @JsonProperty("country") val country: String?,
        @JsonProperty("genre") val genre: String?,
        @JsonProperty("backdrop_path") val backdrop_path: List<String>?,
        @JsonProperty("duration_secs") val duration_secs: Int?,
        @JsonProperty("duration") val duration: String?,
        @JsonProperty("bitrate") val bitrate: Int?,
        @JsonProperty("rating") val rating: Double?,
        @JsonProperty("releasedate") val releasedate: String?,
        @JsonProperty("subtitles") val subtitles: List<String>?
    )

    data class MovieData(
        @JsonProperty("stream_id") val stream_id: Int?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("year") val year: String?,
        @JsonProperty("added") val added: String?,
        @JsonProperty("category_id") val category_id: String?,
        @JsonProperty("category_ids") val category_ids: List<Int>?,
        @JsonProperty("container_extension") val container_extension: String?,
        @JsonProperty("custom_sid") val custom_sid: String?,
        @JsonProperty("direct_source") val direct_source: String?
    )
}
