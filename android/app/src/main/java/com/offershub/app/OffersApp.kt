package com.offershub.app

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.offershub.app.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class OffersApp : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        DealNotifier.createChannel(this)
        val settings = AppSettings.get(this)
        if (FirebaseApp.getApps(this).isNotEmpty()) {
            // Instant alerts: the listener sends every new deal to the "deals" topic.
            scope.launch {
                settings.notifications.collect { enabled ->
                    val messaging = FirebaseMessaging.getInstance()
                    if (enabled) messaging.subscribeToTopic(TOPIC) else messaging.unsubscribeFromTopic(TOPIC)
                }
            }
        } else {
            NewDealsWorker.schedule(this)
        }
    }

    companion object {
        const val TOPIC = "deals"
    }
}

/** Whether an activity is on screen; new-deal alerts are skipped while the user is looking. */
object AppVisibility {
    @Volatile var foreground = false
}
