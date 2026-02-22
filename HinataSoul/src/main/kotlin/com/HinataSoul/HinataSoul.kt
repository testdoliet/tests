package com.hinatasoul

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONArray
import org.json.JSONObject

@CloudstreamPlugin
class HinataSoulPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(HinataSoul())
    }
}

class HinataSoul : MainAPI() {
    override var mainUrl = "hinatasoul.com"
    override var name = "HinataSoul"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime, 
        TvType.AnimeMovie,
        TvType.TvSeries  // Para doramas
    )
    override val usesWebView = false

    companion object {
        // Constantes de seletores
        private const val HOME_ITEM = ".ultimosAnimesHomeItem, .ultimosEpisodiosHomeItem"
        private const val HOME_LINK = "a"
        private const val HOME_TITLE = ".ultimosAnimesHomeItemInfosNome, .ultimosEpisodiosHomeItemInfosNome"
        private const val HOME_IMG = ".ultimosAnimesHomeItemImg img, .ultimosEpisodiosHomeItemImg img"
        private const val HOME_EP_NUM = ".ultimosEpisodiosHomeItemInfosNum"
        private const val HOME_EP_TOTAL = ".ultimosAnimesHomeItemQntEps"
        private const val HOME_TIPO = ".ultimosAnimesHomeItemTipo"

        // Seletores de detalhes
        private const val DETAIL_TITLE = "h1"
        private const val DETAIL_POSTER = ".aniInfosSingleCapa img"
        private const val DETAIL_SYNOPSIS = ".aniInfosSingleSinopse p"
        private const val DETAIL_GENRES = ".aniInfosSingleGeneros span"
        private const val DETAIL_STATUS = "#completed"
        private const val DETAIL_EPISODES_INFO = ".aniInfosSingleNumsItem"
        private const val EPISODE_CONTAINER = ".aniContainer"
        private const val EPISODE_ITEM = ".ultimosEpisodiosHomeItem"
        private const val EPISODE_LINK = "a"
        private const val EPISODE_TITLE_ATTR = "title"

        // URLs das requisições
        private const val OUTBRAIN_URL = "https://widgets.outbrain.com/outbrain.js"
        private const val ADS_API_URL = "https://ads.animeyabu.net/"
    }

    // Página principal com todas as abas
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Últimos Episódios",
        "$mainUrl/animes/letra/todos" to "Animes (AZ)",
        "$mainUrl/anime-dublado" to "Animes Dublados",
        "$mainUrl/tokusatsus" to "Tokusatsus",
        "$mainUrl/doramas" to "Doramas",
        "$mainUrl/donghua" to "Donghuas",
    )

    // === FUNÇÕES AUXILIARES ===
    private fun isDub(title: String, url: String? = null): Boolean {
        return title.contains("Dublado", ignoreCase = true) || 
               url?.contains("dublado", ignoreCase = true) == true ||
               url?.contains("anime-dublado", ignoreCase = true) == true
    }

    private fun cleanTitle(title: String): String {
        return title.replace("(?i)\\s*ep\\s*\\d+".toRegex(), "")
            .replace("(?i)\\s*-\\s*final".toRegex(), "")
            .replace("- Dublado", "", ignoreCase = true)
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    private fun extractEpisodeNumber(title: String): Int? {
        // Tenta extrair de "Episódio 1" ou "Episódio 01"
        val fromEpisodio = "ep(?:is[oó]dio)?\\s*(\\d+)".toRegex(RegexOption.IGNORE_CASE)
            .find(title)?.groupValues?.get(1)?.toIntOrNull()
        if (fromEpisodio != null) return fromEpisodio

        // Tenta extrair de "18 Again - 01" (formato comum em doramas)
        val fromDash = "-\\s*(\\d+)".toRegex().find(title)?.groupValues?.get(1)?.toIntOrNull()
        if (fromDash != null) return fromDash

        // Tenta extrair qualquer número no final do título
        val numberAtEnd = "\\b(\\d+)\\s*$".toRegex().find(title)?.groupValues?.get(1)?.toIntOrNull()

        return numberAtEnd
    }

    private fun extractTotalEpisodes(text: String): Pair<Int?, Int?> {
        val regex = "(\\d+)/(\\d+)".toRegex()
        return regex.find(text)?.let {
            val current = it.groupValues[1].toIntOrNull()
            val total = it.groupValues[2].toIntOrNull()
            current to total
        } ?: (null to null)
    }

    // === FUNÇÃO PRINCIPAL PARA ENCONTRAR LINKS DE VÍDEO ===
    private fun findAllVideoUrls(document: Document): List<Pair<String, String>> {
        val videoUrls = mutableListOf<Pair<String, String>>()

        println("🔍 DEBUG - Varrendo HTML em busca de links do Cloudflare...")

        // 1. Procurar em scripts com var vid
        document.select("script").forEachIndexed { index, script ->
            val content = script.data()

            // Regex para encontrar var vid = 'URL' (Cloudflare R2)
            val regex = "var vid = '(https?://[a-zA-Z0-9]+\\.r2\\.cloudflarestorage\\.com/[^']+\\.mp4)'".toRegex()
            val matches = regex.findAll(content)

            matches.forEach { match ->
                val url = match.groupValues[1]

                // Determinar qualidade pelo ID do container pai ou padrão na URL
                val quality = when {
                    script.parent()?.id() == "jwContainer_2" -> "FULLHD"
                    script.parent()?.id() == "jwContainer_1" -> "HD"
                    script.parent()?.id() == "jwContainer_0" -> "SD"
                    url.contains("/fful/") -> "FULLHD"
                    url.contains("/f222/") -> "HD"
                    url.contains("/fiphoneb/") -> "SD"
                    url.contains("/fiphonec/") -> "SD"
                    url.contains("/f333/") -> "HD"
                    else -> "Unknown"
                }

                println("✅ DEBUG - Link encontrado no script $index: $quality - ${url.take(100)}...")
                videoUrls.add(Pair(url, quality))
            }
        }

        // 2. Procurar em qualquer lugar do HTML por URLs do Cloudflare (fallback)
        val html = document.html()
        val cloudflareRegex = "https?://[a-zA-Z0-9]+\\.r2\\.cloudflarestorage\\.com/[a-zA-Z0-9]+/[0-9]+\\.mp4".toRegex()
        cloudflareRegex.findAll(html).forEach { match ->
            val url = match.value
            // Evitar duplicatas
            if (!videoUrls.any { it.first == url }) {
                val quality = when {
                    url.contains("/fful/") -> "FULLHD"
                    url.contains("/f222/") -> "HD"
                    url.contains("/fiphoneb/") -> "SD"
                    url.contains("/fiphonec/") -> "SD"
                    url.contains("/f333/") -> "HD"
                    else -> "Unknown"
                }
                println("✅ DEBUG - Link encontrado no HTML: $quality - ${url.take(100)}...")
                videoUrls.add(Pair(url, quality))
            }
        }

        return videoUrls.distinctBy { it.first }
    }

    // === FUNÇÕES DE MAPEAMENTO ===
    private fun Element.toSearchResponse(): AnimeSearchResponse? {
        val link = selectFirst(HOME_LINK) ?: return null
        val href = fixUrl(link.attr("href"))
        val title = selectFirst(HOME_TITLE)?.text()?.trim() ?: return null
        val poster = selectFirst(HOME_IMG)?.attr("src")?.let { fixUrl(it) }
        val episodeNum = selectFirst(HOME_EP_NUM)
        val tipo = selectFirst(HOME_TIPO)?.text()?.trim()
        val isDub = isDub(title, href)

        // Detectar se é dorama pela URL
        val isDorama = href.contains("/doramas/")

        // Passa o poster junto na URL para usar em load()
        val urlWithPoster = if (poster != null) "$href|poster=$poster" else href

        return if (episodeNum != null) {
            // É um episódio
            val epNumber = extractEpisodeNumber(title) ?: 1
            val animeName = cleanTitle(title)

            newAnimeSearchResponse(animeName, urlWithPoster, if (isDorama) TvType.TvSeries else TvType.Anime) {
                this.posterUrl = poster
                if (isDub) {
                    addDubStatus(DubStatus.Dubbed, epNumber)
                } else {
                    addDubStatus(DubStatus.Subbed, epNumber)
                }
            }
        } else {
            // É um anime/dorama
            newAnimeSearchResponse(cleanTitle(title), urlWithPoster, if (isDorama) TvType.TvSeries else TvType.Anime) {
                this.posterUrl = poster
                if (isDub) {
                    addDubStatus(DubStatus.Dubbed, null)
                } else {
                    addDubStatus(DubStatus.Subbed, null)
                }
            }
        }
    }

    // === PÁGINA PRINCIPAL ===
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Últimos Episódios - seção especial da home (sem paginação)
        if (request.name == "Últimos Episódios") {
            val document = app.get("$mainUrl/", referer = mainUrl).document
            // Pega apenas a seção de "Últimos episódios em lançamento"
            val episodeElements = document.select(".epiContainer .ultimosEpisodiosHomeItem")
            val items = episodeElements
                .mapNotNull { it.toSearchResponse() }
                .distinctBy { it.url }

            // Retorna como lista horizontal (isHorizontalImages = true)
            return newHomePageResponse(
                list = HomePageList(request.name, items, isHorizontalImages = true),
                hasNext = false
            )
        }

        // Para todas as outras abas, a página é TOTALMENTE ALEATÓRIA!
        // O parâmetro 'page' é completamente ignorado - sempre geramos uma página aleatória

        // Define os ranges máximos para cada aba baseado nos dados fornecidos
        val maxPage = when {
            request.data.contains("/animes/letra/todos") -> 218  // Animes A-Z
            request.data.contains("/anime-dublado") -> 51        // Animes Dublados
            request.data.contains("/tokusatsus") -> 12           // Tokusatsus
            request.data.contains("/doramas") -> 36              // Doramas
            request.data.contains("/donghua") -> 10              // Donghuas
            else -> 50 // Fallback
        }

        // Gera um número aleatório entre 1 e o maxPage da aba
        val randomPage = (1..maxPage).random()

        println("🔍 DEBUG - Carregando página ALEATÓRIA $randomPage de ${request.name} (max: $maxPage)")

        // Constrói a URL com a página aleatória
        val baseUrl = request.data.removeSuffix("/")
        val url = if (baseUrl.contains("/letra/todos")) {
            // Para URLs que contêm "/letra/todos" (caso dos animes A-Z)
            baseUrl.replace("/letra/todos", "") + "/page/$randomPage/letra/todos"
        } else {
            // Para todas as outras abas: /doramas, /anime-dublado, /tokusatsus, /donghua
            "$baseUrl/page/$randomPage"
        }

        println("🔍 DEBUG - URL aleatória: $url")

        val document = try {
            app.get(url, referer = mainUrl).document
        } catch (e: Exception) {
            println("❌ DEBUG - Erro ao acessar $url: ${e.message}")
            // Se a página não existir, tenta a página 1 como fallback
            val fallbackUrl = if (baseUrl.contains("/letra/todos")) {
                baseUrl.replace("/letra/todos", "") + "/page/1/letra/todos"
            } else {
                "$baseUrl/page/1"
            }
            println("🔄 DEBUG - Tentando fallback: $fallbackUrl")
            app.get(fallbackUrl, referer = mainUrl).document
        }

        val items = document.select(HOME_ITEM)
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }

        // SEMPRE diz que tem próxima página, porque sempre podemos gerar outra aleatória!
        // Isso faz com que o usuário possa ficar rolando infinitamente
        val hasNext = true

        return newHomePageResponse(request.name, items, hasNext)
    }

    // === PESQUISA ===
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()
        val searchUrl = "$mainUrl/busca?busca=${query.replace(" ", "+")}"
        val document = app.get(searchUrl).document
        return document.select(HOME_ITEM).mapNotNull { it.toSearchResponse() }
    }

    // === CARREGAR DETALHES ===
    override suspend fun load(url: String): LoadResponse {
        val (actualUrl, thumbPoster) = url.split("|poster=").let { 
            it[0] to it.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
        }

        val document = app.get(actualUrl).document
        val title = document.selectFirst(DETAIL_TITLE)?.text()?.trim() ?: "Sem título"
        val isDub = isDub(title, actualUrl)

        // Detectar se é dorama pela URL
        val isDorama = actualUrl.contains("/doramas/")

        // Poster
        val poster = thumbPoster ?: document.selectFirst(DETAIL_POSTER)?.attr("src")?.let { fixUrl(it) }

        // Sinopse
        val synopsis = document.selectFirst(DETAIL_SYNOPSIS)?.text()?.trim() ?: "Sinopse não disponível"

        // Gêneros
        val genres = document.select(DETAIL_GENRES).map { it.text() }.filter { it.isNotBlank() }

        // Ano e status
        var year: Int? = null
        var totalEpisodes: Int? = null
        var tvType = if (isDorama) TvType.TvSeries else TvType.Anime

        document.select(DETAIL_EPISODES_INFO).forEach { element ->
            val text = element.text()
            when {
                text.contains("Ano") -> year = text.substringAfter("Ano").trim().toIntOrNull()
                text.contains("Episódios") -> {
                    val (current, total) = extractTotalEpisodes(text)
                    totalEpisodes = total
                }
                // Verifica se é filme pelo texto "Tipo" no elemento (só para animes)
                !isDorama && text.contains("Tipo") && text.contains("Filme", ignoreCase = true) -> {
                    tvType = TvType.AnimeMovie
                }
            }
        }

        // Status (Completo ou Emissão)
        val showStatus = if (document.selectFirst(DETAIL_STATUS)?.text()?.contains("Completo") == true) {
            ShowStatus.Completed
        } else {
            ShowStatus.Ongoing
        }

        // Lista de episódios - CORRIGIDO para pegar TODOS os episódios
        val episodes = mutableListOf<Episode>()

        document.select(EPISODE_CONTAINER).select(EPISODE_ITEM).forEachIndexed { index, element ->
            val link = element.selectFirst(EPISODE_LINK)
            if (link == null) {
                println("⚠️ Episódio sem link encontrado, pulando...")
                return@forEachIndexed
            }

            val episodeUrl = fixUrl(link.attr("href"))
            val episodeTitle = link.attr(EPISODE_TITLE_ATTR).takeIf { it.isNotBlank() } 
                ?: element.selectFirst(HOME_TITLE)?.text()?.trim()

            if (episodeTitle.isNullOrBlank()) {
                println("⚠️ Episódio sem título, pulando...")
                return@forEachIndexed
            }

            // Tenta extrair número do episódio, se não conseguir, usa o índice + 1
            val episodeNumber = extractEpisodeNumber(episodeTitle) ?: (index + 1)
            val episodePoster = element.selectFirst("img")?.attr("src")?.let { fixUrl(it) }

            episodes.add(
                newEpisode(episodeUrl) {
                    name = if (tvType == TvType.AnimeMovie) "Filme" 
                           else "Episódio $episodeNumber"
                    episode = episodeNumber
                    posterUrl = episodePoster ?: poster
                }
            )
        }

        // Ordenar episódios por número
        episodes.sortBy { it.episode }

        println("✅ Total de episódios encontrados: ${episodes.size}")

        return newAnimeLoadResponse(cleanTitle(title), actualUrl, tvType) {
            this.posterUrl = poster
            this.year = year
            this.plot = synopsis
            this.tags = genres
            this.showStatus = showStatus
            // Sem referências a malId ou anilistId - apenas dados do site
            if (isDub) {
                addEpisodes(DubStatus.Dubbed, episodes)
            } else {
                addEpisodes(DubStatus.Subbed, episodes)
            }
        }
    }

    // === FUNÇÃO PARA EXTRAIR TOKEN DO JSON ===
    private fun extractTokenFromJson(jsonString: String): String? {
        return try {
            val jsonArray = JSONArray(jsonString)
            val jsonObject = jsonArray.getJSONObject(0)
            jsonObject.optString("publicidade")
        } catch (e: Exception) {
            println("❌ DEBUG - Erro ao fazer parse do JSON: ${e.message}")
            null
        }
    }

    // === CARREGAR LINKS DE VÍDEO (COM AS 3 REQUISIÇÕES) ===
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodePageUrl = data.split("|poster=")[0]
        println("🔍 DEBUG LOADLINKS - URL recebida: $episodePageUrl")

        // 1. Pega a página do episódio para obter os links base
        val document = try {
            app.get(episodePageUrl, referer = mainUrl).document
        } catch (e: Exception) {
            println("❌ DEBUG - Erro ao acessar página: ${e.message}")
            return false
        }

        val baseUrls = findAllVideoUrls(document)
        println("🔍 DEBUG - Links base encontrados: ${baseUrls.size}")

        if (baseUrls.isEmpty()) {
            println("❌ DEBUG - Nenhum link base encontrado!")
            return false
        }

        // Prioriza os links por qualidade
        val priority = mapOf("FULLHD" to 4, "HD" to 3, "SD" to 2, "Unknown" to 1)
        val sortedUrls = baseUrls.sortedByDescending { priority[it.second] ?: 0 }

        var linksFound = false

        // Para cada link base, faz as 3 requisições
        sortedUrls.forEach { (baseUrl, quality) ->
            try {
                println("🔍 DEBUG - Processando link: $quality")

                // PASSO 1: GET outbrain.js
                println("🔍 DEBUG - Buscando outbrain.js")
                val outbrainJs = app.get(
                    OUTBRAIN_URL,
                    headers = mapOf(
                        "Referer" to mainUrl,
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    )
                ).text

                // PASSO 2: POST para ads.animeyabu.net
                println("🔍 DEBUG - Enviando POST para ads.animeyabu.net")
                val firstResponse = app.post(
                    url = ADS_API_URL,
                    data = mapOf(
                        "category" to "client",
                        "type" to "premium",
                        "ad" to outbrainJs
                    ),
                    headers = mapOf(
                        "Content-Type" to "application/x-www-form-urlencoded",
                        "Referer" to mainUrl,
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    )
                ).text

                // Parse da primeira resposta
                val token = extractTokenFromJson(firstResponse)

                if (token.isNullOrBlank()) {
                    println("❌ DEBUG - Token não encontrado na primeira resposta")
                    return@forEach
                }

                println("🔍 DEBUG - Token obtido: ${token.take(50)}...")

                // PASSO 3: GET com token + URL base
                val secondUrl = "$ADS_API_URL?token=$token&url=$baseUrl"
                println("🔍 DEBUG - Buscando parâmetros finais")

                val secondResponse = app.get(
                    secondUrl,
                    headers = mapOf(
                        "Referer" to mainUrl,
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    )
                ).text

                // Parse da segunda resposta
                val authParams = extractTokenFromJson(secondResponse)

                if (authParams.isNullOrBlank()) {
                    println("❌ DEBUG - Parâmetros não encontrados na segunda resposta")
                    return@forEach
                }

                println("🔍 DEBUG - Parâmetros obtidos: ${authParams.take(50)}...")

                // Link final = baseUrl + authParams (concatenação simples!)
                val finalUrl = baseUrl + authParams
                println("✅ DEBUG - Link final gerado: ${finalUrl.take(100)}...")

                val qualityValue = when (quality) {
                    "FULLHD" -> 1080
                    "HD" -> 720
                    "SD" -> 480
                    else -> 720
                }

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "Cloudflare $quality",
                        url = finalUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = qualityValue
                        referer = mainUrl
                        headers = mapOf(
                            "Referer" to mainUrl,
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                        )
                    }
                )

                linksFound = true

            } catch (e: Exception) {
                println("❌ DEBUG - Erro ao processar link: ${e.message}")
                e.printStackTrace()
            }
        }

        println("✅✅✅ DEBUG - Links encontrados: $linksFound")
        return linksFound
    }
}
