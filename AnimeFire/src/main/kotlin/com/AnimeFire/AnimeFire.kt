package com.AnimeFire

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import kotlinx.coroutines.delay

class AnimeFire : MainAPI() {
    override var mainUrl = "https://animefire.io"
    override var name = "AnimeFire"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)
    override val usesWebView = true

    companion object {
        private const val SEARCH_PATH = "/pesquisar"
        private const val MAX_TRIES = 3
        private const val RETRY_DELAY = 1000L
        private const val TMDB_PROXY_URL = "https://lawliet.euluan1912.workers.dev"
        private const val tmdbImageUrl = "https://image.tmdb.org/t/p"
        
        // Configuração para séries longas
        private const val MAX_INITIAL_EPISODES = 50 // Máximo de episódios a carregar inicialmente
        private const val LOAD_MORE_SEASONS_BUTTON_ID = 9999 // ID especial para botão "Carregar mais"
    }

    override val mainPage = mainPageOf(
        "$mainUrl" to "Lançamentos",
        "$mainUrl" to "Destaques da Semana",
        "$mainUrl" to "Últimos Animes Adicionados",
        "$mainUrl" to "Últimos Episódios Adicionados"
    )

    private fun Element.toSearchResponse(): AnimeSearchResponse? {
        val href = attr("href") ?: return null
        if (href.isBlank()) return null
        
        val titleElement = selectFirst("h3.animeTitle") ?: return null
        val title = titleElement.text().trim()
        
        val imgElement = selectFirst("img.imgAnimes, img.owl-lazy, img[src*='animes']")
        val poster = when {
            imgElement?.hasAttr("data-src") == true -> imgElement.attr("data-src")
            imgElement?.hasAttr("src") == true -> imgElement.attr("src")
            else -> selectFirst("img:not([src*='logo']):not([src*='Logo'])")?.attr("src")
        } ?: return null
        
        if (poster.contains("logo", ignoreCase = true)) return null
        
        val cleanTitle = title.replace(Regex("(?i)(dublado|legendado|todos os episódios|\\(\\d{4}\\))$"), "").trim()
        val isMovie = href.contains("/filmes/") || title.contains("Movie", ignoreCase = true)
        
        return newAnimeSearchResponse(cleanTitle, fixUrl(href)) {
            this.posterUrl = fixUrl(poster)
            this.type = if (isMovie) TvType.Movie else TvType.Anime
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        
        val homeItems = when (request.name) {
            "Lançamentos" -> 
                document.select(".owl-carousel-home .divArticleLancamentos a.item
