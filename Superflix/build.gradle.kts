plugins {
    id("com.android.library")
}

android {
    buildFeatures {
        buildConfig = true
    }
    
    namespace = "com.Superflix"
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
    description = "Superflix - Assistir Filmes e Séries Online HD"
    language = "pt-br"
    authors = listOf("lawlietbr")
    status = 1
    tvTypes = listOf("Movies", "Series")
    iconUrl = "https://superflix1.cloud/wp-content/uploads/2024/07/FAVIFLIX-1-300x300.png"
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
}
