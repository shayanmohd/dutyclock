package com.mohdshayan.dutyclock.alerts

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mohdshayan.dutyclock.MainActivity
import com.mohdshayan.dutyclock.R
import com.mohdshayan.dutyclock.core.engine.AlertPlan
import com.mohdshayan.dutyclock.core.engine.Evaluation
import com.mohdshayan.dutyclock.core.engine.Fmt
import com.mohdshayan.dutyclock.core.engine.RuleEngine
import com.mohdshayan.dutyclock.core.model.Mode
import com.mohdshayan.dutyclock.di.ServiceLocator
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object Channels {
    const val COUNTDOWN = "countdown"

    /** Four alert channels so the Sound and Vibrate switches work without rewriting a channel. */
    fun alertChannel(sound: Boolean, vibrate: Boolean) = when {
        sound && vibrate -> "limit_alerts"
        sound -> "limit_alerts_no_vibration"
        vibrate -> "limit_alerts_vibration_only"
        else -> "limit_alerts_silent"
    }

    fun create(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        for (sound in listOf(true, false)) for (vibrate in listOf(true, false)) {
            val name = when {
                sound && vibrate -> "Limit alerts"
                sound -> "Limit alerts, no vibration"
                vibrate -> "Limit alerts, vibration only"
                else -> "Limit alerts, silent"
            }
            val ch = NotificationChannel(alertChannel(sound, vibrate), name, NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Before a break is due and when a limit is reached"
                if (sound) setSound(alarmSound, attrs) else setSound(null, null)
                enableVibration(vibrate)
                if (vibrate) vibrationPattern = longArrayOf(0, 600, 300, 600, 300, 600)
            }
            nm.createNotificationChannel(ch)
        }
        nm.createNotificationChannel(
            NotificationChannel(COUNTDOWN, "Countdown", NotificationManager.IMPORTANCE_LOW).apply {
                description = "The next limit, counting down"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
    }
}

private fun canPost(context: Context): Boolean =
    (Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        NotificationManagerCompat.from(context).areNotificationsEnabled()

private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
    context, 0,
    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
)

object AlertScheduler {
    private const val SLOTS = 3
    private const val TEST_SLOT = 9
    const val EXTRA_TITLE = "title"
    const val EXTRA_TEXT = "text"
    const val EXTRA_AT = "at"

    /** Inexact alarms are only re-armed this close to the alert, so it lands within a few minutes. */
    private const val HOP_MS = 6 * 60_000L

    fun canScheduleExact(context: Context): Boolean {
        val am = context.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
    }

    private fun pending(context: Context, slot: Int, title: String?, text: String?, at: Long?, flags: Int): PendingIntent? {
        val i = Intent(context, AlertReceiver::class.java).setAction("com.mohdshayan.dutyclock.ALERT_$slot")
        if (title != null) i.putExtra(EXTRA_TITLE, title)
        if (text != null) i.putExtra(EXTRA_TEXT, text)
        if (at != null) i.putExtra(EXTRA_AT, at)
        return PendingIntent.getBroadcast(context, slot, i, flags or PendingIntent.FLAG_IMMUTABLE)
    }

    /**
     * Exact when allowed. Otherwise Android may deliver an inexact alarm up to three quarters of
     * its delay late (capped at an hour), so an alert far off is reached in hops: a silent wake
     * halfway there, which always lands before the alert, re-arms from closer in until the last
     * hop is at most [HOP_MS] away and at most a few minutes late.
     */
    private fun arm(context: Context, slot: Int, at: Long, title: String, text: String, now: Long = System.currentTimeMillis()) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = pending(context, slot, title, text, at, PendingIntent.FLAG_UPDATE_CURRENT)!!
        if (canScheduleExact(context)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            val trigger = if (at - now > HOP_MS) now + (at - now) / 2 else at
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
        }
    }

    /** True when an alarm fired as a hop, well before the alert it carries. */
    fun isHop(intent: Intent, now: Long = System.currentTimeMillis()): Boolean =
        intent.getLongExtra(EXTRA_AT, 0L) - now > 60_000L

    /** Recomputes every limit and re-arms at most three alarms and the countdown notification. */
    suspend fun reschedule(context: Context) {
        ServiceLocator.init(context)
        val am = context.getSystemService(AlarmManager::class.java)
        val (input, settings) = ServiceLocator.logRepository.snapshot()
        val now = System.currentTimeMillis()
        val ev = RuleEngine.evaluate(input.copy(zone = ZoneId.systemDefault()), now)
        val alerts = if (settings.onboardingDone) AlertPlan.plan(ev, settings.alertLeadMin, settings.alertBreakComplete, SLOTS) else emptyList()
        for (slot in 0 until SLOTS) {
            val a = alerts.getOrNull(slot)
            if (a == null) {
                pending(context, slot, null, null, null, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it); it.cancel() }
            } else {
                arm(context, slot, a.at, a.title, a.text)
            }
        }
        if (settings.onboardingDone) CountdownNotifier.update(context, ev) else CountdownNotifier.cancel(context)
    }

    /** Settings: "Send a test alert", five seconds from now through the same path as a real one. */
    fun test(context: Context) {
        arm(context, TEST_SLOT, System.currentTimeMillis() + 5_000, "Test alert", "This is how a limit alert sounds.")
    }
}

object CountdownNotifier {
    private const val ID = 1

    fun cancel(context: Context) = NotificationManagerCompat.from(context).cancel(ID)

    fun update(context: Context, ev: Evaluation) {
        if (!canPost(context)) return
        val zone = ZoneId.systemDefault()
        val (title, at) = when {
            ev.mode == Mode.REST && ev.breakCompleteAt != null ->
                (if (ev.breakCompleteIsSplit) "Split break complete in" else "Break complete in") to ev.breakCompleteAt
            else -> ev.counters.firstOrNull { it.limitAt != null && it.running && !it.met && !it.over }?.let { it.title to it.limitAt!! }
                ?: ev.counters.firstOrNull { it.met && it.limitAt != null }?.let { "${it.title} complete in" to it.limitAt!! }
                ?: (null to 0L)
        }
        if (title == null) {
            cancel(context); return
        }
        val n = NotificationCompat.Builder(context, Channels.COUNTDOWN)
            .setSmallIcon(R.drawable.ic_stat_dutyclock)
            .setContentTitle(title)
            .setContentText("At ${Fmt.clock(at, zone)}. Advisory, not a tachograph.")
            .setWhen(at)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openApp(context))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(ID, n)
        } catch (_: SecurityException) {
        }
    }
}

class AlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                ServiceLocator.init(context)
                val settings = ServiceLocator.appPrefs.current()
                val title = intent.getStringExtra(AlertScheduler.EXTRA_TITLE) ?: "Dutyclock"
                val text = intent.getStringExtra(AlertScheduler.EXTRA_TEXT) ?: ""
                if (canPost(context) && !AlertScheduler.isHop(intent)) {
                    val n = NotificationCompat.Builder(context, Channels.alertChannel(settings.alertSound, settings.alertVibrate))
                        .setSmallIcon(R.drawable.ic_stat_dutyclock)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setCategory(NotificationCompat.CATEGORY_ALARM)
                        .setAutoCancel(true)
                        .setTimeoutAfter(10 * 60_000L)
                        .setContentIntent(openApp(context))
                        .setDefaults(0)
                        .setVisibility(Notification.VISIBILITY_PUBLIC)
                        .build()
                    try {
                        NotificationManagerCompat.from(context).notify(100 + Math.floorMod(intent.action?.hashCode() ?: 0, 50), n)
                    } catch (_: SecurityException) {
                    }
                }
                AlertScheduler.reschedule(context)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Re-arms alerts after a restart, an app update, a clock or time zone change, or a change to exact alarm access. */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                AlertScheduler.reschedule(context)
            } finally {
                pending.finish()
            }
        }
    }
}
