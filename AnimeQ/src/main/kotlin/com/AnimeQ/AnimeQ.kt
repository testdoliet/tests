package com.AnimeQ

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Element
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AnimeQ : MainAPI() {
    override var mainUrl = "https://animeq.net"
    override var name = "AnimeQ"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override val usesWebView = false

    // ⭐⭐ Vamos testar SEM CloudflareKiller primeiro ⭐⭐
    // private val cloudflareInterceptor = CloudflareKiller()
    private val locker = Mutex()
    private var isInitialized = false
    private var cookies: String? = null

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"
        
        // ⭐⭐ DEBUG: Vamos ver URLs sendo chamadas ⭐⭐
        private fun debugLog(msg: String) {
            println("🔍 [AnimeQ-DEBUG] $msg")
        }
    }

    private suspend fun request(url: String): org.jsoup.nodes.Document {
        debugLog("🔗 Tentando URL: $url")
        
        // ⭐⭐ PULA a inicialização completamente ⭐⭐
        // Não vamos tentar pegar cookies primeiro
        // Vamos direto ao ponto!
        
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$mainUrl/",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
            "Accept-Encoding" to "gzip, deflate, br",
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1",
            "Cache-Control" to "max-age=0"
        )
        
        return try {
            debugLog("📡 Fazendo request com timeout 15s")
            val response = app.get(url, headers = headers, timeout = 15)
            debugLog("✅ Response code: ${response.code}")
            
            // Extrai cookies se existirem
            response.okhttpResponse.headers("Set-Cookie").take(3).forEach {
                debugLog("🍪 Cookie recebido: ${it.substringBefore(";")}")
            }
            
            response.document
        } catch (e: Exception) {
            debugLog("❌ ERRO na request: ${e.javaClass.simpleName} - ${e.message}")
            throw e
        }
    }

    // ⭐⭐ MAIN PAGE SIMPLIFICADA - só testar ⭐⭐
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Teste Home",
        "$mainUrl/episodio/" to "Teste Episódios"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        debugLog("📄 getMainPage: ${request.name} - page $page")
        
        return try {
            val document = request(request.data)
            debugLog("📊 Documento obtido, title: ${document.selectFirst("title")?.text()}")
            
            // Só retorna vazio por enquanto
            newHomePageResponse(emptyList(), hasNext = false)
        } catch (e: Exception) {
            debugLog("💥 FALHA getMainPage: ${e.message}")
            newHomePageResponse(emptyList(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        debugLog("🔎 Search: $query")
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        debugLog("📥 Load: $url")
        throw ErrorLoadingException("Teste")
    }
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return false
    }
}
