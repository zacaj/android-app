plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// CI sets GITHUB_RUN_NUMBER/GITHUB_SHA; local builds get 0 / "local".
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
val gitSha = System.getenv("GITHUB_SHA")?.take(7) ?: "local"

android {
    namespace = "com.zacaj.posture"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zacaj.posture"
        minSdk = 29
        targetSdk = 35
        // Offset keeps codes above the original hard-coded 1.
        versionCode = 100 + buildNumber
        versionName = "0.1.$buildNumber"
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
    }

    // Fixed, committed key so every build (CI or local) can update the installed app.
    // Personal sideloaded app only — this key is public, don't reuse it for anything distributed.
    signingConfigs {
        create("personal") {
            storeFile = file("posture.keystore")
            storePassword = "posture"
            keyAlias = "posture"
            keyPassword = "posture"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("personal") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("personal")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
}
