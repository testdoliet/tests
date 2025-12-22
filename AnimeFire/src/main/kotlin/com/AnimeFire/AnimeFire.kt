package com.AnimeFire

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Element

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
        
        private val loadingMutex = Mutex()
        
        // LISTA COMPLETA DE TODAS AS CATEGORIAS
        private val ALL_CATEGORIES = listOf(
            "/em-lancamento" to "Lançamentos",
            "/animes-atualizados" to "Atualizados",
            "/top-animes" to "Top Animes",
            "/lista-de-animes-legendados" to "Legendados",
            "/lista-de-animes-dublados" to "Dublados",
            "/lista-de-filmes-legendados" to "Filmes Legendados",
            "/lista-de-filmes-dublados" to "Filmes Dublados",
            "/genero/acao" to "Ação",
            "/genero/aventura" to "Aventura",
            "/genero/comedia" to "Comédia",
            "/genero/drama" to "Drama",
            "/genero/fantasia" to "Fantasia",
            "/genero/romance" to "Romance",
            "/genero/shounen" to "Shounen",
            "/genero/seinen" to "Seinen",
            "/genero/esporte" to "Esporte",
            "/genero/misterio" to "Mistério",
            "/genero/artes-marciais" to "Artes Marciais",
            "/genero/demonios" to "Demônios",
            "/genero/ecchi" to "Ecchi",
            "/genero/ficcao-cientifica" to "Ficção Científica",
            "/genero/harem" to "Harém",
            "/genero/horror" to "Horror",
            "/genero/magia" to "Magia",
            "/genero/mecha" to "Mecha",
            "/genero/militar" to "Militar",
            "/genero/psicologico" to "Psicológico",
            "/genero/slice-of-life" to "Slice of Life",
            "/genero/sobrenatural" to "Sobrenatural",
            "/genero/superpoder" to "Superpoder",
            "/genero/vampiros" to "Vampiros",
            "/genero/vida-escolar" to "Vida Escolar",
            "/genero/suspense" to "Suspense",
            "/genero/musica" to "Música",
            "/genero/space" to "Espaço",
            "/genero/supernatural" to "Sobrenatural",
            "/genero/sci-fi" to "Sci-Fi",
            "/genero/policial" to "Policial",
            "/genero/historia" to "História",
            "/genero/guerra" to "Guerra",
            "/genero/familia" to "Família"
        )
        
        // Função para verificar se a aba permite N/A
        fun allowsNaItems(basePath: String): Boolean {
            return basePath.contains("/em-lancamento") || 
                   basePath.contains("/animes-atualizados") ||
                   basePath.contains("/lista-de-animes-legendados")
        }
    }

    init {
        println("🔥 ANIMEFIRE: Plugin inicializado com ${ALL_CATEGORIES.size} abas")
        println("📊 Lista completa de abas:")
        ALL_CATEGORIES.forEachIndexed { index, (path, name) ->
            println("   ${index + 1}. $name ($path)")
        }
    }

    // RETORNA TODAS AS ABAS SEM LIMITE
    override val mainPage = mainPageOf(
        *ALL_CATEGORIES.map { (path, name) -> 
            "$mainUrl$path" to name 
        }.toTypedArray()
    )

    // ============ FUNÇÃO DE EXTRACTION ============
    private fun Element.toSearchResponse(allowsNaItems: Boolean = false, debugMode: Boolean = true): AnimeSearchResponse? {
        val href = attr("href") ?: return null
        if (href.isBlank() || (!href.contains("/animes/") && !href.contains("/filmes/"))) return null
        
        if (debugMode) {
            println("\n" + "=".repeat(60))
            println("🔍 DEBUG EXTRACTION")
            println("=".repeat(60))
        }
        
        // TÍTULO
        val titleElement = selectFirst("h3.animeTitle, .animeTitle, h3, .card-title") ?: return null
        val rawTitle = titleElement.text().trim()
        
        val titleAttr = attr("title")?.trim() ?: ""
        val combinedTitle = if (titleAttr.isNotBlank() && titleAttr.length > 3) titleAttr else rawTitle
        
        if (combinedTitle.isBlank()) return null
        
        // AVALIAÇÃO
        val scoreResult = extractScoreAdvanced(this, debugMode)
        val scoreText = scoreResult.first
        
        if (debugMode) {
            println("\n📊 AVALIAÇÃO:")
            println("   • Texto: '$scoreText'")
            println("   • Permite N/A? $allowsNaItems")
        }
        
        // ============ LÓGICA DE FILTRO ============
        val shouldKeepItem = when {
            // Se a aba permite N/A, mantém tudo
            allowsNaItems -> {
                if (debugMode) println("✅ Mantido: Aba permite N/A")
                true
            }
            // Se tem avaliação válida (não N/A), mantém
            scoreText != null && scoreText != "N/A" -> {
                if (debugMode) println("✅ Mantido: Tem avaliação válida")
                true
            }
            // Se não encontrou avaliação (scoreText == null)
            scoreText == null -> {
                if (debugMode) println("✅ Mantido: Não tem avaliação (null)")
                true
            }
            // Se é N/A em aba que NÃO permite
            else -> {
                if (debugMode) println("❌ Filtrado: N/A em aba normal")
                false
            }
        }
        
        if (!shouldKeepItem) return null
        
        // PROCESSAR SCORE FINAL
        val score = when {
            scoreText == null || scoreText == "N/A" -> null
            else -> scoreText.toFloatOrNull()?.let { Score.from10(it) }
        }
        
        // DUB/LEG
        val hasExplicitDub = combinedTitle.contains("dublado", ignoreCase = true)
        val hasExplicitLeg = combinedTitle.contains("legendado", ignoreCase = true)
        
        val finalHasDub: Boolean
        val finalHasLeg: Boolean
        
        when {
            hasExplicitDub && !hasExplicitLeg -> {
                finalHasDub = true
                finalHasLeg = false
            }
            !hasExplicitDub && hasExplicitLeg -> {
                finalHasDub = false
                finalHasLeg = true
            }
            hasExplicitDub && hasExplicitLeg -> {
                finalHasDub = true
                finalHasLeg = true
            }
            else -> {
                finalHasDub = false
                finalHasLeg = true
            }
        }
        
        // NOME LIMPO
        val cleanName = extractAnimeName(combinedTitle, selectFirst(".numEp")?.text())
        
        val isMovie = href.contains("/filmes/") || combinedTitle.contains("filme", ignoreCase = true)
        
        // POSTER
        val sitePoster = try {
            selectFirst("img")?.let { img ->
                val src = when {
                    img.hasAttr("data-src") -> img.attr("data-src")
                    img.hasAttr("src") -> img.attr("src")
                    else -> null
                }?.takeIf { it.isNotBlank() }?.let { 
                    if (it.startsWith("//")) "https:$it"
                    else if (it.startsWith("/")) "$mainUrl$it"
                    else if (!it.startsWith("http")) "$mainUrl/$it"
                    else it
                }
                src
            }
        } catch (e: Exception) {
            null
        }?.let { fixUrl(it) }

        if (debugMode) {
            println("\n🎯 ITEM FINAL:")
            println("   • Nome: $cleanName")
            println("   • URL: $href")
            println("   • Score: ${score?.toString() ?: "null"}")
            println("   • Dub: $finalHasDub, Leg: $finalHasLeg")
            println("=".repeat(60))
        }

        return newAnimeSearchResponse(cleanName, fixUrl(href)) {
            this.posterUrl = sitePoster
            this.type = if (isMovie) TvType.Movie else TvType.Anime
            
            // AVALIAÇÃO (badge acima)
            this.score = score
            
            // DUB/LEG (badge abaixo)
            if (finalHasDub || finalHasLeg) {
                addDubStatus(dubExist = finalHasDub, subExist = finalHasLeg)
            }
        }
    }

    // ============ EXTRACTION DE SCORE ============
    private fun extractScoreAdvanced(element: Element, debugMode: Boolean = true): Pair<String?, String?> {
        val selectors = listOf(
            ".horaUltimosEps" to "Seletor padrão .horaUltimosEps",
            ".rating" to "Seletor .rating",
            ".score" to "Seletor .score",
            ".numEp + span" to "Próximo ao .numEp",
            ".episodes + span" to "Próximo a .episodes",
            ".card-footer span" to "No rodapé do card",
            "span:contains(★)" to "Span com estrela",
            "span:contains(/10)" to "Span com /10",
            "[class*='rating']" to "Classe contém 'rating'",
            "[class*='score']" to "Classe contém 'score'",
            "small" to "Tag small",
            "b" to "Tag bold"
        )

        // Buscar no próprio elemento
        for ((selector, description) in selectors) {
            val found = element.selectFirst(selector)?.text()?.trim()
            if (!found.isNullOrBlank() && isScoreLike(found)) {
                if (debugMode) println("✅ Score: '$found' (via: $description)")
                return found to selector
            }
        }

        // Buscar no elemento pai
        element.parent()?.let { parent ->
            for ((selector, description) in selectors) {
                val found = parent.selectFirst(selector)?.text()?.trim()
                if (!found.isNullOrBlank() && isScoreLike(found)) {
                    if (debugMode) println("✅ Score no pai: '$found'")
                    return found to "parent.$selector"
                }
            }
        }

        // Buscar via regex
        val html = element.outerHtml()
        val scoreRegexes = listOf(
            Regex("""(\d+\.\d+|\d+)\s*(?:★|/10|pontos)"""),
            Regex("""class="[^"]*(?:rating|score)[^"]*">([^<]+)""")
        )

        for (regex in scoreRegexes) {
            val match = regex.find(html)
            if (match != null) {
                val found = match.groupValues[1].trim()
                if (isScoreLike(found)) {
                    if (debugMode) println("✅ Score via regex: '$found'")
                    return found to "regex"
                }
            }
        }

        if (debugMode) println("❌ Nenhum score encontrado")
        return null to null
    }

    // ============ IDENTIFICAR SCORE ============
    private fun isScoreLike(text: String): Boolean {
        return when {
            text.equals("N/A", ignoreCase = true) -> true
            text.matches(Regex("""^\d+(\.\d+)?$""")) -> true
            text.matches(Regex("""^\d+(\.\d+)?/10$""")) -> true
            text.contains("★") -> true
            text.contains("pontos", ignoreCase = true) -> true
            else -> false
        }
    }

    // ============ LIMPAR NOME ============
    private fun extractAnimeName(fullText: String, episodeText: String?): String {
        var cleanName = fullText
        
        episodeText?.let {
            cleanName = cleanName.replace(it, "").trim()
        }
        
        val patterns = listOf(
            Regex("(?i)\\(dublado\\)"),
            Regex("(?i)\\(legendado\\)"),
            Regex("(?i)todos os episódios"),
            Regex("\\s*-\\s*$"),
            Regex("\\(\\d{4}\\)")
        )
        
        patterns.forEach { pattern ->
            cleanName = cleanName.replace(pattern, "")
        }
        
        return cleanName.trim().replace(Regex("\\s+"), " ")
    }

    // ============ DETECTAR SE TEM PRÓXIMA PÁGINA ============
    private suspend fun detectHasNextPage(document: org.jsoup.nodes.Document, currentPageNum: Int): Boolean {
        try {
            val hasElements = document.select("article, .card, .anime-item").isNotEmpty()
            if (!hasElements) return false
            
            val hasPagination = document.select(".pagination, .page-numbers, .paginacao").isNotEmpty()
            val hasNextLink = document.select("a:contains(Próxima), a:contains(›), a:contains(>), a[href*='/${currentPageNum + 1}']").isNotEmpty()
            
            return hasElements && (hasPagination || hasNextLink)
            
        } catch (e: Exception) {
            return false
        }
    }

    // ============ GET MAIN PAGE ============
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return loadingMutex.withLock {
            try {
                println("\n" + "=".repeat(70))
                println("🔥 ANIMEFIRE: Carregando página $page")
                println("=".repeat(70))
                println("📊 Aba: '${request.name}'")
                println("📊 URL base: ${request.data}")
                
                // PAGINAÇÃO
                val basePath = request.data.removePrefix(mainUrl)
                val sitePageNumber = page + 1
                
                val pageUrl = if (sitePageNumber == 1) {
                    "$mainUrl$basePath"
                } else {
                    "$mainUrl$basePath/$sitePageNumber"
                }
                
                println("📊 URL da página: $pageUrl")
                
                // VERIFICAR SE ESTA ABA PERMITE N/A
                val allowsNaItems = allowsNaItems(basePath)
                println("📊 Permite N/A? $allowsNaItems")
                println("-".repeat(70))
                
                kotlinx.coroutines.delay(200)
                
                val document = app.get(pageUrl, timeout = 25).document
                
                // PROCESSAR ITENS
                val elements = document.select("""
                    article a,
                    .card a,
                    .anime-item a,
                    a[href*='/animes/'],
                    a[href*='/filmes/']
                """).take(50)
                
                println("📊 Links encontrados: ${elements.size}")
                
                val homeItems = mutableListOf<SearchResponse>()
                var naItemsCount = 0
                var validItemsCount = 0
                
                elements.forEachIndexed { index, element ->
                    try {
                        val debugMode = index < 2
                        val item = element.toSearchResponse(
                            allowsNaItems = allowsNaItems,
                            debugMode = debugMode
                        )
                        if (item != null) {
                            homeItems.add(item)
                            // Contar tipos de itens
                            if (item.score == null) {
                                naItemsCount++
                            } else {
                                validItemsCount++
                            }
                        }
                    } catch (e: Exception) {
                        // Ignorar erro
                    }
                }
                
                // DETECTAR PRÓXIMA PÁGINA
                val hasNextPage = detectHasNextPage(document, sitePageNumber)
                
                // RESULTADO
                println("\n" + "=".repeat(70))
                println("📊 RESULTADO PÁGINA $sitePageNumber:")
                println("   • Aba: '${request.name}'")
                println("   • Itens válidos: ${homeItems.size}")
                println("   • Com avaliação: $validItemsCount")
                println("   • Sem avaliação (N/A): $naItemsCount")
                println("   • Permite N/A? $allowsNaItems")
                println("   • Tem próxima página? $hasNextPage")
                
                if (homeItems.isNotEmpty()) {
                    println("   • Primeiros itens:")
                    homeItems.take(3).forEachIndexed { i, item ->
                        val scoreText = if (item.score != null) "${item.score}" else "N/A"
                        println("     ${i + 1}. ${item.name} (score: $scoreText)")
                    }
                }
                println("=".repeat(70) + "\n")
                
                kotlinx.coroutines.delay(150)
                
                // Retornar com o MESMO nome da aba
                newHomePageResponse(
                    request.name, // Nome original
                    homeItems.distinctBy { it.url },
                    hasNext = hasNextPage
                )
                
            } catch (e: Exception) {
                println("\n❌ ERRO na página $page: ${e.message}")
                newHomePageResponse(request.name, emptyList(), false)
            }
        }
    }

    // ============ SEARCH ============
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()
        
        val searchUrl = "$mainUrl$SEARCH_PATH/${query.trim().replace(" ", "-").lowercase()}"
        
        return try {
            val document = app.get(searchUrl, timeout = 15).document
            
            document.select("a[href*='/animes/'], a[href*='/filmes/']")
                .mapNotNull { it.toSearchResponse(allowsNaItems = true, debugMode = false) }
                .distinctBy { it.url }
                .take(30)
                
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ============ FUNÇÃO AUXILIAR PARA EXTRAIR TEXTO ============
    private fun extractTextAfterLabel(document: org.jsoup.nodes.Document, label: String): String? {
        return document.select("div.animeInfo:contains($label) span.spanAnimeInfo")
            .firstOrNull()?.text()?.trim()
    }

    // ============ FUNÇÃO PARA EXTRAIR ANO CORRETO ============
    private fun extractYear(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        
        // Extrair ano de datas como "Oct 8, 2025"
        val yearRegex = Regex("""(\d{4})""")
        val match = yearRegex.find(text)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    // ============ LOAD - SIMPLIFICADO ============
    override suspend fun load(url: String): LoadResponse {
        return try {
            println("\n" + "=".repeat(80))
            println("🔥 ANIMEFIRE: Carregando anime")
            println("=".repeat(80))
            println("📊 URL: $url")
            
            val document = app.get(url, timeout = 30).document
            
            // ============ TÍTULO ============
            val title = document.selectFirst("h1.quicksand400")?.text()?.trim() 
                ?: document.selectFirst("h1.animeTitle, h1")?.text()?.trim() 
                ?: "Sem Título"
            
            println("📊 Título: $title")
            
            // ============ POSTER ============
            val poster = try {
                // Primeiro tentar pegar a imagem grande
                val largeImg = document.selectFirst("img.transitioning_src[src*='-large.webp']")
                if (largeImg != null) {
                    fixUrl(largeImg.attr("src").trim())
                } else {
                    // Tentar outras imagens
                    document.selectFirst("img.imgAnimes, .poster img, .sub_animepage_img img, img[src*='img/animes/']")
                        ?.attr("src")
                        ?.takeIf { it.isNotBlank() }
                        ?.let { fixUrl(it) }
                }
            } catch (e: Exception) {
                null
            }
            
            println("📊 Poster: ${poster?.take(50)}...")
            
            // ============ SINOPSE ============
            val synopsis = document.selectFirst("div.divSinopse span.spanAnimeInfo")
                ?.text()
                ?.trim()
                ?: document.selectFirst("div.divSinopse")
                ?.text()
                ?.replace("Sinopse:", "")
                ?.trim()
                ?: document.selectFirst("p.sinopse, .description")
                ?.text()
                ?.trim()
                ?: "Sinopse não disponível."
            
            println("📊 Sinopse (primeiros 100 chars): ${synopsis.take(100)}...")
            
            // ============ ANO ============
            val yearText = extractTextAfterLabel(document, "Ano:")
            val year = extractYear(yearText)
            println("📊 Ano: $year (texto: '$yearText')")
            
            // ============ STATUS ============
            val status = extractTextAfterLabel(document, "Status do Anime:")
                ?: "Desconhecido"
            println("📊 Status: $status")
            
            // ============ GÊNEROS/TAGS ============
            val genres = mutableListOf<String>()
            
            // Extrair gêneros dos links
            document.select("div.animeInfo a[href*='/genero/']").forEach { element ->
                element.text().trim().takeIf { it.isNotBlank() }?.let { 
                    genres.add(it) 
                }
            }
            
            // Se não encontrou pelos links, tentar extrair do texto
            if (genres.isEmpty()) {
                document.select("div.animeInfo:contains(Gênero:) span.spanAnimeInfo")
                    .firstOrNull()
                    ?.text()
                    ?.trim()
                    ?.split(",", ";")
                    ?.forEach { genre ->
                        genre.trim().takeIf { it.isNotBlank() }?.let { 
                            genres.add(it) 
                        }
                    }
            }
            
            println("📊 Gêneros: ${genres.joinToString(", ")}")
            
            // ============ ÁUDIO (DUB/LEG) ============
            val audioText = extractTextAfterLabel(document, "Áudio:")
            val hasDub = audioText?.contains("dublado", ignoreCase = true) ?: false
            val hasSub = audioText?.contains("legendado", ignoreCase = true) ?: true
            
            println("📊 Áudio: $audioText (Dub: $hasDub, Leg: $hasSub)")
            
            // ============ ESTÚDIO ============
            val studio = extractTextAfterLabel(document, "Estúdios:")
            println("📊 Estúdio: $studio")
            
            // ============ TEMPORADA ============
            val season = extractTextAfterLabel(document, "Temporada:")
            println("📊 Temporada: $season")
            
            // ============ CLASSIFICAÇÃO ============
            val rating = document.selectFirst("a.spanAnimeInfo[href*='classificação=']")
                ?.text()
                ?.trim()
                ?: "Desconhecido"
            println("📊 Classificação: $rating")
            
            // ============ SCORE ============
            val scoreText = document.selectFirst("#anime_score")?.text()?.trim()
            val score = scoreText?.toFloatOrNull()?.let { Score.from10(it) }
            println("📊 Score: $scoreText -> $score")
            
            // ============ EPISÓDIOS ============
            val totalEpisodesText = extractTextAfterLabel(document, "Episódios:")
            val totalEpisodes = totalEpisodesText?.toIntOrNull()
            println("📊 Total de episódios: $totalEpisodes (texto: '$totalEpisodesText')")
            
            // ============ DETECTAR SE É FILME ============
            val isMovie = url.contains("/filmes/") || 
                         title.contains("filme", ignoreCase = true) ||
                         totalEpisodesText == "1"
            
            println("📊 É filme? $isMovie")
            
            // ============ EXTRAIR EPISÓDIOS ============
            val episodes = extractAllEpisodes(document, url)
            println("📊 Episódios extraídos: ${episodes.size}")
            
            // ============ CRIAR LOAD RESPONSE ============
            val response = newAnimeLoadResponse(
                title, 
                url, 
                if (isMovie) TvType.Movie else TvType.Anime
            ) {
                this.posterUrl = poster
                this.year = year
                this.plot = synopsis
                this.tags = genres
                this.score = score
                
                // Adicionar estúdio (se disponível)
                if (studio != null) {
                    try {
                        val studioField = this::class.members.find { it.name == "studio" }
                        studioField?.call(this, studio)
                    } catch (e: Exception) {}
                }
                
                // Adicionar episódios
                try {
                    val episodesField = this::class.members.find { it.name == "episodes" }
                    episodesField?.call(this, episodes)
                } catch (e: Exception) {}
                
                // SIMPLIFICADO: Não adicionar dubStatus no load()
                // (O CloudStream não tem uma maneira fácil de adicionar isso no LoadResponse)
            }
            
            // ============ DEBUG FINAL ============
            println("\n" + "=".repeat(80))
            println("📊 LOAD COMPLETO:")
            println("   • Título: ${response.name}")
            println("   • Tipo: ${response.type}")
            println("   • Ano: ${response.year}")
            println("   • Score: ${response.score}")
            println("   • É filme? $isMovie")
            println("   • Episódios: ${episodes.size}")
            println("   • Tags: ${response.tags?.joinToString(", ") ?: "nenhum"}")
            println("=".repeat(80) + "\n")
            
            response
            
        } catch (e: Exception) {
            println("\n❌ ERRO no load: ${e.message}")
            e.printStackTrace()
            
            newAnimeLoadResponse("Erro ao carregar", url, TvType.Anime) {
                this.plot = "Não foi possível carregar esta página. Erro: ${e.message}"
            }
        }
    }

    // ============ EXTRACT EPISODES - MELHORADA ============
    private fun extractAllEpisodes(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        println("\n🔍 Procurando episódios...")
        
        // Procurar por links de episódios
        document.select("a.lEp, .episode-item a, a[href*='/episodio'], a[href*='/animes/']").forEach { element ->
            try {
                val episodeUrl = element.attr("href")?.takeIf { it.isNotBlank() } ?: return@forEach
                
                // Verificar se é realmente um link de episódio
                if (!episodeUrl.contains("/episodio") && !episodeUrl.contains("/animes/")) {
                    return@forEach
                }
                
                val episodeText = element.text().trim()
                println("   • Link encontrado: $episodeText -> $episodeUrl")
                
                // Extrair número do episódio
                val episodeNumber = try {
                    // Tentar extrair número de várias formas
                    val patterns = listOf(
                        Regex("""Episódio\s+(\d{1,4})"""),
                        Regex("""Ep\.?\s*(\d{1,4})"""),
                        Regex("""(\d{1,4})""")
                    )
                    
                    var foundNumber: Int? = null
                    for (pattern in patterns) {
                        val match = pattern.find(episodeText)
                        if (match != null) {
                            foundNumber = match.groupValues[1].toIntOrNull()
                            if (foundNumber != null) break
                        }
                    }
                    
                    foundNumber ?: return@forEach
                } catch (e: Exception) {
                    return@forEach
                }
                
                // Determinar tipo de áudio
                val audioType = when {
                    episodeText.contains("dublado", ignoreCase = true) -> " (Dub)"
                    episodeText.contains("legendado", ignoreCase = true) -> " (Leg)"
                    else -> ""
                }
                
                episodes.add(
                    newEpisode(Pair("Episódio $episodeNumber$audioType", fixUrl(episodeUrl))) {
                        this.name = "Episódio $episodeNumber$audioType"
                        this.episode = episodeNumber
                    }
                )
                
                println("     ✅ Episódio $episodeNumber adicionado")
                
            } catch (e: Exception) {
                println("     ❌ Erro no episódio: ${e.message}")
            }
        }
        
        // Se não encontrou episódios, verificar se é um filme (apenas 1 episódio)
        if (episodes.isEmpty()) {
            println("   ⚠️ Nenhum episódio encontrado, tratando como filme")
            episodes.add(
                newEpisode(Pair("Assistir Filme", fixUrl(baseUrl))) {
                    this.name = "Assistir Filme"
                    this.episode = 1
                }
            )
        }
        
        println("📊 Total de episódios processados: ${episodes.size}")
        
        return episodes.sortedBy { it.episode }
    }

    // ============ LOAD LINKS ============
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return false
    }
}
