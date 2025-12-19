package com.AnimeFire

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder
import kotlinx.coroutines.*

class AnimeFire : MainAPI() {
    override var mainUrl = "https://animefire.io"
    override var name = "ANIMEFIRE"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie, TvType.OVA)
    override val usesWebView = true

    // API GraphQL do AniList
    private val aniListApiUrl = "https://graphql.anilist.co"
    
    // Cache para mapeamento de títulos
    private val titleToUrlCache = mutableMapOf<String, String>()
    
    companion object {
        private const val SEARCH_PATH = "/pesquisar"
        private const val DEBUG_PREFIX = "🔥 [ANIMEFIRE-DEBUG]"
    }

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
        "Referer" to "https://animefire.io/",
        "Connection" to "keep-alive",
        "Upgrade-Insecure-Requests" to "1"
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
        println("$DEBUG_PREFIX getMainPage() - Página: $page, Request: ${request.name}")
        
        return when (request.name) {
            "Em Alta" -> {
                println("$DEBUG_PREFIX Chamando getAniListTrending(page=$page)")
                getAniListTrending(page)
            }
            "Populares Nessa Temporada" -> {
                println("$DEBUG_PREFIX Chamando getAniListSeason(page=$page)")
                getAniListSeason(page)
            }
            "Sempre Populares" -> {
                println("$DEBUG_PREFIX Chamando getAniListPopular(page=$page)")
                getAniListPopular(page)
            }
            "Top 100" -> {
                println("$DEBUG_PREFIX Chamando getAniListTop(page=$page)")
                getAniListTop(page)
            }
            "Na Próxima Temporada" -> {
                println("$DEBUG_PREFIX Chamando getAniListUpcoming(page=$page)")
                getAniListUpcoming(page)
            }
            else -> {
                println("$DEBUG_PREFIX ❌ Seção não reconhecida: ${request.name}")
                newHomePageResponse(request.name, emptyList(), false)
            }
        }
    }

    // ============ FUNÇÕES ANILIST COM DEBUG ============
    private suspend fun getAniListTrending(page: Int): HomePageResponse {
        println("$DEBUG_PREFIX 📈 getAniListTrending() - Página: $page")
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
                        synonyms
                    }
                }
            }
        """.trimIndent()
        
        return executeAniListQuery(query, "Em Alta", page)
    }

    private suspend fun getAniListSeason(page: Int): HomePageResponse {
        println("$DEBUG_PREFIX 📅 getAniListSeason() - Página: $page")
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
                        synonyms
                    }
                }
            }
        """.trimIndent()
        
        return executeAniListQuery(query, "Populares Nessa Temporada", page)
    }

    private suspend fun getAniListPopular(page: Int): HomePageResponse {
        println("$DEBUG_PREFIX ⭐ getAniListPopular() - Página: $page")
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
                        synonyms
                    }
                }
            }
        """.trimIndent()
        
        return executeAniListQuery(query, "Sempre Populares", page)
    }

    private suspend fun getAniListTop(page: Int): HomePageResponse {
        println("$DEBUG_PREFIX 🏆 getAniListTop() - Página: $page")
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
                        synonyms
                    }
                }
            }
        """.trimIndent()
        
        return executeAniListQuery(query, "Top 100", page)
    }

    private suspend fun getAniListUpcoming(page: Int): HomePageResponse {
        println("$DEBUG_PREFIX 🔮 getAniListUpcoming() - Página: $page")
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
                        synonyms
                    }
                }
            }
        """.trimIndent()
        
        return executeAniListQuery(query, "Na Próxima Temporada", page)
    }

    private suspend fun executeAniListQuery(
        query: String, 
        sectionName: String,
        page: Int
    ): HomePageResponse {
        println("$DEBUG_PREFIX 🔄 executeAniListQuery() - Seção: $sectionName, Página: $page")
        
        return try {
            println("$DEBUG_PREFIX 📡 Enviando requisição para AniList API...")
            
            val response = app.post(
                aniListApiUrl,
                data = mapOf("query" to query),
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json"
                ),
                timeout = 10_000
            )
            
            println("$DEBUG_PREFIX 📥 Resposta AniList - Status: ${response.code}")
            
            if (response.code == 200) {
                val aniListResponse = response.parsedSafe<AniListApiResponse>()
                val mediaList = aniListResponse?.data?.Page?.media ?: emptyList()
                
                println("$DEBUG_PREFIX 📊 ${mediaList.size} resultados encontrados do AniList")
                
                val searchResponses = mutableListOf<SearchResponse>()
                
                for ((index, media) in mediaList.withIndex()) {
                    println("\n$DEBUG_PREFIX ── Processando item ${index + 1}/${mediaList.size} ──")
                    
                    // Tentar diferentes títulos (prioridade: inglês > romaji > userPreferred > native)
                    val possibleTitles = mutableListOf<String>()
                    
                    media.title?.english?.takeIf { it.isNotBlank() }?.let { 
                        possibleTitles.add(it)
                        println("$DEBUG_PREFIX 📝 Título inglês: $it")
                    }
                    
                    media.title?.romaji?.takeIf { it.isNotBlank() }?.let { 
                        possibleTitles.add(it)
                        println("$DEBUG_PREFIX 📝 Título romaji: $it")
                    }
                    
                    media.title?.userPreferred?.takeIf { it.isNotBlank() }?.let { 
                        possibleTitles.add(it)
                        println("$DEBUG_PREFIX 📝 Título preferido: $it")
                    }
                    
                    media.title?.native?.takeIf { it.isNotBlank() }?.let { 
                        possibleTitles.add(it)
                        println("$DEBUG_PREFIX 📝 Título nativo: $it")
                    }
                    
                    // Adicionar sinônimos
                    media.synonyms?.filter { it.isNotBlank() }?.forEach { synonym ->
                        possibleTitles.add(synonym)
                        println("$DEBUG_PREFIX 📝 Sinônimo: $synonym")
                    }
                    
                    if (possibleTitles.isEmpty()) {
                        println("$DEBUG_PREFIX ⚠️ Nenhum título encontrado, pulando...")
                        continue
                    }
                    
                    // Tentar encontrar no AnimeFire para cada título possível
                    var foundUrl: String? = null
                    var foundTitle: String? = null
                    
                    for (title in possibleTitles) {
                        println("$DEBUG_PREFIX 🔍 Buscando no AnimeFire: '$title'")
                        
                        val cleanedTitle = cleanAnimeTitle(title)
                        val cacheKey = cleanedTitle.lowercase()
                        
                        // Verificar cache primeiro
                        if (titleToUrlCache.containsKey(cacheKey)) {
                            val cachedUrl = titleToUrlCache[cacheKey]
                            if (cachedUrl != null && cachedUrl.isNotBlank()) {
                                println("$DEBUG_PREFIX ♻️ Cache HIT para: '$cleanedTitle'")
                                foundUrl = cachedUrl
                                foundTitle = cleanedTitle
                                break
                            }
                        }
                        
                        // Buscar no AnimeFire
                        val searchResults = searchAnimeFire(cleanedTitle)
                        println("$DEBUG_PREFIX 🔍 Resultados da busca: ${searchResults.size}")
                        
                        if (searchResults.isNotEmpty()) {
                            val bestMatch = findBestMatch(cleanedTitle, searchResults)
                            if (bestMatch != null) {
                                println("$DEBUG_PREFIX ✅ Match encontrado: '${bestMatch.name}' → ${bestMatch.url}")
                                foundUrl = bestMatch.url
                                foundTitle = bestMatch.name
                                
                                // Armazenar no cache
                                titleToUrlCache[cacheKey] = bestMatch.url
                                titleToUrlCache[bestMatch.name.lowercase()] = bestMatch.url
                                break
                            }
                        }
                    }
                    
                    if (foundUrl != null && foundTitle != null) {
                        println("$DEBUG_PREFIX ✅ Adicionando ao resultado: '$foundTitle'")
                        
                        val posterUrl = media.coverImage?.extraLarge ?: media.coverImage?.large
                        println("$DEBUG_PREFIX 🖼️ Poster URL: ${posterUrl?.take(50)}...")
                        
                        searchResponses.add(newAnimeSearchResponse(foundTitle, foundUrl) {
                            this.posterUrl = posterUrl
                            this.type = TvType.Anime
                        })
                    } else {
                        println("$DEBUG_PREFIX ❌ Nenhum match encontrado para: ${possibleTitles.first()}")
                        // Adicionar mesmo assim com URL especial
                        val fallbackTitle = possibleTitles.first()
                        val fallbackUrl = "anilist:${media.id}:${cleanAnimeTitle(fallbackTitle)}"
                        println("$DEBUG_PREFIX ⚠️ Usando fallback: $fallbackUrl")
                        
                        val posterUrl = media.coverImage?.extraLarge ?: media.coverImage?.large
                        searchResponses.add(newAnimeSearchResponse(cleanAnimeTitle(fallbackTitle), fallbackUrl) {
                            this.posterUrl = posterUrl
                            this.type = TvType.Anime
                        })
                    }
                }
                
                println("$DEBUG_PREFIX ✅ Total de itens processados: ${searchResponses.size}")
                newHomePageResponse(sectionName, searchResponses, hasNext = searchResponses.isNotEmpty())
                
            } else {
                println("$DEBUG_PREFIX ❌ Erro na API AniList: ${response.code}")
                println("$DEBUG_PREFIX ❌ Resposta: ${response.text.take(200)}")
                newHomePageResponse(sectionName, emptyList(), false)
            }
            
        } catch (e: Exception) {
            println("$DEBUG_PREFIX ❌ Exception em executeAniListQuery: ${e.message}")
            e.printStackTrace()
            newHomePageResponse(sectionName, emptyList(), false)
        }
    }

    // ============ BUSCA NO ANIMEFIRE ============
    private suspend fun searchAnimeFire(query: String): List<SearchResponse> {
        println("$DEBUG_PREFIX 🔎 searchAnimeFire() - Query: '$query'")
        
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "$mainUrl$SEARCH_PATH/$encodedQuery"
            println("$DEBUG_PREFIX 🔗 URL de busca: $searchUrl")
            
            val response = app.get(searchUrl, headers = headers, timeout = 15_000)
            println("$DEBUG_PREFIX 📥 Status da busca: ${response.code}")
            
            if (response.code != 200) {
                println("$DEBUG_PREFIX ❌ Erro HTTP na busca: ${response.code}")
                return emptyList()
            }
            
            val document = response.document
            
            // Seletores para encontrar animes
            val elements = document.select("""
                a[href*="/animes/"],
                a[href*="/filmes/"],
                .divCardUltimosEps article.card a,
                .anime-card a,
                .card-anime a
            """.trimIndent())
            
            println("$DEBUG_PREFIX 🔍 Elementos encontrados na busca: ${elements.size}")
            
            val results = elements.mapNotNull { element ->
                runCatching {
                    val href = element.attr("href")
                    if (href.isBlank()) return@runCatching null
                    
                    val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                    
                    // Extrair título
                    val titleElement = element.selectFirst("""
                        h3.animeTitle, 
                        .animeTitle,
                        h3,
                        .title,
                        .card-title,
                        [class*="title"],
                        [class*="name"]
                    """.trimIndent())
                    
                    val rawTitle = titleElement?.text()?.trim() ?: 
                                  element.attr("title")?.trim() ?: 
                                  element.attr("alt")?.trim() ?: 
                                  "Sem Título"
                    
                    val cleanTitle = cleanAnimeTitle(rawTitle)
                    
                    // Extrair imagem
                    val imgElement = element.selectFirst("img")
                    val posterUrl = when {
                        imgElement?.hasAttr("data-src") == true -> fixUrl(imgElement.attr("data-src"))
                        imgElement?.hasAttr("src") == true -> fixUrl(imgElement.attr("src"))
                        else -> null
                    }
                    
                    val isMovie = href.contains("/filmes/") || 
                                 rawTitle.contains("filme", ignoreCase = true) ||
                                 rawTitle.contains("movie", ignoreCase = true)
                    
                    println("$DEBUG_PREFIX 📋 Resultado: '$cleanTitle' → $fullUrl")
                    
                    newAnimeSearchResponse(cleanTitle, fullUrl) {
                        this.posterUrl = posterUrl
                        this.type = if (isMovie) TvType.Movie else TvType.Anime
                    }
                }.getOrElse { e ->
                    println("$DEBUG_PREFIX ⚠️ Erro ao processar elemento: ${e.message}")
                    null
                }
            }.distinctBy { it.url }.take(10) // Limitar a 10 resultados
            
            println("$DEBUG_PREFIX ✅ Total de resultados válidos: ${results.size}")
            results
            
        } catch (e: Exception) {
            println("$DEBUG_PREFIX ❌ Exception em searchAnimeFire: ${e.message}")
            e.printStackTrace()
            emptyList()
        }
    }

    // ============ FUNÇÕES AUXILIARES ============
    private fun cleanAnimeTitle(title: String): String {
        val cleaned = title
            .replace(Regex("\\s*\\([^)]+\\)$"), "")
            .replace(Regex("\\s*-\\s*[^-]+$"), "")
            .replace(Regex("\\(Dublado\\)|\\(Legendado\\)|\\(Dub\\)|\\(Sub\\)"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        
        println("$DEBUG_PREFIX 🧹 Limpando título: '$title' → '$cleaned'")
        return cleaned
    }

    private fun findBestMatch(searchTitle: String, results: List<SearchResponse>): SearchResponse? {
        if (results.isEmpty()) return null
        
        println("$DEBUG_PREFIX 🎯 findBestMatch() - Buscando: '$searchTitle' em ${results.size} resultados")
        
        val cleanSearch = normalizeForMatching(searchTitle)
        println("$DEBUG_PREFIX 🎯 Normalizado: '$cleanSearch'")
        
        // Tentar match exato primeiro
        for (result in results) {
            val cleanResult = normalizeForMatching(result.name)
            if (cleanResult == cleanSearch) {
                println("$DEBUG_PREFIX 🎯✅ Match exato encontrado: '${result.name}'")
                return result
            }
        }
        
        // Tentar match parcial (um contém o outro)
        for (result in results) {
            val cleanResult = normalizeForMatching(result.name)
            if (cleanResult.contains(cleanSearch) || cleanSearch.contains(cleanResult)) {
                println("$DEBUG_PREFIX 🎯✅ Match parcial encontrado: '${result.name}' (contém)")
                return result
            }
        }
        
        // Calcular similaridade
        var bestMatch: SearchResponse? = null
        var bestScore = 0
        
        for (result in results) {
            val cleanResult = normalizeForMatching(result.name)
            val score = calculateSimilarity(cleanSearch, cleanResult)
            
            if (score > bestScore) {
                bestScore = score
                bestMatch = result
                println("$DEBUG_PREFIX 🎯 Pontuação: $score para '${result.name}'")
            }
        }
        
        if (bestMatch != null && bestScore >= 3) { // Pelo menos 3 palavras em comum
            println("$DEBUG_PREFIX 🎯✅ Melhor match por similaridade: '${bestMatch.name}' (score: $bestScore)")
            return bestMatch
        }
        
        println("$DEBUG_PREFIX 🎯❌ Nenhum match bom encontrado")
        return null
    }

    private fun normalizeForMatching(title: String): String {
        return title.lowercase()
            .replace(Regex("[^a-z0-9\\sáéíóúâêîôûãõç]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun calculateSimilarity(str1: String, str2: String): Int {
        val words1 = str1.split(" ").filter { it.length > 2 }
        val words2 = str2.split(" ").filter { it.length > 2 }
        
        var score = 0
        for (word1 in words1) {
            for (word2 in words2) {
                if (word1 == word2) {
                    score++
                    break
                }
            }
        }
        return score
    }

    // ============ SEARCH PÚBLICA ============
    override suspend fun search(query: String): List<SearchResponse> {
        println("\n$DEBUG_PREFIX ============ SEARCH PÚBLICA ============")
        println("$DEBUG_PREFIX 🔍 Buscando: '$query'")
        
        val results = searchAnimeFire(query)
        println("$DEBUG_PREFIX ✅ Search pública retornou: ${results.size} resultados")
        return results
    }

    // ============ LOAD ============
    override suspend fun load(url: String): LoadResponse {
        println("\n$DEBUG_PREFIX ============ LOAD ============")
        println("$DEBUG_PREFIX 🚀 load() - URL: ${url.take(100)}...")
        
        // Se for URL do AniList (fallback), tentar buscar no AnimeFire
        if (url.startsWith("anilist:")) {
            println("$DEBUG_PREFIX 🎯 Detectada URL especial do AniList")
            val parts = url.split(":")
            if (parts.size >= 3) {
                val aniListId = parts[1]
                val title = parts.subList(2, parts.size).joinToString(":")
                println("$DEBUG_PREFIX 📝 ID AniList: $aniListId, Título: $title")
                
                // Tentar buscar este anime no AnimeFire
                val searchResults = searchAnimeFire(title)
                println("$DEBUG_PREFIX 🔍 Resultados da busca para fallback: ${searchResults.size}")
                
                if (searchResults.isNotEmpty()) {
                    val bestMatch = findBestMatch(title, searchResults)
                    if (bestMatch != null) {
                        println("$DEBUG_PREFIX ✅ Match encontrado no fallback! Redirecionando para: ${bestMatch.url}")
                        // Armazenar no cache para próxima vez
                        titleToUrlCache[title.lowercase()] = bestMatch.url
                        return loadFromAnimeFire(bestMatch.url)
                    }
                }
                
                // Se não encontrar, mostrar mensagem de erro
                println("$DEBUG_PREFIX ❌ Anime não encontrado no AnimeFire: $title")
                return newAnimeLoadResponse(title, url, TvType.Anime) {
                    this.plot = "❌ Este anime está no AniList mas não foi encontrado no AnimeFire.\n\n" +
                               "Título: $title\n" +
                               "ID AniList: $aniListId\n\n" +
                               "🔍 Tente buscar manualmente usando a função de busca."
                }
            }
        }
        
        // URL normal do AnimeFire
        return loadFromAnimeFire(url)
    }

    private suspend fun loadFromAnimeFire(url: String): LoadResponse {
        println("$DEBUG_PREFIX 🔗 Carregando do AnimeFire: $url")
        
        return try {
            val response = app.get(url, headers = headers, timeout = 15_000)
            println("$DEBUG_PREFIX 📥 Status do load: ${response.code}")
            
            if (response.code != 200) {
                println("$DEBUG_PREFIX ❌ Erro ao carregar página: ${response.code}")
                throw ErrorLoadingException("Não foi possível carregar a página")
            }
            
            val document = response.document
            
            // Extrair título
            val titleElement = document.selectFirst("h1.quicksand400, .main_div_anime_info h1, h1")
            if (titleElement == null) {
                println("$DEBUG_PREFIX ❌ Título não encontrado na página")
                throw ErrorLoadingException("Título não encontrado")
            }
            
            val rawTitle = titleElement.text().trim()
            println("$DEBUG_PREFIX 📝 Título raw: $rawTitle")
            
            val year = Regex("\\((\\d{4})\\)").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
            val cleanTitle = rawTitle.replace(Regex("\\(\\d{4}\\)"), "").trim()
            println("$DEBUG_PREFIX 📝 Título limpo: $cleanTitle, Ano: $year")
            
            // Determinar tipo
            val isMovie = url.contains("/filmes/") || rawTitle.contains("filme", ignoreCase = true)
            val type = if (isMovie) TvType.Movie else TvType.Anime
            println("$DEBUG_PREFIX 🎬 Tipo: $type")
            
            // Extrair sinopse
            val plotElement = document.selectFirst("div.divSinopse span.spanAnimeInfo")
            val plot = plotElement?.text()?.trim()?.replace(Regex("^Sinopse:\\s*"), "")
            println("$DEBUG_PREFIX 📖 Sinopse encontrada: ${plot?.take(50)}...")
            
            // Extrair poster
            val posterImg = document.selectFirst(".sub_animepage_img img.transitioning_src, .sub_animepage_img img")
            val poster = when {
                posterImg?.hasAttr("src") == true -> fixUrl(posterImg.attr("src"))
                posterImg?.hasAttr("data-src") == true -> fixUrl(posterImg.attr("data-src"))
                else -> document.selectFirst("img[src*='/img/animes/']:not([src*='logo'])")
                    ?.attr("src")?.let { fixUrl(it) }
            }
            println("$DEBUG_PREFIX 🖼️ Poster: ${poster?.take(50)}...")
            
            // Extrair episódios (se anime)
            val episodes = if (!isMovie) {
                document.select("a.lEp.epT, a.lEp, .divListaEps a, [href*='/video/']")
                    .mapIndexedNotNull { index, element ->
                        try {
                            val href = element.attr("href")
                            if (href.isBlank()) return@mapIndexedNotNull null
                            
                            val text = element.text().trim()
                            val epNumber = extractEpisodeNumber(text) ?: (index + 1)
                            
                            val episodeName = element.selectFirst(".ep-name, .title")?.text()?.trim()
                                ?: text.substringAfterLast("-").trim()
                                ?: "Episódio $epNumber"
                            
                            println("$DEBUG_PREFIX 📺 Episódio $epNumber: $episodeName")
                            
                            newEpisode(fixUrl(href)) {
                                this.name = episodeName
                                this.season = 1
                                this.episode = epNumber
                            }
                        } catch (e: Exception) {
                            println("$DEBUG_PREFIX ⚠️ Erro ao processar episódio: ${e.message}")
                            null
                        }
                    }
                    .sortedBy { it.episode }
            } else {
                emptyList()
            }
            
            println("$DEBUG_PREFIX 📺 Total de episódios: ${episodes.size}")
            
            // Criar resposta
            if (isMovie) {
                newMovieLoadResponse(cleanTitle, url, type, url) {
                    this.year = year
                    this.plot = plot
                    this.posterUrl = poster
                    this.type = type
                }
            } else {
                newAnimeLoadResponse(cleanTitle, url, type) {
                    addEpisodes(DubStatus.Subbed, episodes)
                    this.year = year
                    this.plot = plot
                    this.posterUrl = poster
                    this.type = type
                }
            }
            
        } catch (e: Exception) {
            println("$DEBUG_PREFIX ❌ Exception em loadFromAnimeFire: ${e.message}")
            e.printStackTrace()
            throw e
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
        println("$DEBUG_PREFIX ⚠️ loadLinks() não implementado")
        return false
    }

    // ============ CLASSES DE DADOS ANILIST ============
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
        @JsonProperty("coverImage") val coverImage: AniListCoverImage? = null,
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
        @JsonProperty("extraLarge") val extraLarge: String? = null
    )
}
