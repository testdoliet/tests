package com.FilmesOnlineX

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import java.text.SimpleDateFormat

class FilmesOnlineX : MainAPI() {
    override var mainUrl = "https://filmesonlinex.wf/"
    override var name = "Filmes Online X"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val usesWebView = true

    private val tmdbImageUrl = "https://image.tmdb.org/t/p"
    private val TMDB_API_KEY = BuildConfig.TMDB_API_KEY
    private val TMDB_ACCESS_TOKEN = BuildConfig.TMDB_ACCESS_TOKEN

    companion object {
        private const val SEARCH_PATH = "/?s"

        private val ALL_CATEGORIES = listOf(
            "/movies" to "Filmes",
            "/series" to "Séries",
            "/lancamentos" to "Lançamentos",
            "/category/acao/" to "Ação",
            "/category/action-adventure/" to "Action & Adventure",
            "/category/animacao/" to "Animação",
            "/category/aventura/" to "Aventura",
            "/category/cinema-tv/" to "Cinema TV",
            "/category/comedia/" to "Comédia",
            "/category/crime/" to "Crime",
            "/category/documentario/" to "Documentário",
            "/category/drama/" to "Drama",
            "/category/familia/" to "Família",
            "/category/fantasia/" to "Fantasia",
            "/category/faroeste/" to "Faroeste",
            "/category/ficcao-cientifica/" to "Ficção científica",
            "/category/guerra/" to "Guerra",
            "/category/historia/" to "História",
            "/category/misterio/" to "Mistério",
            "/category/musica/" to "Música",
            "/category/romance/" to "Romance",
            "/category/sci-fi-fantasy/" to "Sci-Fi & Fantasy",
            "/category/terror/" to "Terror",
            "/category/thriller/" to "Thriller",
            "/category/war-politics/" to "War & Politics"
        )
    }

    override val mainPage = mainPageOf(
        *ALL_CATEGORIES.map { (path, name) -> 
            "$mainUrl$path" to name 
        }.toTypedArray()
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) {
            if (request.data.contains("?")) {
                "${request.data}&page=$page"
            } else {
                "${request.data}?page=$page"
            }
        } else {
            request.data
        }

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7"
        )

        val document = app.get(url, headers = headers).document
        val items = document.select("ul.MovieList.Rows > li.TPostMv > article.TPost.B").mapNotNull { element ->
            element.toSearchResult()
        }

        val hasNextPage = document.select("a:contains(Próxima), .wp-pagenavi .next, .pagination a[href*='page']").isNotEmpty()

        return newHomePageResponse(request.name, items, hasNextPage)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = selectFirst("a[href]") ?: return null
        val href = fixUrl(linkElement.attr("href"))
        val title = linkElement.selectFirst("h2.Title")?.text() ?: return null

        var poster = selectFirst("img[src]")?.attr("src")?.let { fixUrl(it) }
        if (poster == null || poster.contains("noimga.svg") || poster.contains("lazy")) {
            poster = selectFirst("img[data-src]")?.attr("data-src")?.let { fixUrl(it) }
        }

        val yearElement = selectFirst(".Qlty.Yr")
        val year = yearElement?.text()?.toIntOrNull()
        val cleanTitle = title.replace(Regex("\\s*\\(\\d{4}\\)"), "").trim()

        val urlLower = href.lowercase()
        val isSerie = urlLower.contains("/series/") || urlLower.contains("/serie/")
        
        return if (isSerie) {
            newTvSeriesSearchResponse(cleanTitle, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(cleanTitle, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl$SEARCH_PATH=${java.net.URLEncoder.encode(query, "UTF-8")}"
        
        return try {
            val document = app.get(searchUrl).document
            document.select("ul.MovieList.Rows > li.TPostMv > article.TPost.B").mapNotNull { element ->
                element.toSearchResult()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.Title, h2.Title")?.text() ?: return null
        val cleanTitle = title.replace(Regex("\\s*\\(\\d{4}\\)"), "").trim()
        
        val year = document.selectFirst(".Info .Date")?.text()?.toIntOrNull() ?:
                   Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        
        var poster = document.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }
        if (poster == null) {
            poster = document.selectFirst("img[src*='tmdb.org']")?.attr("src")?.let { fixUrl(it) }
        }
        if (poster == null) {
            poster = document.selectFirst("img[data-src*='tmdb.org']")?.attr("data-src")?.let { fixUrl(it) }
        }
        
        val plot = document.selectFirst(".Description p")?.text()?.trim()
        
        val tags = document.select(".Genre a").map { it.text() }.takeIf { it.isNotEmpty() }
        
        val castItems = document.select(".Cast a").map { it.text() }.takeIf { it.isNotEmpty() }

        val isSerie = url.contains("/series/") || document.selectFirst(".SeasonBx") != null

        val tmdbInfo = searchOnTMDB(cleanTitle, year, isSerie)

        val recommendations = document.select(".MovieList .TPost.B").mapNotNull { element ->
            element.toSearchResult()
        }.take(20)

        return if (isSerie) {
            // Extrair links das temporadas
            val seasonLinks = extractSeasonLinks(document, url)
            
            val allEpisodes = if (seasonLinks.isNotEmpty()) {
                println("📦 [DEBUG] Encontrados ${seasonLinks.size} links de temporadas")
                val episodes = mutableListOf<Episode>()
                
                for (seasonLink in seasonLinks) {
                    try {
                        println("🔄 [DEBUG] Carregando temporada: $seasonLink")
                        val seasonDoc = app.get(seasonLink).document
                        val seasonEpisodes = extractEpisodesFromSeason(seasonDoc)
                        println("  ➕ Adicionados ${seasonEpisodes.size} episódios")
                        episodes.addAll(seasonEpisodes)
                    } catch (e: Exception) {
                        println("❌ [DEBUG] Erro ao carregar temporada $seasonLink: ${e.message}")
                    }
                }
                episodes
            } else {
                println("⚠️ [DEBUG] Nenhum link de temporada encontrado, tentando extrair direto")
                extractEpisodesFromSeason(document)
            }
            
            newTvSeriesLoadResponse(cleanTitle, url, TvType.TvSeries, allEpisodes) {
                this.posterUrl = tmdbInfo?.posterUrl ?: poster
                this.backgroundPosterUrl = tmdbInfo?.backdropUrl
                this.year = tmdbInfo?.year ?: year
                this.plot = tmdbInfo?.overview ?: plot
                this.tags = tmdbInfo?.genres ?: tags
                
                tmdbInfo?.actors?.let { actors ->
                    addActors(actors)
                } ?: castItems?.let { 
                    addActors(it.map { name -> Actor(name) }) 
                }
                
                tmdbInfo?.youtubeTrailer?.let { trailerUrl ->
                    addTrailer(trailerUrl)
                }
                
                this.recommendations = recommendations
            }
        } else {
            val playerUrl = extractPlayerUrl(document) ?: url
            
            newMovieLoadResponse(cleanTitle, url, TvType.Movie, playerUrl) {
                this.posterUrl = tmdbInfo?.posterUrl ?: poster
                this.backgroundPosterUrl = tmdbInfo?.backdropUrl
                this.year = tmdbInfo?.year ?: year
                this.plot = tmdbInfo?.overview ?: plot
                this.tags = tmdbInfo?.genres ?: tags
                this.duration = tmdbInfo?.duration
                
                tmdbInfo?.actors?.let { actors ->
                    addActors(actors)
                } ?: castItems?.let { 
                    addActors(it.map { name -> Actor(name) }) 
                }
                
                tmdbInfo?.youtubeTrailer?.let { trailerUrl ->
                    addTrailer(trailerUrl)
                }
                
                this.recommendations = recommendations
            }
        }
    }

    private fun extractSeasonLinks(document: org.jsoup.nodes.Document, baseUrl: String): List<String> {
        val seasonLinks = mutableListOf<String>()
        
        println("🔍 [DEBUG] Procurando links de temporadas")
        
        // Seletor 1: Links dentro de .SeasonBx .Title a
        document.select(".SeasonBx .Title a[href]").forEach { element ->
            val href = element.attr("href")
            if (href.contains("/season/")) {
                seasonLinks.add(fixUrl(href))
                println("  📌 Encontrado (Seletor 1): $href")
            }
        }
        
        // Seletor 2: Links diretos para /season/ na página
        if (seasonLinks.isEmpty()) {
            document.select("a[href*='/season/']").forEach { element ->
                val href = element.attr("href")
                seasonLinks.add(fixUrl(href))
                println("  📌 Encontrado (Seletor 2): $href")
            }
        }
        
        // Seletor 3: Informação de temporadas no texto
        if (seasonLinks.isEmpty()) {
            val seasonText = document.selectFirst(".Info .Season, .seasons-info, .TPTblCn .Title")?.text()
            if (seasonText != null) {
                println("  📄 Texto de temporada encontrado: $seasonText")
                val match = Regex("(\\d+) Temporadas?").find(seasonText)
                val numSeasons = match?.groupValues?.get(1)?.toIntOrNull()
                if (numSeasons != null && numSeasons > 0) {
                    // Construir links para cada temporada baseado na URL atual
                    val baseWithoutSlash = baseUrl.replace(Regex("/$"), "")
                    for (i in 1..numSeasons) {
                        val seasonUrl = "$baseWithoutSlash-$i/"
                        seasonLinks.add(fixUrl(seasonUrl))
                        println("  📌 Construído (Seletor 3): $seasonUrl")
                    }
                }
            }
        }
        
        println("🔗 [DEBUG] Total de links de temporadas: ${seasonLinks.size}")
        return seasonLinks.distinct()
    }

    private fun extractEpisodesFromSeason(document: org.jsoup.nodes.Document): List<Episode> {
        println("🔍 [DEBUG] Iniciando extração de episódios da temporada")
        val episodes = mutableListOf<Episode>()

        // Na página de temporada, os episódios estão na tabela .TPTblCn
        val episodeRows = document.select(".TPTblCn tbody tr")
        
        if (episodeRows.isNotEmpty()) {
            // Extrair número da temporada do título
            val seasonTitle = document.selectFirst(".SeasonBx .Title")?.text() ?: 
                             document.selectFirst("h1.Title")?.text() ?: "Temporada 1"
            val seasonNumber = Regex("Temporada (\\d+)").find(seasonTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            
            println("📊 [DEBUG] Encontradas ${episodeRows.size} linhas de episódios para temporada $seasonNumber")
            
            for ((index, row) in episodeRows.withIndex()) {
                try {
                    println("  └─ Processando linha #${index + 1}")
                    
                    val numberElement = row.selectFirst("td span.Num")
                    val episodeNumber = numberElement?.text()?.toIntOrNull()
                    
                    if (episodeNumber == null) {
                        println("      ⚠️ Número do episódio não encontrado, pulando")
                        continue
                    }
                    
                    val linkElement = row.selectFirst("td.MvTbImg a[href]") ?: 
                                     row.selectFirst("td.MvTbTtl a[href]")
                    val episodeUrl = linkElement?.attr("href")?.let { fixUrl(it) }
                    
                    if (episodeUrl == null) {
                        println("      ⚠️ Link do episódio $episodeNumber não encontrado, pulando")
                        continue
                    }
                    
                    val titleElement = row.selectFirst("td.MvTbTtl a")
                    val episodeTitle = titleElement?.text()?.trim()
                    
                    val dateElement = row.selectFirst("td.MvTbTtl span")
                    val dateText = dateElement?.text()?.trim()
                    
                    val posterElement = row.selectFirst("td.MvTbImg img")
                    var poster = posterElement?.attr("src")?.let { fixUrl(it) }
                    if (poster == null) {
                        poster = posterElement?.attr("data-src")?.let { fixUrl(it) }
                    }

                    val episode = newEpisode(episodeUrl) {
                        this.name = episodeTitle ?: "Episódio $episodeNumber"
                        this.season = seasonNumber
                        this.episode = episodeNumber
                        this.posterUrl = poster
                        
                        if (dateText != null) {
                            try {
                                val formats = listOf("dd-MM-yyyy", "yyyy-MM-dd", "dd/MM/yyyy")
                                for (format in formats) {
                                    try {
                                        val date = SimpleDateFormat(format).parse(dateText)
                                        this.date = date.time
                                        println("      ✅ Data convertida: $dateText")
                                        break
                                    } catch (e: Exception) {}
                                }
                            } catch (e: Exception) {}
                        }
                    }
                    episodes.add(episode)
                    println("      ✅ Episódio adicionado: S${seasonNumber}E${episodeNumber} - ${episode.name}")
                    
                } catch (e: Exception) {
                    println("      ❌ Erro ao processar linha: ${e.message}")
                }
            }
        } else {
            println("⚠️ [DEBUG] Nenhuma linha de episódio encontrada na tabela")
            
            // Debug: mostrar estrutura da página
            val tables = document.select("table")
            println("📊 [DEBUG] Total de tabelas na página: ${tables.size}")
            tables.forEachIndexed { i, table ->
                println("  Tabela $i: classes = ${table.className()}")
            }
        }

        println("📊 [DEBUG] Total de episódios extraídos: ${episodes.size}")
        return episodes
    }

    private suspend fun searchOnTMDB(query: String, year: Int?, isTv: Boolean): TMDBInfo? {
        return try {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val yearParam = year?.let { "&year=$it" } ?: ""

            val searchUrl = if (isTv) {
                "https://api.themoviedb.org/3/search/tv?api_key=$TMDB_API_KEY&query=$encodedQuery&language=pt-BR$yearParam"
            } else {
                "https://api.themoviedb.org/3/search/movie?api_key=$TMDB_API_KEY&query=$encodedQuery&language=pt-BR$yearParam"
            }

            val headers = mapOf(
                "Authorization" to "Bearer $TMDB_ACCESS_TOKEN",
                "accept" to "application/json"
            )

            val response = app.get(searchUrl, headers = headers, timeout = 10_000)
            if (response.code != 200) return null

            val searchResult = response.parsedSafe<TMDBSearchResponse>() ?: return null
            val result = searchResult.results.firstOrNull() ?: return null

            val details = getTMDBDetails(result.id, isTv) ?: return null

            val allActors = details.credits?.cast?.take(15)?.mapNotNull { actor ->
                if (actor.name.isNotBlank()) {
                    Actor(
                        name = actor.name,
                        image = actor.profile_path?.let { "$tmdbImageUrl/w185$it" }
                    )
                } else null
            }

            val youtubeTrailer = getHighQualityTrailer(details.videos?.results)

            TMDBInfo(
                id = result.id,
                title = if (isTv) result.name else result.title,
                year = if (isTv) {
                    result.first_air_date?.substring(0, 4)?.toIntOrNull()
                } else {
                    result.release_date?.substring(0, 4)?.toIntOrNull()
                },
                posterUrl = result.poster_path?.let { "$tmdbImageUrl/w500$it" },
                backdropUrl = details.backdrop_path?.let { "$tmdbImageUrl/original$it" },
                overview = details.overview,
                genres = details.genres?.map { it.name },
                actors = allActors,
                youtubeTrailer = youtubeTrailer,
                duration = if (!isTv) details.runtime else null
            )
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun getTMDBDetails(id: Int, isTv: Boolean): TMDBDetailsResponse? {
        return try {
            val headers = mapOf(
                "Authorization" to "Bearer $TMDB_ACCESS_TOKEN",
                "accept" to "application/json"
            )

            val url = if (isTv) {
                "https://api.themoviedb.org/3/tv/$id?api_key=$TMDB_API_KEY&language=pt-BR&append_to_response=credits,videos"
            } else {
                "https://api.themoviedb.org/3/movie/$id?api_key=$TMDB_API_KEY&language=pt-BR&append_to_response=credits,videos"
            }

            val response = app.get(url, headers = headers, timeout = 10_000)

            if (response.code != 200) return null
            response.parsedSafe<TMDBDetailsResponse>()
        } catch (e: Exception) {
            null
        }
    }

    private fun getHighQualityTrailer(videos: List<TMDBVideo>?): String? {
        if (videos.isNullOrEmpty()) return null

        return videos.mapNotNull { video ->
            when {
                video.site == "YouTube" && video.type == "Trailer" && video.official == true ->
                    Triple(video.key, 10, "YouTube Trailer Oficial")
                video.site == "YouTube" && video.type == "Trailer" ->
                    Triple(video.key, 9, "YouTube Trailer")
                video.site == "YouTube" && video.type == "Teaser" && video.official == true ->
                    Triple(video.key, 8, "YouTube Teaser Oficial")
                video.site == "YouTube" && video.type == "Teaser" ->
                    Triple(video.key, 7, "YouTube Teaser")
                else -> null
            }
        }
        ?.sortedByDescending { it.second }
        ?.firstOrNull()
        ?.let { (key, _, _) -> "https://www.youtube.com/watch?v=$key" }
    }

    private fun extractPlayerUrl(document: org.jsoup.nodes.Document): String? {
        val playButton = document.selectFirst("a.Button.TPlay[href]")
        if (playButton != null) {
            return playButton.attr("href")
        }

        val iframe = document.selectFirst("iframe[src*='player'], iframe[src*='embed'], iframe[src*='video']")
        if (iframe != null) {
            return iframe.attr("src")
        }

        val videoLink = document.selectFirst("a[href*='.m3u8'], a[href*='.mp4']")
        return videoLink?.attr("href")
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return false
    }

    private data class TMDBInfo(
        val id: Int,
        val title: String?,
        val year: Int?,
        val posterUrl: String?,
        val backdropUrl: String?,
        val overview: String?,
        val genres: List<String>?,
        val actors: List<Actor>?,
        val youtubeTrailer: String?,
        val duration: Int?
    )

    private data class TMDBSearchResponse(
        @JsonProperty("results") val results: List<TMDBResult>
    )

    private data class TMDBResult(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("release_date") val release_date: String? = null,
        @JsonProperty("first_air_date") val first_air_date: String? = null,
        @JsonProperty("poster_path") val poster_path: String?
    )

    private data class TMDBDetailsResponse(
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("backdrop_path") val backdrop_path: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("genres") val genres: List<TMDBGenre>?,
        @JsonProperty("credits") val credits: TMDBCredits?,
        @JsonProperty("videos") val videos: TMDBVideos?
    )

    private data class TMDBGenre(
        @JsonProperty("name") val name: String
    )

    private data class TMDBCredits(
        @JsonProperty("cast") val cast: List<TMDBCast>
    )

    private data class TMDBCast(
        @JsonProperty("name") val name: String,
        @JsonProperty("profile_path") val profile_path: String?
    )

    private data class TMDBVideos(
        @JsonProperty("results") val results: List<TMDBVideo>
    )

    private data class TMDBVideo(
        @JsonProperty("key") val key: String,
        @JsonProperty("site") val site: String,
        @JsonProperty("type") val type: String,
        @JsonProperty("official") val official: Boolean? = false
    )
}
