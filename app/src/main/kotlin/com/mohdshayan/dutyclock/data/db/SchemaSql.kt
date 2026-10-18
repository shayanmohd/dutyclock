package com.mohdshayan.dutyclock.data.db

/**
 * Plain SQL for the Room schema history. Constants only, with no Android imports, so the JVM
 * migration test can run the same statements over JDBC that Room runs on the device.
 */
object SchemaSql {
    /** The shipped 1.0.0 schema (identity hash 16ef86b29dde763c35f4acc46dac221b), from schemas/.../1.json. */
    val V1_CREATE: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `entries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `startUtc` INTEGER NOT NULL, `mode` TEXT NOT NULL, `ruleSet` TEXT NOT NULL, `source` TEXT NOT NULL, `note` TEXT, `createdAt` INTEGER NOT NULL, `editedAt` INTEGER)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_entries_startUtc` ON `entries` (`startUtc`)",
        "CREATE TABLE IF NOT EXISTS `seed` (`id` INTEGER NOT NULL, `seededAtUtc` INTEGER NOT NULL, `drivingSinceBreakMin` INTEGER NOT NULL, `dayStartUtc` INTEGER, `drivingTodayMin` INTEGER NOT NULL, `thisWeekDrivingMin` INTEGER NOT NULL, `lastWeekDrivingMin` INTEGER NOT NULL, `thisWeekWorkMin` INTEGER NOT NULL, `rtdPeriodWorkMin` INTEGER NOT NULL, `tenHourDaysUsed` INTEGER NOT NULL, `reducedDailyRestsUsed` INTEGER NOT NULL, `lastWeeklyRestEndUtc` INTEGER, `lastWeeklyRestMin` INTEGER, `compensationOwedMin` INTEGER NOT NULL, `compensationDueUtc` INTEGER, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `absences` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `date` TEXT NOT NULL, `kind` TEXT NOT NULL, `creditedMin` INTEGER NOT NULL)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_absences_date` ON `absences` (`date`)",
        "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
        "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '16ef86b29dde763c35f4acc46dac221b')",
    )

    /** 1.1.0: the setup's split-break flag, the instant the tachograph was read and other work since the daily rest. Nothing else changes. */
    val V1_TO_V2: List<String> = listOf(
        "ALTER TABLE `seed` ADD COLUMN `part15Taken` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `seed` ADD COLUMN `readAtUtc` INTEGER",
        "ALTER TABLE `seed` ADD COLUMN `otherWorkSinceRestMin` INTEGER NOT NULL DEFAULT 0",
    )
}
