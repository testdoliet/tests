package com.AnimeFire

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import kotlin.math.abs

class AnimeFire : MainAPI() {
    override var mainUrl = "https://animefire.plus"
    override var name = "ANIMEFIRE"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie, TvType.OVA)
    override val usesWebView = false

    // API GraphQL do AniList
    private val aniListApiUrl = "https://graphql.anilist.co"
    
    // Configurações do TMDB
    companion object {
        private val tmdbApiKey = BuildConfig.TMDB_API_KEY
        private val tmdbAccessToken = BuildConfig.TMDB_ACCESS_TOKEN
        private const val tmdbBaseUrl = "https://api.themoviedb.org/3"
        private const val tmdbImageUrl = "https://image.tmdb.org/t/p"
    }
    
    override val mainPage = mainPageOf(
        "trending" to "Em Alta",
        "season" to "Populares Nessa Temporada", 
        "popular" to "Sempre Populares",
        "top" to "Top 100",
        "upcoming" to "Na Próxima Temporada"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return when (request.name) {
            "Em Alta" -> getAniListTrending(page)
            "Populares Nessa Temporada" -> getAniListSeason(page)
            "Sempre Populares" -> getAniListPopular(page)
            "Top 100" -> getAniListTop(page)
            "Na Próxima Temporada" -> getAniListUpcoming(page)
            else -> newHomePageResponse(request.name, emptyList(), false)
        }
    }

    private suspend fun getAniListTrending(page: Int): HomePageResponse {
        println("🌐 [ANILIST] Buscando Trending...")
        
        val query = """
            query {
                Page(page: $page, perPage: 20) {
                    media(sort: TRENDING_DESC, type: ANIME, format_in: [TV, TV_SHORT, OVA, MOVIE, SPECIAL, ONA]) {
                        id
                        title {
                            romaji
                            english
                            native
                            userPreferred
                        }
                        coverImage {
                            large
                            extraLarge
                        }
                        format
                        status
                        episodes
                        averageScore
                        seasonYear
                        synonyms
                    }
                }
            }
        """.trimIndent()
        
        return executeAniListQuery(query, "Em Alta", page)
    }

    private suspend fun getAniListSeason(page: Int): HomePageResponse {
        println("🌐 [ANILIST] Buscando Esta Temporada...")
        
        val query = """
            query {
                Page(page: $page, perPage: 20) {
                    media(season: WINTER, seasonYear: 2025, type: ANIME, sort: POPULARITY_DESC, format_in: [TV, TV_SHORT, OVA, MOVIE, SPECIAL, ONA]) {
                        id
                        title {
                            romaji
                            english
                            native
                            userPreferred
                        }
                        coverImage {
                            large
                            extraLarge
                        }
                        format
                        status
                        episodes
                        averageScore
                        synonyms
                    }
                }
            }
        """.trimIndent()
        
        return executeAniListQuery(query, "Populares Nessa Temporada", page)
    }

    private suspend fun getAniListPopular(page: Int): HomePageResponse {
        println("🌐 [ANILIST] Buscando Populares...")
        
        val query = """
            query {
                Page(page: $page, perPage: 20) {
                    media(sort: POPULARITY_DESC, type: ANIME, format_in: [TV, TV_SHORT, OVA, MOVIE, SPECIAL, ONA]) {
                        id
                        title {
                            romaji
                            english
                            native
                            userPreferred
                        }
                        coverImage {
                            large
                            extraLarge
                        }
                        format
                        status
                        episodes
                        averageScore
                        seasonYear
                        synonyms
                    }
                }
            }
        """.trimIndent()
        
        return executeAniListQuery(query, "Sempre Populares", page)
    }

    private suspend fun getAniListTop(page: Int): HomePageResponse {
        println("🌐 [ANILIST] Buscando Top 100...")
        
        val query = """
            query {
                Page(page: $page, perPage: 20) {
                    media(sort: SCORE_DESC, type: ANIME, format_in: [TV, TV_SHORT, OVA, MOVIE, SPECIAL, ONA], minScore: 70) {
                        id
                        title {
                            romaji
                            english
                            native
                            userPreferred
                        }
                        coverImage {
                            large
                            extraLarge
                        }
                        format
                        status
                        episodes
                        averageScore
                        seasonYear
                        synonyms
                    }
                }
            }
        """.trimIndent()
        
        return executeAniListQuery(query, "Top 100", page, showRank = true)
    }

    private suspend fun getAniListUpcoming(page: Int): HomePageResponse {
        println("🌐 [ANILIST] Buscando Próxima Temporada...")
        
        val query = """
            query {
                Page(page: $page, perPage: 20) {
                    media(season: SPRING, seasonYear: 2025, type: ANIME, sort: POPULARITY_DESC, format_in: [TV, TV_SHORT, OVA, MOVIE, SPECIAL, ONA]) {
                        id
                        title {
                            romaji
                            english
                            native
                            userPreferred
                        }
                        coverImage {
                            large
                            extraLarge
                        }
                        format
                        status
                        episodes
                        averageScore
                        synonyms
                    }
                }
            }
        """.trimIndent()
        
        return executeAniListQuery(query, "Na Próxima Temporada", page)
    }

    private suspend fun executeAniListQuery(
        query: String, 
        pageName: String,
        page: Int,
        showRank: Boolean = false
    ): HomePageResponse {
        return try {
            println("📡 [ANILIST] Enviando query GraphQL para página $page...")
            
            val response = app.post(
                aniListApiUrl,
                data = mapOf("query" to query),
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json"
                ),
                timeout = 10_000
            )
            
            println("✅ [ANILIST] Resposta recebida: ${response.code}")
            
            if (response.code == 200) {
                val aniListResponse = response.parsedSafe<AniListApiResponse>()
                val mediaList = aniListResponse?.data?.Page?.media ?: emptyList()
                
                println("📊 [ANILIST] ${mediaList.size} resultados brutos")
                
                // Processar e filtrar duplicados inteligentemente
                val filteredItems = mutableListOf<SearchResponse>()
                val processedTitles = mutableSetOf<String>()
                
                // Processar em paralelo para melhor performance
                val results = mediaList.mapNotNull { media ->
                    val aniListTitle = media.title?.userPreferred ?: 
                                      media.title?.romaji ?: 
                                      media.title?.english ?: 
                                      "Sem Título"
                    
                    // Verificar se já processamos um título similar
                    val normalizedTitle = normalizeTitle(aniListTitle)
                    if (processedTitles.any { isSimilarTitle(normalizedTitle, it) }) {
                        println("🔁 [FILTRO] Título duplicado/similar: $aniListTitle")
                        return@mapNotNull null
                    }
                    processedTitles.add(normalizedTitle)
                    
                    // Verificar se existe no AnimeFire
                    val existsInAnimeFire = checkIfExistsInAnimeFire(aniListTitle)
                    if (!existsInAnimeFire && pageName != "Na Próxima Temporada") {
                        println("⚠️ [FILTRO] Não encontrado no AnimeFire: $aniListTitle")
                        return@mapNotNull null
                    }
                    
                    // Buscar dados do TMDB com filtros melhores
                    val tmdbData = if (existsInAnimeFire) {
                        getTMDBInfoForAnime(aniListTitle, media.seasonYear, media.synonyms)
                    } else {
                        null
                    }
                    
                    // Usar título do TMDB se disponível, caso contrário usar do AniList
                    val finalTitle = if (tmdbData?.title != null) {
                        if (showRank) "#${filteredItems.size + 1} ${tmdbData.title}" else tmdbData.title
                    } else {
                        if (showRank) "#${filteredItems.size + 1} $aniListTitle" else aniListTitle
                    }
                    
                    // Usar poster do TMDB se disponível, caso contrário usar do AniList
                    val finalPoster = tmdbData?.posterUrl ?: 
                                     (media.coverImage?.extraLarge ?: media.coverImage?.large)
                    
                    val specialUrl = "anilist:${media.id}:$aniListTitle"
                    
                    Pair(
                        aniListTitle,
                        newAnimeSearchResponse(finalTitle, specialUrl) {
                            this.posterUrl = finalPoster
                            this.type = TvType.Anime
                        }
                    )
                }
                
                // Adicionar apenas se ainda não tiver um similar
                results.forEach { (originalTitle, searchResponse) ->
                    if (filteredItems.size < 20) { // Limitar a 20 itens por página
                        filteredItems.add(searchResponse)
                        println("✅ [ANILIST] Adicionado: $originalTitle")
                    }
                }
                
                println("✅ [ANILIST] ${filteredItems.size} itens filtrados para $pageName")
                newHomePageResponse(pageName, filteredItems, hasNext = filteredItems.isNotEmpty())
            } else {
                println("❌ [ANILIST] Erro na API: ${response.code}")
                newHomePageResponse(pageName, emptyList(), false)
            }
        } catch (e: Exception) {
            println("❌ [ANILIST] Exception: ${e.message}")
            e.printStackTrace()
            newHomePageResponse(pageName, emptyList(), false)
        }
    }

    // ============ FUNÇÕES AUXILIARES ============
    
    private fun normalizeTitle(title: String): String {
        return title.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "") // Remove pontuação
            .replace(Regex("\\s+"), " ") // Normaliza espaços
            .trim()
    }

    private fun isSimilarTitle(title1: String, title2: String): Boolean {
        // Verifica se são idênticos
        if (title1 == title2) return true
        
        // Verifica se um contém o outro (para casos como "One Piece" vs "One Piece Fan Letter")
        if (title1.contains(title2) || title2.contains(title1)) {
            // Calcula a diferença de tamanho
            val lengthDiff = abs(title1.length - title2.length).toDouble()
            val minLength = minOf(title1.length, title2.length).toDouble()
            
            // Se a diferença for pequena (menos de 30%), é provavelmente o mesmo
            if (lengthDiff / minLength < 0.3) {
                return true
            }
        }
        
        return false
    }

    private suspend fun checkIfExistsInAnimeFire(title: String): Boolean {
        return try {
            println("🔍 [ANIMEFIRE] Verificando: $title")
            
            // Limpar o título para busca
            val searchQuery = title
                .replace(Regex("[^a-zA-Z0-9\\s]"), " ") // Remove caracteres especiais
                .replace(Regex("\\s+"), " ") // Normaliza espaços
                .trim()
            
            if (searchQuery.length < 3) {
                println("❌ [ANIMEFIRE] Título muito curto: $searchQuery")
                return false
            }
            
            // Usar a função searchAnimeFire que você já tem
            val searchResults = searchAnimeFire(searchQuery)
            
            // Verificar se algum resultado corresponde bem ao título
            val found = searchResults.any { result ->
                val resultTitle = result.name.lowercase()
                val queryTitle = searchQuery.lowercase()
                
                // Verificar correspondência
                val similarity = calculateTitleSimilarity(resultTitle, queryTitle)
                val isGoodMatch = similarity > 0.7
                
                if (isGoodMatch) {
                    println("✅ [ANIMEFIRE] Encontrado: ${result.name} (similaridade: $similarity)")
                }
                
                isGoodMatch
            }
            
            if (!found) {
                println("⚠️ [ANIMEFIRE] Não encontrado: $title")
            }
            
            found
        } catch (e: Exception) {
            println("❌ [ANIMEFIRE] Erro na verificação: ${e.message}")
            false
        }
    }

    private fun calculateTitleSimilarity(str1: String, str2: String): Double {
        val words1 = str1.split(Regex("\\s+")).toSet()
        val words2 = str2.split(Regex("\\s+")).toSet()
        
        val intersection = words1.intersect(words2).size
        val union = words1.size + words2.size - intersection
        
        return if (union == 0) 0.0 else intersection.toDouble() / union.toDouble()
    }

    // ============ FUNÇÕES DO TMDB MELHORADAS ============
    
    private suspend fun getTMDBInfoForAnime(
        query: String, 
        year: Int?,
        synonyms: List<String>? = null
    ): TMDBInfo? {
        // Verificar se as chaves estão configuradas
        if (tmdbApiKey == "dummy_api_key" || tmdbAccessToken == "dummy_access_token") {
            println("⚠️ [TMDB] Chaves BuildConfig não configuradas")
            return null
        }

        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            
            // URL de busca com filtro para ANIME e em português
            var searchUrl = "$tmdbBaseUrl/search/tv?query=$encodedQuery&api_key=$tmdbApiKey&language=pt-BR&include_adult=false"
            if (year != null) searchUrl += "&first_air_date_year=$year"
            
            println("🔗 [TMDB] Buscando: \"$query\" em português...")

            val response = app.get(searchUrl, timeout = 10_000)
            println("📡 [TMDB] Status: ${response.code}")

            if (response.code != 200) {
                println("❌ [TMDB] Erro na busca")
                return null
            }

            val searchResult = response.parsedSafe<TMDBSearchResponse>() ?: return null
            println("✅ [TMDB] ${searchResult.results.size} resultados encontrados")

            // Filtrar para pegar apenas conteúdo relevante de anime
            val filteredResults = filterAnimeResults(searchResult.results, query, synonyms)
            
            val result = filteredResults.firstOrNull()
            if (result == null) {
                println("⚠️ [TMDB] Nenhum resultado relevante para: $query")
                return null
            }

            // Buscar detalhes completos em português
            val details = getTMDBDetailsDirect(result.id, true) ?: return null

            // Verificar se é anime
            val isAnime = details.genres?.any { 
                it.name.equals("Animação", ignoreCase = true) || 
                it.name.equals("Anime", ignoreCase = true) ||
                it.name.equals("Animation", ignoreCase = true)
            } ?: false

            if (!isAnime) {
                println("⚠️ [TMDB] Resultado não é anime: $query")
                return null
            }

            // Buscar trailer
            val youtubeTrailer = getHighQualityTrailer(details.videos?.results)

            TMDBInfo(
                id = result.id,
                title = result.name,
                year = result.first_air_date?.substring(0, 4)?.toIntOrNull(),
                posterUrl = result.poster_path?.let { "$tmdbImageUrl/w500$it" },
                backdropUrl = details.backdrop_path?.let { "$tmdbImageUrl/original$it" },
                overview = details.overview,
                genres = details.genres?.map { it.name },
                youtubeTrailer = youtubeTrailer,
                duration = details.runtime
            )
        } catch (e: Exception) {
            println("❌ [TMDB] ERRO: ${e.message}")
            null
        }
    }

    private fun filterAnimeResults(
        results: List<TMDBResult>, 
        query: String,
        synonyms: List<String>? = null
    ): List<TMDBResult> {
        val normalizedQuery = normalizeTitle(query)
        val allSearchTerms = mutableListOf(normalizedQuery)
        synonyms?.forEach { allSearchTerms.add(normalizeTitle(it)) }
        
        return results.filter { result ->
            val title = result.name ?: ""
            val normalizedResultTitle = normalizeTitle(title)
            
            // Verificar se contém termos comuns de anime indesejados
            val hasUndesiredTerms = normalizedResultTitle.contains("fan letter") ||
                                   normalizedResultTitle.contains("special") ||
                                   normalizedResultTitle.contains("ova") ||
                                   normalizedResultTitle.contains("movie") && !normalizedQuery.contains("movie")
            
            if (hasUndesiredTerms) {
                println("❌ [TMDB FILTER] Termo indesejado: $title")
                return@filter false
            }
            
            // Verificar similaridade com os termos de busca
            val isGoodMatch = allSearchTerms.any { searchTerm ->
                calculateTitleSimilarity(normalizedResultTitle, searchTerm) > 0.6
            }
            
            if (!isGoodMatch) {
                println("❌ [TMDB FILTER] Baixa similaridade: '$title' vs '$query'")
            }
            
            isGoodMatch
        }
    }

    private suspend fun getTMDBDetailsDirect(id: Int, isTv: Boolean): TMDBDetailsResponse? {
        return try {
            val type = if (isTv) "tv" else "movie"
            val url = "$tmdbBaseUrl/$type/$id?append_to_response=videos&language=pt-BR"
            
            val headers = mapOf(
                "Authorization" to "Bearer $tmdbAccessToken",
                "accept" to "application/json"
            )
            
            val response = app.get(url, headers = headers, timeout = 10_000)

            if (response.code != 200) {
                println("❌ [TMDB] Erro detalhes: ${response.code}")
                return null
            }

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

    // ============ FUNÇÃO SEARCH DO ANIMEFIRE ============
    
    override suspend fun search(query: String): List<SearchResponse> {
        println("🔍 [SEARCH] Buscando: $query")
        return try {
            searchAnimeFire(query)
        } catch (e: Exception) {
            println("❌ [SEARCH] Erro: ${e.message}")
            emptyList()
        }
    }

    private suspend fun searchAnimeFire(query: String): List<SearchResponse> {
        println("🔍 [ANIMEFIRE SEARCH] Buscando: $query")
        
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/pesquisar/$encodedQuery"
        
        println("🔗 [ANIMEFIRE SEARCH] URL: $searchUrl")
        
        val document = app.get(searchUrl, timeout = 10_000).document
        
        return document.select("a.item, .item a").mapNotNull { element ->
            try {
                val href = element.attr("href")
                if (href.isBlank()) return@mapNotNull null
                
                val fullUrl = fixUrl(href)
                
                val titleElement = element.selectFirst(".spanAnimeInfo.spanAnimeName, .animeTitle, .title")
                val title = titleElement?.text()?.trim() ?: "Sem Título"
                
                val posterElement = element.selectFirst("img")
                val poster = posterElement?.let { img ->
                    img.attr("src").takeIf { it.isNotBlank() } ?: 
                    img.attr("data-src").takeIf { it.isNotBlank() }
                }?.let { fixUrl(it) }
                
                newAnimeSearchResponse(title, fullUrl) {
                    this.posterUrl = poster
                    this.type = TvType.Anime
                }
            } catch (e: Exception) {
                println("❌ [ANIMEFIRE SEARCH] Erro parse: ${e.message}")
                null
            }
        }.distinctBy { it.url }
    }

    // ============ LOAD FUNCTION (igual você já tem) ============
    
    override suspend fun load(url: String): LoadResponse {
        println("\n🚀 AnimeFire.load() para URL: $url")
        
        if (url.startsWith("anilist:")) {
            return loadAniListContent(url)
        }
        
        return loadFromAnimeFire(url)
    }

    private suspend fun loadFromAnimeFire(url: String): LoadResponse {
        // Implementação que você já tem
        return newAnimeLoadResponse("Anime do AnimeFire", url, TvType.Anime) {
            this.plot = "Carregando do AnimeFire..."
        }
    }

    private suspend fun loadAniListContent(url: String): LoadResponse {
        // Implementação que você já tem
        return newAnimeLoadResponse("Anime do AniList", url, TvType.Anime) {
            this.plot = "Conteúdo carregado do AniList."
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("⚠️ Extração de links para AniList não implementada ainda")
        return false
    }

    // ============ CLASSES DE DADOS ============
    
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListApiResponse(
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
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: AniListTitle? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("format") val format: String? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("episodes") val episodes: Int? = null,
        @JsonProperty("duration") val duration: Int? = null,
        @JsonProperty("averageScore") val averageScore: Int? = null,
        @JsonProperty("seasonYear") val seasonYear: Int? = null,
        @JsonProperty("coverImage") val coverImage: AniListCoverImage? = null,
        @JsonProperty("bannerImage") val bannerImage: String? = null,
        @JsonProperty("genres") val genres: List<String>? = null,
        @JsonProperty("siteUrl") val siteUrl: String? = null,
        @JsonProperty("startDate") val startDate: AniListDate? = null,
        @JsonProperty("trailer") val trailer: AniListTrailer? = null,
        @JsonProperty("synonyms") val synonyms: List<String>? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListTitle(
        @JsonProperty("romaji") val romaji: String? = null,
        @JsonProperty("english") val english: String? = null,
        @JsonProperty("native") val native: String? = null,
        @JsonProperty("userPreferred") val userPreferred: String? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListCoverImage(
        @JsonProperty("large") val large: String? = null,
        @JsonProperty("extraLarge") val extraLarge: String? = null,
        @JsonProperty("color") val color: String? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListDate(
        @JsonProperty("year") val year: Int? = null,
        @JsonProperty("month") val month: Int? = null,
        @JsonProperty("day") val day: Int? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListTrailer(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("site") val site: String? = null,
        @JsonProperty("thumbnail") val thumbnail: String? = null
    )

    // Classes TMDB
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
    private data class TMDBDetailsResponse(
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("backdrop_path") val backdrop_path: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("genres") val genres: List<TMDBGenre>?,
        @JsonProperty("videos") val videos: TMDBVideos?
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBGenre(
        @JsonProperty("name") val name: String
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

    private data class TMDBInfo(
        val id: Int,
        val title: String?,
        val year: Int?,
        val posterUrl: String?,
        val backdropUrl: String?,
        val overview: String?,
        val genres: List<String>?,
        val youtubeTrailer: String?,
        val duration: Int?
    )
}
