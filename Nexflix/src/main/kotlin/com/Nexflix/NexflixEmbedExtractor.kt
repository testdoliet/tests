package com.Nexflix

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.json.JSONObject

object NexflixEmbedExtractor {
    // Domínios principais
    private const val API_DOMAIN = "https://comprarebom.xyz"
    
    // User-Agent fixo para evitar problemas
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"

    suspend fun extractVideoLinks(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🔍 Iniciando extração para: $url")
            
            // Extrair ID do vídeo
            val videoId = extractVideoId(url)
            if (videoId.isEmpty()) {
                println("❌ Não foi possível extrair o ID do vídeo")
                return false
            }
            
            println("✅ ID extraído: $videoId")
            
            // Obter link M3U8
            val m3u8Url = getVideoUrl(videoId, url)
            if (m3u8Url == null) {
                println("❌ Não foi possível obter link do vídeo")
                return false
            }
            
            println("✅ Link obtido: ${m3u8Url.take(80)}...")
            
            // Criar e enviar link
            createVideoLink(m3u8Url, name, videoId, callback)
            
        } catch (e: Exception) {
            println("❌ Erro na extração: ${e.message}")
            false
        }
    }

    /**
     * Extrai ID do vídeo da URL
     */
    private fun extractVideoId(url: String): String {
        return try {
            // Tentar padrões comuns
            val patterns = listOf(
                Regex("""/filme/([^/?]+)"""),
                Regex("""/serie/([^/?]+)"""),
                Regex("""(tt\d+)"""),
                Regex("""/([^/?]+)(?:\?|$|/)""")
            )
            
            for (pattern in patterns) {
                val match = pattern.find(url)
                if (match != null) {
                    val id = match.groups[1]?.value
                    if (!id.isNullOrEmpty() && id.length > 3) {
                        return cleanId(id)
                    }
                }
            }
            
            // Fallback: última parte da URL
            val lastPart = url.substringAfterLast("/").substringBefore("?")
            cleanId(lastPart)
            
        } catch (e: Exception) {
            println("❌ Erro ao extrair ID: ${e.message}")
            ""
        }
    }

    /**
     * Limpa o ID
     */
    private fun cleanId(id: String): String {
        return id
            .replace("-", "")
            .replace("_", "")
            .replace(".", "")
            .trim()
    }

    /**
     * Obtém URL do vídeo da API
     */
    private suspend fun getVideoUrl(videoId: String, refererUrl: String): String? {
        return try {
            val apiUrl = "$API_DOMAIN/player/index.php?data=$videoId&do=getVideo"
            
            // Headers simplificados
            val headers = HashMap<String, String>().apply {
                put("X-Requested-With", "XMLHttpRequest")
                put("Referer", "$API_DOMAIN/e/$videoId")
                put("Origin", API_DOMAIN)
                put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                put("User-Agent", USER_AGENT)
                put("Accept", "application/json, text/javascript, */*; q=0.01")
                put("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
            }
            
            // Dados do POST
            val data = HashMap<String, String>().apply {
                put("hash", videoId)
                put("r", refererUrl)
            }
            
            println("📤 POST para: $apiUrl")
            val response = app.post(apiUrl, headers = headers, data = data)
            
            println("📥 Status: ${response.code}")
            if (response.code != 200) {
                println("❌ Status inválido")
                return null
            }
            
            val jsonText = response.text
            if (jsonText.isBlank()) {
                println("❌ Resposta vazia")
                return null
            }
            
            println("📄 Resposta: ${jsonText.take(300)}...")
            
            // Parse da resposta
            parseResponse(jsonText)
            
        } catch (e: Exception) {
            println("❌ Erro na API: ${e.message}")
            null
        }
    }

    /**
     * Parse da resposta JSON
     */
    private fun parseResponse(jsonText: String): String? {
        return try {
            val json = try {
                JSONObject(jsonText)
            } catch (e: Exception) {
                // Tentar extrair JSON de string
                val match = Regex("""(\{.*\})""", RegexOption.DOT_MATCHES_ALL).find(jsonText)
                if (match != null) {
                    JSONObject(match.groups[1]?.value ?: jsonText)
                } else {
                    println("❌ JSON inválido")
                    return null
                }
            }
            
            // Procurar em diferentes caminhos
            val paths = listOf(
                listOf("securedLink"),
                listOf("videoSource"),
                listOf("url"),
                listOf("link"),
                listOf("data", "url"),
                listOf("sources", "0", "file"),
                listOf("sources", "0", "url")
            )
            
            for (path in paths) {
                var current: Any? = json
                for (key in path) {
                    current = when (current) {
                        is JSONObject -> if (current.has(key)) current.get(key) else null
                        else -> null
                    }
                    if (current == null) break
                }
                
                if (current is String && current.isNotBlank()) {
                    var url = current
                    
                    // Corrigir URL se necessário
                    if (url.startsWith("//")) {
                        url = "https:$url"
                    } else if (url.startsWith("/")) {
                        url = "$API_DOMAIN$url"
                    }
                    
                    if (url.contains(".m3u8") || url.contains(".mp4") || url.contains(".txt")) {
                        println("✅ Link encontrado: ${url.take(80)}...")
                        return url
                    }
                }
            }
            
            // Procurar por regex
            val regex = Regex("""https?://[^"\s]+\.(?:m3u8|mp4|txt)[^"\s]*""")
            val match = regex.find(jsonText)
            match?.groups?.get(0)?.value
            
        } catch (e: Exception) {
            println("❌ Erro ao parsear: ${e.message}")
            null
        }
    }

    /**
     * Cria e envia o link do vídeo
     */
    private suspend fun createVideoLink(
        videoUrl: String,
        name: String,
        videoId: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🎬 Preparando vídeo: $name")
            
            // Headers para o player
            val headers = HashMap<String, String>().apply {
                put("User-Agent", USER_AGENT)
                put("Accept", "*/*")
                put("Accept-Language", "pt-BR")
                put("Referer", "$API_DOMAIN/")
                put("Origin", API_DOMAIN)
            }
            
            // Se for M3U8, usar M3u8Helper
            if (videoUrl.contains(".m3u8")) {
                println("📦 Processando M3U8...")
                try {
                    val links = M3u8Helper.generateM3u8(
                        source = "Nexflix",
                        streamUrl = videoUrl,
                        referer = "$API_DOMAIN/",
                        headers = headers
                    )
                    
                    if (links.isNotEmpty()) {
                        println("✅ ${links.size} links gerados")
                        links.forEach { callback(it) }
                        return true
                    }
                } catch (e: Exception) {
                    println("⚠️  Erro no M3u8Helper: ${e.message}")
                }
            }
            
            // Fallback: link direto
            println("⚠️  Usando link direto...")
            
            val quality = when {
                name.contains("4k", ignoreCase = true) || name.contains("2160") -> 2160
                name.contains("1080") -> 1080
                name.contains("720") -> 720
                name.contains("hd", ignoreCase = true) -> 1080
                name.contains("sd", ignoreCase = true) -> 480
                else -> 720
            }
            
            val link = newExtractorLink(
                source = "Nexflix",
                name = name,
                url = videoUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "$API_DOMAIN/"
                this.quality = quality
                this.headers = headers
            }
            
            callback(link)
            true
            
        } catch (e: Exception) {
            println("❌ Erro ao criar link: ${e.message}")
            false
        }
    }
}
