package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import java.text.SimpleDateFormat

class SuperFlix : MainAPI() {
    override var mainUrl = "https://superflix21.lol"
    override var name = "SuperFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val usesWebView = true

    // ============ APIS ============
    private val TMDB_PROXY_URL = "https://lawliet.euluan1912.workers.dev"
    private val tmdbImageUrl = "https://image.tmdb.org/t/p"
    private val ANILIST_API_URL = "https://graphql.anilist.co"

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
        val searchUrl = "$mainUrl/buscar?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
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

        // ============ ATORES APENAS DO TMDB/ANILIST ============
        println("🔍 [DEBUG] Buscando atores/personagens...")
        val actorsList = if (isAnime) {
            // Para animes: buscar personagens e seiyuus do AniList
            getAnimeCharactersFromAniList(cleanTitle)
        } else {
            // Para filmes/séries: buscar atores do TMDB
            tmdbInfo?.actors ?: emptyList()
        }
        
        println("✅ [DEBUG] Total atores/personagens encontrados: ${actorsList.size}")

        if (tmdbInfo == null) {
            println("⚠️ [DEBUG] TMDB não retornou informações!")
        } else {
            println("✅ [DEBUG] TMDB OK! Título: ${tmdbInfo.title}, Ano: ${tmdbInfo.year}")
        }

        val siteRecommendations = extractRecommendationsFromSite(document)

        println("✅ [DEBUG] Criando resposta final...")
        return if (tmdbInfo != null || actorsList.isNotEmpty()) {
            createLoadResponseWithExternalData(
                tmdbInfo = tmdbInfo,
                actorsList = actorsList,
                document = document,
                url = url,
                cleanTitle = cleanTitle,
                year = year,
                isAnime = isAnime,
                isSerie = isSerie,
                siteRecommendations = siteRecommendations
            )
        } else {
            createLoadResponseFromSiteOnly(
                document = document,
                url = url,
                cleanTitle = cleanTitle,
                year = year,
                isAnime = isAnime,
                isSerie = isSerie
            )
        }
    }

    // ============ BUSCAR ATORES DO TMDB ============
    private suspend fun searchOnTMDB(query: String, year: Int?, isTv: Boolean): TMDBInfo? {
        println("🔍 [TMDB DEBUG] Iniciando busca no TMDB")
        println("🔍 [TMDB DEBUG] Query: $query")
        println("🔍 [TMDB DEBUG] Ano: $year")
        println("🔍 [TMDB DEBUG] Tipo: ${if (isTv) "TV" else "Movie"}")

        return try {
            val type = if (isTv) "tv" else "movie"
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val yearParam = year?.let { "&year=$it" } ?: ""

            val searchUrl = "$TMDB_PROXY_URL/search?query=$encodedQuery&type=$type$yearParam"
            println("🔗 [TMDB DEBUG] URL da busca: $searchUrl")

            val response = app.get(searchUrl, timeout = 10_000)
            println("📡 [TMDB DEBUG] Status da resposta: ${response.code}")

            if (response.code != 200) return null

            val searchResult = response.parsedSafe<TMDBSearchResponse>() ?: return null
            println("✅ [TMDB DEBUG] Parsing OK! Resultados: ${searchResult.results.size}")

            val result = searchResult.results.firstOrNull() ?: return null

            // Buscar detalhes completos
            val details = getTMDBDetails(result.id, isTv) ?: return null

            // ============ ATORES DO TMDB ============
            val allActors = details.credits?.cast?.take(15)?.mapNotNull { actor ->
                if (actor.name.isNotBlank()) {
                    // Para filmes/séries: nome do ator e personagem como descrição
                    Actor(
                        name = actor.name,
                        image = actor.profile_path?.let { "$tmdbImageUrl/w185$it" }
                    )
                } else null
            }
            println("✅ [TMDB DEBUG] Atores encontrados: ${allActors?.size ?: 0}")

            // Buscar trailer
            val youtubeTrailer = getHighQualityTrailer(details.videos?.results)

            // Buscar temporadas e episódios COM RUNTIME
            val seasonsEpisodes = if (isTv) {
                println("🔍 [TMDB DEBUG] Buscando temporadas com runtime...")
                getTMDBAllSeasonsWithRuntime(result.id)
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
            println("❌ [TMDB DEBUG] ERRO na busca do TMDB: ${e.message}")
            null
        }
    }

    // ============ Buscar temporadas COM RUNTIME ============
    private suspend fun getTMDBAllSeasonsWithRuntime(seriesId: Int): Map<Int, List<TMDBEpisode>> {
        println("🔍 [TMDB DEBUG] Buscando temporadas com runtime para série ID: $seriesId")

        return try {
            val seriesDetailsUrl = "$TMDB_PROXY_URL/tv/$seriesId"
            println("🔗 [TMDB DEBUG] URL detalhes série: $seriesDetailsUrl")

            val seriesResponse = app.get(seriesDetailsUrl, timeout = 10_000)
            println("📡 [TMDB DEBUG] Status da resposta: ${seriesResponse.code}")

            if (seriesResponse.code != 200) return emptyMap()

            val seriesDetails = seriesResponse.parsedSafe<TMDBTVDetailsResponse>() ?: return emptyMap()
            println("✅ [TMDB DEBUG] Série OK! Total temporadas: ${seriesDetails.seasons.size}")

            val seasonsEpisodes = mutableMapOf<Int, List<TMDBEpisode>>()

            for (season in seriesDetails.seasons) {
                if (season.season_number > 0) {
                    val seasonNumber = season.season_number
                    println("🔍 [TMDB DEBUG] Buscando temporada $seasonNumber...")

                    val seasonUrl = "$TMDB_PROXY_URL/tv/$seriesId/season/$seasonNumber"
                    val seasonResponse = app.get(seasonUrl, timeout = 10_000)

                    if (seasonResponse.code == 200) {
                        val seasonData = seasonResponse.parsedSafe<TMDBSeasonResponse>()
                        seasonData?.episodes?.let { episodes ->
                            seasonsEpisodes[seasonNumber] = episodes
                            println("✅ [TMDB DEBUG] Temporada $seasonNumber: ${episodes.size} episódios")
                            // Log do runtime dos primeiros episódios
                            episodes.take(3).forEach { ep ->
                                println("📊 [TMDB DEBUG] Ep ${ep.episode_number}: ${ep.runtime} min")
                            }
                        }
                    }
                }
            }

            println("✅ [TMDB DEBUG] Total temporadas com dados: ${seasonsEpisodes.size}")
            seasonsEpisodes
        } catch (e: Exception) {
            println("❌ [TMDB DEBUG] ERRO ao buscar temporadas: ${e.message}")
            emptyMap()
        }
    }

    // ============ BUSCAR PERSONAGENS DO ANILIST (COM SEIYUU) ============
    private suspend fun getAnimeCharactersFromAniList(title: String): List<Actor> {
        println("🔍 [ANILIST DEBUG] Buscando personagens para: $title")
        
        return try {
            // Primeiro buscar o anime no AniList
            val searchQuery = """
                query(${"$"}search: String) {
                    Page(page: 1, perPage: 1) {
                        media(search: ${"$"}search, type: ANIME) {
                            id
                            idMal
                            title {
                                romaji
                                english
                                native
                            }
                        }
                    }
                }
            """.trimIndent()

            val searchVariables = mapOf("search" to title)
            val searchBody = mapOf(
                "query" to searchQuery,
                "variables" to searchVariables
            )

            val searchResponse = app.post(
                ANILIST_API_URL,
                data = searchBody,
                headers = mapOf("Content-Type" to "application/json"),
                timeout = 10_000
            ).parsedSafe<AniListSearchResponse>()

            val animeId = searchResponse?.data?.Page?.media?.firstOrNull()?.id
            if (animeId == null) {
                println("❌ [ANILIST DEBUG] Anime não encontrado: $title")
                return emptyList()
            }

            println("✅ [ANILIST DEBUG] Anime encontrado! ID: $animeId")

            // Agora buscar personagens
            val charactersQuery = """
                query(${"$"}id: Int) {
                    Media(id: ${"$"}id) {
                        characters(role: MAIN, sort: ROLE, perPage: 15) {
                            edges {
                                node {
                                    name {
                                        full
                                    }
                                    image {
                                        large
                                    }
                                }
                                voiceActors(language: JAPANESE) {
                                    name {
                                        full
                                    }
                                    image {
                                        large
                                    }
                                }
                            }
                        }
                    }
                }
            """.trimIndent()

            val charactersVariables = mapOf("id" to animeId)
            val charactersBody = mapOf(
                "query" to charactersQuery,
                "variables" to charactersVariables
            )

            val charactersResponse = app.post(
                ANILIST_API_URL,
                data = charactersBody,
                headers = mapOf("Content-Type" to "application/json"),
                timeout = 10_000
            ).parsedSafe<AniListCharactersResponse>()

            val characters = charactersResponse?.data?.Media?.characters?.edges?.mapNotNull { edge ->
                val character = edge.node
                val voiceActor = edge.voiceActors?.firstOrNull()
                
                // Para animes: personagem como nome, seiyuu como "nome adicional"
                Actor(
                    name = character.name.full,
                    image = character.image?.large ?: voiceActor?.image?.large
                )
            } ?: emptyList()

            println("✅ [ANILIST DEBUG] Personagens encontrados: ${characters.size}")
            characters
        } catch (e: Exception) {
            println("❌ [ANILIST DEBUG] ERRO: ${e.message}")
            emptyList()
        }
    }

    private suspend fun getTMDBDetails(id: Int, isTv: Boolean): TMDBDetailsResponse? {
        println("🔍 [TMDB DEBUG] Buscando detalhes para ID $id")

        return try {
            val type = if (isTv) "tv" else "movie"
            val url = "$TMDB_PROXY_URL/$type/$id"
            println("🔗 [TMDB DEBUG] URL detalhes: $url")

            val response = app.get(url, timeout = 10_000)
            println("📡 [TMDB DEBUG] Status: ${response.code}")

            if (response.code != 200) return null

            response.parsedSafe<TMDBDetailsResponse>()
        } catch (e: Exception) {
            println("❌ [TMDB DEBUG] ERRO detalhes: ${e.message}")
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

    // ============ FUNÇÕES AUXILIARES ============
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

    private suspend fun createLoadResponseWithExternalData(
        tmdbInfo: TMDBInfo?,
        actorsList: List<Actor>,
        document: org.jsoup.nodes.Document,
        url: String,
        cleanTitle: String,
        year: Int?,
        isAnime: Boolean,
        isSerie: Boolean,
        siteRecommendations: List<SearchResponse>
    ): LoadResponse {
        println("🏗️ [DEBUG] Criando resposta com dados externos (TMDB/AniList)")

        return if (isAnime || isSerie) {
            println("🏗️ [DEBUG] Criando série/Anime com dados externos")

            // Extrair episódios COM DADOS DO TMDB (runtime, título, descrição, data)
            val episodes = extractEpisodesWithTMDBInfo(
                document = document,
                url = url,
                tmdbInfo = tmdbInfo,
                isAnime = isAnime
            )

            println("🏗️ [DEBUG] Total de episódios extraídos: ${episodes.size}")
            val type = if (isAnime) TvType.Anime else TvType.TvSeries

            newTvSeriesLoadResponse(
                name = tmdbInfo?.title ?: cleanTitle,
                url = url,
                type = type,
                episodes = episodes
            ) {
                this.posterUrl = tmdbInfo?.posterUrl
                this.backgroundPosterUrl = tmdbInfo?.backdropUrl
                this.year = tmdbInfo?.year ?: year
                this.plot = tmdbInfo?.overview
                this.tags = tmdbInfo?.genres

                // ============ ADICIONAR ATORES/PERSONAGENS ============
                if (actorsList.isNotEmpty()) {
                    println("🏗️ [DEBUG] Adicionando ${actorsList.size} atores/personagens")
                    addActors(actorsList) // CORRIGIDO: addActors ao invés de addActor
                }

                tmdbInfo?.youtubeTrailer?.let { trailerUrl ->
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
                name = tmdbInfo?.title ?: cleanTitle,
                url = url,
                type = TvType.Movie,
                dataUrl = playerUrl ?: url
            ) {
                this.posterUrl = tmdbInfo?.posterUrl
                this.backgroundPosterUrl = tmdbInfo?.backdropUrl
                this.year = tmdbInfo?.year ?: year
                this.plot = tmdbInfo?.overview
                this.tags = tmdbInfo?.genres
                this.duration = tmdbInfo?.duration

                // ============ ADICIONAR ATORES ============
                if (actorsList.isNotEmpty()) {
                    println("🏗️ [DEBUG] Adicionando ${actorsList.size} atores")
                    addActors(actorsList) // CORRIGIDO: addActors ao invés de addActor
                }

                tmdbInfo?.youtubeTrailer?.let { trailerUrl ->
                    println("🏗️ [DEBUG] Adicionando trailer: $trailerUrl")
                    addTrailer(trailerUrl)
                }

                this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
                println("🏗️ [DEBUG] Recomendações: ${siteRecommendations.size}")
            }
        }
    }

    // ============ FUNÇÃO MELHORADA: Episódios COM RUNTIME ============
    private suspend fun extractEpisodesWithTMDBInfo(
        document: org.jsoup.nodes.Document,
        url: String,
        tmdbInfo: TMDBInfo?,
        isAnime: Boolean
    ): List<Episode> {
        println("🔍 [DEBUG] Extraindo episódios COM RUNTIME do TMDB")
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

                    // Buscar dados do episódio no TMDB
                    val tmdbEpisode = findTMDBEpisode(tmdbInfo, seasonNumber, epNumber)

                    val episode = if (tmdbEpisode != null) {
                        // ============ EPISÓDIO COM DADOS COMPLETOS DO TMDB ============
                        // Criar descrição com runtime
                        val descriptionBuilder = StringBuilder()
                        tmdbEpisode.overview?.let { desc ->
                            descriptionBuilder.append(desc)
                        }
                        
                        // Adicionar runtime se disponível
                        tmdbEpisode.runtime?.let { runtime ->
                            if (runtime > 0) {
                                if (descriptionBuilder.isNotEmpty()) {
                                    descriptionBuilder.append("\n\n")
                                }
                                descriptionBuilder.append("Duração: ${runtime} min")
                            }
                        }

                        newEpisode(fixUrl(dataUrl)) {
                            this.name = tmdbEpisode.name ?: "Episódio $epNumber"
                            this.season = seasonNumber
                            this.episode = epNumber
                            this.posterUrl = tmdbEpisode.still_path?.let { "$tmdbImageUrl/w300$it" }
                            this.description = descriptionBuilder.toString().takeIf { it.isNotEmpty() }

                            // Adicionar data de lançamento
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
                    
                    // Log dos detalhes
                    println("📊 [DEBUG] Ep $epNumber (T${seasonNumber}): ${tmdbEpisode?.name ?: "Sem dados TMDB"} - ${tmdbEpisode?.runtime ?: "?"} min")
                    
                } catch (e: Exception) {
                    println("❌ [DEBUG] Erro episódio $index: ${e.message}")
                }
            }
        }

        println("✅ [DEBUG] Total de episódios extraídos: ${episodes.size}")
        return episodes
    }

    private fun findTMDBEpisode(tmdbInfo: TMDBInfo?, season: Int, episode: Int): TMDBEpisode? {
        if (tmdbInfo == null) return null

        val episodes = tmdbInfo.seasonsEpisodes[season]
        if (episodes == null) {
            println("⚠️ [DEBUG] Temporada $season não encontrada no TMDB")
            return null
        }

        return episodes.find { it.episode_number == episode }
    }

    private suspend fun createLoadResponseFromSiteOnly(
        document: org.jsoup.nodes.Document,
        url: String,
        cleanTitle: String,
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

        val tags = document.select("a.chip, .chip, .genre, .tags").map { it.text() }
            .takeIf { it.isNotEmpty() }?.toList()

        if (isAnime || isSerie) {
            println("🏗️ [DEBUG] Criando série/Anime (apenas site)")
            val type = if (isAnime) TvType.Anime else TvType.TvSeries

            val episodes = extractEpisodesFromSiteOnly(document, url)

            return newTvSeriesLoadResponse(cleanTitle, url, type, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                println("🏗️ [DEBUG] Série criada com ${episodes.size} episódios")
            }
        } else {
            println("🏗️ [DEBUG] Criando filme (apenas site)")
            val playerUrl = findPlayerUrl(document)
            println("🏗️ [DEBUG] Player URL: $playerUrl")

            return newMovieLoadResponse(cleanTitle, url, TvType.Movie, playerUrl ?: url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
            }
        }
    }

    private suspend fun extractEpisodesFromSiteOnly(
        document: org.jsoup.nodes.Document,
        url: String
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()

        val episodeElements = document.select("button.bd-play[data-url], a.episode-card, .episode-item, .episode-link")
        
        if (episodeElements.isNotEmpty()) {
            episodeElements.forEachIndexed { index, element ->
                try {
                    val dataUrl = element.attr("data-url") ?: element.attr("href") ?: ""
                    if (dataUrl.isBlank()) return@forEachIndexed

                    val epNumber = extractEpisodeNumber(element, index + 1)
                    val seasonNumber = element.attr("data-season").toIntOrNull() ?: 1

                    episodes.add(newEpisode(fixUrl(dataUrl)) {
                        this.name = "Episódio $epNumber"
                        this.season = seasonNumber
                        this.episode = epNumber
                    })
                } catch (e: Exception) {
                    // Ignorar erros
                }
            }
        }

        return episodes
    }

    private fun extractEpisodeNumber(element: Element, default: Int): Int {
        return element.attr("data-ep").toIntOrNull() ?:
               element.selectFirst(".ep-number, .number, .episode-number")?.text()?.toIntOrNull() ?:
               Regex("Ep\\.?\\s*(\\d+)").find(element.text())?.groupValues?.get(1)?.toIntOrNull() ?:
               Regex("Epis[oó]dio\\s*(\\d+)").find(element.text())?.groupValues?.get(1)?.toIntOrNull() ?:
               default
    }

    private fun findPlayerUrl(document: org.jsoup.nodes.Document): String? {
        val playButton = document.selectFirst("button.bd-play[data-url]")
        if (playButton != null) {
            val url = playButton.attr("data-url")
            println("🔍 [DEBUG] Player URL encontrado no botão: $url")
            return url
        }

        val iframe = document.selectFirst("iframe[src*='fembed'], iframe[src*='filemoon'], iframe[src*='player'], iframe[src*='embed']")
        if (iframe != null) {
            val url = iframe.attr("src")
            println("🔍 [DEBUG] Player URL encontrado no iframe: $url")
            return url
        }

        val videoLink = document.selectFirst("a[href*='.m3u8'], a[href*='.mp4'], a[href*='watch']")
        return videoLink?.attr("href")
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return SuperFlixExtractor.extractVideoLinks(data, mainUrl, name, callback)
    }

    // ============ CLASSES DE DADOS ============

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
        val duration: Int?,
        val seasonsEpisodes: Map<Int, List<TMDBEpisode>> = emptyMap()
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

    private data class TMDBTVDetailsResponse(
        @JsonProperty("seasons") val seasons: List<TMDBSeasonInfo>
    )

    private data class TMDBSeasonInfo(
        @JsonProperty("season_number") val season_number: Int,
        @JsonProperty("episode_count") val episode_count: Int
