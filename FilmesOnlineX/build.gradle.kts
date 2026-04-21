plugins {
    id("com.android.library")
}

android {
    buildFeatures {
        buildConfig = true
    }
    
    namespace = "com.FilmesOnlineX"
    compileSdk = 33

    defaultConfig {
        minSdk = 24

        val tmdbApiKey = System.getenv("TMDB_API_KEY") ?: "dummy_api_key"
        val tmdbAccessToken = System.getenv("TMDB_ACCESS_TOKEN") ?: "dummy_access_token"
        
        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbApiKey\"")
        buildConfigField("String", "TMDB_ACCESS_TOKEN", "\"$tmdbAccessToken\"")
    }
}

cloudstream {
    version = 1
    description = "Filmes Online X - Assistir Filmes Online Grátis Dublado e Legendado em HD 720P e 1080P"
    language = "pt-br"
    authors = listOf("lawlietbr") 
    status = 1 
    tvTypes = listOf("Movies", "Series")
    iconUrl = "https://filmesonlinex.wf/wp-content/uploads/2025/05/favicon.png"
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")

    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")
}
