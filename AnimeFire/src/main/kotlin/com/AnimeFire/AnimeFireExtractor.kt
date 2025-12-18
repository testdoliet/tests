package com.AnimeFire

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import kotlin.math.roundToInt

object AnimeFireExtractor {
    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🔗 AnimeFireExtractor: Extraindo de $url")

            // 1. Extrair slug do anime e número do episódio da URL
            // Formato: https://animefire.io/animes/{slug}/{numero}
            val pathParts = url.removePrefix("https://animefire.io/animes/").split("/")
            if (pathParts.size < 2) {
                println("❌ AnimeFireExtractor: URL inválida")
                return false
            }
            
            val animeSlug = pathParts[0]
            val episodeNum = pathParts[1].toIntOrNull() ?: 1
            
            println("✅ AnimeFireExtractor: Anime: $animeSlug, Episódio: $episodeNum")
            
            // 2. Construir URL da XHR com timestamp atual
            val timestamp = System.currentTimeMillis() / 1000
            val xhrUrl = "https://animefire.io/video/$animeSlug/$episodeNum?tempsubs=0&$timestamp"
            
            println("🌐 AnimeFireExtractor: Requisição XHR para: $xhrUrl")
            
            // 3. Fazer a requisição XHR
            val xhrResponse = app.get(
                xhrUrl,
                headers = mapOf(
                    "Referer" to url,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Accept" to "*/*",
                    "X-Requested-With" to "XMLHttpRequest"
                )
            )
            
            val xhrText = xhrResponse.text
            println("📄 AnimeFireExtractor: Resposta XHR recebida (${xhrText.length} chars)")
            
            // 4. Procurar links de vídeo na resposta
            var foundAny = false
            
            // Padrão 1: Links MP4 diretos
            val mp4Pattern = """https?://[^"\s<>]+\.mp4(?:\?[^"\s<>]*)?""".toRegex()
            val allMp4Links = mp4Pattern.findAll(xhrText).map { it.value }.toList()
            
            println("📊 AnimeFireExtractor: ${allMp4Links.size} links .mp4 encontrados")
            
            // Filtrar apenas links do lightspeedst.net
            val lightspeedLinks = allMp4Links.filter { it.contains("lightspeedst.net") }
            
            if (lightspeedLinks.isNotEmpty()) {
                println("✅ AnimeFireExtractor: ${lightspeedLinks.size} links lightspeedst.net encontrados")
                
                for (link in lightspeedLinks.distinct()) {
                    println("🔗 AnimeFireExtractor: Processando: $link")
                    
                    // Determinar qualidade
                    val (quality, qualityName) = extractQualityInfo(link)
                    
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name ($qualityName)",
                            url = link,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "$mainUrl/"
                            this.quality = quality
                            this.headers = mapOf(
                                "Referer" to url,
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                            )
                        }
                    )
                    foundAny = true
                    println("✅ AnimeFireExtractor: Adicionado $qualityName")
                }
            } else {
                // 5. Tentar analisar como HTML/JavaScript
                println("⚠️ AnimeFireExtractor: Nenhum link direto, analisando estrutura...")
                
                // Remover quebras de linha para facilitar a análise
                val cleanText = xhrText.replace("\n", " ").replace("\r", " ")
                
                // Padrão 2: Objetos JSON com qualidades
                // Exemplo: {"1080p":"url","720p":"url","480p":"url"}
                val jsonPattern = """\{(?:\s*["']?\d+p["']?\s*:\s*["'][^"']+["']\s*,?\s*)+\}""".toRegex()
                val jsonMatches = jsonPattern.findAll(cleanText)
                
                for (jsonMatch in jsonMatches) {
                    val jsonStr = jsonMatch.value
                    println("📋 AnimeFireExtractor: Possível JSON encontrado: $jsonStr")
                    
                    // Extrair pares qualidade:url
                    val qualityUrlPattern = """["']?(\d+)p["']?\s*:\s*["']([^"']+)["']""".toRegex()
                    val qualityMatches = qualityUrlPattern.findAll(jsonStr)
                    
                    for (match in qualityMatches) {
                        val qualityText = match.groupValues[1]
                        val videoUrl = match.groupValues[2]
                        
                        if (videoUrl.contains("lightspeedst.net") && videoUrl.contains(".mp4")) {
                            println("🔗 AnimeFireExtractor: $qualityText -> $videoUrl")
                            
                            val quality = qualityText.toIntOrNull() ?: 480
                            val qualityName = "${qualityText}p"
                            
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = "$name ($qualityName)",
                                    url = videoUrl,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = "$mainUrl/"
                                    this.quality = quality
                                    this.headers = mapOf(
                                        "Referer" to url,
                                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                                    )
                                }
                            )
                            foundAny = true
                        }
                    }
                }
                
                // 6. Padrão 3: Arrays de qualidade
                // Exemplo: ["1080p","url"],["720p","url"]
                val arrayPattern = """\[["'](\d+)p["']\s*,\s*["']([^"']+)["']\]""".toRegex()
                val arrayMatches = arrayPattern.findAll(cleanText)
                
                for (match in arrayMatches) {
                    val qualityText = match.groupValues[1]
                    val videoUrl = match.groupValues[2]
                    
                    if (videoUrl.contains("lightspeedst.net") && videoUrl.contains(".mp4")) {
                        println("🔗 AnimeFireExtractor: Array $qualityText -> $videoUrl")
                        
                        val quality = qualityText.toIntOrNull() ?: 480
                        val qualityName = "${qualityText}p"
                        
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "$name ($qualityName)",
                                url = videoUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "$mainUrl/"
                                this.quality = quality
                                this.headers = mapOf(
                                    "Referer" to url,
                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                                )
                            }
                        )
                        foundAny = true
                    }
                }
                
                // 7. Padrão 4: Variáveis JavaScript
                // Exemplo: var quality_1080 = "url"; var quality_720 = "url";
                val varPattern = """(?:var|let|const)\s+[a-zA-Z_]+(?:_(\d+)p)?\s*=\s*["']([^"']+)["']""".toRegex()
                val varMatches = varPattern.findAll(cleanText)
                
                for (match in varMatches) {
                    val qualityText = match.groupValues[1]
                    val videoUrl = match.groupValues[2]
                    
                    if (qualityText.isNotEmpty() && videoUrl.contains("lightspeedst.net") && videoUrl.contains(".mp4")) {
                        println("🔗 AnimeFireExtractor: Variável $qualityText -> $videoUrl")
                        
                        val quality = qualityText.toIntOrNull() ?: 480
                        val qualityName = "${qualityText}p"
                        
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "$name ($qualityName)",
                                url = videoUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "$mainUrl/"
                                this.quality = quality
                                this.headers = mapOf(
                                    "Referer" to url,
                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                                )
                            }
                        )
                        foundAny = true
                    }
                }
            }
            
            // 8. Se ainda não encontrou nada, logar a resposta para debug
            if (!foundAny) {
                println("❌ AnimeFireExtractor: Nenhum link encontrado na resposta XHR")
                println("📄 AnimeFireExtractor: Primeiros 1000 chars da resposta:")
                println(xhrText.take(1000))
            } else {
                println("🎉 AnimeFireExtractor: Extração concluída com sucesso!")
            }
            
            foundAny
            
        } catch (e: Exception) {
            println("💥 AnimeFireExtractor: Erro - ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    private fun extractQualityInfo(url: String): Pair<Int, String> {
        return when {
            url.contains("/fhd/") || url.contains("1080") -> Pair(1080, "1080p")
            url.contains("/hd/") || url.contains("720") -> Pair(720, "720p")
            url.contains("480") -> Pair(480, "480p")
            url.contains("360") -> Pair(360, "360p")
            url.contains("/sd/") -> Pair(360, "360p")
            else -> Pair(480, "480p") // Default
        }
    }
}
