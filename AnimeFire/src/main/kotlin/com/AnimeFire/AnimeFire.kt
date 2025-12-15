package com.AnimeFire

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import java.text.SimpleDateFormat
import java.net.URLEncoder
import kotlinx.coroutines.delay // ADICIONADO

class AnimeFire : MainAPI() {
    override var mainUrl = "https://animefire.plus"
    override var name = "AnimeFire"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)
    override val usesWebView = true

    // ============ API TVDB ============
    private val TVDB_API_KEY = "7c700ee3-d51d-4ea4-b692-fcec71483fa4"
    private val TVDB_BASE_URL = "https://api4.thetvdb.com/v4"
    private val TVDB_IMAGE_BASE = "https://artworks.thetvdb.com"

    // ============ CONSTANTES ============
    companion object {
        private const val SEARCH_PATH = "/pesquisar"
        private const val MAX_TRIES = 3
        private const val RETRY_DELAY = 1000L
    }

    private var tvdbToken: String? = null

    // ============ APENAS 4 ABAS DA PÁGINA INICIAL ============
    override val mainPage = mainPageOf(
        "$mainUrl" to "Lançamentos",
        "$mainUrl" to "Destaques da Semana",
        "$mainUrl" to "Últimos Animes Adicionados",
        "$mainUrl" to "Últimos Episódios Adicionados"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        
        val homeItems = when (request.name) {
            "Lançamentos" -> extractLancamentos(document)
            "Destaques da Semana" -> extractDestaquesSemana(document)
            "Últimos Animes Adicionados" -> extractUltimosAnimes(document)
            "Últimos Episódios Adicionados" -> extractUltimosEpisodios(document)
            else -> emptyList()
        }
        
        return newHomePageResponse(request.name, homeItems.distinctBy { it.url })
    }

    // 1. LANÇAMENTOS
    private fun extractLancamentos(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        
        val titleElement = document.selectFirst("h1.section2:contains(Em lançamento)")
        titleElement?.let { title ->
            val carousel = title.parent()?.nextElementSibling()?.selectFirst(".owl-carousel-home")
            carousel?.select(".divArticleLancamentos a.item")?.forEach { item ->
                item.toSearchResult()?.let { items.add(it) }
            }
        }
        
        return items.take(15).distinctBy { it.url }
    }

    // 2. DESTAQUES DA SEMANA
    private fun extractDestaquesSemana(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        
        val titleElement = document.selectFirst("div.divSection:nth-child(4) > h1.section2:contains(Destaques da semana)")
        titleElement?.let { title ->
            val carousel = title.parent()?.nextElementSibling()?.selectFirst(".owl-carousel-semana")
            carousel?.select(".divArticleLancamentos a.item")?.forEach { item ->
                item.toSearchResult()?.let { items.add(it) }
            }
        }
        
        return items.take(15).distinctBy { it.url }
    }

    // 3. ÚLTIMOS ANIMES ADICIONADOS
    private fun extractUltimosAnimes(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        
        val titleElement = document.selectFirst("div.divSection:nth-child(6) > h1.section2:contains(Últimos animes adicionados)")
        titleElement?.let { title ->
            val carousel = title.parent()?.nextElementSibling()?.selectFirst(".owl-carousel-l_dia")
            carousel?.select(".divArticleLancamentos a.item")?.forEach { item ->
                item.toSearchResult()?.let { items.add(it) }
            }
        }
        
        return items.take(15).distinctBy { it.url }
    }

    // 4. ÚLTIMOS EPISÓDIOS ADICIONADOS
    private fun extractUltimosEpisodios(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        
        val titleElement = document.selectFirst("div.divSectionUltimosEpsHome:nth-child(3) > h2.section2:contains(Últimos episódios adicionados)")
        titleElement?.let { title ->
            val container = title.parent()?.nextElementSibling()?.selectFirst(".row")
            container?.select(".divCardUltimosEpsHome")?.forEach { card ->
                card.toEpisodeSearchResult()?.let { items.add(it) }
            }
        }
        
        return items.take(20).distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("href") ?: return null
        if (href.isBlank()) return null
        
        val titleElement = selectFirst("h3.animeTitle") ?: return null
        val title = titleElement.text().trim()
        
        val imgElement = selectFirst("img.imgAnimes, img.owl-lazy")
        val poster = when {
            imgElement?.hasAttr("data-src") == true -> imgElement.attr("data-src")
            imgElement?.hasAttr("src") == true -> imgElement.attr("src")
            else -> selectFirst("img")?.attr("src")
        } ?: return null
        
        val cleanTitle = title.replace(Regex("(?i)(dublado|legendado|todos os episódios|\\(\\d{4}\\))$"), "").trim()
        
        val isMovie = href.contains("/filmes/") || title.contains("Movie", ignoreCase = true)
        
        return if (isMovie) {
            newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                this.posterUrl = fixUrl(poster)
            }
        } else {
            newAnimeSearchResponse(cleanTitle, fixUrl(href), TvType.Anime) {
                this.posterUrl = fixUrl(poster)
            }
        }
    }

    private fun Element.toEpisodeSearchResult(): SearchResponse? {
        val link = selectFirst("article.card a") ?: return null
        val href = link.attr("href") ?: return null
        if (href.isBlank()) return null
        
        val titleElement = selectFirst("h3.animeTitle") ?: return null
        val title = titleElement.text().trim()
        
        val epNumberElement = selectFirst(".numEp")
        val epNumber = epNumberElement?.text()?.toIntOrNull() ?: 1
        
        val imgElement = selectFirst("img.imgAnimesUltimosEps, img.transitioning_src")
        val poster = when {
            imgElement?.hasAttr("data-src") == true -> imgElement.attr("data-src")
            imgElement?.hasAttr("src") == true -> imgElement.attr("src")
            else -> selectFirst("img")?.attr("src")
        } ?: return null
        
        val cleanTitle = "${title} - Episódio $epNumber"
        
        return newAnimeSearchResponse(cleanTitle, fixUrl(href), TvType.Anime) {
            this.posterUrl = fixUrl(poster)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl$SEARCH_PATH/${URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document

        return document.select("article.containerAnimes a.item").mapNotNull { element ->
            try {
                element.toSearchResult()
            } catch (e: Exception) {
                null
            }
        }.take(30)
    }

    override suspend fun load(url: String): LoadResponse? {
        println("🔍 [DEBUG] AnimeFire: Iniciando load para URL: $url")

        val document = app.get(url).document

        // Título do site
        val titleElement = document.selectFirst("h1.quicksand400, .main_div_anime_info h1, h1") ?: return null
        val title = titleElement.text().trim()
        
        println("🔍 [DEBUG] AnimeFire: Título encontrado: $title")

        // Extrair ano do título
        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
        println("🔍 [DEBUG] AnimeFire: Título limpo: $cleanTitle | Ano: $year")

        // Determinar se é anime ou filme
        val isAnime = url.contains("/animes/") || !url.contains("/filmes/")
        val isMovie = url.contains("/filmes/") || title.contains("Movie", ignoreCase = true)
        val isTv = isAnime && !isMovie
        
        println("🔍 [DEBUG] AnimeFire: Tipo - Anime: $isAnime, Movie: $isMovie, TV: $isTv")

        // Buscar no TVDB (só para séries)
        val tvdbInfo = if (isTv) {
            println("🔍 [DEBUG] AnimeFire: Buscando no TVDB...")
            searchOnTVDB(cleanTitle, year)
        } else {
            println("⚠️ [DEBUG] AnimeFire: É filme, pulando TVDB")
            null
        }

        if (tvdbInfo == null) {
            println("⚠️ [DEBUG] AnimeFire: TVDB não retornou informações!")
        } else {
            println("✅ [DEBUG] AnimeFire: TVDB OK! Título: ${tvdbInfo.name}, Ano: ${tvdbInfo.year}")
            println("✅ [DEBUG] AnimeFire: Poster: ${tvdbInfo.posterUrl}")
            println("✅ [DEBUG] AnimeFire: Temporadas: ${tvdbInfo.seasonsEpisodes.size}")
        }

        // Extrair recomendações do site
        val siteRecommendations = extractRecommendationsFromSite(document)

        return if (tvdbInfo != null) {
            println("✅ [DEBUG] AnimeFire: Criando resposta COM TVDB")
            createLoadResponseWithTVDB(tvdbInfo, url, document, siteRecommendations)
        } else {
            println("⚠️ [DEBUG] AnimeFire: Criando resposta APENAS com dados do site")
            createLoadResponseFromSite(document, url, cleanTitle, year, isAnime, isMovie)
        }
    }

    // ============ FUNÇÕES TVDB ============

    private suspend fun loginTVDB(): Boolean {
        if (tvdbToken != null) return true
        
        for (attempt in 1..MAX_TRIES) {
            try {
                println("🔐 [TVDB] Tentando login... (tentativa $attempt)")
                val response = app.post(
                    "$TVDB_BASE_URL/login",
                    data = mapOf("apikey" to TVDB_API_KEY),
                    headers = mapOf("Content-Type" to "application/json"),
                    timeout = 10_000
                )
                
                if (response.code == 200) {
                    val json = response.parsedSafe<TVDBLoginResponse>()
                    tvdbToken = json?.data?.token
                    if (tvdbToken != null) {
                        println("✅ [TVDB] Login bem-sucedido")
                        return true
                    }
                }
            } catch (e: Exception) {
                println("❌ [TVDB] Erro no login: ${e.message}")
            }
            
            if (attempt < MAX_TRIES) {
                delay(RETRY_DELAY * attempt)
            }
        }
        
        return false
    }

    private suspend fun searchOnTVDB(query: String, year: Int?): TVDBInfo? {
        if (!loginTVDB()) {
            println("❌ [TVDB] Falha no login")
            return null
        }

        val token = tvdbToken ?: return null

        println("🔍 [TVDB] Buscando: $query")
        
        try {
            // Primeiro busca a série
            val searchUrl = "$TVDB_BASE_URL/search?query=${URLEncoder.encode(query, "UTF-8")}&type=series"
            val searchResponse = app.get(
                searchUrl,
                headers = mapOf("Authorization" to "Bearer $token"),
                timeout = 10_000
            )
            
            if (searchResponse.code != 200) {
                println("❌ [TVDB] Erro na busca: ${searchResponse.code}")
                return null
            }

            val searchResult = searchResponse.parsedSafe<TVDBSearchResponse>()
            val series = searchResult?.data?.firstOrNull() ?: return null
            println("✅ [TVDB] Série encontrada: ${series.name} (ID: ${series.id})")

            // Buscar detalhes completos da série
            val seriesDetails = getTVDBDetails(series.id, token) ?: return null

            // Buscar atores
            val actors = getTVDBActors(series.id, token)

            // Buscar temporadas e episódios
            val seasonsEpisodes = getTVDBSeasonsAndEpisodes(series.id, token)

            return TVDBInfo(
                id = series.id,
                name = series.name,
                year = series.year ?: year,
                posterUrl = series.image,
                bannerUrl = seriesDetails.artworks?.find { it.type == 1 }?.image, // Banner
                overview = seriesDetails.overview,
                actors = actors,
                seasonsEpisodes = seasonsEpisodes
            )
        } catch (e: Exception) {
            println("❌ [TVDB] Erro geral: ${e.message}")
            return null
        }
    }

    private suspend fun getTVDBDetails(seriesId: Int, token: String): TVDBSeriesDetails? {
        try {
            val response = app.get(
                "$TVDB_BASE_URL/series/$seriesId/extended",
                headers = mapOf("Authorization" to "Bearer $token"),
                timeout = 10_000
            )
            
            if (response.code == 200) {
                return response.parsedSafe<TVDBSeriesDetailsResponse>()?.data
            }
        } catch (e: Exception) {
            println("❌ [TVDB] Erro nos detalhes: ${e.message}")
        }
        return null
    }

    private suspend fun getTVDBActors(seriesId: Int, token: String): List<Actor> {
        return try {
            val response = app.get(
                "$TVDB_BASE_URL/series/$seriesId/actors",
                headers = mapOf("Authorization" to "Bearer $token"),
                timeout = 10_000
            )
            
            if (response.code == 200) {
                val actorsData = response.parsedSafe<TVDBActorsResponse>()?.data
                actorsData?.take(15)?.mapNotNull { actor ->
                    if (actor.name.isNotBlank()) {
                        // CORREÇÃO: Actor não tem parâmetro role no CloudStream3
                        Actor(
                            name = actor.name,
                            image = actor.image?.let { "$TVDB_IMAGE_BASE$it" }
                            // role = actor.role // Removido pois Actor não suporta
                        )
                    } else null
                } ?: emptyList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            println("❌ [TVDB] Erro nos atores: ${e.message}")
            emptyList()
        }
    }

    private suspend fun getTVDBSeasonsAndEpisodes(seriesId: Int, token: String): Map<Int, List<TVDBEpisodeInfo>> {
        val seasonsEpisodes = mutableMapOf<Int, List<TVDBEpisodeInfo>>()
        
        try {
            // Primeiro busca todas as temporadas
            val seasonsResponse = app.get(
                "$TVDB_BASE_URL/series/$seriesId/seasons",
                headers = mapOf("Authorization" to "Bearer $token"),
                timeout = 10_000
            )
            
            if (seasonsResponse.code != 200) {
                return emptyMap()
            }

            val seasonsData = seasonsResponse.parsedSafe<TVDBSeasonsResponse>()?.data
            if (seasonsData == null || seasonsData.isEmpty()) {
                return emptyMap()
            }

            println("📊 [TVDB] Temporadas encontradas: ${seasonsData.size}")

            // Para cada temporada, busca os episódios
            for (seasonData in seasonsData) {
                val seasonNumber = seasonData.number ?: continue
                if (seasonNumber <= 0) continue // Pular temporada 0 (especiais)

                try {
                    val episodesResponse = app.get(
                        "$TVDB_BASE_URL/seasons/$seasonData.id/episodes?page=0",
                        headers = mapOf("Authorization" to "Bearer $token"),
                        timeout = 10_000
                    )
                    
                    if (episodesResponse.code == 200) {
                        val episodesData = episodesResponse.parsedSafe<TVDBEpisodesResponse>()?.data
                        if (episodesData != null && episodesData.isNotEmpty()) {
                            println("📺 [TVDB] Temporada $seasonNumber: ${episodesData.size} episódios")
                            
                            val episodes = episodesData.mapNotNull { ep ->
                                if (ep.number != null) {
                                    TVDBEpisodeInfo(
                                        seasonNumber = seasonNumber,
                                        episodeNumber = ep.number,
                                        name = ep.name ?: "Episódio ${ep.number}",
                                        overview = ep.overview,
                                        image = ep.image?.let { "$TVDB_IMAGE_BASE$it" },
                                        airDate = ep.airDate,
                                        runtime = ep.runtime
                                    )
                                } else null
                            }
                            
                            seasonsEpisodes[seasonNumber] = episodes.sortedBy { it.episodeNumber }
                        }
                    }
                } catch (e: Exception) {
                    println("⚠️ [TVDB] Erro ao buscar episódios da temporada $seasonNumber: ${e.message}")
                }
                
                delay(500) // CORREÇÃO: Agora delay está importado
            }
        } catch (e: Exception) {
            println("❌ [TVDB] Erro ao buscar temporadas: ${e.message}")
        }
        
        return seasonsEpisodes
    }

    // ============ FUNÇÕES DO SITE ============

    private fun extractRecommendationsFromSite(document: org.jsoup.nodes.Document): List<SearchResponse> {
        return document.select(".owl-carousel-anime .divArticleLancamentos a.item").mapNotNull { element ->
            try {
                element.toSearchResult()
            } catch (e: Exception) {
                null
            }
        }
    }

    private suspend fun createLoadResponseFromSite(
        document: org.jsoup.nodes.Document,
        url: String,
        title: String,
        year: Int?,
        isAnime: Boolean,
        isMovie: Boolean
    ): LoadResponse {
        
        // Poster do site
        val posterImg = document.selectFirst("img.transitioning_src, .sub_anime_img img, img")
        val posterUrl = posterImg?.attr("src") ?: posterImg?.attr("data-src")
        
        // Sinopse do site
        val plot = document.selectFirst(".divSinopse, .sinopse")?.text()?.trim()
        
        // Tags do site
        val tags = document.select(".animeInfo a.spanAnimeInfo, .spanGeneros").map { it.text().trim() }
            .takeIf { it.isNotEmpty() }?.toList()
        
        // Recomendações
        val siteRecommendations = extractRecommendationsFromSite(document)

        // Episódios do site
        val episodes = extractEpisodesFromSite(document, url, isAnime, isMovie)

        return if (isAnime && !isMovie) {
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = posterUrl?.let { fixUrl(it) }
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
            }
        } else {
            val playerUrl = if (episodes.isNotEmpty()) {
                episodes.first().data
            } else {
                url
            }
            
            newMovieLoadResponse(title, url, TvType.Movie, fixUrl(playerUrl)) {
                this.posterUrl = posterUrl?.let { fixUrl(it) }
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
            }
        }
    }

    private suspend fun createLoadResponseWithTVDB(
        tvdbInfo: TVDBInfo,
        url: String,
        document: org.jsoup.nodes.Document,
        siteRecommendations: List<SearchResponse>
    ): LoadResponse {

        // Episódios com dados TVDB
        val episodes = extractEpisodesWithTVDBInfo(
            document = document,
            url = url,
            tvdbInfo = tvdbInfo
        )

        return newTvSeriesLoadResponse(
            name = tvdbInfo.name ?: "",
            url = url,
            type = TvType.Anime,
            episodes = episodes
        ) {
            this.posterUrl = tvdbInfo.posterUrl?.let { fixUrl(it) }
            this.backgroundPosterUrl = tvdbInfo.bannerUrl?.let { fixUrl(it) }
            this.year = tvdbInfo.year
            this.plot = tvdbInfo.overview

            tvdbInfo.actors?.let { actors ->
                addActors(actors)
            }

            this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
        }
    }

    private suspend fun extractEpisodesFromSite(
        document: org.jsoup.nodes.Document,
        url: String,
        isAnime: Boolean,
        isMovie: Boolean
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()

        val episodeElements = document.select(".div_video_list a.lEp, a[href*='/animes/'], a.lep")
        
        episodeElements.forEachIndexed { index, element ->
            try {
                val episodeHref = element.attr("href") ?: return@forEachIndexed
                val episodeText = element.text().trim()
                
                val episodeNumber = Regex("Epis[oó]dio\\s*(\\d+)").find(episodeText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("Ep\\.?\\s*(\\d+)").find(episodeText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: (index + 1)
                
                episodes.add(
                    newEpisode(fixUrl(episodeHref)) {
                        this.name = "Episódio $episodeNumber"
                        this.episode = episodeNumber
                        this.season = 1
                    }
                )
            } catch (e: Exception) {
                // Ignorar erro
            }
        }

        return episodes.sortedBy { it.episode }.distinctBy { it.episode }
    }

    private suspend fun extractEpisodesWithTVDBInfo(
        document: org.jsoup.nodes.Document,
        url: String,
        tvdbInfo: TVDBInfo?
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()

        val episodeElements = document.select(".div_video_list a.lEp, a[href*='/animes/'], a.lep")
        
        episodeElements.forEachIndexed { index, element ->
            try {
                val episodeHref = element.attr("href") ?: return@forEachIndexed
                val episodeText = element.text().trim()
                
                // Extrair número do episódio
                val episodeNumber = extractEpisodeNumber(element, index + 1)
                val seasonNumber = 1 // AnimeFire geralmente tem só 1 temporada

                // Tentar encontrar episódio no TVDB
                val tvdbEpisode = findTVDBEpisode(tvdbInfo, seasonNumber, episodeNumber)

                val episode = if (tvdbEpisode != null) {
                    val descriptionWithDuration = buildDescriptionWithDuration(
                        tvdbEpisode.overview,
                        tvdbEpisode.runtime
                    )

                    newEpisode(fixUrl(episodeHref)) {
                        this.name = tvdbEpisode.name ?: "Episódio $episodeNumber"
                        this.season = tvdbEpisode.seasonNumber
                        this.episode = tvdbEpisode.episodeNumber
                        this.posterUrl = tvdbEpisode.image
                        this.description = descriptionWithDuration

                        tvdbEpisode.airDate?.let { airDate ->
                            try {
                                val dateFormatter = SimpleDateFormat("yyyy-MM-dd")
                                val date = dateFormatter.parse(airDate)
                                this.date = date.time
                            } catch (e: Exception) {}
                        }
                    }
                } else {
                    newEpisode(fixUrl(episodeHref)) {
                        this.name = "Episódio $episodeNumber"
                        this.season = seasonNumber
                        this.episode = episodeNumber
                    }
                }

                episodes.add(episode)
            } catch (e: Exception) {
                // Ignorar erro
            }
        }

        return episodes.sortedBy { it.episode }.distinctBy { it.episode }
    }

    private fun findTVDBEpisode(tvdbInfo: TVDBInfo?, season: Int, episode: Int): TVDBEpisodeInfo? {
        if (tvdbInfo == null) return null

        val episodes = tvdbInfo.seasonsEpisodes[season]
        return episodes?.find { it.episodeNumber == episode }
    }

    private fun buildDescriptionWithDuration(overview: String?, runtime: Int?): String? {
        return when {
            overview != null && runtime != null && runtime > 0 -> {
                "$overview\n\nDuração: $runtime min"
            }
            overview != null -> overview
            runtime != null && runtime > 0 -> "Duração: $runtime min"
            else -> null
        }
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
        return AnimeFireExtractor.extractVideoLinks(data, mainUrl, name, callback)
    }

    // ============ CLASSES DE DADOS TVDB ============

    private data class TVDBInfo(
        val id: Int,
        val name: String?,
        val year: Int?,
        val posterUrl: String?,
        val bannerUrl: String?,
        val overview: String?,
        val actors: List<Actor>?,
        val seasonsEpisodes: Map<Int, List<TVDBEpisodeInfo>>
    )

    private data class TVDBEpisodeInfo(
        val seasonNumber: Int,
        val episodeNumber: Int,
        val name: String,
        val overview: String?,
        val image: String?,
        val airDate: String?,
        val runtime: Int?
    )

    private data class TVDBLoginResponse(
        @JsonProperty("data") val data: TVDBTokenData
    )

    private data class TVDBTokenData(
        @JsonProperty("token") val token: String
    )

    private data class TVDBSearchResponse(
        @JsonProperty("data") val data: List<TVDBSeriesSearch>
    )

    private data class TVDBSeriesSearch(
        @JsonProperty("id") val id: Int,
        @JsonProperty("name") val name: String,
        @JsonProperty("year") val year: Int?,
        @JsonProperty("image") val image: String?
    )

    private data class TVDBSeriesDetailsResponse(
        @JsonProperty("data") val data: TVDBSeriesDetails
    )

    private data class TVDBSeriesDetails(
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("artworks") val artworks: List<TVDBArtwork>?
    )

    private data class TVDBArtwork(
        @JsonProperty("type") val type: Int,
        @JsonProperty("image") val image: String
    )

    private data class TVDBActorsResponse(
        @JsonProperty("data") val data: List<TVDBActor>
    )

    private data class TVDBActor(
        @JsonProperty("name") val name: String,
        @JsonProperty("image") val image: String?,
        @JsonProperty("role") val role: String?
    )

    private data class TVDBSeasonsResponse(
        @JsonProperty("data") val data: List<TVDBSeason>
    )

    private data class TVDBSeason(
        @JsonProperty("id") val id: Int,
        @JsonProperty("number") val number: Int?
    )

    private data class TVDBEpisodesResponse(
        @JsonProperty("data") val data: List<TVDBEpisode>
    )

    private data class TVDBEpisode(
        @JsonProperty("id") val id: Int,
        @JsonProperty("name") val name: String?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("number") val number: Int?,
        @JsonProperty("seasonNumber") val seasonNumber: Int?,
        @JsonProperty("image") val image: String?,
        @JsonProperty("airDate") val airDate: String?,
        @JsonProperty("runtime") val runtime: Int?
    )
}
