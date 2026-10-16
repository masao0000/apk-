plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.freeformlauncher"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.freeformlauncher"
        minSdk = 30
        targetSdk = 36
        versionCode = 4
        versionName = "0.2.1"
    }

    signingConfigs {
        // 再ビルドしても上書きインストールできるよう固定鍵を同梱（秘密鍵ごと公開。個人利用前提）
        create("shared") {
            storeFile = rootProject.file("keystore/shared.jks")
            storePassword = "freeform"
            keyAlias = "freeform"
            keyPassword = "freeform"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("shared") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    val shizuku = "13.1.5"
    implementation("dev.rikka.shizuku:api:$shizuku")
    implementation("dev.rikka.shizuku:provider:$shizuku")

    implementation(platform("androidx.compose:compose-bom:2025.06.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
}
