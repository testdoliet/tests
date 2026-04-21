plugins {
    id("com.android.library")
}

android {
    buildFeatures {
        buildConfig = true
    }
    
    namespace = "com.Nexflix"
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
    description = "Assistir filmes online, Assistir séries online, Lançamentos de filmes, Filmes 1080p, Séries, Animes e Doramas grátis."
    language = "pt-br"
    authors = listOf("lawlietbr")
    status = 1
    tvTypes = listOf("Movies", "Series", "AsianDrama", "Animes")
    iconUrl = "https://nexflix.vip/uploads/favicon.png"
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
}
