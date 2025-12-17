package com.AnimeFire

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities

object AnimeFireExtractor {

    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🔗 AnimeFireExtractor: Extraindo de $url")

            val streamResolver = WebViewResolver(
                interceptUrl = Regex("""\.(m3u8|mp4|mkv|avi|mov|wmv|flv|webm)"""),
                useOkhttp = false,
                timeout = 15_000L
            )

            val response = app.get(url, interceptor = streamResolver)
            val intercepted = response.url

            println("🌐 AnimeFireExtractor: URL interceptada: $intercepted")

            if (intercepted.isNotEmpty()) {
                when {
                    intercepted.contains(".m3u8") -> {
                        // Para M3U8, gerar múltiplas qualidades
                        val headers = mapOf(
                            "Referer" to url,
                            "Origin" to mainUrl,
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                        )

                        println("✅ AnimeFireExtractor: Gerando links M3U8")
                        M3u8Helper.generateM3u8(
                            name,
                            intercepted,
                            mainUrl,
                            headers = headers
                        ).forEach { link ->
                            // Converter ExtractorLink antigo para novo
                            callback(
                                newExtractorLink(
                                    sourceName = name,
                                    name = name,
                                    url = link.url,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = mainUrl
                                    this.quality = link.quality
                                }
                            )
                        }
                        
                        true
                    }
                    
                    intercepted.contains(".mp4") || intercepted.contains(".mkv") || 
                    intercepted.contains(".avi") || intercepted.contains(".mov") -> {
                        // Para links diretos de vídeo
                        println("✅ AnimeFireExtractor: Link direto encontrado: $intercepted")
                        
                        // Determinar qualidade
                        val quality = when {
                            intercepted.contains("1080") || intercepted.contains("fullhd") -> Qualities.FullHd.value
                            intercepted.contains("720") || intercepted.contains("hd") -> Qualities.HD.value
                            intercepted.contains("480") || intercepted.contains("sd") -> Qualities.SD.value
                            intercepted.contains("360") -> Qualities.P360.value
                            intercepted.contains("240") -> Qualities.P240.value
                            else -> Qualities.Unknown.value
                        }
                        
                        callback(
                            newExtractorLink(
                                sourceName = name,
                                name = name,
                                url = intercepted,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = mainUrl
                                this.quality = quality
                                this.headers = mapOf(
                                    "Referer" to url,
                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                                )
                            }
                        )
                        
                        true
                    }
                    
                    else -> {
                        println("❌ AnimeFireExtractor: Formato não suportado: $intercepted")
                        false
                    }
                }
            } else {
                println("❌ AnimeFireExtractor: Nenhum link de vídeo encontrado")
                false
            }
        } catch (e: Exception) {
            println("💥 AnimeFireExtractor: Erro - ${e.message}")
            e.printStackTrace()
            false
        }
    }
}
