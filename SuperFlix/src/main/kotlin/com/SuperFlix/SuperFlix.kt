package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import java.text.SimpleDateFormat
import java.net.URLEncoder
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

class SuperFlix : MainAPI() {
    override var mainUrl = "https://superflix21.lol"
    override var name = "SuperFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val usesWebView = true

    // ============ TMDB COM BuildConfig ============
    private val tmdbApiKey = BuildConfig.TMDB_API_KEY
    private val tmdbAccessToken = BuildConfig.TMDB_ACCESS_TOKEN
    private val tmdbBaseUrl = "https://api.themoviedb.org/3"
    private val tmdbImageUrl = "https://image.tmdb.org/t/p"

    override val mainPage = mainPageOf(
        "$mainUrl/lancamentos" to "Lançamentos",
        "$mainUrl/filmes" to "Últimos Filmes",
        "$mainUrl/series" to "Últimas Séries",
        "$mainUrl/animes" to "Últimas Animes"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + if (page > 1) "?page=$page" else ""
        val document = app.get(url).document

        val home = document.select("a.card, div.recs-grid a.rec-card").mapNotNull { element ->
            element.toSearchResult()
        }

        return newHomePageResponse(request.name, home.distinctBy { it.url })
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = attr("title") ?: selectFirst("img")?.attr("alt") ?: return null
        val href = attr("href") ?: return null

        val localPoster = selectFirst("img")?.attr("src")?.let { fixUrl(it) }
        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()

        val badge = selectFirst(".badge-kind")?.text()?.lowercase() ?: ""
        val isAnime = badge.contains("anime") || href.contains("/anime/") ||
                      title.contains("(Anime)", ignoreCase = true)
        val isSerie = badge.contains("série") || badge.contains("serie") ||
                     href.contains("/serie/") ||
                     (!isAnime && (badge.contains("tv") || href.contains("/tv/")))
        val isMovie = !isSerie && !isAnime

        return when {
            isAnime -> {
                newAnimeSearchResponse(cleanTitle, fixUrl(href), TvType.Anime) {
                    this.posterUrl = localPoster
                    this.year = year
                }
            }
            isSerie -> {
                newTvSeriesSearchResponse(cleanTitle, fixUrl(href), TvType.TvSeries) {
                    this.posterUrl = localPoster
                    this.year = year
                }
            }
            else -> {
                newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                    this.posterUrl = localPoster
                    this.year = year
                }
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/buscar?q=${URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document

        return document.select(".grid .card, a.card").mapNotNull { card ->
            try {
                val title = card.attr("title") ?: card.selectFirst("img")?.attr("alt") ?: return@mapNotNull null
                val href = card.attr("href") ?: return@mapNotNull null

                val poster = card.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
                val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()

                val badge = card.selectFirst(".badge-kind")?.text()?.lowercase() ?: ""
                val isAnime = badge.contains("anime") || href.contains("/anime/") ||
                             title.contains("(Anime)", ignoreCase = true)
                val isSerie = badge.contains("série") || badge.contains("serie") ||
                             href.contains("/serie/") ||
                             (!isAnime && (badge.contains("tv") || href.contains("/tv/")))

                when {
                    isAnime -> newAnimeSearchResponse(cleanTitle, fixUrl(href), TvType.Anime) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    isSerie -> newTvSeriesSearchResponse(cleanTitle, fixUrl(href), TvType.TvSeries) {
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
    }

    override suspend fun load(url: String): LoadResponse? {
        println("🔍 [DEBUG] Iniciando load para URL: $url")

        val document = app.get(url).document

        val titleElement = document.selectFirst("h1, .title")
        val title = titleElement?.text() ?: return null
        println("🔍 [DEBUG] Título encontrado no site: $title")

        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
        println("🔍 [DEBUG] Título limpo: $cleanTitle | Ano: $year")

        val isAnime = url.contains("/anime/") || title.contains("(Anime)", ignoreCase = true)
        val isSerie = url.contains("/serie/") || url.contains("/tv/") ||
                     (!isAnime && document.selectFirst(".episode-list, .season-list, .seasons") != null)
        println("🔍 [DEBUG] Tipo: ${if (isAnime) "Anime" else if (isSerie) "Série" else "Filme"}")

        println("🔍 [DEBUG] Buscando no TMDB...")
        val tmdbInfo = if (isAnime || isSerie) {
            searchOnTMDB(cleanTitle, year, true)
        } else {
            searchOnTMDB(cleanTitle, year, false)
        }

        if (tmdbInfo == null) {
            println("⚠️ [DEBUG] TMDB não retornou informações!")
        } else {
            println("✅ [DEBUG] TMDB OK! Título: ${tmdbInfo.title}, Ano: ${tmdbInfo.year}")
            println("✅ [DEBUG] Poster URL: ${tmdbInfo.posterUrl}")
            println("✅ [DEBUG] Backdrop URL: ${tmdbInfo.backdropUrl}")
            println("✅ [DEBUG] Overview: ${tmdbInfo.overview?.take(50)}...")
            println("✅ [DEBUG] Atores: ${tmdbInfo.actors?.size ?: 0}")
            println("✅ [DEBUG] Trailer: ${tmdbInfo.youtubeTrailer}")
            println("✅ [DEBUG] Temporadas/Episódios TMDB: ${tmdbInfo.seasonsEpisodes.size}")
        }

        val siteRecommendations = extractRecommendationsFromSite(document)

        return if (tmdbInfo != null) {
            println("✅ [DEBUG] Criando resposta COM dados do TMDB")
            createLoadResponseWithTMDB(tmdbInfo, url, document, isAnime, isSerie, siteRecommendations)
        } else {
            println("⚠️ [DEBUG] Criando resposta APENAS com dados do site")
            createLoadResponseFromSite(document, url, cleanTitle, year, isAnime, isSerie)
        }
    }

    private suspend fun searchOnTMDB(query: String, year: Int?, isTv: Boolean): TMDBInfo? {
        println("🔍 [TMDB DEBUG] Iniciando busca no TMDB (BuildConfig)")
        println("🔍 [TMDB DEBUG] Query: $query")
        println("🔍 [TMDB DEBUG] API Key configurada: ${tmdbApiKey != "dummy_api_key"}")
        println("🔍 [TMDB DEBUG] Access Token configurado: ${tmdbAccessToken != "dummy_access_token"}")

        // Verificar se as chaves estão configuradas
        if (tmdbApiKey == "dummy_api_key" || tmdbAccessToken == "dummy_access_token") {
            println("⚠️ [TMDB DEBUG] Chaves não configuradas - usando proxy como fallback")
            return searchOnTMDBViaProxy(query, year, isTv)
        }

        return try {
            val type = if (isTv) "tv" else "movie"
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            
            // URL de busca DIRETA com API Key
            var searchUrl = "$tmdbBaseUrl/search/$type?query=$encodedQuery&api_key=$tmdbApiKey&language=pt-BR"
            if (year != null) searchUrl += "&year=$year"
            
            println("🔗 [TMDB DEBUG] URL direta: ${searchUrl.take(100)}...")

            val response = app.get(searchUrl, timeout = 10_000)
            println("📡 [TMDB DEBUG] Status da resposta direta: ${response.code}")

            if (response.code != 200) {
                println("❌ [TMDB DEBUG] Erro na busca direta, tentando proxy...")
                return searchOnTMDBViaProxy(query, year, isTv)
            }

            val searchResult = response.parsedSafe<TMDBSearchResponse>() ?: return null
            println("✅ [TMDB DEBUG] Parsing OK! Resultados: ${searchResult.results.size}")

            val result = searchResult.results.firstOrNull() ?: return null

            // Buscar detalhes completos com Access Token
            val details = getTMDBDetailsDirect(result.id, isTv) ?: return null

            // Extrair atores
            val allActors = details.credits?.cast?.take(15)?.mapNotNull { actor ->
                if (actor.name.isNotBlank()) {
                    Actor(
                        name = actor.name,
                        image = actor.profile_path?.let { "$tmdbImageUrl/w185$it" }
                    )
                } else null
            }

            // Buscar trailer
            val youtubeTrailer = getHighQualityTrailer(details.videos?.results)

            // Buscar temporadas se for série
            val seasonsEpisodes = if (isTv) {
                getTMDBAllSeasonsDirect(result.id)
            } else {
                emptyMap()
            }

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
                duration = if (!isTv) details.runtime else null,
                seasonsEpisodes = seasonsEpisodes
            )
        } catch (e: Exception) {
            println("❌ [TMDB DEBUG] ERRO na busca direta TMDB: ${e.message}")
            searchOnTMDBViaProxy(query, year, isTv)
        }
    }

    // Função de fallback para proxy
    private suspend fun searchOnTMDBViaProxy(query: String, year: Int?, isTv: Boolean): TMDBInfo? {
        println("🔍 [TMDB DEBUG] Usando proxy como fallback")
        val TMDB_PROXY_URL = "https://lawliet.euluan1912.workers.dev"
        
        return try {
            val type = if (isTv) "tv" else "movie"
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val yearParam = year?.let { "&year=$it" } ?: ""

            val searchUrl = "$TMDB_PROXY_URL/search?query=$encodedQuery&type=$type$yearParam"
            println("🔗 [TMDB DEBUG] URL proxy: $searchUrl")

            val response = app.get(searchUrl, timeout = 10_000)
            println("📡 [TMDB DEBUG] Status proxy: ${response.code}")

            if (response.code != 200) return null

            val searchResult = response.parsedSafe<TMDBSearchResponse>() ?: return null
            println("✅ [TMDB DEBUG] Parsing proxy OK!")

            val result = searchResult.results.firstOrNull() ?: return null

            // Buscar detalhes completos via proxy
            val details = getTMDBDetailsViaProxy(result.id, isTv) ?: return null

            // Extrair atores
            val allActors = details.credits?.cast?.take(15)?.mapNotNull { actor ->
                if (actor.name.isNotBlank()) {
                    Actor(
                        name = actor.name,
                        image = actor.profile_path?.let { "$tmdbImageUrl/w185$it" }
                    )
                } else null
            }

            // Buscar trailer
            val youtubeTrailer = getHighQualityTrailer(details.videos?.results)

            // Buscar temporadas se for série
            val seasonsEpisodes = if (isTv) {
                getTMDBAllSeasonsViaProxy(result.id)
            } else {
                emptyMap()
            }

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
                duration = if (!isTv) details.runtime else null,
                seasonsEpisodes = seasonsEpisodes
            )
        } catch (e: Exception) {
            println("❌ [TMDB DEBUG] ERRO no proxy: ${e.message}")
            null
        }
    }

    private suspend fun getTMDBDetailsDirect(id: Int, isTv: Boolean): TMDBDetailsResponse? {
        println("🔍 [TMDB DEBUG] Buscando detalhes DIRETOS para ID $id")
        
        return try {
            val type = if (isTv) "tv" else "movie"
            // Usar Access Token para detalhes
            val url = "$tmdbBaseUrl/$type/$id?append_to_response=credits,videos&language=pt-BR"
            
            val headers = mapOf(
                "Authorization" to "Bearer $tmdbAccessToken",
                "accept" to "application/json"
            )
            
            println("🔗 [TMDB DEBUG] URL detalhes diretos: $url")
            
            val response = app.get(url, headers = headers, timeout = 10_000)
            println("📡 [TMDB DEBUG] Status detalhes diretos: ${response.code}")

            if (response.code != 200) {
                println("❌ [TMDB DEBUG] Erro detalhes diretos: ${response.code}")
                return null
            }

            response.parsedSafe<TMDBDetailsResponse>()
        } catch (e: Exception) {
            println("❌ [TMDB DEBUG] ERRO detalhes diretos: ${e.message}")
            null
        }
    }

    private suspend fun getTMDBAllSeasonsDirect(seriesId: Int): Map<Int, List<TMDBEpisode>> {
        println("🔍 [TMDB DEBUG] Buscando temporadas DIRETAS para série ID: $seriesId")
        
        val seasonsEpisodes = mutableMapOf<Int, List<TMDBEpisode>>()
        
        try {
            // Headers com Access Token
            val headers = mapOf(
                "Authorization" to "Bearer $tmdbAccessToken",
                "accept" to "application/json"
            )
            
            // Primeiro pegar detalhes da série
            val seriesUrl = "$tmdbBaseUrl/tv/$seriesId?language=pt-BR"
            println("🔗 [TMDB DEBUG] URL série direta: $seriesUrl")
            
            val seriesResponse = app.get(seriesUrl, headers = headers, timeout = 10_000)
            println("📡 [TMDB DEBUG] Status série: ${seriesResponse.code}")

            if (seriesResponse.code == 200) {
                val seriesDetails = seriesResponse.parsedSafe<TMDBTVDetailsResponse>()
                seriesDetails?.seasons?.forEach { season ->
                    if (season.season_number > 0) {
                        val seasonNumber = season.season_number
                        println("🔍 [TMDB DEBUG] Buscando temporada direta $seasonNumber...")

                        val seasonUrl = "$tmdbBaseUrl/tv/$seriesId/season/$seasonNumber?language=pt-BR"
                        val seasonResponse = app.get(seasonUrl, headers = headers, timeout = 10_000)
                        
                        if (seasonResponse.code == 200) {
                            val seasonData = seasonResponse.parsedSafe<TMDBSeasonResponse>()
                            seasonData?.episodes?.let { episodes ->
                                seasonsEpisodes[seasonNumber] = episodes
                                println("✅ [TMDB DEBUG] Temporada direta $seasonNumber: ${episodes.size} episódios")
                            }
                        } else {
                            println("❌ [TMDB DEBUG] Falha temporada direta $seasonNumber")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("❌ [TMDB DEBUG] ERRO temporadas diretas: ${e.message}")
        }
        
        return seasonsEpisodes
    }

    private suspend fun getTMDBDetailsViaProxy(id: Int, isTv: Boolean): TMDBDetailsResponse? {
        val TMDB_PROXY_URL = "https://lawliet.euluan1912.workers.dev"
        
        println("🔍 [TMDB DEBUG] Buscando detalhes via proxy para ID $id")

        return try {
            val type = if (isTv) "tv" else "movie"
            val url = "$TMDB_PROXY_URL/$type/$id"
            println("🔗 [TMDB DEBUG] URL detalhes proxy: $url")

            val response = app.get(url, timeout = 10_000)
            println("📡 [TMDB DEBUG] Status proxy: ${response.code}")

            if (response.code != 200) return null

            response.parsedSafe<TMDBDetailsResponse>()
        } catch (e: Exception) {
            println("❌ [TMDB DEBUG] ERRO detalhes proxy: ${e.message}")
            null
        }
    }

    private suspend fun getTMDBAllSeasonsViaProxy(seriesId: Int): Map<Int, List<TMDBEpisode>> {
        val TMDB_PROXY_URL = "https://lawliet.euluan1912.workers.dev"
        
        println("🔍 [TMDB DEBUG] Buscando temporadas via proxy para série ID: $seriesId")

        return try {
            // Primeiro, pegar detalhes da série
            val seriesDetailsUrl = "$TMDB_PROXY_URL/tv/$seriesId"
            println("🔗 [TMDB DEBUG] URL série proxy: $seriesDetailsUrl")

            val seriesResponse = app.get(seriesDetailsUrl, timeout = 10_000)
            println("📡 [TMDB DEBUG] Status série proxy: ${seriesResponse.code}")

            if (seriesResponse.code != 200) {
                println("❌ [TMDB DEBUG] Erro HTTP série proxy: ${seriesResponse.code}")
                return emptyMap()
            }

            val seriesDetails = seriesResponse.parsedSafe<TMDBTVDetailsResponse>() ?: return emptyMap()

            println("✅ [TMDB DEBUG] Série proxy OK! Total temporadas: ${seriesDetails.seasons.size}")

            val seasonsEpisodes = mutableMapOf<Int, List<TMDBEpisode>>()

            // Agora buscar cada temporada individualmente
            for (season in seriesDetails.seasons) {
                if (season.season_number > 0) {
                    val seasonNumber = season.season_number
                    println("🔍 [TMDB DEBUG] Buscando temporada proxy $seasonNumber...")

                    val seasonUrl = "$TMDB_PROXY_URL/tv/$seriesId/season/$seasonNumber"
                    println("🔗 [TMDB DEBUG] URL temporada proxy: $seasonUrl")

                    val seasonResponse = app.get(seasonUrl, timeout = 10_000)
                    println("📡 [TMDB DEBUG] Status temporada proxy: ${seasonResponse.code}")

                    if (seasonResponse.code == 200) {
                        val seasonData = seasonResponse.parsedSafe<TMDBSeasonResponse>()
                        seasonData?.episodes?.let { episodes ->
                            seasonsEpisodes[seasonNumber] = episodes
                            println("✅ [TMDB DEBUG] Temporada proxy $seasonNumber: ${episodes.size} episódios")
                        }
                    } else {
                        println("❌ [TMDB DEBUG] Falha temporada proxy $seasonNumber")
                    }
                }
            }

            println("✅ [TMDB DEBUG] Total temporadas proxy com dados: ${seasonsEpisodes.size}")
            seasonsEpisodes
        } catch (e: Exception) {
            println("❌ [TMDB DEBUG] ERRO temporadas proxy: ${e.message}")
            emptyMap()
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

    private suspend fun extractEpisodesFromSite(
        document: org.jsoup.nodes.Document,
        url: String,
        isAnime: Boolean,
        isSerie: Boolean
    ): List<Episode> {
        println("🔍 [SITE DEBUG] Extraindo episódios do site")
        val episodes = mutableListOf<Episode>()

        val episodeElements = document.select("button.bd-play[data-url], a.episode-card, .episode-item, .episode-link, [class*='episode']")
        println("🔍 [SITE DEBUG] Elementos de episódio encontrados: ${episodeElements.size}")

        if (episodeElements.isNotEmpty()) {
            episodeElements.forEachIndexed { index, element ->
                try {
                    val dataUrl = element.attr("data-url") ?: element.attr("href") ?: ""
                    if (dataUrl.isBlank()) {
                        println("⚠️ [SITE DEBUG] Elemento $index sem data-url/href")
                        return@forEachIndexed
                    }

                    val epNumber = extractEpisodeNumber(element, index + 1)
                    val seasonNumber = element.attr("data-season").toIntOrNull() ?: 1

                    val episode = newEpisode(fixUrl(dataUrl)) {
                        this.name = "Episódio $epNumber"
                        this.season = seasonNumber
                        this.episode = epNumber

                        element.selectFirst(".ep-desc, .description")?.text()?.trim()?.let { desc ->
                            if (desc.isNotBlank()) {
                                this.description = desc
                            }
                        }
                    }

                    episodes.add(episode)
                } catch (e: Exception) {
                    println("❌ [SITE DEBUG] Erro ao processar episódio $index: ${e.message}")
                }
            }
        }

        println("✅ [SITE DEBUG] Total de episódios extraídos do site: ${episodes.size}")
        return episodes
    }

    private fun extractRecommendationsFromSite(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val recommendations = document.select(".recs-grid .rec-card, .recs-grid a").mapNotNull { element ->
            try {
                val href = element.attr("href") ?: return@mapNotNull null
                if (href.isBlank() || href == "#") return@mapNotNull null

                val imgElement = element.selectFirst("img")
                val title = imgElement?.attr("alt") ?:
                           element.selectFirst(".rec-title")?.text() ?:
                           element.attr("title") ?:
                           return@mapNotNull null

                val poster = imgElement?.attr("src")?.let { fixUrl(it) }
                val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
                val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()

                val isAnime = href.contains("/anime/") || title.contains("(Anime)", ignoreCase = true)
                val isSerie = href.contains("/serie/") || href.contains("/tv/")
                val isMovie = !isSerie && !isAnime

                when {
                    isAnime -> newAnimeSearchResponse(cleanTitle, fixUrl(href), TvType.Anime) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    isSerie -> newTvSeriesSearchResponse(cleanTitle, fixUrl(href), TvType.TvSeries) {
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

        println("🔍 [DEBUG] Recomendações encontradas no site: ${recommendations.size}")
        return recommendations
    }

    private suspend fun createLoadResponseFromSite(
        document: org.jsoup.nodes.Document,
        url: String,
        title: String,
        year: Int?,
        isAnime: Boolean,
        isSerie: Boolean
    ): LoadResponse {
        println("🏗️ [DEBUG] Criando resposta APENAS com dados do site")

        val ogImage = document.selectFirst("meta[property='og:image']")?.attr("content")
        val poster = ogImage?.let { fixUrl(it) }
        println("🏗️ [DEBUG] Poster do site: $poster")

        val description = document.selectFirst("meta[name='description']")?.attr("content")
        val synopsis = document.selectFirst(".syn, .description, .sinopse, .plot")?.text()
        val plot = description ?: synopsis
        println("🏗️ [DEBUG] Plot do site: ${plot?.take(50)}...")

        val tags = document.select("a.chip, .chip, .genre, .tags").map { it.text() }
            .takeIf { it.isNotEmpty() }?.toList()
        println("🏗️ [DEBUG] Tags do site: $tags")

        val siteRecommendations = extractRecommendationsFromSite(document)

        return if (isAnime || isSerie) {
            println("🏗️ [DEBUG] Criando série/Anime (apenas site)")
            val type = if (isAnime) TvType.Anime else TvType.TvSeries

            // Extrair episódios do site
            val episodes = extractEpisodesFromSite(document, url, isAnime, isSerie)

            newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
                println("🏗️ [DEBUG] Série criada com ${episodes.size} episódios")
            }
        } else {
            println("🏗️ [DEBUG] Criando filme (apenas site)")
            val playerUrl = findPlayerUrl(document)
            println("🏗️ [DEBUG] Player URL: $playerUrl")

            newMovieLoadResponse(title, url, TvType.Movie, playerUrl ?: url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
            }
        }
    }

    private suspend fun createLoadResponseWithTMDB(
        tmdbInfo: TMDBInfo,
        url: String,
        document: org.jsoup.nodes.Document,
        isAnime: Boolean,
        isSerie: Boolean,
        siteRecommendations: List<SearchResponse>
    ): LoadResponse {
        println("🏗️ [DEBUG] Criando resposta com dados TMDB")

        return if (isAnime || isSerie) {
            println("🏗️ [DEBUG] Criando série/Anime")

            // AGORA COM DADOS DO TMDB!
            val episodes = extractEpisodesWithTMDBInfo(
                document = document,
                url = url,
                tmdbInfo = tmdbInfo,
                isAnime = isAnime,
                isSerie = isSerie
            )

            println("🏗️ [DEBUG] Total de episódios: ${episodes.size}")
            val type = if (isAnime) TvType.Anime else TvType.TvSeries

            newTvSeriesLoadResponse(
                name = tmdbInfo.title ?: "",
                url = url,
                type = type,
                episodes = episodes
            ) {
                this.posterUrl = tmdbInfo.posterUrl
                this.backgroundPosterUrl = tmdbInfo.backdropUrl
                this.year = tmdbInfo.year
                this.plot = tmdbInfo.overview
                this.tags = tmdbInfo.genres

                tmdbInfo.actors?.let { actors ->
                    println("🏗️ [DEBUG] Adicionando ${actors.size} atores")
                    addActors(actors)
                }

                tmdbInfo.youtubeTrailer?.let { trailerUrl ->
                    println("🏗️ [DEBUG] Adicionando trailer: $trailerUrl")
                    addTrailer(trailerUrl)
                }

                this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
                println("🏗️ [DEBUG] Recomendações: ${siteRecommendations.size}")
            }
        } else {
            println("🏗️ [DEBUG] Criando filme com dados TMDB")
            val playerUrl = findPlayerUrl(document)
            println("🏗️ [DEBUG] Player URL: $playerUrl")

            newMovieLoadResponse(
                name = tmdbInfo.title ?: "",
                url = url,
                type = TvType.Movie,
                dataUrl = playerUrl ?: url
            ) {
                this.posterUrl = tmdbInfo.posterUrl
                this.backgroundPosterUrl = tmdbInfo.backdropUrl
                this.year = tmdbInfo.year
                this.plot = tmdbInfo.overview
                this.tags = tmdbInfo.genres
                this.duration = tmdbInfo.duration

                tmdbInfo.actors?.let { actors ->
                    println("🏗️ [DEBUG] Adicionando ${actors.size} atores")
                    addActors(actors)
                }

                tmdbInfo.youtubeTrailer?.let { trailerUrl ->
                    println("🏗️ [DEBUG] Adicionando trailer: $trailerUrl")
                    addTrailer(trailerUrl)
                }

                this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
                println("🏗️ [DEBUG] Recomendações: ${siteRecommendations.size}")
            }
        }
    }

    private suspend fun extractEpisodesWithTMDBInfo(
        document: org.jsoup.nodes.Document,
        url: String,
        tmdbInfo: TMDBInfo?,
        isAnime: Boolean,
        isSerie: Boolean = false
    ): List<Episode> {
        println("🔍 [DEBUG] Extraindo episódios da URL: $url")
        val episodes = mutableListOf<Episode>()

        val episodeElements = document.select("button.bd-play[data-url], a.episode-card, .episode-item, .episode-link, [class*='episode']")
        println("🔍 [DEBUG] Elementos de episódio encontrados: ${episodeElements.size}")

        if (episodeElements.isNotEmpty()) {
            episodeElements.forEachIndexed { index, element ->
                try {
                    val dataUrl = element.attr("data-url") ?: element.attr("href") ?: ""
                    if (dataUrl.isBlank()) return@forEachIndexed

                    val epNumber = extractEpisodeNumber(element, index + 1)
                    val seasonNumber = element.attr("data-season").toIntOrNull() ?: 1

                    // AGORA COM DADOS DO TMDB!
                    val tmdbEpisode = findTMDBEpisode(tmdbInfo, seasonNumber, epNumber)

                    val episode = if (tmdbEpisode != null) {
                        // Episódio com dados do TMDB - ADICIONANDO DURAÇÃO "-min" NA SINOPSE
                        val descriptionWithDuration = buildDescriptionWithDuration(
                            tmdbEpisode.overview,
                            tmdbEpisode.runtime
                        )

                        newEpisode(fixUrl(dataUrl)) {
                            this.name = tmdbEpisode.name ?: "Episódio $epNumber"
                            this.season = seasonNumber
                            this.episode = epNumber
                            this.posterUrl = tmdbEpisode.still_path?.let { "$tmdbImageUrl/w300$it" }
                            this.description = descriptionWithDuration

                            tmdbEpisode.air_date?.let { airDate ->
                                try {
                                    val dateFormatter = SimpleDateFormat("yyyy-MM-dd")
                                    val date = dateFormatter.parse(airDate)
                                    this.date = date.time
                                } catch (e: Exception) {}
                            }
                        }
                    } else {
                        // Episódio sem dados do TMDB
                        newEpisode(fixUrl(dataUrl)) {
                            this.name = "Episódio $epNumber"
                            this.season = seasonNumber
                            this.episode = epNumber
                        }
                    }

                    episodes.add(episode)
                } catch (e: Exception) {
                    println("❌ [DEBUG] Erro episódio $index: ${e.message}")
                }
            }
        }

        println("✅ [DEBUG] Total de episódios extraídos: ${episodes.size}")
        return episodes
    }

    // NOVA FUNÇÃO: Adiciona "-
