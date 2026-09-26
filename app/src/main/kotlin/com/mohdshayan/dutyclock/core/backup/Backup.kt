package com.mohdshayan.dutyclock.core.backup

import com.mohdshayan.dutyclock.core.model.Absence
import com.mohdshayan.dutyclock.core.model.Seed
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One logged entry as it travels in a backup. Mode and rule set are enum names. */
@Serializable
data class BackupEntry(
    val startUtc: Long,
    val mode: String,
    val ruleSet: String,
    val source: String = "LIVE",
    val note: String? = null,
    val createdAt: Long = startUtc,
    val editedAt: Long? = null,
)

@Serializable
data class BackupFile(
    val format: String = FORMAT,
    val schema: Int = SCHEMA,
    val exportedAt: Long,
    val prefs: Map<String, String> = emptyMap(),
    val seed: Seed? = null,
    val entries: List<BackupEntry> = emptyList(),
    val absences: List<Absence> = emptyList(),
) {
    companion object {
        const val FORMAT = "dutyclock-backup"
        const val SCHEMA = 1
    }
}

class NotABackup(message: String) : Exception(message)

object Backup {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }
    private val validModes = setOf("DRIVE", "WORK", "AVAILABLE", "REST")
    private val validRules = setOf("EU", "AETR", "VAN", "GB_GOODS")

    fun encode(file: BackupFile): String = json.encodeToString(BackupFile.serializer(), file)

    /** Parses and validates; throws [NotABackup] for anything that is not a Dutyclock backup. */
    fun decode(text: String): BackupFile {
        val file = try {
            json.decodeFromString(BackupFile.serializer(), text.removePrefix("﻿"))
        } catch (e: SerializationException) {
            throw NotABackup("not json")
        } catch (e: IllegalArgumentException) {
            throw NotABackup("not json")
        }
        if (file.format != BackupFile.FORMAT) throw NotABackup("format")
        if (file.schema != BackupFile.SCHEMA) throw NotABackup("schema")
        if (file.entries.any { it.mode !in validModes || it.ruleSet !in validRules }) throw NotABackup("entries")
        if (file.entries.any { it.startUtc !in EARLIEST..LATEST }) throw NotABackup("times")
        if (file.entries.map { it.startUtc }.toSet().size != file.entries.size) throw NotABackup("duplicates")
        if (file.absences.any { a -> a.creditedMin !in 0..24 * 60 || runCatching { LocalDate.parse(a.date) }.isFailure }) throw NotABackup("absences")
        file.seed?.let { s ->
            val minutes = listOf(
                s.drivingSinceBreakMin, s.drivingTodayMin, s.thisWeekDrivingMin, s.lastWeekDrivingMin, s.thisWeekWorkMin,
                s.rtdPeriodWorkMin, s.tenHourDaysUsed, s.reducedDailyRestsUsed, s.compensationOwedMin, s.lastWeeklyRestMin ?: 0,
            )
            if (minutes.any { it !in 0..MAX_SEED_MIN } || s.seededAtUtc !in EARLIEST..LATEST) throw NotABackup("seed")
            if (s.tenHourDaysUsed !in 0..2 || s.reducedDailyRestsUsed !in 0..3) throw NotABackup("seed")
        }
        return file
    }

    /** 2000-01-01 to 2100-01-01 UTC: anything outside is a damaged or foreign file. */
    private const val EARLIEST = 946_684_800_000L
    private const val LATEST = 4_102_444_800_000L

    /** An 18-week working time period, in minutes: the largest number the catch-up can hold. */
    private const val MAX_SEED_MIN = 18 * 7 * 24 * 60

    fun fileName(now: Long, zone: ZoneId): String =
        "dutyclock-backup-" + DateTimeFormatter.ISO_LOCAL_DATE.format(Instant.ofEpochMilli(now).atZone(zone)) + ".json"
}
