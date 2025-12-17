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
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.gson.JsonParser
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class AnimeFire : MainAPI() {
    override var mainUrl = "https://animefire.io"
    override var name = "AnimeFire"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie, TvType.OVA)
    override val usesWebView = true

    companion object {
        private const val SEARCH_PATH = "/pesquisar"
        private const val MAX_TRIES = 3
        private const val RETRY_DELAY = 1000L
        private const val tmdbImageUrl = "https://image.tmdb.org/t/p"
        private const val TRANSLATION_ENABLED = true
        private const val WORKERS_URL = "https://animefire.euluan1912.workers.dev"
        private const val MAX_CACHE_SIZE = 500
        private const val ANIZIP_URL = "https://api.ani.zip/mappings"
    }

    // ============ TMDB COM BuildConfig ============
    private val tmdbApiKey = BuildConfig.TMDB_API_KEY
    private val tmdbAccessToken = BuildConfig.TMDB_ACCESS_TOKEN
    private val tmdbBaseUrl = "https://api.themoviedb.org/3"

    override val mainPage = mainPageOf(
        "$mainUrl" to "Lançamentos",
        "$mainUrl" to "Destaques da Semana",
        "$mainUrl" to "Últimos Animes Adicionados",
        "$mainUrl" to "Últimos Episódios Adicionados"
    )

    // ============ CACHE SIMPLES EM MEMÓRIA ============
    private val translationCache = mutableMapOf<String, String>()
    private val cacheHits = mutableMapOf<String, Int>()
    private val titleCache = mutableMapOf<String, String>() // Cache de títulos AniZip
    private val posterCache = mutableMapOf<String, String>() // Cache de posters AniZip
    
    // ============ LISTA DE ANIMES COM PROBLEMAS NO TMDB ============
    private val problematicTitles = listOf(
        "one piece" to "One Piece (Anime)",
        "naruto" to "Naruto (Anime)",
        "attack on titan" to "Attack on Titan (Anime)",
        "dragon ball" to "Dragon Ball (Anime)",
        "bleach" to "Bleach (Anime)",
        "my hero academia" to "My Hero Academia (Anime)",
        "demon slayer" to "Demon Slayer (Anime)",
        "jujutsu kaisen" to "Jujutsu Kaisen (Anime)",
        "hunter x hunter" to "Hunter x Hunter (Anime)",
        "fullmetal alchemist" to "Fullmetal Alchemist (Anime)",
        "death note" to "Death Note (Anime)",
        "tokyo ghoul" to "Tokyo Ghoul (Anime)",
        "sword art online" to "Sword Art Online (Anime)",
        "fairy tail" to "Fairy Tail (Anime)",
        "one punch man" to "One Punch Man (Anime)",
        "mob psycho 100" to "Mob Psycho 100 (Anime)",
        "vinland saga" to "Vinland Saga (Anime)",
        "chainsaw man" to "Chainsaw Man (Anime)",
        "spy x family" to "Spy x Family (Anime)",
        "berserk" to "Berserk (Anime)"
    )

    private fun cleanAnimeTitleForTMDB(title: String): String {
        val lowerTitle = title.lowercase()
        
        // Verificar se é um anime problemático conhecido
        problematicTitles.forEach { (keyword, corrected) ->
            if (lowerTitle.contains(keyword, ignoreCase = true)) {
                println("⚠️ [TMDB] Título problemático detectado: '$title'")
                println("✅ [TMDB] Usando título corrigido: '$corrected'")
                return corrected
            }
        }
        
        // Adicionar "(Anime)" para evitar confusão com live-action
        return if (!title.contains("(Anime)", ignoreCase = true) && 
                   !title.contains("Anime", ignoreCase = true)) {
            "$title (Anime)"
        } else {
            title
        }
    }

    // ============ FUNÇÕES ANIZIP ============
    
    private suspend fun getAniZipTitleAndPoster(searchTitle: String): Pair<String?, String?> {
        val cacheKey = searchTitle.lowercase()
        
        // Verificar cache primeiro
        val cachedTitle = titleCache[cacheKey]
        val cachedPoster = posterCache[cacheKey]
        if (cachedTitle != null || cachedPoster != null) {
            return Pair(cachedTitle, cachedPoster)
        }
        
        return try {
            val malId = searchMALIdByName(searchTitle)
            if (malId != null) {
                val anilistInfo = getAniListInfo(malId)
                val title = anilistInfo?.title?.romaji ?: anilistInfo?.title?.english
                val poster = anilistInfo?.coverImage?.large
                
                // Armazenar no cache
                if (title != null) titleCache[cacheKey] = title
                if (poster != null) posterCache[cacheKey] = poster
                
                Pair(title, poster)
            } else {
                Pair(null, null)
            }
        } catch (e: Exception) {
            println("❌ [ANIZIP] Erro ao buscar dados: ${e.message}")
            Pair(null, null)
        }
    }
    
    private suspend fun getAniListInfo(malId: Int): AniListMediaInfo? {
        return try {
            val query = """
                query {
                    Media(idMal: $malId, type: ANIME) {
                        title {
                            romaji
                            english
                            native
                        }
                        coverImage {
                            large
                            medium
                        }
                        bannerImage
                        description
                        episodes
                        duration
                        averageScore
                        startDate {
                            year
                            month
                            day
                        }
                        endDate {
                            year
                            month
                            day
                        }
                        genres
                    }
                }
            """.trimIndent()
            
            val response = app.post(
                "https://graphql.anilist.co",
                data = mapOf("query" to query),
                headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
                timeout = 10_000
            )
            
            if (response.code == 200) {
                val data = response.parsedSafe<AniListGraphQLResponse>()
                data?.data?.Media
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun translateWithCache(text: String): String {
        if (!TRANSLATION_ENABLED || text.isBlank() || text.length < 3) return text
        if (isProbablyPortuguese(text)) return text
        
        val cached = translationCache[text]
        if (cached != null) {
            cacheHits[text] = (cacheHits[text] ?: 0) + 1
            if (cacheHits[text] == 1) {
                println("⚡ [CACHE] Tradução em cache: \"${text.take(50)}...\"")
            }
            return cached
        }
        
        println("🌐 [CACHE] Traduzindo: \"${text.take(50)}...\"")
        val translated = translateText(text)
        
        if (translated != text && translated.isNotBlank()) {
            if (translationCache.size >= MAX_CACHE_SIZE) {
                val leastUsed = cacheHits.entries.sortedBy { it.value }.firstOrNull()
                leastUsed?.key?.let { 
                    translationCache.remove(it)
                    cacheHits.remove(it)
                }
            }
            
            translationCache[text] = translated
            cacheHits[text] = 0
            
            if (translationCache.size % 50 == 0) {
                println("📦 [CACHE] Armazenadas ${translationCache.size} traduções")
            }
        }
        
        return translated
    }
    
    private suspend fun translateText(text: String): String {
        if (!TRANSLATION_ENABLED || text.isBlank() || text.length < 3) return text
        
        if (isProbablyPortuguese(text)) return text
        
        return try {
            val workersTranslated = translateWithWorkers(text)
            if (workersTranslated != text) return workersTranslated
            
            translateDirectGoogle(text)
        } catch (e: Exception) {
            text
        }
    }
    
    private fun isProbablyPortuguese(text: String): Boolean {
        val portugueseWords = listOf("episódio", "temporada", "sinopse", "dublado", 
            "legendado", "assistir", "anime", "filme", "série", "ação", "aventura")
        
        val lowerText = text.lowercase()
        return portugueseWords.any { lowerText.contains(it) } ||
               lowerText.contains(Regex("[áéíóúãõç]"))
    }
    
    private suspend fun translateWithWorkers(text: String): String {
        return try {
            val encodedText = URLEncoder.encode(text, "UTF-8")
            val url = "$WORKERS_URL/translate?text=$encodedText&target=pt"
            
            println("🔍 [TRADUÇÃO] Chamando workers: ${url.take(80)}...")
            
            val response = app.get(url, timeout = 5000)
            
            println("📡 [TRADUÇÃO] Resposta workers: ${response.code}")
            
            if (response.code == 200) {
                val json = JsonParser.parseString(response.text)
                
                val translated = when {
                    json.isJsonObject -> {
                        json.asJsonObject.get("translatedText")?.asString
                            ?: json.asJsonObject.get("text")?.asString
                    }
                    json.isJsonPrimitive -> json.asString
                    else -> null
                }
                
                translated?.takeIf { it.isNotBlank() && it != text } ?: text
            } else {
                text
            }
        } catch (e: Exception) {
            println("❌ [TRADUÇÃO] Erro workers: ${e.message}")
            text
        }
    }
    
    private suspend fun translateDirectGoogle(text: String): String {
        return try {
            val encodedText = URLEncoder.encode(text, "UTF-8")
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=pt&dt=t&q=$encodedText"
            
            val response = app.get(url, timeout = 3000)
            
            if (response.code == 200) {
                val json = JsonParser.parseString(response.text)
                val translated = StringBuilder()
                
                json.asJsonArray?.get(0)?.asJsonArray?.forEach { arrayElement ->
                    arrayElement.asJsonArray?.get(0)?.asString?.let { 
                        translated.append(it) 
                    }
                }
                
                translated.toString().ifBlank { text }
            } else {
                text
            }
        } catch (e: Exception) {
            text
        }
    }

    // ============ CLASSES DE DADOS ============
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniZipImage(
        @JsonProperty("coverType") val coverType: String?,
        @JsonProperty("url") val url: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniZipEpisode(
        @JsonProperty("episode") val episode: String?,
        @JsonProperty("title") val title: Map<String, String>?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("image") val image: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("rating") val rating: String?,
        @JsonProperty("airDateUtc") val airDateUtc: String?,
        @JsonProperty("finaleType") val finaleType: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniZipData(
        @JsonProperty("titles") val titles: Map<String, String>? = null,
        @JsonProperty("images") val images: List<AniZipImage>? = null,
        @JsonProperty("episodes") val episodes: Map<String, AniZipEpisode>? = null
    )

    // ============ FUNÇÃO AUXILIAR DE BUSCA COM ANIZIP ============
    
    private suspend fun Element.toSearchResponse(): AnimeSearchResponse? = coroutineScope {
        val href = attr("href") ?: return@coroutineScope null
        if (href.isBlank()) return@coroutineScope null
        
        val titleElement = when {
            selectFirst("h3.animeTitle") != null -> selectFirst("h3.animeTitle")
            selectFirst(".text-block h3") != null -> selectFirst(".text-block h3")
            selectFirst(".animeTitle") != null -> selectFirst(".animeTitle")
            else -> selectFirst("h3")
        } ?: return@coroutineScope null
        
        val rawTitle = titleElement.text().trim()
        
        val cleanTitle = rawTitle
            .replace(Regex("(?i)(dublado|legendado|todos os episódios|\\(\\d{4}\\)|\\s*-\\s*$|\\(movie\\))"), "")
            .trim()
        
        val isMovie = href.contains("/filmes/") || 
                      rawTitle.contains("filme", ignoreCase = true) ||
                      rawTitle.contains("movie", ignoreCase = true)
        
        // Buscar dados do AniZip em paralelo
        val anizipData = async { getAniZipTitleAndPoster(cleanTitle) }
        
        val sitePoster = selectFirst("img.imgAnimes, img.card-img-top, img.transitioning_src, img.owl-lazy, img[src*='animes']")?.let { img ->
            when {
                img.hasAttr("data-src") -> img.attr("data-src")
                img.hasAttr("src") -> img.attr("src")
                else -> null
            }?.takeIf { !it.contains("logo", ignoreCase = true) }
        } ?: selectFirst("img:not([src*='logo']):not([src*='Logo'])")?.attr("src")

        // Usar AniZip se disponível, senão usar dados do site
        val (anizipTitle, anizipPoster) = anizipData.await()
        
        val finalTitle = anizipTitle ?: cleanTitle
        val finalPoster = anizipPoster ?: sitePoster?.let { fixUrl(it) }

        return@coroutineScope newAnimeSearchResponse(finalTitle, fixUrl(href)) {
            this.posterUrl = finalPoster
            this.type = if (isMovie) TvType.Movie else TvType.Anime
            
            // Log para debug
            if (anizipTitle != null) {
                println("✅ [ANIZIP] Título aprimorado: '$cleanTitle' → '$anizipTitle'")
            }
            if (anizipPoster != null) {
                println("✅ [ANIZIP] Poster aprimorado para: '$finalTitle'")
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        
        val homeItems = when (request.name) {
            "Lançamentos" -> 
                document.select(".owl-carousel-home .divArticleLancamentos a.item")
                    .mapNotNull { element -> 
                        runCatching { element.toSearchResponse() }.getOrNull()
                    }
            "Destaques da Semana" -> 
                document.select(".owl-carousel-semana .divArticleLancamentos a.item")
                    .mapNotNull { element -> 
                        runCatching { element.toSearchResponse() }.getOrNull()
                    }
            "Últimos Animes Adicionados" -> 
                document.select(".owl-carousel-l_dia .divArticleLancamentos a.item")
                    .mapNotNull { element -> 
                        runCatching { element.toSearchResponse() }.getOrNull()
                    }
            "Últimos Episódios Adicionados" -> {
                document.select(".divCardUltimosEpsHome").mapNotNull { card ->
                    runCatching {
                        val link = card.selectFirst("article.card a") ?: return@runCatching null
                        val href = link.attr("href") ?: return@runCatching null
                        
                        val titleElement = card.selectFirst("h3.animeTitle") ?: return@runCatching null
                        val rawTitle = titleElement.text().trim()
                        
                        val epNumber = card.selectFirst(".numEp")?.text()?.toIntOrNull() ?: 1
                        val cleanTitle = rawTitle.replace(Regex("(?i)(dublado|legendado|todos os episódios|\\(\\d{4}\\))$"), "").trim()
                        
                        // Buscar dados do AniZip
                        val (anizipTitle, anizipPoster) = getAniZipTitleAndPoster(cleanTitle)
                        val finalTitle = anizipTitle ?: cleanTitle
                        val displayTitle = "${finalTitle} - Episódio $epNumber"
                        
                        val sitePoster = card.selectFirst("img.imgAnimesUltimosEps, img[src*='animes']")?.let { img ->
                            when {
                                img.hasAttr("data-src") -> img.attr("data-src")
                                img.hasAttr("src") -> img.attr("src")
                                else -> null
                            }?.takeIf { !it.contains("logo", ignoreCase = true) }
                        } ?: card.selectFirst("img:not([src*='logo'])")?.attr("src")
                        
                        val finalPoster = anizipPoster ?: sitePoster?.let { fixUrl(it) }
                        
                        newAnimeSearchResponse(displayTitle, fixUrl(href)) {
                            this.posterUrl = finalPoster
                            this.type = TvType.Anime
                        }
                    }.getOrNull()
                }
            }
            else -> emptyList()
        }
        
        return newHomePageResponse(request.name, homeItems.distinctBy { it.url }, false)
    }

    // ============ FUNÇÃO SEARCH COM ANIZIP ============
    
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
            runCatching {
                val href = element.attr("href")
                if (href.isBlank()) {
                    println("⚠️ [SEARCH] Link vazio encontrado")
                    return@runCatching null
                }

                val titleElement = element.selectFirst("h3.animeTitle, .text-block h3, .animeTitle")
                val rawTitle = titleElement?.text()?.trim() ?: "Sem Título"
                
                val cleanTitle = rawTitle
                    .replace(Regex("\\s*-\\s*Todos os Episódios$"), "")
                    .replace(Regex("\\(Dublado\\)"), "")
                    .replace(Regex("\\(Legendado\\)"), "")
                    .trim()

                // Buscar dados do AniZip
                val (anizipTitle, anizipPoster) = getAniZipTitleAndPoster(cleanTitle)
                val finalTitle = anizipTitle ?: cleanTitle

                val imgElement = element.selectFirst("img.imgAnimes, img.card-img-top, img.transitioning_src")
                val sitePoster = when {
                    imgElement?.hasAttr("data-src") == true -> imgElement.attr("data-src")
                    imgElement?.hasAttr("src") == true -> imgElement.attr("src")
                    else -> null
                }

                val finalPoster = anizipPoster ?: sitePoster?.let { fixUrl(it) }

                val isMovie = href.contains("/filmes/") || 
                             cleanTitle.contains("filme", ignoreCase = true) ||
                             rawTitle.contains("filme", ignoreCase = true) ||
                             rawTitle.contains("movie", ignoreCase = true)

                println("✅ [SEARCH] Processado: '$finalTitle' | URL: ${href.take(50)}... | Tipo: ${if (isMovie) "Filme" else "Anime"}")

                newAnimeSearchResponse(finalTitle, fixUrl(href)) {
                    this.posterUrl = finalPoster
                    this.type = if (isMovie) {
                        TvType.Movie
                    } else {
                        TvType.Anime
                    }
                    
                    if (anizipTitle != null) {
                        println("✅ [ANIZIP-SEARCH] Título aprimorado: '$cleanTitle' → '$anizipTitle'")
                    }
                }
            }.getOrElse { e ->
                println("❌ [SEARCH] Erro ao processar elemento: ${e.message}")
                null
            }
        }.take(30)
    }

    // ============ LOAD PRINCIPAL COM TRADUÇÃO ============
    
    override suspend fun load(url: String): LoadResponse {
        println("\n🚀 AnimeFire.load() para URL: $url")
        
        val document = app.get(url).document

        val titleElement = document.selectFirst("h1.quicksand400, .main_div_anime_info h1, h1") ?: 
            throw ErrorLoadingException("Não foi possível encontrar o título")
        val rawTitle = titleElement.text().trim()
        
        val year = Regex("\\((\\d{4})\\)").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = rawTitle.replace(Regex("\\(\\d{4}\\)"), "").trim()
        
        val isMovie = url.contains("/filmes/") || rawTitle.contains("Movie", ignoreCase = true)
        val type = if (isMovie) TvType.Movie else TvType.Anime

        println("📌 Título: $cleanTitle, Ano: $year, Tipo: $type")

        // Buscar dados do AniZip primeiro
        val malId = searchMALIdByName(cleanTitle)
        println("🔍 MAL ID: $malId")

        var aniZipData: AniZipData? = null
        if (malId != null) {
            println("🔍 Buscando AniZip...")
            val syncMetaData = app.get("https://api.ani.zip/mappings?mal_id=$malId").text
            aniZipData = parseAnimeData(syncMetaData)
            println("✅ AniZip carregado: ${aniZipData?.episodes?.size ?: 0} episódios")
        }

        // CORREÇÃO: Limpar título para evitar confusão com live-action
        val tmdbSearchTitle = cleanAnimeTitleForTMDB(cleanTitle)
        val tmdbInfo = searchOnTMDB(tmdbSearchTitle, year, !isMovie)

        val siteMetadata = extractSiteMetadata(document)
        
        val episodes = if (!isMovie) {
            extractEpisodesFromSite(document, cleanTitle, aniZipData)
        } else {
            emptyList()
        }

        val recommendations = extractRecommendations(document)

        val data = document.selectFirst("div#media-info, div.anime-info")
        val genres = data?.select("div:contains(Genre:), div:contains(Gênero:) > span > a")?.map { it.text() }

        // Usar título do AniList se disponível
        val anilistInfo = if (malId != null) getAniListInfo(malId) else null
        val finalTitle = anilistInfo?.title?.romaji ?: anilistInfo?.title?.english ?: cleanTitle

        return createLoadResponseWithTranslation(
            url = url,
            cleanTitle = finalTitle,
            year = year,
            isMovie = isMovie,
            type = type,
            siteMetadata = siteMetadata,
            aniZipData = aniZipData,
            tmdbInfo = tmdbInfo,
            episodes = episodes,
            recommendations = recommendations,
            genres = genres,
            anilistInfo = anilistInfo
        )
    }

    private fun parseAnimeData(jsonString: String): AniZipData? {
        return try {
            val objectMapper = ObjectMapper()
            objectMapper.readValue(jsonString, AniZipData::class.java)
        } catch (e: Exception) {
            println("❌ [ANIZIP] Erro parse: ${e.message}")
            null
        }
    }

    private suspend fun createLoadResponseWithTranslation(
        url: String,
        cleanTitle: String,
        year: Int?,
        isMovie: Boolean,
        type: TvType,
        siteMetadata: SiteMetadata,
        aniZipData: AniZipData?,
        tmdbInfo: TMDBInfo?,
        episodes: List<Episode>,
        recommendations: List<SearchResponse>,
        genres: List<String>?,
        anilistInfo: AniListMediaInfo? = null
    ): LoadResponse {
        
        val finalPlot = if (TRANSLATION_ENABLED) {
            // Prioridade: AniList → site → TMDB → AniZip
            val plotToTranslate = anilistInfo?.description ?: 
                                 siteMetadata.plot ?: 
                                 tmdbInfo?.overview ?: 
                                 aniZipData?.episodes?.values?.firstOrNull()?.overview
            
            if (plotToTranslate != null && !isProbablyPortuguese(plotToTranslate)) {
                println("🔍 Traduzindo sinopse (com cache)...")
                val translated = translateWithCache(plotToTranslate)
                if (translated != plotToTranslate) {
                    println("✅ Sinopse traduzida!")
                    translated
                } else {
                    plotToTranslate
                }
            } else {
                plotToTranslate
            }
        } else {
            anilistInfo?.description ?: siteMetadata.plot ?: tmdbInfo?.overview ?: aniZipData?.episodes?.values?.firstOrNull()?.overview
        }
        
        // Prioridade de poster: AniList → TMDB → AniZip → site
        val finalPoster = anilistInfo?.coverImage?.large ?:
                         tmdbInfo?.posterUrl ?:
                         aniZipData?.images?.find { it.coverType.equals("Poster", ignoreCase = true) }?.url?.let { fixUrl(it) } ?:
                         siteMetadata.poster
        
        val finalBackdrop = anilistInfo?.bannerImage ?:
                           tmdbInfo?.backdropUrl ?:
                           aniZipData?.images?.find { it.coverType.equals("Fanart", ignoreCase = true) }?.url?.let { fixUrl(it) } ?:
                           siteMetadata.poster?.let { fixUrl(it) }
        
        val finalYear = anilistInfo?.startDate?.year ?:
                       tmdbInfo?.year ?: 
                       year ?: 
                       siteMetadata.year ?:
                       aniZipData?.episodes?.values?.firstOrNull()?.airDateUtc?.substring(0, 4)?.toIntOrNull()
        
        val finalTags = (anilistInfo?.genres ?: emptyList()) +
                       (tmdbInfo?.genres ?: emptyList()) + 
                       (genres ?: emptyList()) + 
                       (siteMetadata.tags ?: emptyList())

        if (translationCache.size % 20 == 0 && translationCache.isNotEmpty()) {
            val hits = cacheHits.values.sum()
            println("📊 [CACHE] ${translationCache.size} traduções | ${hits} hits salvos")
        }

        println("🏗️ Criando resposta final...")
        println("📖 Sinopse: ${finalPlot?.take(50)}...")
        println("📅 Ano: $finalYear")
        println("🏷️ Tags: ${finalTags.take(3).joinToString()}")
        println("📺 Episódios: ${episodes.size}")

        return if (isMovie) {
            newMovieLoadResponse(cleanTitle, url, type, url) {
                this.year = finalYear
                this.plot = finalPlot
                this.tags = finalTags.distinct().take(10)
                this.posterUrl = finalPoster
                this.backgroundPosterUrl = finalBackdrop
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
                
                tmdbInfo?.youtubeTrailer?.let { trailerUrl ->
                    addTrailer(trailerUrl)
                }
            }
        } else {
            newAnimeLoadResponse(cleanTitle, url, type) {
                addEpisodes(DubStatus.Subbed, episodes)
                
                this.year = finalYear
                this.plot = finalPlot
                this.tags = finalTags.distinct().take(10)
                this.posterUrl = finalPoster
                this.backgroundPosterUrl = finalBackdrop
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
                
                tmdbInfo?.youtubeTrailer?.let { trailerUrl ->
                    addTrailer(trailerUrl)
                }
            }
        }
    }

    private suspend fun extractEpisodesFromSite(
        document: org.jsoup.nodes.Document,
        animeTitle: String,
        aniZipData: AniZipData?
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        val episodeElements = document.select("a.lEp.epT, a.lEp, .divListaEps a, [href*='/video/'], [href*='/episodio/']")
        
        println("🔍 Encontrados ${episodeElements.size} episódios no site")
        
        episodeElements.forEachIndexed { index, element ->
            try {
                val href = element.attr("href")
                if (href.isBlank()) return@forEachIndexed
                
                val text = element.text().trim()
                if (text.isBlank()) return@forEachIndexed
                
                val episodeNumber = extractEpisodeNumber(text) ?: (index + 1)
                val seasonNumber = 1
                
                val aniZipEpisode = aniZipData?.episodes?.get(episodeNumber.toString())
                
                val episodeName = element.selectFirst(".ep-name, .title")?.text()?.trim()
                    ?: text.substringAfterLast("-").trim()
                    ?: "Episódio $episodeNumber"
                
                val finalEpisodeName = if (TRANSLATION_ENABLED && !isProbablyPortuguese(episodeName)) {
                    translateWithCache(episodeName)
                } else {
                    episodeName
                }
                
                val episodeDescription = if (aniZipEpisode?.overview != null && TRANSLATION_ENABLED) {
                    val overview = aniZipEpisode.overview!!
                    if (!isProbablyPortuguese(overview)) {
                        translateWithCache(overview)
                    } else {
                        overview
                    }
                } else {
                    aniZipEpisode?.overview ?: "Nenhuma descrição disponível"
                }

                episodes.add(
                    newEpisode(fixUrl(href)) {
                        this.name = finalEpisodeName
                        this.season = seasonNumber
                        this.episode = episodeNumber
                        this.description = episodeDescription
                        this.posterUrl = aniZipEpisode?.image ?: aniZipData?.images?.firstOrNull()?.url
                        this.score = Score.from10(aniZipEpisode?.rating)
                        this.runTime = aniZipEpisode?.runtime
                        
                        aniZipEpisode?.airDateUtc?.let { dateStr ->
                            try {
                                val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
                                val date = formatter.parse(dateStr)
                                this.date = date.time
                            } catch (e: Exception) {
                                // Ignorar erro de parsing
                            }
                        }
                    }
                )
                
                if (index % 10 == 0 || index == episodeElements.size - 1) {
                    println("✅ Ep $episodeNumber: $finalEpisodeName")
                }
                
            } catch (e: Exception) {
                println("❌ Erro episódio ${index + 1}: ${e.message}")
            }
        }
        
        return episodes.sortedBy { it.episode }
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
    
    private suspend fun searchMALIdByName(animeName: String): Int? {
        return try {
            val cleanName = animeName
                .replace(Regex("(?i)\\s*-\\s*Todos os Episódios"), "")
                .replace(Regex("(?i)\\s*\\(Dublado\\)"), "")
                .replace(Regex("(?i)\\s*\\(Legendado\\)"), "")
                .trim()
            
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
            
            if (response.code == 200) {
                val data = response.parsedSafe<AniListResponse>()
                data?.data?.Page?.media?.firstOrNull()?.idMal
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private data class SiteMetadata(
        val poster: String? = null,
        val plot: String? = null,
        val tags: List<String>? = null,
        val year: Int? = null
    )

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

    private suspend fun searchOnTMDB(query: String, year: Int?, isTv: Boolean): TMDBInfo? {
        // Verificar se as chaves estão configuradas
        if (tmdbApiKey == "dummy_api_key" || tmdbAccessToken == "dummy_access_token") {
            println("⚠️ [TMDB] Chaves BuildConfig não configuradas - usando fallback")
            return null // Não usar fallback para evitar confusão com live-action
        }

        return try {
            val type = if (isTv) "tv" else "movie"
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            
            // URL de busca DIRETA com API Key
            var searchUrl = "$tmdbBaseUrl/search/$type?query=$encodedQuery&api_key=$tmdbApiKey&language=pt-BR"
            if (year != null) searchUrl += "&year=$year"
            
            println("🔗 [TMDB] Buscando direto: ${searchUrl.take(100)}...")

            val response = app.get(searchUrl, timeout = 10_000)
            println("📡 [TMDB] Status direto: ${response.code}")

            if (response.code != 200) {
                println("❌ [TMDB] Erro na busca direta")
                return null
            }

            val searchResult = response.parsedSafe<TMDBSearchResponse>() ?: return null
            println("✅ [TMDB] Parsing OK! Resultados: ${searchResult.results.size}")

            // Filtrar para evitar live-action
            val result = searchResult.results.firstOrNull { result ->
                val title = (result.name ?: result.title ?: "").lowercase()
                // Evitar resultados que são claramente live-action
                !title.contains("live action", ignoreCase = true) &&
                !title.contains("la série", ignoreCase = true) &&
                !title.contains("a série", ignoreCase = true) &&
                !title.contains("live-action", ignoreCase = true)
            } ?: searchResult.results.firstOrNull()

            if (result == null) {
                println("⚠️ [TMDB] Nenhum resultado adequado encontrado (evitando live-action)")
                return null
            }

            println("✅ [TMDB] Resultado selecionado: ${result.name ?: result.title}")

            // Buscar detalhes completos com Access Token
            val details = getTMDBDetailsDirect(result.id, isTv) ?: return null

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
                youtubeTrailer = youtubeTrailer,
                duration = details.runtime
            )
        } catch (e: Exception) {
            println("❌ [TMDB] ERRO na busca direta TMDB: ${e.message}")
            null
        }
    }

    private suspend fun getTMDBDetailsDirect(id: Int, isTv: Boolean): TMDBDetailsResponse? {
        println("🔍 [TMDB] Buscando detalhes DIRETOS para ID $id")
        
        return try {
            val type = if (isTv) "tv" else "movie"
            // Usar Access Token para detalhes
            val url = "$tmdbBaseUrl/$type/$id?append_to_response=videos&language=pt-BR"
            
            val headers = mapOf(
                "Authorization" to "Bearer $tmdbAccessToken",
                "accept" to "application/json"
            )
            
            println("🔗 [TMDB] URL detalhes diretos: $url")
            
            val response = app.get(url, headers = headers, timeout = 10_000)
            println("📡 [TMDB] Status detalhes diretos: ${response.code}")

            if (response.code != 200) {
                println("❌ [TMDB] Erro detalhes diretos: ${response.code}")
                return null
            }

            response.parsedSafe<TMDBDetailsResponse>()
        } catch (e: Exception) {
            println("❌ [TMDB] ERRO detalhes diretos: ${e.message}")
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

    private suspend fun extractRecommendations(document: org.jsoup.nodes.Document): List<SearchResponse> {
        return document.select(".owl-carousel-anime .divArticleLancamentos a.item")
            .mapNotNull { element -> 
                runCatching { element.toSearchResponse() }.getOrNull()
            }
    }

    override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    // Usar o extractor registrado
    return loadExtractor(data, "$mainUrl/", subtitleCallback, callback)
    }

    // ============ CLASSES DE DADOS ADICIONAIS ============
    
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListGraphQLResponse(
        @JsonProperty("data") val data: AniListData? = null
    )
    
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListData(
        @JsonProperty("Media") val Media: AniListMediaInfo? = null
    )
    
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListMediaInfo(
        @JsonProperty("title") val title: AniListTitle? = null,
        @JsonProperty("coverImage") val coverImage: AniListCoverImage? = null,
        @JsonProperty("bannerImage") val bannerImage: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("episodes") val episodes: Int? = null,
        @JsonProperty("duration") val duration: Int? = null,
        @JsonProperty("averageScore") val averageScore: Int? = null,
        @JsonProperty("startDate") val startDate: AniListDate? = null,
        @JsonProperty("endDate") val endDate: AniListDate? = null,
        @JsonProperty("genres") val genres: List<String>? = null
    )
    
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListTitle(
        @JsonProperty("romaji") val romaji: String? = null,
        @JsonProperty("english") val english: String? = null,
        @JsonProperty("native") val native: String? = null
    )
    
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListCoverImage(
        @JsonProperty("large") val large: String? = null,
        @JsonProperty("medium") val medium: String? = null
    )
    
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListDate(
        @JsonProperty("year") val year: Int? = null,
        @JsonProperty("month") val month: Int? = null,
        @JsonProperty("day") val day: Int? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListResponse(
        @JsonProperty("data") val data: AniListPageData? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListPageData(
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
