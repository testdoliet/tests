package com.AnimeFire

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import kotlinx.coroutines.delay

class AnimeFire : MainAPI() {
    override var mainUrl = "https://animefire.io"
    override var name = "AnimeFire"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)
    override val usesWebView = true

    companion object {
        private const val SEARCH_PATH = "/pesquisar"
        private const val MAX_TRIES = 3
        private const val RETRY_DELAY = 1000L
        private const val TMDB_PROXY_URL = "https://lawliet.euluan1912.workers.dev"
        private const val tmdbImageUrl = "https://image.tmdb.org/t/p"
        
        // Configuração de paginação
        private const val EPISODES_PER_PAGE = 20 // CloudStream carrega 20 em 20
    }

    override val mainPage = mainPageOf(
        "$mainUrl" to "Lançamentos",
        "$mainUrl" to "Destaques da Semana",
        "$mainUrl" to "Últimos Animes Adicionados",
        "$mainUrl" to "Últimos Episódios Adicionados"
    )

    private fun Element.toSearchResponse(): AnimeSearchResponse? {
        val href = attr("href") ?: return null
        if (href.isBlank()) return null
        
        val titleElement = selectFirst("h3.animeTitle") ?: return null
        val title = titleElement.text().trim()
        
        val imgElement = selectFirst("img.imgAnimes, img.owl-lazy, img[src*='animes']")
        val poster = when {
            imgElement?.hasAttr("data-src") == true -> imgElement.attr("data-src")
            imgElement?.hasAttr("src") == true -> imgElement.attr("src")
            else -> selectFirst("img:not([src*='logo']):not([src*='Logo'])")?.attr("src")
        } ?: return null
        
        if (poster.contains("logo", ignoreCase = true)) return null
        
        val cleanTitle = title.replace(Regex("(?i)(dublado|legendado|todos os episódios|\\(\\d{4}\\))$"), "").trim()
        val isMovie = href.contains("/filmes/") || title.contains("Movie", ignoreCase = true)
        
        return newAnimeSearchResponse(cleanTitle, fixUrl(href)) {
            this.posterUrl = fixUrl(poster)
            this.type = if (isMovie) TvType.Movie else TvType.Anime
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        
        val homeItems = when (request.name) {
            "Lançamentos" -> 
                document.select(".owl-carousel-home .divArticleLancamentos a.item")
                    .mapNotNull { it.toSearchResponse() }
            "Destaques da Semana" -> 
                document.select(".owl-carousel-semana .divArticleLancamentos a.item")
                    .mapNotNull { it.toSearchResponse() }
            "Últimos Animes Adicionados" -> 
                document.select(".owl-carousel-l_dia .divArticleLancamentos a.item")
                    .mapNotNull { it.toSearchResponse() }
            "Últimos Episódios Adicionados" -> {
                document.select(".divCardUltimosEpsHome").mapNotNull { card ->
                    val link = card.selectFirst("article.card a") ?: return@mapNotNull null
                    val href = link.attr("href") ?: return@mapNotNull null
                    
                    val titleElement = card.selectFirst("h3.animeTitle") ?: return@mapNotNull null
                    val title = titleElement.text().trim()
                    
                    val epNumber = card.selectFirst(".numEp")?.text()?.toIntOrNull() ?: 1
                    
                    val imgElement = card.selectFirst("img.imgAnimesUltimosEps, img[src*='animes']")
                    val poster = when {
                        imgElement?.hasAttr("data-src") == true -> imgElement.attr("data-src")
                        imgElement?.hasAttr("src") == true -> imgElement.attr("src")
                        else -> card.selectFirst("img:not([src*='logo'])")?.attr("src")
                    } ?: return@mapNotNull null
                    
                    val cleanTitle = "${title} - Episódio $epNumber"
                    
                    newAnimeSearchResponse(cleanTitle, fixUrl(href)) {
                        this.posterUrl = fixUrl(poster)
                        this.type = TvType.Anime
                    }
                }
            }
            else -> emptyList() // Adicionado else para tornar o when exaustivo
        }
        
        return newHomePageResponse(request.name, homeItems.distinctBy { it.url }, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl$SEARCH_PATH/${URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document

        return document.select("article.containerAnimes a.item")
            .mapNotNull { it.toSearchResponse() }
            .take(30)
    }

    // ============ LOAD PRINCIPAL (sem episódios) ============
    override suspend fun load(url: String): LoadResponse {
        println("\n" + "=".repeat(80))
        println("🚀 AnimeFire.load() para URL: $url")
        println("=".repeat(80))
        
        val document = app.get(url).document

        val titleElement = document.selectFirst("h1.quicksand400, .main_div_anime_info h1, h1") ?: 
            throw ErrorLoadingException("Não foi possível encontrar o título")
        val rawTitle = titleElement.text().trim()
        
        val year = Regex("\\((\\d{4})\\)").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = rawTitle.replace(Regex("\\(\\d{4}\\)"), "").trim()
        
        val isMovie = url.contains("/filmes/") || rawTitle.contains("Movie", ignoreCase = true)
        val type = if (isMovie) TvType.Movie else TvType.Anime
        
        println("📌 Título: $cleanTitle")
        println("📌 Ano: $year")
        println("📌 Tipo: ${if (isMovie) "Movie" else "Anime"}")

        // 1. BUSCAR MAL ID
        println("\n🔍 Buscando MAL ID...")
        val malId = searchMALIdByName(cleanTitle)
        println("📌 MAL ID encontrado: $malId")

        // 2. BUSCAR DADOS DA ANI.ZIP
        var aniZipData: AniZipData? = null
        if (malId != null) {
            println("🔍 Buscando dados da ani.zip...")
            aniZipData = fetchAniZipData(malId)
            if (aniZipData != null) {
                println("✅ Dados obtidos com sucesso!")
                println("   📊 Títulos: ${aniZipData.titles?.size ?: 0}")
                println("   📊 Imagens: ${aniZipData.images?.size ?: 0}")
                println("   📊 Episódios: ${aniZipData.episodes?.size ?: 0}")
            }
        }

        // 3. BUSCAR NO TMDB (apenas para trailer e metadados)
        println("\n🔍 Buscando no TMDB...")
        val tmdbInfo = searchOnTMDB(cleanTitle, year, !isMovie)
        
        if (tmdbInfo == null) {
            println("⚠️ TMDB não retornou informações!")
        } else {
            println("✅ TMDB OK! Título: ${tmdbInfo.title}")
            println("✅ Trailer encontrado: ${tmdbInfo.youtubeTrailer != null}")
            println("✅ Total temporadas: ${tmdbInfo.seasonsEpisodes.size}")
        }

        // 4. EXTRAIR METADADOS DO SITE
        println("\n🔍 Extraindo metadados do site...")
        val siteMetadata = extractSiteMetadata(document)
        
        // 5. EXTRAIR RECOMENDAÇÕES
        val recommendations = extractRecommendations(document)

        // 6. CRIAR RESPOSTA (SEM EPISÓDIOS - eles virão via getEpisodeList)
        println("\n🏗️ Criando resposta principal (sem episódios)...")
        
        // PRIORIDADE: AniZip > Site > TMDB (apenas para trailer)
        val finalPoster = aniZipData?.images?.find { it.coverType.equals("Poster", ignoreCase = true) }?.url?.let { fixUrl(it) } ?:
                          siteMetadata.poster
        
        val finalBackdrop = aniZipData?.images?.find { it.coverType.equals("Fanart", ignoreCase = true) }?.url?.let { fixUrl(it) } ?:
                           siteMetadata.poster?.let { fixUrl(it) }
        
        val finalPlot = siteMetadata.plot ?:
                        aniZipData?.episodes?.values?.firstOrNull()?.overview
        
        val finalYear = year ?: 
                        siteMetadata.year ?:
                        aniZipData?.episodes?.values?.firstOrNull()?.airDateUtc?.substring(0, 4)?.toIntOrNull() ?:
                        tmdbInfo?.year
        
        val finalTags = siteMetadata.tags ?: emptyList()
        
        val aniZipActors = extractActorsFromAniZip(aniZipData)
        
        println("📊 [RESPONSE SUMMARY]")
        println("   🖼️  Poster: ${finalPoster ?: "Não encontrado"}")
        println("   🎬 Backdrop: ${finalBackdrop ?: "Não encontrado"}")
        println("   📖 Plot: ${finalPlot?.take(80)}...")
        println("   📅 Ano: $finalYear")
        println("   🏷️  Tags: ${finalTags.take(3).joinToString()}")
        println("   🎬 Trailer: ${tmdbInfo?.youtubeTrailer ?: "Não encontrado"}")
        println("   🎭 Atores: ${aniZipActors.size}")
        println("   📺 Episódios: Serão carregados via paginação (20 em 20)")

        // SALVAR TMDB INFO PARA USAR NA PAGINAÇÃO
        val tmdbSeasonsInfo = tmdbInfo?.seasonsEpisodes
        
        return if (isMovie) {
            newMovieLoadResponse(cleanTitle, url, type, url) {
                this.year = finalYear
                this.plot = finalPlot
                this.tags = finalTags
                this.posterUrl = finalPoster
                this.backgroundPosterUrl = finalBackdrop
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
                
                if (aniZipActors.isNotEmpty()) {
                    addActors(aniZipActors)
                }
                
                tmdbInfo?.youtubeTrailer?.let { trailerUrl ->
                    addTrailer(trailerUrl)
                }
            }
        } else {
            // IMPORTANTE: NÃO adicionar episódios aqui! Eles virão via getEpisodeList
            newAnimeLoadResponse(cleanTitle, url, type) {
                // Apenas adicionar um episódio dummy para o CloudStream saber que há episódios
                addEpisodes(DubStatus.Subbed, listOf(
                    newEpisode(url) {
                        name = "Carregando episódios..."
                        season = 1
                        episode = 1
                    }
                ))
                
                this.year = finalYear
                this.plot = finalPlot
                this.tags = finalTags
                this.posterUrl = finalPoster
                this.backgroundPosterUrl = finalBackdrop
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
                
                if (aniZipActors.isNotEmpty()) {
                    addActors(aniZipActors)
                }
                
                tmdbInfo?.youtubeTrailer?.let { trailerUrl ->
                    addTrailer(trailerUrl)
                }
                
                // Adicionar dados extras para usar na paginação
                this.data = mapOf(
                    "tmdbSeasons" to tmdbSeasonsInfo?.keys?.sorted()?.joinToString(","),
                    "totalSeasons" to (tmdbSeasonsInfo?.size ?: 0).toString(),
                    "url" to url,
                    "tmdbId" to (tmdbInfo?.id?.toString() ?: "")
                ).toString()
            }
        }
    }

    // ============ PAGINAÇÃO DE EPISÓDIOS (20 em 20) ============
    
    /**
     * Esta função é chamada automaticamente pelo CloudStream para carregar episódios em lotes
     * @param page Número da página (começa em 1)
     * @param season Número da temporada (se aplicável)
     * @param data Dados extras passados do load()
     */
    override suspend fun getEpisodeList(
        data: String,
        page: Int,
        season: Int?
    ): List<Episode> {
        println("\n📺 [EPISODE LIST] Carregando página $page, temporada ${season ?: 1}")
        
        // Extrair dados salvos
        val dataMap = parseDataMap(data)
        val url = dataMap["url"] ?: return emptyList()
        val tmdbSeasonsStr = dataMap["tmdbSeasons"]
        val totalSeasons = dataMap["totalSeasons"]?.toIntOrNull() ?: 0
        
        println("📺 [EPISODE LIST] URL: $url")
        println("📺 [EPISODE LIST] Total temporadas: $totalSeasons")
        
        // Se não tiver temporadas do TMDB, usar método do site
        if (totalSeasons == 0 || tmdbSeasonsStr.isNullOrEmpty()) {
            println("⚠️ [EPISODE LIST] Sem dados TMDB, usando extração do site")
            return extractEpisodesFromSite(url, page)
        }
        
        // Usar dados do TMDB para organizar por temporadas
        val tmdbSeasons = tmdbSeasonsStr.split(",").mapNotNull { it.toIntOrNull() }
        val currentSeason = season ?: 1
        
        println("📺 [EPISODE LIST] Temporadas disponíveis: $tmdbSeasons")
        println("📺 [EPISODE LIST] Temporada atual: $currentSeason")
        
        // Buscar dados da temporada atual do TMDB
        val tmdbSeasonData = fetchTMDBSeasonData(dataMap, currentSeason)
        
        // Calcular range de episódios para esta página
        val startEpisode = ((page - 1) * EPISODES_PER_PAGE) + 1
        val endEpisode = startEpisode + EPISODES_PER_PAGE - 1
        
        println("📺 [EPISODE LIST] Episódios: $startEpisode - $endEpisode")
        
        // Extrair episódios do TMDB para esta página
        val episodes = extractEpisodesFromTMDBSeason(
            tmdbSeasonData = tmdbSeasonData,
            url = url,
            seasonNumber = currentSeason,
            startEpisode = startEpisode,
            endEpisode = endEpisode
        )
        
        // Se não tiver episódios do TMDB, tentar do site
        return if (episodes.isEmpty()) {
            println("⚠️ [EPISODE LIST] Sem episódios do TMDB, usando site")
            extractEpisodesFromSite(url, page)
        } else {
            episodes
        }
    }
    
    /**
     * Buscar dados de uma temporada específica do TMDB
     */
    private suspend fun fetchTMDBSeasonData(
        dataMap: Map<String, String>,
        seasonNumber: Int
    ): List<TMDBEpisode>? {
        val tmdbId = dataMap["tmdbId"]?.toIntOrNull() ?: return null
        
        println("🔍 [TMDB] Buscando temporada $seasonNumber...")
        
        return try {
            val seasonUrl = "$TMDB_PROXY_URL/tv/$tmdbId/season/$seasonNumber"
            val response = app.get(seasonUrl, timeout = 10_000)
            
            if (response.code == 200) {
                val seasonData = response.parsedSafe<TMDBSeasonResponse>()
                println("✅ [TMDB] Temporada $seasonNumber: ${seasonData?.episodes?.size} episódios")
                seasonData?.episodes
            } else {
                println("❌ [TMDB] Erro HTTP: ${response.code}")
                null
            }
        } catch (e: Exception) {
            println("❌ [TMDB] Exception: ${e.message}")
            null
        }
    }
    
    /**
     * Extrair episódios de uma temporada do TMDB com paginação
     */
    private fun extractEpisodesFromTMDBSeason(
        tmdbSeasonData: List<TMDBEpisode>?,
        url: String,
        seasonNumber: Int,
        startEpisode: Int,
        endEpisode: Int
    ): List<Episode> {
        if (tmdbSeasonData.isNullOrEmpty()) return emptyList()
        
        val episodes = mutableListOf<Episode>()
        
        // Filtrar apenas os episódios para esta página
        val episodesForPage = tmdbSeasonData.filter { episode ->
            val epNum = episode.episode_number
            epNum in startEpisode..endEpisode
        }
        
        println("📺 [TMDB SEASON] Encontrados ${episodesForPage.size} episódios para a página")
        
        episodesForPage.forEach { tmdbEpisode ->
            try {
                // Criar URL do episódio baseado no padrão do site
                val episodeUrl = buildEpisodeUrl(url, seasonNumber, tmdbEpisode.episode_number)
                
                val descriptionWithDuration = buildDescriptionWithDuration(
                    tmdbEpisode.overview,
                    tmdbEpisode.runtime
                )
                
                val episode = newEpisode(episodeUrl) {
                    this.name = "T${seasonNumber} - ${tmdbEpisode.name}"
                    this.season = seasonNumber
                    this.episode = tmdbEpisode.episode_number
                    this.description = descriptionWithDuration
                    
                    tmdbEpisode.air_date?.let { airDate ->
                        try {
                            val dateFormatter = SimpleDateFormat("yyyy-MM-dd")
                            val date = dateFormatter.parse(airDate)
                            this.date = date.time
                        } catch (e: Exception) {}
                    }
                }
                
                episodes.add(episode)
                
            } catch (e: Exception) {
                println("❌ [TMDB EPISODE] Erro ao criar episódio ${tmdbEpisode.episode_number}: ${e.message}")
            }
        }
        
        return episodes
    }
    
    /**
     * Construir URL do episódio baseado no padrão do site
     */
    private fun buildEpisodeUrl(baseUrl: String, season: Int, episode: Int): String {
        // Extrair o slug do anime da URL base
        // Exemplo: https://animefire.io/animes/one-piece-todos-os-episodios
        val slug = baseUrl.substringAfter("animes/").substringBefore("-todos")
        
        // Construir URL do episódio
        return "$mainUrl/video/$slug-episodio-$episode"
    }
    
    /**
     * Extrair episódios diretamente do site (fallback)
     */
    private suspend fun extractEpisodesFromSite(url: String, page: Int): List<Episode> {
        println("🔍 [SITE] Extraindo episódios do site, página $page")
        
        val document = app.get(url).document
        val episodes = mutableListOf<Episode>()
        
        val episodeElements = document.select("a.lEp.epT, a.lEp, .divListaEps a")
        
        if (episodeElements.isEmpty()) {
            println("⚠️ [SITE] Nenhum episódio encontrado no site")
            return emptyList()
        }
        
        // Calcular range para paginação
        val startIndex = (page - 1) * EPISODES_PER_PAGE
        val endIndex = minOf(startIndex + EPISODES_PER_PAGE, episodeElements.size)
        
        println("📊 [SITE] Elementos encontrados: ${episodeElements.size}")
        println("📊 [SITE] Range: $startIndex - ${endIndex - 1}")
        
        // Extrair apenas os episódios desta página
        for (i in startIndex until endIndex) {
            try {
                val element = episodeElements[i]
                val href = element.attr("href")
                if (href.isBlank()) continue
                
                val text = element.text().trim()
                if (text.isBlank()) continue
                
                val episodeNumber = extractEpisodeNumber(text, i + 1)
                
                val episode = newEpisode(fixUrl(href)) {
                    this.name = text
                    this.season = 1
                    this.episode = episodeNumber
                }
                
                episodes.add(episode)
                
            } catch (e: Exception) {
                println("❌ [SITE EPISODE] Erro ao extrair episódio $i: ${e.message}")
            }
        }
        
        println("✅ [SITE] Extraídos ${episodes.size} episódios")
        return episodes.sortedBy { it.episode }
    }
    
    private fun parseDataMap(data: String): Map<String, String> {
        return try {
            data.removePrefix("{").removeSuffix("}").split(",").associate {
                val parts = it.split("=", limit = 2)
                if (parts.size == 2) parts[0].trim() to parts[1].trim()
                else "" to ""
            }.filter { it.key.isNotBlank() }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    // ============ FUNÇÕES AUXILIARES ============
    
    private suspend fun searchMALIdByName(animeName: String): Int? {
        return try {
            val cleanName = animeName
                .replace(Regex("(?i)\\s*-\\s*Todos os Episódios"), "")
                .replace(Regex("(?i)\\s*\\(Dublado\\)"), "")
                .replace(Regex("(?i)\\s*\\(Legendado\\)"), "")
                .trim()
            
            println("🔍 [MAL] Buscando: '$cleanName'")
            
            val query = """
                query {
                    Page(page: 1, perPage: 5) {
                        media(search: "$cleanName", type: ANIME) {
                            title { romaji english native }
                            idMal
                        }
                    }
                }
            """.trimIndent()
            
            val response = app.post(
                "https://graphql.anilist.co",
                data = mapOf("query" to query),
                headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
                timeout = 10_000
            )
            
            println("📡 [MAL] Resposta: ${response.code}")
            
            if (response.code == 200) {
                val data = response.parsedSafe<AniListResponse>()
                val malId = data?.data?.Page?.media?.firstOrNull()?.idMal
                malId
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun fetchAniZipData(malId: Int): AniZipData? {
        for (attempt in 1..MAX_TRIES) {
            try {
                val response = app.get("https://api.ani.zip/mappings?mal_id=$malId", timeout = 10_000)
                
                if (response.code == 200) {
                    return response.parsedSafe<AniZipData>()
                }
            } catch (e: Exception) {
                delay(RETRY_DELAY * attempt)
            }
        }
        return null
    }

    private suspend fun searchOnTMDB(query: String, year: Int?, isTv: Boolean): TMDBInfo? {
        return try {
            val type = if (isTv) "tv" else "movie"
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val yearParam = year?.let { "&year=$it" } ?: ""

            val searchUrl = "$TMDB_PROXY_URL/search?query=$encodedQuery&type=$type$yearParam"
            val response = app.get(searchUrl, timeout = 10_000)

            if (response.code != 200) return null

            val searchResult = response.parsedSafe<TMDBSearchResponse>() ?: return null
            val result = searchResult.results.firstOrNull() ?: return null

            // Buscar todas as temporadas
            val seasonsEpisodes = if (isTv) {
                getTMDBAllSeasons(result.id)
            } else {
                emptyMap()
            }

            // Buscar trailer
            val details = getTMDBDetails(result.id, isTv)
            val youtubeTrailer = getHighQualityTrailer(details?.videos?.results)

            TMDBInfo(
                id = result.id,
                title = if (isTv) result.name else result.title,
                year = if (isTv) result.first_air_date?.substring(0, 4)?.toIntOrNull() 
                       else result.release_date?.substring(0, 4)?.toIntOrNull(),
                posterUrl = null,
                backdropUrl = null,
                overview = null,
                genres = null,
                actors = null,
                youtubeTrailer = youtubeTrailer,
                duration = null,
                seasonsEpisodes = seasonsEpisodes,
                tmdbId = result.id
            )
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun getTMDBAllSeasons(seriesId: Int): Map<Int, List<TMDBEpisode>> {
        return try {
            val seriesDetailsUrl = "$TMDB_PROXY_URL/tv/$seriesId"
            val seriesResponse = app.get(seriesDetailsUrl, timeout = 10_000)

            if (seriesResponse.code != 200) return emptyMap()

            val seriesDetails = seriesResponse.parsedSafe<TMDBTVDetailsResponse>() ?: return emptyMap()
            val seasonsEpisodes = mutableMapOf<Int, List<TMDBEpisode>>()

            for (season in seriesDetails.seasons) {
                if (season.season_number > 0) {
                    val seasonUrl = "$TMDB_PROXY_URL/tv/$seriesId/season/${season.season_number}"
                    val seasonResponse = app.get(seasonUrl, timeout = 10_000)

                    if (seasonResponse.code == 200) {
                        val seasonData = seasonResponse.parsedSafe<TMDBSeasonResponse>()
                        seasonData?.episodes?.let { episodes ->
                            seasonsEpisodes[season.season_number] = episodes
                        }
                    }
                }
            }
            seasonsEpisodes
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private suspend fun getTMDBDetails(id: Int, isTv: Boolean): TMDBDetailsResponse? {
        return try {
            val type = if (isTv) "tv" else "movie"
            val url = "$TMDB_PROXY_URL/$type/$id"
            val response = app.get(url, timeout = 10_000)
            if (response.code != 200) return null
            response.parsedSafe<TMDBDetailsResponse>()
        } catch (e: Exception) {
            null
        }
    }

    private fun extractEpisodeNumber(text: String, default: Int = 1): Int {
        val patterns = listOf(
            Regex("Epis[oó]dio\\s*(\\d+)"),
            Regex("Ep\\.?\\s*(\\d+)"),
            Regex("(\\d{1,3})\\s*-"),
            Regex("#(\\d+)"),
            Regex("\\b(\\d{1,4})\\b")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                return match.groupValues[1].toIntOrNull() ?: default
            }
        }
        return default
    }

    private fun buildDescriptionWithDuration(overview: String?, runtime: Int?): String? {
        return when {
            overview != null && runtime != null && runtime > 0 -> "$overview\n\nDuração: $runtime min"
            overview != null -> overview
            runtime != null && runtime > 0 -> "Duração: $runtime min"
            else -> null
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

    private fun extractSiteMetadata(document: org.jsoup.nodes.Document): SiteMetadata {
        val posterImg = document.selectFirst(".sub_animepage_img img.transitioning_src")
        val poster = when {
            posterImg?.hasAttr("src") == true -> fixUrl(posterImg.attr("src"))
            posterImg?.hasAttr("data-src") == true -> fixUrl(posterImg.attr("data-src"))
            else -> document.selectFirst("img[src*='/img/animes/']:not([src*='logo'])")
                ?.attr("src")?.let { fixUrl(it) }
        }

        val plot = document.selectFirst("div.divSinopse span.spanAnimeInfo")
            ?.text()
            ?.trim()
            ?.replace(Regex("^Sinopse:\\s*"), "")

        val tags = document.select("a.spanAnimeInfo.spanGeneros")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .takeIf { it.isNotEmpty() }?.toList()

        val year = document.selectFirst("div.animeInfo:contains(Ano:) span.spanAnimeInfo")
            ?.text()
            ?.trim()
            ?.toIntOrNull()

        return SiteMetadata(poster, plot, tags, year)
    }

    private fun extractActorsFromAniZip(aniZipData: AniZipData?): List<Actor> {
        val actors = mutableListOf<Actor>()
        aniZipData?.episodes?.values?.forEach { episode ->
            episode.overview?.let { overview ->
                if (overview.contains("Voice Actor:", ignoreCase = true) ||
                    overview.contains("Seiyū:", ignoreCase = true) ||
                    overview.contains("Cast:", ignoreCase = true)) {
                    
                    val lines = overview.split("\n")
                    lines.forEach { line ->
                        if (line.contains(":", ignoreCase = true) && 
                            (line.contains("Voice", ignoreCase = true) || 
                             line.contains("Seiyū", ignoreCase = true))) {
                            
                            val parts = line.split(":")
                            if (parts.size > 1) {
                                val actorName = parts[1].trim()
                                if (actorName.isNotBlank() && !actors.any { it.name == actorName }) {
                                    actors.add(Actor(name = actorName))
                                }
                            }
                        }
                    }
                }
            }
        }
        return actors.take(10)
    }

    private fun extractRecommendations(document: org.jsoup.nodes.Document): List<SearchResponse> {
        return document.select(".owl-carousel-anime .divArticleLancamentos a.item")
            .mapNotNull { it.toSearchResponse() }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return false
    }

    // ============ CLASSES DE DADOS ============
    
    private data class SiteMetadata(
        val poster: String? = null,
        val plot: String? = null,
        val tags: List<String>? = null,
        val year: Int? = null
    )

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
        val seasonsEpisodes: Map<Int, List<TMDBEpisode>> = emptyMap(),
        val tmdbId: Int? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListResponse(
        @JsonProperty("data") val data: AniListData? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListData(
        @JsonProperty("Page") val Page: AniListPage? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListPage(
        @JsonProperty("media") val media: List<AniListMedia>? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListMedia(
        @JsonProperty("idMal") val idMal: Int? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniZipData(
        @JsonProperty("titles") val titles: Map<String, String>? = null,
        @JsonProperty("images") val images: List<AniZipImage>? = null,
        @JsonProperty("episodes") val episodes: Map<String, AniZipEpisode>? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniZipImage(
        @JsonProperty("coverType") val coverType: String? = null,
        @JsonProperty("url") val url: String? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniZipEpisode(
        @JsonProperty("episode") val episode: String? = null,
        @JsonProperty("title") val title: Map<String, String>? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("runtime") val runtime: Int? = null,
        @JsonProperty("rating") val rating: String? = null,
        @JsonProperty("airDateUtc") val airDateUtc: String? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBSearchResponse(
        @JsonProperty("results") val results: List<TMDBResult>
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBResult(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("release_date") val release_date: String? = null,
        @JsonProperty("first_air_date") val first_air_date: String? = null,
        @JsonProperty("poster_path") val poster_path: String?
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBTVDetailsResponse(
        @JsonProperty("seasons") val seasons: List<TMDBSeasonInfo>
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBSeasonInfo(
        @JsonProperty("season_number") val season_number: Int,
        @JsonProperty("episode_count") val episode_count: Int
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBSeasonResponse(
        @JsonProperty("episodes") val episodes: List<TMDBEpisode>,
        @JsonProperty("air_date") val air_date: String?
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBEpisode(
        @JsonProperty("episode_number") val episode_number: Int,
        @JsonProperty("name") val name: String,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("still_path") val still_path: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("air_date") val air_date: String?
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBDetailsResponse(
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("backdrop_path") val backdrop_path: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("genres") val genres: List<TMDBGenre>?,
        @JsonProperty("credits") val credits: TMDBCredits?,
        @JsonProperty("videos") val videos: TMDBVideos?
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBGenre(
        @JsonProperty("name") val name: String
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBCredits(
        @JsonProperty("cast") val cast: List<TMDBCast>
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBCast(
        @JsonProperty("name") val name: String,
        @JsonProperty("character") val character: String?,
        @JsonProperty("profile_path") val profile_path: String?
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBVideos(
        @JsonProperty("results") val results: List<TMDBVideo>
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBVideo(
        @JsonProperty("key") val key: String,
        @JsonProperty("site") val site: String,
        @JsonProperty("type") val type: String,
        @JsonProperty("official") val official: Boolean? = false
    )
}
