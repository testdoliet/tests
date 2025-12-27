package com.SuperFlix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

object SuperFlixExtractor {
    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🚀 Iniciando extração com estratégia de delay...")
            
            // Variável para controlar o tempo
            val startTime = System.currentTimeMillis()
            var interceptEnabled = false
            
            // A assinatura CORRETA do WebViewResolver é:
            // WebViewResolver(interceptUrl: Regex, useOkhttp: Boolean = false, timeout: Long = 8000L)
            val streamResolver = WebViewResolver(
                interceptUrl = Regex(""".*"""), // Intercepta TUDO inicialmente
                useOkhttp = false,
                timeout = 25000L // 25 segundos
            )
            
            // Mas precisamos de um wrapper para controlar o delay
            // Infelizmente não podemos modificar o WebViewResolver diretamente
            
            // SOLUÇÃO: Usar abordagem diferente - duas etapas
            return twoStepExtraction(url, name, callback)
            
        } catch (e: Exception) {
            println("💥 Erro: ${e.message}")
            false
        }
    }
    
    private suspend fun twoStepExtraction(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            println("🎯 Estratégia em 2 etapas:")
            println("1. Primeiros 5s: Ignorar tudo")
            println("2. Depois: Interceptar m3u8")
            
            val startTime = System.currentTimeMillis()
            var m3u8Found: String? = null
            
            // Primeiro: Deixar a página carregar por 5 segundos
            val initialResolver = WebViewResolver(
                interceptUrl = Regex(""".*\.m3u8.*"""), // Procura m3u8
                useOkhttp = false,
                timeout = 5000L // Só 5 segundos para a fase inicial
            )
            
            println("⏳ Etapa 1: Carregando página (5s)...")
            try {
                val initialResponse = app.get(url, interceptor = initialResolver)
                if (initialResponse.url.contains(".m3u8")) {
                    m3u8Found = initialResponse.url
                    println("⚠️  M3U8 encontrado cedo demais (durante ads)")
                }
            } catch (e: Exception) {
                // Timeout esperado após 5s
                println("✅ Primeiros 5s completos (ads devem ter carregado)")
            }
            
            // Segundo: Agora tentar achar o m3u8 REAL
            println("🔍 Etapa 2: Procurando m3u8 real...")
            
            val finalResolver = WebViewResolver(
                interceptUrl = Regex(""".*\.m3u8.*"""),
                useOkhttp = false,
                timeout = 15000L // Mais 15 segundos
            )
            
            val finalResponse = app.get(url, interceptor = finalResolver)
            
            if (finalResponse.url.contains(".m3u8")) {
                val m3u8Url = finalResponse.url
                println("✅ M3U8 encontrado após delay: $m3u8Url")
                
                M3u8Helper.generateM3u8(
                    name,
                    m3u8Url,
                    "https://g9r6.com/",
                    headers = mapOf(
                        "Referer" to "https://g9r6.com/",
                        "Origin" to "https://g9r6.com",
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
                    )
                ).forEach(callback)
                
                true
            } else {
                println("❌ Nenhum M3U8 encontrado")
                false
            }
        } catch (e: Exception) {
            println("💥 Erro na extração: ${e.message}")
            false
        }
    }
}
