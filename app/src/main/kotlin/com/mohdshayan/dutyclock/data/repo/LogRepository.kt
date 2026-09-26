package com.mohdshayan.dutyclock.data.repo

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.mohdshayan.dutyclock.alerts.AlertScheduler
import com.mohdshayan.dutyclock.core.backup.Backup
import com.mohdshayan.dutyclock.core.backup.BackupEntry
import com.mohdshayan.dutyclock.core.backup.BackupFile
import com.mohdshayan.dutyclock.core.engine.EngineInput
import com.mohdshayan.dutyclock.core.model.Absence
import com.mohdshayan.dutyclock.core.model.AbsenceKind
import com.mohdshayan.dutyclock.core.model.Entry
import com.mohdshayan.dutyclock.core.model.Mode
import com.mohdshayan.dutyclock.core.model.RuleSet
import com.mohdshayan.dutyclock.core.model.Seed
import com.mohdshayan.dutyclock.data.db.AbsenceRow
import com.mohdshayan.dutyclock.data.db.ActivityEntry
import com.mohdshayan.dutyclock.data.db.AppDatabase
import com.mohdshayan.dutyclock.data.db.SeedRow
import com.mohdshayan.dutyclock.data.prefs.AppPrefs
import com.mohdshayan.dutyclock.data.prefs.Settings
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Why an edit was refused, in the words the editor shows. */
class EditRefused(message: String) : Exception(message)

/** The log and everything derived from it. Every write re-arms the alerts. */
class LogRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val prefs: AppPrefs,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val entryDao = db.entryDao()
    private val seedDao = db.seedDao()
    private val absenceDao = db.absenceDao()

    val entries: Flow<List<ActivityEntry>> = entryDao.observeAll()

    /** Everything the rules engine needs, re-emitted on any change to the log or the settings. */
    val input: Flow<Pair<EngineInput, Settings>> = combine(
        entryDao.observeAll(), seedDao.observe(), absenceDao.observeAll(), prefs.settings,
    ) { e, s, a, settings ->
        EngineInput(e.map { it.toCore() }, s?.toCore(), a.map { it.toCore() }, settings.choices, settings.ruleSet, ZoneId.systemDefault()) to settings
    }

    suspend fun snapshot(): Pair<EngineInput, Settings> = input.first()

    private fun afterWrite() {
        scope.launch { AlertScheduler.reschedule(context) }
    }

    /** One tap on the mode bar. Returns the new entry's id, or null when that mode is already current. */
    suspend fun tap(mode: Mode, now: Long = System.currentTimeMillis()): Long? {
        val latest = entryDao.latest()
        if (latest != null && latest.mode == mode.name && latest.startUtc <= now) return null
        val ruleSet = prefs.current().ruleSet
        var t = now
        while (entryDao.countAt(t) > 0) t++
        val id = entryDao.insert(ActivityEntry(startUtc = t, mode = mode.name, ruleSet = ruleSet.name, source = "LIVE", createdAt = now))
        prefs.bump(AppPrefs.Count.MODE_TAPS)
        afterWrite()
        return id
    }

    suspend fun undo(id: Long) {
        entryDao.delete(id)
        afterWrite()
    }

    suspend fun entry(id: Long): ActivityEntry? = entryDao.byId(id)

    /** Moves or re-modes an entry. Refuses a start that would jump over its neighbours. */
    suspend fun saveEdit(id: Long, start: Long, mode: Mode, now: Long = System.currentTimeMillis()) {
        val all = entryDao.all()
        val i = all.indexOfFirst { it.id == id }
        if (i < 0) throw EditRefused("That entry no longer exists.")
        if (start > now) throw EditRefused("That start is in the future. Pick a time up to now.")
        val next = all.getOrNull(i + 1)
        val prev = all.getOrNull(i - 1)
        if (next != null && start >= next.startUtc) throw EditRefused("That start overlaps the next entry. Pick an earlier time.")
        if (prev != null && start <= prev.startUtc) throw EditRefused("That start overlaps the entry before. Pick a later time.")
        val old = all[i]
        entryDao.update(old.copy(startUtc = start, mode = mode.name, source = if (old.source == "IMPORT") "IMPORT" else "EDIT", editedAt = now))
        afterWrite()
    }

    /** Adds an entry at any past minute; it splits whatever stretch it lands in. */
    suspend fun add(start: Long, mode: Mode, now: Long = System.currentTimeMillis()): Long {
        if (start > now) throw EditRefused("That start is in the future. Pick a time up to now.")
        if (entryDao.countAt(start) > 0) throw EditRefused("Another entry starts at that minute. Pick another time.")
        val ruleSet = prefs.current().ruleSet
        val id = try {
            entryDao.insert(ActivityEntry(startUtc = start, mode = mode.name, ruleSet = ruleSet.name, source = "EDIT", createdAt = now, editedAt = now))
        } catch (e: SQLiteConstraintException) {
            throw EditRefused("Another entry starts at that minute. Pick another time.")
        }
        afterWrite()
        return id
    }

    /** "Split here": a new entry inside an existing stretch, strictly between its start and the next. */
    suspend fun split(id: Long, at: Long, mode: Mode, now: Long = System.currentTimeMillis()): Long {
        val all = entryDao.all()
        val i = all.indexOfFirst { it.id == id }
        if (i < 0) throw EditRefused("That entry no longer exists.")
        val end = all.getOrNull(i + 1)?.startUtc ?: now
        if (at <= all[i].startUtc || at >= end) throw EditRefused("Pick a time inside this entry to split it.")
        return add(at, mode, now)
    }

    suspend fun delete(id: Long) {
        entryDao.delete(id)
        afterWrite()
    }

    suspend fun saveSeed(seed: Seed) {
        seedDao.upsert(seed.toRow())
        afterWrite()
    }

    suspend fun clearSeed() {
        seedDao.clear()
        afterWrite()
    }

    suspend fun setAbsence(date: String, kind: AbsenceKind?) {
        if (kind == null) absenceDao.deleteDate(date) else absenceDao.put(AbsenceRow(date = date, kind = kind.name))
        afterWrite()
    }

    suspend fun setRuleSet(ruleSet: RuleSet) {
        prefs.set(AppPrefs.Keys.RULE_SET_DEFAULT, ruleSet.name)
        afterWrite()
    }

    suspend fun setChoice(tenHourFor: Long? = null, reducedFor: Long? = null) {
        if (tenHourFor != null) prefs.set(AppPrefs.Keys.TEN_HOUR_DAY, tenHourFor)
        if (reducedFor != null) prefs.set(AppPrefs.Keys.REDUCED_REST, reducedFor)
        afterWrite()
    }

    suspend fun backup(now: Long = System.currentTimeMillis()): String {
        val file = BackupFile(
            exportedAt = now,
            prefs = prefs.exportable(),
            seed = seedDao.get()?.toCore(),
            entries = entryDao.all().map { BackupEntry(it.startUtc, it.mode, it.ruleSet, it.source, it.note, it.createdAt, it.editedAt) },
            absences = absenceDao.all().map { it.toCore() },
        )
        return Backup.encode(file)
    }

    /** Replaces the whole log with a validated backup in one transaction; returns the entry count. */
    suspend fun restore(file: BackupFile): Int {
        db.withTransaction {
            entryDao.deleteAll(); seedDao.clear(); absenceDao.deleteAll()
            entryDao.insertAll(
                file.entries.map {
                    ActivityEntry(startUtc = it.startUtc, mode = it.mode, ruleSet = it.ruleSet, source = "IMPORT", note = it.note, createdAt = it.createdAt, editedAt = it.editedAt)
                },
            )
            file.seed?.let { seedDao.upsert(it.toRow()) }
            absenceDao.putAll(file.absences.map { AbsenceRow(date = it.date, kind = it.kind.name, creditedMin = it.creditedMin) })
        }
        prefs.import(file.prefs)
        afterWrite()
        return file.entries.size
    }
}

fun ActivityEntry.toCore() = Entry(
    id, startUtc,
    runCatching { Mode.valueOf(mode) }.getOrDefault(Mode.WORK),
    runCatching { RuleSet.valueOf(ruleSet) }.getOrDefault(RuleSet.EU),
    editedAt != null,
)

fun AbsenceRow.toCore() = Absence(date, runCatching { AbsenceKind.valueOf(kind) }.getOrDefault(AbsenceKind.ANNUAL_LEAVE), creditedMin)

fun SeedRow.toCore() = Seed(
    seededAtUtc, drivingSinceBreakMin, dayStartUtc, drivingTodayMin, thisWeekDrivingMin, lastWeekDrivingMin, thisWeekWorkMin,
    rtdPeriodWorkMin, tenHourDaysUsed, reducedDailyRestsUsed, lastWeeklyRestEndUtc, lastWeeklyRestMin, compensationOwedMin, compensationDueUtc,
)

fun Seed.toRow() = SeedRow(
    1, seededAtUtc, drivingSinceBreakMin, dayStartUtc, drivingTodayMin, thisWeekDrivingMin, lastWeekDrivingMin, thisWeekWorkMin,
    rtdPeriodWorkMin, tenHourDaysUsed, reducedDailyRestsUsed, lastWeeklyRestEndUtc, lastWeeklyRestMin, compensationOwedMin, compensationDueUtc,
)
