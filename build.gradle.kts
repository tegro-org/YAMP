// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baselineprofile) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.roborazzi) apply false
}
// Unit tests run in UTF-8 whatever the shell's locale (a developer's POSIX shell, CI): file names are UTF-8 on Android,
// and a test JVM started under a POSIX locale turns the local files tests' Cyrillic names into "?" (phase 4d)
subprojects {
    tasks.withType<Test>().configureEach {
        environment("LC_ALL", "C.UTF-8")
        systemProperty("file.encoding", "UTF-8")
    }
}
