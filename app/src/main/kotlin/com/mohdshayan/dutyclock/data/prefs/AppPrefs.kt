package com.mohdshayan.dutyclock.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mohdshayan.dutyclock.core.model.DayChoices
import com.mohdshayan.dutyclock.core.model.RuleSet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_prefs")

/** Every setting, read together so a screen sees one consistent snapshot. */
data class Settings(
    val onboardingDone: Boolean = false,
    val disclaimerAckVersion: Int = 0,
    val ruleSet: RuleSet = RuleSet.EU,
    val choices: DayChoices = DayChoices(),
    val alertLeadMin: Int = 15,
    val alertSound: Boolean = true,
    val alertVibrate: Boolean = true,
    val alertBreakComplete: Boolean = true,
    val driverName: String = "",
    val vehicleReg: String = "",
    val themeMode: String = "system",
    val usageCountsOptIn: Boolean = false,
    val counts: Map<String, Int> = emptyMap(),
    val reviewPrompted: Boolean = false,
    val catchupDismissed: Boolean = false,
)

class AppPrefs(private val context: Context) {

    object Keys {
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val DISCLAIMER_ACK_VERSION = intPreferencesKey("disclaimer_ack_version")
        val RULE_SET_DEFAULT = stringPreferencesKey("rule_set_default")
        val TEN_HOUR_DAY = longPreferencesKey("ten_hour_day_date")
        val REDUCED_REST = longPreferencesKey("reduced_rest_date")
        val ALERT_LEAD_MIN = intPreferencesKey("alert_lead_min")
        val ALERT_SOUND = booleanPreferencesKey("alert_sound")
        val ALERT_VIBRATE = booleanPreferencesKey("alert_vibrate")
        val ALERT_BREAK_COMPLETE = booleanPreferencesKey("alert_break_complete")
        val DRIVER_NAME = stringPreferencesKey("driver_name")
        val VEHICLE_REG = stringPreferencesKey("vehicle_reg")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val USAGE_OPT_IN = booleanPreferencesKey("usage_counts_opt_in")
        val REVIEW_PROMPTED = booleanPreferencesKey("review_prompted")
        val CATCHUP_DISMISSED = booleanPreferencesKey("catchup_dismissed")
        const val COUNT_PREFIX = "count_"
    }

    /** The opt-in local counters. Never sent anywhere. */
    enum class Count(val key: String, val label: String) {
        MODE_TAPS("count_mode_taps", "Mode taps"),
        PLANNER_RUNS("count_planner_runs", "Planner runs"),
        EXPORTS("count_exports", "Exports"),
        COMPLETED_DAYS("completed_days", "Completed working days"),
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            onboardingDone = p[Keys.ONBOARDING_DONE] ?: false,
            disclaimerAckVersion = p[Keys.DISCLAIMER_ACK_VERSION] ?: 0,
            ruleSet = p[Keys.RULE_SET_DEFAULT]?.let { runCatching { RuleSet.valueOf(it) }.getOrNull() } ?: RuleSet.EU,
            choices = DayChoices(p[Keys.TEN_HOUR_DAY], p[Keys.REDUCED_REST]),
            alertLeadMin = p[Keys.ALERT_LEAD_MIN] ?: 15,
            alertSound = p[Keys.ALERT_SOUND] ?: true,
            alertVibrate = p[Keys.ALERT_VIBRATE] ?: true,
            alertBreakComplete = p[Keys.ALERT_BREAK_COMPLETE] ?: true,
            driverName = p[Keys.DRIVER_NAME] ?: "",
            vehicleReg = p[Keys.VEHICLE_REG] ?: "",
            themeMode = p[Keys.THEME_MODE] ?: "system",
            usageCountsOptIn = p[Keys.USAGE_OPT_IN] ?: false,
            counts = Count.entries.associate { it.key to (p[intPreferencesKey(it.key)] ?: 0) },
            reviewPrompted = p[Keys.REVIEW_PROMPTED] ?: false,
            catchupDismissed = p[Keys.CATCHUP_DISMISSED] ?: false,
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun <T> set(key: Preferences.Key<T>, value: T?) {
        context.dataStore.edit { if (value == null) it.remove(key) else it[key] = value }
    }

    suspend fun finishOnboarding(ruleSet: RuleSet) {
        context.dataStore.edit {
            it[Keys.RULE_SET_DEFAULT] = ruleSet.name
            it[Keys.DISCLAIMER_ACK_VERSION] = DISCLAIMER_VERSION
            it[Keys.ONBOARDING_DONE] = true
        }
    }

    /** Adds one to an opt-in counter; does nothing unless the driver turned counting on. */
    suspend fun bump(count: Count) {
        context.dataStore.edit {
            if (it[Keys.USAGE_OPT_IN] == true) {
                val k = intPreferencesKey(count.key)
                it[k] = (it[k] ?: 0) + 1
            }
        }
    }

    suspend fun clearCounts() {
        context.dataStore.edit { p -> Count.entries.forEach { p.remove(intPreferencesKey(it.key)) } }
    }

    /** Settings that travel in a backup, as strings. */
    suspend fun exportable(): Map<String, String> {
        val s = current()
        return mapOf(
            "rule_set_default" to s.ruleSet.name,
            "alert_lead_min" to s.alertLeadMin.toString(),
            "alert_sound" to s.alertSound.toString(),
            "alert_vibrate" to s.alertVibrate.toString(),
            "alert_break_complete" to s.alertBreakComplete.toString(),
            "driver_name" to s.driverName,
            "vehicle_reg" to s.vehicleReg,
            "theme_mode" to s.themeMode,
        )
    }

    suspend fun import(map: Map<String, String>) {
        context.dataStore.edit { p ->
            map["rule_set_default"]?.let { v -> if (RuleSet.entries.any { it.name == v }) p[Keys.RULE_SET_DEFAULT] = v }
            map["alert_lead_min"]?.toIntOrNull()?.let { if (it in 1..60) p[Keys.ALERT_LEAD_MIN] = it }
            map["alert_sound"]?.toBooleanStrictOrNull()?.let { p[Keys.ALERT_SOUND] = it }
            map["alert_vibrate"]?.toBooleanStrictOrNull()?.let { p[Keys.ALERT_VIBRATE] = it }
            map["alert_break_complete"]?.toBooleanStrictOrNull()?.let { p[Keys.ALERT_BREAK_COMPLETE] = it }
            map["driver_name"]?.let { p[Keys.DRIVER_NAME] = it.take(60) }
            map["vehicle_reg"]?.let { p[Keys.VEHICLE_REG] = it.take(20) }
            map["theme_mode"]?.takeIf { it in setOf("system", "light", "dark") }?.let { p[Keys.THEME_MODE] = it }
        }
    }

    companion object {
        const val DISCLAIMER_VERSION = 1
    }
}
