package com.AnimeFire

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
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
    }

    // ============ PÁGINA INICIAL COM TODAS AS LISTAS ============
    override val mainPage = mainPageOf(
        // CATEGORIA: LANÇAMENTOS E DESTAQUES
        "$mainUrl/em-lancamento" to "Em Lançamento",
        "$mainUrl/animes-atualizados" to "Animes Atualizados",
        "$mainUrl/top-animes" to "Top Animes",
        "$mainUrl" to "Lançamentos",
        
        // CATEGORIA: AUDIO (DUB/LEG)
        "$mainUrl/lista-de-animes-legendados" to "Animes Legendados",
        "$mainUrl/lista-de-animes-dublados" to "Animes Dublados",
        "$mainUrl/lista-de-filmes-legendados" to "Filmes Legendados",
        "$mainUrl/lista-de-filmes-dublados" to "Filmes Dublados",
        
        // CATEGORIA: GÊNEROS POPULARES
        "$mainUrl/genero/acao" to "Ação",
        "$mainUrl/genero/aventura" to "Aventura",
        "$mainUrl/genero/comedia" to "Comédia",
        "$mainUrl/genero/drama" to "Drama",
        "$mainUrl/genero/fantasia" to "Fantasia",
        "$mainUrl/genero/romance" to "Romance",
        "$mainUrl/genero/shounen" to "Shounen",
        "$mainUrl/genero/seinen" to "Seinen",
        "$mainUrl/genero/esporte" to "Esporte",
        "$mainUrl/genero/misterio" to "Mistério",
        
        // CATEGORIA: MAIS GÊNEROS
        "$mainUrl/genero/artes-marciais" to "Artes Marciais",
        "$mainUrl/genero/demonios" to "Demônios",
        "$mainUrl/genero/ecchi" to "Ecchi",
        "$mainUrl/genero/ficcao-cientifica" to "Ficção Científica",
        "$mainUrl/genero/harem" to "Harém",
        "$mainUrl/genero/horror" to "Horror",
        "$mainUrl/genero/magia" to "Magia",
        "$mainUrl/genero/mecha" to "Mecha",
        "$mainUrl/genero/militar" to "Militar",
        "$mainUrl/genero/psicologico" to "Psicológico",
        "$mainUrl/genero/slice-of-life" to "Slice of Life",
        "$mainUrl/genero/sobrenatural" to "Sobrenatural",
        "$mainUrl/genero/superpoder" to "Superpoder",
        "$mainUrl/genero/vampiros" to "Vampiros",
        "$mainUrl/genero/vida-escolar" to "Vida Escolar",
        
        // CATEGORIA: OUTROS GÊNEROS
        "$mainUrl/genero/espaco" to "Espaço",
        "$mainUrl/genero/jogos" to "Jogos",
        "$mainUrl/genero/josei" to "Josei",
        "$mainUrl/genero/musical" to "Musical",
        "$mainUrl/genero/parodia" to "Paródia",
        "$mainUrl/genero/shoujo-ai" to "Shoujo-ai",
        "$mainUrl/genero/suspense" to "Suspense"
    )

    // ============ FUNÇÃO PRINCIPAL DE BUSCA ============
    
    private fun Element.toSearchResponse(): AnimeSearchResponse? {
        val href = attr("href") ?: return null
        if (href.isBlank()) return null
        
        // ============ EXTRAIR TÍTULO E ATRIBUTOS ============
        val titleElement = selectFirst("h3.animeTitle, .text-block h3, .animeTitle, h3") ?: return null
        val rawTitle = titleElement.text().trim()
        
        val titleAttr = attr("title")?.trim() ?: ""
        val combinedTitle = if (titleAttr.isNotBlank()) titleAttr else rawTitle
        
        // ============ DETECTAR DUBLADO/LEGENDADO ============
        val hasDub = combinedTitle.contains("dublado", ignoreCase = true)
        val hasLeg = combinedTitle.contains("legendado", ignoreCase = true)
        
        // ============ EXTRAIR NÚMERO DO EPISÓDIO ============
        var episodeNumber: Int? = null
        var episodeText: String? = null
        
        val numEpElement = selectFirst(".numEp")
        if (numEpElement != null) {
            episodeText = numEpElement.text().trim()
            episodeNumber = extractEpisodeNumber(episodeText)
        }
        
        // ============ EXTRAIR NOME LIMPO DO ANIME ============
        val cleanAnimeName = extractAnimeName(combinedTitle, episodeText)
        
        // ============ EXTRAIR NOTA/AVALIAÇÃO ============
        val scoreText = selectFirst(".horaUltimosEps, .rating")?.text()?.trim()
        val score = when {
            scoreText == null || scoreText == "N/A" -> Score.from10(0f)
            else -> scoreText.toFloatOrNull()?.let { Score.from10(it) } ?: Score.from10(0f)
        }
        
        val isMovie = href.contains("/filmes/") || combinedTitle.contains("filme", ignoreCase = true)
        
        // ============ EXTRAIR POSTER ============
        val sitePoster = selectFirst("img.imgAnimes, img.card-img-top, img.transitioning_src, img.owl-lazy, .imgAnimesUltimosEps")?.let { img ->
            when {
                img.hasAttr("data-src") -> img.attr("data-src")
                img.hasAttr("src") -> img.attr("src")
                else -> null
            }
        }

        // ============ CRIAR RESPOSTA DE BUSCA ============
        return newAnimeSearchResponse(cleanAnimeName, fixUrl(href)) {
            this.posterUrl = sitePoster?.let { fixUrl(it) }
            this.type = if (isMovie) TvType.Movie else TvType.Anime
            this.score = score
            
            // Adicionar status de dublagem se detectado
            if (hasDub || hasLeg) {
                addDubStatus(
                    dubExist = hasDub,
                    subExist = hasLeg
                )
            }
        }
    }

    // ============ FUNÇÃO PARA EXTRAIR NOME DO ANIME ============
    private fun extractAnimeName(fullText: String, episodeText: String?): String {
        var cleanName = fullText
        
        // Remover indicação de episódio
        episodeText?.let {
            cleanName = cleanName.replace(it, "")
        }
        
        // Padrões de limpeza
        val patterns = listOf(
            Regex("(?i)\\(dublado\\)"),
            Regex("(?i)\\(legendado\\)"),
            Regex("(?i)todos os episódios"),
            Regex("\\(\\d{4}\\)"),
            Regex("\\s*-\\s*$"),
            Regex("\\s*:\\s*$")
        )
        
        patterns.forEach { pattern ->
            cleanName = cleanName.replace(pattern, "")
        }
        
        // Limpar espaços extras
        cleanName = cleanName.trim().replace(Regex("\\s+"), " ")
        
        // Se ainda tiver " - " no final, remover
        if (cleanName.endsWith("-")) {
            cleanName = cleanName.substringBeforeLast("-").trim()
        }
        
        return cleanName
    }

    // ============ EXTRATOR DE NÚMERO DE EPISÓDIO ============
    private fun extractEpisodeNumber(text: String): Int? {
        val patterns = listOf(
            Regex("Epis[oó]dio\\s*(\\d+)"),
            Regex("Ep\\.?\\s*(\\d+)"),
            Regex("(\\d{1,3})\\s*-"),
            Regex("#(\\d+)"),
            Regex("\\b(\\d{1,4})\\b")
        )
        
        for (pattern in patterns) {
            pattern.find(text)?.let { match ->
                return match.groupValues[1].toIntOrNull()
            }
        }
        return null
    }

    // ============ GET MAIN PAGE ATUALIZADA ============
    
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data == mainUrl && request.name == "Lançamentos") {
            // Para "Lançamentos", usar a página inicial
            mainUrl
        } else {
            request.data
        }
        
        val document = app.get(url).document
        
        // Para páginas de gênero, usar seletores específicos
        val elements = if (url.contains("/genero/")) {
            document.select("article.card a, .divArticleLancamentos a.item, .anime-item a")
        } else if (url.contains("/lista-de-") || url.contains("/em-lancamento") || url.contains("/animes-atualizados") || url.contains("/top-animes")) {
            document.select(".divCardUltimosEps a, .divArticleLancamentos a.item, article.card a")
        } else {
            // Página inicial e outras
            document.select(".owl-carousel-home .divArticleLancamentos a.item, .divCardUltimosEps a")
        }
        
        val homeItems = elements.mapNotNull { it.toSearchResponse() }.distinctBy { it.url }
        
        return newHomePageResponse(request.name, homeItems, hasNext = false)
    }

    // ============ BUSCA ============
    
    override suspend fun search(query: String): List<SearchResponse> {
        val formattedQuery = query
            .trim()
            .replace(Regex("\\s+"), "-")
            .lowercase()
        
        val searchUrl = "$mainUrl$SEARCH_PATH/$formattedQuery"
        val document = app.get(searchUrl).document

        val elements = document.select("div.divCardUltimosEps article.card a, .search-result a")
        
        return elements.mapNotNull { element ->
            element.toSearchResponse()
        }.take(30)
    }

    // ============ LOAD PRINCIPAL (PÁGINA DE DETALHES) ============
    
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        // ============ TÍTULO ============
        val title = document.selectFirst("h1.animeTitle")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: "Sem título"
        
        // ============ POSTER ============
        val poster = document.selectFirst("img.imgAnimes")?.attr("src")?.let { fixUrl(it) }
            ?: document.selectFirst("img.rounded-3")?.attr("src")?.let { fixUrl(it) }
            ?: document.selectFirst("img.card-img-top")?.attr("src")?.let { fixUrl(it) }
        
        // ============ SINOPSE ============
        val synopsis = document.selectFirst("p.sinopse")?.text()?.trim()
            ?: document.selectFirst("div.text-muted")?.text()?.trim()
            ?: "Sinopse não disponível."
        
        // ============ ANO E GÊNEROS ============
        val year = document.select("div.animeInfo")
            .find { it.text().contains("Ano:", ignoreCase = true) }
            ?.selectFirst("span.spanAnimeInfo")?.text()?.trim()?.toIntOrNull()
        
        val genres = document.select("div.animeInfo")
            .find { it.text().contains("Gênero:", ignoreCase = true) }
            ?.select("span.spanAnimeInfo a")?.map { it.text().trim() }
            ?: emptyList()
        
        // ============ VERIFICA SE É FILME ============
        val isMovie = url.contains("/filmes/") || title.contains("filme", ignoreCase = true)
        
        // ============ TRAILER (se disponível) ============
        val trailer = document.selectFirst("iframe[src*='youtube']")?.attr("src")
            ?: document.selectFirst("a[href*='youtube']")?.attr("href")
        
        // ============ EPISÓDIOS ============
        val episodes = if (isMovie) {
            listOf(
                newEpisode(Pair("Filme", url))
            )
        } else {
            extractAllEpisodes(document, url)
        }
        
        // ============ CONSTRUIR LOAD RESPONSE ============
        return newAnimeLoadResponse(title, url, if (isMovie) TvType.Movie else TvType.Anime) {
            this.posterUrl = poster
            this.year = year
            this.plot = synopsis
            this.tags = genres
            
            // Adicionar trailer se existir
            if (trailer != null) {
                addTrailer(trailer)
            }
            
            // Criar lista de episódios
            val episodeList = episodes.map { episode ->
                newEpisode(Pair(episode.name ?: "Episódio", episode.data))
            }
            
            // Usar reflexão para definir episódios se o campo existir
            try {
                val episodesField = this::class.members.find { it.name == "episodes" }
                episodesField?.call(this, episodeList)
            } catch (e: Exception) {
                println("AnimeFire: Não foi possível definir episódios para $title")
            }
        }
    }

    // ============ FUNÇÃO PARA EXTRAIR TODOS OS EPISÓDIOS ============
    
    private fun extractAllEpisodes(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        // Extrair todos os episódios da página
        document.select("a.lEp.epT, a.lEp, .divListaEps a").forEach { episodeElement ->
            try {
                val episodeUrl = episodeElement.attr("href")?.takeIf { it.isNotBlank() }
                if (episodeUrl.isNullOrBlank()) return@forEach
                
                val episodeText = episodeElement.text().trim()
                val episodeNumber = AnimeFireUtils.extractEpisodeNumber(episodeText)
                
                if (episodeNumber != null) {
                    val audioType = when {
                        episodeText.contains("dublado", ignoreCase = true) -> " (Dub)"
                        episodeText.contains("legendado", ignoreCase = true) -> " (Leg)"
                        else -> ""
                    }
                    
                    val episodeName = "Episódio $episodeNumber$audioType"
                    
                    episodes.add(
                        newEpisode(Pair(episodeName, fixUrl(episodeUrl))) {
                            this.name = episodeName
                            this.episode = episodeNumber
                        }
                    )
                }
            } catch (e: Exception) {
                // Ignorar episódio com erro
            }
        }
        
        // Se não encontrou episódios, verificar outras estruturas
        if (episodes.isEmpty()) {
            document.select("div.episodesContainer a, .episode-item a").forEach { episodeElement ->
                try {
                    val episodeUrl = episodeElement.attr("href")?.takeIf { it.isNotBlank() }
                    if (episodeUrl.isNullOrBlank()) return@forEach
                    
                    val episodeText = episodeElement.text().trim()
                    val episodeNumber = extractEpisodeNumber(episodeText)
                    
                    if (episodeNumber != null) {
                        episodes.add(
                            newEpisode(Pair("Episódio $episodeNumber", fixUrl(episodeUrl))) {
                                this.name = "Episódio $episodeNumber"
                                this.episode = episodeNumber
                            }
                        )
                    }
                } catch (e: Exception) {
                    // Ignorar episódio com erro
                }
            }
        }
        
        // Ordenar episódios por número
        return episodes.sortedBy { it.episode }
    }

    // ============ LOAD LINKS (CHAMAR EXTRACTOR) ============
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return AnimeFireVideoExtractor.extractVideoLinks(data, mainUrl, name, callback)
    }
}

// ============ FUNÇÃO GETSTATUS ============
fun getStatus(t: String?): ShowStatus {
    if (t == null) {
        return ShowStatus.Completed
    }
    
    val status = t.trim()
    
    return when {
        status.contains("em lançamento", ignoreCase = true) ||
        status.contains("lançando", ignoreCase = true) ||
        status.contains("em andamento", ignoreCase = true) ||
        status.contains("ongoing", ignoreCase = true) ||
        status.contains("atualizando", ignoreCase = true) -> ShowStatus.Ongoing
        
        status.contains("concluído", ignoreCase = true) ||
        status.contains("completo", ignoreCase = true) ||
        status.contains("completado", ignoreCase = true) ||
        status.contains("terminado", ignoreCase = true) ||
        status.contains("finished", ignoreCase = true) -> ShowStatus.Completed
        
        else -> ShowStatus.Completed
    }
}
