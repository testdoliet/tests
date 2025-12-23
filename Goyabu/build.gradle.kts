plugins {
    kotlin("android")
    id("com.android.library")
}

android {
    buildFeatures {
    buildConfig = true
    namespace = "com.SuperFlix"
    compileSdk = 33
}
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
    description = "Site brasileiro de animes em FHD"
    language = "pt-br"
    authors = listOf("lawlietbr")
    status = 1
    tvTypes = listOf("Animes")
    iconUrl = "https://imgs.search.brave.com/Cz3NeRck6yEHS1-3i5Za8ebF-kuuj8j1Oh5II2B4NSg/rs:fit:32:32:1:0/g:ce/aHR0cDovL2Zhdmlj/b25zLnNlYXJjaC5i/cmF2ZS5jb20vaWNv/bnMvNTk1NGQ3MmRh/ZjAyNjE2YmY4NDVh/MTMxNTVmZTM2ZGE2/MTZhNDgwMjgzM2Iz/MjRiNjJjYmYzN2Nh/ZWRhOWJmMy9nb3lh/YnUuaW8v"
    isCrossPlatform = true
    requiresResources = true
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
}
