package com.AnimeFire

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType

object AnimeFireExtractor {
    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🔗 AnimeFireExtractor: Extraindo de $url")

            // 1. Vamos interceptar QUALQUER link de vídeo primeiro
            val firstUrl = captureFirstVideoUrl(url)
            
            if (firstUrl.isEmpty()) {
                println("❌ AnimeFireExtractor: Nenhum link encontrado")
                return false
            }
            
            println("✅ AnimeFireExtractor: Primeira URL: ${firstUrl.take(80)}...")
            
            // 2. Extrair informações da URL
            val baseInfo = extractBaseInfo(firstUrl)
            if (baseInfo.basePath.isEmpty()) {
                // Se não conseguiu extrair padrão, usa só o que encontrou
                addQualityLink(firstUrl, name, mainUrl, url, callback)
                return true
            }
            
            // 3. Gerar e testar TODAS as qualidades
            val qualities = listOf("fhd", "hd", "sd")
            var foundAny = false
            
            // Primeiro adiciona a que já encontrou
            for (quality in qualities) {
                if (firstUrl.contains("/$quality/")) {
                    addQualityLink(firstUrl, name, mainUrl, url, quality, callback)
                    foundAny = true
                    break
                }
            }
            
            // Agora testa as outras
            for (quality in qualities) {
                if (firstUrl.contains("/$quality/")) continue // Já adicionamos essa
                
                val testUrl = "${baseInfo.basePath}/$quality/${baseInfo.episode}.mp4"
                println("🔍 AnimeFireExtractor: Testando $quality: ${testUrl.take(80)}...")
                
                if (testVideoUrl(testUrl)) {
                    println("✅ AnimeFireExtractor: $quality funciona!")
                    addQualityLink(testUrl, name, mainUrl, url, quality, callback)
                    foundAny = true
                } else {
                    println("❌ AnimeFireExtractor: $quality não disponível")
                }
            }
            
            foundAny
            
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
    
    private data class BaseInfo(val basePath: String, val episode: String)
    
    private fun extractBaseInfo(videoUrl: String): BaseInfo {
        return try {
            // Padrão: https://lightspeedst.net/sXX/mp4/VIDEO_ID/QUALIDADE/EPISODE.mp4
            val pattern = """(https://lightspeedst\.net/s\d+/mp4/[^/]+)/[^/]+/(\d+)\.mp4""".toRegex()
            val match = pattern.find(videoUrl)
            
            if (match != null) {
                BaseInfo(match.groupValues[1], match.groupValues[2])
            } else {
                BaseInfo("", "1")
            }
        } catch (e: Exception) {
            BaseInfo("", "1")
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
        quality: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val (qualityNum, qualityName) = when (quality) {
                "fhd" -> Pair(1080, "1080p")
                "hd" -> Pair(720, "720p")
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
                    this.quality = qualityNum
                    this.headers = mapOf(
                        "Referer" to referer,
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                    )
                }
            )
            
            println("✅ AnimeFireExtractor: Adicionado $qualityName")
        } catch (e: Exception) {
            println("⚠️ AnimeFireExtractor: Erro ao adicionar $quality: ${e.message}")
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
            val quality = when {
                videoUrl.contains("/fhd/") -> Pair(1080, "1080p")
                videoUrl.contains("/hd/") -> Pair(720, "720p")
                else -> Pair(480, "480p")
            }
            
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name (${quality.second})",
                    url = videoUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = quality.first
                    this.headers = mapOf(
                        "Referer" to referer,
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                    )
                }
            )
            
            println("✅ AnimeFireExtractor: Adicionado ${quality.second}")
        } catch (e: Exception) {
            println("⚠️ AnimeFireExtractor: Erro ao adicionar qualidade: ${e.message}")
        }
    }
}
