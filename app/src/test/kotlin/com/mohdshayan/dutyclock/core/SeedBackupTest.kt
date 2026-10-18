package com.mohdshayan.dutyclock.core

import com.mohdshayan.dutyclock.core.backup.Backup
import com.mohdshayan.dutyclock.core.backup.BackupFile
import com.mohdshayan.dutyclock.core.backup.NotABackup
import com.mohdshayan.dutyclock.core.model.Seed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** Buyers' data: 1.0.0 backups keep reading, and the two new seed fields travel and are checked. */
class SeedBackupTest {
    @Test
    fun `a 1_0_0 backup reads with the new seed fields at their defaults`() {
        val f = Backup.decode("""{"format":"dutyclock-backup","schema":1,"exportedAt":1,"seed":{"seededAtUtc":1790000000000,"drivingSinceBreakMin":100}}""")
        val s = f.seed!!
        assertEquals(100, s.drivingSinceBreakMin)
        assertFalse(s.part15Taken)
        assertNull(s.readAtUtc)
        assertEquals(0, s.otherWorkSinceRestMin)
    }

    @Test
    fun `a backup keeps other work since the daily rest, from 0 to 1440 minutes`() {
        val seed = Seed(seededAtUtc = 1_790_000_000_000, otherWorkSinceRestMin = 1440)
        assertEquals(seed, Backup.decode(Backup.encode(BackupFile(exportedAt = 1, seed = seed))).seed)
        for (bad in listOf(-1, 1441)) {
            try {
                Backup.decode("""{"format":"dutyclock-backup","schema":1,"exportedAt":1,"seed":{"seededAtUtc":1790000000000,"otherWorkSinceRestMin":$bad}}""")
                fail("accepted $bad")
            } catch (_: NotABackup) {
            }
        }
    }

    @Test
    fun `a backup keeps part15Taken and readAtUtc`() {
        val seed = Seed(seededAtUtc = 1_790_000_000_000, drivingSinceBreakMin = 100, part15Taken = true, readAtUtc = 1_790_000_000_000 + h(0, 20))
        val back = Backup.decode(Backup.encode(BackupFile(exportedAt = 1, seed = seed)))
        assertEquals(seed, back.seed)
    }

    @Test
    fun `a readAtUtc before seededAtUtc is refused`() {
        val bad = listOf(
            """{"format":"dutyclock-backup","schema":1,"exportedAt":1,"seed":{"seededAtUtc":1790000000000,"readAtUtc":1789999999999}}""",
            """{"format":"dutyclock-backup","schema":1,"exportedAt":1,"seed":{"seededAtUtc":1790000000000,"readAtUtc":${1_790_000_000_000 + 7 * 24 * 3_600_000L + 1}}}""",
        )
        for (text in bad) {
            try {
                Backup.decode(text); fail("accepted $text")
            } catch (_: NotABackup) {
            }
        }
        Backup.decode("""{"format":"dutyclock-backup","schema":1,"exportedAt":1,"seed":{"seededAtUtc":1790000000000,"readAtUtc":${1_790_000_000_000 + 7 * 24 * 3_600_000L}}}""")
    }
}
