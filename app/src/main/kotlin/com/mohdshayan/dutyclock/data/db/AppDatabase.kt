package com.mohdshayan.dutyclock.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** One tap on a tachograph mode. Lasts until the next entry. editedAt non-null marks it edited. */
@Entity(tableName = "entries", indices = [Index(value = ["startUtc"], unique = true)])
data class ActivityEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startUtc: Long,
    val mode: String,
    val ruleSet: String,
    val source: String = "LIVE",
    val note: String? = null,
    val createdAt: Long,
    val editedAt: Long? = null,
)

/** The catch-up sheet, one row. */
@Entity(tableName = "seed")
data class SeedRow(
    @PrimaryKey val id: Int = 1,
    val seededAtUtc: Long,
    val drivingSinceBreakMin: Int,
    val dayStartUtc: Long?,
    val drivingTodayMin: Int,
    val thisWeekDrivingMin: Int,
    val lastWeekDrivingMin: Int,
    val thisWeekWorkMin: Int,
    val rtdPeriodWorkMin: Int,
    val tenHourDaysUsed: Int,
    val reducedDailyRestsUsed: Int,
    val lastWeeklyRestEndUtc: Long?,
    val lastWeeklyRestMin: Int?,
    val compensationOwedMin: Int,
    val compensationDueUtc: Long?,
)

@Entity(tableName = "absences", indices = [Index(value = ["date"], unique = true)])
data class AbsenceRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val kind: String,
    val creditedMin: Int = 480,
)

@Dao
interface EntryDao {
    @Query("SELECT * FROM entries ORDER BY startUtc")
    fun observeAll(): Flow<List<ActivityEntry>>

    @Query("SELECT * FROM entries ORDER BY startUtc")
    suspend fun all(): List<ActivityEntry>

    @Query("SELECT * FROM entries ORDER BY startUtc DESC LIMIT 1")
    suspend fun latest(): ActivityEntry?

    @Query("SELECT * FROM entries WHERE id = :id")
    suspend fun byId(id: Long): ActivityEntry?

    @Query("SELECT COUNT(*) FROM entries WHERE startUtc = :start")
    suspend fun countAt(start: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: ActivityEntry): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(entries: List<ActivityEntry>)

    @Update
    suspend fun update(entry: ActivityEntry)

    @Query("DELETE FROM entries WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM entries")
    suspend fun deleteAll()
}

@Dao
interface SeedDao {
    @Query("SELECT * FROM seed WHERE id = 1")
    fun observe(): Flow<SeedRow?>

    @Query("SELECT * FROM seed WHERE id = 1")
    suspend fun get(): SeedRow?

    @Upsert
    suspend fun upsert(seed: SeedRow)

    @Query("DELETE FROM seed")
    suspend fun clear()
}

@Dao
interface AbsenceDao {
    @Query("SELECT * FROM absences ORDER BY date")
    fun observeAll(): Flow<List<AbsenceRow>>

    @Query("SELECT * FROM absences ORDER BY date")
    suspend fun all(): List<AbsenceRow>

    @Query("DELETE FROM absences WHERE date = :date")
    suspend fun deleteDate(date: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: AbsenceRow)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(rows: List<AbsenceRow>)

    @Query("DELETE FROM absences")
    suspend fun deleteAll()
}

/** Version 1 until the app has shipped; after that turn on exportSchema and write a Migration. */
@Database(entities = [ActivityEntry::class, SeedRow::class, AbsenceRow::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun entryDao(): EntryDao
    abstract fun seedDao(): SeedDao
    abstract fun absenceDao(): AbsenceDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "dutyclock.db",
                ).build().also { instance = it }
            }
    }
}
