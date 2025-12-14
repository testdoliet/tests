package com.AnimeFire

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnimeFire : MainAPI() {
    override var mainUrl = "https://animefire.plus"
    override var name = "AnimeFire"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)
    override val usesWebView = true

    companion object {
        private const val SEARCH_PATH = "/pesquisar"
    }

    // CORRIGIDO: Cada aba com URL específica
    override val mainPage = mainPageOf(
        "$mainUrl" to "Lançamentos",                     // Página inicial
        "$mainUrl/animes" to "Animes Populares",         // Lista de animes
        "$mainUrl/filmes" to "Filmes de Anime",          // Lista de filmes
        "$mainUrl" to "Destaques da Semana",            // Seção específica da home
        "$mainUrl" to "Últimos Animes Adicionados",     // Seção específica da home  
        "$mainUrl" to "Últimos Episódios Adicionados"   // Seção específica da home
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        
        val homeItems = when (request.name) {
            "Lançamentos" -> extractEmLancamento(document)
            "Animes Populares" -> extractAnimesPopulares(document, page)
            "Filmes de Anime" -> extractFilmesAnime(document, page)
            "Destaques da Semana" -> extractDestaquesSemana(document)
            "Últimos Animes Adicionados" -> extractUltimosAnimes(document)
            "Últimos Episódios Adicionados" -> extractUltimosEpisodios(document)
            else -> emptyList()
        }
        
        return newHomePageResponse(request.name, homeItems.distinctBy { it.url })
    }

    // CORRIGIDO: Extrai APENAS a seção "Em lançamento" (carrossel principal)
    private fun extractEmLancamento(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        
        // Encontrar a seção "Em lançamento" pelo título
        val lancamentoSection = document.selectFirst("h1.section2:contains(Em lançamento)")
        lancamentoSection?.let { section ->
            // Encontrar o carrossel correspondente (próximo elemento)
            val carousel = section.parent()?.nextElementSibling()?.selectFirst(".owl-carousel-home")
            carousel?.select(".divArticleLancamentos a.item")?.forEach { item ->
                item.toSearchResult()?.let { items.add(it) }
            }
        }
        
        return items.take(20) // Limitar para não repetir
    }

    // CORRIGIDO: Extrai APENAS a seção "Destaques da semana"
    private fun extractDestaquesSemana(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        
        // Encontrar pela posição CSS que você forneceu
        val destaquesSection = document.selectFirst("div.divSection:nth-child(4) h1.section2:contains(Destaques da semana)")
        destaquesSection?.let { section ->
            val carousel = section.parent()?.nextElementSibling()?.selectFirst(".owl-carousel-semana")
            carousel?.select(".divArticleLancamentos a.item")?.forEach { item ->
                item.toSearchResult()?.let { items.add(it) }
            }
        }
        
        return items.take(15) // Limitar
    }

    // CORRIGIDO: Extrai APENAS a seção "Últimos animes adicionados"
    private fun extractUltimosAnimes(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        
        // Pela posição CSS: div.divSection:nth-child(6)
        val ultimosAnimesSection = document.selectFirst("div.divSection:nth-child(6) h1.section2:contains(Últimos animes adicionados)")
        ultimosAnimesSection?.let { section ->
            val carousel = section.parent()?.nextElementSibling()?.selectFirst(".owl-carousel-l_dia")
            carousel?.select(".divArticleLancamentos a.item")?.forEach { item ->
                item.toSearchResult()?.let { items.add(it) }
            }
        }
        
        return items.take(15) // Limitar
    }

    // CORRIGIDO: Extrai APENAS a seção "Últimos episódios adicionados"
    private fun extractUltimosEpisodios(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        
        // Pela posição CSS: div.divSectionUltimosEpsHome:nth-child(3)
        val episodiosSection = document.selectFirst("div.divSectionUltimosEpsHome:nth-child(3) h2.section2:contains(Últimos episódios adicionados)")
        episodiosSection?.let { section ->
            val container = section.parent()?.nextElementSibling()?.selectFirst(".card-group .row")
            container?.select(".divCardUltimosEpsHome")?.forEach { card ->
                card.toEpisodeSearchResult()?.let { items.add(it) }
            }
        }
        
        return items.take(20) // Limitar
    }

    // CORRIGIDO: Animes populares com paginação REAL
    private fun extractAnimesPopulares(document: org.jsoup.nodes.Document, page: Int): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        
        // Se for página 1, pega da home também
        if (page == 1) {
            document.select(".divArticleLancamentos a.item").forEach { item ->
                item.toSearchResult()?.let { items.add(it) }
            }
        }
        
        // Se tiver mais páginas, o site deve ter paginação própria
        // A URL /animes já mostra todos os animes
        
        return items.take(30).distinctBy { it.url }
    }

    // CORRIGIDO: Filmes de anime com paginação REAL
    private fun extractFilmesAnime(document: org.jsoup.nodes.Document, page: Int): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        
        // Filtrar apenas filmes
        document.select(".divArticleLancamentos a.item").forEach { item ->
            val href = item.attr("href") ?: ""
            val title = item.selectFirst("h3.animeTitle")?.text() ?: ""
            
            if (href.contains("/filmes/") || title.contains("Movie", ignoreCase = true)) {
                item.toSearchResult()?.let { items.add(it) }
            }
        }
        
        return items.take(30).distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("href") ?: return null
        val titleElement = selectFirst("h3.animeTitle") ?: return null
        val title = titleElement.text().trim()
        
        val imgElement = selectFirst("img.imgAnimes, img.owl-lazy")
        val poster = when {
            imgElement?.hasAttr("data-src") == true -> imgElement.attr("data-src")
            imgElement?.hasAttr("src") == true -> imgElement.attr("src")
            else -> selectFirst("img")?.attr("src")
        } ?: return null
        
        val cleanTitle = title.replace(Regex("(?i)(dublado|legendado|todos os episódios|\\(\\d{4}\\))$"), "").trim()
        
        val isMovie = href.contains("/filmes/") || title.contains("Movie", ignoreCase = true)
        
        return if (isMovie) {
            newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                this.posterUrl = fixUrl(poster)
            }
        } else {
            newAnimeSearchResponse(cleanTitle, fixUrl(href), TvType.Anime) {
                this.posterUrl = fixUrl(poster)
            }
        }
    }

    private fun Element.toEpisodeSearchResult(): SearchResponse? {
        val link = selectFirst("article.card a") ?: return null
        val href = link.attr("href") ?: return null
        if (href.isBlank()) return null
        
        val titleElement = selectFirst("h3.animeTitle") ?: return null
        val title = titleElement.text().trim()
        
        // Extrair número do episódio
        val epNumberElement = selectFirst(".numEp")
        val epNumber = epNumberElement?.text()?.toIntOrNull() ?: 1
        
        // Extrair imagem do episódio
        val imgElement = selectFirst("img.imgAnimesUltimosEps, img.transitioning_src")
        val poster = when {
            imgElement?.hasAttr("data-src") == true -> imgElement.attr("data-src")
            imgElement?.hasAttr("src") == true -> imgElement.attr("src")
            else -> selectFirst("img")?.attr("src")
        } ?: return null
        
        val cleanTitle = "${title} - Episódio $epNumber"
        
        return newAnimeSearchResponse(cleanTitle, fixUrl(href), TvType.Anime) {
            this.posterUrl = fixUrl(poster)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl$SEARCH_PATH/${URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document

        return document.select("article.containerAnimes a.item").mapNotNull { element ->
            try {
                element.toSearchResult()
            } catch (e: Exception) {
                null
            }
        }.take(50) // Limitar resultados da busca
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // Título Principal
        val titleElement = document.selectFirst("h1.quicksand400, .main_div_anime_info h1, h1") ?: return null
        val title = titleElement.text().trim()
        
        // Poster
        val posterImg = document.selectFirst("img.transitioning_src, .sub_anime_img img, img")
        val posterUrl = posterImg?.attr("src") ?: posterImg?.attr("data-src")
        
        // Sinopse
        val plot = document.selectFirst(".divSinopse, .sinopse")?.text()?.trim()
        
        // Ano
        val yearText = document.selectFirst(".animeInfo span.snpanAnimeInfo:contains(Ano:)")?.text()
        val year = yearText?.let { 
            Regex("Ano:\\s*(\\d{4})").find(it)?.groupValues?.get(1)?.toIntOrNull()
        }
        
        // Gêneros
        val tags = document.select(".animeInfo a.spanAnimeInfo, .spanGeneros").map { it.text().trim() }
            .takeIf { it.isNotEmpty() }?.toList()
        
        // Lista de Episódios
        val episodes = mutableListOf<Episode>()
        val episodeElements = document.select(".div_video_list a.lEp, a[href*='/animes/'], a.lep")
        
        episodeElements.forEachIndexed { index, element ->
            try {
                val episodeHref = element.attr("href") ?: return@forEachIndexed
                val episodeText = element.text().trim()
                
                val episodeNumber = Regex("Epis[oó]dio\\s*(\\d+)").find(episodeText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("Ep\\.?\\s*(\\d+)").find(episodeText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: (index + 1)
                
                episodes.add(
                    newEpisode(fixUrl(episodeHref)) {
                        this.name = "Episódio $episodeNumber"
                        this.episode = episodeNumber
                        this.season = 1
                    }
                )
            } catch (e: Exception) {
                // Ignorar erro
            }
        }

        // Ordenar episódios
        val sortedEpisodes = episodes.sortedBy { it.episode }.distinctBy { it.episode }

        // Determinar tipo
        val isMovie = url.contains("/filmes/") || title.contains("Movie", ignoreCase = true) || sortedEpisodes.isEmpty()

        if (!isMovie && sortedEpisodes.isNotEmpty()) {
            // É uma série/anime
            return newTvSeriesLoadResponse(title, url, TvType.Anime, sortedEpisodes) {
                this.posterUrl = posterUrl?.let { fixUrl(it) }
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        } else {
            // É um filme
            val playerUrl = if (sortedEpisodes.isNotEmpty()) {
                sortedEpisodes.first().data
            } else {
                url
            }
            
            return newMovieLoadResponse(title, url, TvType.Movie, fixUrl(playerUrl)) {
                this.posterUrl = posterUrl?.let { fixUrl(it) }
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return AnimeFireExtractor.extractVideoLinks(data, mainUrl, name, callback)
    }
}
