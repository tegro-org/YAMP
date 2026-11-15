pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Yandex Music"
include(":app")
include(":baselineprofile")
// YAMP Diag (docs/diag-tool.md): a debug tool built by hand only. Included only with -Pyamp.diag, so CI, release
// builds and the local gates (root tasks such as lintDebug / testDebugUnitTest) never see it.
if (providers.gradleProperty("yamp.diag").isPresent) include(":diag")
 