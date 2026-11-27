// :provider:local — the pure core of the local files provider (docs/phase-4d-local-files.md, step 1): refs, tag text
// repair, ID3v1, file-name fallbacks, album grouping and the scan planner. Pure Kotlin/JVM: no Android, no Media3, and
// :core:domain is the only project it may depend on (D77, ArchitectureTest pins this).
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
    implementation(project(":core:domain"))
    testImplementation(libs.junit)
}
