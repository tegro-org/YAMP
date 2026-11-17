package org.tegro.yamp.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Layering rules from the plan (B1): screens and ViewModels reach the network only through repositories. */
class ArchitectureTest {

    private val sourceDir = File("src/main/java/org/tegro/yamp")

    @Test
    fun presentationDoesNotCallTheApi() {
        val offenders = File(sourceDir, "presentation").walk()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("YandexMusicApi") }
            .map { it.relativeTo(sourceDir).path }
            .toList()
        assertEquals("presentation files using YandexMusicApi", emptyList<String>(), offenders)
    }

    @Test
    fun accountChangesResetPerUserDataInOnePlace() {
        val resets = sourceDir.walk()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("playlistsRepository.resetForAccountChange()") }
            .map { it.name }
            .toList()
        assertEquals(listOf("AccountSessionManager.kt"), resets)
        assertTrue(File(sourceDir, "presentation/main/MainViewModel.kt").readText().contains("session.syncAll()"))
    }

    /** ARCHITECTURE B3: the queue switcher talks to the controller only — no repositories, no store, no manager. */
    @Test
    fun theQueueSwitcherDependsOnThePlaybackControllerOnly() {
        val allowed = listOf("import org.tegro.yamp.data.dto.", "import org.tegro.yamp.data.player.PlaybackController")
        val offenders = File(sourceDir, "presentation/queues").walk()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines()
                    .filter { line -> line.startsWith("import org.tegro.yamp.data.") && allowed.none { line.startsWith(it) } }
                    .map { "${file.name}: $it" }
            }
            .toList()
        assertEquals(emptyList<String>(), offenders)
    }

    /**
     * ARCHITECTURE B1 / D71, D82: `:core:domain` is a pure Kotlin/JVM module. The compiler already keeps Android
     * and the app's code out (the module has neither on its classpath); this pins the build file so nobody adds
     * them back, and keeps DTO names out of its sources.
     */
    @Test
    fun theDomainModuleStaysPureKotlin() {
        val build = File(domainModuleDir, "build.gradle.kts").readText()
        val forbiddenInBuild = listOf("com.android", "android.application", "android.library", "project(\"")
        assertEquals(emptyList<String>(), forbiddenInBuild.filter { build.contains(it) })
        val forbidden = listOf("import android.", "import androidx.", "import org.tegro.yamp.data.", "import org.tegro.yamp.presentation.", "Dto")
        val offenders = File(domainModuleDir, "src/main").walk()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> file.readLines().filter { line -> forbidden.any { line.contains(it) } }.map { "${file.name}: $it" } }
            .toList()
        assertEquals(emptyList<String>(), offenders)
    }

    /**
     * ARCHITECTURE B1 for what is still under `:app`'s `domain/` (models, repository interfaces): no data layer,
     * no screens. The two known framework leaks are listed and go when their files are touched (B1 note).
     */
    @Test
    fun theAppDomainPackageLeaksOnlyTheKnownFrameworkTypes() {
        val knownLeaks = setOf(
            "model/RadioStation.kt: androidx.",
            "repository/PlayerRepository.kt: androidx."
        )
        val forbidden = listOf("android.", "androidx.", "org.tegro.yamp.data.", "org.tegro.yamp.presentation.")
        val domainDir = File(sourceDir, "domain")
        val leaks = domainDir.walk()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val name = file.relativeTo(domainDir).invariantSeparatorsPath
                // Code only: KDoc may point at implementations ([org.tegro.yamp.data…]) without depending on them.
                val code = file.readLines().filterNot { it.trimStart().let { line -> line.startsWith("*") || line.startsWith("//") || line.startsWith("/*") } }
                forbidden.filter { prefix -> code.any { it.contains(Regex("""(?<![\w.])${Regex.escape(prefix)}""")) } }
                    .map { prefix -> "$name: $prefix" }
            }
            .toSet()
        assertEquals("new framework/data imports in app/domain (fix them, don't list them)", emptySet<String>(), leaks - knownLeaks)
    }

    /**
     * ARCHITECTURE B2/B4, D77: Yandex DTOs belong to the Yandex provider. Files outside `data/dto` and
     * `data/provider/yandex` that still use them are listed in `architecture/yandex-dto-users.txt`; the list may
     * only shrink — a new user fails, and so does a listed file that no longer needs the entry.
     */
    @Test
    fun yandexDtosStayInsideTheProviderExceptForTheShrinkingAllowList() {
        val dtoNames = Regex("""\bclass\s+([A-Z]\w*)""").findAll(File(sourceDir, "data/dto/ApiModels.kt").readText())
            .map { it.groupValues[1] }.toSet()
        val usage = Regex("""\b(${dtoNames.joinToString("|")})\b""")
        val users = sourceDir.walk()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(sourceDir).invariantSeparatorsPath to it }
            .filterNot { (path, _) -> path.startsWith("data/dto/") || path.startsWith("data/provider/yandex/") }
            .filter { (_, file) -> usage.containsMatchIn(file.readText()) }
            .map { it.first }
            .toSet()
        val allowList = checkNotNull(javaClass.classLoader?.getResource("architecture/yandex-dto-users.txt")) { "allow-list missing" }
            .readText().lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()
        assertEquals("new files using Yandex DTOs — map to domain models instead", emptySet<String>(), users - allowList)
        assertEquals("files no longer using Yandex DTOs — remove them from the allow-list", emptySet<String>(), allowList - users)
    }

    /**
     * D71, D77: `:provider:jellyfin` is pure Kotlin/JVM and depends on no project but `:core:domain` — it never
     * reaches into the app or the Yandex provider; the app wires it in, not the other way round.
     */
    @Test
    fun theJellyfinModuleDependsOnlyOnTheDomainModule() {
        val build = File(jellyfinModuleDir, "build.gradle.kts").readText()
        val forbiddenInBuild = listOf("com.android", "android.application", "android.library")
        assertEquals(emptyList<String>(), forbiddenInBuild.filter { build.contains(it) })
        val projects = Regex("""project\("([^"]+)"\)""").findAll(build).map { it.groupValues[1] }.toList()
        assertEquals(listOf(":core:domain"), projects)
        val forbidden = listOf("import android.", "import androidx.", "import org.tegro.yamp.data.", "import org.tegro.yamp.presentation.")
        val offenders = File(jellyfinModuleDir, "src/main").walk()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> file.readLines().filter { line -> forbidden.any { line.contains(it) } }.map { "${file.name}: $it" } }
            .toList()
        assertEquals(emptyList<String>(), offenders)
    }

    private val domainModuleDir = File("../core/domain")
    private val jellyfinModuleDir = File("../provider/jellyfin")
}
