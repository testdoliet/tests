package com.AnimeFire

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.delay

object AnimeFireExtractor {
    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🔗 AnimeFireExtractor: Extraindo de $url")

            // 1. Interceptar a primeira URL
            val firstUrl = captureFirstVideoUrl(url)
            
            if (firstUrl.isEmpty()) {
                println("❌ AnimeFireExtractor: Nenhum link encontrado")
                return false
            }
            
            println("✅ AnimeFireExtractor: Primeira URL: ${firstUrl.take(80)}...")
            
            // 2. Extrair informações da URL usando o PADRÃO CORRETO
            // Padrão: https://lightspeedst.net/s5/mp4_temp/VIDEO_ID/EPISODIO/QUALIDADE.mp4
            val baseInfo = extractBaseInfo(firstUrl)
            
            if (baseInfo.basePath.isEmpty()) {
                // Se não conseguiu extrair padrão, usa só o que encontrou
                addQualityLink(firstUrl, name, mainUrl, url, callback)
                return true
            }
            
            println("🎯 AnimeFireExtractor: Base Path: ${baseInfo.basePath}")
            println("🎯 AnimeFireExtractor: Episódio: ${baseInfo.episode}")
            println("🎯 AnimeFireExtractor: Qualidade encontrada: ${baseInfo.foundQuality}")
            
            // 3. Lista de qualidades para testar
            val qualities = listOf(
                Triple("1080p", 1080),
                Triple("720p", 720),
                Triple("480p", 480),
                Triple("360p", 360)
            )
            
            var successCount = 0
            
            // 4. Adicionar a qualidade que já encontrou
            addQualityLink(firstUrl, name, mainUrl, url, callback)
            successCount++
            
            // 5. AGORA testar as outras qualidades
            for ((qualityName, qualityValue) in qualities) {
                // Pular se for a qualidade que já encontramos
                if (baseInfo.foundQuality == qualityName) continue
                
                val testUrl = "${baseInfo.basePath}/${baseInfo.episode}/$qualityName.mp4"
                println("🔍 AnimeFireExtractor: Testando $qualityName: ${testUrl.take(80)}...")
                
                if (testVideoUrl(testUrl)) {
                    println("✅ AnimeFireExtractor: $qualityName funciona!")
                    addQualityLinkWithParams(testUrl, name, mainUrl, url, qualityName, qualityValue, callback)
                    successCount++
                } else {
                    println("❌ AnimeFireExtractor: $qualityName não disponível")
                }
                
                // Pequena pausa entre requisições
                delay(300)
            }
            
            println("🎉 AnimeFireExtractor: $successCount qualidades adicionadas com sucesso!")
            successCount > 0
            
        } catch (e: Exception) {
            println("💥 AnimeFireExtractor: Erro - ${e.message}")
            false
        }
    }
    
    private suspend fun captureFirstVideoUrl(url: String): String {
        return try {
            val resolver = WebViewResolver(
                interceptUrl = Regex("""lightspeedst\.net.*\.mp4(?:\?|$)"""),
                useOkhttp = false,
                timeout = 15_000L
            )
            
            val response = app.get(url, interceptor = resolver, timeout = 20_000L)
            response.url
        } catch (e: Exception) {
            println("⚠️ AnimeFireExtractor: Erro ao capturar URL: ${e.message}")
            ""
        }
    }
    
    private data class BaseInfo(
        val basePath: String,
        val episode: String,
        val foundQuality: String
    )
    
    private fun extractBaseInfo(videoUrl: String): BaseInfo {
        return try {
            // PADRÃO CORRETO: https://lightspeedst.net/s5/mp4_temp/VIDEO_ID/EPISODIO/QUALIDADE.mp4
            val pattern = """(https://lightspeedst\.net/s\d+/mp4_temp/[^/]+)/(\d+)/([^/]+)\.mp4""".toRegex()
            val match = pattern.find(videoUrl)
            
            if (match != null) {
                BaseInfo(
                    basePath = match.groupValues[1],
                    episode = match.groupValues[2],
                    foundQuality = match.groupValues[3]
                )
            } else {
                // Fallback: tentar outro padrão
                println("⚠️ AnimeFireExtractor: Padrão principal não encontrado, tentando alternativo...")
                
                // Padrão alternativo: https://lightspeedst.net/sXX/mp4/VIDEO_ID/QUALIDADE/EPISODIO.mp4
                val altPattern = """(https://lightspeedst\.net/s\d+/mp4/[^/]+)/([^/]+)/(\d+)\.mp4""".toRegex()
                val altMatch = altPattern.find(videoUrl)
                
                if (altMatch != null) {
                    BaseInfo(
                        basePath = altMatch.groupValues[1],
                        episode = altMatch.groupValues[3],
                        foundQuality = altMatch.groupValues[2]
                    )
                } else {
                    println("❌ AnimeFireExtractor: Nenhum padrão reconhecido")
                    BaseInfo("", "1", "480p")
                }
            }
        } catch (e: Exception) {
            println("⚠️ AnimeFireExtractor: Erro ao extrair padrão: ${e.message}")
            BaseInfo("", "1", "480p")
        }
    }
    
    private suspend fun testVideoUrl(url: String): Boolean {
        return try {
            val response = app.head(url, timeout = 5000L)
            response.code == 200
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun addQualityLink(
        videoUrl: String,
        name: String,
        mainUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Determinar qualidade baseada na URL
            val (qualityValue, qualityName) = when {
                videoUrl.contains("1080") || videoUrl.contains("1080p") -> Pair(1080, "1080p")
                videoUrl.contains("720") || videoUrl.contains("720p") -> Pair(720, "720p")
                videoUrl.contains("480") || videoUrl.contains("480p") -> Pair(480, "480p")
                videoUrl.contains("360") || videoUrl.contains("360p") -> Pair(360, "360p")
                else -> Pair(480, "480p")
            }
            
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name ($qualityName)",
                    url = videoUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = qualityValue
                    this.headers = mapOf(
                        "Referer" to referer,
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                    )
                }
            )
            
            println("✅ AnimeFireExtractor: Adicionado $qualityName")
        } catch (e: Exception) {
            println("⚠️ AnimeFireExtractor: Erro ao adicionar qualidade: ${e.message}")
        }
    }
    
    private suspend fun addQualityLinkWithParams(
        videoUrl: String,
        name: String,
        mainUrl: String,
        referer: String,
        qualityName: String,
        qualityValue: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name ($qualityName)",
                    url = videoUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = qualityValue
                    this.headers = mapOf(
                        "Referer" to referer,
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                    )
                }
            )
            
            println("✅ AnimeFireExtractor: Adicionado $qualityName")
        } catch (e: Exception) {
            println("⚠️ AnimeFireExtractor: Erro ao adicionar $qualityName: ${e.message}")
        }
    }
}
