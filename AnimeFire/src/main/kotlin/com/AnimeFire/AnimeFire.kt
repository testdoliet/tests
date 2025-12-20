package com.AnimeFire

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.google.gson.JsonParser
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnimeFire : MainAPI() {
    override var mainUrl = "https://animefire.io"
    override var name = "ANIMEFIRE"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie, TvType.OVA)
    override val usesWebView = false  // Não precisamos de WebView agora

    // API GraphQL do AniList (para página principal)
    private val aniListApiUrl = "https://graphql.anilist.co"
    
    // Cache para mapear títulos AniList → URLs AnimeFire
    private val titleToUrlCache = mutableMapOf<String, String>()

    // ============ CONFIGURAÇÕES ============
    companion object {
        private const val SEARCH_PATH = "/pesquisar"
        private const val MAX_CACHE_SIZE = 500
    }

    // Cabeçalhos melhorados para evitar bloqueio
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
        "Accept-Encoding" to "gzip, deflate, br",
        "Referer" to "https://animefire.io/",
        "Connection" to "keep-alive",
        "Upgrade-Insecure-Requests" to "1",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin",
        "Cache-Control" to "max-age=0"
    )

    // ============ PÁGINA PRINCIPAL (ANILIST) ============
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
        val query = """
            query {
                Page(page: $page, perPage: 20) {
                    media(sort: TRENDING_DESC, type: ANIME) {
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
                    }
                }
            }
        """.trimIndent()
        
        return executeAniListQuery(query, "Em Alta", page)
    }

    private suspend fun getAniListSeason(page: Int): HomePageResponse {
        val query = """
            query {
                Page(page: $page, perPage: 20) {
                    media(season: WINTER, seasonYear: 2025, type: ANIME, sort: POPULARITY_DESC) {
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
                    }
                }
            }
        """.trimIndent()
        
        return executeAniListQuery(query, "Populares Nessa Temporada", page)
    }

    private suspend fun getAniListPopular(page: Int): HomePageResponse {
        val query = """
            query {
                Page(page: $page, perPage: 20) {
                    media(sort: POPULARITY_DESC, type: ANIME) {
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
                    }
                }
            }
        """.trimIndent()
        
        return executeAniListQuery(query, "Sempre Populares", page)
    }

    private suspend fun getAniListTop(page: Int): HomePageResponse {
        val query = """
            query {
                Page(page: $page, perPage: 20) {
                    media(sort: SCORE_DESC, type: ANIME) {
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
                    }
                }
            }
        """.trimIndent()
        
        return executeAniListQuery(query, "Top 100", page)
    }

    private suspend fun getAniListUpcoming(page: Int): HomePageResponse {
        val query = """
            query {
                Page(page: $page, perPage: 20) {
                    media(season: SPRING, seasonYear: 2025, type: ANIME, sort: POPULARITY_DESC) {
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
                    }
                }
            }
        """.trimIndent()
        
        return executeAniListQuery(query, "Na Próxima Temporada", page)
    }

    private suspend fun executeAniListQuery(
        query: String, 
        pageName: String,
        page: Int
    ): HomePageResponse {
        return try {
            println("📡 [ANILIST] Buscando: $pageName")
            
            val response = app.post(
                aniListApiUrl,
                data = mapOf("query" to query),
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json"
                ),
                timeout = 10_000
            )
            
            if (response.code == 200) {
                val aniListResponse = response.parsedSafe<AniListApiResponse>()
                val mediaList = aniListResponse?.data?.Page?.media ?: emptyList()
                
                println("📊 [ANILIST] ${mediaList.size} resultados encontrados para $pageName")
                
                val filteredItems = mutableListOf<SearchResponse>()
                val seenIds = mutableSetOf<Int>()
                
                for (media in mediaList) {
                    if (media.id in seenIds) continue
                    seenIds.add(media.id)
                    
                    // Prioridade para inglês, depois romaji
                    val aniListTitle = media.title?.english ?: 
                                     media.title?.romaji ?: 
                                     media.title?.userPreferred ?: 
                                     "Sem Título"
                    
                    // Limpar título
                    val cleanTitle = cleanAnimeTitle(aniListTitle)
                    
                    // Verificar cache primeiro
                    val cachedUrl = titleToUrlCache[cleanTitle.lowercase()]
                    
                    if (cachedUrl != null && cachedUrl.startsWith("http")) {
                        // Usar URL do cache
                        println("♻️ [CACHE] Usando URL em cache para: $cleanTitle")
                        val finalPoster = media.coverImage?.extraLarge ?: media.coverImage?.large
                        
                        filteredItems.add(newAnimeSearchResponse(cleanTitle, cachedUrl) {
                            this.posterUrl = finalPoster
                            this.type = TvType.Anime
                        })
                    } else {
                        // Buscar automaticamente pelo título
                        val searchResults = searchFirstResult(cleanTitle)
                        
                        if (searchResults.isNotEmpty()) {
                            val searchResult = searchResults.first()
                            val finalPoster = media.coverImage?.extraLarge ?: 
                                             media.coverImage?.large ?: 
                                             searchResult.posterUrl
                            
                            // Armazenar no cache
                            titleToUrlCache[cleanTitle.lowercase()] = searchResult.url
                            
                            filteredItems.add(newAnimeSearchResponse(cleanTitle, searchResult.url) {
                                this.posterUrl = finalPoster
                                this.type = searchResult.type
                            })
                            
                            println("✅ [ANILIST] Adicionado via busca: $cleanTitle -> ${searchResult.url}")
                        } else {
                            println("⚠️ [ANILIST] Nenhum resultado encontrado para: $cleanTitle")
                        }
                    }
                }
                
                println("✅ [ANILIST] ${filteredItems.size} itens adicionados para $pageName")
                newHomePageResponse(pageName, filteredItems, hasNext = filteredItems.isNotEmpty())
            } else {
                println("❌ [ANILIST] Erro na API: ${response.code}")
                newHomePageResponse(pageName, emptyList(), false)
            }
        } catch (e: Exception) {
            println("❌ [ANILIST] Exception: ${e.message}")
            newHomePageResponse(pageName, emptyList(), false)
        }
    }

    private fun cleanAnimeTitle(title: String): String {
        return title
            .replace(Regex("\\s*\\([^)]+\\)$"), "")
            .replace(Regex("\\s*-\\s*[^-]+$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    // ============ FUNÇÃO DE BUSCA NOVA ============
    // Esta função faz exatamente o que você pediu:
    // 1. Pega o nome do anime
    // 2. Converte para slug (espaços viram -)
    // 3. Busca em /pesquisar/nome-do-anime
    // 4. Clica no primeiro resultado
    // 5. Retorna o link final
    
    override suspend fun search(query: String): List<SearchResponse> {
        // Usamos a nova função que pega apenas o primeiro resultado
        return searchFirstResult(query)
    }
    
    private suspend fun searchFirstResult(query: String): List<SearchResponse> {
        println("🔍 [SEARCH-FIRST] Buscando primeiro resultado para: '$query'")
        
        // 1. Converter nome para slug (espaços viram -)
        val slug = createSearchSlug(query)
        
        // 2. Construir URL de pesquisa
        val searchUrl = "$mainUrl$SEARCH_PATH/$slug"
        println("🔗 [SEARCH-FIRST] URL de busca: $searchUrl")
        
        return try {
            // 3. Fazer requisição para a página de resultados
            val response = app.get(searchUrl, headers = headers, timeout = 15_000)
            
            if (response.code == 403) {
                println("❌ [SEARCH-FIRST] ERRO 403: Acesso negado")
                return emptyList()
            }
            
            if (response.code != 200) {
                println("❌ [SEARCH-FIRST] Erro HTTP: ${response.code}")
                return emptyList()
            }
            
            val document = response.document
            
            // 4. Procurar o primeiro resultado (tentando vários seletores)
            val firstResult = findFirstSearchResult(document)
            
            if (firstResult == null) {
                println("⚠️ [SEARCH-FIRST] Nenhum resultado encontrado")
                return emptyList()
            }
            
            // 5. Extrair informações do primeiro resultado
            val href = firstResult.attr("href")
            if (href.isBlank() || (!href.contains("/animes/") && !href.contains("/filmes/"))) {
                println("⚠️ [SEARCH-FIRST] Link inválido: $href")
                return emptyList()
            }
            
            val finalUrl = fixUrl(href)
            
            // Extrair título
            val titleElement = firstResult.selectFirst("""
                h3.animeTitle, 
                .text-block h3, 
                .animeTitle, 
                h3, 
                .title, 
                .card-title,
                [class*="title"],
                [class*="name"]
            """.trimIndent())
            
            var title = titleElement?.text()?.trim() ?: query
            
            // Limpar título
            title = title
                .replace(Regex("\\s*-\\s*Todos os Episódios$"), "")
                .replace(Regex("\\(Dublado\\)"), "")
                .replace(Regex("\\(Legendado\\)"), "")
                .replace(Regex("\\s*\\([^)]+\\)$"), "")
                .trim()
            
            // Extrair imagem
            val imgElement = firstResult.selectFirst("""
                img.imgAnimes, 
                img.card-img-top, 
                img.transitioning_src,
                img[src*="/img/"],
                img
            """.trimIndent())
            
            val posterUrl = when {
                imgElement?.hasAttr("data-src") == true -> fixUrl(imgElement.attr("data-src"))
                imgElement?.hasAttr("src") == true -> fixUrl(imgElement.attr("src"))
                else -> null
            }
            
            // Determinar tipo
            val isMovie = finalUrl.contains("/filmes/") || 
                         title.contains("filme", ignoreCase = true) ||
                         title.contains("movie", ignoreCase = true)
            
            println("✅ [SEARCH-FIRST] Primeiro resultado: '$title' -> $finalUrl")
            
            // 6. Armazenar no cache
            titleToUrlCache[query.lowercase()] = finalUrl
            titleToUrlCache[title.lowercase()] = finalUrl
            
            // 7. Retornar o resultado
            listOf(newAnimeSearchResponse(title, finalUrl) {
                this.posterUrl = posterUrl
                this.type = if (isMovie) TvType.Movie else TvType.Anime
            })
            
        } catch (e: Exception) {
            println("❌ [SEARCH-FIRST] Exception: ${e.message}")
            emptyList()
        }
    }
    
    private fun createSearchSlug(query: String): String {
        // Converter para slug: espaços viram -, remove caracteres especiais
        return query.lowercase()
            .replace(Regex("[áàâãä]"), "a")
            .replace(Regex("[éèêë]"), "e")
            .replace(Regex("[íìîï]"), "i")
            .replace(Regex("[óòôõö]"), "o")
            .replace(Regex("[úùûü]"), "u")
            .replace(Regex("[ç]"), "c")
            .replace(Regex("[ñ]"), "n")
            .replace(Regex("[^a-z0-9\\s-]"), "") // Remove outros caracteres especiais
            .replace(Regex("\\s+"), "-") // Espaços viram -
            .replace(Regex("-+"), "-") // Remove múltiplos - seguidos
            .trim('-') // Remove - do início e fim
    }
    
    private fun findFirstSearchResult(document: org.jsoup.nodes.Document): Element? {
        // Tentar vários seletores para encontrar o primeiro resultado
        val selectors = listOf(
            "div.divCardUltimosEps article.card a",
            ".divListaAnimes article.card a",
            ".anime-card a",
            ".card-anime a",
            "a[href*='/animes/']:not([href*='pesquisar'])",
            "a[href*='/filmes/']:not([href*='pesquisar'])",
            ".search-results a",
            ".results a"
        )
        
        for (selector in selectors) {
            val elements = document.select(selector)
            if (elements.isNotEmpty()) {
                println("🔍 [SEARCH-FIRST] Encontrado com seletor: $selector (${elements.size} elementos)")
                return elements.first()
            }
        }
        
        return null
    }

    // ============ LOAD (usando o URL já encontrado) ============
    override suspend fun load(url: String): LoadResponse {
        println("\n🚀 AnimeFire.load() para URL: $url")
        
        // Agora a URL sempre será uma URL válida do AnimeFire
        // (porque a busca já resolveu isso)
        return loadFromAnimeFire(url)
    }

    private suspend fun loadFromAnimeFire(url: String): LoadResponse {
        return try {
            // Usar headers melhorados
            val response = app.get(url, headers = headers, timeout = 15_000)
            
            if (response.code == 403) {
                println("❌ [LOAD] ERRO 403: Acesso negado ao carregar: $url")
                throw ErrorLoadingException("Acesso negado pelo site")
            }
            
            if (response.code != 200) {
                println("❌ [LOAD] Erro HTTP: ${response.code} para: $url")
                throw ErrorLoadingException("Erro ao carregar a página")
            }
            
            val document = response.document

            val titleElement = document.selectFirst("h1.quicksand400, .main_div_anime_info h1, h1") ?: 
                throw ErrorLoadingException("Não foi possível encontrar o título")
            val rawTitle = titleElement.text().trim()
            
            val year = Regex("\\((\\d{4})\\)").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
            val cleanTitle = rawTitle.replace(Regex("\\(\\d{4}\\)"), "").trim()
            
            val isMovie = url.contains("/filmes/") || rawTitle.contains("Movie", ignoreCase = true)
            val type = if (isMovie) TvType.Movie else TvType.Anime

            println("📌 Título: $cleanTitle, Ano: $year, Tipo: $type")

            // Extrair sinopse do site
            val plotElement = document.selectFirst("div.divSinopse span.spanAnimeInfo")
            val plot = plotElement?.text()?.trim()?.replace(Regex("^Sinopse:\\s*"), "")

            // Extrair poster
            val posterImg = document.selectFirst(".sub_animepage_img img.transitioning_src")
            val poster = when {
                posterImg?.hasAttr("src") == true -> fixUrl(posterImg.attr("src"))
                posterImg?.hasAttr("data-src") == true -> fixUrl(posterImg.attr("data-src"))
                else -> document.selectFirst("img[src*='/img/animes/']:not([src*='logo'])")
                    ?.attr("src")?.let { fixUrl(it) }
            }

            // Extrair episódios (se for anime)
            val episodes = if (!isMovie) {
                document.select("a.lEp.epT, a.lEp, .divListaEps a, [href*='/video/'], [href*='/episodio/']")
                    .mapIndexedNotNull { index, element ->
                        try {
                            val href = element.attr("href")
                            if (href.isBlank()) return@mapIndexedNotNull null
                            
                            val text = element.text().trim()
                            val epNumber = extractEpisodeNumber(text) ?: (index + 1)
                            
                            val episodeName = element.selectFirst(".ep-name, .title")?.text()?.trim()
                                ?: text.substringAfterLast("-").trim()
                                ?: "Episódio $epNumber"
                            
                            newEpisode(fixUrl(href)) {
                                this.name = episodeName
                                this.season = 1
                                this.episode = epNumber
                                this.posterUrl = null
                                this.description = null
                                this.date = null
                            }
                        } catch (e: Exception) {
                            null
                        }
                    }
                    .sortedBy { it.episode }
            } else {
                emptyList()
            }

            // Extrair recomendações
            val recommendations = document.select(".owl-carousel-anime .divArticleLancamentos a.item")
                .mapNotNull { element -> 
                    runCatching { 
                        val href = element.attr("href")
                        val titleEl = element.selectFirst("h3.animeTitle, .text-block h3")
                        val title = titleEl?.text()?.trim() ?: "Sem Título"
                        
                        newAnimeSearchResponse(title, fixUrl(href)) {
                            val img = element.selectFirst("img.imgAnimes, img.card-img-top")
                            this.posterUrl = img?.attr("src")?.let { fixUrl(it) }
                            this.type = TvType.Anime
                        }
                    }.getOrNull()
                }

            // Criar resposta final
            if (isMovie) {
                newMovieLoadResponse(cleanTitle, url, type, url) {
                    this.year = year
                    this.plot = plot
                    this.posterUrl = poster
                    this.type = type
                    this.recommendations = recommendations.takeIf { it.isNotEmpty() }
                }
            } else {
                newAnimeLoadResponse(cleanTitle, url, type) {
                    addEpisodes(DubStatus.Subbed, episodes)
                    this.year = year
                    this.plot = plot
                    this.posterUrl = poster
                    this.type = type
                    this.recommendations = recommendations.takeIf { it.isNotEmpty() }
                }
            }
        } catch (e: Exception) {
            println("❌ Erro ao carregar do AnimeFire: ${e.message}")
            newAnimeLoadResponse("Erro", url, TvType.Anime) {
                this.plot = "Não foi possível carregar este anime."
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("⚠️ Extração de links não implementada ainda")
        return false
    }

    // ============ CLASSES DE DADOS PARA ANILIST ============
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListApiResponse(
        @JsonProperty("data") val data: AniListData? = null
    )
    
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListMediaResponse(
        @JsonProperty("data") val data: AniListMediaData? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListData(
        @JsonProperty("Page") val Page: AniListPage? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListMediaData(
        @JsonProperty("Media") val Media: AniListMedia? = null
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
        @JsonProperty("coverImage") val coverImage: AniListCoverImage? = null,
        @JsonProperty("bannerImage") val bannerImage: String? = null,
        @JsonProperty("genres") val genres: List<String>? = null,
        @JsonProperty("siteUrl") val siteUrl: String? = null,
        @JsonProperty("startDate") val startDate: AniListDate? = null,
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
}
