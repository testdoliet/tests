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
        
        // TMDB API Credentials
        buildConfigField("String", "TMDB_API_KEY", 
            "\"${System.getenv("TMDB_API_KEY") ?: "dummy_api_key"}\"")
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
    version = 1  
    description = "SuperFlix - Filmes e Séries em Português"
    language = "pt-br"
    authors = listOf("lawlietbr")
    status = 1
    tvTypes = listOf("Movies", "Series", "Animes")
    iconUrl = "https://imgs.search.brave.com/1ekfsjPNcZ1vInPWIe6FcAT6pLaRpbPmkomfCZLoCg8/rs:fit:32:32:1:0/g:ce/aHR0cDovL2Zhdmlj/b25zLnNlYXJjaC5i/cmF2ZS5jb20vaWNv/bnMvOGU2MDlmM2Y3/NjE2ZTdhZGI5M2Q2/M2ZmOTkyODg5NjYz/YzRmNjBlODVkZjYw/OGMxMmVhMzY2NmU5/NzlmNTY4Yi9zdXBl/cmZsaXgyMS5sb2wv"
    isCrossPlatform = true
    requiresResources = true
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
}
