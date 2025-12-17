plugins {
    kotlin("android")
    id("com.android.library")
}

android {
    namespace = "com.SuperFlix"
    compileSdk = 33

    defaultConfig {
        minSdk = 24
        targetSdk = 33

        // VARIÁVEIS DE BUILD PARA TMDB
        // API KEY: Usada em queries (?api_key=xxx)
        buildConfigField("String", "TMDB_API_KEY", 
            "\"${System.getenv("TMDB_API_KEY") ?: "dummy_api_key"}\"")
        
        // ACCESS TOKEN: Usada em headers (Authorization: Bearer xxx)
        buildConfigField("String", "TMDB_ACCESS_TOKEN", 
            "\"${System.getenv("TMDB_ACCESS_TOKEN") ?: "dummy_access_token"}\"")
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

cloudstream {
    description = "SuperFlix - Filmes e Séries em Português"
    language = "pt-br"
    authors = listOf("lawlietbr")
    status = 1
    tvTypes = listOf("Movies", "Series", "Animes")
    iconUrl = "https://superflix21.lol/assets/logo.png"
    isCrossPlatform = true
    requiresResources = true
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
}
