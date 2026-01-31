package com.FilmesPK

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class FilmesPK : MainAPI() {
    override var mainUrl = "https://www.filmesrave.online"
    override var name = "Filmes P K"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override val mainPage = mainPageOf(
        "$mainUrl" to "Top 10 da Semana",
        "$mainUrl/search/label/S%C3%A9rie" to "Série",
        "$mainUrl/search/label/Doramas" to "Doramas",
        "$mainUrl/search/label/Disney" to "Disney",
        "$mainUrl/search/label/Anime" to "Anime",
        "$mainUrl/search/label/A%C3%A7%C3%A3o" to "Ação",
        "$mainUrl/search/label/Anima%C3%A7%C3%A3o" to "Animação",
        "$mainUrl/search/label/Aventura" to "Aventura",
        "$mainUrl/search/label/Com%C3%A9dia" to "Comédia",
        "$mainUrl/search/label/Drama" to "Drama",
        "$mainUrl/search/label/Romance" to "Romance",
        "$mainUrl/search/label/Terror" to "Terror"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isHomePage = request.data == mainUrl
        val url = if (page > 1) {
            // Para páginas de categoria com paginação
            if (request.data.contains("/search/label/")) {
                "${request.data}?max-results=12&start=${(page - 1) * 12}"
            } else {
                "${request.data}?max-results=12&start=${(page - 1) * 12}"
            }
        } else {
            request.data
        }

        val document = app.get(url).document
        val home = mutableListOf<HomePageList>()

        // Para a página inicial (Top 10 da Semana), processamos os carrosséis com imagens verticais
        if (isHomePage) {
            document.select(".stream-carousel").forEach { carousel ->
                val title = carousel.previousElementSibling()?.selectFirst(".stream-title, h2")?.text() 
                    ?: carousel.attr("id").replace("-carousel", "").capitalize()
                val items = carousel.select(".stream-card").mapNotNull { it.toSearchResult() }
                if (items.isNotEmpty()) {
                    home.add(HomePageList(title, items))
                }
            }
            
            // Também pegamos movie-cards avulsos se houver
            val otherItems = document.select(".movie-card").mapNotNull { it.toSearchResult() }
            if (otherItems.isNotEmpty()) {
                home.add(HomePageList("Destaques", otherItems))
            }
        } else {
            // Para páginas de categoria/label - layout horizontal
            val items = document.select(".ntry").mapNotNull { it.toSearchResult() }
            
            // Criar HomePageList com imagens horizontais
            home.add(HomePageList(
                request.name,
                items,
                isHorizontalImages = true
            ))
        }

        // Verificar se há mais páginas
        val hasNextPage = document.select("#blog-pager .jsLd").isNotEmpty() || 
                          document.select("a.blog-pager-older-link").isNotEmpty() ||
                          items.size >= 12 // Assume que se tem 12 itens, há mais páginas

        return newHomePageResponse(home, hasNextPage)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val article = this
        
        // Extrair título do link dentro de .pTtl
        val titleElement = article.selectFirst(".pTtl a") ?: return null
        val title = titleElement.text() ?: return null
        
        // Extrair URL
        val href = titleElement.attr("href") ?: return null
        
        // Extrair imagem - prioridade para data-src, depois src
        val imgElement = article.selectFirst("img")
        val posterUrl = when {
            imgElement?.hasAttr("data-src") == true -> {
                // Usar data-src se disponível
                val src = imgElement.attr("data-src")
                // Remover parâmetros de redimensionamento para imagem original
                src.replace("-rw-e90", "")
                   .replace("-p-k-no-nu-rw-e90", "-p-k-no-nu")
                   .replace("/w600-h337-p-k-no-nu", "/w240-h240-p-k-no-nu")
            }
            imgElement?.hasAttr("src") == true -> {
                // Usar src como fallback
                val src = imgElement.attr("src")
                src.replace("-rw-e90", "")
                   .replace("-p-k-no-nu-rw-e90", "-p-k-no-nu")
                   .replace("/w600-h337-p-k-no-nu", "/w240-h240-p-k-no-nu")
            }
            else -> null
        }

        // Extrair descrição
        val description = article.selectFirst(".pSnpt")?.text() ?: ""
        
        // Determinar tipo baseado no conteúdo
        val isSerie = description.contains("Série", ignoreCase = true) ||
                      title.contains("Série", ignoreCase = true) ||
                      href.contains("/search/label/S%C3%A9rie") ||
                      article.select(".pLbls a").any { 
                          it.text().contains("Série", ignoreCase = true) 
                      }
        
        val isAnime = description.contains("Anime", ignoreCase = true) ||
                      title.contains("Anime", ignoreCase = true) ||
                      href.contains("/search/label/Anime") ||
                      article.select(".pLbls a").any { 
                          it.text().contains("Anime", ignoreCase = true) 
                      }

        // Criar resposta baseada no tipo
        return when {
            isAnime -> newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) { 
                this.posterUrl = posterUrl
            }
            isSerie -> newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) { 
                this.posterUrl = posterUrl
            }
            else -> newMovieSearchResponse(title, fixUrl(href), TvType.Movie) { 
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(url).document
        return document.select(".ntry").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        // Extrair título
        val title = document.selectFirst("h1.post-title")?.text() 
            ?: document.selectFirst(".pTtl a")?.text() 
            ?: return null
        
        // Extrair imagem do artigo
        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: document.selectFirst(".pThmb img")?.let { img ->
                when {
                    img.hasAttr("data-src") -> img.attr("data-src")
                        .replace("-rw-e90", "")
                        .replace("-p-k-no-nu-rw-e90", "-p-k-no-nu")
                    img.hasAttr("src") -> img.attr("src")
                        .replace("-rw-e90", "")
                        .replace("-p-k-no-nu-rw-e90", "-p-k-no-nu")
                    else -> null
                }
            }
            ?: document.selectFirst("img.imgThm")?.attr("src")?.let { src ->
                src.replace("-rw-e90", "").replace("-p-k-no-nu-rw-e90", "-p-k-no-nu")
            }
        
        // Extrair descrição
        val description = document.selectFirst(".post-body")?.text() 
            ?: document.selectFirst(".pSnpt")?.text()
        
        // Extrair ano da descrição
        val year = description?.let { 
            Regex("""\b(19|20)\d{2}\b""").find(it)?.value?.toIntOrNull()
        }
        
        // Determinar se é série
        val isSerie = url.contains("Série", ignoreCase = true) || 
                      document.select(".post-labels a").any { 
                          it.text().contains("Séries", ignoreCase = true) || 
                          it.text().contains("Série", ignoreCase = true)
                      } ||
                      title.contains("Série", ignoreCase = true) ||
                      description?.contains("Temporada", ignoreCase = true) == true ||
                      description?.contains("Episódio", ignoreCase = true) == true

        return if (isSerie) {
            val episodes = mutableListOf<Episode>()
            
            // Extrair episódios do post-body
            document.select(".post-body a").forEachIndexed { index, element ->
                val epUrl = element.attr("href")
                val epText = element.text().trim()
                
                if (epUrl.isNotBlank() && (epUrl.contains("episodio") || 
                                           epUrl.contains("episode") || 
                                           epUrl.contains("temporada") ||
                                           epText.contains("Episódio") ||
                                           epText.contains("EP"))) {
                    
                    // Extrair número do episódio do texto
                    val episodeNumber = extractEpisodeNumber(epText) ?: (index + 1)
                    
                    episodes.add(newEpisode(fixUrl(epUrl)) {
                        this.name = if (epText.isNotBlank()) epText else "Episódio $episodeNumber"
                        this.episode = episodeNumber
                    })
                }
            }
            
            // Se não encontrou episódios específicos, criar um episódio com o link da página
            if (episodes.isEmpty()) {
                episodes.add(newEpisode(url) { 
                    this.name = "Assistir"
                })
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
            }
        }
    }
    
    private fun extractEpisodeNumber(text: String): Int? {
        val patterns = listOf(
            Regex("""Episódio\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""EP\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""(\d+)\s*ª?\s*Temp""", RegexOption.IGNORE_CASE),
            Regex("""[Tt]emp\s*(\d+)"""),
            Regex("""\b(\d{1,3})\b""")
        )
        
        for (pattern in patterns) {
            pattern.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let {
                return it
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
        val document = app.get(data).document
        var foundLinks = false
        
        // 1. Procura por iframes de vídeo
        document.select("iframe[src*='embed'], iframe[src*='player'], iframe[src*='video']").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                loadExtractor(fixUrl(src), data, subtitleCallback, callback)
                foundLinks = true
            }
        }

        // 2. Procura por botões com data-url
        document.select("[data-url]").forEach { element ->
            val url = element.attr("data-url")
            if (url.contains("http")) {
                loadExtractor(fixUrl(url), data, subtitleCallback, callback)
                foundLinks = true
            }
        }

        // 3. Procura por links diretos em players no post-body
        document.select(".post-body a[href*='player'], .post-body a[href*='embed'], .post-body a[href*='watch']").forEach { link ->
            val href = link.attr("href")
            if (href.isNotBlank() && href.contains("http")) {
                loadExtractor(fixUrl(href), data, subtitleCallback, callback)
                foundLinks = true
            }
        }

        // 4. Extrair URLs de scripts
        document.select("script").forEach { script ->
            val content = script.html()
            // Procura por URLs comuns de vídeo
            val videoUrls = Regex("""(https?://[^"' ]*\.(?:mp4|m3u8|mkv|avi|mov|flv)[^"' ]*)""").findAll(content)
            videoUrls.forEach { match ->
                loadExtractor(fixUrl(match.value), data, subtitleCallback, callback)
                foundLinks = true
            }
            
            // Procura por URLs de embed
            val embedUrls = Regex("""(https?://[^"' ]*\.(?:com|net|org)/[^"' ]*embed[^"' ]*)""").findAll(content)
            embedUrls.forEach { match ->
                loadExtractor(fixUrl(match.value), data, subtitleCallback, callback)
                foundLinks = true
            }
        }

        return foundLinks
    }
}
