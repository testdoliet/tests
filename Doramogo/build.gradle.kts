plugins {
    id("com.android.library")
}

android {
    buildFeatures {
        buildConfig = true
    }
    
    namespace = "com.Doramogo"
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
    description = "Site de Doramas online"
    language = "pt-br"
    authors = listOf("lawlietbr")
    status = 1
    tvTypes = listOf("AsianDrama")
    iconUrl = "https://www.doramogo.net/assets/doramogo/images/apple-touch-icon.webp?version=200.50.10"
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
}
