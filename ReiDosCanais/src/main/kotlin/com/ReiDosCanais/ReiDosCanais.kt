package com.reidoscanais

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import org.jsoup.nodes.Element
import java.util.Base64

@CloudstreamPlugin
class ReiDosCanaisProvider : BasePlugin() {
    override fun load() {
        registerMainAPI(ReiDosCanais())
    }
}

class ReiDosCanais : MainAPI() {
    override var name = "Rei dos Canais"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = false
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Live)
    
    private val baseUrl = "https://reidoscanais.io"
    private val playerDomain = "p2player.live"
    
    // Constante mágica para decodificação (encontrada no HTML)
    private val magicNumber = 10659686

    // ================== PÁGINA PRINCIPAL ==================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(baseUrl).document
        
        // Extrair todas as seções de categorias (div.mb-8)
        val sections = doc.select("div.mb-8")
        val homePageList = mutableListOf<HomePageList>()
        
        for (section in sections) {
            // Extrair título da categoria
            val titleElement = section.selectFirst("h2.text-2xl")
            val categoryName = titleElement?.text()?.trim() ?: continue
            
            // Extrair cards de canais da categoria
            val cards = section.select("div.flex-shrink-0")
            val channels = cards.mapNotNull { card ->
                parseChannelCard(card)
            }
            
            if (channels.isNotEmpty()) {
                homePageList.add(HomePageList(categoryName, channels, isHorizontalImages = true))
            }
        }
        
        return newHomePageResponse(homePageList)
    }
    
    private fun parseChannelCard(card: Element): LiveSearchResponse? {
        // Título do canal
        val titleElement = card.selectFirst("h4.font-semibold")
        val title = titleElement?.text()?.trim() ?: return null
        
        // URL da imagem do canal
        val imgElement = card.selectFirst("img")
        val imgUrl = imgElement?.attr("src") ?: return null
        
        // Criar um ID único para o canal baseado no título
        val channelId = title.lowercase()
            .replace(Regex("[^a-z0-9]"), "")
            .replace(Regex("\\s+"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
        
        return newLiveSearchResponse(title, "$channelId|$title|$imgUrl", TvType.Live) {
            this.posterUrl = fixUrl(imgUrl)
        }
    }

    // ================== CARREGAR STREAM ==================
    override suspend fun load(data: String): LoadResponse {
        val parts = data.split("|", limit = 3)
        if (parts.size < 2) throw ErrorLoadingException("Formato de dados inválido")
        
        val channelId = parts[0]
        val channelName = parts[1]
        val posterUrl = if (parts.size >= 3) parts[2] else null
        
        // Construir URL do canal
        val channelUrl = "$baseUrl/$channelId"
        
        return newMovieLoadResponse(channelName, channelUrl, TvType.Live, channelUrl) {
            this.posterUrl = posterUrl
        }
    }

    // ================== BUSCA ==================
    override suspend fun search(query: String): List<SearchResponse>? {
        val doc = app.get(baseUrl).document
        
        // Buscar em todas as seções
        val allCards = doc.select("div.flex-shrink-0")
        val results = allCards.mapNotNull { card ->
            val titleElement = card.selectFirst("h4.font-semibold")
            val title = titleElement?.text()?.trim() ?: return@mapNotNull null
            
            if (title.contains(query, ignoreCase = true)) {
                parseChannelCard(card)
            } else null
        }
        
        return results
    }

    // ================== EXTRAIR LINK M3U8 ==================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val channelUrl = data.ifEmpty { return false }
        
        // 1. Acessar página do canal
        val doc = app.get(channelUrl).document
        
        // 2. Extrair URL do iframe do player
        val iframeSrc = doc.select("iframe")
            .map { it.attr("src") }
            .firstOrNull { it.contains(playerDomain) }
        
        if (iframeSrc.isNullOrEmpty()) {
            return false
        }
        
        val absoluteIframeUrl = if (iframeSrc.startsWith("//")) {
            "https:$iframeSrc"
        } else if (!iframeSrc.startsWith("http")) {
            "https://$playerDomain$iframeSrc"
        } else {
            iframeSrc
        }
        
        // 3. Acessar página do iframe
        val iframeDoc = app.get(absoluteIframeUrl).document
        val iframeHtml = iframeDoc.html()
        
        // 4. Extrair array hNO do JavaScript
        val hnoArray = extractHNOArray(iframeHtml) ?: return false
        
        // 5. Decodificar array para obter HTML real
        val decodedHtml = decodeHNOArray(hnoArray)
        if (decodedHtml.isEmpty()) {
            return false
        }
        
        // 6. Procurar link .m3u8 no HTML decodificado
        val finalUrl = extractM3u8Url(decodedHtml) ?: return false
        
        // 7. Retornar link para o player - SEGUINDO O PADRÃO DO GOYABU
        val extractorLink = newExtractorLink(
            source = "ReiDosCanais",
            name = "Rei dos Canais",
            url = finalUrl,
            type = ExtractorLinkType.M3U8
        ) {
            this.referer = absoluteIframeUrl
            this.quality = Qualities.Unknown.value
            this.headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to absoluteIframeUrl
            )
        }
        
        callback.invoke(extractorLink)
        return true
    }
    
    // ================== FUNÇÕES AUXILIARES ==================
    
    /**
     * Extrai o array hNO do JavaScript ofuscado
     */
    private fun extractHNOArray(html: String): List<String>? {
        // Procurar pelo padrão: var hNO = [ ... ];
        val regex = Regex("""var\s+hNO\s*=\s*(\[.*?\]);""", RegexOption.DOT_MATCHES_ALL)
        val match = regex.find(html) ?: return null
        
        val arrayContent = match.groupValues[1]
        
        // Extrair cada string entre aspas
        val itemRegex = Regex("""["']([^"']+)["']""")
        return itemRegex.findAll(arrayContent)
            .map { it.groupValues[1] }
            .toList()
    }
    
    /**
     * Decodifica o array hNO usando a lógica do site
     */
    private fun decodeHNOArray(items: List<String>): String {
        return buildString {
            for (encoded in items) {
                try {
                    // 1. Decodificar Base64 usando java.util.Base64
                    val base64Decoded = String(Base64.getDecoder().decode(encoded))
                    
                    // 2. Remover tudo que não é dígito
                    val numbersOnly = base64Decoded.replace(Regex("\\D"), "")
                    
                    // 3. Converter para número e subtrair o magic number
                    val charCode = numbersOnly.toIntOrNull()?.minus(magicNumber)
                    
                    // 4. Converter para caractere e anexar
                    if (charCode != null) {
                        append(charCode.toChar())
                    }
                } catch (e: Exception) {
                    // Ignorar erros
                }
            }
        }
    }
    
    /**
     * Encontra URL .m3u8 no HTML decodificado
     */
    private fun extractM3u8Url(html: String): String? {
        // Procurar em iframe src
        val iframePattern = Regex("""<iframe.*?src=["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
        iframePattern.find(html)?.let { return it.groupValues[1] }
        
        // Procurar em video src
        val videoPattern = Regex("""<video.*?src=["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
        videoPattern.find(html)?.let { return it.groupValues[1] }
        
        // Procurar qualquer URL .m3u8
        val urlPattern = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
        return urlPattern.find(html)?.value
    }
    
    // ================== METADADOS DO PLUGIN ==================
    override val mainPage = mainPageOf(
        Pair(baseUrl, "Todos os Canais")
    )
    
    // ================== MÉTODO DE FIX ==================
    private fun fixUrl(url: String): String {
        return if (url.startsWith("//")) "https:$url" 
               else if (url.startsWith("http")) url 
               else if (url.startsWith("/")) "$baseUrl$url"
               else "$baseUrl/$url"
    }
}
