// YAMP Diag — debug tool, never released and never built by CI (docs/diag-tool.md).
// Build by hand: scripts/build-diag.sh (./gradlew -Pyamp.diag :diag:assembleDebug).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.tegro.yamp.diag"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "org.tegro.music.yamp.diag"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    // Debug only: there is deliberately no release build type to publish.
    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/versions/**")
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.dadb)
    testImplementation(libs.junit)
}
