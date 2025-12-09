// =========================================================================
// CARREGAR DETALHES (VERSÃO SIMPLIFICADA - igual à página principal)
// =========================================================================
override suspend fun load(url: String): LoadResponse? {
    println("🎬 SuperFlix: Carregando página: $url")
    
    try {
        val document = app.get(url).document
        
        // 1. Tenta extrair usando a mesma lógica da página principal
        // Primeiro, procura por cards (pode ter cards relacionados)
        val card = document.selectFirst("a.card")
        
        if (card != null) {
            // Se encontrou um card, usa a mesma lógica da página principal
            val searchResult = card.toSearchResult()
            if (searchResult != null) {
                println("✅ Usando dados do card encontrado na página")
                return convertSearchResultToLoadResponse(searchResult, url, document)
            }
        }
        
        // 2. Se não encontrou card, extrai manualmente
        return extractFromDocument(document, url)
        
    } catch (e: Exception) {
        println("❌ Erro ao carregar página: ${e.message}")
        e.printStackTrace()
        return null
    }
}

// =========================================================================
// CONVERTER SEARCHRESULT PARA LOADRESPONSE
// =========================================================================
private suspend fun convertSearchResultToLoadResponse(
    searchResult: SearchResponse, 
    url: String, 
    document: org.jsoup.nodes.Document
): LoadResponse? {
    val isSerie = searchResult is TvSeriesSearchResponse
    
    // Extrair descrição da página
    val description = document.selectFirst("meta[property='og:description']")?.attr("content")
                     ?: document.selectFirst("meta[name='description']")?.attr("content")
                     ?: document.selectFirst(".description, .synopsis")?.text()
    
    // Extrair tags
    val tags = document.select("a[href*='/categoria/']").map { it.text() }.takeIf { it.isNotEmpty() }
    
    if (isSerie) {
        val episodes = extractEpisodesFromDocument(document, url)
        
        return newTvSeriesLoadResponse(searchResult.name, url, TvType.TvSeries, episodes) {
            this.posterUrl = searchResult.posterUrl
            this.year = searchResult.year
            this.plot = description
            this.tags = tags
        }
    } else {
        return newMovieLoadResponse(searchResult.name, url, TvType.Movie, "") {
            this.posterUrl = searchResult.posterUrl
            this.year = searchResult.year
            this.plot = description
            this.tags = tags
        }
    }
}

// =========================================================================
// EXTRATIR MANUALMENTE DO DOCUMENT
// =========================================================================
private suspend fun extractFromDocument(document: org.jsoup.nodes.Document, url: String): LoadResponse? {
    // 1. Extrair título
    val title = document.selectFirst("h1")?.text() ?: 
               document.selectFirst("title")?.text()?.replace(" | SuperFlix", "") ?: 
               return null
    
    // 2. Determinar tipo
    val isSerie = url.contains("/serie/")
    
    // 3. Extrair ano
    val yearMatch = Regex("\\((\\d{4})\\)").find(title)
    val year = yearMatch?.groupValues?.get(1)?.toIntOrNull()
    val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
    
    // 4. Extrair poster
    val poster = document.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }
                 ?: document.selectFirst(".card-img, .poster, img[src*='tmdb']")?.attr("src")?.let { fixUrl(it) }
    
    // 5. Extrair descrição
    val description = document.selectFirst("meta[property='og:description']")?.attr("content")
                     ?: document.selectFirst("meta[name='description']")?.attr("content")
    
    // 6. Extrair tags
    val tags = document.select("a[href*='/categoria/']").map { it.text() }.takeIf { it.isNotEmpty() }
    
    println("🎬 Extraído: '$cleanTitle' (${if (isSerie) "Série" else "Filme"})")
    
    if (isSerie) {
        val episodes = extractEpisodesFromDocument(document, url)
        
        // Se não encontrou episódios, criar pelo menos 1
        val finalEpisodes = if (episodes.isEmpty()) {
            listOf(newEpisode(url) {
                name = "Episódio 1"
                season = 1
                episode = 1
            })
        } else {
            episodes
        }
        
        return newTvSeriesLoadResponse(cleanTitle, url, TvType.TvSeries, finalEpisodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
        }
    } else {
        return newMovieLoadResponse(cleanTitle, url, TvType.Movie, "") {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
        }
    }
}

// =========================================================================
// EXTRATIR EPISÓDIOS (MELHORADA)
// =========================================================================
private fun extractEpisodesFromDocument(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
    val episodes = mutableListOf<Episode>()
    
    // Estratégia 1: Botões com data-url (mais comum)
    document.select("button[data-url], a[data-url]").forEachIndexed { index, element ->
        val episodeUrl = element.attr("data-url")?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
        val episodeTitle = element.attr("title")?.takeIf { it.isNotBlank() }
                          ?: element.selectFirst(".ep-title, .title, .name")?.text()?.takeIf { it.isNotBlank() }
                          ?: "Episódio ${index + 1}"
        
        episodes.add(newEpisode(fixUrl(episodeUrl)) {
            name = episodeTitle.trim()
            episode = index + 1
            season = 1
        })
    }
    
    // Estratégia 2: Links que parecem ser de episódios
    if (episodes.isEmpty()) {
        document.select("a[href*='watch'], a[href*='player']").forEachIndexed { index, element ->
            val href = element.attr("href")?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
            if (href.contains("embed") || href.contains("fembed")) {
                val episodeTitle = element.text().takeIf { it.isNotBlank() } ?: "Episódio ${index + 1}"
                
                episodes.add(newEpisode(fixUrl(href)) {
                    name = episodeTitle.trim()
                    episode = index + 1
                    season = 1
                })
            }
        }
    }
    
    return episodes.distinctBy { it.url }
}