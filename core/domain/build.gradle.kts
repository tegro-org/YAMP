// :core:domain — pure Kotlin/JVM (docs/phase-3-providers.md D71, D82): references, accounts, the queue model and,
// from Phase 3 on, the provider / stream / outcome interfaces. No Android types here — the compiler enforces it (B1).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
