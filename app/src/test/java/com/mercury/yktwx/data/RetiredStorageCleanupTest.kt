package com.mercury.yktwx.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RetiredStorageCleanupTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun retiredCacheIsRemovedRecursivelyWhileCurrentAndUnrelatedFilesRemain() {
        val retired = File(files.root, "display-v1").apply { mkdirs() }
        File(retired, "old.cache").writeText("old snapshot")
        File(retired, "nested").apply { mkdirs() }.resolve("partial.tmp").writeText("partial snapshot")
        val current = File(files.root, "display-v2").apply { mkdirs() }.resolve("current.cache")
            .apply { writeText("current snapshot") }
        val unrelated = File(files.root, "other-cache").apply { writeText("keep") }
        val completion = File(files.root, "no-backup/retired-cache-cleanup.version")
        val cleanup = RetiredStorageCleanup(files.root, completion)

        cleanup.cleanUp()
        assertFalse(retired.exists())
        assertEquals("current snapshot", current.readText())
        assertEquals("keep", unrelated.readText())
        assertEquals("1", completion.readText())
        cleanup.cleanUp()
        assertTrue(current.exists())

        // Completed versions skip traversing the cache on subsequent starts.
        retired.writeText("leftover")
        RetiredStorageCleanup(files.root, completion).cleanUp()
        assertTrue(retired.exists())
    }

    @Test fun failedCompletionWriteRetriesAtTheNextStart() {
        val retired = File(files.root, "display-v1").apply { mkdirs() }
        File(retired, "old.cache").writeText("old")
        val blockedParent = File(files.root, "no-backup").apply { writeText("not a directory") }
        val completion = File(blockedParent, "retired-cache-cleanup.version")
        RetiredStorageCleanup(files.root, completion).cleanUp()
        assertFalse(retired.exists())
        assertFalse(completion.exists())

        retired.mkdirs()
        File(retired, "partial.tmp").writeText("leftover after interruption")
        assertTrue(blockedParent.delete())
        RetiredStorageCleanup(files.root, completion).cleanUp()
        assertFalse(retired.exists())
        assertEquals("1", completion.readText())
    }
}
