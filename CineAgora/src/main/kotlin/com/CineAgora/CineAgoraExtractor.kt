package com.CineAgora

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element

object CineAgoraExtractor {
private const val TAG = "CineAgoraExtractor"
private const val BASE_PLAYER = "https://watch.brplayer.cc"
private const val REFERER_CINEAGORA = "https://cineagora.net/"

/**
    * Função principal chamada pelo provider.
     * Extrai os links HLS diretamente da página do episódio.
    */
suspend fun extractVideoLinks(
        url: String,        // URL do episódio (watch.brplayer.cc/watch/XXXX)
        name: String,      // Nome do episódio
callback: (ExtractorLink) -> Unit
): Boolean {
        println("[$TAG] Extraindo links para: $url")
        println("[$TAG] Nome: $name")

        return extractHlsFromWatchPage(url, name, callback)
}

    // =============================
    // FUNÇÃO ÚNICA DE EXTRAÇÃO
    // =============================

    /**
     * Extrai HLS diretamente da página /watch/XXXX
     * OBS: A página contém todas as informações no JavaScript
     */
    private suspend fun extractHlsFromWatchPage(
        watchUrl: String,
        name: String,
callback: (ExtractorLink) -> Unit
): Boolean {
try {
            println("[$TAG] Acessando página: $watchUrl")

            val html = app.get(watchUrl, referer = REFERER_CINEAGORA).text
            println("[$TAG] Página carregada (${html.length} chars)")
            
            // MÉTODO 1: Extrair do objeto video no JavaScript
            val uid = extractFromRegex(html, "\"uid\"\\s*:\\s*\"(\\d+)\"")
            val md5 = extractFromRegex(html, "\"md5\"\\s*:\\s*\"([a-f0-9]{32})\"")
            val videoId = extractFromRegex(html, "\"id\"\\s*:\\s*\"(\\d+)\"")
            val status = extractFromRegex(html, "\"status\"\\s*:\\s*\"([01])\"") ?: "1"
            
            println("[$TAG] Extraído - UID: '$uid', MD5: '$md5', ID: '$videoId', Status: '$status'")
            
if (uid != null && md5 != null && videoId != null) {
                // URL principal do master.txt
val masterUrl = "$BASE_PLAYER/m3u8/$uid/$md5/master.txt?s=1&id=$videoId&cache=$status"
                println("[$TAG] Master URL: $masterUrl")
                
                // URL alternativa do master.m3u8
val altUrl = "$BASE_PLAYER/alternative_stream/$uid/$md5/master.m3u8"
                println("[$TAG] Alternative URL: $altUrl")
                
                val headers = mapOf(
                    "Referer" to watchUrl,
                    "Origin" to BASE_PLAYER,
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
                )
                
                // Tentar gerar links com qualidades
                println("[$TAG] Gerando links M3U8...")
val links = try {
M3u8Helper.generateM3u8(
source = "CineAgora",
streamUrl = masterUrl,
                        referer = watchUrl,
headers = headers
)
} catch (e: Exception) {
                    println("[$TAG] M3u8Helper falhou: ${e.message}")
emptyList()
}

if (links.isNotEmpty()) {
                    println("[$TAG] ${links.size} links M3U8 gerados!")
                    links.forEach { link ->
                        println("[$TAG] Link: ${link.url} (${link.quality})")
                        callback(link)
                    }
                    return true
                } else {
                    // Fallback: link único principal
                    val fallbackLink = newExtractorLink(
                        source = "CineAgora",
                        name = name,
                        url = masterUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = watchUrl
                        this.quality = Qualities.Unknown.value
                        this.headers = headers
                    }
                    
                    callback(fallbackLink)
                    println("[$TAG] Link fallback criado: $masterUrl")
                    
                    // Adicionar também o link alternativo como backup
                    val altLink = newExtractorLink(
                        source = "CineAgora (Alt)",
                        name = "$name (Alternativo)",
                        url = altUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = watchUrl
                        this.quality = Qualities.Unknown.value
                        this.headers = headers
}
                    
                    callback(altLink)
                    println("[$TAG] Link alternativo adicionado: $altUrl")
                    
return true
}
}

            // MÉTODO 2: Tentar extrair a URL diretamente do source do player
            println("[$TAG] Método 1 falhou, tentando extrair URL diretamente...")
            val masterUrlDirect = extractMasterUrlDirect(html)
            if (masterUrlDirect != null) {
                println("[$TAG] URL master encontrada diretamente: $masterUrlDirect")

                val headers = mapOf(
                    "Referer" to watchUrl,
                    "Origin" to BASE_PLAYER
                )
                
                val directLink = newExtractorLink(
source = "CineAgora",
name = name,
                    url = masterUrlDirect,
                    type = ExtractorLinkType.M3U8
) {
                    this.referer = watchUrl
this.quality = Qualities.Unknown.value
this.headers = headers
}
                
                callback(directLink)
return true
}

            println("[$TAG] Falha ao extrair links HLS")
return false

} catch (e: Exception) {
            println("[$TAG] Erro ao extrair HLS: ${e.message}")
e.printStackTrace()
return false
}
}

    private fun extractFromRegex(text: String, pattern: String): String? {
        val regex = Regex(pattern)
        val match = regex.find(text)
        return match?.groupValues?.get(1)
}
    
    private fun extractMasterUrlDirect(html: String): String? {
        // Procurar pelo source do player no JavaScript
        val patterns = listOf(
            """file\s*:\s*['"](/m3u8/\d+/[a-f0-9]+/master\.txt[^'"]*)['"]""",
            """["']sources["']\s*:\s*\[.*?file["']\s*:\s*["']([^"']+master\.txt[^"']*)["']""",
            """master\.txt[?&]s=1&id=\d+""",
            """["']file["']\s*:\s*["']([^"']+\.txt)["']"""
        )
        
        for (pattern in patterns) {
            val regex = Regex(pattern, RegexOption.DOT_MATCHES_ALL)
            val match = regex.find(html)
            if (match != null) {
                var url = match.groupValues.getOrNull(1) ?: match.value
                // Garantir que seja uma URL completa
                if (url.startsWith("/")) {
                    url = BASE_PLAYER + url
}
                return url
}
}
        
        return null
}
}
