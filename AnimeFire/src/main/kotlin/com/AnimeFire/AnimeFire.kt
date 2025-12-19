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

    // Cabeçalhos melhorados para evitar bloqueios
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
        "Accept-Encoding" to "gzip, deflate, br",
        "Referer" to "$mainUrl/",
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
                        status
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
                        status
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
                        status
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
                        status
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
                        status
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
                
                // Filtrar apenas animes com status RELEASING ou FINISHED (evita animes não lançados)
                val filteredMedia = mediaList.filter { 
                    it.status == "RELEASING" || it.status == "FINISHED" 
                }.take(10) // Limitar a 10 itens para evitar muitas requisições
                
                println("$DEBUG_PREFIX 🎯 Animes filtrados (lançados/finalizados): ${filteredMedia.size}")
                
                for ((index, media) in filteredMedia.withIndex()) {
                    println("\n$DEBUG_PREFIX ── Processando item ${index + 1}/${filteredMedia.size} ──")
                    
                    // Escolher o melhor título (preferência: inglês > romaji > userPreferred)
                    val bestTitle = media.title?.english?.takeIf { it.isNotBlank() }
                        ?: media.title?.romaji?.takeIf { it.isNotBlank() }
                        ?: media.title?.userPreferred?.takeIf { it.isNotBlank() }
                        ?: continue // Pular se não tiver título
                    
                    println("$DEBUG_PREFIX 📝 Título escolhido: '$bestTitle'")
                    
                    // Limpar título
                    val cleanedTitle = cleanAnimeTitle(bestTitle)
                    val cacheKey = cleanedTitle.lowercase()
                    
                    // Verificar cache primeiro
                    if (titleToUrlCache.containsKey(cacheKey)) {
                        val cachedUrl = titleToUrlCache[cacheKey]
                        if (cachedUrl != null && cachedUrl.isNotBlank() && !cachedUrl.startsWith("anilist:")) {
                            println("$DEBUG_PREFIX ♻️ Cache HIT para: '$cleanedTitle'")
                            
                            val posterUrl = media.coverImage?.extraLarge ?: media.coverImage?.large
                            searchResponses.add(newAnimeSearchResponse(cleanedTitle, cachedUrl) {
                                this.posterUrl = posterUrl
                                this.type = TvType.Anime
                            })
                            continue
                        }
                    }
                    
                    // Buscar no AnimeFire
                    println("$DEBUG_PREFIX 🔍 Buscando no AnimeFire: '$cleanedTitle'")
                    
                    // Adicionar delay para evitar bloqueio
                    delay(500)
                    
                    val searchResults = searchAnimeFire(cleanedTitle)
                    println("$DEBUG_PREFIX 🔍 Resultados da busca: ${searchResults.size}")
                    
                    if (searchResults.isNotEmpty()) {
                        val bestMatch = findBestMatch(cleanedTitle, searchResults)
                        if (bestMatch != null) {
                            println("$DEBUG_PREFIX ✅ Match encontrado: '${bestMatch.name}' → ${bestMatch.url}")
                            
                            // Armazenar no cache
                            titleToUrlCache[cacheKey] = bestMatch.url
                            titleToUrlCache[bestMatch.name.lowercase()] = bestMatch.url
                            
                            val posterUrl = media.coverImage?.extraLarge ?: media.coverImage?.large
                            searchResponses.add(newAnimeSearchResponse(bestMatch.name, bestMatch.url) {
                                this.posterUrl = posterUrl
                                this.type = TvType.Anime
                            })
                        } else {
                            // Se não encontrar match bom, usar busca manual simplificada
                            addFallbackItem(media, cleanedTitle, searchResponses)
                        }
                    } else {
                        // Se busca retornar vazio, usar fallback
                        addFallbackItem(media, cleanedTitle, searchResponses)
                    }
                }
                
                println("$DEBUG_PREFIX ✅ Total de itens processados: ${searchResponses.size}")
                newHomePageResponse(sectionName, searchResponses, hasNext = searchResponses.isNotEmpty())
                
            } else {
                println("$DEBUG_PREFIX ❌ Erro na API AniList: ${response.code}")
                newHomePageResponse(sectionName, emptyList(), false)
            }
            
        } catch (e: Exception) {
            println("$DEBUG_PREFIX ❌ Exception em executeAniListQuery: ${e.message}")
            e.printStackTrace()
            newHomePageResponse(sectionName, emptyList(), false)
        }
    }

    private fun addFallbackItem(
        media: AniListMedia, 
        title: String,
        searchResponses: MutableList<SearchResponse>
    ) {
        println("$DEBUG_PREFIX ⚠️ Nenhum resultado encontrado para: $title")
        
        // Criar URL especial para fallback (será tratada na função load)
        val fallbackUrl = "anilist:${media.id}:$title"
        val posterUrl = media.coverImage?.extraLarge ?: media.coverImage?.large
        
        var searchResponse = newAnimeSearchResponse(title, fallbackUrl) {
            this.posterUrl = posterUrl
            this.type = TvType.Anime
        }
        
        // Adicionar informação extra (não temos campo description em SearchResponse)
        // Podemos adicionar ao nome para indicar que precisa de busca
        searchResponse.name = "$title 🔍"
        
        searchResponses.add(searchResponse)
        println("$DEBUG_PREFIX ⚠️ Usando fallback: $fallbackUrl")
    }

    // ============ BUSCA NO ANIMEFIRE (CORRIGIDA) ============
    private suspend fun searchAnimeFire(query: String): List<SearchResponse> {
        println("$DEBUG_PREFIX 🔎 searchAnimeFire() - Query: '$query'")
        
        return try {
            // Primeiro, tentar buscar no site principal
            val searchResults = mutableListOf<SearchResponse>()
            
            // Tentar diferentes variações da query
            val searchVariations = getSearchVariations(query)
            
            for (searchQuery in searchVariations) {
                // Adicionar delay entre tentativas
                delay(300)
                
                val results = trySearchOnSite(searchQuery)
                if (results.isNotEmpty()) {
                    searchResults.addAll(results)
                    break // Parar na primeira variação que der resultados
                }
            }
            
            // Se ainda não encontrou, tentar buscar diretamente na página de animes populares
            if (searchResults.isEmpty()) {
                println("$DEBUG_PREFIX 🔍 Busca direta falhou, tentando página popular...")
                searchResults.addAll(tryGetFromPopularPage(query))
            }
            
            println("$DEBUG_PREFIX ✅ Total de resultados válidos: ${searchResults.size}")
            searchResults.take(10) // Limitar resultados
            
        } catch (e: Exception) {
            println("$DEBUG_PREFIX ❌ Exception em searchAnimeFire: ${e.message}")
            emptyList()
        }
    }

    private fun getSearchVariations(query: String): List<String> {
        val variations = mutableListOf<String>()
        
        // Adicionar a query original
        variations.add(query)
        
        // Remover caracteres especiais
        val cleanQuery = query.replace(Regex("[^a-zA-Z0-9\\sáéíóúâêîôûãõç]"), " ").trim()
        if (cleanQuery != query) variations.add(cleanQuery)
        
        // Converter para minúsculas
        variations.add(query.lowercase())
        
        // Tentar versão em português para animes conhecidos
        val portugueseTitles = mapOf(
            "one piece" to "one piece",
            "naruto" to "naruto",
            "attack on titan" to "attack on titan",
            "demon slayer" to "demon slayer",
            "my hero academia" to "my hero academia",
            "dragon ball" to "dragon ball",
            "jujutsu kaisen" to "jujutsu kaisen",
            "hunter x hunter" to "hunter x hunter",
            "death note" to "death note",
            "fullmetal alchemist" to "fullmetal alchemist",
            "assassination classroom" to "assassination classroom"
        )
        
        val lowerQuery = query.lowercase()
        portugueseTitles[lowerQuery]?.let { variations.add(it) }
        
        return variations.distinct()
    }

    private suspend fun trySearchOnSite(query: String): List<SearchResponse> {
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            // CORREÇÃO: Usar parâmetro de query ?search= em vez de /pesquisar/[query]
            val searchUrl = "$mainUrl$SEARCH_PATH?search=$encodedQuery"
            println("$DEBUG_PREFIX 🔗 Tentando URL: $searchUrl")
            
            val response = app.get(searchUrl, headers = headers, timeout = 15_000)
            println("$DEBUG_PREFIX 📥 Status da busca: ${response.code}")
            
            if (response.code == 200) {
                val document = response.document
                
                // Procurar por várias estruturas possíveis
                val elements = document.select("""
                    a[href*="/animes/"],
                    a[href*="/filmes/"],
                    article.card a,
                    .anime-card a,
                    .card-anime a,
                    .divCardUltimosEps a,
                    .anime-item a,
                    .item-anime a
                """.trimIndent())
                
                println("$DEBUG_PREFIX 🔍 Elementos encontrados: ${elements.size}")
                
                return parseSearchElements(elements)
            } else if (response.code == 403 || response.code == 404) {
                // Tentar formato alternativo de URL
                return tryAlternativeSearchFormat(query)
            }
            
            emptyList()
        } catch (e: Exception) {
            println("$DEBUG_PREFIX ⚠️ Erro em trySearchOnSite: ${e.message}")
            emptyList()
        }
    }

    private suspend fun tryAlternativeSearchFormat(query: String): List<SearchResponse> {
        return try {
            // Tentar formato antigo: /pesquisar/[query]
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "$mainUrl$SEARCH_PATH/$encodedQuery"
            println("$DEBUG_PREFIX 🔗 Tentando formato alternativo: $searchUrl")
            
            val response = app.get(searchUrl, headers = headers, timeout = 15_000)
            
            if (response.code == 200) {
                val document = response.document
                val elements = document.select("a[href*='/animes/'], a[href*='/filmes/']")
                return parseSearchElements(elements)
            }
            
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun tryGetFromPopularPage(query: String): List<SearchResponse> {
        return try {
            // Buscar na página de animes populares
            val popularUrl = "$mainUrl/animes/mais-acessados"
            println("$DEBUG_PREFIX 🔗 Buscando na página popular: $popularUrl")
            
            val response = app.get(popularUrl, headers = headers, timeout = 15_000)
            
            if (response.code == 200) {
                val document = response.document
                val elements = document.select("a[href*='/animes/'], a[href*='/filmes/']")
                val results = parseSearchElements(elements)
                
                // Filtrar resultados que correspondem à query
                val filtered = results.filter { result ->
                    normalizeForMatching(result.name).contains(normalizeForMatching(query)) ||
                    normalizeForMatching(query).contains(normalizeForMatching(result.name))
                }
                
                return filtered
            }
            
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseSearchElements(elements: List<Element>): List<SearchResponse> {
        return elements.mapNotNull { element ->
            runCatching {
                val href = element.attr("href")
                if (href.isBlank() || (!href.contains("/animes/") && !href.contains("/filmes/"))) {
                    return@runCatching null
                }

                val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                
                // Extrair título
                val titleElement = element.selectFirst("""
                    h3.animeTitle, 
                    .animeTitle,
                    h3,
                    .title,
                    .card-title,
                    [class*="title"],
                    [class*="name"],
                    span,
                    div
                """.trimIndent())
                
                val rawTitle = titleElement?.text()?.trim() ?: 
                              element.attr("title")?.trim() ?: 
                              element.attr("alt")?.trim() ?: 
                              "Sem Título"
                
                val cleanTitle = cleanAnimeTitle(rawTitle)
                if (cleanTitle == "Sem Título") return@runCatching null
                
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
                
                println("$DEBUG_PREFIX 📋 Resultado encontrado: '$cleanTitle' → $fullUrl")
                
                newAnimeSearchResponse(cleanTitle, fullUrl) {
                    this.posterUrl = posterUrl
                    this.type = if (isMovie) TvType.Movie else TvType.Anime
                }
            }.getOrElse { e ->
                println("$DEBUG_PREFIX ⚠️ Erro ao processar elemento: ${e.message}")
                null
            }
        }.distinctBy { it.url }.take(10)
    }

    // ============ FUNÇÕES AUXILIARES ============
    private fun cleanAnimeTitle(title: String): String {
        val cleaned = title
            .replace(Regex("\\s*\\([^)]+\\)$"), "") // Remove (2024), (Dublado), etc no final
            .replace(Regex("\\(Dublado\\)|\\(Legendado\\)|\\(Dub\\)|\\(Sub\\)|\\(Uncensored\\)"), "")
            .replace(Regex("\\s*-\\s*Todos os Episódios$"), "")
            .replace(Regex("\\s*-\\s*Filme$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        
        if (cleaned != title) {
            println("$DEBUG_PREFIX 🧹 Limpando título: '$title' → '$cleaned'")
        }
        return cleaned
    }

    private fun findBestMatch(searchTitle: String, results: List<SearchResponse>): SearchResponse? {
        if (results.isEmpty()) return null
        
        println("$DEBUG_PREFIX 🎯 findBestMatch() - Buscando: '$searchTitle' em ${results.size} resultados")
        
        val cleanSearch = normalizeForMatching(searchTitle)
        println("$DEBUG_PREFIX 🎯 Normalizado: '$cleanSearch'")
        
        // Prioridade 1: Match exato
        for (result in results) {
            val cleanResult = normalizeForMatching(result.name)
            if (cleanResult == cleanSearch) {
                println("$DEBUG_PREFIX 🎯✅ Match exato encontrado: '${result.name}'")
                return result
            }
        }
        
        // Prioridade 2: Um contém o outro
        for (result in results) {
            val cleanResult = normalizeForMatching(result.name)
            if (cleanResult.contains(cleanSearch) || cleanSearch.contains(cleanResult)) {
                println("$DEBUG_PREFIX 🎯✅ Match parcial (contém): '${result.name}'")
                return result
            }
        }
        
        // Prioridade 3: Similaridade por palavras-chave
        val searchWords = cleanSearch.split(" ").filter { it.length > 2 }
        var bestMatch: SearchResponse? = null
        var bestScore = 0
        
        for (result in results) {
            val cleanResult = normalizeForMatching(result.name)
            var score = 0
            
            for (word in searchWords) {
                if (cleanResult.contains(word)) {
                    score++
                }
            }
            
            if (score > bestScore) {
                bestScore = score
                bestMatch = result
            }
        }
        
        if (bestMatch != null && bestScore >= 2) {
            println("$DEBUG_PREFIX 🎯✅ Melhor match por similaridade: '${bestMatch.name}' (score: $bestScore)")
            return bestMatch
        }
        
        // Prioridade 4: Primeiro resultado se tiver palavra-chave em comum
        if (bestScore > 0) {
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

    // ============ SEARCH PÚBLICA ============
    override suspend fun search(query: String): List<SearchResponse> {
        println("\n$DEBUG_PREFIX ============ SEARCH PÚBLICA ============")
        println("$DEBUG_PREFIX 🔍 Buscando: '$query'")
        
        val results = searchAnimeFire(query)
        println("$DEBUG_PREFIX ✅ Search pública retornou: ${results.size} resultados")
        return results
    }

    // ============ LOAD (CORRIGIDA) ============
    override suspend fun load(url: String): LoadResponse {
        println("\n$DEBUG_PREFIX ============ LOAD ============")
        println("$DEBUG_PREFIX 🚀 load() - URL: ${url.take(100)}...")
        
        // Se for URL do AniList (fallback), fazer busca manual
        if (url.startsWith("anilist:")) {
            println("$DEBUG_PREFIX 🎯 Detectada URL especial do AniList")
            return handleAniListUrl(url)
        }
        
        // URL normal do AnimeFire
        return loadFromAnimeFire(url)
    }

    private suspend fun handleAniListUrl(url: String): LoadResponse {
        val parts = url.split(":")
        if (parts.size >= 3) {
            val aniListId = parts[1]
            val title = parts.subList(2, parts.size).joinToString(":")
            println("$DEBUG_PREFIX 📝 ID AniList: $aniListId, Título: $title")
            
            // Tentar buscar este anime no AnimeFire
            println("$DEBUG_PREFIX 🔍 Buscando anime no site: '$title'")
            
            // Adicionar delay para evitar bloqueio
            delay(1000)
            
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
            
            // Se não encontrar, mostrar página com opção de busca
            println("$DEBUG_PREFIX ❌ Anime não encontrado no AnimeFire: $title")
            return createNotFoundResponse(title, aniListId, url)
        }
        
        // Fallback genérico
        return createGenericNotFoundResponse(url)
    }

    private suspend fun createNotFoundResponse(title: String, aniListId: String, url: String): LoadResponse {
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.plot = """
                ❌ Este anime está no AniList mas não foi encontrado no AnimeFire.
                
                📝 Título: $title
                🆔 ID AniList: $aniListId
                
                🔍 O que você pode fazer:
                1. Tente buscar manualmente usando a função de busca
                2. Verifique se o nome está correto: '$title'
                3. O anime pode estar com nome diferente no AnimeFire
                
                💡 Dica: Tente buscar por nomes alternativos em português
            """.trimIndent()
        }
    }

    private suspend fun createGenericNotFoundResponse(url: String): LoadResponse {
        return newAnimeLoadResponse("Anime não encontrado", url, TvType.Anime) {
            this.plot = "Este anime está no AniList mas não foi encontrado no AnimeFire.\n\nTente buscar manualmente."
        }
    }

    private suspend fun loadFromAnimeFire(url: String): LoadResponse {
        println("$DEBUG_PREFIX 🔗 Carregando do AnimeFire: $url")
        
        // VERIFICAÇÃO IMPORTANTE: Garantir que a URL é válida
        if (!url.startsWith(mainUrl) || url.contains("anilist:")) {
            println("$DEBUG_PREFIX ❌ URL inválida: $url")
            throw ErrorLoadingException("URL inválida do AnimeFire")
        }
        
        return try {
            val response = app.get(url, headers = headers, timeout = 15_000)
            println("$DEBUG_PREFIX 📥 Status do load: ${response.code}")
            
            if (response.code != 200) {
                println("$DEBUG_PREFIX ❌ Erro ao carregar página: ${response.code}")
                throw ErrorLoadingException("Não foi possível carregar a página (${response.code})")
            }
            
            val document = response.document
            
            // Extrair título
            val titleElement = document.selectFirst("h1.quicksand400, .main_div_anime_info h1, h1.title, h1")
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
            val isMovie = url.contains("/filmes/") || 
                         rawTitle.contains("filme", ignoreCase = true) ||
                         rawTitle.contains("movie", ignoreCase = true)
            val type = if (isMovie) TvType.Movie else TvType.Anime
            println("$DEBUG_PREFIX 🎬 Tipo: $type")
            
            // Extrair sinopse
            val plotElement = document.selectFirst("div.divSinopse, .sinopse, .description, [class*='sinopse'], [class*='description']")
            val plot = plotElement?.text()?.trim()?.replace(Regex("^Sinopse:\\s*"), "")
            println("$DEBUG_PREFIX 📖 Sinopse encontrada: ${plot?.take(50)}...")
            
            // Extrair poster
            val posterImg = document.selectFirst("""
                .sub_animepage_img img,
                .anime-poster img,
                .poster img,
                img[src*='/img/animes/'],
                img[src*='/covers/']
            """.trimIndent())
            
            val poster = when {
                posterImg?.hasAttr("src") == true -> fixUrl(posterImg.attr("src"))
                posterImg?.hasAttr("data-src") == true -> fixUrl(posterImg.attr("data-src"))
                else -> null
            }
            println("$DEBUG_PREFIX 🖼️ Poster: ${poster?.take(50)}...")
            
            // Extrair episódios (se anime)
            val episodes = if (!isMovie) {
                document.select("""
                    a.lEp.epT,
                    a.lEp,
                    .divListaEps a,
                    .episode-list a,
                    [href*='/video/'],
                    [href*='/episodio/'],
                    [href*='/assistir/']
                """.trimIndent())
                    .mapIndexedNotNull { index, element ->
                        try {
                            val href = element.attr("href")
                            if (href.isBlank()) return@mapIndexedNotNull null
                            
                            val fullEpisodeUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                            val text = element.text().trim()
                            val epNumber = extractEpisodeNumber(text) ?: (index + 1)
                            
                            val episodeName = element.selectFirst(".ep-name, .title, .name")?.text()?.trim()
                                ?: text.substringAfterLast("-").trim()
                                ?: "Episódio $epNumber"
                            
                            println("$DEBUG_PREFIX 📺 Episódio $epNumber: $episodeName")
                            
                            newEpisode(fullEpisodeUrl) {
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
        @JsonProperty("synonyms") val synonyms: List<String>? = null,
        @JsonProperty("status") val status: String? = null
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
