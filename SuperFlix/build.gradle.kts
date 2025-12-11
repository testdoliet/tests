android {
    compileSdk = 34 
    buildFeatures.buildConfig = true
    namespace = "com.lietrepo.superflix"
    
    defaultConfig {
        minSdk = 21
        
        // DEBUG: Log durante o build
        println("=== DEBUG BUILD ===")
        println("TMDB_API_KEY definida? ${System.getenv("TMDB_API_KEY") != null}")
        
        val tmdbApiKey = System.getenv("TMDB_API_KEY") ?: ""
        
        println("Tamanho da chave: ${tmdbApiKey.length}")
        if (tmdbApiKey.isNotEmpty()) {
            println("Primeiros 4 chars: ${tmdbApiKey.take(4)}...")
        }
        
        buildConfigField(
            "String",
            "TMDB_API_KEY", 
            "\"$tmdbApiKey\"" 
        )
        
        // ADICIONE ISSO para debug extra
        buildConfigField(
            "Boolean",
            "DEBUG_MODE",
            "true"
        )
    }
}

// Tarefa customizada para debug
tasks.register("printDebugInfo") {
    doLast {
        println("=== DEBUG INFO ===")
        println("BuildConfig será gerado com:")
        println("TMDB_API_KEY: ${System.getenv("TMDB_API_KEY")?.take(8)}...")
        println("==================")
    }
}