package com.AnimeFire

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import org.json.JSONObject
import org.jsoup.Jsoup

object AnimeFireExtractor {
    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🔗 AnimeFireExtractor: Extraindo de $url")

            // 1. Extrair slug do anime e número do episódio
            val pathParts = url.removePrefix("https://animefire.io/animes/").split("/")
            if (pathParts.size < 2) {
                println("❌ AnimeFireExtractor: URL inválida")
                return false
            }
            
            val animeSlug = pathParts[0]
            val episodeNum = pathParts[1].toIntOrNull() ?: 1
            
            println("✅ AnimeFireExtractor: Anime: $animeSlug, Episódio: $episodeNum")
            
            // 2. Construir URL da XHR (com ou sem timestamp, ambos funcionam)
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
            println("📄 AnimeFireExtractor: Resposta recebida (${xhrText.length} chars)")
            
            // 4. Mostrar preview da resposta para debug
            println("📄 Resposta JSON: ${xhrText.take(500)}...")
            
            // 5. Tentar parsear como JSON
            try {
                val json = JSONObject(xhrText)
                val dataArray = json.getJSONArray("data")
                var foundAny = false
                
                println("✅ AnimeFireExtractor: JSON parseado com sucesso, ${dataArray.length()} itens encontrados")
                
                for (i in 0 until dataArray.length()) {
                    val item = dataArray.getJSONObject(i)
                    val videoUrl = item.getString("src")
                    val qualityLabel = item.getString("label")
                    
                    println("🔗 AnimeFireExtractor: Item $i - URL: $videoUrl, Label: $qualityLabel")
                    
                    // Determinar qualidade numérica
                    val quality = when {
                        qualityLabel.contains("1080") -> 1080
                        qualityLabel.contains("720") -> 720
                        qualityLabel.contains("480") -> 480
                        qualityLabel.contains("360") -> 360
                        else -> {
                            // Tentar extrair do URL
                            when {
                                videoUrl.contains("1080") -> 1080
                                videoUrl.contains("720") -> 720
                                videoUrl.contains("480") -> 480
                                videoUrl.contains("360") -> 360
                                else -> 480
                            }
                        }
                    }
                    
                    val qualityName = when (quality) {
                        1080 -> "1080p"
                        720 -> "720p"
                        480 -> "480p"
                        360 -> "360p"
                        else -> qualityLabel
                    }
                    
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
                    println("✅ AnimeFireExtractor: Qualidade $qualityName adicionada")
                }
                
                if (foundAny) {
                    println("🎉 AnimeFireExtractor: Extração JSON concluída com sucesso!")
                    return true
                }
                
            } catch (e: Exception) {
                println("⚠️ AnimeFireExtractor: Falha ao parsear JSON: ${e.message}")
                // Continuar com métodos alternativos
            }
            
            // 6. Fallback: buscar links MP4 diretamente no texto
            println("⚠️ AnimeFireExtractor: Tentando fallback com regex...")
            
            val mp4Pattern = """https?://[^"\s<>']+\.mp4(?:\?[^"\s<>']*)?""".toRegex(RegexOption.IGNORE_CASE)
            val allLinks = mp4Pattern.findAll(xhrText).map { it.value }.toList().distinct()
            
            println("📊 AnimeFireExtractor: ${allLinks.size} links .mp4 encontrados via regex")
            
            var foundAny = false
            for (link in allLinks.filter { it.contains("lightspeedst.net") }) {
                println("🔗 AnimeFireExtractor: Link via regex: $link")
                
                val quality = when {
                    link.contains("1080") -> 1080
                    link.contains("720") -> 720
                    link.contains("480") -> 480
                    link.contains("360") -> 360
                    else -> 480
                }
                
                val qualityName = when (quality) {
                    1080 -> "1080p"
                    720 -> "720p"
                    480 -> "480p"
                    360 -> "360p"
                    else -> "SD"
                }
                
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
            }
            
            if (foundAny) {
                println("🎉 AnimeFireExtractor: Extração via regex concluída!")
                return true
            }
            
            println("❌ AnimeFireExtractor: Nenhum link encontrado")
            false
            
        } catch (e: Exception) {
            println("💥 AnimeFireExtractor: Erro - ${e.message}")
            e.printStackTrace()
            false
        }
    }
}
