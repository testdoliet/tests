package com.reidosembeds

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import org.json.JSONObject

@CloudstreamPlugin
class ReiDosEmbedsProvider : BasePlugin() {
    override fun load() {
        registerMainAPI(ReiDosEmbeds())
    }
}

class ReiDosEmbeds : MainAPI() {
    override var name = "Rei dos Embeds"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = false
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Live)

    private val apiUrl = "https://reidosembeds.com/api"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        println("🚀 Iniciando getMainPage...")
        
        val categories = mutableListOf<HomePageList>()
        
        // 1. Primeiro, pega todas as categorias
        val categoriesResponse = app.get("$apiUrl/channels/categories").text
        val categoriesJson = JSONObject(categoriesResponse)
        val categoriesArray = categoriesJson.getJSONArray("data")
        
        println("📑 Encontradas ${categoriesArray.length()} categorias")
        
        // 2. Para cada categoria, pega os canais
        for (i in 0 until categoriesArray.length()) {
            val category = categoriesArray.getJSONObject(i)
            val categoryName = category.getString("name")
            val categoryId = category.getString("id")
            
            println("🔄 Processando categoria: '$categoryName'")
            
            // Pega canais da categoria - retorna {success: true, data: [...], total: X}
            val channelsResponse = app.get("$apiUrl/channels?category=${categoryId.replace(" ", "%20")}").text
            val channelsJson = JSONObject(channelsResponse)
            val success = channelsJson.getBoolean("success")
            
            if (success) {
                val channelsArray = channelsJson.getJSONArray("data")
                
                println("📺 Encontrados ${channelsArray.length()} canais em '$categoryName'")
                
                val channels = mutableListOf<SearchResponse>()
                
                for (j in 0 until channelsArray.length()) {
                    val channel = channelsArray.getJSONObject(j)
                    val name = channel.getString("name")
                    val embedUrl = channel.getString("embed_url")
                    val logoUrl = channel.getString("logo_url")
                    
                    var posterUrl = logoUrl
                    if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
                    
                    println("  📺 Canal: '$name' -> $embedUrl")
                    
                    channels.add(
                        newLiveSearchResponse(name, embedUrl, TvType.Live) {
                            this.posterUrl = posterUrl
                        }
                    )
                }
                
                if (channels.isNotEmpty()) {
                    categories.add(HomePageList(categoryName, channels, isHorizontalImages = true))
                }
            }
        }
        
        // 3. Também adiciona a categoria "Todos" com todos os canais
        val allChannelsResponse = app.get("$apiUrl/channels").text
        val allChannelsJson = JSONObject(allChannelsResponse)
        val allChannelsArray = allChannelsJson.getJSONArray("data")
        
        val allChannels = mutableListOf<SearchResponse>()
        for (i in 0 until allChannelsArray.length()) {
            val channel = allChannelsArray.getJSONObject(i)
            val name = channel.getString("name")
            val embedUrl = channel.getString("embed_url")
            val logoUrl = channel.getString("logo_url")
            
            var posterUrl = logoUrl
            if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
            
            allChannels.add(
                newLiveSearchResponse(name, embedUrl, TvType.Live) {
                    this.posterUrl = posterUrl
                }
            )
        }
        categories.add(0, HomePageList("📺 Todos", allChannels, isHorizontalImages = true))
        
        if (categories.isEmpty()) {
            println("❌ Nenhuma categoria encontrada!")
            throw ErrorLoadingException("Nenhum canal encontrado")
        }
        
        println("🎉 Total de ${categories.size} categorias carregadas!")
        return newHomePageResponse(categories, hasNext = false)
    }

    override suspend fun load(url: String): LoadResponse {
        println("📖 Carregando canal: $url")
        
        val doc = app.get(url).document
        val title = doc.selectFirst("title")?.text()?.replace("Assistindo ", "") ?: "Canal"
        
        println("📺 Título do canal: $title")
        
        return newMovieLoadResponse(title, url, TvType.Live, url)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        println("🔎 Buscando por: '$query'")
        
        val response = app.get("$apiUrl/pesquisa?q=${query.replace(" ", "%20")}").text
        val json = JSONObject(response)
        val data = json.getJSONObject("data")
        
        val results = mutableListOf<SearchResponse>()
        
        // Busca em canais
        val channelsArray = data.getJSONArray("channels")
        for (i in 0 until channelsArray.length()) {
            val channel = channelsArray.getJSONObject(i)
            val name = channel.getString("name")
            val embedUrl = channel.getString("embed_url")
            
            println("✅ Canal encontrado: '$name'")
            results.add(newLiveSearchResponse(name, embedUrl, TvType.Live))
        }
        
        // Busca em eventos
        val eventsArray = data.getJSONArray("events")
        for (i in 0 until eventsArray.length()) {
            val event = eventsArray.getJSONObject(i)
            val title = event.getString("title")
            val embeds = event.getJSONArray("embeds")
            
            if (embeds.length() > 0) {
                val embedUrl = embeds.getJSONObject(0).getString("embed_url")
                println("✅ Evento encontrado: '$title'")
                results.add(newLiveSearchResponse(title, embedUrl, TvType.Live))
            }
        }
        
        println("🔎 Busca finalizada: ${results.size} resultados")
        return results
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🎬 Carregando links para: $data")
        
        val html = app.get(data).text
        println("📄 HTML obtido, tamanho: ${html.length} caracteres")
        
        val pattern = Regex(""""sources":\s*\[\s*\{\s*"src":\s*"([^"]+\.txt[^"]*?)"""")
        val match = pattern.find(html)
        
        if (match == null) {
            println("❌ Padrão 'sources' não encontrado no HTML")
            return false
        }
        
        var streamUrl = match.groupValues[1].replace("\\/", "/")
        println("✅ Stream URL encontrada: $streamUrl")
        
        M3u8Helper.generateM3u8(
            name,
            streamUrl,
            data,
            headers = mapOf("Referer" to data)
        ).forEach { link ->
            println("  🔗 Link gerado: ${link.url}")
            callback(link)
        }
        
        println("🎉 Links carregados com sucesso!")
        return true
    }
}
