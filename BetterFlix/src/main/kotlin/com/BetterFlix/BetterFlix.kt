package com.Betterflix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty

class BetterFlixProvider : MainAPI() {
    override var mainUrl = "https://betterflix.vercel.app"
    override var name = "BetterFlix"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    // 🔧 Configuração de debug
    private val DEBUG = true
    
    private fun debug(message: String) {
        if (DEBUG) {
            println("🔍 [BetterFlix] $message")
        }
    }

    private fun debugError(message: String, error: Throwable? = null) {
        if (DEBUG) {
            println("❌ [BetterFlix] ERRO: $message")
            error?.printStackTrace()
        }
    }

    private fun debugSuccess(message: String) {
        if (DEBUG) {
            println("✅ [BetterFlix] $message")
        }
    }

    private fun debugApi(url: String, status: Int, itemsCount: Int = 0) {
        if (DEBUG) {
            println("🌐 [BetterFlix] API: $url")
            println("   📊 Status: $status | Itens: $itemsCount")
        }
    }

    // Headers fixos para todas as requisições
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
        "Referer" to "$mainUrl/",
        "sec-ch-ua" to "\"Chromium\";v=\"127\", \"Not)A;Brand\";v=\"99\", \"Microsoft Edge Simulate\";v=\"127\", \"Lemur\";v=\"127\"",
        "sec-ch-ua-mobile" to "?1",
        "sec-ch-ua-platform" to "\"Android\""
    )

    // 📂 Mapeamento de gêneros
    private val genreMap = mapOf(
        "28" to "🎬 Ação e Aventura",
        "35" to "😂 Comédia", 
        "27" to "😱 Terror e Suspense",
        "99" to "📚 Documentário",
        "10751" to "👨‍👩‍👧‍👦 Para a Família",
        "80" to "🔫 Crime",
        "10402" to "🎵 Musical",
        "10749" to "💖 Romance"
    )

    // 📦 Modelo da resposta da API
    data class ApiResponse(
        @JsonProperty("results") val results: List<MediaItem> = emptyList(),
        @JsonProperty("items") val items: List<MediaItem> = emptyList()
    )

    data class MediaItem(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("media_type") val mediaType: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("genre_ids") val genreIds: List<Int>? = null,
        @JsonProperty("original_language") val originalLanguage: String? = null
    )

    // 🏠 Páginas principais
    override val mainPage = mainPageOf(
        "trending" to "🔥 Em Alta",
        "28" to "🎬 Ação e Aventura",
        "35" to "😂 Comédia", 
        "27" to "😱 Terror e Suspense",
        "99" to "📚 Documentário",
        "10751" to "👨‍👩‍👧‍👦 Para a Família",
        "80" to "🔫 Crime",
        "10402" to "🎵 Musical",
        "10749" to "💖 Romance",
        "anime" to "🇯🇵 Animes"
    )

    override suspend fun getMainPage(
        page: Int, 
        request: MainPageRequest
    ): HomePageResponse {
        debug("📥 Iniciando getMainPage: Página $page | Categoria: ${request.name} (${request.displayName})")
        
        val items = mutableListOf<HomePageList>()
        
        try {
            when (request.name) {
                "trending" -> {
                    debug("📈 Buscando conteúdos em alta...")
                    val url = "$mainUrl/api/trending?type=all"
                    
                    val response = app.get(url, headers = headers)
                    debugApi(url, response.code)
                    
                    val data = response.parsedSafe<ApiResponse>()
                    val results = data?.results ?: emptyList()
                    
                    debug("🎯 Em Alta: ${results.size} itens encontrados")
                    if (results.isNotEmpty()) {
                        debug("   📌 Primeiro item: ${results.first().title ?: results.first().name}")
                        debug("   🎬 Tipo: ${results.first().mediaType}")
                    }
                    
                    items.add(HomePageList(
                        name = request.displayName,
                        list = results.mapNotNull { 
                            debug("   ➡️ Convertendo: ${it.title ?: it.name}")
                            it.toSearchResponse() 
                        },
                        isHorizontalImages = true
                    ))
                    debugSuccess("✅ Seção 'Em Alta' carregada com ${results.size} itens")
                }
                
                "anime" -> {
                    debug("🇯🇵 Buscando animes...")
                    val url = "$mainUrl/api/list-animes"
                    
                    val response = app.get(url, headers = headers)
                    debugApi(url, response.code)
                    
                    val data = response.parsedSafe<ApiResponse>()
                    val results = data?.results ?: data?.items ?: emptyList()
                    
                    debug("🎌 Animes: ${results.size} itens encontrados")
                    if (results.isNotEmpty()) {
                        debug("   📌 Primeiro anime: ${results.first().title ?: results.first().name}")
                        debug("   🌐 Idioma: ${results.first().originalLanguage ?: "N/A"}")
                    }
                    
                    items.add(HomePageList(
                        name = request.displayName,
                        list = results.mapNotNull { 
                            debug("   ➡️ Convertendo anime: ${it.title ?: it.name}")
                            it.toSearchResponse() 
                        },
                        isHorizontalImages = true
                    ))
                    debugSuccess("✅ Seção 'Animes' carregada com ${results.size} itens")
                }
                
                else -> {
                    if (genreMap.containsKey(request.name)) {
                        val genreName = genreMap[request.name] ?: request.name
                        debug("🎭 Buscando gênero: $genreName (ID: ${request.name})")
                        
                        val url = "$mainUrl/api/preview-genre?id=${request.name}"
                        
                        val response = app.get(url, headers = headers)
                        debugApi(url, response.code)
                        
                        val data = response.parsedSafe<ApiResponse>()
                        val results = data?.results ?: emptyList()
                        
                        debug("📊 Gênero $genreName: ${results.size} itens encontrados")
                        if (results.isNotEmpty()) {
                            val firstItem = results.first()
                            debug("   📌 Primeiro item: ${firstItem.title ?: firstItem.name}")
                            debug("   🎬 Tipo: ${firstItem.mediaType}")
                            debug("   ⭐ Avaliação: ${firstItem.voteAverage ?: "N/A"}")
                        }
                        
                        items.add(HomePageList(
                            name = genreName,
                            list = results.mapNotNull { 
                                debug("   ➡️ Convertendo: ${it.title ?: it.name} (${it.mediaType})")
                                it.toSearchResponse() 
                            },
                            isHorizontalImages = true
                        ))
                        debugSuccess("✅ Gênero '$genreName' carregado com ${results.size} itens")
                    } else {
                        debugError("⚠️ Categoria desconhecida: ${request.name}")
                    }
                }
            }
        } catch (e: Exception) {
            debugError("💥 Erro ao carregar página principal", e)
        }
        
        debug("📊 Total de seções carregadas: ${items.size}")
        debug("📦 Total de itens em todas as seções: ${items.sumOf { it.list.size }}")
        
        if (items.isEmpty()) {
            debugError("🚨 Nenhum item carregado! Verifique a conexão ou a API")
        } else {
            debugSuccess("✨ HomePage carregada com sucesso!")
        }
        
        // ✅ CORREÇÃO: Usar newHomePageResponse em vez do construtor antigo
        return newHomePageResponse(items, hasNext = false)
    }

    // 🎯 Converte item da API para SearchResponse do CloudStream
    private fun MediaItem.toSearchResponse(): SearchResponse? {
        debug("   🛠️ Iniciando conversão do item...")
        
        val itemId = this.id ?: run {
            debugError("   ❌ Item sem ID!")
            return null
        }
        
        val itemTitle = this.title ?: this.name ?: run {
            debugError("   ❌ Item sem título! ID: $itemId")
            return null
        }
        
        val itemType = when (this.mediaType) {
            "movie" -> {
                debug("   🎥 Tipo: Filme")
                TvType.Movie
            }
            "tv" -> {
                debug("   📺 Tipo: Série")
                TvType.TvSeries
            }
            "anime" -> {
                debug("   🇯🇵 Tipo: Anime")
                TvType.Anime
            }
            else -> {
                debug("   ❓ Tipo desconhecido: ${this.mediaType}, usando Série como padrão")
                TvType.TvSeries
            }
        }
        
        // 🖼️ URL da imagem
        val posterUrl = this.posterPath?.let { path ->
            if (path.startsWith("http")) {
                debug("   🖼️ Poster URL completo: ${path.take(50)}...")
                path
            } else {
                val fullPath = "https://image.tmdb.org/t/p/w500$path"
                debug("   🖼️ Poster TMDB: $fullPath")
                fullPath
            }
        } ?: run {
            debug("   ⚠️ Sem poster")
            null
        }
        
        val backdropUrl = this.backdropPath?.let { path ->
            if (path.startsWith("http")) {
                debug("   🎨 Backdrop URL completo: ${path.take(50)}...")
                path
            } else {
                val fullPath = "https://image.tmdb.org/t/p/w780$path"
                debug("   🎨 Backdrop TMDB: $fullPath")
                fullPath
            }
        } ?: run {
            debug("   ⚠️ Sem backdrop")
            null
        }
        
        // 📅 Ano de lançamento
        val year = (this.releaseDate ?: this.firstAirDate)?.take(4)?.toIntOrNull()
        debug("   📅 Ano: ${year ?: "Desconhecido"}")
        
        // ⭐ Qualidade baseada na avaliação
        val vote = this.voteAverage ?: 0.0
        // ✅ CORREÇÃO: Usar o enum correto para qualidade
        val quality = when (vote) {
            in 8.0..10.0 -> {
                debug("   ⭐⭐ Avaliação excelente: $vote (HD)")
                SearchQuality.HD
            }
            in 6.0..7.9 -> {
                debug("   ⭐ Avaliação boa: $vote (SD)")
                SearchQuality.SD
            }
            else -> {
                debug("   ⚠️ Avaliação baixa/desconhecida: $vote")
                null // ✅ CORREÇÃO: Não usar SearchQuality.Unknown
            }
        }
        
        debug("   📝 Sinopse: ${this.overview?.take(50) ?: "N/A"}...")
        debug("   🎭 Gêneros: ${this.genreIds?.size ?: 0} gêneros")
        
        debugSuccess("   ✅ Item convertido: $itemTitle (ID: $itemId)")
        
        // ✅ CORREÇÃO: Usar a nova API corretamente
        return newMovieSearchResponse(
            name = itemTitle,
            url = itemId.toString(),
            apiName = this@BetterFlixProvider.name,
            type = itemType
        ) {
            this.posterUrl = posterUrl
            this.backgroundPosterUrl = backdropUrl // ✅ CORREÇÃO: backdropUrl mudou para backgroundPosterUrl
            this.year = year
            this.quality = quality
            addPlot(this@toSearchResponse.overview) // ✅ CORREÇÃO: Usar addPlot
        }
    }

    // 🔍 Busca simples (pode ser expandida depois)
    override suspend fun search(query: String): List<SearchResponse> {
        debug("🔎 Buscando: '$query'")
        debug("⚠️ Busca não implementada ainda")
        return emptyList()
    }

    // 📄 Carregar detalhes (placeholder com debug)
    override suspend fun load(url: String): LoadResponse {
        debug("📄 Carregando detalhes para URL: $url")
        
        val id = url.toIntOrNull() ?: run {
            debugError("❌ ID inválido na URL: $url")
            throw ErrorLoadingException("ID inválido: $url")
        }
        
        debug("🎬 Preparando LoadResponse para ID: $id")
        
        // ✅ CORREÇÃO: Usar newMovieLoadResponse em vez do construtor antigo
        return newMovieLoadResponse(
            name = "🔄 Carregando detalhes...",
            url = url,
            apiName = this.name,
            type = TvType.Movie,
            dataUrl = url
        ) {
            addPlot("""
            📋 **Informações do Item**
            
            🆔 **ID:** $id
            🌐 **Fonte:** BetterFlix API
            
            ⚠️ **Status:** Detalhes completos não implementados ainda.
            
            🔧 **Próximos passos:**
            1. Implementar endpoint de detalhes na API
            2. Buscar informações completas do TMDB
            3. Adicionar elenco, temporadas, etc.
            
            📢 **Debug Info:**
            - URL recebida: $url
            - Plugin: BetterFlix
            """.trimIndent())
        }
    }

    // 🎬 Links de streaming (placeholder com debug)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        debug("🎬 loadLinks chamado")
        debug("📦 Data recebida: ${data.take(100)}...")
        debug("🎥 Casting mode: $isCasting")
        debug("⚠️ Links de streaming não implementados ainda")
        
        // Simulando um link para teste
        if (DEBUG) {
            debug("🧪 Modo debug ativo - criando link de teste")
            // callback(ExtractorLink(...)) // Descomente para testar
        }
        
        return false
    }

    // 🌐 Teste de conexão (opcional)
    override suspend fun checkAvailability(): Boolean {
        debug("🌐 Testando conexão com a API...")
        return try {
            val response = app.get("$mainUrl/api/trending?type=all", headers = headers, timeout = 30)
            val available = response.code == 200
            if (available) {
                debugSuccess("✅ API está online! Status: ${response.code}")
            } else {
                debugError("❌ API offline ou com erro. Status: ${response.code}")
            }
            available
        } catch (e: Exception) {
            debugError("💥 Falha ao conectar com a API", e)
            false
        }
    }
}
