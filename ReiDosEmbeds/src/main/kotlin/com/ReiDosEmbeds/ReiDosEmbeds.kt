package com.reidosembeds

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import org.json.JSONObject
import org.json.JSONArray

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
    
    // Cache para guardar os dados dos canais (slug -> (nome, poster))
    private var channelsCache: MutableMap<String, Pair<String, String>> = mutableMapOf()
    
    // Categorias bloqueadas (não aparecem na página inicial nem na busca)
    private val blockedCategories = listOf("Adulto", "adulto", "ADULTO")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        println("🚀 Iniciando getMainPage...")
        
        val categories = mutableListOf<HomePageList>()
        
        // 1. Pega todas as categorias
        val categoriesResponse = app.get("$apiUrl/channels/categories").text
        val categoriesJson = JSONObject(categoriesResponse)
        val categoriesArray = categoriesJson.getJSONArray("data")
        
        println("📑 Encontradas ${categoriesArray.length()} categorias")
        
        // 2. Para cada categoria, pega os canais (exceto bloqueadas)
        for (i in 0 until categoriesArray.length()) {
            val category = categoriesArray.getJSONObject(i)
            val categoryName = category.getString("name")
            val categoryId = category.getString("id")
            
            // Pula categorias bloqueadas
            if (blockedCategories.contains(categoryName)) {
                println("🚫 Pulando categoria bloqueada: '$categoryName'")
                continue
            }
            
            println("🔄 Processando categoria: '$categoryName'")
            
            val channelsResponse = app.get("$apiUrl/channels?category=${categoryId.replace(" ", "%20")}").text
            val channelsJson = JSONObject(channelsResponse)
            val channelsArray = channelsJson.getJSONArray("data")
            
            println("📺 Encontrados ${channelsArray.length()} canais em '$categoryName'")
            
            val channels = mutableListOf<SearchResponse>()
            
            for (j in 0 until channelsArray.length()) {
                val channel = channelsArray.getJSONObject(j)
                val name = channel.getString("name")
                val slug = channel.getString("id")
                val embedUrl = channel.getString("embed_url")
                val logoUrl = channel.getString("logo_url")
                
                var posterUrl = logoUrl
                if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
                
                // Guarda no cache
                channelsCache[slug] = Pair(name, posterUrl)
                
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
        
        // 3. Categoria "Todos" (filtra canais de categorias bloqueadas)
        val allChannelsResponse = app.get("$apiUrl/channels").text
        val allChannelsArray = JSONArray(allChannelsResponse)
        
        val allChannels = mutableListOf<SearchResponse>()
        for (i in 0 until allChannelsArray.length()) {
            val channel = allChannelsArray.getJSONObject(i)
            val name = channel.getString("name")
            val slug = channel.getString("id")
            val embedUrl = channel.getString("embed_url")
            val logoUrl = channel.getString("logo_url")
            val category = channel.optString("category", "")
            
            // Pula canais de categorias bloqueadas
            if (blockedCategories.contains(category)) {
                println("🚫 Pulando canal de categoria bloqueada: '$name' ($category)")
                continue
            }
            
            var posterUrl = logoUrl
            if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
            
            // Guarda no cache se não existir
            if (!channelsCache.containsKey(slug)) {
                channelsCache[slug] = Pair(name, posterUrl)
            }
            
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
        
        // Extrai o slug da URL
        val slug = url.substringAfterLast("/")
        
        // Tenta pegar do cache
        val channelData = channelsCache[slug]
        val title = channelData?.first ?: slug.replace("-", " ").split(" ").joinToString(" ") { word ->
            if (word.isNotEmpty()) word.replaceFirstChar { it.uppercase() } else word
        }
        val posterUrl = channelData?.second ?: ""
        
        // Sinopse personalizada
        val plot = "Assista $title ao vivo!"
        
        println("📺 Título do canal: $title")
        println("📝 Sinopse: $plot")
        println("🖼️ Poster: $posterUrl")
        
        return newMovieLoadResponse(title, url, TvType.Live, url) {
            this.posterUrl = posterUrl
            this.plot = plot
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        println("🔎 Buscando por: '$query'")
        
        val response = app.get("$apiUrl/pesquisa?q=${query.replace(" ", "%20")}").text
        val json = JSONObject(response)
        val data = json.getJSONObject("data")
        
        val results = mutableListOf<SearchResponse>()
        
        // Busca em canais (filtra categorias bloqueadas)
        val channelsArray = data.getJSONArray("channels")
        for (i in 0 until channelsArray.length()) {
            val channel = channelsArray.getJSONObject(i)
            val name = channel.getString("name")
            val embedUrl = channel.getString("embed_url")
            val logoUrl = channel.optString("logo_url", "")
            val category = channel.optString("category", "")
            
            // Pula canais de categorias bloqueadas
            if (blockedCategories.contains(category)) {
                println("🚫 Pulando canal bloqueado na busca: '$name' ($category)")
                continue
            }
            
            var posterUrl = logoUrl
            if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
            
            println("✅ Canal encontrado: '$name'")
            results.add(
                newLiveSearchResponse(name, embedUrl, TvType.Live) {
                    this.posterUrl = posterUrl
                }
            )
        }
        
        // Busca em eventos
        val eventsArray = data.getJSONArray("events")
        for (i in 0 until eventsArray.length()) {
            val event = eventsArray.getJSONObject(i)
            val title = event.getString("title")
            val poster = event.optString("poster", "")
            val embeds = event.getJSONArray("embeds")
            
            var posterUrl = poster
            if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
            
            if (embeds.length() > 0) {
                val embedUrl = embeds.getJSONObject(0).getString("embed_url")
                println("✅ Evento encontrado: '$title'")
                results.add(
                    newLiveSearchResponse(title, embedUrl, TvType.Live) {
                        this.posterUrl = posterUrl
                    }
                )
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
        
        val channelHtml = app.get(data).text
        val iframePattern = Regex("""<iframe[^>]*src="([^"]*__play[^"]*)"[^>]*>""")
        val iframeMatch = iframePattern.find(channelHtml) ?: return false
        
        val playerUrl = iframeMatch.groupValues[1].replace("&amp;", "&")
        println("✅ Player URL encontrada: $playerUrl")
        
        val playerHtml = app.get(playerUrl, headers = mapOf("Referer" to data)).text
        println("📄 HTML do player obtido, tamanho: ${playerHtml.length} caracteres")
        
        val sourcesPattern = Regex("""var sources\s*=\s*(\[.*?\]);""", RegexOption.DOT_MATCHES_ALL)
        val sourcesMatch = sourcesPattern.find(playerHtml) ?: return false
        
        val sourcesArray = JSONArray(sourcesMatch.groupValues[1])
        
        for (i in 0 until sourcesArray.length()) {
            val source = sourcesArray.getJSONObject(i)
            val streamUrl = source.getString("src").replace("\\/", "/")
            val label = source.optString("label", "Source ${i + 1}")
            
            println("  📡 Baixando M3U8 para detectar qualidades: $streamUrl")
            
            // Headers para as requisições
            val headers = mapOf(
                "Referer" to playerUrl,
                "Origin" to "https://rde.buzz",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
            )
            
            // Baixa o M3U8 para analisar as qualidades
            try {
                val m3u8Content = app.get(streamUrl, headers = headers).text
                
                // Extrai as qualidades do M3U8
                val qualities = extractQualitiesFromM3u8(m3u8Content, streamUrl, playerUrl)
                
                if (qualities.isNotEmpty()) {
                    println("  📊 Qualidades detectadas: ${qualities.map { it.second }}")
                    for ((qualityUrl, height) in qualities) {
                        val qualityName = "${height}p"
                        val linkName = "$name - $qualityName"
                        println("    🔗 Gerando link: $linkName")
                        
                        M3u8Helper.generateM3u8(
                            linkName,
                            qualityUrl,
                            playerUrl,
                            headers = headers
                        ).forEach { link ->
                            callback(link)
                        }
                    }
                } else {
                    // Fallback: usa o link original sem qualidade detectada
                    println("  ⚠️ Nenhuma qualidade detectada, usando link original")
                    M3u8Helper.generateM3u8(
                        "$name - $label",
                        streamUrl,
                        playerUrl,
                        headers = headers
                    ).forEach { link ->
                        callback(link)
                    }
                }
            } catch (e: Exception) {
                println("  ❌ Erro ao processar M3U8: ${e.message}")
                // Fallback em caso de erro
                M3u8Helper.generateM3u8(
                    "$name - $label",
                    streamUrl,
                    playerUrl,
                    headers = headers
                ).forEach { link ->
                    callback(link)
                }
            }
        }
        
        println("🎉 Links carregados com sucesso!")
        return true
    }
    
    // Função para extrair qualidades do M3U8
    private fun extractQualitiesFromM3u8(content: String, baseUrl: String, referer: String): List<Pair<String, Int>> {
        val qualities = mutableListOf<Pair<String, Int>>()
        val lines = content.lines()
        
        // Procura por RESOLUTION no M3U8
        val resolutionPattern = Regex("""RESOLUTION=(\d+)x(\d+)""")
        
        for (i in lines.indices) {
            val line = lines[i]
            val match = resolutionPattern.find(line)
            
            if (match != null) {
                val height = match.groupValues[2].toIntOrNull() ?: continue
                
                // Pega a URL do stream (próxima linha)
                var streamUrl = lines.getOrNull(i + 1)?.trim() ?: continue
                
                // Se a URL for relativa, completa com o baseUrl
                if (!streamUrl.startsWith("http")) {
                    val base = baseUrl.substringBeforeLast("/")
                    streamUrl = if (streamUrl.startsWith("/")) {
                        val baseDomain = baseUrl.substringBefore("//").plus("//").plus(baseUrl.substringAfter("//").substringBefore("/"))
                        baseDomain + streamUrl
                    } else {
                        "$base/$streamUrl"
                    }
                }
                
                qualities.add(Pair(streamUrl, height))
            }
        }
        
        return qualities
    }
}
