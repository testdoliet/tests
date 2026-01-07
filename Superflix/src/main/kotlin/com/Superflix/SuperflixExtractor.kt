class SuperflixExtractor : ExtractorApi() {
    override val name = "Superflix"
    override val mainUrl = "https://superflix1.cloud"
    override val requiresReferer = true
    
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val refererHeader = referer ?: mainUrl
        
        try {
            val res = app.get(url, referer = refererHeader)
            
            // Padrões para URLs de vídeo
            val patterns = listOf(
                """(https?://storage\.googleapis\.com/mediastorage/[^"'\s<>]+\.mp4)""",
                """(https?://[^"'\s<>]+\.sssrr\.org/sora/[^"'\s<>]+)""",
                """['"]((?:https?://)?[^"']+\.mp4(?:\?[^"']*)?)['"]""",
                """source\s*['"]?\s*:\s*['"]([^"']+)['"]""",
                """file\s*['"]?\s*:\s*['"]([^"']+)['"]""",
                """url\s*['"]?\s*:\s*['"]([^"']+)['"]"""
            )
            
            patterns.forEach { pattern ->
                Regex(pattern).findAll(res.text).forEach { match ->
                    val videoUrl = match.groupValues[1]
                    if (videoUrl.isNotBlank() && (videoUrl.contains(".mp4") || videoUrl.contains("sssrr.org"))) {
                        val fullUrl = if (videoUrl.startsWith("http")) videoUrl 
                                     else "https:$videoUrl"
                        
                        callback.invoke(newExtractorLink(
                            source = name,
                            name = "Superflix Video",
                            url = fullUrl,
                            referer = refererHeader,
                            quality = Qualities.P720.value,
                            type = ExtractorLinkType.VIDEO
                        ))
                        
                        println("[Superflix] Encontrado: $fullUrl")
                    }
                }
            }
            
        } catch (e: Exception) {
            println("[Superflix] Erro: ${e.message}")
        }
    }
}
