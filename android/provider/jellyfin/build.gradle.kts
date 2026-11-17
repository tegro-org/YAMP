// :provider:jellyfin — the Jellyfin provider (docs/phase-3-providers.md D71, D77–D79): API client, DTOs and mappers
// to the neutral models of :core:domain. Pure Kotlin/JVM: no Android types, and :core:domain is the only project it
// may depend on (ArchitectureTest pins this). The app wires it in from step 21 (sign-in).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core:domain"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
