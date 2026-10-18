package com.mohdshayan.dutyclock.core.engine

/** What to ask on the first mode tap, so alerts can reach the driver and sound on time. */
object AlertAccess {
    enum class Step { ASK_NOTIFICATIONS, EXPLAIN_EXACT, NONE }

    /** Notifications first on Android 13 and later, then exact alarms on Android 12 and later. */
    fun first(sdk: Int, notificationsGranted: Boolean, exactAllowed: Boolean): Step = when {
        sdk >= 33 && !notificationsGranted -> Step.ASK_NOTIFICATIONS
        sdk >= 31 && !exactAllowed -> Step.EXPLAIN_EXACT
        else -> Step.NONE
    }

    /** After the notification prompt: a refusal ends the asking, since no alert could post anyway. */
    fun afterPrompt(sdk: Int, granted: Boolean, exactAllowed: Boolean): Step =
        if (sdk >= 31 && granted && !exactAllowed) Step.EXPLAIN_EXACT else Step.NONE
}
