package com.TopAnimes

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.jsoup.Jsoup

object OdaCDNExtractor {
    
    /**
     * Extrai links de vídeo do player OdaCDN
     * Usa /antivirus2/ em vez de /antivirus3/
     */
    suspend fun extractVideoLinks(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔍 ODACDN EXTRACTOR INICIADO")
        println("📄 URL do episódio: $url")
        
        return try {
            // 1. CARREGA PÁGINA DO EPISÓDIO
            println("📥 Baixando página do episódio...")
            val episodeResponse = app.get(url)
            val doc = episodeResponse.document
            println("✅ Página carregada (${episodeResponse.text.length} chars)")
            
            // 2. PROCURA IFRAME DO ODACDN (/antivirus2/)
            println("🔎 Procurando iframe do OdaCDN (/antivirus2/)...")
            
            var odaIframeSrc: String? = null
            
            // Procura todos os iframes
            val allIframes = doc.select("iframe")
            println("📊 Total de iframes na página: ${allIframes.size}")
            
            for ((index, iframe) in allIframes.withIndex()) {
                val src = iframe.attr("src")
                println("
