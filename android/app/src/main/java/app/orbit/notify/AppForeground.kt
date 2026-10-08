package app.orbit.notify

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether Orbit is on screen right now, for code that runs outside any
 * screen (NOTIF-16: the notification after a call is posted only while
 * Orbit is not in the foreground; on screen, Home's stack says the same
 * thing).
 *
 * MainActivity reports its ON_START and ON_STOP here, the same events that
 * drive the privacy curtain. A process that WorkManager woke for the call-log
 * trigger has no activity, so it starts, and stays, not in the foreground.
 * App-scoped rather than read from AppViewModel because a worker has no
 * ViewModel; no new library (lifecycle-process) for one boolean.
 */
@Singleton
class AppForeground @Inject constructor() {

    @Volatile
    var isForeground: Boolean = false
        private set

    fun onStarted() {
        isForeground = true
    }

    fun onStopped() {
        isForeground = false
    }
}
