package com.mohdshayan.dutyclock.data

import com.mohdshayan.dutyclock.data.db.SchemaSql
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Room 1 to 2 on the JVM: the shipped v1 schema, real rows, the migration statements, then the
 * migrated seed table compared with the one Room expects (2.json). The on-device upgrade test is
 * the final check, since Room validates the schema when it opens the file.
 */
class MigrationTest {
    private val dir = File("schemas/com.mohdshayan.dutyclock.data.db.AppDatabase")

    private fun schema(version: Int): JsonObject =
        Json.parseToJsonElement(File(dir, "$version.json").readText()).jsonObject["database"]!!.jsonObject

    private fun statements(db: JsonObject): List<String> {
        val out = mutableListOf<String>()
        for (e in db["entities"]!!.jsonArray) {
            val o = e.jsonObject
            val name = o["tableName"]!!.jsonPrimitive.content
            out += o["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", name)
            o["indices"]?.jsonArray?.forEach { i -> out += i.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", name) }
        }
        db["setupQueries"]!!.jsonArray.forEach { out += it.jsonPrimitive.content }
        return out
    }

    private fun tableInfo(c: Connection, table: String): List<List<Any?>> = c.createStatement().use { st ->
        st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
            buildList { while (rs.next()) add(listOf(rs.getString("name"), rs.getString("type"), rs.getInt("notnull"), rs.getString("dflt_value"), rs.getInt("pk"))) }
        }
    }

    @Test
    fun `the v1 statements are the shipped schema`() {
        val v1 = schema(1)
        assertEquals("16ef86b29dde763c35f4acc46dac221b", v1["identityHash"]!!.jsonPrimitive.content)
        assertEquals(statements(v1), SchemaSql.V1_CREATE)
    }

    @Test
    fun `version 1 database gains the three seed columns with nothing lost`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            c.createStatement().use { st ->
                SchemaSql.V1_CREATE.forEach { st.execute(it) }
                st.execute(
                    "INSERT INTO seed VALUES (1, 1790000000000, 100, 1789990000000, 200, 1500, 2400, 1600, 30000, 1, 2, 1789700000000, 2700, 60, 1791000000000)",
                )
                st.execute("INSERT INTO entries (startUtc, mode, ruleSet, source, note, createdAt, editedAt) VALUES (1789990000000, 'WORK', 'EU', 'LIVE', NULL, 1789990000000, NULL)")
                st.execute("INSERT INTO entries (startUtc, mode, ruleSet, source, note, createdAt, editedAt) VALUES (1789990600000, 'DRIVE', 'EU', 'LIVE', NULL, 1789990600000, NULL)")
                st.execute("INSERT INTO entries (startUtc, mode, ruleSet, source, note, createdAt, editedAt) VALUES (1789995000000, 'REST', 'GB_GOODS', 'EDIT', 'n', 1789995000000, 1789996000000)")
                st.execute("INSERT INTO absences (date, kind, creditedMin) VALUES ('2026-10-01', 'ANNUAL_LEAVE', 480)")
                SchemaSql.V1_TO_V2.forEach { st.execute(it) }
                st.executeQuery("SELECT * FROM seed WHERE id = 1").use { rs ->
                    rs.next()
                    val expected = listOf(1L, 1790000000000L, 100L, 1789990000000L, 200L, 1500L, 2400L, 1600L, 30000L, 1L, 2L, 1789700000000L, 2700L, 60L, 1791000000000L)
                    val cols = listOf(
                        "id", "seededAtUtc", "drivingSinceBreakMin", "dayStartUtc", "drivingTodayMin", "thisWeekDrivingMin", "lastWeekDrivingMin",
                        "thisWeekWorkMin", "rtdPeriodWorkMin", "tenHourDaysUsed", "reducedDailyRestsUsed", "lastWeeklyRestEndUtc", "lastWeeklyRestMin",
                        "compensationOwedMin", "compensationDueUtc",
                    )
                    assertEquals(expected, cols.map { rs.getLong(it) })
                    assertEquals(0, rs.getInt("part15Taken"))
                    rs.getLong("readAtUtc"); assertEquals(true, rs.wasNull())
                    assertEquals(0, rs.getInt("otherWorkSinceRestMin")); assertEquals(false, rs.wasNull())
                }
                st.executeQuery("SELECT COUNT(*) FROM entries").use { rs -> rs.next(); assertEquals(3, rs.getInt(1)) }
                st.executeQuery("SELECT COUNT(*) FROM absences").use { rs -> rs.next(); assertEquals(1, rs.getInt(1)) }
                st.executeQuery("SELECT note, editedAt, ruleSet FROM entries WHERE mode = 'REST'").use { rs ->
                    rs.next(); assertEquals("n", rs.getString(1)); assertEquals(1789996000000L, rs.getLong(2)); assertEquals("GB_GOODS", rs.getString(3))
                }
            }
            val fresh = DriverManager.getConnection("jdbc:sqlite::memory:")
            fresh.use { f ->
                val seedSql = schema(2)["entities"]!!.jsonArray.map { it.jsonObject }.first { it["tableName"]!!.jsonPrimitive.content == "seed" }
                f.createStatement().use { it.execute(seedSql["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", "seed")) }
                assertEquals(tableInfo(f, "seed"), tableInfo(c, "seed"))
            }
        }
    }
}
