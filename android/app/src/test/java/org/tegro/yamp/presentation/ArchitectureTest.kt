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

    /** ARCHITECTURE B1: the domain is pure Kotlin — no Android, no Media3, no data layer, no DTOs. */
    @Test
    fun theDomainQueueModelImportsNothingFromAndroidOrData() {
        val forbidden = listOf("import android.", "import androidx.", "import org.tegro.yamp.data.", "import org.tegro.yamp.presentation.", "Dto")
        val offenders = File(sourceDir, "domain/queue").walk()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> file.readLines().filter { line -> forbidden.any { line.contains(it) } }.map { "${file.name}: $it" } }
            .toList()
        assertEquals(emptyList<String>(), offenders)
    }
}
