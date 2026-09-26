package com.mohdshayan.dutyclock.di

import android.content.Context
import com.mohdshayan.dutyclock.data.db.AppDatabase
import com.mohdshayan.dutyclock.data.prefs.AppPrefs
import com.mohdshayan.dutyclock.data.repo.LogRepository

/**
 * Manual dependency container. Initialised in App.onCreate, and idempotent so a broadcast
 * receiver may call init() again with whatever context it has.
 */
object ServiceLocator {

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) {
            synchronized(this) {
                if (appContext == null) appContext = context.applicationContext
            }
        }
    }

    private fun ctx(): Context =
        appContext ?: error("ServiceLocator.init() must be called before use")

    val appPrefs: AppPrefs by lazy { AppPrefs(ctx()) }

    val database: AppDatabase by lazy { AppDatabase.get(ctx()) }

    val logRepository: LogRepository by lazy { LogRepository(ctx(), database, appPrefs) }
}
