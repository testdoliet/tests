package com.AnimeFire

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import java.net.URLEncoder
import kotlinx.coroutines.delay
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.gson.JsonParser
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

class AnimeFire : MainAPI() {
    override var mainUrl = "https://animefire.io"
    override var name = "AnimeFire"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie, TvType.OVA)
    override val usesWebView = true

    // ============ TMDB COM PROXY ============
    private val TMDB_PROXY_URL = "https://lawliet.euluan1912.workers.dev"
    private val tmdbImageUrl = "https://image.tmdb.org/t/p"

    // API AniList para a aba "Em Breve"
    private val aniListApiUrl = "https://graphql.anilist.co"

    companion object {
        private const val SEARCH_PATH = "/pesquisar"
        private const val MAX_TRIES = 3
        private const val RETRY_DELAY = 1000L
    }

    // ============ ABAS DA PÁGINA INICIAL ============
    override val mainPage = mainPageOf(
        "$mainUrl" to "Lançamentos",
        "$mainUrl" to "Destaques da Semana",
        "$mainUrl" to "Últimos Animes Adicionados",
        "$mainUrl" to "Últimos Episódios Adicionados",
        "anilist_upcoming" to "Em Breve"
    )

    // ============ PÁGINA INICIAL DO ANIMEFIRE ============
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return when (request.name) {
            "Em Breve" -> getAniListUpcoming(page)
            else -> getAnimeFireHomePage(request.name)
        }
    }

    private suspend fun getAnimeFireHomePage(pageName: String): HomePageResponse {
        return try {
            println("🏠 [HOMEPAGE] Carregando aba: $pageName")
            
            val response = app.get(mainUrl, timeout = 15_000)
            
            if (response.code != 200) {
                println("❌ [HOMEPAGE] Erro HTTP: ${response.code}")
                return newHomePageResponse(pageName, emptyList(), false)
            }
            
            val document = response.document
            
            val homeItems = when (pageName) {
                "Lançamentos" -> 
                    document.select(".owl-carousel-home .divArticleLancamentos a.item, .owl-carousel:first-child a.item")
                        .mapNotNull { element -> 
                            element.toSearchResponse()
                        }
                
                "Destaques da Semana" -> 
                    document.select(".owl-carousel-semana .divArticleLancamentos a.item, .owl-carousel:nth-child(2) a.item")
                        .mapNotNull { element -> 
                            element.toSearchResponse()
                        }
                
                "Últimos Animes Adicionados" -> 
                    document.select(".owl-carousel-l_dia .divArticleLancamentos a.item, .owl-carousel:nth-child(3) a.item")
                        .mapNotNull { element -> 
                            element.toSearchResponse()
                        }
                
                "Últimos Episódios Adicionados" -> {
                    document.select(".divCardUltimosEpsHome, .divListaEpsHome article.card").mapNotNull { card ->
                        try {
                            val link = card.selectFirst("a") ?: return@mapNotNull null
                            val href = link.attr("href").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                            
                            val titleElement = card.selectFirst("h3.animeTitle, .title, h3") ?: return@mapNotNull null
                            val rawTitle = titleElement.text().trim()
                            
                            // Extrair número do episódio
                            val epNumber = card.selectFirst(".numEp, .ep-number, [class*='ep']")?.text()?.let {
                                Regex("(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull()
                            } ?: 1
                            
                            // Limpar título
                            val cleanTitle = rawTitle
                                .replace(Regex("(?i)(dublado|legendado|todos os episódios|\\(\\d{4}\\)|\\s*-\\s*$)"), "")
                                .trim()
                            
                            val displayTitle = if (cleanTitle.isNotEmpty()) {
                                "$cleanTitle - Episódio $epNumber"
                            } else {
                                "Episódio $epNumber"
                            }
                            
                            // Extrair imagem do site
                            val sitePoster = card.selectFirst("img.imgAnimesUltimosEps, img.imgAnimes, img[src*='animes']")?.let { img ->
                                when {
                                    img.hasAttr("data-src") -> img.attr("data-src")
                                    img.hasAttr("src") -> img.attr("src")
                                    else -> null
                                }?.takeIf { !it.contains("logo", ignoreCase = true) }
                            } ?: card.selectFirst("img:not([src*='logo'])")?.attr("src")
                            
                            newAnimeSearchResponse(displayTitle, fixUrl(href)) {
                                this.posterUrl = sitePoster?.let { fixUrl(it) }
                                this.type = TvType.Anime
                            }
                        } catch (e: Exception) {
                            println("❌ [HOMEPAGE] Erro ao processar episódio: ${e.message}")
                            null
                        }
                    }
                }
                
                else -> emptyList()
            }
            
            println("✅ [HOMEPAGE] ${homeItems.size} itens encontrados para: $pageName")
            newHomePageResponse(pageName, homeItems.distinctBy { it.url }, false)
            
        } catch (e: Exception) {
            println("❌ [HOMEPAGE] Exception: ${e.message}")
            newHomePageResponse(pageName, emptyList(), false)
        }
    }

    // ============ ABA "EM BREVE" (ANILIST) ============
    private suspend fun getAniListUpcoming(page: Int): HomePageResponse {
        val query = """
            query {
                Page(page: $page, perPage: 20) {
                    media(season: SPRING, seasonYear: 2025, type: ANIME, sort: POPULARITY_DESC) {
                        id
                        title {
                            romaji
                            english
                        }
                        coverImage {
                            extraLarge
                        }
                    }
                }
            }
        """.trimIndent()
        
        return try {
            println("📡 [ANILIST] Buscando: Em Breve")
            
            val response = app.post(
                aniListApiUrl,
                data = mapOf("query" to query),
                headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
                timeout = 10_000
            )
            
            if (response.code == 200) {
                val aniListResponse = response.parsedSafe<AniListResponse>()
                val mediaList = aniListResponse?.data?.Page?.media ?: emptyList()
                
                println("📊 [ANILIST] ${mediaList.size} resultados")
                
                val filteredItems = mutableListOf<SearchResponse>()
                
                for (media in mediaList) {
                    val title = media.title?.english ?: media.title?.romaji ?: "Sem Título"
                    val cleanTitle = cleanAnimeTitle(title)
                    val posterUrl = media.coverImage?.extraLarge
                    
                    // URL especial para o AniList
                    val anilistUrl = "anilist:${media.id}:$cleanTitle"
                    
                    filteredItems.add(newAnimeSearchResponse(cleanTitle, anilistUrl) {
                        this.posterUrl = posterUrl
                        this.type = TvType.Anime
                    })
                    
                    println("✅ [ANILIST] Adicionado: $cleanTitle")
                }
                
                println("✅ [ANILIST] ${filteredItems.size} itens adicionados")
                newHomePageResponse("Em Breve", filteredItems, hasNext = filteredItems.isNotEmpty())
            } else {
                println("❌ [ANILIST] Erro: ${response.code}")
                newHomePageResponse("Em Breve", emptyList(), false)
            }
        } catch (e: Exception) {
            println("❌ [ANILIST] Exception: ${e.message}")
            newHomePageResponse("Em Breve", emptyList(), false)
        }
    }

    // ============ FUNÇÃO AUXILIAR DE BUSCA ============
    private fun Element.toSearchResponse(): AnimeSearchResponse? {
        return try {
            val href = attr("href").takeIf { it.isNotEmpty() } ?: return null
            
            val titleElement = selectFirst("h3.animeTitle, .text-block h3, .animeTitle, h3, .title, .card-title")
            val rawTitle = titleElement?.text()?.trim() ?: return null
            
            val cleanTitle = rawTitle
                .replace(Regex("(?i)(dublado|legendado|todos os episódios|\\(\\d{4}\\)|\\s*-\\s*$|\\(movie\\))"), "")
                .trim()
            
            val isMovie = href.contains("/filmes/") || 
                          rawTitle.contains("filme", ignoreCase = true) ||
                          rawTitle.contains("movie", ignoreCase = true)
            
            val sitePoster = selectFirst("img.imgAnimes, img.card-img-top, img.transitioning_src, img.owl-lazy, img[src*='animes']")?.let { img ->
                when {
                    img.hasAttr("data-src") -> img.attr("data-src")
                    img.hasAttr("src") -> img.attr("src")
                    else -> null
                }?.takeIf { !it.contains("logo", ignoreCase = true) }
            } ?: selectFirst("img:not([src*='logo']):not([src*='Logo'])")?.attr("src")

            newAnimeSearchResponse(cleanTitle, fixUrl(href)) {
                this.posterUrl = sitePoster?.let { fixUrl(it) }
                this.type = if (isMovie) TvType.Movie else TvType.Anime
            }
        } catch (e: Exception) {
            println("❌ [toSearchResponse] Erro: ${e.message}")
            null
        }
    }

    // ============ BUSCA NO ANIMEFIRE ============
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl$SEARCH_PATH/${URLEncoder.encode(query, "UTF-8")}"
        println("🔍 [SEARCH] Buscando: '$query' | URL: $searchUrl")
        
        val document = app.get(searchUrl).document

        val elements = document.select("div.divCardUltimosEps article.card a")
        println("🔍 [SEARCH] Elementos encontrados: ${elements.size}")
        
        if (elements.isEmpty()) {
            println("⚠️ [SEARCH] Nenhum elemento encontrado com o seletor atual")
        }

        return elements.mapNotNull { element ->
            try {
                val href = element.attr("href")
                if (href.isBlank()) {
                    println("⚠️ [SEARCH] Link vazio encontrado")
                    return@mapNotNull null
                }

                val titleElement = element.selectFirst("h3.animeTitle, .text-block h3, .animeTitle")
                val rawTitle = titleElement?.text()?.trim() ?: "Sem Título"
                
                val cleanTitle = rawTitle
                    .replace(Regex("\\s*-\\s*Todos os Episódios$"), "")
                    .replace(Regex("\\(Dublado\\)"), "")
                    .replace(Regex("\\(Legendado\\)"), "")
                    .trim()

                val isMovie = href.contains("/filmes/") || 
                             cleanTitle.contains("filme", ignoreCase = true) ||
                             rawTitle.contains("filme", ignoreCase = true) ||
                             rawTitle.contains("movie", ignoreCase = true)

                val imgElement = element.selectFirst("img.imgAnimes, img.card-img-top, img.transitioning_src")
                val sitePoster = when {
                    imgElement?.hasAttr("data-src") == true -> imgElement.attr("data-src")
                    imgElement?.hasAttr("src") == true -> imgElement.attr("src")
                    else -> null
                }

                println("✅ [SEARCH] Processado: '$cleanTitle' | URL: ${href.take(50)}... | Tipo: ${if (isMovie) "Filme" else "Anime"}")

                newAnimeSearchResponse(cleanTitle, fixUrl(href)) {
                    this.posterUrl = sitePoster?.let { fixUrl(it) }
                    this.type = if (isMovie) {
                        TvType.Movie
                    } else {
                        TvType.Anime
                    }
                }
            } catch (e: Exception) {
                println("❌ [SEARCH] Erro ao processar elemento: ${e.message}")
                null
            }
        }.take(30)
    }

    // ============ LOAD PRINCIPAL (TMDB) ============
    override suspend fun load(url: String): LoadResponse {
        println("\n🚀 AnimeFire.load() para URL: $url")
        
        // Se for URL do AniList (Em Breve)
        if (url.startsWith("anilist:")) {
            val parts = url.split(":")
            if (parts.size >= 3) {
                val titleFromUrl = parts[2]
                println("🎯 [LOAD] Anime do AniList: $titleFromUrl")
                
                // Buscar no AnimeFire
                val searchResults = search(titleFromUrl)
                val searchResult = searchResults.firstOrNull()
                if (searchResult != null) {
                    println("✅ [LOAD] Encontrado no AnimeFire: ${searchResult.name}")
                    return loadFromAnimeFire(searchResult.url)
                } else {
                    println("❌ [LOAD] Não encontrado no AnimeFire")
                    return createErrorResponse(titleFromUrl, url)
                }
            }
        }
        
        // Carregar normalmente do AnimeFire
        return loadFromAnimeFire(url)
    }

    private suspend fun loadFromAnimeFire(url: String): LoadResponse {
        println("📺 [LOAD] Carregando do AnimeFire: $url")
        
        val document = app.get(url).document

        val titleElement = document.selectFirst("h1.quicksand400, .main_div_anime_info h1, h1") ?: 
            throw ErrorLoadingException("Título não encontrado")
        val rawTitle = titleElement.text().trim()
        
        val year = Regex("\\((\\d{4})\\)").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = rawTitle.replace(Regex("\\(\\d{4}\\)"), "").trim()
        
        val isMovie = url.contains("/filmes/") || rawTitle.contains("Movie", ignoreCase = true)
        val type = if (isMovie) TvType.Movie else TvType.Anime

        println("📌 [LOAD] Título: $cleanTitle, Ano: $year, Tipo: $type")

        // ============ BUSCAR NO TMDB ============
        println("🔍 [TMDB] Buscando informações...")
        val tmdbInfo = searchOnTMDB(cleanTitle, year, !isMovie)
        
        if (tmdbInfo != null) {
            println("✅ [TMDB] Encontrado: ${tmdbInfo.title}")
        } else {
            println("⚠️ [TMDB] Não encontrado no TMDB")
        }

        // Extrair dados do site (como fallback)
        val siteMetadata = extractSiteMetadata(document)
        val episodes = if (!isMovie) extractEpisodesFromSite(document, tmdbInfo) else emptyList()
        val recommendations = extractRecommendationsFromSite(document)

        // ============ CRIAR RESPOSTA COM TMDB ============
        return createLoadResponseWithTMDB(
            url = url,
            cleanTitle = cleanTitle,
            year = year,
            isMovie = isMovie,
            type = type,
            tmdbInfo = tmdbInfo,
            siteMetadata = siteMetadata,
            episodes = episodes,
            recommendations = recommendations
        )
    }

    // ============ FUNÇÕES TMDB ============
    private suspend fun searchOnTMDB(query: String, year: Int?, isTv: Boolean): TMDBInfo? {
        println("🔍 [TMDB] Buscando: '$query'")
        
        return try {
            val type = if (isTv) "tv" else "movie"
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val yearParam = year?.let { "&year=$it" } ?: ""

            val searchUrl = "$TMDB_PROXY_URL/search?query=$encodedQuery&type=$type$yearParam"
            println("🔗 [TMDB] URL: $searchUrl")

            val response = app.get(searchUrl, timeout = 10_000)
            println("📡 [TMDB] Status: ${response.code}")

            if (response.code != 200) return null

            val searchResult = response.parsedSafe<TMDBSearchResponse>() ?: return null
            println("✅ [TMDB] Resultados: ${searchResult.results.size}")

            val result = searchResult.results.firstOrNull() ?: return null

            // Buscar detalhes completos
            val details = getTMDBDetails(result.id, isTv) ?: return null

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
            println("❌ [TMDB] ERRO: ${e.message}")
            null
        }
    }

    private suspend fun getTMDBDetails(id: Int, isTv: Boolean): TMDBDetailsResponse? {
        println("🔍 [TMDB] Buscando detalhes para ID $id")
        
        return try {
            val type = if (isTv) "tv" else "movie"
            val url = "$TMDB_PROXY_URL/$type/$id"
            println("🔗 [TMDB] URL detalhes: $url")

            val response = app.get(url, timeout = 10_000)
            println("📡 [TMDB] Status: ${response.code}")

            if (response.code != 200) return null

            response.parsedSafe<TMDBDetailsResponse>()
        } catch (e: Exception) {
            println("❌ [TMDB] ERRO detalhes: ${e.message}")
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

    private fun extractEpisodesFromSite(
        document: org.jsoup.nodes.Document,
        tmdbInfo: TMDBInfo?
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        val episodeElements = document.select("a.lEp.epT, a.lEp, .divListaEps a, [href*='/video/'], [href*='/episodio/']")
        
        println("🔍 [SITE] Encontrados ${episodeElements.size} episódios no site")
        
        episodeElements.forEachIndexed { index, element ->
            try {
                val href = element.attr("href")
                if (href.isBlank()) return@forEachIndexed
                
                val text = element.text().trim()
                if (text.isBlank()) return@forEachIndexed
                
                val episodeNumber = extractEpisodeNumber(text) ?: (index + 1)
                val seasonNumber = 1
                
                val episodeName = element.selectFirst(".ep-name, .title")?.text()?.trim()
                    ?: text.substringAfterLast("-").trim()
                    ?: "Episódio $episodeNumber"

                episodes.add(
                    newEpisode(fixUrl(href)) {
                        this.name = episodeName
                        this.season = seasonNumber
                        this.episode = episodeNumber
                    }
                )
                
            } catch (e: Exception) {
                println("❌ [SITE] Erro episódio ${index + 1}: ${e.message}")
            }
        }
        
        return episodes.sortedBy { it.episode }
    }

    private fun extractRecommendationsFromSite(document: org.jsoup.nodes.Document): List<SearchResponse> {
        return document.select(".owl-carousel-anime .divArticleLancamentos a.item")
            .mapNotNull { element -> 
                try { 
                    element.toSearchResponse() 
                } catch (e: Exception) {
                    println("❌ [RECOMMENDATIONS] Erro: ${e.message}")
                    null
                }
            }
    }

    private fun extractEpisodeNumber(text: String): Int? {
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
                return match.groupValues[1].toIntOrNull()
            }
        }
        return null
    }

    private fun cleanAnimeTitle(title: String): String {
        return title
            .replace(Regex("\\s*\\([^)]+\\)$"), "")
            .replace(Regex("\\s*-\\s*[^-]+$"), "")
            .replace(Regex("\\s+"), " ")
            .replace(Regex("[^a-zA-Z0-9\\s-]"), "")
            .trim()
    }

    // ============ CRIAR RESPOSTA COM TMDB ============
    private suspend fun createLoadResponseWithTMDB(
        url: String,
        cleanTitle: String,
        year: Int?,
        isMovie: Boolean,
        type: TvType,
        tmdbInfo: TMDBInfo?,
        siteMetadata: SiteMetadata,
        episodes: List<Episode>,
        recommendations: List<SearchResponse>
    ): LoadResponse {
        
        println("🏗️ [RESPONSE] Criando resposta com TMDB")
        
        // Usar dados do TMDB quando disponíveis
        val finalTitle = tmdbInfo?.title ?: cleanTitle
        val finalYear = tmdbInfo?.year ?: year ?: siteMetadata.year
        val finalPlot = tmdbInfo?.overview ?: siteMetadata.plot
        val finalPoster = tmdbInfo?.posterUrl
        val finalBackdrop = tmdbInfo?.backdropUrl
        val finalGenres = tmdbInfo?.genres ?: siteMetadata.tags
        
        println("📊 [RESPONSE] Dados finais:")
        println("   Título: $finalTitle")
        println("   Ano: $finalYear")
        println("   Poster: ${finalPoster?.take(50)}...")
        println("   Gêneros: ${finalGenres?.take(3)?.joinToString()}")
        println("   Episódios: ${episodes.size}")

        return if (isMovie) {
            newMovieLoadResponse(finalTitle, url, type, url) {
                this.year = finalYear
                this.plot = finalPlot
                this.tags = finalGenres?.take(10)
                this.posterUrl = finalPoster
                this.backgroundPosterUrl = finalBackdrop
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
                
                tmdbInfo?.actors?.let { actors ->
                    println("🎭 [RESPONSE] Adicionando ${actors.size} atores")
                    addActors(actors)
                }
                
                tmdbInfo?.youtubeTrailer?.let { trailerUrl ->
                    println("🎬 [RESPONSE] Adicionando trailer")
                    addTrailer(trailerUrl)
                }
            }
        } else {
            newAnimeLoadResponse(finalTitle, url, type) {
                addEpisodes(DubStatus.Subbed, episodes)
                
                this.year = finalYear
                this.plot = finalPlot
                this.tags = finalGenres?.take(10)
                this.posterUrl = finalPoster
                this.backgroundPosterUrl = finalBackdrop
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
                
                tmdbInfo?.actors?.let { actors ->
                    println("🎭 [RESPONSE] Adicionando ${actors.size} atores")
                    addActors(actors)
                }
                
                tmdbInfo?.youtubeTrailer?.let { trailerUrl ->
                    println("🎬 [RESPONSE] Adicionando trailer")
                    addTrailer(trailerUrl)
                }
            }
        }
    }

    private fun createErrorResponse(title: String, url: String): LoadResponse {
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.plot = "Este anime da lista 'Em Breve' ainda não está disponível no AnimeFire.\n\nTente buscar manualmente pelo nome."
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return AnimeFireVideoExtractor.extractVideoLinks(data, mainUrl, name, callback)
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
        val duration: Int?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListResponse(
        @JsonProperty("data") val data: AniListData? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListData(
        @JsonProperty("Page") val Page: AniListPage? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListPage(
        @JsonProperty("media") val media: List<AniListMedia>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListMedia(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: AniListTitle? = null,
        @JsonProperty("coverImage") val coverImage: AniListCoverImage? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListTitle(
        @JsonProperty("romaji") val romaji: String? = null,
        @JsonProperty("english") val english: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListCoverImage(
        @JsonProperty("extraLarge") val extraLarge: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBSearchResponse(
        @JsonProperty("results") val results: List<TMDBResult>
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBResult(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("release_date") val release_date: String? = null,
        @JsonProperty("first_air_date") val first_air_date: String? = null,
        @JsonProperty("poster_path") val poster_path: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBDetailsResponse(
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("backdrop_path") val backdrop_path: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("genres") val genres: List<TMDBGenre>?,
        @JsonProperty("credits") val credits: TMDBCredits?,
        @JsonProperty("videos") val videos: TMDBVideos?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBGenre(
        @JsonProperty("name") val name: String
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBCredits(
        @JsonProperty("cast") val cast: List<TMDBCast>
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBCast(
        @JsonProperty("name") val name: String,
        @JsonProperty("character") val character: String?,
        @JsonProperty("profile_path") val profile_path: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBVideos(
        @JsonProperty("results") val results: List<TMDBVideo>
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBVideo(
        @JsonProperty("key") val key: String,
        @JsonProperty("site") val site: String,
        @JsonProperty("type") val type: String,
        @JsonProperty("official") val official: Boolean? = false
    )
}
