package com.mohdshayan.dutyclock

import android.app.Application
import com.mohdshayan.dutyclock.alerts.AlertScheduler
import com.mohdshayan.dutyclock.alerts.Channels
import com.mohdshayan.dutyclock.di.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Registered in the manifest as android:name=".App": wires the service locator, creates the
 * notification channels and re-arms the alerts whenever the process starts.
 */
class App : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        Channels.create(this)
        scope.launch { runCatching { AlertScheduler.reschedule(this@App) } }
    }
}
