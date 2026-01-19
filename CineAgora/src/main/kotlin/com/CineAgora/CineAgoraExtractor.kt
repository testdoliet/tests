import org.jsoup.nodes.Element

object CineAgoraExtractor {
    private const val TAG = "CineAgoraExtractor"
private const val BASE_PLAYER = "https://watch.brplayer.cc"
private const val REFERER_CINEAGORA = "https://cineagora.net/"

    /**
     * Função principal chamada pelo provider.
     * Detecta se a URL é do player ou do site e age de acordo.
     */
suspend fun extractVideoLinks(
url: String,
name: String,
callback: (ExtractorLink) -> Unit
): Boolean {
        println("[$TAG] =========================================")
        println("[$TAG] Extraindo links para: $url")
        println("[$TAG] Nome: $name")
        
        // Se já for URL do player (watch.brplayer.cc), extrair diretamente
        if (url.contains("watch.brplayer.cc")) {
            println("[$TAG] ✓ É URL do player, extraindo diretamente...")
            return extractHlsFromWatchPage(url, name, callback)
        }
        // Se for URL do CineAgora (cineagora.net), extrair primeiro o player URL
        else if (url.contains("cineagora.net")) {
            println("[$TAG] ✓ É URL do CineAgora, extraindo player URL primeiro...")
            return extractFromCineAgoraPage(url, name, callback)
        }
        // URL não reconhecida
        else {
            println("[$TAG] ⚠️ URL não reconhecida, tentando extrair como player...")
            return extractHlsFromWatchPage(url, name, callback)
}
}

    // =============================
    // FUNÇÃO PARA EXTRAIR DO SITE CINEAGORA
    // =============================

    /**
     * Extrai a URL do player da página do CineAgora
     */
private suspend fun extractFromCineAgoraPage(
cineAgoraUrl: String,
name: String,
callback: (ExtractorLink) -> Unit
): Boolean {
try {
            println("[$TAG] 📄 Extraindo player URL de: $cineAgoraUrl")
            
val html = app.get(cineAgoraUrl, referer = REFERER_CINEAGORA).text
            println("[$TAG] 📄 HTML obtido (${html.length} chars)")
            
            // Padrões para encontrar o iframe do player
val iframePatterns = listOf(
"""<iframe[^>]*src=["'](https://watch\.brplayer\.cc/watch\?v=[^"']+)["']""",
"""<iframe[^>]*src=["'](https://watch\.brplayer\.cc/watch/[^"']+)["']""",
"""src=["'](https://watch\.brplayer\.cc/watch[^"']+)["'][^>]*allowfullscreen""",
"""<iframe[^>]*allowfullscreen[^>]*src=["'](https://watch\.brplayer\.cc/[^"']+)["']""",
"""data-src=["'][^"']*["'][^>]*src=["'](https://watch\.brplayer\.cc/[^"']+)["']"""
)
            
            for ((index, pattern) in iframePatterns.withIndex()) {
                println("[$TAG] 🔍 Tentando padrão $index...")
val match = Regex(pattern).find(html)
if (match != null) {
var playerUrl = match.groupValues[1]
                    println("[$TAG] ✅ Player URL encontrada: $playerUrl")
                    
                    // Garantir que a URL está completa
if (!playerUrl.startsWith("http")) {
playerUrl = BASE_PLAYER + (if (playerUrl.startsWith("/")) "" else "/") + playerUrl
                        println("[$TAG] 🔗 URL corrigida: $playerUrl")
}
                    
                    // Agora extrair do player
return extractHlsFromWatchPage(playerUrl, name, callback)
}
}
            
            // Fallback: Procurar qualquer menção a watch.brplayer.cc
            println("[$TAG] 🔍 Fallback: procurando qualquer watch.brplayer.cc no HTML...")
val fallbackPattern = """https://watch\.brplayer\.cc/[^"'\s<>]+"""
val allMatches = Regex(fallbackPattern).findAll(html).toList()
            
            if (allMatches.isNotEmpty()) {
                println("[$TAG] 📍 Encontradas ${allMatches.size} URLs do player")
                
                for (match in allMatches) {
                    val playerUrl = match.value
                    println("[$TAG] 🔍 Analisando: $playerUrl")
                    
                    // Verificar se é uma URL de watch
                    if (playerUrl.contains("/watch")) {
                        println("[$TAG] ✅ URL de watch válida encontrada: $playerUrl")
                        return extractHlsFromWatchPage(playerUrl, name, callback)
                    }
}
}
            
            println("[$TAG] ❌ Não encontrou player URL na página")
            println("[$TAG] 🔎 Vou fazer um dump de parte do HTML para debug:")
            
            // Encontrar a área com iframes
            val iframeSection = html.substring(
                html.indexOf("<iframe"),
                html.indexOf("</iframe>").takeIf { it > 0 } ?: html.length.coerceAtMost(html.indexOf("<iframe") + 1000)
            )
            
            if (iframeSection.length > 100) {
                println("[$TAG] --- ÁREA DO IFRAME ---")
                println(iframeSection)
                println("[$TAG] --- FIM ---")
            } else {
                val sample = html.take(3000)
                println("[$TAG] --- INÍCIO HTML (3000 chars) ---")
                println(sample)
                println("[$TAG] --- FIM HTML ---")
            }
            
return false
            
} catch (e: Exception) {
            println("[$TAG] ❌ Erro ao extrair player URL: ${e.message}")
            e.printStackTrace()
return false
}
}

    // =============================
    // FUNÇÃO PRINCIPAL DE EXTRAÇÃO DO PLAYER
    // =============================

    /**
     * Extrai HLS diretamente da página /watch/XXXX
     */
private suspend fun extractHlsFromWatchPage(
watchUrl: String,
name: String,
callback: (ExtractorLink) -> Unit
): Boolean {
try {
            println("[$TAG] 🎬 Acessando página do player: $watchUrl")

val html = app.get(watchUrl, referer = REFERER_CINEAGORA).text
            println("[$TAG] 📄 Página carregada (${html.length} chars)")
            
            // MÉTODO 1: Extrair do objeto video no JavaScript
val uid = extractFromRegex(html, "\"uid\"\\s*:\\s*\"(\\d+)\"")
val md5 = extractFromRegex(html, "\"md5\"\\s*:\\s*\"([a-f0-9]{32})\"")
val videoId = extractFromRegex(html, "\"id\"\\s*:\\s*\"(\\d+)\"")
val status = extractFromRegex(html, "\"status\"\\s*:\\s*\"([01])\"") ?: "1"
            
            println("[$TAG] 📊 Parâmetros extraídos:")
            println("[$TAG]   UID: '$uid'")
            println("[$TAG]   MD5: '$md5'")
            println("[$TAG]   ID: '$videoId'")
            println("[$TAG]   Status: '$status'")
            
if (uid != null && md5 != null && videoId != null) {
                // URL principal do master.txt
val masterUrl = "$BASE_PLAYER/m3u8/$uid/$md5/master.txt?s=1&id=$videoId&cache=$status"
                println("[$TAG] 🔗 Master URL: $masterUrl")
                
                // URL alternativa do master.m3u8
val altUrl = "$BASE_PLAYER/alternative_stream/$uid/$md5/master.m3u8"
                println("[$TAG] 🔗 Alternative URL: $altUrl")
                
val headers = mapOf(
"Referer" to watchUrl,
"Origin" to BASE_PLAYER,
"User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
)
                
                // Tentar gerar links com qualidades usando M3u8Helper
                println("[$TAG] 🔄 Gerando links M3U8...")
val links = try {
M3u8Helper.generateM3u8(
source = "CineAgora",
@@ -192,65 +104,48 @@ object CineAgoraExtractor {
headers = headers
)
} catch (e: Exception) {
                    println("[$TAG] ⚠️ M3u8Helper falhou: ${e.message}")
emptyList()
}

if (links.isNotEmpty()) {
                    println("[$TAG] ✅ ${links.size} links M3U8 gerados!")
                    links.forEach { link ->
                        println("[$TAG]   📺 Link: ${link.url} (${link.quality})")
                        callback(link)
                    }
                    return true
                } else {
                    println("[$TAG] ⚠️ M3u8Helper não gerou links, usando fallback...")
                    
                    // Fallback 1: link único principal
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
                    println("[$TAG] ✅ Link fallback criado: $masterUrl")
                    
                    // Fallback 2: link alternativo como backup
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
                    println("[$TAG] ✅ Link alternativo adicionado: $altUrl")
                    
return true
}
}

            // MÉTODO 2: Tentar extrair a URL diretamente do source do player
            println("[$TAG] 🔍 Método 1 falhou, tentando extrair URL diretamente...")
val masterUrlDirect = extractMasterUrlDirect(html)
if (masterUrlDirect != null) {
                println("[$TAG] ✅ URL master encontrada diretamente: $masterUrlDirect")

val headers = mapOf(
"Referer" to watchUrl,
"Origin" to BASE_PLAYER
)
                
val directLink = newExtractorLink(
source = "CineAgora",
name = name,
@@ -261,19 +156,14 @@ object CineAgoraExtractor {
this.quality = Qualities.Unknown.value
this.headers = headers
}
                
callback(directLink)
return true
}

            // MÉTODO 3: Tentar encontrar URLs m3u8 no HTML
            println("[$TAG] 🔍 Método 2 falhou, procurando URLs m3u8 no HTML...")
val m3u8Urls = extractAllM3u8Urls(html)
if (m3u8Urls.isNotEmpty()) {
                println("[$TAG] ✅ Encontradas ${m3u8Urls.size} URLs m3u8")
                
m3u8Urls.forEach { m3u8Url ->
                    println("[$TAG]   🔗 m3u8: $m3u8Url")
val m3u8Link = newExtractorLink(
source = "CineAgora",
name = name,
@@ -289,91 +179,76 @@ object CineAgoraExtractor {
return true
}

            println("[$TAG] ❌ Falha ao extrair links HLS de: $watchUrl")
return false

} catch (e: Exception) {
            println("[$TAG] ❌ Erro ao extrair HLS: ${e.message}")
            e.printStackTrace()
return false
}
}

    // =============================
    // FUNÇÕES AUXILIARES
    // =============================

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
"""["']file["']\s*:\s*["']([^"']+\.txt)["']""",
"""src\s*:\s*['"]([^'"]+\.m3u8[^'"]*)['"]"""
)
        
for (pattern in patterns) {
val regex = Regex(pattern, RegexOption.DOT_MATCHES_ALL)
val match = regex.find(html)
if (match != null) {
var url = match.groupValues.getOrNull(1) ?: match.value
                println("[$TAG] 🔍 Encontrou padrão: $pattern")
                println("[$TAG] 📍 Match: $url")
                
                // Garantir que seja uma URL completa
if (url.startsWith("/")) {
url = BASE_PLAYER + url
} else if (!url.startsWith("http")) {
url = "$BASE_PLAYER/$url"
}
                
                println("[$TAG] ✅ URL final: $url")
return url
}
}
        
return null
}
    
private fun extractAllM3u8Urls(html: String): List<String> {
val urls = mutableListOf<String>()
        
        // Procurar por URLs que contenham .m3u8
val patterns = listOf(
"""["'](https?://[^"']+\.m3u8[^"']*)["']""",
"""["'](/[^"']+\.m3u8[^"']*)["']""",
"""(https?://[^\s<>"']+\.m3u8)""",
"""(/\S+\.m3u8\S*)"""
)
        
for (pattern in patterns) {
val regex = Regex(pattern)
val matches = regex.findAll(html)
            
matches.forEach { match ->
var url = match.value
if (url.startsWith("\"") || url.startsWith("'")) {
url = url.substring(1, url.length - 1)
}
                
                // Garantir que seja uma URL completa
if (url.startsWith("/") && !url.startsWith("//")) {
url = BASE_PLAYER + url
}
                
if (url.startsWith("http") && !urls.contains(url)) {
urls.add(url)
}
}
}
        
return urls
}
}
