package com.AnimeFire

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import kotlinx.coroutines.delay
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.gson.JsonParser
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.io.File

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
        
        // Cache para metadados dos animes
        private val animeMetadataCache = mutableMapOf<String, AnimeMetadata>()
        private const val CACHE_FILE = "animefire_metadata_cache.json"
    }

    // ============ TMDB COM BuildConfig ============
    private val tmdbApiKey = BuildConfig.TMDB_API_KEY
    private val tmdbAccessToken = BuildConfig.TMDB_ACCESS_TOKEN
    private val tmdbBaseUrl = "https://api.themoviedb.org/3"

    override val mainPage = mainPageOf(
        "$mainUrl" to "Lançamentos",
        "$mainUrl" to "Destaques da Semana",
        "$mainUrl" to "Em Breve",  // Alterado de "Últimos Animes Adicionados"
        "$mainUrl" to "Últimos Episódios Adicionados"
    )

    // ============ CACHE SIMPLES EM MEMÓRIA ============
    private val translationCache = mutableMapOf<String, String>()
    private val cacheHits = mutableMapOf<String, Int>()
   
    // ============ NOVAS CLASSES DE DADOS ============
    
    data class AnimeMetadata(
        val malId: Int? = null,
        val titleEnglish: String? = null,
        val titleRomaji: String? = null,
        val titlePortuguese: String? = null,
        val posterUrl: String? = null,
        val bannerUrl: String? = null,
        val coverImage: String? = null,
        val hasEpisodes: Boolean = true,
        val isUpcoming: Boolean = false,
        val cachedAt: Long = System.currentTimeMillis()
    )

// ============ SISTEMA DE CACHE DE METADADOS ============
    
private suspend fun getAnimeMetadata(animeName: String, url: String? = null): AnimeMetadata? {
    val cacheKey = animeName.lowercase().trim()
    
    // Verificar cache em memória primeiro
    animeMetadataCache[cacheKey]?.let { cached ->
        // Cache válido por 7 dias
        if (System.currentTimeMillis() - cached.cachedAt < 7 * 24 * 60 * 60 * 1000) {
            return cached
        }
    }
    
    // Buscar MAL ID
    val malId = searchMALIdByName(animeName)
    
    // Sempre obter o valor de hasEpisodes (não nullable)
    val hasEpsResult = checkIfHasEpisodes(url)
    val hasEps = hasEpsResult ?: true  // Converte para non-nullable
    
    if (malId == null) {
        // Se não encontrar MAL ID, criar metadados básicos
        val basicMetadata = AnimeMetadata(
            titleEnglish = animeName,
            hasEpisodes = hasEps,
            isUpcoming = !hasEps  // Se não tem episódios, considera como "em breve"
        )
        animeMetadataCache[cacheKey] = basicMetadata
        saveMetadataCache()
        return basicMetadata
    }
    
    // Buscar metadados completos do AniList
    val metadata = fetchAniListMetadata(malId, hasEps)
    animeMetadataCache[cacheKey] = metadata
    saveMetadataCache()
    
    return metadata
}

private suspend fun fetchAniListMetadata(malId: Int, hasEpsFromUrl: Boolean): AnimeMetadata {
    return try {
        val query = """
            query {
                Media(idMal: $malId, type: ANIME) {
                    title {
                        english
                        romaji
                        native
                    }
                    coverImage {
                        extraLarge
                        large
                        medium
                    }
                    bannerImage
                    episodes
                    status
                    nextAiringEpisode {
                        episode
                        timeUntilAiring
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
            val data = response.parsedSafe<AniListFullResponse>()
            val media = data?.data?.Media
            
            if (media != null) {
                // Usar o valor de hasEpsFromUrl (já convertido para non-nullable)
                // ou verificar se tem episódios no AniList
                val hasEps = if (hasEpsFromUrl) {
                    true
                } else {
                    (media.episodes ?: 0) > 0
                }
                
                val isUpcoming = media.status == "NOT_YET_RELEASED" || 
                                 media.nextAiringEpisode != null
                
                return AnimeMetadata(
                    malId = malId,
                    titleEnglish = media.title?.english ?: media.title?.romaji ?: "",
                    titleRomaji = media.title?.romaji,
                    titlePortuguese = media.title?.native,
                    posterUrl = media.coverImage?.extraLarge ?: 
                               media.coverImage?.large ?: 
                               media.coverImage?.medium,
                    bannerUrl = media.bannerImage,
                    hasEpisodes = hasEps,      // Non-nullable
                    isUpcoming = isUpcoming    // Non-nullable
                )
            }
        }
        
        // Fallback básico - usa o valor já verificado
        AnimeMetadata(
            malId = malId,
            titleEnglish = "",
            hasEpisodes = hasEpsFromUrl,
            isUpcoming = !hasEpsFromUrl
        )
    } catch (e: Exception) {
        println("❌ Erro ao buscar metadados AniList: ${e.message}")
        AnimeMetadata(
            malId = malId,
            titleEnglish = "",
            hasEpisodes = hasEpsFromUrl,
            isUpcoming = !hasEpsFromUrl
        )
    }
}
            
    private suspend fun checkIfHasEpisodes(url: String?): Boolean? {
        if (url == null) return null
        
        return try {
            val document = app.get(url).document
            val episodeElements = document.select("a.lEp.epT, a.lEp, .divListaEps a, [href*='/video/'], [href*='/episodio/']")
            episodeElements.isNotEmpty()
        } catch (e: Exception) {
            null
        }
    }
    
    private suspend fun saveMetadataCache() {
        // Implementação simplificada - em produção usar arquivo JSON
        println("💾 Cache de metadados: ${animeMetadataCache.size} itens")
    }
    
    private suspend fun loadMetadataCache() {
        // Carregar cache do arquivo se existir
        println("📥 Cache de metadados carregado")
    }

    // ============ FUNÇÃO AUXILIAR DE BUSCA ATUALIZADA ============
    
    private suspend fun Element.toSearchResponse(): AnimeSearchResponse? {
        val href = attr("href") ?: return null
        if (href.isBlank()) return null
        
        val titleElement = when {
            selectFirst("h3.animeTitle") != null -> selectFirst("h3.animeTitle")
            selectFirst(".text-block h3") != null -> selectFirst(".text-block h3")
            selectFirst(".animeTitle") != null -> selectFirst(".animeTitle")
            else -> selectFirst("h3")
        } ?: return null
        
        val rawTitle = titleElement.text().trim()
        
        val cleanTitle = rawTitle
            .replace(Regex("(?i)(dublado|legendado|todos os episódios|\\(\\d{4}\\)|\\s*-\\s*$|\\(movie\\))"), "")
            .trim()
        
        val isMovie = href.contains("/filmes/") || 
                      rawTitle.contains("filme", ignoreCase = true) ||
                      rawTitle.contains("movie", ignoreCase = true)
        
        // Obter metadados melhorados
        val metadata = getAnimeMetadata(cleanTitle, href)
        
        // Usar título em inglês se disponível, senão manter o original
        val finalTitle = metadata?.titleEnglish?.ifBlank { cleanTitle } ?: cleanTitle
        
        // Usar imagem do AniZip/AniList se disponível, senão usar do site
        val sitePoster = selectFirst("img.imgAnimes, img.card-img-top, img.transitioning_src, img.owl-lazy, img[src*='animes']")?.let { img ->
            when {
                img.hasAttr("data-src") -> img.attr("data-src")
                img.hasAttr("src") -> img.attr("src")
                else -> null
            }?.takeIf { !it.contains("logo", ignoreCase = true) }
        } ?: selectFirst("img:not([src*='logo']):not([src*='Logo'])")?.attr("src")
        
        val finalPoster = metadata?.posterUrl ?: sitePoster?.let { fixUrl(it) }
        
        return newAnimeSearchResponse(finalTitle, fixUrl(href)) {
            this.posterUrl = finalPoster
            this.type = if (isMovie) TvType.Movie else TvType.Anime
        }
    }

    // ============ GET MAIN PAGE ATUALIZADA ============
    
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        
        val homeItems = when (request.name) {
            "Lançamentos" -> {
                val allItems = document.select(".owl-carousel-home .divArticleLancamentos a.item")
                    .mapNotNull { element -> 
                        runCatching { element.toSearchResponse() }.getOrNull()
                    }
                // Filtrar para remover "em breve"
                allItems.filter { item ->
                    val metadata = getAnimeMetadata(item.name, item.url)
                    !(metadata?.isUpcoming == true || metadata?.hasEpisodes == false)
                }
            }
            
            "Destaques da Semana" -> {
                val allItems = document.select(".owl-carousel-semana .divArticleLancamentos a.item")
                    .mapNotNull { element -> 
                        runCatching { element.toSearchResponse() }.getOrNull()
                    }
                // Filtrar para remover "em breve"
                allItems.filter { item ->
                    val metadata = getAnimeMetadata(item.name, item.url)
                    !(metadata?.isUpcoming == true || metadata?.hasEpisodes == false)
                }
            }
            
            "Em Breve" -> {
                // Coletar todos os animes da página
                val allAnimes = mutableListOf<AnimeSearchResponse>()
                
                // Animes sem episódios do carrossel "l_dia"
                val upcomingFromSite = document.select(".owl-carousel-l_dia .divArticleLancamentos a.item")
                    .mapNotNull { element -> 
                        runCatching { element.toSearchResponse() }.getOrNull()
                    }
                    .filter { item ->
                        val metadata = getAnimeMetadata(item.name, item.url)
                        metadata?.isUpcoming == true || metadata?.hasEpisodes == false
                    }
                
                allAnimes.addAll(upcomingFromSite)
                
                // Também verificar outras seções para animes sem episódios
                val otherSections = listOf(
                    ".owl-carousel-home .divArticleLancamentos a.item",
                    ".owl-carousel-semana .divArticleLancamentos a.item"
                )
                
                otherSections.forEach { selector ->
                    document.select(selector).forEach { element ->
                        runCatching {
                            element.toSearchResponse()?.let { item ->
                                val metadata = getAnimeMetadata(item.name, item.url)
                                if (metadata?.isUpcoming == true || metadata?.hasEpisodes == false) {
                                    allAnimes.add(item)
                                }
                            }
                        }
                    }
                }
                
                allAnimes.distinctBy { it.url }
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
                        
                        // Obter metadados melhorados
                        val metadata = getAnimeMetadata(cleanTitle, href)
                        val finalTitle = metadata?.titleEnglish?.ifBlank { cleanTitle } ?: cleanTitle
                        
                        val displayTitle = "$finalTitle - Episódio $epNumber"
                        
                        // Usar imagem do AniZip/AniList se disponível
                        val sitePoster = card.selectFirst("img.imgAnimesUltimosEps, img[src*='animes']")?.let { img ->
                            when {
                                img.hasAttr("data-src") -> img.attr("data-src")
                                img.hasAttr("src") -> img.attr("src")
                                else -> null
                            }?.takeIf { !it.contains("logo", ignoreCase = true) }
                        } ?: card.selectFirst("img:not([src*='logo'])")?.attr("src")
                        
                        val finalPoster = metadata?.posterUrl ?: sitePoster?.let { fixUrl(it) }
                        
                        newAnimeSearchResponse(displayTitle, fixUrl(href)) {
                            this.posterUrl = finalPoster
                            this.type = TvType.Anime
                        }
                    }.getOrNull()
                }.filter { item ->
                    // Filtrar para remover "em breve"
                    val cleanName = item.name.replace(Regex(" - Episódio \\d+$"), "")
                    val metadata = getAnimeMetadata(cleanName, item.url)
                    !(metadata?.isUpcoming == true || metadata?.hasEpisodes == false)
                }
            }
            
            else -> emptyList()
        }
        
        println("🏠 ${request.name}: ${homeItems.size} itens")
        return newHomePageResponse(request.name, homeItems.distinctBy { it.url }, false)
    }

    // ============ LOAD PRINCIPAL ATUALIZADA ============
    
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

        // Obter metadados melhorados
        val metadata = getAnimeMetadata(cleanTitle, url)
        val finalTitle = metadata?.titleEnglish?.ifBlank { cleanTitle } ?: cleanTitle

        val malId = metadata?.malId ?: searchMALIdByName(cleanTitle)
        println("🔍 MAL ID: $malId")

        var aniZipData: AniZipData? = null
        if (malId != null) {
            println("🔍 Buscando AniZip...")
            try {
                val syncMetaData = app.get("https://api.ani.zip/mappings?mal_id=$malId").text
                aniZipData = parseAnimeData(syncMetaData)
                println("✅ AniZip carregado: ${aniZipData?.episodes?.size ?: 0} episódios")
            } catch (e: Exception) {
                println("⚠️ Erro ao buscar AniZip: ${e.message}")
            }
        }

        // TMDB
        val tmdbInfo = searchOnTMDB(finalTitle, year, !isMovie)

        val siteMetadata = extractSiteMetadata(document)
        
        val episodes = if (!isMovie) {
            extractEpisodesFromSite(document, finalTitle, aniZipData)
        } else {
            emptyList()
        }

        val recommendations = extractRecommendations(document)

        val data = document.selectFirst("div#media-info, div.anime-info")
        val genres = data?.select("div:contains(Genre:), div:contains(Gênero:) > span > a")?.map { it.text() }

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
            metadata = metadata
        )
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
        metadata: AnimeMetadata? = null
    ): LoadResponse {
        
        val finalPlot = if (TRANSLATION_ENABLED && siteMetadata.plot != null) {
            val originalPlot = siteMetadata.plot!!
            if (!isProbablyPortuguese(originalPlot)) {
                println("🔍 Traduzindo sinopse (com cache)...")
                val translated = translateWithCache(originalPlot)
                if (translated != originalPlot) {
                    println("✅ Sinopse traduzida!")
                    translated
                } else {
                    originalPlot
                }
            } else {
                originalPlot
            }
        } else {
            siteMetadata.plot ?: tmdbInfo?.overview ?: aniZipData?.episodes?.values?.firstOrNull()?.overview
        }
        
        // USAR THUMB COMO BANNER SE NÃO TIVER BANNER
        val finalPoster = tmdbInfo?.posterUrl ?:
                         metadata?.posterUrl ?:
                         aniZipData?.images?.find { it.coverType.equals("Poster", ignoreCase = true) }?.url?.let { fixUrl(it) } ?:
                         siteMetadata.poster
        
        // SE NÃO TIVER BANNER, USAR A THUMB COMO BANNER
        val finalBackdrop = tmdbInfo?.backdropUrl ?:
                           metadata?.bannerUrl ?:
                           metadata?.posterUrl ?:  // Usar poster como fallback para banner
                           aniZipData?.images?.find { it.coverType.equals("Fanart", ignoreCase = true) }?.url?.let { fixUrl(it) } ?:
                           finalPoster  // Usar o poster como banner se não tiver banner específico
        
        val finalYear = tmdbInfo?.year ?: year ?: siteMetadata.year ?:
                       aniZipData?.episodes?.values?.firstOrNull()?.airDateUtc?.substring(0, 4)?.toIntOrNull()
        
        val finalTags = (tmdbInfo?.genres ?: emptyList()) + 
                       (genres ?: emptyList()) + 
                       (siteMetadata.tags ?: emptyList())

        println("🏗️ Criando resposta final...")
        println("📖 Sinopse: ${finalPlot?.take(50)}...")
        println("📅 Ano: $finalYear")
        println("🏷️ Tags: ${finalTags.take(3).joinToString()}")
        println("📺 Episódios: ${episodes.size}")
        println("🖼️ Poster: ${finalPoster?.take(50)}...")
        println("🎬 Banner: ${finalBackdrop?.take(50)}...")

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

    // ============ FUNÇÕES DE TRADUÇÃO (mantidas) ============
    
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

    // ============ OUTRAS FUNÇÕES (mantidas) ============
    
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

                // Obter metadados melhorados
                val metadata = getAnimeMetadata(cleanTitle, href)
                val finalTitle = metadata?.titleEnglish?.ifBlank { cleanTitle } ?: cleanTitle

                val imgElement = element.selectFirst("img.imgAnimes, img.card-img-top, img.transitioning_src")
                val sitePoster = when {
                    imgElement?.hasAttr("data-src") == true -> imgElement.attr("data-src")
                    imgElement?.hasAttr("src") == true -> imgElement.attr("src")
                    else -> null
                }

                val finalPoster = metadata?.posterUrl ?: sitePoster?.let { fixUrl(it) }

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
                }
            }.getOrElse { e ->
                println("❌ [SEARCH] Erro ao processar elemento: ${e.message}")
                null
            }
        }.take(30)
    }

    // ============ CLASSES DE DADOS ADICIONAIS ============
    
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniListFullResponse(
        @JsonProperty("data") val data: AniListFullData? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniListFullData(
        @JsonProperty("Media") val Media: AniListFullMedia? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniListFullMedia(
        @JsonProperty("title") val title: AniListTitle? = null,
        @JsonProperty("coverImage") val coverImage: AniListCoverImage? = null,
        @JsonProperty("bannerImage") val bannerImage: String? = null,
        @JsonProperty("episodes") val episodes: Int? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("nextAiringEpisode") val nextAiringEpisode: AniListAiringEpisode? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniListTitle(
        @JsonProperty("english") val english: String? = null,
        @JsonProperty("romaji") val romaji: String? = null,
        @JsonProperty("native") val native: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniListCoverImage(
        @JsonProperty("extraLarge") val extraLarge: String? = null,
        @JsonProperty("large") val large: String? = null,
        @JsonProperty("medium") val medium: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniListAiringEpisode(
        @JsonProperty("episode") val episode: Int? = null,
        @JsonProperty("timeUntilAiring") val timeUntilAiring: Int? = null
    )

    // ============ FUNÇÕES EXISTENTES (mantidas) ============
    
    private fun parseAnimeData(jsonString: String): AniZipData? {
        return try {
            val objectMapper = ObjectMapper()
            objectMapper.readValue(jsonString, AniZipData::class.java)
        } catch (e: Exception) {
            println("❌ [ANIZIP] Erro parse: ${e.message}")
            null
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
        if (tmdbApiKey == "dummy_api_key" || tmdbAccessToken == "dummy_access_token") {
            println("⚠️ [TMDB] Chaves BuildConfig não configuradas - usando fallback")
            return searchOnTMDBFallback(query, year, isTv)
        }

        return try {
            val type = if (isTv) "tv" else "movie"
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            
            var searchUrl = "$tmdbBaseUrl/search/$type?query=$encodedQuery&api_key=$tmdbApiKey&language=pt-BR"
            if (year != null) searchUrl += "&year=$year"
            
            println("🔗 [TMDB] Buscando direto: ${searchUrl.take(100)}...")

            val response = app.get(searchUrl, timeout = 10_000)
            println("📡 [TMDB] Status direto: ${response.code}")

            if (response.code != 200) {
                println("❌ [TMDB] Erro na busca direta")
                return searchOnTMDBFallback(query, year, isTv)
            }

            val searchResult = response.parsedSafe<TMDBSearchResponse>() ?: return null
            println("✅ [TMDB] Parsing OK! Resultados: ${searchResult.results.size}")

            val result = searchResult.results.firstOrNull() ?: return null

            val details = getTMDBDetailsDirect(result.id, isTv) ?: return null

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
            searchOnTMDBFallback(query, year, isTv)
        }
    }

    private suspend fun searchOnTMDBFallback(query: String, year: Int?, isTv: Boolean): TMDBInfo? {
        val TMDB_FALLBACK_PROXY = "https://lawliet.euluan1912.workers.dev"
        
        return try {
            val type = if (isTv) "tv" else "movie"
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val yearParam = year?.let { "&year=$it" } ?: ""

            val searchUrl = "$TMDB_FALLBACK_PROXY/search?query=$encodedQuery&type=$type$yearParam"
            println("🔗 [TMDB FALLBACK] Usando proxy: $searchUrl")

            val response = app.get(searchUrl, timeout = 10_000)
            println("📡 [TMDB FALLBACK] Status proxy: ${response.code}")

            if (response.code != 200) return null

            val searchResult = response.parsedSafe<TMDBSearchResponse>() ?: return null
            println("✅ [TMDB FALLBACK] Parsing proxy OK!")

            val result = searchResult.results.firstOrNull() ?: return null

            val details = getTMDBDetailsViaProxy(result.id, isTv) ?: return null

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
            println("❌ [TMDB FALLBACK] ERRO no proxy: ${e.message}")
            null
        }
    }

    private suspend fun getTMDBDetailsDirect(id: Int, isTv: Boolean): TMDBDetailsResponse? {
        println("🔍 [TMDB] Buscando detalhes DIRETOS para ID $id")
        
        return try {
            val type = if (isTv) "tv" else "movie"
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

    private suspend fun getTMDBDetailsViaProxy(id: Int, isTv: Boolean): TMDBDetailsResponse? {
        val TMDB_FALLBACK_PROXY = "https://lawliet.euluan1912.workers.dev"
        
        println("🔍 [TMDB] Buscando detalhes via proxy para ID $id")

        return try {
            val type = if (isTv) "tv" else "movie"
            val url = "$TMDB_FALLBACK_PROXY/$type/$id?append_to_response=videos"

            val response = app.get(url, timeout = 10_000)
            println("📡 [TMDB] Status proxy: ${response.code}")

            if (response.code != 200) return null

            response.parsedSafe<TMDBDetailsResponse>()
        } catch (e: Exception) {
            println("❌ [TMDB] ERRO detalhes proxy: ${e.message}")
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
        return AnimeFireVideoExtractor.extractVideoLinks(data, mainUrl, name, callback)
    }

    // ============ CLASSES DE DADOS EXISTENTES ============
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
