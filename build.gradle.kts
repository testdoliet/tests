
}

buildscript {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")  // Última estável é 8.7.4
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.0")
        classpath("com.github.recloudstream:gradle:master-SNAPSHOT")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://maven.pkg.jetbrains.space/public/p/kotlinx-html/maven") } // Adicione se necessário
    }
}

fun Project.cloudstream(configuration: com.lagradost.cloudstream3.gradle.CloudstreamExtension.() -> Unit) = 
    extensions.getByName<com.lagradost.cloudstream3.gradle.CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: com.android.build.gradle.BaseExtension.() -> Unit) = 
    extensions.getByName<com.android.build.gradle.BaseExtension>("android").configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        setRepo("https://github.com/lawlietbt/lietrepo")
        authors = listOf("lawlietbr")
    }

    android {
        namespace = "com.lietrepo"

        defaultConfig {
            minSdk = 21
            // CORREÇÃO: Use compileSdk e targetSdk diretamente
            compileSdk = 35
            targetSdk = 35
            
            // Adicionar configurações para evitar warnings do lint
            testOptions {
                targetSdk = 35
            }
            lint {
                targetSdk = 35
            }
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }

        // Adicionar configurações do Kotlin dentro do bloco android
        kotlin {
            compilerOptions {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions"
                )
            }
        }
        
        // Adicionar buildTypes se necessário
        buildTypes {
            getByName("release") {
                isMinifyEnabled = false
            }
        }
    }

    dependencies {
        val implementation by configurations
        val cloudstream by configurations
        
        // Core dependencies
        cloudstream("com.lagradost:cloudstream3:pre-release")
        implementation(kotlin("stdlib"))
        
        // Network & parsing
        implementation("com.github.Blatzar:NiceHttp:0.4.13")
        implementation("org.jsoup:jsoup:1.19.1")
        
        // JSON processing
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.0")
        implementation("com.fasterxml.jackson.core:jackson-databind:2.16.0")
        implementation("com.google.code.gson:gson:2.11.0")
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
        
        // Coroutines
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
        
        // Scripting
        implementation("org.mozilla:rhino:1.8.0")
        implementation("app.cash.quickjs:quickjs-android:0.9.2")
        
        // Utilities
        implementation("me.xdrop:fuzzywuzzy:1.4.0")
        implementation("com.github.vidstige:jadb:v1.2.1")
        
        // Adicionar core-ktx para melhor suporte a Kotlin
        implementation("androidx.core:core-ktx:1.13.2")
        
        // Adicionar lifecycle se usar ViewModel/LiveData
        // implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
