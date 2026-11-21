plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.calculadora.entregas"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.calculadora.entregas"
        minSdk = 26
        targetSdk = 35
        // Sobe a cada build do GitHub Actions; local usa 11.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 11
        versionName = "1.0.0"
    }

    // Chave fixa no projeto: todo APK sai com a mesma assinatura e atualiza por cima do anterior.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
