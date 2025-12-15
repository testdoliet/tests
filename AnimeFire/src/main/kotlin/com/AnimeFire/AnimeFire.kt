package com.AnimeFire

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import java.text.SimpleDateFormat
import java.net.URLEncoder
import kotlinx.coroutines.delay
import com.fasterxml.jackson.databind.ObjectMapper

class AnimeFire : MainAPI() {
    override var mainUrl = "https://animefire.io"
    override var name = "AnimeFire"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)
    override val usesWebView = true

    // ============ CONSTANTES ============
    companion object {
        private const val SEARCH_PATH = "/pesquisar"
    }

    // Página principal com 4 abas
    override val mainPage = mainPageOf(
        "$mainUrl" to "Lançamentos",
        "$mainUrl" to "Destaques da Semana", 
        "$mainUrl" to "Últimos Animes Adicionados",
        "$mainUrl" to "Últimos Episódios Adicionados"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        
        val homeItems = when (request.name) {
            "Lançamentos" -> extractLancamentos(document)
            "Destaques da Semana" -> extractDestaquesSemana(document)
            "Últimos Animes Adicionados" -> extractUltimosAnimes(document)
            "Últimos Episódios Adicionados" -> extractUltimosEpisodios(document)
            else -> emptyList()
        }
        
        return newHomePageResponse(request.name, homeItems.distinctBy { it.url })
    }

    // 1. LANÇAMENTOS
    private fun extractLancamentos(document: org.jsoup.nodes.Document): List<SearchResponse> {
        return document.select(".owl-carousel-home .divArticleLancamentos a.item")
            .mapNotNull { it.toSearchResult() }
            .take(15)
    }

    // 2. DESTAQUES DA SEMANA
    private fun extractDestaquesSemana(document: org.jsoup.nodes.Document): List<SearchResponse> {
        return document.select(".owl-carousel-semana .divArticleLancamentos a.item")
            .mapNotNull { it.toSearchResult() }
            .take(15)
    }

    // 3. ÚLTIMOS ANIMES ADICIONADOS
    private fun extractUltimosAnimes(document: org.jsoup.nodes.Document): List<SearchResponse> {
        return document.select(".owl-carousel-l_dia .divArticleLancamentos a.item")
            .mapNotNull { it.toSearchResult() }
            .take(15)
    }

    // 4. ÚLTIMOS EPISÓDIOS ADICIONADOS
    private fun extractUltimosEpisodios(document: org.jsoup.nodes.Document): List<SearchResponse> {
        return document.select(".divCardUltimosEpsHome")
            .mapNotNull { it.toEpisodeSearchResult() }
            .take(20)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("href")?.takeIf { it.isNotBlank() } ?: return null
        val titleElement = selectFirst("h3.animeTitle") ?: return null
        val title = titleElement.text().trim()
        
        // Buscar imagem CORRETAMENTE
        val imgElement = selectFirst("img:not([src*='logo']):not([src*='lupa']):not([src*='icon']):not([src*='nekog'])")
        val poster = when {
            imgElement?.hasAttr("data-src") == true -> imgElement.attr("data-src")
            imgElement?.hasAttr("src") == true -> imgElement.attr("src")
            else -> null
        }?.takeIf { it.isNotBlank() && !it.contains("nekog") } ?: return null
        
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
        val href = link.attr("href")?.takeIf { it.isNotBlank() } ?: return null
        val titleElement = selectFirst("h3.animeTitle") ?: return null
        val title = titleElement.text().trim()
        
        val epNumberElement = selectFirst(".numEp")
        val epNumber = epNumberElement?.text()?.toIntOrNull() ?: 1
        
        // Buscar imagem específica para episódios
        val imgElement = selectFirst("img:not([src*='logo']):not([src*='lupa']):not([src*='nekog'])")
        val poster = when {
            imgElement?.hasAttr("data-src") == true -> imgElement.attr("data-src")
            imgElement?.hasAttr("src") == true -> imgElement.attr("src")
            else -> null
        }?.takeIf { it.isNotBlank() && !it.contains("nekog") } ?: return null
        
        val cleanTitle = "${title} - Episódio $epNumber"
        
        return newAnimeSearchResponse(cleanTitle, fixUrl(href), TvType.Anime) {
            this.posterUrl = fixUrl(poster)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl$SEARCH_PATH/${URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document

        return document.select("article.containerAnimes a.item, a[href*='/animes/']")
            .mapNotNull { element ->
                try {
                    element.toSearchResult()
                } catch (e: Exception) {
                    null
                }
            }
            .distinctBy { it.url }
            .take(30)
    }

    override suspend fun load(url: String): LoadResponse? {
        println("🔍 [DEBUG] AnimeFire: Iniciando load para URL: $url")

        val document = app.get(url).document

        // Título principal
        val titleElement = document.selectFirst("h1.quicksand400, h1.title, h1") ?: return null
        val title = titleElement.text().trim()
        println("🔍 [DEBUG] AnimeFire: Título encontrado: $title")

        // Extrair ano
        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
        println("🔍 [DEBUG] AnimeFire: Título limpo: $cleanTitle | Ano: $year")

        // Determinar tipo
        val isMovie = url.contains("/filmes/") || title.contains("Movie", ignoreCase = true)
        val isTv = !isMovie && (url.contains("/animes/") || title.contains("Episódio", ignoreCase = true))
        
        // Buscar poster CORRETO no site
        val sitePoster = findSitePoster(document)
        val sitePlot = findSitePlot(document)
        val tags = findSiteTags(document)
        val recommendations = extractRecommendationsFromSite(document)

        println("🏗️ [DEBUG] Título final: $cleanTitle")
        println("🏗️ [DEBUG] Poster final: $sitePoster")
        println("🏗️ [DEBUG] Plot final (primeiros 50 chars): ${sitePlot?.take(50)}...")
        println("🏗️ [DEBUG] Tags: $tags")

        // Extrair episódios CORRETAMENTE
        val episodes = if (isTv) {
            extractEpisodesFromSite(document, url)
        } else {
            emptyList()
        }

        println("🏗️ [DEBUG] Total episódios: ${episodes.size}")

        // Criar resposta
        return if (isTv) {
            newTvSeriesLoadResponse(cleanTitle, url, TvType.Anime, episodes) {
                this.posterUrl = sitePoster
                this.year = year
                this.plot = sitePlot
                this.tags = tags
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
            }
        } else {
            val playerUrl = findPlayerUrl(document) ?: url
            newMovieLoadResponse(cleanTitle, url, TvType.Movie, fixUrl(playerUrl)) {
                this.posterUrl = sitePoster
                this.year = year
                this.plot = sitePlot
                this.tags = tags
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
            }
        }
    }

    // ============ FUNÇÕES AUXILIARES ============

    private fun findSitePoster(document: org.jsoup.nodes.Document): String? {
        // Buscar em várias posições possíveis, EVITANDO IMAGENS GENÉRICAS
        val selectors = listOf(
            "img.transitioning_src[src*='/animes/']",
            ".sub_anime_img img:not([src*='nekog'])",
            ".anime-poster img:not([src*='nekog'])",
            ".cover img:not([src*='nekog'])",
            "img[src*='/animes/'][src*='.jpg'], img[src*='/animes/'][src*='.png'], img[src*='/animes/'][src*='.webp']",
            "img[src*='/covers/'][src*='.jpg'], img[src*='/covers/'][src*='.png']",
            "img:not([src*='logo']):not([src*='lupa']):not([src*='icon']):not([src*='nekog'])[src*='/animes/']"
        )
        
        for (selector in selectors) {
            val img = document.selectFirst(selector)
            if (img != null) {
                val src = when {
                    img.hasAttr("data-src") -> img.attr("data-src")
                    img.hasAttr("src") -> img.attr("src")
                    else -> null
                }
                if (src != null && !src.contains("nekog", ignoreCase = true)) {
                    println("✅ [POSTER] Imagem encontrada: $src")
                    return fixUrl(src)
                }
            }
        }
        
        // Fallback: tentar pegar qualquer imagem que não seja genérica
        document.select("img").forEach { img ->
            val src = img.attr("src")
            if (src.isNotBlank() && !src.contains("logo", ignoreCase = true) && 
                !src.contains("lupa", ignoreCase = true) && 
                !src.contains("icon", ignoreCase = true) &&
                !src.contains("nekog", ignoreCase = true) &&
                (src.contains(".jpg") || src.contains(".png") || src.contains(".webp"))) {
                println("✅ [POSTER] Imagem fallback: $src")
                return fixUrl(src)
            }
        }
        
        println("❌ [POSTER] Nenhuma imagem válida encontrada")
        return null
    }

    private fun findSitePlot(document: org.jsoup.nodes.Document): String? {
        return document.selectFirst(".divSinopse, .sinopse, .description, .plot")?.text()?.trim()
            ?.replace(Regex("^Sinopse:\\s*"), "")
            ?.replace(Regex("^Descrição:\\s*"), "")
    }

    private fun findSiteTags(document: org.jsoup.nodes.Document): List<String> {
        return document.select(".animeInfo a.spanAnimeInfo, .spanGeneros, .tags a, .genre a, .category a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun extractRecommendationsFromSite(document: org.jsoup.nodes.Document): List<SearchResponse> {
        return document.select(".owl-carousel-anime .divArticleLancamentos a.item, .recommendations a")
            .mapNotNull { element ->
                try {
                    element.toSearchResult()
                } catch (e: Exception) {
                    null
                }
            }
            .distinctBy { it.url }
            .take(10)
    }

    private suspend fun extractEpisodesFromSite(
        document: org.jsoup.nodes.Document,
        baseUrl: String
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        // CORREÇÃO: Buscar episódios de forma mais abrangente
        val episodeElements = document.select("""
            .div_video_list a.lEp, 
            a.lep, 
            .episode-item a, 
            a[href*='episodio'],
            a[href*='/animes/'],
            .list-episodes a
        """.trimIndent())
        
        println("🔍 [EPISODES] Elementos encontrados no site: ${episodeElements.size}")
        
        var episodeCount = 0
        
        episodeElements.forEach { element ->
            try {
                val episodeHref = element.attr("href")?.takeIf { it.isNotBlank() } ?: return@forEach
                
                // Extrair número do episódio de várias formas
                val episodeNumber = extractEpisodeNumber(element, ++episodeCount)
                
                val episode = newEpisode(fixUrl(episodeHref)) {
                    this.name = "Episódio $episodeNumber"
                    this.season = 1
                    this.episode = episodeNumber
                    
                    // Tentar extrair título específico do episódio
                    val epTitle = element.selectFirst(".ep-title, .title")?.text()?.trim()
                    if (!epTitle.isNullOrEmpty() && epTitle != "Episódio $episodeNumber") {
                        this.name = epTitle
                    }
                }
                
                episodes.add(episode)
                
            } catch (e: Exception) {
                println("❌ [EPISODES] Erro no episódio ${episodeCount + 1}: ${e.message}")
            }
        }
        
        println("✅ [EPISODES] Total processados: ${episodes.size}")
        
        // Se não encontrou episódios, tentar método alternativo
        if (episodes.isEmpty()) {
            return extractEpisodesAlternative(document, baseUrl)
        }
        
        return episodes.sortedBy { it.episode }.distinctBy { it.episode }
    }

    private fun extractEpisodesAlternative(
        document: org.jsoup.nodes.Document,
        baseUrl: String
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        // Método alternativo: procurar por números de episódio na página
        val text = document.text()
        val episodeRegex = Regex("""Epis[oó]dio\s*(\d+)""", RegexOption.IGNORE_CASE)
        val matches = episodeRegex.findAll(text).toList()
        
        if (matches.isNotEmpty()) {
            val maxEpisode = matches.maxOfOrNull { it.groupValues[1].toIntOrNull() ?: 0 } ?: 0
            
            for (i in 1..maxEpisode) {
                val episodeUrl = if (baseUrl.endsWith("/")) {
                    "${baseUrl}${i}"
                } else {
                    "$baseUrl/${i}"
                }
                
                episodes.add(
                    newEpisode(episodeUrl) {
                        this.name = "Episódio $i"
                        this.season = 1
                        this.episode = i
                    }
                )
            }
        }
        
        return episodes
    }

    private fun extractEpisodeNumber(element: Element, default: Int): Int {
        // Tentar várias formas de extrair o número do episódio
        return element.attr("data-ep").toIntOrNull() ?:
               element.selectFirst(".numEp, .ep-number, .episode-num")?.text()?.toIntOrNull() ?:
               Regex("""Ep\\.?\s*(\d+)""", RegexOption.IGNORE_CASE).find(element.text())?.groupValues?.get(1)?.toIntOrNull() ?:
               Regex("""Epis[oó]dio\s*(\d+)""", RegexOption.IGNORE_CASE).find(element.text())?.groupValues?.get(1)?.toIntOrNull() ?:
               Regex("""(\d+)""").find(element.text())?.groupValues?.get(1)?.toIntOrNull() ?:
               default
    }

    private fun findPlayerUrl(document: org.jsoup.nodes.Document): String? {
        return document.selectFirst("button[data-url], a[data-url]")?.attr("data-url") ?:
               document.selectFirst("iframe[src]")?.attr("src") ?:
               document.selectFirst("a[href*='.mp4'], a[href*='.m3u8'], video source")?.attr("href") ?:
               document.selectFirst("video")?.attr("src")
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
