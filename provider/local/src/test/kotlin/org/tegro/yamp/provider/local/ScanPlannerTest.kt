package org.tegro.yamp.provider.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanPlannerTest {

    private val now = 1_000_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun listed(path: String, size: Long = 100, mtime: Long = 1) = ListedFile(path, size, mtime)

    private fun row(path: String, size: Long = 100, mtime: Long = 1, hash: String? = "h-$path", missingSince: Long? = null) =
        IndexedFile(path, size, mtime, hash, missingSince)

    private fun listing(vararg files: ListedFile, root: String = "Music", mounted: Boolean = true, failed: Set<String> = emptySet()) =
        SourceListing("V1", root, mounted, files.toList(), failed)

    private val hashes = mutableMapOf<String, String?>()
    private val hashed = mutableListOf<String>()

    private fun plan(listing: SourceListing, index: List<IndexedFile>) = ScanPlanner.plan(listing, index, now) { file ->
        hashed += file.relPath
        if (file.relPath in hashes) hashes[file.relPath] else "h-${file.relPath}"
    }

    @Test
    fun aFirstScanAddsEverythingWithHashes() {
        val plan = plan(listing(listed("Music/a.mp3"), listed("Music/sub/b.mp3")), emptyList())
        assertFalse(plan.skipped)
        assertEquals(setOf("Music/a.mp3", "Music/sub/b.mp3"), plan.added.map { it.file.relPath }.toSet())
        assertEquals("h-Music/a.mp3", plan.added.first { it.file.relPath == "Music/a.mp3" }.quickHash)
        assertTrue(plan.removed.isEmpty() && plan.moves.isEmpty() && plan.purged.isEmpty())
    }

    @Test
    fun anUnchangedTreeReadsAndHashesNothing() {
        val plan = plan(listing(listed("Music/a.mp3"), listed("Music/b.mp3")), listOf(row("Music/a.mp3"), row("Music/b.mp3")))
        assertEquals(2, plan.unchanged)
        assertTrue(plan.added.isEmpty() && plan.changed.isEmpty() && plan.revived.isEmpty())
        assertTrue(hashed.isEmpty())
    }

    @Test
    fun aSizeOrMtimeChangeIsReRead() {
        val plan = plan(
            listing(listed("Music/a.mp3", size = 200), listed("Music/b.mp3", mtime = 2), listed("Music/c.mp3")),
            listOf(row("Music/a.mp3"), row("Music/b.mp3"), row("Music/c.mp3"))
        )
        assertEquals(listOf("Music/a.mp3", "Music/b.mp3"), plan.changed.map { it.file.relPath }.sorted())
        assertEquals(1, plan.unchanged)
    }

    @Test
    fun aFileGoneFromAPresentVolumeBecomesATombstone() {
        val plan = plan(listing(listed("Music/a.mp3")), listOf(row("Music/a.mp3"), row("Music/gone.mp3", hash = null)))
        assertEquals(listOf("Music/gone.mp3"), plan.removed)
        assertTrue(plan.purged.isEmpty())
    }

    @Test
    fun anAbsentVolumeChangesNothing() {
        val plan = plan(listing(mounted = false), listOf(row("Music/a.mp3"), row("Music/b.mp3")))
        assertSame(ScanPlan.SKIPPED, plan)
        assertTrue(plan.skipped && plan.removed.isEmpty() && plan.purged.isEmpty())
        assertTrue(hashed.isEmpty())
    }

    @Test
    fun aRootThatCannotBeListedChangesNothing() {
        val plan = plan(listing(root = "Music", failed = setOf("Music")), listOf(row("Music/a.mp3")))
        assertTrue(plan.skipped)
        assertTrue(plan.removed.isEmpty())
    }

    @Test
    fun aFailedSubdirectoryKeepsItsFilesWhileOthersAreScanned() {
        val plan = plan(
            listing(listed("Music/ok/new.mp3"), failed = setOf("Music/broken")),
            listOf(row("Music/broken/a.mp3"), row("Music/broken/deep/b.mp3"), row("Music/ok/gone.mp3"), row("Music/brokenish/c.mp3"))
        )
        assertEquals(listOf("Music/brokenish/c.mp3", "Music/ok/gone.mp3"), plan.removed.sorted())
        assertEquals(listOf("Music/ok/new.mp3"), plan.added.map { it.file.relPath })
    }

    @Test
    fun aRenameOnTheSameVolumeIsAMove() {
        hashes["Music/new name.mp3"] = "same"
        val plan = plan(listing(listed("Music/new name.mp3", size = 500)), listOf(row("Music/old name.mp3", size = 500, hash = "same")))
        assertEquals(listOf(Move("Music/old name.mp3", "Music/new name.mp3")), plan.moves)
        assertEquals(listOf("Music/new name.mp3"), plan.added.map { it.file.relPath })
        // the alias carries the old file: purged, not tombstoned
        assertTrue(plan.removed.isEmpty())
        assertEquals(listOf("Music/old name.mp3"), plan.purged)
    }

    @Test
    fun aMoveIsFoundFromAnEarlierTombstone() {
        hashes["Music/b/x.mp3"] = "hx"
        val plan = plan(listing(listed("Music/b/x.mp3")), listOf(row("Music/a/x.mp3", hash = "hx", missingSince = now - 5 * day)))
        assertEquals(listOf(Move("Music/a/x.mp3", "Music/b/x.mp3")), plan.moves)
        assertEquals(listOf("Music/a/x.mp3"), plan.purged)
    }

    @Test
    fun sameHashButDifferentSizeIsNoMove() {
        hashes["Music/new.mp3"] = "same"
        val plan = plan(listing(listed("Music/new.mp3", size = 501)), listOf(row("Music/old.mp3", size = 500, hash = "same")))
        assertTrue(plan.moves.isEmpty())
        assertEquals(listOf("Music/old.mp3"), plan.removed)
    }

    @Test
    fun twoCandidatesForOneOldFileRecordNothing() {
        hashes["Music/copy1.mp3"] = "dup"
        hashes["Music/copy2.mp3"] = "dup"
        val plan = plan(listing(listed("Music/copy1.mp3"), listed("Music/copy2.mp3")), listOf(row("Music/old.mp3", hash = "dup")))
        assertTrue(plan.moves.isEmpty())
        assertEquals(listOf("Music/old.mp3"), plan.removed)
        assertEquals(2, plan.added.size)
    }

    @Test
    fun twoOldFilesForOneNewRecordNothing() {
        hashes["Music/new.mp3"] = "dup"
        val plan = plan(
            listing(listed("Music/new.mp3")),
            listOf(row("Music/old1.mp3", hash = "dup"), row("Music/old2.mp3", hash = "dup", missingSince = now - day))
        )
        assertTrue(plan.moves.isEmpty())
        assertEquals(listOf("Music/old1.mp3"), plan.removed)
        assertTrue(plan.purged.isEmpty())
    }

    @Test
    fun unknownHashesNeverMatch() {
        hashes["Music/new.mp3"] = null
        val plan = plan(listing(listed("Music/new.mp3")), listOf(row("Music/old.mp3", hash = null)))
        assertTrue(plan.moves.isEmpty())
        assertEquals(null, plan.added.single().quickHash)
    }

    @Test
    fun aFileStillPresentIsNeverAMoveSource() {
        hashes["Music/copy.mp3"] = "h-Music/orig.mp3"
        val plan = plan(listing(listed("Music/orig.mp3"), listed("Music/copy.mp3")), listOf(row("Music/orig.mp3")))
        assertTrue(plan.moves.isEmpty())
        assertEquals(1, plan.unchanged)
    }

    @Test
    fun aTombstoneThatComesBackIsRevived() {
        val plan = plan(listing(listed("Music/a.mp3")), listOf(row("Music/a.mp3", missingSince = now - day)))
        assertEquals(listOf("Music/a.mp3"), plan.revived.map { it.file.relPath })
        assertEquals(0, plan.unchanged)
        assertTrue(plan.removed.isEmpty() && plan.purged.isEmpty())
    }

    @Test
    fun tombstonesArePurgedAfterThirtyDaysOnly() {
        val plan = plan(
            listing(),
            listOf(
                row("Music/old.mp3", missingSince = now - ScanPlanner.TOMBSTONE_TTL_MS - 1),
                row("Music/recent.mp3", missingSince = now - ScanPlanner.TOMBSTONE_TTL_MS)
            )
        )
        assertEquals(listOf("Music/old.mp3"), plan.purged)
        assertTrue(plan.removed.isEmpty())
    }

    @Test
    fun tombstonesOnAnAbsentVolumeAreNotPurged() {
        val plan = plan(listing(mounted = false), listOf(row("Music/old.mp3", missingSince = now - 100 * day)))
        assertTrue(plan.purged.isEmpty())
    }

    @Test
    fun anEmptySuccessfulListingTombstonesEverything() {
        val plan = plan(listing(), listOf(row("Music/a.mp3"), row("Music/b.mp3")))
        assertEquals(listOf("Music/a.mp3", "Music/b.mp3"), plan.removed.sorted())
    }

    @Test
    fun rowsAndFilesOutsideTheSourceRootAreIgnored() {
        val plan = plan(
            listing(listed("Music/a.mp3"), listed("Other/x.mp3"), listed("Musical/y.mp3")),
            listOf(row("Podcasts/p.mp3"), row("Musical/z.mp3"))
        )
        assertEquals(listOf("Music/a.mp3"), plan.added.map { it.file.relPath })
        assertTrue(plan.removed.isEmpty())
    }

    @Test
    fun aVolumeRootSourceCoversEverything() {
        val plan = plan(listing(listed("a.mp3"), listed("d/b.mp3"), root = ""), listOf(row("gone.mp3")))
        assertEquals(2, plan.added.size)
        assertEquals(listOf("gone.mp3"), plan.removed)
    }
}
